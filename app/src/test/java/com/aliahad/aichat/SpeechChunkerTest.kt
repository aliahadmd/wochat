package com.aliahad.aichat

import com.aliahad.aichat.speech.SpeechChunker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeechChunkerTest {
    @Test
    fun fragmentedClauseEmitsBeforeFinalFlush() {
        val chunker = SpeechChunker()

        assertTrue(chunker.accept("Hello").isEmpty())
        assertEquals(listOf("Hello world."), chunker.accept(" world. Next"))
        assertEquals(listOf("Next"), chunker.finish())
    }

    @Test
    fun markdownAndFencedCodeAreRemoved() {
        val chunker = SpeechChunker()

        val first = chunker.accept("Answer **bold**. ```kotlin\nval hidden = 1\n")
        val rest = chunker.accept("``` Then done.")

        assertEquals(listOf("Answer bold."), first)
        assertEquals(listOf("Then done."), rest + chunker.finish())
    }

    @Test
    fun urlsAreNotSpoken() {
        val chunker = SpeechChunker()

        val result = chunker.accept("See https://example.com/path for details.") + chunker.finish()

        assertEquals(listOf("See for details."), result)
    }

    @Test
    fun boundedWordCountPreventsUnboundedLatency() {
        val chunker = SpeechChunker(maxWords = 5)

        val result = chunker.accept("one two three four five six")

        assertEquals(listOf("one two three four five"), result)
        assertEquals(listOf("six"), chunker.finish())
    }

    @Test
    fun resetDropsEveryPendingFragment() {
        val chunker = SpeechChunker()
        chunker.accept("This must never be spoken")

        chunker.reset()

        assertTrue(chunker.finish().isEmpty())
    }
}
