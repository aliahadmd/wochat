package com.aliahad.aichat

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import com.aliahad.aichat.core.ChatMessage
import com.aliahad.aichat.core.MessageRole
import com.aliahad.aichat.core.MessageStatus
import com.aliahad.aichat.ui.MessageList
import com.aliahad.aichat.ui.theme.AichatTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class ChatUiInstrumentedTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun assistantMarkdownIsRendered() {
        composeRule.setContent {
            AichatTheme {
                MessageList(
                    messages = listOf(message(0, "**Bold response** with `code`")),
                    conversationId = "markdown",
                    modifier = Modifier,
                )
            }
        }

        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithText("Bold response", substring = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("Bold response", substring = true).assertIsDisplayed()
        assertTrue(
            composeRule.onAllNodesWithText("**Bold response**", substring = true)
                .fetchSemanticsNodes().isEmpty(),
        )
    }

    @Test
    fun streamingDoesNotOverrideManualScroll() {
        var messages by mutableStateOf((0 until 24).map(::message))
        composeRule.setContent {
            AichatTheme {
                MessageList(
                    messages = messages,
                    conversationId = "scroll",
                    modifier = Modifier,
                )
            }
        }

        composeRule.onNodeWithTag("message-list").performTouchInput { swipeDown() }
        composeRule.onNodeWithContentDescription("Jump to latest message").assertIsDisplayed()

        composeRule.runOnIdle {
            messages = messages.dropLast(1) + messages.last().copy(
                content = messages.last().content + "\n\nA streamed update that must not steal scrolling.",
                status = MessageStatus.STREAMING,
            )
        }

        composeRule.onNodeWithContentDescription("Jump to latest message").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Jump to latest message").performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithContentDescription("Jump to latest message")
                .fetchSemanticsNodes().isEmpty()
        }
    }

    private fun message(index: Int, content: String = "Message $index\n\nSupporting text for scrolling.") =
        ChatMessage(
            id = "message-$index",
            conversationId = "conversation",
            role = MessageRole.ASSISTANT,
            content = content,
            createdAt = index.toLong(),
            status = MessageStatus.COMPLETE,
        )
}
