package com.aliahad.aichat.ui.viewmodel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Capacity math for a picker selection. The defect these pin down is silent
 * truncation: the file picker used to `take(20)` without counting what was
 * already staged, so files vanished with no message at all.
 */
class AttachmentIntakeTest {

    @Test
    fun `accepts a selection that fits and says nothing`() {
        val intake = planAttachmentIntake(staged = 0, selected = 5, limit = 20)

        assertEquals(5, intake.accepted)
        assertEquals(0, intake.rejected)
        assertNull(intake.message)
    }

    @Test
    fun `accepts a selection that exactly reaches the limit without a message`() {
        val intake = planAttachmentIntake(staged = 15, selected = 5, limit = 20)

        assertEquals(5, intake.accepted)
        assertEquals(0, intake.rejected)
        assertNull(intake.message)
    }

    @Test
    fun `counts against attachments already staged`() {
        // The regression: 15 staged + 10 picked used to stage all 10.
        val intake = planAttachmentIntake(staged = 15, selected = 10, limit = 20)

        assertEquals(5, intake.accepted)
        assertEquals(5, intake.rejected)
        assertEquals(
            "Added 5 of 10 — a message can include up to 20 attachments.",
            intake.message,
        )
    }

    @Test
    fun `reports the limit when the draft is already full`() {
        val intake = planAttachmentIntake(staged = 20, selected = 3, limit = 20)

        assertEquals(0, intake.accepted)
        assertEquals(3, intake.rejected)
        assertEquals(
            "Attachment limit reached — a message can include up to 20.",
            intake.message,
        )
    }

    @Test
    fun `treats an over-full draft as having no remaining capacity`() {
        val intake = planAttachmentIntake(staged = 25, selected = 2, limit = 20)

        assertEquals(0, intake.accepted)
        assertEquals(2, intake.rejected)
    }

    @Test
    fun `truncates a selection larger than the whole limit`() {
        val intake = planAttachmentIntake(staged = 0, selected = 30, limit = 20)

        assertEquals(20, intake.accepted)
        assertEquals(10, intake.rejected)
        assertEquals(
            "Added 20 of 30 — a message can include up to 20 attachments.",
            intake.message,
        )
    }

    @Test
    fun `handles an empty selection`() {
        val intake = planAttachmentIntake(staged = 3, selected = 0, limit = 20)

        assertEquals(0, intake.accepted)
        assertEquals(0, intake.rejected)
        assertNull(intake.message)
    }
}
