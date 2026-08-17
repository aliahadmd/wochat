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

### Step 1: Baseline the latency honestly, before building anything — DONE

Done by plan `037`, 2026-08-17/18, on the device. **The ~6 s figure in the table
above is wrong, and the table needs rewriting before Step 8 is judged against
it.** Warm TTFT is not a single number; it is bimodal:

| condition | LLM time to first token |
|---|---|
| short prompt, no memory hit, healthy regime | **1.22-1.25 s** |
| ~50-token memory preamble retrieved | **5.5-6.1 s** |
| first turn after app start (cold-session replay) | **15-60 s**, grows with conversation length |

The regime split is a **fixed ~4.5 s per `llama_decode` call** that appears
intermittently; in the healthy regime prefill is a clean ~50-60 ms/token from 7
tokens to 745 and the first token costs ~107 ms. Details, fault counters and the
refuted page-warming experiment are in `037`.

**Consequences for this plan:**
- The STOP condition ("materially worse than ~6 s") is **not** triggered — the
  common case is better than assumed, sometimes 5x better.
- The retrieved-memory preamble, not the speech stack, is the second-largest
  controllable term. A call that pulls memories pays ~4 s more than one that does
  not, which is what the memory indicator in Step 7 should make legible.
- The **first turn of a call is the expensive one** (cold-session replay). Step 6
  should warm the session when the call *opens*, not when the user stops
  speaking, so the replay overlaps the greeting rather than the first answer.
- Do not build a "speak the first sentence" design around 6 s of dead air that
  frequently is not there.

### Step 2: Add sherpa-onnx and prove it loads — DONE (except audible speech)

`sherpa-onnx-1.13.5.aar`, vendored in `app/libs/` beside the SQLCipher AAR and
referenced with `implementation(files(...))`. k2-fsa publishes **no official
artifact on Maven Central** — only third-party repackagings, which are not
acceptable for an offline app — so the unmodified official release asset is kept
whole, with its URL and sha256 recorded in `app/build.gradle.kts`.

**Measured APK size delta: 23,348,328 -> 35,012,170 bytes (+11.66 MB, +50 %).**
The AAR ships four ABIs; `abiFilters` keeps only arm64-v8a, which lands four
libraries totalling ~31 MB uncompressed:

| library | uncompressed |
|---|---|
| `libonnxruntime.so` | 21.68 MB |
| `libsherpa-onnx-jni.so` | 4.76 MB |
| `libsherpa-onnx-c-api.so` | 4.45 MB |
| `libsherpa-onnx-cxx-api.so` | 0.44 MB |

`./gradlew :app:assembleRelease` exits 0. The AAR's manifest declares no
permissions and no components, so it adds nothing to ours.

