package io.ymusic.desktop.media

/**
 * JNI into mseffmpegjni. The library is loaded before the window exists.
 * [bound] is true once the volume flyout is actually attached to our HWND.
 */
internal object WinSmtc {
    @Volatile
    var ownsTransport: Boolean = false

    @JvmStatic external fun bind(hwnd: Long): Int

    @JvmStatic external fun update(title: String, artist: String, playing: Boolean, hasItem: Boolean)

    /** 0 none, 1 play, 2 pause, 3 next, 4 previous. */
    @JvmStatic external fun poll(): Int

    @JvmStatic external fun bound(): Int

    @JvmStatic external fun release()
}
