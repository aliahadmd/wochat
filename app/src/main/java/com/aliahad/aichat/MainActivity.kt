package com.aliahad.aichat

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.net.Uri
import androidx.core.content.ContextCompat
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aliahad.aichat.ui.AiChatApp
import com.aliahad.aichat.ui.theme.AichatTheme
import com.aliahad.aichat.residency.ModelResidencyService
import com.aliahad.aichat.core.ActivitySource
import com.aliahad.aichat.settings.DeviceSettingsNavigator
import androidx.health.connect.client.PermissionController
import com.aliahad.aichat.ui.viewmodel.AiChatViewModelFactory
import com.aliahad.aichat.ui.viewmodel.AppShellViewModel
import com.aliahad.aichat.ui.viewmodel.ChatViewModel
import com.aliahad.aichat.ui.viewmodel.MemoryViewModel
import com.aliahad.aichat.ui.viewmodel.ModelSetupViewModel
import com.aliahad.aichat.ui.viewmodel.SkillsViewModel

class MainActivity : ComponentActivity() {
    private lateinit var viewModelFactory: AiChatViewModelFactory
    private val appShellViewModel: AppShellViewModel by viewModels { viewModelFactory }
    private val chatViewModel: ChatViewModel by viewModels { viewModelFactory }
    private val modelSetupViewModel: ModelSetupViewModel by viewModels { viewModelFactory }
    private val memoryViewModel: MemoryViewModel by viewModels { viewModelFactory }
    private val skillsViewModel: SkillsViewModel by viewModels { viewModelFactory }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        viewModelFactory = AiChatViewModelFactory((application as AiChatApplication).container)
        appShellViewModel.initialize()
        ModelResidencyService.start(this)
        setContent {
            AichatTheme(dynamicColor = false) {
                val shellState by appShellViewModel.uiState.collectAsStateWithLifecycle()
                val chatState by chatViewModel.uiState.collectAsStateWithLifecycle()
                val modelState by modelSetupViewModel.uiState.collectAsStateWithLifecycle()
                val memoryState by memoryViewModel.uiState.collectAsStateWithLifecycle()
                val skillsState by skillsViewModel.uiState.collectAsStateWithLifecycle()
                var pendingExportPassphrase by remember { mutableStateOf<CharArray?>(null) }
                val officeExportLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.CreateDocument("application/octet-stream"),
                ) { uri ->
                    val passphrase = pendingExportPassphrase
                    pendingExportPassphrase = null
                    if (uri != null && passphrase != null) {
                        memoryViewModel.exportOfficeBackup(uri, passphrase)
                    } else {
                        passphrase?.fill('\u0000')
                    }
                }
                val officeImportLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.OpenDocument(),
                ) { uri ->
                    memoryViewModel.selectOfficeBackup(uri)
                }
                val diagnosticsLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.CreateDocument("application/json"),
                ) { uri -> uri?.let(modelSetupViewModel::exportDiagnostics) }
                val phonePermissionLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestMultiplePermissions(),
                ) {
                    memoryViewModel.refreshPhoneSourceAccess()
                }
                val healthPermissionLauncher = rememberLauncherForActivityResult(
                    PermissionController.createRequestPermissionResultContract(),
                ) {
                    memoryViewModel.refreshPhoneSourceAccess()
                }
                val fileLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.OpenMultipleDocuments(),
                ) { uris ->
                    uris.take(20).forEach(chatViewModel::stageAttachment)
                }
                val photoLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.PickMultipleVisualMedia(20),
                ) { uris ->
                    uris.forEach(chatViewModel::stageAttachment)
                }
                var pendingCameraUri by androidx.compose.runtime.remember {
                    androidx.compose.runtime.mutableStateOf<Uri?>(null)
                }
                val cameraLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.TakePicture(),
                ) { success ->
                    if (success) pendingCameraUri?.let(chatViewModel::stageAttachment)
                    pendingCameraUri = null
                }
                Surface(modifier = Modifier.fillMaxSize()) {
                    shellState.launchDestination?.let { launchDestination ->
                        AiChatApp(
                        launchDestination = launchDestination,
                        shellState = shellState,
                        chatState = chatState,
                        modelState = modelState,
                        memoryState = memoryState,
                        skillsState = skillsState,
                        shellActions = appShellViewModel,
                        chatActions = chatViewModel,
                        modelActions = modelSetupViewModel,
                        memoryActions = memoryViewModel,
                        skillsActions = skillsViewModel,
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
                            val file = java.io.File(cacheDir, "camera/${System.currentTimeMillis()}.jpg").apply {
                                parentFile?.mkdirs()
                            }
                            val uri = androidx.core.content.FileProvider.getUriForFile(
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
                                "wochat-office-${System.currentTimeMillis()}.aichatoffice",
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
                        onRequestPhoneSourceAccess = { source ->
                            when (source) {
                                ActivitySource.APP_USAGE ->
                                    DeviceSettingsNavigator.openUsageAccess(this)
                                ActivitySource.NOTIFICATION ->
                                    DeviceSettingsNavigator.openNotificationAccess(this)
                                ActivitySource.ACCESSIBILITY ->
                                    DeviceSettingsNavigator.openAccessibility(this)
                                ActivitySource.LOCATION,
                                ActivitySource.SENSOR,
                                ActivitySource.CONTACT,
                                ActivitySource.CALENDAR -> {
                                    // The user opts into phone-source collection as a whole, so
                                    // every missing standard runtime permission is requested in
                                    // one batch instead of one dialog per source.
                                    val missing = buildList {
                                        val foregroundLocation =
                                            hasPermission(Manifest.permission.ACCESS_FINE_LOCATION) ||
                                                hasPermission(Manifest.permission.ACCESS_COARSE_LOCATION)
                                        if (!foregroundLocation) {
                                            add(Manifest.permission.ACCESS_COARSE_LOCATION)
                                            add(Manifest.permission.ACCESS_FINE_LOCATION)
                                        }
                                        if (!hasPermission(Manifest.permission.READ_CONTACTS)) {
                                            add(Manifest.permission.READ_CONTACTS)
                                        }
                                        if (!hasPermission(Manifest.permission.READ_CALENDAR)) {
                                            add(Manifest.permission.READ_CALENDAR)
                                        }
                                        if (!hasPermission(Manifest.permission.ACTIVITY_RECOGNITION)) {
                                            add(Manifest.permission.ACTIVITY_RECOGNITION)
                                        }
                                    }
                                    if (missing.isEmpty()) {
                                        // Everything requestable is granted; background location
                                        // and other elevations live in system settings only.
                                        DeviceSettingsNavigator.openAppPermissions(this)
                                    } else {
                                        phonePermissionLauncher.launch(missing.toTypedArray())
                                    }
                                }
                                ActivitySource.HEALTH -> {
                                    val container = (application as AiChatApplication).container
                                    if (container.phoneSourceAccessManager.healthConnectAvailable) {
                                        healthPermissionLauncher.launch(
                                            container.healthDataSource.readPermissions,
                                        )
                                    }
                                }
                                else -> Unit
                            }
                        },
                        onExportDiagnostics = {
                            diagnosticsLauncher.launch("wochat-diagnostics-${System.currentTimeMillis()}.json")
                        },
                        )
                    }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        (application as AiChatApplication).container.residencyController.setUiForeground(true)
    }

    override fun onResume() {
        super.onResume()
        memoryViewModel.refreshPhoneSourceAccess()
    }

    override fun onStop() {
        (application as AiChatApplication).container.residencyController.setUiForeground(false)
        super.onStop()
    }

    private fun hasPermission(permission: String): Boolean =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
}
