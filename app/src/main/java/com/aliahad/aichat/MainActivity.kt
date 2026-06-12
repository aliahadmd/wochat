package com.aliahad.aichat

import android.Manifest
import android.os.Bundle
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aliahad.aichat.ui.AiChatApp
import com.aliahad.aichat.ui.theme.AichatTheme
import com.aliahad.aichat.residency.ModelResidencyService

class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels {
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                MainViewModel((application as AiChatApplication).container) as T
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {}.launch(
            Manifest.permission.POST_NOTIFICATIONS,
        )
        ModelResidencyService.start(this)
        setContent {
            AichatTheme(dynamicColor = false) {
                val state by viewModel.uiState.collectAsStateWithLifecycle()
                val importLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.OpenDocument(),
                ) { uri ->
                    uri?.let(viewModel::importModel)
                }
                var pendingExportPassphrase by remember { mutableStateOf<String?>(null) }
                val officeExportLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.CreateDocument("application/octet-stream"),
                ) { uri ->
                    val passphrase = pendingExportPassphrase
                    pendingExportPassphrase = null
                    if (uri != null && passphrase != null) {
                        viewModel.exportOfficeBackup(uri, passphrase)
                    }
                }
                val officeImportLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.OpenDocument(),
                ) { uri ->
                    viewModel.selectOfficeBackup(uri)
                }
                val fileLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.OpenMultipleDocuments(),
                ) { uris ->
                    uris.take(20).forEach(viewModel::stageAttachment)
                }
                val photoLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.PickMultipleVisualMedia(20),
                ) { uris ->
                    uris.forEach(viewModel::stageAttachment)
                }
                var pendingCameraUri by androidx.compose.runtime.remember {
                    androidx.compose.runtime.mutableStateOf<Uri?>(null)
                }
                val cameraLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.TakePicture(),
                ) { success ->
                    if (success) pendingCameraUri?.let(viewModel::stageAttachment)
                    pendingCameraUri = null
                }
                Surface(modifier = Modifier.fillMaxSize()) {
                    AiChatApp(
                        state = state,
                        actions = viewModel,
                        onImportModel = { importLauncher.launch(arrayOf("*/*")) },
                        onAddPhotos = {
                            photoLauncher.launch(
                                androidx.activity.result.PickVisualMediaRequest(
                                    ActivityResultContracts.PickVisualMedia.ImageOnly,
                                ),
                            )
                        },
                        onAddFiles = {
                            fileLauncher.launch(
                                arrayOf(
                                    "image/*",
                                    "application/pdf",
                                    "text/*",
                                    "application/json",
                                    "application/xml",
                                    "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                                    "application/vnd.openxmlformats-officedocument.presentationml.presentation",
                                ),
                            )
                        },
                        onTakePhoto = {
                            val file = File(cacheDir, "camera/${System.currentTimeMillis()}.jpg").apply {
                                parentFile?.mkdirs()
                            }
                            val uri = FileProvider.getUriForFile(
                                this,
                                "$packageName.files",
                                file,
                            )
                            pendingCameraUri = uri
                            cameraLauncher.launch(uri)
                        },
                        onExportOffice = { passphrase ->
                            pendingExportPassphrase = passphrase
                            officeExportLauncher.launch(
                                "AIchat-office-${System.currentTimeMillis()}.aichatoffice",
                            )
                        },
                        onImportOffice = {
                            officeImportLauncher.launch(
                                arrayOf(
                                    "application/octet-stream",
                                    "application/zip",
                                    "*/*",
                                ),
                            )
                        },
                    )
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        (application as AiChatApplication).container.residencyController.setUiForeground(true)
    }

    override fun onStop() {
        (application as AiChatApplication).container.residencyController.setUiForeground(false)
        super.onStop()
    }
}
