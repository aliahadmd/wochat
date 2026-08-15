package com.aliahad.aichat.benchmark

import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Frame timing while scrolling the message list and the settings list.
 *
 * This is the scoreboard plans 024 and 025 are measured against — each claims
 * a smoothness win that cannot be shown without P50/P99 frame numbers.
 *
 * Honest limitation, stated so nobody reads more into these numbers than they
 * carry: **this does not measure streaming.** Real streaming needs a
 * downloaded 4.8 GB model and on-device inference, which is not something a
 * benchmark should drive. A conversation must already exist on the device for
 * the message-list case to mean anything — with an empty list this measures an
 * empty screen. See `benchmark/README.md` for the streaming protocol.
 */
@RunWith(AndroidJUnit4::class)
class ScrollJankBenchmark {

    @get:Rule
    val benchmarkRule = MacrobenchmarkRule()

    @Test
    fun scrollMessageList() = benchmarkRule.measureRepeated(
        packageName = PACKAGE_NAME,
        metrics = listOf(FrameTimingMetric()),
        compilationMode = CompilationMode.Partial(),
        startupMode = StartupMode.WARM,
        iterations = ITERATIONS,
        setupBlock = {
            pressHome()
            startActivityAndWait()
        },
    ) {
        device.wait(Until.hasObject(By.res("message-list")), UI_TIMEOUT_MILLIS)
        val list = device.findObject(By.res("message-list")) ?: return@measureRepeated
        list.setGestureMargin(device.displayWidth / GESTURE_MARGIN_DIVISOR)
        repeat(SCROLL_REPEATS) {
            list.fling(Direction.UP)
            device.waitForIdle()
            list.fling(Direction.DOWN)
            device.waitForIdle()
        }
    }

    @Test
    fun scrollSettings() = benchmarkRule.measureRepeated(
        packageName = PACKAGE_NAME,
        metrics = listOf(FrameTimingMetric()),
        compilationMode = CompilationMode.Partial(),
        startupMode = StartupMode.WARM,
        iterations = ITERATIONS,
        setupBlock = {
            pressHome()
            startActivityAndWait()
            device.findObject(By.desc("Open conversations"))?.click()
            device.waitForIdle()
            device.findObject(By.text("Settings"))?.click()
            device.waitForIdle()
        },
    ) {
        val list = device.findObject(By.res("settings-list")) ?: return@measureRepeated
        list.setGestureMargin(device.displayWidth / GESTURE_MARGIN_DIVISOR)
        repeat(SCROLL_REPEATS) {
            list.fling(Direction.DOWN)
            device.waitForIdle()
            list.fling(Direction.UP)
            device.waitForIdle()
        }
    }

    private companion object {
        const val PACKAGE_NAME = "com.aliahad.aichat"
        const val ITERATIONS = 10
        const val UI_TIMEOUT_MILLIS = 15_000L
        const val GESTURE_MARGIN_DIVISOR = 5
        const val SCROLL_REPEATS = 3
    }
}
