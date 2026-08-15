# Plan 019: Composer draft must be saveable and conversation-scoped

> **Executor instructions**: Follow this plan step by step. Run every
> verification command and confirm the expected result before moving to the
> next step. If anything in the "STOP conditions" section occurs, stop and
> report — do not improvise. When done, update the status row for this plan
> in `plans/README.md` — unless a reviewer dispatched you and told you they
> maintain the index.
>
> **Drift check (run first)**:
> `git diff --stat 619d7de..HEAD -- app/src/main/java/com/aliahad/aichat/ui/AiChatApp.kt app/src/main/java/com/aliahad/aichat/ui/viewmodel/ChatViewModel.kt`

## Status

- **Priority**: P1
- **Effort**: S
- **Risk**: MED (touches the send path; a mistake sends the wrong text or the
  wrong conversation's text)
- **Depends on**: none
- **Category**: bug / data loss
- **Planned at**: commit `619d7de`, 2026-08-15

## Why this matters

The composer's text is plain local composable state:

```kotlin
// AiChatApp.kt:523, inside ChatScreen
var input by remember { mutableStateOf("") }
```

Two user-visible defects follow directly:

1. **Rotation and process death discard a typed message.** `remember` does not
   survive configuration changes. Type three paragraphs, rotate, lose them.
2. **Drafts bleed between conversations.** The `remember` is not keyed to the
   selected conversation, so text typed in chat A is still sitting in the
   composer after switching to chat B — and pressing send posts it there.

The second is the more serious of the two: it is a correctness bug with a
privacy edge, since a message intended for one conversation can be sent into
another.

Note the asymmetry that makes this worse: **attachments already do this
correctly.** `ChatUiState.draftKey` scopes staged attachments per draft
(`AttachmentRepository.observeDraft(draftKey)`, `attachments.draftKey` column
with an index, cleared on send). Only the text was left behind.

## Current state

- `app/src/main/java/com/aliahad/aichat/ui/AiChatApp.kt:523`:

```kotlin
var input by remember { mutableStateOf("") }
var showAttachmentSheet by remember { mutableStateOf(false) }
var previewAttachment by remember { mutableStateOf<Attachment?>(null) }
```

- Send handler, `AiChatApp.kt:598-603`:

```kotlin
onSend = {
    if (input.isNotBlank() || state.draftAttachments.isNotEmpty()) {
        onSend(input)
        input = ""
    }
},
```

- The attachment side, for reference — `ChatViewModel.kt:76` seeds
  `draftKey = initialDraftKey`, and `ChatViewModel.kt:425` rotates it
  (`draftKey = key`) when the conversation changes.
- `ChatUiState` (`FeatureUiStates.kt:28-50`) has `draftKey`,
  `selectedConversationId`, and `draftAttachments` — but **no** input field.

Conventions: UI state lives in `ChatUiState` and is mutated through
`ChatViewModel` action methods; the composable receives state plus lambdas.
Follow that rather than adding a second state channel.

## Commands you will need

| Purpose | Command | Expected on success |
|---------|---------|---------------------|
| Unit tests | `./gradlew testDebugUnitTest` | exit 0 |
| Instrumented compile | `./gradlew compileDebugAndroidTestKotlin` | exit 0 |
| Chat UI tests | `./gradlew connectedDebugAndroidTest --tests '*ChatUi*'` | exit 0 (needs a device) |

## Scope

**In scope**:
- `app/src/main/java/com/aliahad/aichat/ui/AiChatApp.kt` (`ChatScreen` and the
  composer call site)
- `app/src/main/java/com/aliahad/aichat/ui/viewmodel/ChatViewModel.kt`
- `app/src/main/java/com/aliahad/aichat/ui/viewmodel/FeatureUiStates.kt`

**Out of scope**:
- Attachment draft handling — it is already correct; do not "unify" it in
  this plan.
- Enabling the composer during generation — that is plan 026.
- Persisting drafts to the database across app restarts. In-memory +
  `SavedStateHandle` survival is the goal here; durable drafts are a separate
  product decision.

## Steps

### Step 1: Move the draft into ChatUiState

Add an `input: String = ""` field to `ChatUiState` and an
`onInputChange`-style action on `ChatViewModel` that updates it. Back it with
the ViewModel's `SavedStateHandle` so it survives process death, not just
rotation.

**Verify**: `./gradlew compileDebugAndroidTestKotlin` → exit 0.

### Step 2: Clear the draft when the conversation changes

Wherever `ChatViewModel` rotates `draftKey` (around line 425) and wherever
`selectedConversationId` changes, the text draft must follow the same
lifecycle as the attachment draft.

Decide explicitly between two behaviors and state your choice in the status
row:

- **Clear on switch** (simpler, matches attachments' apparent behavior), or
- **Keep a per-conversation draft map** (nicer, more state to manage).

Prefer *clear on switch* unless the map falls out naturally — it is the
smaller change and removes the bleed. Do not leave the current behavior,
where the draft silently follows the user.

**Verify**: the instrumented test from Step 4.

### Step 3: Rewire the composable

Replace the local `remember` at line 523 with the state field, and route
`onInputChange` through the ViewModel. Update the send handler at 598-603 so
it reads from state and clears through the ViewModel rather than assigning a
local.

Leave `showAttachmentSheet` and `previewAttachment` as-is — those are genuine
ephemeral UI state and are out of scope.

**Verify**: `grep -n "var input by remember" app/src/main/java/com/aliahad/aichat/ui/AiChatApp.kt`
→ no matches.

### Step 4: Test both defects

Add instrumented tests to the existing chat UI suite:

1. Type text, trigger a configuration change, assert the text is still there.
2. Type text in conversation A, switch to conversation B, assert the composer
   is empty (or shows B's own draft, per your Step 2 choice).

Follow the existing `testTag` conventions (`"message-list"`, `"settings-list"`)
— add a composer tag if none exists.

**Verify**: `./gradlew connectedDebugAndroidTest --tests '*ChatUi*'` → exit 0.

## Test plan

- The two new instrumented tests above are the core of this plan; it is not
  done without them.
- `ChatViewModelInstrumentedTest` must stay green.
- Manual: type in chat A, switch to B, confirm nothing carried over; rotate
  mid-compose, confirm the text survives.

## Done criteria

- [ ] Draft text lives in `ChatUiState`, backed by `SavedStateHandle`
- [ ] Switching conversations no longer carries text across
- [ ] Rotation preserves the draft
- [ ] Send reads and clears through the ViewModel
- [ ] Two new instrumented tests pass
- [ ] `./gradlew testDebugUnitTest` and `compileDebugAndroidTestKotlin` exit 0
- [ ] `plans/README.md` status row updated, naming the Step 2 choice

## STOP conditions

Stop and report back if:
- `ChatViewModel` has no `SavedStateHandle` and adding one requires changing
  `AiChatViewModelFactory` in a way that touches every other ViewModel.
- Moving input into state causes visible per-keystroke recomposition lag in
  the message list — report it; that interacts with plan 024/025 and should
  be measured, not guessed at.
- The conversation-switch path turns out to already clear the draft through
  some mechanism not visible in the excerpts (drift).

## Maintenance notes

- Attachments are the reference implementation for draft scoping in this
  codebase. If a future change adds a third draft-ish thing, it should follow
  `draftKey`, not invent a fourth pattern.
- Reviewers: the acceptance question is "can text typed in one conversation
  ever be sent to a different one?" The answer must be no.
