package com.aliahad.aichat.activity

/**
 * Content-level redaction for one-time codes captured from notifications.
 *
 * The redactor is intentionally dumb and fully local: it pattern-matches the
 * shapes OTP/2FA codes usually take and replaces them with a placeholder.
 * Redacting benign text (a false positive) is the accepted failure mode;
 * leaking a one-time code into persisted memory is not.
 */
internal object OneTimeCodeRedactor {

    const val PLACEHOLDER = "[redacted code]"

    /** Standalone 6–8 digit runs at word boundaries. */
    private val DIGIT_RUN = Regex("""\b\d{6,8}\b""")

    /**
     * A 4–8 character alphanumeric run appearing within 0–12 non-alphanumeric
     * characters after an OTP-style context word.
     */
    private val CONTEXT_CODE = Regex(
        """(\b(?:code|otp|pin|passcode|verification|verify|2fa|authenticate)\b[^0-9A-Za-z]{0,12})([0-9A-Za-z]{4,8}\b)""",
        RegexOption.IGNORE_CASE,
    )

    fun redact(text: String): String {
        if (text.isEmpty()) return text
        val withContextCodes = CONTEXT_CODE.replace(text) { match ->
            match.groupValues[1] + PLACEHOLDER
        }
        val redacted = DIGIT_RUN.replace(withContextCodes, PLACEHOLDER)
        // Never collapse a non-blank capture to blank: keep a placeholder record.
        return if (redacted.isBlank() && text.isNotBlank()) PLACEHOLDER else redacted
    }
}
