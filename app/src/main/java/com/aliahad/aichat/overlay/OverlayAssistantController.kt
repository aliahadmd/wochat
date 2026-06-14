package com.aliahad.aichat.overlay

import android.content.Context
import com.aliahad.aichat.core.DeviceAction
import com.aliahad.aichat.core.GenerationEvent
import com.aliahad.aichat.core.InferenceExecutionProfile
import com.aliahad.aichat.core.MessageRole
import com.aliahad.aichat.core.UserTurn
import com.aliahad.aichat.data.ChatRepository
import com.aliahad.aichat.device.DeviceActionExecutor
import com.aliahad.aichat.inference.InferenceEngine
import com.aliahad.aichat.residency.ModelResidencyController
import com.aliahad.aichat.settings.AppSettingsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

class OverlayAssistantController(
    private val context: Context,
    private val screenContextProvider: () -> ScreenContextProvider?,
    private val residencyController: ModelResidencyController,
    private val inferenceEngine: InferenceEngine,
    private val settings: AppSettingsRepository,
    private val chatRepository: ChatRepository,
    private val deviceActionExecutor: DeviceActionExecutor,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow<OverlayAssistantState>(OverlayAssistantState.Idle)
    val state: StateFlow<OverlayAssistantState> = _state.asStateFlow()
    private val _overlayContextState = MutableStateFlow(OverlayContextState.Empty)
    val overlayContextState: StateFlow<OverlayContextState> = _overlayContextState.asStateFlow()
    private var generationJob: Job? = null
    private val activeRunId = AtomicLong(0L)

    fun captureVisibleSnapshot(): ScreenSnapshot =
        screenContextProvider()?.captureVisibleSnapshot()
            ?: ScreenSnapshot.unavailable("Enable AIchat screen context access first.")

    fun submit(snapshot: ScreenSnapshot, request: ScreenAssistantRequest) {
        val runId = startNewRun()
        generationJob = scope.launch {
            if (snapshot.blockedReason != null) {
                setState(runId, OverlayAssistantState.Error(snapshot.blockedReason))
                return@launch
            }
            if (snapshot.visibleText.isBlank()) {
                setState(runId, OverlayAssistantState.Error("No readable text was found on this screen."))
                return@launch
            }
            var inferenceUseStarted = false
            val answer = StringBuilder()
            try {
                setState(runId, OverlayAssistantState.Loading())
                val generationSettings = settings.generationSettings.first()
                    .normalized()
                    .copy(systemPrompt = ScreenAssistantPromptFormatter.systemPrompt())
                residencyController.beginInferenceUse()
                inferenceUseStarted = true
                residencyController.ensureLoaded(requireVision = false)
                inferenceEngine.restoreSession(
                    OVERLAY_CONVERSATION_ID,
                    emptyList(),
                    generationSettings,
                )
                inferenceEngine.generate(
                    UserTurn(
                        conversationId = OVERLAY_CONVERSATION_ID,
                        text = ScreenAssistantPromptFormatter.userPrompt(
                            snapshot = snapshot,
                            request = request,
                            previousContext = _overlayContextState.value.turns,
                        ),
                    ),
                    generationSettings,
                    InferenceExecutionProfile.NORMAL,
                ).collect { event ->
                    when (event) {
                        is GenerationEvent.AnswerDelta -> {
                            answer.append(event.text)
                            setState(runId, OverlayAssistantState.Answering(answer.toString()))
                        }
                        is GenerationEvent.Completed -> {
                            completeWithResult(
                                runId = runId,
                                result = ScreenAssistantResult(
                                    request = request,
                                    snapshotPackageName = snapshot.packageName,
                                    answer = answer.toString().trim(),
                                    completedAt = System.currentTimeMillis(),
                                ),
                                targetWindowTitle = snapshot.windowTitle,
                            )
                        }
                        is GenerationEvent.Phase,
                        is GenerationEvent.ThoughtDelta -> Unit
                    }
                }
                if (isActiveRun(runId) && _state.value !is OverlayAssistantState.Complete) {
                    completeWithResult(
                        runId = runId,
                        result = ScreenAssistantResult(
                            request = request,
                            snapshotPackageName = snapshot.packageName,
                            answer = answer.toString().trim(),
                            completedAt = System.currentTimeMillis(),
                        ),
                        targetWindowTitle = snapshot.windowTitle,
                    )
                }
            } catch (cancelled: CancellationException) {
                setState(runId, OverlayAssistantState.Idle)
                throw cancelled
            } catch (error: Throwable) {
                setState(
                    runId,
                    OverlayAssistantState.Error(error.message ?: "The floating assistant failed."),
                )
            } finally {
                if (inferenceUseStarted) residencyController.endInferenceUse()
            }
        }
    }

    fun executeSimple(instruction: String) {
        val request = ScreenAssistantRequest(
            preset = ScreenAssistantPreset.EXPLAIN,
            customInstruction = instruction.trim(),
        )
        val runId = startNewRun()
        generationJob = scope.launch {
            if (request.customInstruction.isBlank()) {
                setState(runId, OverlayAssistantState.Error("Write what you want AIchat to do with this screen."))
                return@launch
            }
            var inferenceUseStarted = false
            val answer = StringBuilder()
            try {
                val provider = screenContextProvider()
                    ?: run {
                        setState(runId, OverlayAssistantState.Error("Enable AIchat screen context access first."))
                        return@launch
                    }
                setState(runId, OverlayAssistantState.Loading("Scanning down through the target app..."))
                val scan = provider.captureScrollableContext(
                    maxPages = MAX_SCAN_PAGES,
                    restorePosition = false,
                )
                scan.blockedReason?.let {
                    setState(runId, OverlayAssistantState.Error(it))
                    return@launch
                }
                if (scan.visibleText.isBlank()) {
                    setState(runId, OverlayAssistantState.Error("No readable text was found on this screen."))
                    return@launch
                }

                setState(runId, OverlayAssistantState.Loading("Writing answer..."))
                val generationSettings = settings.generationSettings.first()
                    .normalized()
                    .copy(
                        maxNewTokens = SIMPLE_EXECUTE_MAX_TOKENS,
                        maxAnswerTokens = SIMPLE_EXECUTE_MAX_TOKENS,
                        temperature = 0.2f,
                        systemPrompt = ScreenAssistantPromptFormatter.systemPrompt(),
                    )
                residencyController.beginInferenceUse()
                inferenceUseStarted = true
                residencyController.ensureLoaded(requireVision = false)
                inferenceEngine.restoreSession(
                    OVERLAY_CONVERSATION_ID,
                    emptyList(),
                    generationSettings,
                )
                var simpleExecuteTimedOut = false
                val watchdog = launch {
                    delay(SIMPLE_EXECUTE_TIMEOUT_MS)
                    simpleExecuteTimedOut = true
                    inferenceEngine.cancel()
                    setState(
                        runId,
                        OverlayAssistantState.Error("Answer timed out. Try a shorter request."),
                    )
                }
                try {
                    inferenceEngine.generate(
                        UserTurn(
                            conversationId = OVERLAY_CONVERSATION_ID,
                            text = ScreenAssistantPromptFormatter.userPrompt(
                                scan = scan,
                                request = request,
                                previousContext = _overlayContextState.value.turns,
                            ),
                        ),
                        generationSettings,
                        InferenceExecutionProfile.NORMAL,
                    ).collect { event ->
                        when (event) {
                            is GenerationEvent.AnswerDelta -> {
                                answer.append(event.text)
                                setState(runId, OverlayAssistantState.Answering(answer.toString()))
                            }
                            is GenerationEvent.Completed -> {
                                completeWithResult(
                                    runId = runId,
                                    result = ScreenAssistantResult(
                                        request = request,
                                        snapshotPackageName = scan.targetPackageName,
                                        answer = answer.toString().trim(),
                                        completedAt = System.currentTimeMillis(),
                                    ),
                                    targetWindowTitle = scan.firstSnapshot?.windowTitle,
                                )
                            }
                            is GenerationEvent.Phase,
                            is GenerationEvent.ThoughtDelta -> Unit
                        }
                    }
                } finally {
                    watchdog.cancel()
                }
                if (simpleExecuteTimedOut) return@launch
                if (isActiveRun(runId) && _state.value !is OverlayAssistantState.Complete) {
                    completeWithResult(
                        runId = runId,
                        result = ScreenAssistantResult(
                            request = request,
                            snapshotPackageName = scan.targetPackageName,
                            answer = answer.toString().trim(),
                            completedAt = System.currentTimeMillis(),
                        ),
                        targetWindowTitle = scan.firstSnapshot?.windowTitle,
                    )
                }
            } catch (cancelled: CancellationException) {
                setState(runId, OverlayAssistantState.Idle)
                throw cancelled
            } catch (error: Throwable) {
                setState(
                    runId,
                    OverlayAssistantState.Error(error.message ?: "The floating assistant failed."),
                )
            } finally {
                if (inferenceUseStarted) residencyController.endInferenceUse()
            }
        }
    }

    fun submitActionPlan(request: ScreenAssistantRequest) {
        val runId = startNewRun()
        generationJob = scope.launch {
            var inferenceUseStarted = false
            val rawResponse = StringBuilder()
            try {
                val provider = screenContextProvider()
                    ?: run {
                        setState(runId, OverlayAssistantState.Error("Enable AIchat screen context access first."))
                        return@launch
                    }
                if (request.customInstruction.isBlank()) {
                    val snapshot = provider.captureVisibleSnapshot()
                    completeWithResult(
                        runId = runId,
                        result = ScreenAssistantResult(
                            request = request,
                            snapshotPackageName = snapshot.packageName,
                            answer = "Type a specific action request, then tap Plan action. No actions were run.",
                            completedAt = System.currentTimeMillis(),
                        ),
                        targetWindowTitle = snapshot.windowTitle,
                    )
                    return@launch
                }
                setState(runId, OverlayAssistantState.Loading("Scanning target app..."))
                val scan = provider.captureScrollableContext(MAX_SCAN_PAGES)
                val blockedReason = scan.blockedReason
                if (blockedReason != null) {
                    setState(runId, OverlayAssistantState.Error(blockedReason))
                    return@launch
                }
                if (scan.visibleText.isBlank()) {
                    setState(runId, OverlayAssistantState.Error("No readable text was found on this screen."))
                    return@launch
                }
                OverlayDeterministicActionPlanner.plan(scan, request)?.let { plan ->
                    setState(
                        runId,
                        OverlayAssistantState.AwaitingConfirmation(
                            plan = plan,
                            targetPackageAllowed = isTargetPackageAllowed(plan.targetPackageName),
                        ),
                    )
                    return@launch
                }

                setState(runId, OverlayAssistantState.Loading("Planning confirmed actions..."))
                val generationSettings = settings.generationSettings.first()
                    .normalized()
                    .copy(
                        maxNewTokens = ACTION_PLANNER_MAX_TOKENS,
                        maxAnswerTokens = ACTION_PLANNER_MAX_TOKENS,
                        temperature = 0.1f,
                        systemPrompt = ScreenAssistantActionPromptFormatter.systemPrompt(),
                    )
                residencyController.beginInferenceUse()
                inferenceUseStarted = true
                residencyController.ensureLoaded(requireVision = false)
                inferenceEngine.restoreSession(
                    OVERLAY_CONVERSATION_ID,
                    emptyList(),
                    generationSettings,
                )
                var plannerTimedOut = false
                val watchdog = launch {
                    delay(ACTION_PLANNER_TIMEOUT_MS)
                    plannerTimedOut = true
                    inferenceEngine.cancel()
                    setState(
                        runId,
                        OverlayAssistantState.Error("Action planning timed out. No actions were run."),
                    )
                }
                try {
                    inferenceEngine.generate(
                        UserTurn(
                            conversationId = OVERLAY_CONVERSATION_ID,
                            text = ScreenAssistantActionPromptFormatter.userPrompt(scan, request),
                        ),
                        generationSettings,
                        InferenceExecutionProfile.NORMAL,
                    ).collect { event ->
                        when (event) {
                            is GenerationEvent.AnswerDelta -> rawResponse.append(event.text)
                            is GenerationEvent.Completed -> Unit
                            is GenerationEvent.Phase,
                            is GenerationEvent.ThoughtDelta -> Unit
                        }
                    }
                } finally {
                    watchdog.cancel()
                }
                if (plannerTimedOut) return@launch
                when (val parsed = OverlayActionPlanParser.parse(rawResponse.toString(), scan, request)) {
                    is OverlayActionPlanParseResult.Parsed -> {
                        setState(
                            runId,
                            OverlayAssistantState.AwaitingConfirmation(
                                plan = parsed.plan,
                                targetPackageAllowed = isTargetPackageAllowed(parsed.plan.targetPackageName),
                            ),
                        )
                    }
                    is OverlayActionPlanParseResult.AnswerOnly -> {
                        val answer = buildString {
                            append(parsed.answer.ifBlank { "I could not create a safe action plan." })
                            appendLine()
                            appendLine()
                            append("No actions will run: ${parsed.reason}")
                        }.trim()
                        completeWithResult(
                            runId = runId,
                            result = ScreenAssistantResult(
                                request = request,
                                snapshotPackageName = scan.targetPackageName,
                                answer = answer,
                                completedAt = System.currentTimeMillis(),
                            ),
                            targetWindowTitle = scan.firstSnapshot?.windowTitle,
                        )
                    }
                }
            } catch (cancelled: CancellationException) {
                setState(runId, OverlayAssistantState.Idle)
                throw cancelled
            } catch (error: Throwable) {
                setState(
                    runId,
                    OverlayAssistantState.Error(error.message ?: "The floating assistant failed to plan actions."),
                )
            } finally {
                if (inferenceUseStarted) residencyController.endInferenceUse()
            }
        }
    }

    fun allowActionsForCurrentPlan() {
        val current = _state.value as? OverlayAssistantState.AwaitingConfirmation ?: return
        generationJob = scope.launch {
            settings.addActionAllowedPackage(current.plan.targetPackageName)
            _state.value = current.copy(targetPackageAllowed = true)
        }
    }

    fun runConfirmedActionPlan() {
        val current = _state.value as? OverlayAssistantState.AwaitingConfirmation ?: return
        if (!current.canRun) return
        val runId = activeRunId.incrementAndGet()
        generationJob = scope.launch {
            val plan = current.plan
            try {
                setState(runId, OverlayAssistantState.Executing(plan))
                val provider = screenContextProvider()
                    ?: run {
                        setState(runId, OverlayAssistantState.Error("Enable AIchat screen context access first."))
                        return@launch
                    }
                val freshScan = provider.captureScrollableContext(maxPages = 1)
                val freshPackage = freshScan.targetPackageName
                val freshBlockedReason = freshScan.blockedReason
                when {
                    freshBlockedReason != null -> {
                        setState(runId, OverlayAssistantState.Error(freshBlockedReason))
                        return@launch
                    }
                    freshPackage != plan.targetPackageName -> {
                        setState(
                            runId,
                            OverlayAssistantState.Error(
                                "The target app changed from ${plan.targetPackageName} to $freshPackage. Re-scan before running actions.",
                            ),
                        )
                        return@launch
                    }
                    !isTargetPackageAllowed(plan.targetPackageName) -> {
                        setState(
                            runId,
                            OverlayAssistantState.AwaitingConfirmation(
                                plan = plan,
                                targetPackageAllowed = false,
                            ),
                        )
                        return@launch
                    }
                }
                val actions: List<DeviceAction> = plan.steps.map { it.toDeviceAction(plan.targetPackageName) }
                val results = deviceActionExecutor.executePlan(
                    actions = actions,
                    confirmedActionIds = actions.map(DeviceAction::id).toSet(),
                )
                val executionSummary = buildString {
                    appendLine(plan.displayText())
                    appendLine()
                    appendLine("Execution results:")
                    results.forEachIndexed { index, result ->
                        val status = if (result.success) "Done" else "Failed"
                        appendLine("${index + 1}. $status: ${result.message}")
                    }
                }.trim()
                completeWithResult(
                    runId = runId,
                    result = ScreenAssistantResult(
                        request = plan.request,
                        snapshotPackageName = plan.targetPackageName,
                        answer = executionSummary,
                        completedAt = System.currentTimeMillis(),
                    ),
                    targetWindowTitle = null,
                )
            } catch (cancelled: CancellationException) {
                setState(runId, OverlayAssistantState.Idle)
                throw cancelled
            } catch (error: Throwable) {
                setState(
                    runId,
                    OverlayAssistantState.Error(error.message ?: "The confirmed action plan failed."),
                )
            }
        }
    }

    suspend fun saveLastResultToChat(): Boolean {
        val result = (state.value as? OverlayAssistantState.Complete)?.result ?: return false
        val conversation = chatRepository.createConversation()
        chatRepository.addMessage(
            conversationId = conversation.id,
            role = MessageRole.USER,
            content = "Floating assistant: ${result.request.displayPrompt()}",
        )
        chatRepository.addMessage(
            conversationId = conversation.id,
            role = MessageRole.ASSISTANT,
            content = result.answer.ifBlank { "No answer was generated." },
        )
        return true
    }

    fun cancelGeneration(resetState: Boolean = true) {
        activeRunId.incrementAndGet()
        generationJob?.cancel()
        inferenceEngine.cancel()
        generationJob = null
        if (resetState) _state.value = OverlayAssistantState.Idle
    }

    fun clearOverlayContext() {
        cancelGeneration(resetState = true)
        _overlayContextState.value = OverlayContextState.Empty
    }

    fun destroy() {
        cancelGeneration()
        scope.cancel()
    }

    private fun startNewRun(): Long {
        generationJob?.cancel()
        inferenceEngine.cancel()
        generationJob = null
        return activeRunId.incrementAndGet()
    }

    private fun isActiveRun(runId: Long): Boolean =
        activeRunId.get() == runId

    private fun setState(runId: Long, state: OverlayAssistantState) {
        if (isActiveRun(runId)) _state.value = state
    }

    private fun completeWithResult(
        runId: Long,
        result: ScreenAssistantResult,
        targetWindowTitle: String?,
    ) {
        if (!isActiveRun(runId)) return
        _state.value = OverlayAssistantState.Complete(result)
        if (result.answer.isNotBlank()) {
            _overlayContextState.value = _overlayContextState.value.append(
                OverlayContextTurn.fromResult(
                    result = result,
                    targetWindowTitle = targetWindowTitle,
                ),
            )
        }
    }

    private suspend fun isTargetPackageAllowed(packageName: String): Boolean =
        packageName.isNotBlank() && packageName in settings.actionAllowlist.first()

    private companion object {
        val OVERLAY_CONVERSATION_ID = "overlay-${UUID.randomUUID()}"
        const val MAX_SCAN_PAGES = 3
        const val SIMPLE_EXECUTE_MAX_TOKENS = 512
        const val SIMPLE_EXECUTE_TIMEOUT_MS = 60_000L
        const val ACTION_PLANNER_MAX_TOKENS = 256
        const val ACTION_PLANNER_TIMEOUT_MS = 45_000L
    }
}
