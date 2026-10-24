package kmpcommon.log

import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * One scope hop in a logger's trail. [tag] is a stable, code-location-like name (`fetch`, `http`)
 * a sink can group by; anything variable (ids, urls, params) goes in [prop], whose values should be
 * JSON primitives (String/Number/Boolean) so any sink can serialize them. [atMillis] is when the hop
 * was entered (epoch ms).
 */
data class Crumb(
    val tag: String,
    val prop: Map<String, Any>? = null,
    val atMillis: Long = nowMillis(),
)

@OptIn(ExperimentalTime::class)
private fun nowMillis(): Long = Clock.System.now().toEpochMilliseconds()

/**
 * An immutable scope-trail logger the host application wires to its sink. Library code logs
 * through it; a `null` `Logger` means logging is off.
 *
 * [fork] returns a new logger carrying this trail plus one hop — a *logical async stack*: each
 * layer forks its own hop and hands the child down, so a log deep in the call graph carries the
 * caller's context. Forking emits nothing; only [log] does.
 *
 * [log] records a line, with the real [Throwable] when there is one. [captureError] reports a
 * failure the caller could not recover from; the default just logs it at ERROR.
 */
interface Logger {
    /** The scope trail this logger carries (root → leaf). */
    val trail: List<Crumb>

    fun fork(tag: String, prop: Map<String, Any>? = null): Logger

    fun forkAll(crumbs: List<Crumb>): Logger

    fun log(level: Level, message: String, error: Throwable? = null)

    fun captureError(error: Throwable, message: String? = null, prop: Map<String, Any>? = null) {
        log(Level.ERROR, message ?: error.message ?: error.toString(), error)
    }

    enum class Level { DEBUG, INFO, WARN, ERROR }
}

@Suppress("NOTHING_TO_INLINE")
inline fun Logger.info(message: String, error: Throwable? = null) = log(Logger.Level.INFO, message, error)

/** The trail bookkeeping every [Logger] shares: implementations supply [fork] (rebuild with a new
 *  crumb list) and the output side; [path] renders the trail for display/grouping. */
abstract class CrumbLogger protected constructor(
    protected val crumbs: List<Crumb>,
) : Logger {
    final override val trail: List<Crumb> get() = crumbs

    protected abstract fun fork(crumbs: List<Crumb>): CrumbLogger

    final override fun fork(tag: String, prop: Map<String, Any>?): Logger = fork(crumbs + Crumb(tag, prop))

    final override fun forkAll(crumbs: List<Crumb>): Logger =
        if (crumbs.isEmpty()) this else fork(this.crumbs + crumbs)

    protected val path: String by lazy { crumbs.joinToString("/") { it.tag } }
}

/**
 * A DEBUG line whose message is built ONLY outside production builds — the form for chatty paths
 * (per-request lines, per-part transfer chatter). [BuildFlags.PRODUCTION] is a `const val` baked
 * by the build (`-Pproduction`, default false): it inlines as a literal into every call site
 * and the compiler drops the dead branch, so a production build carries none of these sites —
 * removed, not silently dropped — on every platform, no optimizer needed.
 */
inline fun Logger.debug(message: () -> String) {
    if (!BuildFlags.PRODUCTION) log(Logger.Level.DEBUG, message())
}
