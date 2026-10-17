package dev.kigya.cutout.data

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.content.FileProvider
import dev.kigya.cutout.domain.ClipPiece
import dev.kigya.cutout.domain.ClipSource
import dev.kigya.cutout.domain.clipHasImage
import dev.kigya.cutout.domain.clipSources
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

sealed interface ClipboardLook {
    data class Found(val image: IncomingImage) : ClipboardLook
    data object Empty : ClipboardLook
    data object Unreadable : ClipboardLook
}

@Singleton
class ClipboardImages @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    fun hasPicture(): Boolean {
        val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return false
        val description = clipboard.primaryClipDescription ?: return false
        val mimeTypes = ArrayList<String>(description.mimeTypeCount)
        for (index in 0 until description.mimeTypeCount) {
            mimeTypes += description.getMimeType(index)
        }
        return clipHasImage(mimeTypes)
    }

    fun peek(): ClipboardLook {
        val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return ClipboardLook.Empty
        val clip = clipboard.primaryClip ?: return ClipboardLook.Empty
        val pieces = ArrayList<ClipPiece>(clip.itemCount)
        for (index in 0 until clip.itemCount) {
            pieces += piece(clip.getItemAt(index))
        }
        val sources = clipSources(pieces)
        if (sources.isEmpty()) return ClipboardLook.Empty
        var denied = false
        for (source in sources) {
            when (source) {
                is ClipSource.Svg -> return ClipboardLook.Found(IncomingImage.Svg(source.text))
                is ClipSource.LocalUri -> when (val opened = open(Uri.parse(source.uri))) {
                    is Opened.Image -> return ClipboardLook.Found(opened.image)
                    Opened.Denied -> denied = true
                    Opened.NotImage -> Unit
                }
            }
        }
        return if (denied) ClipboardLook.Unreadable else ClipboardLook.Empty
    }

    @Synchronized
    fun publish(bytes: ByteArray, extension: String): Uri {
        val directory = File(context.filesDir, "cutouts").apply { mkdirs() }
        val file = File(directory, "cutout-${java.util.UUID.randomUUID()}.$extension")
        file.writeBytes(bytes)
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val clipboard = context.getSystemService(ClipboardManager::class.java)
        checkNotNull(clipboard) { "Clipboard unavailable" }.setPrimaryClip(ClipData.newUri(context.contentResolver, "Cutout", uri))
        directory.listFiles()?.forEach { older ->
            if (older != file) older.delete()
        }
        return uri
    }

    private fun piece(item: ClipData.Item): ClipPiece {
        val text = item.text?.toString() ?: runCatching {
            item.coerceToText(context)?.toString()
        }.getOrNull()
        return ClipPiece(
            uri = item.uri?.toString(),
            streamUris = streamUris(item.intent).map { it.toString() },
            html = item.htmlText,
            text = text,
        )
    }

    private fun open(uri: Uri): Opened {
        return try {
            val type = context.contentResolver.getType(uri).orEmpty()
            val bytes = context.contentResolver.openInputStream(uri)?.use { readImageBytes(it) } ?: return Opened.Denied
            val image = decode(type, uri, bytes)
            if (image == null) Opened.NotImage else Opened.Image(image)
        } catch (_: SecurityException) {
            Opened.Denied
        } catch (_: Exception) {
            Opened.Denied
        }
    }

    private fun decode(type: String, uri: Uri, bytes: ByteArray): IncomingImage? {
        val text = runCatching { bytes.toString(Charsets.UTF_8) }.getOrNull()
        val svgName = type == "image/svg+xml" || uri.toString().endsWith(".svg", ignoreCase = true)
        if (text != null && looksLikeSvg(text) && (svgName || type.startsWith("text") || type.isEmpty())) {
            return IncomingImage.Svg(text)
        }
        if (type.startsWith("image/") || type.isEmpty() || type == "application/octet-stream") {
            return IncomingImage.Raster(bytes)
        }
        return null
    }

    private fun streamUris(intent: Intent?): List<Uri> {
        if (intent == null) return emptyList()
        val gathered = ArrayList<Uri>()
        intent.data?.let { gathered += it }
        val single = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(Intent.EXTRA_STREAM)
        }
        if (single != null) gathered += single
        val many = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM)
        }
        if (many != null) gathered += many
        return gathered
    }

    private sealed interface Opened {
        data class Image(val image: IncomingImage) : Opened
        data object Denied : Opened
        data object NotImage : Opened
    }

}
