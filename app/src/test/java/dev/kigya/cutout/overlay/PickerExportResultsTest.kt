package dev.kigya.cutout.overlay

import android.Manifest
import android.app.Activity
import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.AssetFileDescriptor
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.os.Looper
import android.os.ParcelFileDescriptor
import dagger.hilt.android.internal.managers.ActivityComponentManager
import dev.kigya.cutout.data.GalleryStore
import dev.kigya.cutout.data.IncomingImage
import dev.kigya.cutout.domain.Messages
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.launch
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.yield
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class PickerExportResultsTest {
    private val app: Application get() = RuntimeEnvironment.getApplication()
    private fun injected(activity: Activity) {
        ReflectionHelpers.setField(activity, "injected", true)
        ReflectionHelpers.setField(activity, "componentManager", mockk<ActivityComponentManager>(relaxed = true))
    }
    private fun finish(activity: Activity) {
        val deadline = System.nanoTime() + 3_000_000_000L
        while (!activity.isFinishing && System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(5)
        }
        assertTrue(activity.isFinishing)
    }
    private fun provider(): Documents = Documents(File(app.cacheDir, "document-${java.util.UUID.randomUUID()}")).apply {
        attachInfo(app, android.content.pm.ProviderInfo().apply { authority = "qa.document" })
        ShadowContentResolver.registerProviderInternal("qa.document", this)
    }
    @Test fun `gallery results import raster svg and reject unreadable or mismatched text`() = runBlocking<Unit> {
        val provider = provider()
        for (name in listOf("raster", "vector", "plain", "denied", "nullstream")) {
            val bus = PickBus()
            val pending = bus.awaitEditor()
            val intent = Intent(app, PickerActivity::class.java)
                .putExtra(PickerActivity.EXTRA_TARGET, PickBus.TARGET_EDITOR)
                .putExtra(PickerActivity.EXTRA_REQUEST, bus.editorRequestId)
            val controller = Robolectric.buildActivity(PickerActivity::class.java, intent)
            val activity = controller.get()
            injected(activity); activity.bus = bus
            controller.create().start().resume()
            val launch = shadowOf(activity).nextStartedActivityForResult
            assertNotNull(launch)
            shadowOf(activity).receiveResult(launch.intent, Activity.RESULT_OK, Intent().setData(Uri.parse("content://qa.document/$name")))
            finish(activity)
            val image = withTimeout(3000) { pending.await() }
            when (name) {
                "raster" -> assertArrayEquals(byteArrayOf(1,2,3), (image as IncomingImage.Raster).bytes)
                "vector" -> assertTrue((image as IncomingImage.Svg).text.contains("<svg"))
                else -> assertEquals(IncomingImage.Invalid(Messages.READ_FAILED), image)
            }
            controller.pause().stop().destroy()
        }
        assertTrue(provider.file.isFile)
    }
    @Test fun `granted legacy save completes exactly once with actual gallery outcome`() = runBlocking<Unit> {
        shadowOf(app).grantPermissions(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        for (saved in listOf(false, true)) {
            val source = File(app.cacheDir, "export-${java.util.UUID.randomUUID()}.png").apply { writeBytes(byteArrayOf(1,2,3)) }
            val pending = LegacyExportActivity.reserve()!!
            val controller = Robolectric.buildActivity(LegacyExportActivity::class.java, Intent(app, LegacyExportActivity::class.java).putExtra("path", source.path))
            val activity = controller.get()
            val gallery = mockk<GalleryStore>()
            every { gallery.savePng(source.canonicalPath) } returns saved
            injected(activity); activity.gallery = gallery
            controller.create().start().resume()
            finish(activity)
            assertEquals(if (saved) "saved" else "failed", withTimeout(3000) { pending.await() })
            verify(exactly = 1) { gallery.savePng(source.canonicalPath) }
            controller.pause().stop().destroy()
            assertTrue(LegacyExportActivity.reserve()?.also { LegacyExportActivity.cancel(it) } != null)
        }
    }
    @Test fun `denied legacy storage offers document fallback and reports bytes written or cancellation`() = runBlocking<Unit> {
        shadowOf(app).denyPermissions(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        val provider = provider()
        for (destination in listOf(null, "write", "denied", "nullstream")) {
            val source = File(app.cacheDir, "export-${java.util.UUID.randomUUID()}.png").apply { writeBytes(byteArrayOf(9,8,7)) }
            val pending = LegacyExportActivity.reserve()!!
            val controller = Robolectric.buildActivity(LegacyExportActivity::class.java, Intent(app, LegacyExportActivity::class.java).putExtra("path", source.path))
            val activity = controller.get()
            val gallery = mockk<GalleryStore>(relaxed = true)
            injected(activity); activity.gallery = gallery
            controller.create().start().resume()
            val request = shadowOf(activity).lastRequestedPermission
            assertArrayEquals(arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), request.requestedPermissions)
            activity.onRequestPermissionsResult(request.requestCode, request.requestedPermissions, intArrayOf(PackageManager.PERMISSION_DENIED))
            val document = shadowOf(activity).nextStartedActivityForResult
            assertEquals(Intent.ACTION_CREATE_DOCUMENT, document.intent.action)
            assertEquals("image/png", document.intent.type)
            val result = destination?.let { Intent().setData(Uri.parse("content://qa.document/$it")) }
            shadowOf(activity).receiveResult(document.intent, if (result == null) Activity.RESULT_CANCELED else Activity.RESULT_OK, result)
            finish(activity)
            assertEquals(if (destination == "write") "saved" else "failed", withTimeout(3000) { pending.await() })
            if (destination == "write") assertArrayEquals(source.readBytes(), provider.file.readBytes())
            verify(exactly = 0) { gallery.savePng(any()) }
            controller.pause().stop().destroy()
        }
    }
    @Test fun `recreated legacy bridge waits for OS callback instead of repeating permission request`() = runBlocking<Unit> {
        val pending = LegacyExportActivity.reserve()!!
        val controller = Robolectric.buildActivity(LegacyExportActivity::class.java)
        val activity = controller.get()
        injected(activity); activity.gallery = mockk(relaxed = true)
        controller.create(Bundle())
        assertNull(shadowOf(activity).lastRequestedPermission)
        assertFalse(pending.isCompleted)
        activity.finish(); controller.destroy()
        assertEquals("failed", withTimeout(3000) { pending.await() })
    }
    @Test fun `configuration destruction keeps legacy reservation alive for the recreated owner`() = runBlocking<Unit> {
        val pending = LegacyExportActivity.reserve()!!
        val controller = Robolectric.buildActivity(LegacyExportActivity::class.java)
        val activity = controller.get()
        injected(activity); activity.gallery = mockk(relaxed = true)
        controller.create(Bundle()).start().resume().pause().stop().destroy()
        assertFalse(activity.isFinishing)
        assertFalse(pending.isCompleted)
        // A second caller cannot steal the pending OS result during recreation.
        assertNull(LegacyExportActivity.reserve())
        LegacyExportActivity.cancel(pending)
        assertEquals("failed", withTimeout(3000) { pending.await() })
        LegacyExportActivity.cancel(pending)
    }
    @Test fun `orphaned legacy callback finishes without creating a new export reservation`() {
        val controller = Robolectric.buildActivity(LegacyExportActivity::class.java)
        val activity = controller.get()
        injected(activity); activity.gallery = mockk(relaxed = true)
        controller.create()
        assertTrue(activity.isFinishing)
        assertNull(shadowOf(activity).lastRequestedPermission)
        controller.destroy()
        val next = LegacyExportActivity.reserve()
        assertNotNull(next)
        LegacyExportActivity.cancel(next!!)
    }
    @Test fun `request without explicit target routes picker cancellation to Bubble only`() = runBlocking<Unit> {
        val bus = PickBus()
        val results = mutableListOf<IncomingImage?>()
        val collector = launch(start = CoroutineStart.UNDISPATCHED) { bus.bubblePicks.collect { results += it } }
        val controller = Robolectric.buildActivity(PickerActivity::class.java)
        val activity = controller.get()
        injected(activity); activity.bus = bus
        controller.create().start().resume()
        val launch = shadowOf(activity).nextStartedActivityForResult
        // RESULT_OK without data is also a cancellation, rather than an unreadable image.
        shadowOf(activity).receiveResult(launch.intent, Activity.RESULT_OK, Intent())
        yield()
        assertEquals(listOf<IncomingImage?>(null), results)
        assertTrue(activity.isFinishing)
        collector.cancel()
        controller.pause().stop().destroy()
    }
    @Test fun `legacy permission grant revalidates a source removed while system dialog was open`() = runBlocking<Unit> {
        shadowOf(app).denyPermissions(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        val source = File(app.cacheDir, "removed-${java.util.UUID.randomUUID()}.png").apply { writeBytes(byteArrayOf(1)) }
        val pending = LegacyExportActivity.reserve()!!
        val controller = Robolectric.buildActivity(LegacyExportActivity::class.java, Intent(app, LegacyExportActivity::class.java).putExtra("path", source.path))
        val activity = controller.get()
        val gallery = mockk<GalleryStore>(relaxed = true)
        injected(activity); activity.gallery = gallery
        controller.create().start().resume()
        val request = shadowOf(activity).lastRequestedPermission
        assertTrue(source.delete())
        activity.onRequestPermissionsResult(request.requestCode, request.requestedPermissions, intArrayOf(PackageManager.PERMISSION_GRANTED))
        finish(activity)
        assertEquals("failed", withTimeout(3000) { pending.await() })
        verify(exactly = 0) { gallery.savePng(any()) }
        controller.pause().stop().destroy()
    }
    @Test fun `document fallback does not copy a source removed after permission denial`() = runBlocking<Unit> {
        shadowOf(app).denyPermissions(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        val documents = provider()
        val source = File(app.cacheDir, "removed-${java.util.UUID.randomUUID()}.png").apply { writeBytes(byteArrayOf(1)) }
        val pending = LegacyExportActivity.reserve()!!
        val controller = Robolectric.buildActivity(LegacyExportActivity::class.java, Intent(app, LegacyExportActivity::class.java).putExtra("path", source.path))
        val activity = controller.get()
        injected(activity); activity.gallery = mockk(relaxed = true)
        controller.create().start().resume()
        val request = shadowOf(activity).lastRequestedPermission
        activity.onRequestPermissionsResult(request.requestCode, request.requestedPermissions, intArrayOf(PackageManager.PERMISSION_DENIED))
        val document = shadowOf(activity).nextStartedActivityForResult
        assertTrue(source.delete())
        shadowOf(activity).receiveResult(document.intent, Activity.RESULT_OK, Intent().setData(Uri.parse("content://qa.document/write")))
        finish(activity)
        assertEquals("failed", withTimeout(3000) { pending.await() })
        assertFalse(documents.file.exists())
        controller.pause().stop().destroy()
    }
    private class Documents(val file: File) : ContentProvider() {
        override fun onCreate() = true
        override fun getType(uri: Uri): String? = when (uri.lastPathSegment) { "vector" -> "image/svg+xml"; "plain" -> "text/plain"; else -> null }
        override fun openAssetFile(uri: Uri, mode: String): AssetFileDescriptor? {
            if (uri.lastPathSegment == "nullstream") return null
            return super.openAssetFile(uri, mode)
        }
        override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
            if (uri.lastPathSegment == "denied") throw java.io.FileNotFoundException("denied")
            if (mode.startsWith("w")) return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_WRITE or ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_TRUNCATE)
            file.writeBytes(when (uri.lastPathSegment) { "vector" -> "<svg><circle r='1'/></svg>".toByteArray(); "plain" -> "not an svg".toByteArray(); else -> byteArrayOf(1,2,3) })
            return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        }
        override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
    }
}
