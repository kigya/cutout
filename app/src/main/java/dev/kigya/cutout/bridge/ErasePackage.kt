package dev.kigya.cutout.bridge

import dev.kigya.cutout.cutout.ImageCutter
import dev.kigya.cutout.cutout.MaskEditor
import dev.kigya.cutout.data.BubblePreferences
import dev.kigya.cutout.data.ClipboardImages
import dev.kigya.cutout.data.EditSessions
import dev.kigya.cutout.data.GalleryStore
import dev.kigya.cutout.overlay.BubblePresenter
import dev.kigya.cutout.overlay.BubbleStarter
import dev.kigya.cutout.overlay.BubbleWindow
import dev.kigya.cutout.overlay.ClipboardBus
import dev.kigya.cutout.overlay.PickBus
import dev.kigya.cutout.permissions.PermissionCenter
import dev.kigya.cutout.permissions.SettingsNavigator
import com.facebook.react.BaseReactPackage
import com.facebook.react.bridge.NativeModule
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.module.model.ReactModuleInfo
import com.facebook.react.module.model.ReactModuleInfoProvider
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

@EntryPoint
@InstallIn(SingletonComponent::class)
interface EraseEntryPoint {
    fun permissions(): PermissionCenter
    fun preferences(): BubblePreferences
    fun navigator(): SettingsNavigator
    fun bubble(): BubbleStarter
    fun presenter(): BubblePresenter
    fun cutter(): ImageCutter
    fun sessions(): EditSessions
    fun picks(): PickBus
    fun clipboard(): ClipboardBus
    fun masks(): MaskEditor
    fun window(): BubbleWindow
    fun gallery(): GalleryStore
    fun images(): ClipboardImages
}

class ErasePackage : BaseReactPackage() {
    override fun getModule(name: String, reactContext: ReactApplicationContext): NativeModule? {
        if (name != EraseModule.NAME) return null
        val entry = EntryPointAccessors.fromApplication(
            reactContext.applicationContext,
            EraseEntryPoint::class.java,
        )
        return EraseModule(
            reactContext,
            entry.permissions(),
            entry.preferences(),
            entry.navigator(),
            entry.bubble(),
            entry.presenter(),
            entry.cutter(),
            entry.sessions(),
            entry.picks(),
            entry.clipboard(),
            entry.masks(),
            entry.window(),
            entry.gallery(),
            entry.images(),
        )
    }

    override fun getReactModuleInfoProvider(): ReactModuleInfoProvider = ReactModuleInfoProvider {
        mapOf(
            EraseModule.NAME to ReactModuleInfo(
                EraseModule.NAME,
                EraseModule::class.java.name,
                false,
                false,
                false,
                false,
            ),
        )
    }
}
