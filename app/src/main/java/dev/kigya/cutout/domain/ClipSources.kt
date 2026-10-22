package dev.kigya.cutout.domain

import dev.kigya.cutout.data.looksLikeSvg

sealed interface ClipSource {
    data class Svg(val text: String) : ClipSource
    data class LocalUri(val uri: String) : ClipSource
}

data class ClipPiece(
    val uri: String? = null,
    val streamUris: List<String> = emptyList(),
    val html: String? = null,
    val text: String? = null,
)

private val imgSrc = Regex(
    """<img\b[^>]*?\bsrc\s*=\s*(?:"([^"]+)"|'([^']+)'|([^\s>]+))""",
    RegexOption.IGNORE_CASE,
)

fun clipSources(pieces: List<ClipPiece>): List<ClipSource> {
    val sources = mutableListOf<ClipSource>()
    for (piece in pieces) {
        val text = piece.text?.trim()
        if (text != null && looksLikeSvg(text)) sources += ClipSource.Svg(text)
        val html = piece.html?.trim()
        if (html != null && looksLikeSvg(html)) sources += ClipSource.Svg(html)
        addLocal(sources, piece.uri)
        piece.streamUris.forEach { addLocal(sources, it) }
        if (html != null) imageSourcesFromHtml(html).forEach { addLocal(sources, it) }
        if (text != null && !looksLikeSvg(text)) addLocal(sources, text)
    }
    return sources
}

fun clipHasImage(mimeTypes: List<String>): Boolean {
    return mimeTypes.any { mime -> mime.startsWith("image/") }
}

fun imageSourcesFromHtml(html: String): List<String> {
    return imgSrc.findAll(html).map { match ->
        match.groupValues.drop(1).first { it.isNotEmpty() }
    }.toList()
}

fun localImageUri(raw: String): String? {
    val value = raw.trim()
    if (value.isEmpty()) return null
    val lower = value.lowercase()
    if (lower.startsWith("content:") || lower.startsWith("file:")) return value
    if (value.startsWith("/")) return "file://$value"
    return null
}

private fun addLocal(sources: MutableList<ClipSource>, raw: String?) {
    if (raw == null) return
    val uri = localImageUri(raw) ?: return
    sources += ClipSource.LocalUri(uri)
}
