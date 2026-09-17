package com.aliahad.aichat.baselineprofile

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Generates the baseline profile for Offmind.
 *
 * The journey deliberately covers more than `startActivityAndWait()`: a profile
 * that only knows how to reach the first frame leaves the screens the user
 * actually touches interpreted. Cold start, the message list, the conversation
 * drawer and settings are the routes worth pre-compiling.
 *
 * Startup is slower here than in a normal launch because the app warms the
 * encrypted database and the native inference library in the background, so the
 * waits below are generous on purpose.
 */
@RunWith(AndroidJUnit4::class)
class StartupBaselineProfileGenerator {

    @get:Rule
    val baselineProfileRule = BaselineProfileRule()

    @Test
    fun generate() = baselineProfileRule.collect(
        packageName = PACKAGE_NAME,
        includeInStartupProfile = true,
    ) {
        pressHome()
        startActivityAndWait()

        // The UI is gated on the container warm-up, so give it room to appear
        // before touching anything.
        device.wait(Until.hasObject(By.pkg(PACKAGE_NAME).depth(0)), UI_TIMEOUT_MILLIS)
        device.waitForIdle()

        scrollMessageList()
        openAndCloseDrawer()
    }

    private fun androidx.benchmark.macro.MacrobenchmarkScope.scrollMessageList() {
        val list = device.findObject(By.res("message-list")) ?: return
        list.setGestureMargin(device.displayWidth / GESTURE_MARGIN_DIVISOR)
        repeat(SCROLL_REPEATS) {
            list.fling(androidx.test.uiautomator.Direction.DOWN)
            device.waitForIdle()
        }
    }

    private fun androidx.benchmark.macro.MacrobenchmarkScope.openAndCloseDrawer() {
        // Content description, not a test tag: UiAutomator sees accessibility
        // labels rather than Compose testTags unless they are also semantics.
        val menu = device.findObject(By.desc("Open conversations")) ?: return
        menu.click()
        device.waitForIdle()
        device.pressBack()
        device.waitForIdle()
    }

    private companion object {
        const val PACKAGE_NAME = "com.aliahad.aichat"
        const val UI_TIMEOUT_MILLIS = 15_000L
        const val GESTURE_MARGIN_DIVISOR = 5
        const val SCROLL_REPEATS = 3
    }
}
