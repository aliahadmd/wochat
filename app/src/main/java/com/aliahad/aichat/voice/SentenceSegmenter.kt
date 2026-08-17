package com.aliahad.aichat.voice

/**
 * Cuts a growing answer into speakable chunks.
 *
 * Plan 036's latency argument rests on speaking early: generation outruns speech
 * 3-4x, so once talking starts TTS never starves, and waiting for the finished
 * answer would add the whole generation time to the gap before any sound.
 *
 * Step 8 measured that waiting for a *sentence* is nearly as bad. One turn reached
 * its first token at 19.5 s and its first full sentence at 36.9 s — 17 seconds of
 * generation before a word could be spoken. So the first chunk is allowed to end at
 * a clause boundary (a comma, semicolon, colon or dash) once it is long enough to
 * sound deliberate rather than clipped, and everything after it waits for a proper
 * sentence, where the extra smoothness costs nothing the listener notices.
 *
 * [accept] is called with the answer so far — which only ever grows — and returns
 * whatever has become speakable since the last call.
 */
class SentenceSegmenter(
    /**
     * Shortest first chunk worth speaking. Below this a clause break produces
     * "Well," or "Sure," on its own, which sounds like a stutter rather than a
     * faster reply.
     */
    private val firstChunkMinChars: Int = 20,
) {
    private var consumed = 0
    private var spokeFirstChunk = false

    /** Whatever became speakable now that the answer has grown to [answerSoFar]. */
    fun accept(answerSoFar: String): List<String> {
        if (answerSoFar.length < consumed) return emptyList()
        val pending = answerSoFar.substring(consumed)
        val chunks = mutableListOf<String>()
        var start = 0
        var index = 0
        while (index < pending.length) {
            val breaksHere = when {
                pending[index].endsSentence() && endsSentence(pending, index) -> true
                // Only the opening chunk may end mid-sentence, and only once it has
                // enough words behind it to stand on its own.
                !spokeFirstChunk && chunks.isEmpty() && pending[index].endsClause() &&
                    endsClause(pending, index) && index - start + 1 >= firstChunkMinChars -> true
                else -> false
            }
            if (breaksHere) {
                val chunk = pending.substring(start, index + 1).trim()
                if (chunk.isNotEmpty()) {
                    chunks += chunk
                    spokeFirstChunk = true
                }
                start = index + 1
            }
            index++
        }
        consumed += start
        return chunks
    }

    /** Whatever is left when generation stops, so a reply without a full stop is still spoken. */
    fun flush(answerSoFar: String): String? {
        if (answerSoFar.length <= consumed) return null
        val tail = answerSoFar.substring(consumed).trim()
        consumed = answerSoFar.length
        if (tail.isNotEmpty()) spokeFirstChunk = true
        return tail.ifEmpty { null }
    }

    fun reset() {
        consumed = 0
        spokeFirstChunk = false
    }

    private companion object {
        fun Char.endsSentence() = this == '.' || this == '!' || this == '?' || this == '\n'

        fun Char.endsClause() = this == ',' || this == ';' || this == ':' || this == '—'

        /**
         * A terminator only ends a sentence when what follows is whitespace or
         * nothing. Without this, "3.5" and "e.g." are each spoken as two fragments,
         * and the pause lands in the middle of a number.
         */
        fun endsSentence(text: String, index: Int): Boolean {
            if (text[index] == '\n') return true
            val next = text.getOrNull(index + 1) ?: return true
            if (!next.isWhitespace()) return false
            val previous = text.getOrNull(index - 1)
            return previous == null || !previous.isDigit() || text[index] != '.'
        }

        /** Same rule, plus never splitting a thousands separator like "1,000". */
        fun endsClause(text: String, index: Int): Boolean {
            val next = text.getOrNull(index + 1) ?: return false
            if (!next.isWhitespace()) return false
            val previous = text.getOrNull(index - 1)
            return !(text[index] == ',' && previous != null && previous.isDigit())
        }
    }
}
