package com.aliahad.aichat.model

data class OfficialModelSpec(
    val id: String,
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

    fun officialModel(id: String): OfficialModelSpec? =
        (OFFICIAL_MODELS + EMBEDDING_MODELS).firstOrNull { it.id == id }

    fun officialProjector(id: String): OfficialProjectorSpec? =
        OFFICIAL_PROJECTORS.firstOrNull { it.id == id }

    fun projectorForModel(modelId: String): OfficialProjectorSpec? =
        OFFICIAL_PROJECTORS.firstOrNull { it.modelId == modelId }
}
