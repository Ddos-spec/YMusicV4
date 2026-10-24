package io.ymusic.telemetry

import io.sentry.kotlin.multiplatform.Sentry
import io.sentry.kotlin.multiplatform.log.SentryLogLevel
import kmpcommon.log.Crumb
import kmpcommon.log.CrumbLogger
import kmpcommon.log.Logger
import kotlinx.coroutines.CancellationException
import projectcommon.error.CodedError
import projectcommon.error.NETWORK_CODE
import projectcommon.error.codedError
import projectcommon.log.trailMark

/**
 * The app's root [Logger] over Sentry: an INFO+ [log] is a Sentry Log (attributes = the crumb-tag
 * path + the trail's merged props), every [captureError] a Sentry event (the ignored categories
 * excepted: an INFO log) whose grouping comes from the coded error — else exception
 * type + tag path — applied by [applyYMusicTelemetry]'s `beforeSend` off the tags set here.
 */
class SentryLogger private constructor(crumbs: List<Crumb>) : CrumbLogger(crumbs) {

    constructor() : this(emptyList())

    override fun fork(crumbs: List<Crumb>): CrumbLogger = SentryLogger(crumbs)

    /** The trail's props flattened root→leaf (leaf wins) — every log line carries them as
     *  attributes, which is how one job's lines stay filterable next to a concurrent twin's. */
    private val props: Map<String, Any> by lazy(LazyThreadSafetyMode.NONE) { mergedProps(crumbs) }

    override fun log(level: Logger.Level, message: String, error: Throwable?) {
        // DEBUG stays in the local log file, which ships only with a user's feedback report.
        if (level == Logger.Level.DEBUG) return
        sendLog(level, message, error, path, props)
    }

    private fun sendLog(
        level: Logger.Level,
        message: String,
        error: Throwable?,
        trailPath: String,
        props: Map<String, Any>,
    ) {
        val body = buildString {
            append(message)
            if (error != null) {
                append("\nError: ")
                append(error.stackTraceToString().take(STACK_CAP))
            }
        }
        // Trail + props ride as structured attributes (typed setters), not message prefixes —
        // that is what Sentry Logs can search and filter on.
        Sentry.logger.log(level.toSentryLogLevel()) {
            message(body)
            attributes {
                set(TRAIL_TAG, trailPath)
                for ((k, v) in props) {
                    when (v) {
                        is String -> set(k, v)
                        is Boolean -> set(k, v)
                        is Int -> set(k, v)
                        is Long -> set(k, v)
                        is Float -> set(k, v)
                        is Double -> set(k, v)
                        else -> set(k, v.toString())
                    }
                }
            }
        }
    }

    override fun captureError(error: Throwable, message: String?, prop: Map<String, Any>?) {
        // The deepest trail stamped in flight (Throwable.markTrail), else this catch site's.
        val trail = error.trailMark() ?: crumbs
        val trailPath = trail.joinToString("/") { it.tag }
        val coded = error.codedError()
        if (isIgnored(error, coded)) {
            val text = message ?: error.message ?: error.toString()
            sendLog(Logger.Level.INFO, text, error, trailPath, mergedProps(trail))
            return
        }
        captureTrailedError(error, trail, trailPath, coded?.code, message, prop)
    }

    private fun Logger.Level.toSentryLogLevel(): SentryLogLevel = when (this) {
        Logger.Level.DEBUG -> SentryLogLevel.DEBUG
        Logger.Level.INFO -> SentryLogLevel.INFO
        Logger.Level.WARN -> SentryLogLevel.WARN
        Logger.Level.ERROR -> SentryLogLevel.ERROR
    }

    internal companion object {
        internal const val TRAIL_TAG = "trail"
        internal const val CODE_TAG = "error.code"

        /** Failures that are never defects — the user's connectivity, a cancelled job: the UI already
         *  shows the localized category, so they are INFO logs, never Sentry events. */
        private val IGNORED_CATEGORIES = setOf(NETWORK_CODE)

        private fun mergedProps(crumbs: List<Crumb>): Map<String, Any> {
            val merged = mutableMapOf<String, Any>()
            for (c in crumbs) c.prop?.let(merged::putAll)
            return merged
        }

        private fun isIgnored(error: Throwable, coded: CodedError?): Boolean {
            if (coded?.code in IGNORED_CATEGORIES) return true
            var cause: Throwable? = error
            var depth = 0 // cause chains can be cyclic on some platforms
            while (cause != null && depth++ < 100) {
                if (cause is CancellationException) return true
                cause = cause.cause
            }
            return false
        }

        private const val STACK_CAP = 2000
    }
}

/** Sends [error] as an event: [trailPath] and [code] as the grouping tags, every [trail] hop a
 *  breadcrumb stamped with its own time, [message] and [prop] as extras/contexts/attachments. */
internal expect fun captureTrailedError(
    error: Throwable,
    trail: List<Crumb>,
    trailPath: String,
    code: String?,
    message: String?,
    prop: Map<String, Any>?,
)
