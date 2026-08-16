package com.aliahad.aichat.ui

import android.animation.ValueAnimator
import android.os.PowerManager
import androidx.compose.animation.AnimatedContent
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.aliahad.aichat.ThinkingUiState
import com.aliahad.aichat.core.BackendMode
import com.aliahad.aichat.core.Attachment
import com.aliahad.aichat.core.AttachmentKind
import com.aliahad.aichat.core.AttachmentProcessingState
import com.aliahad.aichat.core.ChatMessage
import com.aliahad.aichat.core.ContextVerificationState
import com.aliahad.aichat.core.DownloadStatus
import com.aliahad.aichat.ui.viewmodel.SemanticRecallUiState
import com.aliahad.aichat.core.GenerationSettings
import com.aliahad.aichat.core.InferenceState
import com.aliahad.aichat.core.MessageRole
import com.aliahad.aichat.core.MessageStatus
import com.aliahad.aichat.core.MemoryItem
import com.aliahad.aichat.core.MemoryType
import com.aliahad.aichat.core.ModelContextProfile
import com.aliahad.aichat.core.ModelRecord
import com.aliahad.aichat.core.ProjectorRecord
import com.aliahad.aichat.core.SkillPromptBlock
import com.aliahad.aichat.core.SkillRecord
import com.aliahad.aichat.core.ThemeMode
import com.aliahad.aichat.model.ModelConstants
import com.aliahad.aichat.model.formatBytes
import com.aliahad.aichat.residency.ModelResidencyState
import com.aliahad.aichat.settings.DeviceSettingsNavigator
import com.aliahad.aichat.ui.navigation.AppRoute
import com.aliahad.aichat.ui.viewmodel.AppShellUiState
import com.aliahad.aichat.ui.viewmodel.AppShellViewModel
import com.aliahad.aichat.ui.viewmodel.ChatUiState
import com.aliahad.aichat.ui.viewmodel.UiMessage
import com.aliahad.aichat.ui.viewmodel.ChatViewModel
import com.aliahad.aichat.ui.viewmodel.MAX_MESSAGE_ATTACHMENTS
import com.aliahad.aichat.ui.viewmodel.MemoryUiState
import com.aliahad.aichat.ui.viewmodel.MemoryViewModel
import com.aliahad.aichat.ui.viewmodel.ModelSetupUiState
import com.aliahad.aichat.ui.viewmodel.ModelSetupViewModel
import com.aliahad.aichat.ui.viewmodel.SkillsUiState
import com.aliahad.aichat.ui.viewmodel.SkillsViewModel
import com.aliahad.aichat.ui.theme.EmeraldDark
import com.aliahad.aichat.ui.theme.EmeraldLight
import com.aliahad.aichat.skill.MAX_SELECTED_SKILLS
import com.aliahad.aichat.skill.MAX_SKILL_DESCRIPTION_CHARS
import com.aliahad.aichat.skill.MAX_SKILL_INSTRUCTIONS_CHARS
import com.aliahad.aichat.skill.MAX_SKILL_NAME_CHARS
import com.mikepenz.markdown.compose.MarkdownSuccess
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.model.rememberMarkdownState
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.launch
import coil3.compose.AsyncImage
import java.io.File
import java.text.DateFormat
import java.util.Date
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.IconButtonDefaults

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AiChatApp(
    launchDestination: AppRoute,
    shellState: AppShellUiState,
    chatState: ChatUiState,
    modelState: ModelSetupUiState,
    memoryState: MemoryUiState,
    skillsState: SkillsUiState,
    shellActions: AppShellViewModel,
    chatActions: ChatViewModel,
    modelActions: ModelSetupViewModel,
    memoryActions: MemoryViewModel,
    skillsActions: SkillsViewModel,
    onAddPhotos: () -> Unit,
    onAddFiles: () -> Unit,
    onTakePhoto: () -> Unit,
    onExportOffice: (CharArray) -> Unit,
    onExportConversation: (String) -> Unit,
    onImportOffice: () -> Unit,
    onExportDiagnostics: () -> Unit,
) {
    val chat = chatState
    val models = modelState
    val memory = memoryState
    val skills = skillsState
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val snackbarHost = remember { SnackbarHostState() }
    val navController = rememberNavController()
    val currentBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = currentBackStackEntry?.destination?.route
        ?.let(AppRoute::fromRoute)
        ?: launchDestination
    var showSkillSheet by remember { mutableStateOf(false) }
    var settingsSection by remember { mutableStateOf(SettingsSection.MODELS) }
    val navigateTo: (AppRoute) -> Unit = { destination ->
        if (navController.currentDestination?.route != destination.route) {
            navController.navigate(destination.route) {
                launchSingleTop = true
                // These are two top-level destinations, not a drill-down. Popping
                // back to the start destination keeps the stack one entry deep;
                // without it, leaving settings pushed a second CHAT entry and
                // system back took the user back into settings.
                // saveState/restoreState must be paired — restoreState alone is inert.
                popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                restoreState = true
            }
        }
    }

    LaunchedEffect(shellState.error) {
        shellState.error?.let { message ->
            val result = snackbarHost.showSnackbar(
                message = message.text,
                actionLabel = message.actionLabel,
                // A blocking failure that vanishes after four seconds may as well
                // not have been shown.
                duration = if (message.important) {
                    SnackbarDuration.Indefinite
                } else {
                    SnackbarDuration.Short
                },
                withDismissAction = message.important,
            )
            if (result == SnackbarResult.ActionPerformed) message.action?.invoke()
            shellActions.clearError()
        }
    }
    LaunchedEffect(shellState.pendingNavigation) {
        shellState.pendingNavigation?.let { destination ->
            if (navController.currentDestination?.route != destination.route) {
                navController.navigate(destination.route) { launchSingleTop = true }
            }
            shellActions.consumeNavigation()
        }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ConversationDrawer(
                state = chat,
                onNewChat = {
                    chatActions.newConversation()
                    navigateTo(AppRoute.CHAT)
                    scope.launch { drawerState.close() }
                },
                onNewTemporaryChat = {
                    chatActions.newTemporaryConversation()
                    navigateTo(AppRoute.CHAT)
                    scope.launch { drawerState.close() }
                },
                onSelect = {
                    chatActions.selectConversation(it)
                    navigateTo(AppRoute.CHAT)
                    scope.launch { drawerState.close() }
                },
                onDelete = chatActions::deleteConversation,
                onExport = onExportConversation,
                onSearchQueryChange = chatActions::setSearchQuery,
                onSettings = {
                    navigateTo(AppRoute.SETTINGS)
                    scope.launch { drawerState.close() }
                },
            )
        },
    ) {
        Scaffold(
            snackbarHost = { SnackbarHost(snackbarHost) },
            topBar = {
                TopAppBar(
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.background,
                        titleContentColor = MaterialTheme.colorScheme.onBackground,
                        navigationIconContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        actionIconContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    ),
                    navigationIcon = {
                        IconButton(onClick = { scope.launch { drawerState.open() } }) {
                            Icon(Icons.Default.Menu, contentDescription = "Open conversations")
                        }
                    },
                    title = {
                        when (currentRoute) {
                            AppRoute.SETTINGS -> Text("Settings")
                            AppRoute.CHAT -> Text("wochat")
                        }
                    },
                    actions = {
                        if (currentRoute == AppRoute.CHAT) {
                            IconButton(
                                onClick = { showSkillSheet = true },
                                enabled = !chat.isSending,
                            ) {
                                Icon(
                                    Icons.Default.CheckCircle,
                                    contentDescription = "Select skills",
                                    tint = if (chat.selectedSkillIds.isNotEmpty()) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                                )
                            }
                        } else {
                            IconButton(onClick = { navigateTo(AppRoute.CHAT) }) {
                                Icon(Icons.Default.Close, contentDescription = "Close settings")
                            }
                        }
                    },
                )
            },
        ) { padding ->
            NavHost(
                navController = navController,
                startDestination = launchDestination.route,
                modifier = Modifier.testTag("app-nav-host"),
            ) {
                composable(AppRoute.CHAT.route) { ChatScreen(
                    state = chat,
                    onSend = { chatActions.sendMessage(it) },
                    onInputChange = chatActions::setInput,
                    onStop = chatActions::stopGeneration,
                    onContinue = chatActions::continueResponse,
                    onToggleThinking = chatActions::toggleThinking,
                    onSetThinkingMode = chatActions::setThinkingMode,
                    onOpenSettings = { navigateTo(AppRoute.SETTINGS) },
                    onToggleSkill = chatActions::toggleSelectedSkill,
                    onAddPhotos = onAddPhotos,
                    onAddFiles = onAddFiles,
                    onTakePhoto = onTakePhoto,
                    onRemoveAttachment = chatActions::removeAttachment,
                    onRetryAttachment = chatActions::retryAttachment,
                    onSelectPages = chatActions::selectAttachmentPages,
                    modifier = Modifier.padding(padding),
                ) }
                composable(AppRoute.SETTINGS.route) { SettingsHub(
                    section = settingsSection,
                    onSectionChange = { settingsSection = it },
                    modelState = models,
                    modelActions = modelActions,
                    memoryState = memory,
                    memoryActions = memoryActions,
                    skillsState = skills,
                    skillsActions = skillsActions,
                    onExportDiagnostics = onExportDiagnostics,
                    onExportOffice = onExportOffice,
                    onImportOffice = onImportOffice,
                    modifier = Modifier.padding(padding),
                ) }
            }
        }
    }

    if (showSkillSheet) {
        SkillPickerSheet(
            skills = chat.skills,
            selectedSkillIds = chat.selectedSkillIds,
            onToggleSkill = chatActions::toggleSelectedSkill,
            onClearSkills = chatActions::clearSelectedSkills,
            onOpenSkills = {
                showSkillSheet = false
                settingsSection = SettingsSection.SKILLS
                navigateTo(AppRoute.SETTINGS)
            },
            onDismiss = { showSkillSheet = false },
        )
    }

    models.pendingProjectorId?.let { id ->
        models.projectors.firstOrNull { it.id == id }?.let { projector ->
            ProjectorDownloadDialog(
                projector = projector,
                onDownload = { modelActions.startProjectorDownload(projector.id) },
                onDismiss = modelActions::dismissProjectorPrompt,
            )
        }
    }
}

