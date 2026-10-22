package dev.kigya.cutout.data

import android.net.Uri
import androidx.core.content.FileProvider

class CutoutFileProvider : FileProvider() {
    override fun getType(uri: Uri): String {
        val name = uri.lastPathSegment.orEmpty()
        return when {
            name.endsWith(".svg", ignoreCase = true) -> "image/svg+xml"
            name.endsWith(".png", ignoreCase = true) -> "image/png"
            else -> super.getType(uri) ?: "application/octet-stream"
        }
    }
}
