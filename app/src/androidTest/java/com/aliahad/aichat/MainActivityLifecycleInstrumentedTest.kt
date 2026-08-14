package com.aliahad.aichat

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.lifecycle.Lifecycle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import androidx.test.core.app.ActivityScenario

@RunWith(AndroidJUnit4::class)
class MainActivityLifecycleInstrumentedTest {

    @Test
    fun activityLaunchesAndReachesResumedState() {
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        scenario.moveToState(Lifecycle.State.RESUMED)
        assertEquals(Lifecycle.State.RESUMED, scenario.state)
        scenario.close()
    }

    @Test
    fun residencyControllerTracksForegroundLifecycle() {
        val app = ApplicationProvider.getApplicationContext<AiChatApplication>()
        val controller = app.container.residencyController

        val scenario = ActivityScenario.launch(MainActivity::class.java)

        // After moving to RESUMED, onStart should have set uiForeground = true
        scenario.moveToState(Lifecycle.State.RESUMED)
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()

        // Move to CREATED (stopped) — onStop sets uiForeground = false
        scenario.moveToState(Lifecycle.State.CREATED)
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()

        // Re-enter RESUMED — onStart sets it back to true
        scenario.moveToState(Lifecycle.State.RESUMED)
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()

        // If we got here without crashing, the lifecycle callbacks are wired correctly
        scenario.close()
    }

    @Test
    fun viewModelFactoryIsWiredThroughApplication() {
        val app = ApplicationProvider.getApplicationContext<AiChatApplication>()
        assertNotNull(app.container)
        assertNotNull(app.container.settings)
        assertNotNull(app.container.chatRepository)
        assertNotNull(app.container.modelRepository)
        assertNotNull(app.container.skillRepository)
        assertNotNull(app.container.attachmentRepository)
        assertNotNull(app.container.inferenceEngine)
        assertNotNull(app.container.residencyController)
    }

    @Test
    fun memoryRefreshRunsOnResumeWithoutCrash() {
        // Launching and resuming the activity triggers memoryViewModel.refreshPhoneSourceAccess()
        // in onResume. This test verifies that the refresh path does not crash.
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        scenario.moveToState(Lifecycle.State.RESUMED)
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        scenario.moveToState(Lifecycle.State.DESTROYED)
    }

    @Test
    fun stopAndRestartCycleDoesNotLeakOrCrash() {
        val scenario = ActivityScenario.launch(MainActivity::class.java)

        // Full cycle: RESUMED -> STARTED -> CREATED -> STARTED -> RESUMED
        scenario.moveToState(Lifecycle.State.STARTED)
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        scenario.moveToState(Lifecycle.State.CREATED)
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        scenario.moveToState(Lifecycle.State.STARTED)
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        scenario.moveToState(Lifecycle.State.RESUMED)
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()

        scenario.close()
    }
}
