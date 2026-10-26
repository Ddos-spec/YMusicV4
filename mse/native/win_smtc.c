/* Windows volume-flyout transport (SMTC) for an unpackaged Win32 window. */
#include <jni.h>
#include <pthread.h>
#include <stdlib.h>
#include <string.h>
#include <wchar.h>

#define COBJMACROS
#include <roapi.h>
#include <winstring.h>
#include <windows.media.h>
#include <systemmediatransportcontrolsinterop.h>

#include "mse_ffmpeg.h"

typedef __x_ABI_CWindows_CMedia_CISystemMediaTransportControls ISmtc;
typedef __x_ABI_CWindows_CMedia_CISystemMediaTransportControlsDisplayUpdater ISmtcUp;
typedef __x_ABI_CWindows_CMedia_CIMusicDisplayProperties IMusic;
typedef __x_ABI_CWindows_CMedia_CISystemMediaTransportControlsButtonPressedEventArgs IBtnArgs;
typedef __FITypedEventHandler_2_Windows__CMedia__CSystemMediaTransportControls_Windows__CMedia__CSystemMediaTransportControlsButtonPressedEventArgs IBtnHandler;
typedef __FITypedEventHandler_2_Windows__CMedia__CSystemMediaTransportControls_Windows__CMedia__CSystemMediaTransportControlsButtonPressedEventArgsVtbl IBtnHandlerVtbl;

#define CMD_Q 16
static const GUID kIID_SmtcInterop = {0xddb0472d, 0xc911, 0x4a1f, {0x86, 0xd9, 0xdc, 0x3d, 0x71, 0xa9, 0x5f, 0x5a}};
static const GUID kIID_Smtc = {0x99fa3ff4, 0x1742, 0x42a6, {0x90, 0x2e, 0x08, 0x7d, 0x41, 0xf9, 0x65, 0xec}};
static const GUID kIID_Btn = {0x0557e996, 0x7b23, 0x5bae, {0xaa, 0x81, 0xea, 0x0d, 0x67, 0x11, 0x43, 0xa4}};

static pthread_mutex_t g_mu = PTHREAD_MUTEX_INITIALIZER;
static int g_cmd[CMD_Q];
static int g_r, g_n;
static ISmtc *g_smtc;
static ISystemMediaTransportControlsInterop *g_factory;
static IBtnHandler *g_handler;
static EventRegistrationToken g_token;
static int g_ro;
static int g_bound;

typedef struct Handler {
    IBtnHandlerVtbl *lpVtbl;
    LONG refs;
} Handler;

static void push_cmd(int cmd)
{
    pthread_mutex_lock(&g_mu);
    if (g_n < CMD_Q) {
        g_cmd[(g_r + g_n) % CMD_Q] = cmd;
        g_n++;
    }
    pthread_mutex_unlock(&g_mu);
}

static HRESULT STDMETHODCALLTYPE h_qi(IBtnHandler *This, REFIID riid, void **out)
{
    if (!out)
        return E_POINTER;
    if (IsEqualIID(riid, &IID_IUnknown) ||
        IsEqualIID(riid, &kIID_Btn)) {
        *out = This;
        This->lpVtbl->AddRef(This);
        return S_OK;
    }
    *out = NULL;
    return E_NOINTERFACE;
}

static ULONG STDMETHODCALLTYPE h_add(IBtnHandler *This)
{
    return InterlockedIncrement(&((Handler *)This)->refs);
}

static ULONG STDMETHODCALLTYPE h_rel(IBtnHandler *This)
{
    ULONG n = InterlockedDecrement(&((Handler *)This)->refs);
    if (n == 0)
        free(This);
    return n;
}

static HRESULT STDMETHODCALLTYPE h_invoke(IBtnHandler *This, ISmtc *sender, IBtnArgs *args)
{
    __x_ABI_CWindows_CMedia_CSystemMediaTransportControlsButton button;
    (void)This;
    (void)sender;
    if (!args || FAILED(args->lpVtbl->get_Button(args, &button)))
        return S_OK;
    switch (button) {
    case SystemMediaTransportControlsButton_Play:
        push_cmd(1);
        break;
    case SystemMediaTransportControlsButton_Pause:
        push_cmd(2);
        break;
    case SystemMediaTransportControlsButton_Next:
    case SystemMediaTransportControlsButton_FastForward:
        push_cmd(3);
        break;
    case SystemMediaTransportControlsButton_Previous:
    case SystemMediaTransportControlsButton_Rewind:
        push_cmd(4);
        break;
    case SystemMediaTransportControlsButton_Stop:
        push_cmd(2);
        break;
    default:
        break;
    }
    return S_OK;
}

static IBtnHandlerVtbl g_vt = { h_qi, h_add, h_rel, h_invoke };

static HSTRING hstr(JNIEnv *env, jstring value)
{
    const jchar *chars;
    jsize length;
    HSTRING hs = NULL;
    if (!value) {
        WindowsCreateString(L"", 0, &hs);
        return hs;
    }
    length = (*env)->GetStringLength(env, value);
    chars = (*env)->GetStringChars(env, value, NULL);
    if (!chars)
        return NULL;
    WindowsCreateString((const wchar_t *)chars, (UINT32)length, &hs);
    (*env)->ReleaseStringChars(env, value, chars);
    return hs;
}

