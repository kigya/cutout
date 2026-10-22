package dev.kigya.cutout.domain

data class MaskVerdict(
    val usable: Boolean,
)

object MaskPolicy {
    const val MIN_FRACTION = 0.02f
    const val MAX_FRACTION = 0.95f

    fun evaluate(foregroundFraction: Float): MaskVerdict {
        val usable = foregroundFraction in MIN_FRACTION..MAX_FRACTION
        return MaskVerdict(usable = usable)
    }

    fun foregroundFraction(confidence: FloatArray, threshold: Float = 0.5f): Float {
        if (confidence.isEmpty()) return 0f
        var foreground = 0
        for (value in confidence) {
            if (value >= threshold) foreground += 1
        }
        return foreground.toFloat() / confidence.size.toFloat()
    }
}
