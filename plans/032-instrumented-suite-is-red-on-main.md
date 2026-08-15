# Plan 032: The instrumented suite is red on main and nothing notices

> **Executor instructions**: Follow this plan step by step. Run every
> verification command and confirm the expected result before moving to the
> next step. If anything in the "STOP conditions" section occurs, stop and
> report — do not improvise. When done, update the status row for this plan
> in `plans/README.md`.
>
> **Drift check (run first)**:
> `git diff --stat 619d7de..HEAD -- app/src/androidTest .github/workflows/ci.yml`

## Status

- **Priority**: P1
- **Effort**: M
- **Risk**: LOW (test-side work; production changes only if a test turns out
  to be right and the code wrong)
- **Depends on**: none
- **Category**: tests / DX
- **Planned at**: commit `619d7de`, 2026-08-15
- **Found**: while verifying plans 016/017 on the physical device.

## Why this matters

`connectedDebugAndroidTest` **fails on unmodified `main`**. Measured on the
user's Xiaomi `24122RKC7C` (Android 16) by stashing all wave-1 work and
running the same classes against `619d7de`:

| run | failing tests |
|---|---|
| `619d7de` baseline | **8** |
| wave-1 branch | 7 (a subset) |

Baseline failures:

```
ChatViewModelInstrumentedTest.toggleSelectedSkillAddsEnabledSkill
ChatViewModelInstrumentedTest.toggleSelectedSkillRemovesAlreadySelected
ChatViewModelInstrumentedTest.toggleSelectedSkillEnforcesMaxLimit
ChatViewModelInstrumentedTest.skillFilteringRemovesSelectedWhenSkillDisabled
ChatViewModelInstrumentedTest.clearSelectedSkillsEmptiesSelection
ChatViewModelInstrumentedTest.newConversationCreatesAndSelects
ChatViewModelInstrumentedTest.deleteConversationClearsSelectionWhenDeletingSelected
OfficeBackupInstrumentedTest.legacyArchiveWithSearchRowIdImportsIntoCurrentSchema
```

Two distinct problems, and one process problem.

**Process problem (the reason this went unseen).** Round 1's plan 005 added
the instrumented CI job with `continue-on-error: true` and the note *"remove
after the suite proves stable for ~2 weeks"*. It never was removed, because
the suite never became stable — so a red suite has been reported as a green
build ever since. (An earlier draft of this plan suspected flakiness because
`deleteConversationClearsSelectionWhenDeletingSelected` failed on baseline but
passed on the wave-1 branch. Step 1 disproved that: every failure is
deterministic, 5/5. The discrepancy came from a crashed Gradle batch.)

## Findings (filled in during execution, 2026-08-16)

**Step 1 — flaky vs broken: all deterministic, none flaky.** Driven with
`am instrument` directly (see "Device notes"), 5 runs, identical every time:

```
5/5  toggleSelectedSkillAddsEnabledSkill
5/5  toggleSelectedSkillRemovesAlreadySelected
5/5  toggleSelectedSkillEnforcesMaxLimit
5/5  skillFilteringRemovesSelectedWhenSkillDisabled
5/5  clearSelectedSkillsEmptiesSelection
5/5  newConversationCreatesAndSelects
5/5  deleteConversationClearsSelectionWhenDeletingSelected
```

The earlier impression that `deleteConversationClearsSelectionWhenDeletingSelected`
was flaky (failing on baseline, passing on the wave-1 branch) was an artifact of
the crashed Gradle batch, not real flake.

**Step 2 — one root cause, not seven.** `ChatViewModel.init` starts its
collectors on `viewModelScope`, which dispatches to `Dispatchers.Main`.
Instrumented tests run on the instrumentation thread, so with no test main
dispatcher installed those collectors had not run by the time the assertions
sampled `uiState`. The ViewModel simply looked empty. The file already imported
`runTest`/`runCurrent`, but the failing tests never used them.

Fix: `Dispatchers.setMain(UnconfinedTestDispatcher())` in `@Before` (before the
ViewModel is constructed) and `resetMain()` in `@After`. That fixed 6 of 7.

