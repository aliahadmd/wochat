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
3. **Add the no-permission set:** alarm, timer, dial, calendar, directions.
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
