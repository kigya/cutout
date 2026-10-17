package dev.kigya.cutout.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ClipSourcesTest {
    @Test
    fun htmlImageAndPlainUriBecomeLocalSources() {
        val sources = clipSources(
            listOf(
                ClipPiece(
                    html = """<img src="https://example.com/a.png"><img src="content://gallery/1">""",
                    text = "/sdcard/Pictures/object.png",
                ),
            ),
        )
        assertEquals(
            listOf(
                "content://gallery/1",
                "file:///sdcard/Pictures/object.png",
            ),
            sources.filterIsInstance<ClipSource.LocalUri>().map { it.uri },
        )
    }

    @Test
    fun svgTextIsKeptAheadOfAUri() {
        val sources = clipSources(
            listOf(
                ClipPiece(
                    uri = "content://gallery/1",
                    text = """<svg viewBox="0 0 10 10"><circle cx="5" cy="5" r="2"/></svg>""",
                ),
            ),
        )
        assertTrue(sources.first() is ClipSource.Svg)
        assertTrue(sources.any { it is ClipSource.LocalUri })
    }

    @Test
    fun streamUriIsIncluded() {
        val sources = clipSources(
            listOf(ClipPiece(streamUris = listOf("content://media/external/images/media/7"))),
        )
        assertEquals("content://media/external/images/media/7", (sources.single() as ClipSource.LocalUri).uri)
    }

    @Test
    fun imageMimeEnablesClipboardAndTextDoesNot() {
        assertTrue(clipHasImage(listOf("image/png")))
        assertTrue(clipHasImage(listOf("image/svg+xml")))
        assertTrue(!clipHasImage(listOf("text/plain")))
        assertTrue(!clipHasImage(emptyList()))
    }
}
