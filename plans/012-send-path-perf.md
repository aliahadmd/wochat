# Plan 012: Cut send-path tokenization and context-assembly waste (memoize planner counts, hoist regexes, batch per-message attachment loads)

> **Executor instructions**: Follow this plan step by step. Run every
> verification command and confirm the expected result before moving to the
> next step. If anything in the "STOP conditions" section occurs, stop and
> report — do not improvise. When done, update the status row for this plan
> in `plans/README.md` — unless a reviewer dispatched you and told you they
> maintain the index.
>
> **Drift check (run first)**: `git diff --stat 2dff7e6..HEAD -- app/src/main/java/com/aliahad/aichat/memory/PromptContextPlanner.kt app/src/main/java/com/aliahad/aichat/attachment/AttachmentRepository.kt app/src/main/java/com/aliahad/aichat/ui/viewmodel/ChatTurnRunner.kt app/src/main/java/com/aliahad/aichat/data/Daos.kt`
> Plans 001 and 011 touch ChatTurnRunner.kt and Daos.kt/AttachmentRepository.kt —
> run this plan AFTER those have landed and reconcile any drift.

## Status

- **Priority**: P2
- **Effort**: M
- **Risk**: MED (caching must invalidate on chunk/page-selection changes)
- **Depends on**: 006 (characterization tests must exist first), 011 (same files)
- **Category**: perf
- **Planned at**: commit `2dff7e6`, 2026-08-15

## Why this matters

Every send/continue runs three layers of redundant work that grows with
conversation size:

1. `PromptContextPlanner` tokenizes each selected history turn during the
   fit loop, then re-tokenizes the identical turns for the final sum —
   doubling native JNI `countTokens` traversals (each covering up to 36 KB
   of attachment text per attachment).
2. Attachment context assembly is N+1 per message (`contextsForMessage` per
   historical turn → `dao.getForMessage` + `dao.chunks(entity.id)` per
   attachment), and the keyword ranking compiles a new `Regex` per keyword
   per chunk inside `sortedByDescending`.
3. The batched `contexts(ids, prompt)` path exists but the send path
   doesn't use it.

## Current state

- `app/src/main/java/com/aliahad/aichat/memory/PromptContextPlanner.kt`:

```kotlin
// PromptContextPlanner.kt:78-88
for (turn in history.asReversed()) {
    val tokens = turnTokenCount(turn)
    if (tokens <= remaining) { selectedReversed.addFirst(turn); remaining -= tokens }
    else { trimmed += turn }
}
// ...
// PromptContextPlanner.kt:119
val historyTokens = selectedReversed.sumOf { turnTokenCount(it) }
```

- `app/src/main/java/com/aliahad/aichat/attachment/AttachmentRepository.kt`:

```kotlin
// AttachmentRepository.kt:151-152
override suspend fun contextsForMessage(messageId: String, prompt: String): List<AttachmentContext> =
    dao.getForMessage(messageId).map { contextFor(it, prompt) }
```

```kotlin
// AttachmentRepository.kt:181-190 (abridged)
val chunks = dao.chunks(entity.id)
    .filter { selectedPages.isEmpty() || it.pageNumber == null || it.pageNumber in selectedPages }
    .sortedByDescending { chunk ->
        keywords.sumOf { keyword ->
            Regex("\\b${Regex.escape(keyword)}", RegexOption.IGNORE_CASE)
                .findAll(chunk.content).count()
        }
    }
    .take(MAX_CONTEXT_CHUNKS)
```

  The batched exemplar in the same file (lines 154–158):

```kotlin
override suspend fun contexts(ids: List<String>, prompt: String): List<AttachmentContext> {
    if (ids.isEmpty()) return emptyList()
    val byId = dao.getByIds(ids).associateBy { it.id }
    return ids.mapNotNull(byId::get).map { contextFor(it, prompt) }
}
```

- Call sites: `ChatTurnRunner.kt:127-134` (send: per-turn
  `contextsForMessage`) and `:391-395` (continue: per-message).
  `selectPages` mutates `selectedPages`/images at
  `AttachmentRepository.kt:123-135` — the invalidation trigger for any
  caching.

## Commands you will need

| Purpose | Command | Expected on success |
|---------|---------|---------------------|
| Unit tests | `./gradlew testDebugUnitTest` | exit 0 (incl. plan 006 characterization tests unchanged) |
| Detekt | `./gradlew detekt` | exit 0 |
| Build | `./gradlew assembleDebug` | exit 0 |

## Scope

