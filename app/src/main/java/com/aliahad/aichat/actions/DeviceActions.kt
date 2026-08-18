package com.aliahad.aichat.actions

import android.content.Context
import android.hardware.camera2.CameraManager
import android.util.Log
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Things the assistant can do to the device.
 *
 * Plan 038. The model proposes a call, this executes it, and the result is
 * reported back in words — a tool that fails silently is worse than no tool,
 * because the user is left believing something happened.
 *
 * Deliberately platform APIs only. `setTorchMode` needs no permission and works
 * on any Android phone, which is the same reasoning that kept the call
 * notification on `CallStyle` rather than a vendor island.
 */
class DeviceActions(private val context: Context) {

    /**
     * Tool declarations handed to the model, as JSON.
     *
     * This lands in the prompt prefix, so it costs prefill once per conversation
     * and then rides the KV cache. Descriptions are written for a 4B model: short,
     * concrete, and saying when *not* to call as well as when to.
     */
    fun declarations(): String = TOOLS

    /** Runs [name] with [arguments], returning what to tell the user. */
    fun execute(name: String, arguments: String): ActionResult = when (name) {
        TOGGLE_FLASHLIGHT -> toggleFlashlight(arguments)
        else -> ActionResult(false, "I don't know how to do that yet.")
    }

    private fun toggleFlashlight(arguments: String): ActionResult {
        val on = runCatching {
            val parsed = Json.parseToJsonElement(arguments) as? JsonObject
            parsed?.get("on")?.jsonPrimitive?.content?.toBooleanStrictOrNull()
        }.getOrNull() ?: return ActionResult(false, "I couldn't tell whether to switch it on or off.")

        return runCatching {
            val manager = context.getSystemService(CameraManager::class.java)
            // The first camera that reports a flash unit. Picking by index would
            // break on phones whose front camera enumerates first.
            val cameraId = manager.cameraIdList.firstOrNull { id ->
                manager.getCameraCharacteristics(id)
                    .get(android.hardware.camera2.CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            } ?: return ActionResult(false, "This phone doesn't have a flashlight I can reach.")
            manager.setTorchMode(cameraId, on)
            torchOn = on
            ActionResult(true, if (on) "Flashlight on." else "Flashlight off.")
        }.getOrElse { error ->
            Log.w(TAG, "Flashlight toggle failed", error)
            // Camera-in-use is the common one, and worth saying rather than a
            // generic failure the user cannot act on.
            ActionResult(false, "I couldn't change the flashlight — the camera may be in use.")
        }
    }

    /** Best-effort record of the torch, so "turn it off" works without querying. */
    var torchOn: Boolean = false
        private set

    companion object {
        private const val TAG = "DeviceActions"
        const val TOGGLE_FLASHLIGHT = "toggle_flashlight"

        private val TOOLS = """
            [
              {
                "name": "$TOGGLE_FLASHLIGHT",
                "description": "Turn the phone's flashlight (torch) on or off. Only call this when the user asks about the flashlight or torch.",
                "parameters": {
                  "type": "object",
                  "properties": {
                    "on": {
                      "type": "boolean",
                      "description": "true to switch the flashlight on, false to switch it off"
                    }
                  },
                  "required": ["on"]
                }
              }
            ]
        """.trimIndent()
    }
}

/** What an action did, and what to say about it. */
data class ActionResult(val succeeded: Boolean, val message: String)
