# Plan 016: Move container and database initialization off the main thread

> **Executor instructions**: Follow this plan step by step. Run every
> verification command and confirm the expected result before moving to the
> next step. If anything in the "STOP conditions" section occurs, stop and
> report — do not improvise. When done, update the status row for this plan
> in `plans/README.md` — unless a reviewer dispatched you and told you they
> maintain the index.
>
> **Drift check (run first)**:
> `git diff --stat 619d7de..HEAD -- app/src/main/java/com/aliahad/aichat/AiChatApplication.kt app/src/main/java/com/aliahad/aichat/data/AppDatabase.kt`
> If either file changed since this plan was written, compare the
> "Current state" excerpts against the live code before proceeding; on a
> mismatch, treat it as a STOP condition.

## Status

- **Priority**: P1
- **Effort**: M
- **Risk**: HIGH (touches app startup and the SQLCipher open path; a mistake
  here bricks cold start for every user, including the encryption migration)
- **Depends on**: none
- **Category**: bug / performance
- **Planned at**: commit `619d7de`, 2026-08-15

## Why this matters

Cold start currently performs, synchronously on the Android main thread:

1. `System.loadLibrary("sqlcipher")`
2. SQLCipher passphrase derivation (`DatabaseKeyManager.passphrase()`)
3. A plaintext→encrypted migration check that can rewrite the entire database
4. **All 16 Room migrations**, because `database.openHelper.writableDatabase`
   explicitly forces the open rather than letting Room open lazily
5. A residue sweep and a verification finish step (more file I/O)
6. Construction of `NativeInferenceEngine`, which loads native code

This runs inside `Application.onCreate()` — before the first frame can be
produced. On a device with real chat history this is an ANR-class stall, and
it is the single largest contributor to the app feeling slow to open. It is
also invisible to the current CI, which never launches the app.