**Audible speech: DONE.** `VoiceSpeaker` (Piper via `OfflineTts`, played through
an `AudioTrack` at the model's 22 050 Hz) is wired to a **Test voice** action on
the ready card, which doubles as the way to audition voices later. Measured on
device: **60 672 samples = 2.75 s of speech synthesised in 377 ms, RTF 0.14** —
matching the ~0.15 this plan predicted for Piper, and confirming Kokoro's
rejection was the right call.

**A release-only crash was found and fixed doing this, and it would have
shipped.** R8 obfuscated the sherpa classes, and sherpa's JNI resolves its config
objects *by name*:

    java.lang.NoSuchFieldError: no type "Lcom/k2fsa/sherpa/onnx/OfflineTtsModelConfig;"
    found and so no field "model" could be found in class "OfflineTtsConfig"

The process died instantly. The AAR ships an **empty** `proguard.txt`, so it
contributes no rules, and debug builds are not minified — nothing but a release
build reveals this. Fixed with a `-keep class com.k2fsa.sherpa.onnx.** { *; }` in
`src/main/keepRules/rules.keep`. **Any future sherpa work must be smoke-tested on
a release build**, not just debug.

### Step 3 artifacts, pinned and verified by HTTP HEAD

Every one of these was checked with `curl -sIL`, not assumed. LFS files carry
`x-linked-etag`, which is the sha256 the existing download path already verifies.

**Packaging decision.** ASR is four ordinary files and fits the existing
single-file machinery exactly. TTS cannot: Piper needs `espeak-ng-data`, which is
**355 files / 18 MB** — impractical to fetch file-by-file — so the TTS voice
comes from the vendor tarball and is extracted on device (`commons-compress` is
already a dependency for backups). Its sha256 must be computed from the
downloaded bytes and pinned, since GitHub release assets carry no LFS hash.

ASR — HF `csukuangfj/sherpa-onnx-streaming-zipformer-en-2023-06-26`:

| file | bytes | sha256 |
|---|---|---|
| `encoder-…-chunk-16-left-128.int8.onnx` | 71,083,163 | `563fde43…3f45ac1` |
| `decoder-…-chunk-16-left-128.int8.onnx` | 1,307,236 | `98da299f…dd83b02` |
| `joiner-…-chunk-16-left-128.int8.onnx` | 259,335 | `d944208d…dd6e297` |
| `tokens.txt` | 5,048 | not LFS — compute and pin |

VAD — HF `csukuangfj/vad/resolve/main/silero_vad.onnx`, 1,807,522 bytes,
sha256 `a35ebf52…b1f5af28`. (The GitHub copy is a different, older 643 KB build;
prefer the HF one so it is pinned like everything else.)

TTS — `vits-piper-en_US-libritts_r-medium.tar.bz2`, 82,038,311 bytes, from the
sherpa-onnx `tts-models` release. The extracted `.onnx` alone is 78,581,047 bytes
with sha256 `acd94250…4b11ad0e` on HF, which is a useful cross-check that the
tarball contents match the mirrored files.

### Step 2: Add sherpa-onnx and prove it loads

Add the Android AAR, confirm arm64-v8a native libs land in the APK, and get one
synthesised utterance out of a hardcoded string.

Note the real cost: this is a second inference runtime. Record the APK size
delta and the added native memory in the status row rather than discovering it
later.

**Verify**: `./gradlew :app:assembleRelease` exits 0; APK size delta recorded;
audible speech on the device.

### Step 3: Ship the models through the existing download path — DONE AND VERIFIED

All six artifacts are `OfficialModelSpec`s in `VOICE_MODELS`, downloaded by the
existing worker and rendered by `ArtifactDownloadCard` as **one** card ("English
speech pack, 149.3 MB") — the vendor publishes an encoder, a decoder and a joiner,
but no user should have to reason about a "joiner", and none of them is useful
alone. `aggregateDownloadStatus` is worst-news-first (8 unit tests) so five
successes cannot hide one failure.

Three changes were needed to the shared path, all narrow:
- `OfficialModelSpec` gained optional `absoluteUrl` and `archiveRootDirectory`.
  Both default to null, so nothing existing changed.
- `GgufValidator` now runs **only on `.gguf`**. It previously ran on every
  download, which would have rejected every ONNX file, the tokens list and the
  tarball.
- `ArchiveInstaller` extracts the Piper tarball via `commons-compress`, staging
  into a `.partial` directory and renaming, with a traversal guard mirroring
  `BackupPathSafety`.

**Verified on device**: all six downloaded, the tarball extracted (disk grew
256 MiB — 149 MiB fetched plus ~96 MiB unpacked), the card went to Delete, and
**everything survived a force-stop and relaunch** — the retire sweep that ate the
embedder in `034` leaves them alone, because `ALL_DOWNLOADABLE_MODELS` now feeds
both the record and the sweep.

The tarball is deliberately kept on disk after extraction. `ensureOfficialRecords`
decides readiness from the downloaded file's size, so deleting it would make the
app re-download 82 MB on the next start.

### Step 3 (original text): Ship the two models through the existing download path

Both go through `ModelConstants` + `startOfficialDownload`, pinned by revision
and sha256 exactly like the embedder, and render with `ArtifactDownloadCard`
(commit `ddc76d3`) so they are consistent with the other three.

**Get the hashes honestly**: `curl -sIL` the resolve URL and read
`x-linked-etag`, which is the LFS sha256. Do not invent them.

**Also add both ids to `retireUnsupportedArtifacts`.** Commit `023916d` records
what happens when that is missed: a freshly downloaded, verified model is
deleted on the next app start.

**Verify**: both download, verify and survive an app restart.

### Steps 4-7 built; a spoken exchange works end to end — 2026-08-18

`VoiceListener` (mic -> Silero VAD -> streaming Zipformer), `SentenceSegmenter`
(9 unit tests), `VoiceCallSession` (Idle/Listening/Thinking/Speaking, half-duplex),
`VoiceCallCoordinator` (a spoken turn goes through the *same* `ChatTurnRunner`,
tagged `TurnOrigin.VOICE`), and `VoiceCallScreen` behind a call button in the top
bar. `RECORD_AUDIO` is requested at the point of use, and is the only permission
added back.

**Verified on device**: the user spoke, the recogniser produced a transcript, the
endpoint fired, the microphone stopped, a turn ran, and the model answered — a
full spoken exchange, several turns of it, with the transcript and the memory
indicator live on screen.

**Four defects were found by running it on the device, none by reading the code.**
All four presented as "no error, nothing happens", which is why they are recorded:

1. **The VAD gated the recogniser, so the feature failed closed.** Audio was only
   fed to the recogniser while Silero reported speech; when the VAD said nothing,
   there was no transcript, no turn and no error. The recogniser is now fed
   unconditionally and the VAD drives only the animation — a wrong VAD costs a
   visual, not the call.
2. **`ENCODING_PCM_FLOAT` capture returns zeros on this device.** `AudioRecord`
   initialised and read successfully; every window came back at ~1e-4 peak.
   16-bit PCM works.
3. **`AudioSource.VOICE_RECOGNITION` delivers a dead line on this HyperOS build.**
   The "correct" source on paper. Peak went from 1.5e-4 to 0.013-0.42 — about
   100x — by switching to plain `MIC`. Note the trade this makes: `MIC` gets the
   platform's ordinary preprocessing rather than the recognition-tuned path.
4. **`modelType = "zipformer2"` was asserted on a model that is not one.** The
   recogniser consumed audio and returned empty text, silently. Leaving it unset
   lets sherpa read the type from the model's own metadata, which cannot disagree
   with the file. First transcript appeared immediately after.

A silence guard now logs when the microphone delivers nothing above the noise
floor for 5 s, because three of the four above were invisible without it.

**Accuracy: FIXED by replacing the recogniser.** The streaming Zipformer produced
"HALLO KA NU YERI" and "GANY HEER MECHILLI" for "Hello, can you hear me?" — and
audio was ruled out first, properly: level healthy (peak 0.10-0.42, no clipping),
sample rate confirmed 16 kHz from the recorder itself, and **`dropped=0`** windows
once capture was instrumented. Phonetically-close-but-word-wrong output with clean
audio is an acoustic model mismatched to the speaker, not a capture problem.

Swapped to **Whisper base.en, VAD-segmented** (208 MB). Measured on the owner's own
speech, same sentence:

| | transcript |
|---|---|
| streaming Zipformer int8 | `HALLO KA NU YERI` |
| **Whisper base.en** | **`Hello, can you hear me?`** |

Exact words, capitalisation and punctuation, and the model answered "I can hear
you. How can I help you?" aloud. **Transcription cost 424 ms for 1.41 s of audio
(RTF 0.30)** — well inside the budget freed up by the LLM being faster than this
plan assumed.

What it costs, accepted deliberately: no word-by-word transcript while speaking
(Whisper is not streaming, so text appears when the user stops), and Silero VAD
now owns turn-taking, with `minSilenceDuration` raised 0.25 s -> 0.6 s because the
shorter value cut people off mid-sentence.

A fifth defect was found and fixed on the way: `VoiceCallCoordinator` cached
`listener.isInstalled()` in a `val` at construction, so a pack that finished
downloading *after* the ViewModel existed stayed invisible until an app restart —
and the call screen sat on "Starting" forever with nothing to explain it. It is
re-checked per call now, and an unavailable pack says so.
- **Latency is the LLM, exactly as `037` predicted.** The first call turn landed
  on a conversation with 745+ tokens of history and spent **minutes** in a
  cold-session replay, in the degraded regime where a *7-token* decode takes
  6103 ms. Nothing in the voice stack is close to this. Step 6's session warming
  should happen when the call *opens*, and a call probably wants a fresh
  conversation rather than inheriting a long one.

### Step 4 (original text): Speech in — VAD then streaming ASR

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

### Step 8: DONE — and it fires this plan's STOP condition, 2026-08-18

Measured on the device with a fresh conversation, from the transcript arriving to
the first audible word:

| stage | turn 1 | turn 2 |
|---|---|---|
| Whisper transcription | 370 ms | 363 ms |
| **LLM, to a complete first sentence** | **50 328 ms** | **36 874 ms** |
| Piper synthesis | 222 ms | 208 ms |
| **first audio after transcript** | **50.5 s** | **37.1 s** |

**The speech stack is ~0.6 s of a ~50 s turn — under 1.5 %.** Every component this
plan chose is comfortably fast: Whisper at RTF 0.26-0.30, Piper at RTF 0.14, VAD
negligible. The remaining ~98.5 % is the LLM, and this was a *fresh* conversation,
so it is not the cold-replay cost either.

**STOP condition triggered** ("materially worse than ~6 s ... attack TTFT first,
not keep building"). Steps 1-7 are complete and the feature demonstrably works —
a spoken exchange with an accurate transcript and an audible answer — but it is
not pleasant to use at this latency, and no further polish changes that.

Two things make the gap worse than `037`'s time-to-first-token figure, and both
are properties of *this* design rather than of the model:

1. **Speech waits for a whole sentence, not the first token.** Turn 2 reached its
   first token at 19.5 s and its first *sentence* at 36.9 s — 17 s of generation
   spent before a word could be spoken. Speaking on a clause boundary, or on a
   token budget with a timeout, would recover a large part of that.
2. **The degraded per-decode regime from `037` dominates.** Same feature measured
   **989 ms** to first token on a healthy turn and **19-27 s** here. Until that
   regime is understood, call latency is unpredictable by an order of magnitude,
   which is worse for a call than being uniformly slow.

**Do not tune the speech stack further.** The next work is `037`'s open question —
what causes a fixed multi-second cost per `llama_decode` — and it needs Perfetto
and a look at thread scheduling, not more audio engineering.

### Step 8 (original text): Measure, and report the real number

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
