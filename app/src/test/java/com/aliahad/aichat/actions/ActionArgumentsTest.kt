package com.aliahad.aichat.actions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The grammar guarantees the *shape* of a tool call, never the values. A 4B model
 * still chooses the numbers, so these are the cases where a plausible-looking call
 * would otherwise become a real-world mistake.
 */
class ActionArgumentsTest {

    @Test
    fun `a normal time is accepted`() {
        assertEquals(15 to 30, validTimeOfDay(15, 30))
    }

    @Test
    fun `a missing minute means on the hour`() {
        assertEquals(7 to 0, validTimeOfDay(7, null))
    }

    @Test
    fun `an impossible hour is refused rather than clamped`() {
        // Clamping 25 to 23 would set an alarm two hours early and say nothing.
        // Refusing produces a sentence the user can correct.
        assertNull(validTimeOfDay(25, 0))
        assertNull(validTimeOfDay(-1, 0))
        assertNull(validTimeOfDay(null, 30))
    }

    @Test
    fun `an impossible minute is refused`() {
        assertNull(validTimeOfDay(10, 60))
        assertNull(validTimeOfDay(10, -5))
    }

    @Test
    fun `the confirmation says AM or PM, because that is the mistake worth catching`() {
        // "Three in the afternoon" reaching the clock as 3 AM is the failure mode.
        // Echoing "15:00" or "done" would hide it; this does not.
        assertEquals("3:00 PM", spokenTime(15, 0))
        assertEquals("3:00 AM", spokenTime(3, 0))
    }

    @Test
    fun `midnight and noon read the way people say them`() {
        assertEquals("12:00 AM", spokenTime(0, 0))
        assertEquals("12:30 PM", spokenTime(12, 30))
    }

    @Test
    fun `a sensible timer length is accepted`() {
        assertEquals(600, validTimerSeconds(600))
    }

    @Test
    fun `a zero or negative timer is refused`() {
        assertNull(validTimerSeconds(0))
        assertNull(validTimerSeconds(-30))
        assertNull(validTimerSeconds(null))
    }

    @Test
    fun `a timer longer than a day is refused as a likely unit mix-up`() {
        assertEquals(MAX_TIMER_SECONDS, validTimerSeconds(MAX_TIMER_SECONDS))
        // 10 minutes sent as milliseconds looks exactly like this.
        assertNull(validTimerSeconds(600_000))
    }

    @Test
    fun `durations read back the way they were asked for`() {
        assertEquals("10 minutes", spokenDuration(600))
        assertEquals("1 minute", spokenDuration(60))
        assertEquals("1 hour 30 minutes", spokenDuration(5400))
        assertEquals("45 seconds", spokenDuration(45))
    }
}

/**
 * Dial and calendar arguments. Nothing here places a call or writes an event —
 * both open a composer the user confirms — so these checks exist to keep obvious
 * nonsense off the screen rather than to prevent harm.
 */
class DialAndCalendarArgumentsTest {

    @Test
    fun `ordinary numbers survive their punctuation`() {
        assertEquals("+44 20 7946 0958", validPhoneNumber("+44 20 7946 0958"))
        assertEquals("(555) 123-4567", validPhoneNumber("(555) 123-4567"))
        assertEquals("07700900123", validPhoneNumber("  07700900123  "))
    }

    @Test
    fun `a name is not a number`() {
        // The failure worth catching: the model passing who to call instead of what
        // to dial, which would otherwise put "Mum" into the dialer.
        assertNull(validPhoneNumber("Mum"))
        assertNull(validPhoneNumber("call the office"))
    }

    @Test
    fun `punctuation alone is not a number`() {
        assertNull(validPhoneNumber("()-"))
        assertNull(validPhoneNumber("12"))
        assertNull(validPhoneNumber(""))
        assertNull(validPhoneNumber(null))
    }

    @Test
    fun `days ahead default to today and are bounded`() {
        assertEquals(0, validDaysAhead(null))
        assertEquals(1, validDaysAhead(1))
        assertEquals(MAX_DAYS_AHEAD, validDaysAhead(MAX_DAYS_AHEAD))
        assertNull(validDaysAhead(-1))
        assertNull(validDaysAhead(MAX_DAYS_AHEAD + 1))
    }

    @Test
    fun `event length defaults to an hour and is bounded`() {
        assertEquals(DEFAULT_EVENT_MINUTES, validDurationMinutes(null))
        assertEquals(30, validDurationMinutes(30))
        assertNull(validDurationMinutes(0))
        // Minutes sent as seconds looks exactly like this.
        assertNull(validDurationMinutes(3600))
    }
}
