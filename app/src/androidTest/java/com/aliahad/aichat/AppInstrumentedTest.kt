package com.aliahad.aichat

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppInstrumentedTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun settingsNavigationShowsModelManagement() {
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithTag("app-nav-host").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithContentDescription("Open conversations").performClick()
        composeRule.onNodeWithText("Settings").performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithTag("settings-list").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("settings-navigation").assertIsDisplayed()
        composeRule.onNodeWithText("Models").assertIsDisplayed()
        // The model catalog is seeded asynchronously during startup
        // (ensureOfficialRecords), so on a fresh install the row may not exist
        // yet. Wait for it instead of scrolling to a node that is not there —
        // performScrollToNode fails outright rather than retrying.
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText("Gemma 4 E4B IT Q4")
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("settings-list")
            .performScrollToNode(hasText("Gemma 4 E4B IT Q4"))
        composeRule.onNodeWithText("Gemma 4 E4B IT Q4").assertIsDisplayed()
        composeRule.onNodeWithText("Runtime").performClick()
        composeRule.onNodeWithTag("settings-list").performScrollToNode(hasText("Automatic context"))
        composeRule.onNodeWithText("Automatic context").assertIsDisplayed()
        assertTrue(composeRule.onAllNodesWithText("Context mode").fetchSemanticsNodes().isEmpty())
        assertTrue(
            composeRule.onAllNodesWithText("Context", substring = false)
                .fetchSemanticsNodes()
                .isEmpty(),
        )
    }
}
