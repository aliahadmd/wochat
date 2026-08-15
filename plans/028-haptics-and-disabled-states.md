# Plan 028: Haptics and honest disabled states

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

- **Priority**: P3
- **Effort**: S
- **Risk**: LOW
- **Depends on**: none
- **Category**: UX polish
- **Planned at**: commit `619d7de`, 2026-08-15

## Why this matters

**The send button lies about being disabled.** It paints its background
unconditionally, so a button the user *cannot press* is pixel-identical to
one they can:

```kotlin
// AiChatApp.kt:1214-1231
IconButton(
    onClick = if (sending) onStop else onSend,
    enabled = sending || (enabled && (input.isNotBlank() || ...)),
    modifier = Modifier
        .size(50.dp)
        .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(50)),
) {
    Icon(..., tint = MaterialTheme.colorScheme.onPrimary)
}
```

`IconButton` fades its *content* when disabled, but the `.background()`
modifier is applied by the caller and ignores enabled state entirely, and the
`tint` is hardcoded to `onPrimary`. The result: a solid primary-colored
circle that does nothing when tapped. The user's model is "the app is broken",
not "I need to type something first".

**The app has no haptics at all.** `performHapticFeedback` appears zero times
across the entire codebase. Sending a message, stopping generation, a
generation completing, long-pressing a conversation to delete — none of them
produce any tactile response. On a phone, that is a large part of what makes
software feel finished.

## Current state

- `grep -rn "performHapticFeedback" app/src/main/java` → **0 matches**.
- `AiChatApp.kt:1222-1224` — the send button `.background()` quoted above.
- `AiChatApp.kt:862-868` — the jump-to-latest button uses the same pattern
  (`.background(surfaceContainerHigh, RoundedCornerShape(50))`), though it is
  never disabled, so it is cosmetic consistency rather than a bug.
- Material 3 provides `FilledIconButton` with proper
  `disabledContainerColor` / `disabledContentColor` — the intended API here.
- Interactions that warrant haptics today: send, stop, generation complete,
  conversation delete, skill chip toggle, attachment removal.

## Commands you will need

| Purpose | Command | Expected on success |
|---------|---------|---------------------|
| Unit tests | `./gradlew testDebugUnitTest` | exit 0 |
| Instrumented compile | `./gradlew compileDebugAndroidTestKotlin` | exit 0 |
| Debug APK | `./gradlew assembleDebug` | exit 0 |

## Scope

**In scope**:
- `app/src/main/java/com/aliahad/aichat/ui/AiChatApp.kt`

**Out of scope**:
- A user-facing haptics setting. Respect the system setting (which
  `performHapticFeedback` does automatically) and stop there; an in-app
  toggle is a product decision, not a polish task.
- Custom vibration patterns via `Vibrator`. Use the standard Compose haptic
  constants only.
- Sound effects.

## Steps

### Step 1: Fix the send button's disabled state

Replace the hand-rolled `IconButton` + `.background()` with `FilledIconButton`
and explicit `IconButtonDefaults.filledIconButtonColors(...)`, including real
disabled colors. Drop the hardcoded `onPrimary` tint and let the button's
color scheme drive the content color.

Preserve the existing send/stop swap and the full `enabled` expression
verbatim — this is a presentation fix, not a behavior change.

**Verify**: `grep -n "\.background(MaterialTheme.colorScheme.primary" app/src/main/java/com/aliahad/aichat/ui/AiChatApp.kt`
→ no matches.

### Step 2: Audit for the same pattern elsewhere

`grep -n "\.background(" app/src/main/java/com/aliahad/aichat/ui/AiChatApp.kt`
and check each hit on an interactive element for the same
disabled-state blindness. Fix any that can actually be disabled; leave purely
decorative backgrounds alone.

**Verify**: written list of hits and what you did with each.

### Step 3: Add haptics to the interactions that earn them

Using `LocalHapticFeedback`, add feedback to:

- **Send** and **Stop** — `HapticFeedbackType.ContextClick` or equivalent.
- **Generation complete** — a distinct, gentle confirmation, fired once when
  a message reaches a terminal status. This is the highest-value one: the
  user can look away during a slow local generation and feel when it lands.
- **Conversation delete** and other destructive confirmations — a firmer
  `LongPress`-class effect.

Keep it sparse. Haptics on every chip toggle becomes noise; the round-2 goal
is "feels finished", not "buzzes constantly".

**Verify**: `./gradlew assembleDebug` → exit 0, and manual check on a device
(haptics cannot be verified in an emulator).

### Step 4: Fire completion haptics exactly once

The generation-complete haptic must not re-fire on recomposition or when
scrolling an old message back into view. Key it to the message reaching a
terminal status, not to the status being terminal.

**Verify**: manual — scroll an old completed message in and out of view and
confirm silence.

## Test plan

- `./gradlew assembleDebug` and `compileDebugAndroidTestKotlin` exit 0.
- Existing `ChatUiInstrumentedTest` green (the send button's test tags and
  content descriptions must survive the `FilledIconButton` swap).
- Manual on a physical device: disabled send button now looks disabled;
  haptics fire on send, stop, and completion, and only once each.

## Done criteria

- [ ] Send button uses `FilledIconButton` with real disabled colors
- [ ] No interactive element paints an enabled-looking background while disabled
- [ ] `.background(` audit completed and recorded
- [ ] Haptics on send, stop, generation complete, and destructive actions
- [ ] Completion haptic fires exactly once per generation
- [ ] Existing instrumented tests still pass
- [ ] `plans/README.md` status row updated

## STOP conditions

Stop and report back if:
- Swapping to `FilledIconButton` changes the button's size or alignment
  enough to break the composer layout — report rather than compensating with
  magic padding numbers.
- Existing instrumented tests locate the send button by a structure that the
  swap breaks. Fix the test's locator to use the existing content description
  (`"Send"` / `"Stop generation"`), and say so.
- No physical device is available. Haptics cannot be verified on an emulator;
  land Steps 1-2 and mark Steps 3-4 BLOCKED rather than claiming them done.

## Maintenance notes

- Rule for review: never pass `.background()` to an `IconButton` that has an
  `enabled` expression. Use the `Filled*` variants.
- Reviewers: the acceptance question is "can I tell, without tapping, whether
  the send button will do anything?"
