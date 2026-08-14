# Plan 009: Trim the Android manifest to what the app actually implements

> **Executor instructions**: Follow this plan step by step. Run every
> verification command and confirm the expected result before moving to the
> next step. If anything in the "STOP conditions" section occurs, stop and
> report — do not improvise. When done, update the status row for this plan
> in `plans/README.md` — unless a reviewer dispatched you and told you they
> maintain the index.
>
> **Drift check (run first)**: `git diff --stat 2dff7e6..HEAD -- app/src/main/AndroidManifest.xml app/build.gradle.kts app/src/main/res/xml/office_accessibility_service.xml app/src/main/java/com/aliahad/aichat/brief/`
> The repo had uncommitted branding work in flight at audit time; verify
> current state before editing.

## Status

- **Priority**: P2
- **Effort**: S
- **Risk**: LOW (removing never-exercised permissions cannot break implemented flows — with one verification for QUERY_ALL_PACKAGES)
- **Depends on**: none
- **Category**: security
- **Planned at**: commit `2dff7e6`, 2026-08-15

## Why this matters

The manifest declares permissions and capabilities the code never uses:
three Health Connect permissions plus a healthdata `<queries>` entry and
the `health-connect-client` dependency, while `PhoneSourceAccessManager`
literally reports Health as "not implemented yet"; background location;
`canPerformGestures="true"` on an accessibility service that never performs
actions. For an app whose value proposition is privacy, this overhang is
both least-privilege violation and store-review exposure (background
location + QUERY_ALL_PACKAGES + health permissions are flagged
categories). Service labels also brand two system-settings entries
"wochat" while the app is AIchat everywhere else — fixed here since we're
in the file anyway.

## Current state

- `app/src/main/AndroidManifest.xml`:

```xml
<!-- AndroidManifest.xml:15-26 -->
<uses-permission android:name="android.permission.QUERY_ALL_PACKAGES"
    tools:ignore="QueryAllPackagesPermission" />
<uses-permission android:name="android.permission.ACCESS_COARSE_LOCATION" />
<uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" />
<uses-permission android:name="android.permission.ACCESS_BACKGROUND_LOCATION" />
<uses-permission android:name="android.permission.ACTIVITY_RECOGNITION" />
<uses-permission android:name="android.permission.READ_CONTACTS" />
<uses-permission android:name="android.permission.READ_CALENDAR" />
<uses-permission android:name="android.permission.health.READ_STEPS" />
<uses-permission android:name="android.permission.health.READ_SLEEP" />
<uses-permission android:name="android.permission.health.READ_EXERCISE" />
```

```xml
<!-- AndroidManifest.xml:31-33 -->
<queries>
    <package android:name="com.google.android.apps.healthdata" />
</queries>
```

```xml
<!-- AndroidManifest.xml:73-93 (labels) -->
<service ... android:label="wochat notification memory" ... />
<service ... android:label="wochat screen memory" ... />
```

- `app/src/main/res/xml/office_accessibility_service.xml:6` —
  `canPerformGestures="true"`.
- `app/build.gradle.kts:178` — `health-connect-client` dependency.
- `app/src/main/java/com/aliahad/aichat/brief/HealthDataSource.kt` — the
  only `brief/` file; a Health Connect data source wired at
  `AiChatApplication.kt:152`. The permissions are declared "for when it
  ships" while `PhoneSourceAccessManager.kt:70-73` reports the source as
  not implemented.
- `QUERY_ALL_PACKAGES` usage: `activity/` collectors enumerate installed
  apps for usage summaries (see `OfficeActivityWorkers`/package query
  code) — verify before touching (Step 3).

## Commands you will need

| Purpose | Command | Expected on success |
|---------|---------|---------------------|
| Build | `./gradlew assembleDebug` | exit 0 |
| Lint (flagged-permission check) | `./gradlew lint` | exit 0 |
| Unit tests | `./gradlew testDebugUnitTest` | exit 0 |
| Detekt | `./gradlew detekt` | exit 0 |