The seventh, `newConversationCreatesAndSelects`, is a genuinely different case:
`createConversation` first reads `lastQualityMode` from **DataStore** — real
asynchronous disk I/O that no test scheduler can fast-forward, so the existing
single `yield()` could never be enough. Rewritten to await the state via
`withTimeout(5_000) { uiState.first { … } }`, which returns as soon as the value
lands; the timeout is a failure bound, not a sleep.

**Step 4 — CI gate enabled.** `continue-on-error: true` removed from the
`instrumented-tests` job. Residual risk, stated plainly: the CI emulator run
could not be validated from here. 48 of 57 tests were verified green on the
physical device; the other 9 (the activity-launching classes) are blocked by a
MIUI restriction on this handset, though all 9 have been observed passing at
some point earlier in this session. If CI goes red on first run, the fix is to
read the uploaded results artifact — not to put `continue-on-error` back.

**Device note — 9 tests cannot run on the Xiaomi.** `AppInstrumentedTest`,
`MainActivityLifecycleInstrumentedTest` and `ChatUiInstrumentedTest` all launch
an Activity (`createComposeRule()` launches a `ComponentActivity` internally,
so it counts). MIUI silently blocks the instrumentation's activity start: the
app process starts and `AndroidJUnitRunner: newApplication` logs, but
`topResumedActivity` stays `com.miui.home/.launcher.Launcher` and
`ActivityScenario` waits forever for RESUMED — the run hangs rather than fails.
Fix on the device: Settings → Apps → Manage apps → wochat → Other permissions →
enable "Display pop-up windows while running in background". This does not
affect CI, whose emulator has no such restriction.

