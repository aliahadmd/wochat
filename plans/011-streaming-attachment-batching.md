# Plan 011: Stop the streaming N+1 — batch attachment loads out of the messages Flow hot path

> **Executor instructions**: Follow this plan step by step. Run every
> verification command and confirm the expected result before moving to the
> next step. If anything in the "STOP conditions" section occurs, stop and
> report — do not improvise. When done, update the status row for this plan
> in `plans/README.md` — unless a reviewer dispatched you and told you they
> maintain the index.
>
> **Drift check (run first)**: `git diff --stat 2dff7e6..HEAD -- app/src/main/java/com/aliahad/aichat/ui/viewmodel/ChatViewModel.kt app/src/main/java/com/aliahad/aichat/data/Daos.kt app/src/main/java/com/aliahad/aichat/data/ChatRepository.kt`
> If any in-scope file changed since this plan was written, compare the
> "Current state" excerpts against the live code before proceeding; on a
> mismatch, treat it as a STOP condition.

## Status

- **Priority**: P1
- **Effort**: M
- **Risk**: MED (changes when attachment/skill UI state updates arrive; mitigated by keeping the same collectors, only batching queries)
- **Depends on**: none
- **Category**: perf
- **Planned at**: commit `2dff7e6`, 2026-08-15

## Why this matters

During generation, `ChatTurnRunner` persists the streaming message every
~250 ms (or every small delta). Each `updateMessage` also bumps
`conversations.updatedAt`, invalidating the conversations table — so the
messages Flow re-emits ~4×/sec while tokens stream. The collector then
issues ONE attachment query PER MESSAGE (`attachmentsForMessage`) plus a
skills query, on every emission. A 100-message conversation performs ~400
extra encrypted-DB round-trips per second of streaming, scaling linearly
with history length. This is the chat hotspot.

## Current state

- `app/src/main/java/com/aliahad/aichat/ui/viewmodel/ChatViewModel.kt` —
  conversation switch (lines 180–199):

```kotlin
// ChatViewModel.kt:187-198
messagesJob?.cancel()
messagesJob = viewModelScope.launch {
    chatRepository.messages(id).collectLatest { messages ->
        _uiState.update { it.copy(messages = messages) }
        val attachments = messages.associate { message ->
            message.id to attachmentRepository.attachmentsForMessage(message.id)
        }
        val skills = skillRepository.blocksForMessages(messages.map { it.id })
        _uiState.update {
            it.copy(messageAttachments = attachments, messageSkills = skills)
        }
    }
}
```

  Note: `collectLatest` CANCELS the per-message attachment work when a new
  emission arrives mid-loop — the current code is already racy/wasteful
  under rapid emissions.

- Skills already have the batched pattern to copy —
  `skillRepository.blocksForMessages(ids)` (one `IN` query; DAO at
  `Daos.kt:194-198`, `invocationsForMessages`).
- `app/src/main/java/com/aliahad/aichat/attachment/AttachmentRepository.kt:160-161`:

```kotlin
override suspend fun attachmentsForMessage(messageId: String): List<Attachment> =
    dao.getForMessage(messageId).map(AttachmentEntity::toDomain)
```

- Streaming writes: `ChatTurnRunner.kt:284-291` (250 ms cadence) and
  `ChatRepository.kt:99-104` (`updateMessage` also touches
  `conversations.updatedAt`).
- UI consumers: `ChatUiState.messageAttachments: Map<String, List<Attachment>>`
  and `messageSkills` (reset at lines 180–186 on switch).

Conventions: repositories expose suspend functions over DAO `IN` queries
(see `AttachmentRepository.contexts(ids, prompt)` at lines 154–158 for the
`getByIds` batching style).

## Commands you will need

| Purpose | Command | Expected on success |
|---------|---------|---------------------|
| Unit tests | `./gradlew testDebugUnitTest` | exit 0 |
| Detekt | `./gradlew detekt` | exit 0 |
| Build | `./gradlew assembleDebug` | exit 0 |
| AndroidTest compile | `./gradlew compileDebugAndroidTestKotlin` | exit 0 |

## Scope

**In scope**:
- `app/src/main/java/com/aliahad/aichat/ui/viewmodel/ChatViewModel.kt`
- `app/src/main/java/com/aliahad/aichat/attachment/AttachmentRepository.kt` (one new method)
- `app/src/main/java/com/aliahad/aichat/attachment/AttachmentRepository.kt`'s interface (same file or sibling interface file — find where `AttachmentRepository` interface is declared)
- `app/src/main/java/com/aliahad/aichat/data/Daos.kt` (one new `@Transaction` query)
- `app/src/test/java/com/aliahad/aichat/` (new unit test for the batching)

