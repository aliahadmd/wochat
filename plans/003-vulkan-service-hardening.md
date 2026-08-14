# Plan 003: Harden the Vulkan service against dead clients and stop leaking `.part` request files

> **Executor instructions**: Follow this plan step by step. Run every
> verification command and confirm the expected result before moving to the
> next step. If anything in the "STOP conditions" section occurs, stop and
> report — do not improvise. When done, update the status row for this plan
> in `plans/README.md` — unless a reviewer dispatched you and told you they
> maintain the index.
>
> **Drift check (run first)**: `git diff --stat 2dff7e6..HEAD -- app/src/main/java/com/aliahad/aichat/inference/remote/`
> If any in-scope file changed since this plan was written, compare the
> "Current state" excerpts against the live code before proceeding; on a
> mismatch, treat it as a STOP condition.

## Status

- **Priority**: P1
- **Effort**: S
- **Risk**: LOW (only suppresses already-doomed IPC; adds file cleanup)
- **Depends on**: none
- **Category**: bug
- **Planned at**: commit `2dff7e6`, 2026-08-15

## Why this matters

Two defects in the isolated `:vulkan` process:

1. `VulkanInferenceService.generate` invokes Binder callback proxies
   (`callback.onCompleted/onFailure`, `batch.flush()`) without catching
   `RemoteException`/`DeadObjectException`. If the app process dies or
   unbinds mid-generation, the rethrow escapes the coroutine, and with the
   service scope (`SupervisorJob + Dispatchers.IO`, no
   `CoroutineExceptionHandler`) this crashes the service process — the very
   process that exists to isolate Vulkan failures from the app.
2. `VulkanRequestCodec.write` leaves `${uuid}.json.part` files behind when
   the size check or rename fails. Requests can be up to 8 MB, so failures
   accumulate disk garbage forever.

## Current state

- `app/src/main/java/com/aliahad/aichat/inference/remote/VulkanInferenceService.kt`
  — AIDL stub implementation. `generate` (lines 104–145):

```kotlin
// VulkanInferenceService.kt:104-145 (abridged)
override fun generate(requestPath: String, callback: IVulkanGenerationCallback) {
    scope.launch {
        val batch = CallbackBatch(callback)
        try {
            val request = codec.readGeneration(requestPath)
            engine.generate(...).collect { event ->
                when (event) {
                    ...
                    is GenerationEvent.Completed -> {
                        batch.flush()
                        callback.onCompleted(event.reason.ordinal, event.answerTokens, event.continuationCount)
                    }
                }
            }
        } catch (cancelled: CancellationException) {
            batch.flush()
            callback.onFailure(BackendFailureStage.UNKNOWN.ordinal, ...)
        } catch (error: Throwable) {
            batch.flush()
            callback.onFailure(stage.ordinal, ...)
        }
    }
}
```

  Problems: `callback.onCompleted` at line 120 is inside the try, so a
  `DeadObjectException` there falls into `catch (Throwable)` whose own
  `batch.flush()`/`callback.onFailure` throw again and escape. The scope is
  created without a `CoroutineExceptionHandler`.

- `app/src/main/java/com/aliahad/aichat/inference/remote/VulkanRequestCodec.kt`
  — file-based request serialization (whole file, 175 lines). The leak
  (lines 73–80):

```kotlin
// VulkanRequestCodec.kt:73-80
private fun write(root: JSONObject): String {
    val target = File(requestDirectory, "${UUID.randomUUID()}.json")
    val temporary = File(requestDirectory, "${target.name}.part")
    temporary.bufferedWriter().use { it.write(root.toString()) }
    check(temporary.length() <= MAX_REQUEST_BYTES) { "Inference request is too large." }
    check(temporary.renameTo(target)) { "Unable to prepare inference request." }
    return target.absolutePath
}
```

  Both `check`s throw without deleting `temporary`. The caller
  (`VulkanInferenceClient`) deletes only the final request path in its
  `finally`, never the `.part`.

