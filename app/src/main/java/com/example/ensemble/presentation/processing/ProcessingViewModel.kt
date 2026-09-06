package com.example.ensemble.presentation.processing

import android.app.Application
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.ensemble.data.FaceEmbedder
import com.example.ensemble.data.MlKitFaceDetector
import com.example.ensemble.data.PersonClusterer
import com.example.ensemble.data.VideoFrameExtractor
import com.example.ensemble.domain.DataError
import com.example.ensemble.domain.FaceInstance
import com.example.ensemble.domain.Person
import com.example.ensemble.domain.Result
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TAG = "ProcessingViewModel"

class ProcessingViewModel(
    application: Application,
    private val frameExtractor: VideoFrameExtractor,
    private val faceDetector: MlKitFaceDetector,
    private val faceEmbedder: FaceEmbedder,
    private val personClusterer: PersonClusterer
) : AndroidViewModel(application) {

    private val _state = MutableStateFlow(ProcessingState())
    val state: StateFlow<ProcessingState> = _state.asStateFlow()

    private val _events = Channel<ProcessingEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    // Accumulated face instances with 192-d embeddings for the current video (passed to Part 4 clustering)
    private val _allFaceInstances = mutableListOf<FaceInstance>()
    val allFaceInstances: List<FaceInstance> get() = _allFaceInstances.toList()

    fun onAction(action: ProcessingAction) {
        when (action) {
            is ProcessingAction.PickVideo -> {
                viewModelScope.launch {
                    _events.send(ProcessingEvent.LaunchVideoPicker)
                }
            }
            is ProcessingAction.VideoSelected -> {
                _state.update { it.copy(selectedVideoUri = action.uri, error = null) }
                startProcessing(action.uri)
            }
            is ProcessingAction.DismissError -> {
                _state.update { it.copy(error = null) }
            }
            is ProcessingAction.NavigateToResults -> {
                viewModelScope.launch {
                    _events.send(ProcessingEvent.NavigateToResults)
                }
            }
        }
    }

    private fun startProcessing(uri: Uri) {
        viewModelScope.launch {
            _allFaceInstances.clear()
            _state.update {
                it.copy(
                    isProcessing = true,
                    progress = 0f,
                    framesProcessed = 0,
                    facesDetected = 0,
                    isDone = false,
                    error = null,
                    persons = emptyList()
                )
            }

            frameExtractor.extractFrames(uri).collect { result ->
                when (result) {
                    is Result.Success -> {
                        val frame = result.data

                        // 1. Run ML Kit face detection on this frame
                        val detectResult = faceDetector.detect(
                            bitmap = frame.bitmap,
                            timestampMs = frame.timestampMs,
                            videoUri = uri.toString()
                        )

                        when (detectResult) {
                            is Result.Success -> {
                                val instances = detectResult.data
                                if (instances.isNotEmpty()) {
                                    // 2. Generate MobileFaceNet 192-d embedding for each detected face
                                    val instancesWithEmbeddings = instances.mapNotNull { instance ->
                                        val embedResult = faceEmbedder.generateEmbedding(instance)
                                        if (embedResult is Result.Success) {
                                            instance.copy(embedding = embedResult.data)
                                        } else {
                                            Log.w(TAG, "Embedding generation failed for face @${instance.timestampMs}ms")
                                            null
                                        }
                                    }

                                    if (instancesWithEmbeddings.isNotEmpty()) {
                                        _allFaceInstances.addAll(instancesWithEmbeddings)
                                    } else {
                                        frame.bitmap.recycle()
                                    }
                                } else {
                                    // Recycle bitmap if no face detected in this frame
                                    frame.bitmap.recycle()
                                }

                                _state.update { current ->
                                    val progress = (frame.frameIndex + 1).toFloat() /
                                            frame.totalFrames.coerceAtLeast(1)
                                    current.copy(
                                        framesProcessed = frame.frameIndex + 1,
                                        totalFrames = frame.totalFrames,
                                        progress = progress.coerceIn(0f, 1f),
                                        facesDetected = _allFaceInstances.size
                                    )
                                }
                            }
                            is Result.Error -> {
                                Log.w(TAG, "Detection error at frame ${frame.frameIndex}: ${detectResult.error}")
                                frame.bitmap.recycle()
                                _state.update { current ->
                                    val progress = (frame.frameIndex + 1).toFloat() /
                                            frame.totalFrames.coerceAtLeast(1)
                                    current.copy(
                                        framesProcessed = frame.frameIndex + 1,
                                        totalFrames = frame.totalFrames,
                                        progress = progress.coerceIn(0f, 1f)
                                    )
                                }
                            }
                        }

                        Log.d(TAG, "Frame ${frame.frameIndex}/${frame.totalFrames} @ ${frame.timestampMs}ms — " +
                                "totalFaces=${_allFaceInstances.size}")
                    }
                    is Result.Error -> {
                        Log.e(TAG, "Frame extraction error: ${result.error}")
                        if (result.error == DataError.Local.UNSUPPORTED_VIDEO ||
                            result.error == DataError.Local.OUT_OF_MEMORY
                        ) {
                            _state.update { it.copy(error = result.error) }
                        }
                    }
                }
            }

            // 3. Perform Part 4 Single-Pass Incremental Clustering across all detected faces
            Log.d(TAG, "Extraction finished. Starting single-pass clustering on ${_allFaceInstances.size} faces...")
            val clusteredPeople: List<Person> = withContext(Dispatchers.Default) {
                personClusterer.clusterFaces(_allFaceInstances, uri.toString())
            }

            _state.update {
                it.copy(
                    isProcessing = false,
                    progress = 1f,
                    isDone = true,
                    persons = clusteredPeople
                )
            }
            Log.d(
                TAG,
                "Processing & Clustering complete! frames=${_state.value.framesProcessed} " +
                        "facesWithEmbeddings=${_allFaceInstances.size} uniquePeople=${clusteredPeople.size}"
            )
        }
    }

    override fun onCleared() {
        super.onCleared()
        faceDetector.close()
        faceEmbedder.close()
    }
}
