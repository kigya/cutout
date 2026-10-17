package dev.kigya.cutout.cutout

import android.content.Context
import android.graphics.Bitmap
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import dev.kigya.cutout.domain.MaskPolicy
import dev.kigya.cutout.domain.boundsOfAlpha
import dev.kigya.cutout.domain.isnetChw
import dev.kigya.cutout.domain.resampleBilinear
import dev.kigya.cutout.domain.softAlpha
import dev.kigya.cutout.domain.stretchUnit
import dagger.hilt.android.qualifiers.ApplicationContext
import java.nio.FloatBuffer
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

sealed interface Removal {
    data class Ready(val bitmap: Bitmap, val frame: Bitmap) : Removal
    data object SubjectNotFound : Removal
    data class Broken(val detail: String) : Removal
}

@Singleton
class BackgroundRemover @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val mutex = Mutex()
    private var environment: OrtEnvironment? = null
    private var session: OrtSession? = null
    // ORT requires these native resources to outlive every session created with them.
    @field:androidx.annotation.Keep
    private var sessionOptions: OrtSession.SessionOptions? = null
    private var inputName: String? = null

    suspend fun prepare(): Result<Unit> = withContext(Dispatchers.Default) {
        mutex.withLock {
            runCatching {
                if (session == null) open()
            }
        }
    }

    suspend fun cut(source: Bitmap): Removal = withContext(Dispatchers.Default) {
        mutex.withLock {
            val engine = session ?: return@withLock Removal.Broken("model")
            val name = inputName ?: return@withLock Removal.Broken("model")
            runCatching { segment(engine, name, source) }.getOrElse { error ->
                Removal.Broken(error.message ?: error.javaClass.simpleName)
            }
        }
    }

    private fun open() {
        val env = environment ?: OrtEnvironment.getEnvironment().also { environment = it }
        val options = OrtSession.SessionOptions()
        var created: OrtSession? = null
        var retained = false
        try {
            options.setIntraOpNumThreads(2)
            options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            val bytes = context.assets.open(MODEL).use { it.readBytes() }
            // ISNet at 1024 (MIT, imgly/isnet-general-onnx); inference stays on device.
            created = env.createSession(bytes, options)
            inputName = created.inputNames.first()
            sessionOptions = options
            session = created
            retained = true
        } finally {
            if (!retained) {
                try { created?.close() }
                finally { options.close() }
            }
        }
    }

    private fun segment(engine: OrtSession, name: String, source: Bitmap): Removal {
        val env = environment ?: return Removal.Broken("model")
        val frame = software(source) ?: return Removal.Broken("bitmap")
        try {
            return segmentFrame(engine, name, env, frame)
        } finally {
            if (frame !== source) frame.recycle()
        }
    }

    private fun segmentFrame(
        engine: OrtSession,
        name: String,
        env: OrtEnvironment,
        frame: Bitmap,
    ): Removal {
        val scaled = if (frame.width == INPUT && frame.height == INPUT) {
            frame
        } else {
            Bitmap.createScaledBitmap(frame, INPUT, INPUT, true)
        }
        val pixels = IntArray(INPUT * INPUT)
        try {
            scaled.getPixels(pixels, 0, INPUT, 0, 0, INPUT, INPUT)
        } finally {
            if (scaled !== frame) scaled.recycle()
        }
        val chw = isnetChw(pixels)
        val input = OnnxTensor.createTensor(
            env,
            FloatBuffer.wrap(chw),
            longArrayOf(1, 3, INPUT.toLong(), INPUT.toLong()),
        )
        input.use {
            engine.run(mapOf(name to input)).use { result ->
                val output = result.get(0) as? OnnxTensor ?: return Removal.SubjectNotFound
                val info = output.info as? TensorInfo ?: return Removal.Broken("mask")
                val height = info.shape[info.shape.size - 2].toInt()
                val width = info.shape[info.shape.size - 1].toInt()
                val buffer = output.floatBuffer
                buffer.rewind()
                val raw = FloatArray(buffer.remaining())
                buffer.get(raw)
                if (raw.size < width * height || width <= 0 || height <= 0) return Removal.Broken("mask")
                val confidence = stretchUnit(raw.copyOf(width * height))
                val fraction = MaskPolicy.foregroundFraction(confidence)
                if (!MaskPolicy.evaluate(fraction).usable) return Removal.SubjectNotFound
                return paint(frame, confidence, width, height)
            }
        }
    }

    private fun paint(
        source: Bitmap,
        confidence: FloatArray,
        maskWidth: Int,
        maskHeight: Int,
    ): Removal {
        val width = source.width
        val height = source.height
        val alpha = resampleBilinear(confidence, maskWidth, maskHeight, width, height)
        val pixels = IntArray(width * height)
        source.getPixels(pixels, 0, width, 0, 0, width, height)
        for (index in pixels.indices) {
            val sample = minOf((softAlpha(alpha[index]) * 255f).toInt(), (pixels[index] ushr 24) and 0xFF)
            pixels[index] = (pixels[index] and 0x00FFFFFF) or (sample shl 24)
        }
        val bounds = boundsOfAlpha(pixels, width, height, MIN_ALPHA) ?: return Removal.SubjectNotFound
        val full = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        full.setPixels(pixels, 0, width, 0, 0, width, height)
        if (bounds.left == 0 && bounds.top == 0 && bounds.width == width && bounds.height == height) {
            return Removal.Ready(bitmap = full, frame = full)
        }
        val cropped = Bitmap.createBitmap(full, bounds.left, bounds.top, bounds.width, bounds.height)
        return Removal.Ready(bitmap = cropped, frame = full)
    }

    private fun software(source: Bitmap): Bitmap? {
        if (source.config == Bitmap.Config.ARGB_8888) return source
        return source.copy(Bitmap.Config.ARGB_8888, false)
    }

    private companion object {
        const val MODEL = "isnet.onnx"
        const val INPUT = 1024
        const val MIN_ALPHA = 8
    }
}
