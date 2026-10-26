#include "aq_pulse.h"

#include <pthread.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>

#define COBJMACROS
#include <mmdeviceapi.h>
#include <audioclient.h>
#include <audiopolicy.h>
#include <functiondiscoverykeys_devpkey.h>
#include <avrt.h>
#include <ksmedia.h>

#include "mse_ffmpeg.h"

#define AQW_MAX_BUFS 16
#define AQW_AHEAD_MS_SPEAKER 40
#define AQW_AHEAD_MS_BT 100

/* MinGW's uuid import lib does not ship these. */
static const GUID kIID_IMMDeviceEnumerator = {0xa95664d2, 0x9614, 0x4f35, {0xa7, 0x46, 0xde, 0x8d, 0xb6, 0x36, 0x17, 0xe6}};
static const GUID kCLSID_MMDeviceEnumerator = {0xbcde0395, 0xe52f, 0x467c, {0x8e, 0x3d, 0xc4, 0x57, 0x92, 0x91, 0x69, 0x2e}};
static const GUID kIID_IAudioClient = {0x1cb9ad4c, 0xdbfa, 0x4c32, {0xb1, 0x78, 0xc2, 0xf5, 0x68, 0xa7, 0x03, 0xb2}};
static const GUID kIID_IAudioRenderClient = {0xf294acfc, 0x3146, 0x4483, {0xa7, 0xbf, 0xad, 0xdc, 0xa7, 0xc2, 0x60, 0xe2}};
static const GUID kIID_IAudioSessionControl2 = {0xbfb7ff88, 0x7239, 0x4fc9, {0x8f, 0xa2, 0x07, 0xc9, 0x50, 0xbe, 0x9c, 0x6d}};
static const GUID kIID_IAudioSessionManager2 = {0x77aa99a0, 0x1bd6, 0x484f, {0x8b, 0xc7, 0x2c, 0x65, 0x4c, 0x9a, 0x9b, 0x6f}};
static const GUID kIID_IAudioVolumeDuckNotification = {0xc3b284d4, 0x6d39, 0x4359, {0xb3, 0xcf, 0xb5, 0x6d, 0xdb, 0x3b, 0xb3, 0x9c}};
static const GUID kPCM = {0x00000001, 0x0000, 0x0010, {0x80, 0x00, 0x00, 0xaa, 0x00, 0x38, 0x9b, 0x71}};

struct OpaqueAudioQueue {
    pthread_mutex_t mu;
    pthread_t th;
    HANDLE kick;
    HANDLE ready;
    AudioQueueOutputCallback cb;
    void *user;
    int rate;
    int ch;
    int frame_bytes;
    AudioQueueBufferRef bufs[AQW_MAX_BUFS];
    int nbufs;
    AudioQueueBufferRef fifo[AQW_MAX_BUFS];
    int fifo_r;
    int fifo_n;
    AudioQueueBufferRef cur;
    int cur_off;
    int running;
    int quit;
    int flush_req;
    int open_hr;
    float vol;
    float vol_target;
    float ramp_s;
    int ramp_left;
    volatile float duck;
    IMMDevice *dev;
    IAudioClient *client;
    IAudioRenderClient *render;
    IAudioSessionManager2 *sessions;
    IAudioVolumeDuckNotification *duck_obj;
    LPWSTR session_id;
    HANDLE wasapi_event;
    UINT32 buf_frames;
    int started;
    int bluetooth;
};

typedef struct DuckNote {
    IAudioVolumeDuckNotificationVtbl *lpVtbl;
    LONG refs;
    AudioQueueRef q;
} DuckNote;

static HRESULT STDMETHODCALLTYPE duck_qi(IAudioVolumeDuckNotification *This, REFIID riid, void **out)
{
    if (!out)
        return E_POINTER;
    if (IsEqualIID(riid, &IID_IUnknown) || IsEqualIID(riid, &kIID_IAudioVolumeDuckNotification)) {
        *out = This;
        This->lpVtbl->AddRef(This);
        return S_OK;
    }
    *out = NULL;
    return E_NOINTERFACE;
}

static ULONG STDMETHODCALLTYPE duck_add(IAudioVolumeDuckNotification *This)
{
    return InterlockedIncrement(&((DuckNote *)This)->refs);
}

