# Plan 006: Characterization tests for PromptContextPlanner and mergeContinuation

> **Executor instructions**: Follow this plan step by step. Run every
> verification command and confirm the expected result before moving to the
> next step. If anything in the "STOP conditions" section occurs, stop and
> report — do not improvise. When done, update the status row for this plan
> in `plans/README.md` — unless a reviewer dispatched you and told you they
> maintain the index.
>
> **Drift check (run first)**: `git diff --stat 2dff7e6..HEAD -- app/src/main/java/com/aliahad/aichat/memory/PromptContextPlanner.kt app/src/main/java/com/aliahad/aichat/ui/viewmodel/ChatTurnRunner.kt`
> If any in-scope file changed since this plan was written, compare the
> "Current state" excerpts against the live code before proceeding; on a
> mismatch, treat it as a STOP condition.

## Status

- **Priority**: P1
- **Effort**: S
- **Risk**: LOW (test-only; one mechanical relocation of a private function)
- **Depends on**: none
- **Category**: tests
- **Planned at**: commit `2dff7e6`, 2026-08-15

## Why this matters

`PromptContextPlanner` is the context-overflow guard — the code that keeps
the model from being handed more tokens than its context window. It has
zero direct tests (the older `HistoryTrimmer` is tested in
`CoreLogicTest.kt`, not the planner). `mergeContinuation`, the
overlap-stitching algorithm for continued answers, is likewise untested.
Both are about to be touched by perf work (plan 012) and any future turn
lifecycle refactor; without characterization tests, regressions in the
greedy fit loop or the overlap stitch ship silently.

## Current state

- `app/src/main/java/com/aliahad/aichat/memory/PromptContextPlanner.kt`
  (160 lines). The fit loop and final check:

```kotlin
// PromptContextPlanner.kt:78-88
val selectedReversed = ArrayDeque<ChatTurn>()
val trimmed = mutableListOf<ChatTurn>()
for (turn in history.asReversed()) {
    val tokens = turnTokenCount(turn)
    if (tokens <= remaining) {
        selectedReversed.addFirst(turn)
        remaining -= tokens
    } else {
        trimmed += turn
    }
}
```

```kotlin
// PromptContextPlanner.kt:118-123
val systemTokens = inferenceEngine.countTokens(systemPrompt).coerceAtLeast(1)
val historyTokens = selectedReversed.sumOf { turnTokenCount(it) }
val estimatedTokens = systemTokens + historyTokens + currentTokens
check(estimatedTokens + outputReserve <= contextTokens) {
    "Prompt planning exceeded the loaded model context"
}
```

  Memory selection lives at lines 60–76, summary fallback at 90–105, and
  the system prompt assembly at 107–117. The class constructor takes (per
  the audit) an `InferenceEngine` (for `countTokens`), repositories for
  memories/summaries — read the actual constructor signature before
  writing fakes. `ChatViewModelInstrumentedTest.kt:104-108` (androidTest)
  already demonstrates fakeable `InferenceEngine` usage; mirror that
  pattern in JVM tests.

- `app/src/main/java/com/aliahad/aichat/ui/viewmodel/ChatTurnRunner.kt:570-579`
  — file-private function:

```kotlin
private fun mergeContinuation(existing: String, continuation: String): String {
    if (existing.isEmpty() || continuation.isEmpty()) return existing + continuation
    val maximum = minOf(existing.length, continuation.length, 320)
    for (overlap in maximum downTo 12) {
        if (existing.regionMatches(existing.length - overlap, continuation, 0, overlap)) {
            return existing + continuation.drop(overlap)
        }
    }
    return existing + continuation
}
```

- Existing test conventions: `app/src/test/java/com/aliahad/aichat/` —
  plain JUnit4, kotlinx-coroutines-test `runTest`, inline private `Fake*`
  classes implementing production interfaces (see
  `RecoveringInferenceEngineTest.kt`, `CoreLogicTest.kt`).

## Commands you will need

| Purpose | Command | Expected on success |
|---------|---------|---------------------|
| Unit tests | `./gradlew testDebugUnitTest` | exit 0, all pass |
| Detekt | `./gradlew detekt` | exit 0 |

## Scope

