package com.aliahad.aichat.model

data class OfficialModelSpec(
    val id: String,
    val displayName: String,
    val repository: String,
    val revision: String,
    val fileName: String,
    val sizeBytes: Long,
    val sha256: String,
    /**
     * Explicit URL for artifacts that are not Hugging Face files. Only the Piper
     * voice needs this: it ships as a GitHub release tarball because its
     * `espeak-ng-data` is 355 files, which no per-file download path can carry.
     */
    val absoluteUrl: String? = null,
    /**
     * When set, the verified download is a `.tar.bz2` whose contents are extracted
     * into the models directory, and readiness means this directory exists rather
     * than a single file.
     */
    val archiveRootDirectory: String? = null,
) {
    val downloadUrl: String
        get() = absoluteUrl ?: "https://huggingface.co/$repository/resolve/$revision/$fileName"

    val workName: String
        get() = "official-model-download-$id"
}

data class OfficialProjectorSpec(
    val id: String,
    val modelId: String,
    val displayName: String,
    val repository: String,
    val revision: String,
    val fileName: String,
    val sizeBytes: Long,
    val sha256: String,
) {
    val downloadUrl: String
        get() = "https://huggingface.co/$repository/resolve/$revision/$fileName"

    val workName: String
        get() = "official-projector-download-$id"
}

object ModelConstants {
    val GEMMA_4_E4B = OfficialModelSpec(
        id = "google-gemma-4-e4b-it-q4",
        displayName = "Gemma 4 E4B IT Q4",
        repository = "google/gemma-4-E4B-it-qat-q4_0-gguf",
        revision = GEMMA_4_E4B_REVISION,
        fileName = "gemma-4-E4B_q4_0-it.gguf",
        sizeBytes = 5_154_941_280L,
        sha256 = "676c35070db6dbe52f93e9c864ee0fba4eddea94b9c875d9cb10daff453fbaee",
    )


    /**
     * Sentence embedder for semantic memory recall, published by the llama.cpp
     * organisation so it tracks the vendored runtime. Small and CPU-only: it runs
     * once per stored memory and once per query, alongside the chat model.
     */
    val EMBEDDING_GEMMA_300M = OfficialModelSpec(
        id = "ggml-org-embeddinggemma-300m-q8",
        displayName = "EmbeddingGemma 300M Q8",
        repository = "ggml-org/embeddinggemma-300M-GGUF",
        revision = EMBEDDING_GEMMA_REVISION,
        fileName = "embeddinggemma-300M-Q8_0.gguf",
        sizeBytes = 333_590_944L,
        sha256 = "b5ce9d77a3fc4b3b39ccb5643c36777911cc4eb46a66962eadfa3f5f60490d63",
    )

    val OFFICIAL_MODELS = listOf(GEMMA_4_E4B)

    /** Downloadable but never selectable as a chat model. */
    val EMBEDDING_MODELS = listOf(EMBEDDING_GEMMA_300M)

    // ---------------------------------------------------------------------------
    // Voice call mode (plan 036). Three roles, one runtime (sherpa-onnx):
    // Silero VAD decides when the user has stopped, Whisper transcribes what they
    // said, and Piper speaks the answer.
    //
    // Every size and hash below was read from the server with `curl -sIL` before
    // being written down. Hugging Face LFS files expose their sha256 as
    // `x-linked-etag`; the two exceptions are noted where they occur.
    // ---------------------------------------------------------------------------

    /**
     * Silero VAD. It decides both when the user starts talking (the call screen's
     * listening state) and when they have finished — with Whisper being
     * non-streaming, this is what owns turn-taking.
     */
    val SILERO_VAD = OfficialModelSpec(
        id = "silero-vad-v5",
        displayName = "Silero voice activity detector",
        repository = "csukuangfj/vad",
        revision = "fba88cd2e921609e7675c3aaf51e0b9b295da4bc",
        fileName = "silero_vad.onnx",
        sizeBytes = 1_807_522L,
        sha256 = "a35ebf52fd3ce5f1469b2a36158dba761bc47b973ea3382b3186ca15b1f5af28",
    )

