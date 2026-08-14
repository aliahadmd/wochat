# Plan 001: Re-restore the native session when the planned prompt changes between turns

> **Executor instructions**: Follow this plan step by step. Run every
> verification command and confirm the expected result before moving to the
> next step. If anything in the "STOP conditions" section occurs, stop and
> report — do not improvise. When done, update the status row for this plan
> in `plans/README.md` — unless a reviewer dispatched you and told you they
> maintain the index.
>
> **Drift check (run first)**: `git diff --stat 2dff7e6..HEAD -- app/src/main/java/com/aliahad/aichat/inference/NativeInferenceEngine.kt app/src/main/java/com/aliahad/aichat/ui/viewmodel/ChatTurnRunner.kt`
> If any in-scope file changed since this plan was written, compare the
> "Current state" excerpts against the live code before proceeding; on a
> mismatch, treat it as a STOP condition.

## Status

- **Priority**: P1
- **Effort**: M
- **Risk**: MED (touching KV-cache restore cadence; mitigation below keeps the fast path)
- **Depends on**: none
- **Category**: bug
- **Planned at**: commit `2dff7e6`, 2026-08-15

## Why this matters

Every chat turn plans a fresh system prompt (skills + memories + conversation
summary assembled by `PromptContextPlanner`) and passes it to
`restoreSession`. But `restoreSession` returns early when the conversation id
is unchanged, so from turn 2 onward — on a still-loaded model — the native
prompt/KV cache permanently reflects turn 1. Newly selected skills, newly
retrieved memories, edited system prompts, updated summaries, and
thinking-mode toggles silently never reach the model. This breaks the app's
headline features (Office Memory, skills) with no error anywhere.

This plan also fixes an adjacent one-line bug in the same code path: an
inverted projector-budget comparison that forces a needless projector reload
right after restore.

## Current state

- `app/src/main/java/com/aliahad/aichat/inference/NativeInferenceEngine.kt` —
  the CPU/Vulkan JNI engine. `restoreSession` (lines 148–193) builds the
  native prompt from `settings` and replays history, then records
  `activeConversationId = conversationId` (line 188). The early return at
  line 154:

```kotlin
// NativeInferenceEngine.kt:148-158
override suspend fun restoreSession(
    conversationId: String,
    history: List<ChatTurn>,
    settings: GenerationSettings,
) = withContext(dispatcher) {
    check(loadedModelPath != null) { "Load a model first" }
    if (activeConversationId == conversationId) return@withContext
    // The native session is cleared below; a failed restore must not leave the
    // previous conversation marked active against the new conversation's KV cache.
    activeConversationId = null
    _state.value = InferenceState.PreparingHistory
```

  The native prompt is derived from `settings.systemPrompt` and
  `settings.thinkingEnabled` (lines 161–167), and history is appended
  message-by-message (lines 171–183). Nothing else feeds the restore.
  `generate` requires `activeConversationId == turn.conversationId`
  (line 201). `unloadProjector()` also resets `activeConversationId = null`
  (line 145), and the error path of `generate` resets it (line 296).

- `app/src/main/java/com/aliahad/aichat/ui/viewmodel/ChatTurnRunner.kt` —
  turn orchestration. Every send re-plans and calls restore (lines 243–247):

```kotlin
// ChatTurnRunner.kt:243-247
val plannedSettings = settings.copy(systemPrompt = contextPlan.systemPrompt)
inferenceEngine.restoreSession(request.conversationId, plannedTurns, plannedSettings)
if (visualCount > 0 && historyImageBudget > visualBudget) {
    residencyController.ensureLoaded(mediaRequirement, visualBudget)
}
```

  Two issues here: (a) `plannedSettings`/`plannedTurns` differ per turn but
  restore skips on the same conversation id; (b) the post-restore
  `ensureLoaded` passes `visualBudget` (the *smaller* value) precisely when
  `historyImageBudget > visualBudget`, unlike the sibling call at lines
  208–213 which correctly passes `maxOf(visualBudget, historyImageBudget)`.
  `ModelResidencyController.ensureLoaded` treats a changed
  `imageTokenBudget` as a projector reload (see
  `ModelResidencyController.kt:320-328`), so this triggers an expensive
  reload right after the KV cache was rebuilt.

- `continueResponse` (ChatTurnRunner.kt:370–524) also calls
  `restoreSession` (around lines 435–440) with the same conversation id and
  is subject to the same skip.

Conventions to match: private mutable state on the engine is plain vars
guarded by `dispatcher` (`withContext(dispatcher)`); engine state is exposed
via `_state`/`_metrics` `MutableStateFlow`s. Follow the existing
`check(...)`/error-state style.

## Commands you will need

| Purpose | Command | Expected on success |
|---------|---------|---------------------|
| Unit tests | `./gradlew testDebugUnitTest` | exit 0, all pass |
| Detekt | `./gradlew detekt` | exit 0 |
| Debug build | `./gradlew assembleDebug` | exit 0 (native build may take a while) |
| AndroidTest compile | `./gradlew compileDebugAndroidTestKotlin` | exit 0 |

## Scope

**In scope** (the only files you should modify):
- `app/src/main/java/com/aliahad/aichat/inference/NativeInferenceEngine.kt`
- `app/src/main/java/com/aliahad/aichat/ui/viewmodel/ChatTurnRunner.kt`
- `app/src/test/java/com/aliahad/aichat/inference/` (new or existing test file)

