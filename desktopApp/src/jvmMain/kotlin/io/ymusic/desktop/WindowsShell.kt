package io.ymusic.desktop

import com.sun.jna.NativeLibrary
import com.sun.jna.WString

/** Before AWT. One taskbar identity. Do not flip DPI awareness here: Compose already sizes the window, and a second scale pass shrinks it every move. */
internal fun installWindowsProcess() {
    if (!System.getProperty("os.name").orEmpty().lowercase().contains("win")) return
    runCatching {
        NativeLibrary.getInstance("shell32")
            .getFunction("SetCurrentProcessExplicitAppUserModelID")
            .invokeInt(arrayOf(WString("io.ymusic.desktop")))
    }
}
