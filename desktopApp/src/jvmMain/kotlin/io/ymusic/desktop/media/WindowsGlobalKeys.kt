package io.ymusic.desktop.media

import com.sun.jna.NativeLibrary
import com.sun.jna.Pointer
import com.sun.jna.Structure
import com.sun.jna.win32.StdCallLibrary
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Space is never swallowed. Ctrl+Alt+Space toggles play from any app.
 * Headset media keys are taken here only when SMTC did not bind. If both handled them, one press
 * would pause and immediately resume.
 */
internal class WindowsGlobalKeys(
    private val onCommand: (Int) -> Unit,
    private val smtcOwnsMediaKeys: () -> Boolean,
) {
    private val alive = AtomicBoolean(true)
    private var hook: Pointer? = null
    private var proc: Hook? = null
    private var thread: Thread? = null

    @Volatile private var threadId = 0

    fun start() {
        val user32 = NativeLibrary.getInstance("user32")
        val setHook = user32.getFunction("SetWindowsHookExW")
        val nextHook = user32.getFunction("CallNextHookEx")
        val unhook = user32.getFunction("UnhookWindowsHookEx")
        val getMsg = user32.getFunction("GetMessageW")
        val translate = user32.getFunction("TranslateMessage")
        val dispatch = user32.getFunction("DispatchMessageW")
        val asyncKey = user32.getFunction("GetAsyncKeyState")
        val postThread = user32.getFunction("PostThreadMessageW")
        val kernel32 = NativeLibrary.getInstance("kernel32")
        val threadIdFn = kernel32.getFunction("GetCurrentThreadId")
        thread = Thread({
            threadId = threadIdFn.invokeInt(arrayOf())
            postQuit = {
                val id = threadId
                if (id != 0) postThread.invokeInt(arrayOf(id, WM_QUIT, 0, 0))
            }
            proc = Hook { nCode, wParam, lParam ->
                if (nCode >= 0 && wParam.toInt() == WM_KEYDOWN && lParam != null) {
                    val key = Kbd(lParam)
                    key.read()
                    val ctrl = (asyncKey.invokeInt(arrayOf(VK_CONTROL)) and 0x8000) != 0
                    val alt = (key.flags and LLKHF_ALTDOWN) != 0 ||
                        (asyncKey.invokeInt(arrayOf(VK_MENU)) and 0x8000) != 0
                    if (key.vkCode == VK_SPACE && ctrl && alt) {
                        onCommand(CMD_TOGGLE)
                        return@Hook 1L
                    }
                    if (!smtcOwnsMediaKeys()) {
                        val cmd = when (key.vkCode) {
                            VK_MEDIA_PLAY_PAUSE -> CMD_TOGGLE
                            VK_MEDIA_NEXT -> CMD_NEXT
                            VK_MEDIA_PREV -> CMD_PREV
                            else -> 0
                        }
                        if (cmd != 0) {
                            onCommand(cmd)
                            return@Hook 1L
                        }
                    }
                }
                nextHook.invokeLong(arrayOf(hook, nCode, wParam, lParam))
            }
            hook = setHook.invokePointer(arrayOf(WH_KEYBOARD_LL, proc, Pointer.NULL, 0))
            if (hook == null) return@Thread
            val msg = Msg()
            while (alive.get()) {
                val rc = getMsg.invokeInt(arrayOf(msg, Pointer.NULL, 0, 0))
                if (rc == 0 || rc == -1) break
                translate.invokeInt(arrayOf(msg))
                dispatch.invokeLong(arrayOf(msg))
            }
            hook?.let { unhook.invokeInt(arrayOf(it)) }
            hook = null
        }, "ymusic-keys")
        thread?.isDaemon = true
        thread?.start()
    }

    @Volatile private var postQuit: () -> Unit = {}

    fun stop() {
        alive.set(false)
        runCatching { postQuit() }
    }

    private fun interface Hook : StdCallLibrary.StdCallCallback {
        fun callback(nCode: Int, wParam: Long, lParam: Pointer?): Long
    }

    private class Kbd(p: Pointer) : Structure(p) {
        @JvmField var vkCode = 0

        @JvmField var scanCode = 0

        @JvmField var flags = 0

        @JvmField var time = 0

        @JvmField var extra = 0L
        override fun getFieldOrder() = listOf("vkCode", "scanCode", "flags", "time", "extra")
    }

    private class Msg : Structure() {
        @JvmField var hwnd: Pointer? = null

        @JvmField var message = 0

        @JvmField var wParam = 0L

        @JvmField var lParam = 0L

        @JvmField var time = 0

        @JvmField var x = 0

        @JvmField var y = 0
        override fun getFieldOrder() = listOf("hwnd", "message", "wParam", "lParam", "time", "x", "y")
    }

    private companion object {
        const val WH_KEYBOARD_LL = 13
        const val WM_KEYDOWN = 0x0100
        const val WM_QUIT = 0x0012
        const val VK_SPACE = 0x20
        const val VK_CONTROL = 0x11
        const val VK_MENU = 0x12
        const val VK_MEDIA_NEXT = 0xB0
        const val VK_MEDIA_PREV = 0xB1
        const val VK_MEDIA_PLAY_PAUSE = 0xB3
        const val LLKHF_ALTDOWN = 0x20
        const val CMD_TOGGLE = 5
        const val CMD_NEXT = 3
        const val CMD_PREV = 4
    }
}
