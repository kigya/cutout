package dev.kigya.cutout.data

import android.app.Application
import android.content.ComponentName
import android.content.pm.PackageManager
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class CutoutFileProviderTest {
    private lateinit var app: Application
    private lateinit var provider: CutoutFileProvider
    private lateinit var cutouts: File

    @Before fun setUp() {
        app = RuntimeEnvironment.getApplication()
        cutouts = File(app.filesDir, "cutouts").apply { mkdirs() }
        val info = app.packageManager.getProviderInfo(
            ComponentName(app, CutoutFileProvider::class.java),
            PackageManager.GET_META_DATA,
        )
        provider = CutoutFileProvider().also {
            it.attachInfo(app, info)
            ShadowContentResolver.registerProviderInternal("${app.packageName}.fileprovider", it)
        }
    }

    @Test fun `manifest provider is private and grants explicit read access`() {
        val info = app.packageManager.getProviderInfo(
            ComponentName(app, CutoutFileProvider::class.java),
            PackageManager.GET_META_DATA,
        )

        assertFalse(info.exported)
        assertTrue(info.grantUriPermissions)
        assertTrue(info.metaData?.containsKey("android.support.FILE_PROVIDER_PATHS") == true)
        assertTrue((info.metaData?.getInt("android.support.FILE_PROVIDER_PATHS") ?: 0) > 0)
    }

    @Test fun `maps only files from configured cutouts directory and reads them through content resolver`() {
        val bytes = byteArrayOf(1, 3, 5, 7)
        val file = File(cutouts, "published.png").apply { writeBytes(bytes) }

        val uri = FileProvider.getUriForFile(app, "${app.packageName}.fileprovider", file)

        assertEquals("${app.packageName}.fileprovider", uri.authority)
        assertEquals("image/png", app.contentResolver.getType(uri))
        assertArrayEquals(bytes, app.contentResolver.openInputStream(uri)!!.use { it.readBytes() })
        assertThrows(IllegalArgumentException::class.java) {
            FileProvider.getUriForFile(app, "${app.packageName}.fileprovider", File(app.cacheDir, "outside.png"))
        }
    }

    @Test fun `does not expose neighboring private files through provider paths`() {
        val privateFile = File(app.filesDir, "account-token.txt").apply { writeText("private") }

        assertThrows(IllegalArgumentException::class.java) {
            FileProvider.getUriForFile(app, "${app.packageName}.fileprovider", privateFile)
        }
    }

    @Test fun `returns special MIME types for SVG and PNG and delegates unknown extensions`() {
        val svg = providerUri("image.svg")
        val png = providerUri("image.png")
        val unknown = providerUri("image.unknown")

        assertEquals("image/svg+xml", provider.getType(svg))
        assertEquals("image/png", provider.getType(png))
        assertEquals("application/octet-stream", provider.getType(unknown))
    }

    @Test fun `extension MIME matching is case insensitive`() {
        assertEquals("image/svg+xml", provider.getType(providerUri("image.SvG")))
        assertEquals("image/png", provider.getType(providerUri("image.PNG")))
    }

    private fun providerUri(name: String): Uri {
        val file = File(cutouts, name).apply { writeText("test") }
        return FileProvider.getUriForFile(app, "${app.packageName}.fileprovider", file)
    }
}
