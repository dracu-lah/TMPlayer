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
     * Rotate picture (#34): the picture turned a quarter at a time as it is drawn, a page of the
     * four turns ([PictureRotation]). Not the screen orientation. Only for a picture, not a cast.
     */
    RotatePicture,
    /**
     * 3D video: a page of how the file keeps its two pictures and which glasses are on
     * ([Stereo3d]). Not while the video plays on a cast.
     */
    Stereo,
    /** The speeds as a page, the one playing on the line; the row's button opens the same page. */
    Speed,
    /**
     * Player volume (#33): the player's own gain under the device's, 1 per cent a step
     * ([PlayerVolume]). Left and Right step it on the line itself; OK opens its page. Not on a cast.
     */
    PlayerVolume,
    /** Louder: quiet dialogue lifted, the peaks kept from clipping. On or off, and the panel stays. */
    VolumeBoost,
    /** Night mode: the range squeezed, so loud scenes are quieter and dialogue clearer. */
    NightMode,
    /**
     * Only while Dolby or DTS sound goes to the speakers as it is, so Volume boost and Night mode
     * cannot reach it (WP-24): the sound is rebuilt as PCM for this video, the ladder's own
     * passthrough-off step taken as the viewer's press.
     */
    PassthroughOff,
    /** Even out volume: a quiet track lifted to the level of other videos. On or off. */
    EvenVolume,
    /** A page of lengths, and the time left on one that is running. */
    SleepTimer,
    /** A-B repeat: set the start, then the end, then stop; the line says which comes next. */
    AbLoop,
    /** Repeat this video (EG-F52): a switch; off again when another video starts, and not with the A-B repeat. */
    RepeatVideo,
    /**
     * The chapters as a page (WP-26): each with its name and start, the one playing ticked, OK
     * jumps. Only for a file with two or more ([Chapters.listable]).
     */
    Chapters,
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
    /** Two presses: where the intro starts, then where it ends; saved here and used as a guess for the season. Episodes only. */
    MarkIntro,
    /** Takes the viewer's intro mark off this video; only while it has one. */
    RemoveIntroMark,
    SaveToDownloads,
    /** "Mark as watched", or "Mark as unwatched" once it is on the list. */
    MarkWatched,
    /**
     * "Add to a list" (HX43), as in a video's hold menu: a page of the panel with every named list
     * (ticked where the video is, a press toggles) and New list. Only for a video from a message.
     */
    AddToList,
    PictureInPicture,
    /**
     * "Try the other decoder" while the video plays (WP-25): a picture with green or choppy frames
     * and no error moves to the device's decoder or the app's FFmpeg one, at the same place with
     * the same tracks. Only when the other side decodes the picture's format ([DecoderSwitch.planForPicture]).
     */
    Decoder,
    /**
     * The same video in the other engine (PR2), at the same place with the same tracks: ExoPlayer
     * to VLC or back. Only where the VLC library loads, never for 3D or a cast.
     */
    OtherEngine,
    OpenInAnotherApp,
    /** The video's Telegram link with the time playing (U9); not for a protected chat or a video of pieces. */
    CopyLinkAtTime,
    /**
     * Load a subtitle file (WP-81), through the system document picker. Only where a picker is installed
     * ([documentPickerAvailable]): most sticks have none, and a line that opens nothing is not shown.
     */
    LoadSubtitleFile,
    /**
     * Marks the moment as stuttering and keeps the last minute of the playback trace for Send logs
     * (L3). Also a held Info key on the remote. Not offered on a cast, which plays on the other TV.
     */
    ReportStutter,

    /**
     * "Picture looks wrong" (RL10): marks the moment like [ReportStutter] and tries another way to
     * show the picture, once. Offered and hidden with it.
     */
    ReportWrongPicture,
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
    /** AirPlay's list of screens (iOS only, WP-31): for a file of Apple's player's; the line says why not for mpv's. */
    AirPlay,
    /**
     * Every shape as a page (HX16): the row's button (on a phone held sideways) steps them a press
     * at a time and the pinch only the first three, so this is where any one is a tap away.
     */
    PictureShape,
    /**
     * Only while the picture is pinched larger than its shape: back to 100 per cent in one tap,
     * without a gesture (the zoom chip's tap does the same), so TalkBack reaches it too.
     */
    ResetZoom,
    /** Rotate picture (#34), as on the television: a page of the four turns. */
    RotatePicture,
    /** 3D video, as on the television: a page of the layout, the glasses and the eye swap. */
    Stereo,
    /** The speeds as a page, the one playing on the line, as the row's button opens them. */
    Speed,
    /** Player volume (#33), as on the television: a page with Quieter and Louder. */
    PlayerVolume,
    /** Brightness (WP-27), a phone's: a page of steps and Follow the system; the television has no window brightness. */
    Brightness,
    VolumeBoost,
    NightMode,
    /** Only while the sound plays as a Dolby or DTS bitstream, as on the television (WP-24). */
    PassthroughOff,
    EvenVolume,
    SleepTimer,
    /** A-B repeat, as on the television: one line that says what the next press does. */
    AbLoop,
    /** Repeat this video, as on the television: a switch that leaves the panel up. */
    RepeatVideo,
    /** The chapters as a page, as on the television: only for a file with two or more. */
    Chapters,
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
    /** Two presses: where the intro starts, then where it ends, as on the television. */
    MarkIntro,
    RemoveIntroMark,
    /** The other decoder while the video plays (WP-25), as on the television. */
    Decoder,
    /** The other engine, as on the television. */
    OtherEngine,
    OpenInAnotherApp,
    /** The video's Telegram link with the time playing (U9), as the television's. */
    CopyLinkAtTime,
    LoadSubtitleFile,
    SaveToDownloads,
    MarkWatched,
    /** "Add to a list" (HX43), as on the television: a page of the lists, ticked where the video is. */
    AddToList,
    /** The moment marked as stuttering, with the last minute of the trace kept for Send logs (L3). */
    ReportStutter,
    /** "Picture looks wrong" (RL10): the mark, and another way to show the picture, once. */
    ReportWrongPicture,
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
    introMarking: Boolean = false,
): String = when (this) {
    PhoneMenuEntry.LockScreen -> L.playerLockScreen
    PhoneMenuEntry.PictureInPicture -> L.playerPictureInPicture
    PhoneMenuEntry.AirPlay -> L.playerAirplay
    PhoneMenuEntry.PictureShape -> shape?.let { L.commonWithDetail(L.playerPictureShape, it.label) } ?: L.playerPictureShape
    PhoneMenuEntry.ResetZoom -> L.playerResetZoom
    PhoneMenuEntry.Stereo -> stereo?.let { L.commonWithDetail(L.playerStereo, it) } ?: L.playerStereo
    PhoneMenuEntry.Speed -> L.playerSpeed
    PhoneMenuEntry.RotatePicture -> L.playerRotatePicture
    PhoneMenuEntry.PlayerVolume -> L.playerVolumeLevel
    PhoneMenuEntry.Brightness -> L.playerBrightness
    PhoneMenuEntry.VolumeBoost -> L.playerVolumeBoost
    PhoneMenuEntry.NightMode -> L.playerQuietLoudScenes
    PhoneMenuEntry.PassthroughOff -> L.playerPassthroughOffOnce
    PhoneMenuEntry.EvenVolume -> L.playerEvenVolume
    PhoneMenuEntry.SleepTimer -> sleepDetail?.let { L.playerSleepTimerNow(it) } ?: L.playerSleepTimer
    PhoneMenuEntry.AbLoop -> PlayerMenu.loopLabel(loop)
    PhoneMenuEntry.RepeatVideo -> L.playerRepeatVideo
    PhoneMenuEntry.Chapters -> L.playerChapters
    PhoneMenuEntry.NextChapter -> L.playerChapterNext
    PhoneMenuEntry.PreviousChapter -> L.playerChapterPrevious
    PhoneMenuEntry.NextFrame -> L.playerFrameNext
    PhoneMenuEntry.PreviousFrame -> L.playerFramePrevious
    PhoneMenuEntry.SaveFrame -> L.playerSaveFrame
    PhoneMenuEntry.Decoder -> L.playerDecoderOther
    PhoneMenuEntry.OtherEngine -> L.playerEngineOther
    PhoneMenuEntry.OpenInAnotherApp -> L.playerOpenInAnotherApp
    PhoneMenuEntry.CopyLinkAtTime -> L.playerCopyLinkAtTime
    PhoneMenuEntry.LoadSubtitleFile -> L.playerLoadSubtitleFile
    PhoneMenuEntry.SaveToDownloads -> L.playerSaveToDownloads
    PhoneMenuEntry.MarkWatched -> if (watched) L.playerMarkUnwatched else L.playerMarkWatched
    PhoneMenuEntry.AddToList -> L.listsAddTo
    PhoneMenuEntry.AddBookmark -> L.playerAddBookmark
    PhoneMenuEntry.MarkIntro -> if (introMarking) L.playerMarkIntroEnd else L.playerMarkIntro
    PhoneMenuEntry.RemoveIntroMark -> L.playerMarkIntroRemove
    PhoneMenuEntry.Bookmarks -> if (bookmarks > 0) L.commonWithDetail(L.playerBookmarks, L.messages.formatter.number(bookmarks)) else L.playerBookmarks
    PhoneMenuEntry.ReportStutter -> L.playerReportStutter
    PhoneMenuEntry.ReportWrongPicture -> L.playerReportWrongPicture
    PhoneMenuEntry.PlaybackDetails -> L.playerPlaybackDetails
}

