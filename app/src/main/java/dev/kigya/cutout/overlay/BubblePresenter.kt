package dev.kigya.cutout.overlay

import android.content.Context
import android.content.Intent
import dev.kigya.cutout.MainActivity
import dev.kigya.cutout.cutout.CutListener
import dev.kigya.cutout.cutout.CutOutcome
import dev.kigya.cutout.cutout.ImageCutter
import dev.kigya.cutout.domain.Messages
import dev.kigya.cutout.data.ClipboardImages
import dev.kigya.cutout.data.ClipboardLook
import dev.kigya.cutout.data.EditSessions
import dev.kigya.cutout.data.IncomingImage
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Singleton
class BubblePresenter @Inject constructor(
    @ApplicationContext private val context: Context,
    private val cutter: ImageCutter,
    private val sessions: EditSessions,
    private val picks: PickBus,
    private val reads: ClipboardBus,
    private val images: ClipboardImages,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val telemetry by lazy { dev.kigya.cutout.data.AppTelemetry(context) }
    private var activeCut = false
    private val _state = MutableStateFlow(BubbleUiState())
    val state = _state.asStateFlow()

    init {
        scope.launch {
            picks.bubblePicks.collect { image ->
            if (image == null) {
                mutate {
                    it.copy(
                        phase = BubblePhase.Failed,
                        readingClipboard = false,
                        expanded = true,
                        message = null,
                    )
                }
            } else {
                cut(image)
            }
        }
        }
        scope.launch {
            reads.reads.collect { read -> onClipboard(read) }
        }
    }

    fun onEvent(event: BubbleUiEvent) {
        when (event) {
            BubbleUiEvent.Toggle -> if (_state.value.expanded) collapse() else expand()
            BubbleUiEvent.Dismiss -> collapse()
            BubbleUiEvent.Collapse -> collapse()
            BubbleUiEvent.FromClipboard -> {
                mutate {
                    it.copy(
                        readingClipboard = true,
                        phase = BubblePhase.Working,
                        message = null,
                        previewPath = null,
                        percent = 8,
                    )
                }
                openClipboard(consume = true)
            }
            BubbleUiEvent.FromGallery -> openPicker(PickerActivity.MODE_GALLERY)
            BubbleUiEvent.FromFile -> openPicker(PickerActivity.MODE_FILE)
            BubbleUiEvent.Refine -> openEditor()
            BubbleUiEvent.Again -> mutate {
                it.copy(
                    phase = BubblePhase.Idle,
                    message = null,
                    previewPath = null,
                    editReady = false,
                    percent = 0,
                    rasterizedSvg = false,
                )
            }
        }
    }

    private fun mutate(block: (BubbleUiState) -> BubbleUiState) {
        _state.update { current -> block(current).copy(revision = current.revision + 1) }
    }

    fun setAnchorEnd(anchorEnd: Boolean) {
        mutate { it.copy(anchorEnd = anchorEnd) }
    }

    fun collapse() {
        if (!_state.value.expanded && !_state.value.readingClipboard) return
        mutate { it.copy(expanded = false, readingClipboard = false) }
    }

    private fun expand() {
        val ready = runCatching { images.hasPicture() }.getOrDefault(false)
        mutate { current ->
            val fresh = current.phase == BubblePhase.Idle
            current.copy(
                expanded = true,
                readingClipboard = false,
                clipboardReady = ready,
                phase = if (fresh) BubblePhase.Idle else current.phase,
                message = if (fresh) null else current.message,
                previewPath = if (fresh) null else current.previewPath,
                rasterizedSvg = if (fresh) false else current.rasterizedSvg,
            )
        }
    }

    private fun onClipboard(read: ClipboardRead) {
        when (val look = read.look) {
            is ClipboardLook.Found -> {
                if (read.consume) {
                    mutate { it.copy(readingClipboard = false, clipboardReady = true) }
                    cut(look.image)
                } else {
                    mutate { current ->
                        val clearError = current.phase == BubblePhase.Failed
                        current.copy(
                            readingClipboard = false,
                            clipboardReady = true,
                            phase = if (clearError) BubblePhase.Idle else current.phase,
                            message = if (clearError) null else current.message,
                        )
                    }
                }
            }
            ClipboardLook.Empty -> {
                mutate { current ->
                    current.copy(
                        readingClipboard = false,
                        clipboardReady = false,
                        phase = if (read.consume) BubblePhase.Failed else current.phase,
                        message = if (read.consume) Messages.EMPTY_CLIPBOARD else current.message,
                    )
                }
            }
            ClipboardLook.Unreadable -> {
                mutate {
                    it.copy(
                        readingClipboard = false,
                        clipboardReady = false,
                        phase = BubblePhase.Failed,
                        message = Messages.READ_FAILED,
                    )
                }
            }
        }
    }

    private fun openClipboard(consume: Boolean) {
        val intent = Intent(context, ClipboardActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
            .putExtra(ClipboardActivity.EXTRA_CONSUME, consume)
        try {
            context.startActivity(intent)
        } catch (_: Exception) {
            mutate {
                it.copy(
                    readingClipboard = false,
                    phase = BubblePhase.Failed,
                    message = Messages.PICKER_FAILED,
                )
            }
        }
    }

    private fun openPicker(mode: String) {
        collapse()
        val intent = Intent(context, PickerActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra(PickerActivity.EXTRA_MODE, mode)
            .putExtra(PickerActivity.EXTRA_TARGET, PickBus.TARGET_BUBBLE)
        try {
            context.startActivity(intent)
        } catch (_: Exception) {
            mutate {
                it.copy(
                    phase = BubblePhase.Failed,
                    message = Messages.PICKER_FAILED,
                )
            }
        }
    }

    private fun openEditor() {
        if (sessions.pending == null) {
            mutate { it.copy(editReady = false, message = Messages.READ_FAILED, expanded = true) }
            return
        }
        val intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(MainActivity.EXTRA_EDIT, true)
        try {
            sessions.requestOpen()
            context.startActivity(intent)
            mutate { it.copy(editReady = true, message = null, expanded = false) }
        } catch (_: Exception) {
            sessions.openRequested = false
            mutate { it.copy(editReady = false, message = Messages.READ_FAILED, expanded = true) }
        }
    }

    private fun cut(image: IncomingImage) {
        if (_state.value.phase == BubblePhase.Working && !_state.value.readingClipboard && activeCut) return
        activeCut = true
        val task = java.util.UUID.randomUUID().toString()
        val started = android.os.SystemClock.elapsedRealtime()
        telemetry.event("cutout_started", mapOf("surface" to "bubble"))
        scope.launch {
            try {
            mutate {
                it.copy(
                    expanded = true,
                    readingClipboard = false,
                    phase = BubblePhase.Working,
                    message = null,
                    previewPath = null,
                    rasterizedSvg = false,
                    editReady = false,
                    percent = 8,
                    taskId = task,
                    stage = "preparing",
                )
            }
            val outcome = withContext(Dispatchers.Default) {
                cutter.cut(
                    image,
                    object : CutListener {
                        override fun onPreview(path: String) {
                            mutate { it.copy(previewPath = path, phase = BubblePhase.Working) }
                        }

                        override fun onProgress(percent: Int) {
                            mutate { it.copy(percent = maxOf(it.percent, percent), phase = BubblePhase.Working,
                                stage = if (percent < 28) "preparing" else if (percent < 92) "inference" else "exporting") }
                        }
                    },
                )
            }
            apply(outcome)
            telemetry.event(if (outcome is CutOutcome.Failed) "cutout_failed" else "cutout_completed",
                mapOf("surface" to "bubble", "duration_ms" to android.os.SystemClock.elapsedRealtime() - started))
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                mutate { it.copy(phase = BubblePhase.Failed, stage = "failed", message = Messages.READ_FAILED) }
            } finally {
                activeCut = false
            }
        }
    }

    private fun apply(outcome: CutOutcome) {
        when (outcome) {
            is CutOutcome.CopiedRaster -> mutate {
                it.copy(
                    phase = BubblePhase.Copied,
                    stage = "ready",
                    previewPath = outcome.previewPath,
                    rasterizedSvg = outcome.rasterizedSvg,
                    editReady = true,
                    message = outcome.message,
                    percent = 100,
                )
            }
            is CutOutcome.Manual -> mutate {
                it.copy(
                    phase = BubblePhase.Failed,
                    stage = "ready",
                    previewPath = outcome.edit.cutoutPath,
                    editReady = true,
                    message = outcome.message,
                    percent = 100,
                )
            }
            is CutOutcome.CopiedSvg -> mutate {
                it.copy(
                    phase = BubblePhase.Copied,
                    stage = "ready",
                    previewPath = null,
                    rasterizedSvg = false,
                    editReady = false,
                    message = outcome.message,
                    percent = 100,
                )
            }
            is CutOutcome.Failed -> mutate {
                it.copy(phase = BubblePhase.Failed, editReady = false, message = outcome.message, percent = 100)
            }
        }
    }
}