@Composable
private fun ConversationDrawer(
    state: ChatUiState,
    onNewChat: () -> Unit,
    onNewTemporaryChat: () -> Unit,
    onSelect: (String) -> Unit,
    onDelete: (String) -> Unit,
    onExport: (String) -> Unit,
    onSearchQueryChange: (String) -> Unit,
    onSettings: () -> Unit,
) {
    val drawerHaptics = LocalHapticFeedback.current
    ModalDrawerSheet(modifier = Modifier.width(304.dp)) {
        Column(
            modifier = Modifier
                .fillMaxHeight()
                .statusBarsPadding()
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            Button(onClick = onNewChat, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.Add, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("New chat")
            }
            TextButton(onClick = onNewTemporaryChat, modifier = Modifier.fillMaxWidth()) {
                Text("Temporary chat")
            }
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = state.searchQuery,
                onValueChange = onSearchQueryChange,
                label = { Text("Search chats") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(12.dp))
            Text(
                if (state.searchQuery.isNotBlank()) "Search results" else "Conversations",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            )
            LazyColumn(modifier = Modifier.weight(1f)) {
                if (state.searchQuery.isNotBlank()) {
                    if (state.searchResults.isEmpty()) {
                        item {
                            Text(
                                "No chats matched \"${state.searchQuery}\".",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            )
                        }
                    }
                    items(state.searchResults, key = { it.conversationId }) { result ->
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .clickable { onSelect(result.conversationId) }
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                        ) {
                            Text(
                                result.title,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.titleSmall,
                            )
                            Text(
                                result.snippet,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                } else {
                    items(state.conversations, key = { it.id }) { conversation ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(
                                if (conversation.id == state.selectedConversationId) {
                                    MaterialTheme.colorScheme.secondaryContainer
                                } else Color.Transparent,
                            )
                            .clickable { onSelect(conversation.id) }
                            .padding(start = 12.dp, top = 10.dp, bottom = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            conversation.title,
                            modifier = Modifier.weight(1f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        IconButton(onClick = { onExport(conversation.id) }) {
                            Icon(Icons.Default.Download, contentDescription = "Export conversation as Markdown")
                        }
                        IconButton(onClick = {
                            // Firmer feedback for a destructive action than for
                            // an ordinary tap.
                            drawerHaptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            onDelete(conversation.id)
                        }) {
                            Icon(Icons.Default.Delete, contentDescription = "Delete conversation")
                        }
                    }
                    }
                }
            }
            HorizontalDivider()
            TextButton(onClick = onSettings, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.Settings, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Settings")
            }
        }
    }
}

@Composable
private fun ChatScreen(
    state: ChatUiState,
    onSend: (String) -> Unit,
    onInputChange: (String) -> Unit,
    onStop: () -> Unit,
    onContinue: () -> Unit,
    onToggleThinking: (String) -> Unit,
    onSetThinkingMode: (Boolean) -> Unit,
    onOpenSettings: () -> Unit,
    onToggleSkill: (String) -> Unit,
    onAddPhotos: () -> Unit,
    onAddFiles: () -> Unit,
    onTakePhoto: () -> Unit,
    onRemoveAttachment: (String) -> Unit,
    onRetryAttachment: (String) -> Unit,
    onSelectPages: (String, Set<Int>) -> Unit,
    modifier: Modifier = Modifier,
) {
    // While a response is streaming, back stops generation instead of sending
    // the user out of the app mid-turn. Disabled otherwise, so ordinary back
    // behaviour is untouched.
    BackHandler(enabled = state.isSending) { onStop() }

    // A local answer can take a long time, so the user often looks away. One
    // confirmation when it lands is the highest-value haptic in the app.
    // Keyed on the true -> false transition, not on "not sending", so it cannot
    // re-fire on recomposition or when an old message scrolls back into view.
    val chatHaptics = LocalHapticFeedback.current
    var wasSending by remember { mutableStateOf(false) }
    LaunchedEffect(state.isSending) {
        if (wasSending && !state.isSending) {
            chatHaptics.performHapticFeedback(HapticFeedbackType.Confirm)
        }
        wasSending = state.isSending
    }
    // The draft lives in ChatUiState so it survives configuration changes and
    // process death, and is cleared when the conversation changes.
    val input = state.input
    var showAttachmentSheet by remember { mutableStateOf(false) }
    var previewAttachment by remember { mutableStateOf<Attachment?>(null) }
    val selectedModel = if (state.modelCatalogLoaded) {
        state.models.firstOrNull { it.selected }
    } else {
        null
    }
    val mediaRequired = state.draftAttachments.any {
        it.kind == AttachmentKind.IMAGE ||
            it.kind == AttachmentKind.AUDIO ||
            it.derivedImagePaths.isNotEmpty()
    }
    val selectedProjector = selectedModel?.let { model ->
        state.projectors.firstOrNull { it.modelId == model.id }
    }
    val mediaBlockingReason = when {
        !mediaRequired -> null
        selectedProjector == null -> "The selected model has no configured multimedia projector."
        selectedProjector.status != DownloadStatus.READY ->
            "Install ${selectedProjector.displayName} to send image or audio attachments."
        else -> null
    }

    Column(modifier = modifier.fillMaxSize()) {
        if (!state.modelCatalogLoaded) {
            ModelCatalogLoadingBanner()
        } else if (selectedModel == null) {
            ModelRequiredBanner(onOpenSettings)
        } else {
            InferenceStatusBar(state, selectedModel)
        }
        if (!state.modelCatalogLoaded) {
            StartupChatPlaceholder(modifier = Modifier.weight(1f))
        } else if (state.messages.isEmpty()) {
            EmptyChat(
                modelName = selectedModel?.displayName,
                modifier = Modifier.weight(1f),
            )
        } else {
            MessageList(
                messages = state.messages,
                conversationId = state.selectedConversationId,
                attachments = state.messageAttachments,
                skills = state.messageSkills,
                thinking = state.thinking,
                onToggleThinking = onToggleThinking,
                onContinue = onContinue,
                onPreviewAttachment = { previewAttachment = it },
                modifier = Modifier.weight(1f),
            )
        }
        AnimatedVisibility(visible = state.usedMemoryCount > 0 && state.isSending) {
            AssistChip(
                onClick = {},
                label = { Text("Using ${state.usedMemoryCount} memories") },
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }
        Composer(
            input = input,
            onInputChange = onInputChange,
            sending = state.isSending,
            enabled = state.modelCatalogLoaded && selectedModel != null,
            thinkingEnabled = state.thinkingEnabled,
            attachments = state.draftAttachments,
            skills = state.skills,
            selectedSkillIds = state.selectedSkillIds,
            onAttach = { showAttachmentSheet = true },
            onThinkingModeChange = onSetThinkingMode,
            onRemoveSkill = onToggleSkill,
            onRemoveAttachment = onRemoveAttachment,
            onRetryAttachment = onRetryAttachment,
            onPreviewAttachment = { previewAttachment = it },
            blockingReason = mediaBlockingReason,
            onSend = {
                if (input.isNotBlank() || state.draftAttachments.isNotEmpty()) {
                    // The ViewModel clears the draft once the turn commits.
                    onSend(input)
                }
            },
            onStop = onStop,
        )
    }

    if (showAttachmentSheet) {
        AttachmentSourceSheet(
            onDismiss = { showAttachmentSheet = false },
            onCamera = {
                showAttachmentSheet = false
                onTakePhoto()
            },
            onPhotos = {
                showAttachmentSheet = false
                onAddPhotos()
            },
            onFiles = {
                showAttachmentSheet = false
                onAddFiles()
            },
        )
    }
    previewAttachment?.let { attachment ->
        AttachmentPreviewDialog(
            attachment = attachment,
            onDismiss = { previewAttachment = null },
            onSelectPages = {
                onSelectPages(attachment.id, it)
                previewAttachment = null
            },
        )
    }
}

@Composable
private fun ModelCatalogLoadingBanner() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
        Spacer(Modifier.width(10.dp))
        Text(
            "Loading local model catalog...",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ModelRequiredBanner(onOpenSettings: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.tertiaryContainer)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("A local GGUF model is required.", modifier = Modifier.weight(1f))
        TextButton(onClick = onOpenSettings) { Text("Set up") }
    }
}


@Composable
private fun StartupChatPlaceholder(modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator()
            Spacer(Modifier.height(12.dp))
            Text(
                "Preparing wochat",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                "Checking your local models and background services.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun InferenceStatusBar(state: ChatUiState, model: ModelRecord) {
    val status = when (val inference = state.inferenceState) {
        InferenceState.Uninitialized -> "Starting engine"
        InferenceState.Idle -> residencyLabel(state.residencyState)
        is InferenceState.Loading -> "Loading ${inference.modelName}"
        is InferenceState.Ready -> residencyLabel(state.residencyState)
        InferenceState.PreparingHistory -> "Reconstructing conversation"
        InferenceState.EvaluatingPrompt -> "Evaluating prompt"
        InferenceState.EncodingMedia -> "Encoding media"
        InferenceState.Generating -> state.inferenceMetrics.firstTokenMillis?.let {
            "Generating · first token ${formatMillis(it)}"
        } ?: "Waiting for first token"
        is InferenceState.Recovering ->
            "Recovering on ${inference.to.name.lowercase().replaceFirstChar(Char::uppercase)}"
        is InferenceState.Error -> inference.message
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(8.dp)
                .background(
                    if (state.inferenceState is InferenceState.Error ||
                        state.residencyState is ModelResidencyState.Error
                    ) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.primary,
                    RoundedCornerShape(50),
                ),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            "${model.displayName} · $status",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun EmptyChat(modelName: String?, modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("wochat", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            Text(
                modelName?.let { "Private, on-device chat with $it" } ?: "Set up a local model to begin",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * The inputs that decide whether the message list should follow the latest
 * content. Collapsed into one value so snapshotFlow emits a single conflatable
 * update instead of the seven separate LaunchedEffect keys this replaced.
 */
private data class ScrollFollowState(
    val messageCount: Int,
    val lastContentLength: Int,
    val thinkingLength: Int,
    val thinkingExpanded: Boolean,
    val lastMessageHeight: Int,
    val renderRevision: Int,
    val follow: Boolean,
)

/**
 * How far the list may be from the target before following snaps instead of
 * animating. Streaming moves a item or two at a time, which should glide;
 * switching conversation can move hundreds, which should not be a long scroll.
 */
private const val SMOOTH_FOLLOW_ITEM_DISTANCE = 3

@Composable
internal fun MessageList(
    messages: List<ChatMessage>,
    conversationId: String?,
    attachments: Map<String, List<Attachment>> = emptyMap(),
    skills: Map<String, List<SkillPromptBlock>> = emptyMap(),
    thinking: ThinkingUiState? = null,
    onToggleThinking: (String) -> Unit = {},
    onContinue: () -> Unit = {},
    onPreviewAttachment: (Attachment) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val isUserDragging by listState.interactionSource.collectIsDraggedAsState()
    val isAtBottom by remember {
        derivedStateOf { !listState.canScrollForward }
    }
    var followLatest by remember(conversationId) { mutableStateOf(true) }
    val lastMessage = messages.lastOrNull()
    var lastMessageHeight by remember(conversationId, lastMessage?.id) {
        mutableIntStateOf(0)
    }
    var lastMessageRenderRevision by remember(conversationId, lastMessage?.id) {
        mutableIntStateOf(0)
    }

    LaunchedEffect(isUserDragging) {
        if (isUserDragging) followLatest = false
    }
    LaunchedEffect(isAtBottom, isUserDragging) {
        if (isAtBottom && !isUserDragging) followLatest = true
    }
    LaunchedEffect(lastMessage?.id) {
        if (lastMessage?.role == MessageRole.USER) followLatest = true
    }
    // Keyed on nothing that changes per token: the effect starts once and then
    // observes the scroll-relevant state through snapshotFlow. The previous
    // version listed lastMessage.content.length among its keys, so every single
    // token cancelled and relaunched the whole coroutine.
    //
    // conflate() collapses a burst of token updates into one scroll per frame
    // rather than one per token, and the decision logic (followLatest) is
    // deliberately unchanged — this alters how often the scroll runs, not when
    // the app decides to follow.
    LaunchedEffect(listState, conversationId) {
        snapshotFlow {
            ScrollFollowState(
                messageCount = messages.size,
                lastContentLength = messages.lastOrNull()?.content?.length ?: 0,
                thinkingLength = thinking?.text?.length?.div(80) ?: 0,
                thinkingExpanded = thinking?.expanded == true,
                lastMessageHeight = lastMessageHeight,
                renderRevision = lastMessageRenderRevision,
                follow = followLatest,
            )
        }
            .conflate()
            .collect { state ->
                if (!state.follow || state.messageCount == 0) return@collect
                val target = state.messageCount
                val distance = target - listState.firstVisibleItemIndex
                // Animate the small, continuous case that streaming produces so
                // the list glides; snap for large jumps (first load, switching
                // conversation) where an animation would just be a long scroll.
                if (distance in 0..SMOOTH_FOLLOW_ITEM_DISTANCE) {
                    listState.animateScrollToItem(target)
                } else {
                    listState.scrollToItem(target)
                }
            }
    }

    Box(modifier = modifier) {
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .testTag("message-list"),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            items(messages, key = { it.id }) { message ->
                MessageBubble(
                    role = message.role,
                    content = message.content,
                    status = message.status,
                    stopReason = message.stopReason,
                    attachments = attachments[message.id].orEmpty(),
                    skills = skills[message.id].orEmpty(),
                    thinking = thinking?.takeIf { message.id == it.messageId },
                    onToggleThinking = { onToggleThinking(message.id) },
                    onContinue = onContinue,
                    onPreviewAttachment = onPreviewAttachment,
                    modifier = if (message.id == lastMessage?.id) {
                        Modifier.onSizeChanged { lastMessageHeight = it.height }
                    } else {
                        Modifier
                    },
                    onMarkdownRendered = if (message.id == lastMessage?.id) {
                        { lastMessageRenderRevision++ }
                    } else {
                        {}
                    },
                )
            }
            item(key = "conversation-bottom") {
                Spacer(Modifier.height(1.dp))
            }
        }
        androidx.compose.animation.AnimatedVisibility(
            visible = !isAtBottom,
            enter = fadeIn(tween(160)),
            exit = fadeOut(tween(200)),
            modifier = Modifier.align(Alignment.BottomEnd),
        ) {
            IconButton(
                onClick = {
                    followLatest = true
                    scope.launch { listState.animateScrollToItem(messages.size) }
                },
                modifier = Modifier
                    .padding(16.dp)
                    .background(
                        MaterialTheme.colorScheme.surfaceContainerHigh,
                        RoundedCornerShape(50),
                    ),
            ) {
                Icon(
                    Icons.Default.KeyboardArrowDown,
                    contentDescription = "Jump to latest message",
                )
            }
        }
    }
}

@Composable
internal fun MessageBubble(
    role: MessageRole,
    content: String,
    status: MessageStatus,
    stopReason: com.aliahad.aichat.core.GenerationStopReason? = null,
    attachments: List<Attachment> = emptyList(),
    skills: List<SkillPromptBlock> = emptyList(),
    thinking: ThinkingUiState? = null,
    onToggleThinking: () -> Unit = {},
    onContinue: () -> Unit = {},
    onPreviewAttachment: (Attachment) -> Unit = {},
    modifier: Modifier = Modifier,
    onMarkdownRendered: () -> Unit = {},
) {
    val isUser = role == MessageRole.USER
    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = if (isUser) Alignment.End else Alignment.Start,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(if (isUser) 0.82f else 1f)
                .clip(RoundedCornerShape(10.dp))
                .background(
                    if (isUser) MaterialTheme.colorScheme.primaryContainer
                    else MaterialTheme.colorScheme.surfaceContainerLow,
                )
                .padding(14.dp),
        ) {
            Column {
                if (attachments.isNotEmpty()) {
                    MessageAttachmentGrid(attachments, onPreviewAttachment)
                    if (content.isNotEmpty()) Spacer(Modifier.height(10.dp))
                }
                if (isUser && skills.isNotEmpty()) {
                    UsedSkillChips(skills)
                    if (content.isNotEmpty()) Spacer(Modifier.height(8.dp))
                }
                val currentThinking = thinking
                if (!isUser && currentThinking != null) {
                    AnimatedVisibility(
                        visible = currentThinking.text.isNotEmpty() ||
                            status == MessageStatus.STREAMING,
                        enter = fadeIn(tween(160)),
                        exit = fadeOut(tween(200)) + shrinkVertically(tween(200)),
                    ) {
                        ThinkingPanel(
                            thinking = currentThinking,
                            onToggle = onToggleThinking,
                        )
                    }
                }
                if (thinking != null && content.isNotEmpty()) Spacer(Modifier.height(8.dp))
                if (content.isEmpty() && status == MessageStatus.STREAMING && thinking == null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                        Text("Generating…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                } else {
                    if (isUser) {
                        Text(content)
                    } else {
                        val markdownState = rememberMarkdownState(
                            content = content,
                            retainState = true,
                        )
                        Markdown(
                            markdownState = markdownState,
                            modifier = Modifier.fillMaxWidth(),
                            success = { state, components, successModifier ->
                                LaunchedEffect(state) { onMarkdownRendered() }
                                MarkdownSuccess(state, components, successModifier)
                            },
                        )
                    }
                }
            }
        }
        if (status == MessageStatus.CONTINUABLE) {
            Row(
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    when (stopReason) {
                        com.aliahad.aichat.core.GenerationStopReason.CONTEXT_LIMIT ->
                            "Context filled before the answer finished"
                        com.aliahad.aichat.core.GenerationStopReason.PROCESS_DEATH ->
                            "Interrupted when the app stopped"
                        else -> "Answer can continue"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onContinue) { Text("Continue") }
            }
        } else if (status == MessageStatus.CANCELLED || status == MessageStatus.ERROR) {
            Text(
                if (status == MessageStatus.CANCELLED) "Stopped" else "Error",
                style = MaterialTheme.typography.labelSmall,
                color = if (status == MessageStatus.ERROR) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
            )
        }
    }
}

@Composable
private fun UsedSkillChips(skills: List<SkillPromptBlock>) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 2.dp),
    ) {
        skills.forEach { skill ->
            AssistChip(
                onClick = {},
                label = { Text("Used ${skill.name}", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                leadingIcon = {
                    Icon(Icons.Default.CheckCircle, contentDescription = null, Modifier.size(16.dp))
                },
            )
        }
    }
}

@Composable
private fun ThinkingPanel(
    thinking: ThinkingUiState,
    onToggle: () -> Unit,
) {
    val animationsEnabled = ValueAnimator.areAnimatorsEnabled()
    val displayText = remember(thinking.text) { formatThoughtForDisplay(thinking.text) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("thinking-panel")
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .animateContentSize(
                animationSpec = tween(if (animationsEnabled) 180 else 0),
            ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .clickable(onClick = onToggle)
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (!thinking.complete) {
                CircularProgressIndicator(
                    Modifier.size(15.dp),
                    strokeWidth = 2.dp,
                )
                Spacer(Modifier.width(8.dp))
            }
            Text(
                if (thinking.complete) "Thought" else "Thinking…",
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Icon(
                if (thinking.expanded) Icons.Default.KeyboardArrowUp
                else Icons.Default.KeyboardArrowDown,
                contentDescription = if (thinking.expanded) "Hide thought" else "Show thought",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (!thinking.expanded && !thinking.complete && displayText.isNotBlank()) {
            Text(
                displayText.takeLast(200).replace(WHITESPACE, " ").trim(),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 12.dp, end = 12.dp, bottom = 10.dp)
                    .clearAndSetSemantics {},
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (thinking.expanded && displayText.isNotBlank()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 12.dp, end = 12.dp, bottom = 12.dp)
                    .testTag("thinking-content"),
            ) {
                Text(
                    displayText,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clearAndSetSemantics {},
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private val WHITESPACE = Regex("\\s+")

// Compiled once: the thought panel re-formats the growing buffer on every flush.
private val LIST_MARKER = Regex("""^(\s*)[*-]\s+""")

private fun formatThoughtForDisplay(text: String): String =
    text
        .replace("<|channel>thought", "")
        .replace("<|channel>", "")
        .replace("<channel|>", "")
        .lineSequence()
        .joinToString("\n") { line ->
            line.replace(LIST_MARKER) { match ->
                "${match.groupValues[1]}• "
            }
        }
        .trim()

@Composable
private fun Composer(
    input: String,
    onInputChange: (String) -> Unit,
    sending: Boolean,
    enabled: Boolean,
    thinkingEnabled: Boolean,
    attachments: List<Attachment>,
    skills: List<SkillRecord>,
    selectedSkillIds: List<String>,
    onAttach: () -> Unit,
    onThinkingModeChange: (Boolean) -> Unit,
    onRemoveSkill: (String) -> Unit,
    onRemoveAttachment: (String) -> Unit,
    onRetryAttachment: (String) -> Unit,
    onPreviewAttachment: (Attachment) -> Unit,
    blockingReason: String?,
    onSend: () -> Unit,
    onStop: () -> Unit,
) {
    val selectedSkills = selectedSkillIds.mapNotNull { id ->
        skills.firstOrNull { it.id == id && it.enabled }
    }
    val haptics = LocalHapticFeedback.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .navigationBarsPadding()
            .imePadding()
            .padding(12.dp)
            .animateContentSize(),
    ) {
        AnimatedVisibility(
            visible = attachments.isNotEmpty(),
            enter = fadeIn(),
            exit = fadeOut(),
        ) {
            DraftAttachmentTray(
                attachments = attachments,
                onRemove = onRemoveAttachment,
                onRetry = onRetryAttachment,
                onPreview = onPreviewAttachment,
            )
        }
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(bottom = 8.dp),
        ) {
            item {
                FilterChip(
                    selected = thinkingEnabled,
                    onClick = { onThinkingModeChange(!thinkingEnabled) },
                    enabled = !sending,
                    label = { Text("Thinking") },
                    leadingIcon = if (thinkingEnabled) {
                        { Icon(Icons.Default.Check, contentDescription = null, Modifier.size(16.dp)) }
                    } else null,
                )
            }
            items(selectedSkills, key = SkillRecord::id) { skill ->
                AssistChip(
                    onClick = { onRemoveSkill(skill.id) },
                    label = {
                        Text(
                            skill.name,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    leadingIcon = {
                        Icon(Icons.Default.CheckCircle, contentDescription = null, Modifier.size(16.dp))
                    },
                )
            }
        }
        AnimatedVisibility(visible = blockingReason != null) {
            Text(
                blockingReason.orEmpty(),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }
        // A disabled attach button with no explanation reads as a broken app.
        AnimatedVisibility(visible = attachments.size >= MAX_MESSAGE_ATTACHMENTS) {
            Text(
                "Attachment limit reached ($MAX_MESSAGE_ATTACHMENTS per message).",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }
        Row(verticalAlignment = Alignment.Bottom) {
            IconButton(
                onClick = onAttach,
                enabled = enabled && !sending && attachments.size < MAX_MESSAGE_ATTACHMENTS,
                modifier = Modifier.size(50.dp),
            ) {
                Icon(Icons.Default.AttachFile, contentDescription = "Add attachment")
            }
            OutlinedTextField(
                value = input,
                onValueChange = onInputChange,
                // Deliberately not gated on `sending`. On-device generation is
                // slow, and locking the composer for its whole duration means
                // the user just watches. A second send cannot slip through:
                // ChatViewModel.sendMessage returns early while generationJob is
                // active, and the button below is a Stop button while sending.
                // Disabling a focused TextField also tears down the keyboard
                // mid-word, which is the part users actually feel.
                enabled = enabled,
                placeholder = {
                    Text(
                        if (enabled) "Message your local model" else "Set up a model first",
                    )
                },
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(22.dp),
                minLines = 1,
                maxLines = 6,
                // Enter inserts a newline rather than sending: the field is
                // multi-line (maxLines = 6) and mapping Enter to send would make
                // paragraphs impossible. Sending stays on the button.
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Default),
            )
            Spacer(Modifier.width(8.dp))
            FilledIconButton(
                onClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.ContextClick)
                    if (sending) onStop() else onSend()
                },
                enabled = sending || (
                    enabled &&
                        (input.isNotBlank() || attachments.isNotEmpty()) &&
                        attachments.all { it.state == AttachmentProcessingState.READY } &&
                        blockingReason == null
                ),
                modifier = Modifier.size(50.dp),
                // FilledIconButton rather than IconButton + .background(): a
                // caller-supplied background ignores `enabled`, so the disabled
                // send button used to render as a solid primary-coloured circle
                // that looked completely pressable and did nothing. Letting the
                // component own its colours is what makes the disabled state
                // visible at all — the tint must not be hardcoded either.
                colors = IconButtonDefaults.filledIconButtonColors(),
            ) {
                Icon(
                    if (sending) Icons.Default.Stop else Icons.AutoMirrored.Filled.Send,
                    contentDescription = if (sending) "Stop generation" else "Send",
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SkillPickerSheet(
    skills: List<SkillRecord>,
    selectedSkillIds: List<String>,
    onToggleSkill: (String) -> Unit,
    onClearSkills: () -> Unit,
    onOpenSkills: () -> Unit,
    onDismiss: () -> Unit,
) {
    val enabledSkills = skills.filter(SkillRecord::enabled)
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Skills", style = MaterialTheme.typography.titleLarge)
                    Text(
                        "Choose up to $MAX_SELECTED_SKILLS prompt-only skills for the next message.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(onClick = onOpenSkills) { Text("Manage") }
            }
            if (enabledSkills.isEmpty()) {
                Text(
                    "No enabled skills yet. Create one from the Skills page.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                LazyColumn(
                    modifier = Modifier.heightIn(max = 360.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(enabledSkills, key = SkillRecord::id) { skill ->
                        val selected = skill.id in selectedSkillIds
                        val blocked = !selected && selectedSkillIds.size >= MAX_SELECTED_SKILLS
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(enabled = !blocked) { onToggleSkill(skill.id) },
                            colors = CardDefaults.cardColors(
                                containerColor = if (selected) {
                                    MaterialTheme.colorScheme.secondaryContainer
                                } else {
                                    MaterialTheme.colorScheme.surfaceContainerLow
                                },
                            ),
                        ) {
                            Row(
                                modifier = Modifier.padding(14.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(skill.name, fontWeight = FontWeight.SemiBold)
                                    Text(
                                        skill.description,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                FilterChip(
                                    selected = selected,
                                    enabled = !blocked,
                                    onClick = { onToggleSkill(skill.id) },
                                    label = { Text(if (selected) "Selected" else "Use") },
                                    leadingIcon = if (selected) {
                                        {
                                            Icon(
                                                Icons.Default.Check,
                                                contentDescription = null,
                                                Modifier.size(16.dp),
                                            )
                                        }
                                    } else {
                                        null
                                    },
                                )
                            }
                        }
                    }
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(
                    onClick = onClearSkills,
                    enabled = selectedSkillIds.isNotEmpty(),
                ) { Text("Clear") }
                Spacer(Modifier.width(8.dp))
                Button(onClick = onDismiss) { Text("Done") }
            }
            Spacer(Modifier.height(10.dp))
        }
    }
}

@Composable
private fun DraftAttachmentTray(
    attachments: List<Attachment>,
    onRemove: (String) -> Unit,
    onRetry: (String) -> Unit,
    onPreview: (Attachment) -> Unit,
) {
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(bottom = 10.dp),
    ) {
        items(attachments, key = { it.id }) { attachment ->
            Card(
                modifier = Modifier
                    .width(150.dp)
                    .clickable { onPreview(attachment) },
            ) {
                Box {
                    AttachmentThumbnail(attachment, Modifier.fillMaxWidth().height(92.dp))
                    IconButton(
                        onClick = { onRemove(attachment.id) },
                        modifier = Modifier.align(Alignment.TopEnd),
                    ) {
                        Icon(Icons.Default.Close, contentDescription = "Remove ${attachment.displayName}")
                    }
                }
                Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
                    Text(
                        attachment.displayName,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.labelMedium,
                    )
                    AnimatedContent(attachment.state, label = "attachment-state") { state ->
                        when (state) {
                            AttachmentProcessingState.READY -> Text(
                                attachmentSummary(attachment),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            AttachmentProcessingState.FAILED -> TextButton(
                                onClick = { onRetry(attachment.id) },
                                contentPadding = PaddingValues(0.dp),
                            ) {
                                Icon(Icons.Default.Refresh, null, Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("Retry")
                            }
                            else -> {
                                LinearProgressIndicator(
                                    progress = { attachment.progress.coerceIn(0f, 1f) },
                                    modifier = Modifier.fillMaxWidth(),
                                )
                                Text(
                                    state.name.lowercase().replaceFirstChar(Char::uppercase),
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MessageAttachmentGrid(
    attachments: List<Attachment>,
    onPreview: (Attachment) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        attachments.forEach { attachment ->
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onPreview(attachment) },
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                ),
            ) {
                Row(
                    modifier = Modifier.padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    AttachmentThumbnail(attachment, Modifier.size(72.dp))
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(attachment.displayName, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(
                            attachmentSummary(attachment),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AttachmentThumbnail(attachment: Attachment, modifier: Modifier = Modifier) {
    val path = attachment.previewPath ?: attachment.derivedImagePaths.firstOrNull()
    if (path != null) {
        AsyncImage(
            model = File(path),
            contentDescription = attachment.displayName,
            modifier = modifier
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHighest),
        )
    } else {
        Box(
            modifier = modifier
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHighest),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                when (attachment.kind) {
                    AttachmentKind.PDF -> Icons.Default.PictureAsPdf
                    AttachmentKind.AUDIO -> Icons.Default.Mic
                    else -> Icons.AutoMirrored.Filled.InsertDriveFile
                },
                contentDescription = null,
            )
        }
    }
}

private fun attachmentSummary(attachment: Attachment): String = when {
    attachment.durationMillis != null -> formatMediaDuration(attachment.durationMillis)
    attachment.pageCount != null -> "${attachment.pageCount} pages"
    else -> formatBytes(attachment.byteSize)
}

private fun formatMediaDuration(durationMillis: Long): String {
    val totalSeconds = durationMillis.coerceAtLeast(0L) / 1_000L
    return "%d:%02d".format(totalSeconds / 60L, totalSeconds % 60L)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AttachmentSourceSheet(
    onDismiss: () -> Unit,
    onCamera: () -> Unit,
    onPhotos: () -> Unit,
    onFiles: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Text(
            "Add attachment",
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
        )
        listOf(
            Triple(Icons.Default.CameraAlt, "Camera", onCamera),
            Triple(Icons.Default.Image, "Photos", onPhotos),
            Triple(Icons.Default.FolderOpen, "Files", onFiles),
        ).forEach { (icon, label, action) ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = action)
                    .padding(horizontal = 24.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(icon, contentDescription = null)
                Spacer(Modifier.width(16.dp))
                Text(label, style = MaterialTheme.typography.bodyLarge)
            }
        }
        Spacer(Modifier.navigationBarsPadding().height(12.dp))
    }
}

@Composable
private fun AttachmentPreviewDialog(
    attachment: Attachment,
    onDismiss: () -> Unit,
    onSelectPages: (Set<Int>) -> Unit,
) {
    var selected by remember(attachment.id, attachment.selectedPages) {
        mutableStateOf(attachment.selectedPages)
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(attachment.displayName, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        text = {
            Column {
                attachment.previewPath?.let {
                    AsyncImage(
                        model = File(it),
                        contentDescription = attachment.displayName,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(280.dp)
                            .clip(RoundedCornerShape(14.dp)),
                    )
                    Spacer(Modifier.height(12.dp))
                }
                if (attachment.kind == AttachmentKind.PDF && attachment.pageCount != null) {
                    Text("Choose pages for visual analysis. Leave all clear for automatic selection.")
                    Spacer(Modifier.height(8.dp))
                    val pageImages = attachment.derivedImagePaths.associateBy { path ->
                        Regex("page-(\\d+)").find(File(path).name)
                            ?.groupValues?.get(1)?.toIntOrNull()
                    }
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items((1..attachment.pageCount).toList()) { page ->
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                pageImages[page]?.let { path ->
                                    AsyncImage(
                                        model = File(path),
                                        contentDescription = "PDF page $page",
                                        modifier = Modifier
                                            .size(width = 84.dp, height = 112.dp)
                                            .clip(RoundedCornerShape(8.dp))
                                            .clickable {
                                                selected = if (page in selected) selected - page
                                                else selected + page
                                            },
                                    )
                                }
                                FilterChip(
                                    selected = page in selected,
                                    onClick = {
                                        selected = if (page in selected) selected - page else selected + page
                                    },
                                    label = { Text("$page") },
                                )
                            }
                        }
                    }
                } else if (attachment.error != null) {
                    Text(attachment.error, color = MaterialTheme.colorScheme.error)
                } else {
                    Text(
                        "${attachment.kind.name.lowercase().replaceFirstChar(Char::uppercase)} · " +
                            formatBytes(attachment.byteSize),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            if (attachment.kind == AttachmentKind.PDF) {
                Button(onClick = { onSelectPages(selected) }) { Text("Use pages") }
            } else {
                Button(onClick = onDismiss) { Text("Done") }
            }
        },
        dismissButton = {
            if (attachment.kind == AttachmentKind.PDF) {
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}

@Composable
private fun ProjectorDownloadDialog(
    projector: ProjectorRecord,
    onDownload: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Enable local vision?") },
        text = {
            Text(
                "Download ${projector.displayName} (${formatBytes(projector.expectedBytes)}). " +
                    "It is public, verified, and stored only on this phone.",
            )
        },
        confirmButton = { Button(onClick = onDownload) { Text("Download") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Not now") } },
    )
}

/** Type filter chips for the memory list, extracted to keep MemoryCenter readable. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MemoryTypeFilters(
    types: List<MemoryType>,
    selected: MemoryType?,
    onSelect: (MemoryType?) -> Unit,
) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        FilterChip(
            selected = selected == null,
            onClick = { onSelect(null) },
            label = { Text("All") },
        )
        types.forEach { type ->
            FilterChip(
                selected = selected == type,
                onClick = { onSelect(if (selected == type) null else type) },
                label = { Text(type.name.lowercase().replaceFirstChar(Char::uppercase)) },
            )
        }
    }
}

@Composable
private fun MemoryCenter(
    state: MemoryUiState,
    actions: MemoryViewModel,
    onExportOffice: (CharArray) -> Unit,
    onImportOffice: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var query by remember { mutableStateOf("") }
    var editing by remember { mutableStateOf<MemoryItem?>(null) }
    var creating by remember { mutableStateOf(false) }
    var exporting by remember { mutableStateOf(false) }
    var typeFilter by remember { mutableStateOf<MemoryType?>(null) }
    val visible = remember(state.memories, query, typeFilter) {
        val value = query.trim()
        state.memories.filter { typeFilter == null || it.type == typeFilter }.let { rows ->
            if (value.isEmpty()) rows else rows.filter {
                it.title.contains(value, ignoreCase = true) ||
                    it.content.contains(value, ignoreCase = true) ||
                    it.type.name.contains(value, ignoreCase = true)
            }
        }
    }
    val memoryTypes = remember(state.memories) {
        state.memories.map(MemoryItem::type).distinct()
    }
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Card {
                Column(Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Personal memory", style = MaterialTheme.typography.titleMedium)
                            Text(
                                "Portable context shared by every local model",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(
                            checked = state.memoryEnabled,
                            onCheckedChange = actions::setMemoryEnabled,
                        )
                    }
                }
            }
        }
        item {
            Card {
                Column(Modifier.padding(16.dp)) {
                    Text("Portable Office", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Encrypted export includes chats, memories, settings, " +
                            "and original attachments. Models, tokens, indexes, and keys stay out.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(10.dp))
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Button(
                            onClick = { exporting = true },
                            enabled = !state.backupBusy,
                        ) {
                            Text("Export")
                        }
                        OutlinedButton(
                            onClick = onImportOffice,
                            enabled = !state.backupBusy,
                        ) {
                            Text("Import")
                        }
                        if (state.backupBusy) {
                            CircularProgressIndicator(Modifier.size(32.dp))
                        }
                    }
                }
            }
        }
        item {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text("Search memory") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (memoryTypes.size > 1) {
            item {
                MemoryTypeFilters(
                    types = memoryTypes,
                    selected = typeFilter,
                    onSelect = { typeFilter = it },
                )
            }
        }
        item {
            Button(onClick = { creating = true }, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.Add, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Add memory")
            }
        }
        if (visible.isEmpty()) {
            item {
                Text(
                    if (query.isBlank()) "No memories yet. Chat normally or add one manually."
                    else "No matching memories.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(12.dp),
                )
            }
        }
        items(visible, key = { it.id }) { memory ->
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { editing = memory },
            ) {
                Column(Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            memory.type.name.lowercase().replaceFirstChar(Char::uppercase),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.weight(1f),
                        )
                        if (memory.pinned) {
                            AssistChip(onClick = {}, label = { Text("Pinned") })
                        }
                    }
                    Text(memory.content, maxLines = 5, overflow = TextOverflow.Ellipsis)
                    val provenance = remember(memory.id, state.memorySources) {
                        state.memorySources[memory.id].orEmpty()
                            .mapNotNull { it.label }
                            .filter(String::isNotBlank)
                            .distinct()
                            .joinToString(" \u00b7 ")
                    }
                    if (provenance.isNotEmpty()) {
                        Text(
                            "From $provenance",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { actions.pinMemory(memory.id, !memory.pinned) }) {
                            Text(if (memory.pinned) "Unpin" else "Pin")
                        }
                        TextButton(onClick = { editing = memory }) { Text("Edit") }
                        TextButton(onClick = { actions.forgetMemory(memory.id) }) {
                            Text("Forget", color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
        }
    }

    if (creating) {
        MemoryEditorDialog(
            title = "Add memory",
            initial = "",
            onDismiss = { creating = false },
            onSave = {
                actions.addMemory(it)
                creating = false
            },
        )
    }
    editing?.let { memory ->
        MemoryEditorDialog(
            title = "Correct memory",
            initial = memory.content,
            onDismiss = { editing = null },
            onSave = {
                actions.correctMemory(memory.id, it)
                editing = null
            },
        )
    }
    if (exporting) {
        BackupPassphraseDialog(
            minPassphraseLength = 12,
            title = "Encrypt Office backup",
            confirmationLabel = "Choose destination",
            busy = false,
            onDismiss = { exporting = false },
            onConfirm = {
                exporting = false
                onExportOffice(it)
            },
        )
    }
    if (state.pendingBackupImportUri != null) {
        BackupPassphraseDialog(
            minPassphraseLength = 8,
            title = "Unlock Office backup",
            confirmationLabel = "Validate",
            busy = state.backupBusy,
            onDismiss = actions::discardOfficeImport,
            onConfirm = actions::prepareOfficeImport,
        )
    }
    state.backupPreview?.let { preview ->
        AlertDialog(
            onDismissRequest = {
                if (!state.backupBusy) actions.discardOfficeImport()
            },
            title = { Text("Import this Office?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("${preview.conversations} conversations")
                    Text("${preview.messages} messages")
                    Text("${preview.memories} memories")
                    Text("${preview.attachments} attachments")
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Existing local data is preserved. Matching IDs and memory content are merged.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = actions::commitOfficeImport,
                    enabled = !state.backupBusy,
                ) {
                    if (state.backupBusy) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                        )
                    } else {
                        Text("Import")
                    }
                }
            },
            dismissButton = {
                TextButton(
                    onClick = actions::discardOfficeImport,
                    enabled = !state.backupBusy,
                ) {
                    Text("Cancel")
                }
            },
        )
    }
}





/**
 * Opt-in control for the sentence embedder behind semantic recall.
 *
 * Deliberately a manual download rather than something the app fetches on its own:
 * it is 318 MB, and without it memory retrieval still works on exact words.
 */
@Composable
private fun SemanticRecallRow(
    state: SemanticRecallUiState,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
    onRemove: () -> Unit,
) {
    Column {
        // No title here: the section header above already names this.
        Text(
            when (state.status) {
                DownloadStatus.READY ->
                    "On. Memories are matched by meaning, not only by shared words."
                DownloadStatus.DOWNLOADING, DownloadStatus.QUEUED ->
                    "Downloading the embedding model"
                else ->
                    "Off. Add a 318 MB embedding model to match memories by meaning, " +
                        "so \"what do I drink in the mornings\" finds a note about coffee."
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        state.error?.takeIf(String::isNotBlank)?.let { error ->
            Text(
                error,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        Spacer(Modifier.height(8.dp))
        when (state.status) {
            DownloadStatus.DOWNLOADING, DownloadStatus.QUEUED -> {
                if (state.totalBytes > 0) {
                    LinearProgressIndicator(
                        progress = {
                            (state.downloadedBytes.toFloat() / state.totalBytes).coerceIn(0f, 1f)
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                }
                OutlinedButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) {
                    Text("Pause download")
                }
            }
            DownloadStatus.READY -> {
                OutlinedButton(onClick = onRemove, modifier = Modifier.fillMaxWidth()) {
                    Text("Remove embedding model")
                }
            }
            else -> {
                Button(onClick = onDownload, modifier = Modifier.fillMaxWidth()) {
                    Text("Download embedding model")
                }
            }
        }
    }
}

@Composable
private fun MemoryEditorDialog(
    title: String,
    initial: String,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
) {
    var value by remember(initial) { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it },
                label = { Text("What should wochat remember?") },
                minLines = 4,
                maxLines = 10,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            Button(
                onClick = { onSave(value.trim()) },
                enabled = value.isNotBlank(),
            ) {
                Text("Save")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun SkillsScreen(
    state: SkillsUiState,
    actions: SkillsViewModel,
    modifier: Modifier = Modifier,
) {
    var editingSkill by remember { mutableStateOf<SkillRecord?>(null) }
    var creating by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<SkillRecord?>(null) }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    SectionTitle("Skills", "Prompt-only workflows for the next message")
                }
                Button(onClick = { creating = true }) {
                    Icon(Icons.Default.Add, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("New")
                }
            }
            Text(
                "Skills never run code or contact services. A selected skill is copied into the " +
                    "prompt for that turn and saved as a snapshot on the user message.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
        if (state.skills.isEmpty()) {
            item {
                Card {
                    Column(
                        modifier = Modifier.padding(18.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text("No skills yet", fontWeight = FontWeight.SemiBold)
                        Text(
                            "Create a focused instruction set, then select it from the chat composer.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        OutlinedButton(onClick = { creating = true }) {
                            Text("Create your first skill")
                        }
                    }
                }
            }
        } else {
            items(state.skills, key = SkillRecord::id) { skill ->
                SkillCard(
                    skill = skill,
                    onEdit = { editingSkill = skill },
                    onEnabledChange = { actions.setSkillEnabled(skill.id, it) },
                    onDelete = { pendingDelete = skill },
                )
            }
        }
    }

    if (creating) {
        SkillEditorDialog(
            title = "Create skill",
            skill = null,
            onDismiss = { creating = false },
            onSave = { name, description, instructions ->
                actions.createSkill(name, description, instructions)
                creating = false
            },
        )
    }
    editingSkill?.let { skill ->
        SkillEditorDialog(
            title = "Edit skill",
            skill = skill,
            onDismiss = { editingSkill = null },
            onSave = { name, description, instructions ->
                actions.updateSkill(skill.id, name, description, instructions)
                editingSkill = null
            },
        )
    }
    pendingDelete?.let { skill ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Delete ${skill.name}?") },
            text = {
                Text("Past messages keep their skill snapshots. This only removes the skill from future use.")
            },
            confirmButton = {
                Button(onClick = {
                    actions.deleteSkill(skill.id)
                    pendingDelete = null
                }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun SkillCard(
    skill: SkillRecord,
    onEdit: () -> Unit,
    onEnabledChange: (Boolean) -> Unit,
    onDelete: () -> Unit,
) {
    Card {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(skill.name, fontWeight = FontWeight.SemiBold)
                    Text(
                        if (skill.enabled) "Enabled" else "Disabled",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (skill.enabled) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
                Switch(
                    checked = skill.enabled,
                    onCheckedChange = onEnabledChange,
                )
            }
            Text(
                skill.description,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                skill.instructions.replace(Regex("\\s+"), " "),
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onEdit) { Text("Edit") }
                TextButton(onClick = onDelete) { Text("Delete") }
            }
        }
    }
}

@Composable
private fun SkillEditorDialog(
    title: String,
    skill: SkillRecord?,
    onDismiss: () -> Unit,
    onSave: (String, String, String) -> Unit,
) {
    var name by remember(skill?.id) { mutableStateOf(skill?.name.orEmpty()) }
    var description by remember(skill?.id) { mutableStateOf(skill?.description.orEmpty()) }
    var instructions by remember(skill?.id) { mutableStateOf(skill?.instructions.orEmpty()) }
    var submitted by remember(skill?.id) { mutableStateOf(false) }
    val trimmedName = name.trim()
    val trimmedDescription = description.trim()
    val trimmedInstructions = instructions.trim()
    val nameError = skillFieldError(
        value = trimmedName,
        label = "Skill name",
        maxChars = MAX_SKILL_NAME_CHARS,
        showRequired = submitted,
    )
    val descriptionError = skillFieldError(
        value = trimmedDescription,
        label = "Skill description",
        maxChars = MAX_SKILL_DESCRIPTION_CHARS,
        showRequired = submitted,
    )
    val instructionsError = skillFieldError(
        value = trimmedInstructions,
        label = "Skill instructions",
        maxChars = MAX_SKILL_INSTRUCTIONS_CHARS,
        showRequired = submitted,
    )
    val hasValidationErrors = trimmedName.isEmpty() ||
        trimmedName.length > MAX_SKILL_NAME_CHARS ||
        trimmedDescription.isEmpty() ||
        trimmedDescription.length > MAX_SKILL_DESCRIPTION_CHARS ||
        trimmedInstructions.isEmpty() ||
        trimmedInstructions.length > MAX_SKILL_INSTRUCTIONS_CHARS

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            LazyColumn(
                modifier = Modifier.heightIn(max = 520.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                item {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text("Name") },
                        singleLine = true,
                        isError = nameError != null,
                        supportingText = {
                            Text(nameError ?: "${name.length}/$MAX_SKILL_NAME_CHARS")
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                item {
                    OutlinedTextField(
                        value = description,
                        onValueChange = { description = it },
                        label = { Text("Description") },
                        minLines = 2,
                        maxLines = 4,
                        isError = descriptionError != null,
                        supportingText = {
                            Text(
                                descriptionError ?:
                                    "${description.length}/$MAX_SKILL_DESCRIPTION_CHARS",
                            )
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                item {
                    OutlinedTextField(
                        value = instructions,
                        onValueChange = { instructions = it },
                        label = { Text("Instructions") },
                        minLines = 8,
                        maxLines = 14,
                        isError = instructionsError != null,
                        supportingText = {
                            Text(
                                instructionsError ?:
                                    "${instructions.length}/$MAX_SKILL_INSTRUCTIONS_CHARS",
                            )
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    submitted = true
                    if (!hasValidationErrors) {
                        onSave(trimmedName, trimmedDescription, trimmedInstructions)
                    }
                },
            ) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

private fun skillFieldError(
    value: String,
    label: String,
    maxChars: Int,
    showRequired: Boolean,
): String? = when {
    value.isEmpty() && showRequired -> "$label is required."
    value.length > maxChars -> "$label must be $maxChars characters or less."
    else -> null
}

private enum class SettingsSection(val label: String) {
    MODELS("Models"),
    RUNTIME("Runtime"),
    MEMORY("Memory"),
    SKILLS("Skills"),
}

@Composable
private fun SettingsHub(
    section: SettingsSection,
    onSectionChange: (SettingsSection) -> Unit,
    modelState: ModelSetupUiState,
    modelActions: ModelSetupViewModel,
    memoryState: MemoryUiState,
    memoryActions: MemoryViewModel,
    skillsState: SkillsUiState,
    skillsActions: SkillsViewModel,
    onExportDiagnostics: () -> Unit,
    onExportOffice: (CharArray) -> Unit,
    onImportOffice: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(modifier = modifier.fillMaxSize()) {
        NavigationRail(
            modifier = Modifier
                .fillMaxHeight()
                .testTag("settings-navigation"),
        ) {
            SettingsSection.entries.forEach { destination ->
                NavigationRailItem(
                    selected = section == destination,
                    onClick = { onSectionChange(destination) },
                    icon = {
                        Icon(
                            when (destination) {
                                SettingsSection.MODELS -> Icons.Default.Storage
                                SettingsSection.RUNTIME -> Icons.Default.Settings
                                SettingsSection.MEMORY -> Icons.Default.Info
                                SettingsSection.SKILLS -> Icons.Default.CheckCircle
                            },
                            contentDescription = null,
                        )
                    },
                    label = { Text(destination.label) },
                    alwaysShowLabel = true,
                )
            }
        }
        Box(modifier = Modifier.weight(1f)) {
            when (section) {
                SettingsSection.MODELS,
                SettingsSection.RUNTIME,
                -> SettingsScreen(
                    state = modelState,
                    actions = modelActions,
                    section = section,
                    onExportDiagnostics = onExportDiagnostics,
                )
                SettingsSection.MEMORY -> MemoryCenter(
                    state = memoryState,
                    actions = memoryActions,
                    onExportOffice = onExportOffice,
                    onImportOffice = onImportOffice,
                )
                SettingsSection.SKILLS -> SkillsScreen(
                    state = skillsState,
                    actions = skillsActions,
                )
            }
        }
    }
}

@Composable
private fun SettingsScreen(
    state: ModelSetupUiState,
    actions: ModelSetupViewModel,
    section: SettingsSection,
    onExportDiagnostics: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var pendingDownload by remember { mutableStateOf<ModelRecord?>(null) }
    var showTokenDialog by remember { mutableStateOf(false) }
    val selectedModel = state.models.firstOrNull(ModelRecord::selected)
    val selectedProfile = selectedModel?.let { model ->
        state.contextProfiles
            .filter { it.modelId == model.id }
            .maxByOrNull(ModelContextProfile::updatedAt)
    }
    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .testTag("settings-list"),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        if (section == SettingsSection.MODELS) {
            item {
                SectionTitle("Gemma 4 E4B", "The only supported local model; weights stay in private storage")
            }
            item {
                Card {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("Download network", style = MaterialTheme.typography.titleSmall)
                            Text(
                                if (state.allowMeteredModelDownloads) {
                                    "Wi-Fi and mobile/metered networks"
                                } else {
                                    "Wi-Fi or another unmetered network only"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(
                            checked = state.allowMeteredModelDownloads,
                            onCheckedChange = actions::setAllowMeteredModelDownloads,
                        )
                    }
                }
            }
            items(state.models, key = { it.id }) { model ->
                ModelCard(
                    model = model,
                    allowMeteredDownloads = state.allowMeteredModelDownloads,
                    contextProfile = state.contextProfiles
                        .filter { it.modelId == model.id }
                        .maxByOrNull(ModelContextProfile::updatedAt),
                    onDownload = { pendingDownload = model },
                    onRetry = { actions.startDownload(model.id) },
                    onPause = { actions.pauseDownload(model.id) },
                    onSelect = { actions.selectModel(model.id) },
                    onDelete = { actions.deleteModel(model.id) },
                )
            }
            item {
                SectionTitle("Vision projector", "Matching image component for Gemma 4 E4B")
            }
            items(state.projectors, key = { it.id }) { projector ->
                ProjectorCard(
                    projector = projector,
                    allowMeteredDownloads = state.allowMeteredModelDownloads,
                    onDownload = { actions.startProjectorDownload(projector.id) },
                    onRetry = { actions.startProjectorDownload(projector.id) },
                    onPause = { actions.pauseProjectorDownload(projector.id) },
                    onDelete = { actions.deleteProjector(projector.id) },
                )
            }
            item {
                SectionTitle(
                    "Semantic recall",
                    "Optional embedding model so memories match by meaning",
                )
                Spacer(Modifier.height(10.dp))
                Card {
                    Column(Modifier.padding(16.dp)) {
                        SemanticRecallRow(
                            state = state.semanticRecall,
                            onDownload = actions::downloadEmbeddingModel,
                            onCancel = actions::pauseEmbeddingModelDownload,
                            onRemove = actions::deleteEmbeddingModel,
                        )
                    }
                }
            }
            item {
                SectionTitle("Hugging Face", "Optional token for gated downloads")
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(state.tokenMasked ?: "No token saved", modifier = Modifier.weight(1f))
                    if (state.tokenMasked != null) {
                        TextButton(onClick = actions::clearToken) { Text("Clear") }
                    }
                    Button(onClick = { showTokenDialog = true }) {
                        Text(if (state.tokenMasked == null) "Add token" else "Replace")
                    }
                }
                Text(
                    "The official Gemma download is public and does not need a token.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (section == SettingsSection.RUNTIME) {
            item {
                SectionTitle("Appearance", "How wochat looks on this device")
            }
            item {
                Card {
                    Column(Modifier.fillMaxWidth().padding(16.dp)) {
                        Text("Theme", style = MaterialTheme.typography.titleSmall)
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            ThemeMode.entries.forEach { mode ->
                                FilterChip(
                                    selected = state.themeMode == mode,
                                    onClick = { actions.setThemeMode(mode) },
                                    label = {
                                        Text(
                                            mode.name.lowercase()
                                                .replaceFirstChar(Char::uppercase),
                                        )
                                    },
                                )
                            }
                        }
                        Spacer(Modifier.height(16.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    "Wallpaper colours",
                                    style = MaterialTheme.typography.titleSmall,
                                )
                                Text(
                                    "Off by default: the neutral palette keeps private " +
                                        "surfaces looking the same on every device.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Switch(
                                checked = state.dynamicColor,
                                onCheckedChange = actions::setDynamicColor,
                            )
                        }
                    }
                }
            }
            item {
                ContextProfileCard(
                    model = selectedModel,
                    profile = selectedProfile,
                    residencyState = state.residencyState,
                    reverifyEnabled = selectedModel?.status == DownloadStatus.READY &&
                        selectedProfile?.state != ContextVerificationState.VERIFYING &&
                        !state.isSending,
                    onReverify = actions::reverifyContext,
                )
            }
            item {
            SectionTitle("Inference", "Tuned for the connected Redmi K80 Pro")
            Spacer(Modifier.height(10.dp))
            Text("Backend", style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                BackendMode.entries.forEach { backend ->
                    FilterChip(
                        selected = state.backendMode == backend,
                        onClick = { actions.updateBackend(backend) },
                        label = { Text(backend.name.lowercase().replaceFirstChar(Char::uppercase)) },
                        leadingIcon = if (state.backendMode == backend) {
                            { Icon(Icons.Default.Check, contentDescription = null, Modifier.size(16.dp)) }
                        } else null,
                    )
                }
            }
            Button(
                onClick = actions::optimizeForThisPhone,
                enabled = selectedModel?.status == DownloadStatus.READY &&
                    !state.isOptimizingBackend && !state.isSending,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (state.isOptimizingBackend) "Benchmarking CPU and Vulkan…" else "Optimize for this phone")
            }
            state.benchmarks.forEach { benchmark ->
                Text(
                    if (benchmark.success) {
                        "${benchmark.backend.name}: " +
                            "${"%.1f".format(benchmark.generationTokensPerSecond ?: 0.0)} tok/s · " +
                            "${benchmark.loadMillis ?: 0} ms load"
                    } else {
                        "${benchmark.backend.name}: ${benchmark.failureReason ?: "benchmark failed"}"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (benchmark.success) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else MaterialTheme.colorScheme.error,
                )
            }
            Text(
                "Context is selected automatically per model from successful tests on this phone. " +
                    "The 16 GB memory extension is storage-backed swap; HyperOS may still enforce " +
                    "its per-process memory policy.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            Text("Persistent preload", style = MaterialTheme.typography.labelLarge)
            Text(
                residencyLabel(state.residencyState),
                style = MaterialTheme.typography.bodyMedium,
            )
            val ready = state.residencyState as? ModelResidencyState.Ready
            if (ready != null) {
                Text(
                    "Loaded in ${formatMillis(ready.loadMillis)} · " +
                        residentContextLabel(ready),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = actions::retryPreload) { Text("Retry preload") }
                TextButton(onClick = actions::unloadModel) { Text("Unload") }
            }
            }
            item {
                GenerationSettingsEditor(
                    settings = state.generationSettings,
                    onChange = actions::updateGeneration,
                )
            }
            item {
                SectionTitle("Diagnostics", "Private device and runtime state; no chats or source content")
                Spacer(Modifier.height(10.dp))
                OutlinedButton(onClick = onExportDiagnostics, modifier = Modifier.fillMaxWidth()) {
                    Text("Export diagnostics JSON")
                }
            }
        }
        item {
            Spacer(Modifier.height(24.dp))
        }
    }

    pendingDownload?.let { model ->
        AlertDialog(
            onDismissRequest = { pendingDownload = null },
            title = { Text("Download ${model.displayName}?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        "This downloads ${formatBytes(model.expectedBytes ?: 0)}. " +
                            "Interrupted transfers resume automatically from the verified partial file.",
                    )
                    Text(
                        if (state.allowMeteredModelDownloads) {
                            "Network: Wi-Fi or mobile/metered. You can change this above."
                        } else {
                            "Network: unmetered only. Enable mobile/metered downloads above if needed."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                Button(onClick = {
                    pendingDownload = null
                    actions.startDownload(model.id)
                }) { Text("Download") }
            },
            dismissButton = {
                TextButton(onClick = { pendingDownload = null }) { Text("Cancel") }
            },
        )
    }

    if (showTokenDialog) {
        TokenDialog(
            testing = state.tokenTesting,
            onDismiss = { if (!state.tokenTesting) showTokenDialog = false },
            onSave = {
                actions.saveToken(it)
                showTokenDialog = false
            },
        )
    }
}

private fun residencyLabel(state: ModelResidencyState): String = when (state) {
    ModelResidencyState.Idle -> "Model is not resident"
    ModelResidencyState.WaitingForUnlock -> "Waiting for phone unlock"
    ModelResidencyState.WaitingForModel -> "Download or select a model"
    is ModelResidencyState.Loading -> "Preloading ${state.modelName}"
    is ModelResidencyState.Ready -> "CPU model resident"
    is ModelResidencyState.Error -> state.message
}

private fun residentContextLabel(state: ModelResidencyState.Ready): String {
    val active = "${formatContextTokens(state.contextSize)} active context"
    return when {
        state.verifiedContextSize >= state.contextSize -> "$active · verified"
        state.contextVerificationState == ContextVerificationState.FAILED ->
            "$active · unverified fallback"
        state.contextVerificationState == ContextVerificationState.VERIFYING ->
            "$active · verification in progress"
        else -> "$active · verification pending"
    }
}

private fun formatMillis(millis: Long): String =
    if (millis >= 1_000) "%.1f s".format(millis / 1_000.0) else "$millis ms"

@Composable
private fun ContextProfileCard(
    model: ModelRecord?,
    profile: ModelContextProfile?,
    residencyState: ModelResidencyState,
    reverifyEnabled: Boolean,
    onReverify: () -> Unit,
) {
    SectionTitle("Automatic context", "Read-only, verified for each model and device")
    Spacer(Modifier.height(10.dp))
    Card {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (model == null) {
                Text("Select an installed model to resolve its context.")
                return@Column
            }
            Text(model.displayName, fontWeight = FontWeight.SemiBold)
            val ready = residencyState as? ModelResidencyState.Ready
            val activeTokens = ready
                ?.takeIf { model.selected }
                ?.contextSize
                ?: profile?.verifiedContextTokens?.takeIf { it > 0 }
                ?: 4_096
            ContextValueRow("Active context", formatContextTokens(activeTokens))
            ContextValueRow(
                "Model maximum",
                profile?.declaredContextTokens
                    ?.takeIf { it > 0 }
                    ?.let(::formatContextTokens)
                    ?: "Read after first load",
            )
            when (profile?.state) {
                ContextVerificationState.VERIFYING -> {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text(
                        "Testing ${formatContextTokens(profile.lastAttemptedTokens ?: activeTokens)}. " +
                            "Send remains available at the latest passing context.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                ContextVerificationState.VERIFIED -> Text(
                    if (profile.declaredContextTokens > 0 &&
                        profile.verifiedContextTokens >= profile.declaredContextTokens
                    ) {
                        "The model maximum has passed allocation and generation on this phone."
                    } else {
                        "Verified on this phone. Higher supported sizes continue during idle time."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                ContextVerificationState.LIMITED -> Text(
                    "Limited by ${formatContextTokens(profile.lastAttemptedTokens ?: activeTokens)}: " +
                        (profile.failureReason ?: "the next context test did not pass"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
                ContextVerificationState.FAILED -> Text(
                    profile.failureReason ?: "The baseline context verification did not pass.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
                ContextVerificationState.UNVERIFIED, null -> Text(
                    "wochat starts at 4K and tests larger contexts after 15 seconds of idle time.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            profile?.verifiedAt?.let { verifiedAt ->
                Text(
                    "Last verified ${formatVerificationDate(verifiedAt)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            OutlinedButton(
                onClick = onReverify,
                enabled = reverifyEnabled,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Re-verify")
            }
        }
    }
}

@Composable
private fun ContextValueRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
    }
}

private fun modelContextSummary(profile: ModelContextProfile): String = when (profile.state) {
    ContextVerificationState.VERIFYING ->
        "Testing ${formatContextTokens(profile.lastAttemptedTokens ?: 4_096)} context"
    ContextVerificationState.LIMITED ->
        "${formatContextTokens(profile.verifiedContextTokens.coerceAtLeast(4_096))} verified on this phone"
    ContextVerificationState.FAILED -> "Baseline context test failed"
    ContextVerificationState.UNVERIFIED -> "Starts at 4K; verification pending"
    ContextVerificationState.VERIFIED ->
        "${formatContextTokens(profile.verifiedContextTokens)} verified · " +
            "${profile.declaredContextTokens.takeIf { it > 0 }?.let(::formatContextTokens) ?: "?"} model max"
}

private fun formatContextTokens(tokens: Int): String = when {
    tokens >= 1_024 && tokens % 1_024 == 0 -> "${tokens / 1_024}K tokens"
    else -> "$tokens tokens"
}

private fun formatVerificationDate(timestamp: Long): String =
    DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(timestamp))

@Composable
private fun ModelCard(
    model: ModelRecord,
    allowMeteredDownloads: Boolean,
    contextProfile: ModelContextProfile?,
    onDownload: () -> Unit,
    onRetry: () -> Unit,
    onPause: () -> Unit,
    onSelect: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (model.selected) MaterialTheme.colorScheme.secondaryContainer
            else MaterialTheme.colorScheme.surfaceContainer,
        ),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Storage, contentDescription = null)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(model.displayName, fontWeight = FontWeight.SemiBold)
                    Text(
                        model.expectedBytes?.let(::formatBytes) ?: "Local GGUF",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    officialModelSummary(model.id)?.let { summary ->
                        Text(
                            summary,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    contextProfile?.let {
                        Text(
                            modelContextSummary(it),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (model.selected) AssistChip(onClick = {}, label = { Text("Active") })
            }
            if (model.status in setOf(
                    DownloadStatus.QUEUED,
                    DownloadStatus.DOWNLOADING,
                    DownloadStatus.VERIFYING,
                    DownloadStatus.PAUSED,
                )
            ) {
                Spacer(Modifier.height(12.dp))
                val total = model.expectedBytes ?: 0L
                LinearProgressIndicator(
                    progress = {
                        if (total > 0) (model.downloadedBytes.toFloat() / total).coerceIn(0f, 1f) else 0f
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "${model.status.name.lowercase().replaceFirstChar(Char::uppercase)} · " +
                        "${formatBytes(model.downloadedBytes)} of ${formatBytes(total)}",
                    style = MaterialTheme.typography.bodySmall,
                )
                if (model.bytesPerSecond > 0) {
                    Text(
                        "${formatTransferRate(model.bytesPerSecond)}" +
                            (model.etaSeconds?.let { " · ${formatEta(it)} remaining" } ?: ""),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else if (model.status == DownloadStatus.QUEUED && model.retryAttempt > 0) {
                    Text(
                        "Retry ${model.retryAttempt} scheduled · tap Retry now to skip the delay",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else if (model.status == DownloadStatus.QUEUED) {
                    Text(
                        if (allowMeteredDownloads) {
                            "Waiting for a network connection and Android's download scheduler"
                        } else {
                            "Waiting for an unmetered network and Android's download scheduler"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            model.error?.let {
                Spacer(Modifier.height(8.dp))
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                when (model.status) {
                    DownloadStatus.NOT_DOWNLOADED,
                    DownloadStatus.FAILED,
                    DownloadStatus.PAUSED -> Button(onClick = onDownload) {
                        Icon(Icons.Default.Download, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text(if (model.downloadedBytes > 0) "Resume" else "Download")
                    }
                    DownloadStatus.DOWNLOADING,
                    DownloadStatus.QUEUED -> OutlinedButton(onClick = onPause) {
                        Icon(Icons.Default.Pause, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text("Pause")
                    }
                    DownloadStatus.READY -> {
                        if (!model.selected) Button(onClick = onSelect) { Text("Use model") }
                        OutlinedButton(onClick = onDelete) {
                            Icon(Icons.Default.Delete, contentDescription = null)
                            Spacer(Modifier.width(6.dp))
                            Text("Delete")
                        }
                    }
                    DownloadStatus.VERIFYING -> Unit
                }
                if (model.status == DownloadStatus.QUEUED) {
                    TextButton(onClick = onRetry) { Text("Retry now") }
                }
            }
        }
    }
}

private fun officialModelSummary(modelId: String): String? = when (modelId) {
    ModelConstants.GEMMA_4_E4B.id -> "Balanced mobile model · 4.5B effective parameters"
    else -> null
}

private fun formatTransferRate(bytesPerSecond: Long): String =
    "${formatBytes(bytesPerSecond)}/s"

private fun formatEta(seconds: Long): String = when {
    seconds >= 3_600 -> "${seconds / 3_600}h ${(seconds % 3_600) / 60}m"
    seconds >= 60 -> "${seconds / 60}m ${seconds % 60}s"
    else -> "${seconds}s"
}

@Composable
private fun ProjectorCard(
    projector: ProjectorRecord,
    allowMeteredDownloads: Boolean,
    onDownload: () -> Unit,
    onRetry: () -> Unit,
    onPause: () -> Unit,
    onDelete: () -> Unit,
) {
    Card {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Image, contentDescription = null)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(projector.displayName, fontWeight = FontWeight.SemiBold)
                    Text(
                        formatBytes(projector.expectedBytes),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (projector.status == DownloadStatus.READY) {
                    AssistChip(onClick = {}, label = { Text("Ready") })
                }
            }
            if (projector.status in setOf(
                    DownloadStatus.QUEUED,
                    DownloadStatus.DOWNLOADING,
                    DownloadStatus.VERIFYING,
                    DownloadStatus.PAUSED,
                )
            ) {
                Spacer(Modifier.height(10.dp))
                LinearProgressIndicator(
                    progress = {
                        (projector.downloadedBytes.toFloat() / projector.expectedBytes)
                            .coerceIn(0f, 1f)
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "${projector.status.name.lowercase().replaceFirstChar(Char::uppercase)} · " +
                        "${formatBytes(projector.downloadedBytes)} of ${formatBytes(projector.expectedBytes)}",
                    style = MaterialTheme.typography.bodySmall,
                )
                if (projector.bytesPerSecond > 0) {
                    Text(
                        "${formatTransferRate(projector.bytesPerSecond)}" +
                            (projector.etaSeconds?.let { " · ${formatEta(it)} remaining" } ?: ""),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else if (projector.status == DownloadStatus.QUEUED) {
                    Text(
                        when {
                            projector.retryAttempt > 0 ->
                                "Retry ${projector.retryAttempt} scheduled · tap Retry now to skip the delay"
                            allowMeteredDownloads ->
                                "Waiting for a network connection and Android's download scheduler"
                            else ->
                                "Waiting for an unmetered network and Android's download scheduler"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            projector.error?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
            Spacer(Modifier.height(10.dp))
            when (projector.status) {
                DownloadStatus.NOT_DOWNLOADED,
                DownloadStatus.FAILED,
                DownloadStatus.PAUSED -> Button(onClick = onDownload) {
                    Icon(Icons.Default.Download, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text(if (projector.downloadedBytes > 0) "Resume" else "Download")
                }
                DownloadStatus.QUEUED,
                DownloadStatus.DOWNLOADING -> OutlinedButton(onClick = onPause) {
                    Icon(Icons.Default.Pause, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("Pause")
                }
                DownloadStatus.READY -> OutlinedButton(onClick = onDelete) {
                    Icon(Icons.Default.Delete, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("Delete")
                }
                DownloadStatus.VERIFYING -> Unit
            }
            if (projector.status == DownloadStatus.QUEUED) {
                TextButton(onClick = onRetry) { Text("Retry now") }
            }
        }
    }
}

@Composable
private fun GenerationSettingsEditor(
    settings: GenerationSettings,
    onChange: (GenerationSettings) -> Unit,
) {
    SectionTitle("Generation", "Changes apply to the next response")
    Spacer(Modifier.height(10.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Thinking mode")
            Text(
                "Slower, deeper responses",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(
            checked = settings.thinkingEnabled,
            onCheckedChange = { onChange(settings.copy(thinkingEnabled = it)) },
        )
    }
    Spacer(Modifier.height(10.dp))
    NumberField(
        label = "Answer limit",
        value = settings.maxAnswerTokens.toString(),
        onValue = { value ->
            value.toIntOrNull()?.let { onChange(settings.copy(maxAnswerTokens = it)) }
        },
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(10.dp))
    OutlinedTextField(
        value = settings.temperature.toString(),
        onValueChange = { value ->
            value.toFloatOrNull()?.let { onChange(settings.copy(temperature = it)) }
        },
        label = { Text("Temperature") },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(10.dp))
    OutlinedTextField(
        value = settings.systemPrompt,
        onValueChange = { onChange(settings.copy(systemPrompt = it)) },
        label = { Text("System prompt") },
        minLines = 3,
        maxLines = 6,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun NumberField(
    label: String,
    value: String,
    onValue: (String) -> Unit,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValue,
        enabled = enabled,
        label = { Text(label) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = modifier,
    )
}

@Composable
private fun SectionTitle(title: String, subtitle: String) {
    Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
    Text(
        subtitle,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun TokenDialog(
    testing: Boolean,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
) {
    var token by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Hugging Face token") },
        text = {
            Column {
                Text("Use a fine-grained or read-only token. It is encrypted with Android Keystore.")
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = token,
                    onValueChange = { token = it },
                    label = { Text("hf_…") },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onSave(token) },
                enabled = token.startsWith("hf_") && !testing,
            ) {
                if (testing) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                }
                Text("Test and save")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun BackupPassphraseDialog(
    title: String,
    confirmationLabel: String,
    busy: Boolean,
    minPassphraseLength: Int = 8,
    onDismiss: () -> Unit,
    onConfirm: (CharArray) -> Unit,
) {
    var passphrase by remember { mutableStateOf("") }
    val weakPassphrase = passphrase.isNotEmpty() &&
        passphrase.length < 12 &&
        passphrase.none(Char::isDigit) &&
        passphrase.none { !it.isLetterOrDigit() }
    AlertDialog(
        onDismissRequest = {
            if (!busy) {
                passphrase = ""
                onDismiss()
            }
        },
        title = { Text(title) },
        text = {
            Column {
                OutlinedTextField(
                    value = passphrase,
                    onValueChange = { passphrase = it },
                    label = { Text("Passphrase") },
                    supportingText = {
                        Text(
                            if (weakPassphrase) {
                                "At least $minPassphraseLength characters. It cannot be " +
                                    "recovered. Backups are encrypted with the passphrase " +
                                    "alone, so a long one is the only brute-force defense. " +
                                    "Weak passphrase — a stronger one better protects your data."
                            } else {
                                "At least $minPassphraseLength characters. It cannot be " +
                                    "recovered. Backups are encrypted with the passphrase " +
                                    "alone, so a long one is the only brute-force defense."
                            },
                        )
                    },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (busy) {
                    Spacer(Modifier.height(12.dp))
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(passphrase.toCharArray()) },
                enabled = passphrase.length >= minPassphraseLength && !busy,
            ) {
                Text(confirmationLabel)
            }
        },
        dismissButton = {
            TextButton(
                onClick = {
                    passphrase = ""
                    onDismiss()
                },
                enabled = !busy,
            ) { Text("Cancel") }
        },
    )
}
