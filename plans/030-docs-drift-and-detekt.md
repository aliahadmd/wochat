# Plan 030: Docs drift and detekt wiring

> **Executor instructions**: Follow this plan step by step. Run every
> verification command and confirm the expected result before moving to the
> next step. If anything in the "STOP conditions" section occurs, stop and
> report — do not improvise. When done, update the status row for this plan
> in `plans/README.md` — unless a reviewer dispatched you and told you they
> maintain the index.
>
> **Drift check (run first)**:
> `git diff --stat 619d7de..HEAD -- README.md AGENTS.md build.gradle.kts app/build.gradle.kts .github/workflows/ci.yml`

## Status

- **Priority**: P3
- **Effort**: S
- **Risk**: LOW
- **Depends on**: none
- **Category**: docs / DX
- **Planned at**: commit `619d7de`, 2026-08-15

## Why this matters

Two small, unrelated pieces of rot, bundled because each is too small for its
own plan.

**1. The docs still call the app "AIchat".** The app has shipped as `wochat`
since `439fa04` — `strings.xml` `app_name` is `wochat`, and so are the
notification copy, backup filenames, and the top-bar title
(`AiChatApp.kt:293`). But `README.md` still says "AIchat" in 8 places and
`AGENTS.md` in 2, including the README's title line and its install and
troubleshooting instructions.

This is *not* a call to rename the package. The split is deliberate and must
be preserved: user-facing copy says **wochat**, while identifiers keep
`aichat` — the Gradle `namespace`, `applicationId` (`com.aliahad.aichat`),
Kotlin package, class names (`AiChatApp`, `AiChatApplication`), the
`.aichatoffice` backup extension, the release keystore path, and the
`AICHAT_RELEASE_*` environment variables. Only prose about the *product*
changes.

**2. detekt is declared but never applied.** Root `build.gradle.kts:6` has
`alias(libs.plugins.detekt) apply false`, and a `config/` directory exists,
but no module applies the plugin. CI had a `Run detekt` step that referenced a
nonexistent task; it was deleted in `72879dc` rather than fixed, and the
`Upload detekt reports` artifact step still remains in `ci.yml` pointing at a
directory nothing writes.

## Current state

- `grep -c "AIchat" README.md AGENTS.md` → `README.md:8`, `AGENTS.md:2`.
- `AGENTS.md` is otherwise current — it already says "Current version: 17"
  for the database, matching `AppDatabase.VERSION`. Do not assume it is
  wholesale stale; check each claim.
- README claims worth re-verifying while you are in there: the module/package
  tree (`brief/` and `core/` are missing from README's listing but present in
  AGENTS.md), test counts, and the toolchain table.
- Root `build.gradle.kts:2-6`:

```kotlin
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.detekt) apply false
}
```

- `.github/workflows/ci.yml:58-63` — an `Upload detekt reports` step with
  `if: failure()`, pointing at `app/build/reports/detekt/`, which no task
  produces.

## Commands you will need

| Purpose | Command | Expected on success |
|---------|---------|---------------------|
| detekt | `./gradlew detekt` | exit 0 (after Step 3) |
| Unit tests | `./gradlew testDebugUnitTest` | exit 0 |
| Debug APK | `./gradlew assembleDebug` | exit 0 |

## Scope

**In scope**:
- `README.md`, `AGENTS.md`
- `app/build.gradle.kts` (apply detekt), `config/` (detekt config)
- `.github/workflows/ci.yml`

**Out of scope**:
- Renaming any identifier, package, `applicationId`, or the `.aichatoffice`
  extension. See STOP conditions — this is the most likely way to get this
  plan wrong.
- Fixing whatever detekt reports. Getting it to *run* is this plan; a clean
  baseline is a follow-up.

## Steps

### Step 1: Update user-facing prose only

Replace "AIchat" with "wochat" in `README.md` and `AGENTS.md` **only where
the text refers to the product**. Leave every occurrence that names a package,
class, file path, keystore, or environment variable.

Pay attention to these README lines specifically, which mix both:
- the release-signing section (`release-signing/AIchat-release.jks` is a real
  path — leave it; the surrounding prose is product text)
- the `com.aliahad.aichat.release-signing` Keychain service (leave it)

**Verify**: `grep -n "AIchat" README.md AGENTS.md` → every remaining hit is
an identifier, path, or credential name. List them in the status row.

### Step 2: Re-verify the docs' factual claims

While in the files, check and correct: the package tree (README omits `brief/`
and `core/`), unit and instrumented test counts, database version, and the
toolchain table against `gradle/libs.versions.toml`.

**Verify**: each claim checked against the source of truth, not against
memory.

### Step 3: Apply detekt

Apply the already-declared plugin to `:app`, add a config under `config/` if
one is not already there, and make `./gradlew detekt` run.

Do **not** make it a blocking CI gate in this plan and do not fix the
findings — a first run on a 3,481-line file will produce a lot. Record the
count.

**Verify**: `./gradlew detekt` → the task exists and runs.

### Step 4: Make CI honest

Either restore a non-blocking detekt step now that the task exists, or remove
the orphaned `Upload detekt reports` artifact step. Do not leave CI
referencing outputs nothing produces.

**Verify**: `.github/workflows/ci.yml` has no step pointing at a
nonexistent path.

## Test plan

- `./gradlew detekt` runs to completion.
- `./gradlew assembleDebug` and `testDebugUnitTest` unaffected.
- No source file under `app/src/` modified (`git status`) — this plan touches
  docs and build wiring only.

## Done criteria

- [ ] Product prose says "wochat"; every identifier still says `aichat`
- [ ] Remaining "AIchat" occurrences listed and justified
- [ ] README package tree, test counts, DB version, toolchain verified
- [ ] `./gradlew detekt` runs
- [ ] CI references no nonexistent outputs
- [ ] No `app/src/` changes
- [ ] `plans/README.md` status row updated with the detekt finding count

## STOP conditions

Stop and report back (do not improvise) if:
- You find yourself editing anything under `app/src/`, `applicationId`,
  `namespace`, or the `.aichatoffice` extension. The display-name/identifier
  split is deliberate; "fixing" the mismatch is a defect, not a cleanup.
- detekt's first run fails the build in a way that cannot be made
  non-blocking without suppressing whole rule sets.
- A README claim turns out to be wrong in a way that implies a real bug
  (for example, documented behavior the app no longer has) — report it as a
  finding rather than quietly editing the docs to match.

## Maintenance notes

- The rename rule, for anyone who touches these files later: **user-facing
  copy says wochat; identifiers, packages, and the backup format stay
  aichat.** The repo directory being `wochat` while the code says `aichat` is
  intentional.
- Reviewers: the acceptance question is "did anything outside docs and build
  wiring change?" It should not have.
