package com.example.ensemble.presentation.results

import android.net.Uri
import com.example.ensemble.domain.Person

data class ResultsState(
    val persons: List<Person> = emptyList(),
    val totalFacesDetected: Int = 0,
    val totalFramesProcessed: Int = 0,
    val videoUriString: String = "",
    val collageUri: Uri? = null,
    val isGeneratingCollage: Boolean = false,
    val showCollagePreviewDialog: Boolean = false
)

sealed interface ResultsAction {
    data object GenerateCollage : ResultsAction
    data object ShareCollage : ResultsAction
    data object DismissCollagePreview : ResultsAction
    data object NavigateBack : ResultsAction
}

sealed interface ResultsEvent {
    data class ShareUri(val uri: Uri) : ResultsEvent
    data class ShowToast(val message: String) : ResultsEvent
}
