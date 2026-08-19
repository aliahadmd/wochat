# wochat — Agent Guide

## Project Overview

wochat is an on-device AI chat Android app. It runs Gemma 4 E4B locally via llama.cpp (GGUF) with CPU and Vulkan GPU backends. All inference, memory indexing, and conversation persistence happen on-device — there is no cloud LLM dependency.

- **Package:** `com.aliahad.aichat`
- **Min SDK:** 33 · **Target SDK:** 36 · **Compile SDK:** 36
- **Application ID:** `com.aliahad.aichat`

## Module Structure

Single Gradle module (`:app`). Root project name is `Aichat`.

```
app/src/main/java/com/aliahad/aichat/
├── attachment/        # File/image/audio attachment inspection and processing
├── backup/            # Encrypted database export/import
├── context/           # Prompt context assembly (history, summaries, memories → token-bounded prompt)
├── core/              # Application-level DI and shared utilities
├── data/              # Room database, DAOs, entities, migrations, SQLCipher encryption
├── diagnostics/       # Runtime diagnostics and health reporting
├── inference/         # Inference engine abstraction (llama.cpp JNI + Vulkan service)
│   └── remote/        # AIDL-based Vulkan inference service interface
├── memory/            # Personal memory indexing (Room + AppSearch) and retrieval
├── model/             # GGUF model and vision projector download, validation, installation
├── residency/         # Foreground service keeping selected model resident in memory
├── settings/          # DataStore-backed app configuration and preferences
├── skill/             # User-defined skills with invocation history
├── ui/                # Jetpack Compose UI layer (all screens live in ui/AiChatApp.kt — monolith, split planned)
│   ├── navigation/    # Navigation graph and routing
│   ├── theme/         # Material 3 theme, colors, typography
│   └── viewmodel/     # Feature ViewModels with activity-scoped factory
├── AiChatApplication.kt
├── MainActivity.kt
└── ThinkingUiState.kt
```

Note: `device/`, `overlay/`, and `speech/` were removed features and no longer exist; there are no `ui/brief/` or `ui/settings/` subpackages.

**Native code** lives under `app/src/main/cpp/`:
- `CMakeLists.txt` — top-level native build
- `aichat_jni.cpp` — JNI bridge between Kotlin and llama.cpp
- `llama.cpp/` — vendored llama.cpp source
- `vulkan-headers/` — bundled Vulkan headers
- `cmake/` — CMake helper modules

**AIDL interfaces** under `app/src/main/aidl/`:
- `IVulkanInferenceService.aidl`
- `IVulkanGenerationCallback.aidl`

## Build Commands

```bash
# Unit tests
./gradlew testDebugUnitTest

# Instrumented test compilation check
./gradlew compileDebugAndroidTestKotlin

# Debug APK
./gradlew assembleDebug

# Release APK (requires signing credentials)
./gradlew assembleRelease

# Full clean build
./gradlew clean testDebugUnitTest assembleDebug
```

## Toolchain & Dependencies

| Component | Version |
|---|---|
| Gradle | 9.4.1 |
| AGP | 9.2.1 |
| Kotlin | 2.2.10 |
| KSP | 2.2.10-2.0.2 |
| JVM toolchain | 21 |
| NDK | 29.0.13113456 |
| CMake | 3.31.6 |
| Compose BOM | 2026.02.01 |
| Room | 2.8.4 |
| SQLCipher | 4.16.0 (local AAR) |
| ABI filter | arm64-v8a only |

## Database

- **Engine:** Room 2.8.4 with SQLCipher encryption (`sqlcipher-android-4.16.0.aar`)
- **Current version:** 20
- **Migrations:** 19 hand-written migrations (1→2 through 19→20) in `AppDatabase.kt`
- **Schema exports:** `app/schemas/com.aliahad.aichat.data.AppDatabase/` (versions 1–9 and 11–20, note: version 10 schema file is absent)
- **KSP schema location:** configured via `ksp { arg("room.schemaLocation", ...) }`

## Key Architecture Constraints

1. **No network inference.** All LLM inference runs locally via llama.cpp JNI. The Vulkan backend runs in a separate Android Service (`inference/remote/`) with AIDL IPC; CPU is the fallback.
2. **SQLCipher encryption is mandatory.** The database must always be opened with a passphrase. `DatabaseEncryption.kt` manages key derivation and verification.
3. **Single ABI.** Only `arm64-v8a` is built. Native code uses Vulkan for GPU acceleration with automatic CPU fallback on GPU failure.
4. **Release signing via Keychain (macOS).** Release builds read the keystore password from macOS Keychain (`security find-generic-password`) or environment variables (`AICHAT_RELEASE_STORE_PASSWORD`, `AICHAT_RELEASE_KEY_PASSWORD`).
5. **R8 minification for release.** Release builds use `isMinifyEnabled = true` with `isShrinkResources = true` and custom keep rules at `app/src/main/keepRules/rules.keep`.
6. **Foreground service for model residency.** The `residency/` package maintains a foreground service to keep the selected GGUF model loaded in memory.
7. **Dependency resolution is centralized.** `FAIL_ON_PROJECT_REPOS` is set — all dependencies resolve through Google and Maven Central repos declared in the root `build.gradle.kts`. No per-module repo declarations.

## Test Layout

- **Unit tests:** `app/src/test/java/com/aliahad/aichat/` (25 files — inference recovery and engine fallback, restore fingerprints, memory search and redaction, prompt planning, residency, attachment detection, artifact downloading and atomic install, backup sweep and path safety, health collection keys, conversation export/search helpers, routing)
- **Instrumented tests:** `app/src/androidTest/java/com/aliahad/aichat/` (9 files — Compose UI, ChatViewModel, Room migrations, persistence, AppSearch indexer, encrypted backup round-trip)

## Agent Skills

The `.agents/skills/` directory contains declarative markdown skills consumed by AI agent tooling (not compiled code). These guide agents through Android development tasks like Compose UI, Perfetto trace analysis, R8 keep-rule optimization, and testing setup.
