package com.sodre90.cmuxremote.ui

/** Minimal load state for screens backed by a single async resource. */
sealed interface UiState<out T> {
    data object Loading : UiState<Nothing>
    data class Error(val message: String) : UiState<Nothing>
    data class Ready<T>(val data: T) : UiState<T>
}
