package com.example.ensemble.domain

// Generic Result type
sealed interface Result<out D, out E : Error> {
    data class Success<out D>(val data: D) : Result<D, Nothing>
    data class Error<out E : com.example.ensemble.domain.Error>(val error: E) : Result<Nothing, E>
}

fun <D, E : Error> Result<D, E>.onSuccess(block: (D) -> Unit): Result<D, E> {
    if (this is Result.Success) block(data)
    return this
}

fun <D, E : Error> Result<D, E>.onFailure(block: (E) -> Unit): Result<D, E> {
    if (this is Result.Error) block(error)
    return this
}

fun <D, E : Error, R> Result<D, E>.map(transform: (D) -> R): Result<R, E> {
    return when (this) {
        is Result.Success -> Result.Success(transform(data))
        is Result.Error -> this
    }
}
