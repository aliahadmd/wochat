# Plan 004: Make inference/residency state changes exception-safe (counter leak, stale model path, repetition stop reason)

> **Executor instructions**: Follow this plan step by step. Run every
> verification command and confirm the expected result before moving to the
> next step. If anything in the "STOP conditions" section occurs, stop and
> report — do not improvise. When done, update the status row for this plan
> in `plans/README.md` — unless a reviewer dispatched you and told you they
> maintain the index.
>
> **Drift check (run first)**: `git diff --stat 2dff7e6..HEAD -- app/src/main/java/com/aliahad/aichat/residency/ModelResidencyController.kt app/src/main/java/com/aliahad/aichat/inference/NativeInferenceEngine.kt app/src/main/java/com/aliahad/aichat/core/AppModels.kt`
> If any in-scope file changed since this plan was written, compare the
> "Current state" excerpts against the live code before proceeding; on a
> mismatch, treat it as a STOP condition.

## Status

- **Priority**: P1
- **Effort**: S
- **Risk**: LOW
- **Depends on**: none
- **Category**: bug
- **Planned at**: commit `2dff7e6`, 2026-08-15

## Why this matters

Three small state-integrity bugs that each strand the app in a wrong state
until process restart:

1. `ModelResidencyController.beginInferenceUse()` increments
   `activeInferenceUsers` BEFORE `job.cancelAndJoin()`. If the caller's
   coroutine is cancelled during the join, the increment survives while the
   caller (`ChatTurnRunner`) never sets `inferenceUseStarted = true` — so
   `endInferenceUse()` (guarded by that flag) never runs. The counter
   clamps at 0 and `canStartVerification()` requires it to BE 0, so
   progressive context-size verification is permanently disabled for the
   process lifetime — users stay capped at the 4,096-token fallback
   context.
2. A failed model load leaves `loadedModelPath`/`loadedBackend` at their
   previous values while the native side has already unloaded
   (`load_model` calls `unload_model()` first in `aichat_jni.cpp:228`).
   The residency controller then believes a model is still loaded:
   `isPathInUse` wrongly blocks file operations, `loadedSignature` is not
   cleared.
3. When the repetition guard rejects output, the stop reason is recorded as
   `TOKEN_LIMIT`, which maps to `MessageStatus.CONTINUABLE` — the UI offers
   "Continue", which just resumes the same degenerate loop.

## Current state

- `app/src/main/java/com/aliahad/aichat/residency/ModelResidencyController.kt`:

```kotlin
// ModelResidencyController.kt:138-149
override suspend fun beginInferenceUse() {
    activeInferenceUsers.incrementAndGet()
    verificationJob?.let { job ->
        job.cancel()
        job.cancelAndJoin()
    }
}

override fun endInferenceUse() {
    activeInferenceUsers.updateAndGet { value -> (value - 1).coerceAtLeast(0) }
    scheduleVerification()
}
```

  Callers: `ChatTurnRunner.kt:158-159` (`beginInferenceUse()`;
  `inferenceUseStarted = true` only AFTER the call returns) and
  `:366`/`:522` (`if (inferenceUseStarted) residencyController.endInferenceUse()`).
  `canStartVerification()` (around line 475-478) requires
  `activeInferenceUsers.get() == 0`.

- `app/src/main/java/com/aliahad/aichat/inference/NativeInferenceEngine.kt`:

```kotlin
// NativeInferenceEngine.kt:92-113 (abridged)
val loadError = try { nativeLoad(...) } finally { releaseCpu() }
if (loadError != null) {
    _state.value = InferenceState.Error(loadError)
    throw BackendInferenceException(selected, BackendFailureStage.LOAD, loadError)
}
loadedBackend = selected
loadedModelPath = path
...
```

  On failure, `loadedModelPath`/`loadedBackend` keep their old values.
  Also lines 272-275 (repetition guard):

```kotlin
// NativeInferenceEngine.kt:272-276
if (!repetitionGuard.accept(token)) {
    stopReason = GenerationStopReason.TOKEN_LIMIT
    cancelled = true
    break
}
```

- `app/src/main/java/com/aliahad/aichat/core/AppModels.kt:17-25` —
  `GenerationStopReason` enum (EOG, TOKEN_LIMIT, CONTEXT_LIMIT,
  PROCESS_DEATH, CANCELLED, DECODE_ERROR, ERROR — verify exact members
  before editing). Persisted via `stopReason` on messages; adding a value
  is additive and safe for Room (stored as String name) but verify how it
  is serialized (`AppDatabase`/entities use name-based converters).
- `ChatTurnRunner.kt:560-568` — `GenerationStopReason.toMessageStatus()`
  maps TOKEN_LIMIT/CONTEXT_LIMIT/PROCESS_DEATH → CONTINUABLE.

Conventions: engine state vars are private vars mutated inside
`withContext(dispatcher)`; errors surface via `BackendInferenceException`
with a `BackendFailureStage`.

## Commands you will need

| Purpose | Command | Expected on success |
|---------|---------|---------------------|
| Unit tests | `./gradlew testDebugUnitTest` | exit 0 |
| Detekt | `./gradlew detekt` | exit 0 |
| Debug build | `./gradlew assembleDebug` | exit 0 |
| AndroidTest compile | `./gradlew compileDebugAndroidTestKotlin` | exit 0 |

## Scope

