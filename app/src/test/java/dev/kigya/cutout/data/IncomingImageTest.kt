package dev.kigya.cutout.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IncomingImageTest {
    @Test fun `recognizes SVG roots after whitespace and regardless of root case`() {
        assertTrue(looksLikeSvg("  \n<svg viewBox='0 0 1 1'/>"))
        assertTrue(looksLikeSvg("<SVG/>"))
    }

    @Test fun `recognizes XML declarations containing an SVG document`() {
        assertTrue(looksLikeSvg("<?xml version='1.0'?><svg xmlns='http://www.w3.org/2000/svg'/>"))
    }

    @Test fun `does not treat unrelated text or a non-SVG root as SVG`() {
        assertFalse(looksLikeSvg("<html><body><svg/></body></html>"))
        assertFalse(looksLikeSvg("image.svg"))
        assertFalse(looksLikeSvg("<?xml version='1.0'?><html/>"))
    }
}