JNIEXPORT jint JNICALL
Java_io_ymusic_desktop_media_WinSmtc_bind(JNIEnv *env, jclass cls, jlong hwnd)
{
    HSTRING clsname = NULL;
    HRESULT hr;
    Handler *handler;
    static const wchar_t *kClass = L"Windows.Media.SystemMediaTransportControls";
    (void)env;
    (void)cls;
    if (g_bound)
        return 0;
    if (!hwnd)
        return -1;
    hr = RoInitialize(RO_INIT_MULTITHREADED);
    if (hr == RPC_E_CHANGED_MODE)
        hr = S_OK;
    if (FAILED(hr) && hr != S_FALSE)
        return -1;
    g_ro = (hr == S_OK);
    hr = WindowsCreateString(kClass, (UINT32)wcslen(kClass), &clsname);
    if (FAILED(hr))
        return -1;
    hr = RoGetActivationFactory(clsname, &kIID_SmtcInterop, (void **)&g_factory);
    WindowsDeleteString(clsname);
    if (FAILED(hr) || !g_factory) {
        mse_critf(MSE_LOG_WARN, "smtc: activation factory failed hr=0x%08lx", (unsigned long)hr);
        return -1;
    }
    hr = ISystemMediaTransportControlsInterop_GetForWindow(
        g_factory, (HWND)(intptr_t)hwnd, &kIID_Smtc, (void **)&g_smtc);
    if (FAILED(hr) || !g_smtc) {
        mse_critf(MSE_LOG_WARN, "smtc: GetForWindow failed hr=0x%08lx", (unsigned long)hr);
        return -1;
    }
    g_smtc->lpVtbl->put_IsEnabled(g_smtc, 1);
    g_smtc->lpVtbl->put_IsPlayEnabled(g_smtc, 1);
    g_smtc->lpVtbl->put_IsPauseEnabled(g_smtc, 1);
    g_smtc->lpVtbl->put_IsNextEnabled(g_smtc, 1);
    g_smtc->lpVtbl->put_IsPreviousEnabled(g_smtc, 1);
    handler = (Handler *)calloc(1, sizeof(*handler));
    if (!handler)
        return -1;
    handler->lpVtbl = &g_vt;
    handler->refs = 1;
    g_handler = (IBtnHandler *)handler;
    hr = g_smtc->lpVtbl->add_ButtonPressed(g_smtc, g_handler, &g_token);
    if (FAILED(hr)) {
        mse_critf(MSE_LOG_WARN, "smtc: button hook failed hr=0x%08lx", (unsigned long)hr);
        g_handler->lpVtbl->Release(g_handler);
        g_handler = NULL;
        return -1;
    }
    g_bound = 1;
    mse_critf(MSE_LOG_INFO, "smtc: bound");
    return 0;
}

JNIEXPORT void JNICALL
Java_io_ymusic_desktop_media_WinSmtc_update(JNIEnv *env, jclass cls, jstring title, jstring artist,
                                            jboolean playing, jboolean has_item)
{
    ISmtcUp *up = NULL;
    IMusic *music = NULL;
    HSTRING hs;
    (void)cls;
    if (!g_bound || !g_smtc)
        return;
    g_smtc->lpVtbl->put_PlaybackStatus(
        g_smtc, playing ? MediaPlaybackStatus_Playing : (has_item ? MediaPlaybackStatus_Paused : MediaPlaybackStatus_Stopped));
    if (FAILED(g_smtc->lpVtbl->get_DisplayUpdater(g_smtc, &up)) || !up)
        return;
    up->lpVtbl->put_Type(up, MediaPlaybackType_Music);
    if (SUCCEEDED(up->lpVtbl->get_MusicProperties(up, &music)) && music) {
        hs = hstr(env, title);
        if ((*env)->ExceptionCheck(env)) {
            music->lpVtbl->Release(music);
            up->lpVtbl->Release(up);
            return;
        }
        if (hs) {
            music->lpVtbl->put_Title(music, hs);
            WindowsDeleteString(hs);
        }
        hs = hstr(env, artist);
        if ((*env)->ExceptionCheck(env)) {
            music->lpVtbl->Release(music);
            up->lpVtbl->Release(up);
            return;
        }
        if (hs) {
            music->lpVtbl->put_Artist(music, hs);
            WindowsDeleteString(hs);
        }
        music->lpVtbl->Release(music);
    }
    up->lpVtbl->Update(up);
    up->lpVtbl->Release(up);
}

JNIEXPORT jint JNICALL
Java_io_ymusic_desktop_media_WinSmtc_poll(JNIEnv *env, jclass cls)
{
    int cmd = 0;
    (void)env;
    (void)cls;
    pthread_mutex_lock(&g_mu);
    if (g_n > 0) {
        cmd = g_cmd[g_r];
        g_r = (g_r + 1) % CMD_Q;
        g_n--;
    }
    pthread_mutex_unlock(&g_mu);
    return cmd;
}

JNIEXPORT jint JNICALL
Java_io_ymusic_desktop_media_WinSmtc_bound(JNIEnv *env, jclass cls)
{
    (void)env;
    (void)cls;
    return g_bound;
}

JNIEXPORT void JNICALL
Java_io_ymusic_desktop_media_WinSmtc_release(JNIEnv *env, jclass cls)
{
    (void)env;
    (void)cls;
    if (g_smtc && g_handler)
        g_smtc->lpVtbl->remove_ButtonPressed(g_smtc, g_token);
    if (g_handler) {
        g_handler->lpVtbl->Release(g_handler);
        g_handler = NULL;
    }
    if (g_smtc) {
        g_smtc->lpVtbl->Release(g_smtc);
        g_smtc = NULL;
    }
    if (g_factory) {
        ISystemMediaTransportControlsInterop_Release(g_factory);
        g_factory = NULL;
    }
    g_bound = 0;
    if (g_ro) {
        RoUninitialize();
        g_ro = 0;
    }
}
