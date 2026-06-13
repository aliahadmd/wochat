package com.aliahad.aichat.speech

import com.aliahad.aichat.core.SpeechAssetKind

data class OfficialSpeechAssetSpec(
    val id: String,
    val kind: SpeechAssetKind,
    val displayName: String,
    val archiveFileName: String,
    val archiveRoot: String,
    val installDirectory: String,
    val sizeBytes: Long,
    val sha256: String,
    val downloadUrl: String,
    val installedFileSizes: Map<String, Long> = emptyMap(),
) {
    val workName: String
        get() = "official-speech-download-$id"
}

object SpeechAssetConstants {
    val ZIPFORMER_ASR = OfficialSpeechAssetSpec(
        id = "zipformer-en-20m-int8",
        kind = SpeechAssetKind.ASR,
        displayName = "Zipformer English 20M INT8",
        archiveFileName = "sherpa-onnx-streaming-zipformer-en-20M-2023-02-17.tar.bz2",
        archiveRoot = "sherpa-onnx-streaming-zipformer-en-20M-2023-02-17",
        installDirectory = "zipformer-en-20m-int8",
        sizeBytes = 127_887_156L,
        sha256 = "9c559283e8498d3fe95913c79ca1cb454bb26281ac2b102b41306c7d752765d9",
        downloadUrl = "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/" +
            "sherpa-onnx-streaming-zipformer-en-20M-2023-02-17.tar.bz2",
        installedFileSizes = mapOf(
            "encoder-epoch-99-avg-1.int8.onnx" to 42_845_182L,
            "decoder-epoch-99-avg-1.onnx" to 2_092_272L,
            "joiner-epoch-99-avg-1.int8.onnx" to 259_572L,
            "tokens.txt" to 5_048L,
        ),
    )

    val KITTEN_TTS = OfficialSpeechAssetSpec(
        id = "kitten-nano-en-v0-8-int8",
        kind = SpeechAssetKind.TTS,
        displayName = "KittenTTS Nano 0.8 INT8",
        archiveFileName = "kitten-nano-en-v0_8-int8.tar.bz2",
        archiveRoot = "kitten-nano-en-v0_8-int8",
        installDirectory = "kitten-nano-en-v0-8-int8",
        sizeBytes = 31_220_690L,
        sha256 = "6fa5be852612ce761094ba74ee6123b4fc4acfefa79bf64dc63acae4a83af2fd",
        downloadUrl = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/" +
            "kitten-nano-en-v0_8-int8.tar.bz2",
    )

    val OFFICIAL_ASSETS = listOf(ZIPFORMER_ASR, KITTEN_TTS)

    const val WORK_INPUT_ASSET_ID = "speech_asset_id"
    const val DOWNLOAD_CHANNEL_ID = "speech_downloads"

    fun officialAsset(id: String): OfficialSpeechAssetSpec? =
        OFFICIAL_ASSETS.firstOrNull { it.id == id }

    fun officialAsset(kind: SpeechAssetKind): OfficialSpeechAssetSpec =
        OFFICIAL_ASSETS.first { it.kind == kind }
}
