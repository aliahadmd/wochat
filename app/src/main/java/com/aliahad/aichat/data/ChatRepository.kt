package com.aliahad.aichat.data

import androidx.room.withTransaction
import com.aliahad.aichat.core.ChatMessage
import com.aliahad.aichat.core.ChatQualityMode
import com.aliahad.aichat.core.Conversation
import com.aliahad.aichat.core.MessageRole
import com.aliahad.aichat.core.MessageStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.UUID

interface ChatRepository {
    val conversations: Flow<List<Conversation>>
    fun messages(conversationId: String): Flow<List<ChatMessage>>
    suspend fun getMessages(conversationId: String): List<ChatMessage>
    suspend fun createConversation(qualityMode: ChatQualityMode = ChatQualityMode.FAST): Conversation
    suspend fun setQualityMode(id: String, mode: ChatQualityMode)
    suspend fun addMessage(
        conversationId: String,
        role: MessageRole,
        content: String,
        status: MessageStatus = MessageStatus.COMPLETE,
    ): ChatMessage
    suspend fun updateMessage(message: ChatMessage)
    suspend fun deleteConversation(id: String)
    suspend fun markInterruptedMessages()
}

class RoomChatRepository(
    private val database: AppDatabase,
) : ChatRepository {
    private val conversationsDao = database.conversationDao()
    private val messagesDao = database.messageDao()

    override val conversations: Flow<List<Conversation>> =
        conversationsDao.observeAll().map { rows -> rows.map(ConversationEntity::toDomain) }

    override fun messages(conversationId: String): Flow<List<ChatMessage>> =
        messagesDao.observeForConversation(conversationId).map { rows -> rows.map(MessageEntity::toDomain) }

    override suspend fun getMessages(conversationId: String): List<ChatMessage> =
        messagesDao.getForConversation(conversationId).map(MessageEntity::toDomain)

    override suspend fun createConversation(qualityMode: ChatQualityMode): Conversation {
        val now = System.currentTimeMillis()
        return Conversation(
            id = UUID.randomUUID().toString(),
            title = "New chat",
            createdAt = now,
            updatedAt = now,
            qualityMode = qualityMode,
        ).also { conversationsDao.upsert(it.toEntity()) }
    }

    override suspend fun setQualityMode(id: String, mode: ChatQualityMode) {
        conversationsDao.updateQualityMode(id, mode, System.currentTimeMillis())
    }

    override suspend fun addMessage(
        conversationId: String,
        role: MessageRole,
        content: String,
        status: MessageStatus,
    ): ChatMessage {
        val now = System.currentTimeMillis()
        val message = ChatMessage(
            id = UUID.randomUUID().toString(),
            conversationId = conversationId,
            role = role,
            content = content,
            createdAt = now,
            status = status,
        )
        database.withTransaction {
            messagesDao.upsert(message.toEntity())
            conversationsDao.touch(conversationId, now)
            if (role == MessageRole.USER) {
                val conversation = conversationsDao.get(conversationId)
                if (conversation?.title == "New chat") {
                    conversationsDao.updateTitle(conversationId, titleFrom(content), now)
                }
            }
        }
        return message
    }

    override suspend fun updateMessage(message: ChatMessage) {
        database.withTransaction {
            messagesDao.update(message.toEntity())
            conversationsDao.touch(message.conversationId, System.currentTimeMillis())
        }
    }

    override suspend fun deleteConversation(id: String) {
        conversationsDao.delete(id)
    }

    override suspend fun markInterruptedMessages() {
        messagesDao.markInterruptedAsCancelled()
    }

    private fun titleFrom(content: String): String =
        content.trim().replace(Regex("\\s+"), " ").take(48).ifEmpty { "New chat" }
}

private fun ConversationEntity.toDomain() = Conversation(id, title, createdAt, updatedAt, qualityMode)
private fun Conversation.toEntity() = ConversationEntity(id, title, createdAt, updatedAt, qualityMode)
private fun MessageEntity.toDomain() = ChatMessage(id, conversationId, role, content, createdAt, status)
private fun ChatMessage.toEntity() = MessageEntity(id, conversationId, role, content, createdAt, status)
