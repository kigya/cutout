package dev.kigya.cutout.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProductRulesTest {
    @Test
    fun maskRejectsNearlyEmptyAndNearlyFull() {
        assertFalse(MaskPolicy.evaluate(0.019f).usable)
        assertFalse(MaskPolicy.evaluate(0.96f).usable)
        assertTrue(MaskPolicy.evaluate(0.02f).usable)
        assertTrue(MaskPolicy.evaluate(0.95f).usable)
        val samples = FloatArray(100) { index -> if (index == 0) 1f else 0f }
        assertEquals(0.01f, MaskPolicy.foregroundFraction(samples), 0.0001f)
        assertEquals(0f, MaskPolicy.foregroundFraction(floatArrayOf()), 0f)
        assertEquals(0.5f, MaskPolicy.foregroundFraction(floatArrayOf(0.49f, 0.5f), threshold = 0.5f), 0.0001f)
    }

    @Test
    fun svgDropsOnlyTheFullBleedBackdrop() {
        val svg = """
            <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 100 100">
              <rect width="100%" height="100%" fill="#ffffff"/>
              <circle cx="50" cy="50" r="20" fill="#000"/>
            </svg>
        """.trimIndent()
        val edited = stripSolidBackdrop(svg)
        assertTrue(edited is SvgEdit.Stripped)
        val text = (edited as SvgEdit.Stripped).svg
        assertFalse(text.contains("<rect"))
        assertTrue(text.contains("circle"))
    }

    @Test
    fun svgWithoutBackdropStaysUntouched() {
        val svg = """
            <svg viewBox="0 0 100 100">
              <circle cx="50" cy="50" r="20" fill="#000"/>
            </svg>
        """.trimIndent()
        assertTrue(stripSolidBackdrop(svg) is SvgEdit.NoSeparateBackdrop)
        val transparent = """
            <svg viewBox="0 0 10 10">
              <rect width="10" height="10" fill="none"/>
              <circle cx="5" cy="5" r="2"/>
            </svg>
        """.trimIndent()
        assertTrue(stripSolidBackdrop(transparent) is SvgEdit.NoSeparateBackdrop)
        assertTrue(stripSolidBackdrop("not svg") is SvgEdit.NotSvg)
    }

    @Test
    fun xiaomiShowsPopupAndAutostartOthersDoNot() {
        assertEquals(OemFamily.Xiaomi, oemFamily("Xiaomi", "POCO"))
        assertEquals(OemFamily.Generic, oemFamily("Google", "Pixel"))
        val xiaomi = visiblePermissions(OemFamily.Xiaomi)
        assertTrue(PermissionKind.BackgroundPopup in xiaomi)
        assertTrue(PermissionKind.Autostart in xiaomi)
        val generic = visiblePermissions(OemFamily.Generic)
        assertFalse(PermissionKind.BackgroundPopup in generic)
        assertFalse(PermissionKind.Autostart in generic)
        val first = settingsAttempts(OemFamily.Xiaomi, PermissionKind.Autostart).first()
        assertEquals(
            "com.miui.permcenter.autostart.AutoStartManagementActivity",
            first.componentClass,
        )
    }
}
