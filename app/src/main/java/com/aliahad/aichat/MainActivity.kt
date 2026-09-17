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
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aliahad.aichat.ui.AiChatApp
import com.aliahad.aichat.ui.theme.AichatTheme
import com.aliahad.aichat.residency.ModelResidencyService
import com.aliahad.aichat.core.ThemeMode
import com.aliahad.aichat.settings.DeviceSettingsNavigator
import com.aliahad.aichat.ui.viewmodel.AiChatViewModelFactory
import com.aliahad.aichat.ui.viewmodel.MAX_MESSAGE_ATTACHMENTS
import com.aliahad.aichat.ui.viewmodel.AppShellViewModel
import com.aliahad.aichat.ui.viewmodel.ChatViewModel
import com.aliahad.aichat.ui.viewmodel.MemoryViewModel
import com.aliahad.aichat.ui.viewmodel.ModelSetupViewModel
import com.aliahad.aichat.ui.viewmodel.SkillsViewModel
import androidx.compose.foundation.isSystemInDarkTheme

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
class MainActivity : ComponentActivity() {
    // Lazy on purpose: building the factory touches container.modelRepository,
    // which forces the encrypted database open. It must not resolve until the
    // container has been warmed on a background thread.
    private val viewModelFactory by lazy {
        AiChatViewModelFactory(aiChatApplication.container)
    }
    private val aiChatApplication: AiChatApplication
        get() = application as AiChatApplication
    private var uiForeground = false
    private val appShellViewModel: AppShellViewModel by viewModels { viewModelFactory }
    private val chatViewModel: ChatViewModel by viewModels { viewModelFactory }
    private val modelSetupViewModel: ModelSetupViewModel by viewModels { viewModelFactory }
    private val memoryViewModel: MemoryViewModel by viewModels { viewModelFactory }
    private val skillsViewModel: SkillsViewModel by viewModels { viewModelFactory }

    override fun onCreate(savedInstanceState: Bundle?) {
        val splashScreen = installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Hold the launch theme until the container is warm. Composing earlier
        // would touch container-backed ViewModels and drag the database open
        // back onto the main thread, which is what plan 016 moved off it.
        splashScreen.setKeepOnScreenCondition {
            // Released once warm, and also when startup is parked on the keyguard
            // so the explanation below can be shown instead of an endless splash.
            !aiChatApplication.containerWarm.value &&
                !aiChatApplication.startupBlockedByLock.value
        }
        setContent {
            // Theme preferences live in DataStore, so they are read here rather
            // than hardcoded. Defaults preserve the previous behaviour exactly:
            // follow the system, neutral palette.
            val themeMode by aiChatApplication.container.settings.themeMode
                .collectAsStateWithLifecycle(initialValue = ThemeMode.SYSTEM)
            val dynamicColor by aiChatApplication.container.settings.dynamicColor
                .collectAsStateWithLifecycle(initialValue = false)
            AichatTheme(
                darkTheme = when (themeMode) {
                    ThemeMode.SYSTEM -> isSystemInDarkTheme()
                    ThemeMode.LIGHT -> false
                    ThemeMode.DARK -> true
                },
                dynamicColor = dynamicColor,
            ) {
                val containerWarm by aiChatApplication.containerWarm
                    .collectAsStateWithLifecycle()
                val startupBlockedByLock by aiChatApplication.startupBlockedByLock
                    .collectAsStateWithLifecycle()
                if (!containerWarm) {
                    if (startupBlockedByLock) LockedStartupMessage()
                    return@AichatTheme
                }
                LaunchedEffect(Unit) {
                    appShellViewModel.initialize()
                    ModelResidencyService.start(this@MainActivity)
                    applyUiForeground()
                }
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
                var pendingMarkdownExportId by remember { mutableStateOf<String?>(null) }
                val markdownExportLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.CreateDocument("text/markdown"),
                ) { uri ->
                    val conversationId = pendingMarkdownExportId
                    pendingMarkdownExportId = null
                    if (uri != null && conversationId != null) {
                        chatViewModel.exportConversationMarkdown(uri, conversationId)
                    }
                }
                val fileLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.OpenMultipleDocuments(),
                ) { uris ->
                    // Pass the whole selection: the ViewModel decides how much
                    // fits and reports anything it had to leave out. Truncating
                    // here used to discard files silently.
                    chatViewModel.stageAttachments(uris)
                }
                val photoLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.PickMultipleVisualMedia(MAX_MESSAGE_ATTACHMENTS),
                ) { uris ->
                    chatViewModel.stageAttachments(uris)
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
                Surface(
                    modifier = Modifier
                        .fillMaxSize()
                        // Publishes Compose testTags into the accessibility tree as
                        // resource ids. UiAutomator (and therefore the macrobenchmarks
                        // in :benchmark) cannot see testTag otherwise — it is not a
                        // test-only hook, it changes no runtime behaviour, and it is
                        // the documented way to make a Compose app measurable.
                        .semantics { testTagsAsResourceId = true },
                ) {
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
                                "offmind-office-${System.currentTimeMillis()}.aichatoffice",
                            )
                        },
                        onExportConversation = { conversationId ->
                            pendingMarkdownExportId = conversationId
                            val safeName = chatState.conversations
                                .firstOrNull { it.id == conversationId }?.title.orEmpty()
                                .replace(Regex("[^A-Za-z0-9-_ ]+"), "")
                                .trim()
                                .replace(' ', '-')
                                .take(40)
                                .ifEmpty { "chat" }
                            markdownExportLauncher.launch("$safeName.md")
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
                        onExportDiagnostics = {
                            diagnosticsLauncher.launch("offmind-diagnostics-${System.currentTimeMillis()}.json")
                        },
                        )
                    }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        uiForeground = true
        applyUiForeground()
    }

    override fun onStop() {
        uiForeground = false
        applyUiForeground()
        super.onStop()
    }

    private fun applyUiForeground() {
        if (!aiChatApplication.containerWarm.value) return
        aiChatApplication.container.residencyController.setUiForeground(uiForeground)
    }
}

/**
 * Shown when the app was started behind the keyguard. The chat database is
 * encrypted with a key that Keystore will not release while the device is
 * locked, so there is genuinely nothing to display until the user unlocks —
 * but saying so beats a blank screen or a splash that never ends.
 */
@Composable
private fun LockedStartupMessage() {
    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = "Unlock your phone to open Offmind",
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "Your chats are encrypted with a key that stays locked " +
                    "with the device. Offmind opens as soon as you unlock.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}
