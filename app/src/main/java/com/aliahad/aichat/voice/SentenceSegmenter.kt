package com.aliahad.aichat.voice

/**
 * Cuts a growing answer into speakable sentences.
 *
 * Plan 036's whole latency argument rests on speaking the *first sentence* rather
 * than the finished reply: generation outruns speech 3-4x, so once talking starts
 * TTS never starves, and waiting for the full answer would add the entire
 * generation time to the gap before any sound.
 *
 * [accept] is called with the answer so far — which only ever grows — and returns
 * whatever complete sentences have appeared since the last call.
 */
class SentenceSegmenter {
    private var consumed = 0

    /** Complete sentences revealed by growing the answer to [answerSoFar]. */
    fun accept(answerSoFar: String): List<String> {
        if (answerSoFar.length < consumed) return emptyList()
        val pending = answerSoFar.substring(consumed)
        val sentences = mutableListOf<String>()
        var start = 0
        var index = 0
        while (index < pending.length) {
            if (pending[index].isSentenceEnd() && endsSentence(pending, index)) {
                val sentence = pending.substring(start, index + 1).trim()
                if (sentence.isNotEmpty()) sentences += sentence
                start = index + 1
            }
            index++
        }
        consumed += start
        return sentences
    }

    /** Whatever is left when generation stops, so a reply without a full stop is still spoken. */
    fun flush(answerSoFar: String): String? {
        if (answerSoFar.length <= consumed) return null
        val tail = answerSoFar.substring(consumed).trim()
        consumed = answerSoFar.length
        return tail.ifEmpty { null }
    }

    fun reset() {
        consumed = 0
    }

    private companion object {
        fun Char.isSentenceEnd() = this == '.' || this == '!' || this == '?' || this == '\n'

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
    }
}
