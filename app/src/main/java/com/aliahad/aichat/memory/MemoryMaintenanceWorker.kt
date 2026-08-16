package com.aliahad.aichat.memory

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.aliahad.aichat.AiChatApplication
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * Expires memories that have outlived their `validTo`, and drops AppSearch documents
 * left behind by deleted rows.
 *
 * This is what survives of the old `ActivityRetentionWorker`. That worker mostly
 * compacted phone-source events into archive digests, which is gone along with the
 * collectors (plan 033) — but memory expiry applies to chat-derived memories too and
 * still has to run.
 */
class MemoryMaintenanceWorker(
    context: Context,
    parameters: WorkerParameters,
) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val container = (applicationContext as AiChatApplication).container
        val now = System.currentTimeMillis()
        try {
            container.memoryRepository.purgeExpiredMemories(now)
            container.memoryRepository.purgeStaleIndexDocs()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Log.e(TAG, "Memory maintenance failed", error)
            return Result.retry()
        }
        return Result.success()
    }

    private companion object {
        const val TAG = "MemoryMaintenance"
    }
}

object MemoryWorkScheduler {
    const val WORK_MEMORY_RETENTION = "office-memory-retention"

    /**
     * Unique names of periodic works that no longer exist. Installs upgrading from a
     * build with phone sources still have these enqueued, and WorkManager would keep
     * firing them at a worker class that is gone.
     */
    private val RETIRED_WORK_NAMES = listOf(
        "office-collect-all",
        "office-usage-collection",
        "office-installed-apps-collection",
        "office-location-collection",
        "office-sensor-collection",
        "office-contacts-collection",
        "office-calendar-collection",
    )

    val scheduledWorkNames: List<String> = listOf(WORK_MEMORY_RETENTION)

    fun schedule(context: Context) {
        val workManager = WorkManager.getInstance(context)
        RETIRED_WORK_NAMES.forEach(workManager::cancelUniqueWork)
        workManager.enqueueUniquePeriodicWork(
            WORK_MEMORY_RETENTION,
            ExistingPeriodicWorkPolicy.KEEP,
            request(),
        )
    }

    /**
     * Re-asserts the periodic work: if it is absent or in a terminal state it is
     * re-enqueued with UPDATE. Guards against OEM schedulers (HyperOS) dropping
     * periodic jobs while the app is closed.
     */
    suspend fun verifyAndRepair(context: Context) {
        withContext(Dispatchers.IO) {
            val workManager = WorkManager.getInstance(context)
            val states = runCatching {
                workManager.getWorkInfosForUniqueWork(WORK_MEMORY_RETENTION)
                    .get()
                    .map { it.state.name }
            }.getOrDefault(emptyList())
            if (isPeriodicWorkHealthy(states)) return@withContext
            Log.i(TAG, "Re-enqueueing $WORK_MEMORY_RETENTION")
            workManager.enqueueUniquePeriodicWork(
                WORK_MEMORY_RETENTION,
                ExistingPeriodicWorkPolicy.UPDATE,
                request(),
            )
        }
    }

    private fun request() = PeriodicWorkRequestBuilder<MemoryMaintenanceWorker>(
        1,
        TimeUnit.DAYS,
    ).build()

    private const val TAG = "MemoryWorkScheduler"
}

/** A periodic work is healthy when it is enqueued or running; anything else needs repair. */
internal fun isPeriodicWorkHealthy(states: List<String>): Boolean =
    states.isNotEmpty() && states.any { it == "ENQUEUED" || it == "RUNNING" }
