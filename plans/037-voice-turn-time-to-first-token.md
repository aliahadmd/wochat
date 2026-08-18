# Plan 037: Cut time to first token for voice turns

> **Executor instructions**: Follow this plan step by step. Run every
> verification command and confirm the expected result before moving to the
> next step. If anything in the "STOP conditions" section occurs, stop and
> report — do not improvise. When done, update the status row for this plan
> in `plans/README.md`.
>
> **Drift check (run first)**:
> `git log --oneline -5 -- app/src/main/java/com/aliahad/aichat/memory/PromptContextPlanner.kt`

## Status

- **Priority**: P1
- **Effort**: M
- **Risk**: LOW-MED (touches prompt assembly, which governs answer quality)
- **Depends on**: 034
- **Blocks**: 036 — agreed with the owner that this comes first
- **Category**: performance
- **Planned at**: commit `45da848`, 2026-08-17

## Why this comes before call mode

Measured end to end for voice: **~6 s from "user stops speaking" to first
audio**, of which **~5.2 s is the LLM's time to first token**. VAD, ASR and TTS
together are under a second.

Building the call UI first would produce a screen that works correctly and does
not feel good, and no amount of animation fixes a six-second gap. Call mode
built on a 2-3 s TTFT is a different product from one built on 6.

## What is already known, so nobody re-derives it

From `e4e1630` and the work after it, measured on the owner's Redmi K80 Pro
(Gemma 4 E4B, CPU backend):

- **"Prefill runs at ~21 tok/s" is right, but only when the weights are
  resident.** Prefill is not one rate. Measured on identical 63-token prompts:
  **2858 ms (22 tok/s) with 26 major page faults**, and **4981-5242 ms
  (12 tok/s) with 16 875-21 461 major faults**. A major fault is a read from
  storage. The rate is a function of how much of the 4.9 GB mapping the kernel
  has evicted since the last forward pass.
- **KV cache reuse already works.** A warm turn appends at the current position
  instead of re-decoding from 0. Do not break this — the invariant is that
  anything volatile stays out of the system prompt. Putting per-turn content in
  the stable prefix costs a full re-prefill and takes TTFT from ~5 s to ~30 s.
  (Confirmed: measured **33.4 s** for a 380-token replay, see below.)
- **The "missing half" of the 5.2 s is page eviction.** It was never a separate
  phase. It is the same prefill and the same first token running at half speed
  or worse because the weights had to be re-read from storage. This is the
  headline result of Step 1 and it is not something prompt trimming addresses.

## Step 1 results (measured 2026-08-17, release build, Redmi K80 Pro)

Method: `TurnTrace` (JVM phase marks, `AIchatTurn` tag) plus the existing
`AIchatNative` logs, over 15 turns. Attribution of a **warm** turn (KV reuse
accepted), in a process behaving normally:

| Phase | Cost | Share of a 1.3 s turn |
|-------|------|-----------------------|
| setup, history load (DB reads) | 0-2 ms | <1 % |
| `residencyController.ensureLoaded` | 0-1 ms | <1 % |
| **prompt planning** (`PromptContextPlanner.plan`) | **31-62 ms** | ~3 % |
| — of which the query embedding | 11-18 ms | ~1 % |
| — of which ~8 `countTokens` calls | 0-5 ms total | <1 % |
| persist user + assistant rows | 1-3 ms | <1 % |
| `restoreSession` (reuse hit) | 1-2 ms | <1 % |
| **prefill** | **45 ms/token resident, 80-170 ms/token evicted** | **86 %** |
| **first generated token** | **80-206 ms resident, seconds when evicted** | ~10 % |

Steady-state generation afterwards: 81-117 ms/token (8.5-12 tok/s) on a settled
device, degrading to ~150-220 ms/token under the sustained load of a long answer.

Best warm turns measured end to end: **1222 ms** and **1253 ms** — both already
inside the plan's 2-3 s target. The same shape of turn measured **5.5-6.1 s**
when the weights had been evicted. That spread, not the prompt, is the story.

**The embedder is exonerated.** The suspect named in Step 1 costs 11-18 ms per
turn — about 1 % of TTFT. Prompt planning as a whole never exceeded 191 ms.

