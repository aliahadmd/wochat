# Plan 013: Buffer thinking-delta state updates out of the per-token path

> **Executor instructions**: Follow this plan step by step. Run every
> verification command and confirm the expected result before moving to the
> next step. If anything in the "STOP conditions" section occurs, stop and
> report — do not improvise. When done, update the status row for this plan
> in `plans/README.md` — unless a reviewer dispatched you and told you they
> maintain the index.
>
> **Drift check (run first)**: `git diff --stat 2dff7e6..HEAD -- app/src/main/java/com/aliahad/aichat/ui/viewmodel/ChatTurnRunner.kt app/src/main/java/com/aliahad/aichat/ui/AiChatApp.kt`
> Plans 001 and 004 touch ChatTurnRunner.kt — run after those land.

## Status

- **Priority**: P2
- **Effort**: M
- **Risk**: MED (touches scroll-follow behavior users feel; keep changes minimal and mechanical)
- **Depends on**: 006 (characterization safety net), 001/004 (same file)
- **Category**: perf
- **Planned at**: commit `2dff7e6`, 2026-08-15

## Why this matters

Every `ThoughtDelta` token currently: (a) copies the ENTIRE accumulated
thinking string (`thinking.text + event.text`) into a new `ChatUiState`;
(b) triggers `formatThoughtForDisplay` (3 replaces + per-line regex over
the full text) via `remember(thinking.text)`; (c) re-triggers
`scrollToItem` via a `LaunchedEffect(thinking?.text?.length)`. Over a
multi-KB thinking trace this is quadratic total copying plus a
full-recomposition path per token — competing with the inference thread
for frames exactly while the user watches the spinner. Also, a preview
regex `Regex("\\s+")` is constructed on every recomposition.

## Current state

- `app/src/main/java/com/aliahad/aichat/ui/viewmodel/ChatTurnRunner.kt`
  (send path, lines 260–272; identical continue path at 443–452):

```kotlin
// ChatTurnRunner.kt:260-272
is GenerationEvent.ThoughtDelta -> {
    val messageId = assistant?.id ?: return@collect
    _state.update { state ->
        val thinking = state.thinking?.takeIf { it.messageId == messageId }
            ?: ThinkingUiState(messageId)
        state.copy(
            thinking = thinking.copy(
                text = thinking.text + event.text,
                complete = false,
            ),
        )
    }
}
```

  Answer deltas already use a cadence gate for PERSISTENCE (250 ms, lines
  284–291) — but thinking STATE updates have no gate.

- `app/src/main/java/com/aliahad/aichat/ui/AiChatApp.kt` (3,231 lines —
  touch only the cited regions; locate by exact strings):
  - Line ~932: `remember(thinking.text) { formatThoughtForDisplay(thinking.text) }`
  - Line ~973: `displayText.replace(Regex("\\s+"), " ")` (1-line preview)
  - Lines ~721–733: `LaunchedEffect(thinking?.text?.length)` →
    `scrollToItem` (follow-latest machinery; also `lastMessageHeight`
    around 703–733).

Conventions: state flows through a single `MutableStateFlow<ChatUiState>`;
the answer path already demonstrates the accepted 250 ms cadence pattern
(`lastSavedAt` gating) — mirror it.

## Commands you will need

| Purpose | Command | Expected on success |
|---------|---------|---------------------|
| Unit tests | `./gradlew testDebugUnitTest` | exit 0 |
| Detekt | `./gradlew detekt` | exit 0 |
| Build | `./gradlew assembleDebug` | exit 0 |
| AndroidTest compile | `./gradlew compileDebugAndroidTestKotlin` | exit 0 |

## Scope

**In scope**:
- `app/src/main/java/com/aliahad/aichat/ui/viewmodel/ChatTurnRunner.kt` (both ThoughtDelta handlers)
- `app/src/main/java/com/aliahad/aichat/ui/AiChatApp.kt` (ONLY the three cited regions: ~932, ~973, ~721-733)

**Out of scope**:
- Answer-delta persistence cadence (already gated).
- The follow-latest/`lastMessageHeight` design (keep semantics identical).
- Any restructuring of `ChatUiState` into multiple flows (bigger design).

## Steps

### Step 1: Cadence-gate thinking state updates

In BOTH delta handlers (send 260–272, continue 443–452), accumulate into a
local `StringBuilder thinkingBuffer` (declared next to `content`/`continuation`)
and flush into `_state.update` on the same cadence the answers use:

