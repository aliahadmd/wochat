package com.aliahad.aichat

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aliahad.aichat.core.MemoryItem
import com.aliahad.aichat.core.MemorySensitivity
import com.aliahad.aichat.core.MemoryStatus
import com.aliahad.aichat.core.MemoryType
import com.aliahad.aichat.memory.AppSearchMemoryIndexer
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppSearchMemoryIndexerInstrumentedTest {
    @Test
    fun rebuildSearchAndRemove() = runBlocking {
        val application = ApplicationProvider.getApplicationContext<AiChatApplication>()
        val indexer = AppSearchMemoryIndexer(application)
        val now = System.currentTimeMillis()
        val memory = MemoryItem(
            id = "appsearch-test-memory",
            type = MemoryType.PROJECT,
            title = "AIchat project",
            content = "The private office assistant runs Gemma locally on the Redmi phone.",
            confidence = 1f,
            importance = 0.9f,
            sensitivity = MemorySensitivity.PRIVATE,
            status = MemoryStatus.ACTIVE,
            pinned = true,
            validFrom = now,
            validTo = null,
            supersedesId = null,
            createdAt = now,
            updatedAt = now,
        )

        try {
            indexer.rebuild(listOf(memory))
            assertEquals(listOf(memory.id), indexer.searchIds("private office", 10))

            indexer.remove(memory.id)
            assertTrue(indexer.searchIds("private office", 10).isEmpty())
        } finally {
            indexer.close()
        }
    }
}
