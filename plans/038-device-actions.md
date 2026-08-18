# Plan 038: Let the assistant act on the device

## Status

Step 1 **done and verified on the device**, 2026-08-18. Researched the same day. The headline finding is that **nothing needs
to be added to llama.cpp for this** — the tool-calling machinery is already
vendored and completely unused.

## The premise this started from was wrong, usefully

The question was whether our llama.cpp is months behind and missing features.
Measured, it is not:

| | |
|---|---|
| `chat-peg-parser.cpp`, `COMMON_CHAT_FORMAT_PEG_GEMMA4` | present — landed upstream 2026-08-13 |
| `common/subproc.*`, `common/trie.*` | absent — added upstream late July |
| `common/json-partial.*`, `regex-partial.*` | present — **removed** upstream 2026-06-24 |
| `ggml/src/ggml-et` backend | absent |

Having a file upstream deleted in June while lacking files it added in July means
this is not a clean snapshot of any single commit. The recorded revision,
`ad857250…-aichat-context-v1`, is not an upstream SHA, and
`examples/llama.android/lib/src/main/cpp/ai_chat.cpp` is ours. So the tree is a
recent upstream base carrying local adjustments, days behind on the parts we use
rather than months.

The four missing files are server subprocess tooling and a trie, neither of which
this app links. **There is no upgrade worth doing for its own sake**, and a naive
`git pull` over a patched tree would be a bad trade.

**What we are actually missing is not new llama.cpp. It is the llama.cpp we
already have.** Three capabilities are vendored and called zero times:

- `common_chat_tool` / `inputs.tools` / `tool_choice` / `parallel_tool_calls`
- `COMMON_CHAT_FORMAT_PEG_GEMMA4` — a parser written for Gemma 4's tool syntax
- `grammar`, `grammar_lazy`, `grammar_triggers`, `json_schema` — constrained
  decoding, so a tool call is *generated* well-formed rather than repaired after

`chat_inputs()` in `aichat_jni.cpp` sets messages, thinking and reasoning format,
and stops there.

## What the scenario looks like

    You:  "Set an alarm for 3 in the afternoon."
    →     Gemma 4 emits a tool call, grammar-constrained:
              set_alarm{hour: 15, minutes: 0, label: "Alarm"}
    →     PEG_GEMMA4 parses it into msg.tool_calls
    →     Kotlin fires AlarmClock.ACTION_SET_ALARM
    →     result returned to the model as a tool message
    You hear: "Done — alarm set for 3 PM."

The last hop matters: the model should confirm in its own words, so a call that
half-worked is described rather than silently assumed.

## The Android side is all standard, no vendor SDK

Chosen deliberately after the Hyper Island work — everything here is platform
API, so it behaves the same on a Pixel or a Galaxy.

| Action | API | Permission |
|---|---|---|
| Set alarm | `AlarmClock.ACTION_SET_ALARM` | none |
| Set timer | `AlarmClock.ACTION_SET_TIMER` | none |
| Flashlight | `CameraManager.setTorchMode()` | none |
| Dial a number | `Intent.ACTION_DIAL` | none |
| Place a call | `Intent.ACTION_CALL` | `CALL_PHONE` |
| Resolve "call Mum" | `ContactsContract` | `READ_CONTACTS` |
| Calendar event | `CalendarContract` insert intent | none (opens composer) |
| Send a message | `ACTION_SENDTO` `smsto:` | none (opens composer) |
| Directions | `ACTION_VIEW` `geo:` | none |

Most need no permission at all, because the intent opens the relevant app
pre-filled rather than acting silently.

## Two rules this should be built on

**Execute local and reversible; confirm outward-facing.** Flashlight, timer and
alarm are the user's own device and trivially undone — do them. A phone call, an
SMS or a calendar invite reaches other people or is awkward to retract — show what
was understood and let the user press the button. A 4B model misparsing "call
Dad" is a question of when, not if.

