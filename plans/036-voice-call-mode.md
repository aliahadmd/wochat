# Plan 036: Offline voice call mode

> **Executor instructions**: Follow this plan step by step. Run every
> verification command and confirm the expected result before moving to the
> next step. If anything in the "STOP conditions" section occurs, stop and
> report — do not improvise. When done, update the status row for this plan
> in `plans/README.md`.
>
> **Drift check (run first)**:
> `git log --oneline -3 -- app/src/main/java/com/aliahad/aichat/ui/viewmodel/ChatTurnRunner.kt`

## Status

- **Priority**: P2 (new capability, nothing is broken without it)
- **Effort**: XL — the largest single feature in the backlog
- **Risk**: MED-HIGH (second inference runtime, audio hardware, a new permission)
- **Depends on**: 034 (turns already run on the process scope, which a call needs)
- **Category**: feature
- **Planned at**: commit `2bca40c` (v1.2.1), 2026-08-17
- **Requested by**: the owner. English only, fast, real-time, fully offline.

## Why this matters, and the one number that decides the design

The owner wants to talk instead of type: hold a call, speak, and hear the model
answer, entirely offline.

**The speech stack is not the bottleneck — the LLM is.** Measured latency from
"user stops speaking" to "first audio", using this repo's own numbers:

| stage | latency |
|---|---|
| VAD endpoint detection | ~300–500 ms |
| Streaming ASR final result | ~160 ms |
| **LLM first token** | **~5,200 ms** |
| TTS first audio | ~200–400 ms |
| **total** | **~6 s** |

Everything except the LLM is rounding error. No amount of speech engineering
improves this; only shrinking what gets prefilled per turn does.

**What works in our favour**: generation runs at ~12 tok/s (measured, plan 035)
while natural speech is ~3–4 tok/s. Generation is 3–4x faster than speech, so
once talking starts TTS never starves. The problem is purely the cold start,
which is why Step 5 speaks the *first sentence* rather than the finished reply.

## Chosen components — all from sherpa-onnx

One library covers VAD, streaming ASR and TTS, so this adds **one** runtime
(ONNX Runtime) rather than three. Apache-2.0, publishes prebuilt Android AARs
including arm64-v8a. Kotlin API is small: `OfflineTts`, `OfflineTtsConfig`,
`GeneratedAudio`, plus the online recognizer types.

| role | model | size | speed |
|---|---|---|---|
| VAD | Silero VAD | ~2 MB | negligible |
| STT | `sherpa-onnx-streaming-zipformer-en-2023-06-26` (int8) | ~68 MB encoder | RTF 0.077, ~160 ms (vendor, Pi 4) |
| TTS | Piper VITS `en_US-libritts_r-medium` | ~75 MB, **904 speakers** | **RTF ~0.15 measured on this phone** |

### Benchmarked on the owner's device, 2026-08-17

Not vendor numbers. Both models were installed as sherpa-onnx prebuilt TTS APKs
(`com.k2fsa.sherpa.onnx`) and given the identical 18-word sentence; generation
time was measured by polling `/proc/<pid>/stat` utime+stime until the busy
window closed.

| model | CPU for ~7 s of speech | RTF |
|---|---|---|
| Kokoro-82M (`kokoro-en-v0_19`) | **5.97 s** | **~0.85** |
| Piper (`en_US-lessac-medium`) | **1.04 s** | **~0.15** |

**Piper is ~5.7x faster on this hardware.** The Raspberry Pi 4 figures predicted
~8x, so the ordering held and the newer silicon did not rescue Kokoro.

**Kokoro is rejected for v1 on measured evidence, not size.** The owner
explicitly does not care about storage, so size is not the argument. RTF 0.85 is
measured *idle*; during a chat turn the 4.8 GB Gemma is already saturating the
same cores, so Kokoro would very likely cross 1.0 and stutter mid-sentence.
Piper's ~6x headroom survives that contention. Kokoro remains viable later for
*tap-to-speak on a finished message*, where nothing else is running and a
6-second wait is acceptable — a split worth revisiting once call mode works.

**Two caveats on the above, so nobody over-trusts it**: the ~7 s speech duration
is estimated from word count rather than measured from the WAV, and both runs
had the LLM idle. Re-measure under contention before committing.

**Use `libritts_r-medium`, not the `lessac` that was benchmarked.** Same
architecture and speed class, but 904 selectable speakers in one file versus
one, and voice choice is exactly what the owner will want to tune. Vendor Pi 4
numbers put the two within 0.001 RTF of each other, but that is an inference —
re-run the benchmark on `libritts_r` before shipping it.

