package com.tmplayer.player

import com.tmplayer.i18n.L

/**
 * One line of the television player's More menu.
 *
 * The phone has had these behind its overflow button for a while; the television's row had no
 * overflow at all, so everything here was either a held key away or missing. Kept as plain values
 * rather than lambdas so which lines appear, and in what order, can be tested without a screen.
 */
enum class PlayerMenuEntry {
    // The episode steps are not here: the row's Episodes button opens the episode list, with the
    // previous and next episode at its top, and the remote's media keys step as well.
    PlaybackDetails,
    StartOver,
    /** Night mode: quiet dialogue lifted, loud scenes held back. On or off, and the menu closes. */
    VolumeBoost,
    /** A page of lengths, and the time left on one that is running. */
    SleepTimer,
    SaveToDownloads,
    /** "Mark as watched", or "Mark as unwatched" once it is on the list. */
    MarkWatched,
    /** Fit, crop or stretch: a page of the three. Its only home on the television, the row has no button for it. */
    PictureShape,
    PictureInPicture,
    OpenInAnotherApp,
    RemoteKeys,
}

/**
 * One line of the phone's overflow menu.
 *
 * Speed is not here: the bottom row's button opens the same sheet. Start over is not here either:
 * the resume notice carries it for the first seconds, and the bar reaches zero.
 */
enum class PhoneMenuEntry {
    LockScreen,
    PictureInPicture,
    VolumeBoost,
    SleepTimer,
    OpenInAnotherApp,
    LoadSubtitleFile,
    SaveToDownloads,
    MarkWatched,
    /** Last: a line for bug reports, not for watching. */
    PlaybackDetails,
}

/**
 * The line's text. [sleepDetail] is a running sleep timer's time left, which the line carries;
 * [watched] turns Mark as watched into Mark as unwatched.
 */
fun PhoneMenuEntry.label(sleepDetail: String? = null, watched: Boolean = false): String = when (this) {
    PhoneMenuEntry.LockScreen -> L.playerLockScreen
    PhoneMenuEntry.PictureInPicture -> L.playerPictureInPicture
    PhoneMenuEntry.VolumeBoost -> L.playerVolumeBoost
    PhoneMenuEntry.SleepTimer -> sleepDetail?.let { L.playerSleepTimerNow(it) } ?: L.playerSleepTimer
    PhoneMenuEntry.OpenInAnotherApp -> L.playerOpenInAnotherApp
    PhoneMenuEntry.LoadSubtitleFile -> L.playerLoadSubtitleFile
    PhoneMenuEntry.SaveToDownloads -> L.playerSaveToDownloads
    PhoneMenuEntry.MarkWatched -> if (watched) L.playerMarkUnwatched else L.playerMarkWatched
    PhoneMenuEntry.PlaybackDetails -> L.playerPlaybackDetails
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
     */
    fun tvEntries(
        pictureInPicture: Boolean,
        saveToDownloads: Boolean = false,
        markWatched: Boolean = false,
        openInAnotherApp: Boolean = true,
    ): List<PlayerMenuEntry> = buildList {
        add(PlayerMenuEntry.PlaybackDetails)
        add(PlayerMenuEntry.StartOver)
        add(PlayerMenuEntry.VolumeBoost)
        add(PlayerMenuEntry.SleepTimer)
        if (saveToDownloads) add(PlayerMenuEntry.SaveToDownloads)
        if (markWatched) add(PlayerMenuEntry.MarkWatched)
        add(PlayerMenuEntry.PictureShape)
        if (pictureInPicture) add(PlayerMenuEntry.PictureInPicture)
        if (openInAnotherApp) add(PlayerMenuEntry.OpenInAnotherApp)
        add(PlayerMenuEntry.RemoteKeys)
    }

    /**
     * The phone's overflow, in the order it draws. [pictureInPicture] is whether the device can do
     * it; [openInAnotherApp] false for a chat that restricts saving; [markWatched]
     * needs a message to point at; [saveToDownloads] only while the video can still become one.
     */
    fun phoneEntries(
        pictureInPicture: Boolean,
        openInAnotherApp: Boolean,
        saveToDownloads: Boolean,
        markWatched: Boolean,
    ): List<PhoneMenuEntry> = buildList {
        add(PhoneMenuEntry.LockScreen)
        if (pictureInPicture) add(PhoneMenuEntry.PictureInPicture)
        add(PhoneMenuEntry.VolumeBoost)
        add(PhoneMenuEntry.SleepTimer)
        if (openInAnotherApp) add(PhoneMenuEntry.OpenInAnotherApp)
        add(PhoneMenuEntry.LoadSubtitleFile)
        if (saveToDownloads) add(PhoneMenuEntry.SaveToDownloads)
        if (markWatched) add(PhoneMenuEntry.MarkWatched)
        add(PhoneMenuEntry.PlaybackDetails)
    }
}

/**
 * The remote's keys and what each does in the player, for the More menu's Remote keys sheet.
 *
 * The same table INSTALL.md prints under "Using the remote", so somebody who never read the
 * install guide can still find out that OK twice pauses, and a change to one is a change to both.
 */
object RemoteKeys {
    val ROWS: List<Pair<String, String>> get() = listOf(
        L.playerKeyLeftRight to L.playerKeyLeftRightDoes,
        L.playerKeyOk to L.playerKeyOkDoes,
        L.playerKeyPlayPause to L.playerKeyPlayPauseDoes,
        L.playerKeySeekBar to L.playerKeySeekBarDoes,
        L.playerKeyDown to L.playerKeyDownDoes,
        L.playerKeyTime to L.playerKeyTimeDoes,
        L.playerKeyDigits to L.playerKeyDigitsDoes,
        L.playerKeyRewind to L.playerKeyRewindDoes,
        L.playerKeyNext to L.playerKeyNextDoes,
        L.playerKeyHoldOk to L.playerKeyHoldOkDoes,
        L.playerKeyBack to L.playerKeyBackDoes,
    )
}
