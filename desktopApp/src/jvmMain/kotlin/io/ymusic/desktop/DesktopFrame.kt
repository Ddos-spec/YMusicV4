package io.ymusic.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.WindowState
import com.sun.jna.NativeLibrary
import com.sun.jna.Pointer
import com.sun.jna.Structure
import io.ymusic.mse.FileAvNative
import java.awt.Toolkit
import java.awt.Window

/**
 * Floating, zoom (fit the work area), and fullscreen.
 *
 * Windows sizes the undecorated window to the display; other platforms use the native bridge.
 * Never uses [WindowPlacement.Maximized]: AWT's maximized
 * flag fights the macOS fullscreen animation and forgets the floating size.
 */
internal class DesktopFrame(
    private val state: WindowState,
    startSize: DpSize,
    startPosition: WindowPosition,
) {
    var host: Window? = null
    var fullscreen by mutableStateOf(false)
        private set
    var zoomed by mutableStateOf(false)
        private set

    private var floatingSize = startSize
    private var floatingPosition = startPosition
    private var enforcer: java.awt.event.ComponentAdapter? = null
    private var enforcePasses = 0

    fun persistSize(): DpSize = floatingSize

    fun persistPosition(): WindowPosition = floatingPosition

    fun noteFloatingIfIdle() {
        if (fullscreen || zoomed) return
        if (state.placement != WindowPlacement.Floating) return
        floatingSize = state.size
        floatingPosition = state.position
    }

    fun toggleZoom() {
        if (fullscreen) return
        val w = host ?: return
        if (zoomed) {
            zoomed = false
            put(floatingSize, floatingPosition)
        } else {
            floatingSize = state.size
            floatingPosition = state.position
            zoomed = true
            val (size, position) = workArea(w)
            put(size, position)
        }
    }

    fun toggleFullscreen() {
        val w = host ?: return
        if (!isWindows) {
            FileAvNative.toggleFullScreen(w)
            return
        }
        if (fullscreen) {
            stopEnforcer(w)
            if (zoomed) {
                val (size, position) = workArea(w)
                put(size, position)
            } else {
                put(floatingSize, floatingPosition)
            }
            fullscreen = false
            return
        }
        if (!zoomed) {
            floatingSize = state.size
            floatingPosition = state.position
        }
        fullscreen = true
        // AWT exclusive fullscreen iconifies on focus loss, including input-method windows.
        startEnforcer(w)
        applyMonitorBounds(w)
    }

    fun minimize() {
        if (!isWindows) return
        val w = host ?: return
        state.isMinimized = true
        winMinimize(w)
    }

    /** Clicking the taskbar icon should come back to the same fullscreen, not a floating window. */
    fun restoreAfterMinimize() {
        val w = host ?: return
        if (!isWindows || !fullscreen) return
        state.isMinimized = false
        startEnforcer(w)
        applyMonitorBounds(w)
    }

    /**
     * Undecorated Compose keeps reapplying [WindowState.position], which snaps a drag back to
     * where the window was opened. Mirror the real bounds into state whenever the user moves it,
     * including onto another monitor. Fullscreen stays pinned to that monitor.
     */
    fun followMoves(window: Window) {
        if (!isWindows) return
        window.addComponentListener(object : java.awt.event.ComponentAdapter() {
            override fun componentMoved(e: java.awt.event.ComponentEvent) {
                if (fullscreen || zoomed) return
                rememberPosition(window)
            }
        })
    }

    private fun rememberPosition(window: Window) {
        val b = window.bounds
        val position = WindowPosition(b.x.dp, b.y.dp)
        if (state.position.isSpecified &&
            kotlin.math.abs(state.position.x.value - position.x.value) < 1f &&
            kotlin.math.abs(state.position.y.value - position.y.value) < 1f
        ) return
        state.position = position
        floatingPosition = position
    }

    fun onNativeFullscreen(on: Boolean) {
        val w = host ?: return
        fullscreen = on
        if (on) {
            val b = w.graphicsConfiguration.bounds
            state.size = DpSize(b.width.dp, b.height.dp)
            return
        }
        if (zoomed) {
            val (size, position) = workArea(w)
            put(size, position)
        } else {
            put(floatingSize, floatingPosition)
        }
    }

    private fun startEnforcer(w: Window) {
        stopEnforcer(w)
        enforcePasses = 0
        val listener = object : java.awt.event.ComponentAdapter() {
            override fun componentResized(e: java.awt.event.ComponentEvent) {
                if (fullscreen) enforceMonitor(w)
            }

            override fun componentMoved(e: java.awt.event.ComponentEvent) {
                if (fullscreen) enforceMonitor(w)
            }
        }
        enforcer = listener
        w.addComponentListener(listener)
    }

    private fun stopEnforcer(w: Window) {
        enforcer?.let { w.removeComponentListener(it) }
        enforcer = null
        enforcePasses = 0
    }

    private fun enforceMonitor(w: Window) {
        if (!isWindows || !fullscreen || state.isMinimized || enforcePasses > 6) return
        val target = w.graphicsConfiguration.bounds
        val b = w.bounds
        val covered = kotlin.math.abs(b.width - target.width) <= 2 &&
            kotlin.math.abs(b.height - target.height) <= 2 &&
            kotlin.math.abs(b.x - target.x) <= 2 &&
            kotlin.math.abs(b.y - target.y) <= 2
        if (covered) return
        enforcePasses++
        applyMonitorBounds(w)
    }

    private fun applyMonitorBounds(w: Window) {
        val bounds = w.graphicsConfiguration.bounds
        put(DpSize(bounds.width.dp, bounds.height.dp), WindowPosition(bounds.x.dp, bounds.y.dp))
        w.setBounds(bounds)
        if (isWindows) coverMonitor(w)
    }

    private fun put(size: DpSize, position: WindowPosition) {
        state.placement = WindowPlacement.Floating
        state.size = size
        state.position = position
    }
}

