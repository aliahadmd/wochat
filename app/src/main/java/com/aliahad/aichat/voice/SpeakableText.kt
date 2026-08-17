package com.aliahad.aichat.voice

/**
 * Turns a chat answer into something worth hearing.
 *
 * The model writes for a screen: it emphasises with `**bold**`, cites with
 * `[text](url)`, and lays answers out as `- bullets`. Piper reads every one of
 * those characters aloud — the owner heard "asterisk asterisk Dhaka asterisk
 * asterisk" during a call — so the markup has to go before the text reaches the
 * speaker.
 *
 * Applied per spoken chunk rather than to the whole growing answer on purpose. The
 * segmenter tracks how much of the answer it has already spoken by index, and
 * cleaning a *growing* string moves those indices: "the capital is **Dhaka" and
 * "the capital is **Dhaka**" clean to different lengths, which would make the call
 * repeat or skip words. A finished chunk is stable, so it is cleaned once, on its
 * way out.
 */
internal fun speakableText(markdown: String): String {
    var text = markdown
    // Links first: the visible text is worth speaking, the URL never is.
    text = LINK.replace(text) { it.groupValues[1] }
    text = IMAGE.replace(text, "")
    // Fenced and inline code: keep what is inside, drop the ticks.
    text = FENCE.replace(text, "")
    text = INLINE_CODE.replace(text) { it.groupValues[1] }
    // Emphasis. Bounded so that snake_case identifiers and 2 * 3 survive intact.
    text = BOLD_ASTERISK.replace(text) { it.groupValues[1] }
    text = BOLD_UNDERSCORE.replace(text) { it.groupValues[1] }
    text = ITALIC_ASTERISK.replace(text) { it.groupValues[1] }
    text = ITALIC_UNDERSCORE.replace(text) { it.groupValues[1] }
    text = STRIKETHROUGH.replace(text) { it.groupValues[1] }
    // Line-leading furniture: headings, bullets, numbering, quotes, rules.
    text = HORIZONTAL_RULE.replace(text, "")
    text = HEADING.replace(text, "")
    text = BLOCKQUOTE.replace(text, "")
    text = BULLET.replace(text, "")
    text = NUMBERED.replace(text) { it.groupValues[1] + ". " }
    // Table pipes become pauses rather than the word "pipe". Row edges go first,
    // or "| Dhaka | Bangladesh |" starts and ends on a stray comma.
    text = TABLE_EDGE.replace(text, "")
    text = TABLE_PIPE.replace(text, ", ")
    return text.replace(WHITESPACE_RUN, " ").trim()
}

private val LINK = Regex("""\[([^\]]*)\]\([^)]*\)""")
private val IMAGE = Regex("""!\[[^\]]*\]\([^)]*\)""")
private val FENCE = Regex("""```[a-zA-Z0-9+#-]*""")
private val INLINE_CODE = Regex("""`([^`]+)`""")
private val BOLD_ASTERISK = Regex("""\*\*([^*]+)\*\*""")
private val BOLD_UNDERSCORE = Regex("""(?<![\w])__([^_]+)__(?![\w])""")
private val ITALIC_ASTERISK = Regex("""(?<!\*)\*([^*\n]+)\*(?!\*)""")
private val ITALIC_UNDERSCORE = Regex("""(?<![\w])_([^_\n]+)_(?![\w])""")
private val STRIKETHROUGH = Regex("""~~([^~]+)~~""")
private val HORIZONTAL_RULE = Regex("""(?m)^\s*([-*_]\s*){3,}$""")
private val HEADING = Regex("""(?m)^\s{0,3}#{1,6}\s+""")
private val BLOCKQUOTE = Regex("""(?m)^\s{0,3}>\s?""")
private val BULLET = Regex("""(?m)^\s{0,3}[-*+]\s+""")
private val NUMBERED = Regex("""(?m)^\s{0,3}(\d{1,2})[.)]\s+""")
private val TABLE_EDGE = Regex("""(?m)^\s*\||\|\s*$""")
private val TABLE_PIPE = Regex("""\s*\|\s*""")
private val WHITESPACE_RUN = Regex("""\s+""")
