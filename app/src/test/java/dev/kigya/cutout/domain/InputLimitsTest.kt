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
    @Test(expected = IllegalArgumentException::class) fun inputLimitRejectsInvalidLimits() {
        readImageBytes(ByteArrayInputStream(byteArrayOf()), 0)
    }
}
