# Plan 018: StrictMode main-thread I/O gate (debug builds)

> **Executor instructions**: Follow this plan step by step. Run every
> verification command and confirm the expected result before moving to the
> next step. If anything in the "STOP conditions" section occurs, stop and
> report — do not improvise. When done, update the status row for this plan
> in `plans/README.md` — unless a reviewer dispatched you and told you they
> maintain the index.
>
> **Drift check (run first)**:
> `git diff --stat 619d7de..HEAD -- app/src/main/java/com/aliahad/aichat/AiChatApplication.kt app/build.gradle.kts`

## Status

- **Priority**: P1
- **Effort**: S
- **Risk**: LOW (debug-only; must never affect release behavior)
- **Depends on**: none
- **Category**: DX / guard rail
- **Planned at**: commit `619d7de`, 2026-08-15

## Why this matters

Plan 016 exists because the app opens an encrypted database and runs 16
migrations on the main thread. That defect survived a full four-track audit
and fifteen shipped plans. The reason is simple: **nothing in this project
can observe it.** CI compiles the app and runs JVM tests; it never launches
the app on a device, so main-thread disk I/O is invisible.

StrictMode makes it self-reporting. Once enabled in debug builds, any future
main-thread read, write, or leaked closeable announces itself in logcat the
first time a developer runs the app. This is the cheapest durable guard in
the entire round-2 backlog, and it is what stops 016 from silently coming
back.

## Current state

- `grep -rn "StrictMode" app/src/main/java` → no matches. StrictMode is not
  used anywhere.
- `app/src/main/java/com/aliahad/aichat/AiChatApplication.kt` — `onCreate()`
  begins with a process check and then builds the container:

```kotlin
override fun onCreate() {
    super.onCreate()
    if (Application.getProcessName() == "$packageName:vulkan") return
    container = AppContainer(this)
    ...
}
```

- `app/build.gradle.kts:97-104` — two build types; `debug` has
  `isMinifyEnabled = false`, `release` has minify + resource shrinking.
- `BuildConfig` is already used in this codebase
  (`com.aliahad.aichat.BuildConfig.LLAMA_RUNTIME_REVISION` in `AppContainer`),
  so `BuildConfig.DEBUG` is available without new wiring.

## Commands you will need

| Purpose | Command | Expected on success |
|---------|---------|---------------------|
| Debug APK | `./gradlew assembleDebug` | exit 0 |
| Release APK | `./gradlew assembleRelease` | exit 0 (needs signing) |
| Unit tests | `./gradlew testDebugUnitTest` | exit 0 |

## Scope

**In scope**:
- `app/src/main/java/com/aliahad/aichat/AiChatApplication.kt`
- Optionally a small `debug/StrictModeInitializer.kt` helper

**Out of scope**:
- Fixing whatever StrictMode reports. This plan installs the detector only.
  Findings become their own plans (016 is already one of them).
- Any release-build behavior change.
- `penaltyDeath` — see STOP conditions.

## Steps

### Step 1: Install thread and VM policies in debug only

At the very top of `onCreate()`, before the process check and before the
container is built, install StrictMode when `BuildConfig.DEBUG` is true:

- **Thread policy**: `detectDiskReads()`, `detectDiskWrites()`,
  `detectNetwork()`, `detectCustomSlowCalls()`, with `penaltyLog()`.
- **VM policy**: `detectLeakedSqlLiteObjects()`,
  `detectLeakedClosableObjects()`, `detectActivityLeaks()`, with
  `penaltyLog()`.

Use `penaltyLog()` only. Do **not** use `penaltyDeath()` or
`penaltyDeathOnNetwork()` — this codebase currently violates the policy on
purpose-built paths, and crashing debug builds would block all other round-2
work.

Guard the `:vulkan` process: install the policy there too, or return before
it, but be deliberate about which and say so in a comment.

**Verify**: `./gradlew assembleDebug` → exit 0.

### Step 2: Confirm release builds are untouched

StrictMode must be entirely absent from the release path. Because
`BuildConfig.DEBUG` is a compile-time constant, R8 will strip the branch —
confirm that is actually what happens rather than assuming it.

**Verify**: `./gradlew assembleRelease` → exit 0, and confirm the branch is
not reachable in release (inspect the R8 mapping/output, or reason from
`BuildConfig.DEBUG` being `false` as a constant — state which check you did).

### Step 3: Record the baseline violations

Launch the debug app on a device or emulator and capture the StrictMode
output:

```bash
adb logcat -d -s StrictMode
```

Write the distinct violations into the plan status row or a short note in
`plans/README.md`. This baseline is the input to prioritizing follow-up
plans, and it is how 016's fix gets confirmed.

**Verify**: at least the known `AppDatabase.create` disk reads appear, unless
016 already landed.

## Test plan

- `./gradlew assembleDebug` and `assembleRelease` both exit 0.
- `./gradlew testDebugUnitTest` unaffected.
- Manual: debug launch produces StrictMode log lines; release build produces
  none.

## Done criteria

- [ ] StrictMode thread + VM policies installed under `BuildConfig.DEBUG`
- [ ] `penaltyLog()` only — no `penaltyDeath` variants
- [ ] `:vulkan` process behavior is deliberate and commented
- [ ] Release build verified free of StrictMode
- [ ] Baseline violation list recorded
- [ ] `plans/README.md` status row updated

## STOP conditions

Stop and report back if:
- StrictMode logging is so voluminous that logcat becomes unusable — report
  the top offenders instead of silencing categories unilaterally.
- Installing the VM policy destabilizes the `:vulkan` service process.
- Anyone asks for `penaltyDeath` as part of this plan. That is a separate
  decision to make *after* the baseline is clean, not before.

## Maintenance notes

- The end state, once 016 and its follow-ups land, is a clean baseline plus a
  future plan to escalate to `penaltyDeath` in debug. Do not escalate early.
- Reviewers: the acceptance question is "does a fresh debug launch print
  main-thread disk I/O?" Before 016, yes — and that is the point.
