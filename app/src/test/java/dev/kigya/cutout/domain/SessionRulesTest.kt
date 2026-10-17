package dev.kigya.cutout.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionRulesTest {
    @Test
    fun everyPermissionIsOptionalForCuttingInsideTheApp() {
        PermissionKind.entries.forEach { kind ->
            assertEquals(if (kind == PermissionKind.Overlay) PermissionNeed.RequiredForBubble else PermissionNeed.Optional, permissionNeed(kind))
            assertFalse(blocksInAppCutout(kind))
        }
    }

    @Test
    fun hideClearsTheBubbleAndSetStoresTheRequest() {
        assertFalse(nextBubbleEnabled(BubbleVisibilityAction.Hide, requested = true))
        assertTrue(nextBubbleEnabled(BubbleVisibilityAction.Set, requested = true))
        assertFalse(nextBubbleEnabled(BubbleVisibilityAction.Set, requested = false))
    }

    @Test
    fun editedFrameCopiesOnlyWhenAPixelRemains() {
        assertEquals(0, opaqueCount(intArrayOf(0, 0)))
        assertEquals(1, opaqueCount(intArrayOf(0, 1)))
        assertEquals(1, opaqueCount(intArrayOf(0, 9)))
        assertFalse(canCopyEdited(0))
        assertTrue(canCopyEdited(opaqueCount(intArrayOf(9))))
    }
}
