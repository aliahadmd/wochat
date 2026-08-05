package com.aliahad.aichat.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.aliahad.aichat.core.BackendMode
import kotlinx.coroutines.flow.Flow

@Dao
interface ModelBenchmarkDao {
    @Query("SELECT * FROM model_benchmarks WHERE modelId = :modelId ORDER BY measuredAt DESC")
    fun observeForModel(modelId: String): Flow<List<ModelBenchmarkEntity>>

    @Query(
        "SELECT * FROM model_benchmarks WHERE modelSha256 = :modelSha256 " +
            "AND deviceFingerprint = :deviceFingerprint AND backend = :backend " +
            "AND llamaRevision = :llamaRevision LIMIT 1",
    )
    suspend fun get(
        modelSha256: String,
        deviceFingerprint: String,
        backend: BackendMode,
        llamaRevision: String,
    ): ModelBenchmarkEntity?

    @Query(
        "SELECT * FROM model_benchmarks WHERE modelSha256 = :modelSha256 " +
            "AND deviceFingerprint = :deviceFingerprint AND llamaRevision = :llamaRevision " +
            "ORDER BY measuredAt DESC",
    )
    suspend fun forConfiguration(
        modelSha256: String,
        deviceFingerprint: String,
        llamaRevision: String,
    ): List<ModelBenchmarkEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(benchmark: ModelBenchmarkEntity)

    @Query("DELETE FROM model_benchmarks WHERE modelId = :modelId")
    suspend fun deleteForModel(modelId: String)
}
