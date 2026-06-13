package com.aliahad.aichat.speech

class SpeechChunker(
    private val maxWords: Int = 28,
) {
    private val pending = StringBuilder()
    private var inFencedCode = false
    private var backtickCount = 0

    fun accept(delta: String): List<String> {
        delta.forEach(::acceptCharacter)
        return drain(force = false)
    }

    fun finish(): List<String> {
        flushBackticks()
        return drain(force = true)
    }

    fun reset() {
        pending.clear()
        inFencedCode = false
        backtickCount = 0
    }

    private fun acceptCharacter(character: Char) {
        if (character == '`') {
            backtickCount++
            if (backtickCount == 3) {
                inFencedCode = !inFencedCode
                backtickCount = 0
                if (!inFencedCode && pending.isNotEmpty() && !pending.last().isWhitespace()) {
                    pending.append(' ')
                }
            }
            return
        }
        flushBackticks()
        if (inFencedCode) return
        when (character) {
            '#', '*', '_', '>', '[', ']', '(', ')' -> {
                if (pending.isNotEmpty() && !pending.last().isWhitespace()) pending.append(' ')
            }
            '\r' -> Unit
            else -> pending.append(character)
        }
    }

    private fun flushBackticks() {
        if (backtickCount > 0 && !inFencedCode) {
            backtickCount = 0
        }
    }

    private fun drain(force: Boolean): List<String> {
        val chunks = mutableListOf<String>()
        while (true) {
            val boundary = findBoundary(force) ?: break
            val raw = pending.substring(0, boundary).trim()
            pending.delete(0, boundary)
            val normalized = normalize(raw)
            if (normalized.isNotBlank()) chunks += normalized
            if (force && pending.isEmpty()) break
        }
        return chunks
    }

    private fun findBoundary(force: Boolean): Int? {
        if (pending.isEmpty()) return null
        var words = 0
        var inWord = false
        for (index in pending.indices) {
            val character = pending[index]
            if (character.isWhitespace()) {
                if (inWord) {
                    words++
                    inWord = false
                }
                val previous = pending.getOrNull(index - 1)
                if (previous in CLAUSE_PUNCTUATION || character == '\n' || words >= maxWords) {
                    return index + 1
                }
            } else {
                inWord = true
            }
        }
        if (inWord) words++
        if (words >= maxWords) {
            val lastWhitespace = pending.indexOfLast(Char::isWhitespace)
            if (lastWhitespace >= 0) return lastWhitespace + 1
        }
        return pending.length.takeIf { force }
    }

    private fun normalize(value: String): String =
        value
            .replace(URL, " ")
            .replace(MARKDOWN_LINK, "$1")
            .replace(HTML_TAG, " ")
            .replace(Regex("""\s+"""), " ")
            .replace(Regex("""\s+([.,!?;:])"""), "$1")
            .trim()

    private companion object {
        val CLAUSE_PUNCTUATION = setOf('.', '!', '?', ';', ':')
        val URL = Regex("""(?i)\b(?:https?://|www\.)\S+""")
        val MARKDOWN_LINK = Regex("""\[([^\]]+)]\([^)]*\)""")
        val HTML_TAG = Regex("""<[^>]+>""")
    }
}
