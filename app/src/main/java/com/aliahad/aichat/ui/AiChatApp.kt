package com.aliahad.aichat.ui

import android.animation.ValueAnimator
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Menu
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
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aliahad.aichat.AppPage
import com.aliahad.aichat.MainUiState
import com.aliahad.aichat.MainViewModel
import com.aliahad.aichat.ThinkingUiState
import com.aliahad.aichat.core.BackendMode
import com.aliahad.aichat.core.Attachment
import com.aliahad.aichat.core.AttachmentKind
import com.aliahad.aichat.core.AttachmentProcessingState
import com.aliahad.aichat.core.ActivitySource
import com.aliahad.aichat.core.ChatQualityMode
import com.aliahad.aichat.core.ChatMessage
import com.aliahad.aichat.core.ContextVerificationState
import com.aliahad.aichat.core.DownloadStatus
import com.aliahad.aichat.core.GenerationSettings
import com.aliahad.aichat.core.InferenceState
import com.aliahad.aichat.core.MessageRole
import com.aliahad.aichat.core.MessageStatus
import com.aliahad.aichat.core.MemoryItem
import com.aliahad.aichat.core.ModelContextProfile
import com.aliahad.aichat.core.ModelRecord
import com.aliahad.aichat.core.ProjectorRecord
import com.aliahad.aichat.model.ModelConstants
import com.aliahad.aichat.model.formatBytes
import com.aliahad.aichat.residency.ModelResidencyState
import com.aliahad.aichat.settings.DeviceSettingsNavigator
import com.mikepenz.markdown.compose.MarkdownSuccess
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.model.rememberMarkdownState
import kotlinx.coroutines.launch
import coil3.compose.AsyncImage
import java.io.File
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AiChatApp(
    state: MainUiState,
    actions: MainViewModel,
    onImportModel: () -> Unit,
    onAddPhotos: () -> Unit,
    onAddFiles: () -> Unit,
    onTakePhoto: () -> Unit,
    onExportOffice: (String) -> Unit,
    onImportOffice: () -> Unit,
) {
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val snackbarHost = remember { SnackbarHostState() }

    LaunchedEffect(state.error) {
        state.error?.let {
            snackbarHost.showSnackbar(it)
            actions.clearError()
        }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ConversationDrawer(
                state = state,
                onNewChat = {
                    actions.newConversation()
                    scope.launch { drawerState.close() }
                },
                onNewTemporaryChat = {
                    actions.newTemporaryConversation()
                    scope.launch { drawerState.close() }
                },
                onSelect = {
                    actions.selectConversation(it)
                    scope.launch { drawerState.close() }
                },
                onDelete = actions::deleteConversation,
                onMemory = {
                    actions.setPage(AppPage.MEMORY)
                    scope.launch { drawerState.close() }
                },
                onSettings = {
                    actions.setPage(AppPage.SETTINGS)
                    scope.launch { drawerState.close() }
                },
            )
        },
    ) {
        Scaffold(
            snackbarHost = { SnackbarHost(snackbarHost) },
            topBar = {
                TopAppBar(
                    navigationIcon = {
                        IconButton(onClick = { scope.launch { drawerState.open() } }) {
                            Icon(Icons.Default.Menu, contentDescription = "Open conversations")
                        }
                    },
                    title = {
                        when (state.page) {
                            AppPage.SETTINGS -> Text("Settings")
                            AppPage.MEMORY -> Text("Office Memory")
                            AppPage.CHAT -> {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    state.conversations.firstOrNull {
                                        it.id == state.selectedConversationId
                                    }?.title ?: "AIchat",
                                    modifier = Modifier.weight(1f, fill = false),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Spacer(Modifier.width(10.dp))
                                QualityModePill(
                                    mode = currentQualityMode(state),
                                    enabled = !state.isSending,
                                    onChange = actions::updateQualityMode,
                                )
                            }
                            }
                        }
                    },
                    actions = {
                        if (state.page == AppPage.CHAT) {
                            IconButton(onClick = { actions.setPage(AppPage.SETTINGS) }) {
                                Icon(Icons.Default.Settings, contentDescription = "Settings")
                            }
                        } else {
                            IconButton(onClick = { actions.setPage(AppPage.CHAT) }) {
                                Icon(Icons.Default.Close, contentDescription = "Close settings")
                            }
                        }
                    },
                )
            },
        ) { padding ->
            when (state.page) {
                AppPage.CHAT -> ChatScreen(
                    state = state,
                    onSend = actions::sendMessage,
                    onStop = actions::stopGeneration,
                    onContinue = actions::continueResponse,
                    onToggleThinking = actions::toggleThinking,
                    onOpenSettings = { actions.setPage(AppPage.SETTINGS) },
                    onAddPhotos = onAddPhotos,
                    onAddFiles = onAddFiles,
                    onTakePhoto = onTakePhoto,
                    onRemoveAttachment = actions::removeAttachment,
                    onRetryAttachment = actions::retryAttachment,
                    onSelectPages = actions::selectAttachmentPages,
                    modifier = Modifier.padding(padding),
                )
                AppPage.MEMORY -> MemoryCenter(
                    state = state,
                    actions = actions,
                    onExportOffice = onExportOffice,
                    onImportOffice = onImportOffice,
                    modifier = Modifier.padding(padding),
                )
                AppPage.SETTINGS -> SettingsScreen(
                    state = state,
                    actions = actions,
                    onImportModel = onImportModel,
                    modifier = Modifier.padding(padding),
                )
            }
        }
    }

    state.pendingProjectorId?.let { id ->
        state.projectors.firstOrNull { it.id == id }?.let { projector ->
            ProjectorDownloadDialog(
                projector = projector,
                onDownload = { actions.startProjectorDownload(projector.id) },
                onDismiss = actions::dismissProjectorPrompt,
            )
        }
    }
}