private val osName = System.getProperty("os.name").orEmpty().lowercase()
private val isWindows = "win" in osName

private fun winMinimize(window: Window) {
    runCatching {
        val hwnd = peerHwnd(window) ?: return
        val show = NativeLibrary.getInstance("user32").getFunction("ShowWindow")
        show.invokeInt(arrayOf(Pointer(hwnd), SW_MINIMIZE))
    }
}

internal fun windowHwnd(window: Window): Long? = peerHwnd(window)

private fun peerHwnd(window: Window): Long? {
    var peer: Any? = null
    var cls: Class<*>? = java.awt.Component::class.java
    while (cls != null && peer == null) {
        val field = cls.declaredFields.firstOrNull { it.name == "peer" }
        if (field != null) {
            try {
                field.isAccessible = true
                peer = field.get(window)
            } catch (_: Exception) {
                return null
            }
        }
        cls = cls.superclass
    }
    val target = peer ?: return null
    var type: Class<*>? = target.javaClass
    while (type != null) {
        val field = type.declaredFields.firstOrNull { it.name == "hwnd" }
        if (field != null) {
            try {
                field.isAccessible = true
                return field.getLong(target)
            } catch (_: Exception) {
                return null
            }
        }
        type = type.superclass
    }
    return null
}

private const val SW_MINIMIZE = 6
private const val MONITOR_DEFAULTTONEAREST = 2
private const val SWP_SHOWWINDOW = 0x0040

private class WinRect : Structure() {
    @JvmField var left = 0

    @JvmField var top = 0

    @JvmField var right = 0

    @JvmField var bottom = 0
    override fun getFieldOrder() = listOf("left", "top", "right", "bottom")
}

private class MonitorInfo : Structure() {
    @JvmField var cbSize = 0

    @JvmField var rcMonitor = WinRect()

    @JvmField var rcWork = WinRect()

    @JvmField var dwFlags = 0
    override fun getFieldOrder() = listOf("cbSize", "rcMonitor", "rcWork", "dwFlags")
}

/** Win32 coordinates are physical pixels; AWT bounds stay in user-space units. */
private fun monitorPixels(window: Window): IntArray? {
    val hwnd = peerHwnd(window) ?: return null
    return runCatching {
        val user32 = NativeLibrary.getInstance("user32")
        val monitor = user32.getFunction("MonitorFromWindow")
            .invokePointer(arrayOf(Pointer(hwnd), MONITOR_DEFAULTTONEAREST))
            ?: return null
        val info = MonitorInfo()
        info.cbSize = info.size()
        val ok = user32.getFunction("GetMonitorInfoW").invokeInt(arrayOf(monitor, info))
        if (ok == 0) return null
        info.read()
        val r = info.rcMonitor
        intArrayOf(r.left, r.top, r.right - r.left, r.bottom - r.top)
    }.getOrNull()
}

private fun coverMonitor(window: Window) {
    val hwnd = peerHwnd(window) ?: return
    val px = monitorPixels(window) ?: return
    runCatching {
        NativeLibrary.getInstance("user32").getFunction("SetWindowPos").invokeInt(
            arrayOf(Pointer(hwnd), Pointer.createConstant(0), px[0], px[1], px[2], px[3], SWP_SHOWWINDOW),
        )
    }
}

internal fun displayBounds(w: Window): Pair<DpSize, WindowPosition> {
    val bounds = w.graphicsConfiguration.bounds
    return DpSize(bounds.width.dp, bounds.height.dp) to WindowPosition(bounds.x.dp, bounds.y.dp)
}

internal fun workArea(w: Window): Pair<DpSize, WindowPosition> {
    val gc = w.graphicsConfiguration
    val b = gc.bounds
    val insets = Toolkit.getDefaultToolkit().getScreenInsets(gc)
    val width = (b.width - insets.left - insets.right).coerceAtLeast(320)
    val height = (b.height - insets.top - insets.bottom).coerceAtLeast(480)
    return DpSize(width.dp, height.dp) to
        WindowPosition((b.x + insets.left).dp, (b.y + insets.top).dp)
}
