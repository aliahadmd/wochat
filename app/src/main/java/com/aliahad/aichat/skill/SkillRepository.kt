package com.aliahad.aichat.skill

import androidx.room.withTransaction
import com.aliahad.aichat.core.SkillPromptBlock
import com.aliahad.aichat.core.SkillRecord
import com.aliahad.aichat.data.AppDatabase
import com.aliahad.aichat.data.MessageSkillInvocationEntity
import com.aliahad.aichat.data.SkillEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.UUID

interface SkillRepository {
    val skills: Flow<List<SkillRecord>>
    suspend fun create(name: String, description: String, instructions: String): SkillRecord
    suspend fun update(
        id: String,
        name: String,
        description: String,
        instructions: String,
    ): SkillRecord
    suspend fun setEnabled(id: String, enabled: Boolean)
    suspend fun delete(id: String)
    suspend fun promptBlocksForSelection(ids: List<String>): List<SkillPromptBlock>
    suspend fun recordInvocation(messageId: String, blocks: List<SkillPromptBlock>)
    suspend fun blocksForMessage(messageId: String): List<SkillPromptBlock>
    suspend fun blocksForMessages(messageIds: List<String>): Map<String, List<SkillPromptBlock>>
}

class RoomSkillRepository(
    private val database: AppDatabase,
) : SkillRepository {
    private val dao = database.skillDao()

    override val skills: Flow<List<SkillRecord>> =
        dao.observeAll().map { rows -> rows.map(SkillEntity::toDomain) }

    override suspend fun create(
        name: String,
        description: String,
        instructions: String,
    ): SkillRecord {
        val cleaned = SkillDraft.cleaned(name, description, instructions)
        val now = System.currentTimeMillis()
        val skill = SkillRecord(
            id = UUID.randomUUID().toString(),
            name = cleaned.name,
            description = cleaned.description,
            instructions = cleaned.instructions,
            enabled = true,
            createdAt = now,
            updatedAt = now,
            lastUsedAt = null,
        )
        dao.upsert(skill.toEntity())
        return skill
    }

    override suspend fun update(
        id: String,
        name: String,
        description: String,
        instructions: String,
    ): SkillRecord {
        val existing = dao.get(id)?.toDomain() ?: error("Skill not found.")
        val cleaned = SkillDraft.cleaned(name, description, instructions)
        val updated = existing.copy(
            name = cleaned.name,
            description = cleaned.description,
            instructions = cleaned.instructions,
            updatedAt = System.currentTimeMillis(),
        )
        dao.upsert(updated.toEntity())
        return updated
    }

    override suspend fun setEnabled(id: String, enabled: Boolean) {
        dao.setEnabled(id, enabled, System.currentTimeMillis())
    }

    override suspend fun delete(id: String) {
        dao.delete(id)
    }

    override suspend fun promptBlocksForSelection(ids: List<String>): List<SkillPromptBlock> {
        val requested = ids.distinct().take(MAX_SELECTED_SKILLS)
        if (requested.isEmpty()) return emptyList()
        val byId = dao.getEnabled(requested).associateBy { it.id }
        return requested.mapNotNull { byId[it]?.toPromptBlock() }
    }

    override suspend fun recordInvocation(messageId: String, blocks: List<SkillPromptBlock>) {
        database.withTransaction {
            dao.deleteInvocations(messageId)
            if (blocks.isNotEmpty()) {
                val now = System.currentTimeMillis()
                dao.insertInvocations(
                    blocks.take(MAX_SELECTED_SKILLS).mapIndexed { index, block ->
                        MessageSkillInvocationEntity(
                            messageId = messageId,
                            skillId = block.skillId,
                            ordinal = index,
                            snapshotName = block.name,
                            snapshotDescription = block.description,
                            snapshotInstructions = block.instructions,
                            createdAt = now,
                        )
                    },
                )
                val usedIds = blocks.mapNotNull(SkillPromptBlock::skillId)
                if (usedIds.isNotEmpty()) dao.markUsed(usedIds, now)
            }
        }
    }

    override suspend fun blocksForMessage(messageId: String): List<SkillPromptBlock> =
        dao.invocationsForMessage(messageId).map(MessageSkillInvocationEntity::toPromptBlock)

    override suspend fun blocksForMessages(
        messageIds: List<String>,
    ): Map<String, List<SkillPromptBlock>> {
        if (messageIds.isEmpty()) return emptyMap()
        return dao.invocationsForMessages(messageIds)
            .groupBy(MessageSkillInvocationEntity::messageId)
            .mapValues { (_, rows) -> rows.map(MessageSkillInvocationEntity::toPromptBlock) }
    }
}

