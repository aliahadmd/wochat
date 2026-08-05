package com.aliahad.aichat.context

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import com.aliahad.aichat.BuildConfig
import com.aliahad.aichat.core.BackendMode
import com.aliahad.aichat.core.ContextVerificationMetrics
import com.aliahad.aichat.core.ContextVerificationState
import com.aliahad.aichat.core.ModelContextProfile
import com.aliahad.aichat.core.ModelRecord
import com.aliahad.aichat.data.ModelContextProfileDao
import com.aliahad.aichat.data.ModelContextProfileEntity
import com.aliahad.aichat.settings.AppSettingsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.security.MessageDigest

interface ContextProfileRepository {
    val profiles: Flow<List<ModelContextProfile>>
    suspend fun resolve(
        model: ModelRecord,
        backendOverride: BackendMode? = null,
    ): ModelContextProfile
    suspend fun recordDeclared(profile: ModelContextProfile, declaredTokens: Int): ModelContextProfile
    suspend fun markAttempt(profile: ModelContextProfile, candidateTokens: Int): ModelContextProfile
    suspend fun recordPassed(
        profile: ModelContextProfile,
        candidateTokens: Int,
        declaredTokens: Int,
        metrics: ContextVerificationMetrics,
    ): ModelContextProfile
    suspend fun recordFailure(
        profile: ModelContextProfile,
        candidateTokens: Int,
        reason: String,
        metrics: ContextVerificationMetrics?,
    ): ModelContextProfile
    suspend fun markPaused(profile: ModelContextProfile): ModelContextProfile
    suspend fun resetVerification(model: ModelRecord): ModelContextProfile
    suspend fun latestForModel(modelId: String): ModelContextProfile?
    suspend fun deleteForModel(modelId: String)
}

class RoomContextProfileRepository(
    context: Context,
    private val dao: ModelContextProfileDao,
    private val settings: AppSettingsRepository,
) : ContextProfileRepository {
    private val device = DeviceContextIdentity.read(context)

    override val profiles: Flow<List<ModelContextProfile>> =
        dao.observeAll().map { rows ->
            rows.filter { it.deviceFingerprint == device.key }
                .map(ModelContextProfileEntity::toDomain)
        }

    override suspend fun resolve(
        model: ModelRecord,
        backendOverride: BackendMode?,
    ): ModelContextProfile {
        val modelHash = requireNotNull(model.sha256) { "The model fingerprint is unavailable" }
        val backend = backendOverride ?: settings.effectiveBackend(
                modelHash,
                device.key,
                BuildConfig.LLAMA_RUNTIME_REVISION,
            )
        val id = sha256("$modelHash:${device.key}:${backend.name}")
        val existing = dao.get(id)
        if (existing != null) {
            val domain = existing.toDomain()
            if (domain.state == ContextVerificationState.VERIFYING) {
                return update(
                    domain.copy(
                        state = if (domain.verifiedContextTokens > 0) {
                            ContextVerificationState.LIMITED
                        } else {
                            ContextVerificationState.FAILED
                        },
                        failureReason =
                            "The app stopped while testing ${domain.lastAttemptedTokens ?: 0} tokens.",
                        updatedAt = System.currentTimeMillis(),
                    ),
                )
            }
            return domain
        }
        val now = System.currentTimeMillis()
        return update(
            ModelContextProfile(
                id = id,
                modelId = model.id,
                modelSha256 = modelHash,
                deviceFingerprint = device.key,
                physicalRamBytes = device.physicalRamBytes,
                swapBytes = device.swapBytes,
                backend = backend,
                llamaRevision = BuildConfig.LLAMA_RUNTIME_REVISION,
                declaredContextTokens = 0,
                verifiedContextTokens = 0,
                lastAttemptedTokens = null,
                state = ContextVerificationState.UNVERIFIED,
                peakPssBytes = null,
                peakRssBytes = null,
                peakSwapBytes = null,
                failureReason = null,
                verifiedAt = null,
                updatedAt = now,
            ),
        )
    }

    override suspend fun recordDeclared(
        profile: ModelContextProfile,
        declaredTokens: Int,
    ): ModelContextProfile = update(
        profile.copy(
            declaredContextTokens = declaredTokens.coerceAtLeast(1),
            updatedAt = System.currentTimeMillis(),
        ),
    )

    override suspend fun markAttempt(
        profile: ModelContextProfile,
        candidateTokens: Int,
    ): ModelContextProfile = update(
        profile.copy(
            lastAttemptedTokens = candidateTokens,
            state = ContextVerificationState.VERIFYING,
            failureReason = null,
            updatedAt = System.currentTimeMillis(),
        ),
    )

    override suspend fun recordPassed(
        profile: ModelContextProfile,
        candidateTokens: Int,
        declaredTokens: Int,
        metrics: ContextVerificationMetrics,
    ): ModelContextProfile = update(
        profile.copy(
            declaredContextTokens = declaredTokens,
            verifiedContextTokens = maxOf(profile.verifiedContextTokens, candidateTokens),
            lastAttemptedTokens = candidateTokens,
            state = ContextVerificationState.VERIFIED,
            peakPssBytes = maxNullable(profile.peakPssBytes, metrics.pssBytes),
            peakRssBytes = maxNullable(profile.peakRssBytes, metrics.rssBytes),
            peakSwapBytes = maxNullable(profile.peakSwapBytes, metrics.swapBytes),
            failureReason = null,
            verifiedAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis(),
        ),
    )

    override suspend fun recordFailure(
        profile: ModelContextProfile,
        candidateTokens: Int,
        reason: String,
        metrics: ContextVerificationMetrics?,
    ): ModelContextProfile = update(
        profile.copy(
            lastAttemptedTokens = candidateTokens,
            state = if (profile.verifiedContextTokens > 0) {
                ContextVerificationState.LIMITED
            } else {
                ContextVerificationState.FAILED
            },
            peakPssBytes = maxNullable(profile.peakPssBytes, metrics?.pssBytes),
            peakRssBytes = maxNullable(profile.peakRssBytes, metrics?.rssBytes),
            peakSwapBytes = maxNullable(profile.peakSwapBytes, metrics?.swapBytes),
            failureReason = reason.take(500),
            updatedAt = System.currentTimeMillis(),
        ),
    )

    override suspend fun markPaused(profile: ModelContextProfile): ModelContextProfile = update(
        profile.copy(
            state = if (profile.verifiedContextTokens > 0) {
                ContextVerificationState.VERIFIED
            } else {
                ContextVerificationState.UNVERIFIED
            },
            updatedAt = System.currentTimeMillis(),
        ),
    )

    override suspend fun resetVerification(model: ModelRecord): ModelContextProfile {
        val profile = resolve(model)
        return update(
            profile.copy(
                lastAttemptedTokens = null,
                state = ContextVerificationState.UNVERIFIED,
                failureReason = null,
                updatedAt = System.currentTimeMillis(),
            ),
        )
    }

    override suspend fun latestForModel(modelId: String): ModelContextProfile? =
        dao.latestForModel(modelId, device.key, settings.effectiveBackend())?.toDomain()

    override suspend fun deleteForModel(modelId: String) {
        dao.deleteForModel(modelId)
    }

    private suspend fun update(profile: ModelContextProfile): ModelContextProfile {
        dao.upsert(profile.toEntity())
        return profile
    }

    companion object {
        const val SAFE_CONTEXT_TOKENS = 4_096
    }
}

