package dev.kigya.cutout.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SvgBackgroundTest {
    @Test fun `malformed XML wrong root and a doctype are not accepted as SVG`() {
        assertEquals(SvgEdit.NotSvg, stripSolidBackdrop("<svg>"))
        assertEquals(SvgEdit.NotSvg, stripSolidBackdrop("<html><rect width='10' height='10'/></html>"))
        assertEquals(
            SvgEdit.NotSvg,
            stripSolidBackdrop("<!DOCTYPE svg [<!ENTITY x SYSTEM 'file:///private'>]><svg viewBox='0 0 1 1'>&x;</svg>"),
        )
    }

    @Test fun `requires a valid viewport and a graphic before looking for a backdrop`() {
        assertEquals(SvgEdit.NoSeparateBackdrop, stripSolidBackdrop("<svg><rect width='10' height='10'/></svg>"))
        assertEquals(SvgEdit.NoSeparateBackdrop, stripSolidBackdrop("<svg viewBox='0 0 nope 10'><rect width='10' height='10'/></svg>"))
        assertEquals(SvgEdit.NoSeparateBackdrop, stripSolidBackdrop("<svg width='100%' height='100%'><rect width='10' height='10'/></svg>"))
        assertEquals(
            SvgEdit.NoSeparateBackdrop,
            stripSolidBackdrop("<svg viewBox='0 0 10 10'><defs/><title>empty</title><style>.x{}</style></svg>"),
        )
    }

    @Test fun `skips non-graphic elements and empty containers before the first actual graphic`() {
        val svg = """
            <svg viewBox="0 0 100 100">
              <metadata/><defs/><g/><a/><switch/>
              <g><rect x="0" y="0" width="100" height="100" fill="#fff"/></g>
              <path d="M1 1"/>
            </svg>
        """.trimIndent()

        val edited = stripSolidBackdrop(svg)

        assertTrue(edited is SvgEdit.Stripped)
        assertFalse((edited as SvgEdit.Stripped).svg.contains("<rect"))
        assertTrue(edited.svg.contains("<path"))
    }

    @Test fun `rejects partial transparent patterned and malformed rectangles`() {
        val rectangleCases = listOf(
            "<rect x='3' y='0' width='100' height='100' fill='#fff'/>",
            "<rect x='0' y='0' width='97' height='100' fill='#fff'/>",
            "<rect width='100' height='100' fill='none'/>",
            "<rect width='100' height='100' fill='transparent'/>",
            "<rect width='100' height='100' fill='url(#paint)'/>",
            "<rect width='bad' height='100' fill='#fff'/>",
            "<rect height='100' fill='#fff'/>",
            "<circle cx='50' cy='50' r='40'/>",
        )

        rectangleCases.forEach { graphic ->
            val svg = "<svg viewBox='0 0 100 100'>$graphic</svg>"
            assertEquals(graphic, SvgEdit.NoSeparateBackdrop, stripSolidBackdrop(svg))
        }
    }

    @Test fun `uses percent pixel and root dimensions while allowing small full bleed tolerances`() {
        val percent = stripSolidBackdrop(
            "<svg viewBox='10,20,100,100'><rect x='11' y='21' width='98%' height='98%' fill='#fff'/><path d='M1 1'/></svg>",
        )
        assertTrue(percent is SvgEdit.Stripped)

        val pixels = stripSolidBackdrop(
            "<svg width='100px' height='80px'><rect x='0' y='0' width='100px' height='80px'/><path d='M1 1'/></svg>",
        )
        assertTrue(pixels is SvgEdit.Stripped)

        val prefixed = stripSolidBackdrop(
            "<svg:svg xmlns:svg='http://www.w3.org/2000/svg' viewBox='0 0 10 10'><svg:rect width='10' height='10'/><svg:path d='M1 1'/></svg:svg>",
        )
        assertTrue(prefixed is SvgEdit.Stripped)
    }

    @Test fun `falls back to valid root dimensions when the declared viewBox is malformed`() {
        val edited = stripSolidBackdrop(
            "<svg viewBox='0 nope 10 10' width='10px' height='10px'><rect width='100%' height='100%'/><path d='M1 1'/></svg>",
        )

        assertTrue(edited is SvgEdit.Stripped)
        assertFalse((edited as SvgEdit.Stripped).svg.contains("<rect"))
        assertTrue(edited.svg.contains("<path"))
    }

    @Test fun `ignored SVG metadata and executable elements do not hide a nested backdrop`() {
        val source = """
            <svg viewBox="0 0 10 10">
              <defs><rect width="10" height="10"/></defs>
              <script>window.example = 1;</script><desc>description</desc><filter/>
              <g><rect width="10" height="10" fill="#fff"/></g>
              <path d="M1 1"/>
            </svg>
        """.trimIndent()

        val edited = stripSolidBackdrop(source)

        assertTrue(edited is SvgEdit.Stripped)
        assertFalse((edited as SvgEdit.Stripped).svg.contains("<rect"))
        assertTrue(edited.svg.contains("<path"))
    }
}
