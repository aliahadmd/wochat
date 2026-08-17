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

- **Prefill runs at ~21 tok/s.** So `TTFT ≈ (tokens decoded this turn) / 21`.
  That single relation is the whole lever: fewer tokens in, less waiting.
- **KV cache reuse already works.** A warm turn appends at the current position
  instead of re-decoding from 0. Do not break this — the invariant is that
  anything volatile stays out of the system prompt. Putting per-turn content in
  the stable prefix costs a full re-prefill and takes TTFT from ~5 s to ~30 s.
- **A warm turn measured 54 prompt tokens -> ~5.2 s.** Note the implication:
  54 tokens at 21 tok/s is ~2.6 s, so **roughly half the 5.2 s is not prefill**.
  Find out what it is before optimising the half that is already understood.

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

- [ ] The 5.2 s is attributed, not assumed
- [ ] Voice turns measurably faster, or a recorded null result
- [ ] KV-cache reuse still works — check for `Session reuse accepted` in logcat
- [ ] Memory recall still works for a question that should hit
- [ ] Unit + lint + detekt green
- [ ] Real numbers in `plans/README.md`

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
