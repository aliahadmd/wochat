package com.aliahad.aichat.data

import androidx.room.withTransaction
import com.aliahad.aichat.core.ChatMessage
import com.aliahad.aichat.core.ChatQualityMode
import com.aliahad.aichat.core.Conversation
import com.aliahad.aichat.core.MessageRole
import com.aliahad.aichat.core.MessageStatus
import com.aliahad.aichat.core.TurnOrigin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.UUID

interface ChatRepository {
    val conversations: Flow<List<Conversation>>
    fun messages(conversationId: String): Flow<List<ChatMessage>>
    suspend fun getMessages(conversationId: String): List<ChatMessage>
    suspend fun conversation(id: String): Conversation?
    suspend fun searchConversations(query: String): List<ChatSearchResult>
    suspend fun createConversation(
        qualityMode: ChatQualityMode = ChatQualityMode.FAST,
        temporary: Boolean = false,
    ): Conversation
    suspend fun setQualityMode(id: String, mode: ChatQualityMode)
    suspend fun addMessage(
        conversationId: String,
        role: MessageRole,
        content: String,
        status: MessageStatus = MessageStatus.COMPLETE,
        origin: TurnOrigin = TurnOrigin.TYPED,
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

    override suspend fun conversation(id: String): Conversation? =
        conversationsDao.get(id)?.toDomain()

    override suspend fun searchConversations(query: String): List<ChatSearchResult> {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return emptyList()
        val hits = messagesDao.searchContent(trimmed)
        if (hits.isEmpty()) return emptyList()
        val conversationsById = hits.map(MessageEntity::conversationId).distinct()
            .mapNotNull { conversationsDao.get(it) }
            .associateBy(ConversationEntity::id)
        return hits.groupBy(MessageEntity::conversationId)
            .mapNotNull { (conversationId, messages) ->
                val conversation = conversationsById[conversationId] ?: return@mapNotNull null
                ChatSearchResult(
                    conversationId = conversationId,
                    title = conversation.title,
                    snippet = snippetAround(messages.first().content, trimmed),
                    updatedAt = conversation.updatedAt,
                )
            }
            .sortedByDescending(ChatSearchResult::updatedAt)
    }

    override suspend fun createConversation(
        qualityMode: ChatQualityMode,
        temporary: Boolean,
    ): Conversation {
        val now = System.currentTimeMillis()
        return Conversation(
            id = UUID.randomUUID().toString(),
            title = "New chat",
            createdAt = now,
            updatedAt = now,
            qualityMode = qualityMode,
            temporary = temporary,
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
        origin: TurnOrigin,
    ): ChatMessage {
        val now = System.currentTimeMillis()
        val message = ChatMessage(
            id = UUID.randomUUID().toString(),
            conversationId = conversationId,
            role = role,
            content = content,
            createdAt = now,
            status = status,
            origin = origin,
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
            // Touching re-emits the whole conversations flow; during streaming that
            // fires ~4x/second. Only advance the conversation timestamp once the
            // message reaches a terminal state.
            if (message.status != MessageStatus.STREAMING) {
                conversationsDao.touch(message.conversationId, System.currentTimeMillis())
            }
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

private fun ConversationEntity.toDomain() =
    Conversation(id, title, createdAt, updatedAt, qualityMode, temporary)

/** A full-text chat search hit: the newest matching message per conversation. */
data class ChatSearchResult(
    val conversationId: String,
    val title: String,
    val snippet: String,
    val updatedAt: Long,
)

internal fun snippetAround(content: String, query: String, radius: Int = 60): String {
    val index = content.indexOf(query, ignoreCase = true)
    if (index < 0) return content.take(radius * 2).replace('\n', ' ')
    val start = (index - radius).coerceAtLeast(0)
    val end = (index + query.length + radius).coerceAtMost(content.length)
    return buildString {
        if (start > 0) append('…')
        append(content.substring(start, end).replace('\n', ' '))
        if (end < content.length) append('…')
    }
}

private fun Conversation.toEntity() =
    ConversationEntity(id, title, createdAt, updatedAt, qualityMode, temporary)

private fun MessageEntity.toDomain() = ChatMessage(
    id = id,
    conversationId = conversationId,
    role = role,
    content = content,
    createdAt = createdAt,
    status = status,
    origin = origin,
    stopReason = stopReason,
    continuationCount = continuationCount,
    promptTokens = promptTokens,
    generatedTokens = generatedTokens,
)

private fun ChatMessage.toEntity() = MessageEntity(
    id = id,
    conversationId = conversationId,
    role = role,
    content = content,
    createdAt = createdAt,
    status = status,
    origin = origin,
    stopReason = stopReason,
    continuationCount = continuationCount,
    promptTokens = promptTokens,
    generatedTokens = generatedTokens,
)