**Out of scope** (do NOT touch):
- `residency/ModelResidencyController.kt` — its reload-on-budget-change
  behavior is correct; the fix belongs to the caller.
- `PromptContextPlanner.kt`, `ChatViewModel.kt`, the Vulkan remote stack.
- Any native code in `app/src/main/cpp/`.

## Steps

### Step 1: Add a restore fingerprint to `NativeInferenceEngine`

Add a private `var activeRestoreFingerprint: String? = null` next to
`activeConversationId`. Replace the skip condition at line 154 so that
restore is skipped only when BOTH the conversation id matches AND the
fingerprint of the actual restore inputs matches:

```kotlin
val fingerprint = conversationId + '\u0000' +
    settings.thinkingEnabled.toString() + '\u0000' +
    settings.systemPrompt.hashCode() + '\u0000' +
    history.size + '\u0000' +
    history.lastOrNull()?.message?.id.hashCode()
if (activeConversationId == conversationId && activeRestoreFingerprint == fingerprint) {
    return@withContext
}
```

Set `activeRestoreFingerprint = fingerprint` at line 188 (next to
`activeConversationId = conversationId`), and clear it everywhere
`activeConversationId` is cleared or reset (lines 113, 145, 157→ set null,
296; also `unload()` around line 315–320). The fingerprint must include
every input `restoreSession` feeds to native: systemPrompt,
thinkingEnabled, and history shape. `history.size` plus the last message id
is a pragmatic proxy for history content; if the last history message
content can change without an id change (it cannot — messages are
immutable rows and `ChatTurnRunner` re-reads them per turn), this is safe.

**Verify**: `./gradlew compileDebugKotlin` → exit 0.

### Step 2: Fix the inverted projector-budget argument in `ChatTurnRunner`

Change line 246 from `residencyController.ensureLoaded(mediaRequirement, visualBudget)`
to `residencyController.ensureLoaded(mediaRequirement, maxOf(visualBudget, historyImageBudget))`,
mirroring lines 208–213. (Consider whether the call is needed at all — the
pre-plan `ensureLoaded` at lines 171–174 already used
`maxOf(initialVisualBudget, allHistoryImageBudget)` — but keep the call for
safety; only fix the argument.)

**Verify**: `./gradlew compileDebugKotlin` → exit 0.

### Step 3: Regression test

In `app/src/test/java/com/aliahad/aichat/inference/` there are existing
fake-based tests (`RecoveringInferenceEngineTest.kt`,
`VisionBudgetPlannerTest.kt`) — model after their style. Write a JVM unit
test around a fake `InferenceEngine` is NOT sufficient here because the bug
lives in `NativeInferenceEngine` (which needs JNI). Instead:
- If `NativeInferenceEngine` cannot be instantiated on the JVM, extract the
  fingerprint computation into a small internal pure function
  `internal fun restoreFingerprint(conversationId, settings, history): String`
  in the same file (or a small companion), and unit-test THAT plus the skip
  rule expressed as a pure predicate `shouldSkipRestore(activeId,
  activeFingerprint, ...)`.
- Test cases: same inputs → skip; changed systemPrompt → no skip; changed
  thinkingEnabled → no skip; changed history size → no skip; changed
  conversation id → no skip.

**Verify**: `./gradlew testDebugUnitTest` → all pass including the new tests.

### Step 4: Full gate

**Verify**: `./gradlew detekt assembleDebug` → exit 0.

## Test plan

- New test file `app/src/test/java/com/aliahad/aichat/inference/RestoreSessionFingerprintTest.kt`
  covering the five skip/no-skip cases above.
- Pattern to follow: plain JUnit4 + kotlinx-coroutines-test as seen in
  `app/src/test/java/com/aliahad/aichat/inference/RecoveringInferenceEngineTest.kt`.
- Manual on-device sanity (optional, if a device is attached): send turn 1,
  pin a new memory or select a skill, send turn 2, and confirm via logcat
  that a restore (PreparingHistory state) occurs.

## Done criteria

- [ ] `./gradlew testDebugUnitTest` exits 0; fingerprint tests pass
- [ ] `./gradlew detekt assembleDebug` exits 0
- [ ] `grep -n "ensureLoaded(mediaRequirement, visualBudget)" app/src/main/java/com/aliahad/aichat/ui/viewmodel/ChatTurnRunner.kt` returns no matches
- [ ] No files outside the in-scope list are modified (`git status`)
- [ ] `plans/README.md` status row updated

## STOP conditions

Stop and report back (do not improvise) if:
- The excerpts above don't match the live code (drift).
- You find that `restoreSession` inputs include something not covered by
  the fingerprint (e.g., native code reads settings fields beyond
  systemPrompt/thinkingEnabled) — extend the fingerprint only if provable
  from `aichat_jni.cpp`'s `nativeRestore` signature usage; otherwise STOP.
- `unload()` or other reset sites cannot be located by searching
  `activeConversationId =` — do not guess.
- Tests fail twice after a reasonable fix attempt.

## Maintenance notes

- Any future field added to the native prompt (e.g., a new generation
  setting consumed by `nativeRestore`) MUST be added to the fingerprint or
  the silent-staleness bug returns.
- Reviewers should confirm the fingerprint covers exactly the inputs read
  at `NativeInferenceEngine.kt:161-183`.
- Deferred: restructuring restore to diff history instead of replaying it
  (a bigger latency win, needs its own plan after characterization tests
  from plan 006 exist).