## Scope

**In scope**:
- `app/src/main/AndroidManifest.xml`
- `app/src/main/res/xml/office_accessibility_service.xml`
- `app/build.gradle.kts` (remove `health-connect-client` only)
- `gradle/libs.versions.toml` (remove the catalog entry only if nothing else references it)

**Out of scope**:
- `brief/HealthDataSource.kt` and its wiring in `AiChatApplication.kt`
  (leave the code; only the permission surface is removed — the source
  already no-ops without permissions).
- Location, contacts, calendar, activity-recognition, notification-listener
  permissions (all implemented by `activity/` collectors).
- Any behavior change in `activity/`.

## Steps

### Step 1: Remove Health Connect surface

Delete manifest lines 24–26 (three `android.permission.health.*` entries)
and the `<queries>` block (lines 31–33). Remove the
`health-connect-client` dependency from `app/build.gradle.kts` (~line 178)
and its catalog entry from `gradle/libs.versions.toml` if unreferenced.

**Verify**: `./gradlew assembleDebug` → exit 0. If compilation fails
because `HealthDataSource.kt` imports
`androidx.health.connect.client.*`, STOP (see STOP conditions).

### Step 2: Drop `canPerformGestures`

In `office_accessibility_service.xml`, remove
`canPerformGestures="true"`.

**Verify**: `./gradlew assembleDebug` → exit 0.

### Step 3: Verify `QUERY_ALL_PACKAGES` necessity, then align labels

1. `grep -rn "getInstalledPackages\\|queryIntentActivities\\|PackageManager" app/src/main/java/com/aliahad/aichat/activity/ | head -20`
   — if app enumeration for usage summaries relies on it, KEEP the
   permission and record that in the plan status; do not remove.
2. Change the two service labels to AIchat branding:
   `"AIchat notification memory"` and `"AIchat screen memory"` (match the
   app-name casing used in `@string/app_name`).

**Verify**: `./gradlew assembleDebug lint` → exit 0.

### Step 4: Full gate

**Verify**: `./gradlew testDebugUnitTest detekt assembleDebug` → exit 0.

## Test plan

- Compile + lint is the verification (manifest changes are declarative).
- If an arm64 device is available, launch the app once and confirm: app
  starts, Settings → phone sources still lists collectors, accessibility
  service still binds (labels changed only).
- No new unit tests (nothing testable changed).

## Done criteria

- [ ] `grep -n "health" app/src/main/AndroidManifest.xml` returns no matches
- [ ] `grep -n "healthdata" app/src/main/AndroidManifest.xml` returns no matches
- [ ] `grep -n "canPerformGestures" app/src/main/res/xml/office_accessibility_service.xml` returns no matches
- [ ] `grep -rn "wochat" app/src/main/AndroidManifest.xml` returns no matches
- [ ] `./gradlew testDebugUnitTest detekt assembleDebug` exit 0
- [ ] `git status` shows only in-scope files
- [ ] `plans/README.md` status row updated

## STOP conditions

Stop and report back (do not improvise) if:
- Removing `health-connect-client` breaks compilation of
  `HealthDataSource.kt` — the fallback is: keep the dependency, remove ONLY
  the manifest permissions, and report (dead code removal of
  `HealthDataSource` is a separate decision).
- `QUERY_ALL_PACKAGES` verification shows collectors would silently lose
  data — keep it and record the verdict.
- The working tree's in-flight branding work conflicts (drift).

## Maintenance notes

- When Health ingestion actually ships, re-add exactly the permissions the
  shipped code needs (likely STEPS/SLEEP only) with the `<queries>` entry.
- Store listing declarations must be updated to match the trimmed manifest
  (privacy-label hygiene).
- Deferred: allowlist-style notification capture (see plan 010 for the
  content side).
