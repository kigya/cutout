package dev.kigya.cutout.overlay

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import dev.kigya.cutout.data.GalleryStore
import dagger.hilt.android.AndroidEntryPoint
import java.io.File
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Android 26–28: request legacy write access only for Save; denial offers the system file picker. */
@AndroidEntryPoint
class LegacyExportActivity : ComponentActivity() {
    @Inject lateinit var gallery: GalleryStore
    private val permission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { allowed ->
        if (allowed) saveGallery() else document.launch("Cutout-${System.currentTimeMillis()}.png")
    }
    private val document = registerForActivityResult(ActivityResultContracts.CreateDocument("image/png")) { uri ->
        lifecycleScope.launch {
            val saved = if (uri == null) false else withContext(Dispatchers.IO) {
                runCatching {
                    val source = source() ?: return@runCatching false
                    contentResolver.openOutputStream(uri)?.use { output -> source.inputStream().use { it.copyTo(output) } }
                        ?: return@runCatching false
                    true
                }.getOrDefault(false)
            }
            complete(saved)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState != null) return
        if (source() == null) { complete(false); return }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED) saveGallery()
        else permission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
    }

    private fun source(): File? {
        val path = intent.getStringExtra("path") ?: return null
        val file = File(path).canonicalFile
        return file.takeIf { it.isFile && it.path.startsWith(cacheDir.canonicalPath + File.separator) }
    }

    private fun saveGallery() {
        lifecycleScope.launch {
            val saved = withContext(Dispatchers.IO) { source()?.let { gallery.savePng(it.path) } ?: false }
            complete(saved)
        }
    }

    private fun complete(saved: Boolean) {
        pending.getAndSet(null)?.complete(if (saved) "saved" else "failed")
        finish()
    }

    override fun onDestroy() {
        if (isFinishing) pending.getAndSet(null)?.complete("failed")
        super.onDestroy()
    }

    companion object {
        private val pending = AtomicReference<CompletableDeferred<String>?>(null)
        fun reserve(): CompletableDeferred<String>? {
            val next = CompletableDeferred<String>()
            return next.takeIf { pending.compareAndSet(null, next) }
        }
        fun cancel(request: CompletableDeferred<String>) {
            if (pending.compareAndSet(request, null)) request.complete("failed")
        }
    }
}
