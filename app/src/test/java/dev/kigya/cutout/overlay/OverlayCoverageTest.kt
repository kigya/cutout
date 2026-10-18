package dev.kigya.cutout.overlay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class OverlayCoverageTest {
    @Test
    fun `window publishes and clears geometry while revisions stay monotonic`() {
        val window = BubbleWindow()
        assertNull(window.geometry.value)
        assertFalse(window.interacting.value)

        window.setInteracting(true)
        assertTrue(window.interacting.value)
        window.setInteracting(false)
        assertFalse(window.interacting.value)

        val geometry = BubbleGeometry(
            revision = 40,
            expanded = true,
            anchorEnd = false,
            windowWidthDp = 356,
            windowHeightDp = 340,
        )
        window.publishGeometry(geometry)
        assertSame(geometry, window.geometry.value)
        assertEquals(41L, window.nextGeometryRevision())

        window.clearGeometry()
        assertNull(window.geometry.value)
        assertEquals(42L, window.nextGeometryRevision())
    }

    @Test
    fun `geometry defaults and data value contract remain stable`() {
        val geometry = BubbleGeometry(
            revision = 1,
            expanded = false,
            anchorEnd = true,
            windowWidthDp = 72,
            windowHeightDp = 72,
        )
        assertEquals(640, geometry.surfaceWidthDp)
        assertEquals(400, geometry.surfaceHeightDp)
        assertEquals(72, geometry.chipSizeDp)
        assertEquals(260, geometry.panelWidthDp)
        assertEquals(260, geometry.panelHeightDp)
        assertEquals(272, geometry.chipLeftDp)
        assertEquals(0, geometry.chipTopDp)
        assertEquals(0, geometry.stateRevision)
        assertEquals(BubblePhase.Idle, geometry.phase)

        val copy = geometry.copy(revision = 2, phase = BubblePhase.Copied)
        assertNotEquals(geometry, copy)
        assertEquals(geometry.hashCode(), geometry.copy().hashCode())
        assertTrue(geometry.toString().contains("windowWidthDp=72"))
        val (revision, expanded, anchorEnd, width, height) = geometry
        assertEquals(1L, revision)
        assertFalse(expanded)
        assertTrue(anchorEnd)
        assertEquals(72, width)
        assertEquals(72, height)
    }
}