static ULONG STDMETHODCALLTYPE duck_rel(IAudioVolumeDuckNotification *This)
{
    ULONG n = InterlockedDecrement(&((DuckNote *)This)->refs);
    if (n == 0)
        free(This);
    return n;
}

static HRESULT STDMETHODCALLTYPE duck_on(IAudioVolumeDuckNotification *This, LPCWSTR sid, UINT32 count)
{
    DuckNote *d = (DuckNote *)This;
    (void)sid;
    if (d->q)
        d->q->duck = count > 1 ? 0.12f : 0.22f;
    return S_OK;
}

static HRESULT STDMETHODCALLTYPE duck_off(IAudioVolumeDuckNotification *This, LPCWSTR sid)
{
    DuckNote *d = (DuckNote *)This;
    (void)sid;
    if (d->q)
        d->q->duck = 1.f;
    return S_OK;
}

static IAudioVolumeDuckNotificationVtbl g_duck_vt = {
    duck_qi, duck_add, duck_rel, duck_on, duck_off,
};

static void wake(AudioQueueRef q)
{
    if (q && q->kick)
        SetEvent(q->kick);
}

static void sleep_ms(double s)
{
    if (s > 0)
        Sleep((DWORD)(s * 1000.0));
}

static int device_bluetooth(IMMDevice *dev)
{
    IPropertyStore *ps = NULL;
    PROPVARIANT v;
    int bt = 0;
    if (!dev || FAILED(IMMDevice_OpenPropertyStore(dev, STGM_READ, &ps)))
        return 0;
    PropVariantInit(&v);
    if (SUCCEEDED(IPropertyStore_GetValue(ps, &PKEY_Device_EnumeratorName, &v)) &&
        v.vt == VT_LPWSTR && v.pwszVal &&
        (wcsstr(v.pwszVal, L"BTHENUM") || wcsstr(v.pwszVal, L"BTHHFENUM")))
        bt = 1;
    PropVariantClear(&v);
    IPropertyStore_Release(ps);
    return bt;
}

/* Higher is a music endpoint. Mono 8/16 kHz is the headset hands-free profile. */
static int endpoint_score(IMMDevice *dev, int *bluetooth)
{
    IAudioClient *ac = NULL;
    WAVEFORMATEX *mix = NULL;
    int score = 0;
    *bluetooth = device_bluetooth(dev);
    if (FAILED(IMMDevice_Activate(dev, &kIID_IAudioClient, CLSCTX_ALL, NULL, (void **)&ac)))
        return -1000;
    if (FAILED(IAudioClient_GetMixFormat(ac, &mix)) || !mix) {
        IAudioClient_Release(ac);
        return -1000;
    }
    if (mix->nChannels >= 2)
        score += 100;
    if (mix->nSamplesPerSec >= 44100)
        score += 80;
    if (mix->nSamplesPerSec <= 16000 || mix->nChannels < 2)
        score -= 400;
    CoTaskMemFree(mix);
    IAudioClient_Release(ac);
    return score;
}

static IMMDevice *pick_endpoint(IMMDeviceEnumerator *en)
{
    IMMDevice *def = NULL;
    IMMDeviceCollection *col = NULL;
    IMMDevice *best = NULL;
    UINT n = 0, i;
    int def_bt = 0, def_score, best_score = -100000;
    if (FAILED(IMMDeviceEnumerator_GetDefaultAudioEndpoint(en, eRender, eMultimedia, &def)) || !def)
        return NULL;
    def_score = endpoint_score(def, &def_bt);
    /* Multimedia default is already the music device (A2DP, not the call profile). */
    if (def_score >= 100)
        return def;
    if (FAILED(IMMDeviceEnumerator_EnumAudioEndpoints(en, eRender, DEVICE_STATE_ACTIVE, &col)) || !col) {
        mse_critf(MSE_LOG_WARN, "aq-wasapi: default endpoint is hands-free (%d); no other device", def_score);
        return def;
    }
    IMMDeviceCollection_GetCount(col, &n);
    for (i = 0; i < n; i++) {
        IMMDevice *d = NULL;
        int bt = 0, score;
        if (FAILED(IMMDeviceCollection_Item(col, i, &d)) || !d)
            continue;
        score = endpoint_score(d, &bt);
        if (score > best_score) {
            if (best)
                IMMDevice_Release(best);
            best = d;
            best_score = score;
        } else {
            IMMDevice_Release(d);
        }
    }
    IMMDeviceCollection_Release(col);
    if (best && best_score > def_score) {
        mse_critf(MSE_LOG_INFO, "aq-wasapi: skipped hands-free default, using stereo endpoint");
        IMMDevice_Release(def);
        return best;
    }
    if (best)
        IMMDevice_Release(best);
    if (def_score < 0)
        mse_critf(MSE_LOG_WARN, "aq-wasapi: only a low-rate endpoint is up (headset call profile). Music stays thin until Windows is on the stereo device");
    return def;
}

