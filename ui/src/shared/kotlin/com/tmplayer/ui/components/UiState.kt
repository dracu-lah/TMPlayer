package com.tmplayer.ui.components

/** Every screen is exactly one of these: no blank frames, ever. */
sealed interface UiState<out T> {
    /**
     * @param tip one line under the spinner for a wait the viewer has not seen before, such as the
     *   very first chat list, which takes far longer than every launch after it.
     */
    data class Loading(val label: String = "Loading…", val tip: String? = null) : UiState<Nothing>

    /** Something actually failed and retrying might help. */
    data class Error(val message: String) : UiState<Nothing>

    /**
     * The request worked; there is simply nothing to show.
     *
     * Kept apart from [Error] because "That didn't work / Try again" over an empty list is both
     * untrue and alarming; a chat with no videos in it is a normal answer, not a failure.
     *
     * @param action the one thing worth doing about it, drawn as a button under the message.
     */
    data class Empty(val message: String, val action: StateAction? = null) : UiState<Nothing>

    data class Content<T>(val value: T) : UiState<T>
}

/**
 * A button an empty state can offer. An enum rather than a lambda, so a state stays a value that
 * compares equal to itself, and each screen decides what the button does.
 */
enum class StateAction(val label: String) {
    /** Lift the size limits for this listing, so the videos they hid come into view. */
    ShowHidden("Show them"),

    /** Ask again: the scaffold's ordinary retry. */
    KeepLooking("Keep looking"),
}

/** What every platform says when a load runs on for too long, and when it starts saying it. */
object SlowAnswer {
    /**
     * Long enough that an ordinary first page never sees it, short enough that a viewer staring
     * at a spinner gets a way out before they give up on the app.
     */
    const val AFTER_MS = 15_000L
    const val MESSAGE = "Telegram is slow to answer"
    const val RETRY = "Retry"
}
