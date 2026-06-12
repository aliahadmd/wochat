package com.aliahad.aichat.memory

import com.aliahad.aichat.core.ChatMessage
import com.aliahad.aichat.core.ConversationSummary
import com.aliahad.aichat.core.MessageRole
import com.aliahad.aichat.data.AppDatabase
import com.aliahad.aichat.data.ConversationSummaryEntity

class ConversationSummaryRepository(
    database: AppDatabase,
) {
    private val dao = database.conversationSummaryDao()

    suspend fun get(conversationId: String): ConversationSummary? =
        dao.get(conversationId)?.toDomain()

    suspend fun updateFromTrimmed(
        conversationId: String,
        trimmedMessages: List<ChatMessage>,
        tokenCount: suspend (String) -> Int,
    ): ConversationSummary? {
        if (trimmedMessages.isEmpty()) return get(conversationId)
        val existing = dao.get(conversationId)
        val content = buildString {
            existing?.content?.takeIf(String::isNotBlank)?.let {
                append(it)
                append('\n')
            }
            trimmedMessages.forEach { message ->
                append(if (message.role == MessageRole.USER) "User: " else "Assistant: ")
                append(message.content.replace(Regex("\\s+"), " ").take(420))
                append('\n')
            }
        }.trim().takeLast(12_000)
        val row = ConversationSummaryEntity(
            conversationId = conversationId,
            throughMessageId = trimmedMessages.last().id,
            content = content,
            tokenCount = tokenCount(content),
            updatedAt = System.currentTimeMillis(),
        )
        dao.upsert(row)
        return row.toDomain()
    }
}

private fun ConversationSummaryEntity.toDomain() = ConversationSummary(
    conversationId = conversationId,
    throughMessageId = throughMessageId,
    content = content,
    tokenCount = tokenCount,
    updatedAt = updatedAt,
)