static void release_client(AudioQueueRef q)
{
    if (q->sessions && q->duck_obj) {
        IAudioSessionManager2_UnregisterDuckNotification(q->sessions, q->duck_obj);
    }
    if (q->duck_obj) {
        q->duck_obj->lpVtbl->Release(q->duck_obj);
        q->duck_obj = NULL;
    }
    if (q->session_id) {
        CoTaskMemFree(q->session_id);
        q->session_id = NULL;
    }
    if (q->sessions) {
        IAudioSessionManager2_Release(q->sessions);
        q->sessions = NULL;
    }
    if (q->render) {
        IAudioRenderClient_Release(q->render);
        q->render = NULL;
    }
    if (q->client) {
        if (q->started)
            IAudioClient_Stop(q->client);
        q->started = 0;
        IAudioClient_Release(q->client);
        q->client = NULL;
    }
    if (q->dev) {
        IMMDevice_Release(q->dev);
        q->dev = NULL;
    }
    if (q->wasapi_event) {
        CloseHandle(q->wasapi_event);
        q->wasapi_event = NULL;
    }
}

static int open_client(AudioQueueRef q)
{
    IMMDeviceEnumerator *en = NULL;
    WAVEFORMATEXTENSIBLE fmt;
    REFERENCE_TIME dur;
    HRESULT hr;
    DWORD flags;
    IAudioSessionControl2 *ctl = NULL;
    DuckNote *duck;
    hr = CoCreateInstance(&kCLSID_MMDeviceEnumerator, NULL, CLSCTX_ALL, &kIID_IMMDeviceEnumerator, (void **)&en);
    if (FAILED(hr))
        return -1;
    q->dev = pick_endpoint(en);
    IMMDeviceEnumerator_Release(en);
    if (!q->dev)
        return -1;
    q->bluetooth = device_bluetooth(q->dev);
    hr = IMMDevice_Activate(q->dev, &kIID_IAudioClient, CLSCTX_ALL, NULL, (void **)&q->client);
    if (FAILED(hr) || !q->client)
        return -1;
    memset(&fmt, 0, sizeof(fmt));
    fmt.Format.wFormatTag = WAVE_FORMAT_EXTENSIBLE;
    fmt.Format.nChannels = (WORD)q->ch;
    fmt.Format.nSamplesPerSec = (DWORD)q->rate;
    fmt.Format.wBitsPerSample = 16;
    fmt.Format.nBlockAlign = (WORD)q->frame_bytes;
    fmt.Format.nAvgBytesPerSec = fmt.Format.nSamplesPerSec * fmt.Format.nBlockAlign;
    fmt.Format.cbSize = (WORD)(sizeof(fmt) - sizeof(WAVEFORMATEX));
    fmt.Samples.wValidBitsPerSample = 16;
    fmt.dwChannelMask = q->ch == 1 ? SPEAKER_FRONT_CENTER : (SPEAKER_FRONT_LEFT | SPEAKER_FRONT_RIGHT);
    fmt.SubFormat = kPCM;
    /* One resample, inside WASAPI, at the engine's good SRC. Not waveOut's mapper. */
    flags = AUDCLNT_STREAMFLAGS_EVENTCALLBACK | AUDCLNT_STREAMFLAGS_AUTOCONVERTPCM |
            AUDCLNT_STREAMFLAGS_SRC_DEFAULT_QUALITY | AUDCLNT_STREAMFLAGS_NOPERSIST;
    dur = (REFERENCE_TIME)(q->bluetooth ? AQW_AHEAD_MS_BT : AQW_AHEAD_MS_SPEAKER) * 10000;
    hr = IAudioClient_Initialize(q->client, AUDCLNT_SHAREMODE_SHARED, flags, dur, 0, &fmt.Format, NULL);
    if (hr == AUDCLNT_E_BUFFER_SIZE_NOT_ALIGNED) {
        UINT32 frames = 0;
        IAudioClient_GetBufferSize(q->client, &frames);
        IAudioClient_Release(q->client);
        q->client = NULL;
        hr = IMMDevice_Activate(q->dev, &kIID_IAudioClient, CLSCTX_ALL, NULL, (void **)&q->client);
        if (FAILED(hr))
            return -1;
        dur = (REFERENCE_TIME)((10000000.0 * (double)frames / (double)q->rate) + 0.5);
        hr = IAudioClient_Initialize(q->client, AUDCLNT_SHAREMODE_SHARED, flags, dur, 0, &fmt.Format, NULL);
    }
    if (FAILED(hr)) {
        mse_critf(MSE_LOG_WARN, "aq-wasapi: Initialize failed hr=0x%08lx rate=%d ch=%d",
                  (unsigned long)hr, q->rate, q->ch);
        return -1;
    }
    q->wasapi_event = CreateEventW(NULL, FALSE, FALSE, NULL);
    if (!q->wasapi_event || FAILED(IAudioClient_SetEventHandle(q->client, q->wasapi_event)))
        return -1;
    if (FAILED(IAudioClient_GetBufferSize(q->client, &q->buf_frames)) || q->buf_frames == 0)
        return -1;
    if (FAILED(IAudioClient_GetService(q->client, &kIID_IAudioRenderClient, (void **)&q->render)))
        return -1;
    if (SUCCEEDED(IAudioClient_GetService(q->client, &kIID_IAudioSessionControl2, (void **)&ctl)) && ctl) {
        IAudioSessionControl2_GetSessionInstanceIdentifier(ctl, &q->session_id);
        IAudioSessionControl2_Release(ctl);
    }
    if (q->session_id &&
        SUCCEEDED(IMMDevice_Activate(q->dev, &kIID_IAudioSessionManager2, CLSCTX_ALL, NULL, (void **)&q->sessions)) &&
        q->sessions) {
        duck = (DuckNote *)calloc(1, sizeof(*duck));
        if (duck) {
            duck->lpVtbl = &g_duck_vt;
            duck->refs = 1;
            duck->q = q;
            q->duck_obj = (IAudioVolumeDuckNotification *)duck;
            if (FAILED(IAudioSessionManager2_RegisterDuckNotification(q->sessions, q->session_id, q->duck_obj))) {
                q->duck_obj->lpVtbl->Release(q->duck_obj);
                q->duck_obj = NULL;
            }
        }
    }
    mse_critf(MSE_LOG_INFO, "aq-wasapi: shared %d Hz x%d buffer=%u frames bt=%d",
              q->rate, q->ch, q->buf_frames, q->bluetooth);
    return 0;
}

