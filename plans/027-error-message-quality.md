# Plan 027: Error message quality and retry affordances

> **Executor instructions**: Follow this plan step by step. Run every
> verification command and confirm the expected result before moving to the
> next step. If anything in the "STOP conditions" section occurs, stop and
> report — do not improvise. When done, update the status row for this plan
> in `plans/README.md` — unless a reviewer dispatched you and told you they
> maintain the index.
>
> **Drift check (run first)**:
> `git diff --stat 619d7de..HEAD -- app/src/main/java/com/aliahad/aichat/ui/viewmodel/UiCoordinators.kt app/src/main/java/com/aliahad/aichat/ui/AiChatApp.kt`

## Status

- **Priority**: P2
- **Effort**: M
- **Risk**: LOW-MED (touching error paths risks swallowing information that
  is currently reaching diagnostics)
- **Depends on**: none
- **Category**: UX
- **Planned at**: commit `619d7de`, 2026-08-15

## Why this matters

Every error in the app reaches the user as a raw exception string:

```kotlin
// ui/viewmodel/UiCoordinators.kt:23-25
fun report(error: Throwable) {
    _error.value = error.message ?: error.javaClass.simpleName
}
```

When a `Throwable` has no message — common for many Android and SQLite
exceptions — the user sees a **Java class name** in a snackbar:
`SQLiteConstraintException`, `IOException`, `IllegalStateException`. When it
does have a message, that message was written for a developer reading logs.

Compounding it, the snackbar is fire-and-forget:

```kotlin
// AiChatApp.kt:230-235
LaunchedEffect(shellState.error) {
    shellState.error?.let {
        snackbarHost.showSnackbar(it)
        shellActions.clearError()
    }
}
```

No action button, no retry, no distinction between "your download paused,
tap to resume" and "the database is corrupt". For a privacy-focused offline
app where the user cannot ask a support team what happened, this matters more
than usual — the app is the only source of explanation.

## Current state

- `UiMessageManager` (`ui/viewmodel/UiCoordinators.kt:19-34`) — documented as
  "One activity-scoped destination for actionable errors from every feature",
  with `report(Throwable)`, `report(String)`, and `clear()`. The
  `Throwable` overload is the problem; the `String` overload is fine and is
  what well-behaved call sites should use.
- `FeatureViewModels.kt:49` collects into `ChatUiState`/shell state.
- `AiChatApp.kt:213` — a single `SnackbarHostState`; `AiChatApp.kt:276` wires
  the host into the `Scaffold`.
- The app already has a `diagnostics/` package and a diagnostics export
  (`onExportDiagnostics`, `MainActivity.kt:241-243`) — that is where technical
  detail belongs, and it already exists.
- Long-running, failure-prone flows that deserve specific treatment: model
  and projector download (pause/resume/retry already exist as actions in
  `ModelSetupViewModel`), backup import/export, and inference backend
  fallback.

## Commands you will need

| Purpose | Command | Expected on success |
|---------|---------|---------------------|
| Unit tests | `./gradlew testDebugUnitTest` | exit 0 |
| Instrumented compile | `./gradlew compileDebugAndroidTestKotlin` | exit 0 |
| Debug APK | `./gradlew assembleDebug` | exit 0 |

## Scope

**In scope**:
- `app/src/main/java/com/aliahad/aichat/ui/viewmodel/UiCoordinators.kt`
- `app/src/main/java/com/aliahad/aichat/ui/AiChatApp.kt` (snackbar wiring)
- Call sites of `report(Throwable)` that need a human message instead

**Out of scope**:
- Changing what is *logged* or what diagnostics captures. Technical detail
  must keep flowing there unchanged — this plan changes what the *user* sees.
- Localization / string resource extraction. Worth doing, but it is a
  separate sweep across the whole app; note it as a follow-up.
- Retry logic itself for downloads — those actions already exist.

## Steps

### Step 1: Inventory the call sites

`grep -rn "\.report(" app/src/main/java` and classify each: which are
transient and retryable (network, download, file access), which are
user-correctable (wrong passphrase, no space left), and which are genuinely
unexpected.

**Verify**: written inventory in the status row or a scratch note.

### Step 2: Make the Throwable overload safe

`report(Throwable)` must never put a raw class name in front of a user.
Change it to map known exception types to human sentences, with a generic
but honest fallback ("Something went wrong opening that file") — while
continuing to log the original for diagnostics.

Keep `report(String)` as the preferred path and make that explicit in the
KDoc.

**Verify**: `grep -n "javaClass.simpleName" app/src/main/java/com/aliahad/aichat/ui/viewmodel/UiCoordinators.kt`
→ no matches in user-facing output.

### Step 3: Add actions where retry is real

Extend the error model so a message can carry an optional action label and
callback, and use `SnackbarHost`'s action support. Apply it where a retry
genuinely exists — download failures being the clearest case, since
`startDownload`, `pauseDownload`, and the retry actions already exist on
`ModelSetupViewModel`.

Do not add an action button that merely dismisses.

**Verify**: `./gradlew assembleDebug` → exit 0.

### Step 4: Match duration to severity

A transient notice and a "your import failed" both currently get the same
default snackbar. Use `SnackbarDuration` deliberately, and consider whether
genuinely blocking failures belong in a dialog or inline in the affected
screen rather than a transient snackbar that can be missed entirely.

Do not convert everything to dialogs — that trades one bad extreme for
another.

**Verify**: manual review of the three worst cases from Step 1.

### Step 5: Point users at diagnostics for the unexplainable

For the genuinely-unexpected class, the honest message is a plain sentence
plus a route to the existing diagnostics export. Wire that rather than
inventing new technical surfacing.

**Verify**: manual check.

### Step 6: Test the mapping

Add JVM unit tests for the exception-to-message mapping, including the
no-message `Throwable` case that produces the class name today.

**Verify**: `./gradlew testDebugUnitTest` → exit 0, new tests present.

## Test plan

- New unit tests covering: exception with message, exception without message,
  each mapped type, and the fallback.
- Manual: trigger a download failure and confirm the message is human and the
  retry action works.
- Confirm diagnostics export still contains the technical detail.

## Done criteria

- [ ] Call-site inventory completed and recorded
- [ ] No user-facing path can surface a Java class name or a developer message
- [ ] Technical detail still reaches logs and diagnostics unchanged
- [ ] Retryable failures offer a real retry action
- [ ] Duration/presentation matches severity
- [ ] Mapping covered by unit tests
- [ ] `./gradlew testDebugUnitTest`, `compileDebugAndroidTestKotlin`,
      `assembleDebug` all exit 0
- [ ] `plans/README.md` status row updated

## STOP conditions

Stop and report back (do not improvise) if:
- Mapping exceptions to messages would require catching broader exception
  types at call sites, changing control flow.
- Some errors turn out to reach the user through a path other than
  `UiMessageManager` — report the second channel rather than patching both
  blindly.
- Adding actions to the error model requires restructuring how every feature
  ViewModel reports errors.

## Maintenance notes

- The rule to enforce in review: `report(Throwable)` is for *logging plus a
  safe fallback*; any error the user is expected to act on should call
  `report(String)` with a written sentence.
- Reviewers: the acceptance question is "could a non-developer read this
  message and know what to do next?"
