package com.aliahad.aichat.actions

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Reading and checking what the model asked for.
 *
 * Separate from [DeviceActions] because this is the part worth testing without a
 * device, and the part most likely to be wrong: a 4B model writing JSON under a
 * grammar still chooses the *values*, and "quarter past midnight" reaching the
 * clock as hour 25 should be a sentence rather than a crash.
 */
internal object ActionArguments {

    fun read(arguments: String): JsonObject? =
        runCatching { Json.parseToJsonElement(arguments) as? JsonObject }.getOrNull()

    fun JsonObject.boolean(key: String): Boolean? =
        get(key)?.jsonPrimitive?.content?.toBooleanStrictOrNull()

    fun JsonObject.int(key: String): Int? =
        get(key)?.jsonPrimitive?.content?.trim()?.toIntOrNull()

    fun JsonObject.text(key: String): String? =
        get(key)?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
}

/**
 * A time of day the clock will accept, or null.
 *
 * Rejects rather than clamps. Clamping 25 to 23 would set an alarm an hour before
 * the one asked for and say nothing, which is worse than admitting confusion.
 */
internal fun validTimeOfDay(hour: Int?, minute: Int?): Pair<Int, Int>? {
    if (hour == null || hour !in 0..23) return null
    val minutes = minute ?: 0
    if (minutes !in 0..59) return null
    return hour to minutes
}

/**
 * How the time is read back to the user, deliberately in 12-hour form.
 *
 * The model converts "three in the afternoon" into an hour, and the way that goes
 * wrong is 3 instead of 15 — a mistake invisible if the confirmation echoes "15:00"
 * or just says "done". "3:00 PM" is wrong in a way somebody notices.
 */
internal fun spokenTime(hour: Int, minute: Int): String {
    val suffix = if (hour < 12) "AM" else "PM"
    val display = when {
        hour % 12 == 0 -> 12
        else -> hour % 12
    }
    return "%d:%02d %s".format(display, minute, suffix)
}

/** A timer length the clock will accept, in seconds, or null. */
internal fun validTimerSeconds(seconds: Int?): Int? {
    if (seconds == null || seconds <= 0) return null
    // A day is past the point where a timer is the right tool, and a value this
    // large is far more likely to be a unit mix-up than a real request.
    if (seconds > MAX_TIMER_SECONDS) return null
    return seconds
}

/** Reads a duration back as the user would say it. */
internal fun spokenDuration(seconds: Int): String {
    val hours = seconds / 3600
    val minutes = (seconds % 3600) / 60
    val rest = seconds % 60
    val parts = buildList {
        if (hours > 0) add(if (hours == 1) "1 hour" else "$hours hours")
        if (minutes > 0) add(if (minutes == 1) "1 minute" else "$minutes minutes")
        if (rest > 0) add(if (rest == 1) "1 second" else "$rest seconds")
    }
    return if (parts.isEmpty()) "0 seconds" else parts.joinToString(" ")
}

internal const val MAX_TIMER_SECONDS = 24 * 60 * 60
