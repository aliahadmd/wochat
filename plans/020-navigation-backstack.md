# Plan 020: Fix the navigation back stack and add back handling

> **Executor instructions**: Follow this plan step by step. Run every
> verification command and confirm the expected result before moving to the
> next step. If anything in the "STOP conditions" section occurs, stop and
> report — do not improvise. When done, update the status row for this plan
> in `plans/README.md` — unless a reviewer dispatched you and told you they
> maintain the index.
>
> **Drift check (run first)**:
> `git diff --stat 619d7de..HEAD -- app/src/main/java/com/aliahad/aichat/ui/AiChatApp.kt app/src/main/java/com/aliahad/aichat/ui/navigation/AppRoute.kt`

## Status

- **Priority**: P1
- **Effort**: S
- **Risk**: MED (navigation changes are easy to get subtly wrong; the drawer
  and the settings "close" button both route through the same helper)
- **Depends on**: none
- **Category**: bug / UX
- **Planned at**: commit `619d7de`, 2026-08-15

## Why this matters

Three defects in one small area:

1. **`restoreState` is inert.** `navigateTo` passes `restoreState = true`
   with no matching `popUpTo(...) { saveState = true }`. Without a save there
   is nothing to restore, so the flag does nothing and reads as if state
   preservation is handled when it is not.
2. **Closing settings pushes instead of pops.** The settings "close" action
   calls `navigateTo(AppRoute.CHAT)`, which *navigates forward* to a second
   CHAT entry. The back stack becomes `[chat, settings, chat]`, so pressing
   system back from the chat screen takes the user **back into settings**.
3. **There is no `BackHandler` anywhere in the app** (`grep -rn "BackHandler"`
   → no matches). Nothing intercepts back during generation, so back
   backgrounds the app mid-stream rather than offering to stop.

## Current state

- `app/src/main/java/com/aliahad/aichat/ui/AiChatApp.kt:221-228`:

```kotlin
val navigateTo: (AppRoute) -> Unit = { destination ->
    if (navController.currentDestination?.route != destination.route) {
        navController.navigate(destination.route) {
            launchSingleTop = true
            restoreState = true
        }
    }
}
```

- The settings close action, `AiChatApp.kt:312-316`:

```kotlin
} else {
    IconButton(onClick = { navigateTo(AppRoute.CHAT) }) {
        Icon(Icons.Default.Close, contentDescription = "Close settings")
    }
}
```

- Drawer actions (`AiChatApp.kt:250-271`) also call `navigateTo(AppRoute.CHAT)`
  after selecting or creating a conversation — those are legitimate
  navigations, but they hit the same helper.
- `AppRoute` (`ui/navigation/AppRoute.kt`) has exactly two destinations,
  `CHAT` and `SETTINGS`. `fromRoute` falls back to `CHAT`.
- `ChatUiState.isSending` already exists and is what a back interceptor would
  key off.

Conventions: the `NavController` is documented as "the only owner of the
active route" in `AppRoute.kt`. Keep it that way — do not add a parallel
route variable.

## Commands you will need

| Purpose | Command | Expected on success |
|---------|---------|---------------------|
| Unit tests | `./gradlew testDebugUnitTest` | exit 0 |
| Instrumented compile | `./gradlew compileDebugAndroidTestKotlin` | exit 0 |
| Route tests | `./gradlew testDebugUnitTest --tests '*AppRoute*'` | exit 0 |

## Scope

**In scope**:
- `app/src/main/java/com/aliahad/aichat/ui/AiChatApp.kt` (the `navigateTo`
  helper, the settings close action, and one new `BackHandler`)

**Out of scope**:
- Adding destinations or restructuring `AppRoute`.
- Predictive back animations. Opting into
  `android:enableOnBackInvokedCallback` is a reasonable follow-up but is a
  manifest-and-motion change with its own testing needs — note it, do not do
  it here.
- The drawer's own back behavior (`ModalNavigationDrawer` handles it).

## Steps

### Step 1: Make closing settings pop

Give the settings close action a real pop rather than a forward navigation:
`navController.popBackStack()` when settings is not the start destination, or
a `popUpTo(AppRoute.CHAT.route) { inclusive = false }` navigation.

The drawer's `navigateTo(AppRoute.CHAT)` calls should keep working — a user
selecting a conversation from the drawer while in settings should land on
chat with a sane stack, not a growing one.

**Verify**: reason through the three flows and confirm with Step 4's test:
- chat → settings → close → back  ⇒ leaves the app (does not re-enter settings)
- chat → settings → drawer → select conversation ⇒ chat, stack depth 1
- chat → settings → system back ⇒ chat

### Step 2: Fix or remove `restoreState`

Either pair it correctly with `popUpTo(...) { saveState = true }`, or drop
the flag. With two top-level destinations and ViewModels scoped to the
activity, the honest answer is probably to drop it — state is not actually
per-destination here. Whichever you choose, do not leave a flag that implies
behavior the app does not have.

**Verify**: `grep -n "restoreState\|saveState\|popUpTo" app/src/main/java/com/aliahad/aichat/ui/AiChatApp.kt`
→ either both `saveState` and `restoreState` present, or neither.

### Step 3: Handle back during generation

Add a single `BackHandler` on the chat screen, enabled only while
`state.isSending`, that stops generation instead of backgrounding the app.
Reuse the existing `onStop` lambda already threaded into `ChatScreen` — do
not introduce a second stop path.

Keep it narrow: when not sending, back must behave exactly as it does today.

**Verify**: `grep -rn "BackHandler" app/src/main/java` → exactly one match.

### Step 4: Test the stack

Extend `AppRouteTest` (JVM) for any pure routing logic you can isolate, and
add an instrumented test for the settings round-trip: open settings, close
it, press back, assert the app does not land back in settings.

**Verify**: `./gradlew testDebugUnitTest --tests '*AppRoute*'` → exit 0, and
the instrumented test passes on a device.

## Test plan

- `AppRouteTest` stays green and gains coverage where the logic is testable
  on the JVM.
- New instrumented test for chat → settings → close → back.
- Manual: start a generation, press back, confirm generation stops and the
  app stays foregrounded; press back again, confirm it backgrounds normally.

## Done criteria

- [ ] Closing settings pops rather than pushing a duplicate CHAT entry
- [ ] Back from chat after visiting settings does not return to settings
- [ ] `restoreState` is either correctly paired or removed
- [ ] Exactly one `BackHandler`, active only while sending, reusing `onStop`
- [ ] New instrumented test covers the settings round-trip
- [ ] `./gradlew testDebugUnitTest` and `compileDebugAndroidTestKotlin` exit 0
- [ ] `plans/README.md` status row updated

## STOP conditions

Stop and report back if:
- Popping breaks the drawer flows (selecting a conversation from settings) in
  a way that needs more than the `navigateTo` helper to fix.
- `ModalNavigationDrawer`'s internal back handling conflicts with the new
  `BackHandler` — report the interaction rather than disabling the drawer's.
- Making back stop generation causes a partially-written message to persist
  in a bad state. That is a `ChatTurnRunner` concern; report it rather than
  editing turn lifecycle here.

## Maintenance notes

- Predictive back (`android:enableOnBackInvokedCallback="true"`) is the
  natural follow-up once this is correct. It needs the `BackHandler` to be
  right first, which is why it is not bundled here.
- Reviewers: the acceptance question is "after visiting settings once, does
  back ever take the user somewhere they did not come from?"