**Actions are a toggle, not a default.** Tool schemas cost prompt tokens, and
prefill is compute-bound at ~56 ms/token cold on this device (plan 037). Six tools
is roughly 250-400 tokens. The saving grace is that tools live in the stable part
of the prompt, so they are decoded **once per conversation** and then ride the KV
cache — the persistence work in 037 is what makes this affordable at all. Still,
someone who never uses actions should not pay for them: this belongs next to the
existing *Thinking* and *Memory* chips.

Note it invalidates every saved KV sequence once, the same as any system-prompt
change. Expected, one-off.

## Why not FunctionGemma

Google's Mobile Actions demo uses **FunctionGemma-270M**, purpose-built for this
and available as GGUF (~200 MB quantised). Tempting, and rejected for now:

- Its own model card says it "is intended to be fine-tuned for your specific
  function-calling task" — raw quality is not the selling point.
- It is a second model competing for RAM in an app that already spent a day
  fighting memory pressure and a 4.8 GB resident model.
- Gemma 4 tool calling costs nothing extra: the model is already loaded and the
  parser is already vendored.

Start with Gemma 4. Revisit FunctionGemma only if measured tool accuracy is poor,
and measure it before believing either way.

## Steps

1. ~~**Prove the loop with one tool.**~~ **Done.** "Turn on the flashlight" →
   `Tools available to the model: 1` → and Android's own camera service, not our
   logging, reporting `Torch for camera id 0 turned on for client PID 14541`.
   "Now turn it off please" turned it off again, so the model reads the argument
   rather than pattern-matching the word *flashlight*. The reply reads
   "Flashlight on."

   What was needed natively: `inputs.tools` and `tool_choice` in `chat_inputs()`,
   `parse_tool_calls` flipped from the hardcoded `false`, and the template's
   tool-call grammar handed to the sampler as `COMMON_GRAMMAR_TYPE_TOOL_CALLS`
   with its lazy triggers — that last one is what makes a call valid by
   construction rather than parsed hopefully. Two JNI calls carry it: `setTools`
   in, `lastToolCalls` out.

   Gated by an **Actions** chip beside Thinking and Memory, off by default.
2. **Measure the prompt cost.** Tokens added by the schema, and first-turn prefill
   with tools on versus off. Numbers, not estimates.
3. ~~**Add the no-permission set:**~~ **alarm and timer done.** Verified in the
   clock app, not just our log: `set_alarm({"hour":7})` produced a real 07:00 AM
   alarm ("Alarm in 8 hours 32 minutes"), and `set_timer({"seconds":600})` a real
   10-minute timer. The model converts "7 in the morning" and "10 minute" itself.
   Dial and calendar are done too, both verified: `dial_number({"number":
   "07700900123"})` opened the dialer with the number filled in — focus moved to
   `com.android.contacts/TwelveKeyDialer` and **no call was placed** — and "add a
   calendar event tomorrow at 2pm called Dentist" produced
   `create_event({"days_from_now":1,"hour":14,"title":"Dentist"})`. Directions
   remain.

   Those two are the first of the confirm-first set, and the confirmation is the
   platform's rather than ours: `ACTION_DIAL` fills the dialer but leaves the call
   button to a human, and the calendar composer leaves saving to a human. That is
   a better guarantee than a dialog of our own, because it cannot be bypassed by
   a model that phrases things persuasively. `ACTION_CALL` — which would place the
   call outright and needs CALL_PHONE — is deliberately not implemented.

   The calendar tool takes an hour and a number of days ahead rather than a
   timestamp. Epoch arithmetic is exactly what a 4B model gets quietly wrong, and
   the device knows what "tomorrow at 2" means far better than the model does.

   **Two things were wrong in the research above, both found by measuring.** The
   table said alarms need no permission; `ACTION_SET_ALARM` in fact throws
   `SecurityException: requires com.android.alarm.permission.SET_ALARM`. It is a
   normal permission, so declaring it is enough and no prompt appears — but it is
   not "none". And `resolveActivity()` returned null for a clock that was plainly
   installed: API 30+ package visibility hides it unless the manifest declares
   `<queries>` for those intent actions. Both cost a build each and neither was
   guessable from reading.

