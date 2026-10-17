package dev.kigya.cutout.overlay

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BubbleStarter(
    private val context: Context,
    private val io: kotlinx.coroutines.CoroutineDispatcher,
) {
    @Inject constructor(@ApplicationContext context: Context) : this(context, kotlinx.coroutines.Dispatchers.IO)

    suspend fun start() = kotlinx.coroutines.withContext(io) {
        ContextCompat.startForegroundService(
            context,
            Intent(context, BubbleService::class.java),
        )
    }

    suspend fun stop() = kotlinx.coroutines.withContext(io) {
        context.stopService(Intent(context, BubbleService::class.java))
    }
}
