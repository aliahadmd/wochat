package com.aliahad.aichat.memory

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Conversion and similarity for stored sentence embeddings.
 *
 * Vectors are L2-normalized by the native embedder, so cosine similarity is a plain
 * dot product and needs no per-comparison normalization — which matters, because
 * ranking scans every candidate row on the interactive path.
 */
object MemoryVectors {
    /** Little-endian float32, matching how the values are read back. */
    fun encode(values: FloatArray): ByteArray {
        val buffer = ByteBuffer.allocate(values.size * Float.SIZE_BYTES).order(ByteOrder.LITTLE_ENDIAN)
        values.forEach(buffer::putFloat)
        return buffer.array()
    }

    fun decode(bytes: ByteArray?): FloatArray? {
        if (bytes == null || bytes.isEmpty() || bytes.size % Float.SIZE_BYTES != 0) return null
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        return FloatArray(bytes.size / Float.SIZE_BYTES) { buffer.getFloat() }
    }

    /**
     * Cosine similarity of two normalized vectors, in `[-1, 1]`.
     *
     * Returns 0 for mismatched or empty vectors instead of throwing: a memory
     * embedded by a different model is a ranking question, not a crash. Dimension
     * changes are otherwise silent, so callers must not treat 0 as "unrelated".
     */
    fun cosine(a: FloatArray, b: FloatArray): Float {
        if (a.isEmpty() || a.size != b.size) return 0f
        var sum = 0f
        for (index in a.indices) sum += a[index] * b[index]
        return sum.coerceIn(-1f, 1f)
    }
}
