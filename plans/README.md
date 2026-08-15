# Implementation Plans — Round 2 (UX & perceived quality)

Generated 2026-08-15 from a **user-experience audit** of commit `619d7de`
(tag v1.0.4). Execute in the order below unless dependencies say otherwise.
Each executor: read the plan fully before starting, run its drift check
first, honor its STOP conditions, and update your row when done.

## Why this round exists

Round 1 (plans 001–015, all shipped and since deleted in `52c2ae1`) ran four
audit tracks: correctness, security, performance/architecture/tests, and
dependencies/DX/direction. **None of them was a user-experience track.**
Every finding below survived that entire cleanup because nothing was looking
for it. Numbering continues at 016 because round-1 branch names
(`advisor/001-…` … `advisor/015-…`) are recorded in merged commit messages;
reusing 001–015 would make the history ambiguous.

## Execution order & status

| Plan | Title | Priority | Effort | Depends on | Status |
|------|-------|----------|--------|------------|--------|
| 016 | Move container + database init off the main thread | P1 | M | — | DONE (branch `advisor/wave1-ux-foundations`. AppContainer is now all-lazy and warmed on IO; `AppDatabase.kt` untouched — approved deviation from Step 1: moving *where* construction happens preserves the sweep→open→finish contract without editing the encryption path. Completing it required 017's async gate, because `AiChatViewModelFactory` otherwise dragged the database open back onto the main thread. Migration gate satisfied: `DatabaseMigrationInstrumentedTest` 4/4 PASS. Cold start 602-650ms → ~500ms on the phone.) |
| 017 | Splash screen and first-frame gate | P1 | S | 016 | DONE (branch `advisor/wave1-ux-foundations`. core-splashscreen; `Theme.Aichat.Starting` in values + values-night; `installSplashScreen()` before `super.onCreate()`; held on `containerWarm`. MainActivity's factory is `by lazy` and content is gated on warm — this is what removes the last main-thread database block. Bounded-failure path complete: warm-up runs in its own job and the flag releases on completion or after 8s, so a *hung* warm-up cannot strand the splash (the first version only handled warm-up *throwing*). Timeout never fires in practice. `AppInstrumentedTest` passes.) |
| 018 | StrictMode main-thread I/O gate (debug) | P1 | S | — | DONE (branch `advisor/wave1-ux-foundations`; thread + VM policies under `BuildConfig.DEBUG`, `penaltyLog()` only, installed before the `:vulkan` process check so that process is covered too. **It paid for itself immediately**: the first run surfaced a 64ms main-thread disk read that plan 016 had missed — `MainActivity.getModelSetupViewModel` → `AiChatViewModelFactory.create` → `AppContainer.inferenceBenchmarkRunner` (lazy, not warmed) → `DeviceContextIdentity.read()`. Fixed by warming every lazy the ViewModel factory can reach. After the fix, all app-attributed violations are 0ms.) |
| 019 | Composer draft: saveable and conversation-scoped | P1 | S | — | DONE (branch `advisor/wave1-ux-foundations`; draft moved into `ChatUiState.input` backed by SavedStateHandle, cleared via `clearDraft()` on a real conversation switch only. Scope grew: `clearDraft` was never called on switch at all, so staged **attachments and selected skills bled across conversations too** — fixed by the same change. Verified on the phone: typed BLEEDCHECK in conv B, switched to conv A, composer empty; ROTATESURVIVE survived a landscape/portrait round-trip.) |
| 020 | Navigation back stack + back handling | P1 | S | — | DONE (branch `advisor/wave1-ux-foundations`; `navigateTo` now pairs `popUpTo(findStartDestination) { saveState = true }` with `restoreState`, so leaving settings no longer pushes a duplicate CHAT entry. BackHandler added, enabled only while `isSending`, reusing the existing `onStop`. Verified on the phone: Settings → X close → back now exits to the launcher instead of returning to Settings.) |
| 021 | Attachment limit honesty | P2 | S | — | DONE (branch `advisor/wave1-ux-foundations`; new `stageAttachments(uris)` + pure `planAttachmentIntake()` counts against already-staged and reports 'Added X of Y'; MainActivity no longer does `uris.take(20)`; shared `MAX_MESSAGE_ATTACHMENTS`; disabled attach button now explains itself. 7 JVM unit tests. Correction to the plan's premise: `stageAttachmentNow` already reported the limit — the silent drop was only at the picker.) |
| 022 | Baseline Profile + profileinstaller | P1 | M | 018 | DONE (branch `advisor/wave1-ux-foundations`. Deviation, approved: the plugin is pinned to **1.5.0-rc01**, not the 1.4.1 stable line — 1.4.1 refuses to apply against AGP 9.2.1 with "Module `:app` is not a supported android module"; it is build-time only and never ships. Second deviation: Gradle's `generateReleaseBaselineProfile` could not run because MIUI blocks its session install (`INSTALL_FAILED_USER_RESTRICTED`), so the generator was driven via `adb install` + `am instrument` and the profile committed at `app/src/main/baseline-prof.txt`. Verified rather than assumed: merged ART profile went from **0 → 4232 app rules**, R8 expanded to 13500, and the shipped APK's `assets/dexopt/baseline.prof` grew 9124 → 10095 bytes. Profile is 4.9MB raw / ~303KB compressed. Startup timing delta NOT measured — that needs plan 023's macrobenchmark.) |
| 023 | Macrobenchmark: startup and streaming jank | P2 | M | 022 | DONE (branch `advisor/wave1-ux-foundations`. New `:benchmark` module; StartupTimingMetric cold/warm/hot plus a with/without-profile pair, and FrameTimingMetric scroll. Baselines in `benchmark/README.md`. **Measured result worth acting on: the plan-022 baseline profile is worth only ~10ms (~1.8%) of cold start on this device** — the round-2 backlog had argued it was the highest-value startup item, and measurement says otherwise. Settings scroll is clean (P50 2.0ms, P99 4.4ms). Deviation, approved by the owner against this plan's own STOP condition: one production line (`testTagsAsResourceId = true` at the app root) was needed because UiAutomator cannot see Compose testTags — semantics only, no runtime behaviour change. Not done: automated streaming measurement (needs the 4.8GB model and real inference) — a manual Perfetto protocol is documented instead; and `scrollMessageList` needs a seeded conversation to mean anything. CI is not gated on benchmarks.) |
| 024 | Streaming scroll smoothness | P1 | M | 023 | TODO |
| 025 | Incremental markdown rendering while streaming | P1 | M | 023 | TODO |
| 026 | Allow composing during generation | P2 | S | 019 | TODO |
| 027 | Error message quality and retry affordances | P2 | M | — | DONE (branch `advisor/wave1-ux-foundations`. `report(Throwable)` no longer falls back to `javaClass.simpleName`, so users can no longer be shown the literal string 'SQLiteConstraintException'. Important nuance found while doing it: many throws here carry text written deliberately for users — `error("Attachments exceed the 500 MB message limit.")`, `require { "The private attachment file is missing." }` — so this is a filter, not a blanket replacement; a presentability check keeps those and rejects anything reading like plumbing. Originals are still logged in full, and diagnostics are unchanged. Errors now carry an optional action + severity: model loading gets a real Retry (it fails transiently and succeeds on a second attempt), and important failures use SnackbarDuration.Indefinite with a dismiss action instead of vanishing in 4s. 10 JVM unit tests. Not done: string-resource extraction/localisation — noted as out of scope in the plan and still outstanding.) |
| 028 | Haptics and honest disabled states | P3 | S | — | TODO |
| 029 | Theming, insets, and accessibility pass | P3 | M | — | TODO |
| 030 | Docs drift + detekt wiring | P3 | S | — | TODO |
| 031 | App crashes when started while the device is locked | P1 | M | 016 | DONE (branch `advisor/wave1-ux-foundations`; typed `DatabaseLockedException` + `Context.isDeviceCurrentlyLocked()` using KeyguardManager; startup defers behind the keyguard and retries on ACTION_USER_PRESENT; `ModelResidencyController`'s three `userManager.isUserUnlocked` guards corrected to also require the device be unlocked *now* — the pre-existing guards used the Direct Boot signal, exactly the trap the plan warned about; `ModelResidencyService` no longer touches the container's lazy chain while locked. **Verified on the phone: launch while locked → process ALIVE, 0 fatals** (baseline: process DEAD, InvalidKeyException). Step 4 recovery VERIFIED on the phone: the same deferred process (pid 18300) became fully functional after a manual unlock, with no relaunch and 0 fatals across the whole locked-to-unlocked cycle. Step 5 message is implemented but not visually confirmable — a normal activity is not shown over a secure keyguard, so it only covers the edge case where the activity resumes while isDeviceLocked() is still true. Step 6: no automated regression test — the instrumented suite is red (see 032); the manual adb procedure is documented in the plan's "Commands you will need".) |
| 032 | Instrumented suite is red on main and nothing notices | P1 | M | — | DONE (branch `advisor/wave1-ux-foundations`; all 7 ChatViewModel failures were one root cause — no test main dispatcher, so `viewModelScope` collectors had not run when assertions sampled state; fixed with `Dispatchers.setMain(UnconfinedTestDispatcher())` + an awaited state for the DataStore-backed case. Backup failure was a stale fixture describing an `attachments` table shape that never shipped — production code was correct and untouched. 48/57 verified green on the device (ChatViewModel 5 runs in a row); the remaining 9 launch Activities and are blocked by a MIUI background-activity-start restriction, not by code. `continue-on-error` removed from the CI job. **FINAL: the full suite runs 57/57 OK on the device** once MIUI's "Display pop-up windows while running in background" is granted — that permission was blocking the 3 activity-launching classes. One extra failure surfaced in the full run (`AppInstrumentedTest#settingsNavigationShowsModelManagement` scrolling to the Gemma row before `ensureOfficialRecords` had seeded it) and was fixed test-side after confirming the product renders the catalog correctly on a real first launch. A run in between died with "Process crashed" — that was MIUI killing the app via `SwipeUpClean`, not a defect.) |

Status values: TODO | IN PROGRESS | DONE | BLOCKED (with one-line reason) |
REJECTED (with one-line rationale)

## Dependency notes

- 017 requires 016: the splash's `keepOnScreenCondition` is only correct once
  container init is asynchronous; wiring it first would hide a main-thread
  stall behind a splash instead of fixing it.
- 022 should land after 018 so StrictMode is already catching main-thread I/O
  when the profile generator runs.
- 023 requires 022 (same `benchmark/` module and Gradle plugin wiring).
- 024 and 025 both require 023 — each claims a smoothness win, and without
  `FrameTimingMetric` numbers neither can be shown to have worked. They also
  both edit `ChatScreen`/`MessageBubble` in `ui/AiChatApp.kt`; serialize them.
- 026 requires 019, because letting the user type during generation is only
  safe once the draft is conversation-scoped and survives recomposition.

## Suggested execution waves

1. **Trust** (parallel): 016, 019, 020, 021 — then 017 after 016.
2. **Instrumentation**: 018, then 022, then 023. Land before wave 3 so the
   streaming work has a scoreboard.
3. **Streaming feel** (serialize): 024, then 025, then 026. Plus 027.
4. **Polish** (parallel): 028, 029, 030.

## Explicitly deferred to a later round

- **DEBT-01: split the 3,481-line `ui/AiChatApp.kt`.** Deliberately *not*
  first. Plans 019/020/024/025/026/028 all make surgical edits inside it;
  splitting first would force every one of them to rebase onto moved code.
  Split afterwards, when the split can be a pure file move.
- Round-1 deferred items that remain untouched and still valid: BUG-10
  (attachment worker write-back race), BUG-12 (memory dedupe race), DEP-01
  (dependency verification), DEP-02 (SQLCipher AAR provenance), DX-03
  (release pipeline), DEBT-02/03 (turn-lifecycle dedupe), PERF-06, TEST-03,
  DIR-01/02.

## Audit provenance

- Commit audited: `619d7de` (2026-08-15, v1.0.4). Static reading only — no
  device run, no profiler, no Perfetto trace. Severity ordering for 016, 024
  and 025 is reasoned from the code, not measured; plan 023 exists precisely
  to replace that reasoning with numbers.
- Every finding was re-verified against `619d7de` after an initial pass had
  read a stale worktree view. Line references in these plans are from
  `619d7de`.
- Not re-audited: correctness, security, and dependency tracks (round 1
  covered those). Vendored `llama.cpp/` untouched as always.
