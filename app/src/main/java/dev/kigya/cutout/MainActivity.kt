package dev.kigya.cutout

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import androidx.activity.enableEdgeToEdge
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import dev.kigya.cutout.locale.AppLocales
import com.facebook.react.ReactActivity
import com.facebook.react.ReactActivityDelegate
import com.facebook.react.defaults.DefaultNewArchitectureEntryPoint.fabricEnabled
import com.facebook.react.defaults.DefaultReactActivityDelegate

class MainActivity : ReactActivity() {
    override fun getMainComponentName(): String = "Erase"

    override fun createReactActivityDelegate(): ReactActivityDelegate =
        DefaultReactActivityDelegate(this, mainComponentName, fabricEnabled)

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLocales.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        applySystemBarAppearance()
        emitEdit(intent)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        applySystemBarAppearance()
    }

    private fun applySystemBarAppearance() {
        val savedTheme = getSharedPreferences("cutout_preferences", Context.MODE_PRIVATE).getString("theme", "system")
        val systemDark = resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK == android.content.res.Configuration.UI_MODE_NIGHT_YES
        val light = when (savedTheme) { "light" -> true; "dark" -> false; else -> !systemDark }
        androidx.core.view.WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = light
            isAppearanceLightNavigationBars = light
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        emitEdit(intent)
    }

    private fun emitEdit(intent: Intent?) {
        if (intent?.getBooleanExtra(EXTRA_EDIT, false) != true) return
        val host = (application as EraseApp).reactHost
        host.currentReactContext?.emitDeviceEvent("eraseOpenEdit")
    }

    companion object {
        const val EXTRA_EDIT = "erase_edit"
    }
}
