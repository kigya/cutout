package dev.kigya.cutout.domain

/** Eligibility records attempts, never ratings or whether the OS actually showed a dialog. */
fun reviewEligible(exports: Int, now: Long, lastAttempt: Long, minimum: Long = 5, cooldownDays: Long = 90): Boolean {
    val threshold = minimum.takeIf { it in 3..1000 } ?: 5
    val days = cooldownDays.takeIf { it in 30..365 } ?: 90
    if (exports < threshold || now < 0) return false
    if (lastAttempt == 0L) return true
    return now >= lastAttempt && now - lastAttempt >= days * 86_400_000L
}
