package dev.kigya.cutout.overlay

import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BubbleWindow @Inject constructor() {
    var move: ((Float, Float) -> Unit)? = null
    var settle: (() -> Boolean)? = null
    var resize: ((Float, Float) -> Unit)? = null
}
