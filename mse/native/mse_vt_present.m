#ifdef __APPLE__
#import "mse_vt_present.h"
#import "mse_ffmpeg.h"
#import <jni.h>
#import <jawt.h>
#import <jawt_md.h>
#import <AppKit/AppKit.h>
#import <AVFoundation/AVFoundation.h>
#import <QuartzCore/QuartzCore.h>
#import <dlfcn.h>
#import <math.h>
#import <string.h>

void *mse_vt_jawt_layer;
static NSView *mse_vt_jawt_view;
static id mse_end_live_obs;
static NSWindow *mse_live_win;
static int mse_pending_layout;
static int mse_px, mse_py, mse_pw, mse_ph;

/* Overlay sits on AWTView and takes the hit. Forward mouse-up to Java
   (FileAvNative.onOverlayMouse) so the desktop shell can toggle chrome.
   Do not copy events into Compose — that froze AppKit. */
static JavaVM *mse_jvm;
static jclass mse_native_cls;
static jmethodID mse_mouse_mid;
static jmethodID mse_fs_mid;
static id mse_fs_enter_obs;
static id mse_fs_exit_obs;
static id mse_fs_resize_obs;
static id mse_fs_move_obs;
static unsigned mse_fs_gen;
static int mse_overlay_deferred_show;

static void mse_bind_mouse_jni(JNIEnv *env)
{
    jclass local;
    if (!env)
        return;
    if (!mse_jvm)
        (*env)->GetJavaVM(env, &mse_jvm);
    if (!mse_native_cls) {
        local = (*env)->FindClass(env, "io/ymusic/mse/FileAvNative");
        if (!local) {
            if ((*env)->ExceptionCheck(env))
                (*env)->ExceptionClear(env);
            mse_critf(MSE_LOG_WARN, "mse-v mouse FindClass FileAvNative failed");
            return;
        }
        mse_native_cls = (*env)->NewGlobalRef(env, local);
        (*env)->DeleteLocalRef(env, local);
    }
    if (!mse_mouse_mid) {
        mse_mouse_mid = (*env)->GetStaticMethodID(env, mse_native_cls, "onOverlayMouse", "(IIIIIII)V");
        if (!mse_mouse_mid && (*env)->ExceptionCheck(env))
            (*env)->ExceptionClear(env);
    }
    if (!mse_fs_mid) {
        mse_fs_mid = (*env)->GetStaticMethodID(env, mse_native_cls, "onFullScreenChanged", "(Z)V");
        if (!mse_fs_mid && (*env)->ExceptionCheck(env))
            (*env)->ExceptionClear(env);
    }
}

static void mse_clear_live_obs(void);
static void mse_apply_host_frame(int x, int y, int w, int h);

static void mse_watch_live_resize(NSWindow *win)
{
    if (!win)
        return;
    if (mse_live_win == win && mse_end_live_obs)
        return;
    mse_clear_live_obs();
    mse_live_win = win;
    mse_end_live_obs = [NSNotificationCenter.defaultCenter
        addObserverForName:NSWindowDidEndLiveResizeNotification
                    object:win
                     queue:nil
                usingBlock:^(NSNotification *note) {
                    (void)note;
                    if (mse_pending_layout) {
                        mse_pending_layout = 0;
                        mse_apply_host_frame(mse_px, mse_py, mse_pw, mse_ph);
                    }
                }];
}

static jint mse_awt_mods(NSEvent *e)
{
    jint m = 0;
    NSUInteger b = NSEvent.pressedMouseButtons;
    if (e.modifierFlags & NSEventModifierFlagShift)
        m |= 64;
    if (e.modifierFlags & NSEventModifierFlagControl)
        m |= 128;
    if (e.modifierFlags & NSEventModifierFlagCommand)
        m |= 256;
    if (e.modifierFlags & NSEventModifierFlagOption)
        m |= 512;
    if (b & 1)
        m |= 1024;
    if (b & 4)
        m |= 2048;
    if (b & 2)
        m |= 4096;
    return m;
}