The 2-3 s target is already met when the prompt is short *and* the weights are
resident. The plan's lever (fewer prompt tokens) is real and priced below, but it
is the second-largest term — see the eviction section.

### What a retrieved memory costs (the Step 2 lever, measured)

Same conversation, same process, Memory chip toggled:

| Condition | Prompt tokens | Prefill | Turn total |
|-----------|---------------|---------|------------|
| Memory ON | 63-68 | 5.34-5.39 s | ~5.6 s |
| Memory OFF | 13 | 1.43 s | ~1.6 s |

A ~50-token memory preamble cost **~3.9 s of TTFT**. At the current caps
(4 memories, 192 tokens) the worst case is ~17 s of prefill from memory alone.
Retrieval is also less selective than assumed: "Name one animal." pulled a
~50-token preamble.

### The dominant term: the kernel evicts the model between turns

This is the finding the plan was missing, and it outweighs everything else here.

The 4.9 GB model is a file-backed `mmap`. While the app is idle the kernel
reclaims those pages, so the next forward pass re-reads the evicted weights from
storage. Measured with `getrusage` fault counters around each decode:

| Prompt | Prefill | minor faults | **major faults** |
|--------|---------|--------------|------------------|
| 63 tokens | **2858 ms** | 9 162 | **26** |
| 63 tokens | **4981 ms** | 65 093 | **16 875** |
| 63 tokens | **5242 ms** | 67 116 | **21 461** |
| 63 tokens | **5064 ms** | 60 447 | **21 281** |

Identical prompt size, ~2x the time, and the difference is ~21 000 reads from
storage (~84 MB) per turn. `RssFile` measured **2.0 GB immediately before** one
of those turns and **2.9 GB immediately after** — the turn faulted ~856 MB of
weights back in. The model never reaches full residency; the kernel keeps
trimming it.

The app is **not** the culprit: exactly one "Released N MiB of file-backed model
pages" appears in the whole session, the deliberate one at model load.
`restoreSession`'s existing comment already warned about the app-initiated
version of this; what remains is the kernel doing it unbidden.

This subsumes the "unexplained first token" seen earlier in the session (3.7-7.5 s
on token 1 while token 32 of the same generation cost 155 ms): the first pass of
a turn pays the eviction bill, later passes run on warm pages. Sampling was
measured at ≤3 ms throughout, so it was never the sampler.

### Warming the pages was tried and it made things far worse — do not retry naively

The obvious consequence of the above is that a turn has a free warming window
(the user typing, or speaking in call mode), so the evicted pages could be read
back off the critical path. That was implemented and measured, and the result is
**strongly negative**.

Implementation: `madvise(MADV_WILLNEED)` over every VMA of the model file
(the mirror of the existing `release_file_pages`), issued from the composer's
`setInput` on a 20 s throttle, dispatched to IO. Issuing it is genuinely cheap —
"Warm requested for 4901 MiB ... in 7 ms".

Then the same conversation replayed, with and without it:

| Prompt tokens | With warming | Without (control) |
|---------------|--------------|-------------------|
| 7 | 4852 ms | **419 ms** |
| 9 | 5391 ms | **536 ms** |
| 12 | 5438 ms | **~540 ms** |
| 745 | 55 785 ms | **36 154 ms** |
| first token | ~5000 ms | **107 ms** |

**Up to 11x slower.** Advising 4.9 GB of readahead on a device that cannot hold
4.9 GB starts a read/evict storm that competes with the decode for memory
bandwidth; small decodes suffer worst because the fixed cost swamps them. Note
the faults during the slow decodes were ~0, so the decodes were not themselves
waiting on storage — they were being starved by the kernel's background work.

The code has been removed rather than left unwired: an unused primitive that
halves throughput if someone wires it up is worse than a documented result.

If anyone retries this, it must be **bounded and rate-limited** — a slice of the
mapping, or `readahead()` on a byte range, throttled well below the device's
bandwidth — and it must be measured against the control column above, not
against intuition.

### A fixed per-decode cost is the real target

