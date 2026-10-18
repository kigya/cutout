package dev.kigya.cutout.bridge

import dev.kigya.cutout.cutout.CutListener
import dev.kigya.cutout.cutout.CutOutcome
import dev.kigya.cutout.cutout.ImageCutter
import dev.kigya.cutout.cutout.MaskEditor
import dev.kigya.cutout.cutout.MaskWrite
import dev.kigya.cutout.data.BubbleAppearance
import dev.kigya.cutout.data.BubblePreferences
import dev.kigya.cutout.data.ClipboardImages
import dev.kigya.cutout.data.EditFiles
import dev.kigya.cutout.data.EditSessions
import dev.kigya.cutout.data.GalleryStore
import dev.kigya.cutout.data.IncomingImage
import dev.kigya.cutout.data.ClipboardLook
import dev.kigya.cutout.domain.Messages
import dev.kigya.cutout.domain.OemFamily
import dev.kigya.cutout.domain.PermissionKind
import dev.kigya.cutout.domain.permissionNeed
import dev.kigya.cutout.domain.PermissionNeed
import dev.kigya.cutout.locale.AppLocales
import dev.kigya.cutout.overlay.BubblePhase
import dev.kigya.cutout.overlay.BubblePresenter
import dev.kigya.cutout.overlay.BubbleStarter
import dev.kigya.cutout.overlay.BubbleUiEvent
import dev.kigya.cutout.overlay.BubbleUiState
import dev.kigya.cutout.overlay.BubbleGeometry
import dev.kigya.cutout.overlay.BubbleWindow
import dev.kigya.cutout.overlay.ClipboardActivity
import dev.kigya.cutout.overlay.ClipboardBus
import dev.kigya.cutout.overlay.PickBus
import dev.kigya.cutout.overlay.PickerActivity
import dev.kigya.cutout.permissions.GrantStatus
import dev.kigya.cutout.permissions.PermissionCenter
import dev.kigya.cutout.permissions.PermissionRequestHistory
import dev.kigya.cutout.permissions.SettingsNavigator
import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReactContextBaseJavaModule
import com.facebook.react.bridge.ReactMethod
import com.facebook.react.bridge.UiThreadUtil
import com.facebook.react.bridge.WritableMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

