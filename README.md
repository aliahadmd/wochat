# AIchat

AIchat is a private, on-device Android assistant built around Gemma 4 E4B. Chats,
memory, attachments, skills, and model inference stay on the phone. The app does
not send prompts to a hosted inference service.

## Highlights

- **Local Gemma 4 E4B inference** using the official Q4 GGUF artifact.
- **Text and image conversations** with the matching Gemma vision projector.
- **CPU and Vulkan backends** with per-device recovery and benchmarking.
- **Automatic context sizing** that verifies safe context limits on the device.
- **Resumable model downloads** with pinned artifact revisions, SHA-256
  verification, progress, transfer rate, ETA, pause, resume, and retry controls.
- **Office Memory** for optional, private context from conversations and granted
  phone sources.
- **Prompt skills** that can be selected per message without running arbitrary
  code.
- **Encrypted portable backups** for chats, memory, settings, and attachments.
- **Encrypted local database** backed by SQLCipher.

AIchat intentionally has no speech-to-text, text-to-speech, floating overlay,
screen-action automation, or Daily Brief feature.

## Requirements

- Android 13 or newer (`minSdk 33`).
- A 64-bit ARM device (`arm64-v8a`).
- About 4.80 GB for the Gemma model.
- About 946 MB for the optional vision projector.
- At least 2 GB of additional free space while downloading and installing an
  artifact. Around 8 GB free is recommended for a complete setup.
- A modern device with ample RAM. AIchat verifies a conservative context size
  instead of assuming the model's maximum context will fit.

## Install

Download the APK from the repository's **Releases** page and install it on a
compatible Android device. Android may ask you to allow installation from the
app used to open the APK.

AIchat does not bundle model weights. On first launch:

1. Open **Settings → Models**.
2. Choose whether downloads may use mobile or metered networks.
3. Download **Gemma 4 E4B IT Q4**.
4. Optionally download the **Gemma 4 E4B vision projector** for image input.
5. Select the downloaded model if it is not selected automatically.

The official artifacts are public, so a Hugging Face token is normally not
required. Interrupted downloads keep a verified partial file and can resume.

## Privacy and permissions

Core chat and inference run locally. Model files are stored in app-private
storage, and Android backup is disabled.

Office Memory is optional. Each phone source requires explicit Android access
and can be disabled independently. Depending on the sources you choose, Android
may request access to usage statistics, installed apps, notifications,
accessibility text, location, activity recognition, contacts, calendar, or
Health Connect data. Password fields and keyboards are excluded from
accessibility collection, and sensitive number patterns are redacted before
storage.

Encrypted Office backups exclude model files, authentication tokens, indexes,
and encryption keys.

## Build from source

### Toolchain

- Android Studio with Android SDK 36
- JDK 17
- Android NDK `29.0.13113456`
- CMake `3.31.6`

The native runtime is built from the vendored `llama.cpp` sources with CPU,
KleidiAI, OpenMP, and Vulkan support enabled.

### Build and test

```bash
./gradlew testDebugUnitTest compileDebugAndroidTestKotlin assembleDebug
```

The debug APK is written to:

```text
app/build/outputs/apk/debug/app-debug.apk
```

### Release signing

Production releases use `release-signing/AIchat-release.jks`, which is excluded
from Git. On the release Mac, the password is stored in the system Keychain under
service `com.aliahad.aichat.release-signing` and account `aichat-release`.
Back up the keystore securely: losing it prevents future APK updates from using
the same signing identity.

For CI or another workstation, restore the keystore to the same path and set:

```text
AICHAT_RELEASE_STORE_PASSWORD
AICHAT_RELEASE_KEY_PASSWORD
```

Build the signed, optimized APK with:

```bash
./gradlew assembleRelease
```

To run connected instrumentation tests:

```bash
./gradlew connectedDebugAndroidTest
```

Some Android vendors require a separate developer setting before USB-installed
test APKs are accepted.

## Project structure

```text
app/src/main/java/com/aliahad/aichat/
├── activity/      Optional Office Memory collectors
├── attachment/    Private attachment ingestion and processing
├── backup/        Encrypted Office export and import
├── context/       Per-model context verification
├── data/          Room and SQLCipher persistence
├── diagnostics/   Private runtime diagnostics export
├── inference/     CPU/Vulkan inference and recovery
├── memory/        Personal memory retrieval and indexing
├── model/         Artifact catalog, download, and verification
├── residency/     Persistent local model lifecycle
├── settings/      App preferences and protected token storage
├── skill/         Prompt-only skill management
└── ui/            Jetpack Compose UI and navigation
```

Native inference code and the vendored runtime live under `app/src/main/cpp/`.
Room schema snapshots are stored under `app/schemas/` and must be committed with
database changes.

## Model artifacts

AIchat supports exactly these official artifacts:

| Artifact | Purpose | Approximate size |
| --- | --- | ---: |
| Gemma 4 E4B IT Q4 | Local text generation and reasoning | 4.80 GB |
| Gemma 4 E4B vision projector | Image input | 946 MB |

Artifact URLs are pinned to an immutable Hugging Face revision. Every completed
download must match its expected size, SHA-256 digest, and GGUF header before it
is installed.

## Troubleshooting downloads

- If a download remains **Queued**, check **Settings → Models → Download
  network**. Wi-Fi-only mode waits for an unmetered Android network.
- Use **Retry now** to skip WorkManager's retry delay after a temporary network
  failure.
- Use **Pause** before changing networks; **Resume** continues from the saved
  byte range.
- Keep at least the displayed required free space available.
- A metadata-mismatch error means the installed app catalog is outdated. Update
  AIchat before retrying instead of repeatedly downloading an unverified file.

## Current release scope

The first release focuses on dependable private chat, multimodal input, local
memory, skills, backups, and robust on-device inference. Model weights are
downloaded separately and remain subject to their upstream terms.