data class DeviceContextIdentity(
    val key: String,
    val physicalRamBytes: Long,
    val swapBytes: Long,
) {
    companion object {
        fun read(context: Context): DeviceContextIdentity {
            val memory = ActivityManager.MemoryInfo()
            context.getSystemService(ActivityManager::class.java).getMemoryInfo(memory)
            val swap = readMemInfoBytes("SwapTotal")
            val raw = listOf(
                Build.FINGERPRINT,
                memory.totalMem.toString(),
                swap.toString(),
                BackendMode.CPU.name,
                BuildConfig.LLAMA_RUNTIME_REVISION,
                CONTEXT_PROFILE_POLICY_REVISION.toString(),
            ).joinToString("|")
            return DeviceContextIdentity(
                key = sha256(raw),
                physicalRamBytes = memory.totalMem,
                swapBytes = swap,
            )
        }

        private fun readMemInfoBytes(name: String): Long =
            runCatching {
                java.io.File("/proc/meminfo").useLines { lines ->
                    lines.firstOrNull { it.startsWith("$name:") }
                        ?.substringAfter(':')
                        ?.trim()
                        ?.substringBefore(' ')
                        ?.toLong()
                        ?.times(1_024)
                        ?: 0L
                }
            }.getOrDefault(0L)

        private const val CONTEXT_PROFILE_POLICY_REVISION = 2
    }
}

private fun ModelContextProfileEntity.toDomain() = ModelContextProfile(
    id = id,
    modelId = modelId,
    modelSha256 = modelSha256,
    deviceFingerprint = deviceFingerprint,
    physicalRamBytes = physicalRamBytes,
    swapBytes = swapBytes,
    backend = backend,
    llamaRevision = llamaRevision,
    declaredContextTokens = declaredContextTokens,
    verifiedContextTokens = verifiedContextTokens,
    lastAttemptedTokens = lastAttemptedTokens,
    state = state,
    peakPssBytes = peakPssBytes,
    peakRssBytes = peakRssBytes,
    peakSwapBytes = peakSwapBytes,
    failureReason = failureReason,
    verifiedAt = verifiedAt,
    updatedAt = updatedAt,
)

private fun ModelContextProfile.toEntity() = ModelContextProfileEntity(
    id = id,
    modelId = modelId,
    modelSha256 = modelSha256,
    deviceFingerprint = deviceFingerprint,
    physicalRamBytes = physicalRamBytes,
    swapBytes = swapBytes,
    backend = backend,
    llamaRevision = llamaRevision,
    declaredContextTokens = declaredContextTokens,
    verifiedContextTokens = verifiedContextTokens,
    lastAttemptedTokens = lastAttemptedTokens,
    state = state,
    peakPssBytes = peakPssBytes,
    peakRssBytes = peakRssBytes,
    peakSwapBytes = peakSwapBytes,
    failureReason = failureReason,
    verifiedAt = verifiedAt,
    updatedAt = updatedAt,
)

private fun maxNullable(first: Long?, second: Long?): Long? = when {
    first == null -> second
    second == null -> first
    else -> maxOf(first, second)
}

private fun sha256(value: String): String =
    MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