static jint mse_awt_id(NSEventType t)
{
    switch (t) {
    case NSEventTypeLeftMouseDown:
    case NSEventTypeRightMouseDown:
    case NSEventTypeOtherMouseDown:
        return 501;
    case NSEventTypeLeftMouseUp:
    case NSEventTypeRightMouseUp:
    case NSEventTypeOtherMouseUp:
        return 502;
    case NSEventTypeMouseMoved:
        return 503;
    case NSEventTypeMouseEntered:
        return 504;
    case NSEventTypeMouseExited:
        return 505;
    case NSEventTypeLeftMouseDragged:
    case NSEventTypeRightMouseDragged:
    case NSEventTypeOtherMouseDragged:
        return 506;
    case NSEventTypeScrollWheel:
        return 507;
    default:
        return 503;
    }
}

static jint mse_awt_button(NSEventType t)
{
    switch (t) {
    case NSEventTypeLeftMouseDown:
    case NSEventTypeLeftMouseUp:
    case NSEventTypeLeftMouseDragged:
        return 1;
    case NSEventTypeOtherMouseDown:
    case NSEventTypeOtherMouseUp:
    case NSEventTypeOtherMouseDragged:
        return 2;
    case NSEventTypeRightMouseDown:
    case NSEventTypeRightMouseUp:
    case NSEventTypeRightMouseDragged:
        return 3;
    default:
        return 0;
    }
}

static void mse_java_mouse(NSEvent *e)
{
    NSPoint scr;
    CGFloat top;
    jint x, y, wheel, clicks, eid, mods, button;

    if (!e || !e.window || !mse_jvm || !mse_native_cls || !mse_mouse_mid)
        return;
    scr = [e.window convertPointToScreen:e.locationInWindow];
    top = e.window.screen ? NSMaxY(e.window.screen.frame) : NSMaxY(NSScreen.mainScreen.frame);
    x = (jint)llround(scr.x);
    y = (jint)llround(top - scr.y);
    wheel = (e.type == NSEventTypeScrollWheel) ? (jint)llround(-e.scrollingDeltaY) : 0;
    switch (e.type) {
    case NSEventTypeLeftMouseDown:
    case NSEventTypeLeftMouseUp:
    case NSEventTypeRightMouseDown:
    case NSEventTypeRightMouseUp:
    case NSEventTypeOtherMouseDown:
    case NSEventTypeOtherMouseUp:
        clicks = (jint)e.clickCount;
        break;
    default:
        clicks = 0;
        break;
    }
    eid = mse_awt_id(e.type);
    mods = mse_awt_mods(e);
    button = mse_awt_button(e.type);
    /* Return from NSView mouseDown before Java. AWT EDT is AppKit main:
       dispatchEvent/toFront from this stack freezes the UI. */
    dispatch_async(dispatch_get_main_queue(), ^{
        JNIEnv *async_env = NULL;
        if ((*mse_jvm)->GetEnv(mse_jvm, (void **)&async_env, JNI_VERSION_1_6) != JNI_OK)
            (*mse_jvm)->AttachCurrentThread(mse_jvm, (void **)&async_env, NULL);
        if (!async_env || !mse_native_cls || !mse_mouse_mid)
            return;
        (*async_env)->CallStaticVoidMethod(async_env, mse_native_cls, mse_mouse_mid,
                                           eid, x, y, mods, button, clicks, wheel);
        if ((*async_env)->ExceptionCheck(async_env))
            (*async_env)->ExceptionClear(async_env);
    });
}

@interface MseVtHostView : NSView
@end
@implementation MseVtHostView
- (NSView *)hitTest:(NSPoint)point
{
    /* Must honor hidden. Default NSView skips hidden views; this override did
       not, so mouseDown never reached Compose (hover still did — mouseMoved
       hits the DropdownMenu JDialog). Covering the canvas does not help while
       this view stays in the hierarchy and eats presses. */
    if (self.hidden || self.alphaValue < 0.01f)
        return nil;
    return NSPointInRect(point, self.frame) ? self : nil;
}
#define MSE_JNI_MOUSE(sel) - (void)sel:(NSEvent *)e { mse_java_mouse(e); }
MSE_JNI_MOUSE(mouseDown)
MSE_JNI_MOUSE(mouseUp)
MSE_JNI_MOUSE(mouseDragged)
MSE_JNI_MOUSE(rightMouseDown)
MSE_JNI_MOUSE(rightMouseUp)
MSE_JNI_MOUSE(otherMouseDown)
MSE_JNI_MOUSE(otherMouseUp)
MSE_JNI_MOUSE(scrollWheel)
#undef MSE_JNI_MOUSE
@end

