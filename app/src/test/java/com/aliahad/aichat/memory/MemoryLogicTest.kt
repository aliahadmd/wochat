package com.aliahad.aichat.memory

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryLogicTest {
    @Test
    fun redactorRemovesCredentialsAndPaymentNumbers() {
        val value = SensitiveTextRedactor.redact(
            "My OTP is 123456, password: secret123 and card 4111 1111 1111 1111",
        )

        assertFalse(value.contains("123456"))
        assertFalse(value.contains("secret123"))
        assertFalse(value.contains("4111"))
        assertTrue(value.contains("[redacted]"))
    }
}
