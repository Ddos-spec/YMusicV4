package io.ymusic.telemetry

import io.sentry.SentryEvent
import io.sentry.protocol.SentryException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TelemetryOptionsTest {

    private fun exception(type: String, value: String? = null) = SentryException().also {
        it.type = type
        it.value = value
    }

    @Test
    fun coded_error_groups_by_its_code_alone() {
        val event = SentryEvent()
        event.setTag(SentryLogger.CODE_TAG, "E20011")
        event.setTag(SentryLogger.TRAIL_TAG, "Discover/fetchHomeFeed/http")
        assertEquals(listOf("E20011"), fingerprintPolicy(event).fingerprints?.toList())
    }

    @Test
    fun non_coded_capture_groups_by_type_plus_trail() {
        val event = SentryEvent()
        event.setTag(SentryLogger.TRAIL_TAG, "Discover/fetchHomeFeed/http")
        event.exceptions = listOf(exception("SocketTimeoutException"))
        assertEquals(
            listOf("SocketTimeoutException", "Discover/fetchHomeFeed/http"),
            fingerprintPolicy(event).fingerprints?.toList(),
        )
    }

    @Test
    fun uncaught_crash_keeps_default_grouping() {
        val event = SentryEvent()
        event.exceptions = listOf(exception("NullPointerException"))
        assertTrue(fingerprintPolicy(event).fingerprints.isNullOrEmpty())
    }

    @Test
    fun trail_marks_are_stripped_from_the_exception_chain() {
        val event = SentryEvent()
        event.exceptions = listOf(
            exception("TechnicalException", "[E20011] boom"),
            exception("TrailMark", "Discover/task/http"),
        )
        assertEquals(listOf("TechnicalException"), fingerprintPolicy(event).exceptions?.map { it.type })
    }

    @Test
    fun category_code_groups_per_trail_site_code_groups_alone() {
        val network = SentryEvent()
        network.setTag(SentryLogger.CODE_TAG, "network")
        network.setTag(SentryLogger.TRAIL_TAG, "Search/search/js:search/http")
        assertEquals(listOf("network", "Search/search/js:search/http"), fingerprintPolicy(network).fingerprints?.toList())

        val site = SentryEvent()
        site.setTag(SentryLogger.CODE_TAG, "E20011")
        site.setTag(SentryLogger.TRAIL_TAG, "Search/search/js:search/http")
        assertEquals(listOf("E20011"), fingerprintPolicy(site).fingerprints?.toList())
    }
}
