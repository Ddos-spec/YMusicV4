package io.ymusic.telemetry

import io.sentry.kotlin.multiplatform.Sentry
import io.sentry.kotlin.multiplatform.SentryEvent
import io.sentry.kotlin.multiplatform.SentryOptions

/**
 * The Sentry options every app shell shares, applied inside its `Sentry.init` block. No PII
 * scrubbing: sensitive values (cookie values, SAPISIDHASH) are never put into log lines at the
 * source — names only.
 */
fun SentryOptions.applyYMusicTelemetry() {
    maxBreadcrumbs = 200
    // Consent is enforced HERE, not by deinitializing (see bindCrashReporting): everything that
    // would leave the process passes one of these hooks and is dropped while consent says no.
    // A feedback report is its own consent (the Feedback screen turns reports on as it sends), so it
    // passes even before the gate's collector has seen that flip.
    beforeSend = { event ->
        val feedback = event.getTag(REPORT_TYPE_TAG) == REPORT_TYPE_FEEDBACK
        if (crashReportingAllowed || feedback) fingerprintPolicy(event) else null
    }
    // Sentry Logs are OFF by default — without this every Sentry.logger call is dropped. A log is
    // NOT an event: it has its own pipeline and its own beforeSend, so the consent lock repeats.
    logs.enabled = true
    logs.beforeSend = { log -> if (crashReportingAllowed) log else null }
    // Sessions are the one outbound thing WITHOUT a consent hook, so the option itself is the
    // gate: bindCrashReporting sets the flag from the stored answer BEFORE first init, so this
    // reads the real consent. Decided per launch — a mid-session change applies from the next
    // start, which suits a launch-scoped concept.
    enableAutoSessionTracking = crashReportingAllowed
}

/**
 * Grouping for [SentryLogger.captureError] events (async stacks make frame-based grouping
 * meaningless here): a site code (`E#####`) groups alone — one code, one throw site, one issue;
 * a category code ("parse", "oom"; "network" and cancellations are never captured) and a non-coded exception
 * group per trail, so the same failure shape in two subsystems stays two issues. Events with no
 * trail tag (uncaught crashes the SDK captured itself) keep Sentry's default grouping. Each feedback
 * report is its own issue; everything else is tagged [REPORT_TYPE_ERROR] so the two filter apart.
 */
internal fun fingerprintPolicy(event: SentryEvent): SentryEvent {
    if (event.getTag(REPORT_TYPE_TAG) == REPORT_TYPE_FEEDBACK) {
        event.fingerprint = mutableListOf(REPORT_TYPE_FEEDBACK, event.eventId.toString())
        return event
    }
    event.setTag(REPORT_TYPE_TAG, REPORT_TYPE_ERROR)
    // TrailMark rides throws as a SUPPRESSED marker, but sentry-java serializes suppressed
    // exceptions into the visible chain (exception groups) — it is transport, never a report.
    if (event.exceptions.any { it.type == "TrailMark" }) {
        event.exceptions = event.exceptions.filterNot { it.type == "TrailMark" }.toMutableList()
    }
    // An ANR or native crash is reported by the NEXT process, enriched from the scope Sentry persisted
    // before the death — including the trail/code tags of the last event captureError sent. They don't
    // describe this event and would group every such ANR under that unrelated code.
    if (event.exceptions.any { it.type in NEXT_PROCESS_REPORTS }) {
        event.removeTag(SentryLogger.TRAIL_TAG)
        event.removeTag(SentryLogger.CODE_TAG)
        return event
    }
    val code = event.getTag(SentryLogger.CODE_TAG)
    val trail = event.getTag(SentryLogger.TRAIL_TAG)
    if (code != null) {
        // A site code (E#####) IS one throw site — it groups alone. Category codes ("parse",
        // "oom") are shared by every subsystem, so they group per trail: a search parse failure
        // and a download parse failure are different issues.
        event.fingerprint =
            if (SITE_CODE.matches(code) || trail == null) mutableListOf(code) else mutableListOf(code, trail)
    } else if (trail != null) {
        event.fingerprint = mutableListOf(event.exceptions.lastOrNull()?.type ?: "error", trail)
    }
    return event
}

private const val DEVICE_ID_TAG = "device_id"

private val SITE_CODE = Regex("""E\d{5}""")

private val NEXT_PROCESS_REPORTS = setOf("ApplicationNotResponding", "SIGSEGV", "SIGABRT", "SIGBUS", "SIGILL", "SIGFPE", "SIGTRAP")

/** Attach the anonymous stable device id to every event this process sends (crash grouping across
 *  sessions, "how many devices" triage). No-op until Sentry.init ran. */
fun installSentryDeviceId(id: String) {
    Sentry.configureScope { scope ->
        scope.setContext("DEVICE_ID", id)
        // A tag too: contexts aren't searchable, and a user quotes this id on GitHub or by email.
        scope.setTag(DEVICE_ID_TAG, id)
    }
}

/** Attach the RUNNING engine-js bundle's self-declared revision to every event — set at start and
 *  again on every remote swap (the manager's onBackendVersion callback), so a report always names
 *  the bundle that was live. A tag, not a context: tags are indexed (searchable, and issues show
 *  their value distribution), a context is display-only. */
fun installSentryEngineVersion(version: Int) {
    Sentry.configureScope { scope -> scope.setTag("engine.version", version.toString()) }
}

/** The player.js revision of the most recent player session, as the `player.version` tag — set each
 *  time a session's bundle passes its health check, so playback reports name the player that ran. */
fun installSentryPlayerVersion(version: Int) {
    Sentry.configureScope { scope -> scope.setTag("player.version", version.toString()) }
}