@Composable
private fun QualityModePill(
    mode: ChatQualityMode,
    enabled: Boolean,
    onChange: (ChatQualityMode) -> Unit,
) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(2.dp),
    ) {
        ChatQualityMode.entries.forEach { item ->
            val selected = mode == item
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(
                        if (selected) MaterialTheme.colorScheme.primary
                        else Color.Transparent,
                    )
                    .clickable(enabled = enabled && !selected) { onChange(item) }
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            ) {
                AnimatedContent(targetState = selected, label = "quality-mode") {
                    Text(
                        if (item == ChatQualityMode.FAST) "Fast" else "Best",
                        style = MaterialTheme.typography.labelMedium,
                        color = if (it) MaterialTheme.colorScheme.onPrimary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun ConversationDrawer(
    state: MainUiState,
    onNewChat: () -> Unit,
    onNewTemporaryChat: () -> Unit,
    onSelect: (String) -> Unit,
    onDelete: (String) -> Unit,
    onMemory: () -> Unit,
    onSettings: () -> Unit,
) {
    ModalDrawerSheet(modifier = Modifier.width(320.dp)) {
        Column(
            modifier = Modifier
                .fillMaxHeight()
                .statusBarsPadding()
                .padding(12.dp),
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
            Text(
                "Conversations",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            )
            LazyColumn(modifier = Modifier.weight(1f)) {
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
                        IconButton(onClick = { onDelete(conversation.id) }) {
                            Icon(Icons.Default.Delete, contentDescription = "Delete conversation")
                        }
                    }
                }
            }
            HorizontalDivider()
            TextButton(onClick = onMemory, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.Storage, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Office Memory")
            }
            TextButton(onClick = onSettings, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.Settings, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Models and settings")
            }
        }
    }
}

@Composable
private fun ChatScreen(
    state: MainUiState,
    onSend: (String) -> Unit,
    onStop: () -> Unit,
    onContinue: () -> Unit,
    onToggleThinking: (String) -> Unit,
    onOpenSettings: () -> Unit,
    onAddPhotos: () -> Unit,
    onAddFiles: () -> Unit,
    onTakePhoto: () -> Unit,
    onRemoveAttachment: (String) -> Unit,
    onRetryAttachment: (String) -> Unit,
    onSelectPages: (String, Set<Int>) -> Unit,
    modifier: Modifier = Modifier,
) {
    var input by remember { mutableStateOf("") }
    var showAttachmentSheet by remember { mutableStateOf(false) }
    var previewAttachment by remember { mutableStateOf<Attachment?>(null) }
    val selectedModel = state.models.firstOrNull { it.selected }
    val qualityModelId = when (currentQualityMode(state)) {
        ChatQualityMode.FAST -> ModelConstants.GEMMA_4_E4B.id
        ChatQualityMode.BEST -> ModelConstants.GEMMA_4_12B.id
    }
    val visionRequired = state.draftAttachments.any {
        it.kind == AttachmentKind.IMAGE || it.derivedImagePaths.isNotEmpty()
    }
    val projectorReady = !visionRequired || state.projectors.any {
        it.modelId == qualityModelId && it.status == DownloadStatus.READY
    }

    Column(modifier = modifier.fillMaxSize()) {
        if (selectedModel == null) {
            ModelRequiredBanner(onOpenSettings)
        } else {
            InferenceStatusBar(state, selectedModel)
        }
        if (state.messages.isEmpty()) {
            EmptyChat(
                modelName = selectedModel?.displayName,
                modifier = Modifier.weight(1f),
            )
        } else {
            MessageList(
                messages = state.messages,
                conversationId = state.selectedConversationId,
                attachments = state.messageAttachments,
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
            onInputChange = { input = it },
            sending = state.isSending,
            enabled = selectedModel != null,
            attachments = state.draftAttachments,
            onAttach = { showAttachmentSheet = true },
            onRemoveAttachment = onRemoveAttachment,
            onRetryAttachment = onRetryAttachment,
            onPreviewAttachment = { previewAttachment = it },
            blockingReason = if (!projectorReady) "Install the matching vision projector to send." else null,
            onSend = {
                if (input.isNotBlank() || state.draftAttachments.isNotEmpty()) {
                    onSend(input)
                    input = ""
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
private fun InferenceStatusBar(state: MainUiState, model: ModelRecord) {
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
            Text("AIchat", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            Text(
                modelName?.let { "Private, on-device chat with $it" } ?: "Set up a local model to begin",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
internal fun MessageList(
    messages: List<ChatMessage>,
    conversationId: String?,
    attachments: Map<String, List<Attachment>> = emptyMap(),
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
    LaunchedEffect(
        messages.size,
        lastMessage?.content?.length,
        thinking?.text?.length,
        thinking?.expanded,
        lastMessageHeight,
        lastMessageRenderRevision,
        followLatest,
    ) {
        if (followLatest && messages.isNotEmpty()) {
            listState.scrollToItem(messages.size)
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
        if (!isAtBottom) {
            IconButton(
                onClick = {
                    followLatest = true
                    scope.launch { listState.animateScrollToItem(messages.size) }
                },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
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
                .fillMaxWidth(if (isUser) 0.86f else 1f)
                .clip(RoundedCornerShape(18.dp))
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
            if (!isUser) {
                AnimatedVisibility(
                    visible = thinking != null &&
                        (thinking.text.isNotEmpty() || status == MessageStatus.STREAMING),
                    enter = fadeIn(tween(160)),
                    exit = fadeOut(tween(200)) + shrinkVertically(tween(200)),
                ) {
                    ThinkingPanel(
                        thinking = requireNotNull(thinking),
                        onToggle = onToggleThinking,
                    )
                }
            }
            if (thinking != null && content.isNotEmpty()) Spacer(Modifier.height(8.dp))
            if (content.isEmpty() && status == MessageStatus.STREAMING && thinking == null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text("Thinking locally…", color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                displayText.replace(Regex("\\s+"), " ").trim(),
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
        AnimatedVisibility(
            visible = thinking.expanded && displayText.isNotBlank(),
            enter = fadeIn(tween(if (animationsEnabled) 160 else 0)),
            exit = fadeOut(tween(if (animationsEnabled) 120 else 0)),
        ) {
            Text(
                displayText,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 12.dp, end = 12.dp, bottom = 12.dp)
                    .clearAndSetSemantics {}
                    .testTag("thinking-content"),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun formatThoughtForDisplay(text: String): String =
    text
        .replace("<|channel>thought", "")
        .replace("<|channel>", "")
        .replace("<channel|>", "")
        .lineSequence()
        .joinToString("\n") { line ->
            line.replace(Regex("""^(\s*)[*-]\s+""")) { match ->
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
    attachments: List<Attachment>,
    onAttach: () -> Unit,
    onRemoveAttachment: (String) -> Unit,
    onRetryAttachment: (String) -> Unit,
    onPreviewAttachment: (Attachment) -> Unit,
    blockingReason: String?,
    onSend: () -> Unit,
    onStop: () -> Unit,
) {
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
        AnimatedVisibility(visible = blockingReason != null) {
            Text(
                blockingReason.orEmpty(),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }
        Row(verticalAlignment = Alignment.Bottom) {
        IconButton(
            onClick = onAttach,
            enabled = enabled && !sending && attachments.size < 20,
            modifier = Modifier.size(50.dp),
        ) {
            Icon(Icons.Default.AttachFile, contentDescription = "Add attachment")
        }
        OutlinedTextField(
            value = input,
            onValueChange = onInputChange,
            enabled = enabled && !sending,
            placeholder = { Text(if (enabled) "Message your local model" else "Set up a model first") },
            modifier = Modifier.weight(1f),
            shape = RoundedCornerShape(22.dp),
            minLines = 1,
            maxLines = 6,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Default),
            keyboardActions = KeyboardActions(),
        )
        Spacer(Modifier.width(8.dp))
        IconButton(
            onClick = if (sending) onStop else onSend,
            enabled = sending || (
                enabled &&
                    (input.isNotBlank() || attachments.isNotEmpty()) &&
                    attachments.all { it.state == AttachmentProcessingState.READY } &&
                    blockingReason == null
                ),
            modifier = Modifier
                .size(50.dp)
                .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(50)),
        ) {
            Icon(
                if (sending) Icons.Default.Stop else Icons.AutoMirrored.Filled.Send,
                contentDescription = if (sending) "Stop generation" else "Send",
                tint = MaterialTheme.colorScheme.onPrimary,
            )
        }
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
                                attachment.pageCount?.let { "$it pages" } ?: formatBytes(attachment.byteSize),
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
                            attachment.pageCount?.let { "$it pages" } ?: formatBytes(attachment.byteSize),
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
                if (attachment.kind == AttachmentKind.PDF) Icons.Default.PictureAsPdf
                else Icons.AutoMirrored.Filled.InsertDriveFile,
                contentDescription = null,
            )
        }
    }
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

private fun currentQualityMode(state: MainUiState): ChatQualityMode =
    state.conversations.firstOrNull { it.id == state.selectedConversationId }?.qualityMode
        ?: ChatQualityMode.FAST

@Composable
private fun MemoryCenter(
    state: MainUiState,
    actions: MainViewModel,
    onExportOffice: (String) -> Unit,
    onImportOffice: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var query by remember { mutableStateOf("") }
    var editing by remember { mutableStateOf<MemoryItem?>(null) }
    var creating by remember { mutableStateOf(false) }
    var exporting by remember { mutableStateOf(false) }
    var clearingSource by remember { mutableStateOf<ActivitySource?>(null) }
    val visible = remember(state.memories, query) {
        val value = query.trim()
        if (value.isEmpty()) state.memories else state.memories.filter {
            it.title.contains(value, ignoreCase = true) ||
                it.content.contains(value, ignoreCase = true) ||
                it.type.name.contains(value, ignoreCase = true)
        }
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
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "Phone collection",
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        FilterChip(
                            selected = state.collectionPaused,
                            onClick = { actions.setCollectionPaused(!state.collectionPaused) },
                            label = { Text(if (state.collectionPaused) "Paused" else "Active") },
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
                        "Encrypted export includes chats, memories, retained activity, settings, " +
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
            Card {
                Column(Modifier.padding(16.dp)) {
                    Text("Phone sources", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Every source remains off until you enable it here. Android access is " +
                            "also required where applicable. " +
                            "Passwords, OTPs, keyboards, and secure windows are excluded.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(10.dp))
                    CollectorSourceRow(
                        label = "App usage",
                        description = "Foreground app sessions and duration",
                        checked = ActivitySource.APP_USAGE in state.enabledCollectionSources,
                        paused = state.collectionPaused,
                        onCheckedChange = {
                            actions.setCollectionSourceEnabled(ActivitySource.APP_USAGE, it)
                        },
                        onAccess = { DeviceSettingsNavigator.openUsageAccess(context) },
                        onClear = { clearingSource = ActivitySource.APP_USAGE },
                    )
                    CollectorSourceRow(
                        label = "Installed apps",
                        description = "App inventory and package changes",
                        checked = ActivitySource.APP_INSTALL in state.enabledCollectionSources,
                        paused = state.collectionPaused,
                        onCheckedChange = {
                            actions.setCollectionSourceEnabled(ActivitySource.APP_INSTALL, it)
                        },
                        onClear = { clearingSource = ActivitySource.APP_INSTALL },
                    )
                    CollectorSourceRow(
                        label = "Notifications",
                        description = "Redacted title and visible notification text",
                        checked = ActivitySource.NOTIFICATION in state.enabledCollectionSources,
                        paused = state.collectionPaused,
                        onCheckedChange = {
                            actions.setCollectionSourceEnabled(ActivitySource.NOTIFICATION, it)
                        },
                        onAccess = { DeviceSettingsNavigator.openNotificationAccess(context) },
                        onClear = { clearingSource = ActivitySource.NOTIFICATION },
                    )
                    CollectorSourceRow(
                        label = "Screen context",
                        description = "Visible non-secure UI text and optional actions",
                        checked = ActivitySource.ACCESSIBILITY in state.enabledCollectionSources,
                        paused = state.collectionPaused,
                        onCheckedChange = {
                            actions.setCollectionSourceEnabled(ActivitySource.ACCESSIBILITY, it)
                        },
                        onAccess = { DeviceSettingsNavigator.openAccessibility(context) },
                        onClear = { clearingSource = ActivitySource.ACCESSIBILITY },
                    )
                    CollectorSourceRow(
                        label = "Location",
                        description = "Periodic passive location snapshots",
                        checked = ActivitySource.LOCATION in state.enabledCollectionSources,
                        paused = state.collectionPaused,
                        onCheckedChange = {
                            actions.setCollectionSourceEnabled(ActivitySource.LOCATION, it)
                        },
                        onAccess = { DeviceSettingsNavigator.openAppPermissions(context) },
                        onClear = { clearingSource = ActivitySource.LOCATION },
                    )
                    CollectorSourceRow(
                        label = "Sensors",
                        description = "Low-frequency environment and step snapshots",
                        checked = ActivitySource.SENSOR in state.enabledCollectionSources,
                        paused = state.collectionPaused,
                        onCheckedChange = {
                            actions.setCollectionSourceEnabled(ActivitySource.SENSOR, it)
                        },
                        onAccess = { DeviceSettingsNavigator.openAppPermissions(context) },
                        onClear = { clearingSource = ActivitySource.SENSOR },
                    )
                    CollectorSourceRow(
                        label = "Contacts",
                        description = "Contact names and update timestamps",
                        checked = ActivitySource.CONTACT in state.enabledCollectionSources,
                        paused = state.collectionPaused,
                        onCheckedChange = {
                            actions.setCollectionSourceEnabled(ActivitySource.CONTACT, it)
                        },
                        onAccess = { DeviceSettingsNavigator.openAppPermissions(context) },
                        onClear = { clearingSource = ActivitySource.CONTACT },
                    )
                    CollectorSourceRow(
                        label = "Calendar",
                        description = "Past month and upcoming year of events",
                        checked = ActivitySource.CALENDAR in state.enabledCollectionSources,
                        paused = state.collectionPaused,
                        onCheckedChange = {
                            actions.setCollectionSourceEnabled(ActivitySource.CALENDAR, it)
                        },
                        onAccess = { DeviceSettingsNavigator.openAppPermissions(context) },
                        onClear = { clearingSource = ActivitySource.CALENDAR },
                    )
                    HorizontalDivider(Modifier.padding(vertical = 8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Health Connect", style = MaterialTheme.typography.bodyMedium)
                            Text(
                                "Permission setup is available; local record ingestion is not enabled yet.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        TextButton(
                            onClick = { DeviceSettingsNavigator.openHealthConnect(context) },
                        ) { Text("Access") }
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
                    Text("${preview.activities} retained activities")
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
    clearingSource?.let { source ->
        AlertDialog(
            onDismissRequest = { clearingSource = null },
            title = { Text("Clear collected data?") },
            text = {
                Text(
                    "This permanently removes retained ${source.name.lowercase().replace('_', ' ')} " +
                        "activity from Office Memory. It does not revoke Android access.",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        actions.clearCollectedSource(source)
                        clearingSource = null
                    },
                ) {
                    Text("Clear", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { clearingSource = null }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun CollectorSourceRow(
    label: String,
    description: String,
    checked: Boolean,
    paused: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    onAccess: (() -> Unit)? = null,
    onClear: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text(
                if (paused && checked) "$description. Paused by master control." else description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        onAccess?.let { action ->
            TextButton(onClick = action) { Text("Access") }
        }
        TextButton(onClick = onClear) { Text("Clear") }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
        )
    }
}

@Composable
private fun BackupPassphraseDialog(
    title: String,
    confirmationLabel: String,
    busy: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var passphrase by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(title) },
        text = {
            Column {
                OutlinedTextField(
                    value = passphrase,
                    onValueChange = { passphrase = it },
                    label = { Text("Passphrase") },
                    supportingText = { Text("At least 8 characters. It cannot be recovered.") },
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
                onClick = { onConfirm(passphrase) },
                enabled = passphrase.length >= 8 && !busy,
            ) {
                Text(confirmationLabel)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !busy) { Text("Cancel") }
        },
    )
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
                label = { Text("What should AIchat remember?") },
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
private fun SettingsScreen(
    state: MainUiState,
    actions: MainViewModel,
    onImportModel: () -> Unit,
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
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        item {
            SectionTitle("Models", "Weights stay in private app storage")
        }
        items(state.models, key = { it.id }) { model ->
            ModelCard(
                model = model,
                contextProfile = state.contextProfiles
                    .filter { it.modelId == model.id }
                    .maxByOrNull(ModelContextProfile::updatedAt),
                onDownload = { pendingDownload = model },
                onPause = { actions.pauseDownload(model.id) },
                onSelect = { actions.selectModel(model.id) },
                onDelete = { actions.deleteModel(model.id) },
            )
        }
        item {
            SectionTitle("Vision projectors", "Installed separately after first use")
        }
        items(state.projectors, key = { it.id }) { projector ->
            ProjectorCard(
                projector = projector,
                onDownload = { actions.startProjectorDownload(projector.id) },
                onPause = { actions.pauseProjectorDownload(projector.id) },
                onDelete = { actions.deleteProjector(projector.id) },
            )
        }
        item {
            OutlinedButton(onClick = onImportModel, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.FolderOpen, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Import local GGUF")
            }
        }
        item {
            SectionTitle("Inference", "Tuned for the connected Redmi K80 Pro")
            Spacer(Modifier.height(10.dp))
            Text("Backend", style = MaterialTheme.typography.labelLarge)
            FilterChip(
                selected = true,
                onClick = { actions.updateBackend(BackendMode.CPU) },
                label = { Text("CPU (unrestricted)") },
                leadingIcon = {
                    Icon(Icons.Default.Check, contentDescription = null, Modifier.size(16.dp))
                },
            )
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
            DevicePersistenceSettings()
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
            GenerationSettingsEditor(
                settings = state.generationSettings,
                onChange = actions::updateGeneration,
            )
        }
        item {
            SectionTitle("Hugging Face", "Optional for private or gated repositories")
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
        item {
            Spacer(Modifier.height(24.dp))
        }
    }

    pendingDownload?.let { model ->
        AlertDialog(
            onDismissRequest = { pendingDownload = null },
            title = { Text("Download ${model.displayName}?") },
            text = {
                Text(
                    "This downloads ${formatBytes(model.expectedBytes ?: 0)} over the current network. " +
                        "Keep the phone charged; the download can be paused and resumed.",
                )
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

@Composable
private fun DevicePersistenceSettings() {
    val context = LocalContext.current
    Spacer(Modifier.height(10.dp))
    Text(
        "HyperOS permissions",
        style = MaterialTheme.typography.labelLarge,
    )
    Text(
        "Allow auto-start and unrestricted battery use so reboot preloading has the best chance to run.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { DeviceSettingsNavigator.openAutoStart(context) }) {
            Text("Auto-start")
        }
        OutlinedButton(onClick = { DeviceSettingsNavigator.openBatterySettings(context) }) {
            Text("Battery")
        }
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
                    "AIchat starts at 4K and tests larger contexts after 15 seconds of idle time.",
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
    contextProfile: ModelContextProfile?,
    onDownload: () -> Unit,
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
                    if (model.id == ModelConstants.GEMMA_4_E4B.id) {
                        Text(
                            "Faster mobile option · 4.5B effective parameters",
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
            }
        }
    }
}

@Composable
private fun ProjectorCard(
    projector: ProjectorRecord,
    onDownload: () -> Unit,
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
                    "${formatBytes(projector.downloadedBytes)} of ${formatBytes(projector.expectedBytes)}",
                    style = MaterialTheme.typography.bodySmall,
                )
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
    Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
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
