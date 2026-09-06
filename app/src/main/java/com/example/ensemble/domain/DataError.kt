package com.example.ensemble.domain

sealed interface Error

sealed interface DataError : Error {
    enum class Local : DataError {
        UNSUPPORTED_VIDEO,
        NO_FACES_FOUND,
        FILE_NOT_FOUND,
        OUT_OF_MEMORY,
        UNKNOWN
    }

    enum class Processing : DataError {
        MODEL_LOAD_FAILED,
        INFERENCE_FAILED,
        FACE_ALIGNMENT_FAILED,
        UNKNOWN
    }
}