static NSRect mse_screen_to_view(NSView *parent, jint x, jint y, jint w, jint h);

static void mse_clear_live_obs(void)
{
    if (!mse_end_live_obs)
        return;
    [NSNotificationCenter.defaultCenter removeObserver:mse_end_live_obs];
    mse_end_live_obs = nil;
    mse_live_win = nil;
}

static void mse_apply_host_frame(int x, int y, int w, int h)
{
    NSView *host = mse_vt_jawt_view;
    NSView *parent = host.superview;
    NSRect frame;
    if (!host || !parent)
        return;
    frame = mse_screen_to_view(parent, x, y, w, h);
    if (NSEqualRects(host.frame, frame))
        return;
    host.frame = frame;
}

static jboolean (*mse_jawt_get_awt)(JNIEnv *, JAWT *);

/* Never @protocol(JAWT_SurfaceLayers): it emits a second copy of libawt_lwawt's protocol, and
   macOS 11's libobjc SIGBUSes rewriting its ref in read-only __DATA_CONST at System.load. */
static int mse_is_surface_layers(id sl)
{
    return sl && [sl respondsToSelector:@selector(layer)] && [sl respondsToSelector:@selector(windowLayer)];
}

static int jawt_bind(void)
{
    void *h;
    if (mse_jawt_get_awt)
        return 1;
    /* Kotlin System.loadLibrary("jawt") already mapped it; macOS 11's dyld doesn't match the
       leaf name against its @rpath install name, so the dlopen below is only a fallback. */
    mse_jawt_get_awt = (jboolean (*)(JNIEnv *, JAWT *))dlsym(RTLD_DEFAULT, "JAWT_GetAWT");
    if (mse_jawt_get_awt)
        return 1;
    h = dlopen("libjawt.dylib", RTLD_LAZY | RTLD_GLOBAL);
    if (!h) {
        mse_critf(MSE_LOG_WARN, "mse-v jawt dlopen failed %s", dlerror() ? dlerror() : "?");
        return 0;
    }
    mse_jawt_get_awt = (jboolean (*)(JNIEnv *, JAWT *))dlsym(h, "JAWT_GetAWT");
    if (!mse_jawt_get_awt) {
        mse_critf(MSE_LOG_WARN, "mse-v jawt dlsym JAWT_GetAWT failed");
        return 0;
    }
    return 1;
}

/* Always async. AWT's EDT is AppKit main: inlining during Canvas removeNotify /
   addNotify (mini player → Now Playing) re-enters AppKit while JAWT still owns
   the surface and freezes the UI. Never dispatch_sync. */
static void mse_on_main(void (^block)(void))
{
    dispatch_async(dispatch_get_main_queue(), block);
}

void mse_vt_jawt_unhost(void)
{
    NSView *v = mse_vt_jawt_view;
    mse_vt_jawt_view = nil;
    mse_pending_layout = 0;
    mse_clear_live_obs();
    if (!v)
        return;
    mse_on_main(^{
        [v removeFromSuperview];
    });
}

void mse_vt_set_overlay_hidden(int hid)
{
    NSView *v = mse_vt_jawt_view;
    if (hid) {
        mse_overlay_deferred_show = 0;
        if (!v)
            return;
        if ([NSThread isMainThread])
            v.hidden = YES;
        else
            mse_on_main(^{
                v.hidden = YES;
            });
        return;
    }
    /* Stay hidden until mse_vt_reveal_overlay (next presented frame). */
    mse_overlay_deferred_show = 1;
}

void mse_vt_reveal_overlay(void)
{
    NSView *v;
    if (!mse_overlay_deferred_show)
        return;
    mse_overlay_deferred_show = 0;
    v = mse_vt_jawt_view;
    if (!v)
        return;
    if ([NSThread isMainThread])
        v.hidden = NO;
    else
        mse_on_main(^{
            NSView *host = mse_vt_jawt_view;
            if (host)
                host.hidden = NO;
        });
}

static NSView *mse_content_view(CALayer *wl);