Conventions: the codebase uses `runCatching` sparingly; prefer explicit
`catch` for Android Binder errors. Logging uses `android.util.Log` with
per-component TAGs (see repository knowledge "Android Log-based Logging
with Per-Component TAGs").

## Commands you will need

| Purpose | Command | Expected on success |
|---------|---------|---------------------|
| Unit tests | `./gradlew testDebugUnitTest` | exit 0 |
| Detekt | `./gradlew detekt` | exit 0 |
| Debug build | `./gradlew assembleDebug` | exit 0 |

## Scope

**In scope**:
- `app/src/main/java/com/aliahad/aichat/inference/remote/VulkanInferenceService.kt`
- `app/src/main/java/com/aliahad/aichat/inference/remote/VulkanRequestCodec.kt`
- `app/src/test/java/com/aliahad/aichat/inference/remote/` (new test dir)

**Out of scope**:
- `VulkanInferenceClient.kt` (its cleanup of final request files is fine).
- AIDL files, `NativeInferenceEngine`, anything in `cpp/`.

## Steps

### Step 1: Wrap all callback invocations in the service

Add a private helper in the service (or in `CallbackBatch`):

```kotlin
private inline fun withCallback(block: () -> Unit) {
    try {
        block()
    } catch (remote: RemoteException) {
        Log.w(TAG, "Vulkan client went away during callback", remote)
    }
}
```

Route every `callback.onCompleted(...)`, `callback.onFailure(...)`, and
`batch.flush()` (if `flush` invokes the proxy — verify inside
`CallbackBatch`) through it. Also add a `CoroutineExceptionHandler` to the
service scope that logs instead of crashing:

```kotlin
private val scope = CoroutineScope(
    SupervisorJob() + Dispatchers.IO +
        CoroutineExceptionHandler { _, error -> Log.e(TAG, "Vulkan service coroutine failed", error) },
)
```

Locate the scope declaration (around lines 60–70) and match its existing
style. Keep `CancellationException` rethrow semantics intact.

**Verify**: `./gradlew compileDebugKotlin` → exit 0.

### Step 2: Delete `.part` files on failure in the codec

Change `write` so the checks clean up:

```kotlin
private fun write(root: JSONObject): String {
    val target = File(requestDirectory, "${UUID.randomUUID()}.json")
    val temporary = File(requestDirectory, "${target.name}.part")
    try {
        temporary.bufferedWriter().use { it.write(root.toString()) }
        check(temporary.length() <= MAX_REQUEST_BYTES) { "Inference request is too large." }
        check(temporary.renameTo(target)) { "Unable to prepare inference request." }
    } catch (error: Throwable) {
        temporary.delete()
        throw error
    }
    return target.absolutePath
}
```

Also add a stale-file sweep in the `VulkanRequestCodec` init:
`requestDirectory.listFiles { f -> f.name.endsWith(".part") }?.forEach { it.delete() }`.

**Verify**: `./gradlew compileDebugKotlin` → exit 0.

### Step 3: Unit test for the codec cleanup

`VulkanRequestCodec` needs `Context` (uses `context.cacheDir`) and
`org.json`. In JVM unit tests `org.json` is stubbed by default. Add
`testImplementation("org.json:json:20240303")` to `app/build.gradle.kts`
(if a similar dependency already exists, reuse it) and write
`app/src/test/java/com/aliahad/aichat/inference/remote/VulkanRequestCodecTest.kt`
using a fake `Context` returning a temp dir (Robolectric is NOT needed if
you only override `getCacheDir`; alternatively use
`androidx.test:core`'s `ApplicationProvider` if already a test dependency —
check `gradle/libs.versions.toml` first).

Test cases:
- Oversized write throws AND no `.part`/`.json` file remains in the dir.
- Successful write leaves exactly one `.json` and no `.part`.
- Constructor init sweeps a pre-planted `stale.json.part`.

**Verify**: `./gradlew testDebugUnitTest` → all pass including new tests.

## Test plan

- New file `app/src/test/java/com/aliahad/aichat/inference/remote/VulkanRequestCodecTest.kt`
  with the three cases above; plain JUnit4.
- Service-side RemoteException wrapping is verified by compile + review
  (binding a real dead client is not feasible on the JVM).

## Done criteria

- [ ] `./gradlew testDebugUnitTest` exits 0; codec tests pass
- [ ] `./gradlew detekt assembleDebug` exits 0
- [ ] Every `callback.`/`batch.flush()` proxy call in
      `VulkanInferenceService.kt` is wrapped or inside a handler that logs
- [ ] `grep -n "json.part" app/src/main/java/com/aliahad/aichat/inference/remote/VulkanRequestCodec.kt`
      shows the cleanup in place
- [ ] No files outside the in-scope list are modified (`git status`)

## STOP conditions

Stop and report back (do not improvise) if:
- Adding `org.json:json` to testImplementation breaks other unit tests
  (serialization behavior differs) — revert and report.
- `CallbackBatch.flush()` does something beyond proxy calls (verify by
  reading it) — adjust the wrapping accordingly or STOP if unclear.
- The excerpts don't match live code (drift).

## Maintenance notes

- If the AIDL callback surface grows (new methods), every new proxy call
  must go through the same wrapper.
- Reviewers should confirm no `catch (Throwable)` path can still rethrow
  out of the coroutine.
- Deferred: client-binding state-machine tests (needs interface extraction;
  see deferred finding TEST-04b in `plans/README.md`).
