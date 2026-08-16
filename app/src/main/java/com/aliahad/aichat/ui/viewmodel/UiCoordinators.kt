package com.aliahad.aichat.ui.viewmodel

import android.app.Application
import android.database.sqlite.SQLiteException
import android.util.Log
import com.aliahad.aichat.data.DatabaseLockedException
import com.aliahad.aichat.memory.MemoryRepository
import com.aliahad.aichat.model.ModelRepository
import com.aliahad.aichat.settings.AppSettingsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import java.io.FileNotFoundException
import java.io.IOException

/**
 * Turns a failure into something worth showing a person.
 *
 * Two rules, and the second is why this is not a blanket replacement:
 *
 * 1. A framework exception's text is written for a developer reading logs, and
 *    when it has no message at all the class name is not an error message. Those
 *    are mapped to plain sentences.
 * 2. Plenty of throws in this codebase carry messages written deliberately for
 *    the user — `error("Attachments exceed the 500 MB message limit.")`,
 *    `require(...) { "The private attachment file is missing." }`. Those must
 *    survive untouched; replacing them with something vaguer would be a
 *    regression, not a fix.
 */
internal fun userFacingMessage(error: Throwable): String = when (error) {
    is DatabaseLockedException ->
        "Unlock your phone to continue — your chats are encrypted with a key that stays locked with the device."
    is SecurityException ->
        "wochat does not have permission to do that. You can grant it in Settings."
    is FileNotFoundException ->
        "That file could not be found. It may have been moved or deleted."
    is IOException ->
        "Something went wrong reading or writing a file. Check your storage and try again."
    is SQLiteException ->
        "Something went wrong with wochat's local storage. If this keeps happening, " +
            "export diagnostics from Settings."
    else -> error.message?.takeIf(String::isUserPresentable) ?: GENERIC_FAILURE_MESSAGE
}

/**
 * Whether a throwable's own message can be shown as-is.
 *
 * Rejects anything that reads like plumbing — a class name, a package path, a
 * stack fragment — since those are the strings that were reaching users.
 */
private fun String.isUserPresentable(): Boolean {
    val trimmed = trim()
    if (trimmed.isEmpty() || trimmed.length > MAX_PRESENTABLE_MESSAGE_LENGTH) return false
    val plumbing = listOf("Exception", "Error:", "java.", "kotlin.", "android.", "androidx.", "at ")
    return plumbing.none { trimmed.contains(it) }
}

private const val MAX_PRESENTABLE_MESSAGE_LENGTH = 200
private const val GENERIC_FAILURE_MESSAGE =
    "Something went wrong. If this keeps happening, export diagnostics from Settings."

/**
 * A failure worth showing, plus what the user can do about it.
 *
 * [action] is a lambda rather than a serialisable command because this is
 * ephemeral UI state that never outlives the process — the alternative is an
 * enum the shell has to switch on, which just moves the coupling.
 */
data class UiMessage(
    val text: String,
    val actionLabel: String? = null,
    val action: (() -> Unit)? = null,
    /** Blocking failures stay on screen; routine notices can time out. */
    val important: Boolean = false,
)

/** One activity-scoped destination for actionable errors from every feature. */
class UiMessageManager {
    private val _error = MutableStateFlow<UiMessage?>(null)
    val error: StateFlow<UiMessage?> = _error.asStateFlow()

    /**
     * Reports a failure. The original is logged in full — this only changes what
     * the *user* sees, never what diagnostics receive.
     *
     * Prefer [report] with a written sentence for anything the user is expected
     * to act on; this overload is the safety net for unexpected failures.
     */
    fun report(error: Throwable) {
        Log.e("UiMessageManager", "Reporting failure to the user", error)
        _error.value = UiMessage(userFacingMessage(error))
    }

    fun report(message: String) {
        _error.value = UiMessage(message)
    }

    /**
     * Reports a failure the user can actually do something about.
     *
     * Only pass an action when retrying is real — an action button that merely
     * dismisses is worse than none.
     */
    fun report(
        message: String,
        actionLabel: String,
        important: Boolean = false,
        action: () -> Unit,
    ) {
        _error.value = UiMessage(
            text = message,
            actionLabel = actionLabel,
            action = action,
            important = important,
        )
    }

    /** As [report], but derives the text from a throwable. */
    fun report(error: Throwable, actionLabel: String, action: () -> Unit) {
        Log.e("UiMessageManager", "Reporting retryable failure to the user", error)
        _error.value = UiMessage(
            text = userFacingMessage(error),
            actionLabel = actionLabel,
            action = action,
            important = true,
        )
    }

    fun clear() {
        _error.value = null
    }
}

/** Shares the optional projector prompt without making feature ViewModels depend on each other. */
class ProjectorPromptCoordinator(
    private val modelRepository: ModelRepository,
) {
    private val _pendingProjectorId = MutableStateFlow<String?>(null)
    val pendingProjectorId: StateFlow<String?> = _pendingProjectorId.asStateFlow()

    suspend fun requestForSelectedModel() {
        val model = modelRepository.selectedModel() ?: return
        val projector = modelRepository.projectorForModel(model.id) ?: return
        if (projector.status != com.aliahad.aichat.core.DownloadStatus.READY) {
            _pendingProjectorId.value = projector.id
        }
    }

    fun dismiss() {
        _pendingProjectorId.value = null
    }
}

