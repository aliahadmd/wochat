# Plan 023: Macrobenchmark for startup time and streaming jank

> **Executor instructions**: Follow this plan step by step. Run every
> verification command and confirm the expected result before moving to the
> next step. If anything in the "STOP conditions" section occurs, stop and
> report — do not improvise. When done, update the status row for this plan
> in `plans/README.md` — unless a reviewer dispatched you and told you they
> maintain the index.
>
> **Drift check (run first)**:
> `git diff --stat 619d7de..HEAD -- settings.gradle.kts app/build.gradle.kts`

## Status

- **Priority**: P2
- **Effort**: M
- **Risk**: LOW (test-only module; no production code changes)
- **Depends on**: 022
- **Category**: tests / performance
- **Planned at**: commit `619d7de`, 2026-08-15

## Why this matters

Plans 024 and 025 both claim to make streaming smoother. Neither can be
*shown* to have worked, because this project has no way to measure
user-perceived performance. CI compiles the app and runs JVM logic tests; it
never launches the app and never looks at a frame.

That gap is exactly why the round-1 audit — which had a dedicated
performance track — shipped fifteen plans without noticing that the message
list re-scrolls on every token or that markdown is re-parsed from scratch on
every delta. Static analysis found the algorithmic issues it could reason
about; nothing measured what the user actually felt.

This plan builds the scoreboard. It comes *before* 024 and 025 so those plans
can prove their claims with numbers instead of assertions.

## Current state

- No macrobenchmark module (`grep -n "macrobenchmark" settings.gradle.kts
  gradle/libs.versions.toml` → no matches). Plan 022 creates the module this
  plan extends.
- Existing UI test hooks to reuse:
  - `testTag("message-list")` — `AiChatApp.kt:824`
  - `testTag("settings-list")` — `AiChatApp.kt:2755`
- `app/build.gradle.kts:97-104` — release is minified; benchmarks must run
  against a release-like build to be meaningful.
- CI (`.github/workflows/ci.yml`) has an `instrumented-tests` job on
  `macos-latest` using `reactivecircus/android-emulator-runner` with
  `arch: arm64-v8a`, currently `continue-on-error: true`.

## Commands you will need

| Purpose | Command | Expected on success |
|---------|---------|---------------------|
| Run benchmarks | `./gradlew :benchmark:connectedBenchmarkAndroidTest` | exit 0, metrics printed |
| Debug APK | `./gradlew assembleDebug` | exit 0 |
| Unit tests | `./gradlew testDebugUnitTest` | exit 0 |

*(Adjust the module name to whatever plan 022 created.)*

## Scope

**In scope**:
- The benchmark module created by plan 022
- `gradle/libs.versions.toml` for benchmark dependencies
- A short results note in `plans/README.md` or a `benchmark/README.md`

**Out of scope**:
- Any `app/src/main` change. If a benchmark needs production hooks beyond the
  existing `testTag`s, that is a STOP condition.
- Wiring benchmarks as a blocking CI gate. Emulator-based frame timing is too
  noisy to gate merges on; see Step 4.
- Fixing anything the benchmarks reveal — those are plans 024, 025, and
  whatever else the numbers justify.

## Steps

### Step 1: Startup timing

Add a `StartupTimingMetric` benchmark covering `COLD`, `WARM`, and `HOT`
startup, with enough iterations to be meaningful (10+ for cold).

Run it **before** plans 016/017 land if they have not yet, so the numbers
capture the main-thread-database startup as a baseline. If 016 has already
landed, note that in the results.

**Verify**: benchmark runs and reports `timeToInitialDisplayMs`.

### Step 2: Scroll jank

Add a `FrameTimingMetric` benchmark that scrolls the message list against a
seeded conversation. Seeding matters: a benchmark over an empty list measures
nothing. Use the same seeding approach the existing instrumented tests use
(`RoomPersistenceInstrumentedTest` and `ChatUiInstrumentedTest` are the
references) rather than inventing a new fixture format.

**Verify**: benchmark reports `frameDurationCpuMs` percentiles including P99.

### Step 3: Streaming jank — the important one

This is the benchmark plans 024 and 025 exist to move, and the hardest to
build honestly, because real streaming requires a downloaded 4.8 GB model and
on-device inference. Do not attempt that in a benchmark.

Instead, drive the *rendering* path with a synthetic token stream: feed the
chat UI deltas at a fixed rate and measure frame timing while the message
list follows along. If the current architecture makes that impossible without
production hooks, **stop** and report — do not add test-only seams to
`app/src/main` under this plan.

If a synthetic stream is not reachable, fall back to a documented manual
protocol (Perfetto trace of a real generation, with the exact steps written
down) and record that as the measurement method. A written, repeatable manual
protocol is an acceptable outcome; an unmeasured claim is not.

**Verify**: either an automated streaming benchmark, or a written protocol
committed alongside the module.

### Step 4: Record baselines, do not gate CI

Write the baseline numbers into a results note: device/emulator, build type,
metric, median and P99. Plans 024 and 025 will append their after-numbers to
the same note.

Do not make benchmarks a required CI check. The existing instrumented job is
still `continue-on-error: true` because it has not proven stable; frame
timing on an emulator is noisier still. If benchmarks run in CI at all, they
run informationally.

**Verify**: results note committed; `.github/workflows/ci.yml` has no new
blocking gate.

## Test plan

- Benchmarks execute on an arm64 device or emulator and produce metrics.
- `./gradlew testDebugUnitTest` and `assembleDebug` unaffected.
- No file under `app/src/main` modified (`git status`).

## Done criteria

- [ ] Startup benchmark (cold/warm/hot) runs and reports numbers
- [ ] Message-list scroll jank benchmark runs against a seeded conversation
- [ ] Streaming measured automatically, or a written manual protocol committed
- [ ] Baseline results recorded with device, build type, median and P99
- [ ] No `app/src/main` changes
- [ ] No new blocking CI gate
- [ ] `plans/README.md` status row updated

## STOP conditions

Stop and report back (do not improvise) if:
- A benchmark would require adding test-only hooks to production code.
- Only an x86_64 emulator is available (the app is `arm64-v8a` only) —
  mark BLOCKED.
- Frame timing variance between runs is so wide that before/after comparison
  would be meaningless. Report the variance; plans 024/025 need to know their
  measurement floor.

## Maintenance notes

- These numbers are the acceptance evidence for 024 and 025. A reviewer on
  either of those plans should ask to see the delta against this baseline.
- Re-baseline after the eventual `ui/AiChatApp.kt` split, since composition
  structure changes will move the numbers for reasons unrelated to
  performance work.