### Curate 6 voices, not 904

The owner does not want a 904-entry picker: **3 male and 3 female**, chosen for
quality. That is the right call — a long unlabelled list is a worse experience
than a short curated one.

**These six cannot be guessed, and must not be invented.** The model exposes
speaker IDs as bare integers with no gender or quality metadata. Deriving them:

1. Gender comes from the **LibriTTS corpus** `SPEAKERS.txt` (reader ID -> M/F),
   not from the model. Map corpus reader IDs to the model's speaker indices via
   the `libritts_r` training speaker list — verify the mapping on a couple of
   known voices rather than trusting it blindly, because an off-by-one here
   ships a "male" voice that is female.
2. Quality has to be **listened to**. Synthesise one identical sentence across a
   sample of candidates, then have the owner pick. The sherpa-onnx TTS APK used
   for the benchmark takes a Speaker ID directly and is the fastest way to
   audition without writing any app code.

Ship the six as a named, ordered list in `ModelConstants` with a short label
each, and keep the raw integer out of the UI entirely. Leave a comment
recording *why* those six, so the next person does not silently reshuffle them.

If auditioning proves expensive, a defensible fallback is to ship a smaller
single-speaker Piper voice (for example `en_US-lessac-medium`, already
benchmarked at RTF 0.15) for v1 and add the six-voice picker once someone has
actually listened. Do not ship six arbitrary IDs.

### Second language: Bangla

Requested as a secondary language, English primary. Verified to exist by HTTP
HEAD, not assumed:

    bn/bn_BD/google/medium/bn_BD-google-medium.onnx
    HTTP 200, x-linked-size: 76,782,515  (~73 MB), 16 speakers

Same VITS architecture, so the same speed class is expected — unmeasured.

Piper covers 30–40+ languages, so adding more later is a download, not a
re-architecture.

**Language selection should be manual in v1.** Script detection is trivial
(Bengali has its own Unicode block), but mixed English–Bangla sentences would
flip voice mid-utterance, which sounds worse than consistently picking one.
Auto-detection is ~90% right and the remaining 10% is jarring.

**Bangla quality is unverified.** `en_US-libritts_r` comes from a large curated
corpus; `bn_BD-google-medium` comes from a smaller Google dataset, and open
Bangla TTS is generally less polished than English. Have the owner listen before
treating it as shippable.

**audio.cpp was considered and rejected**: ggml-based and architecturally
aligned with the vendored llama.cpp, but it supports Windows/Linux/macOS only —
no Android or ARM64. Worth re-checking if it ever ships mobile.

**Installing third-party APKs over adb on this device**: HyperOS returns
`INSTALL_FAILED_USER_RESTRICTED` when the screen is off, because it cannot show
its install-confirmation dialog. Send `KEYCODE_WAKEUP` first. This is not a
developer-options problem and it is not specific to any package — it cost an
uninstall of the whole app to rule out during benchmarking.

## Scope

**In scope**: a call mode reachable from a dedicated button, its own full-screen
UI, VAD + streaming ASR + Piper TTS, and the two model downloads.

**Out of scope**:
- Kokoro (see the benchmark above).
- Bangla in v1. English ships first; Bangla is a second model behind the same
  download card once English works end to end.
- Full-duplex barge-in. See Step 6 — v1 is half-duplex on purpose.
- Wake words, phone-call integration, ConnectionService.

## Steps

### Step 1: Baseline the latency honestly, before building anything

Measure current warm TTFT on the device and record it. Everything in this plan
is judged against it, and the owner has already been told the ~6 s figure — if
it has drifted, say so before building on it.

**Verify**: a recorded number in the status row.

### Step 2: Add sherpa-onnx and prove it loads

Add the Android AAR, confirm arm64-v8a native libs land in the APK, and get one
synthesised utterance out of a hardcoded string.

Note the real cost: this is a second inference runtime. Record the APK size
delta and the added native memory in the status row rather than discovering it
later.

**Verify**: `./gradlew :app:assembleRelease` exits 0; APK size delta recorded;
audible speech on the device.

### Step 3: Ship the two models through the existing download path

Both go through `ModelConstants` + `startOfficialDownload`, pinned by revision
and sha256 exactly like the embedder, and render with `ArtifactDownloadCard`
(commit `ddc76d3`) so they are consistent with the other three.

