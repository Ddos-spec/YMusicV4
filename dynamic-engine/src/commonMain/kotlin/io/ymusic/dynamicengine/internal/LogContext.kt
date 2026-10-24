package io.ymusic.dynamicengine.internal

import kmpcommon.log.Crumb
import kmpcommon.log.Logger
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import projectcommon.json.arrayOrNull
import projectcommon.json.intOrNull
import projectcommon.json.objectOrNull
import projectcommon.json.stringOrNull
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * The caller's logger for one host-protocol serve: the head's `log` field —
 * `{"key": <parked>, "trail": [["tag", {prop}|null?, enteredAtMillis?], …]}` — resolves the parked Kotlin caller (absent →
 * the engine root), gets the JS-side trail grafted on by value, and this protocol's [tag] hop
 * appended.
 */
internal fun protocolLogger(
    parked: ParkedLoggers,
    head: JsonObject,
    tag: String,
    prop: Map<String, Any>? = null,
): Logger? {
    val wire = head.objectOrNull("log")
    // No key on the wire (fire-and-forget JS jobs) → the engine root.
    val key = wire?.intOrNull("key")
    val caller = if (key == null) parked.root else parked.resolve(key)
    // The JS leg of the trail already opens with the bundle's revision (js:<version>/js:<job>/…) —
    // the bundle bakes its own version in, this side just grafts the trail as sent.
    return caller?.forkAll(readTrail(wire))?.fork(tag, prop)
}

/** A head's string field as a one-entry prop map, the query string stripped (PII rule: never log
 *  query parameters). Absent/malformed → null, never a throw — props must not break the serve. */
internal fun JsonObject.propOf(field: String): Map<String, Any>? =
    stringOrNull(field)?.substringBefore('?')?.let { mapOf(field to it) }

@OptIn(ExperimentalTime::class)
private fun readTrail(wire: JsonObject?): List<Crumb> {
    val trail = wire?.arrayOrNull("trail") ?: return emptyList()
    val graftedAt = Clock.System.now().toEpochMilliseconds()
    return trail.mapNotNull { entry ->
        val arr = entry as? JsonArray ?: return@mapNotNull null
        val tag = (arr.getOrNull(0) as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
        val prop = (arr.getOrNull(1) as? JsonObject)
            ?.mapNotNull { (k, v) -> (v as? JsonPrimitive)?.toPropValue()?.let { k to it } }
            ?.toMap()
        // Bundles before engine 85 / player 96 send no time; a time past the graft is a skewed JS clock.
        val enteredAt = (arr.getOrNull(2) as? JsonPrimitive)?.longOrNull?.takeIf { it in 1..graftedAt } ?: graftedAt
        Crumb(tag, prop?.takeIf { it.isNotEmpty() }, enteredAt)
    }
}

private fun JsonPrimitive.toPropValue(): Any? =
    if (isString) content else booleanOrNull ?: longOrNull ?: doubleOrNull ?: contentOrNull
