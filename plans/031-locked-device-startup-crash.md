# Plan 031: App crashes when started while the device is locked

> **Executor instructions**: Follow this plan step by step. Run every
> verification command and confirm the expected result before moving to the
> next step. If anything in the "STOP conditions" section occurs, stop and
> report — do not improvise. When done, update the status row for this plan
> in `plans/README.md`.
>
> **Drift check (run first)**:
> `git diff --stat 619d7de..HEAD -- app/src/main/java/com/aliahad/aichat/data/DatabaseEncryption.kt`

## Status

- **Priority**: P1
- **Effort**: M
- **Risk**: MED-HIGH (touches the database key path; a wrong fix weakens
  at-rest encryption)
- **Depends on**: 016 (which changes where the failure surfaces)
- **Category**: bug / crash
- **Planned at**: commit `619d7de`, 2026-08-15
- **Found**: empirically on the user's Xiaomi `24122RKC7C` (Android 16),
  during plan 016 verification — not by static analysis.

## Why this matters

Starting the app while the device is locked kills the process.

Measured A/B on the physical device, release builds, screen locked
(`mIsShowing=true`):

| build | process after launch | crash |
|---|---|---|
| `619d7de` baseline | **DEAD** | `java.security.InvalidKeyException: Keystore operation failed` |
| with plan 016 | alive | FATAL logged, process survived |

Root cause: the wrapping key that protects the SQLCipher passphrase is created
with `setUnlockedDeviceRequired(true)`, so the Keystore refuses to use it while
the device is locked:

```
Caused by: android.security.KeyStoreException: Device locked
  (internal Keystore code: -72)
  0: system/security/keystore2/src/enforcements.rs:577: device is locked.
  1: Error::Km(r#DEVICE_LOCKED)
```

Nothing anywhere handles that failure — it propagates out of
`DatabaseKeyManager.passphrase()` and takes the process with it.

This is reachable in normal use, not just under adb. The app registers a
`BootCompletedReceiver` for `BOOT_COMPLETED` **and** `USER_UNLOCKED`, a
`ModelResidencyService` foreground service with `stopWithTask="false"`, a
notification listener, and an accessibility service — all of which can bring
the process up before the user has unlocked after a reboot.

`setUnlockedDeviceRequired(true)` is a deliberate and good security property.
The bug is the absence of handling, not the requirement.

## Current state

- `app/src/main/java/com/aliahad/aichat/data/DatabaseEncryption.kt:56-71`:

```kotlin
private fun getOrCreateWrappingKey(): SecretKey {
    (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
    val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
    generator.init(
        KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setRandomizedEncryptionRequired(true)
            .setUnlockedDeviceRequired(true)
            .build(),
    )
    return generator.generateKey()
}
```

- `passphrase()` (line 23) calls `decrypt(...)` → `getOrCreateWrappingKey()`;
  neither catches `InvalidKeyException` / `KeyStoreException`.
- After plan 016 the first attempt happens inside
  `reconcileStartupStep("runtime warmup")`, which catches and logs — but the
  lazy is not memoized on failure, so every later consumer retries and throws
  again from an uncaught context.
- **Do not reach for `UserManager.isUserUnlocked()` — it is the wrong API here.**
  The codebase already uses it in `residency/BootCompletedReceiver.kt:30` and
  `residency/ModelResidencyController.kt:116,426,513`, so it is the obvious
  thing to copy, and it will not fix this bug.

  `isUserUnlocked()` is the **Direct Boot** signal: it reports whether
  credential-encrypted storage is available, i.e. whether the user has unlocked
  *at least once since boot*. It returns `true` while the screen is locked.
  The crash was reproduced in exactly that state (`mIsShowing=true`, user had
  unlocked earlier in the session), so an `isUserUnlocked()` guard would have
  passed straight through into the failing Keystore call.

  `setUnlockedDeviceRequired(true)` is enforced against whether the device is
  **currently** unlocked. The matching query is
  `KeyguardManager.isDeviceLocked()`. Use that, and treat
  `ACTION_USER_PRESENT` / `KeyguardManager` state as the retry trigger rather
  than `ACTION_USER_UNLOCKED` alone.

## Commands you will need