static NSWindow *mse_awt_nswindow(JNIEnv *env, jobject awt)
{
    JAWT awt_api;
    JAWT_DrawingSurface *ds;
    JAWT_DrawingSurfaceInfo *dsi;
    id<JAWT_SurfaceLayers> sl;
    NSView *v = nil;
    jint lock;

    if (!awt)
        return nil;
    if (!jawt_bind())
        return nil;
    memset(&awt_api, 0, sizeof(awt_api));
    awt_api.version = JAWT_VERSION_1_7;
    if (mse_jawt_get_awt(env, &awt_api) == JNI_FALSE)
        return nil;
    ds = awt_api.GetDrawingSurface(env, awt);
    if (!ds)
        return nil;
    lock = ds->Lock(ds);
    if ((lock & JAWT_LOCK_ERROR) != 0) {
        awt_api.FreeDrawingSurface(ds);
        return nil;
    }
    dsi = ds->GetDrawingSurfaceInfo(ds);
    if (dsi) {
        sl = (__bridge id<JAWT_SurfaceLayers>)dsi->platformInfo;
        if (mse_is_surface_layers(sl))
            v = mse_content_view(sl.windowLayer);
        ds->FreeDrawingSurfaceInfo(dsi);
    }
    ds->Unlock(ds);
    awt_api.FreeDrawingSurface(ds);
    return v ? v.window : nil;
}

void mse_vt_order_window_below(void *envp, void *below, void *above)
{
    JNIEnv *env = envp;
    NSWindow *lo;
    NSWindow *hi;
    if (!env || !below || !above)
        return;
    lo = mse_awt_nswindow(env, below);
    hi = mse_awt_nswindow(env, above);
    if (!lo || !hi || lo == hi)
        return;
    mse_on_main(^{
        [lo orderWindow:NSWindowBelow relativeTo:hi.windowNumber];
    });
}

static NSWindow *mse_named_window(NSString *title)
{
    for (NSWindow *w in NSApp.windows) {
        if ([w.title isEqualToString:title])
            return w;
    }
    return nil;
}

static void mse_fs_layout_unwatch(void)
{
    NSNotificationCenter *nc = NSNotificationCenter.defaultCenter;
    if (mse_fs_resize_obs) {
        [nc removeObserver:mse_fs_resize_obs];
        mse_fs_resize_obs = nil;
    }
    if (mse_fs_move_obs) {
        [nc removeObserver:mse_fs_move_obs];
        mse_fs_move_obs = nil;
    }
}

static void mse_fs_timer_stop(void)
{
    mse_fs_gen++;
    mse_fs_layout_unwatch();
}

static void mse_fs_restack(void)
{
    NSWindow *ui = mse_named_window(@"YMusic");
    NSWindow *video = mse_named_window(@"YMusic video");
    BOOL fs;
    if (!ui || !video || ui == video)
        return;
    fs = (ui.styleMask & NSWindowStyleMaskFullScreen) != 0;
    if (video.parentWindow)
        [video.parentWindow removeChildWindow:video];
    ui.level = fs ? NSFloatingWindowLevel : NSNormalWindowLevel;
    video.level = NSNormalWindowLevel;
    video.collectionBehavior = NSWindowCollectionBehaviorManaged |
                               NSWindowCollectionBehaviorFullScreenNone |
                               NSWindowCollectionBehaviorMoveToActiveSpace;
    video.ignoresMouseEvents = fs;
    [ui orderFrontRegardless];
    [video orderWindow:NSWindowBelow relativeTo:ui.windowNumber];
}

static void mse_fs_burst(unsigned gen, int left)
{
    if (gen != mse_fs_gen)
        return;
    mse_fs_restack();
    if (left <= 0)
        return;
    dispatch_after(dispatch_time(DISPATCH_TIME_NOW, 50 * NSEC_PER_MSEC),
                   dispatch_get_main_queue(), ^{
                       mse_fs_burst(gen, left - 1);
                   });
}

