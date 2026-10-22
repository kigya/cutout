package dev.kigya.cutout

import android.app.Activity
import java.util.WeakHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class MainActivitySnapshot(
    val revision: Int = 0,
    val visible: Boolean = false,
    val resumed: Boolean = false,
)

/** Activity visibility is independent of the React runtime kept alive by the overlay. */
@Singleton
class MainActivityState @Inject constructor() {
    private val started = WeakHashMap<Activity, Boolean>()
    private val resumed = WeakHashMap<Activity, Boolean>()
    private val mutableState = MutableStateFlow(MainActivitySnapshot())
    val state = mutableState.asStateFlow()

    fun started(activity: Activity) { started[activity] = true; publish() }
    fun resumed(activity: Activity) { resumed[activity] = true; publish() }
    fun paused(activity: Activity) { resumed.remove(activity); publish() }
    fun stopped(activity: Activity) { started.remove(activity); resumed.remove(activity); publish() }
    fun destroyed(activity: Activity) = stopped(activity)

    private fun publish() {
        mutableState.value = MainActivitySnapshot(
            revision = mutableState.value.revision + 1,
            visible = started.isNotEmpty(),
            resumed = resumed.isNotEmpty(),
        )
    }
}