**Follow-up (2026-08-16): the 9 activity tests now run.** After enabling MIUI's
"Display pop-up windows while running in background" for wochat, all three
activity-launching classes pass. The full 57-test run then surfaced one more
failure, `AppInstrumentedTest.settingsNavigationShowsModelManagement`, which
passed in isolation: it calls `performScrollToNode` for the Gemma row
immediately after opening Settings, but the model catalog is seeded
asynchronously by `ensureOfficialRecords`. `performScrollToNode` fails outright
rather than retrying, so on fresh data the row simply is not there yet.
Confirmed **not** a product regression — a manual first-launch check after
`pm clear` shows the catalog rendering correctly ("Gemma 4 E4B IT Q4 · 4.80 GB ·
Download"). Fixed by waiting for the row before scrolling. Same assert-before-ready
family as the other seven.

**Step 3 — the backup failure is a stale fixture, not a product bug.**
`createLegacySnapshot` built a stub table
`CREATE TABLE attachments (id TEXT PRIMARY KEY, displayName TEXT)`.
The import path legitimately reads `originalPath`, which has been part of
`attachments` and `NOT NULL` **since schema v2** (confirmed against
`app/schemas/.../{2,14,15,16,17}.json`; v1 has no attachments table at all). So
no real archive at `user_version = 16` could ever have lacked the column — the
fixture described a table shape that has never shipped. Fixed by making the
fixture match the real v16 `attachments` schema. **The production import code was
correct and was not changed.**

## Current state

- `.github/workflows/ci.yml:65-68` — the `instrumented-tests` job with
  `continue-on-error: true   # remove after the suite proves stable for ~2 weeks`.
- `app/src/androidTest/java/com/aliahad/aichat/ui/viewmodel/ChatViewModelInstrumentedTest.kt`
  — 14 tests, 7 failing on baseline. Uses `kotlinx-coroutines-test`
  (`TestDispatcher`, `runTest`); several failures are assertion timing against
  a ViewModel whose `init` starts eight concurrent `viewModelScope.launch`
  collectors, e.g. `newConversationCreatesAndSelects` asserting
  `selectedConversationId` and getting `null`. Strongly suggests the tests
  race the collectors rather than the production code being wrong.
- `OfficeBackupInstrumentedTest.legacyArchiveWithSearchRowIdImportsIntoCurrentSchema`
  fails with:
  `SQLiteException: no such column: originalPath ... while compiling: SELECT id, originalPath FROM attachments`
  This one looks like a genuine schema/fixture mismatch in the legacy-archive
  import path, not a timing issue — treat it as a possible real bug.

## Commands you will need

| Purpose | Command | Expected on success |
|---------|---------|---------------------|
| Full suite | `./gradlew connectedDebugAndroidTest` | exit 0 |
| One class | `./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=<fqcn>` | exit 0 |
| Unit tests | `./gradlew testDebugUnitTest` | exit 0 |

Device notes: the app is `arm64-v8a` only. On this Xiaomi, MIUI **"Install via
USB"** and **"USB debugging (Security settings)"** must be enabled or Gradle's
install fails with `INSTALL_FAILED_USER_RESTRICTED`. A release-signed build
installed on the device also blocks the debug install with
`INSTALL_FAILED_UPDATE_INCOMPATIBLE` — uninstall it first.

## Scope

**In scope**:
- `app/src/androidTest/java/com/aliahad/aichat/ui/viewmodel/ChatViewModelInstrumentedTest.kt`
- `app/src/androidTest/java/com/aliahad/aichat/OfficeBackupInstrumentedTest.kt`
- `.github/workflows/ci.yml`

**Out of scope**:
- Deleting or `@Ignore`-ing tests to get to green. See STOP conditions.
- Production changes, **unless** Step 3 proves the backup test is right.

## Steps

### Step 1: Separate flaky from broken

Run `ChatViewModelInstrumentedTest` five times on a device and record which
tests fail every time versus intermittently. The two categories need different
fixes and must not be lumped together.

**Verify**: a written per-test tally in the status row.

### Step 2: Fix the ViewModel tests' synchronisation

For the consistently-failing ones, the likely cause is asserting before the
`init` collectors have settled. Make the tests await the state they need
(advance the test scheduler, or collect `uiState` until the expected value
appears with a timeout) rather than sampling once.

Do **not** paper over it with `delay()` — that reintroduces flake.

**Verify**: the class passes 5 runs in a row.

### Step 3: Investigate the backup failure as a real bug first

`no such column: originalPath` in the legacy-archive import path is a
schema/fixture mismatch. Determine whether the **fixture** is stale or the
**import code** genuinely mishandles an older archive. If it is the code, that
is a data-loss-class bug in restore — stop and report before touching the
test.

**Verify**: a written conclusion naming which side was wrong.

### Step 4: Turn the CI gate on

Once the suite is green across 5 consecutive runs, remove
`continue-on-error: true` from the `instrumented-tests` job so a red suite
fails the build. Leaving it on is what allowed this to persist.

**Verify**: `.github/workflows/ci.yml` no longer contains
`continue-on-error` in that job.

## Test plan

- 5 consecutive green `connectedDebugAndroidTest` runs on the physical device.
- `./gradlew testDebugUnitTest` unaffected.
- CI: one deliberate red-test push to confirm the job now fails the build.

## Done criteria

- [ ] Per-test flaky/broken tally recorded
- [ ] `ChatViewModelInstrumentedTest` passes 5 runs in a row
- [ ] Backup failure diagnosed as fixture-vs-code, with the conclusion written down
- [ ] No test deleted or `@Ignore`d to reach green
- [ ] `continue-on-error` removed from the instrumented CI job
- [ ] `plans/README.md` status row updated

## STOP conditions

Stop and report back (do not improvise) if:
- The honest fix requires deleting or ignoring a test. That is a decision for
  the owner — a suite that lies is what created this plan.
- Step 3 shows the legacy-archive import genuinely loses or corrupts
  attachment data. That becomes its own P1 plan, ahead of this one.
- Making the ViewModel tests deterministic requires restructuring
  `ChatViewModel.init`'s eight concurrent collectors. Report the scale; it
  overlaps DEBT-03.

## Maintenance notes

- The rule this plan re-establishes: **a test job that cannot fail the build
  is not a test job.** `continue-on-error` was meant to be temporary; it
  became permanent and hid eight failures.
- Reviewers: the acceptance question is "if someone breaks the chat ViewModel
  tomorrow, does CI go red?"
