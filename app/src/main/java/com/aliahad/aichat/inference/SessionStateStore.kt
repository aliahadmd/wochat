package com.aliahad.aichat.inference

import android.util.Log
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Keeps one conversation's KV cache on disk so it survives a model reload.
 *
 * Measured 2026-08-18 on the Redmi K80 Pro: HyperOS trims this app whenever it is
 * backgrounded, which unloads the model, and the next turn re-decodes the entire
 * conversation — `restore` was 72,624 ms cold against 5 ms when the live cache had
 * survived. Nothing in between: either the cache is there or the whole history is
 * paid for again.
 *
 * Only the most recent conversation is kept. A sequence is hundreds of megabytes,
 * this lives in `cacheDir`, and holding a history per conversation would trade a
 * latency problem for a storage one.
 *
 * The cache directory can be cleared by the system at any moment. That is the
 * intended failure mode — a missing file costs exactly what today costs.
 */
internal class SessionStateStore(private val directory: File) {

    val sequenceFile: File get() = File(directory, SEQUENCE_NAME)

    private val descriptorFile: File get() = File(directory, DESCRIPTOR_NAME)

    fun read(): SavedSession? = runCatching {
        if (!descriptorFile.isFile || !sequenceFile.isFile) return null
        json.decodeFromString(SavedSession.serializer(), descriptorFile.readText())
    }.onFailure {
        Log.w(TAG, "Unreadable session descriptor; discarding", it)
        clear()
    }.getOrNull()

    /**
     * Records what the sequence file holds. Written only after the sequence itself
     * is on disk, so a descriptor never describes a file that is not there.
     */
    fun write(saved: SavedSession) {
        runCatching {
            descriptorFile.writeText(json.encodeToString(SavedSession.serializer(), saved))
        }.onFailure {
            Log.w(TAG, "Could not record session descriptor; discarding sequence", it)
            clear()
        }
    }

    fun prepareDirectory(): Boolean = directory.isDirectory || directory.mkdirs()

    fun clear() {
        runCatching {
            descriptorFile.delete()
            sequenceFile.delete()
        }
    }

    private companion object {
        // Serializers are named explicitly rather than reified. The reified form
        // resolves one by reflection over the class name, which R8 renames, so a
        // release build looks for a serializer under an obfuscated name. Naming the
        // generated serializer is a direct call that survives minification.
        const val TAG = "SessionStateStore"
        const val SEQUENCE_NAME = "sequence.bin"
        const val DESCRIPTOR_NAME = "sequence.json"
        val json = Json { ignoreUnknownKeys = true }
    }
}

@Serializable
internal data class SavedSession(
    val conversationId: String,
    val modelPath: String,
    val systemPrompt: String,
    val thinkingEnabled: Boolean,
    val messages: List<SavedMessage>,
    /**
     * The context the sequence was built in. Defaults to 0 so a descriptor written
     * before this field existed still decodes, and then fails the match rather than
     * being trusted.
     */
    val contextSize: Int = 0,
    /**
     * The tool definitions the template rendered this sequence with. Tools reshape
     * the prompt exactly like a system-prompt change, so a mismatch must rebuild.
     * Defaults to the no-tools value for descriptors written before this existed.
     */
    val toolsJson: String = "[]",
)

@Serializable
internal data class SavedMessage(val role: String, val content: String)

/**
 * How many of [history]'s messages [saved] already covers, or [REBUILD_SESSION].
 *
 * Deliberately the same shape as the live check in `nativeSessionPrefixLength`: a
 * saved sequence is reusable when it is a *prefix* of where the conversation now
 * stands, so the remaining messages can be decoded on top of it exactly as they are
 * decoded on top of a surviving cache.
 *
 * Everything the prompt prefix depends on has to match, because the KV cache is
 * only meaningful for the exact tokens it was built from. A changed system prompt
 * or thinking mode reshapes the very first tokens, so the file is worthless — the
 * same reason those changes invalidate the live cache.
 */
internal fun savedSessionPrefixLength(
    saved: SavedSession?,
    conversationId: String,
    modelPath: String?,
    contextSize: Int,
    systemPrompt: String,
    thinkingEnabled: Boolean,
    toolsJson: String,
    history: List<SavedMessage>,
): Int {
    if (saved == null || modelPath == null) return REBUILD_SESSION
    // A different model has a different tokenizer and a different KV geometry, so
    // the bytes on disk are not merely stale, they are unreadable as this model.
    // The context size is not fixed either: ModelResidencyController probes
    // candidates and falls back to a smaller safe context under memory pressure,
    // so the same model can be loaded into different geometry than the sequence
    // was written for. And a changed system prompt, thinking mode or tool set
    // reshapes the very first tokens, making the file worthless — the same
    // reason those changes invalidate the live cache. Re-decoding is the cost of
    // a mismatch; a mis-restored cache would be worse.
    val prefixMismatch = saved.conversationId != conversationId ||
        saved.modelPath != modelPath ||
        saved.contextSize <= 0 ||
        saved.contextSize != contextSize ||
        saved.systemPrompt != systemPrompt ||
        saved.thinkingEnabled != thinkingEnabled ||
        saved.toolsJson != toolsJson
    if (prefixMismatch) return REBUILD_SESSION
    if (saved.messages.isEmpty()) return REBUILD_SESSION
    if (saved.messages.size > history.size) return REBUILD_SESSION
    saved.messages.forEachIndexed { index, message ->
        if (history[index] != message) return REBUILD_SESSION
    }
    return saved.messages.size
}
