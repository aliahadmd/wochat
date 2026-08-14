# Plan 008: Backup crypto hardening — plaintext residue, import caps, passphrase handling, strength feedback

> **Executor instructions**: Follow this plan step by step. Run every
> verification command and confirm the expected result before moving to the
> next step. If anything in the "STOP conditions" section occurs, stop and
> report — do not improvise. When done, update the status row for this plan
> in `plans/README.md` — unless a reviewer dispatched you and told you they
> maintain the index.
>
> **Drift check (run first)**: `git diff --stat 2dff7e6..HEAD -- app/src/main/java/com/aliahad/aichat/backup/ app/src/main/java/com/aliahad/aichat/data/DatabaseEncryption.kt app/src/main/java/com/aliahad/aichat/MainActivity.kt app/src/main/java/com/aliahad/aichat/ui/AiChatApp.kt`
> `ui/AiChatApp.kt` is a 3,200-line file other plans avoid; if it changed,
> re-locate the passphrase dialog by searching `passphrase` before editing.

## Status

- **Priority**: P1
- **Effort**: M
- **Risk**: MED (touches the backup/restore and migration-failure paths; the instrumented round-trip test is the safety net)
- **Depends on**: 005 (the `OfficeBackupInstrumentedTest` round-trip must actually RUN in CI to validate this plan; local `testDebugUnitTest` cannot cover it)
- **Category**: security
- **Planned at**: commit `2dff7e6`, 2026-08-15

## Why this matters

For a privacy-first app whose core promise is encryption-at-rest, the
backup and legacy-migration code currently writes complete PLAINTEXT copies
of every conversation, memory, and activity record to flash:

1. Export `sqlcipher_export`s the whole DB to a plaintext snapshot under
   `cacheDir/office-backup/` before encrypting it into the zip; deletion
   (`working.deleteRecursively()`) does not zero flash.
2. Legacy plaintext→SQLCipher migration keeps `aichat.db.plaintext-backup`
   until `finishVerifiedMigration()` after Room open succeeds — a crash
   mid-migration leaves it until the next successful launch.
3. Import extracts decrypted entries to disk BEFORE the GCM tag is verified
   (tag check happens at stream close), with caps of 8 GiB/entry and
   64 GiB/archive — a hostile/corrupt backup can exhaust device storage
   before authentication fails.
4. The passphrase travels as immutable Kotlin `String` from the Compose
   dialog to the repository, which already zeroizes its `CharArray` copies —
   the String copies linger on the heap.
5. The only passphrase policy is "≥ 8 characters" with no strength signal.

## Current state

- `app/src/main/java/com/aliahad/aichat/backup/OfficeBackupRepository.kt`
  (653 lines) — `EncryptedOfficeBackupRepository`.

```kotlin
// OfficeBackupRepository.kt:49-82 (export, abridged)
override suspend fun export(uri: Uri, passphrase: CharArray) = withContext(Dispatchers.IO) {
    require(passphrase.size >= 8) { "Use a backup passphrase with at least 8 characters" }
    val working = createWorkingDirectory("export")
    try {
        val snapshot = File(working, DATABASE_ENTRY)
        exportPlaintextSnapshot(snapshot)
        ...
        working.deleteRecursively()
    } finally {
        passphrase.fill('\u0000')
        working.deleteRecursively()
    }
}
```

```kotlin
// OfficeBackupRepository.kt:572-575
private fun createWorkingDirectory(prefix: String): File =
    File(context.cacheDir, "office-backup/$prefix-${UUID.randomUUID()}").apply {
        check(mkdirs()) { "Unable to prepare backup storage" }
    }
```

```kotlin
// OfficeBackupRepository.kt:625-626
const val MAX_ENTRY_BYTES = 8L * 1024 * 1024 * 1024
const val MAX_ARCHIVE_BYTES = 64L * 1024 * 1024 * 1024
```

  Import extraction streams decrypted bytes to staging with only those
  caps (lines 271–296, `decryptAndExtract`), then `commitImport` copies
  into the live DB (lines ~135–140 re-verify containment). Manifest is
  created with a per-table row count (`createManifest(snapshot,
  attachments.size)`).

- `app/src/main/java/com/aliahad/aichat/data/DatabaseEncryption.kt`:

```kotlin
// DatabaseEncryption.kt:88-89, 120-122, 157-167 (abridged)
private val backupFile: File = File(databaseFile.parentFile, "$databaseName.plaintext-backup")
private val encryptedTemp: File = File(databaseFile.parentFile, "$databaseName.encrypted-tmp")
...
fun finishVerifiedMigration() {
    if (backupFile.exists()) deleteDatabaseFiles(backupFile)
}
...
private fun replacePlaintextWithEncrypted() {
    deleteDatabaseFiles(backupFile)
    check(databaseFile.renameTo(backupFile)) { "Unable to preserve the plaintext database" }
    ...
}
```

  `AppDatabase.kt:521-522` calls `finishVerifiedMigration()` only after a
  successful Room open — the crash window.

- `app/src/main/java/com/aliahad/aichat/MainActivity.kt:57-64`:

```kotlin
var pendingExportPassphrase by remember { mutableStateOf<String?>(null) }
...
val passphrase = pendingExportPassphrase
pendingExportPassphrase = null
if (uri != null && passphrase != null) {
    memoryViewModel.exportOfficeBackup(uri, passphrase)
}
```

  The passphrase dialog lives in `ui/AiChatApp.kt` (~lines 2061–2090; the
  dialog state field is `passphrase: String`, hint text "At least 8
  characters. It cannot be recovered.").
  `MemoryViewModel.exportOfficeBackup/selectOfficeBackup` take `String`.

## Commands you will need

| Purpose | Command | Expected on success |
|---------|---------|---------------------|
| Unit tests | `./gradlew testDebugUnitTest` | exit 0 |
| Detekt | `./gradlew detekt` | exit 0 |
| Debug build | `./gradlew assembleDebug` | exit 0 |
| Backup round-trip (device/emulator, if available) | `./gradlew connectedDebugAndroidTest --tests '*OfficeBackup*'` | all pass |

## Scope

**In scope**:
- `app/src/main/java/com/aliahad/aichat/backup/OfficeBackupRepository.kt`
- `app/src/main/java/com/aliahad/aichat/data/DatabaseEncryption.kt` (sweep only)
- `app/src/main/java/com/aliahad/aichat/data/AppDatabase.kt` (call-site for the sweep, ~line 521)
- `app/src/main/java/com/aliahad/aichat/MainActivity.kt` (passphrase typing)
- `app/src/main/java/com/aliahad/aichat/ui/AiChatApp.kt` (passphrase dialog only)
- `app/src/main/java/com/aliahad/aichat/ui/viewmodel/MemoryViewModel.kt` (passphrase typing)

**Out of scope**:
- Changing the backup FORMAT (magic/version/KDF stay identical — old
  backups must remain importable).
- Streaming per-entry encryption for export (the bigger redesign; see
  maintenance notes — this plan shrinks the residue instead).
- `BackupPathSafety.kt` (already correct).

## Steps

### Step 1: Shrink the migration crash window to zero-at-next-boot

In `DatabaseEncryptionMigrator`, add a public sweep method:

```kotlin
fun sweepResidueFromFailedMigration() {
    // Safe to run unconditionally: these files only exist mid-migration.
    if (isPlaintextDatabase(databaseFile)) return  // migration still pending; keep sources
    if (backupFile.exists()) deleteDatabaseFiles(backupFile)
    if (encryptedTemp.exists()) deleteDatabaseFiles(encryptedTemp)
}
```

Call it from `AppDatabase.kt` BEFORE the encrypted DB is opened (locate
the create sequence around line 521 where `finishVerifiedMigration()` is
invoked after open; add the sweep immediately before
`database.openHelper.writableDatabase`). Rationale: a leftover
`plaintext-backup` with an already-encrypted main DB is pure residue.

**Verify**: `./gradlew compileDebugKotlin` → exit 0.

### Step 2: Overwrite-before-delete for the export snapshot

In the export `finally` block, before `working.deleteRecursively()`, add a
best-effort overwrite pass:

```kotlin
private fun shred(file: File) {
    val files = file.walkBottomUp().filter { it.isFile }.toList()
    for (f in files) {
        runCatching {
            Random.nextBytes(ByteArray(coerceAtMost(f.length(), 8L * 1024 * 1024).toInt()))
                .let { f.outputStream().use { os -> os.write(it) } }
        }
    }
}
```

(Overwrite each file's leading bytes with random data up to 8 MB, then
delete recursively. `runCatching` keeps this best-effort.) Call `shred`
before `working.deleteRecursively()` in BOTH the success and failure paths
(currently one shared `finally` — keep it shared). Also switch
`createWorkingDirectory` from `context.cacheDir` to
`context.noBackupFilesDir` (create parent dirs as needed) so the snapshot
is excluded from OS backup surfaces.

**Verify**: `./gradlew compileDebugKotlin` → exit 0.

### Step 3: Bound the import against the manifest

1. Lower caps to realistic bounds in the companion:
   `MAX_ENTRY_BYTES = 1L * 1024 * 1024 * 1024`,
   `MAX_ARCHIVE_BYTES = 4L * 1024 * 1024 * 1024`.
2. In `prepareImport`, after parsing the manifest, verify the extracted
   DATABASE_ENTRY file size against the manifest's declared values (the
   manifest already carries a row count and format version — read
   `createManifest` to see which size fields exist; if no byte-size field
   exists, add `require(snapshot.length() <= MAX_ENTRY_BYTES)` which is
   already implied by Step 3.1's per-entry cap during extraction — that is
   sufficient).
