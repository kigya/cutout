package dev.kigya.cutout.domain

enum class BubbleVisibilityAction {
    Hide,
    Set,
}

fun nextBubbleEnabled(action: BubbleVisibilityAction, requested: Boolean = false): Boolean = when (action) {
    BubbleVisibilityAction.Hide -> false
    BubbleVisibilityAction.Set -> requested
}

fun opaqueCount(alpha: IntArray, threshold: Int = 0): Int {
    var count = 0
    for (value in alpha) {
        if (value > threshold) count += 1
    }
    return count
}

fun canCopyEdited(opaquePixels: Int): Boolean = opaquePixels > 0
