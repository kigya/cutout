package dev.kigya.cutout.overlay

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BubbleStarter @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    fun start() {
        ContextCompat.startForegroundService(
            context,
            Intent(context, BubbleService::class.java),
        )
    }

    fun stop() {
        context.stopService(Intent(context, BubbleService::class.java))
    }
}
