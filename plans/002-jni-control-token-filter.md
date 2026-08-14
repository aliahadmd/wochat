# Plan 002: Stop the JNI layer from silently dropping answer tokens shaped like `<...>`

> **Executor instructions**: Follow this plan step by step. Run every
> verification command and confirm the expected result before moving to the
> next step. If anything in the "STOP conditions" section occurs, stop and
> report — do not improvise. When done, update the status row for this plan
> in `plans/README.md` — unless a reviewer dispatched you and told you they
> maintain the index.
>
> **Drift check (run first)**: `git diff --stat 2dff7e6..HEAD -- app/src/main/cpp/aichat_jni.cpp`
> If the file changed since this plan was written, compare the
> "Current state" excerpts against the live code before proceeding; on a
> mismatch, treat it as a STOP condition.

## Status

- **Priority**: P1
- **Effort**: S
- **Risk**: MED (the filter exists to suppress control-tag leakage; the fix must not resurface raw control tags)
- **Depends on**: none
- **Category**: bug
- **Planned at**: commit `2dff7e6`, 2026-08-15

## Why this matters

In the JNI token stream, when the generation parser is inactive (thinking
disabled / plain answer mode), ANY token whose text starts with `<` and ends
with `>` is silently discarded — from both the streamed UI and the persisted
message, while remaining in KV context. Legitimate single tokens like
`<div>`, `<td>`, `<s>` in code answers or bracketed prose simply vanish,
corrupting generated code/markup and making persisted history diverge from
what the model actually produced.

## Current state

- `app/src/main/cpp/aichat_jni.cpp` — JNI bridge. The token emission
  function (around lines 895–932). Relevant excerpt:

```cpp
// aichat_jni.cpp:912-926
if (generation_parser_active) {
    generated_raw_text += result;
    queue_parsed_generation_output(true);
    if (!pending_generation_output.empty()) {
        return pop_generation_output(env);
    }
    return to_jstring(env, "");
}
if (result.size() >= 2 && result.front() == '<' && result.back() == '>') {
    return to_jstring(env, "");
}
last_token_channel = 2;
generated_answer_tokens++;
assistant_text += result;
return to_jstring(env, result);
```

  Nearby context shows the existing special-token awareness: tokens starting
  with `<unused` are counted and abort after 8 consecutive (lines 895–907,
  referencing "Gemma 4 … upstream issue #21516"). `llama.cpp` APIs available
  in this TU (it already includes llama headers): `llama_token_is_eog()`,
  and the vocab exposes control-token metadata via
  `llama_vocab_get_text` / token attribute checks
  (`llama_token_attr` / `LLAMA_TOKEN_ATTR_CTRL` family), depending on the
  vendored llama.cpp version — check `app/src/main/cpp/llama.cpp/include/llama.h`
  for the exact API names before writing code.

  Kotlin side: `NativeInferenceEngine.kt:262` does
  `if (token.isNotEmpty())` — empty strings are already skipped there, so
  the JNI may keep returning `""` for genuinely-control tokens.

Conventions: C++ here uses `LOGE(...)` for errors, camelCase locals, and
returns `to_jstring(env, ...)` for token text. Match it.

## Commands you will need

| Purpose | Command | Expected on success |
|---------|---------|---------------------|
| Build native + APK | `./gradlew assembleDebug` | exit 0 |
| Unit tests | `./gradlew testDebugUnitTest` | exit 0 |
| Detekt | `./gradlew detekt` | exit 0 |

## Scope

**In scope**:
- `app/src/main/cpp/aichat_jni.cpp`

**Out of scope**:
- `app/src/main/cpp/llama.cpp/**` (vendored upstream — never modify).
- `NativeInferenceEngine.kt` (no change needed; it already skips empty).
- The parser-active branch (lines 912–918) — it is correct.

## Steps

### Step 1: Replace the shape-based filter with a control-token check

In the non-parser path, replace:

```cpp
if (result.size() >= 2 && result.front() == '<' && result.back() == '>') {
    return to_jstring(env, "");
}
```

with a check against the model's actual control/special tokens. Preferred
implementation (adapt names to the vendored llama.h):

1. If the token id is available at this point in the loop (the caller holds
   the `llama_token` from sampling), test
   `llama_token_is_eog(vocab, token_id)` or the token attribute flags for
   control/unknown tokens, and suppress only those.
2. If only the text piece is available here, thread the token id down from
   the sampling loop into this function (follow the existing parameter
   style), then apply (1).
3. Keep the existing `<unused` counter logic untouched.

As a defensive fallback ONLY if neither works: suppress only exact matches
against a small static allowlist of Gemma control tags
(`<start_of_turn>`, `<end_of_turn>`, `<eos>`, `<bos>`, `<end_of_image>`,
plus anything already `<unused`-prefixed) and pass everything else through.

**Verify**: `./gradlew assembleDebug` → exit 0 (CMake + NDK compile).

### Step 2: Confirm behavior switch sites

Search the file for other places that filter `<...>`-shaped pieces in the
non-parser path (`grep -n "'<'" app/src/main/cpp/aichat_jni.cpp`) and
confirm none remain besides the ones covered by Step 1 and the `<unused`
counter.

**Verify**: `grep -n "result.front() == '<'" app/src/main/cpp/aichat_jni.cpp`
→ returns no matches (or only inside the parser-active branch, which must
not be touched).

## Test plan

- Native behavior cannot be unit-tested on the JVM. Verification is:
  - `./gradlew assembleDebug` succeeds.
  - If an arm64 device/emulator is available: enable a conversation, ask
    the model to write a small HTML snippet, and confirm `<div>` and
    `<td>` survive in the answer. If no device is available, record that
    in the plan status and rely on code review.
- Add no Kotlin test changes.

## Done criteria

- [ ] `./gradlew assembleDebug` exits 0
- [ ] The shape-based suppression at former line 920-922 is replaced by an
      explicit control-token check (or exact-tag allowlist fallback)
- [ ] The `<unused` counter logic is unchanged
- [ ] No files outside the in-scope list are modified (`git status`)
- [ ] `plans/README.md` status row updated

## STOP conditions

Stop and report back (do not improvise) if:
- The vendored `llama.h` exposes neither token-attribute APIs nor a usable
  `llama_token_is_eog`/vocab text lookup — report what IS available.
- The token id is not reachable at the filter site and threading it through
  would require touching more than the emission function and its single
  caller.
- The excerpt above doesn't match live code (drift).

## Maintenance notes

- If llama.cpp is upgraded, re-verify the control-token API names still
  exist (they move occasionally).
- Reviewers: the acceptance question is "does `<div>` survive while
  `<end_of_turn>` stays suppressed when the parser is off?"
- Deferred: streaming-side golden tests would require a native test harness
  that does not exist in this repo.
