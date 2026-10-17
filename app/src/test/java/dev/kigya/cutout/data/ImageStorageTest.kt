package dev.kigya.cutout.data

import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.util.Base64
import dev.kigya.cutout.cutout.MaskEditor
import dev.kigya.cutout.cutout.MaskWrite
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import java.io.ByteArrayOutputStream
import java.io.File
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowContentResolver

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ImageStorageTest {
    private lateinit var app: Application
    private lateinit var directory: File
    @Before fun setUp() {
        app = RuntimeEnvironment.getApplication()
        directory = File(app.cacheDir, "storage-${java.util.UUID.randomUUID()}").apply { mkdirs() }
    }
    private fun bitmap() = Bitmap.createBitmap(intArrayOf(Color.RED, 0x804488cc.toInt(), Color.TRANSPARENT, Color.BLUE), 2, 2, Bitmap.Config.ARGB_8888)
    private fun original(): File = File(directory, "source.png").also { file -> file.outputStream().use { bitmap().compress(Bitmap.CompressFormat.PNG, 100, it) } }
    private fun editor() = MaskEditor(ClipboardImages(app))
    @Test fun `manual export clamps mask to original transparency without squaring alpha`() {
        val result = editor().writeMasked(original().path, Base64.encodeToString(byteArrayOf(-1, -1, -1, 64), Base64.NO_WRAP), 2, 2)
        assertTrue(result is MaskWrite.Ready)
        val exported = BitmapFactory.decodeFile((result as MaskWrite.Ready).path)
        assertEquals(listOf(255, 128, 0, 64), (0..3).map { Color.alpha(exported.getPixel(it % 2, it / 2)) })
        assertEquals(listOf(255, 128, 0, 64), Base64.decode(editor().readAlpha(result.path), Base64.NO_WRAP).map { it.toInt() and 255 })
    }
    @Test fun `fully erased result is empty and cannot be published`() {
        val result = editor().writeMasked(original().path, "AAAAAA==", 2, 2)
        assertTrue(result is MaskWrite.Empty)
        assertEquals("empty", editor().publish((result as MaskWrite.Empty).path))
    }
    @Test fun `invalid edits never create a valid result`() {
        val file = original()
        assertEquals(MaskWrite.Failed("size"), editor().writeMasked(file.path, "", 0, 2))
        assertEquals(MaskWrite.Failed("size"), editor().writeMasked(file.path, "", 2, -1))
        assertEquals(MaskWrite.Failed("size"), editor().writeMasked(file.path, "", Int.MAX_VALUE, 2))
        assertEquals(MaskWrite.Failed("original"), editor().writeMasked("missing", "", 2, 2))
        assertEquals(MaskWrite.Failed("alpha"), editor().writeMasked(file.path, "???", 2, 2))
        assertEquals(MaskWrite.Failed("alpha"), editor().writeMasked(file.path, "AA==", 2, 2))
        assertNull(editor().readAlpha("missing"))
        assertEquals("failed", editor().publish("missing"))
    }
    @Test fun `manual fallback can resize a source before applying mask`() {
        val result = editor().writeMasked(original().path, "/w==", 1, 1)
        assertTrue(result is MaskWrite.Ready)
        assertEquals(1, BitmapFactory.decodeFile((result as MaskWrite.Ready).path).width)
    }
    @Test fun `manual export can be copied as a valid PNG and preserves its alpha`() {
        val publishedBytes = slot<ByteArray>()
        val clipboard = mockk<ClipboardImages>()
        every { clipboard.publish(capture(publishedBytes), "png") } returns Uri.parse("content://test/cutout.png")
        val maskEditor = MaskEditor(clipboard)
        val write = maskEditor.writeMasked(
            original().path,
            Base64.encodeToString(byteArrayOf(-1, -1, -1, 64), Base64.NO_WRAP),
            2,
            2,
        ) as MaskWrite.Ready

        assertEquals("copied", maskEditor.publish(write.path))
        verify(exactly = 1) { clipboard.publish(any(), "png") }
        val decoded = BitmapFactory.decodeByteArray(publishedBytes.captured, 0, publishedBytes.captured.size)
        assertNotNull(decoded)
        assertEquals(listOf(255, 128, 0, 64), (0..3).map { Color.alpha(decoded.getPixel(it % 2, it / 2)) })
    }
    @Test fun `raster decoder preserves transparency and rejects malformed inputs`() {
        val bytes = ByteArrayOutputStream().also { bitmap().compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        val decoded = decodeRaster(bytes)!!
        assertEquals(2, decoded.width)
        assertEquals(0, Color.alpha(decoded.getPixel(0, 1)))
        assertNull(decodeRaster(byteArrayOf(0, 1, 2)))
    }
    @Test fun `raster decoder downsamples oversized sources to the supported maximum side`() {
        val source = Bitmap.createBitmap(4097, 2, Bitmap.Config.ARGB_8888)
        val bytes = ByteArrayOutputStream().also { source.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        source.recycle()

        val decoded = decodeRaster(bytes)!!

        assertEquals(2048, decoded.width)
        assertEquals(1, decoded.height)
        decoded.recycle()
    }
    @Test @Config(sdk = [30]) fun `invalid raster fallback safely returns null before ImageDecoder is available`() {
        assertNull(decodeRaster(byteArrayOf(0, 1, 2)))
    }
    @Test fun `truncated raster data is rejected by the modern decoder fallback`() {
        val valid = ByteArrayOutputStream().also {
            bitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
        }.toByteArray()

        // Keep the PNG signature and IHDR dimensions but omit the image data. Bounds decoding
        // can still succeed, while the full BitmapFactory decode must fall back and fail safely.
        val truncated = valid.copyOf(24)
        assertNull(decodeRaster(truncated))
    }
    @Test fun `raster import honors every EXIF rotation and mirror orientation`() {
        val source = Bitmap.createBitmap(intArrayOf(Color.RED, Color.GREEN, Color.BLUE, Color.CYAN, Color.MAGENTA, Color.YELLOW), 3, 2, Bitmap.Config.ARGB_8888)
        val file = File(directory, "oriented.jpg")
        file.outputStream().use { source.compress(Bitmap.CompressFormat.JPEG, 100, it) }
        val reference = BitmapFactory.decodeFile(file.path)
        val colors = IntArray(6).also { reference.getPixels(it, 0, 3, 0, 0, 3, 2) }
        val orders = listOf(listOf(0,1,2,3,4,5), listOf(2,1,0,5,4,3), listOf(5,4,3,2,1,0), listOf(3,4,5,0,1,2), listOf(0,3,1,4,2,5), listOf(3,0,4,1,5,2), listOf(5,2,4,1,3,0), listOf(2,5,1,4,0,3))
        for (orientation in 1..8) {
            androidx.exifinterface.media.ExifInterface(file).apply {
                setAttribute(androidx.exifinterface.media.ExifInterface.TAG_ORIENTATION, orientation.toString())
                saveAttributes()
            }
            val decoded = decodeRaster(file.readBytes())!!
            val width = if (orientation >= 5) 2 else 3
            assertEquals("orientation $orientation", width, decoded.width)
            val pixels = IntArray(6).also { decoded.getPixels(it, 0, width, 0, 0, width, decoded.height) }
            assertArrayEquals("orientation $orientation", orders[orientation-1].map { colors[it] }.toIntArray(), pixels)
            decoded.recycle()
        }
    }
    @Test fun `edit session keeps current pair and prunes only expired files`() {
        val cache = File(app.cacheDir, "editor").apply { mkdirs() }
        val expired = File(cache, "expired.png").apply { writeText("old"); setLastModified(1) }
        val recent = File(cache, "recent.png").apply { writeText("recent") }
        val sessions = EditSessions(app)
        assertNull(sessions.consumeOpen())
        assertFalse(sessions.openRequested)
        sessions.requestOpen()
        assertTrue(sessions.openRequested)
        assertNull(sessions.consumeOpen())
        assertFalse(sessions.openRequested)
        val files = sessions.store(bitmap(), bitmap())
        assertEquals(files, sessions.pending)
        assertTrue(File(files.originalPath).isFile)
        assertTrue(File(files.cutoutPath).isFile)
        assertFalse(expired.exists())
        assertTrue(recent.exists())
        assertEquals(2, files.width)
        assertEquals(2, files.height)
        assertNull(sessions.consumeOpen())
        sessions.requestOpen()
        assertEquals(files, sessions.consumeOpen())
        assertNull(sessions.consumeOpen())
    }
    @Test fun `gallery publishes only after bytes are written and rolls back failures`() {
        val provider = RecordingMediaProvider(File(directory, "gallery.png")).apply {
            attachInfo(app, android.content.pm.ProviderInfo().apply { authority = "media" })
        }
        ShadowContentResolver.registerProviderInternal("media", provider)
        val gallery = GalleryStore(app)
        assertFalse(gallery.savePng("missing"))
        val file = original()
        assertTrue(gallery.savePng(file.path))
        assertArrayEquals(file.readBytes(), provider.output.readBytes())
        assertEquals("image/png", provider.inserted!!.getAsString(MediaStore.Images.Media.MIME_TYPE))
        assertEquals(1, provider.inserted!!.getAsInteger(MediaStore.Images.Media.IS_PENDING))
        assertEquals(0, provider.updated!!.getAsInteger(MediaStore.Images.Media.IS_PENDING))
        provider.failPublish = true
        assertFalse(gallery.savePng(file.path))
        assertEquals(1, provider.deletes)
        provider.failPublish = false
        provider.failWrite = true
        assertFalse(gallery.savePng(file.path))
        assertEquals(2, provider.deletes)
        provider.failInsert = true
        assertFalse(gallery.savePng(file.path))
        assertEquals(2, provider.deletes)
    }
    @Test @Config(sdk = [28]) fun `legacy gallery save skips scoped storage pending metadata`() {
        val provider = RecordingMediaProvider(File(directory, "legacy-gallery.png")).apply {
            attachInfo(app, android.content.pm.ProviderInfo().apply { authority = "media" })
        }
        ShadowContentResolver.registerProviderInternal("media", provider)
        val source = original()

        assertTrue(GalleryStore(app).savePng(source.path))

        assertArrayEquals(source.readBytes(), provider.output.readBytes())
        assertNull(provider.inserted!!.getAsString(MediaStore.Images.Media.RELATIVE_PATH))
        assertNull(provider.inserted!!.getAsInteger(MediaStore.Images.Media.IS_PENDING))
        assertNull(provider.updated)
    }
    private class RecordingMediaProvider(val output: File) : ContentProvider() {
        var inserted: ContentValues? = null
        var updated: ContentValues? = null
        var deletes = 0
        var failInsert = false
        var failWrite = false
        var failPublish = false
        override fun onCreate() = true
        override fun getType(uri: Uri) = "image/png"
        override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
        override fun insert(uri: Uri, values: ContentValues?): Uri? {
            if (failInsert) throw SecurityException("denied")
            inserted = ContentValues(values)
            return Uri.parse("content://media/external/images/media/1")
        }
        override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
            if (failWrite) throw java.io.FileNotFoundException("full")
            return ParcelFileDescriptor.open(output, ParcelFileDescriptor.MODE_READ_WRITE or ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_TRUNCATE)
        }
        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int {
            updated = ContentValues(values)
            return if (failPublish) 0 else 1
        }
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int { deletes++; output.delete(); return 1 }
    }
}
