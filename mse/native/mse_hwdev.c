#include "mse_hwdev.h"

#include <libavutil/hwcontext.h>
#include <libavutil/pixdesc.h>
#include <pthread.h>
#include <stdlib.h>
#include <string.h>

#include "mse_ffmpeg.h"

#define MAX_FAILED 8

static pthread_once_t g_once = PTHREAD_ONCE_INIT;
static AVBufferRef *g_dev;
static enum AVPixelFormat g_pix = AV_PIX_FMT_NONE;
static const char *g_name = "none";

static pthread_mutex_t g_mu = PTHREAD_MUTEX_INITIALIZER;
static enum AVCodecID g_failed[MAX_FAILED];
static int g_failed_n;

static void hwdev_init(void)
{
    static const struct {
        enum AVHWDeviceType type;
        enum AVPixelFormat pix;
        const char *name;
    } kinds[] = {
#ifdef _WIN32
        /* Desktop Windows: the ffmpeg pack has d3d11va. CUDA/Vulkan are not in that pack. */
        { AV_HWDEVICE_TYPE_D3D11VA, AV_PIX_FMT_D3D11, "d3d11va" },
#endif
        { AV_HWDEVICE_TYPE_CUDA, AV_PIX_FMT_CUDA, "cuda" },
        { AV_HWDEVICE_TYPE_VULKAN, AV_PIX_FMT_VULKAN, "vulkan" },
    };
    const char *want = getenv("YMUSIC_HWDEC");
    size_t i;
    if (want && strcmp(want, "none") == 0) {
        mse_critf(MSE_LOG_INFO, "hwdec off (YMUSIC_HWDEC=none)");
        return;
    }
    for (i = 0; i < sizeof(kinds) / sizeof(kinds[0]); i++) {
        int err;
        if (want && *want && strcmp(want, kinds[i].name) != 0)
            continue;
        err = av_hwdevice_ctx_create(&g_dev, kinds[i].type, NULL, NULL, 0);
        if (err >= 0) {
            g_pix = kinds[i].pix;
            g_name = kinds[i].name;
            mse_critf(MSE_LOG_INFO, "hwdec device %s", g_name);
            return;
        }
        mse_critf(MSE_LOG_INFO, "hwdec %s unavailable (%s)", kinds[i].name, av_err2str(err));
    }
    mse_critf(MSE_LOG_INFO, "hwdec none: software decode");
}

AVBufferRef *mse_hwdev_get(void)
{
    pthread_once(&g_once, hwdev_init);
    return g_dev;
}

enum AVPixelFormat mse_hwdev_pix_fmt(void)
{
    pthread_once(&g_once, hwdev_init);
    return g_pix;
}

const char *mse_hwdev_name(void)
{
    pthread_once(&g_once, hwdev_init);
    return g_name;
}

int mse_hwdev_usable(enum AVCodecID id)
{
    int i, ok;
    if (!mse_hwdev_get())
        return 0;
    pthread_mutex_lock(&g_mu);
    ok = 1;
    for (i = 0; i < g_failed_n; i++) {
        if (g_failed[i] == id)
            ok = 0;
    }
    pthread_mutex_unlock(&g_mu);
    return ok;
}

void mse_hwdev_mark_failed(enum AVCodecID id)
{
    int i;
    pthread_mutex_lock(&g_mu);
    for (i = 0; i < g_failed_n; i++) {
        if (g_failed[i] == id) {
            pthread_mutex_unlock(&g_mu);
            return;
        }
    }
    if (g_failed_n < MAX_FAILED)
        g_failed[g_failed_n++] = id;
    pthread_mutex_unlock(&g_mu);
    mse_critf(MSE_LOG_WARN, "hwdec %s rejected codec %s: software from now on", g_name,
              avcodec_get_name(id));
}

enum AVPixelFormat mse_hwdev_get_format(AVCodecContext *ctx, const enum AVPixelFormat *fmts)
{
    const enum AVPixelFormat *p;
    if (mse_hwdev_usable(ctx->codec_id)) {
        for (p = fmts; *p != AV_PIX_FMT_NONE; p++) {
            if (*p == g_pix)
                return *p;
        }
        /* libavcodec re-offers the list without the device format after its hwaccel init failed. */
        if (ctx->hw_device_ctx)
            mse_hwdev_mark_failed(ctx->codec_id);
    }
    for (p = fmts; *p != AV_PIX_FMT_NONE; p++) {
        const AVPixFmtDescriptor *d = av_pix_fmt_desc_get(*p);
        if (d && !(d->flags & AV_PIX_FMT_FLAG_HWACCEL))
            return *p;
    }
    return AV_PIX_FMT_NONE;
}
