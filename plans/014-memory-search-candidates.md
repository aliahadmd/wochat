# Plan 014: Pre-filter memory-search candidates with AppSearch hits and batch source lookups

> **Executor instructions**: Follow this plan step by step. Run every
> verification command and confirm the expected result before moving to the
> next step. If anything in the "STOP conditions" section occurs, stop and
> report — do not improvise. When done, update the status row for this plan
> in `plans/README.md` — unless a reviewer dispatched you and told you they
> maintain the index.
>
> **Drift check (run first)**: `git diff --stat 2dff7e6..HEAD -- app/src/main/java/com/aliahad/aichat/memory/ app/src/main/java/com/aliahad/aichat/data/MemoryDaos.kt`
> If any in-scope file changed since this plan was written, compare the
> "Current state" excerpts against the live code before proceeding.

## Status

- **Priority**: P3
- **Effort**: M
- **Risk**: MED (changing candidate selection changes which memories can surface; the fallback design keeps behavior when AppSearch is empty)
- **Depends on**: none (but run AFTER 006 if possible — planner tests share the package)
- **Category**: perf
- **Planned at**: commit `2dff7e6`, 2026-08-15

## Why this matters

Every user turn's memory search deserializes up to 500 memory rows and
1,000 activity-event rows (full content + metadataJson) over the encrypted
SQLCipher DB, then ranks them in Kotlin — regardless of the query. On top,
each of up to 24 ranked hits triggers a per-row `sources()` query (N+1).
The AppSearch index is already queried first (`searchIds`) but its result
is only used as a score boost, not as a filter. On a phone with a large
memory DB this adds a fixed, growing cost to every send.

## Current state

- `app/src/main/java/com/aliahad/aichat/memory/MemoryRepository.kt` —
  `search` (lines 111–190):

```kotlin
// MemoryRepository.kt:111-118 (abridged)
override suspend fun search(query: MemoryQuery): List<MemoryHit> {
    val normalized = normalize(query.text)
    val terms = normalized.split(' ').filter { it.length > 1 }.toSet()
    val indexedIds = runCatching {
        indexer.searchIds(normalized, query.limit.coerceIn(1, 24) * 4)
    }.getOrDefault(emptyList())
    val indexRanks = indexedIds.withIndex().associate { it.value to it.index }
    val candidates = dao.candidates(query.includePrivate, 500)
```

```kotlin
// MemoryRepository.kt:151-153
val memoryHits = ranked.map { rankedRow ->
    val row = rankedRow.row
    val sources = dao.sources(row.id).map(MemorySourceEntity::toDomain)
```

```kotlin
// MemoryRepository.kt:166-167
val activityIntent = activityRetrievalIntent(query.text, now)
val rankedActivityHits = database.activityDao()
    .retrievalCandidates(query.includePrivate, 1_000)
```

  Scoring weights (lines 124–140): overlap ×0.48, phrase 0.35, index boost
  0.28, pinned 0.25, minors for importance/confidence/recency. Activity
  per-source quota 3/8 (lines 176–186). DAO `candidates` query at
  `MemoryDaos.kt:54-59` filters only status/sensitivity.

- The `indexer.searchIds` call already returns memory ids relevant to the
  query — currently used ONLY for `indexRanks` boosting.

## Commands you will need

| Purpose | Command | Expected on success |
|---------|---------|---------------------|
| Unit tests | `./gradlew testDebugUnitTest` | exit 0 |
| Detekt | `./gradlew detekt` | exit 0 |
| Build | `./gradlew assembleDebug` | exit 0 |

## Scope

**In scope**:
- `app/src/main/java/com/aliahad/aichat/memory/MemoryRepository.kt`
- `app/src/main/java/com/aliahad/aichat/data/MemoryDaos.kt` (candidate filter + batched sources)
- `app/src/test/java/com/aliahad/aichat/memory/MemorySearchTest.kt` (new)