**In scope**:
- `app/src/test/java/com/aliahad/aichat/memory/PromptContextPlannerTest.kt` (new)
- `app/src/test/java/com/aliahad/aichat/ui/viewmodel/MergeContinuationTest.kt` (new)
- `app/src/main/java/com/aliahad/aichat/ui/viewmodel/ChatTurnRunner.kt` (ONLY to make `mergeContinuation` internal/testable — no behavior change)

**Out of scope**:
- `PromptContextPlanner.kt` production logic — this plan adds tests only;
  if a test reveals a bug, STOP and report (do not fix).
- Any other production file.

## Steps

### Step 1: Make `mergeContinuation` testable

In `ChatTurnRunner.kt`, change the file-private `mergeContinuation` to
`internal fun mergeContinuation(...)` at file level (same location). No
behavior change. Verify call sites compile unchanged.

**Verify**: `./gradlew compileDebugKotlin` → exit 0.

### Step 2: Write `MergeContinuationTest`

Plain JUnit4, direct calls. Cases (expected values derived from the
CURRENT implementation — characterization, not aspiration):

1. Empty existing → returns continuation as-is.
2. Empty continuation → returns existing as-is.
3. Exact-tail overlap ≥ 12 chars → stitched once (`existing +
   continuation.drop(overlap)`), e.g. existing `"…the answer is 42 and`
   continuation `"and the reason is…"`.
4. Overlap shorter than 12 chars → NOT stitched (loop starts at 12).
5. Overlap longer than 320 chars → capped at 320 (construct strings where
   the true overlap is 400 chars; assert the first 80 chars of the
   continuation remain visible).
6. No overlap → plain concatenation.
7. Overlap occurring in the middle (not at the tail) → ignored.

**Verify**: `./gradlew testDebugUnitTest --tests '*MergeContinuationTest*'` → all pass.

### Step 3: Write `PromptContextPlannerTest`

Read the planner's actual constructor and collaborators first
(`grep -n "class PromptContextPlanner" -A 20 ...`). Build JVM fakes:

- Fake `InferenceEngine`: `countTokens(text) = text.length / 4` (or any
  deterministic function) — only that member is needed; implement the rest
  of the interface with `error("unused")` stubs, following the inline-fake
  convention of `RecoveringInferenceEngineTest.kt`.
- Fake memory repository / summaries per the real constructor parameter
  types (in-memory maps).

Cases:
1. Everything fits → all history kept, newest-first order preserved.
2. One oversized old turn → dropped from history, trimmed list passed to
   summary update, newest turns kept.
3. Budget forces partial fit → newest turns preferred over older ones.
4. Memories included only when `memoryEnabled = true`.
5. Summary block fits → included in systemPrompt; summary too large →
   excluded (line 101–105 behavior).
6. Overflow beyond context → `check` throws
   `IllegalStateException("Prompt planning exceeded the loaded model context")`.
7. `estimatedTokens + outputReserve <= contextTokens` holds on a fitted
   plan (assert via the returned `ContextPlan.estimatedTokens` field —
   check its actual name).

**Verify**: `./gradlew testDebugUnitTest --tests '*PromptContextPlannerTest*'` → all pass.

### Step 4: Full gate

**Verify**: `./gradlew testDebugUnitTest detekt` → exit 0.

## Test plan

This plan IS the test plan (above). Structural pattern:
`app/src/test/java/com/aliahad/aichat/inference/RecoveringInferenceEngineTest.kt`
(inline fakes + runTest).

## Done criteria

- [ ] `./gradlew testDebugUnitTest` exits 0 with both new test files
- [ ] `./gradlew detekt` exits 0
- [ ] `mergeContinuation` is `internal`, production behavior unchanged
- [ ] No other files modified (`git status`)
- [ ] `plans/README.md` status row updated

## STOP conditions

Stop and report back (do not improvise) if:
- Any characterization test FAILS against current behavior — that's a
  latent bug; report which case and the actual output (do not "fix" the
  test to match a guess, and do not change production code).
- The planner's constructor requires Android framework types that cannot
  be faked on the JVM — report the exact types; a Robolectric or
  instrumented fallback decision belongs to the maintainer.
- Drift in the excerpts.

## Maintenance notes

- These are characterization tests: if plan 012 (token memoization) changes
  internal counting, update expectations deliberately, not by deleting
  cases.
- The fakes built here should be reused by future turn-lifecycle refactors
  (deferred finding DEBT-02).
