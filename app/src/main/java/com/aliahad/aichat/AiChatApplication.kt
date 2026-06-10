package com.aliahad.aichat

import android.app.Application
import com.aliahad.aichat.data.AppDatabase
import com.aliahad.aichat.data.ChatRepository
import com.aliahad.aichat.data.RoomChatRepository
import com.aliahad.aichat.attachment.AttachmentRepository
import com.aliahad.aichat.attachment.DefaultAttachmentRepository
import com.aliahad.aichat.inference.InferenceEngine
import com.aliahad.aichat.inference.NativeInferenceEngine
import com.aliahad.aichat.model.DefaultModelRepository
import com.aliahad.aichat.model.ModelRepository
import com.aliahad.aichat.residency.ModelResidencyController
import com.aliahad.aichat.settings.AppSettingsRepository
import com.aliahad.aichat.settings.TokenCipher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class AiChatApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            container.chatRepository.markInterruptedMessages()
            container.attachmentRepository.markInterrupted()
            container.attachmentRepository.cleanupAbandonedDrafts()
            container.modelRepository.ensureOfficialRecords()
            container.settings.setBackend(com.aliahad.aichat.core.BackendMode.CPU)
        }
    }
}

class AppContainer(application: Application) {
    val database: AppDatabase = AppDatabase.create(application)
    val settings = AppSettingsRepository(application, TokenCipher(application))
    val chatRepository: ChatRepository = RoomChatRepository(database)
    val attachmentRepository: AttachmentRepository = DefaultAttachmentRepository(application, database)
    val inferenceEngine: InferenceEngine = NativeInferenceEngine(application)
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

    init {
        residencyController = ModelResidencyController(
            context = application,
            inferenceEngine = inferenceEngine,
            modelRepository = modelRepository,
            settingsRepository = settings,
        )
    }
}
