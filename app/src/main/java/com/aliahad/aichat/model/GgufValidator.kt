package com.aliahad.aichat.model

import java.io.File
import java.io.InputStream

object GgufValidator {
    private val magic = byteArrayOf('G'.code.toByte(), 'G'.code.toByte(), 'U'.code.toByte(), 'F'.code.toByte())

    fun validate(file: File): Result<Unit> = runCatching {
        require(file.isFile) { "Model file does not exist" }
        require(file.length() >= 16) { "File is too small to be a GGUF model" }
        file.inputStream().use(::validateStream)
    }

    fun validateStream(input: InputStream) {
        val header = ByteArray(4)
        require(input.read(header) == header.size && header.contentEquals(magic)) {
            "Selected file is not a GGUF model"
        }
    }
}
