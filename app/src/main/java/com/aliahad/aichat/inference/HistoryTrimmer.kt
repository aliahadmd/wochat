package com.aliahad.aichat.inference

import com.aliahad.aichat.core.ChatMessage

object HistoryTrimmer {
    fun trim(messages: List<ChatMessage>, contextSize: Int): List<ChatMessage> {
        val characterBudget = (contextSize * 3).coerceAtLeast(2048)
        var used = 0
        val kept = ArrayDeque<ChatMessage>()
        for (message in messages.asReversed()) {
            val estimated = message.content.length + 32
            if (kept.isNotEmpty() && used + estimated > characterBudget) break
            kept.addFirst(message)
            used += estimated
        }
        return kept.toList()
    }
}