static void mse_fs_timer_start(void)
{
    NSNotificationCenter *nc = NSNotificationCenter.defaultCenter;
    unsigned gen;
    mse_fs_gen++;
    gen = mse_fs_gen;
    mse_fs_layout_unwatch();
    mse_fs_resize_obs = [nc addObserverForName:NSWindowDidResizeNotification
                                        object:nil
                                         queue:NSOperationQueue.mainQueue
                                    usingBlock:^(NSNotification *note) {
                                        NSWindow *w = note.object;
                                        NSString *t = w.title;
                                        if (![t isEqualToString:@"YMusic"] &&
                                            ![t isEqualToString:@"YMusic video"])
                                            return;
                                        mse_fs_restack();
                                    }];
    mse_fs_move_obs = [nc addObserverForName:NSWindowDidMoveNotification
                                      object:nil
                                       queue:NSOperationQueue.mainQueue
                                  usingBlock:^(NSNotification *note) {
                                      NSWindow *w = note.object;
                                      NSString *t = w.title;
                                      if (![t isEqualToString:@"YMusic"] &&
                                          ![t isEqualToString:@"YMusic video"])
                                          return;
                                      mse_fs_restack();
                                  }];
    mse_fs_burst(gen, 8);
}

static void mse_notify_fs(int on)
{
    JNIEnv *env = NULL;
    if (!mse_jvm || !mse_native_cls || !mse_fs_mid)
        return;
    if ((*mse_jvm)->GetEnv(mse_jvm, (void **)&env, JNI_VERSION_1_6) != JNI_OK)
        (*mse_jvm)->AttachCurrentThread(mse_jvm, (void **)&env, NULL);
    if (!env)
        return;
    (*env)->CallStaticVoidMethod(env, mse_native_cls, mse_fs_mid, on ? JNI_TRUE : JNI_FALSE);
    if ((*env)->ExceptionCheck(env))
        (*env)->ExceptionClear(env);
}

static void mse_fs_watch(NSWindow *win)
{
    NSNotificationCenter *nc = NSNotificationCenter.defaultCenter;
    if (!win)
        return;
    if (mse_fs_enter_obs) {
        [nc removeObserver:mse_fs_enter_obs];
        mse_fs_enter_obs = nil;
    }
    if (mse_fs_exit_obs) {
        [nc removeObserver:mse_fs_exit_obs];
        mse_fs_exit_obs = nil;
    }
    mse_fs_enter_obs = [nc addObserverForName:NSWindowDidEnterFullScreenNotification
                                      object:win
                                       queue:nil
                                  usingBlock:^(NSNotification *note) {
                                      (void)note;
                                      mse_fs_restack();
                                      mse_fs_timer_start();
                                      mse_notify_fs(1);
                                  }];
    mse_fs_exit_obs = [nc addObserverForName:NSWindowDidExitFullScreenNotification
                                     object:win
                                      queue:nil
                                 usingBlock:^(NSNotification *note) {
                                     (void)note;
                                     mse_fs_timer_stop();
                                     mse_fs_restack();
                                     mse_notify_fs(0);
                                 }];
}

void mse_vt_prepare_fullscreen(void *envp, void *primary)
{
    JNIEnv *env = envp;
    NSWindow *ui;
    if (!env || !primary)
        return;
    mse_bind_mouse_jni(env);
    ui = mse_awt_nswindow(env, primary);
    if (!ui)
        ui = mse_named_window(@"YMusic");
    if (!ui)
        return;
    mse_on_main(^{
        ui.collectionBehavior |= NSWindowCollectionBehaviorFullScreenPrimary |
                                 NSWindowCollectionBehaviorManaged;
        mse_fs_watch(ui);
    });
}

void mse_vt_toggle_fullscreen(void *envp, void *primary)
{
    JNIEnv *env = envp;
    NSWindow *ui;
    if (!env)
        return;
    ui = primary ? mse_awt_nswindow(env, primary) : nil;
    if (!ui)
        ui = mse_named_window(@"YMusic");
    if (!ui)
        return;
    mse_on_main(^{
        [ui toggleFullScreen:nil];
    });
}

