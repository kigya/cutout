package dev.kigya.cutout.cutout

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import dev.kigya.cutout.data.ClipboardImages
import dev.kigya.cutout.data.EditFiles
import dev.kigya.cutout.data.EditSessions
import dev.kigya.cutout.data.IncomingImage
import dev.kigya.cutout.data.decodeRaster
import dev.kigya.cutout.domain.Messages
import dev.kigya.cutout.domain.SvgEdit
import dev.kigya.cutout.domain.stripSolidBackdrop
import com.caverock.androidsvg.SVG
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.coroutineContext
import kotlin.math.min
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

interface CutListener {
    fun onPreview(path: String) {}
    fun onProgress(percent: Int) {}
}

sealed interface CutOutcome {
    data class CopiedRaster(
        val message: String,
        val previewPath: String,
        val edit: EditFiles,
        val rasterizedSvg: Boolean,
    ) : CutOutcome

    data class Manual(
        val message: String,
        val edit: EditFiles,
    ) : CutOutcome

    data class CopiedSvg(val message: String) : CutOutcome

    data class Failed(val message: String) : CutOutcome
}

@Singleton
class ImageCutter @Inject constructor(
    @ApplicationContext private val context: Context,
    private val remover: BackgroundRemover,
    private val clipboard: ClipboardImages,
    private val sessions: EditSessions,
) {
    private val running = java.util.concurrent.atomic.AtomicBoolean(false)

    suspend fun prepare(): Result<Unit> = remover.prepare()

    suspend fun cut(image: IncomingImage, listener: CutListener? = null): CutOutcome {
        if (!running.compareAndSet(false, true)) return CutOutcome.Failed(Messages.BUSY)
        try { return cutExclusive(image, listener) } finally { running.set(false) }
    }

    private suspend fun cutExclusive(image: IncomingImage, listener: CutListener?): CutOutcome {
        coroutineContext.ensureActive()
        listener?.onProgress(8)
        return when (image) {
            is IncomingImage.Invalid -> CutOutcome.Failed(image.message)
            is IncomingImage.Svg -> cutSvg(image.text, listener)
            is IncomingImage.Raster -> {
                val bitmap = decodeRaster(image.bytes) ?: return CutOutcome.Failed(Messages.READ_FAILED)
                coroutineContext.ensureActive()
                listener?.onProgress(18)
                cutBitmap(bitmap, rasterizedSvg = false, listener)
            }
        }
    }

    suspend fun cutFixture(listener: CutListener? = null): CutOutcome {
        val bytes = context.assets.open(FIXTURE).use { it.readBytes() }
        return cut(IncomingImage.Raster(bytes), listener)
    }

    private suspend fun cutSvg(text: String, listener: CutListener?): CutOutcome = when (val edit = stripSolidBackdrop(text)) {
        is SvgEdit.Stripped -> {
            coroutineContext.ensureActive()
            clipboard.publish(edit.svg.toByteArray(Charsets.UTF_8), "svg")
            listener?.onProgress(100)
            CutOutcome.CopiedSvg(Messages.COPIED)
        }
        SvgEdit.NotSvg -> CutOutcome.Failed(Messages.READ_FAILED)
        SvgEdit.NoSeparateBackdrop -> {
            val bitmap = rasterize(text) ?: return CutOutcome.Failed(Messages.READ_FAILED)
            coroutineContext.ensureActive()
            listener?.onProgress(18)
            cutBitmap(bitmap, rasterizedSvg = true, listener)
        }
    }

    private suspend fun cutBitmap(bitmap: Bitmap, rasterizedSvg: Boolean, listener: CutListener?): CutOutcome {
        val original = bitmap.copy(Bitmap.Config.ARGB_8888, false)
        if (original == null) {
            if (!bitmap.isRecycled) bitmap.recycle()
            return CutOutcome.Failed(Messages.READ_FAILED)
        }
        return try {
            listener?.onPreview(writePreview(bitmap))
            val prepared = remover.prepare()
            coroutineContext.ensureActive()
            if (prepared.isFailure) return CutOutcome.Failed(Messages.MODEL_FAILED)
            listener?.onProgress(28)
            val removal = remover.cut(bitmap)
            coroutineContext.ensureActive()
            listener?.onProgress(92)
            when (removal) {
                is Removal.Ready -> {
                    val edit = sessions.store(original, removal.frame)
                    recyclePair(removal.bitmap, removal.frame)
                    listener?.onProgress(100)
                    val message = if (rasterizedSvg) Messages.SVG_AS_PNG else ""
                    CutOutcome.CopiedRaster(message, edit.cutoutPath, edit, rasterizedSvg)
                }
                Removal.SubjectNotFound -> {
                    val edit = sessions.store(original, original)
                    listener?.onProgress(100)
                    CutOutcome.Manual(Messages.MANUAL, edit)
                }
                is Removal.Broken -> CutOutcome.Failed(Messages.READ_FAILED)
            }
        } finally {
            if (original !== bitmap) original.recycle()
            if (!bitmap.isRecycled) bitmap.recycle()
        }
    }

    private fun writePreview(bitmap: Bitmap): String {
        context.cacheDir.listFiles()?.filter { it.name.startsWith("cut-source-") }
            ?.sortedByDescending { it.lastModified() }?.drop(8)?.forEach { it.delete() }
        val file = File(context.cacheDir, "cut-source-${java.util.UUID.randomUUID()}.jpg")
        file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.JPEG, 82, it)) { "Preview compression failed" } }
        return file.absolutePath
    }

    private fun recyclePair(tight: Bitmap, frame: Bitmap) {
        if (!tight.isRecycled) tight.recycle()
        if (frame !== tight && !frame.isRecycled) frame.recycle()
    }

    private fun rasterize(svg: String): Bitmap? = try {
        val picture = SVG.getFromString(svg)
        val documentWidth = picture.documentWidth.takeIf { it > 0f } ?: 1024f
        val documentHeight = picture.documentHeight.takeIf { it > 0f } ?: 1024f
        val scale = min(2048f / documentWidth, min(2048f / documentHeight, 1f))
        val width = (documentWidth * scale).toInt().coerceAtLeast(1)
        val height = (documentHeight * scale).toInt().coerceAtLeast(1)
        picture.setDocumentWidth(width.toFloat())
        picture.setDocumentHeight(height.toFloat())
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        picture.renderToCanvas(Canvas(bitmap))
        bitmap
    } catch (_: Exception) {
        null
    }

    private companion object {
        const val FIXTURE = "fixture.png"
    }
}
