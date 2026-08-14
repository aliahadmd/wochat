# Plan 010: Redact one-time codes from captured notifications before they reach memory

> **Executor instructions**: Follow this plan step by step. Run every
> verification command and confirm the expected result before moving to the
> next step. If anything in the "STOP conditions" section occurs, stop and
> report — do not improvise. When done, update the status row for this plan
> in `plans/README.md` — unless a reviewer dispatched you and told you they
> maintain the index.
>
> **Drift check (run first)**: `git diff --stat 2dff7e6..HEAD -- app/src/main/java/com/aliahad/aichat/activity/`
> The activity collection system was overhauled recently (commit 7a434cf);
> verify current shape first.

## Status

- **Priority**: P2
- **Effort**: M
- **Risk**: LOW (additive redaction pass before persistence; only affects content that matches code patterns)
- **Depends on**: none
- **Category**: security
- **Planned at**: commit `2dff7e6`, 2026-08-15

## Why this matters

The notification listener persists `EXTRA_TEXT`/`EXTRA_BIG_TEXT` of every
notification from packages not on a substring denylist keyed on the PACKAGE
NAME ("authenticator", "password", "bank", …). Default SMS/email clients
and most branded bank/2FA apps don't match, so OTP/2FA codes are persisted
to `activity_events`, become Office Memory candidates, and can surface in
LLM prompt context — silently defeating 2FA secrecy for the feature's
lifetime. A content-level redaction pass closes the gap for all packages
at once.

## Current state

- `app/src/main/java/com/aliahad/aichat/activity/OfficeActivityServices.kt`
  — notification capture (lines 26–50) persists title/text into the
  activity DB via a `record(...)`-style call; the only content filtering is
  `SENSITIVE_PACKAGE_TERMS` substring matching against the package name
  (lines ~206–216). Password nodes are skipped in the accessibility path
  (line ~174), keyboard packages and own-app events excluded — those are
  fine and stay.

  (Read the file before editing — locate the exact `onNotificationPosted`
  handler and the `record` call; the surrounding worker/checkpoint plumbing
  in `OfficeActivityWorkers.kt` is out of scope.)

- Test conventions: `app/src/test/java/com/aliahad/aichat/` — plain JUnit4;
  see `CoreLogicTest.kt` for pure-function testing style.

## Commands you will need

| Purpose | Command | Expected on success |
|---------|---------|---------------------|
| Unit tests | `./gradlew testDebugUnitTest` | exit 0 |
| Detekt | `./gradlew detekt` | exit 0 |
| Build | `./gradlew assembleDebug` | exit 0 |

## Scope

**In scope**:
- `app/src/main/java/com/aliahad/aichat/activity/OfficeActivityServices.kt` (redaction call site)
- `app/src/main/java/com/aliahad/aichat/activity/OneTimeCodeRedactor.kt` (new, pure Kotlin)
- `app/src/test/java/com/aliahad/aichat/activity/OneTimeCodeRedactorTest.kt` (new)

**Out of scope**:
- The package-name denylist (keep it — defense in depth).
- Accessibility capture path (already skips password nodes).
- Memory indexing/retrieval code.

## Steps

### Step 1: Implement a pure redaction function

New file `activity/OneTimeCodeRedactor.kt`:

```kotlin
package com.aliahad.aichat.activity

/**
 * Replaces likely one-time verification codes with a placeholder before
 * notification content is persisted. Deliberately conservative: only
 * redacts digit/alphanumeric runs of 4–8 chars that appear near
 * verification-context words, or standalone 6–8 digit runs.
 */
internal object OneTimeCodeRedactor {
    private val CONTEXT_WORDS = Regex(
        "(?i)\\b(code|otp|pin|passcode|verification|verify|2fa|authenticate)\\b",
    )
    private val STANDALONE_CODE = Regex("""(?<![\w])(\d{6,8})(?![\w])""")
    private val CONTEXT_CODE = Regex("""(?i)(code|otp|pin|passcode|verification(?: code)?)[^\d\w]{0,12}([A-Za-z0-9]{4,8})""")

    fun redact(text: String): String = ...
}
```

Rules (implement all three; each individually conservative):
1. Standalone 6–8 digit runs → `[redacted code]`.
2. 4–8 char alphanumeric run within 0–12 non-alphanumeric chars after a
   context word → `[redacted code]`.
3. Never redact pure years/prices in obvious date/currency context? —
   NO such exception; rule 1 requires 6–8 digits with word boundaries,
   which excludes years in sentences like "in 2024 we…" only if surrounded
   by word chars. Keep it simple; false-positive redaction is an acceptable
   failure mode (better than leaking an OTP).

### Step 2: Wire into the notification path

In `OfficeActivityServices.kt`, at the single point where title/text are
prepared for persistence, apply `OneTimeCodeRedactor.redact(...)` to the
text (and title if titles carry codes — apply to both, titles are short).
If the redacted text becomes blank-but-was-nonempty, still record the
event with the placeholder text (keeps timing metadata useful).

### Step 3: Unit tests

`OneTimeCodeRedactorTest.kt` cases:
- "Your verification code is 482913" → code redacted.
- "Bank: OTP 8附近的字符" style — "OTP: X7K2Q9" → redacted.
- "Meeting at 10am in room 4" → unchanged.
- "Invoice #12345 for $1,234.56" → unchanged (5 digits with prefix `#`
  and currency context doesn't match rules — assert actual behavior; if it
  DOES match rule 2 via no context word, it stays unchanged because no
  context word is present).
- "code 1234" (4 digits + context) → redacted.
- "123456" alone → redacted (rule 1).
- Long digit strings (12+) → unchanged (too long for an OTP).

**Verify**: `./gradlew testDebugUnitTest --tests '*OneTimeCodeRedactor*'` → all pass.

### Step 4: Full gate

**Verify**: `./gradlew testDebugUnitTest detekt assembleDebug` → exit 0.

## Test plan

- The redactor is pure Kotlin → fully unit-testable (Step 3).
- Pattern: plain JUnit4 like `CoreLogicTest.kt`.
- On-device verification (optional): post a fake notification with an OTP
  via `adb shell cmd notification post` and confirm the stored activity
  event shows the placeholder.

## Done criteria

- [ ] `./gradlew testDebugUnitTest` exits 0 with new redactor tests
- [ ] Notification persistence path routes text through the redactor
- [ ] Package denylist untouched; accessibility path untouched
- [ ] `git status` shows only the three in-scope files
- [ ] `plans/README.md` status row updated

## STOP conditions

Stop and report back (do not improvise) if:
- `OfficeActivityServices.kt` has drifted such that there is no single
  persistence point for notification text (multiple record sites) —
  report the sites rather than sprinkling calls.
- You feel tempted to redact via the LLM or add network calls — neither is
  in scope.

## Maintenance notes

- The redactor is intentionally dumb and local; do not "upgrade" it to
  something with false negatives.
- The structural follow-up (deferred, product decision): an allowlist where
  the user picks WHICH apps contribute notifications — strictly better
  privacy posture than denylist+redaction.
- New locales: context words are English-only for now; extending the word
  list is a safe, additive change.