Round 1 recorded this as deferred item BUG-06 ("main-thread DB open +
migrations + JNI load at startup"). Reading the code, it is worse than that
note implies: the `writableDatabase` call at line 531 makes the migration
work *mandatory* and *eager* rather than deferred to first query.

## Current state

- `app/src/main/java/com/aliahad/aichat/AiChatApplication.kt` — `onCreate()`
  builds the container synchronously:

```kotlin
// AiChatApplication.kt, onCreate()
override fun onCreate() {
    super.onCreate()
    if (Application.getProcessName() == "$packageName:vulkan") return
    container = AppContainer(this)          // <-- everything below happens here, on the main thread
    OfficeWorkScheduler.schedule(this)
    applicationScope.launch { /* reconciliation, already off-thread */ }
}
```

- `AppContainer` initializes `database` as its **first property**, so
  constructing the container runs `AppDatabase.create()` inline:

```kotlin
class AppContainer(val application: Application) {
    val database: AppDatabase = AppDatabase.create(application)
    val settings = AppSettingsRepository(application, TokenCipher(application))
    ...
    private val cpuInferenceEngine: InferenceEngine =
        NativeInferenceEngine(application, com.aliahad.aichat.core.BackendMode.CPU)
```

- `app/src/main/java/com/aliahad/aichat/data/AppDatabase.kt:500-535`:

```kotlin
fun create(context: Context): AppDatabase {
    System.loadLibrary("sqlcipher")
    val passphrase = DatabaseKeyManager(context).passphrase()
    val migrator = DatabaseEncryptionMigrator(context, DATABASE_NAME)
    migrator.migratePlaintextIfNeeded(passphrase)
    val database = Room.databaseBuilder(...)
        .openHelperFactory(SupportOpenHelperFactory(passphrase.copyOf()))
        .addMigrations(MIGRATION_1_2, ... MIGRATION_16_17)
        .build()
    migrator.sweepResidueFromFailedMigration()
    database.openHelper.writableDatabase        // line 531 — forces open + migrations NOW
    migrator.finishVerifiedMigration()
    passphrase.fill(0)
    return database
}
```

- `MainActivity.onCreate()` reads `(application as AiChatApplication).container`
  immediately (line 47) and again in `onStart`/`onStop`, so any change to
  container availability must keep those call sites safe.

Conventions: this codebase uses constructor-injected `AppContainer` (no DI
framework), `CoroutineScope(SupervisorJob() + Dispatchers.IO)` at application
scope, and `Log.e("AiChatApplication", ...)` for startup failures. Match it.

## Commands you will need

| Purpose | Command | Expected on success |
|---------|---------|---------------------|
| Unit tests | `./gradlew testDebugUnitTest` | exit 0 |
| Instrumented compile | `./gradlew compileDebugAndroidTestKotlin` | exit 0 |
| Debug APK | `./gradlew assembleDebug` | exit 0 |
| Migration tests | `./gradlew connectedDebugAndroidTest --tests '*DatabaseMigration*'` | exit 0 (needs a device) |

## Scope

**In scope**:
- `app/src/main/java/com/aliahad/aichat/AiChatApplication.kt`
- `app/src/main/java/com/aliahad/aichat/data/AppDatabase.kt` (only the eager
  `writableDatabase` line and, if needed, a `create` overload)
- `app/src/main/java/com/aliahad/aichat/MainActivity.kt` (container access)
- ViewModel factory wiring if it must await readiness

**Out of scope**:
- Any migration body (`MIGRATION_*`) — do not touch migration SQL.
- `DatabaseKeyManager` / `DatabaseEncryptionMigrator` internals. The
  encryption migration must keep running *before* the first query; you are
  changing *which thread* it runs on, never whether it runs.
- The `:vulkan` process early-return.
- Splash/first-frame UI — that is plan 017.

## Steps

### Step 1: Make the eager open explicit and cancellable

Remove `database.openHelper.writableDatabase` from `create()` and give the
class an explicit `suspend fun prepare()` (or an `openBlocking()` used only
from a background dispatcher) that performs, in order: the residue sweep, the
forced open, and `finishVerifiedMigration()`.

The ordering in the current code is load-bearing — sweep, open, finish — so
preserve it exactly. Do not let Room open lazily on an arbitrary caller's
thread instead; the verified-migration handshake depends on knowing when the
open completed.

**Verify**: `grep -n "openHelper.writableDatabase" app/src/main/java/com/aliahad/aichat/data/AppDatabase.kt`
→ the call appears only inside the new prepare path, not in `create()`.

### Step 2: Make container construction lazy or asynchronous

Choose the smaller change that works:

- **Preferred**: keep `AppContainer` construction synchronous but make its
  `database` property `by lazy`, and move the *preparation* (Step 1) into the
  existing `applicationScope.launch { }` block ahead of the reconciliation
  steps. Anything already inside that block is off-thread today.
- **Fallback**: expose `container` as a `Deferred<AppContainer>` /
  `StateFlow<AppContainer?>` and have callers await it.

Whichever you pick, `NativeInferenceEngine` construction must also leave the
main thread — check whether its constructor or an `init` block calls
`System.loadLibrary`; if it does, make that lazy too.

**Verify**: `./gradlew assembleDebug` → exit 0.

### Step 3: Keep every container consumer safe

`MainActivity` touches the container in `onCreate`, `onStart`, `onStop`, and
inside the Health Connect branch of `onRequestPhoneSourceAccess`. Audit each
with `grep -n "container" app/src/main/java/com/aliahad/aichat/MainActivity.kt`
and make sure none can observe a half-built or not-yet-ready container.

`AppShellViewModel.initialize()` is the natural place to await readiness and
publish `launchDestination` — plan 017 depends on that signal being honest.

**Verify**: `./gradlew compileDebugAndroidTestKotlin` → exit 0.

### Step 4: Prove the main thread is clean

Add a unit test asserting that constructing the container does not open the
database (for example, assert the database file's `writableDatabase` was not
requested, or that `create()` returns without touching disk). If the seams
don't allow a JVM test, say so explicitly in the status row rather than
inventing an elaborate harness — plan 018 (StrictMode) is the real net here.

**Verify**: `./gradlew testDebugUnitTest` → exit 0.

## Test plan

- `./gradlew testDebugUnitTest` — existing suite must stay green.
- `./gradlew connectedDebugAndroidTest --tests '*DatabaseMigration*'` — the
  migration suite is the safety net for Step 1. It **must** run before this
  is considered done. If no device is available, mark the plan BLOCKED rather
  than DONE.
- `MainActivityLifecycleInstrumentedTest` must still pass — it exercises the
  container access paths from Step 3.
- Manual: install on a device with an existing database and confirm the app
  still opens the *same* data (no re-migration, no data loss).

## Done criteria

- [ ] `create()` no longer forces the database open
- [ ] Neither `AppDatabase.create` nor `NativeInferenceEngine` construction
      runs on the main thread during `Application.onCreate()`
- [ ] Sweep → open → finish ordering preserved
- [ ] `./gradlew testDebugUnitTest` exits 0
- [ ] `./gradlew compileDebugAndroidTestKotlin` exits 0
- [ ] `./gradlew assembleDebug` exits 0
- [ ] Migration instrumented tests pass on a device
- [ ] No migration SQL modified (`git diff` on `AppDatabase.kt` shows only
      the `create`/prepare region)
- [ ] `plans/README.md` status row updated

## STOP conditions

Stop and report back (do not improvise) if:
- `migratePlaintextIfNeeded` or `finishVerifiedMigration` turns out to depend
  on being called on the same thread as the open, or on the main thread.
- Making the database lazy causes any migration instrumented test to fail —
  report which one and its assertion, do not "fix" the migration.
- A container consumer needs the database synchronously during
  `Application.onCreate()` for correctness (not convenience) — report which.
- No device is available to run the migration suite. Mark BLOCKED; this plan
  must not merge on unit tests alone.

## Maintenance notes

- The reason for the eager open is the verified-migration handshake, not
  performance. Anyone tempted to "simplify" it back to a lazy open should
  read `DatabaseEncryptionMigrator` first.
- Once plan 018 lands, a regression here shows up immediately as a StrictMode
  log line on every debug launch — that is the intended long-term guard.
