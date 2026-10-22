package dev.kigya.cutout.data

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.provider.MediaStore
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class GalleryStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    fun savePng(path: String): Boolean {
        val source = File(path)
        if (!source.isFile) return false
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "Cutout-${System.currentTimeMillis()}.png")
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Cutout")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        }
        val uri = try {
            context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
        } catch (_: Exception) { null } ?: return false
        return try {
            context.contentResolver.openOutputStream(uri)?.use { output ->
                source.inputStream().use { input -> input.copyTo(output) }
            } ?: throw java.io.IOException("No output stream")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val done = ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }
                if (context.contentResolver.update(uri, done, null, null) <= 0) throw java.io.IOException("Could not publish image")
            }
            true
        } catch (_: Exception) {
            runCatching { context.contentResolver.delete(uri, null, null) }
            false
        }
    }
}
