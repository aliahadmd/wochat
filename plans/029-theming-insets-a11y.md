# Plan 029: Theming, insets, and accessibility pass

> **Executor instructions**: Follow this plan step by step. Run every
> verification command and confirm the expected result before moving to the
> next step. If anything in the "STOP conditions" section occurs, stop and
> report — do not improvise. When done, update the status row for this plan
> in `plans/README.md` — unless a reviewer dispatched you and told you they
> maintain the index.
>
> **Drift check (run first)**:
> `git diff --stat 619d7de..HEAD -- app/src/main/java/com/aliahad/aichat/ui/ app/src/main/java/com/aliahad/aichat/MainActivity.kt`

## Status

- **Priority**: P3
- **Effort**: M
- **Risk**: LOW-MED (inset changes can break layouts on devices you cannot
  test; theme changes touch every screen at once)
- **Depends on**: none
- **Category**: UX polish / accessibility
- **Planned at**: commit `619d7de`, 2026-08-15

## Why this matters

Three related gaps, grouped because they all concern how the app sits inside
the system rather than what it does.

1. **Material You is hardcoded off with no user control.**
   `MainActivity.kt:51` passes `dynamicColor = false`, and
   `ui/theme/Theme.kt:82` defaults it to `false` too. The app follows the
   system light/dark setting via `isSystemInDarkTheme()` and offers no in-app
   choice of theme or accent. On Android 13+ (this app's `minSdk` is 33,
   so *every* supported device) users expect dynamic color to be at least
   available.

2. **Insets are handled ad hoc.** `enableEdgeToEdge()` is called
   (`MainActivity.kt:46`) but there are only seven inset call sites in the
   entire 3,481-line UI file — a `statusBarsPadding()` on one screen, a
   `navigationBarsPadding()` + `imePadding()` on the composer, and a couple
   of spacers. Edge-to-edge without systematic inset handling means any
   screen nobody explicitly checked can draw content under the system bars.

3. **Accessibility is roughly half-labeled.** 21 of 36 `contentDescription`s
   in the app are `null`. Some of those are correct — decorative icons beside
   a text label genuinely should be null — but 21 is too many to be all
   deliberate, and nothing distinguishes "intentionally decorative" from
   "nobody filled it in".

## Current state

- `MainActivity.kt:51` — `AichatTheme(dynamicColor = false)`.
- `ui/theme/Theme.kt:81-87`:

```kotlin
darkTheme: Boolean = isSystemInDarkTheme(),
dynamicColor: Boolean = false,
...
val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme
```

  The `dynamicColor` parameter exists but is dead — no caller ever passes
  `true`.
- Inset call sites in `AiChatApp.kt`: lines 403 (`statusBarsPadding`),
  1133-1134 (`navigationBarsPadding`, `imePadding`), 1515
  (`navigationBarsPadding` on a spacer), plus imports at 28-32.
- `AndroidManifest.xml:120` — `android:windowSoftInputMode="adjustResize"`.
- `AiChatApp.kt:2221` and `2266` use `isSystemInDarkTheme()` directly for
  ad-hoc color choices (`EmeraldDark`/`EmeraldLight`), bypassing the theme.
- `AppSettingsRepository` (DataStore-backed) is the established home for user
  preferences, and a Settings hub already exists with sections.

## Commands you will need

| Purpose | Command | Expected on success |
|---------|---------|---------------------|
| Unit tests | `./gradlew testDebugUnitTest` | exit 0 |
| Android lint | `./gradlew lint` | exit 0, no new a11y warnings |
| Instrumented compile | `./gradlew compileDebugAndroidTestKotlin` | exit 0 |
| Debug APK | `./gradlew assembleDebug` | exit 0 |

## Scope

**In scope**:
- `app/src/main/java/com/aliahad/aichat/ui/theme/Theme.kt`
- `app/src/main/java/com/aliahad/aichat/MainActivity.kt`
- `app/src/main/java/com/aliahad/aichat/ui/AiChatApp.kt`
- `app/src/main/java/com/aliahad/aichat/settings/` for the new preference

**Out of scope**:
- Redesigning the color palette. `Color.kt` stays as the fallback scheme.
- Font scaling and dynamic type audit — related, but a separate pass.
- RTL layout support — also separate.

