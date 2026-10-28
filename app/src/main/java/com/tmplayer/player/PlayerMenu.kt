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
    /**
     * First, as on the phone: a child lock for the remote. Every key is ignored until OK is held
     * for a couple of seconds, which a press or a mash of the D-pad does not do by accident.
     */
    LockScreen,
    PlaybackDetails,
    StartOver,
    /**
     * Every shape as a page of the panel (HX16), the eight at a glance: the row's own button steps
     * them one press at a time, which is long way round to 1:1. Only for a picture, not a cast.
     */
    PictureShape,
    /**
     * 3D video: a page of how the file keeps its two pictures and which glasses are on
     * ([Stereo3d]). Not for sound alone, nor while the video plays on a cast.
     */
    Stereo,
    /** The speeds as a page, the one playing on the line; the row's button opens the same page. */
    Speed,
    /** Louder: quiet dialogue lifted, the peaks kept from clipping. On or off, and the panel stays. */
    VolumeBoost,
    /** Night mode: the range squeezed, so loud scenes are quieter and dialogue clearer. */
    NightMode,
    /** Even out volume: a quiet track lifted to the level of other videos. On or off. */
    EvenVolume,
    /** A page of lengths, and the time left on one that is running. */
    SleepTimer,
    /** A-B repeat: set the start, then the end, then stop; the line says which comes next. */
    AbLoop,
    /**
     * A chaptered file only ([MkvChapters]). Lines rather than keys, as the frame steps are: Left
     * and Right on the bare picture jump and on the bar travel, and 1.16.0's key model stays.
     */
    NextChapter,
    PreviousChapter,
    /** Only while paused, and only here: the arrows already seek, so frame steps get no key. */
    NextFrame,
    PreviousFrame,
    /** Saves the moment playing at once; a note can be added from Bookmarks, where typing is optional. */
    AddBookmark,
    /** This video's bookmarks: play from one, give it a note, delete it. */
    Bookmarks,
    SaveToDownloads,
    /** "Mark as watched", or "Mark as unwatched" once it is on the list. */
    MarkWatched,
    PictureInPicture,
    OpenInAnotherApp,
    RemoteKeys,
}

/**
 * One line of the phone's More options panel.
 *
 * Start over is not here: the resume notice carries it for the first seconds, and the bar
 * reaches zero.
 */
enum class PhoneMenuEntry {
    /** First and set apart with its icon: the line people come to the menu for (user, 2026-10-18). */
    LockScreen,
    PictureInPicture,
    /**
     * Every shape as a page (HX16): the row's button (on a phone held sideways) steps them a press
     * at a time and the pinch only the first three, so this is where any one is a tap away.
     */
    PictureShape,
    /** 3D video, as on the television: a page of the layout, the glasses and the eye swap. */
    Stereo,
    /** The speeds as a page, the one playing on the line, as the row's button opens them. */
    Speed,
    VolumeBoost,
    NightMode,
    EvenVolume,
    SleepTimer,
    /** A-B repeat, as on the television: one line that says what the next press does. */
    AbLoop,
    /** A chaptered file only, as on the television. */
    NextChapter,
    PreviousChapter,
    /** Only while paused: a step on a playing video would be lost in the next frame anyway. */
    NextFrame,
    PreviousFrame,
    /**
     * The picture on screen as a PNG in Pictures/TMPlayer, with Share on the answer. Never for a
     * chat that restricts saving, and never on a television, which has nowhere to look at it.
     */
    SaveFrame,
    /** Asks for an optional note, then saves the moment the note was asked at. */
    AddBookmark,
    Bookmarks,
    OpenInAnotherApp,
    LoadSubtitleFile,
    SaveToDownloads,
    MarkWatched,
    /** Last: a line for bug reports, not for watching. */
    PlaybackDetails,
}

/**
 * The line's text. [sleepDetail] is a running sleep timer's time left, which the line carries;
 * [watched] turns Mark as watched into Mark as unwatched; [loop] is the repeat marked so far;
 * [bookmarks] is how many bookmarks the video has.
 */
