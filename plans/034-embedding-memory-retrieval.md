# Plan 034: Rebuild memory retrieval on embeddings

> **Executor instructions**: Follow this plan step by step. Run every
> verification command and confirm the expected result before moving to the
> next step. If anything in the "STOP conditions" section occurs, stop and
> report — do not improvise. When done, update the status row for this plan
> in `plans/README.md`.
>
> **Drift check (run first)**:
> `git log --oneline -3 -- app/src/main/java/com/aliahad/aichat/memory/MemoryRepository.kt`

## Status

- **Priority**: P1
- **Effort**: L
- **Risk**: MED (adds a second model to the process; changes what the model sees)
- **Depends on**: 033 (do not embed rows that plan deletes)
- **Category**: quality / latency
- **Planned at**: commit `e4e1630`, 2026-08-16

## Why this matters

After `e4e1630` fixed KV-cache reuse, TTFT is essentially
`(memory block tokens) / 21 per second`. The memory block is therefore now the
whole latency story, and it is badly chosen:

- "Explain gravity briefly" retrieved **16/16** chat-message fragments of
  6–19 characters — rows whose content is literally `"4"`, `"26"`, `"22"`.
- Retrieval returns `limit = 16` on essentially every query regardless of
  relevance.

Root cause is scoring, not budget. `searchMemoryRows` computes a lexical score
(term overlap, phrase, recency, importance) and filters on
`lexicalScore > 0.08f` — but `floorExemptIds = (indexedIds + pinnedIds)`
exempts everything AppSearch returned, and AppSearch is asked for up to
`limit * 8` candidates. So the floor almost never applies.

Raising the lexical floor is **not** the fix and reviewers should not settle
for it: lexical overlap on a 3-word query against a 12-char memory is too
noisy a signal to threshold on at any value. A semantically meaningful score
is what makes a cutoff possible.

## Design

**Do not add a SQLite vector extension.** sqlite-vec/VSS means loading a
native extension alongside SQLCipher, which ships its own SQLite build with
extension loading typically disabled — high integration risk for no benefit
at this scale. Store vectors as a `BLOB` column on the existing memory table
and scan.

Scale check: 10,000 memories × 384 dims × 4 bytes ≈ 15 MB, and a brute-force
cosine pass over that is single-digit milliseconds. ANN indexes exist to avoid
scanning millions of rows; this app has thousands.

**Embedding model**: the vendored llama.cpp already exposes
`llama_get_embeddings_seq` and `llama_pooling_type` (`llama.h:1031`, `:171`) —
compiled in, currently unused by `aichat_jni.cpp`. Add a small GGUF embedding
model (EmbeddingGemma 300M, or all-MiniLM-L6 at ~90 MB / 384 dims) in its own
context. Embed each memory once on write; embed the query once per turn.

**Keep hybrid.** Pure vector search is weak on exact tokens (names, IDs, error
codes). Final score should combine cosine with the existing lexical signal
rather than replacing it.

## Scope

**In scope**: the embedding JNI surface, a vector column + migration, a
backfill, hybrid scoring with a real cutoff, and a token cap on the block.

**Out of scope**:
- Action-based tools (plan 035).
- Changing what gets *written* to memory beyond the triviality filter below.

## Steps

### Step 1: Baseline

Record, for a fixed set of ~6 questions, how many memories are injected and
how many tokens. This is the oracle for every later step.

**Verify**: numbers recorded before any change.

### Step 2: Stop memorising trivia

Chat messages like `"4"` and `"26"` must never become memories. Add a
minimum-substance filter at write time.

**Verify**: unit tests; a short numeric reply produces no memory row.

### Step 3: Embedding JNI

Load a second GGUF in embedding mode and expose "embed this text → float
array". Must not disturb the chat context — a shared context would evict the
KV cache that `e4e1630` exists to preserve, re-introducing the 29 s prefill.

**Verify**: instrumented test embedding two similar and two unrelated strings;
cosine(similar) > cosine(unrelated).

### Step 4: Store and backfill

Schema 18 → 19 adding the vector BLOB. Backfill existing memories in the
background, off the interactive path, resumable.

**Verify**: migration test; backfill leaves no ACTIVE memory without a vector.

### Step 5: Hybrid scoring with a real cutoff

Combine cosine with the lexical score. Apply a cosine floor and a top-k cap
(start k=4). Remove the blanket `floorExemptIds` exemption — an AppSearch hit
should be a *recall* mechanism, not a bypass of relevance.

**Verify**: "Explain gravity briefly" injects 0 memories; a question that
genuinely matches a stored fact still retrieves it. Both asserted in tests.

### Step 6: Cap the block and re-measure

Hard token cap on the assembled memory block (~192) as a backstop independent
of scoring.

**Verify**: repeat Step 1's six questions; record real before/after TTFT,
including a null result if it does not improve.

## Test plan

- Unit tests for the triviality filter, cosine, hybrid ranking, cutoff, cap.
- Migration tests for 18→19 and the backfill.
- Instrumented test for the embedding JNI on the device.
- On-device before/after TTFT on the same conversation.
- **Quality regression check**: confirm a question that *should* hit memory
  still does. A retrieval system that returns nothing is fast and useless.

## Done criteria

- [ ] Trivial chat fragments no longer become memories
- [ ] Vectors stored, backfilled, and used in ranking
- [ ] Irrelevant queries inject 0 memories; relevant ones still hit
- [ ] Memory block capped in tokens
- [ ] Chat KV-cache reuse still works (no regression of `e4e1630`)
- [ ] Before/after TTFT recorded honestly in `plans/README.md`

## STOP conditions

Stop and report (do not improvise) if:
- The embedding model cannot be loaded alongside the chat model within the
  device's memory budget without evicting chat KV cache. That trade would undo
  `e4e1630`; report before proceeding.
- Backfilling requires a foreground pass long enough to be user-visible.
- Hybrid scoring cannot be tuned to both drop the junk and keep true hits —
  report the tension rather than silently choosing recall or precision.

## Maintenance notes

- The invariant from `e4e1630` still governs: retrieved memories are volatile
  and must ride on `UserTurn.preamble`, never the system prompt. See
  `plans/README.md` and the comment in `PromptContextPlanner`.
