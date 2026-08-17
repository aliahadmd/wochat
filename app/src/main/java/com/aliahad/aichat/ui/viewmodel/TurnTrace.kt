package com.aliahad.aichat.ui.viewmodel

import android.util.Log
import kotlin.time.TimeSource

/**
 * Per-phase latency attribution for one chat turn.
 *
 * Exists because reading the send path could not answer where its seconds go:
 * plan 037 needed the ~5 s before the first token split into prompt planning,
 * persistence, native restore and prefill, and only a measurement can do that.
 * The native half is already timed by `aichat_jni.cpp` under the `AIchatNative`
 * tag; this covers the JVM half so the two together account for the whole turn.
 *
 * Records durations and counts only — never message text, memory content or
 * prompts — so it is safe to leave enabled in a release build. Read it with
 * `adb logcat -s AIchatTurn:I AIchatNative:I`.
 */
internal class TurnTrace(private val label: String) {
    private val start = TimeSource.Monotonic.markNow()
    private var phaseStart = start
    private var logged = false
    private val phases = mutableListOf<Pair<String, Long>>()

    /** Closes the phase that began at the previous mark. */
    fun mark(phase: String) {
        val now = TimeSource.Monotonic.markNow()
        phases += phase to (now - phaseStart).inWholeMilliseconds
        phaseStart = now
    }

    /**
     * Closes the trace at the first streamed delta, which is the moment plan 037
     * is about. Idempotent, so callers can invoke it from every delta branch
     * without tracking whether the first one already arrived.
     */
    fun firstToken() {
        if (logged) return
        logged = true
        mark("first-token")
        val total = start.elapsedNow().inWholeMilliseconds
        Log.i(TAG, "$label total=${total}ms " + phases.joinToString(" ") { "${it.first}=${it.second}ms" })
    }

    private companion object {
        const val TAG = "AIchatTurn"
    }
}
