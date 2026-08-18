package com.aliahad.aichat.actions

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.CalendarContract
import android.hardware.camera2.CameraManager
import android.provider.AlarmClock
import android.util.Log
import com.aliahad.aichat.actions.ActionArguments.boolean
import com.aliahad.aichat.actions.ActionArguments.int
import com.aliahad.aichat.actions.ActionArguments.text

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
    fun execute(name: String, arguments: String): ActionResult {
        val args = ActionArguments.read(arguments)
            ?: return ActionResult(false, "I couldn't read what you asked for.")
        val result = when (name) {
            TOGGLE_FLASHLIGHT -> toggleFlashlight(args)
            SET_ALARM -> setAlarm(args)
            SET_TIMER -> setTimer(args)
            DIAL_NUMBER -> dialNumber(args)
            CREATE_EVENT -> createEvent(args)
            else -> ActionResult(false, "I don't know how to do that yet.")
        }
        // Logged for every call, because a model that merely *claims* to have set an
        // alarm produces a reply indistinguishable from one that really did. Without
        // this line there is no way to tell the two apart from outside the process.
        // Arguments are device settings, not conversation, so they are safe to log.
        Log.i(TAG, "Action $name($arguments) -> ${if (result.succeeded) "ok" else "failed"}: ${result.message}")
        return result
    }

    private fun toggleFlashlight(args: kotlinx.serialization.json.JsonObject): ActionResult {
        val on = args.boolean("on")
            ?: return ActionResult(false, "I couldn't tell whether to switch it on or off.")

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

    private fun setAlarm(args: kotlinx.serialization.json.JsonObject): ActionResult {
        val (hour, minute) = validTimeOfDay(args.int("hour"), args.int("minute"))
            ?: return ActionResult(false, "That isn't a time I can set — I need an hour between 0 and 23.")
        val label = args.text("label")
        val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
            putExtra(AlarmClock.EXTRA_HOUR, hour)
            putExtra(AlarmClock.EXTRA_MINUTES, minute)
            label?.let { putExtra(AlarmClock.EXTRA_MESSAGE, it) }
            // Create it outright rather than dropping the user into the clock app
            // mid-conversation. The confirmation below is what they check instead.
            putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        }
        // The time is echoed in 12-hour form on purpose: the way this goes wrong is
        // 3 AM for "three in the afternoon", and that is only catchable if the
        // confirmation says which one it picked.
        val spoken = spokenTime(hour, minute)
        return start(intent, "Alarm set for $spoken${label?.let { ", $it" } ?: "" }.")
    }

    private fun setTimer(args: kotlinx.serialization.json.JsonObject): ActionResult {
        val seconds = validTimerSeconds(args.int("seconds"))
            ?: return ActionResult(false, "That isn't a length I can time — give me between a second and a day.")
        val label = args.text("label")
        val intent = Intent(AlarmClock.ACTION_SET_TIMER).apply {
            putExtra(AlarmClock.EXTRA_LENGTH, seconds)
            label?.let { putExtra(AlarmClock.EXTRA_MESSAGE, it) }
            putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        }
        return start(intent, "Timer started for ${spokenDuration(seconds)}.")
    }

    /**
     * Opens the dialer with a number filled in. Deliberately does not call it.
     *
     * `ACTION_CALL` would place the call outright and needs CALL_PHONE; `ACTION_DIAL`
     * needs no permission and leaves the last step to a human. For a 4B model
     * reading a number out of conversation, that is the right division: a misheard
     * digit becomes a wrong number visible on screen rather than a wrong call.
     */
    private fun dialNumber(args: kotlinx.serialization.json.JsonObject): ActionResult {
        val number = validPhoneNumber(args.text("number"))
            ?: return ActionResult(false, "That doesn't look like a phone number I can dial.")
        val intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:$number"))
        return start(intent, "Dialer open with $number — press call when you're ready.")
    }

    /**
     * Opens the calendar composer for a new event.
     *
     * The model supplies an hour and how many days ahead rather than a timestamp:
     * epoch arithmetic is exactly the sort of thing it gets quietly wrong, and the
     * device knows what "tomorrow at 3" means far better than a 4B model does.
     * Saving is left to the user, so a misunderstood date is visible before it lands.
     */
    private fun createEvent(args: kotlinx.serialization.json.JsonObject): ActionResult {
        val title = args.text("title")
            ?: return ActionResult(false, "I need to know what the event is called.")
        val (hour, minute) = validTimeOfDay(args.int("hour"), args.int("minute"))
            ?: return ActionResult(false, "That isn't a time I can use — I need an hour between 0 and 23.")
        val daysAhead = validDaysAhead(args.int("days_from_now"))
            ?: return ActionResult(false, "That's further ahead than I can schedule.")
        val minutes = validDurationMinutes(args.int("duration_minutes"))
            ?: return ActionResult(false, "That isn't a length I can use for an event.")

        val start = java.util.Calendar.getInstance().apply {
            add(java.util.Calendar.DAY_OF_YEAR, daysAhead)
            set(java.util.Calendar.HOUR_OF_DAY, hour)
            set(java.util.Calendar.MINUTE, minute)
            set(java.util.Calendar.SECOND, 0)
            set(java.util.Calendar.MILLISECOND, 0)
        }
        val intent = Intent(Intent.ACTION_INSERT)
            .setData(CalendarContract.Events.CONTENT_URI)
            .putExtra(CalendarContract.Events.TITLE, title)
            .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, start.timeInMillis)
            .putExtra(
                CalendarContract.EXTRA_EVENT_END_TIME,
                start.timeInMillis + minutes * 60_000L,
            )
        args.text("location")?.let { intent.putExtra(CalendarContract.Events.EVENT_LOCATION, it) }
        val whenText = when (daysAhead) {
            0 -> "today"
            1 -> "tomorrow"
            else -> "in $daysAhead days"
        }
        return start(
            intent,
            "Calendar open for \"$title\" $whenText at ${spokenTime(hour, minute)} — save it to confirm.",
        )
    }

    /**
     * Fires [intent] at whatever clock app the phone has, reporting [success] only
     * if something actually took it.
     *
     * NEW_TASK because this is launched from an application context, and the
     * resolve check first because a phone with no clock app should be told so
     * rather than throwing ActivityNotFoundException into a chat turn.
     */
    private fun start(intent: Intent, success: String): ActionResult {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (intent.resolveActivity(context.packageManager) == null) {
            return ActionResult(false, "I couldn't find a clock app on this phone to do that.")
        }
        return runCatching {
            context.startActivity(intent)
            ActionResult(true, success)
        }.getOrElse { error ->
            Log.w(TAG, "Could not start ${intent.action}", error)
            ActionResult(false, "The clock app wouldn't open just now.")
        }
    }

    /** Best-effort record of the torch, so "turn it off" works without querying. */
    var torchOn: Boolean = false
        private set

    companion object {
        private const val TAG = "DeviceActions"
        const val TOGGLE_FLASHLIGHT = "toggle_flashlight"
        const val SET_ALARM = "set_alarm"
        const val SET_TIMER = "set_timer"
        const val DIAL_NUMBER = "dial_number"
        const val CREATE_EVENT = "create_event"

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
              },
              {
                "name": "$SET_ALARM",
                "description": "Set an alarm for a specific clock time. Use for a time of day such as 7 in the morning or half past three. For a countdown from now, use $SET_TIMER instead. If the user gives an hour without making clear whether they mean morning or evening, such as just \"at 3\", ask which they mean instead of calling this — guessing wrong wakes them at the wrong end of the day.",
                "parameters": {
                  "type": "object",
                  "properties": {
                    "hour": {
                      "type": "integer",
                      "description": "Hour on a 24-hour clock, 0 to 23. Three in the afternoon is 15, not 3."
                    },
                    "minute": {
                      "type": "integer",
                      "description": "Minute past the hour, 0 to 59. Defaults to 0."
                    },
                    "label": {
                      "type": "string",
                      "description": "What the alarm is for, if the user said."
                    }
                  },
                  "required": ["hour"]
                }
              },
              {
                "name": "$SET_TIMER",
                "description": "Start a countdown timer for a length of time from now, such as ten minutes. For a specific clock time, use $SET_ALARM instead.",
                "parameters": {
                  "type": "object",
                  "properties": {
                    "seconds": {
                      "type": "integer",
                      "description": "How long the timer runs, in seconds. Ten minutes is 600."
                    },
                    "label": {
                      "type": "string",
                      "description": "What the timer is for, if the user said."
                    }
                  },
                  "required": ["seconds"]
                }
              },
              {
                "name": "$DIAL_NUMBER",
                "description": "Open the phone dialer with a number ready to call. The user still presses the call button. Only use a number the user actually gave; never invent one, and if they named a person rather than a number, ask for the number.",
                "parameters": {
                  "type": "object",
                  "properties": {
                    "number": {
                      "type": "string",
                      "description": "The phone number exactly as the user gave it."
                    }
                  },
                  "required": ["number"]
                }
              },
              {
                "name": "$CREATE_EVENT",
                "description": "Open the calendar to add an event at a given day and time. The user still saves it. If the day or whether the time is morning or afternoon is unclear, ask before calling this rather than guessing.",
                "parameters": {
                  "type": "object",
                  "properties": {
                    "title": {
                      "type": "string",
                      "description": "What the event is, such as Lunch with Sam."
                    },
                    "hour": {
                      "type": "integer",
                      "description": "Hour on a 24-hour clock, 0 to 23. Three in the afternoon is 15."
                    },
                    "minute": {
                      "type": "integer",
                      "description": "Minute past the hour, 0 to 59. Defaults to 0."
                    },
                    "days_from_now": {
                      "type": "integer",
                      "description": "0 for today, 1 for tomorrow, and so on. Defaults to 0."
                    },
                    "duration_minutes": {
                      "type": "integer",
                      "description": "How long it lasts in minutes. Defaults to 60."
                    },
                    "location": {
                      "type": "string",
                      "description": "Where it happens, if the user said."
                    }
                  },
                  "required": ["title", "hour"]
                }
              }
            ]
        """.trimIndent()
    }
}

/** What an action did, and what to say about it. */
data class ActionResult(val succeeded: Boolean, val message: String)