| Purpose | Command | Expected on success |
|---------|---------|---------------------|
| Unit tests | `./gradlew testDebugUnitTest` | exit 0 |
| Release APK | `./gradlew assembleRelease` | exit 0 |
| Lock the device | `adb -s <serial> shell input keyevent 26` | `mIsShowing=true` |
| Launch while locked | `adb -s <serial> shell am start -n com.aliahad.aichat/.MainActivity` | process stays alive |

## Scope

**In scope**:
- `app/src/main/java/com/aliahad/aichat/data/DatabaseEncryption.kt`
- `app/src/main/java/com/aliahad/aichat/AiChatApplication.kt` (retry on unlock)
- Background entry points that touch the container while possibly locked:
  `residency/`, `activity/OfficeActivityServices.kt`, the workers

**Out of scope**:
- **Removing `setUnlockedDeviceRequired(true)`.** That is the security
  property, not the bug. See STOP conditions.
- Changing the passphrase format or key alias — that would orphan every
  existing install's database.

## Steps

### Step 1: Reproduce

Lock the device, force-stop, launch, confirm the process dies on baseline.
Record the exact exception. This is the regression test's oracle.

**Verify**: `adb logcat -b crash -d | grep "Device locked"` → present.

### Step 2: Make the locked case a typed, expected outcome

Introduce an explicit "database unavailable because the device is locked"
result rather than letting `InvalidKeyException` escape. Catch it precisely —
`KeyStoreException` with a locked-device cause, or `InvalidKeyException`
wrapping one — and do **not** swallow unrelated key failures, which indicate
real corruption and must still be loud.

**Verify**: unit test asserting a locked-device failure maps to the new
result and an unrelated failure still throws.

### Step 3: Do not start work that needs the database while locked

Guard the entry points: `UserManager.isUserUnlocked()` before warm-up,
workers, and collector services. When locked, skip and wait — the app has no
useful work it can do without its database.

**Verify**: launch while locked → no FATAL in `logcat -b crash`.

### Step 4: Retry on unlock

Run the skipped warm-up when the device is actually unlocked, so the app
becomes usable without a manual relaunch. Note again that
`ACTION_USER_UNLOCKED` (Direct Boot) is **not** the right trigger on its own —
it fires once per boot and can arrive while the keyguard is still up.
`ACTION_USER_PRESENT`, or re-checking `KeyguardManager.isDeviceLocked()` when
the UI resumes, is what corresponds to the key becoming usable.

**Verify**: launch locked, unlock, confirm the app works without a restart.

### Step 5: Tell the user, if the UI is up

If an activity is visible on the lockscreen and cannot proceed, show a plain
message ("Unlock your phone to open your chats") instead of a blank screen or
a silent failure. Coordinate with plan 017's bounded splash failure path.

**Verify**: manual.

### Step 6: Regression test

Add an instrumented test that exercises the locked path if the harness allows
it; otherwise document the manual adb procedure from "Commands you will need"
in the plan status row and run it every release.

## Test plan

- Locked-launch A/B repeated at least 3× on the physical device.
- Unit tests for the exception mapping in Step 2.
- Confirm the unlocked path is completely unchanged (timings and behavior).
- Confirm an *unrelated* key failure still surfaces loudly.

## Done criteria

- [ ] Launching while locked no longer produces a FATAL or a dead process
- [ ] Unrelated keystore failures still fail loudly
- [ ] Work requiring the database is skipped, not attempted, while locked
- [ ] The app recovers on unlock without a manual relaunch
- [ ] `setUnlockedDeviceRequired(true)` still set; key alias unchanged
- [ ] `./gradlew testDebugUnitTest` and `assembleRelease` exit 0
- [ ] `plans/README.md` status row updated

## STOP conditions

Stop and report back (do not improvise) if:
- The only way to make it work while locked is dropping
  `setUnlockedDeviceRequired(true)` or changing the key alias. Both weaken or
  orphan at-rest encryption — that is a decision for the owner, not this plan.
- Existing installs turn out to have a key created *without* the flag (so the
  crash only affects newer installs) — report the split before changing
  anything.
- Guarding the collector services on unlock state loses activity data the app
  is expected to capture; report the trade-off.

## Maintenance notes

- The general rule: **anything that can run before first unlock must treat the
  database as unavailable, not assume it.**
- Reviewers: the acceptance question is "reboot the phone and don't unlock —
  does anything crash?"
