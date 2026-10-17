package dev.kigya.cutout.data

sealed interface IncomingImage {
    data class Invalid(val message: String) : IncomingImage
    data class Raster(val bytes: ByteArray) : IncomingImage
    data class Svg(val text: String) : IncomingImage
}

fun looksLikeSvg(text: String): Boolean {
    val trimmed = text.trimStart()
    return trimmed.startsWith("<svg", ignoreCase = true) ||
        (trimmed.startsWith("<?xml") && trimmed.contains("<svg", ignoreCase = true))
}
