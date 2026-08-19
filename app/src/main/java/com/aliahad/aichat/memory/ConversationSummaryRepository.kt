package com.aliahad.aichat.memory

import com.aliahad.aichat.core.ChatMessage
import com.aliahad.aichat.core.ConversationSummary
import com.aliahad.aichat.core.MessageRole
import com.aliahad.aichat.data.AppDatabase
import com.aliahad.aichat.data.ConversationSummaryEntity
import kotlinx.coroutines.CancellationException

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
        // The planner rebuilds history from the whole conversation every turn, so
        // the same oldest turns are handed here repeatedly. Only append what the
        // stored summary does not already cover, or the summary devolves into
        // duplicates of the first few turns within a handful of sends.
        val newMessages = existing?.throughMessageId?.let { marker ->
            val markerIndex = trimmedMessages.indexOfFirst { it.id == marker }
            if (markerIndex >= 0) {
                trimmedMessages.drop(markerIndex + 1)
            } else {
                trimmedMessages.filter { it.createdAt > existing.throughCreatedAt }
            }
        } ?: trimmedMessages
        if (newMessages.isEmpty()) return existing?.toDomain()
        val content = buildString {
            existing?.content?.takeIf(String::isNotBlank)?.let {
                append(it)
                append('\n')
            }
            newMessages.forEach { message ->
                append(if (message.role == MessageRole.USER) "User: " else "Assistant: ")
                append(message.content.replace(Regex("\\s+"), " ").take(420))
                append('\n')
            }
        }.trim().takeLast(12_000)
        val row = ConversationSummaryEntity(
            conversationId = conversationId,
            throughMessageId = newMessages.last().id,
            throughCreatedAt = newMessages.last().createdAt,
            content = content,
            tokenCount = tokenCount(content),
            updatedAt = System.currentTimeMillis(),
        )
        dao.upsert(row)
        return row.toDomain()
    }

    /**
     * Summaries whose raw transcript grew past the distillation threshold. The
     * synchronous [updateFromTrimmed] path keeps appending raw turn text; once the
     * content is long enough a background LLM pass should condense it. Distilled
     * outputs are capped below this threshold, so a freshly distilled summary is
     * never immediately due again.
     */
    suspend fun dueForLlmRefresh(limit: Int): List<ConversationSummary> =
        dao.all()
            .filter { it.content.length >= SUMMARY_LLM_REFRESH_CHARS }
            .sortedByDescending { it.content.length }
            .take(limit)
            .map { it.toDomain() }

    /**
     * Rolling LLM summarization. Builds a token-capped input from the existing
     * summary plus newly trimmed messages, asks [generator] for a dense profile,
     * and stores the result (defensively truncated). When the generator is
     * unavailable, fails, or returns blank output this falls back to the
     * synchronous [updateFromTrimmed] truncation behavior so prompts never regress.
     */
    suspend fun summarizeFrom(
        conversationId: String,
        existingSummary: String?,
        trimmedMessages: List<ChatMessage>,
        tokenCount: suspend (String) -> Int,
        generator: suspend (prompt: String) -> String?,
    ): ConversationSummary? {
        val input = summarizationInput(existingSummary, trimmedMessages, tokenCount)
        val hasMaterial = existingSummary?.isNotBlank() == true || trimmedMessages.isNotEmpty()
        val generated = if (hasMaterial) {
            try {
                generator(input)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                null
            }
        } else {
            null
        }
        val distilled = generated?.trim()?.takeIf(String::isNotBlank)
        if (distilled == null) {
            return updateFromTrimmed(conversationId, trimmedMessages, tokenCount)
        }
        val content = distilled.take(MAX_SUMMARY_OUTPUT_CHARS)
        val existing = dao.get(conversationId)
        val row = ConversationSummaryEntity(
            conversationId = conversationId,
            throughMessageId = trimmedMessages.lastOrNull()?.id ?: existing?.throughMessageId,
            throughCreatedAt = trimmedMessages.lastOrNull()?.createdAt ?: existing?.throughCreatedAt ?: 0,
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

/** Raw summary content length that marks a conversation due for LLM distillation. */
internal const val SUMMARY_LLM_REFRESH_CHARS = 4_500

/** Defensive output cap (~1k tokens) for a distilled summary stored in the database. */
internal const val MAX_SUMMARY_OUTPUT_CHARS = 4_000

/** Token budget for the extraction prompt input. */
internal const val MAX_SUMMARY_INPUT_TOKENS = 3_000

/** Per-message character cap inside the extraction prompt. */
internal const val MAX_SUMMARY_MESSAGE_CHARS = 600

internal const val SUMMARY_EXTRACTION_INSTRUCTION =
    "Summarize the following conversation history into a dense factual profile of the " +
        "user: preferences, facts, decisions, plans. Preserve specifics such as names, " +
        "numbers, and dates. No commentary, no preamble."

/**
 * Assembles the token-capped extraction prompt. The existing summary comes first so
 * the model can merge and supersede it; trimmed messages are admitted newest-first
 * (most salient) while keeping chronological order in the emitted text.
 */
internal suspend fun summarizationInput(
    existingSummary: String?,
    trimmedMessages: List<ChatMessage>,
    tokenCount: suspend (String) -> Int,
    maxTokens: Int = MAX_SUMMARY_INPUT_TOKENS,
): String {
    val existingText = existingSummary?.trim()?.takeIf(String::isNotBlank)
        ?.take(MAX_SUMMARY_OUTPUT_CHARS)
    val lines = trimmedMessages.map { message ->
        val prefix = if (message.role == MessageRole.USER) "User: " else "Assistant: "
        prefix + message.content.replace(Regex("\\s+"), " ").take(MAX_SUMMARY_MESSAGE_CHARS)
    }
    val header = buildString {
        append(SUMMARY_EXTRACTION_INSTRUCTION)
        existingText?.let {
            append("\n\nExisting summary:\n")
            append(it)
        }
    }
    if (lines.isEmpty()) return header
    fun historyBlock(admitted: BooleanArray): String =
        "\n\nConversation history:\n" +
            lines.filterIndexed { index, _ -> admitted[index] }.joinToString("\n")
    val admitted = BooleanArray(lines.size)
    for (index in lines.indices.reversed()) {
        val candidate = BooleanArray(lines.size) { admitted[it] || it == index }
        if (tokenCount(header + historyBlock(candidate)).coerceAtLeast(1) > maxTokens) continue
        admitted[index] = true
    }
    return if (admitted.none()) header else header + historyBlock(admitted)
}
