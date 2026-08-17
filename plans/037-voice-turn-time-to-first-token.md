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

### Why Steps 2 and 3 did not proceed

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
