# Plan 015: Fix documentation drift and repo hygiene

> **Executor instructions**: Follow this plan step by step. Run every
> verification command and confirm the expected result before moving to the
> next step. If anything in the "STOP conditions" section occurs, stop and
> report — do not improvise. When done, update the status row for this plan
> in `plans/README.md` — unless a reviewer dispatched you and told you they
> maintain the index.
>
> **Drift check (run first)**: `git diff --stat 2dff7e6..HEAD -- README.md AGENTS.md .gitignore`
> On drift, re-verify the claims below against the live tree before
> editing (the facts are cheap to re-derive with the commands given).

## Status

- **Priority**: P2
- **Effort**: S
- **Risk**: LOW (docs + ignore rules + removing tracked build-error logs)
- **Depends on**: none (but the "wochat labels" item overlaps plan 009 —
  if 009 already changed the manifest labels, skip that item here)
- **Category**: docs
- **Planned at**: commit `2dff7e6`, 2026-08-15

## Why this matters

AGENTS.md is the file AI agents and new contributors trust first, and it
currently sends them to nonexistent screens (`ui/brief/`, `ui/settings/`
are empty; everything lives in the 3,231-line `ui/AiChatApp.kt`),
mislabels `brief/` (it's a Health Connect data source, not brief
generation), and miscounts instrumented tests (6 claimed, 9 exist).
README says JDK 17 while the Gradle daemon requires JDK 21 — a contributor
following it hits toolchain provisioning failures. And the repo tracks
`.kotlin/errors/*.log` build-error logs (with local paths) while `.kotlin/`
and `.qoder/` are unignored. Two manifest service labels say "wochat"
while the app is AIchat everywhere else.

## Current state

Facts to re-verify with the commands (they were true at `2dff7e6`):

1. `find app/src/androidTest -name '*.kt' | wc -l` → 9 (AGENTS.md:110 says 6).
2. `ls app/src/main/java/com/aliahad/aichat/ui/` → only `AiChatApp.kt`,
   `navigation/`, `theme/`, `viewmodel/` have content; `brief/` and
   `settings/` dirs are empty or absent.
3. `ls app/src/main/java/com/aliahad/aichat/brief/` → only
   `HealthDataSource.kt` (a Health Connect source, wired in
   `AiChatApplication.kt`).
4. Empty removal-residue dirs: `device/`, `overlay/`, `speech/` under
   `app/src/main/java/com/aliahad/aichat/` (untracked empty dirs).
5. `git ls-files .kotlin/` → 3 tracked log files under `.kotlin/errors/`.
6. `.gitignore` has no `.kotlin/` or `.qoder/` entries; `.qoder/repowiki/`
   exists untracked.
7. `README.md:74` says "JDK 17";
   `gradle/gradle-daemon-jvm.properties` sets `toolchainVersion=21`;
   CI installs JDK 21.
8. `AndroidManifest.xml:76,85` labels say "wochat notification memory" /
   "wochat screen memory".
9. `git ls-files local.properties` → should be EMPTY (it's in .gitignore
   already — verify; if tracked, that's a secrets exposure, see STOP).

## Commands you will need

| Purpose | Command | Expected on success |
|---------|---------|---------------------|
| Doc facts | the `find`/`ls`/`git ls-files` commands above | match "Current state" |
| Build sanity after .gitignore edits | `./gradlew help` | exit 0 |

## Scope

**In scope**:
- `README.md` (JDK line only)
- `AGENTS.md` (module tree, test counts, `brief/` description)
- `.gitignore` (add `.kotlin/`, `.qoder/`)
- git index removal of `.kotlin/errors/*.log` (`git rm --cached`)
- local deletion of empty residue dirs `device/ overlay/ speech/` (and
  `ui/brief/`, `ui/settings/` if empty)
- `app/src/main/AndroidManifest.xml` (ONLY the two labels, and only if
  plan 009 hasn't landed)

**Out of scope**:
- Any source code.
- `.qoder/repowiki/` content (leave the directory; just ignore it).
- Restructuring the UI file (deferred finding DEBT-01).

## Steps

### Step 1: .gitignore + untrack logs + remove empty dirs

1. Append to `.gitignore`:
   ```
   .kotlin/
   .qoder/
   ```
2. `git rm --cached .kotlin/errors/*.log` (exact paths from
   `git ls-files .kotlin/`).
3. `rmdir` the empty residue dirs (only if `find <dir> -type f | wc -l`
   yields 0 — otherwise STOP on that dir and report).

**Verify**: `git ls-files .kotlin/` → empty output; `git status` shows the
removals staged and no `.kotlin` noise.

### Step 2: README JDK correction

Change the JDK line (find by `grep -n "JDK" README.md`) to:
"JDK 21 (Gradle daemon toolchain, auto-provisioned via Foojay); Java
sources compile to 17 compatibility."

**Verify**: `grep -n "JDK" README.md` shows 21.

### Step 3: AGENTS.md accuracy pass

Edit only the factually wrong parts (re-verify counts first):
- Instrumented test count → 9 (list the actual files).
- Module tree: remove `ui/brief/` and `ui/settings/` entries; note under
  `ui/` that all screens currently live in `ui/AiChatApp.kt` (monolith,
  split planned); describe `brief/` as "Health Connect data source (name
  is leftover from the removed Daily Brief feature)".
- Note that `device/`, `overlay/`, `speech/` were removed features
  (dirs deleted in Step 1).
- Keep everything else (DB v16, 15 migrations, build commands — verified
  accurate at audit time).

**Verify**: spot-check 3 claims in the edited AGENTS.md against the tree
(test count, module list, migration count
`grep -c "MIGRATION_" app/src/main/java/com/aliahad/aichat/data/AppDatabase.kt`).

### Step 4: Manifest labels (skip if plan 009 landed)

Change `android:label="wochat notification memory"` →
`android:label="AIchat notification memory"` and
`"wochat screen memory"` → `"AIchat screen memory"`.

**Verify**: `grep -rn "wochat" app/src/main/AndroidManifest.xml README.md AGENTS.md | grep -v wochat-repo-path` → no branding leftovers in app-facing docs (note: the repo directory itself is named `wochat` — that is out of scope).

### Step 5: Final check

**Verify**: `./gradlew help` → exit 0 (gitignore changes broke nothing);
`git status` shows only intended changes.

## Test plan

No automated tests — verification is the grep/ls commands embedded in the
steps.

## Done criteria

- [ ] `git ls-files .kotlin/ .qoder/` → empty
- [ ] `.gitignore` contains `.kotlin/` and `.qoder/`
- [ ] README JDK line says 21
- [ ] AGENTS.md test count matches `find` output; no phantom UI dirs
- [ ] No "wochat" branding in manifest (or noted as done by plan 009)
- [ ] `./gradlew help` exit 0
- [ ] `plans/README.md` status row updated

## STOP conditions

Stop and report back (do not improvise) if:
- `git ls-files local.properties` is NON-empty — a tracked
  `local.properties` may contain SDK paths/keys; report it and recommend
  `git rm --cached local.properties` + history scrub decision (maintainer
  call; do not rewrite history yourself).
- A "residue" dir actually contains files — report what's there.
- AGENTS.md has drifted so far that a section rewrite (not spot-fixes)
  would be needed — propose the rewrite outline instead.

## Maintenance notes

- Re-run the fact-check commands whenever the module tree changes; the
  highest-value line in AGENTS.md for agents is the "all screens live in
  AiChatApp.kt" note until DEBT-01's split lands.
- The repo directory name (`wochat/`) vs app name (AIchat) mismatch
  remains — renaming the working copy is a maintainer preference, not a
  repo change.