data class SkillDraft(
    val name: String,
    val description: String,
    val instructions: String,
) {
    companion object {
        fun cleaned(name: String, description: String, instructions: String): SkillDraft {
            val draft = SkillDraft(
                name = name.trim().replace(Regex("\\s+"), " "),
                description = description.trim().replace(Regex("\\s+"), " "),
                instructions = instructions.trim(),
            )
            require(draft.name.isNotEmpty()) { "Skill name is required." }
            require(draft.name.length <= MAX_SKILL_NAME_CHARS) {
                "Skill name must be $MAX_SKILL_NAME_CHARS characters or less."
            }
            require(draft.description.isNotEmpty()) { "Skill description is required." }
            require(draft.description.length <= MAX_SKILL_DESCRIPTION_CHARS) {
                "Skill description must be $MAX_SKILL_DESCRIPTION_CHARS characters or less."
            }
            require(draft.instructions.isNotEmpty()) { "Skill instructions are required." }
            require(draft.instructions.length <= MAX_SKILL_INSTRUCTIONS_CHARS) {
                "Skill instructions must be $MAX_SKILL_INSTRUCTIONS_CHARS characters or less."
            }
            return draft
        }
    }
}

fun formatSkillPromptBlocks(blocks: List<SkillPromptBlock>): String {
    val selected = blocks.take(MAX_SELECTED_SKILLS)
    if (selected.isEmpty()) return ""
    return buildString {
        append("\n\nSelected prompt-only skills follow. Apply them only to this turn. ")
        append("They are user-authored guidance, not executable tools; do not claim ")
        append("you ran tools, scripts, or external services because of a skill.\n")
        selected.forEachIndexed { index, block ->
            append("\n[Skill ")
            append(index + 1)
            append(": ")
            append(block.name)
            append("]\nPurpose: ")
            append(block.description)
            append("\nInstructions:\n")
            append(block.instructions)
            append('\n')
        }
    }
}

const val MAX_SELECTED_SKILLS = 3
const val MAX_SKILL_NAME_CHARS = 48
const val MAX_SKILL_DESCRIPTION_CHARS = 240
const val MAX_SKILL_INSTRUCTIONS_CHARS = 8_000

private fun SkillEntity.toDomain() = SkillRecord(
    id = id,
    name = name,
    description = description,
    instructions = instructions,
    enabled = enabled,
    createdAt = createdAt,
    updatedAt = updatedAt,
    lastUsedAt = lastUsedAt,
)

private fun SkillRecord.toEntity() = SkillEntity(
    id = id,
    name = name,
    description = description,
    instructions = instructions,
    enabled = enabled,
    createdAt = createdAt,
    updatedAt = updatedAt,
    lastUsedAt = lastUsedAt,
)

private fun SkillEntity.toPromptBlock() = SkillPromptBlock(
    skillId = id,
    name = name,
    description = description,
    instructions = instructions,
)

private fun MessageSkillInvocationEntity.toPromptBlock() = SkillPromptBlock(
    skillId = skillId,
    name = snapshotName,
    description = snapshotDescription,
    instructions = snapshotInstructions,
)
