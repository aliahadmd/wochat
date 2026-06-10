package com.aliahad.aichat.model

data class OfficialModelSpec(
    val id: String,
    val displayName: String,
    val repository: String,
    val fileName: String,
    val sizeBytes: Long,
    val sha256: String,
) {
    val downloadUrl: String
        get() = "https://huggingface.co/$repository/resolve/main/$fileName"

    val workName: String
        get() = "official-model-download-$id"
}

data class OfficialProjectorSpec(
    val id: String,
    val modelId: String,
    val displayName: String,
    val repository: String,
    val fileName: String,
    val sizeBytes: Long,
    val sha256: String,
) {
    val downloadUrl: String
        get() = "https://huggingface.co/$repository/resolve/main/$fileName"

    val workName: String
        get() = "official-projector-download-$id"
}

object ModelConstants {
    val GEMMA_4_E4B = OfficialModelSpec(
        id = "google-gemma-4-e4b-it-q4",
        displayName = "Gemma 4 E4B IT Q4",
        repository = "google/gemma-4-E4B-it-qat-q4_0-gguf",
        fileName = "gemma-4-E4B_q4_0-it.gguf",
        sizeBytes = 5_154_939_136L,
        sha256 = "e8b6a059ba86947a44ace84d6e5679795bc41862c25c30513142588f0e9dba1d",
    )

    val GEMMA_4_12B = OfficialModelSpec(
        id = "google-gemma-4-12b-it-q4",
        displayName = "Gemma 4 12B IT Q4",
        repository = "google/gemma-4-12B-it-qat-q4_0-gguf",
        fileName = "gemma-4-12b-it-qat-q4_0.gguf",
        sizeBytes = 6_975_877_728L,
        sha256 = "faff1a63667fac17ac5e777f47114688fcefea96e220e211aaa8d62c2c4561f1",
    )

    val OFFICIAL_MODELS = listOf(GEMMA_4_E4B, GEMMA_4_12B)

    val GEMMA_4_E4B_PROJECTOR = OfficialProjectorSpec(
        id = "google-gemma-4-e4b-mmproj",
        modelId = GEMMA_4_E4B.id,
        displayName = "Gemma 4 E4B vision projector",
        repository = "google/gemma-4-E4B-it-qat-q4_0-gguf",
        fileName = "gemma-4-E4B-it-mmproj.gguf",
        sizeBytes = 991_551_904L,
        sha256 = "c6398448d84a4836fdedf58f9775979e69ae0cc4dfdf4d697b5597693a555b12",
    )

    val GEMMA_4_12B_PROJECTOR = OfficialProjectorSpec(
        id = "google-gemma-4-12b-mmproj",
        modelId = GEMMA_4_12B.id,
        displayName = "Gemma 4 12B vision projector",
        repository = "google/gemma-4-12B-it-qat-q4_0-gguf",
        fileName = "mmproj-gemma-4-12b-it-qat-q4_0.gguf",
        sizeBytes = 175_115_264L,
        sha256 = "e70b0e5cd80323d5d588b4ed06780356b7b1ba03995a4b8164c6ae9db0ff5989",
    )

    val OFFICIAL_PROJECTORS = listOf(GEMMA_4_E4B_PROJECTOR, GEMMA_4_12B_PROJECTOR)

    const val DOWNLOAD_CHANNEL_ID = "model_downloads"
    const val WORK_INPUT_MODEL_ID = "model_id"
    const val WORK_INPUT_PROJECTOR_ID = "projector_id"

    fun officialModel(id: String): OfficialModelSpec? =
        OFFICIAL_MODELS.firstOrNull { it.id == id }

    fun officialProjector(id: String): OfficialProjectorSpec? =
        OFFICIAL_PROJECTORS.firstOrNull { it.id == id }

    fun projectorForModel(modelId: String): OfficialProjectorSpec? =
        OFFICIAL_PROJECTORS.firstOrNull { it.modelId == modelId }
}