**Out of scope**:
- `ChatTurnRunner.kt` streaming cadence (deferred to plan 013's state
  architecture; this plan removes the amplification, not the writes).
- `ChatRepository.updateMessage`'s `updatedAt` bump (intentional UX:
  conversation sorts to top).
- The Compose UI rendering.

## Steps

### Step 1: Add a batched DAO query

In `Daos.kt` (AttachmentDao), mirror the skills pattern:

```kotlin
@Transaction
suspend fun getForMessages(@Suppress("LargeParam") messageIds: List<String>): List<MessageAttachmentEntity>
```

plus the attachment join query with `WHERE messageId IN (:messageIds)` —
read `getForMessage`'s existing SQL one screen above and `invocationsForMessages`
(`Daos.kt:194-198`) as the exemplar, then adapt. Return shape must let the
caller group by `messageId` (if the join returns `(messageId, attachmentId)`
pairs, join to `AttachmentEntity` in the repository via the existing
`getByIds`).

### Step 2: Repository facade

Add to the `AttachmentRepository` interface and implementation:

```kotlin
override suspend fun attachmentsForMessages(messageIds: List<String>): Map<String, List<Attachment>> =
    if (messageIds.isEmpty()) emptyMap()
    else dao.getForMessagePairs(messageIds) /* adapt to Step 1 shape */
        .groupBy({ it.messageId }, { it.attachment })
```

Keep `attachmentsForMessage` (other callers exist — verify with
`grep -rn "attachmentsForMessage" app/src/main`).

**Verify**: `./gradlew compileDebugKotlin` → exit 0.

### Step 3: Use the batched call in the collector

```kotlin
messagesJob = viewModelScope.launch {
    chatRepository.messages(id).collectLatest { messages ->
        val ids = messages.map { it.id }
        val attachments = attachmentRepository.attachmentsForMessages(ids)
        val skills = skillRepository.blocksForMessages(ids)
        _uiState.update {
            it.copy(messages = messages, messageAttachments = attachments, messageSkills = skills)
        }
    }
}
```

Single `_uiState.update` (messages+attachments+skills land atomically —
also fixes the two-phase flash of empty attachment trays). Behavior notes:
- Attachments now arrive in the same update as messages; previously they
  lagged one update. UI reads `messageAttachments[message.id]` either way.
- Under rapid re-emissions, `collectLatest` cancels the batched queries —
  same semantics as before, one query instead of N per attempt.

**Verify**: `./gradlew compileDebugKotlin` → exit 0.

### Step 4: Unit test + full gate

Unit test (fake DAO pattern from existing tests): a fake returning
predetermined pairs; assert grouping, empty-input short-circuit, and that
unknown ids simply don't appear.

**Verify**: `./gradlew testDebugUnitTest detekt assembleDebug compileDebugAndroidTestKotlin` → exit 0.

## Test plan

- New JVM unit test for `attachmentsForMessages` grouping (fake DAO or
  fake repository layer per existing `app/src/test` conventions).
- `ChatViewModelInstrumentedTest.kt` (androidTest) exercises the send path
  and reads `messageAttachments` — compile check now; run when plan 005's
  CI job exists.

## Done criteria

- [ ] `./gradlew testDebugUnitTest` exits 0 with the new test
- [ ] `grep -n "attachmentsForMessage(message.id)" app/src/main/java/com/aliahad/aichat/ui/viewmodel/ChatViewModel.kt` returns no matches
- [ ] `./gradlew detekt assembleDebug compileDebugAndroidTestKotlin` exit 0
- [ ] `git status` shows only in-scope files
- [ ] `plans/README.md` status row updated

## STOP conditions

Stop and report back (do not improvise) if:
- The DAO join shape can't express the grouping in one query without a
  schema change — report the entities involved; a schema change (new index)
  needs its own migration plan and is NOT authorized here.
- `ChatUiState` consumers depend on attachments arriving AFTER messages
  (search `messageAttachments` usages in `ui/AiChatApp.kt` first — if a
  consumer treats a missing-then-present tray as a signal, split the
  update back into two `_uiState.update` calls in the same collector and
  note it).
- Drift in the excerpts.

## Maintenance notes

- If message-level attachment updates become needed mid-stream (e.g.,
  attachment READY transitions), they flow through the same messages
  invalidation — no extra work needed.
- The deeper fix (decoupling streaming text from DB-emitted state entirely)
  is deferred to plan 013; this plan makes the current architecture cheap.
