package com.aliahad.aichat.residency

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class InferenceUseCounterTest {
    @Test
    fun cancellationDuringBeginJoinWindowRollsBackTheIncrement() = runTest {
        val counter = InferenceUseCounter()
        val release = CompletableDeferred<Unit>()
        // A verification job that ignores cancellation until released, keeping the
        // begin() join window open long enough for the caller to be cancelled.
        val verification = launch {
            try {
                awaitCancellation()
            } finally {
                withContext(NonCancellable) { release.await() }
            }
        }
        var observedCancellation = false
        val beginner = launch {
            try {
                counter.begin {
                    verification.cancel()
                    verification.cancelAndJoin()
                }
            } catch (cancelled: CancellationException) {
                observedCancellation = true
                throw cancelled
            }
        }
        yield()
        assertEquals(1, counter.count)
        beginner.cancelAndJoin()
        assertEquals(0, counter.count)
        assertTrue(observedCancellation)
        release.complete(Unit)
        verification.join()
    }

    @Test
    fun beginThenEndLeavesCounterAtZero() = runTest {
        val counter = InferenceUseCounter()
        var joined = false
        counter.begin { joined = true }
        assertEquals(1, counter.count)
        counter.end()
        assertEquals(0, counter.count)
        assertTrue(joined)
    }

    @Test
    fun endNeverDropsBelowZero() = runTest {
        val counter = InferenceUseCounter()
        counter.end()
        counter.end()
        assertEquals(0, counter.count)
    }
}