The control run also sharpens what the slow regime actually is. Fitting the
"with warming" column: cost ≈ **4.5 s fixed per `llama_decode` call + ~70 ms per
token**. A 7-token decode and a 12-token decode cost the same, because both are
dominated by the fixed term. That is the same shape as the "first token costs
3.7-7.5 s while token 32 costs 155 ms" observation from earlier in the plan: it
was never about the first token, it is a per-`llama_decode` cost that appears
when the app is in the bad regime, and a continuous generation only pays it once
because its decodes are back to back.

In the healthy regime the fixed term is absent: prefill is a clean ~50-60 ms per
token from 7 tokens up to 745, and the first token costs 107 ms.

**This is what `036` should be built against, and it is not a prompt problem.**
Finding the trigger needs a scheduler-level tool (Perfetto, and a look at whether
HyperOS is parking the inference threads between decodes), not more logging.

### FIXED: the app was discarding the mapping it had just paid for — 2026-08-18

`load_model()` ended with `release_model_file_pages()`, an unconditional
`MADV_DONTNEED` over the whole model mapping, to "leave HyperOS room for projector
work". But llama.cpp maps the entire file with **`MAP_POPULATE`** already
(`llama-mmap.cpp:455`; `llama_mmap`'s `prefetch` defaults to the whole file). So the
loader was throwing away a fully populated mapping and then re-faulting it, one
decode at a time, during the user's first turn.

Removing that one call, measured identically on a fresh process:

| | before | after |
|---|---|---|
| first 22-token prefill | **7511 ms**, 52 769 minor faults | **2013 ms**, 2 088 |
| **first generated token** | **4109 ms** | **164 ms**, then **97 ms** next turn |

**The first-token penalty is gone** — 164 ms is ordinary steady-state speed, reached
immediately rather than after seconds of mapping. That penalty was the single
largest term in voice-call latency.

The two releases with an actual reason are kept: before projector initialisation,
which genuinely competes for the same budget, and `onTrimMemory`, which is real
memory pressure. `RssFile` settled at 3.27-3.36 GB and the process stayed alive
across turns, so holding the mapping did not trip HyperOS's reclaim.

This also retires the "warming" idea entirely. The pages never needed hinting,
prefetching or touching — they only needed to not be thrown away.

### The regime is page-table warm-up, not throttling — 2026-08-18

The owner supplied the reproduction condition this needed: *"if I close the app and
open it, it's fast... sometimes it generates fast and sometimes it takes too long"*.
Measured on a fresh process, with CPU sampled from `/proc/<pid>/stat` throughout:

| decode | time | minor faults |
|--------|------|--------------|
| 22 prompt tokens | **7511 ms** | **52 769** |
| 13 prompt tokens | **5099 ms** | **9 126** |
| generated token 1 | **4109 ms** | 226 |
| generated token 32 | **164 ms** | 0 |
| generated token 64 | **170 ms** | 1 |
| generated token 128 | **172 ms** | 104 |

**The app is never starved.** It held ~2530 CPU ticks per 5 s window — 5.06 cores
saturated — for the whole run, in the `top-app` cpuset. So the slow decodes are not
throttling, not the scheduler, and not HyperOS restricting a long-running app.

They are **minor** faults: pages already in the page cache being mapped into this
process's page tables for the first time. The opening decodes map ~250 MB and cost
4-7 s; once mapped, every token costs ~170 ms and faults fall to zero.

That explains the whole bimodal pattern recorded above, and corrects the emphasis of
the eviction section: **major** faults (storage reads) do occur and do cost time, but
the dominant, reproducible cost at the start of a session is the *minor*-fault
mapping of an mmap'd 4.9 GB model. It also explains why the earlier
`MADV_WILLNEED` experiment made things worse rather than better — that hints
readahead into the page cache, which was not the missing step. The pages were
usually already cached; they were not *mapped*.

**What to try next, in order.** None of this is audio work:
1. `MAP_POPULATE` at mmap time, or a rate-limited sequential touch of the mapping on
   a background thread once the model is resident, so the mapping cost is paid while
   the user is opening the app rather than mid-turn.
2. Measure `llama_model_params.use_mlock`, which llama.cpp already exposes.
3. Only then reconsider warming hints, and only bounded — see the refuted attempt.

Anything here must be measured against the numbers in this table, not against
intuition: the last attempt looked obviously right and was 11x slower.

### Two smaller findings

1. **Thermal throttling is real but modest.** Across a sustained generation the
   CPU went 41 °C -> 92 °C and per-token time drifted 81 ms -> ~105 ms, about
   25 %. It is not the explanation for the 2-4x swings; eviction is.
2. **The Memory chip invalidates the KV prefix.** Toggling it mid-conversation
   changes the system prompt (`MEMORY_DISABLED_NOTICE` is appended there), which
   declines session reuse and forces a full replay — **33.4 s measured** on a
   380-token conversation. Same trap the plan warns about, reachable from the UI
   in one tap.

Also worth carrying into `036`: the first turn after app start replays the whole
conversation (15.4 s for ~170 tokens, 33.4 s for ~380), so the first turn of a
call pays a cold-session cost that no prompt trimming touches.

### The memory block was mostly wrapper — 2026-08-18

Following the Step 2 result, retrieval selectivity was investigated and **found not
to be the problem**. A general-knowledge question spoken into a call — "can you tell
me 10 African countries names?", 40 characters — carried **19 prompt tokens** and
finished in **1237 ms**; the next turn 14 tokens and 692 ms. The relevance floor
correctly retrieved nothing. The earlier "Name one animal." observation was a
near-duplicate of a past user message stored as an episode, not a loose floor.

The real waste was in how a memory is *packaged*:

| part of the block | size |
|---|---|
| header | 143 chars, ~35 tokens |
| `- [preference; source: Chat message] ` | 37 chars, ~9 tokens |
| **the fact itself** | **30 chars, ~7 tokens** |

**45 tokens of wrapper around 7 tokens of fact.** A compact turn now uses a short
header that keeps the one instruction which changes answers — that this is the
user's own context, not something the model knows — and drops the type and source,
which exist for the Memory screen rather than for the model.

Measured on device, same question, fresh conversation so the fact had to come from
retrieval: **67 -> 39 prompt tokens**, and the answer was still "Yes, you prefer
green tea after lunch." with `Memory · 1`. This costs no recall at all, because the
facts are untouched.

**Caveat on that run's clock, so nobody quotes it as a latency win**: first audio
took 71.8 s, with `major=640` and `847` faults. Fewer tokens, but each paid storage
latency. The token count is the clean signal here; the timing measured eviction.

### Major faults are back, and the mapping fix does not cover them

The load-path fix above cured *minor* faults — mapping pages already in the page
cache. It cannot stop the kernel evicting the pages themselves, and eviction is now
more likely, not less: the model holds ~5 GB mapped and no longer releases it, while
Whisper (208 MB), Piper and the embedder compete for the same memory. Turns
alternate between ~70 ms/token and ~210 ms/token accordingly.

That is the next thing worth attacking, and `use_mlock` is the obvious candidate to
measure — with the caveat that locking ~5 GB on a phone may simply fail or provoke
the low-memory killer, which is exactly why it needs measuring rather than assuming.

### mlock is impossible on this device, and faults were the wrong suspect — 2026-08-18

Two null results in one sitting, both worth writing down so nobody re-derives them.

**`use_mlock` cannot work here.** `/proc/<pid>/limits` on the Redmi K80 Pro:

    Max locked memory   65536   65536   bytes

64 KB, and the *hard* limit is 64 KB too. An unprivileged process may only lower
its hard limit; raising it needs `CAP_SYS_RESOURCE`, which an Android app never
has. Setting `use_mlock = true` would ask llama.cpp to lock 4.8 GB against a 64 KB
ceiling, take `ENOMEM`, log a warning and behave exactly as before. No code was
written. Do not try this again.

**Page faults do not explain the slow tokens.** Four consecutive samples from one
generation:

    token 800  236 ms   major=49
    token 832  247 ms   major=35
    token 864  205 ms   major=3
    token 896  217 ms   major=6

The fault count moves 16x while the time barely moves. Token 864 took 205 ms with
**three** major faults — well under a millisecond of I/O. Across a full 832-token
generation, decode held ~210-280 ms/token from position 1017 to position 1817 with
major faults flat at ~29 per sample. Generation is simply ~4.3 tok/s on this device.
It is not fault-bound and it is not meaningfully context-depth-bound.

### The real variable is whether the KV cache survived — 2026-08-18

Same app, same conversation, two consecutive turns:

| | cache lost | cache warm |
|---|---|---|
| `restore` | **72,624 ms** | **5 ms** |
| `prefill` | 6,144 ms | 15,857 ms |
| `first-token` | 7,682 ms | 232 ms |
| total | 86.9 s | 16.3 s |

`restore` is 72.6 s or 5 ms with nothing in between. That is not a paging gradient,
it is a binary: either the in-RAM KV cache survived, or the entire conversation is
re-decoded from scratch. The cold turn re-decoded 929 history tokens in 68.6 s
(13.5 tok/s).

`restoreSession()` already reuses a valid prefix in RAM and skips on an unchanged
fingerprint — that is why the warm number is 5 ms. What it cannot survive is a
**model reload**, and the model reloads whenever HyperOS trims the app while it is
backgrounded. Confirmed: sitting behind another app cost 700 MB of mapped model
pages (4.19 GB -> 3.50 GB) and pushed swap 667 MB -> 772 MB, and a reload
("Model weights loaded ... in 3784 ms") landed immediately on return.

This is exactly the owner's report — "if I close this app and then open it, it's
fast, but when you close this app suddenly and open it, it's not fast." It was never
about the audio and never about the prompt.

**The fix that follows:** `llama_state_seq_save_file` / `llama_state_seq_load_file`
are vendored in `llama.cpp/include/llama.h` and the app never calls them. Persisting
the sequence state and reloading it turns a 72.6 s re-decode into a file read.
Not yet built, and the state file must be sized by measurement
(`llama_state_seq_get_size`) rather than estimated — context is 4096, and Gemma's
interleaved sliding-window attention makes any hand estimate unreliable.

Note the coupling: the state is only valid for the exact token prefix it was saved
against, so this raises the stakes on [[wochat-prompt-prefix-must-stay-stable]] —
any drift in the system prompt or memory block invalidates the file as surely as it
invalidates the live cache.

### The KV cache now survives a model reload — 2026-08-18

`llama_state_seq_save_file` / `llama_state_seq_load_file` were vendored and never
called. They are now, and the 72.6 s re-decode is gone.

Measured on the device, release build, with `am force-stop` between the two turns so
the model genuinely reloaded:

    turn 1   Session saved: 130 tokens, 7.1 MB in 6 ms
    force-stop, relaunch, Model weights loaded in 2091 ms
    turn 2   Session loaded: 130 tokens, 7.1 MB in 6 ms
             restore=8ms

**restore: 72,624 ms -> 8 ms** across a process death. The sequence costs ~56 KB per
token, so a full 4096-token context is ~225 MB, and writing it is single-digit
milliseconds — it lands in page cache, not on the UFS critical path.

Design points worth keeping:

- **Only the most recent conversation is kept**, in `cacheDir`. Per-conversation
  history would trade a latency problem for a storage one, and the system clearing
  the cache is a supported outcome: a missing file costs exactly what today cost.
- **Media conversations decline to save.** Image chunks occupy positions without
  being text tokens, so `session_tokens` could not honestly describe the sequence.
  A length that disagrees with its contents would restore a corrupt session.
- **The save is gated by the existing prefix check.** `persistSession` refuses
  unless `nativeSessionPrefixLength` reports the cache holds every message being
  described, so a descriptor can never claim more than the cache contains.
- **Descriptor is written after the sequence**, so a crash mid-save leaves an
  ignored file rather than a descriptor pointing at a truncated one.

Two build traps this hit, both invisible to the gate:

- The `kotlinx-serialization` **compiler plugin was never applied** — only the
  runtime library was a dependency, so `@Serializable` generated nothing and the
  first release run failed with "Serializer for class 'xp2' is not found". Unit
  tests could not catch it: they exercise the same code with the plugin equally
  absent, and the failure only appears where a serializer is actually resolved.
- Serializers are now **named explicitly** rather than reified, so R8 renaming
  cannot break the lookup.

### Conversation history is ignored whenever a memory is retrieved — 2026-08-18

Found while verifying the above, and worse than the latency bug it was hiding behind.

Same conversation, same question, only the Memory toggle differing:

| Memory | Answer to "Which of those three rivers is the longest?" |
|---|---|
| on | "The personal office memory you provided does not contain a list of rivers, so I cannot tell you which of those three is the longest." |
| off | "The Ganges is generally considered the longest among the three rivers you listed (Ganges, Brahmaputra, Meghna)" |

The log for the failing turn reads `Session reuse accepted: 10 of 10 history messages
already decoded` — the history was in the KV cache the whole time. The model was not
missing the context, it was declining to look at it.

The cause is the preamble's own wording, which the model quoted back:

    Personal Office Memory follows. Treat it as user-owned context, prefer
    corrected or pinned items, and do not claim it came from model training.

Attached immediately before the user's message, that reads as a declaration of what
context *is*, so the model scopes its answer to the memory block and treats the
conversation above as absent. With memory enabled by default, every multi-turn
conversation that retrieves anything is effectively single-turn.

**Fixed by rewording both headers** to say outright that the conversation is still
there, rather than implying the block is all there is:

    Notes about the user from earlier sessions. They add to the conversation
    above, never replace it. Prefer corrected or pinned items, and do not
    present them as training knowledge.

Re-measured on the device with memory back on and a memory actually retrieved
(`Memory · 1`), same conversation, same question:

| | answer |
|---|---|
| before | "The personal office memory you provided does not contain a list of rivers" |
| after | "The Ganges is generally considered the longest among the three rivers you listed (Ganges, Brahmaputra, Meghna)" |

— word for word what the memory-*off* control produced, so history is fully back.
And recall itself still works in the same session: "what is my favourite drink?"
returns "Your favorite drink is green tea after lunch." The compact header keeps
the "not from training" instruction, which a unit test protects: the first attempt
dropped it and the test caught it.

Worth noting for anything else that touches these strings: this failure is invisible
to the whole gate. Nothing in unit tests, lint, detekt or the build can see a prompt
that quietly changes what the model pays attention to. Only an A/B on the device
found it, and only because a restore that *looked* successful produced an answer
that was not.

### Step 2 DONE, with a partly negative result — 2026-08-18

Unblocked once `036` introduced `TurnOrigin.VOICE`. A spoken turn now plans with
`ContextBudget.COMPACT`: 2 retrieved memories instead of 4, a 96-token instead of
192-token memory block, and no conversation summary.

**One of this plan's three suggestions was rejected on measurement grounds.** A
"terser system prompt in voice mode" would change the *stable KV prefix*, so any
conversation mixing typed and spoken turns would decline session reuse and
re-prefill everything on each switch — trading ~4 s for ~30 s. Both budgets share
one system prompt, and a unit test pins that.

**Verified on device, in a fresh conversation so the fact could only come from
retrieval**: "Do I like to drink after lunch?" -> "Yes, you prefer green tea after
lunch.", with the composer reporting `Memory · 1`. Recall survives the cut, which is
this plan's STOP condition.

**But the latency win is smaller than the framing suggested, and the honest reason
is that the cap rarely binds:**

| | prompt tokens | prefill |
|---|---|---|
| before (FULL) | 63-68 | 5.34 s |
| after (COMPACT) | **67** | **2.73 s** |

The token count barely moved. The prefill halved because of the mapping fix above
(`minor=1808 major=0`, against tens of thousands before), **not** because of the
budget. Retrieval had found only one relevant memory, so a cap of 2 never applied.

The compact budget is therefore a **guard on the worst case** — 4 memories at the
192-token ceiling is ~17 s of prefill — rather than a saving on the common one. It
is worth keeping for that, and for skipping the summary, but it is not the lever.

**The lever is retrieval selectivity.** The evidence was already in this plan and
was under-weighted: *"Name one animal."* pulled a ~50-token memory preamble, so a
question needing no memory pays ~2-5 s of prefill for one. Tightening the relevance
floor would cut tokens on turns that should retrieve nothing *and* improve answers
by not injecting irrelevant context — it helps both sides of the trade, which is
rare here. That is the next thing to do, and it must be measured against the
cross-conversation recall check above so a tighter floor cannot silently break it.

### Why Steps 2 and 3 did not proceed (superseded above for Step 2)

Both are specified as *voice-specific* — "fewer retrieved memories for voice",
"a terser system prompt in voice mode", "lower `maxNewTokens` for voice turns".
**There is no voice turn to specialise.** `TurnOrigin` has exactly one value,
`TYPED`, and the voice path is built by `036`, which is unimplemented. Adding a
`TurnOrigin.VOICE` that nothing sets and no measurement can exercise would be
speculative scaffolding, and applying the trims to *every* turn is the silent
quality trade this plan's own STOP condition forbids. The lever is priced above;
spending it belongs with `036`.

## Steps

### Step 1: Find out where the 5.2 s actually goes

Do not skip to trimming the prompt. Instrument one warm turn and attribute the
time: prompt planning (memory search, embedding the query, tokenizing), the
native decode itself, and everything between the send tap and the first native
call.

The embedder now runs a forward pass per query on the interactive path — that is
a prime suspect and did not exist when the 5.2 s figure was first measured.

**Verify**: a written breakdown summing to the measured TTFT, in the status row.

### Step 2: Only then, trim what a voice turn prefixes

Voice utterances are short, so a voice turn should already be cheaper than a
typed one. Candidates, in the order they are likely to pay off:

- fewer retrieved memories for voice specifically (4 is the current cap)
- a terser system prompt in voice mode
- skip the conversation summary for voice turns

Each of these trades context for latency, so change one at a time and keep the
measurement from Step 1 attached to each.

**Verify**: TTFT re-measured after each change, not just at the end.

### Step 3: Cap reply length in voice mode

A spoken answer wants to be shorter than a written one regardless of latency.
Lower `maxNewTokens` for voice turns and instruct brevity in the voice system
prompt.

This does not improve TTFT — it improves the *conversation*. Keep it separate in
the reporting so it is not confused with a latency win.

**Verify**: replies are audibly shorter; TTFT unchanged by this step alone.

### Step 4: Re-measure honestly

Same method as `e4e1630`: `adb logcat -s AIchatNative`, read
`Decoding N prompt tokens at position P` and `Generated token 1`.

**Verify**: before/after in `plans/README.md`, including a null result if the
levers did not move it.

## Test plan

- Unit tests for any change to `PromptContextPlanner` — it has good coverage and
  several tests assert exact prompt content.
- **Quality regression check**: a question that should hit memory must still hit
  it. A faster assistant that forgot the user's name is not an improvement; that
  exact failure already happened once and is regression-tested.
- On-device before/after TTFT on the same conversation.

## Done criteria

- [x] The 5.2 s is attributed, not assumed — it is prefill, at ~90 ms/token
- [~] Voice turns measurably faster — **not attempted**: there is no voice turn
      yet (see "Why Steps 2 and 3 did not proceed"). The lever is priced instead
- [x] KV-cache reuse still works — `Session reuse accepted` on every warm turn,
      restore 1-2 ms
- [x] Memory recall still works — "What do I like to drink after lunch?" ->
      "You like green tea after lunch.", recalled in a *different* conversation
      from the one it was stated in
- [x] Unit + lint + detekt green; `ChatViewModelInstrumentedTest` 14/14 on device
      (the class covering `ChatTurnRunner`). The **full 57 was not completed**:
      it died with `Process crashed` on `AppInstrumentedTest`, the MIUI
      background-activity-start restriction documented in `032`. Gradle
      reinstalled `com.aliahad.aichat.debug`, which resets MIUI's "Display
      pop-up windows while running in background" — a human has to re-grant it
- [x] Real numbers in `plans/README.md`

## STOP conditions

Stop and report (do not improvise) if:
- The breakdown in Step 1 shows prefill is a minority of the time. Then the
  prompt is the wrong target and the plan needs rewriting around whatever
  dominates.
- Trimming context measurably degrades answers. Latency is not worth a worse
  assistant; report the trade rather than picking silently.
- Any change breaks KV-cache prefix reuse. That regression is worth more than
  every gain in this plan combined.

## Maintenance notes

- Target worth aiming at: **2-3 s**. Below that the call feels responsive; at 6 s
  it does not.
- If this lands well, revisit `036`'s latency table — it is written around ~6 s
  and its STOP condition references that figure.