/* q->mu held on entry for the bookkeeping; released around callbacks. */
static void return_buf(AudioQueueRef q, AudioQueueBufferRef buf)
{
    pthread_mutex_unlock(&q->mu);
    q->cb(q->user, q, buf);
    pthread_mutex_lock(&q->mu);
}

static void gain_copy(AudioQueueRef q, int16_t *dst, const int16_t *src, int frames)
{
    int i, c;
    float duck = q->duck > 0.f ? q->duck : 1.f;
    for (i = 0; i < frames; i++) {
        float g;
        if (q->ramp_left > 0) {
            q->vol += (q->vol_target - q->vol) / (float)q->ramp_left;
            q->ramp_left--;
        }
        g = q->vol * duck;
        for (c = 0; c < q->ch; c++) {
            int iv = (int)((float)src[i * q->ch + c] * g);
            if (iv > 32767)
                iv = 32767;
            if (iv < -32768)
                iv = -32768;
            dst[i * q->ch + c] = (int16_t)iv;
        }
    }
}

static void pump(AudioQueueRef q)
{
    UINT32 pad = 0, want, filled;
    BYTE *data = NULL;
    int16_t *dst;
    if (!q->client || !q->render || !q->running)
        return;
    if (!q->started) {
        if (FAILED(IAudioClient_Start(q->client)))
            return;
        q->started = 1;
    }
    if (FAILED(IAudioClient_GetCurrentPadding(q->client, &pad)))
        return;
    if (pad >= q->buf_frames)
        return;
    want = q->buf_frames - pad;
    if (FAILED(IAudioRenderClient_GetBuffer(q->render, want, &data)) || !data)
        return;
    dst = (int16_t *)data;
    filled = 0;
    pthread_mutex_lock(&q->mu);
    while (filled < want) {
        const int16_t *src;
        int have, take;
        if (!q->cur) {
            if (q->fifo_n <= 0)
                break;
            q->cur = q->fifo[q->fifo_r];
            q->fifo_r = (q->fifo_r + 1) % AQW_MAX_BUFS;
            q->fifo_n--;
            q->cur_off = 0;
        }
        have = ((int)q->cur->mAudioDataByteSize - q->cur_off) / q->frame_bytes;
        if (have <= 0) {
            AudioQueueBufferRef done = q->cur;
            q->cur = NULL;
            return_buf(q, done);
            continue;
        }
        take = have;
        if ((UINT32)take > want - filled)
            take = (int)(want - filled);
        src = (const int16_t *)((const char *)q->cur->mAudioData + q->cur_off);
        gain_copy(q, dst + filled * q->ch, src, take);
        q->cur_off += take * q->frame_bytes;
        filled += (UINT32)take;
        if (q->cur_off >= (int)q->cur->mAudioDataByteSize) {
            AudioQueueBufferRef done = q->cur;
            q->cur = NULL;
            return_buf(q, done);
        }
    }
    pthread_mutex_unlock(&q->mu);
    if (filled < want)
        memset(dst + filled * q->ch, 0, (size_t)(want - filled) * (size_t)q->frame_bytes);
    IAudioRenderClient_ReleaseBuffer(q->render, want, 0);
}

