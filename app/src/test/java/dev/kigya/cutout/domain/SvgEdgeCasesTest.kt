package dev.kigya.cutout.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Real SVG encodings that must not cause a foreground shape to be deleted. */
class SvgEdgeCasesTest {
    @Test fun `invalid viewport sides never identify a full bleed backdrop`() {
        for (attributes in listOf(
            "viewBox='0 0 0 100'", "viewBox='0 0 100 0'",
            "viewBox='0 0 -100 100'", "viewBox='0 0 100 -100'",
            "width='0' height='100'", "width='100' height='0'",
            "width='-1' height='100'", "width='100' height='-1'",
            "width='100'", "height='100'",
        )) {
            assertEquals(attributes, SvgEdit.NoSeparateBackdrop,
                stripSolidBackdrop("<svg $attributes><rect width='100' height='100'/></svg>"))
        }
    }

    @Test fun `short height far y and malformed dimensions preserve foreground rectangles`() {
        for (attributes in listOf(
            "width='100' height='97'", "x='0' y='3' width='100' height='100'",
            "width='100' height='invalid'", "width='100'",
            "width='invalid%' height='100%'", "width='100%' height='invalid%'",
        )) {
            assertEquals(attributes, SvgEdit.NoSeparateBackdrop,
                stripSolidBackdrop("<svg viewBox='0 0 100 100'><rect $attributes/></svg>"))
        }
    }

    @Test fun `default solid fill and missing coordinates are accepted while content is retained`() {
        val edited = stripSolidBackdrop("<svg viewBox='0 0 10 10'><rect width='100%' height='100%'/><circle r='1'/></svg>")
        assertTrue(edited is SvgEdit.Stripped)
        val result = (edited as SvgEdit.Stripped).svg
        assertFalse(result.contains("<rect"))
        assertTrue(result.contains("<circle"))
    }
}
