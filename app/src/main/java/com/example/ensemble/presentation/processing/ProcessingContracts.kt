package com.example.ensemble.presentation.processing

import android.net.Uri
import com.example.ensemble.domain.DataError
import com.example.ensemble.domain.Person

// ── State ──────────────────────────────────────────────────────────────────
data class ProcessingState(
    val isPickingVideo: Boolean = false,
    val selectedVideoUri: Uri? = null,
    val isProcessing: Boolean = false,
    val progress: Float = 0f,         // 0..1
    val framesProcessed: Int = 0,
    val totalFrames: Int = 0,
    val facesDetected: Int = 0,
    val error: DataError? = null,
    val isDone: Boolean = false,
    val persons: List<Person> = emptyList()
)

// ── Action ─────────────────────────────────────────────────────────────────
sealed interface ProcessingAction {
    data object PickVideo : ProcessingAction
    data class VideoSelected(val uri: Uri) : ProcessingAction
    data object DismissError : ProcessingAction
    data object NavigateToResults : ProcessingAction
}

// ── Event (one-shot) ───────────────────────────────────────────────────────
sealed interface ProcessingEvent {
    data object LaunchVideoPicker : ProcessingEvent
    data object NavigateToResults : ProcessingEvent
    data class ShowError(val message: String) : ProcessingEvent
}