3. No format change: old backups import unchanged (caps only ever rejected
   absurd archives).

**Verify**: `./gradlew compileDebugKotlin` → exit 0.

### Step 4: Passphrase as `CharArray` end-to-end

1. `MemoryViewModel.exportOfficeBackup(uri, passphrase: CharArray)` and
   `selectOfficeBackup` — change types; zero the array after launching
   repository work completes (the repository already zeroes its copy; the
   ViewModel should hold no copy).
2. `MainActivity.kt`: `pendingExportPassphrase: CharArray?`; null it after
   use.
3. `ui/AiChatApp.kt` dialog: keep the `TextField` `String` for IME
   interaction, but convert to `CharArray` at dialog-confirm time and do
   not store the String in state longer than the composition
   (`remember { mutableStateOf("") }` cleared on dismiss). Search for
   `passphrase` in the file to find: the dialog composable (~2061–2090),
   the "At least 8 characters" hint (~2071/2086), and the import dialog —
   update both flows.
4. In the dialog, add a lightweight strength signal: if
   `passphrase.length < 12` AND it lacks both a digit and a symbol, show
   the existing hint plus "Weak passphrase — a stronger one better
   protects your data." (pure UI text; the hard floor stays 8 chars so old
   behavior is compatible).

**Verify**: `./gradlew compileDebugKotlin` → exit 0.

### Step 5: Full gate + instrumented round-trip

**Verify**: `./gradlew testDebugUnitTest detekt assembleDebug` → exit 0.
If a device/emulator is available:
`./gradlew connectedDebugAndroidTest --tests '*OfficeBackup*'` → pass
(this is the regression net for the migration/export changes).

## Test plan

- `app/src/androidTest/.../OfficeBackupInstrumentedTest.kt` already covers
  the export/import round-trip — run it (Step 5). If it pins the 8-char
  minimum or passphrase types, update the test to `CharArray`.
- New unit test (JVM, `app/src/test/.../backup/`): the sweep predicate —
  plaintext main DB present → sweep is a no-op; encrypted main DB present
  + leftover backup file → sweep deletes it (uses temp dirs;
  `DatabaseEncryptionMigrator` takes `context` for `getDatabasePath` — use
  the fake-Context pattern, see plan 007).

## Done criteria

- [ ] `./gradlew testDebugUnitTest detekt assembleDebug` exit 0
- [ ] Export writes under `noBackupFilesDir` and shreds before delete
- [ ] `sweepResidueFromFailedMigration()` runs before DB open in `AppDatabase.kt`
- [ ] `MAX_ENTRY_BYTES`/`MAX_ARCHIVE_BYTES` reduced; import still rejects gracefully
- [ ] Passphrase flows as `CharArray` from dialog to repository; UI shows weak-passphrase hint
- [ ] No format/constant changes that break old backups (MAGIC, FORMAT_VERSION, KDF bounds untouched)
- [ ] `git status` shows only in-scope files
- [ ] `plans/README.md` status row updated

## STOP conditions

Stop and report back (do not improvise) if:
- `OfficeBackupInstrumentedTest` fails after the changes and the cause is
  not the passphrase typing (e.g. `noBackupFilesDir` unavailable in the
  test environment) — report the stack.
- The manifest lacks any size/count field you need for Step 3.2 — the
  per-entry cap alone is acceptable; note it and continue.
- The passphrase dialog in `AiChatApp.kt` cannot be located by searching
  `passphrase` (drift).
- Changing `MemoryViewModel` signatures breaks callers beyond
  `MainActivity.kt` — report the caller list.

## Maintenance notes

- The full fix for export residue is per-entry streaming encryption (never
  materializing a plaintext DB file); that is a format-v2 design deferred
  deliberately (effort L, breaks compat).
- Reviewers should confirm `FORMAT_VERSION`, `MAGIC`, `KDF_ITERATIONS`
  bounds, and `MIN_KDF_ITERATIONS` are untouched.
- If a future format version adds a byte-size manifest field, wire it into
  the Step 3 checks.
