package dev.kigya.cutout.data

import java.io.ByteArrayOutputStream
import java.io.InputStream

const val MAX_IMAGE_BYTES = 32 * 1024 * 1024

/** Bounds compressed input before bitmap decode/downsampling allocates more memory. */
fun readImageBytes(input: InputStream, maximum: Int = MAX_IMAGE_BYTES): ByteArray? {
    require(maximum > 0)
    val output = ByteArrayOutputStream(minOf(maximum, 8192))
    val buffer = ByteArray(8192)
    var total = 0
    while (true) {
        val count = input.read(buffer)
        if (count < 0) break
        if (count == 0) continue
        if (count > maximum - total) return null
        output.write(buffer, 0, count)
        total += count
    }
    return output.toByteArray()
}
