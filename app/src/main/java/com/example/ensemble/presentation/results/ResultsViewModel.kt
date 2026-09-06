package com.example.ensemble.presentation.results

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.ensemble.data.CollageGenerator
import com.example.ensemble.domain.Person
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

class ResultsViewModel : ViewModel() {

    private val _state = MutableStateFlow(ResultsState())
    val state: StateFlow<ResultsState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<ResultsEvent>()
    val events: SharedFlow<ResultsEvent> = _events.asSharedFlow()

    fun setResults(
        persons: List<Person>,
        facesDetected: Int,
        framesProcessed: Int,
        videoUri: String
    ) {
        _state.update {
            it.copy(
                persons = persons,
                totalFacesDetected = facesDetected,
                totalFramesProcessed = framesProcessed,
                videoUriString = videoUri
            )
        }
    }

    fun onAction(action: ResultsAction, context: Context) {
        when (action) {
            is ResultsAction.GenerateCollage -> {
                generateCollage(context)
            }
            is ResultsAction.ShareCollage -> {
                val currentUri = _state.value.collageUri
                if (currentUri != null) {
                    viewModelScope.launch {
                        _events.emit(ResultsEvent.ShareUri(currentUri))
                    }
                } else {
                    // Auto-generate then share
                    generateCollage(context) { uri ->
                        viewModelScope.launch {
                            _events.emit(ResultsEvent.ShareUri(uri))
                        }
                    }
                }
            }
            is ResultsAction.DismissCollagePreview -> {
                _state.update { it.copy(showCollagePreviewDialog = false) }
            }
            is ResultsAction.NavigateBack -> {
                // Handled in UI navigation
            }
        }
    }

    private fun generateCollage(context: Context, onComplete: ((Uri) -> Unit)? = null) {
        viewModelScope.launch(Dispatchers.Default) {
            _state.update { it.copy(isGeneratingCollage = true) }
            val people = _state.value.persons
            val bitmap: Bitmap = CollageGenerator.generateCollageBitmap(people)
            val uri: Uri? = CollageGenerator.saveCollageToCache(context.applicationContext, bitmap)

            _state.update {
                it.copy(
                    isGeneratingCollage = false,
                    collageUri = uri,
                    showCollagePreviewDialog = onComplete == null
                )
            }

            if (uri != null && onComplete != null) {
                onComplete(uri)
            }
        }
    }
}