fun PhoneMenuEntry.label(
    sleepDetail: String? = null,
    watched: Boolean = false,
    loop: AbLoop? = null,
    bookmarks: Int = 0,
    shape: VideoScale? = null,
    stereo: String? = null,
): String = when (this) {
    PhoneMenuEntry.LockScreen -> L.playerLockScreen
    PhoneMenuEntry.PictureInPicture -> L.playerPictureInPicture
    PhoneMenuEntry.PictureShape -> shape?.let { L.commonWithDetail(L.playerPictureShape, it.label) } ?: L.playerPictureShape
    PhoneMenuEntry.Stereo -> stereo?.let { L.commonWithDetail(L.playerStereo, it) } ?: L.playerStereo
    PhoneMenuEntry.Speed -> L.playerSpeed
    PhoneMenuEntry.VolumeBoost -> L.playerVolumeBoost
    PhoneMenuEntry.NightMode -> L.playerNightMode
    PhoneMenuEntry.EvenVolume -> L.playerEvenVolume
    PhoneMenuEntry.SleepTimer -> sleepDetail?.let { L.playerSleepTimerNow(it) } ?: L.playerSleepTimer
    PhoneMenuEntry.AbLoop -> PlayerMenu.loopLabel(loop)
    PhoneMenuEntry.NextChapter -> L.playerChapterNext
    PhoneMenuEntry.PreviousChapter -> L.playerChapterPrevious
    PhoneMenuEntry.NextFrame -> L.playerFrameNext
    PhoneMenuEntry.PreviousFrame -> L.playerFramePrevious
    PhoneMenuEntry.SaveFrame -> L.playerSaveFrame
    PhoneMenuEntry.OpenInAnotherApp -> L.playerOpenInAnotherApp
    PhoneMenuEntry.LoadSubtitleFile -> L.playerLoadSubtitleFile
    PhoneMenuEntry.SaveToDownloads -> L.playerSaveToDownloads
    PhoneMenuEntry.MarkWatched -> if (watched) L.playerMarkUnwatched else L.playerMarkWatched
    PhoneMenuEntry.AddBookmark -> L.playerAddBookmark
    PhoneMenuEntry.Bookmarks -> if (bookmarks > 0) L.commonWithDetail(L.playerBookmarks, L.messages.formatter.number(bookmarks)) else L.playerBookmarks
    PhoneMenuEntry.PlaybackDetails -> L.playerPlaybackDetails
}

/** Which lines the More menu offers on this device, in the order it draws them. */
object PlayerMenu {

    /** The repeat line's text: what choosing it does next, given the repeat marked so far. */
    fun loopLabel(loop: AbLoop?): String = when {
        loop == null -> L.playerLoopSetStart
        loop.complete -> L.playerLoopClear
        else -> L.playerLoopSetEnd
    }

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
     * [paused] adds the frame steps. They live only here on the television: Left and Right on the
     * bare picture jump, and on the bar they travel, so no arrow is free for a frame.
     *
     * [bookmarks] is whether the video can keep bookmarks: like the Watched list, they are keyed
     * by its message. Their lines sit with the repeat and the frame steps, the other ways of
     * working through one moment of a video.
     *
     * [chapters] is whether the file has chapter marks, which adds the chapter steps.
     *
     * [stereo] is whether 3D video is offered: a picture to draw, not sound alone, and
     * [pictureShape] the same for the shapes' page.
     */
    fun tvEntries(
        pictureInPicture: Boolean,
        saveToDownloads: Boolean = false,
        markWatched: Boolean = false,
        openInAnotherApp: Boolean = true,
        paused: Boolean = false,
        bookmarks: Boolean = false,
        chapters: Boolean = false,
        stereo: Boolean = false,
        pictureShape: Boolean = false,
    ): List<PlayerMenuEntry> = buildList {
        add(PlayerMenuEntry.LockScreen)
        add(PlayerMenuEntry.PlaybackDetails)
        add(PlayerMenuEntry.StartOver)
        if (pictureShape) add(PlayerMenuEntry.PictureShape)
        if (stereo) add(PlayerMenuEntry.Stereo)
        add(PlayerMenuEntry.Speed)
        add(PlayerMenuEntry.VolumeBoost)
        add(PlayerMenuEntry.NightMode)
        add(PlayerMenuEntry.EvenVolume)
        add(PlayerMenuEntry.SleepTimer)
        add(PlayerMenuEntry.AbLoop)
        if (chapters) {
            add(PlayerMenuEntry.NextChapter)
            add(PlayerMenuEntry.PreviousChapter)
        }
        if (paused) {
            add(PlayerMenuEntry.NextFrame)
            add(PlayerMenuEntry.PreviousFrame)
        }
        if (bookmarks) {
            add(PlayerMenuEntry.AddBookmark)
            add(PlayerMenuEntry.Bookmarks)
        }
        if (saveToDownloads) add(PlayerMenuEntry.SaveToDownloads)
        if (markWatched) add(PlayerMenuEntry.MarkWatched)
        if (pictureInPicture) add(PlayerMenuEntry.PictureInPicture)
        if (openInAnotherApp) add(PlayerMenuEntry.OpenInAnotherApp)
        add(PlayerMenuEntry.RemoteKeys)
    }