    /**
     * Whisper base.en, VAD-segmented rather than streaming.
     *
     * Replaces a streaming Zipformer (int8, LibriSpeech-style US English) that was
     * measured on the owner's own speech and produced "HALLO KA NU YERI" for "hello
     * can you hear me" — phonetically close, word-wrong, which is the signature of an
     * acoustic model mismatched to the speaker rather than of bad audio. Level, sample
     * rate and dropped-window count were all verified clean first.
     *
     * The trade, accepted deliberately: Whisper is not streaming, so there is no
     * word-by-word transcript while the user talks and recognition costs ~0.6-1.5 s
     * after they stop. Plan 037 measured the LLM at 1-1.7 s to first token in a fresh
     * conversation, so the whole turn still lands far inside this plan's 6 s budget.
     *
     * GitHub release assets carry no LFS hash; this sha256 is of the downloaded bytes.
     */
    val WHISPER_BASE_EN = OfficialModelSpec(
        id = "sherpa-whisper-base-en",
        displayName = "Speech recogniser (Whisper base.en)",
        repository = "k2-fsa/sherpa-onnx",
        revision = "asr-models",
        fileName = "sherpa-onnx-whisper-base.en.tar.bz2",
        sizeBytes = 208_576_005L,
        sha256 = "475bc7052ce299c007f6d5d5407ba8601f819a2867f6eecee510ed17df581542",
        absoluteUrl = "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/" +
            "sherpa-onnx-whisper-base.en.tar.bz2",
        archiveRootDirectory = "sherpa-onnx-whisper-base.en",
    )

    /**
     * Piper VITS, medium quality. Ships as a tarball rather than loose files
     * because Piper phonemises through `espeak-ng-data` — 355 files, 18 MB — which
     * a per-file download path cannot reasonably carry.
     *
     * GitHub release assets have no LFS hash, so this sha256 is of the downloaded
     * bytes; the extracted `.onnx` cross-checks against the Hugging Face mirror's
     * `acd9425049d887a4d04681010c3b2ed143898b824e5bc1f2ef6ddaee4b11ad0e`.
     *
     * This file holds 904 speakers. Plan 036 wanted six curated by listening;
     * the owner has no preference and chose the plan's sanctioned fallback, so v1
     * ships [DEFAULT_VOICE_SPEAKER_ID] and no picker. Adding one later is a config
     * change, not another download — which is why this voice was kept over the
     * single-speaker `lessac`.
     */
    val PIPER_VOICE_EN_US = OfficialModelSpec(
        id = "vits-piper-en-us-libritts-r-medium",
        displayName = "English voice (Piper)",
        repository = "k2-fsa/sherpa-onnx",
        revision = "tts-models",
        fileName = "vits-piper-en_US-libritts_r-medium.tar.bz2",
        sizeBytes = 82_038_311L,
        sha256 = "10dc268f3e371696d721486123e2705a9fc1faa113491979fde4d88dba1f1b1c",
        absoluteUrl = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/" +
            "vits-piper-en_US-libritts_r-medium.tar.bz2",
        archiveRootDirectory = "vits-piper-en_US-libritts_r-medium",
    )

    /** Arbitrary but fixed; see [PIPER_VOICE_EN_US]. Change only with a listened-to reason. */
    const val DEFAULT_VOICE_SPEAKER_ID = 0

    /**
     * Downloadable, never selectable as a chat model, and all required together
     * before call mode can run.
     */
    val VOICE_MODELS = listOf(
        SILERO_VAD,
        WHISPER_BASE_EN,
        PIPER_VOICE_EN_US,
    )

    val GEMMA_4_E4B_PROJECTOR = OfficialProjectorSpec(
        id = "google-gemma-4-e4b-mmproj",
        modelId = GEMMA_4_E4B.id,
        displayName = "Gemma 4 E4B vision projector",
        repository = "google/gemma-4-E4B-it-qat-q4_0-gguf",
        revision = GEMMA_4_E4B_REVISION,
        fileName = "gemma-4-E4B-it-mmproj.gguf",
        sizeBytes = 991_552_256L,
        sha256 = "7498a37cb619e55f2fcf87eb931f56e99389ed6d432e4c5c66110694c0d65578",
    )

    val OFFICIAL_PROJECTORS = listOf(GEMMA_4_E4B_PROJECTOR)

    const val DOWNLOAD_CHANNEL_ID = "model_downloads"
    const val WORK_INPUT_MODEL_ID = "model_id"
    const val WORK_INPUT_PROJECTOR_ID = "projector_id"

    private const val GEMMA_4_E4B_REVISION = "4b4a2c1d584be7264f87aac328a1bc739ce81b6c"
    private const val EMBEDDING_GEMMA_REVISION = "0f741b5a6585bd53aeb15cd1372c56f2a0f65e12"

    /** Every spec the app can download, in one place — the set the download worker resolves against. */
    val ALL_DOWNLOADABLE_MODELS = OFFICIAL_MODELS + EMBEDDING_MODELS + VOICE_MODELS

    fun officialModel(id: String): OfficialModelSpec? =
        ALL_DOWNLOADABLE_MODELS.firstOrNull { it.id == id }

    fun officialProjector(id: String): OfficialProjectorSpec? =
        OFFICIAL_PROJECTORS.firstOrNull { it.id == id }

    fun projectorForModel(modelId: String): OfficialProjectorSpec? =
        OFFICIAL_PROJECTORS.firstOrNull { it.modelId == modelId }
}
