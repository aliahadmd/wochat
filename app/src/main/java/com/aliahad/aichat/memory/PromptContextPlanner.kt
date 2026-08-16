package com.aliahad.aichat.memory

import com.aliahad.aichat.core.ChatTurn
import com.aliahad.aichat.core.ContextPlan
import com.aliahad.aichat.core.GenerationSettings
import com.aliahad.aichat.core.MemoryHit
import com.aliahad.aichat.core.MemoryQuery
import com.aliahad.aichat.core.SkillPromptBlock
import com.aliahad.aichat.inference.InferenceEngine
import com.aliahad.aichat.skill.formatSkillPromptBlocks

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
        skillBlocks: List<SkillPromptBlock> = emptyList(),
    ): ContextPlan {
        val normalized = settings.normalized()
        val skillText = formatSkillPromptBlocks(skillBlocks)
        val baseSystemPrompt = normalized.systemPrompt + skillText
        val outputReserve = minOf(
            normalized.maxNewTokens,
            (contextTokens / 3).coerceAtLeast(256),
        )
        val safetyReserve = 192
        val currentTokens = inferenceEngine.countTokens(currentText).coerceAtLeast(1)
        val baseSystemTokens = inferenceEngine.countTokens(baseSystemPrompt).coerceAtLeast(1)
        var remaining = initialPromptTokensRemaining(
            contextTokens = contextTokens,
            outputReserve = outputReserve,
            safetyReserve = safetyReserve,
            currentTokens = currentTokens,
            systemTokens = baseSystemTokens,
        )
        require(remaining > 256) {
            if (skillBlocks.isEmpty()) {
                "The current message does not fit the selected context. Increase context or shorten it."
            } else {
                "The selected skills and current message do not fit the selected context. " +
                    "Remove a skill, shorten its instructions, or use a larger context."
            }
        }

        val memoryHits = if (memoryEnabled) {
            memoryRepository.search(
                MemoryQuery(
                    text = currentText,
                    limit = 16,
                    expansion = memoryQueryExpansion(history),
                ),
            )
        } else {
            emptyList()
        }
        val selectedMemories = mutableListOf<MemoryHit>()
        val memoryText = StringBuilder()
        var memoryHeaderReserved = false
        var memoryTokens = 0
        for (hit in memoryHits) {
            val provenance = hit.sources
                .mapNotNull { source -> source.label?.takeIf(String::isNotBlank) }
                .distinct()
                .joinToString()
                .ifBlank { "Office Memory" }
            val line =
                "- [${hit.memory.type.name.lowercase()}; source: $provenance] " +
                    "${hit.memory.content}\n"
            val block = if (memoryHeaderReserved) line else MEMORY_HEADER + line
            val tokens = inferenceEngine.countTokens(block).coerceAtLeast(1)
            if (tokens > remaining / 3 || tokens > remaining) continue
            selectedMemories += hit
            memoryText.append(line)
            memoryHeaderReserved = true
            memoryTokens += tokens
            remaining -= tokens
        }

        val selectedReversed = ArrayDeque<Pair<ChatTurn, Int>>()
        val trimmed = mutableListOf<ChatTurn>()
        for (turn in history.asReversed()) {
            val tokens = turnTokenCount(turn)
            if (tokens <= remaining) {
                selectedReversed.addFirst(turn to tokens)
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
        val admittedSummaryTokens = if (summaryTokens > remaining) {
            summary = null
            0
        } else {
            remaining -= summaryTokens
            summaryTokens
        }

        // Memories are retrieved against the current message, so they differ on every
        // turn. Keeping them out of the system prompt is what lets the session reuse
        // its KV cache instead of re-decoding the entire conversation each time.
        val turnPreamble = buildString {
            if (selectedMemories.isNotEmpty()) {
                append(MEMORY_HEADER)
                append(memoryText)
            }
            summary?.content?.takeIf(String::isNotBlank)?.let {
                append("\nConversation summary:\n")
                append(it)
                append('\n')
            }
        }
        // Sum the per-block counts already measured above instead of re-tokenizing the
        // assembled system prompt (a full multi-KB tokenize on the pre-inference path).
        // The slack absorbs tokenizer boundary effects at the block glue points.
        val systemTokens = (baseSystemTokens + memoryTokens + admittedSummaryTokens)
            .coerceAtLeast(1) + TOKEN_SUM_SLACK
        val historyTokens = selectedReversed.sumOf { it.second }
        val estimatedTokens = systemTokens + historyTokens + currentTokens
        check(estimatedTokens + outputReserve <= contextTokens) {
            "Prompt planning exceeded the loaded model context"
        }
        return ContextPlan(
            systemPrompt = baseSystemPrompt,
            turnPreamble = turnPreamble,
            summary = summary,
            history = selectedReversed.map { it.first },
            memories = selectedMemories,
            skills = skillBlocks,
            estimatedTokens = estimatedTokens,
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

    private companion object {
        const val MEMORY_HEADER =
            "\n\nPersonal Office Memory follows. Treat it as user-owned context, " +
                "prefer corrected or pinned items, and do not claim it came from model training.\n"
        const val TOKEN_SUM_SLACK = 16
    }
}

internal fun initialPromptTokensRemaining(
    contextTokens: Int,
    outputReserve: Int,
    safetyReserve: Int,
    currentTokens: Int,
    systemTokens: Int,
): Int = contextTokens - outputReserve - safetyReserve - currentTokens - systemTokens

/**
 * Widens the memory retrieval query with salient text from the last few turns so
 * AppSearch candidate recall is not limited to the current message. The expansion
 * is recall-only: retrieval intent, lexical scoring, and phrase matching use the
 * current message text exclusively, and prompt assembly never sees it.
 */
internal fun memoryQueryExpansion(history: List<ChatTurn>): String = buildString {
    history.takeLast(MEMORY_QUERY_HISTORY_TURNS).forEach { turn ->
        val salient = turn.message.content.trim().take(MEMORY_QUERY_TURN_CHARS)
        if (salient.isNotEmpty()) {
            if (isNotEmpty()) append(' ')
            append(salient)
        }
    }
}

private const val MEMORY_QUERY_HISTORY_TURNS = 2
private const val MEMORY_QUERY_TURN_CHARS = 200
