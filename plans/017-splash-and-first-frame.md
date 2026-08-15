# Plan 017: Splash screen and first-frame gate

> **Executor instructions**: Follow this plan step by step. Run every
> verification command and confirm the expected result before moving to the
> next step. If anything in the "STOP conditions" section occurs, stop and
> report — do not improvise. When done, update the status row for this plan
> in `plans/README.md` — unless a reviewer dispatched you and told you they
> maintain the index.
>
> **Drift check (run first)**:
> `git diff --stat 619d7de..HEAD -- app/src/main/java/com/aliahad/aichat/MainActivity.kt app/src/main/res/values/themes.xml`
> On a mismatch with the "Current state" excerpts, treat it as a STOP condition.

## Status

- **Priority**: P1
- **Effort**: S
- **Risk**: LOW
- **Depends on**: 016
- **Category**: UX
- **Planned at**: commit `619d7de`, 2026-08-15

## Why this matters

The entire UI is gated behind an optional value:

```kotlin
// MainActivity.kt:117
shellState.launchDestination?.let { launchDestination ->
    AiChatApp(...)
}
```

Until `AppShellViewModel` resolves a launch destination, the app renders an
empty `Surface` — a blank screen. There is no `androidx.core:core-splashscreen`
dependency in the project, so nothing covers that window. The user sees blank,
then a sudden pop-in of the whole app.

This is separate from plan 016: 016 makes the wait *short*, 017 makes it
*not look broken*. Doing 017 first would be actively harmful — it would hide a
main-thread stall behind a pretty splash instead of fixing it. Hence the
dependency.

## Current state

- No splash dependency:
  `grep -n "splashscreen" gradle/libs.versions.toml` → no matches.
- `app/src/main/AndroidManifest.xml:117-120` — `MainActivity` uses
  `android:theme="@style/Theme.Aichat"` and `android:windowSoftInputMode="adjustResize"`.
- `MainActivity.kt:46` calls `enableEdgeToEdge()` before `setContent`.
- `MainActivity.kt:117` is the gate quoted above; `AiChatApp` is only composed
  once `launchDestination` is non-null.

Conventions: the app already ships a launcher icon set refreshed in `439fa04`
and a Material 3 theme in `ui/theme/`. Reuse those assets — do not introduce a
new brand mark.

## Commands you will need

| Purpose | Command | Expected on success |
|---------|---------|---------------------|
| Debug APK | `./gradlew assembleDebug` | exit 0 |
| Unit tests | `./gradlew testDebugUnitTest` | exit 0 |
| Instrumented compile | `./gradlew compileDebugAndroidTestKotlin` | exit 0 |

## Scope

**In scope**:
- `gradle/libs.versions.toml`, `app/build.gradle.kts` (add core-splashscreen)
- `app/src/main/res/values/themes.xml` (and `values-night/` if present)
- `app/src/main/AndroidManifest.xml` (activity theme)
- `app/src/main/java/com/aliahad/aichat/MainActivity.kt`

**Out of scope**:
- Any change to what `launchDestination` *means* or when the ViewModel
  resolves it — that is 016's territory.
- New artwork. Use the existing adaptive icon.

## Steps

### Step 1: Add the splash screen dependency and theme

Add `androidx.core:core-splashscreen` to the version catalog and to
`app/build.gradle.kts`. Create a `Theme.Aichat.Starting` theme with
`postSplashScreenTheme` pointing at `Theme.Aichat`, using the existing
launcher icon and a window background drawn from the Material 3 background
color. Point `MainActivity`'s manifest theme at the new starting theme.

**Verify**: `./gradlew assembleDebug` → exit 0.

### Step 2: Install the splash and hold it honestly

In `MainActivity.onCreate()`, call `installSplashScreen()` **before**
`super.onCreate()` per the API contract, and keep it on screen only while the
shell is genuinely not ready:

```kotlin
val splash = installSplashScreen()
splash.setKeepOnScreenCondition { appShellViewModel.uiState.value.launchDestination == null }
```

Keep `enableEdgeToEdge()` where it is.

**Verify**: `grep -n "installSplashScreen" app/src/main/java/com/aliahad/aichat/MainActivity.kt`
→ exactly one call, before `setContent`.

### Step 3: Bound the wait

The keep-on-screen condition must not be able to hang forever. If the shell
fails to resolve a destination (a container init failure from 016, for
example), the splash must give way to a visible error state rather than a
permanent blank. Add a timeout or an error branch in the shell state and
render something actionable.

**Verify**: `./gradlew compileDebugAndroidTestKotlin` → exit 0.

### Step 4: Remove the silent blank branch

With a splash in place, the `?.let { }` at line 117 should no longer be the
only thing standing between the user and an empty screen. Either render an
explicit loading/error composable in the `else` branch, or restructure so the
gate is unreachable once the splash dismisses. Do not leave a code path where
the splash is gone and nothing renders.

**Verify**: manual reasoning plus Step 5's test.

### Step 5: Test the gate

Add or extend an instrumented test asserting that the app reaches a rendered
chat surface after launch (the existing `MainActivityLifecycleInstrumentedTest`
and `ChatUiInstrumentedTest` use `testTag`s such as `"message-list"` — reuse
that pattern rather than inventing new tags).

**Verify**: `./gradlew connectedDebugAndroidTest --tests '*MainActivityLifecycle*'`
→ exit 0 (needs a device).

## Test plan

- `./gradlew testDebugUnitTest` and `compileDebugAndroidTestKotlin` green.
- Instrumented launch test green on a device.
- Manual: cold-launch on a device and confirm there is no blank frame between
  the launcher icon animation and the chat UI, in both light and dark mode.

## Done criteria

- [ ] `core-splashscreen` added to the catalog and module
- [ ] `installSplashScreen()` called before `super.onCreate()`, exactly once
- [ ] Splash releases on real readiness, with a bounded failure path
- [ ] No reachable state where the splash is gone and nothing is rendered
- [ ] Light and dark splash both use existing theme colors
- [ ] `./gradlew assembleDebug`, `testDebugUnitTest`,
      `compileDebugAndroidTestKotlin` all exit 0
- [ ] `plans/README.md` status row updated

## STOP conditions

Stop and report back if:
- Plan 016 is not DONE. Landing this first masks the real defect.
- `installSplashScreen()` conflicts with `enableEdgeToEdge()` ordering in a
  way that breaks insets — report the symptom rather than reshuffling both.
- The shell has no representable error state and adding one would require
  changing ViewModel contracts beyond this plan's scope.

## Maintenance notes

- The splash is a *cover for a short wait*, not a brand moment. If someone
  later proposes a minimum display duration, that is a regression: it makes
  the app measurably slower to use.
- Reviewers: the acceptance question is "with 016 landed, how many frames of
  blank screen remain?" The answer must be zero.
