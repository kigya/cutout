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

sealed interface BubbleUiEvent {
    data object Toggle : BubbleUiEvent
    data object FromClipboard : BubbleUiEvent
    data object FromGallery : BubbleUiEvent
    data object FromFile : BubbleUiEvent
    data object Dismiss : BubbleUiEvent
    data object Refine : BubbleUiEvent
    data object Again : BubbleUiEvent
}
