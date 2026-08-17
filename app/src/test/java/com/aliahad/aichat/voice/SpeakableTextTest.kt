package com.aliahad.aichat.voice

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The reported failure and its neighbours. Piper reads punctuation literally, so
 * every one of these was audible as markup during a call.
 */
class SpeakableTextTest {

    @Test
    fun `the reported case - bold is spoken as words, not as asterisks`() {
        // Heard on the device as "asterisk asterisk Dhaka asterisk asterisk".
        assertEquals(
            "The capital of Bangladesh is Dhaka.",
            speakableText("The capital of Bangladesh is **Dhaka**."),
        )
    }

    @Test
    fun `italics lose their markers`() {
        assertEquals("That is really important.", speakableText("That is *really* important."))
        assertEquals("That is really important.", speakableText("That is _really_ important."))
    }

    @Test
    fun `underscores inside identifiers survive`() {
        // "file_name" must not become "filename", and must not swallow the sentence.
        assertEquals("Open file_name now.", speakableText("Open file_name now."))
    }

    @Test
    fun `multiplication is not mistaken for emphasis`() {
        assertEquals("It is 2 * 3 = 6.", speakableText("It is 2 * 3 = 6."))
    }

    @Test
    fun `a link is read as its text, never its url`() {
        assertEquals(
            "See the docs for more.",
            speakableText("See [the docs](https://example.com/a/b?c=d) for more."),
        )
    }

    @Test
    fun `headings lose their hashes`() {
        assertEquals("Summary", speakableText("## Summary"))
    }

    @Test
    fun `bullets are read as sentences, not as dashes`() {
        assertEquals(
            "Apples Oranges Pears",
            speakableText("- Apples\n- Oranges\n- Pears"),
        )
    }

    @Test
    fun `numbered lists keep their numbers`() {
        assertEquals("1. Wake up 2. Stand up", speakableText("1. Wake up\n2. Stand up"))
    }

    @Test
    fun `inline code keeps the word inside it`() {
        assertEquals("Run gradlew build now.", speakableText("Run `gradlew build` now."))
    }

    @Test
    fun `code fences do not become backticks`() {
        assertEquals("val x = 1", speakableText("```kotlin\nval x = 1\n```"))
    }

    @Test
    fun `blockquotes lose the angle bracket`() {
        assertEquals("To be or not to be", speakableText("> To be or not to be"))
    }

    @Test
    fun `a horizontal rule is silent rather than three dashes`() {
        assertEquals("Before After", speakableText("Before\n---\nAfter"))
    }

    @Test
    fun `table pipes become pauses`() {
        assertEquals("Dhaka, Bangladesh", speakableText("| Dhaka | Bangladesh |"))
    }

    @Test
    fun `plain prose is returned unchanged`() {
        val plain = "The capital of France is Paris."
        assertEquals(plain, speakableText(plain))
    }

    @Test
    fun `mixed markup in one sentence is fully cleaned`() {
        assertEquals(
            "Dhaka is the capital, see Wikipedia for the population.",
            speakableText("**Dhaka** is the _capital_, see [Wikipedia](https://w.org) for the `population`."),
        )
    }
}
