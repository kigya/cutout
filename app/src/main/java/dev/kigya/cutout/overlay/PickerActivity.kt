package dev.kigya.cutout.overlay

import android.net.Uri
import android.os.Bundle
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import dev.kigya.cutout.data.readImageBytes
import dev.kigya.cutout.domain.Messages
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts.OpenDocument
import androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia
import androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia.ImageOnly
import dev.kigya.cutout.data.IncomingImage
import dev.kigya.cutout.data.looksLikeSvg
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class PickerActivity : ComponentActivity() {
    @Inject lateinit var bus: PickBus

    private val gallery = registerForActivityResult(PickVisualMedia()) { uri -> deliver(uri) }
    private val file = registerForActivityResult(OpenDocument()) { uri -> deliver(uri) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (intent.getStringExtra(EXTRA_TARGET) == PickBus.TARGET_EDITOR &&
            !bus.attachEditor(this, intent.getStringExtra(EXTRA_REQUEST))) { finish(); return }
        if (savedInstanceState != null) return
        when (intent.getStringExtra(EXTRA_MODE)) {
            MODE_FILE -> file.launch(arrayOf("image/*", "image/svg+xml"))
            else -> gallery.launch(PickVisualMediaRequest(ImageOnly))
        }
    }

    private fun deliver(uri: Uri?) {
        val target = intent.getStringExtra(EXTRA_TARGET) ?: PickBus.TARGET_BUBBLE
        if (uri == null) {
            bus.cancel(target, intent.getStringExtra(EXTRA_REQUEST))
            finish()
            return
        }
        lifecycleScope.launch {
            val image = withContext(Dispatchers.IO) {
                try {
                    val type = contentResolver.getType(uri).orEmpty()
                    val bytes = contentResolver.openInputStream(uri)?.use { readImageBytes(it) }
                    if (bytes == null) IncomingImage.Invalid(Messages.READ_FAILED)
                    else if (type.contains("svg") || type.startsWith("text")) {
                        val text = bytes.toString(Charsets.UTF_8)
                        if (looksLikeSvg(text)) IncomingImage.Svg(text) else IncomingImage.Invalid(Messages.READ_FAILED)
                    } else IncomingImage.Raster(bytes)
                } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                catch (_: Exception) { IncomingImage.Invalid(Messages.READ_FAILED) }
            }
            bus.offer(image, target, intent.getStringExtra(EXTRA_REQUEST))
            finish()
        }
    }

    companion object {
        const val EXTRA_REQUEST = "request_id"
        const val EXTRA_MODE = "mode"
        const val EXTRA_TARGET = "target"
        const val MODE_GALLERY = "gallery"
        const val MODE_FILE = "file"
    }
}