static void do_flush(AudioQueueRef q)
{
    AudioQueueBufferRef dropped[AQW_MAX_BUFS];
    int n = 0, i;
    pthread_mutex_lock(&q->mu);
    if (q->cur) {
        memset(q->cur->mAudioData, 0, q->cur->mAudioDataBytesCapacity);
        dropped[n++] = q->cur;
        q->cur = NULL;
        q->cur_off = 0;
    }
    while (q->fifo_n > 0 && n < AQW_MAX_BUFS) {
        AudioQueueBufferRef buf = q->fifo[q->fifo_r];
        q->fifo_r = (q->fifo_r + 1) % AQW_MAX_BUFS;
        q->fifo_n--;
        memset(buf->mAudioData, 0, buf->mAudioDataBytesCapacity);
        dropped[n++] = buf;
    }
    q->flush_req = 0;
    pthread_mutex_unlock(&q->mu);
    if (q->started && q->client) {
        IAudioClient_Stop(q->client);
        IAudioClient_Reset(q->client);
        q->started = 0;
    }
    for (i = 0; i < n; i++)
        q->cb(q->user, q, dropped[i]);
}

static void *aqw_loop(void *arg)
{
    AudioQueueRef q = (AudioQueueRef)arg;
    DWORD task = 0;
    HANDLE mm = NULL;
    HRESULT co = CoInitializeEx(NULL, COINIT_MULTITHREADED);
    mm = AvSetMmThreadCharacteristicsW(L"Audio", &task);
    q->duck = 1.f;
    if (open_client(q) != 0) {
        q->open_hr = -1;
        release_client(q);
    }
    SetEvent(q->ready);
    if (q->open_hr != 0)
        goto out;
    for (;;) {
        HANDLE waits[2];
        DWORD nw = 0, wr;
        int quit, flush, running;
        waits[nw++] = q->kick;
        if (q->wasapi_event)
            waits[nw++] = q->wasapi_event;
        pthread_mutex_lock(&q->mu);
        quit = q->quit;
        flush = q->flush_req;
        running = q->running;
        pthread_mutex_unlock(&q->mu);
        if (quit)
            break;
        if (flush)
            do_flush(q);
        if (!running && q->started && q->client) {
            IAudioClient_Stop(q->client);
            q->started = 0;
        }
        if (running)
            pump(q);
        pthread_mutex_lock(&q->mu);
        quit = q->quit;
        running = q->running;
        pthread_mutex_unlock(&q->mu);
        if (quit)
            break;
        wr = WaitForMultipleObjects(nw, waits, FALSE, running ? 30 : INFINITE);
        (void)wr;
    }
    if (q->started && q->client)
        IAudioClient_Stop(q->client);
    release_client(q);
out:
    if (mm)
        AvRevertMmThreadCharacteristics(mm);
    if (SUCCEEDED(co))
        CoUninitialize();
    return NULL;
}

