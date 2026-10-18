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
    private val app: EraseApp get() = application as EraseApp

    override fun onStart() {
        app.mainActivityState.started(this)
        super.onStart()
    }

    override fun onResume() {
        app.bubbleLifecycle.beforeActivityResume()
        super.onResume()
        app.mainActivityState.resumed(this)
    }

    override fun onPause() {
        super.onPause()
        app.mainActivityState.paused(this)
        app.bubbleLifecycle.afterActivityCallback()
    }

    override fun onStop() {
        super.onStop()
        app.mainActivityState.stopped(this)
    }

    override fun onDestroy() {
        super.onDestroy()
        app.mainActivityState.destroyed(this)
        app.bubbleLifecycle.afterActivityCallback()
    }

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
        window.setBackgroundDrawableResource(R.color.splash_background)
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
