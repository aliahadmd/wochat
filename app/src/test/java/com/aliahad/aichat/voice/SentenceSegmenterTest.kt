package com.aliahad.aichat.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SentenceSegmenterTest {

    @Test
    fun `emits nothing until a sentence completes`() {
        val segmenter = SentenceSegmenter()
        assertEquals(emptyList<String>(), segmenter.accept("Hello there"))
    }

    @Test
    fun `emits a sentence once terminated and followed by space`() {
        val segmenter = SentenceSegmenter()
        assertEquals(listOf("Hello there."), segmenter.accept("Hello there. And"))
    }

    @Test
    fun `does not re-emit an already spoken sentence`() {
        val segmenter = SentenceSegmenter()
        segmenter.accept("One. ")
        assertEquals(listOf("Two."), segmenter.accept("One. Two. "))
    }

    @Test
    fun `emits several sentences arriving at once`() {
        val segmenter = SentenceSegmenter()
        assertEquals(listOf("One.", "Two!", "Three?"), segmenter.accept("One. Two! Three? "))
    }

    @Test
    fun `a decimal point does not split a number`() {
        val segmenter = SentenceSegmenter()
        assertEquals(emptyList<String>(), segmenter.accept("Water boils at 99.9"))
        assertEquals(listOf("Water boils at 99.9 degrees."), segmenter.accept("Water boils at 99.9 degrees. "))
    }

    @Test
    fun `a newline ends a sentence even without punctuation`() {
        val segmenter = SentenceSegmenter()
        assertEquals(listOf("A list item"), segmenter.accept("A list item\nnext"))
    }

    @Test
    fun `flush speaks a trailing fragment that never got a full stop`() {
        val segmenter = SentenceSegmenter()
        segmenter.accept("Done. ")
        assertEquals("and then some", segmenter.flush("Done. and then some"))
    }

    @Test
    fun `flush returns null when everything was already spoken`() {
        val segmenter = SentenceSegmenter()
        segmenter.accept("All done. ")
        assertNull(segmenter.flush("All done. "))
    }

    @Test
    fun `reset starts a new answer`() {
        val segmenter = SentenceSegmenter()
        segmenter.accept("First answer. ")
        segmenter.reset()
        assertEquals(listOf("Second answer."), segmenter.accept("Second answer. "))
    }
}