OSStatus AudioQueueNewOutput(const AudioStreamBasicDescription *fmt, AudioQueueOutputCallback cb, void *user,
                             void *run_loop, const void *run_loop_mode, UInt32 flags, AudioQueueRef *out)
{
    AudioQueueRef q;
    DWORD ready;
    (void)run_loop;
    (void)run_loop_mode;
    (void)flags;
    *out = NULL;
    if (!fmt || !cb || fmt->mBitsPerChannel != 16 || fmt->mChannelsPerFrame < 1 ||
        fmt->mChannelsPerFrame > 2 || fmt->mSampleRate <= 0)
        return -50;
    q = (AudioQueueRef)calloc(1, sizeof(*q));
    if (!q)
        return -108;
    q->cb = cb;
    q->user = user;
    q->rate = (int)fmt->mSampleRate;
    q->ch = (int)fmt->mChannelsPerFrame;
    q->frame_bytes = q->ch * 2;
    q->vol = q->vol_target = 1.f;
    q->duck = 1.f;
    q->kick = CreateEventA(NULL, FALSE, FALSE, NULL);
    q->ready = CreateEventA(NULL, TRUE, FALSE, NULL);
    if (!q->kick || !q->ready) {
        if (q->kick)
            CloseHandle(q->kick);
        if (q->ready)
            CloseHandle(q->ready);
        free(q);
        return -1;
    }
    pthread_mutex_init(&q->mu, NULL);
    if (pthread_create(&q->th, NULL, aqw_loop, q) != 0) {
        CloseHandle(q->kick);
        CloseHandle(q->ready);
        pthread_mutex_destroy(&q->mu);
        free(q);
        return -1;
    }
    ready = WaitForSingleObject(q->ready, 4000);
    if (ready != WAIT_OBJECT_0 || q->open_hr != 0) {
        pthread_mutex_lock(&q->mu);
        q->quit = 1;
        pthread_mutex_unlock(&q->mu);
        wake(q);
        pthread_join(q->th, NULL);
        CloseHandle(q->kick);
        CloseHandle(q->ready);
        pthread_mutex_destroy(&q->mu);
        free(q);
        return -1;
    }
    *out = q;
    return 0;
}

OSStatus AudioQueueAllocateBuffer(AudioQueueRef q, UInt32 bytes, AudioQueueBufferRef *out)
{
    AudioQueueBufferRef buf;
    *out = NULL;
    pthread_mutex_lock(&q->mu);
    if (q->nbufs >= AQW_MAX_BUFS) {
        pthread_mutex_unlock(&q->mu);
        return -1;
    }
    buf = (AudioQueueBufferRef)calloc(1, sizeof(*buf) + bytes);
    if (!buf) {
        pthread_mutex_unlock(&q->mu);
        return -108;
    }
    buf->mAudioData = buf + 1;
    buf->mAudioDataBytesCapacity = bytes;
    q->bufs[q->nbufs++] = buf;
    pthread_mutex_unlock(&q->mu);
    *out = buf;
    return 0;
}

