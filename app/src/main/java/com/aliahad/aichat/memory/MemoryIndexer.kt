package com.aliahad.aichat.memory

import android.app.appsearch.AppSearchBatchResult
import android.app.appsearch.AppSearchManager
import android.app.appsearch.AppSearchResult
import android.app.appsearch.AppSearchSchema
import android.app.appsearch.AppSearchSession
import android.app.appsearch.BatchResultCallback
import android.app.appsearch.GenericDocument
import android.app.appsearch.PutDocumentsRequest
import android.app.appsearch.RemoveByDocumentIdRequest
import android.app.appsearch.SearchSpec
import android.app.appsearch.SetSchemaRequest
import android.content.Context
import com.aliahad.aichat.core.MemoryItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

interface MemoryIndexer {
    suspend fun rebuild(items: List<MemoryItem>)
    suspend fun upsert(item: MemoryItem)
    suspend fun remove(id: String)
    suspend fun searchIds(query: String, limit: Int): List<String>
}

class AppSearchMemoryIndexer(
    context: Context,
) : MemoryIndexer, Closeable {
    private val manager = context.getSystemService(AppSearchManager::class.java)
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()

    override suspend fun rebuild(items: List<MemoryItem>) = withSession(forceSchema = true) { session ->
        if (items.isNotEmpty()) put(session, items)
    }

    override suspend fun upsert(item: MemoryItem) = withSession { session ->
        put(session, listOf(item))
    }

    override suspend fun remove(id: String) = withSession { session ->
        suspendCoroutine { continuation ->
            val request = RemoveByDocumentIdRequest.Builder(NAMESPACE)
                .addIds(id)
                .build()
            session.remove(
                request,
                executor,
                object : BatchResultCallback<String, Void> {
                    override fun onResult(result: AppSearchBatchResult<String, Void>) {
                        val onlyMissing = result.failures.values.all {
                            it.resultCode == AppSearchResult.RESULT_NOT_FOUND
                        }
                        if (result.isSuccess || onlyMissing) {
                            continuation.resume(Unit)
                        } else {
                            continuation.resumeWithException(
                                IllegalStateException(result.failures.toString()),
                            )
                        }
                    }

                    override fun onSystemError(throwable: Throwable?) {
                        continuation.resumeWithException(
                            throwable ?: IllegalStateException("AppSearch remove failed"),
                        )
                    }
                },
            )
        }
    }

    override suspend fun searchIds(query: String, limit: Int): List<String> =
        withSession { session ->
            if (query.isBlank()) return@withSession emptyList()
            val spec = SearchSpec.Builder()
                .addFilterSchemas(SCHEMA)
                .setTermMatch(SearchSpec.TERM_MATCH_PREFIX)
                .setRankingStrategy(SearchSpec.RANKING_STRATEGY_RELEVANCE_SCORE)
                .setResultCountPerPage(limit.coerceIn(1, 64))
                .build()
            val results = session.search(query, spec)
            try {
                suspendCoroutine { continuation ->
                    results.getNextPage(executor) { result ->
                        if (result.isSuccess) {
                            continuation.resume(
                                result.resultValue.orEmpty().map {
                                    it.genericDocument.id
                                },
                            )
                        } else {
                            continuation.resumeWithException(
                                IllegalStateException(result.errorMessage),
                            )
                        }
                    }
                }
            } finally {
                results.close()
            }
        }

    override fun close() {
        executor.shutdown()
    }

    private suspend fun <T> withSession(
        forceSchema: Boolean = false,
        block: suspend (AppSearchSession) -> T,
    ): T = withContext(Dispatchers.IO) {
        val session = createSession()
        try {
            setSchema(session, forceSchema)
            block(session)
        } finally {
            session.close()
        }
    }

    private suspend fun createSession(): AppSearchSession = suspendCoroutine { continuation ->
        manager.createSearchSession(
            AppSearchManager.SearchContext.Builder(DATABASE).build(),
            executor,
        ) { result ->
            if (result.isSuccess) {
                continuation.resume(requireNotNull(result.resultValue))
            } else {
                continuation.resumeWithException(IllegalStateException(result.errorMessage))
            }
        }
    }

    private suspend fun setSchema(session: AppSearchSession, forceOverride: Boolean) {
        val indexedText = AppSearchSchema.StringPropertyConfig.Builder(PROPERTY_TEXT)
            .setCardinality(AppSearchSchema.PropertyConfig.CARDINALITY_REQUIRED)
            .setTokenizerType(AppSearchSchema.StringPropertyConfig.TOKENIZER_TYPE_PLAIN)
            .setIndexingType(AppSearchSchema.StringPropertyConfig.INDEXING_TYPE_PREFIXES)
            .build()
        val type = AppSearchSchema.StringPropertyConfig.Builder(PROPERTY_TYPE)
            .setCardinality(AppSearchSchema.PropertyConfig.CARDINALITY_REQUIRED)
            .setTokenizerType(AppSearchSchema.StringPropertyConfig.TOKENIZER_TYPE_PLAIN)
            .setIndexingType(AppSearchSchema.StringPropertyConfig.INDEXING_TYPE_EXACT_TERMS)
            .build()
        val schema = AppSearchSchema.Builder(SCHEMA)
            .addProperty(indexedText)
            .addProperty(type)
            .build()
        val request = SetSchemaRequest.Builder()
            .addSchemas(schema)
            .setVersion(SCHEMA_VERSION)
            .setForceOverride(forceOverride)
            .build()
        suspendCoroutine { continuation ->
            session.setSchema(request, executor, executor) { result ->
                if (result.isSuccess) continuation.resume(Unit)
                else continuation.resumeWithException(IllegalStateException(result.errorMessage))
            }
        }
    }

    private suspend fun put(session: AppSearchSession, items: List<MemoryItem>) {
        val documents = items.map { item ->
            GenericDocument.Builder<GenericDocument.Builder<*>>(NAMESPACE, item.id, SCHEMA)
                .setCreationTimestampMillis(item.updatedAt)
                .setScore((item.importance.coerceIn(0f, 1f) * 10_000).toInt())
                .setPropertyString(PROPERTY_TEXT, "${item.title}\n${item.content}")
                .setPropertyString(PROPERTY_TYPE, item.type.name)
                .build()
        }
        val request = PutDocumentsRequest.Builder()
            .addGenericDocuments(documents)
            .build()
        suspendCoroutine { continuation ->
            session.put(
                request,
                executor,
                object : BatchResultCallback<String, Void> {
                    override fun onResult(result: AppSearchBatchResult<String, Void>) {
                        if (result.isSuccess) continuation.resume(Unit)
                        else continuation.resumeWithException(
                            IllegalStateException(result.failures.toString()),
                        )
                    }

                    override fun onSystemError(throwable: Throwable?) {
                        continuation.resumeWithException(
                            throwable ?: IllegalStateException("AppSearch indexing failed"),
                        )
                    }
                },
            )
        }
    }

    private companion object {
        const val DATABASE = "office-memory"
        const val NAMESPACE = "memories"
        const val SCHEMA = "MemoryItem"
        const val SCHEMA_VERSION = 1
        const val PROPERTY_TEXT = "text"
        const val PROPERTY_TYPE = "type"
    }
}
