# Plan 024: Streaming scroll smoothness

> **Executor instructions**: Follow this plan step by step. Run every
> verification command and confirm the expected result before moving to the
> next step. If anything in the "STOP conditions" section occurs, stop and
> report — do not improvise. When done, update the status row for this plan
> in `plans/README.md` — unless a reviewer dispatched you and told you they
> maintain the index.
>
> **Drift check (run first)**:
> `git diff --stat 619d7de..HEAD -- app/src/main/java/com/aliahad/aichat/ui/AiChatApp.kt`
> This plan quotes an exact region of a 3,481-line file. On any mismatch,
> re-locate the code by content, and treat a behavioral difference as a STOP
> condition.

## Status

- **Priority**: P1
- **Effort**: M
- **Risk**: MED (autoscroll behavior is subtle — the existing code handles
  user-drag, at-bottom, and thinking-panel cases that must not regress)
- **Depends on**: 023
- **Category**: performance / UX
- **Planned at**: commit `619d7de`, 2026-08-15

## Why this matters

The message list re-runs its follow-the-latest effect on **every token**, and
when it does, it jumps rather than scrolls:

```kotlin
// AiChatApp.kt:805-817
LaunchedEffect(
    messages.size,
    lastMessage?.content?.length,        // <-- changes on every single token
    thinking?.text?.length?.div(80),
    thinking?.expanded,
    lastMessageHeight,
    lastMessageRenderRevision,
    followLatest,
) {
    if (followLatest && messages.isNotEmpty()) {
        listState.scrollToItem(messages.size)   // <-- instant jump, not a scroll
    }
}
```

Two distinct problems:

1. **Per-token coroutine churn.** `lastMessage?.content?.length` changes with
   every delta, so the whole `LaunchedEffect` is cancelled and relaunched
   many times per second during generation.
2. **`scrollToItem` snaps.** It is the instantaneous variant. Combined with
   the growing last message, the list lurches instead of gliding — this is
   the most visible part of "streaming doesn't feel smooth".

A third, smaller issue lives just below: the jump-to-latest button appears
and disappears with no transition at all (`if (!isAtBottom) { IconButton(...) }`
at line 856), so it pops in and out during scrolling.

## Current state

- `AiChatApp.kt:795-817` — four `LaunchedEffect`s managing `followLatest`:

```kotlin
LaunchedEffect(isUserDragging) {
    if (isUserDragging) followLatest = false
}
LaunchedEffect(isAtBottom, isUserDragging) {
    if (isAtBottom && !isUserDragging) followLatest = true
}
LaunchedEffect(lastMessage?.id) {
    if (lastMessage?.role == MessageRole.USER) followLatest = true
}
LaunchedEffect(/* 7 keys */) { ... }
```

- `AiChatApp.kt:819-855` — the `LazyColumn` with `key = { it.id }` (already
  correct) and a trailing `item(key = "conversation-bottom")` spacer, which is
  the actual scroll target at index `messages.size`.
- `AiChatApp.kt:856-875` — the jump-to-latest `IconButton`, which does use
  `animateScrollToItem` on tap (so the animated API is already imported).

These behaviors are load-bearing and must survive:
- dragging up stops the follow;
- returning to the bottom resumes it;
- sending a new user message forces follow back on;
- the thinking panel expanding/collapsing keeps the view pinned.

## Commands you will need

| Purpose | Command | Expected on success |
|---------|---------|---------------------|
| Unit tests | `./gradlew testDebugUnitTest` | exit 0 |
| Instrumented compile | `./gradlew compileDebugAndroidTestKotlin` | exit 0 |
| Chat UI tests | `./gradlew connectedDebugAndroidTest --tests '*ChatUi*'` | exit 0 |
| Benchmarks | `./gradlew :benchmark:connectedBenchmarkAndroidTest` | exit 0 |

## Scope

**In scope**:
- `app/src/main/java/com/aliahad/aichat/ui/AiChatApp.kt`, the message-list
  region (roughly lines 780-880)

**Out of scope**:
- Markdown rendering cost — that is plan 025. These two plans both touch this
  region; **serialize them** and do 024 first.
