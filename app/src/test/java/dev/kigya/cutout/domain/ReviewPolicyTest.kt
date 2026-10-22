package dev.kigya.cutout.domain

import org.junit.Assert.*
import org.junit.Test

class ReviewPolicyTest {
    @Test fun `first request needs successful use and uses conservative bounds`() {
        assertFalse(reviewEligible(4, 1000, 0))
        assertTrue(reviewEligible(5, 1000, 0))
        assertFalse(reviewEligible(100, -1, 0))
        assertTrue(reviewEligible(3, 1000, 0, 3))
        assertFalse(reviewEligible(4, 1000, 0, 0))
        assertFalse(reviewEligible(4, 1000, 0, 1001))
    }
    @Test fun `cooldown records attempts even if store did not show a dialog`() {
        val day = 86_400_000L
        assertFalse(reviewEligible(100, 90 * day, day))
        assertTrue(reviewEligible(100, 91 * day, day))
        assertFalse(reviewEligible(100, 0, day))
        assertTrue(reviewEligible(100, 31 * day, day, 5, 30))
        assertFalse(reviewEligible(100, 31 * day, day, 5, 29))
        assertFalse(reviewEligible(100, 31 * day, day, 5, 366))
        assertTrue(reviewEligible(100, 366 * day, day, 5, 365))
    }
}
