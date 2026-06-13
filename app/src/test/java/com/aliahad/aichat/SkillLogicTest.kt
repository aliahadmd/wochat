package com.aliahad.aichat

import com.aliahad.aichat.core.SkillPromptBlock
import com.aliahad.aichat.skill.MAX_SELECTED_SKILLS
import com.aliahad.aichat.skill.SkillDraft
import com.aliahad.aichat.skill.formatSkillPromptBlocks
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SkillLogicTest {
    @Test
    fun skillDraftTrimsAndValidatesRequiredFields() {
        val draft = SkillDraft.cleaned(
            name = "  Kotlin    reviewer  ",
            description = "  Reviews Kotlin code  ",
            instructions = "\nPrefer small focused changes.\n",
        )

        assertEquals("Kotlin reviewer", draft.name)
        assertEquals("Reviews Kotlin code", draft.description)
        assertEquals("Prefer small focused changes.", draft.instructions)
    }

    @Test(expected = IllegalArgumentException::class)
    fun skillDraftRejectsBlankInstructions() {
        SkillDraft.cleaned(
            name = "Reviewer",
            description = "Reviews code",
            instructions = " ",
        )
    }

    @Test
    fun skillPromptFormattingIsPromptOnlyAndBounded() {
        val blocks = (1..5).map {
            SkillPromptBlock(
                skillId = "skill-$it",
                name = "Skill $it",
                description = "Description $it",
                instructions = "Instruction $it",
            )
        }

        val prompt = formatSkillPromptBlocks(blocks)

        assertTrue(prompt.contains("prompt-only skills"))
        assertTrue(prompt.contains("not executable tools"))
        assertTrue(prompt.contains("Skill $MAX_SELECTED_SKILLS"))
        assertFalse(prompt.contains("Skill ${MAX_SELECTED_SKILLS + 1}"))
    }
}
