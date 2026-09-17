# Benchmarks

Macrobenchmarks for Offmind: startup timing and scroll jank. These exist so
performance claims stop being anecdotes — plans 024 and 025 are measured
against these numbers, not against how the app feels.

## Running

Gradle's `connectedBenchmarkAndroidTest` is the normal route:

```
./gradlew :benchmark:connectedBenchmarkAndroidTest
```

**On the Xiaomi 24122RKC7C that does not work.** MIUI rejects Gradle's
session-based install with `INSTALL_FAILED_USER_RESTRICTED`, intermittently and
without a reliable workaround. Drive it manually instead:

```bash
./gradlew :benchmark:assemble
adb -s <serial> install -r benchmark/build/outputs/apk/benchmark/benchmark-benchmark.apk
adb -s <serial> shell am instrument -w -r \
  -e class com.aliahad.aichat.benchmark.StartupBenchmark \
  -e androidx.benchmark.suppressErrors EMULATOR,LOW-BATTERY,DEBUGGABLE,NOT-PROFILEABLE \
  com.aliahad.aichat.benchmark/androidx.test.runner.AndroidJUnitRunner
```

Keep the screen on (`adb shell svc power stayon true`) and the device charged.
A low battery downclocks the CPU and quietly invalidates comparisons — the
runner warns about this, and the warning is worth heeding rather than
suppressing out of habit.

## What is and is not measured

`StartupBenchmark` covers cold/warm/hot. `startupColdWithProfile` and
`startupColdWithoutProfile` differ only in `CompilationMode`, so the baseline
profile's contribution can be read off rather than assumed.

`ScrollJankBenchmark` covers the message list and the settings list.

**Streaming is not measured automatically.** Real streaming needs the 4.8 GB
model downloaded and on-device inference, which is not something a benchmark
should drive. Two consequences:

1. `scrollMessageList` is only meaningful if a conversation with enough
   messages already exists on the device. Against a fresh install it measures
   an empty screen and the numbers mean nothing.
2. Plans 024 and 025 — the streaming-smoothness work — need the manual
   protocol below.

## Manual streaming-jank protocol

Until a synthetic token-stream harness exists, this is the repeatable
measurement for plans 024/025. Run it identically before and after a change.

1. Charge the device above 50% and keep the screen on.
2. Ensure the model is downloaded and resident (Settings → Models).
3. Start a Perfetto trace:
   ```bash
   adb -s <serial> shell perfetto -o /data/misc/perfetto-traces/stream.pftrace \
     -t 30s sched freq idle am wm gfx view binder_driver hal dalvik camera input res memory
   ```
4. In the app, send a prompt that produces a long answer, e.g.
   "Explain how HTTPS works, in detail, with examples."
5. Let it stream for the full trace duration without touching the screen.
6. Pull and analyse:
   ```bash
   adb -s <serial> pull /data/misc/perfetto-traces/stream.pftrace
   ```
   Open at <https://ui.perfetto.dev> and read frame timings on the app's
   render thread. Record P50 and P99, and note the answer's final length —
   plan 025's whole premise is that cost grows with answer length, so a short
   answer will not show it.

Record before/after in the plan's status row. A claim without both numbers
does not count.

## Baselines

Recorded 2026-08-16 on the Xiaomi 24122RKC7C (Android 16, arm64-v8a),
release build with the plan-022 baseline profile, 10 iterations each.

**Caveat: the device was at 20% battery and the runner warned about it.**
Low battery downclocks the CPU, so treat the absolute numbers as directional.
The with/without-profile comparison is still fair — both were measured back to
back under the same conditions.

### Startup — `timeToInitialDisplayMs`

| benchmark | min | median | max |
|---|---|---|---|
| `startupColdWithoutProfile` | 568.6 | **582.4** | 607.2 |
| `startupColdWithProfile` | 547.0 | **572.2** | 591.7 |
| `startupWarm` | 34.8 | 39.4 | 47.7 |
| `startupHot` | 23.5 | 27.9 | 35.6 |

The baseline profile is worth roughly **10 ms (~1.8%)** on cold start here.
That is far less than the usual rule of thumb, and worth knowing: this app's
cold start is dominated by process setup and native library loading, not by
interpreted Compose code — and plans 016/017 already moved the database open
and the native load off the startup critical path. The profile was originally
argued for as the highest-value startup item in the round-2 backlog; measured,
it is not. That is precisely why this module exists.

### Scroll jank — `frameDurationCpuMs`

| benchmark | P50 | P90 | P95 | P99 | frames |
|---|---|---|---|---|---|
| `scrollSettings` | 2.0 | 2.9 | 3.3 | **4.4** | ~341 |

Comfortably inside the 16.7 ms budget, so the settings list is not a jank
source. `scrollMessageList` is **not** in this table: the device had a fresh
install with no conversations, so it would have measured an empty list. Seed a
conversation before trusting that number.
