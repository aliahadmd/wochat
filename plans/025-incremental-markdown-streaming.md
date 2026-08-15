# Plan 025: Stop re-parsing the whole answer on every token

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

- **Priority**: P1
- **Effort**: M
- **Risk**: MED-HIGH (markdown rendering is what the user reads; a wrong fix
  produces flicker, lost formatting, or broken code blocks mid-stream)
- **Depends on**: 023, and land after 024 (same file region)
- **Category**: performance
- **Planned at**: commit `619d7de`, 2026-08-15

## Why this matters

Every assistant message renders through:

```kotlin
// AiChatApp.kt:943-950, inside MessageBubble
val markdownState = rememberMarkdownState(
    content = content,
    retainState = true,
)
Markdown(
    markdownState = markdownState,
    modifier = Modifier.fillMaxWidth(),
    ...
)
```

`content` is the **entire answer so far**, and it changes with every token.
`rememberMarkdownState` re-derives from that whole string each time, so
parsing work grows with answer length while the token rate stays constant —
the cost per token rises as the answer gets longer. A short reply feels fine;
a long one degrades as it generates, which matches the reported symptom that
the app is "not smooth" specifically during longer responses.

This is the second half of streaming smoothness. Plan 024 fixes *how the list
moves*; this fixes *how much work each frame does*.

## Current state

- Library: `com.mikepenz:multiplatform-markdown-renderer-m3` version
  `0.41.0` (`gradle/libs.versions.toml`), imported at `AiChatApp.kt:177` as
  `com.mikepenz.markdown.model.rememberMarkdownState`.
- Only one call site: `AiChatApp.kt:943`. User messages take the cheap path
  (`Text(content)` at line 941); only assistant messages parse markdown.
- `retainState = true` is already passed — investigate what that actually
  retains in 0.41.0 before assuming it does or does not help here.
- `MessageBubble` also reports render completions upward via
  `onMarkdownRendered`, which feeds `lastMessageRenderRevision` — one of the
  autoscroll keys plan 024 touches. Changing render cadence affects that
  signal; coordinate with 024's outcome.
- Round 1 already buffered *token delivery* (plan 013, "Buffer thinking-delta
  state updates"), so deltas do not arrive one-per-frame at the state layer.
  The remaining cost is in parsing, not in state churn.

## Commands you will need

| Purpose | Command | Expected on success |
|---------|---------|---------------------|
| Unit tests | `./gradlew testDebugUnitTest` | exit 0 |
| Instrumented compile | `./gradlew compileDebugAndroidTestKotlin` | exit 0 |
| Chat UI tests | `./gradlew connectedDebugAndroidTest --tests '*ChatUi*'` | exit 0 |
| Benchmarks | `./gradlew :benchmark:connectedBenchmarkAndroidTest` | exit 0 |

## Scope

**In scope**:
- `app/src/main/java/com/aliahad/aichat/ui/AiChatApp.kt`, the `MessageBubble`
  markdown region
- `gradle/libs.versions.toml` only if a library upgrade is the answer

**Out of scope**:
- Autoscroll — plan 024.
- Replacing the markdown library. That is a large migration with its own
  rendering-fidelity risk; if you conclude the library is the blocker, report
  it as a finding rather than starting the swap here.
- The thinking panel's own rendering.

## Steps

### Step 1: Measure before deciding

Run plan 023's streaming measurement and record before-numbers, specifically
comparing a short answer against a long one. The claim in this plan is that
cost *grows with length*; confirm that shape before optimizing for it. If the
curve is flat, the premise is wrong — report that and stop.

**Verify**: before-numbers recorded, including the short-vs-long comparison.

### Step 2: Understand what the library already offers

Read what `retainState = true` does in 0.41.0 and whether the library exposes
incremental or chunked parsing. Check whether a newer version added
streaming-oriented APIs. Spending an hour here may make Steps 3-4
unnecessary.

**Verify**: written finding in the status row — what the library does and
does not support.

### Step 3: Render streaming and settled messages differently

The most reliable structural fix, if the library has no incremental API:
while a message is `MessageStatus.STREAMING`, render the tail cheaply and
only do full markdown parsing when it settles.

Concretely, one of:

1. **Split rendering**: parse markdown for the stable prefix (up to the last
   completed block boundary — blank line outside a fenced code block) and
   render only the growing tail as plain text. Re-parse the prefix only when
   a new block completes.
2. **Throttled re-parse**: keep full parsing but recompute at a bounded
   cadence rather than on every delta, accepting slightly stale formatting
   mid-stream.

Option 1 is better but must handle fenced code blocks correctly — a naive
blank-line split inside a ``` fence will render broken output, which is worse
than the current jank. If you cannot make fence handling correct, use option
2.

Whichever you pick: when the message reaches a terminal status, the final
render must be a full, correct parse. No permanently degraded output.

**Verify**: manual check that a streaming answer containing a fenced code
block, a list, and a table renders correctly *during* and *after*
generation.

### Step 4: Keep the render signal honest

`onMarkdownRendered` currently fires per render and drives
`lastMessageRenderRevision`. If rendering cadence changes, that signal's
meaning changes with it. Confirm plan 024's autoscroll still tracks the
growing message correctly.

**Verify**: `./gradlew connectedDebugAndroidTest --tests '*ChatUi*'` → exit 0.

### Step 5: Measure again

Append after-numbers, including the same short-vs-long comparison. The
acceptance evidence is that the long-answer curve flattened.

## Test plan

- Before/after streaming numbers, with the short-vs-long comparison.
- Manual rendering-fidelity check with a mixed-content answer (headings,
  fenced code, nested list, table, inline code) during and after streaming.
- `ChatUiInstrumentedTest` green.
- Confirm user messages still take the cheap `Text` path.

## Done criteria

- [ ] Before/after numbers recorded, showing the long-answer curve flattened
- [ ] Library capabilities investigated and written down
- [ ] Streaming messages no longer re-parse the full answer per delta
- [ ] Terminal-status messages render a full, correct parse
- [ ] Fenced code blocks render correctly mid-stream
- [ ] Autoscroll still tracks correctly after the cadence change
- [ ] `./gradlew testDebugUnitTest` and `compileDebugAndroidTestKotlin` exit 0
- [ ] `plans/README.md` status row updated with the measured delta

## STOP conditions

Stop and report back (do not improvise) if:
- Step 1 shows cost does not grow with answer length. The premise is wrong;
  report and stop rather than optimizing anyway.
- Correct fenced-code-block handling in a split renderer proves unreliable.
  Fall back to option 2 and say so.
- The fix requires replacing the markdown library.
- Rendering flickers or reflows visibly at the streaming/settled boundary —
  that trade is not worth it; report it.

## Maintenance notes

- The split-render boundary is a correctness surface, not just a perf
  optimization: anything that changes block-boundary detection needs the
  mixed-content fidelity check re-run.
- Reviewers: the acceptance question is "does a 2,000-word answer stream as
  smoothly as a 50-word one?"