- `ChatTurnRunner` / token delivery. Delta buffering already landed in round 1
  (plan 013); do not revisit it here.
- The `MessageBubble` internals.

## Steps

### Step 1: Take the measurement first

Run the streaming benchmark or manual protocol from plan 023 and record the
before-numbers. Without a before, this plan cannot be shown to have worked
and should not merge.

**Verify**: baseline numbers recorded in the results note.

### Step 2: Replace the multi-key effect with a snapshot flow

Collapse the 7-key `LaunchedEffect` into a single effect that observes
scroll-relevant state via `snapshotFlow`, with conflation so a burst of token
updates coalesces into one scroll action per frame rather than one per token.

Keep the *decision* logic (`followLatest`) exactly as it is — you are
changing how often the scroll runs, not when the app decides to follow.

**Verify**: `grep -n "lastMessage?.content?.length" app/src/main/java/com/aliahad/aichat/ui/AiChatApp.kt`
→ no longer used as a `LaunchedEffect` key.

### Step 3: Scroll instead of snapping

During streaming, keep the view pinned to the bottom without the lurch.
Options, in preference order:

1. `animateScrollToItem` when the distance is small, falling back to
   `scrollToItem` for large jumps (switching conversations, first load).
2. `LazyColumn(reverseLayout = true)` so growth naturally stays anchored —
   **only** if it does not require restructuring the message ordering,
   padding, and the empty state. It usually does; treat it as a STOP
   condition if it cascades.

Do not animate the initial load or a conversation switch — those should be
instant.

**Verify**: manual check that a streaming answer glides rather than jumps.

### Step 4: Animate the jump-to-latest button

Wrap the `if (!isAtBottom)` button in `AnimatedVisibility` with a short
fade+scale. The file already imports `AnimatedVisibility`, `fadeIn`, `fadeOut`,
and `tween` — match the durations already used nearby (`tween(160)` in,
`tween(200)` out at lines 923-924) rather than introducing new timings.

**Verify**: `./gradlew assembleDebug` → exit 0.

### Step 5: Prove the behaviors survived

The four autoscroll behaviors listed under "Current state" are the regression
risk. Add instrumented coverage for at least: drag-up stops following, and
return-to-bottom resumes following.

**Verify**: `./gradlew connectedDebugAndroidTest --tests '*ChatUi*'` → exit 0.

### Step 6: Take the measurement again

Re-run the plan 023 benchmark and append the after-numbers. A plan claiming a
smoothness win with no delta recorded is not done.

## Test plan

- Before/after streaming frame-timing numbers, recorded in the results note.
- New instrumented tests for drag-up and return-to-bottom.
- Existing `ChatUiInstrumentedTest` green.
- Manual: generate a long answer and confirm the list glides; drag up
  mid-generation and confirm it stays put; scroll back down and confirm it
  resumes.

## Done criteria

- [ ] Before and after frame-timing numbers recorded
- [ ] Token-length no longer drives a `LaunchedEffect` key
- [ ] Scroll updates are conflated to at most one per frame
- [ ] Streaming scroll is animated; conversation switches stay instant
- [ ] Jump-to-latest button animates in and out with existing durations
- [ ] All four autoscroll behaviors verified intact
- [ ] `./gradlew testDebugUnitTest` and `compileDebugAndroidTestKotlin` exit 0
- [ ] `plans/README.md` status row updated with the measured delta

## STOP conditions

Stop and report back (do not improvise) if:
- `reverseLayout` turns out to require restructuring message ordering, the
  empty state, or content padding. Report and use option 1 instead.
- Conflating updates makes the view visibly lag behind the text.
- Plan 023 could not produce a streaming measurement. Do not merge this on
  "it feels better" — mark BLOCKED and say so.
- The before/after delta is within measurement noise. Report that honestly;
  a null result is a real finding and redirects effort to plan 025.

## Maintenance notes

- The `conversation-bottom` spacer item exists so the scroll target is stable
  as the last message grows. Do not remove it.
- Reviewers: the acceptance question is "what were the P99 frame times before
  and after?" Not "does the code look better?"
