package com.aliahad.aichat.memory

import com.aliahad.aichat.core.ChatTurn
import com.aliahad.aichat.core.ContextPlan
import com.aliahad.aichat.core.GenerationSettings
import com.aliahad.aichat.core.MemoryHit
import com.aliahad.aichat.core.MemoryQuery
import com.aliahad.aichat.inference.InferenceEngine

class PromptContextPlanner(
    private val inferenceEngine: InferenceEngine,
    private val memoryRepository: MemoryRepository,
    private val summaries: ConversationSummaryRepository,
) {
    suspend fun plan(
        conversationId: String,
        history: List<ChatTurn>,
        currentText: String,
        settings: GenerationSettings,
        contextTokens: Int,
        memoryEnabled: Boolean,
    ): ContextPlan {
        val normalized = settings.normalized()
        val outputReserve = minOf(
            normalized.maxNewTokens,
            (contextTokens / 3).coerceAtLeast(256),
        )
        val safetyReserve = 192
        val currentTokens = inferenceEngine.countTokens(currentText).coerceAtLeast(1)
        var remaining = contextTokens - outputReserve - safetyReserve - currentTokens
        require(remaining > 256) {
            "The current message does not fit the selected context. Increase context or shorten it."
        }

        val memoryHits = if (memoryEnabled) {
            memoryRepository.search(MemoryQuery(currentText, limit = 10))
        } else {
            emptyList()
        }
        val selectedMemories = mutableListOf<MemoryHit>()
        val memoryText = StringBuilder()
        for (hit in memoryHits) {
            val provenance = hit.sources
                .mapNotNull { source -> source.label?.takeIf(String::isNotBlank) }
                .distinct()
                .joinToString()
                .ifBlank { "Office Memory" }
            val line =
                "- [${hit.memory.type.name.lowercase()}; source: $provenance] " +
                    "${hit.memory.content}\n"
            val tokens = inferenceEngine.countTokens(line).coerceAtLeast(1)
            if (tokens > remaining / 3 || tokens > remaining) continue
            selectedMemories += hit
            memoryText.append(line)
            remaining -= tokens
        }

        val selectedReversed = ArrayDeque<ChatTurn>()
        val trimmed = mutableListOf<ChatTurn>()
        for (turn in history.asReversed()) {
            val tokens = turnTokenCount(turn)
            if (tokens <= remaining) {
                selectedReversed.addFirst(turn)
                remaining -= tokens
            } else {
                trimmed += turn
            }
        }

        var summary = summaries.get(conversationId)
        if (trimmed.isNotEmpty()) {
            summary = summaries.updateFromTrimmed(
                conversationId = conversationId,
                trimmedMessages = trimmed.asReversed().map(ChatTurn::message),
                tokenCount = inferenceEngine::countTokens,
            )
        }
        val summaryText = summary?.content?.takeIf(String::isNotBlank)
        val summaryBlock = summaryText?.let { "\nConversation summary:\n$it\n" }
        val summaryTokens = summaryBlock?.let { inferenceEngine.countTokens(it) } ?: 0
        if (summaryTokens > remaining) summary = null

        val systemPrompt = buildString {
            append(normalized.systemPrompt)
            if (selectedMemories.isNotEmpty()) {
                append(
                    "\n\nPersonal Office Memory follows. Treat it as user-owned context, " +
                        "prefer corrected or pinned items, and do not claim it came from model training.\n",
                )
                append(memoryText)
            }
            summary?.content?.takeIf(String::isNotBlank)?.let {
                append("\nConversation summary:\n")
                append(it)
            }
        }
        val systemTokens = inferenceEngine.countTokens(systemPrompt).coerceAtLeast(1)
        val historyTokens = selectedReversed.sumOf { turnTokenCount(it) }
        return ContextPlan(
            systemPrompt = systemPrompt,
            summary = summary,
            history = selectedReversed.toList(),
            memories = selectedMemories,
            estimatedTokens = systemTokens + historyTokens + currentTokens,
            outputReserveTokens = outputReserve,
        )
    }

    private suspend fun turnTokenCount(turn: ChatTurn): Int {
        val text = buildString {
            append(turn.message.content)
            turn.attachments.forEach { append(it.extractedText) }
        }
        return inferenceEngine.countTokens(text).coerceAtLeast(1) +
            turn.attachments.sumOf { attachment ->
                attachment.imagePaths.size * attachment.imageTokenBudget
            } + 12
    }
}
