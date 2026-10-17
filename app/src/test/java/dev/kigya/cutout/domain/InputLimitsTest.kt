package dev.kigya.cutout.domain

import dev.kigya.cutout.data.readImageBytes
import java.io.ByteArrayInputStream
import java.io.InputStream
import org.junit.Assert.*
import org.junit.Test

class InputLimitsTest {
    @Test fun inputLimitAcceptsExactSizeAndRejectsOversizedFiles() {
        assertArrayEquals(byteArrayOf(1, 2), readImageBytes(ByteArrayInputStream(byteArrayOf(1, 2)), 2))
        assertNull(readImageBytes(ByteArrayInputStream(byteArrayOf(1, 2, 3)), 2))
        assertArrayEquals(byteArrayOf(), readImageBytes(ByteArrayInputStream(byteArrayOf()), 2))
    }
    @Test fun inputLimitToleratesAZeroLengthReadWithoutCountingIt() {
        val source = object : InputStream() {
            var calls = 0
            override fun read(): Int = -1
            override fun read(b: ByteArray, off: Int, len: Int): Int = when (calls++) {
                0 -> 0
                1 -> { b[off] = 7; 1 }
                else -> -1
            }
        }
        assertArrayEquals(byteArrayOf(7), readImageBytes(source, 1))
    }
    @Test fun inputLimitEnforcesTheMaximumAcrossMultipleBufferReads() {
        val bytes = ByteArray(8_205) { (it % 251).toByte() }

        assertArrayEquals(bytes, readImageBytes(ByteArrayInputStream(bytes), bytes.size))
        assertNull(readImageBytes(ByteArrayInputStream(bytes), bytes.size - 1))
    }
    @Test fun inputReadFailureIsNotSilentlyReturnedAsATruncatedImage() {
        val source = object : InputStream() {
            private var first = true
            override fun read(): Int = error("single-byte read should not be used")
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (first) {
                    first = false
                    b[off] = 11
                    return 1
                }
                throw java.io.IOException("provider failed during read")
            }
        }

        try {
            readImageBytes(source, 8)
            throw AssertionError("IOException should propagate instead of accepting partial image bytes")
        } catch (expected: java.io.IOException) {
            assertEquals("provider failed during read", expected.message)
        }
    }
    @Test(expected = IllegalArgumentException::class) fun inputLimitRejectsInvalidLimits() {
        readImageBytes(ByteArrayInputStream(byteArrayOf()), 0)
    }
}
