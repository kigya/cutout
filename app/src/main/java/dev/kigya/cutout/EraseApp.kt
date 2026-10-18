package dev.kigya.cutout

import android.app.Application
import android.content.Context
import android.content.pm.ApplicationInfo
import dev.kigya.cutout.locale.AppLocales
import com.facebook.react.PackageList
import com.facebook.react.ReactApplication
import com.facebook.react.ReactHost
import com.facebook.react.ReactNativeApplicationEntryPoint.loadReactNative
import com.facebook.react.defaults.DefaultReactHost.getDefaultReactHost
import dev.kigya.cutout.bridge.ErasePackage
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class EraseApp : Application(), ReactApplication {
    @javax.inject.Inject lateinit var mainActivityState: MainActivityState
    val bubbleLifecycle by lazy { dev.kigya.cutout.overlay.BubbleReactLifecycle({ reactHost }, mainActivityState) }
    override val reactHost: ReactHost by lazy {
        val debuggable = (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        getDefaultReactHost(
            context = applicationContext,
            packageList = PackageList(this).packages.apply {
                add(ErasePackage())
            },
            useDevSupport = debuggable,
        )
    }

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(AppLocales.wrap(base))
    }

    override fun onCreate() {
        super.onCreate()
        dev.kigya.cutout.data.AppTelemetry(this).applyConsent()
        com.facebook.react.common.assets.ReactFontManager.getInstance().addCustomFont(
            "Manrope",
            android.graphics.Typeface.createFromAsset(assets, "fonts/Manrope.ttf"),
        )
        loadReactNative(this)
    }
}