static NSView *mse_content_view(CALayer *wl)
{
    NSView *v = nil;
    CALayer *p;
    Class theme;
    if ([wl.delegate isKindOfClass:[NSView class]])
        v = (NSView *)wl.delegate;
    else {
        for (p = wl; p; p = p.superlayer) {
            if ([p.delegate isKindOfClass:[NSView class]]) {
                v = (NSView *)p.delegate;
                break;
            }
        }
    }
    if (!v)
        v = [NSApp keyWindow].contentView;
    theme = NSClassFromString(@"NSThemeFrame");
    if (theme && [v isKindOfClass:theme])
        v = v.window.contentView;
    if (v.window.contentView)
        v = v.window.contentView;
    return v;
}

static int mse_java_screen_rect(JNIEnv *env, jobject obj, jint *x, jint *y, jint *w, jint *h)
{
    jclass cls, ptcls;
    jmethodID getW, getH, getLoc;
    jobject pt;
    jfieldID fx, fy;

    cls = (*env)->GetObjectClass(env, obj);
    getW = (*env)->GetMethodID(env, cls, "getWidth", "()I");
    getH = (*env)->GetMethodID(env, cls, "getHeight", "()I");
    getLoc = (*env)->GetMethodID(env, cls, "getLocationOnScreen", "()Ljava/awt/Point;");
    if (!getW || !getH || !getLoc)
        return -1;
    *w = (*env)->CallIntMethod(env, obj, getW);
    *h = (*env)->CallIntMethod(env, obj, getH);
    pt = (*env)->CallObjectMethod(env, obj, getLoc);
    if ((*env)->ExceptionCheck(env) || !pt) {
        if ((*env)->ExceptionCheck(env))
            (*env)->ExceptionClear(env);
        return -1;
    }
    ptcls = (*env)->GetObjectClass(env, pt);
    fx = (*env)->GetFieldID(env, ptcls, "x", "I");
    fy = (*env)->GetFieldID(env, ptcls, "y", "I");
    if (!fx || !fy)
        return -1;
    *x = (*env)->GetIntField(env, pt, fx);
    *y = (*env)->GetIntField(env, pt, fy);
    return (*w > 0 && *h > 0) ? 0 : -1;
}

static NSRect mse_screen_to_view(NSView *parent, jint x, jint y, jint w, jint h)
{
    NSRect screen, win;
    CGFloat top = NSMaxY(parent.window.screen.frame);

    screen = NSMakeRect((CGFloat)x, top - (CGFloat)y - (CGFloat)h, (CGFloat)w, (CGFloat)h);
    win = [parent.window convertRectFromScreen:screen];
    return [parent convertRect:win fromView:nil];
}

void mse_vt_layout_screen(int x, int y, int w, int h)
{
    if (w <= 0 || h <= 0)
        return;
    /* Always async: inlining on main from AWT doLayout set the NSView frame,
       which re-entered AWT layout (~300% CPU). Skip while the user is dragging
       the window edge — apply once on NSWindowDidEndLiveResize. */
    dispatch_async(dispatch_get_main_queue(), ^{
        NSView *host = mse_vt_jawt_view;
        NSWindow *win;
        if (!host)
            return;
        win = host.window;
        mse_watch_live_resize(win);
        if (win && win.inLiveResize) {
            mse_px = x;
            mse_py = y;
            mse_pw = w;
            mse_ph = h;
            mse_pending_layout = 1;
            return;
        }
        mse_pending_layout = 0;
        mse_apply_host_frame(x, y, w, h);
    });
}

static void *mse_vt_host_new(CALayer *wl, CALayer *canvas_layer, jint x, jint y, jint w, jint h)
{
    AVSampleBufferDisplayLayer *layer;
    void *retained;

    layer = [AVSampleBufferDisplayLayer layer];
    layer.videoGravity = AVLayerVideoGravityResizeAspect;
    layer.backgroundColor = CGColorGetConstantColor(kCGColorBlack);
    layer.actions = @{ @"contents": [NSNull null], @"bounds": [NSNull null], @"frame": [NSNull null] };
    retained = (void *)CFBridgingRetain(layer);
    if (mse_vt_jawt_layer && mse_vt_jawt_layer != retained)
        mse_vt_detach(mse_vt_jawt_layer);
    mse_vt_jawt_layer = retained;
    mse_on_main(^{
        NSView *parent = mse_content_view(wl);
        NSView *host;
        NSRect frame;
        if (mse_vt_jawt_layer != retained)
            return;
        if (!parent) {
            mse_critf(MSE_LOG_WARN, "mse-v jawt no content view");
            return;
        }
        /* Child of AWTView: the picture is visible. A sibling under AWTView is a
           black hole (Metal does not composite through, and NSThemeFrame rejects
           unknown subviews). Compose chrome over the hole is copied via JNI. */
        frame = mse_screen_to_view(parent, x, y, w, h);
        host = [[MseVtHostView alloc] initWithFrame:frame];
        host.wantsLayer = YES;
        host.layer = (__bridge AVSampleBufferDisplayLayer *)retained;
        [parent addSubview:host];
        host.window.acceptsMouseMovedEvents = YES;
        mse_vt_jawt_view = host;
        mse_watch_live_resize(host.window);
        (void)canvas_layer;
        mse_critf(MSE_LOG_INFO, "mse-v jawt layer attached %.0fx%.0f @ %.0f,%.0f parent=%s",
                 frame.size.width, frame.size.height, frame.origin.x, frame.origin.y,
                 parent.className.UTF8String ?: "?");
    });
    return retained;
}