OSStatus AudioQueueEnqueueBuffer(AudioQueueRef q, AudioQueueBufferRef buf, UInt32 n_packet_descs,
                                 const void *packet_descs)
{
    (void)n_packet_descs;
    (void)packet_descs;
    pthread_mutex_lock(&q->mu);
    if (q->fifo_n >= AQW_MAX_BUFS) {
        pthread_mutex_unlock(&q->mu);
        return -1;
    }
    q->fifo[(q->fifo_r + q->fifo_n) % AQW_MAX_BUFS] = buf;
    q->fifo_n++;
    pthread_mutex_unlock(&q->mu);
    wake(q);
    return 0;
}

OSStatus AudioQueueStart(AudioQueueRef q, const void *start_time)
{
    (void)start_time;
    pthread_mutex_lock(&q->mu);
    q->running = 1;
    pthread_mutex_unlock(&q->mu);
    wake(q);
    return 0;
}

OSStatus AudioQueuePause(AudioQueueRef q)
{
    pthread_mutex_lock(&q->mu);
    q->running = 0;
    pthread_mutex_unlock(&q->mu);
    wake(q);
    return 0;
}

OSStatus AudioQueueFlush(AudioQueueRef q)
{
    pthread_mutex_lock(&q->mu);
    q->flush_req = 1;
    pthread_mutex_unlock(&q->mu);
    wake(q);
    return 0;
}

OSStatus AudioQueueStop(AudioQueueRef q, bool immediate)
{
    if (immediate)
        AudioQueueFlush(q);
    return AudioQueuePause(q);
}

OSStatus AudioQueueDispose(AudioQueueRef q, bool immediate)
{
    int i;
    (void)immediate;
    if (!q)
        return 0;
    pthread_mutex_lock(&q->mu);
    q->quit = 1;
    q->running = 0;
    pthread_mutex_unlock(&q->mu);
    wake(q);
    pthread_join(q->th, NULL);
    if (q->kick)
        CloseHandle(q->kick);
    if (q->ready)
        CloseHandle(q->ready);
    for (i = 0; i < q->nbufs; i++)
        free(q->bufs[i]);
    pthread_mutex_destroy(&q->mu);
    free(q);
    return 0;
}

OSStatus AudioQueueSetParameter(AudioQueueRef q, AudioQueueParameterID id, AudioQueueParameterValue v)
{
    pthread_mutex_lock(&q->mu);
    if (id == kAudioQueueParam_VolumeRampTime) {
        q->ramp_s = v > 0 ? v : 0;
    } else if (id == kAudioQueueParam_Volume) {
        q->vol_target = v < 0 ? 0 : (v > 1 ? 1 : v);
        q->ramp_left = (int)(q->ramp_s * (float)q->rate);
        if (q->ramp_left <= 0)
            q->vol = q->vol_target;
    }
    pthread_mutex_unlock(&q->mu);
    return 0;
}

dispatch_time_t dispatch_time(dispatch_time_t when, int64_t delta_ns)
{
    return when + (dispatch_time_t)(delta_ns > 0 ? delta_ns : 0);
}

dispatch_queue_t dispatch_get_global_queue(long identifier, unsigned long flags)
{
    (void)identifier;
    (void)flags;
    return NULL;
}

typedef struct {
    dispatch_time_t delay_ns;
    void *ctx;
    dispatch_function_t fn;
} AfterJob;

static void *after_loop(void *arg)
{
    AfterJob job = *(AfterJob *)arg;
    free(arg);
    sleep_ms((double)job.delay_ns / 1e9);
    job.fn(job.ctx);
    return NULL;
}

void dispatch_after_f(dispatch_time_t when, dispatch_queue_t queue, void *ctx, dispatch_function_t fn)
{
    pthread_t th;
    pthread_attr_t attr;
    AfterJob *job = (AfterJob *)malloc(sizeof(AfterJob));
    (void)queue;
    if (!job) {
        fn(ctx);
        return;
    }
    job->delay_ns = when;
    job->ctx = ctx;
    job->fn = fn;
    pthread_attr_init(&attr);
    pthread_attr_setdetachstate(&attr, PTHREAD_CREATE_DETACHED);
    if (pthread_create(&th, &attr, after_loop, job) != 0) {
        free(job);
        fn(ctx);
    }
    pthread_attr_destroy(&attr);
}
