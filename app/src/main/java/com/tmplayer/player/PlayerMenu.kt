package com.tmplayer.player

/**
 * One line of the television player's More menu.
 *
 * The phone has had these behind its overflow button for a while; the television's row had no
 * overflow at all, so everything here was either a held key away or missing. Kept as plain values
 * rather than lambdas so which lines appear, and in what order, can be tested without a screen.
 */
enum class PlayerMenuEntry {
    /**
     * The episode steps. The television's row has no buttons for them, so besides the remote's
     * media keys this is where they live, labelled with the episode they open.
     */
    NextEpisode,
    PreviousEpisode,
    PlaybackDetails,
    StartOver,
    Speed,
    /** Night mode: quiet dialogue lifted, loud scenes held back. On or off, and the menu closes. */
    VolumeBoost,
    /** A page of lengths, and the time left on one that is running. */
    SleepTimer,
    SaveToDownloads,
    /** "Mark as watched", or "Mark as unwatched" once it is on the list. */
    MarkWatched,
    PictureInPicture,
    OpenInAnotherApp,
    RemoteKeys,
}

/** Which lines the More menu offers on this device, in the order it draws them. */
object PlayerMenu {

    /**
     * The television's More menu.
     *
     * [pictureInPicture] is whether the device can do it at all (Android TV has supported it since
     * 8.0, but not every stick ships the feature), because a line that does nothing when chosen is
     * worse than no line.
     *
     * [saveToDownloads] is whether the video can still become a download: not one already in
     * Downloads or in the queue, which would make the line a press that does nothing.
     *
     * [markWatched] is whether the video came from a message: the Watched list is keyed by one,
     * so a file with none has nothing to mark.
     *
     * [openInAnotherApp] is false for a video from a chat that restricts saving content, whose
     * file must not be handed to another app.
     *
     * [nextEpisode] and [previousEpisode] are whether the chat search found a neighbour, and come
     * first: on a series they are what the menu is most often opened for.
     */
    fun tvEntries(
        pictureInPicture: Boolean,
        saveToDownloads: Boolean = false,
        markWatched: Boolean = false,
        openInAnotherApp: Boolean = true,
        nextEpisode: Boolean = false,
        previousEpisode: Boolean = false,
    ): List<PlayerMenuEntry> = buildList {
        if (nextEpisode) add(PlayerMenuEntry.NextEpisode)
        if (previousEpisode) add(PlayerMenuEntry.PreviousEpisode)
        add(PlayerMenuEntry.PlaybackDetails)
        add(PlayerMenuEntry.StartOver)
        add(PlayerMenuEntry.Speed)
        add(PlayerMenuEntry.VolumeBoost)
        add(PlayerMenuEntry.SleepTimer)
        if (saveToDownloads) add(PlayerMenuEntry.SaveToDownloads)
        if (markWatched) add(PlayerMenuEntry.MarkWatched)
        if (pictureInPicture) add(PlayerMenuEntry.PictureInPicture)
        if (openInAnotherApp) add(PlayerMenuEntry.OpenInAnotherApp)
        add(PlayerMenuEntry.RemoteKeys)
    }
}

/**
 * The remote's keys and what each does in the player, for the More menu's Remote keys sheet.
 *
 * The same table INSTALL.md prints under "Using the remote", so somebody who never read the
 * install guide can still find out that OK twice pauses, and a change to one is a change to both.
 */
object RemoteKeys {
    val ROWS: List<Pair<String, String>> = listOf(
        "Left / Right" to "Jump back 5 or forward 10 seconds, right on the picture, nothing else comes up",
        "OK" to "Show the controls with the seek bar focused; on the bar OK is play and pause, " +
            "so OK twice from the bare picture pauses",
        "Play / Pause" to "Play or pause; paused, the controls stay up with a pause sign in the middle",
        "Left / Right on the seek bar" to "Travel 30 seconds a press; presses pile up and land as " +
            "one seek a moment after the last",
        "Down, controls up" to "The button row: subtitles, audio, speed, picture shape, and More at the end",
        "OK on the time" to "Switch between the total length and the time left",
        "0 to 9" to "Jump to that tenth of the video; 5 is halfway",
        "Rewind / Fast forward" to "Jump back 5 or forward 10 seconds",
        "Next / Previous" to "The next or previous episode, when the chat has one; also in More",
        "Hold OK, controls up" to "Open the video in another app",
        "Back" to "Close what is on top: the controls, then the player; the position is remembered",
    )
}