**In scope**:
- `app/src/main/java/com/aliahad/aichat/memory/PromptContextPlanner.kt`
- `app/src/main/java/com/aliahad/aichat/attachment/AttachmentRepository.kt`
- `app/src/main/java/com/aliahad/aichat/ui/viewmodel/ChatTurnRunner.kt` (call sites only)
- `app/src/main/java/com/aliahad/aichat/data/Daos.kt` (batched chunk query if needed)
- `app/src/test/java/com/aliahad/aichat/memory/PromptContextPlannerTest.kt` (extend plan 006's file)

**Out of scope**:
- `turnTokenCount` internals / JNI counting.
- Any ranking-weight or `MAX_CONTEXT_CHUNKS` behavior change.
- Per-attachment relevance precompute persisted at processing time
  (deferred — bigger design).

## Steps

### Step 1: Memoize planner token counts

Restructure the fit loop to carry counts:

```kotlin
val selected = ArrayDeque<Pair<ChatTurn, Int>>()   // turn, tokens
for (turn in history.asReversed()) {
    val tokens = turnTokenCount(turn)
    if (tokens <= remaining) { selected.addFirst(turn to tokens); remaining -= tokens }
    else { trimmed += turn }
}
// ...
val historyTokens = selected.sumOf { it.second }
```

Adapt downstream uses of `selectedReversed` (the returned `ContextPlan.history`
maps back to turns). Plan 006's tests must pass UNCHANGED — they pin
behavior.

**Verify**: `./gradlew testDebugUnitTest --tests '*PromptContextPlanner*'` → all pass.

### Step 2: Hoist keyword regexes in `contextFor`

In `contextFor`, compile once per call:

```kotlin
val keywordRegexes = keywords.map { keyword ->
    Regex("\\b${Regex.escape(keyword)}", RegexOption.IGNORE_CASE)
}
// inside sortedByDescending:
keywordRegexes.sumOf { it.findAll(chunk.content).count() }
```

Zero behavior change; identical matching semantics.

**Verify**: `./gradlew compileDebugKotlin` → exit 0.

### Step 3: Batch the per-message attachment loads in the send path

1. Add to the DAO (if not already present from plan 011): a query
   `(messageId, attachmentId)` pairs for `WHERE messageId IN (:ids)`.
2. Add repository method:

```kotlin
override suspend fun contextsForMessages(
    messageIds: List<String>,
    prompt: String,
): Map<String, List<AttachmentContext>>
```

   implemented via the pairs query + `contextFor` per entity, grouped by
   messageId. Keep `contextsForMessage` delegating to it for any other
   callers (verify with grep).
3. Replace the loops at `ChatTurnRunner.kt:127-134` (send) and `:391-395`
   (continue) with one batched call.

**Verify**: `./gradlew compileDebugKotlin` → exit 0.

### Step 4: Full gate

**Verify**: `./gradlew testDebugUnitTest detekt assembleDebug` → exit 0,
and plan 006 characterization tests untouched and green.

## Test plan

- Extend `PromptContextPlannerTest` (plan 006): add a counting fake
  `InferenceEngine` that records `countTokens` invocations; assert the
  selection loop calls it exactly once per history turn per `plan()` call
  (no double traversal after Step 1).
- Regex hoisting is behavior-identical — covered by existing tests + any
  attachment-ranking assertions already present (`SkillLogicTest`,
  `CoreLogicTest`).

## Done criteria

- [ ] `./gradlew testDebugUnitTest` exits 0; plan 006 tests pass unchanged
- [ ] New counting test proves single traversal per turn
- [ ] `grep -n "contextsForMessage(message.id" app/src/main/java/com/aliahad/aichat/ui/viewmodel/ChatTurnRunner.kt` returns no matches
- [ ] `Regex("\\b${Regex.escape(keyword)}"` appears exactly once per `contextFor` call site (hoisted, not inside the sort lambda)
- [ ] `./gradlew detekt assembleDebug` exit 0
- [ ] `git status` shows only in-scope files
- [ ] `plans/README.md` status row updated

## STOP conditions

Stop and report back (do not improvise) if:
- Plan 006's characterization tests fail after Step 1 — the memoization
  changed behavior; STOP and report the diff.
- The pairs query requires a schema/index change — report; not authorized.
- `contextFor`'s `require(entity.state == READY)` throws for historical
  attachments when batched (previously each turn's failure aborted only
  that turn) — if semantics change, STOP and report.

## Maintenance notes

- If per-attachment ranking is later cached/persisted (deferred design),
  Step 2's hoist becomes moot but harmless.
- `selectPages` invalidation: this plan deliberately caches NOTHING across
  calls (only within one call), so there is no invalidation surface — keep
  it that way unless the persisted-precompute design lands.
