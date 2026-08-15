package com.aliahad.aichat.benchmark

import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Cold, warm and hot startup timing.
 *
 * This exists so startup claims stop being anecdotes. Plan 016's improvement
 * was originally reported from `am start -W` samples, which turned out to mix
 * cold and warm launches because the residency foreground service resurrects
 * the process — the first reading was wrong by a factor of two in both
 * directions before it was pinned down.
 *
 * [CompilationMode.Partial] is the shipping configuration: it exercises the
 * baseline profile from plan 022. [CompilationMode.None] is included so the
 * profile's contribution can be seen rather than assumed.
 */
@RunWith(AndroidJUnit4::class)
class StartupBenchmark {

    @get:Rule
    val benchmarkRule = MacrobenchmarkRule()

    @Test
    fun startupColdWithProfile() = measure(StartupMode.COLD, CompilationMode.Partial())

    @Test
    fun startupColdWithoutProfile() = measure(StartupMode.COLD, CompilationMode.None())

    @Test
    fun startupWarm() = measure(StartupMode.WARM, CompilationMode.Partial())

    @Test
    fun startupHot() = measure(StartupMode.HOT, CompilationMode.Partial())

    private fun measure(startupMode: StartupMode, compilationMode: CompilationMode) =
        benchmarkRule.measureRepeated(
            packageName = PACKAGE_NAME,
            metrics = listOf(StartupTimingMetric()),
            compilationMode = compilationMode,
            startupMode = startupMode,
            iterations = ITERATIONS,
            setupBlock = { pressHome() },
        ) {
            startActivityAndWait()
            // The UI is gated on the container warm-up, so "displayed" is not
            // the same as "usable". Wait for real content before stopping.
            device.wait(Until.hasObject(By.pkg(PACKAGE_NAME).depth(0)), UI_TIMEOUT_MILLIS)
        }

    private companion object {
        const val PACKAGE_NAME = "com.aliahad.aichat"
        const val ITERATIONS = 10
        const val UI_TIMEOUT_MILLIS = 15_000L
    }
}
