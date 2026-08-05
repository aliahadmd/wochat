package com.aliahad.aichat

data class ThinkingUiState(
    val messageId: String,
    val text: String = "",
    val complete: Boolean = false,
    val expanded: Boolean = false,
)