**Get the hashes honestly**: `curl -sIL` the resolve URL and read
`x-linked-etag`, which is the LFS sha256. Do not invent them.

**Also add both ids to `retireUnsupportedArtifacts`.** Commit `023916d` records
what happens when that is missed: a freshly downloaded, verified model is
deleted on the next app start.

**Verify**: both download, verify and survive an app restart.

### Step 4: Speech in — VAD then streaming ASR

Silero VAD gates the recogniser so it is not transcribing silence. Streaming
Zipformer gives partial text as the user speaks; the endpoint decides the turn
is over.

**Verify**: an instrumented or manual test showing partial transcripts, and an
endpoint firing within ~500 ms of the user stopping.

### Step 5: Speech out — first sentence, not the finished reply

Feed TTS from the answer stream sentence by sentence. Waiting for the whole
reply would add the full generation time to an already ~6 s gap.

Generation outpaces speech 3–4x, so a simple sentence queue is sufficient; there
is no need to pre-buffer the whole answer.

**Verify**: audio starts within ~400 ms of the first sentence existing, and
playback never audibly stalls mid-reply.

### Step 6: The call session

A `VoiceSession` state machine: **Idle → Listening → Thinking → Speaking →
Listening**, owned by the container and running in the existing foreground
service, so a call survives the screen going off (plan 035 already moved turns
onto the process scope).

**Half-duplex in v1, deliberately.** With the mic open while the phone speaks,
it hears itself; `AcousticEchoCanceler` is unreliable across devices. v1 stops
listening while speaking, with a tap to interrupt — which calls the existing
`cancelTurn()`. Full barge-in needs real echo cancellation and is a separate
plan.

**Verify**: a full spoken exchange end to end; tap-to-interrupt stops both TTS
and generation.

### Step 7: The call screen

A dedicated button in the chat top bar opens a full-screen surface that is
visually its own thing, not the chat screen with a banner. It carries:

- an animation reflecting state — listening / thinking / speaking — which is
  what makes a 6 s wait tolerable, because silence with no feedback reads as a
  hang
- **a transcript toggle**, text on or off, as in the ChatGPT and Claude apps
- **a memory indicator**, showing whether memory is on for this call, consistent
  with the composer's Memory chip

Reduced-motion must be honoured; the animation is feedback, not decoration.

**Verify**: on the device — all three states animate distinctly, the transcript
toggles, and the memory state matches the setting.

### Step 8: Measure, and report the real number

Re-measure end-to-end latency on the device and put the true figure in the
status row, including a null result if it is worse than the ~6 s estimate.

## Test plan

- Unit tests for the state machine transitions, especially interrupt-while-
  speaking and endpoint-during-generation.
- Unit tests for sentence segmentation feeding TTS.
- Instrumented test that the call screen opens and toggles.
- On-device: a real spoken conversation, screen-off behaviour, and interrupt.

## Done criteria

- [ ] A spoken exchange works end to end with no network
- [ ] First audio within ~400 ms of the first sentence
- [ ] Transcript toggle and memory indicator both work
- [ ] Interrupt stops TTS *and* cancels the turn
- [ ] Both models download, verify, and survive a restart
- [ ] Unit + lint + detekt + instrumented all green
- [ ] Real measured latency in `plans/README.md`

## STOP conditions

Stop and report (do not improvise) if:
- **End-to-end latency lands materially worse than ~6 s.** That makes call mode
  unpleasant regardless of polish, and the answer is to attack TTFT first, not
  to keep building. Report the measurement.
- ONNX Runtime and llama.cpp conflict — duplicate symbols, an APK size the owner
  would object to, or CPU contention that visibly slows chat generation.
- HyperOS freezes or reclaims the process during a call with the screen off
  badly enough that audio breaks up. Plan 035 recorded RSS being cut from 3.7 GB
  to 1.5 GB shortly after backgrounding.
- Echo makes half-duplex unusable even without an open mic during playback.

## Maintenance notes

- `RECORD_AUDIO` returns to a manifest that plan 033 deliberately shrank from 19
  permissions to 7. It is justified — a voice assistant needs a microphone, and
  nothing leaves the device — but it should be the only one added, and it should
  be requested at the point of use rather than at startup.
- The rule from `e4e1630` still governs: anything volatile stays out of the
  system prompt. A voice turn must not put transcripts or call state into the
  stable prefix, or it forfeits KV-cache reuse and the ~5 s TTFT becomes ~30 s.
