# Plan 033: Remove phone sources entirely

> **Executor instructions**: Follow this plan step by step. Run every
> verification command and confirm the expected result before moving to the
> next step. If anything in the "STOP conditions" section occurs, stop and
> report — do not improvise. When done, update the status row for this plan
> in `plans/README.md`.
>
> **Drift check (run first)**:
> `git log --oneline -3 -- app/src/main/java/com/aliahad/aichat/activity/`

## Status

- **Priority**: P1
- **Effort**: L
- **Risk**: MED-HIGH (deletes a feature surface and drops database tables)
- **Depends on**: none. Blocks 034 only in the sense that doing 034 first
  would mean writing embeddings for rows this plan deletes.
- **Category**: removal / latency / privacy
- **Planned at**: commit `e4e1630`, 2026-08-16
- **Decided by**: the owner, explicitly — "let's completely remove phone
  source. We don't need phone source."

## Why this matters

Phone-source memories were measured to be the largest single cost in time to
first token, and they do not deliver the payload they exist for.

Audit on the owner's Redmi K80 Pro (release build, instrumented
`PromptContextPlanner`, 2026-08-16):

| query | memories injected | tokens | composition |
|---|---|---|---|
| "Explain gravity briefly" | 16 | 270 | 16/16 chat-message fragments, 6–19 chars each |
| "What is on my calendar today" | 16 | **1578** | 8 accessibility screen-scrapes of 261–1006 chars, **0 calendar events** |

At the measured ~21 tok/s prefill, that 1578-token block is ~75 s of latency
for a calendar question that returns no calendar data.

Two independent problems, both fatal to the feature as built:

1. **It cannot answer its own use case.** Retrieval is fuzzy lexical matching
   over LLM-compacted archive digests. Asking about the calendar surfaced
   accessibility blobs, not events. Passive ingestion is the wrong shape for
   "what is on my calendar" — that is a query, not a recollection.
2. **It is a privacy exposure.** `OfficeAccessibilityService` scrapes screen
   content from other apps into 1006-char episodic memories marked
   `MemorySensitivity.PRIVATE`, which are then injected into model prompts.

Note the intent gating is *not* the problem, and reviewers should not "fix"
it instead: raw activity events already pass through
`activityRetrievalIntent()` regex gates. The leak is that once events are
compacted into `rememberActivitySummaryRow(...)` they become ordinary
`MemoryType.EPISODE` rows that bypass the gate entirely, and AppSearch-indexed
rows are additionally exempt from the lexical floor via `floorExemptIds`.

## Current state

- `app/src/main/java/com/aliahad/aichat/activity/` — 1,576 lines across
  `ActivityRepository.kt`, `OfficeActivityServices.kt`,
  `OfficeActivityWorkers.kt`, `PhoneSourceAccessManager.kt`,
  `OneTimeCodeRedactor.kt`
- 25 files reference `ActivitySource` / `PhoneSource` / `activityDao` /
  `ActivityRepository`
- `AndroidManifest.xml`: `OfficeNotificationListenerService`,
  `OfficeAccessibilityService`, `PackageChangeReceiver`, and 10 permissions
  (location ×3, activity recognition, contacts, calendar, health ×3)
- `AppDatabase.kt` is at `version = 17`
- `MemoryRepository` carries `forgetActivitySource`, `rememberActivitySummaryRow`,
  `activityRetrievalIntent`, `applyPerSourceActivityQuota`, `perSourceActivityLimit`

## Scope

**In scope**: deleting the collectors, their services/receivers/permissions,
the activity tables and their DAOs, the phone-source UI, and every
activity-derived memory row.

**Out of scope**:
- The retrieval quality fixes and embeddings — plan 034.
- `residency/` (`ModelResidencyService`, `BootCompletedReceiver`). These keep
  the *model* warm and are unrelated to phone data. Do not delete them.
- Chat-derived memories. They stay (034 fixes their quality).

## Steps

### Step 1: Establish the before-state

Record the current DB row counts and TTFT so the removal can be shown to help
and not merely to delete. Capture with the app's diagnostics report, not by
guessing.

**Verify**: a recorded baseline exists in the status row.

### Step 2: Delete collection

Remove the `activity/` package, the two services, `PackageChangeReceiver`,
their manifest entries, and the 10 permissions. Remove the WorkManager
registrations so no worker is enqueued for a class that no longer exists.

**Verify**: `./gradlew :app:assembleRelease` exits 0; `adb shell dumpsys
package com.aliahad.aichat | grep -A 30 "requested permissions"` no longer
lists location/contacts/calendar/health.

### Step 3: Drop the tables and purge derived memories

Schema 17 → 18. The migration must **delete the activity-derived memory rows**
(`sourceKind = ACTIVITY`), not just the event tables — otherwise the digests
survive as ordinary memories and keep being injected, which would make this
whole plan a no-op for latency. Remove them from the AppSearch index too, or
`purgeStaleIndexDocs` will leave orphaned docs that still match queries.

**Verify**: a migration test asserting activity tables are gone AND no
`sourceKind = ACTIVITY` rows remain.

### Step 4: Remove the UI and the model types

Delete the phone-source screens/state, `PhoneSourceStatus`,
`PhoneSourceAccessState`, `ActivitySource`, `ActivitySourceStats`, and the
`MemorySourceKind.ACTIVITY` handling. Keep `MemorySourceKind` itself.

**Verify**: `./gradlew :app:testDebugUnitTest :app:compileDebugAndroidTestKotlin
:app:lint :app:detekt` all exit 0.

### Step 5: Re-measure

Repeat the Step 1 measurement on the same conversation.

**Verify**: memory block for a neutral question is materially smaller, and no
`episode/accessibility` entries can appear. Record real numbers — including a
null result if TTFT does not move.

## Test plan

- Unit test for the 17→18 migration, including the ACTIVITY-row purge.
- Full unit suite, lint, detekt.
- Instrumented suite on the physical device (57/57 is the known-good number).
- Manual: confirm the app starts, chats, and that Settings no longer offers
  phone sources.

## Done criteria

- [ ] `activity/` package gone; no services/receivers/permissions remain
- [ ] Schema at 18 with a tested migration
- [ ] No `sourceKind = ACTIVITY` rows and no orphaned AppSearch docs
- [ ] Unit + lint + detekt + instrumented all green
- [ ] Before/after TTFT recorded honestly in `plans/README.md`

## STOP conditions

Stop and report (do not improvise) if:
- Dropping the activity tables turns out to cascade into chat-message memories
  (i.e. chat memories are stored with an activity source id). Report the
  coupling before deleting anything.
- The owner's installed build has activity data they may want exported. This
  plan destroys it permanently; offer an export first.
- Removing the accessibility service breaks an unrelated feature that quietly
  depends on it.

## Maintenance notes

- The general rule this establishes: **the app does not passively ingest phone
  data.** If a future feature needs calendar or contacts, it queries them on
  demand at the moment of the question (see plan 035), rather than scraping
  them into memory in advance.