**Out of scope**:
- Scoring weights and the ranking formula (frozen — see STOP conditions).
- AppSearch indexer internals.
- The activity-events side beyond reducing its candidate count via the
  same pattern IF trivially expressible; otherwise leave `retrievalCandidates`
  alone.

## Steps

### Step 1: Pre-filter memory candidates by indexed ids when available

Behavior-preserving design with an explicit fallback:

```kotlin
val candidates = if (indexedIds.isNotEmpty()) {
    // Wider than the top-N because lexical scores can outrank index rank;
    // pinned memories must also stay eligible.
    dao.candidatesIn(query.includePrivate, (indexedIds + pinnedIds))
} else {
    dao.candidates(query.includePrivate, 500)   // unchanged fallback
}
```

Where `pinnedIds` come from a new tiny DAO query
(`SELECT id FROM memory_items WHERE pinned = 1 AND status = 'ACTIVE'` —
match the actual column names in `MemoryDaos.kt`). `candidatesIn` takes an
id list and applies the SAME status/sensitivity filters as `candidates`.
Pinned memories are few, so this keeps the pinned-boost pathway intact
while the indexed set covers the query-relevant rows.

**Verify**: `./gradlew compileDebugKotlin` → exit 0.

### Step 2: Batch the `sources()` lookups

Add to `MemoryDaos.kt` a query `sourcesFor(memoryIds: List<String>):
List<MemorySourceEntity>` joining on `memoryId IN (:ids)` (read the
existing `sources(id)` SQL one screen away and adapt). In `search`,
replace the per-hit loop with one call, grouped by `memoryId`, before
building `MemoryHit`s.

**Verify**: `./gradlew compileDebugKotlin` → exit 0.

### Step 3: Unit tests

New `MemorySearchTest.kt` with in-memory fakes for the DAOs/indexer
(follow `MemoryLogicTest.kt`'s conventions in the same package). Pin the
invariants:
1. Pinned memory is returned even when not in the indexed id set.
2. SECRET sensitivity never returned (existing filter preserved).
3. When `indexedIds` is empty (AppSearch unavailable), the 500-row fallback
   runs and returns the same results as the old path for the fake data.
4. Phrase match outranks pure overlap (weights frozen).
5. Per-source activity quota still caps at 3 (multi-source) / 8 (single).
6. `sources` batch returns identical mapping to per-row lookups.

**Verify**: `./gradlew testDebugUnitTest --tests '*MemorySearch*'` → all pass.

### Step 4: Full gate

**Verify**: `./gradlew testDebugUnitTest detekt assembleDebug` → exit 0.

## Test plan

See Step 3 — the ranking-weight freeze tests double as the
characterization net this high-churn area lacks (deferred finding TEST-05
is partially delivered here).

## Done criteria

- [ ] `./gradlew testDebugUnitTest` exits 0 with the new tests
- [ ] Indexed-path + pinned-union + fallback implemented; fallback
      identical to old behavior
- [ ] Exactly ONE `sources` query per `search` call
- [ ] `./gradlew detekt assembleDebug` exit 0
- [ ] `git status` shows only in-scope files
- [ ] `plans/README.md` status row updated

## STOP conditions

Stop and report back (do not improvise) if:
- Any ranking invariant from Step 3 changes versus current behavior — the
  filter is leaking semantics; report the failing case.
- The DAO schema lacks a `pinned` column of the assumed shape — read
  `MemoryDaos.kt`/`Entities.kt` and adapt, or STOP if pinned eligibility
  can't be cheaply preserved.
- AppSearch `searchIds` proves unreliable (returns ids not in Room) in the
  fakes — note that `candidatesIn` simply won't match them; that's safe,
  but if it returns STALE ids the fallback correctness depends on Room
  filtering — verify with a fake returning a nonexistent id (should be
  silently dropped).

## Maintenance notes

- The activity-events side (1,000 rows) still loads fully — if profiling
  after this plan shows it matters, apply the same indexed-filter pattern
  to `retrievalCandidates`.
- When ranking weights are deliberately retuned in the future, update the
  Step 3 freeze tests in the same PR — they are now the guardian of the
  product behavior.
