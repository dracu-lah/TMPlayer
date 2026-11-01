package com.tmplayer.ui.components

import androidx.compose.ui.graphics.vector.ImageVector
import com.tmplayer.i18n.L
import com.tmplayer.ui.icons.TmIcons

/** Every screen is exactly one of these: no blank frames, ever. */
sealed interface UiState<out T> {
    /**
     * @param tip one line under the spinner for a wait the viewer has not seen before, such as the
     *   very first chat list, which takes far longer than every launch after it.
     */
    data class Loading(val label: String = L.commonLoading, val tip: String? = null) : UiState<Nothing>

    /** Something actually failed and retrying might help. */
    data class Error(val message: String) : UiState<Nothing>

    /**
     * The request worked; there is simply nothing to show.
     *
     * Kept apart from [Error] because "That didn't work / Try again" over an empty list is both
     * untrue and alarming; a chat with no videos in it is a normal answer, not a failure.
     *
     * @param action the one thing worth doing about it, drawn as a button under the message.
     * @param icon the picture over the message ([EmptyArt]), named by whatever knows why the list is
     *   empty; [picture] picks one from the action when it says nothing.
     */
    data class Empty(
        val message: String,
        val action: StateAction? = null,
        val icon: ImageVector? = null,
    ) : UiState<Nothing> {
        /** The picture to draw: the state's own, or the one its action stands for. */
        val picture: ImageVector get() = icon ?: when (action) {
            StateAction.KeepLooking -> TmIcons.Search
            StateAction.SearchAllChats -> TmIcons.SearchRemove
            StateAction.Refresh -> TmIcons.Refresh
            StateAction.AddChats -> TmIcons.ChatsMany
            null -> TmIcons.VideoOff
        }
    }

    data class Content<T>(val value: T) : UiState<T>
}

/**
 * A button an empty state can offer. An enum rather than a lambda, so a state stays a value that
 * compares equal to itself, and each screen decides what the button does.
 */
enum class StateAction {
    /** Ask again: the scaffold's ordinary retry. */
    KeepLooking,

    /**
     * Nothing here matched the search: run the same words across every chat ([com.tmplayer.ui.browse.SearchRequest]),
     * which also finds a chat by its @username.
     */
    SearchAllChats,

    /** Ask Telegram again, for a list that may still be on its way (a first sign in). */
    Refresh,

    /**
     * Open the picker (HX27): the account has chats, but none of them has been added to TMPlayer,
     * so the way on is a choice, not a retry.
     */
    AddChats,
    ;

    /** The button's words. */
    val label: String get() = when (this) {
        KeepLooking -> L.commonKeepLooking
        SearchAllChats -> L.searchAllChats
        Refresh -> L.commonRefresh
        AddChats -> L.pickerChooseChats
    }
}

/** What every platform says when a load runs on for too long, and when it starts saying it. */
object SlowAnswer {
    /**
     * Long enough that an ordinary first page never sees it, short enough that a viewer staring
     * at a spinner gets a way out before they give up on the app.
     */
    const val AFTER_MS = 15_000L
    val MESSAGE: String get() = L.commonSlowAnswer
    val RETRY: String get() = L.commonRetry
}
