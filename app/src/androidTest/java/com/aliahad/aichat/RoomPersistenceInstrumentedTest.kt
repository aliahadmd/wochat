package com.aliahad.aichat

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aliahad.aichat.core.MessageRole
import com.aliahad.aichat.data.AppDatabase
import com.aliahad.aichat.data.RoomChatRepository
import com.aliahad.aichat.core.ChatQualityMode
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RoomPersistenceInstrumentedTest {
    @Test
    fun roomPersistsMessagesAndCascadesConversationDelete() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            val repository = RoomChatRepository(database)
            val conversation = repository.createConversation()
            repository.addMessage(conversation.id, MessageRole.USER, "Hello local model")
            assertEquals(1, repository.getMessages(conversation.id).size)

            repository.deleteConversation(conversation.id)
            assertEquals(0, repository.getMessages(conversation.id).size)
        } finally {
            database.close()
        }
    }

    @Test
    fun roomPersistsConversationQualityMode() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            val repository = RoomChatRepository(database)
            val conversation = repository.createConversation(ChatQualityMode.BEST)
            assertEquals(ChatQualityMode.BEST, conversation.qualityMode)
            repository.setQualityMode(conversation.id, ChatQualityMode.FAST)
            assertEquals(ChatQualityMode.FAST, database.conversationDao().get(conversation.id)?.qualityMode)
        } finally {
            database.close()
        }
    }
}
