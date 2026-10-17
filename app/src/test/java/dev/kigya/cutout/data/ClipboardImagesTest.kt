package dev.kigya.cutout.data

import android.app.Application
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.content.res.AssetFileDescriptor
import android.os.Build
import android.os.ParcelFileDescriptor
import dev.kigya.cutout.data.IncomingImage.Raster
import dev.kigya.cutout.data.IncomingImage.Svg
import java.io.File
import java.io.FileNotFoundException
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver
import org.robolectric.shadows.ShadowSystemClock
import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ClipboardImagesTest {
    private lateinit var app: Application
    private lateinit var clipboard: ClipboardManager
    private lateinit var sourceProvider: SourceProvider
    private lateinit var provider: CutoutFileProvider
    private val images by lazy { ClipboardImages(app) }

    @Before fun setUp() {
        app = RuntimeEnvironment.getApplication()
        clipboard = app.getSystemService(ClipboardManager::class.java)!!
        sourceProvider = SourceProvider(app).also {
            it.attachInfo(app, android.content.pm.ProviderInfo().apply { authority = SOURCE_AUTHORITY })
            ShadowContentResolver.registerProviderInternal(SOURCE_AUTHORITY, it)
        }
        val info = providerInfo()
        provider = CutoutFileProvider().also {
            it.attachInfo(app, info)
            ShadowContentResolver.registerProviderInternal("${app.packageName}.fileprovider", it)
        }
    }

    @Test fun `publishes PNG and SVG bytes as readable MIME typed clipboard URIs`() {
        val png = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 1, 2, 3)
        val pngUri = images.publish(png, "png")
        assertEquals("image/png", app.contentResolver.getType(pngUri))
        assertArrayEquals(png, app.contentResolver.openInputStream(pngUri)!!.use { it.readBytes() })
        assertTrue(images.hasPicture())
        assertRaster(images.peek(), png)

        val svg = "<svg viewBox=\"0 0 1 1\"><path d=\"M0 0\"/></svg>".toByteArray()
        val svgUri = images.publish(svg, "svg")
        assertEquals("image/svg+xml", app.contentResolver.getType(svgUri))
        assertArrayEquals(svg, app.contentResolver.openInputStream(svgUri)!!.use { it.readBytes() })
        assertTrue(images.hasPicture())
        assertEquals(Svg(svg.toString(Charsets.UTF_8)), images.peek().let { (it as ClipboardLook.Found).image })
    }

    @Test fun `keeps only the newest clipboard payload file`() {
        val directory = File(app.filesDir, "cutouts")
        directory.deleteRecursively()
        val first = images.publish(byteArrayOf(1), "png")
        assertEquals(1, directory.listFiles()?.size)

        ShadowSystemClock.advanceBy(Duration.ofMillis(1))
        val second = images.publish(byteArrayOf(2), "svg")

        assertTrue(first != second)
        assertEquals(1, directory.listFiles()?.size)
        assertEquals("image/svg+xml", app.contentResolver.getType(second))
        assertTrue(runCatching { app.contentResolver.openInputStream(first) }.isFailure)
        assertArrayEquals(byteArrayOf(2), app.contentResolver.openInputStream(second)!!.use { it.readBytes() })
    }

    @Test fun `concurrent publish cleanup cannot delete the newer primary clip file`() {
        val controlledClipboard = mockk<ClipboardManager>()
        val context = spyk(app)
        every { context.getSystemService(ClipboardManager::class.java) } returns controlledClipboard
        val concurrentCalls = AtomicInteger()
        val maxConcurrentCalls = AtomicInteger()
        val callbackCount = AtomicInteger()
        val firstCallbackEntered = CountDownLatch(1)
        val allowFirstCallbackToReturn = CountDownLatch(1)
        val currentClip = AtomicReference<ClipData>()
        every { controlledClipboard.setPrimaryClip(any()) } answers {
            val active = concurrentCalls.incrementAndGet()
            maxConcurrentCalls.updateAndGet { previous -> maxOf(previous, active) }
            try {
                currentClip.set(firstArg())
                if (callbackCount.getAndIncrement() == 0) {
                    firstCallbackEntered.countDown()
                    check(allowFirstCallbackToReturn.await(5, TimeUnit.SECONDS)) {
                        "timed out waiting to release first clipboard publication"
                    }
                }
            } finally {
                concurrentCalls.decrementAndGet()
            }
        }

        val publisher = ClipboardImages(context)
        val failures = java.util.concurrent.CopyOnWriteArrayList<Throwable>()
        val firstResult = AtomicReference<Uri>()
        val secondResult = AtomicReference<Uri>()
        val secondAttempting = CountDownLatch(1)
        val secondFinished = CountDownLatch(1)
        val firstThread = Thread({
            runCatching { publisher.publish(byteArrayOf(1), "png") }
                .onSuccess(firstResult::set)
                .onFailure(failures::add)
        }, "clipboard-publish-first")
        val secondThread = Thread({
            secondAttempting.countDown()
            try {
                runCatching { publisher.publish(byteArrayOf(2), "svg") }
                    .onSuccess(secondResult::set)
                    .onFailure(failures::add)
            } finally {
                secondFinished.countDown()
            }
        }, "clipboard-publish-second")

        try {
            firstThread.start()
            assertTrue("first publication should reach setPrimaryClip", firstCallbackEntered.await(5, TimeUnit.SECONDS))
            secondThread.start()
            assertTrue("second publisher should start", secondAttempting.await(5, TimeUnit.SECONDS))

            // With serialized publish(), B must wait while A is between setting the
            // primary clip and pruning old payload files. On the unsynchronized path,
            // B finishes and A's later cleanup deletes B's active file.
            val secondFinishedBeforeFirstReleased = secondFinished.await(300, TimeUnit.MILLISECONDS)
            allowFirstCallbackToReturn.countDown()
            firstThread.join(5_000)
            secondThread.join(5_000)

            assertFalse("publisher threads must finish", firstThread.isAlive || secondThread.isAlive)
            assertTrue("both publications should succeed: $failures", failures.isEmpty())
            assertFalse("the second publication must wait for first cleanup", secondFinishedBeforeFirstReleased)
            assertEquals(1, maxConcurrentCalls.get())
            assertNotNull(firstResult.get())
            assertNotNull(secondResult.get())

            val activeUri = currentClip.get().getItemAt(0).uri
            assertEquals(secondResult.get(), activeUri)
            assertArrayEquals(byteArrayOf(2), app.contentResolver.openInputStream(activeUri!!)!!.use { it.readBytes() })
        } finally {
            allowFirstCallbackToReturn.countDown()
            firstThread.join(5_000)
            secondThread.join(5_000)
        }
    }

    @Test fun `returns empty when the clipboard service or primary clip is absent`() {
        val missingService = mockk<Context>()
        every { missingService.getSystemService(ClipboardManager::class.java) } returns null
        val missingClipboard = ClipboardImages(missingService)
        assertFalse(missingClipboard.hasPicture())
        assertEquals(ClipboardLook.Empty, missingClipboard.peek())

        clipboard.clearPrimaryClip()
        assertFalse(images.hasPicture())
        assertEquals(ClipboardLook.Empty, images.peek())

        clipboard.setPrimaryClip(ClipData.newPlainText("text", "not an image"))
        assertEquals(ClipboardLook.Empty, images.peek())
        assertFalse(images.hasPicture())
    }

    @Test fun `handles a clip description with no MIME entries`() {
        val clip = ClipData(ClipDescription("empty", emptyArray()), ClipData.Item("plain text"))
        clipboard.setPrimaryClip(clip)

        assertFalse(images.hasPicture())
        assertEquals(ClipboardLook.Empty, images.peek())
    }

    @Test fun `returns empty for unsupported MIME even when the URI can be read`() {
        val uri = source("document.pdf", "application/pdf", byteArrayOf(1, 2, 3))
        clipboard.setPrimaryClip(ClipData.newUri(app.contentResolver, "PDF", uri))

        assertFalse(images.hasPicture())
        assertEquals(ClipboardLook.Empty, images.peek())
    }

    @Test fun `resolves a plain text content URI through the clipboard provider`() {
        val expected = byteArrayOf(9, 8, 7)
        val uri = source("text-uri.png", "image/png", expected)
        clipboard.setPrimaryClip(ClipData.newPlainText("URI", uri.toString()))

        assertRaster(images.peek(), expected)
    }

    @Test fun `skips unreadable URI items and returns the next readable image`() {
        val denied = source("denied.png", "image/png", byteArrayOf(0))
        sourceProvider.denied += denied.lastPathSegment!!
        val validBytes = byteArrayOf(8, 7, 6)
        val valid = source("valid.png", "image/png", validBytes)
        val clip = ClipData.newUri(app.contentResolver, "image", denied).apply { addItem(ClipData.Item(valid)) }
        clipboard.setPrimaryClip(clip)

        assertRaster(images.peek(), validBytes)
    }

    @Test fun `reports unreadable when all discovered image sources are denied`() {
        val denied = source("denied-only.png", "image/png", byteArrayOf(0))
        sourceProvider.denied += denied.lastPathSegment!!
        clipboard.setPrimaryClip(ClipData.newUri(app.contentResolver, "image", denied))

        assertEquals(ClipboardLook.Unreadable, images.peek())
    }

    @Test fun `ignores a readable local URI whose provider type is not an image`() {
        val uri = source("document.pdf", "application/pdf", byteArrayOf(1, 2, 3))
        clipboard.setPrimaryClip(ClipData.newUri(app.contentResolver, "document", uri))

        assertEquals(ClipboardLook.Empty, images.peek())
    }

    @Test fun `continues after a readable but unsupported source and finds a later image`() {
        val textUri = source("document.pdf", "application/pdf", byteArrayOf(1, 2, 3))
        val imageBytes = byteArrayOf(4, 5, 6)
        val imageUri = source("image.png", "image/png", imageBytes)
        clipboard.setPrimaryClip(
            ClipData.newUri(app.contentResolver, "document", textUri).apply {
                addItem(ClipData.Item(imageUri))
            },
        )

        assertRaster(images.peek(), imageBytes)
    }

    @Test fun `accepts SVG from a typed URI and text SVG from a text provider`() {
        val svg = "<svg viewBox=\"0 0 1 1\"><path d=\"M0 0\"/></svg>"
        val typed = source("vector.svg", "image/svg+xml", svg.toByteArray())
        clipboard.setPrimaryClip(ClipData.newUri(app.contentResolver, "svg", typed))
        assertEquals(ClipboardLook.Found(Svg(svg)), images.peek())

        val textUri = source("vector.txt", "text/plain", svg.toByteArray())
        clipboard.setPrimaryClip(ClipData.newUri(app.contentResolver, "svg text", textUri))
        assertEquals(ClipboardLook.Found(Svg(svg)), images.peek())

        val extensionOnly = source("vector.svg", "application/octet-stream", svg.toByteArray())
        clipboard.setPrimaryClip(ClipData.newUri(app.contentResolver, "svg extension", extensionOnly))
        assertEquals(ClipboardLook.Found(Svg(svg)), images.peek())
    }

    @Test fun `accepts raster URI when provider omits MIME or reports a generic binary type`() {
        val expected = byteArrayOf(8, 4, 2)
        val noType = source("opaque.bin", "", expected)
        clipboard.setPrimaryClip(ClipData.newUri(app.contentResolver, "file", noType))
        assertRaster(images.peek(), expected)

        val generic = source("generic.bin", "application/octet-stream", expected)
        clipboard.setPrimaryClip(ClipData.newUri(app.contentResolver, "file", generic))
        assertRaster(images.peek(), expected)
    }

    @Test fun `returns unreadable when opening a URI yields no stream`() {
        sourceProvider.emptyStreams += "empty-stream.png"
        val uri = source("empty-stream.png", "image/png", byteArrayOf(1, 2, 3))
        clipboard.setPrimaryClip(ClipData.newUri(app.contentResolver, "image", uri))

        assertEquals(ClipboardLook.Unreadable, images.peek())
    }

    @Test fun `does not treat arbitrary text as an SVG document`() {
        clipboard.setPrimaryClip(ClipData.newPlainText("text", "<html><svg>not an SVG root</svg></html>"))

        assertEquals(ClipboardLook.Empty, images.peek())
    }

    @Test fun `uses a text SVG directly without requiring a clipboard file`() {
        val svg = "  <svg xmlns=\"http://www.w3.org/2000/svg\"></svg>  ".trim()
        clipboard.setPrimaryClip(ClipData.newPlainText("SVG", svg))

        assertFalse(images.hasPicture())
        assertEquals(ClipboardLook.Found(Svg(svg)), images.peek())
    }

    @Test fun `reads a single intent stream on API 34`() = assertIntentStreamUris(Build.VERSION_CODES.UPSIDE_DOWN_CAKE, single = true)

    @Test fun `reads multiple intent stream URIs on API 34 and falls through unreadable entries`() =
        assertIntentStreamUris(Build.VERSION_CODES.UPSIDE_DOWN_CAKE, single = false)

    @Test @Config(sdk = [31]) fun `reads a single legacy intent stream on API 31`() =
        assertIntentStreamUris(Build.VERSION_CODES.S, single = true)

    @Test @Config(sdk = [31]) fun `reads multiple legacy intent stream URIs on API 31`() =
        assertIntentStreamUris(Build.VERSION_CODES.S, single = false)

    @Test fun `reads an intent data URI when no stream extra is present`() {
        val expected = byteArrayOf(4, 5, 6)
        val uri = source("data-uri.png", "image/png", expected)
        clipboard.setPrimaryClip(ClipData.newIntent("share", Intent().setData(uri)))
        assertRaster(images.peek(), expected)
    }

    private fun assertIntentStreamUris(sdk: Int, single: Boolean) {
        assertEquals(sdk, android.os.Build.VERSION.SDK_INT)
        val expected = byteArrayOf((if (single) 1 else 2).toByte(), 3, 4)
        val uri = source("stream-$sdk-$single.png", "image/png", expected)
        val intent = Intent()
        if (single) {
            intent.putExtra(Intent.EXTRA_STREAM, uri)
        } else {
            val denied = source("denied-stream-$sdk.png", "image/png", byteArrayOf(0))
            sourceProvider.denied += denied.lastPathSegment!!
            intent.putParcelableArrayListExtra(Intent.EXTRA_STREAM, arrayListOf(denied, uri))
        }
        clipboard.setPrimaryClip(ClipData.newIntent("share", intent))

        assertRaster(images.peek(), expected)
    }

    private fun source(name: String, mime: String, bytes: ByteArray): Uri =
        sourceProvider.add(name, mime, bytes)

    private fun providerInfo() = app.packageManager.getProviderInfo(
        android.content.ComponentName(app, CutoutFileProvider::class.java),
        android.content.pm.PackageManager.GET_META_DATA,
    )

    private fun assertRaster(result: ClipboardLook, expected: ByteArray) {
        assertTrue(result is ClipboardLook.Found)
        val image = (result as ClipboardLook.Found).image
        assertTrue(image is Raster)
        assertArrayEquals(expected, (image as Raster).bytes)
    }

    @After fun tearDown() {
        sourceProvider.denied.clear()
        sourceProvider.emptyStreams.clear()
        File(app.filesDir, "cutouts").deleteRecursively()
    }

    private class SourceProvider(private val app: Application) : ContentProvider() {
        val denied = mutableSetOf<String>()
        val emptyStreams = mutableSetOf<String>()
        private val values = mutableMapOf<String, Pair<String, ByteArray>>()

        fun add(name: String, mime: String, bytes: ByteArray): Uri {
            values[name] = mime to bytes
            return Uri.parse("content://$SOURCE_AUTHORITY/$name")
        }

        override fun onCreate() = true
        override fun getType(uri: Uri): String? = values[uri.lastPathSegment]?.first
        override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0

        override fun openAssetFile(uri: Uri, mode: String): AssetFileDescriptor? {
            if (uri.lastPathSegment in emptyStreams) return null
            return super.openAssetFile(uri, mode)
        }

        override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
            val name = uri.lastPathSegment.orEmpty()
            if (name in denied) throw SecurityException("read denied")
            val bytes = values[name]?.second ?: throw FileNotFoundException(name)
            val file = File(app.cacheDir, "clipboard-source-$name").apply { writeBytes(bytes) }
            return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        }
    }

    private companion object {
        const val SOURCE_AUTHORITY = "dev.kigya.cutout.test.clipboard"
    }
}