class EraseModule(
    reactContext: ReactApplicationContext,
    private val permissions: PermissionCenter,
    private val preferences: BubblePreferences,
    private val navigator: SettingsNavigator,
    private val bubble: BubbleStarter,
    private val presenter: BubblePresenter,
    private val cutter: ImageCutter,
    private val sessions: EditSessions,
    private val picks: PickBus,
    private val clipboard: ClipboardBus,
    private val masks: MaskEditor,
    private val window: BubbleWindow,
    private val gallery: GalleryStore,
    private val images: ClipboardImages,
    private val mainActivity: dev.kigya.cutout.MainActivityState = dev.kigya.cutout.MainActivityState(),
) : ReactContextBaseJavaModule(reactContext) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var cutJob: Job? = null
    private val reviews by lazy { dev.kigya.cutout.data.ReviewPrompts(reactApplicationContext) }
    private val telemetry by lazy { dev.kigya.cutout.data.AppTelemetry(reactApplicationContext) }
    private val permissionHistory by lazy { PermissionRequestHistory(reactApplicationContext) }

    init {
        scope.launch {
            preferences.appearance.collect { value ->
                if (reactApplicationContext.hasActiveReactInstance())
                    reactApplicationContext.emitDeviceEvent("bubbleAppearance", appearanceMap(value))
            }
        }
        scope.launch {
            window.interacting.collect { value ->
                if (reactApplicationContext.hasActiveReactInstance())
                    reactApplicationContext.emitDeviceEvent("bubbleInteraction", Arguments.createMap().apply { putBoolean("active", value) })
            }
        }
        scope.launch {
            mainActivity.state.collect { state ->
                UiThreadUtil.runOnUiThread {
                    if (reactApplicationContext.hasActiveReactInstance()) {
                        reactApplicationContext.emitDeviceEvent("mainActivityState", mainActivityMap(state))
                    }
                }
            }
        }
        scope.launch {
            presenter.state.collect { state ->
                emitBubble(state)
            }
        }
        scope.launch {
            window.geometry.collect { geometry ->
                if (geometry != null) UiThreadUtil.runOnUiThread {
                    if (reactApplicationContext.hasActiveReactInstance()) {
                        reactApplicationContext.emitDeviceEvent("bubbleGeometry", geometryMap(geometry))
                    }
                }
            }
        }
        scope.launch {
            preferences.enabled.collect { enabled -> emitBubbleEnabled(enabled) }
        }
    }

    override fun getName(): String = NAME

    override fun invalidate() {
        scope.cancel()
        super.invalidate()
    }

    private fun appearanceMap(value: BubbleAppearance): WritableMap = Arguments.createMap().apply {
        putInt("sizeDp", value.sizeDp)
        putDouble("opacity", value.opacity.toDouble())
        putBoolean("shrinkWhenIdle", value.shrinkWhenIdle)
        putBoolean("shrinkToDot", value.shrinkToDot)
    }

    @ReactMethod
    fun getOnboardingCompleted(promise: Promise) { scope.launch {
        try { promise.resolve(preferences.onboardingCompleted.first()) }
        catch (error: Exception) { promise.reject("preferences", error) }
    } }

    @ReactMethod
    fun completeOnboarding(promise: Promise) { scope.launch {
        try { preferences.completeOnboarding(); promise.resolve(null) }
        catch (error: Exception) { promise.reject("preferences", error) }
    } }

    @ReactMethod
    fun getBubbleAppearance(promise: Promise) { scope.launch {
        try { promise.resolve(appearanceMap(preferences.appearance.first())) }
        catch (error: Exception) { promise.reject("preferences", error) }
    } }

    @ReactMethod
    fun setBubbleAppearance(sizeDp: Double, opacity: Double, shrinkWhenIdle: Boolean, shrinkToDot: Boolean, promise: Promise) { scope.launch {
        try {
            require(sizeDp.isFinite() && opacity.isFinite())
            preferences.setAppearance(BubbleAppearance(sizeDp.toInt(), opacity.toFloat(), shrinkWhenIdle, shrinkToDot))
            promise.resolve(appearanceMap(preferences.appearance.first()))
        } catch (error: Exception) { promise.reject("preferences", error) }
    } }

    @ReactMethod
    fun getPreferences(promise: Promise) {
        promise.resolve(Arguments.createMap().apply {
            putString("theme", telemetry.theme)
            putBoolean("analytics", telemetry.analyticsEnabled)
            putBoolean("crashes", telemetry.crashEnabled)
            putBoolean("diagnosticsNoticePending", telemetry.diagnosticsNoticePending)
        })
    }

    @ReactMethod
    fun setPreferences(theme: String, analytics: Boolean, crashes: Boolean, promise: Promise) {
        telemetry.update(theme, analytics, crashes)
        val preferences = Arguments.createMap().apply {
            putString("theme", telemetry.theme); putBoolean("analytics", telemetry.analyticsEnabled); putBoolean("crashes", telemetry.crashEnabled)
            putBoolean("diagnosticsNoticePending", telemetry.diagnosticsNoticePending)
        }
        UiThreadUtil.runOnUiThread {
            if (reactApplicationContext.hasActiveReactInstance()) reactApplicationContext.emitDeviceEvent("preferencesChanged", preferences)
            reactApplicationContext.currentActivity?.let { activity ->
                val systemDark = (activity.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES
                val light = when (theme) { "light" -> true; "dark" -> false; else -> !systemDark }
                androidx.core.view.WindowCompat.getInsetsController(activity.window, activity.window.decorView).apply {
                    isAppearanceLightStatusBars = light
                    isAppearanceLightNavigationBars = light
                }
            }
        }
        promise.resolve(null)
    }

    @ReactMethod
    fun logProductEvent(name: String, values: com.facebook.react.bridge.ReadableMap, promise: Promise) {
        telemetry.event(name, values.toHashMap())
        promise.resolve(null)
    }

    @ReactMethod
    fun acknowledgeDiagnosticsNotice(promise: Promise) {
        if (telemetry.acknowledgeDiagnosticsNotice()) promise.resolve(null)
        else promise.reject("preferences", "Could not save diagnostics notice state")
    }

    @ReactMethod
    fun getHome(promise: Promise) {
        scope.launch {
            try {
                var enabled = preferences.enabled.first()
                try {
                    val active = syncBubble(enabled)
                    if (enabled && !active) preferences.setEnabled(false)
                    enabled = active
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) {
                    disableBubbleAfterFailure()
                    enabled = false
                }
                val rows = withContext(Dispatchers.IO) { permissions.snapshot() }
                val history = withContext(Dispatchers.IO) {
                    rows.associate { it.kind to permissionHistory.entry(it.kind) }
                }
                promise.resolve(homeMap(enabled, rows, history))
            } catch (cancelled: CancellationException) {
                promise.reject("cancelled", "Home refresh cancelled")
                throw cancelled
            } catch (_: Exception) { promise.reject("home", "Could not refresh permissions") }
        }
    }

    @ReactMethod
    fun prepareModel(promise: Promise) {
        scope.launch {
            val prepared = withContext(Dispatchers.Default) { cutter.prepare() }
            promise.resolve(
                prepared.fold(
                    onSuccess = { "ready" },
                    onFailure = { error -> error.message ?: "failed" },
                ),
            )
        }
    }

    @ReactMethod
    fun setBubble(enabled: Boolean, promise: Promise) {
        scope.launch {
            try {
                val allowed = !enabled || withContext(Dispatchers.IO) { permissions.snapshot() }.any {
                    it.kind == PermissionKind.Overlay && it.status == GrantStatus.Granted
                }
                val active = enabled && allowed
                preferences.setEnabled(active)
                syncBubble(active)
                telemetry.event("bubble_changed", mapOf("enabled" to active))
                promise.resolve(null)
            } catch (cancelled: CancellationException) {
                promise.reject("cancelled", "Bubble change cancelled")
                throw cancelled
            } catch (_: Exception) {
                try { disableBubbleAfterFailure() }
                finally { promise.reject("bubble", "Could not change Bubble") }
            }
        }
    }

    @ReactMethod
    fun hideBubble(promise: Promise) {
        scope.launch {
            presenter.stop()
            try {
                preferences.setEnabled(false)
                bubble.stop()
                telemetry.event("bubble_changed", mapOf("enabled" to false))
                promise.resolve(null)
            } catch (cancelled: CancellationException) {
                promise.reject("cancelled", "Bubble change cancelled")
                throw cancelled
            } catch (_: Exception) {
                try { disableBubbleAfterFailure() }
                finally { promise.reject("bubble", "Could not change Bubble") }
            }
        }
    }

    @ReactMethod
    fun openPermission(kind: String, promise: Promise) {
        val parsed = kindOf(kind)
        if (parsed == null) {
            promise.reject("kind", kind)
            return
        }
        if (navigator.open(parsed)) promise.resolve(null)
        else promise.reject("permission", "Could not open permission settings")
    }

    @ReactMethod
    fun beginPermissionRequest(kind: String, promise: Promise) {
        val parsed = kindOf(kind)
        if (parsed == null) {
            promise.reject("kind", kind)
            return
        }
        try { promise.resolve(permissionHistory.begin(parsed)) }
        catch (_: Exception) { promise.reject("permission", "Could not record permission request") }
    }

    @ReactMethod
    fun completePermissionRequest(kind: String, attemptId: String, blocked: Boolean, promise: Promise) {
        val parsed = kindOf(kind)
        if (parsed == null || attemptId.isBlank()) {
            promise.reject("kind", kind)
            return
        }
        scope.launch {
            try {
                val status = withContext(Dispatchers.IO) {
                    permissions.snapshot().firstOrNull { it.kind == parsed }?.status ?: GrantStatus.Unknown
                }
                permissionHistory.complete(parsed, attemptId, status, blocked)
                promise.resolve(null)
            } catch (cancelled: CancellationException) {
                runCatching { permissionHistory.complete(parsed, attemptId, GrantStatus.Unknown, blocked = false) }
                promise.reject("cancelled", "Permission check cancelled")
                throw cancelled
            } catch (_: Exception) {
                runCatching { permissionHistory.complete(parsed, attemptId, GrantStatus.Unknown, blocked = false) }
                promise.reject("permission", "Could not check permission status")
            }
        }
    }

    @ReactMethod
    fun pickAndCut(mode: String, promise: Promise) {
        launchCut(promise) {
            val image = awaitPick(mode)
            if (image == null) {
                failedMap(Messages.CANCELLED)
            } else {
                withContext(Dispatchers.Default) { outcomeMap(cutter.cut(image, progressListener())) }
            }
        }
    }

    @ReactMethod
    fun cutClipboard(promise: Promise) {
        launchCut(promise) {
            val look = awaitClipboard()
            val image = (look as? ClipboardLook.Found)?.image
            if (image == null) {
                failedMap(Messages.EMPTY_CLIPBOARD)
            } else {
                withContext(Dispatchers.Default) { outcomeMap(cutter.cut(image, progressListener())) }
            }
        }
    }

    @ReactMethod
    fun cutFixture(promise: Promise) {
        launchCut(promise) {
            withContext(Dispatchers.Default) { outcomeMap(cutter.cutFixture(progressListener())) }
        }
    }

    @ReactMethod
    fun cancelCut(promise: Promise) {
        cutJob?.cancel()
        promise.resolve(null)
    }

    private fun launchCut(promise: Promise, block: suspend () -> WritableMap) {
        cutJob?.cancel()
        val settled = AtomicBoolean(false)
        fun settle(map: WritableMap) {
            if (settled.compareAndSet(false, true)) promise.resolve(map)
        }
        val job = scope.launch {
            try {
                settle(block())
            } catch (cancelled: CancellationException) {
                settle(failedMap(Messages.CANCELLED))
                throw cancelled
            } catch (_: Exception) {
                settle(failedMap(Messages.READ_FAILED))
            }
        }
        cutJob = job
        job.invokeOnCompletion { cause ->
            if (cutJob === job) cutJob = null
            if (cause is CancellationException) settle(failedMap(Messages.CANCELLED))
        }
    }

    @ReactMethod
    fun bubbleAction(action: String, promise: Promise) {
        val event = when (action) {
            "toggle" -> BubbleUiEvent.Toggle
            "clipboard" -> BubbleUiEvent.FromClipboard
            "gallery" -> BubbleUiEvent.FromGallery
            "file" -> BubbleUiEvent.FromFile
            "refine" -> BubbleUiEvent.Refine
            "dismiss" -> BubbleUiEvent.Dismiss
            "collapse" -> BubbleUiEvent.Collapse
            "again" -> BubbleUiEvent.Again
            else -> null
        }
        if (event == null) {
            promise.reject("action", action)
            return
        }
        scope.launch {
            try {
                presenter.onEvent(event)
                promise.resolve(bubbleMap(presenter.state.value).apply {
                    if (event == BubbleUiEvent.Toggle && mainActivity.state.value.visible) {
                        putString("actionMessage", Messages.BUBBLE_OTHER_APPS)
                    }
                })
            } catch (_: Exception) {
                promise.reject("bubble", "Could not perform Bubble action")
            }
        }
    }

    private fun mainActivityMap(state: dev.kigya.cutout.MainActivitySnapshot): WritableMap = Arguments.createMap().apply {
        putInt("revision", state.revision)
        putBoolean("visible", state.visible)
        putBoolean("resumed", state.resumed)
    }

    @ReactMethod
    fun getMainActivityState(promise: Promise) {
        promise.resolve(mainActivityMap(mainActivity.state.value))
    }

    @ReactMethod
    fun getBubbleState(promise: Promise) {
        promise.resolve(bubbleMap(presenter.state.value))
    }

    @ReactMethod
    fun readAlpha(path: String, promise: Promise) {
        scope.launch {
            try {
                val encoded = withContext(Dispatchers.Default) { masks.readAlpha(path) }
                if (encoded == null) promise.reject("read", "Could not read mask") else promise.resolve(encoded)
            } catch (cancelled: CancellationException) { promise.reject("cancelled", "Read cancelled"); throw cancelled }
            catch (_: Exception) { promise.reject("read", "Could not read mask") }
        }
    }

    @ReactMethod
    fun writeMasked(originalPath: String, alphaBase64: String, width: Int, height: Int, promise: Promise) {
        scope.launch {
            try {
                val written = withContext(Dispatchers.Default) {
                    masks.writeMasked(originalPath, alphaBase64, width, height)
                }
                promise.resolve(maskMap(written))
            } catch (cancelled: CancellationException) { promise.reject("cancelled", "Export cancelled"); throw cancelled }
            catch (_: Exception) { promise.resolve(maskMap(MaskWrite.Failed("write"))) }
        }
    }

    @ReactMethod
    fun saveToGallery(path: String, promise: Promise) {
        scope.launch {
            if (android.os.Build.VERSION.SDK_INT <= 28) {
                val request = dev.kigya.cutout.overlay.LegacyExportActivity.reserve()
                if (request == null) { completeSave(false, promise); return@launch }
                val saved = try {
                    val intent = android.content.Intent(reactApplicationContext, dev.kigya.cutout.overlay.LegacyExportActivity::class.java)
                        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("path", path)
                    reactApplicationContext.startActivity(intent)
                    withTimeoutOrNull(300_000) { request.await() } == "saved"
                } catch (cancelled: CancellationException) {
                    completeSave(false, promise)
                    throw cancelled
                } catch (_: Exception) { false }
                finally { dev.kigya.cutout.overlay.LegacyExportActivity.cancel(request) }
                completeSave(saved, promise)
                return@launch
            }
            val saved = try {
                withContext(Dispatchers.IO) { gallery.savePng(path) }
            } catch (cancelled: CancellationException) {
                promise.reject("cancelled", "Save cancelled")
                throw cancelled
            } catch (_: Exception) { false }
            completeSave(saved, promise)
        }
    }

    private fun completeSave(saved: Boolean, promise: Promise) {
        telemetry.event(if (saved) "export_completed" else "export_failed", mapOf("action" to "save", "format" to "png"))
        promise.resolve(if (saved) "saved" else "failed")
        if (saved) reviews.exportSucceeded(reactApplicationContext.currentActivity)
    }

    @ReactMethod
    fun getLocale(promise: Promise) {
        val map = Arguments.createMap()
        map.putString("selected", AppLocales.selectedTag(reactApplicationContext) ?: "")
        map.putString("system", AppLocales.systemTag(reactApplicationContext))
        promise.resolve(map)
    }

    @ReactMethod
    fun setLocale(tag: String, promise: Promise) {
        UiThreadUtil.runOnUiThread {
            val next = tag.ifBlank { null }
            AppLocales.apply(reactApplicationContext, next)
            telemetry.event("locale_changed", mapOf("locale" to (next ?: "system")))
            val map = Arguments.createMap()
            map.putString("selected", next ?: "")
            map.putString("system", AppLocales.systemTag(reactApplicationContext))
            emitLocale(map)
            if (android.os.Build.VERSION.SDK_INT < 33) reactApplicationContext.currentActivity?.recreate()
            promise.resolve(null)
        }
    }

    @ReactMethod
    fun restart(promise: Promise) {
        UiThreadUtil.runOnUiThread {
            reactApplicationContext.currentActivity?.recreate()
            promise.resolve(null)
        }
    }

    @ReactMethod
    fun publishPng(path: String, promise: Promise) {
        scope.launch {
            try {
                val status = withContext(Dispatchers.Default) { masks.publish(path) }
                telemetry.event(if (status == "copied") "export_completed" else "export_failed", mapOf("action" to "copy", "format" to "png"))
                promise.resolve(status)
                if (status == "copied") reviews.exportSucceeded(reactApplicationContext.currentActivity)
            } catch (cancelled: CancellationException) { promise.reject("cancelled", "Copy cancelled"); throw cancelled }
            catch (_: Exception) { promise.resolve("failed") }
        }
    }

    @ReactMethod
    fun consumePendingEdit(promise: Promise) {
        val files = sessions.consumeOpen()
        promise.resolve(
            if (files == null) {
                null
            } else {
                editMap(files).apply { putString("status", "ready") }
            },
        )
    }

    @ReactMethod
    fun moveBubble(dx: Double, dy: Double) {
        window.move?.invoke(dx.toFloat(), dy.toFloat())
    }

    @ReactMethod
    fun settleBubble(promise: Promise) {
        val anchorEnd = window.settle?.invoke() ?: true
        promise.resolve(anchorEnd)
    }

    @ReactMethod
    fun getBubbleGeometry(promise: Promise) {
        promise.resolve(window.geometry.value?.let(::geometryMap))
    }

    @ReactMethod
    fun ackBubbleGeometry(revision: Double) {
        if (revision.isFinite() && revision >= 0) UiThreadUtil.runOnUiThread {
            window.acknowledgeGeometry?.invoke(revision.toLong())
        }
    }

    @ReactMethod
    fun setBubbleSize(width: Double, height: Double) {
        window.resize?.invoke(width.toFloat(), height.toFloat())
    }

    private val permissionStates = mutableMapOf<PermissionKind, GrantStatus>()

    private suspend fun awaitClipboard(): ClipboardLook? {
        val deferred = clipboard.awaitConsume()
        val requestId = clipboard.consumeRequestId
        val intent = android.content.Intent(reactApplicationContext, ClipboardActivity::class.java)
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_NO_ANIMATION)
            .putExtra(ClipboardActivity.EXTRA_CONSUME, true)
            .putExtra(ClipboardActivity.EXTRA_REQUEST, requestId)
        return try {
            reactApplicationContext.startActivity(intent)
            withTimeoutOrNull(20_000) { deferred.await() }
        } catch (cancelled: CancellationException) {
            clipboard.cancelConsume(requestId)
            throw cancelled
        } catch (_: Exception) {
            clipboard.cancelConsume(requestId)
            null
        } finally {
            if (!deferred.isCompleted) clipboard.cancelConsume(requestId)
        }
    }

    private suspend fun awaitPick(mode: String): IncomingImage? {
        val deferred = picks.awaitEditor()
        val requestId = picks.editorRequestId
        val pickerMode = if (mode == "file") PickerActivity.MODE_FILE else PickerActivity.MODE_GALLERY
        val intent = android.content.Intent(reactApplicationContext, PickerActivity::class.java)
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra(PickerActivity.EXTRA_MODE, pickerMode)
            .putExtra(PickerActivity.EXTRA_TARGET, PickBus.TARGET_EDITOR)
            .putExtra(PickerActivity.EXTRA_REQUEST, requestId)
        return try {
            reactApplicationContext.startActivity(intent)
            withTimeoutOrNull(120_000) { deferred.await() }
        } catch (cancelled: CancellationException) {
            picks.cancel(PickBus.TARGET_EDITOR, requestId)
            throw cancelled
        } catch (_: Exception) {
            picks.cancel(PickBus.TARGET_EDITOR, requestId)
            null
        } finally {
            if (!deferred.isCompleted) picks.cancel(PickBus.TARGET_EDITOR, requestId)
        }
    }

    private suspend fun disableBubbleAfterFailure() {
        try { preferences.setEnabled(false) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { /* A storage failure must not leave the bridge promise pending. */ }
        try { bubble.stop() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { /* OEM service rejection is reported through the settled caller. */ }
    }

    private suspend fun syncBubble(enabled: Boolean): Boolean {
        val overlay = enabled && withContext(Dispatchers.IO) {
            android.provider.Settings.canDrawOverlays(reactApplicationContext)
        }
        if (overlay) bubble.start() else bubble.stop()
        return overlay
    }

    private fun homeMap(
        bubbleEnabled: Boolean,
        snapshot: List<dev.kigya.cutout.permissions.PermissionSnapshot>,
        histories: Map<PermissionKind, dev.kigya.cutout.permissions.PermissionHistoryEntry>,
    ): WritableMap {
        val family = permissions.family()
        val rows = Arguments.createArray()
        snapshot.forEach { snapshot ->
            val previous = permissionStates.put(snapshot.kind, snapshot.status)
            if (previous != null && previous != snapshot.status) {
                telemetry.event("permission_result", mapOf("permission" to kindName(snapshot.kind), "result" to statusName(snapshot.status)))
            }
            val row = Arguments.createMap()
            row.putString("kind", kindName(snapshot.kind))
            row.putString("title", title(snapshot.kind))
            row.putString("status", statusName(snapshot.status))
            row.putString("need", needName(permissionNeed(snapshot.kind, bubbleEnabled)))
            val history = histories[snapshot.kind]
            row.putInt("deniedCount", history?.deniedCount ?: 0)
            row.putBoolean("permanentlyDenied", history?.permanentlyDenied == true && snapshot.status == GrantStatus.Denied)
            rows.pushMap(row)
        }
        val map = Arguments.createMap()
        map.putArray("rows", rows)
        map.putBoolean("bubbleSupported", true)
        map.putBoolean("bubbleEnabled", bubbleEnabled)
        map.putBoolean("showXiaomiTip", family == OemFamily.Xiaomi)
        map.putBoolean("clipboardReady", runCatching { images.hasPicture() }.getOrDefault(false))
        return map
    }

    private fun outcomeMap(outcome: CutOutcome): WritableMap = when (outcome) {
        is CutOutcome.CopiedRaster -> editMap(outcome.edit).apply {
            putString("status", "ready")
            putString("message", outcome.message)
        }
        is CutOutcome.Manual -> editMap(outcome.edit).apply {
            putString("status", "manual")
            putString("message", outcome.message)
        }
        is CutOutcome.CopiedSvg -> Arguments.createMap().apply {
            putString("status", "svg")
            putString("message", outcome.message)
        }
        is CutOutcome.Failed -> failedMap(outcome.message)
    }

    private fun editMap(files: EditFiles): WritableMap = Arguments.createMap().apply {
        putString("originalPath", files.originalPath)
        putString("cutoutPath", files.cutoutPath)
        putInt("width", files.width)
        putInt("height", files.height)
    }

    private fun failedMap(message: String): WritableMap = Arguments.createMap().apply {
        putString("status", "failed")
        putString("message", message)
    }

    private fun maskMap(written: MaskWrite): WritableMap = when (written) {
        is MaskWrite.Ready -> Arguments.createMap().apply {
            putString("status", "ready")
            putString("path", written.path)
        }
        is MaskWrite.Empty -> Arguments.createMap().apply {
            putString("status", "empty")
            putString("path", written.path)
        }
        is MaskWrite.Failed -> Arguments.createMap().apply {
            putString("status", "failed")
            putString("message", written.detail)
        }
    }

    private fun geometryMap(geometry: BubbleGeometry): WritableMap = Arguments.createMap().apply {
        putDouble("revision", geometry.revision.toDouble())
        putInt("stateRevision", geometry.stateRevision)
        putString("phase", geometry.phase.name.lowercase())
        putBoolean("expanded", geometry.expanded)
        putBoolean("anchorEnd", geometry.anchorEnd)
        putDouble("windowWidthDp", geometry.windowWidthDp.toDouble())
        putDouble("windowHeightDp", geometry.windowHeightDp.toDouble())
        putDouble("surfaceWidthDp", geometry.surfaceWidthDp.toDouble())
        putDouble("surfaceHeightDp", geometry.surfaceHeightDp.toDouble())
        putDouble("chipSizeDp", geometry.chipSizeDp.toDouble())
        putDouble("panelWidthDp", geometry.panelWidthDp.toDouble())
        putDouble("panelHeightDp", geometry.panelHeightDp.toDouble())
        putDouble("chipLeftDp", geometry.chipLeftDp.toDouble())
        putDouble("chipTopDp", geometry.chipTopDp.toDouble())
    }

    private fun bubbleMap(state: BubbleUiState): WritableMap = Arguments.createMap().apply {
        putInt("revision", state.revision)
        putString("taskId", state.taskId)
        putString("stage", state.stage)
        putBoolean("expanded", state.expanded)
        putBoolean("anchorEnd", state.anchorEnd)
        putString("phase", state.phase.name.lowercase())
        putBoolean("clipboardReady", state.clipboardReady)
        putBoolean("readingClipboard", state.readingClipboard)
        putString("message", state.message)
        putString("previewPath", state.previewPath)
        putBoolean("rasterizedSvg", state.rasterizedSvg)
        putBoolean("editReady", state.editReady)
        putInt("percent", state.percent)
    }

    private fun progressListener() = object : CutListener {
        override fun onPreview(path: String) = emitPreview(path)
        override fun onProgress(percent: Int) = emitProgress(percent)
    }

    private fun emitProgress(percent: Int) {
        val value = percent.coerceIn(0, 100)
        UiThreadUtil.runOnUiThread {
            if (!reactApplicationContext.hasActiveReactInstance()) return@runOnUiThread
            val map = Arguments.createMap()
            map.putInt("percent", value)
            reactApplicationContext.emitDeviceEvent("eraseProgress", map)
        }
    }

    private fun emitPreview(path: String) {
        UiThreadUtil.runOnUiThread {
            if (!reactApplicationContext.hasActiveReactInstance()) return@runOnUiThread
            val map = Arguments.createMap()
            map.putString("path", path)
            reactApplicationContext.emitDeviceEvent("erasePreview", map)
        }
    }

    private fun emitBubble(state: BubbleUiState) {
        UiThreadUtil.runOnUiThread {
            if (!reactApplicationContext.hasActiveReactInstance()) return@runOnUiThread
            reactApplicationContext.emitDeviceEvent("bubbleState", bubbleMap(state))
        }
    }

    private fun emitBubbleEnabled(enabled: Boolean) {
        UiThreadUtil.runOnUiThread {
            if (!reactApplicationContext.hasActiveReactInstance()) return@runOnUiThread
            val map = Arguments.createMap()
            map.putBoolean("enabled", enabled)
            reactApplicationContext.emitDeviceEvent("bubbleEnabled", map)
        }
    }

    private fun emitLocale(map: WritableMap) {
        UiThreadUtil.runOnUiThread {
            if (!reactApplicationContext.hasActiveReactInstance()) return@runOnUiThread
            reactApplicationContext.emitDeviceEvent("eraseLocale", map)
        }
    }

    private fun title(kind: PermissionKind): String = when (kind) {
        PermissionKind.Overlay -> "Поверх окон"
        PermissionKind.BackgroundPopup -> "Окна из фона"
        PermissionKind.Autostart -> "Автозапуск"
        PermissionKind.Battery -> "Батарея"
        PermissionKind.Notifications -> "Уведомление"
    }

    private fun kindName(kind: PermissionKind): String = when (kind) {
        PermissionKind.Overlay -> "overlay"
        PermissionKind.BackgroundPopup -> "backgroundPopup"
        PermissionKind.Autostart -> "autostart"
        PermissionKind.Battery -> "battery"
        PermissionKind.Notifications -> "notifications"
    }

    private fun kindOf(name: String): PermissionKind? = when (name) {
        "overlay" -> PermissionKind.Overlay
        "backgroundPopup" -> PermissionKind.BackgroundPopup
        "autostart" -> PermissionKind.Autostart
        "battery" -> PermissionKind.Battery
        "notifications" -> PermissionKind.Notifications
        else -> null
    }

    private fun statusName(status: GrantStatus): String = when (status) {
        GrantStatus.Granted -> "granted"
        GrantStatus.Denied -> "denied"
        GrantStatus.Unknown -> "unknown"
    }

    private fun needName(need: PermissionNeed): String = when (need) {
        PermissionNeed.RequiredForBubble -> "requiredForBubble"
        PermissionNeed.Optional -> "optional"
    }

    companion object {
        const val NAME = "Erase"
    }
}
