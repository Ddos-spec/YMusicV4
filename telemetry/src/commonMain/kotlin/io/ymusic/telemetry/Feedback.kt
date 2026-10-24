package io.ymusic.telemetry

import io.sentry.kotlin.multiplatform.Attachment
import io.sentry.kotlin.multiplatform.Sentry
import io.sentry.kotlin.multiplatform.protocol.SentryId
import io.sentry.kotlin.multiplatform.protocol.UserFeedback

/**
 * Sends the app logs as one anonymous feedback event and returns its event id, which
 * [sendFeedbackMessage] links to; a user quoting their Device ID finds it by the device_id tag. No
 * user feedback item rides it, so it stays out of the feedback inbox; every report is its own issue.
 * Null until Sentry.init ran (the SDK hands back the empty id).
 */
fun captureFeedbackReport(attachments: List<Attachment>): String? {
    val eventId = Sentry.captureMessage(FEEDBACK_REPORT_MESSAGE) { scope ->
        scope.setTag(REPORT_TYPE_TAG, REPORT_TYPE_FEEDBACK)
        attachments.forEach(scope::addAttachment)
    }.toString()
    return eventId.takeUnless { it.all { char -> char == '0' || char == '-' } }
}

/** A message written in the app, linked to the report [reportId] so it lands in the feedback inbox with its logs. */
fun sendFeedbackMessage(reportId: String, message: String) {
    Sentry.captureUserFeedback(UserFeedback(SentryId(reportId)).also { it.comments = message })
}

private const val FEEDBACK_REPORT_MESSAGE = "Feedback: contact report"

internal const val REPORT_TYPE_TAG = "report_type"
internal const val REPORT_TYPE_FEEDBACK = "feedback"
internal const val REPORT_TYPE_ERROR = "error"
