# Plan 026: Let the user compose while the model is generating

> **Executor instructions**: Follow this plan step by step. Run every
> verification command and confirm the expected result before moving to the
> next step. If anything in the "STOP conditions" section occurs, stop and
> report — do not improvise. When done, update the status row for this plan
> in `plans/README.md` — unless a reviewer dispatched you and told you they
> maintain the index.
>
> **Drift check (run first)**:
> `git diff --stat 619d7de..HEAD -- app/src/main/java/com/aliahad/aichat/ui/AiChatApp.kt`

## Status

- **Priority**: P2
- **Effort**: S
- **Risk**: MED (the disabled state is currently doing real work — it
  prevents a second send landing mid-turn; removing it naively creates a
  concurrency bug in `ChatTurnRunner`)
- **Depends on**: 019
- **Category**: UX
- **Planned at**: commit `619d7de`, 2026-08-15

## Why this matters

The text field is disabled for the entire duration of a response:

```kotlin
// AiChatApp.kt:1197-1212
OutlinedTextField(
    value = input,
    onValueChange = onInputChange,
    enabled = enabled && !sending,      // <-- locked while generating
    ...
)
```

On-device generation is *slow* — that is the nature of local inference on a
phone. Locking the composer for its full duration means the user sits and
watches. Every mainstream chat client lets you write your next message while
the current answer streams.

There is a second, sharper problem: disabling a **focused** `TextField`
dismisses the keyboard and drops focus. If the user is mid-word when they hit
send, the keyboard collapses under them.

Note what the flag is legitimately protecting: sending twice into an active
turn. That protection belongs on the *send action*, not on the *text field*.

## Current state

- `AiChatApp.kt:1200` — `enabled = enabled && !sending` on the text field.
- `AiChatApp.kt:1192` — the attach button uses the same `!sending` guard.
- `AiChatApp.kt:1214-1231` — the send button already switches to Stop while
  sending, so the send path is *already* mutually exclusive by design:

```kotlin
IconButton(
    onClick = if (sending) onStop else onSend,
    enabled = sending || ( enabled && (input.isNotBlank() || ...) ),
```

  While `sending` is true this button is a Stop button — it cannot trigger a
  second send. That is the key fact making this plan small.
- `AiChatApp.kt:1156-1158` — the Thinking chip is also disabled while sending
  (`enabled = !sending`), which is correct: changing thinking mode mid-turn
  would be incoherent.
- `AiChatApp.kt:1210-1211` — `imeAction = ImeAction.Default` with an empty
  `KeyboardActions()`.

## Commands you will need

| Purpose | Command | Expected on success |
|---------|---------|---------------------|
| Unit tests | `./gradlew testDebugUnitTest` | exit 0 |
| Instrumented compile | `./gradlew compileDebugAndroidTestKotlin` | exit 0 |
| Chat UI tests | `./gradlew connectedDebugAndroidTest --tests '*ChatUi*'` | exit 0 |

## Scope

**In scope**:
- `app/src/main/java/com/aliahad/aichat/ui/AiChatApp.kt`, the composer region
  (roughly lines 1189-1232)

**Out of scope**:
- Queuing a message to auto-send when the turn finishes. That is a product
  feature with its own design questions — this plan only lets the user *type*.
- `ChatTurnRunner` and turn lifecycle.
- The Thinking chip's disabled state (correct as-is).
- Attachment staging during generation — leave `!sending` on the attach
  button unless Step 2 shows it is safe, and say which you chose.

## Steps

### Step 1: Confirm the send path is genuinely exclusive

Before removing the guard, verify from the code that no path can start a
second turn while one is active — including the send button, any keyboard
action, and `onContinue`. Write down what you confirmed.

If a second send *is* reachable, add the guard to the send action itself
first, then proceed.

**Verify**: written confirmation in the status row.

### Step 2: Enable the field during generation

Change the text field to `enabled = enabled` (dropping `!sending`). The
placeholder should still reflect reality — it currently says "Message your
local model" / "Set up a model first"; consider what it should say mid-turn,
but do not make it noisy.

Decide and record whether attachments may also be staged during generation.
Safer default: leave the attach button guarded, since staging kicks off
background processing that interacts with the draft.

**Verify**: `./gradlew assembleDebug` → exit 0.

### Step 3: Keep focus and keyboard stable across send

Sending must not tear down focus. Verify that after pressing send, the
keyboard stays up and the cursor stays in the field, so the user can keep
typing. This is the part users actually feel.

**Verify**: manual check on a device, plus the Step 5 test.

### Step 4: Give the field a real IME action

`ImeAction.Default` with empty `KeyboardActions` means the keyboard offers
nothing useful. With `maxLines = 6`, newline is a legitimate action, so do
**not** map Enter to send. Prefer an explicit newline-capable configuration
and leave sending to the button. State your choice.

**Verify**: manual check that Enter inserts a newline and the keyboard shows
a sensible action.

### Step 5: Test it

Add an instrumented test: start a generation, type into the composer, assert
the text lands and the field is enabled.

**Verify**: `./gradlew connectedDebugAndroidTest --tests '*ChatUi*'` → exit 0.

## Test plan

- New instrumented test for typing during generation.
- `ChatUiInstrumentedTest` and `ChatViewModelInstrumentedTest` green.
- Manual: send a message, keep typing while the answer streams, confirm the
  keyboard never collapses and no second turn starts.

## Done criteria

- [ ] Send-path exclusivity confirmed in writing before the guard was removed
- [ ] Text field is usable during generation
- [ ] Focus and keyboard survive pressing send
- [ ] Attach-during-generation decision made and recorded
- [ ] IME action configured deliberately; Enter is not mapped to send
- [ ] New instrumented test passes
- [ ] `./gradlew testDebugUnitTest` and `compileDebugAndroidTestKotlin` exit 0
- [ ] `plans/README.md` status row updated

## STOP conditions

Stop and report back (do not improvise) if:
- Step 1 finds a reachable second-send path that needs `ChatTurnRunner`
  changes to close.
- Plan 019 is not DONE. With the draft still held in a bare `remember`,
  enabling the field during generation widens the existing data-loss window.
- Typing during generation causes visible stutter in the streaming message —
  that points at plans 024/025 and should be measured, not worked around
  here.

## Maintenance notes

- The `sending` flag should guard *actions that mutate the turn*, never plain
  text entry. Reviewers should push back on any future `!sending` added to an
  input control.
- Reviewers: the acceptance question is "can I write my next question while
  the model is still answering the last one?"