    /**
     * The phone's overflow, in the order it draws. [pictureInPicture] is whether the device can do
     * it; [openInAnotherApp] false for a chat that restricts saving; [markWatched]
     * needs a message to point at, and so does [bookmarks]; [saveToDownloads] only while the video
     * can still become one; [paused] adds the frame steps. Lines rather than buttons, so the row
     * CP37 cleared stays clear. [chapters] adds the chapter steps. [saveFrame] is false for a
     * chat that restricts saving (or while that is still being asked), whose frames stay in it.
     * [pictureShape] is whether there is a picture to shape (not sound alone, not a cast), and
     * [stereo] offers 3D video beside it; Speed follows them. The lock stays first: the line
     * people come here for.
     */
    fun phoneEntries(
        pictureInPicture: Boolean,
        openInAnotherApp: Boolean,
        saveToDownloads: Boolean,
        markWatched: Boolean,
        paused: Boolean = false,
        bookmarks: Boolean = false,
        chapters: Boolean = false,
        saveFrame: Boolean = false,
        pictureShape: Boolean = false,
        stereo: Boolean = false,
    ): List<PhoneMenuEntry> = buildList {
        add(PhoneMenuEntry.LockScreen)
        if (pictureInPicture) add(PhoneMenuEntry.PictureInPicture)
        if (pictureShape) add(PhoneMenuEntry.PictureShape)
        if (stereo) add(PhoneMenuEntry.Stereo)
        add(PhoneMenuEntry.Speed)
        add(PhoneMenuEntry.VolumeBoost)
        add(PhoneMenuEntry.NightMode)
        add(PhoneMenuEntry.EvenVolume)
        add(PhoneMenuEntry.SleepTimer)
        add(PhoneMenuEntry.AbLoop)
        if (chapters) {
            add(PhoneMenuEntry.NextChapter)
            add(PhoneMenuEntry.PreviousChapter)
        }
        if (paused) {
            add(PhoneMenuEntry.NextFrame)
            add(PhoneMenuEntry.PreviousFrame)
        }
        if (saveFrame) add(PhoneMenuEntry.SaveFrame)
        if (bookmarks) {
            add(PhoneMenuEntry.AddBookmark)
            add(PhoneMenuEntry.Bookmarks)
        }
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
        L.playerKeyUpJump to L.playerKeyUpJumpDoes,
        L.playerKeyRewind to L.playerKeyRewindDoes,
        L.playerKeyNext to L.playerKeyNextDoes,
        L.playerKeyHoldOk to L.playerKeyHoldOkDoes,
        L.playerKeyHoldOkLocked to L.playerKeyHoldOkLockedDoes,
        L.playerKeyBack to L.playerKeyBackDoes,
    )
}
