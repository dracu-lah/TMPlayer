package com.tmplayer.ui.components

/** Every screen is exactly one of these: no blank frames, ever. */
sealed interface UiState<out T> {
    data class Loading(val label: String = "Loading…") : UiState<Nothing>

    /** Something actually failed and retrying might help. */
    data class Error(val message: String) : UiState<Nothing>

    /**
     * The request worked; there is simply nothing to show.
     *
     * Kept apart from [Error] because "That didn't work / Try again" over an empty list is both
     * untrue and alarming; a chat with no videos in it is a normal answer, not a failure.
     */
    data class Empty(val message: String) : UiState<Nothing>

    data class Content<T>(val value: T) : UiState<T>
}
