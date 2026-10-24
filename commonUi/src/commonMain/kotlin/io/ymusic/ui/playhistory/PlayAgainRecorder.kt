package io.ymusic.ui.playhistory

import io.ymusic.player.PlayableItem
import io.ymusic.player.Player
import io.ymusic.player.QueueState
import io.ymusic.player.isPlaying
import kmpcommon.log.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * Keeps [store] in step with the player: every queue (one [QueueState.queueId]) is a "Play again"
 * session, written when it starts, when its items or current item change, when playback pauses, every
 * [PROGRESS_EVERY] while playing, and one last time when another queue replaces it. Items [isTemporary]
 * picks are left out of the saved queue, and a queue of only those is not saved at all.
 * Exactly one recorder per player — [start] it from the composition root, not per screen.
 */
class PlayAgainRecorder(
    private val player: Player,
    private val store: PlayAgainStore,
    private val log: Logger?,
    private val isTemporary: (PlayableItem) -> Boolean = { false },
) {
    private class Snapshot(val queue: QueueState, val session: PlayAgainSession, val playing: Boolean)

    private var savedQueueId: String? = null
    private var savedItems: List<PlayableItem>? = null
    private var lastWritten: PlayAgainSession? = null
    private var lastWriteAt = TimeSource.Monotonic.markNow()

    fun start(scope: CoroutineScope) {
        scope.launch {
            var current: Snapshot? = null
            try {
                combine(player.queue, player.progress, player.status) { playerQueue, progress, status ->
                    val queue = playerQueue.persistable() ?: return@combine null
                    val item = queue.currentItem ?: return@combine null
                    val live = progress.isLive || (item as? PlayableItem.YtMedia)?.live == true
                    val stripStart = PlayAgainSession.stripStart(queue.currentIndex, queue.items.size)
                    Snapshot(
                        queue,
                        PlayAgainSession(
                            queueId = queue.queueId ?: return@combine null,
                            title = item.displayTitle,
                            subtitle = item.displaySubtitle,
                            thumbnailUrl = item.displayThumbnailUrl,
                            queueIndex = queue.currentIndex,
                            queueSize = queue.items.size,
                            positionMs = if (live) 0L else progress.positionMs.coerceAtLeast(0L),
                            durationMs = if (live) 0L else progress.durationMs.takeIf { it > 0 } ?: item.durationMs ?: 0L,
                            stripStart = stripStart,
                            stripThumbs = queue.items
                                .subList(stripStart, (stripStart + PlayAgainSession.STRIP_SIZE).coerceAtMost(queue.items.size))
                                .map { it.displayThumbnailUrl },
                            sourceToken = queue.more?.resumeToken,
                        ),
                        isPlaying(status, queue),
                    )
                }.collect { next ->
                    val prev = current
                    current = next
                    when {
                        next?.session?.queueId != prev?.session?.queueId -> {
                            if (prev != null) write(prev)
                            if (next != null) write(next.atItemStart())
                        }
                        next == null || prev == null -> Unit
                        next.queue.currentItem != prev.queue.currentItem -> write(next.atItemStart())
                        next.queue.items != prev.queue.items || next.queue.currentIndex != prev.queue.currentIndex ->
                            write(next)
                        prev.playing && !next.playing -> write(next)
                        next.playing && lastWriteAt.elapsedNow() >= PROGRESS_EVERY -> write(next)
                    }
                }
            } finally {
                current?.let { withContext(NonCancellable) { write(it) } }
            }
        }
    }

    private fun QueueState.persistable(): QueueState? {
        if (currentIndex < 0 || items.none(isTemporary)) return this
        val kept = items.filterNot(isTemporary)
        if (kept.isEmpty()) return null
        val keptBefore = items.subList(0, currentIndex).count { !isTemporary(it) }
        return copy(items = kept, currentIndex = keptBefore.coerceAtMost(kept.lastIndex))
    }

    /** A track change: the progress flow may still carry the previous item's play-head. */
    private fun Snapshot.atItemStart() =
        Snapshot(queue, session.copy(positionMs = 0L, durationMs = queue.currentItem?.durationMs ?: 0L), playing)

    private suspend fun write(snapshot: Snapshot) {
        val session = snapshot.session
        val last = lastWritten
        if (last != null && last.copy(positionMs = session.positionMs) == session &&
            abs(last.positionMs - session.positionMs) < MIN_POSITION_DELTA_MS &&
            session.queueId == savedQueueId && snapshot.queue.items === savedItems
        ) {
            return
        }
        try {
            val itemsSaved = session.queueId == savedQueueId && snapshot.queue.items == savedItems
            if (!itemsSaved || !store.saveProgress(session)) {
                store.save(session, snapshot.queue.items)
            }
            savedQueueId = session.queueId
            savedItems = snapshot.queue.items
            lastWritten = session
            lastWriteAt = TimeSource.Monotonic.markNow()
        } catch (c: CancellationException) {
            throw c
        } catch (e: Exception) {
            // A missed save only loses this play-head; the next trigger writes the session again.
            log?.log(Logger.Level.WARN, "play-again save failed", e)
        }
    }

    companion object {
        val PROGRESS_EVERY = 30.seconds
        private const val MIN_POSITION_DELTA_MS = 3_000L
    }
}
