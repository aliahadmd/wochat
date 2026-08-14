package com.aliahad.aichat.activity

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OneTimeCodeRedactorTest {

    @Test
    fun `six digit code in verification sentence is redacted`() {
        val result = OneTimeCodeRedactor.redact("Your verification code is 482913")
        assertFalse(result.contains("482913"))
        assertTrue(result.contains(OneTimeCodeRedactor.PLACEHOLDER))
    }

    @Test
    fun `alphanumeric otp after context word is redacted`() {
        val result = OneTimeCodeRedactor.redact("OTP: X7K2Q9")
        assertFalse(result.contains("X7K2Q9"))
        assertTrue(result.contains(OneTimeCodeRedactor.PLACEHOLDER))
    }

    @Test
    fun `ordinary meeting text is unchanged`() {
        val text = "Meeting at 10am in room 4"
        assertEquals(text, OneTimeCodeRedactor.redact(text))
    }

    @Test
    fun `invoice with short digit runs and no context word is unchanged`() {
        val text = "Invoice #12345 for \$1,234.56"
        assertEquals(text, OneTimeCodeRedactor.redact(text))
    }

    @Test
    fun `four digit code after context word is redacted`() {
        val result = OneTimeCodeRedactor.redact("code 1234")
        assertFalse(result.contains("1234"))
        assertTrue(result.contains(OneTimeCodeRedactor.PLACEHOLDER))
    }

    @Test
    fun `standalone six digit run is redacted`() {
        val result = OneTimeCodeRedactor.redact("123456")
        assertEquals(OneTimeCodeRedactor.PLACEHOLDER, result)
    }

    @Test
    fun `long digit run beyond eight digits is unchanged`() {
        val text = "Reference 123456789012 for your records"
        assertEquals(text, OneTimeCodeRedactor.redact(text))
    }
}
