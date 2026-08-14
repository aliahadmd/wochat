# Plan 007: Round-trip unit tests for VulkanRequestCodec

> **Executor instructions**: Follow this plan step by step. Run every
> verification command and confirm the expected result before moving to the
> next step. If anything in the "STOP conditions" section occurs, stop and
> report — do not improvise. When done, update the status row for this plan
> in `plans/README.md` — unless a reviewer dispatched you and told you they
> maintain the index.
>
> **Drift check (run first)**: `git diff --stat 2dff7e6..HEAD -- app/src/main/java/com/aliahad/aichat/inference/remote/VulkanRequestCodec.kt`
> If the file changed since this plan was written (plan 003 also touches
> it), compare the "Current state" excerpts against live code and reconcile
> with plan 003's changes before proceeding.

## Status

- **Priority**: P2
- **Effort**: S
- **Risk**: LOW (test-only plus one optional test dependency)
- **Depends on**: 003 (touches the same file; run after it)
- **Category**: tests
- **Planned at**: commit `2dff7e6`, 2026-08-15

## Why this matters

`VulkanRequestCodec` is the AIDL-side serialization for every Vulkan
generation/restore request: 174 lines of Bundle-key/JSON round-tripping
where a typo only surfaces on-device during GPU fallback — the exact
recovery path the architecture exists to provide. It has zero tests. It is
pure serialization and trivially unit-testable once `org.json` is available
on the JVM.

## Current state

- `app/src/main/java/com/aliahad/aichat/inference/remote/VulkanRequestCodec.kt`
  — `internal class VulkanRequestCodec(context: Context)` with
  `requestDirectory = File(context.cacheDir, "vulkan_requests")`. Public
  API: `writeRestore(conversationId, history, settings): String`,
  `writeGeneration(turn, settings): String`, `writeTokenCount(text):
  String`, and matching `readRestore/readGeneration/readTokenCount(path)`.
  Serialization helpers are file-private extensions on
  `GenerationSettings`, `ChatTurn`, `UserTurn`, `AttachmentContext`
  (lines 102–174). `read()` validates canonical parent == request
  directory, size bounds, and deletes the file after reading (lines 82–94).

  Key semantic details to pin in tests:
  - `toGenerationSettings()` calls `.normalized()` on decode
    (`VulkanRequestCodec.kt:115`) — clamping is applied on the service side.
  - `toChatTurn()` synthesizes ids (`"remote-history-<uuid>"`,
    `conversationId = "remote"`, `createdAt = 0`) — ids do NOT survive the
    round trip by design.
  - `selectedPages` is serialized sorted and decoded back to a `Set`.

- Test infra: `app/src/test` uses plain JUnit4; `org.json` on the JVM is
  stubbed ("not mocked") unless a real json dependency is added. Check
  `gradle/libs.versions.toml` and `app/build.gradle.kts` dependencies
  block for an existing `testImplementation("org.json:json:…")` before
  adding one.

## Commands you will need

| Purpose | Command | Expected on success |
|---------|---------|---------------------|
| Unit tests (filtered) | `./gradlew testDebugUnitTest --tests '*VulkanRequestCodec*'` | all pass |
| Full unit tests | `./gradlew testDebugUnitTest` | exit 0 |
| Detekt | `./gradlew detekt` | exit 0 |

## Scope

**In scope**:
- `app/src/test/java/com/aliahad/aichat/inference/remote/VulkanRequestCodecTest.kt` (new)
- `app/build.gradle.kts` (ONLY a `testImplementation("org.json:json:20240303")` line, and only if not already present)

**Out of scope**:
- `VulkanRequestCodec.kt` production code (unless plan 003 hasn't landed and
  you need its `.part` cleanup — do NOT duplicate that work here).
- `VulkanInferenceClient`, the AIDL service.

## Steps

### Step 1: Ensure `org.json` is available to unit tests

Check `grep -n "org.json" app/build.gradle.kts gradle/libs.versions.toml`.
If absent, add to the dependencies block:
`testImplementation("org.json:json:20240303")`.

**Verify**: `./gradlew app:dependencies --configuration debugUnitTestRuntimeClasspath | grep org.json` → shows the json artifact.

### Step 2: Write round-trip tests

Create the test file. The codec needs only `context.cacheDir`; provide a
minimal fake `Context` (override `getCacheDir()` to a
`createTempDirectory(...)` and throw `TODO()` for everything else — the
inline-fake convention used across `app/src/test`). Cases:

1. **restore round-trip**: write a `RestoreRequest` with 3 history turns
   (mixed roles, one turn with image+audio attachments), read it back →
   conversationId equal, settings fields equal, history size and roles
   equal, attachment `extractedText`/`imageTokenBudget`/`selectedPages`
   equal, synthesized ids differ from input (documented behavior).
2. **generation round-trip**: `UserTurn` with attachments → text and
   attachment count preserved.
3. **token count round-trip**: multi-line text preserved verbatim.
4. **normalized settings**: write settings with out-of-range temperature /
   maxNewTokens → read back returns `.normalized()` values (assert
   equality against `GenerationSettings(...).normalized()` of the input).
5. **empty selectedPages set** survives as an empty set.
6. **kind mismatch**: `readGeneration(path-of-restore)` throws
   `IllegalArgumentException` ("Unexpected inference request type.").
7. **read deletes the file**: after a successful read, `File(path).exists()`
   is false.
8. **path containment**: `readRestore("/etc/passwd")` throws
   `IllegalArgumentException` ("Invalid inference request path.").

**Verify**: `./gradlew testDebugUnitTest --tests '*VulkanRequestCodec*'` → all pass.

### Step 3: Full gate

**Verify**: `./gradlew testDebugUnitTest detekt` → exit 0 (confirms the new
`org.json` test dependency didn't shift behavior of other tests).

## Test plan

This plan is the test plan. Pattern: inline fake `Context`, `@TempDir`-style
temp dirs (or `createTempDirectory`), JUnit4 asserts.

## Done criteria

- [ ] `./gradlew testDebugUnitTest` exits 0; ≥ 8 new test methods pass
- [ ] `./gradlew detekt` exits 0
- [ ] `VulkanRequestCodec.kt` production code unchanged by THIS plan
- [ ] `git status` shows only the test file (+ optionally one line in
      `app/build.gradle.kts`)
- [ ] `plans/README.md` status row updated

## STOP conditions

Stop and report back (do not improvise) if:
- The real `org.json:json` artifact changes behavior of OTHER existing
  unit tests (serialization differences) — revert the dependency and
  report; the fallback is instrumented tests after plan 005 lands.
- The fake `Context` cannot satisfy the codec (it touches Context beyond
  `getCacheDir` after drift) — report what it needs.
- A round-trip case fails — that's a real serialization bug; report it,
  do not fix production code here.

## Maintenance notes

- Any new field added to `UserTurn`/`ChatTurn`/`GenerationSettings`
  serialization must be added to these round-trip tests in the same PR.
- These tests pair with plan 003's cleanup tests; keep both in
  `inference/remote/` under `src/test`.
