package com.tmplayer.player

/**
 * One line of the television player's More menu.
 *
 * The phone has had these behind its overflow button for a while; the television's row had no
 * overflow at all, so everything here was either a held key away or missing. Kept as plain values
 * rather than lambdas so which lines appear, and in what order, can be tested without a screen.
 */
enum class PlayerMenuEntry {
    PlaybackDetails,
    StartOver,
    Speed,
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
     * Save to Downloads joins this list when the storage work lands: it belongs between Speed and
     * Picture in picture, as one more `add` guarded by whether the video is a cached one.
     */
    fun tvEntries(pictureInPicture: Boolean): List<PlayerMenuEntry> = buildList {
        add(PlayerMenuEntry.PlaybackDetails)
        add(PlayerMenuEntry.StartOver)
        add(PlayerMenuEntry.Speed)
        // Hook: Save to Downloads goes here.
        if (pictureInPicture) add(PlayerMenuEntry.PictureInPicture)
        add(PlayerMenuEntry.OpenInAnotherApp)
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
        "Left / Right on the seek bar" to "Travel 30 seconds a press; presses pile up and land as " +
            "one seek a moment after the last",
        "Down, controls up" to "The button row: play and pause, jumps, subtitles, audio, speed, " +
            "picture shape, and More at the end",
        "OK on the time" to "Switch between the total length and the time left",
        "0 to 9" to "Jump to that tenth of the video; 5 is halfway",
        "Rewind / Fast forward" to "Jump back 5 or forward 10 seconds",
        "Hold OK, controls up" to "Open the video in another app",
        "Back" to "Close what is on top: the controls, then the player; the position is remembered",
    )
}
