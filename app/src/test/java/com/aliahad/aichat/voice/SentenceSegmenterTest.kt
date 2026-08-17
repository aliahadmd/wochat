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

/**
 * The first chunk may end at a clause boundary; later ones may not. Step 8 measured
 * 17 s of generation between the first token and the first full sentence, which is
 * what these buy back.
 */
class FirstChunkClauseTest {

    @Test
    fun `first chunk ends at a comma once it is long enough`() {
        val segmenter = SentenceSegmenter()
        assertEquals(
            listOf("The capital of France is Paris,"),
            segmenter.accept("The capital of France is Paris, and it is known for "),
        )
    }

    @Test
    fun `a short opening clause is not spoken alone`() {
        val segmenter = SentenceSegmenter()
        // "Well," on its own reads as a stutter, not a faster reply.
        assertEquals(emptyList<String>(), segmenter.accept("Well, the answer "))
    }

    @Test
    fun `later chunks wait for a real sentence`() {
        val segmenter = SentenceSegmenter()
        segmenter.accept("The capital of France is Paris, ")
        // A second comma must not split; only sentence ends do from here on.
        assertEquals(
            emptyList<String>(),
            segmenter.accept("The capital of France is Paris, a large city, with "),
        )
        assertEquals(
            listOf("a large city, with many museums."),
            segmenter.accept("The capital of France is Paris, a large city, with many museums. Next"),
        )
    }

    @Test
    fun `a thousands separator never breaks a chunk`() {
        val segmenter = SentenceSegmenter()
        assertEquals(
            emptyList<String>(),
            segmenter.accept("The population is about 2,100,000 people in the "),
        )
    }

    @Test
    fun `a sentence still wins over a clause when it comes first`() {
        val segmenter = SentenceSegmenter()
        assertEquals(listOf("Paris is the capital."), segmenter.accept("Paris is the capital. It is "))
    }

    @Test
    fun `reset restores clause-breaking for the next answer`() {
        val segmenter = SentenceSegmenter()
        segmenter.accept("The capital of France is Paris, and ")
        segmenter.reset()
        assertEquals(
            listOf("The capital of Japan is Tokyo,"),
            segmenter.accept("The capital of Japan is Tokyo, and "),
        )
    }
}
