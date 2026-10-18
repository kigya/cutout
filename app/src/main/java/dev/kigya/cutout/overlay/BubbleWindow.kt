package dev.kigya.cutout.overlay

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

@Singleton
class BubbleWindow @Inject constructor() {
    private val _geometry = MutableStateFlow<BubbleGeometry?>(null)
    val geometry: StateFlow<BubbleGeometry?> = _geometry.asStateFlow()
    private var nextRevision = _geometry.value?.revision ?: 0L

    var move: ((Float, Float) -> Unit)? = null
    var settle: (() -> Boolean)? = null
    var resize: ((Float, Float) -> Unit)? = null
    var acknowledgeGeometry: ((Long) -> Unit)? = null

    fun publishGeometry(value: BubbleGeometry) {
        _geometry.value = value
    }

    fun clearGeometry() {
        _geometry.value = null
    }

    /** Revisions remain monotonic if BubbleService is recreated in one process. */
    @Synchronized
    fun nextGeometryRevision(): Long {
        nextRevision = maxOf(nextRevision, _geometry.value?.revision ?: 0L) + 1L
        return nextRevision
    }
}
