# Plan 005: Execute instrumented tests in CI and validate the Gradle wrapper

> **Executor instructions**: Follow this plan step by step. Run every
> verification command and confirm the expected result before moving to the
> next step. If anything in the "STOP conditions" section occurs, stop and
> report — do not improvise. When done, update the status row for this plan
> in `plans/README.md` — unless a reviewer dispatched you and told you they
> maintain the index.
>
> **Drift check (run first)**: `git diff --stat 2dff7e6..HEAD -- .github/workflows/ci.yml`
> If the file changed since this plan was written, compare the
> "Current state" excerpts against the live code before proceeding; on a
> mismatch, treat it as a STOP condition.

## Status

- **Priority**: P1
- **Effort**: M
- **Risk**: LOW (additive CI; the only hazard is emulator flakiness — mitigated by `continue-on-error` initially)
- **Depends on**: none
- **Category**: tests
- **Planned at**: commit `2dff7e6`, 2026-08-15

## Why this matters

The repo has 9 instrumented test files — including the full 15-step Room
migration chain, the encrypted backup round-trip, persistence, AppSearch
indexing, and a 480-line chat send-path test — but CI only
compile-checks them. Room schema drift, migration regressions, and Compose
UI breakage ship to `main` undetected. Separately, CI never validates the
Gradle wrapper jar, so a modified `gradle-wrapper.jar` in a PR would
execute arbitrary code on every runner before any check runs.

Constraint that shapes the design: the app is **arm64-v8a only** with
native llama.cpp libs, so standard x86_64 Linux emulators cannot run the
APK. Use a macOS runner with an arm64 system image.

## Current state

- `.github/workflows/ci.yml` — single `build` job (excerpt):

```yaml
# ci.yml:12-42 (abridged)
jobs:
  build:
    runs-on: ubuntu-latest
    timeout-minutes: 60
    steps:
      - uses: actions/checkout@v4
      - name: Set up JDK 21
        uses: actions/setup-java@v4
        with: { java-version: "21", distribution: "temurin" }
      - name: Setup Gradle
        uses: gradle/actions/setup-gradle@v4
      - name: Run unit tests
        run: ./gradlew testDebugUnitTest
      - name: Run detekt
        run: ./gradlew detekt
      - name: Compile Android test sources
        run: ./gradlew compileDebugAndroidTestKotlin
      - name: Assemble debug APK
        run: ./gradlew assembleDebug
      - name: Run Android lint
        run: ./gradlew lint
      # ... artifact uploads (test-results, lint-report, detekt-reports)
```

  No `gradle/actions/wrapper-validation` step; no `connected` run; no
  emulator job. README.md documents `connectedDebugAndroidTest` for humans.

- ABI filter `arm64-v8a` is set in `app/build.gradle.kts` (around lines
  63–65, `ndk.abiFilters`). Emulator images for macOS arm64 runners:
  `system-images/android-34/google_apis/arm64-v8a` (API 34 ≥ minSdk 33).

Conventions: workflow uses `actions/checkout@v4`, `actions/setup-java@v4`,
`gradle/actions/setup-gradle@v4`; concurrency group with cancel-in-progress.

## Commands you will need

| Purpose | Command | Expected on success |
|---------|---------|---------------------|
| Workflow lint (local, optional) | `python3 -c "import yaml,sys; yaml.safe_load(open('.github/workflows/ci.yml'))"` | no output, exit 0 |

(CI correctness is ultimately proven by a push/PR run; there is no local
GitHub Actions runner assumed.)

## Scope

**In scope**:
- `.github/workflows/ci.yml`

**Out of scope**:
- Any test source file, `app/build.gradle.kts`, gradle wrapper files.
- Release builds (deferred finding DX-03).

## Steps

### Step 1: Add wrapper validation

Insert immediately after `actions/checkout@v4` in the `build` job:

```yaml
      - name: Validate Gradle wrapper
        uses: gradle/actions/wrapper-validation@v4
```

### Step 2: Add an `instrumented-tests` job

Append a second job (same top-level indentation as `build`):

```yaml
  instrumented-tests:
    runs-on: macos-latest
    timeout-minutes: 60
    continue-on-error: true   # remove after the suite proves stable for ~2 weeks
    steps:
      - uses: actions/checkout@v4
      - name: Set up JDK 21
        uses: actions/setup-java@v4
        with:
          java-version: "21"
          distribution: "temurin"
      - name: Setup Gradle
        uses: gradle/actions/setup-gradle@v4
      - name: Run instrumented tests
        uses: reactivecircus/android-emulator-runner@v2
        with:
          api-level: 34
          arch: arm64-v8a
          target: google_apis
          cores: 4
          ram-size: 6144M
          emulator-options: -no-window -gpu swiftshader_indirect -noaudio -no-boot-anim -camera-back none
          script: ./gradlew connectedDebugAndroidTest
      - name: Upload instrumented results
        if: always()
        uses: actions/upload-artifact@v4
        with:
          name: instrumented-test-results
          path: app/build/outputs/androidTest-results/connected/
```

Notes:
- `continue-on-error: true` keeps first-week emulator flakes from blocking
  PRs; the plan's maintenance note says when to remove it.
- macOS arm64 runners provide `hvf` acceleration; no KVM setup needed.

**Verify**: YAML parses (command above) and `grep -n "wrapper-validation" .github/workflows/ci.yml` shows the step.

### Step 3: Push and observe (or hand to maintainer)

If you have push rights and the maintainer approved running CI, open a PR
with these changes and confirm: (a) wrapper-validation step passes, (b) the
emulator job boots and `connectedDebugAndroidTest` executes (any test
FAILURES are out of scope for this plan — report them, do not fix test
code). If you cannot push, mark the plan DONE-pending-merge and note it.

## Test plan

- The CI run itself is the test. Expected: both jobs green (or
  instrumented job orange-but-non-blocking during the initial
  `continue-on-error` window).

## Done criteria

- [ ] `.github/workflows/ci.yml` parses as valid YAML
- [ ] `wrapper-validation` step is the first post-checkout step of `build`
- [ ] New `instrumented-tests` job present with arm64 image and
      `connectedDebugAndroidTest`
- [ ] No other files modified (`git status`)
- [ ] `plans/README.md` status row updated

## STOP conditions

Stop and report back (do not improvise) if:
- The emulator job cannot boot within the 60-minute timeout twice in a row
  (report logs; consider API 33 image or `-no-snapshot` options rather than
  improvising other runners).
- `connectedDebugAndroidTest` reveals pre-existing test failures — report
  them; do NOT edit test or production code in this plan.
- The workflow file already changed on main in conflicting ways (drift).

## Maintenance notes

- Remove `continue-on-error: true` once the job has been green on ~5
  consecutive PR runs.
- If macOS arm64 runner minutes become a concern, restrict the job to
  `pull_request` only (skip pushes to main).
- This plan unblocks per-step migration validation tests (deferred finding
  TEST-03) — once it lands, that follow-up becomes cheap.
