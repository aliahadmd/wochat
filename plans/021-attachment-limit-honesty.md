# Plan 021: Stop silently discarding attachments over the limit

> **Executor instructions**: Follow this plan step by step. Run every
> verification command and confirm the expected result before moving to the
> next step. If anything in the "STOP conditions" section occurs, stop and
> report — do not improvise. When done, update the status row for this plan
> in `plans/README.md` — unless a reviewer dispatched you and told you they
> maintain the index.
>
> **Drift check (run first)**:
> `git diff --stat 619d7de..HEAD -- app/src/main/java/com/aliahad/aichat/MainActivity.kt app/src/main/java/com/aliahad/aichat/ui/AiChatApp.kt`

## Status

- **Priority**: P2
- **Effort**: S
- **Risk**: LOW
- **Depends on**: none
- **Category**: bug / UX
- **Planned at**: commit `619d7de`, 2026-08-15

## Why this matters

The app enforces a 20-attachment limit in two places, and neither one tells
the user anything.

1. **Silent truncation.** `MainActivity.kt:100` — if the user picks 30 files
   in the system picker, ten of them are dropped on the floor with no message:

```kotlin
val fileLauncher = rememberLauncherForActivityResult(
    ActivityResultContracts.OpenMultipleDocuments(),
) { uris ->
    uris.take(20).forEach(chatViewModel::stageAttachment)
}
```

2. **A dead button with no explanation.** `AiChatApp.kt:1192` — the attach
   button simply stops responding at 20 with no label, tooltip, or message
   explaining why:

```kotlin
enabled = enabled && !sending && attachments.size < 20,
```

The user's mental model breaks in both cases: they asked for something, the
app appeared to accept it, and the result was different from what they asked
for. The photo picker path (`PickMultipleVisualMedia(20)`) is fine — the
system UI enforces that limit visibly.

Note also that `take(20)` counts the *newly picked* files, not the total, so
picking 15 when 15 are already staged stages 15 more and blows past the cap
that line 1192 is trying to enforce.

## Current state

- `app/src/main/java/com/aliahad/aichat/MainActivity.kt:97-106`:

```kotlin
val fileLauncher = rememberLauncherForActivityResult(
    ActivityResultContracts.OpenMultipleDocuments(),
) { uris ->
    uris.take(20).forEach(chatViewModel::stageAttachment)
}
val photoLauncher = rememberLauncherForActivityResult(
    ActivityResultContracts.PickMultipleVisualMedia(20),
) { uris ->
    uris.forEach(chatViewModel::stageAttachment)
}
```

- `app/src/main/java/com/aliahad/aichat/ui/AiChatApp.kt:1190-1196` — the
  attach `IconButton` with the `< 20` guard.
- The literal `20` appears in at least three places with no shared constant.
- `UiMessageManager` (`ui/viewmodel/UiCoordinators.kt`) already exists as the
  "one activity-scoped destination for actionable errors", surfaced through
  the snackbar host wired at `AiChatApp.kt:230-235`. Use it.

## Commands you will need

| Purpose | Command | Expected on success |
|---------|---------|---------------------|
| Unit tests | `./gradlew testDebugUnitTest` | exit 0 |
| Instrumented compile | `./gradlew compileDebugAndroidTestKotlin` | exit 0 |
| Debug APK | `./gradlew assembleDebug` | exit 0 |

## Scope

**In scope**:
- `app/src/main/java/com/aliahad/aichat/MainActivity.kt` (picker callbacks)
- `app/src/main/java/com/aliahad/aichat/ui/AiChatApp.kt` (attach button
  affordance)
- A shared constant for the limit, placed wherever the codebase keeps such
  values (check `core/AppModels.kt` and `model/ModelConstants.kt` for the
  established home before creating a new file)

**Out of scope**:
- Changing the limit itself. 20 is the current product decision; this plan
  makes it *honest*, not different.
- Attachment processing, staging, or the `draftKey` lifecycle.

## Steps

### Step 1: Introduce one constant

Replace the three hardcoded `20`s with a single named constant. Do not create
a new file if an existing constants home fits.

**Verify**: `grep -rn "20" app/src/main/java/com/aliahad/aichat/MainActivity.kt`
→ no bare attachment-limit literals remain.

### Step 2: Count against what is already staged

The file picker callback must consider attachments already in the draft, not
just the new selection. Compute remaining capacity from the current draft
count and take only that many.

**Verify**: reasoning plus the Step 4 test.

### Step 3: Tell the user when something was dropped

When the selection exceeds remaining capacity, report it through
`UiMessageManager` so it reaches the existing snackbar — naming the real
numbers ("Added 5 of 12 files — 20 attachment limit reached") rather than a
generic failure.

Separately, give the attach button an explanation when it is disabled for
capacity reasons specifically. A disabled control with no reason is the
defect; a short supporting line near the composer, or a message on tap, both
qualify. Do not add a tooltip that only appears on long-press as the sole
affordance.

**Verify**: `./gradlew assembleDebug` → exit 0.

### Step 4: Test the truncation path

Add a JVM unit test for the capacity calculation (given N staged and a
selection of M, how many are accepted and what does the message say). This is
pure logic — extract it to a small testable function rather than leaving it
inline in a composable lambda.

**Verify**: `./gradlew testDebugUnitTest` → exit 0, new test present.

## Test plan

- New unit test for capacity math, covering: under limit, exactly at limit,
  over limit, and already-full.
- `AttachmentBatchLoadTest` stays green.
- Manual: stage 15 files, pick 10 more, confirm 5 are added and the message
  says so.

## Done criteria

- [ ] One shared constant for the attachment limit
- [ ] Picker counts against already-staged attachments
- [ ] Over-limit selections produce a specific, numeric user-facing message
- [ ] Disabled attach button explains itself when capacity is the reason
- [ ] Capacity logic covered by a JVM unit test
- [ ] `./gradlew testDebugUnitTest`, `compileDebugAndroidTestKotlin`,
      `assembleDebug` all exit 0
- [ ] `plans/README.md` status row updated

## STOP conditions

Stop and report back if:
- Staging is asynchronous in a way that makes "current draft count" racy at
  callback time — report the race rather than papering over it with a guess.
- The photo picker path turns out to also over-stage (it passes 20 to the
  system picker, but confirm it cannot exceed the total when files are
  already staged).

## Maintenance notes

- If the limit ever changes, it should now be one edit. Reviewers should
  reject any new bare `20` in attachment code.
- Reviewers: the acceptance question is "can the app discard a user's file
  without telling them?"
