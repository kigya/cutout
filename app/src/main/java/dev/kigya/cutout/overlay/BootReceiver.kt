package dev.kigya.cutout.overlay

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Settings
import dev.kigya.cutout.data.BubblePreferences
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

@AndroidEntryPoint
class BootReceiver : BroadcastReceiver() {
    @Inject lateinit var preferences: BubblePreferences

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        if (!Settings.canDrawOverlays(context)) return
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                if (preferences.enabled.first()) {
                    runCatching { BubbleStarter(context.applicationContext).start() }
                }
            } finally {
                pending.finish()
            }
        }
    }
}