### Phrasing was never the problem; guessing was — 2026-08-18

Raised as two concerns: that an action needs the same words each time, and that an
ambiguous request is guessed at rather than questioned. The first turned out not to
be true, the second was.

**Nothing matches text.** There is no `contains`, `startsWith` or regex over
anything the user says anywhere in the action path. The only `when` is on the tool
*name the model chose*, so selection is semantic by construction. Measured, in a
clean conversation:

| said | called |
|---|---|
| "It is dark in here" | `toggle_flashlight({"on":true})` |
| "kill the torch" | `toggle_flashlight({"on":false})` |
| "Now turn it off please" | `toggle_flashlight({"on":false})` |

None of those share a word with `toggle_flashlight`, and the first names no light
at all. The inference is the model's.

**Where it does stop** is unmeasured rather than known: "Thanks, I can see fine
now" was tried and the app restarted under memory pressure mid-test, so that run
proves nothing in either direction. Somewhere between "kill the torch" and pure
implication there is a boundary, and finding it needs a quieter device. Arguably
the boundary belongs there anyway — inferring *turn off my flashlight* from
*thanks, I can see fine* is a leap that would eventually act when nobody asked.

**Guessing was the real gap, and it is fixed in the schemas.** The tool
descriptions now say to ask rather than assume: an hour with no morning or
evening, an unclear day for an event, a person's name where a number belongs.
Verified — "Please set an alarm at 3 o clock" now answers **"Which 3 o'clock would
you like the alarm set for, 3 AM or 3 PM?"** and calls nothing.

That this works at all is worth noting: `tool_choice` is AUTO, so the model is free
to answer instead of calling, and a question is just an ordinary reply. Forcing a
call with `REQUIRED` would have made clarification impossible.

### The model will claim it did things it cannot do — 2026-08-18

Worth its own heading, because it wasted more time than either bug and is a
property of the design rather than a defect to be fixed once.

With **Actions off**, "please create an alarm at 3 o'clock in the evening"
answered **"Alarm set for 3:00 PM."** No tool existed, no call was made, nothing
happened. The sentence was simply generated, and it is indistinguishable from the
real confirmation — which is the same sentence, because the real one is phrased
naturally too.

It then got worse in a way worth understanding: those invented confirmations sat
in the conversation as context, and the next turn — now *with* tools available —
**imitated them instead of calling the tool**. Three hallucinated examples had
taught the model that answering an alarm request means saying "Alarm set for". A
clean conversation called the tool correctly on the first try.

Consequences for the rest of this plan:

- **Every action logs**, success or failure. Without `DeviceActions: Action
  set_alarm(...) -> ok` there is no way from outside the process to tell a real
  action from a sentence about one, and the reply text cannot be trusted as
  evidence. This is what turned an hour of confusion into a two-minute diagnosis.
- **Verify in the target app, never in the chat.** The clock app is the authority
  on whether an alarm exists.
- Step 4's confirmations should describe what the *tool returned*, not what the
  model says it did.
- Open question worth an experiment: when actions are off, should the preamble
  tell the model it cannot act? Untested, and it costs prompt tokens on turns that
  will never use them.
4. **Add the confirm-first set:** place a call, send a message.
5. **Voice.** This is where it earns its keep — "turn on the torch" mid-call, hands
   busy. Reuses the existing call pipeline entirely.

## Verify

- A tool call is *generated* valid under grammar constraint, never repaired.
- Actions off = prompt byte-identical to today, and the KV cache still reuses.
- A misparse is visible: the model says what it did, and outward-facing actions
  are confirmed before they happen.

## STOP conditions

- If tool schemas measurably degrade ordinary conversation, the toggle default is
  off and it stays off.
- If accuracy is poor enough that confirmation is needed for *everything*, this is
  worse than a button and should be reconsidered rather than shipped.