## Steps

### Step 1: Offer theme control

Add a user preference (DataStore, via `AppSettingsRepository`) for theme mode
— System / Light / Dark — and a separate toggle for dynamic color, defaulting
dynamic color to **on** (it is the platform default expectation on
Android 13+) unless the palette breaks under it.

Wire it into the existing Settings hub as a new section or into an existing
one; do not create a new top-level destination.

`AichatTheme` already takes both parameters — pass real values instead of
constants.

**Verify**: `grep -n "dynamicColor = false" app/src/main/java/com/aliahad/aichat/MainActivity.kt`
→ no matches.

### Step 2: Check the palette under dynamic color

Dynamic color replaces the whole scheme, so any place the app assumes its own
palette can break — notably the two ad-hoc `isSystemInDarkTheme()` colors at
`AiChatApp.kt:2221` and `2266`. Route those through the theme, or document
why they are deliberately palette-independent.

Check contrast on the message bubbles specifically: user bubbles use
`primaryContainer`, assistant bubbles `surfaceContainerLow`. Under some
dynamic palettes these can converge.

**Verify**: manual check with at least three different system wallpapers,
in both light and dark.

### Step 3: Make insets systematic

Adopt one consistent approach rather than seven scattered calls: let the
`Scaffold` own window insets and have screens consume its padding, or apply
`WindowInsets.safeDrawing` at a single top-level container. Then remove the
ad-hoc per-screen padding that the systematic approach makes redundant.

Keep `imePadding()` on the composer — that one is correct and load-bearing.

**Verify**: every screen (chat, settings sections, drawer, bottom sheets,
attachment preview) checked with a gesture-navigation device and a
three-button device.

### Step 4: Audit content descriptions

Go through all 36 `contentDescription` sites. For each `null`, either supply
a real description or confirm it is decorative. Make the decorative ones
*explicitly* decorative rather than incidentally null.

Prioritize icon-only controls, which are unusable with TalkBack when
unlabeled — the send/stop button, attach, drawer, skills, and close-settings
buttons are already labeled; find the ones that are not.

**Verify**: `./gradlew lint` → exit 0 with no new accessibility warnings.

### Step 5: Test with TalkBack

Enable TalkBack on a device and complete one full task: open the drawer,
start a new chat, type and send a message, open settings. Note anything
unreachable or unlabeled.

**Verify**: written notes in the status row.

## Test plan

- `./gradlew lint` clean of new a11y warnings.
- Existing instrumented tests green — several locate elements by content
  description, so changes there can break them.
- Manual matrix: light/dark × dynamic on/off, gesture nav and three-button
  nav, one TalkBack pass.

## Done criteria

- [ ] Theme mode and dynamic color are user-controllable and persisted
- [ ] Dynamic color verified against at least three wallpapers, light and dark
- [ ] Ad-hoc `isSystemInDarkTheme()` colors routed through the theme or
      documented
- [ ] Insets handled by one consistent mechanism; redundant padding removed
- [ ] All screens verified against both navigation modes
- [ ] Every `contentDescription = null` either filled or confirmed decorative
- [ ] One TalkBack task pass completed and noted
- [ ] `./gradlew lint`, `testDebugUnitTest`, `compileDebugAndroidTestKotlin`,
      `assembleDebug` all exit 0
- [ ] `plans/README.md` status row updated

## STOP conditions

Stop and report back (do not improvise) if:
- Dynamic color makes message bubbles indistinguishable under common
  palettes. Report it — the fix may be a bubble redesign, which is out of
  scope here.
- Centralizing insets requires restructuring `Scaffold` usage across every
  screen. Report the scale; it may belong with the `AiChatApp.kt` split.
- Changing a content description breaks instrumented tests that locate by it.
  Update the test locator, and list every one you touched.

## Maintenance notes

- Once insets are centralized, new screens should need *no* inset code. A
  future PR adding `statusBarsPadding()` by hand is a signal the central
  mechanism was bypassed.
- Reviewers: the acceptance question for a11y is "can this screen be operated
  with TalkBack?", not "is the lint warning gone?"