void *mse_vt_attach_component(void *envp, void *component)
{
    JNIEnv *env = (JNIEnv *)envp;
    jobject obj = (jobject)component;
    jclass canvas;
    JAWT awt;
    JAWT_DrawingSurface *ds;
    JAWT_DrawingSurfaceInfo *dsi;
    id<JAWT_SurfaceLayers> sl;
    CALayer *wl;
    jint jx = 0, jy = 0, jw = 0, jh = 0;
    void *retained;

    if (!env || !obj)
        return NULL;
    mse_bind_mouse_jni(env);
    if (mse_java_screen_rect(env, obj, &jx, &jy, &jw, &jh) != 0)
        return mse_vt_jawt_layer;
    if (mse_vt_jawt_layer && mse_vt_jawt_view) {
        mse_vt_layout_screen(jx, jy, jw, jh);
        return mse_vt_jawt_layer;
    }
    if (!jawt_bind())
        return NULL;
    canvas = (*env)->FindClass(env, "java/awt/Canvas");
    if (!canvas || !(*env)->IsInstanceOf(env, obj, canvas)) {
        mse_critf(MSE_LOG_WARN, "mse-v jawt not a Canvas");
        if ((*env)->ExceptionCheck(env))
            (*env)->ExceptionClear(env);
        return NULL;
    }
    memset(&awt, 0, sizeof(awt));
    awt.version = JAWT_VERSION_1_7;
    if (mse_jawt_get_awt(env, &awt) == JNI_FALSE) {
        mse_critf(MSE_LOG_WARN, "mse-v jawt GetAWT failed");
        return NULL;
    }
    ds = awt.GetDrawingSurface(env, obj);
    if (!ds) {
        mse_critf(MSE_LOG_WARN, "mse-v jawt GetDrawingSurface failed");
        return NULL;
    }
    if ((ds->Lock(ds) & JAWT_LOCK_ERROR) != 0) {
        mse_critf(MSE_LOG_WARN, "mse-v jawt Lock failed");
        awt.FreeDrawingSurface(ds);
        return NULL;
    }
    dsi = ds->GetDrawingSurfaceInfo(ds);
    if (!dsi || !dsi->platformInfo) {
        mse_critf(MSE_LOG_WARN, "mse-v jawt GetDrawingSurfaceInfo failed");
        ds->Unlock(ds);
        awt.FreeDrawingSurface(ds);
        return NULL;
    }
    sl = (__bridge id<JAWT_SurfaceLayers>)dsi->platformInfo;
    if (!mse_is_surface_layers(sl)) {
        mse_critf(MSE_LOG_WARN, "mse-v jawt platformInfo is not SurfaceLayers");
        ds->FreeDrawingSurfaceInfo(dsi);
        ds->Unlock(ds);
        awt.FreeDrawingSurface(ds);
        return NULL;
    }
    wl = sl.windowLayer;
    {
        CALayer *cl = sl.layer;
        ds->FreeDrawingSurfaceInfo(dsi);
        ds->Unlock(ds);
        awt.FreeDrawingSurface(ds);
        retained = mse_vt_host_new(wl, cl, jx, jy, jw, jh);
        return retained;
    }
    return retained;
}
#endif
