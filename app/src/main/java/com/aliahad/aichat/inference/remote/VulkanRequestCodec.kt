package com.aliahad.aichat.inference.remote

import android.content.Context
import com.aliahad.aichat.core.AttachmentContext
import com.aliahad.aichat.core.ChatMessage
import com.aliahad.aichat.core.ChatTurn
import com.aliahad.aichat.core.GenerationSettings
import com.aliahad.aichat.core.MessageRole
import com.aliahad.aichat.core.MessageStatus
import com.aliahad.aichat.core.UserTurn
import java.io.File
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

internal data class RestoreRequest(
    val conversationId: String,
    val history: List<ChatTurn>,
    val settings: GenerationSettings,
)

internal data class GenerationRequest(
    val turn: UserTurn,
    val settings: GenerationSettings,
)

internal class VulkanRequestCodec(context: Context) {
    private val requestDirectory = File(context.cacheDir, REQUEST_DIRECTORY).apply { mkdirs() }

    fun writeRestore(
        conversationId: String,
        history: List<ChatTurn>,
        settings: GenerationSettings,
    ): String = write(
        JSONObject()
            .put("kind", "restore")
            .put("conversationId", conversationId)
            .put("settings", settings.toJson())
            .put("history", JSONArray().apply { history.forEach { put(it.toJson()) } }),
    )

    fun writeGeneration(turn: UserTurn, settings: GenerationSettings): String = write(
        JSONObject()
            .put("kind", "generation")
            .put("turn", turn.toJson())
            .put("settings", settings.toJson()),
    )

    fun writeTokenCount(text: String): String = write(
        JSONObject().put("kind", "token_count").put("text", text),
    )

    fun readRestore(path: String): RestoreRequest {
        val root = read(path, "restore")
        val history = root.getJSONArray("history").mapObjects { it.toChatTurn() }
        return RestoreRequest(
            conversationId = root.getString("conversationId"),
            history = history,
            settings = root.getJSONObject("settings").toGenerationSettings(),
        )
    }

    fun readGeneration(path: String): GenerationRequest {
        val root = read(path, "generation")
        return GenerationRequest(
            turn = root.getJSONObject("turn").toUserTurn(),
            settings = root.getJSONObject("settings").toGenerationSettings(),
        )
    }

    fun readTokenCount(path: String): String = read(path, "token_count").getString("text")

    private fun write(root: JSONObject): String {
        val target = File(requestDirectory, "${UUID.randomUUID()}.json")
        val temporary = File(requestDirectory, "${target.name}.part")
        temporary.bufferedWriter().use { it.write(root.toString()) }
        check(temporary.length() <= MAX_REQUEST_BYTES) { "Inference request is too large." }
        check(temporary.renameTo(target)) { "Unable to prepare inference request." }
        return target.absolutePath
    }

    private fun read(path: String, expectedKind: String): JSONObject {
        val file = File(path)
        val canonicalParent = file.canonicalFile.parentFile
        require(canonicalParent == requestDirectory.canonicalFile) { "Invalid inference request path." }
        require(file.isFile && file.length() in 1..MAX_REQUEST_BYTES) { "Invalid inference request file." }
        return try {
            JSONObject(file.readText()).also {
                require(it.optString("kind") == expectedKind) { "Unexpected inference request type." }
            }
        } finally {
            file.delete()
        }
    }

    private companion object {
        const val REQUEST_DIRECTORY = "vulkan_requests"
        const val MAX_REQUEST_BYTES = 8L * 1024L * 1024L
    }
}

private fun GenerationSettings.toJson() = JSONObject()
    .put("maxNewTokens", maxNewTokens)
    .put("maxAnswerTokens", maxAnswerTokens)
    .put("temperature", temperature.toDouble())
    .put("thinkingEnabled", thinkingEnabled)
    .put("systemPrompt", systemPrompt)

private fun JSONObject.toGenerationSettings() = GenerationSettings(
    maxNewTokens = getInt("maxNewTokens"),
    maxAnswerTokens = getInt("maxAnswerTokens"),
    temperature = getDouble("temperature").toFloat(),
    thinkingEnabled = getBoolean("thinkingEnabled"),
    systemPrompt = getString("systemPrompt"),
).normalized()

private fun ChatTurn.toJson() = JSONObject()
    .put("role", message.role.name)
    .put("content", message.content)
    .put("attachments", JSONArray().apply { attachments.forEach { put(it.toJson()) } })

private fun JSONObject.toChatTurn(): ChatTurn {
    val role = MessageRole.valueOf(getString("role"))
    return ChatTurn(
        message = ChatMessage(
            id = "remote-history-${UUID.randomUUID()}",
            conversationId = "remote",
            role = role,
            content = getString("content"),
            createdAt = 0,
            status = MessageStatus.COMPLETE,
        ),
        attachments = getJSONArray("attachments").mapObjects { it.toAttachmentContext() },
    )
}

private fun UserTurn.toJson() = JSONObject()
    .put("conversationId", conversationId)
    .put("text", text)
    .put("attachments", JSONArray().apply { attachments.forEach { put(it.toJson()) } })

private fun JSONObject.toUserTurn() = UserTurn(
    conversationId = getString("conversationId"),
    text = getString("text"),
    attachments = getJSONArray("attachments").mapObjects { it.toAttachmentContext() },
)

private fun AttachmentContext.toJson() = JSONObject()
    .put("attachmentId", attachmentId)
    .put("displayName", displayName)
    .put("extractedText", extractedText)
    .put("imagePaths", JSONArray(imagePaths))
    .put("selectedPages", JSONArray(selectedPages.sorted()))
    .put("imageTokenBudget", imageTokenBudget)
    .put("audioPaths", JSONArray(audioPaths))
    .put("audioTokenEstimate", audioTokenEstimate)

private fun JSONObject.toAttachmentContext() = AttachmentContext(
    attachmentId = getString("attachmentId"),
    displayName = getString("displayName"),
    extractedText = getString("extractedText"),
    imagePaths = getJSONArray("imagePaths").mapStrings(),
    selectedPages = getJSONArray("selectedPages").mapInts().toSet(),
    imageTokenBudget = getInt("imageTokenBudget"),
    audioPaths = optJSONArray("audioPaths")?.mapStrings().orEmpty(),
    audioTokenEstimate = optInt("audioTokenEstimate", 0),
)

private inline fun <T> JSONArray.mapObjects(transform: (JSONObject) -> T): List<T> =
    List(length()) { index -> transform(getJSONObject(index)) }

private fun JSONArray.mapStrings(): List<String> = List(length(), ::getString)

private fun JSONArray.mapInts(): List<Int> = List(length(), ::getInt)
