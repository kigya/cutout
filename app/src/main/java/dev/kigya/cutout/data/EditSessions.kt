package dev.kigya.cutout.data

import android.content.Context
import android.graphics.Bitmap
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.FileOutputStream
import javax.inject.Inject
import javax.inject.Singleton

data class EditFiles(
    val originalPath: String,
    val cutoutPath: String,
    val width: Int,
    val height: Int,
)

@Singleton
class EditSessions @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    @Volatile var pending: EditFiles? = null
    @Volatile var openRequested: Boolean = false

    fun store(original: Bitmap, masked: Bitmap): EditFiles {
        val directory = File(context.cacheDir, "editor").apply { mkdirs() }
        val stamp = java.util.UUID.randomUUID().toString()
        val originalFile = File(directory, "original-$stamp.png")
        val cutoutFile = File(directory, "cutout-$stamp.png")
        writePng(original, originalFile)
        writePng(masked, cutoutFile)
        directory.listFiles()?.forEach { older ->
            if (older != originalFile && older != cutoutFile &&
                System.currentTimeMillis() - older.lastModified() > 24L * 60 * 60 * 1000) older.delete()
        }
        val files = EditFiles(originalFile.absolutePath, cutoutFile.absolutePath, masked.width, masked.height)
        pending = files
        return files
    }

    fun requestOpen() {
        openRequested = true
    }

    fun consumeOpen(): EditFiles? {
        if (!openRequested) return null
        openRequested = false
        return pending
    }

    private fun writePng(bitmap: Bitmap, file: File) {
        FileOutputStream(file).use { stream ->
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)) { "PNG compression failed" }
        }
    }
}
