package dev.kigya.cutout.overlay

enum class BubblePhase {
    Idle,
    Working,
    Copied,
    Failed,
}

data class BubbleUiState(
    val revision: Int = 0,
    val taskId: String = "",
    val stage: String = "idle",
    val expanded: Boolean = false,
    val anchorEnd: Boolean = true,
    val phase: BubblePhase = BubblePhase.Idle,
    val clipboardReady: Boolean = false,
    val readingClipboard: Boolean = false,
    val message: String? = null,
    val previewPath: String? = null,
    val rasterizedSvg: Boolean = false,
    val editReady: Boolean = false,
    val percent: Int = 0,
)

/** Native-owned viewport for the persistent React surface embedded in BubbleService. */
data class BubbleGeometry(
    val revision: Long,
    val expanded: Boolean,
    val anchorEnd: Boolean,
    val windowWidthDp: Int,
    val windowHeightDp: Int,
    val surfaceWidthDp: Int = 640,
    val surfaceHeightDp: Int = 400,
    val chipSizeDp: Int = 72,
    val panelWidthDp: Int = 260,
    val panelHeightDp: Int = 260,
    val chipLeftDp: Int = 272,
    val chipTopDp: Int = 0,
    val stateRevision: Int = 0,
    val phase: BubblePhase = BubblePhase.Idle,
)

sealed interface BubbleUiEvent {
    data object Toggle : BubbleUiEvent
    data object FromClipboard : BubbleUiEvent
    data object FromGallery : BubbleUiEvent
    data object FromFile : BubbleUiEvent
    data object Dismiss : BubbleUiEvent
    data object Collapse : BubbleUiEvent
    data object Refine : BubbleUiEvent
    data object Again : BubbleUiEvent
}