**In scope**:
- `app/src/main/java/com/aliahad/aichat/residency/ModelResidencyController.kt`
- `app/src/main/java/com/aliahad/aichat/inference/NativeInferenceEngine.kt`
- `app/src/main/java/com/aliahad/aichat/core/AppModels.kt` (enum addition only)
- `app/src/main/java/com/aliahad/aichat/ui/viewmodel/ChatTurnRunner.kt` (toMessageStatus mapping only)
- `app/src/test/java/com/aliahad/aichat/residency/` (new/extended test)

**Out of scope**:
- `ChatTurnRunner` send/continue orchestration beyond the mapping line.
- `aichat_jni.cpp` (native unload behavior is already correct).
- UI rendering of the new stop reason beyond the mapping.

## Steps

### Step 1: Exception-safe `beginInferenceUse`

Change the method so a cancellation during the join cannot strand the
increment:

```kotlin
override suspend fun beginInferenceUse() {
    activeInferenceUsers.incrementAndGet()
    try {
        verificationJob?.let { job ->
            job.cancel()
            job.cancelAndJoin()
        }
    } catch (error: Throwable) {
        activeInferenceUsers.updateAndGet { value -> (value - 1).coerceAtLeast(0) }
        throw error
    }
}
```

(`CancellationException` is a `Throwable`; rethrowing it after decrementing
keeps structured-concurrency semantics — the caller sees cancellation AND
the counter is restored.)

**Verify**: `./gradlew compileDebugKotlin` → exit 0.

### Step 2: Clear loaded-model state on load failure

In `NativeInferenceEngine`, before throwing at line 104, clear the stale
state to mirror the native side:

```kotlin
if (loadError != null) {
    loadedModelPath = null
    loadedModelName = null
    loadedBackend = null          // if the field is non-nullable, check its type first
    activeConversationId = null
    _state.value = InferenceState.Error(loadError)
    throw BackendInferenceException(selected, BackendFailureStage.LOAD, loadError)
}
```

Check the declared types of `loadedBackend`/`loadedModelPath` first
(`grep -n "loadedBackend\\|loadedModelPath" NativeInferenceEngine.kt | head`)
and use the same nullability approach `unload()` uses (lines 315–320) so
consumers compile unchanged.

**Verify**: `./gradlew compileDebugKotlin` → exit 0.

### Step 3: Add a `REPETITION` stop reason and map it

1. In `core/AppModels.kt`, add `REPETITION` to `GenerationStopReason`.
2. In `NativeInferenceEngine.kt:273`, set
   `stopReason = GenerationStopReason.REPETITION` in the guard branch.
3. In `ChatTurnRunner.kt` `toMessageStatus()`, map `REPETITION` to
   `MessageStatus.ERROR` (NOT CONTINUABLE — continuing a degenerate loop is
   the bug). If the UI renders stop reasons by name anywhere (search
   `stopReason` usages in `ui/AiChatApp.kt`), ensure `REPETITION` falls
   into a sensible default branch (a `when` without a matching branch won't
   compile, which is the safety net).
4. Old persisted messages with `TOKEN_LIMIT` remain readable — enum decode
   is by name and the old value still exists.

**Verify**: `./gradlew compileDebugKotlin` → exit 0.

### Step 4: Unit test

Extend `app/src/test/java/com/aliahad/aichat/residency/` (an existing
residency test file exists there — follow its fake style). Add a test:
call `beginInferenceUse()` from a coroutine, cancel it during the
`cancelAndJoin` window (e.g., make the fake verification job suspend until
cancelled), assert `activeInferenceUsers` returns to 0 (observe via
`canStartVerification()` becoming true again or expose the count for
tests).

**Verify**: `./gradlew testDebugUnitTest` → all pass.

## Test plan

- Residency counter rollback test as above (model after the existing
  residency unit test file).
- Stop-reason mapping: add a small unit test asserting
  `GenerationStopReason.REPETITION` maps to `MessageStatus.ERROR` (the
  `toMessageStatus` fn is private — either test via a public seam or
  relocate the mapper to `core/AppModels.kt` as an internal function and
  test it there; prefer relocation only if trivially safe).

## Done criteria

- [ ] `./gradlew testDebugUnitTest` exits 0 with the new tests
- [ ] `./gradlew detekt assembleDebug compileDebugAndroidTestKotlin` exit 0
- [ ] `grep -n "REPETITION" app/src/main/java/com/aliahad/aichat/core/AppModels.kt` shows the enum value
- [ ] Load-failure path nulls `loadedModelPath` before throwing
- [ ] No files outside the in-scope list are modified (`git status`)

## STOP conditions

Stop and report back (do not improvise) if:
- `loadedBackend` is non-nullable and `unload()` uses a different reset
  convention that doesn't generalize — report what you found.
- Adding the enum value breaks a `when` exhaustive check in the Compose UI
  that cannot be resolved by mapping to an existing status.
- The residency test cannot cancel inside the join window with the existing
  fakes after one reasonable attempt.

## Maintenance notes

- `beginInferenceUse`/`endInferenceUse` pairing is load-bearing for context
  verification; any new caller must keep the invariant
  increment↔decrement.
- `REPETITION` is now part of the persisted `stopReason` vocabulary;
  backup/restore of old databases is unaffected (string decode, old values
  unchanged).
- Deferred: surfacing a user-visible explanation string for REPETITION in
  the UI (small follow-up, cosmetic).
