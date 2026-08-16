package com.aliahad.aichat.memory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryVectorsTest {
    @Test
    fun encodeDecodeRoundTripsExactly() {
        val values = floatArrayOf(0.5f, -0.25f, 0f, 1f, -1f, 3.14159f)
        val decoded = MemoryVectors.decode(MemoryVectors.encode(values))
        assertEquals(values.size, decoded!!.size)
        values.indices.forEach { assertEquals(values[it], decoded[it], 0f) }
    }

    @Test
    fun decodeRejectsMalformedBlobs() {
        assertNull(MemoryVectors.decode(null))
        assertNull(MemoryVectors.decode(ByteArray(0)))
        // Not a whole number of float32s: a truncated or foreign blob must not be
        // read as a short vector, which would silently score against garbage.
        assertNull(MemoryVectors.decode(ByteArray(7)))
    }

    @Test
    fun cosineOfIdenticalNormalizedVectorsIsOne() {
        val v = normalized(floatArrayOf(1f, 2f, 3f))
        assertEquals(1f, MemoryVectors.cosine(v, v), 1e-5f)
    }

    @Test
    fun cosineOfOrthogonalVectorsIsZero() {
        assertEquals(
            0f,
            MemoryVectors.cosine(floatArrayOf(1f, 0f), floatArrayOf(0f, 1f)),
            1e-6f,
        )
    }

    @Test
    fun cosineOfOppositeVectorsIsMinusOne() {
        assertEquals(
            -1f,
            MemoryVectors.cosine(floatArrayOf(1f, 0f), floatArrayOf(-1f, 0f)),
            1e-6f,
        )
    }

    @Test
    fun mismatchedDimensionsScoreZeroRatherThanThrowing() {
        // A memory embedded by a different model must degrade to "no semantic
        // signal", not crash retrieval on the interactive path.
        assertEquals(
            0f,
            MemoryVectors.cosine(floatArrayOf(1f, 0f, 0f), floatArrayOf(1f, 0f)),
            0f,
        )
        assertEquals(0f, MemoryVectors.cosine(FloatArray(0), FloatArray(0)), 0f)
    }

    @Test
    fun cosineStaysWithinRangeUnderAccumulatedError() {
        val large = normalized(FloatArray(768) { 1f })
        assertTrue(MemoryVectors.cosine(large, large) <= 1f)
        assertTrue(MemoryVectors.cosine(large, large) >= 0.999f)
    }

    private fun normalized(values: FloatArray): FloatArray {
        val norm = kotlin.math.sqrt(values.sumOf { (it * it).toDouble() }).toFloat()
        return FloatArray(values.size) { values[it] / norm }
    }
}
