package com.aliahad.aichat

import android.app.Application
import com.aliahad.aichat.data.AppDatabase
import com.aliahad.aichat.data.ChatRepository
import com.aliahad.aichat.data.RoomChatRepository
import com.aliahad.aichat.device.DeviceActionExecutor
import com.aliahad.aichat.device.PolicyControlledDeviceActionExecutor
import com.aliahad.aichat.attachment.AttachmentRepository
import com.aliahad.aichat.attachment.DefaultAttachmentRepository
import com.aliahad.aichat.activity.ActivityRepository
import com.aliahad.aichat.activity.RoomActivityRepository
import com.aliahad.aichat.activity.OfficeWorkScheduler
import com.aliahad.aichat.backup.EncryptedOfficeBackupRepository
import com.aliahad.aichat.backup.OfficeBackupRepository
import com.aliahad.aichat.inference.InferenceEngine
import com.aliahad.aichat.inference.NativeInferenceEngine
import com.aliahad.aichat.model.DefaultModelRepository
import com.aliahad.aichat.model.ModelRepository
import com.aliahad.aichat.memory.AppSearchMemoryIndexer
import com.aliahad.aichat.memory.ConversationSummaryRepository
import com.aliahad.aichat.memory.MemoryIndexer
import com.aliahad.aichat.memory.MemoryRepository
import com.aliahad.aichat.memory.PromptContextPlanner
import com.aliahad.aichat.memory.RoomMemoryRepository
import com.aliahad.aichat.context.ContextProfileRepository
import com.aliahad.aichat.context.RoomContextProfileRepository
import com.aliahad.aichat.residency.ModelResidencyController
import com.aliahad.aichat.settings.AppSettingsRepository
import com.aliahad.aichat.settings.TokenCipher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class AiChatApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        OfficeWorkScheduler.schedule(this)
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            container.chatRepository.markInterruptedMessages()
            container.attachmentRepository.markInterrupted()
            container.attachmentRepository.cleanupAbandonedDrafts()
            container.modelRepository.ensureOfficialRecords()
            container.settings.setBackend(com.aliahad.aichat.core.BackendMode.CPU)
            runCatching {
                container.memoryIndexer.rebuild(container.memoryRepository.memories.first())
            }
        }
    }
}

class AppContainer(val application: Application) {
    val database: AppDatabase = AppDatabase.create(application)
    val settings = AppSettingsRepository(application, TokenCipher(application))
    val chatRepository: ChatRepository = RoomChatRepository(database)
    val memoryIndexer: MemoryIndexer = AppSearchMemoryIndexer(application)
    val memoryRepository: MemoryRepository = RoomMemoryRepository(database, memoryIndexer)
    val contextProfileRepository: ContextProfileRepository =
        RoomContextProfileRepository(application, database.modelContextProfileDao())
    val activityRepository: ActivityRepository = RoomActivityRepository(database)
    val conversationSummaryRepository = ConversationSummaryRepository(database)
    val attachmentRepository: AttachmentRepository = DefaultAttachmentRepository(application, database)
    val inferenceEngine: InferenceEngine = NativeInferenceEngine(application)
    lateinit var promptContextPlanner: PromptContextPlanner
        private set
    lateinit var residencyController: ModelResidencyController
        private set
    val modelRepository: ModelRepository = DefaultModelRepository(
        context = application,
        dao = database.modelDao(),
        projectorDao = database.projectorDao(),
        settings = settings,
        isPathInUse = { path ->
            if (::residencyController.isInitialized) {
                residencyController.isPathInUse(path)
            } else {
                inferenceEngine.loadedModelPath == path
            }
        },
    )
    val deviceActionExecutor: DeviceActionExecutor = PolicyControlledDeviceActionExecutor(
        context = application,
        database = database,
        settings = settings,
    )
    val officeBackupRepository: OfficeBackupRepository = EncryptedOfficeBackupRepository(
        context = application,
        database = database,
        settings = settings,
    )

    init {
        promptContextPlanner = PromptContextPlanner(
            inferenceEngine = inferenceEngine,
            memoryRepository = memoryRepository,
            summaries = conversationSummaryRepository,
        )
        residencyController = ModelResidencyController(
            context = application,
            inferenceEngine = inferenceEngine,
            modelRepository = modelRepository,
            attachmentRepository = attachmentRepository,
            contextProfiles = contextProfileRepository,
            settingsRepository = settings,
        )
    }

}
