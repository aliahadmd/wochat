# Plan 022: Baseline Profile + profileinstaller

> **Executor instructions**: Follow this plan step by step. Run every
> verification command and confirm the expected result before moving to the
> next step. If anything in the "STOP conditions" section occurs, stop and
> report — do not improvise. When done, update the status row for this plan
> in `plans/README.md` — unless a reviewer dispatched you and told you they
> maintain the index.
>
> **Drift check (run first)**:
> `git diff --stat 619d7de..HEAD -- app/build.gradle.kts gradle/libs.versions.toml settings.gradle.kts`

## Status

- **Priority**: P1
- **Effort**: M
- **Risk**: LOW on app behavior, MED on build wiring (new Gradle module and
  plugin, and this project runs with `FAIL_ON_PROJECT_REPOS` and
  configuration cache enabled)
- **Depends on**: 018
- **Category**: performance
- **Planned at**: commit `619d7de`, 2026-08-15

## Why this matters

The release build is a minified Compose app with **no baseline profile and no
`profileinstaller` dependency**:

```
grep -n "splashscreen\|profileinstaller\|baselineprofile\|macrobenchmark" \
  gradle/libs.versions.toml app/build.gradle.kts settings.gradle.kts
→ (none found)
```

Without a baseline profile, every Compose composition, layout, and draw path
on the critical startup and first-scroll routes runs interpreted until JIT
catches up. This is the highest smoothness-per-unit-effort item in the whole
round-2 backlog: it costs no product decisions, changes no behavior, and
typically buys a double-digit percentage off cold start and a visible
reduction in first-scroll jank.

It also unblocks plan 023, which shares the same benchmark module, and gives
plans 024/025 a stable baseline to measure against.

## Current state

- No `profileinstaller`, no `androidx.baselineprofile` plugin, no benchmark
  module. `settings.gradle.kts` declares a single `:app` module.
- `app/build.gradle.kts:97-104` — `release` has `isMinifyEnabled = true` and
  `isShrinkResources = true`, so a profile has real work to do.
- `app/build.gradle.kts` restricts ABI to `arm64-v8a`, so profile generation
  needs an arm64 device or emulator image.
- `gradle.properties` sets `org.gradle.configuration-cache=true` and
  `org.gradle.caching=true`.
- Root `build.gradle.kts` uses `FAIL_ON_PROJECT_REPOS`: all repositories are
  declared centrally, and a new module must not declare its own.
- Root `build.gradle.kts:6` already declares
  `alias(libs.plugins.detekt) apply false` — an example of the
  declare-in-root, apply-in-module pattern to follow.

## Commands you will need

| Purpose | Command | Expected on success |
|---------|---------|---------------------|
| Generate profile | `./gradlew :app:generateReleaseBaselineProfile` | exit 0, profile written |
| Release APK | `./gradlew assembleRelease` | exit 0, profile packaged |
| Unit tests | `./gradlew testDebugUnitTest` | exit 0 |
| Debug APK | `./gradlew assembleDebug` | exit 0 |

## Scope

**In scope**:
- `gradle/libs.versions.toml` (profileinstaller, baselineprofile plugin,
  macrobenchmark/junit deps the generator needs)
- `settings.gradle.kts` (include the new module)
- root `build.gradle.kts` (declare the plugin)
- `app/build.gradle.kts` (apply plugin, add `profileinstaller`)
- a new `benchmark/` (or `baselineprofile/`) module

**Out of scope**:
- Startup and jank *measurement* — that is plan 023, even though it lives in
  the same module. Land the profile first, measure second.
- Any `app/src/main` source change. If generating a profile seems to require
  editing app code, that is a STOP condition.
- Startup Profiles (the separate `.dm` startup variant) — a later refinement.

## Steps

### Step 1: Wire the module and plugin

Add the `androidx.baselineprofile` plugin to the version catalog and declare
it in the root `build.gradle.kts` with `apply false`, matching the existing
pattern. Create the generator module and include it in `settings.gradle.kts`.
Do **not** add repository declarations to the new module —
`FAIL_ON_PROJECT_REPOS` will fail the build, and that is correct.

Add `androidx.profileinstaller:profileinstaller` to `:app`.

**Verify**: `./gradlew projects` lists the new module, and
`./gradlew assembleDebug` → exit 0.

### Step 2: Write a generator that covers the real critical path

The generator must exercise what users actually do on launch, not just
`startActivityAndWait()`. At minimum:

1. Cold start to the chat screen.
2. Scroll the message list (`testTag("message-list")`).
3. Open the drawer.
4. Open settings (`testTag("settings-list")`) and scroll.

Reuse the existing `testTag`s rather than adding new ones. Do **not** attempt
to drive model download or inference from the generator — see STOP conditions.

**Verify**: `./gradlew :app:generateReleaseBaselineProfile` → exit 0 and a
non-empty profile at the generated output path.

### Step 3: Confirm the profile is packaged

A generated profile that never ships is worse than none, because it looks
done. Verify the profile is actually present in the release artifact (check
for `assets/dexopt/baseline.prof` in the APK).

**Verify**: `./gradlew assembleRelease` → exit 0, and unzip the APK to
confirm the baseline profile entry exists.

### Step 4: Record the numbers

Note the profile's size and rule count in the status row, plus the device or
emulator used to generate it. Plan 023 will attach before/after startup
timings; this plan just records provenance.

## Test plan

- `./gradlew testDebugUnitTest` and `assembleDebug` unaffected.
- `./gradlew assembleRelease` succeeds with the profile packaged.
- CI: the profile generation task requires a device and must **not** be added
  to the default CI path in this plan. Note it as a follow-up.

## Done criteria

- [ ] `profileinstaller` dependency added to `:app`
- [ ] Baseline profile generator module builds and runs
- [ ] Generator covers cold start, message-list scroll, drawer, settings
- [ ] Generated profile is non-empty and packaged into the release APK
- [ ] No repositories declared in the new module
- [ ] No changes under `app/src/main`
- [ ] `./gradlew assembleDebug`, `assembleRelease`, `testDebugUnitTest` exit 0
- [ ] Profile size, rule count, and generation device recorded
- [ ] `plans/README.md` status row updated

## STOP conditions

Stop and report back (do not improvise) if:
- Generating a profile appears to require production source changes.
- The configuration cache or `FAIL_ON_PROJECT_REPOS` conflicts with the
  plugin in a way that needs those project-wide settings relaxed. Both are
  deliberate; report rather than disabling them.
- Only an x86_64 emulator is available. The app is `arm64-v8a` only — a
  profile generated against a different ABI is not valid. Mark BLOCKED.
- Release signing credentials are unavailable, so Step 3 cannot be verified.

## Maintenance notes

- Regenerate the profile when the startup path or navigation structure
  changes materially — including after plans 016, 017, and the eventual
  `AiChatApp.kt` split.
- Reviewers: the acceptance question is "is `baseline.prof` actually inside
  the shipped APK?" A green generation task alone does not answer it.
