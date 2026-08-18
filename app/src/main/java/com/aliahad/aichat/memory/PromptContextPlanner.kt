package com.aliahad.aichat.memory

import com.aliahad.aichat.core.ChatTurn
import com.aliahad.aichat.core.ContextPlan
import com.aliahad.aichat.core.GenerationSettings
import com.aliahad.aichat.core.MemoryHit
import com.aliahad.aichat.core.MemoryQuery
import com.aliahad.aichat.core.SkillPromptBlock
import com.aliahad.aichat.core.TurnOrigin
import com.aliahad.aichat.inference.InferenceEngine
import com.aliahad.aichat.skill.formatSkillPromptBlocks

/**
 * How much context a turn can afford.
 *
 * Talking and typing have different economics. Plan 037 measured prefill as the
 * dominant term in time to first token, and a retrieved memory block as its
 * biggest controllable input: with memory on a short question carried 63-68 prompt
 * tokens and 5.34 s of prefill, with memory off 13 tokens and 1.43 s. A reader
 * waits for a screen; a caller waits in silence, and notices every second.
 *
 * [COMPACT] trims only what lives in the *per-turn preamble* — retrieved memories
 * and the conversation summary. The system prompt is deliberately identical in both
 * budgets: it is the stable KV-cache prefix, and varying it would make every switch
 * between a typed and a spoken turn decline session reuse and re-prefill the whole
 * conversation, trading ~4 s for ~30 s.
 */
enum class ContextBudget {
    FULL,
    COMPACT,
    ;

    /** Retrieved memories admitted to the preamble. */
    val maxMemories: Int get() = if (this == COMPACT) COMPACT_MEMORIES else FULL_MEMORIES

    /** Ceiling on the assembled memory block, in tokens. */
    val maxMemoryTokens: Int get() = if (this == COMPACT) COMPACT_MEMORY_TOKENS else FULL_MEMORY_TOKENS

    /**
     * A spoken answer is short and the summary is long; it costs more prefill than
     * it earns back in a call, where the recent turns are still in the KV cache.
     */
    val includesSummary: Boolean get() = this != COMPACT

    /**
     * Whether each memory carries its type and source.
     *
     * Measured: `- [preference; source: Chat message] ` is ~9 tokens of wrapper on a
     * ~7-token fact. That provenance is what the Memory screen is for; the model
     * answering out loud does not need it.
     */
    val includesProvenance: Boolean get() = this != COMPACT

    /**
     * The line introducing retrieved memories.
     *
     * The full header is ~35 tokens, which with provenance meant **45 tokens of
     * wrapper around 7 tokens of fact** — the single largest piece of a spoken
     * turn's prompt. The compact form keeps the one instruction that changes
     * answers, that this is the user's own context rather than something the model
     * knows, and drops the rest.
     */
    val memoryHeader: String
        get() = if (this == COMPACT) COMPACT_MEMORY_HEADER else FULL_MEMORY_HEADER

    companion object {
        /** Talking buys less context than typing; see the class comment for why. */
        fun forOrigin(origin: TurnOrigin): ContextBudget =
            if (origin == TurnOrigin.VOICE) COMPACT else FULL

        private const val FULL_MEMORIES = 4
        private const val FULL_MEMORY_TOKENS = 192

        // Halved rather than removed: plan 037's STOP condition is explicit that a
        // faster assistant which forgot the user is not an improvement, and
        // retrieval is ranked, so the top 2 are the ones worth having.
        private const val COMPACT_MEMORIES = 2
        private const val COMPACT_MEMORY_TOKENS = 96

        // "Personal Office Memory follows. Treat it as user-owned context..." read to
        // the model as a declaration of what context *is*. Measured 2026-08-18 on the
        // same conversation with only this toggle changed: with memory on, "which of
        // those three rivers is the longest?" got "the personal office memory you
        // provided does not contain a list of rivers"; with memory off it answered
        // from the list correctly. The history was in the KV cache throughout
        // ("Session reuse accepted: 10 of 10"), so the model was not missing context,
        // it was scoping itself to this block. Both headers now say outright that the
        // conversation is still there.
        private const val FULL_MEMORY_HEADER =
            "\n\nNotes about the user from earlier sessions. They add to the conversation " +
                "above, never replace it. Prefer corrected or pinned items, and do not " +
                "present them as training knowledge.\n"

        private const val COMPACT_MEMORY_HEADER =
            "\n\nUser notes, not from training, alongside the conversation above:\n"
    }
}

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
        budget: ContextBudget = ContextBudget.FULL,
    ): ContextPlan {
        val normalized = settings.normalized()
        val skillText = formatSkillPromptBlocks(skillBlocks)
        // When nothing will be stored, say so. Without this the model answers "I will
        // remember that your name is Ali" while the turn is being discarded — the app
        // promising something it is actively not doing. Reported by the owner after
        // stating a name and finding it unknown in the next conversation.
        val baseSystemPrompt = normalized.systemPrompt + skillText +
            if (memoryEnabled) "" else MEMORY_DISABLED_NOTICE
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
                    limit = budget.maxMemories,
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
            val line = if (budget.includesProvenance) {
                val provenance = hit.sources
                    .mapNotNull { source -> source.label?.takeIf(String::isNotBlank) }
                    .distinct()
                    .joinToString()
                    .ifBlank { "Office Memory" }
                "- [${hit.memory.type.name.lowercase()}; source: $provenance] " +
                    "${hit.memory.content}\n"
            } else {
                "- ${hit.memory.content}\n"
            }
            val block = if (memoryHeaderReserved) line else budget.memoryHeader + line
            val tokens = inferenceEngine.countTokens(block).coerceAtLeast(1)
            if (tokens > remaining / 3 || tokens > remaining) continue
            // Hard backstop independent of scoring: time to first token is roughly
            // (preamble tokens) / 21 per second on this hardware, so an unbounded
            // memory block is an unbounded wait. A calendar question once pulled
            // 1578 tokens of memory, which was ~75 s of prefill before a word appeared.
            if (memoryTokens + tokens > budget.maxMemoryTokens) continue
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

        var summary = if (budget.includesSummary) summaries.get(conversationId) else null
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
                append(budget.memoryHeader)
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
        const val TOKEN_SUM_SLACK = 16

        /**
         * Appended to the system prompt whenever this turn will not be written to
         * memory, either because the user turned memory off or because the
         * conversation is temporary. Kept in the *stable* half of the prompt on
         * purpose: it changes only when the flag changes, so it does not invalidate
         * the KV-cache prefix the way per-turn content would.
         */
        const val MEMORY_DISABLED_NOTICE =
            "\n\nPersonal memory is turned off for this conversation. You cannot store " +
                "anything the user tells you, and nothing will be available in a later " +
                "conversation. If the user asks you to remember something, say plainly " +
                "that memory is off rather than agreeing to remember it."

        // Retrieval used to ask for 16 and, with the relevance floor bypassed for
        // every AppSearch hit, effectively always return 16 — regardless of whether
        // any of them related to the question. The caps now live on [ContextBudget],
        // because a spoken turn can afford less of them than a typed one.
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