```kotlin
is GenerationEvent.ThoughtDelta -> {
    val messageId = assistant?.id ?: return@collect   // or target.id in continue
    thinkingBuffer.append(event.text)
    val now = System.currentTimeMillis()
    if (now - lastThinkingFlushAt >= 100 || thinkingBuffer.length < 64) {
        lastThinkingFlushAt = now
        _state.update { state ->
            val thinking = state.thinking?.takeIf { it.messageId == messageId }
                ?: ThinkingUiState(messageId)
            state.copy(thinking = thinking.copy(text = thinkingBuffer.toString(), complete = false))
        }
    }
}
```

Add `var lastThinkingFlushAt = 0L` next to `lastSavedAt`. CRITICAL
correctness points:
- `thinkingBuffer` is the single source of truth during the run; state
  reads only flushed snapshots (monotonic — buffer only grows).
- The AnswerDelta "thinking complete" transition (lines 273–283/453–463)
  and the `finally` block's `keepThinking` handling (355–364/511–520) must
  see the FINAL buffer: add a last unconditional flush before the
  completion handling (after the collect loop ends, alongside the
  completion persistence at ~307/484).
- Both paths must be updated symmetrically.

**Verify**: `./gradlew compileDebugKotlin` → exit 0.

### Step 2: Cheaper preview formatting in the UI

At the three AiChatApp.kt regions (locate by string match, not line):
1. ~932: hoist nothing here — but the cadence from Step 1 already reduced
   `remember(thinking.text)` invalidations 10–20×. Leave as-is.
2. ~973: hoist the regex to a private top-level
   `private val WHITESPACE = Regex("\\s+")` and truncate before
   formatting: `displayText.takeLast(200).replace(WHITESPACE, " ")`
   (preview is 1 line; the head of a long trace adds nothing).
3. ~721–733: change the LaunchedEffect key from
   `thinking?.text?.length` to a bucketed length
   `thinking?.text?.length?.div(80)` so scroll-follow re-triggers at most
   every 80 chars instead of per token. (If visual inspection on device
   shows laggy follow, halve the divisor — do not remove the bucketing.)

**Verify**: `./gradlew compileDebugKotlin` → exit 0.

### Step 3: Full gate

**Verify**: `./gradlew testDebugUnitTest detekt assembleDebug compileDebugAndroidTestKotlin` → exit 0.

## Test plan

- Extend plan 006's test file? No — this is UI-state behavior. Minimal JVM
  coverage: if `ChatTurnRunner` has any existing unit-testable seams, add a
  test asserting that N rapid ThoughtDeltas produce ≤ N/5 state emissions
  using a `TestScope` with virtual time (mirror
  `RecoveringInferenceEngineTest`'s fake-engine style). If the runner
  cannot be constructed on the JVM, rely on compile + androidTest
  (`ChatViewModelInstrumentedTest` covers the send path end-to-end once
  plan 005 lands) and note it.
- Manual on-device check (if available): thinking-heavy prompt (e.g. a
  math question with thinking enabled) — confirm the thinking panel still
  streams visibly and scroll-follow tracks.

## Done criteria

- [ ] `./gradlew testDebugUnitTest detekt assembleDebug compileDebugAndroidTestKotlin` exit 0
- [ ] Both ThoughtDelta handlers use the buffered cadence; final flush
      before completion handling verified by reading the diff
- [ ] `Regex("\\s+")` no longer constructed inside composition (grep the
      file: exactly one top-level `WHITESPACE` val)
- [ ] LaunchedEffect key bucketed
- [ ] `git status` shows only in-scope files/regions
- [ ] `plans/README.md` status row updated

## STOP conditions

Stop and report back (do not improvise) if:
- The "thinking complete" transition or `keepThinking` logic can't be
  reconciled with the buffer without changing observable behavior — report
  the conflict.
- The LaunchedEffect region (~721–733) looks materially different from the
  description (drift) — re-derive before editing; do not guess.
- Instrumented/manual testing shows lost thinking text (final flush bug) —
  revert and report.

## Maintenance notes

- The deeper fix is streaming thinking as its own StateFlow (not inside
  ChatUiState) so the whole screen doesn't recompose — deferred; this plan
  makes the current shape cheap.
- The DEBT-02 refactor (deduplicating send/continue) will subsume the two
  symmetric handlers — the buffer logic should move into the shared engine
  when that lands.