/**
 * The television More menu's groups (WP-20), in the order they are drawn. [Top] has no header; the
 * others have one in the panel, which is not focusable, so Page Up and Page Down jump over them.
 */
enum class PlayerMenuGroup { Top, Picture, Sound, Playback, Library, Help }

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
     * [chapters] is whether the file has chapter marks, which adds the chapter steps; [chapterList]
     * is whether it has enough of them for a page of the chapters (WP-26, [Chapters.listable]).
     *
     * [stereo] is whether 3D video is offered: not while a cast plays it, and
     * [pictureShape] the same for the shapes' page. [reportStutter] is false on a cast. [copyLinkAtTime] is false for a chat that restricts saving, a video with no message and one built from pieces.
     *
     * [otherEngine] is whether the video can move to the other engine (PR2): a build whose VLC
     * library loads here, a picture that is not 3D, no cast. It sits just above Open in another
     * app, the other way to a different player.
     *
     * [decoder] is whether the picture can move to the other decoder while it plays (WP-25,
     * [DecoderSwitch.planForPicture]): ExoPlayer's picture, no cast, the other side able to decode
     * it. It sits just above the other engine, the next step out.
     *
     * [markIntro] is whether the video is an episode of a show, which has a season to share a
     * mark with; [removeIntroMark] whether this episode has a mark of its own to take off.
     *
     * [rotatePicture] adds Rotate picture under the shapes (a picture, not a cast), and
     * [playerVolume] adds Player volume above Volume boost (the sound plays here, not on a cast).
     *
     * [loadSubtitleFile] is whether a document picker is installed ([documentPickerAvailable]): the
     * line is left out where there is none, so no press opens nothing.
     *
     * [addToList] is whether the video came from a message, which a named list points at (the
     * same gate as [markWatched]); a list keeps a name, never a copy, so a chat that restricts
     * saving may be in one too, as the card menu has it.
     */
    fun tvEntries(
        pictureInPicture: Boolean,
        saveToDownloads: Boolean = false,
        markWatched: Boolean = false,
        openInAnotherApp: Boolean = true,
        paused: Boolean = false,
        bookmarks: Boolean = false,
        chapters: Boolean = false,
        chapterList: Boolean = false,
        stereo: Boolean = false,
        pictureShape: Boolean = false,
        reportStutter: Boolean = false,
        copyLinkAtTime: Boolean = false,
        otherEngine: Boolean = false,
        repeatVideo: Boolean = false,
        markIntro: Boolean = false,
        removeIntroMark: Boolean = false,
        rotatePicture: Boolean = false,
        playerVolume: Boolean = false,
        addToList: Boolean = false,
        decoder: Boolean = false,
        loadSubtitleFile: Boolean = false,
        passthroughOff: Boolean = false,
    ): List<PlayerMenuEntry> = buildList {
        // WP-20: Lock first, then the frequent lines under no header, then the groups. Every line
        // is added under its own gate, so a group a device leaves empty is never drawn.
        add(PlayerMenuEntry.LockScreen)
        add(PlayerMenuEntry.Speed)
        if (addToList) add(PlayerMenuEntry.AddToList)
        if (markWatched) add(PlayerMenuEntry.MarkWatched)
        add(PlayerMenuEntry.SleepTimer)
        // Picture
        if (pictureShape) add(PlayerMenuEntry.PictureShape)
        if (rotatePicture) add(PlayerMenuEntry.RotatePicture)
        if (stereo) add(PlayerMenuEntry.Stereo)
        if (pictureInPicture) add(PlayerMenuEntry.PictureInPicture)
        // Sound
        if (playerVolume) add(PlayerMenuEntry.PlayerVolume)
        add(PlayerMenuEntry.VolumeBoost)
        add(PlayerMenuEntry.NightMode)
        if (passthroughOff) add(PlayerMenuEntry.PassthroughOff)
        add(PlayerMenuEntry.EvenVolume)
        // Playback
        add(PlayerMenuEntry.AbLoop)
        if (repeatVideo) add(PlayerMenuEntry.RepeatVideo)
        if (chapterList) add(PlayerMenuEntry.Chapters)
        if (chapters) {
            add(PlayerMenuEntry.NextChapter)
            add(PlayerMenuEntry.PreviousChapter)
        }
        if (paused) {
            add(PlayerMenuEntry.NextFrame)
            add(PlayerMenuEntry.PreviousFrame)
        }
        if (decoder) add(PlayerMenuEntry.Decoder)
        if (otherEngine) add(PlayerMenuEntry.OtherEngine)
        add(PlayerMenuEntry.StartOver)
        if (loadSubtitleFile) add(PlayerMenuEntry.LoadSubtitleFile)
        if (bookmarks) {
            add(PlayerMenuEntry.AddBookmark)
            add(PlayerMenuEntry.Bookmarks)
        }
        if (markIntro) add(PlayerMenuEntry.MarkIntro)
        if (removeIntroMark) add(PlayerMenuEntry.RemoveIntroMark)
        // Library
        if (saveToDownloads) add(PlayerMenuEntry.SaveToDownloads)
        if (openInAnotherApp) add(PlayerMenuEntry.OpenInAnotherApp)
        if (copyLinkAtTime) add(PlayerMenuEntry.CopyLinkAtTime)
        // Help: Remote keys always lives here, whatever the hint state (designer #29).
        add(PlayerMenuEntry.PlaybackDetails)
        if (reportStutter) {
            add(PlayerMenuEntry.ReportStutter)
            add(PlayerMenuEntry.ReportWrongPicture)
        }
        add(PlayerMenuEntry.RemoteKeys)
    }

    /** The group a television line belongs to (WP-20). The lock and the frequent lines share [PlayerMenuGroup.Top]. */
    fun groupOf(entry: PlayerMenuEntry): PlayerMenuGroup = when (entry) {
        PlayerMenuEntry.LockScreen, PlayerMenuEntry.Speed, PlayerMenuEntry.AddToList,
        PlayerMenuEntry.MarkWatched, PlayerMenuEntry.SleepTimer,
        -> PlayerMenuGroup.Top
        PlayerMenuEntry.PictureShape, PlayerMenuEntry.RotatePicture, PlayerMenuEntry.Stereo,
        PlayerMenuEntry.PictureInPicture,
        -> PlayerMenuGroup.Picture
        PlayerMenuEntry.PlayerVolume, PlayerMenuEntry.VolumeBoost, PlayerMenuEntry.NightMode,
        PlayerMenuEntry.PassthroughOff, PlayerMenuEntry.EvenVolume,
        -> PlayerMenuGroup.Sound
        PlayerMenuEntry.RepeatVideo, PlayerMenuEntry.AbLoop, PlayerMenuEntry.Chapters, PlayerMenuEntry.NextChapter,
        PlayerMenuEntry.PreviousChapter, PlayerMenuEntry.NextFrame, PlayerMenuEntry.PreviousFrame,
        PlayerMenuEntry.Decoder, PlayerMenuEntry.OtherEngine, PlayerMenuEntry.StartOver, PlayerMenuEntry.AddBookmark,
        PlayerMenuEntry.Bookmarks, PlayerMenuEntry.MarkIntro, PlayerMenuEntry.RemoveIntroMark,
        PlayerMenuEntry.LoadSubtitleFile,
        -> PlayerMenuGroup.Playback
        PlayerMenuEntry.SaveToDownloads, PlayerMenuEntry.OpenInAnotherApp, PlayerMenuEntry.CopyLinkAtTime,
        -> PlayerMenuGroup.Library
        PlayerMenuEntry.PlaybackDetails, PlayerMenuEntry.ReportStutter, PlayerMenuEntry.ReportWrongPicture,
        PlayerMenuEntry.RemoteKeys,
        -> PlayerMenuGroup.Help
    }

    /**
     * Where Page Down ([forward]) or Page Up goes from the line at [from], as an index into
     * [groups] (the group of each line, in order): Page Down is the first line of the next group,
     * Page Up the first line of this group, or of the one before when already on it. Null when there
     * is nowhere to go (the last group down, the very first line up).
     */
    fun pageTarget(groups: List<PlayerMenuGroup>, from: Int, forward: Boolean): Int? {
        if (from !in groups.indices) return null
        if (forward) return (from + 1 until groups.size).firstOrNull { groups[it] != groups[from] }
        var first = from
        while (first > 0 && groups[first - 1] == groups[from]) first--
        if (first != from) return first
        if (first == 0) return null
        var before = first - 1
        while (before > 0 && groups[before - 1] == groups[before]) before--
        return before
    }

    /**
     * The phone's overflow, in the order it draws. [pictureInPicture] is whether the device can do
     * it; [openInAnotherApp] false for a chat that restricts saving; [markWatched]
     * needs a message to point at, and so does [bookmarks]; [saveToDownloads] only while the video
     * can still become one; [paused] adds the frame steps. Lines rather than buttons, so the row
     * CP37 cleared stays clear. [chapters] adds the chapter steps and [chapterList] the page of them. [saveFrame] is false for a
     * chat that restricts saving (or while that is still being asked), whose frames stay in it.
     * [pictureShape] is whether there is a picture to shape (not a cast), and
     * [stereo] offers 3D video beside it; Speed follows them. [reportStutter] adds Report stutter
     * (false on a cast, which plays on the other screen). [copyLinkAtTime] is false for a chat that
     * restricts saving, a video with no message and one built from pieces. [zoomed] adds Reset zoom under the
     * shape while a pinch has the picture enlarged. [otherEngine] adds Try the other engine, as on
     * the television, and [decoder] Try the other decoder above it (WP-25). [repeatVideo] adds Repeat this video under the A-B repeat (a video, not sound
     * alone, which has its own repeat in the list). [addToList] offers "Add to a list" under Mark as
     * watched (a video from a message; see [tvEntries]). [airPlay] is iOS's AirPlay line, after
     * picture in picture (never on Android; a chat that restricts saving leaves it out). The lock stays first: the line people come here for.
     */
    fun phoneEntries(
        pictureInPicture: Boolean,
        openInAnotherApp: Boolean,
        saveToDownloads: Boolean,
        markWatched: Boolean,
        paused: Boolean = false,
        bookmarks: Boolean = false,
        chapters: Boolean = false,
        chapterList: Boolean = false,
        saveFrame: Boolean = false,
        pictureShape: Boolean = false,
        stereo: Boolean = false,
        zoomed: Boolean = false,
        reportStutter: Boolean = false,
        copyLinkAtTime: Boolean = false,
        otherEngine: Boolean = false,
        repeatVideo: Boolean = false,
        markIntro: Boolean = false,
        removeIntroMark: Boolean = false,
        rotatePicture: Boolean = false,
        playerVolume: Boolean = false,
        brightness: Boolean = false,
        addToList: Boolean = false,
        airPlay: Boolean = false,
        decoder: Boolean = false,
        passthroughOff: Boolean = false,
    ): List<PhoneMenuEntry> = buildList {
        add(PhoneMenuEntry.LockScreen)
        if (pictureInPicture) add(PhoneMenuEntry.PictureInPicture)
        if (airPlay) add(PhoneMenuEntry.AirPlay)
        if (pictureShape) add(PhoneMenuEntry.PictureShape)
        if (pictureShape && zoomed) add(PhoneMenuEntry.ResetZoom)
        if (rotatePicture) add(PhoneMenuEntry.RotatePicture)
        if (stereo) add(PhoneMenuEntry.Stereo)
        add(PhoneMenuEntry.Speed)
        if (playerVolume) add(PhoneMenuEntry.PlayerVolume)
        if (brightness) add(PhoneMenuEntry.Brightness)
        add(PhoneMenuEntry.VolumeBoost)
        add(PhoneMenuEntry.NightMode)
        if (passthroughOff) add(PhoneMenuEntry.PassthroughOff)
        add(PhoneMenuEntry.EvenVolume)
        add(PhoneMenuEntry.SleepTimer)
        add(PhoneMenuEntry.AbLoop)
        if (repeatVideo) add(PhoneMenuEntry.RepeatVideo)
        if (chapterList) add(PhoneMenuEntry.Chapters)
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
        if (markIntro) add(PhoneMenuEntry.MarkIntro)
        if (removeIntroMark) add(PhoneMenuEntry.RemoveIntroMark)
        if (decoder) add(PhoneMenuEntry.Decoder)
        if (otherEngine) add(PhoneMenuEntry.OtherEngine)
        if (openInAnotherApp) add(PhoneMenuEntry.OpenInAnotherApp)
        if (copyLinkAtTime) add(PhoneMenuEntry.CopyLinkAtTime)
        add(PhoneMenuEntry.LoadSubtitleFile)
        if (saveToDownloads) add(PhoneMenuEntry.SaveToDownloads)
        if (markWatched) add(PhoneMenuEntry.MarkWatched)
        if (addToList) add(PhoneMenuEntry.AddToList)
        if (reportStutter) {
            add(PhoneMenuEntry.ReportStutter)
            add(PhoneMenuEntry.ReportWrongPicture)
        }
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
        L.playerKeyPage to L.playerKeyPageDoes,
        L.playerKeyDigits to L.playerKeyDigitsDoes,
        L.playerKeyUpJump to L.playerKeyUpJumpDoes,
        L.playerKeyRewind to L.playerKeyRewindDoes,
        L.playerKeyNext to L.playerKeyNextDoes,
        L.playerKeyCc to L.playerKeyCcDoes,
        L.playerKeyHoldOk to L.playerKeyHoldOkDoes,
        L.playerKeyHoldOkLocked to L.playerKeyHoldOkLockedDoes,
        L.playerKeyBack to L.playerKeyBackDoes,
    )
}

/**
 * The detail line of Volume boost or Night mode (WP-24): off, "Not applied" while the sound goes to
 * the speakers as a Dolby or DTS bitstream (neither can touch it), else the line saying what it does.
 */
internal fun soundDetail(on: Boolean, onText: String, bypassed: Boolean, notApplied: String, off: String): String =
    when {
        !on -> off
        bypassed -> notApplied
        else -> onText
    }
