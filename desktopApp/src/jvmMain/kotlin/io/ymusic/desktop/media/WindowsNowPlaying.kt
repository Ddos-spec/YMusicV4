package io.ymusic.desktop.media

import kmpcommon.log.Logger
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Publishes the track into the Windows volume flyout and takes headset / keyboard media keys
 * from SMTC, including while another app is in front. Ctrl+Alt+Space is the global play key
 * that does not steal Space from typing.
 */
internal class WindowsNowPlayingSession(
    private val commands: MediaSessionCommands,
    private val log: Logger? = null,
) : DesktopMediaSession {
    private val stop = AtomicBoolean(false)
    private val keys = WindowsGlobalKeys(
        onCommand = { cmd -> dispatch(cmd) },
        smtcOwnsMediaKeys = { WinSmtc.ownsTransport },
    )
    private val thread = Thread({
        while (!stop.get()) {
            dispatch(runCatching { WinSmtc.poll() }.getOrDefault(0))
            try {
                Thread.sleep(40)
            } catch (_: InterruptedException) {
                break
            }
        }
    }, "ymusic-smtc").also { it.isDaemon = true }

    init {
        keys.start()
        thread.start()
    }

    override fun update(state: MediaSessionState) {
        if (!WinSmtc.ownsTransport) return
        runCatching {
            WinSmtc.update(
                state.title.orEmpty(),
                state.artist.orEmpty(),
                state.playing,
                state.hasItem,
            )
        }.onFailure { log?.log(Logger.Level.WARN, "smtc update failed", it) }
    }

    override fun release() {
        stop.set(true)
        thread.interrupt()
        keys.stop()
        runCatching { WinSmtc.release() }
        WinSmtc.ownsTransport = false
    }

    private fun dispatch(cmd: Int) {
        when (cmd) {
            1 -> commands.play()
            2 -> commands.pause()
            3 -> commands.next()
            4 -> commands.previous()
            5 -> commands.togglePlay()
        }
    }
}
