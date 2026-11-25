package com.tmplayer.desktop.player

import com.tmplayer.data.PostStart
import com.tmplayer.data.QualityWish
import com.tmplayer.data.TelegramQualities
import androidx.compose.ui.input.key.Key
import com.tmplayer.i18n.L
import com.tmplayer.i18n.Translator
import com.tmplayer.ui.i18n.LocalStrings
import com.tmplayer.ui.theme.TmMaterialTheme
import com.tmplayer.desktop.os.MediaKeyEcho
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.NonRestartableComposable
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.isCtrlPressed as pointerCtrl
import androidx.compose.ui.input.pointer.isMetaPressed as pointerMeta
import androidx.compose.ui.input.pointer.isShiftPressed as pointerShift
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.platform.LocalWindowInfo
import com.tmplayer.data.MediaItem
import com.tmplayer.online.OnlineSubtitles
import com.tmplayer.online.SubtitleTarget
import com.tmplayer.data.MediaName
import com.tmplayer.data.EpisodeNeighbours
import com.tmplayer.data.SeriesShelf
import com.tmplayer.online.EpisodeNames
import com.tmplayer.online.OnlineMetadata
import com.tmplayer.online.SkipKind
import com.tmplayer.online.SkipSegment
import com.tmplayer.online.SkipController
import com.tmplayer.online.SkipOffer
import com.tmplayer.online.SkipSegments
import com.tmplayer.data.ResumeRecord
import com.tmplayer.data.ResumeRules
import com.tmplayer.data.ResumeState
import com.tmplayer.data.SettingsStore
import com.tmplayer.desktop.os.UserDirs
import com.tmplayer.data.TrackChoice
import com.tmplayer.data.WatchedRecord
import com.tmplayer.data.WatchedStore
import com.tmplayer.data.Bookmark
import com.tmplayer.data.BookmarkJump
import com.tmplayer.data.VideoCopies
import com.tmplayer.data.CopyHandoff
import com.tmplayer.data.BookmarkStore
import com.tmplayer.desktop.DesktopPrefs
import com.tmplayer.desktop.WatchedWords
import com.tmplayer.data.DownloadRunner
import com.tmplayer.data.OfflineDownloads
import com.tmplayer.player.PictureZoom
import com.tmplayer.player.PlaybackSpeed
import com.tmplayer.player.Stereo3d
import com.tmplayer.player.SeekUndo
import com.tmplayer.player.SleepTimer
import com.tmplayer.player.SpeedMemory
import com.tmplayer.player.PauseControls
import com.tmplayer.player.StillWatching
import com.tmplayer.player.SubtitleStyle
import com.tmplayer.player.SyncDelays
import com.tmplayer.player.TouchPrefs
import com.tmplayer.player.TrackPreferences
import com.tmplayer.player.TrackRules
import com.tmplayer.player.VideoScale
import com.tmplayer.platform.Logger
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.openani.mediamp.mpv.compose.MpvMediampPlayerSurface
import java.awt.Point
import java.awt.Toolkit
import java.awt.image.BufferedImage
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp

/** Outlives the screen, so the resume write on the way out survives the Back that caused it. */
private val playerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO + com.tmplayer.platform.JobFailures.handler("PlayerScreen"))

/** What fills the screen besides the video. */
internal sealed interface Phase {
    data class Loading(val message: String) : Phase
    data object Playing : Phase
    /** [noRoom]: refused for space on the drive, which Free up space answers better than Try again. */
    data class Failed(val message: String, val noRoom: Boolean = false, val format: String = "", val details: String = "") : Phase
    data class Countdown(val next: MediaItem, val seconds: Int) : Phase
    data object Finished : Phase

    /**
     * "Still watching?", with playback stopped and the downloads held. [next] is the episode
     * autoplay was about to start, which Keep watching starts instead; [ended] is a video that had
     * already played to the end, where there is nothing to carry on with but the finished sheet.
     */
    data class StillWatching(val why: String, val next: MediaItem?, val ended: Boolean) : Phase
}

/** One flash of feedback over the picture; [id] restarts the animation for a repeat. */
internal data class Flash(val kind: Kind, val text: String, val id: Long) {
    // No play or pause glyph: a pause brings the controls up, and their centre button's own
    // change is the answer to it (see PauseControls).
    enum class Kind { SeekBack, SeekForward, Text, Volume }
}

internal enum class MenuPage { Main, Audio, Subtitles, Speed, Shape, Sleep, Options, SubtitleStyle, Bookmarks, Copies, Stereo, ChatSubtitles, Chapters }

/** Where an open menu hangs: at the cursor for a right click, or under a button. */
internal data class MenuAt(val page: MenuPage, val anchor: Anchor, val at: Offset = Offset.Zero) {
    enum class Anchor { Cursor, Overflow, Subtitles, Audio, Speed }

    /**
     * The right click and the More button open the side panel docked to the window's end edge
     * (HX16), drawn in the player's own window; the bar's subtitle, audio and speed buttons keep
     * their small menus.
     */
    val panel: Boolean get() = anchor == Anchor.Cursor || anchor == Anchor.Overflow

    /**
     * The page Back returns to, or null on the page a button's own menu opens at: that menu has no
     * Back line, a click outside closes it.
     */
    fun parentPage(): MenuPage? = when (page) {
        MenuPage.Main -> null
        MenuPage.Audio -> if (anchor == Anchor.Audio) null else MenuPage.Main
        MenuPage.Subtitles -> if (anchor == Anchor.Subtitles) null else MenuPage.Main
        MenuPage.Speed -> if (anchor == Anchor.Speed) null else MenuPage.Main
        MenuPage.Sleep, MenuPage.Chapters -> MenuPage.Options
        MenuPage.Options, MenuPage.Shape, MenuPage.Bookmarks, MenuPage.Copies, MenuPage.Stereo -> MenuPage.Main
        MenuPage.SubtitleStyle -> if (anchor == Anchor.Subtitles) MenuPage.Subtitles else MenuPage.Main
        MenuPage.ChatSubtitles -> MenuPage.Subtitles
    }
}

private val blankCursor: PointerIcon by lazy {
    // A toolkit without a display (a headless test run, a session without X) cannot draw a custom
    // cursor: the pointer then simply stays as it is instead of taking the player down.
    runCatching {
        PointerIcon(
            Toolkit.getDefaultToolkit().createCustomCursor(
                BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB), Point(0, 0), "blank",
            ),
        )
    }.getOrDefault(PointerIcon.Default)
}

/**
 * The desktop player: the video, the overlay of B2.4, the keyboard of B2.2 and the mouse of B2.3,
 * over any [PlayerMedia] (Telegram in the app, a file on disk in the dev harness).
 *
 * Fullscreen belongs to the window, so the shell passes its state in and flips it through
 * [onToggleFullscreen]; the same goes for always on top, the mini player and quitting, each of
 * which is offered only when the shell passes a callback for it.
 *
 * @param startFromBeginning ignore the saved position for this first video (Start over from the
 *   browse screen). Episodes played after it resume as usual.
 * @param settings the process's one [SettingsStore]; resume points, speed, picture shape and the
 *   per series track choice are read from and written to it exactly as on Android.
 * @param watched the Watched list: a video played to the end goes on it, and the menu marks or
 *   unmarks the one playing. Null (the UI tests) leaves both out.
 * @param bookmarks the viewer's bookmarks: B and the menu add one, the menu lists them and the
 *   timebar marks them. Null (the UI tests) leaves all three out.
 * @param prefs the desktop's own settings: volume, mute and downmix are kept there across
 *   launches, and the wheel and decoder choices are read from it.
 * @param onPlayingItemChanged called with each item once it has opened, including episodes the
 *   player moved on to by itself.
 * @param onEngine hands the engine out once it exists, for the dev harness's scripted runs.
 * @param detailsOpen start with the Playback details panel up (the harness's `--details`).
 * @param menuOpen start with the menu open at that page, by its name in [MenuPage] (the harness's `--menu`).
 * @param downloads the download queue, held while "Still watching?" is up. Null leaves it be.
 * @param idleLimitMs playback without input before "Still watching?" asks; the harness shortens it.
 * @param shortcutsOpen start with the "?" sheet up (the harness's `--shortcuts`).
 * @param screenshotDir where S puts its pictures: Pictures/TMPlayer, or a temp folder in the tests.
 * @param engineFactory the engine; libmpv in the app, a fake in the UI tests that drive the mouse.
 * @param onlineOpen start with the online subtitle panel up; [onlinePreset] fills it without a
 *   search, for the render test.
 * @param backdrop what is painted behind the overlay; the promo fixture leaves it clear so the
 *   still it draws underneath shows where libmpv's picture would.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun PlayerScreen(
    media: PlayerMedia,
    startFromBeginning: Boolean,
    onBack: () -> Unit,
    fullscreen: Boolean,
    onToggleFullscreen: () -> Unit,
    settings: SettingsStore,
    prefs: DesktopPrefs,
    watched: WatchedStore? = null,
    bookmarks: BookmarkStore? = null,
    modifier: Modifier = Modifier,
    onMiniPlayer: (() -> Unit)? = null,
    onToggleAlwaysOnTop: (() -> Unit)? = null,
    onQuit: (() -> Unit)? = null,
    onPlayingItemChanged: (MediaItem) -> Unit = {},
    onEngine: (PlaybackEngine) -> Unit = {},
    detailsOpen: Boolean = false,
    menuOpen: String? = null,
    downloads: DownloadRunner? = null,
    idleLimitMs: Long = StillWatching.IDLE_LIMIT_MS,
    shortcutsOpen: Boolean = false,
    screenshotDir: () -> java.io.File = { java.io.File(UserDirs.pictures(), "TMPlayer") },
    engineFactory: () -> PlaybackEngine = { MpvNatives.create(OpenPrefs.hwdecFor(prefs.now.softwareDecoding)) },
    backdrop: Color = Color.Black,
    /** The loader's picture where there is no online metadata; for the fixtures, which have no TDLib. */
    loaderThumbnail: androidx.compose.ui.graphics.ImageBitmap? = null,
    onlineOpen: Boolean = false,
    onlinePreset: com.tmplayer.online.SearchResult? = null,
    /** Start with the episode list up, once the chat has answered: the render test's and the promo's. */
    episodesOpen: Boolean = false,
    /** The screen was locked or unlocked, for the OS media controls to hold off ([PlayerHost]). */
    onLockedChanged: (Boolean) -> Unit = {},
    /** Start locked, with the Unlock button up: the render test's. */
    lockedAtStart: Boolean = false,
    /** "Not enough space": close the player for Downloads with Free up space up. Null hides it. */
    onFreeUpSpace: (() -> Unit)? = null,
) {
    // Split so that no method grows past what the release build's ProGuard can write stack map
    // frames for (HX1, as SeriesPanel and AddChatsScreen were): one method of about 2,400 lines
    // and well over a hundred locals failed verifyReleaseClasses. PlayerUi is what the screen
    // remembers, MediaUi, VideoUi and MessageUi what it remembers per media, per video and per
    // message, PlayerSession its actions and what one composition reads (what the body's local
    // functions captured), and the stages below, called in the order the body ran them, its
    // effects and what it draws. Every stage is non-restartable, so a state read in any of them
    // recomposes this screen as before.
    val s = LocalStrings.current
    val ui = remember { PlayerUi(s.playerOpeningVideo, menuOpen, detailsOpen, onlineOpen, shortcutsOpen, episodesOpen, lockedAtStart) }
    val held = remember(media) { MediaUi(media, startFromBeginning) }
    val video = remember(held.current) { VideoUi(held.current, ui.keepRepeat, ui.skipSay) }
    val message = rememberForMessage(held.current.item) { MessageUi() }
    val session = PlayerSession(
        ui, held, video, message, s, androidx.compose.ui.platform.LocalFocusManager.current,
        settings, prefs, watched, bookmarks, onBack, fullscreen, onToggleFullscreen, onMiniPlayer, onToggleAlwaysOnTop,
        onQuit, onPlayingItemChanged, onEngine, downloads, idleLimitMs, screenshotDir, engineFactory, backdrop,
        loaderThumbnail, onlinePreset, onLockedChanged, onFreeUpSpace,
    )
    session.Gather()
    session.Opening()
    session.Ending()
    session.Controls()
    session.Body(modifier)
    session.Refocus()
}

/** What [PlayerScreen] remembers for as long as it is open. */
private class PlayerUi(
    opening: String,
    menuOpen: String?,
    detailsOpen: Boolean,
    onlineOpen: Boolean,
    shortcutsOpen: Boolean,
    episodesOpen: Boolean,
    lockedAtStart: Boolean,
) {
    // The profile this player was opened for (HX88): its resume saves are never another profile's.
    val accountOwner = com.tmplayer.data.Accounts.stamp
    // What the TV is told the video is called, read when a cast loads it.
    val castTitle = arrayOf(com.tmplayer.desktop.cast.CastTitle(""))
    // When libmpv cannot load (Windows blocked it), the reason the stand-in's status sheet shows (HX36).
    val engineBlocked = arrayOfNulls<String>(1)

    // The free zoom over the picture shape (PictureZoom), and the size of the area the picture is
    // drawn in, which its pointer positions and its clamp are measured against.
    var zoom by mutableStateOf(PictureZoom.NONE)
    var videoBox by mutableStateOf(androidx.compose.ui.unit.IntSize.Zero)

    var attempt by mutableIntStateOf(0)
    var phase by mutableStateOf<Phase>(Phase.Loading(opening))
    // The opening's start point and [StreamReads] when it began, for the loader's staged line.
    var openingStartMs by mutableStateOf(0L)
    val openingBytesAt = longArrayOf(0L)
    var episodes by mutableStateOf(Episodes())
    // A fixed picture shape (16:9, 4:3...) picked in this player, which the next video keeps.
    val heldShape = arrayOfNulls<VideoScale>(1)
    // The media handed on to a Telegram copy before it ever opened, whose leaving saves nothing.
    val neverOpened = arrayOfNulls<PlayerMedia>(1)
    var resumedFrom by mutableStateOf<Long?>(null)
    var autoplayNext by mutableStateOf(true)
    var nextUpDismissed by mutableStateOf(false)
    var nextUpShown by mutableStateOf(false)
    var trackChoice by mutableStateOf(TrackChoice())
    var showRemaining by mutableStateOf(false)
    var ignoreClicks by mutableStateOf(false)
    // Lock the screen ([PlayerLock]): whether it is on, when the Unlock button was last asked
    // for (it fades [PlayerLock.SHOW_MS] after), and the hold of Enter toward an unlock.
    var screenLocked by mutableStateOf(lockedAtStart)
    var unlockWanted by mutableLongStateOf(if (lockedAtStart) System.nanoTime() else 0L)
    var showUnlock by mutableStateOf(lockedAtStart)
    var unlockHold by mutableStateOf<Job?>(null)
    var tdlibVersion by mutableStateOf<String?>(null)

    var controlsUp by mutableStateOf(true)
    var activity by mutableLongStateOf(0L)
    var overControls by mutableStateOf(false)
    var menu by mutableStateOf(MenuPage.entries.firstOrNull { it.name.equals(menuOpen, ignoreCase = true) }?.let { MenuAt(it, MenuAt.Anchor.Cursor, Offset(240f, 120f)) })
    var showDetails by mutableStateOf(detailsOpen)
    var showOnline by mutableStateOf(onlineOpen)
    // Which search the online panel runs: OpenSubtitles and SubDL, or MSone.
    var onlineMsone by mutableStateOf(false)
    var onlineSubMaker by mutableStateOf(false)
    // The video SubMaker translations were started for, so they end with it (leaving the player or another video).
    var translatingVideo by mutableStateOf<String?>(null)
    var showShortcuts by mutableStateOf(shortcutsOpen)
    var showEpisodes by mutableStateOf(episodesOpen)
    var flash by mutableStateOf<Flash?>(null)
    var seekRun by mutableStateOf(0L to 0L) // (accumulated ms, last at)
    // Videos marked watched from this player. Their position is forgotten on the way out rather
    // than saved, or leaving would put them straight back into Continue watching (as on Android).
    val markedHere = mutableSetOf<String>()
    // Videos already put on the Watched list for being left past 90 %, so leaving the same one
    // twice (switching away, then closing) marks it once.
    val watchedHere = mutableSetOf<String>()
    // Chapters, the online lists and the viewer's marks merged, with the per kind choice and the
    // countdown: what the controller says goes over the picture.
    val skipSay = arrayOf<(String) -> Unit>({})
    // Repeat this video (EG-F52): lasts for the video it was switched on for; "Keep repeat on"
    // carries it to the next ([com.tmplayer.data.RepeatVideo.repeats]). Excludes the A-B repeat.
    var keepRepeat by mutableStateOf(false)

    // "Still watching?" and the sleep timer (CP07). The counts live for the whole player session,
    // so they carry across the episodes autoplay moves on to, and any input starts them again.
    var autoplayedInARow by mutableIntStateOf(0)
    var idleMs by mutableLongStateOf(0L)
    var sleepAt by mutableLongStateOf(0L)
    var sleepAtTheEnd by mutableStateOf(false)
    var heldDownloads by mutableStateOf(emptyList<Int>())
    // Where Keep watching opens the video again, past the saved resume point and its rules.
    var reopenAt by mutableStateOf<Long?>(null)

    val focus = FocusRequester()
    // Whether the keys are on the player itself rather than on one of its controls: in the
    // big-screen mode a remote walks into the controls with Up and Down (plan 8.7).
    var keysOnPlayer by mutableStateOf(true)
    // Tab has walked focus into the controls: Space, Enter and the arrows are then the focused
    // control's, not the player's (WP-47). A click or a refocus ends it, so a control the mouse
    // pressed never takes the seek keys from the picture.
    var navByKeyboard by mutableStateOf(false)
    // The note dialog: for a new bookmark at a position, or for one already made.
    var notePrompt by mutableStateOf<NotePrompt?>(null)
    // The "Go to a time" dialog ({cmd}+G).
    var timePrompt by mutableStateOf(false)
    // "Add to a list" (HX43): the video as it was when the line was chosen, so the picker keeps
    // naming the one it was opened for even if the next episode starts under it.
    var listPicking by mutableStateOf<com.tmplayer.data.WatchLaterRecord?>(null)
    // The speed to go back to when a hold ends; null while no hold has changed it.
    var speedBeforeHold by mutableStateOf<Float?>(null)
    // The resume after a pause lets the controls go sooner when they were down before it (PauseControls).
    var quickHide by mutableStateOf(false)
    // Whether Compose's focus is on the picture or inside it, where [PlayerSession.playerKey] hears the keys itself.
    var focusInside by mutableStateOf(false)
    val wheelStepper = WheelStepper()
}

/** What [PlayerScreen] remembers for the media it was handed, which the player moves on from. */
private class MediaUi(media: PlayerMedia, startFromBeginning: Boolean) {
    var current by mutableStateOf(media)
    var ignoreSavedPosition by mutableStateOf(startFromBeginning)
}

/** What [PlayerScreen] remembers for the video playing: a new video starts with none of it. */
private class VideoUi(current: PlayerMedia, keepRepeat: Boolean, skipSay: Array<(String) -> Unit>) {
    // Where this video's intro, recap and credits are, from the online lookups, and the ones
    // already skipped (by start), which do not offer themselves again in this playback. Both
    // belong to the video playing: an episode switch starts with none until its own answer.
    var skips by mutableStateOf(emptyList<SkipSegment>())
    var skipped by mutableStateOf(emptySet<Long>())
    // Chapters, the online lists and the viewer's marks merged, with the per kind choice and the
    // countdown; and what the pill shows now, which the loop in Ending keeps current.
    val skipCtl = SkipController { skipSay[0](it) }.also { it.start(current.item) }
    var skipOffer by mutableStateOf<SkipOffer?>(null)
    var introMarking by mutableStateOf(false)
    // A-B repeat, for the video playing only: a new video starts with none.
    var abLoop by mutableStateOf<AbLoop?>(null)
    var repeatVideo by mutableStateOf(com.tmplayer.data.RepeatVideo.repeats(current.item, keepRepeat))
    // "Back to 12:34" after a long jump, for the video playing only. See [SeekUndo].
    val seekUndo = SeekUndo()
    var undoOffer by mutableStateOf<SeekUndo.Offer?>(null)
}

/** What [PlayerScreen] remembers per message, kept through [rememberForMessage]. */
private class MessageUi {
    // The message as Telegram has it now (CP80): its lighter copies, start time and storyboard.
    // The grid's item already carries them; one rebuilt from a record is read again.
    var post by mutableStateOf<MediaItem?>(null)
    // The subtitle files posted in this video's chat (EG-F48), looked for once when the Subtitles menu
    // first opens: one search, kept for the video, and nothing when offline or the kids lock leaves
    // the chat out. The menu shows "In this chat" only when there are some.
    var chatHits by mutableStateOf(emptyList<com.tmplayer.player.ChatSubtitles.Hit>())
    var chatAsked by mutableStateOf(false)
    // "This video only" for the subtitle look (WP-29): this video keeps a look of its own.
    var styleThisVideo by mutableStateOf(false)
}

/**
 * One composition of [PlayerScreen]: the screen's parameters, its remembered states under the names
 * the body gave them, the values that composition read, and every action of the screen. A new one
 * each composition, as the body's local functions were, so an action sees what its composition saw;
 * the stages fill in what they compute as they run, before anything calls on it.
 */
private class PlayerSession(
    val ui: PlayerUi,
    val held: MediaUi,
    val video: VideoUi,
    val message: MessageUi,
    val s: com.tmplayer.i18n.Messages,
    val focusManager: androidx.compose.ui.focus.FocusManager,
    val settings: SettingsStore,
    val prefs: DesktopPrefs,
    val watched: WatchedStore?,
    val bookmarks: BookmarkStore?,
    val onBack: () -> Unit,
    val fullscreen: Boolean,
    val onToggleFullscreen: () -> Unit,
    val onMiniPlayer: (() -> Unit)?,
    val onToggleAlwaysOnTop: (() -> Unit)?,
    val onQuit: (() -> Unit)?,
    val onPlayingItemChanged: (MediaItem) -> Unit,
    val onEngine: (PlaybackEngine) -> Unit,
    val downloads: DownloadRunner?,
    val idleLimitMs: Long,
    val screenshotDir: () -> java.io.File,
    val engineFactory: () -> PlaybackEngine,
    val backdrop: Color,
    val loaderThumbnail: androidx.compose.ui.graphics.ImageBitmap?,
    val onlinePreset: com.tmplayer.online.SearchResult?,
    val onLockedChanged: (Boolean) -> Unit,
    val onFreeUpSpace: (() -> Unit)?,
) {
    // ---- remembered for the whole screen (PlayerUi) ----------------------------------------------
    val accountOwner = ui.accountOwner
    val castTitle = ui.castTitle
    val engineBlocked = ui.engineBlocked
    var zoom by ui::zoom
    var videoBox by ui::videoBox
    var attempt by ui::attempt
    var phase by ui::phase
    var openingStartMs by ui::openingStartMs
    val openingBytesAt = ui.openingBytesAt
    var episodes by ui::episodes
    val heldShape = ui.heldShape
    val neverOpened = ui.neverOpened
    var resumedFrom by ui::resumedFrom
    var autoplayNext by ui::autoplayNext
    var nextUpDismissed by ui::nextUpDismissed
    var nextUpShown by ui::nextUpShown
    var trackChoice by ui::trackChoice
    var showRemaining by ui::showRemaining
    var ignoreClicks by ui::ignoreClicks
    var screenLocked by ui::screenLocked
    var unlockWanted by ui::unlockWanted
    var showUnlock by ui::showUnlock
    var unlockHold by ui::unlockHold
    var tdlibVersion by ui::tdlibVersion
    var controlsUp by ui::controlsUp
    var activity by ui::activity
    var overControls by ui::overControls
    var menu by ui::menu
    var showDetails by ui::showDetails
    var showOnline by ui::showOnline
    var onlineMsone by ui::onlineMsone
    var onlineSubMaker by ui::onlineSubMaker
    var translatingVideo by ui::translatingVideo
    var showShortcuts by ui::showShortcuts
    var showEpisodes by ui::showEpisodes
    var flash by ui::flash
    var seekRun by ui::seekRun
    val markedHere = ui.markedHere
    val watchedHere = ui.watchedHere
    val skipSay = ui.skipSay
    var keepRepeat by ui::keepRepeat
    var autoplayedInARow by ui::autoplayedInARow
    var idleMs by ui::idleMs
    var sleepAt by ui::sleepAt
    var sleepAtTheEnd by ui::sleepAtTheEnd
    var heldDownloads by ui::heldDownloads
    var reopenAt by ui::reopenAt
    val focus = ui.focus
    var keysOnPlayer by ui::keysOnPlayer
    var navByKeyboard by ui::navByKeyboard
    var notePrompt by ui::notePrompt
    var timePrompt by ui::timePrompt
    var listPicking by ui::listPicking
    var speedBeforeHold by ui::speedBeforeHold
    var quickHide by ui::quickHide
    var focusInside by ui::focusInside
    val wheelStepper = ui.wheelStepper

    // ---- remembered per media, per video and per message ------------------------------------------
    var current by held::current
    var ignoreSavedPosition by held::ignoreSavedPosition
    var skips by video::skips
    var skipped by video::skipped
    val skipCtl = video.skipCtl
    var skipOffer by video::skipOffer
    var introMarking by video::introMarking
    var abLoop by video::abLoop
    var repeatVideo by video::repeatVideo
    val seekUndo = video.seekUndo
    var undoOffer by video::undoOffer
    var post by message::post
    var chatHits by message::chatHits
    var chatAsked by message::chatAsked
    var styleThisVideo by message::styleThisVideo

    // ---- read once per composition, as the body's own vals were ---------------------------------
    val item = current.item
    // This video's bookmarks, for the menu's list and the ticks on the timebar. A file with no
    // message behind it (the dev harness) has nothing to key them by, so it has none.
    val marksOn = bookmarks != null && hasMessage(item)
    val next = episodes.next
    // The segment the pill offers to skip. Credits that run to the end are the next-up card's
    // when there is a next episode, so they get no pill of their own; without one, the pill
    // skips to the end like any other. Never while the card is up, which has the same corner.
    // Derived from the live position (the loop in Ending), so the body hears of it as a pill
    // comes and goes only.
    val activeSkip = skipOffer?.segment
    val mac = com.tmplayer.desktop.os.OsInfo.isMac

    // ---- filled in by the stages, in the order they run -----------------------------------------
    // One search for the Cast button per player session (FQ8), not one per reveal of the controls.
    lateinit var castPresence: CastBarPresence
    lateinit var engine: com.tmplayer.desktop.cast.SwitchingEngine
    lateinit var desktopState: androidx.compose.runtime.State<com.tmplayer.desktop.DesktopSettings>
    val desktop get() = desktopState.value
    lateinit var scope: CoroutineScope
    lateinit var activeEngineState: androidx.compose.runtime.State<PlaybackEngine>
    val activeEngine get() = activeEngineState.value
    // Every update of the engine, the clock's four a second among them (see Gather).
    lateinit var liveStatus: androidx.compose.runtime.State<PlaybackStatus>
    lateinit var statusState: androidx.compose.runtime.State<PlaybackStatus>
    val status get() = statusState.value
    lateinit var tracksState: androidx.compose.runtime.State<List<MediaTrack>>
    val tracks get() = tracksState.value
    lateinit var castStateState: androidx.compose.runtime.State<com.tmplayer.desktop.cast.DesktopCast.State>
    val castState get() = castStateState.value
    lateinit var copies: List<MediaItem>
    lateinit var qualities: List<com.tmplayer.data.TelegramQuality>
    var storyboard: DesktopStoryboard? = null
    var frameSource: FrameSource? = null
    lateinit var seekCover: SeekCover
    lateinit var downloadedState: androidx.compose.runtime.State<Float?>
    val downloaded get() = downloadedState.value
    lateinit var savableState: androidx.compose.runtime.State<Boolean>
    val savable get() = savableState.value
    lateinit var watchedKeysState: androidx.compose.runtime.State<Map<String, WatchedRecord>>
    val watchedKeys get() = watchedKeysState.value
    lateinit var seriesKey: String
    lateinit var speedScopes: SpeedMemory.Scopes
    lateinit var progressMapState: androidx.compose.runtime.State<Map<String, com.tmplayer.data.WatchPoint>>
    val progressMap get() = progressMapState.value
    lateinit var marksState: androidx.compose.runtime.State<List<Bookmark>>
    val marks get() = marksState.value
    var nextUpVisible = false
    lateinit var nextUpSecondsState: androidx.compose.runtime.State<Int>
    val nextUpSeconds get() = nextUpSecondsState.value
    lateinit var holdSpeedState: androidx.compose.runtime.State<Float>
    val holdSpeed get() = holdSpeedState.value
    var showControls = false
    lateinit var dispatchNowState: androidx.compose.runtime.State<(PlayerAction) -> Unit>
    val dispatchNow get() = dispatchNowState.value
    lateinit var wheelSeeksNowState: androidx.compose.runtime.State<Boolean>
    val wheelSeeksNow get() = wheelSeeksNowState.value
    lateinit var playerKeyNowState: androidx.compose.runtime.State<(androidx.compose.ui.input.key.KeyEvent) -> Boolean>
    val playerKeyNow get() = playerKeyNowState.value

    // ---- actions, as the body declared them -----------------------------------------------------
    fun showFlash(kind: Flash.Kind, text: String = "") {
        flash = Flash(kind, text, System.nanoTime())
    }

    // ---- free zoom ---------------------------------------------------------------------------
    // Ctrl+wheel around the pointer (a Windows touchpad's pinch arrives as exactly that), Alt+=
    // and Alt+-, and a drag to move the enlarged picture, all through mpv's own video-zoom and
    // video-pan. macOS and Linux deliver no pinch to a Java window, so there it is the wheel and
    // the keys. A new video, a new shape, 3D and leaving the player all start it again.

    /** The area the picture is in and the picture's size there at 100 per cent, for the shape in force. */
    fun zoomFrame(): PictureZoom.Frame? {
        val box = videoBox
        val now = status
        val aspect = if (now.videoWidth > 0 && now.videoHeight > 0) now.videoWidth.toDouble() / now.videoHeight else 0.0
        val (width, height) = now.scale.pictureSize(box.width, box.height, aspect)
        return PictureZoom.Frame(box.width.toFloat(), box.height.toFloat(), width.toFloat(), height.toFloat()).takeIf { it.valid }
    }

    /** Only over this computer's own picture while it plays: not a cast, not 3D, not locked. */
    fun zoomAllowed(): Boolean =
        phase == Phase.Playing && !screenLocked && activeEngine === engine.local && !status.stereo.on && status.videoWidth > 0

    /** [next], held inside the picture, to mpv; [say] puts the figure up ("Zoom 135%"). */
    fun applyZoom(next: PictureZoom, say: Boolean) {
        val frame = zoomFrame()
        val held = if (frame == null || !next.zoomed) PictureZoom.NONE else next.clampedTo(frame)
        if (held != zoom) {
            zoom = held
            val (x, y) = frame?.let(held::mpvPan) ?: (0.0 to 0.0)
            engine.setZoom(held.mpvZoom, x, y)
        }
        if (say) showFlash(Flash.Kind.Text, L.playerZoomPercent(L.messages.formatter.scalePercent(held.percent)))
    }

    /** [steps] of the zoom (negative is out) around [at], or the middle of the picture. */
    fun zoomStep(steps: Float, at: Offset? = null) {
        if (!zoomAllowed()) return
        val frame = zoomFrame() ?: return
        val focus = at ?: Offset(frame.viewWidth / 2f, frame.viewHeight / 2f)
        applyZoom(zoom.stepped(steps, focus.x, focus.y, frame), say = true)
    }

    fun resetZoom(say: Boolean) {
        if (!zoom.zoomed && !say) return
        applyZoom(PictureZoom.NONE, say)
    }

    fun refocus() {
        navByKeyboard = false
        runCatching { focus.requestFocus() }
    }

    /**
     * Every seek the viewer asks for goes through here and [jumpBy], so [SeekUndo] hears of it:
     * the keys, the bar, chapters, bookmarks, the skip pill and Start over. "Watch again" from
     * the end sheet does not, since the place it leaves is the end.
     */
    fun jumpTo(target: Long) {
        val to = SeekMath.clampTarget(target, status.durationMs)
        undoOffer = seekUndo.seeked(liveStatus.value.positionMs, to, System.currentTimeMillis())
        // The resume notice holds the same corner and answers the same question; the newer wins.
        if (undoOffer != null) resumedFrom = null
        // The clamped place: a chapter mark past the end of a malformed file would end the video (PB-02).
        engine.seekTo(to)
    }

    fun jumpBy(delta: Long) {
        val to = SeekMath.clampSeek(liveStatus.value.positionMs, delta, status.durationMs)
        undoOffer = seekUndo.seeked(liveStatus.value.positionMs, to, System.currentTimeMillis())
        if (undoOffer != null) resumedFrom = null
        engine.seekBy(delta)
    }

    /** The chip, or Shift+Backspace: back to where the viewer was before the jump. */
    fun undoSeek() {
        val back = seekUndo.take(System.currentTimeMillis()) ?: return
        undoOffer = null
        engine.seekToExact(back)
        showFlash(Flash.Kind.Text, SeekMath.clock(back))
    }

    /**
     * HX25: the online subtitle picked for this video before comes back by itself, and "Off" does
     * too. The kept file first, a download again only when it is gone and nothing forbids it (the
     * kids lock, a missing key, a spent allowance say so in one line instead).
     */
    fun restoreRememberedSubtitle(opened: MediaItem) {
        val online = OnlineSubtitles.current ?: return
        if (online.picks == null || !hasMessage(opened)) return
        scope.launch {
            try {
                val keys = com.tmplayer.online.SubtitlePicks.keysOf(opened)
                var found = online.restorePick(keys, null, allowDownload = false)
                if (found is OnlineSubtitles.Restored.Missing && online.inBuild && com.tmplayer.data.ChatGate.allowed.value == null) {
                    val target = SubtitleTarget(
                        fileName = opened.fileName.ifBlank { opened.title },
                        sizeBytes = opened.sizeBytes,
                        caption = opened.caption.ifBlank { null },
                        languages = OnlineSubtitles.languagesFor(null),
                    )
                    found = online.restorePick(keys, target, allowDownload = true)
                }
                when (found) {
                    OnlineSubtitles.Restored.None -> Unit
                    OnlineSubtitles.Restored.Off -> engine.selectTrack(TrackType.Subtitle, null)
                    is OnlineSubtitles.Restored.Ready ->
                        if (engine.addSubtitle(found.file.absolutePath)) showFlash(Flash.Kind.Text, L.tracksFileLoaded(found.label))
                    is OnlineSubtitles.Restored.Missing -> found.notice?.let { showFlash(Flash.Kind.Text, com.tmplayer.online.OnlineWords.notice(it)) }
                }
            } catch (e: kotlin.coroutines.cancellation.CancellationException) {
                throw e
            } catch (e: Exception) {
                com.tmplayer.platform.JobFailures.handle("restoreSubtitle", e)
            }
        }
    }

    /** Play here: the video comes back to this computer where the TV left it. */
    fun playHere() {
        val casting = engine.cast ?: return
        val at = casting.positionMs.takeIf { casting.state.value.opened || it > 0 }
        engine.switchTo(engine.local, tellReceiver = castState is com.tmplayer.desktop.cast.DesktopCast.State.Connected)
        com.tmplayer.desktop.cast.DesktopCast.forget()
        if (at != null) reopenAt = at
        attempt++
    }

    /**
     * Puts [m] on the Watched list because playback reached the end, or [manual]ly from the menu.
     * On [playerScope], since the end is often followed by Back straight away. A file with no
     * message behind it (the dev harness) has nothing to key the list by and is left out.
     */
    fun recordWatched(m: PlayerMedia, durationMs: Long, manual: Boolean, then: suspend () -> Unit = {}) {
        val store = watched ?: return
        val it = m.item
        if (!hasMessage(it)) return
        // The end of what is here is not the end of a video missing a piece.
        if (!manual && m.incomplete) return
        val known = it.durationSec.takeIf { d -> d > 0 } ?: (durationMs / 1000).toInt().coerceAtLeast(0)
        val record = WatchedRecord.of(it.copy(durationSec = known), m.chatTitle, System.currentTimeMillis(), manual)
        playerScope.launch {
            runCatching {
                store.markWatched(record)
                then()
            }
        }
    }

    /**
     * Writes [m]'s position with its tracks and speed. Past 90 % the position is forgotten on
     * every write (see [ResumeRules]), but the video goes on the Watched list only when [leaving]
     * it: a mark is what "Remove after watching" deletes a download on, and the last tenth of a
     * film is still being watched.
     */
    fun saveResume(m: PlayerMedia, s: PlaybackStatus, leaving: Boolean = false) {
        if (!s.opened) return
        val it = m.item
        val position = s.positionMs
        val duration = s.durationMs
        val watched = ResumeRules.watched(position, duration) && !m.incomplete
        val key = SettingsStore.progressKey(it.chatId, it.messageId)
        if (watched && leaving && watchedHere.add(key)) recordWatched(m, duration, manual = false)
        val forget = watched || key in markedHere
        // The upload's file and size, whichever of Telegram's copies plays (CP80).
        val upload = TelegramQualities.upload(it)
        val description = ResumeRecord.encode(
            fileId = upload.fileId,
            title = it.title,
            chatTitle = m.chatTitle,
            sizeBytes = upload.sizeBytes,
            durationSec = it.durationSec.takeIf { d -> d > 0 } ?: (duration / 1000).toInt(),
            updatedAt = System.currentTimeMillis(),
            state = resumeState(engine.tracks.value, s.speed),
            // So the record can be told from other videos' and matched to this one's copies.
            fileName = it.fileName,
            caption = it.caption,
        )
        playerScope.launch {
            runCatching {
                if (forget) settings.clearResumePosition(it.chatId, it.messageId, it, owner = accountOwner)
                else settings.saveResumePosition(it.chatId, it.messageId, position, duration, description, owner = accountOwner)
            }
            // Out to the other devices once the saves stop (a pause, the player closed): the
            // push waits for them to settle, so the heartbeat never edits the message (ResumeSync).
            if (leaving) com.tmplayer.data.ResumeSync.leavingPlayer(settings) else com.tmplayer.data.ResumeSync.pushSoon(settings)
        }
    }

    fun switchTo(next: MediaItem) {
        saveResume(current, engine.state.value, leaving = true)
        current = current.episode(next)
    }

    /**
     * Plays another copy of the video in place of this one, from the same moment
     * ([VideoCopies.carryOver]), and remembers it for the video and the next episode.
     */
    fun switchCopy(copy: MediaItem) {
        if (copy.id == current.item.id) return
        val s = engine.state.value
        val from = s.durationMs.takeIf { it > 0 } ?: current.item.durationSec * 1000L
        VideoCopies.carryOver(s.positionMs, from, copy.durationSec * 1000L)?.takeIf { it > 0 }?.let {
            BookmarkJump.request(copy.chatId, copy.messageId, it)
        }
        playerScope.launch { runCatching { settings.setCopyPick(copy) } }
        CopyHandoff.offer(copies)
        switchTo(copy)
    }

    /**
     * Plays Telegram's copy of [height] (0 for the upload) in place of what plays, from the same
     * moment (CP80). The same message, so the place, the Watched mark and the bookmarks stay.
     */
    fun switchQuality(height: Int) {
        val here = current.item
        if (height == here.playingQuality) return
        val source = post ?: here
        val upload = TelegramQualities.upload(here).copy(qualities = source.qualities, storyboard = source.storyboard, startSec = source.startSec)
        val next = if (height == 0) upload else upload.qualities.firstOrNull { it.height == height }?.let { TelegramQualities.withQuality(upload, it) } ?: return
        val at = engine.state.value.positionMs
        if (at > 0) BookmarkJump.request(here.chatId, here.messageId, at)
        // Chosen by the viewer: the player does not pick another by itself for this video.
        QualityWish.request(here.chatId, here.messageId, height)
        showFlash(Flash.Kind.Text, s.playerQualitySwitched(if (height == 0) s.detailUploadQuality else s.detailTelegramQuality(TelegramQualities.label(height))))
        switchTo(next)
    }


    /** [switchTo] for an episode nobody asked for, counted towards "Still watching?". */
    fun autoplay(next: MediaItem) {
        autoplayedInARow++
        switchTo(next)
    }

    /**
     * Any key, click, wheel turn or movement over the player, or a menu pick: somebody is there,
     * and both of "Still watching?"'s counts start again.
     */
    fun noteInput() {
        idleMs = 0L
        autoplayedInARow = 0
    }

    /**
     * "Still watching?": the whole picture, with playback stopped and every download held.
     *
     * The engine is stopped rather than paused, which lets go of the stream: a paused mpv keeps
     * reading ahead, and TDLib keeps fetching for it. Keep watching opens it again where it was.
     */
    fun askStillWatching(why: String, next: MediaItem?, ended: Boolean) {
        // Its buttons have to be reachable, and playback is stopping anyway: an hour without a
        // key or a click, or a sleep timer, is nobody there for the lock to keep out.
        screenLocked = false
        val s = engine.state.value
        if (!ended && s.opened) {
            reopenAt = s.positionMs
            saveResume(current, s)
        }
        menu = null
        nextUpDismissed = true
        engine.stop()
        heldDownloads = downloads?.let { runner ->
            OfflineDownloads.active.value.values.filter { it.busy && it.stage != OfflineDownloads.Stage.Moving }
                .sortedBy { it.order }.map { it.fileId }
                .also { if (it.isNotEmpty()) OfflineDownloads.pauseAll(runner) }
        }.orEmpty()
        phase = Phase.StillWatching(why, next, ended)
    }

    fun keepWatching(asked: Phase.StillWatching) {
        noteInput()
        downloads?.let { runner -> heldDownloads.forEach { OfflineDownloads.resume(runner, it) } }
        heldDownloads = emptyList()
        when {
            asked.next != null -> switchTo(asked.next)
            asked.ended -> phase = Phase.Finished
            else -> {
                nextUpDismissed = false
                attempt++
            }
        }
        refocus()
    }

    /** What the menu says about a running sleep timer, or null when there is none. */
    fun sleepTimerDetail(): String? = when {
        sleepAtTheEnd -> SleepTimer.label(SleepTimer.END_OF_VIDEO)
        sleepAt > 0 -> SleepTimer.remaining(sleepAt - System.currentTimeMillis())
        else -> null
    }

    /** Minutes from now, [SleepTimer.END_OF_VIDEO], or null to turn the timer off. */
    fun setSleepTimer(minutes: Int?) {
        sleepAtTheEnd = minutes == SleepTimer.END_OF_VIDEO
        sleepAt = if (minutes != null && minutes != SleepTimer.END_OF_VIDEO) {
            System.currentTimeMillis() + minutes * 60_000L
        } else {
            0L
        }
        showFlash(
            Flash.Kind.Text,
            when (minutes) {
                null -> L.playerSleepOff
                SleepTimer.END_OF_VIDEO -> L.playerSleepEndOfVideo
                else -> L.playerSleepIn(SleepTimer.label(minutes))
            },
        )
    }

    /** The menu's Rotate picture (#34): a quarter turn more, said over the picture; this file only. */
    fun rotatePicture() {
        val next = com.tmplayer.player.PictureRotation.next(status.pictureRotation)
        engine.setPictureRotation(next)
        showFlash(
            Flash.Kind.Text,
            if (next == 0) L.playerRotatePictureOff else L.playerRotatePictureNow(L.messages.formatter.number(next)),
        )
    }

    /** The menu's Volume boost: switched on the sound already playing, and kept for every video. */
    fun toggleVolumeBoost() {
        val on = !status.volumeBoost
        engine.setVolumeBoost(on)
        showFlash(Flash.Kind.Text, if (on) L.playerVolumeBoostOn else L.playerVolumeBoostOff)
        playerScope.launch { runCatching { settings.setVolumeBoost(on) } }
    }

    /**
     * The menu's 3D video page: the layout, the glasses and the eyes, each applied as it is
     * picked with the page left open. A colour picked while 3D is off only waits as the choice.
     */
    fun setStereo(next: Stereo3d) {
        val before = status.stereo
        // 3D draws for the whole picture: the zoom goes before it comes on.
        if (next.on) resetZoom(say = false)
        engine.setStereo3d(next)
        when {
            next.on && (!before.on || next.view != before.view) -> showFlash(Flash.Kind.Text, L.playerStereoOnNow(stereoGlassesLabel(next.view)))
            !next.on && before.on -> showFlash(Flash.Kind.Text, L.playerStereoOffNow)
        }
    }

    /** The menu's Night mode: switched on the sound already playing, and kept for every video. */
    fun toggleNightMode() {
        val on = !status.nightMode
        engine.setNightMode(on)
        showFlash(Flash.Kind.Text, if (on) L.playerQuietLoudScenesOn else L.playerQuietLoudScenesOff)
        playerScope.launch { runCatching { settings.setNightMode(on) } }
    }

    /** The pill, or G: past the segment, which is then left alone for the rest of this playback. */
    fun skipSegment() {
        val segment = activeSkip ?: return
        skipped = skipped + segment.startMs
        skipOffer = null
        jumpTo(segment.target(status.durationMs))
    }

    /** Cancel on the countdown: this stretch stays a plain button. */
    fun cancelAutoSkip() {
        skipOffer?.segment?.let { skipCtl.cancel(it) }
    }

    /** "Mark this as the intro", O: the first press is the start, the second the end and saves. */
    fun markIntro() {
        if (!skipCtl.canMark) return
        skipCtl.markTap(liveStatus.value.positionMs, status.durationMs)
        introMarking = skipCtl.introMarking
    }

    fun poke() {
        if (screenLocked) {
            unlockWanted = System.nanoTime()
            return
        }
        controlsUp = true
        // The viewer is back at the controls: the usual time again, not the resume's quick one.
        quickHide = false
        activity = System.nanoTime()
    }

    // ---- actions -----------------------------------------------------------------------------

    fun rememberTracks(type: TrackType, chosen: MediaTrack?) {
        val updated = when (type) {
            TrackType.Audio -> trackChoice.copy(audioLanguage = chosen?.language ?: trackChoice.audioLanguage)
            TrackType.Subtitle -> trackChoice.copy(
                textLanguage = chosen?.language ?: trackChoice.textLanguage,
                subtitlesOn = chosen != null,
            )
            TrackType.Video -> trackChoice
        }
        if (updated == trackChoice) return
        trackChoice = updated
        playerScope.launch { runCatching { settings.setTrackChoice(seriesKey, updated) } }
    }

    fun selectTrack(type: TrackType, track: MediaTrack?) {
        engine.selectTrack(type, track?.id)
        rememberTracks(type, track)
        // HX25: Off is remembered for the video; a track chosen again ends an Off.
        if (type == TrackType.Subtitle) OnlineSubtitles.current?.let { online ->
            val keys = com.tmplayer.online.SubtitlePicks.keysOf(current.item)
            if (track == null) online.rememberOff(keys) else online.forgetPick(keys)
        }
        val audio = type == TrackType.Audio
        showFlash(
            Flash.Kind.Text,
            when {
                track == null -> if (audio) L.playerAudioOff else L.playerSubtitlesOff
                audio -> L.playerAudioTrack(track.label)
                else -> L.playerSubtitlesTrack(track.label)
            },
        )
    }

    fun cycleTrack(type: TrackType, forward: Boolean) {
        if (tracks.none { it.type == type }) {
            showFlash(Flash.Kind.Text, if (type == TrackType.Audio) L.playerNoOtherAudio else L.playerNoSubtitles)
            return
        }
        selectTrack(type, tracks.cycle(type, forward))
    }

    fun setSpeed(value: Float) {
        engine.setSpeed(value)
        showFlash(Flash.Kind.Text, SeekMath.speedLabel(value))
        // For the series, the course, the chat or the topic, not only this file: 1x forgets it.
        val scopes = speedScopes
        playerScope.launch { runCatching { settings.rememberSpeed(scopes, value) } }
    }

    fun setScale(scale: VideoScale) {
        // A shape is a fresh start for the zoom, and its own name is the answer on screen.
        resetZoom(say = false)
        engine.setScale(scale)
        showFlash(Flash.Kind.Text, scale.label)
        // A fixed shape is for this player only; the other three are kept for every video.
        heldShape[0] = scale.takeIf { it.fixed }
        if (!scale.fixed) playerScope.launch { runCatching { settings.setVideoScale(scale.name) } }
    }

    fun setVolume(value: Int) {
        engine.setVolume(value)
        prefs.update { it.copy(volume = value, muted = if (value > 0) false else it.muted) }
        showFlash(Flash.Kind.Volume, L.messages.formatter.percent(value / 100.0))
    }

    fun toggleMute() {
        val muted = !status.muted
        engine.setMuted(muted)
        prefs.update { it.copy(muted = muted) }
        showFlash(Flash.Kind.Volume, if (muted) L.playerMuted else L.messages.formatter.percent(status.volume / 100.0))
    }

    /**
     * Writes this file's offsets as they now stand. Started undispatched, so the writes reach the
     * settings in the order the keys were pressed, and a quick run of Z presses cannot land an
     * older figure last.
     */
    fun saveDelays() {
        if (!hasMessage(item)) return
        val chat = item.chatId
        val message = item.messageId
        val delays = engine.state.value.delays
        playerScope.launch(start = CoroutineStart.UNDISPATCHED) {
            runCatching { settings.setSyncDelays(chat, message, delays) }
        }
    }

    /**
     * From the subtitle menu: shown on the picture at once, and kept as the setting for every video,
     * or for this video alone while "This video only" is on (WP-29).
     */
    fun setSubtitleStyle(style: SubtitleStyle) {
        engine.setSubtitleStyle(style)
        val own = styleThisVideo && hasMessage(item)
        val (chat, message) = item.chatId to item.messageId
        playerScope.launch {
            runCatching { if (own) settings.setVideoSubtitleStyle(chat, message, style) else settings.setSubtitleStyle(style) }
        }
    }

    /** "This video only" on: the look in force becomes this video's; off: it is forgotten and the shared look returns. */
    fun setSubtitleThisVideo(on: Boolean) {
        if (!hasMessage(item) || on == styleThisVideo) return
        styleThisVideo = on
        val (chat, message) = item.chatId to item.messageId
        val own = engine.state.value.subtitleStyle
        playerScope.launch {
            if (on) {
                runCatching { settings.setVideoSubtitleStyle(chat, message, own) }
            } else {
                runCatching { settings.setVideoSubtitleStyle(chat, message, null) }
                val shared = runCatching { settings.subtitleStyleNow() }.getOrNull() ?: return@launch
                if (!styleThisVideo) engine.setSubtitleStyle(shared)
            }
        }
    }

    /** One step later ([direction] 1) or earlier (-1); 0 puts the subtitles back in step. */
    /**
     * Subtitles a size bigger or smaller from the keyboard: the one subtitle look every video
     * shares, so it is written to the setting, which the video playing takes up at once.
     */
    fun stepSubtitleSize(direction: Int) {
        val sizes = com.tmplayer.player.SubtitleSize.entries
        val now = engine.state.value.subtitleStyle
        val next = sizes[(now.size.ordinal + direction).coerceIn(0, sizes.size - 1)]
        showFlash(Flash.Kind.Text, L.commonWithDetail(L.settingsSubtitleSize, next.label))
        if (next == now.size) return
        val style = now.copy(size = next, sizePercent = 0)
        setSubtitleStyle(style)
    }

    fun stepSubtitleDelay(direction: Int) {
        val ms = if (direction == 0) 0L else SyncDelays.step(engine.state.value.subtitleDelayMs, direction)
        engine.setSubtitleDelay(ms)
        showFlash(Flash.Kind.Text, L.playerSubtitleDelay(SyncDelays.label(ms)))
        saveDelays()
    }

    fun stepAudioDelay(direction: Int) {
        val ms = if (direction == 0) 0L else SyncDelays.step(engine.state.value.audioDelayMs, direction)
        engine.setAudioDelay(ms)
        showFlash(Flash.Kind.Text, L.playerAudioDelay(SyncDelays.label(ms)))
        saveDelays()
    }

    fun seekFlash(delta: Long) {
        val now = System.currentTimeMillis()
        val (run, at) = seekRun
        val same = now - at < SEEK_RUN_MS && run != 0L && (run > 0) == (delta > 0)
        val total = if (same) run + delta else delta
        seekRun = total to now
        val seconds = kotlin.math.abs(total) / 1000
        val text = if (seconds >= 60 && seconds % 60 == 0L) L.formatMinutesShort(seconds / 60) else L.formatSecondsShort(seconds)
        showFlash(if (delta < 0) Flash.Kind.SeekBack else Flash.Kind.SeekForward, text)
    }

    fun togglePlay() {
        if (phase != Phase.Playing) return
        engine.togglePlay()
    }

    /** The menu's "Mark as watched" and "Mark as unwatched", for the video playing. */
    fun toggleWatched() {
        val store = watched ?: return
        if (!hasMessage(item)) return
        val key = SettingsStore.progressKey(item.chatId, item.messageId)
        if (key in watchedKeys) {
            markedHere.remove(key)
            val chat = item.chatId
            val message = item.messageId
            playerScope.launch { runCatching { store.markUnwatched(chat, message) } }
            showFlash(Flash.Kind.Text, L.playerMarkedUnwatched)
        } else {
            markedHere.add(key)
            val chat = item.chatId
            val message = item.messageId
            recordWatched(current, status.durationMs, manual = true) { settings.clearResumePosition(chat, message) }
            showFlash(Flash.Kind.Text, L.playerMarkedWatched)
        }
    }

    /** The episode list's mark, for any episode: the one playing goes through [toggleWatched]. */
    fun toggleWatchedOf(target: MediaItem) {
        if (target.id == item.id) {
            toggleWatched()
            return
        }
        val store = watched ?: return
        if (!hasMessage(target)) return
        val key = SettingsStore.progressKey(target.chatId, target.messageId)
        val chatTitle = current.chatTitle
        playerScope.launch {
            runCatching {
                if (key in watchedKeys) {
                    store.markUnwatched(target.chatId, target.messageId)
                } else {
                    store.markWatched(WatchedRecord.of(target, chatTitle, System.currentTimeMillis(), manual = true))
                    settings.clearResumePosition(target.chatId, target.messageId)
                }
            }
        }
    }

    fun startOver() {
        jumpTo(0)
        engine.play()
        resumedFrom = null
        // A short watch from here leaves a saved place alone, so starting over forgets it.
        playerScope.launch { runCatching { settings.clearResumePosition(current.item.chatId, current.item.messageId) } }
        showFlash(Flash.Kind.Text, L.playerFromStart)
    }

    /**
     * The frame on screen to Pictures/TMPlayer. Refused for a chat that restricts saving, as
     * Telegram's own apps refuse a screenshot there; written off the UI thread, since a 4K PNG
     * takes a moment.
     */
    fun takeScreenshot(withSubtitles: Boolean) {
        if (!current.savable.value) {
            showFlash(Flash.Kind.Text, com.tmplayer.data.ContentProtection.NOT_SAVABLE)
            return
        }
        val at = liveStatus.value.positionMs
        // The name the title bar shows ("Night Train S01E02"), not the release name with its dots.
        val parsed = MediaName.parse(item.nameForParsing, item.caption)
        val name = listOfNotNull(parsed.title.ifBlank { null }, parsed.episodeCode).joinToString(" ").ifBlank { item.title }
        scope.launch {
            val file = kotlinx.coroutines.withContext(Dispatchers.IO) {
                runCatching {
                    val dir = screenshotDir().apply { mkdirs() }
                    ScreenshotFiles.fileFor(dir, name, at).takeIf { engine.screenshot(it, withSubtitles) }
                }.getOrNull()
            }
            showFlash(Flash.Kind.Text, if (file != null) L.playerScreenshotSaved(file.name) else L.playerScreenshotFailed)
        }
    }

    /** Repeat this video on or off; turning it on clears the A-B repeat, which it excludes. */
    fun toggleRepeatVideo() {
        val on = !repeatVideo
        com.tmplayer.data.RepeatVideo.set(on, current.item)
        repeatVideo = on
        if (on && abLoop != null) {
            abLoop = null
            engine.setAbLoop(null)
        }
        if (on) nextUpShown = false
        showFlash(Flash.Kind.Text, if (on) L.playerRepeatVideoOn else L.playerRepeatOff)
    }

    /** R: the loop's start, then its end (and the loop runs), then off. */
    fun pressAbRepeat() {
        // The two repeats exclude each other: marking a stretch turns Repeat this video off.
        if (repeatVideo) {
            com.tmplayer.data.RepeatVideo.set(false)
            repeatVideo = false
        }
        val at = liveStatus.value.positionMs
        val before = abLoop
        val after = AbLoop.press(before, at)
        abLoop = after
        when {
            after == null -> {
                engine.setAbLoop(null)
                showFlash(Flash.Kind.Text, L.playerRepeatOff)
            }
            after == before -> showFlash(Flash.Kind.Text, L.playerLoopTooShort)
            after.complete -> {
                engine.setAbLoop(after)
                showFlash(Flash.Kind.Text, L.playerRepeating(SeekMath.clock(after.startMs), SeekMath.clock(after.endMs ?: 0)))
            }
            else -> showFlash(Flash.Kind.Text, L.playerLoopFrom(SeekMath.clock(after.startMs)))
        }
    }

    /** Ctrl+Left and Ctrl+Right: the next chapter, or the start of this one, as [Chapters] has it. */
    fun stepChapter(forward: Boolean) {
        // Sorted, and only the marks inside the video: mpv gives its own order, and a mark past the end would end it.
        val chapters = Chapters.inside(status.chapters, status.durationMs)
        if (status.chapters.isEmpty()) {
            // The minute those keys seeked before they meant chapters, for a file that has none.
            val delta = if (forward) PlayerKeys.SEEK_LONG_MS else -PlayerKeys.SEEK_LONG_MS
            jumpBy(delta)
            seekFlash(delta)
            return
        }
        val at = liveStatus.value.positionMs
        val target = if (forward) Chapters.next(chapters, at) else Chapters.previous(chapters, at)
        if (target == null) {
            showFlash(Flash.Kind.Text, if (forward) L.playerLastChapter else L.playerBeforeFirstChapter)
            return
        }
        jumpTo(chapters[target].startMs)
        showFlash(Flash.Kind.Text, Chapters.label(chapters, target))
    }

    /** B and the menu's Add bookmark: the moment playing, with a note asked for in a dialog. */
    fun addBookmark() {
        if (!marksOn) return
        notePrompt = NotePrompt(item.chatId, item.messageId, positionMs = liveStatus.value.positionMs)
    }

    fun saveNote(prompt: NotePrompt, note: String) {
        val store = bookmarks ?: return
        val editing = prompt.editing
        val chat = editing?.chatId ?: prompt.chatId
        val message = editing?.messageId ?: prompt.messageId
        playerScope.launch {
            runCatching {
                if (editing != null) store.rename(chat, message, editing.id, note)
                else store.add(chat, message, prompt.positionMs, note)
            }
        }
        if (editing == null) showFlash(Flash.Kind.Text, L.playerBookmarkSaved(SeekMath.clock(prompt.positionMs)))
    }

    fun deleteBookmark(mark: Bookmark) {
        val store = bookmarks ?: return
        playerScope.launch { runCatching { store.delete(mark.chatId, mark.messageId, mark.id) } }
        showFlash(Flash.Kind.Text, L.playerBookmarkDeleted)
    }

    fun dispatch(action: PlayerAction) {
        when (action) {
            PlayerAction.TogglePlay -> togglePlay()
            PlayerAction.Play -> engine.play()
            PlayerAction.Pause -> engine.pause()
            is PlayerAction.SeekBy -> {
                jumpBy(action.deltaMs)
                seekFlash(action.deltaMs)
            }
            is PlayerAction.JumpToTenth -> SeekMath.tenth(status.durationMs, action.tenth)?.let {
                jumpTo(it)
                showFlash(Flash.Kind.Text, L.messages.formatter.percent(action.tenth / 10.0))
            }
            PlayerAction.JumpToEnd -> if (status.durationMs > 0) jumpTo(status.durationMs)
            is PlayerAction.FrameStep -> if (!status.playing) engine.frameStep(action.forward)
            is PlayerAction.VolumeBy -> setVolume(SeekMath.volume(status.volume, action.delta))
            PlayerAction.ToggleMute -> toggleMute()
            PlayerAction.ToggleFullscreen -> onToggleFullscreen()
            PlayerAction.ExitFullscreen -> if (fullscreen) onToggleFullscreen()
            PlayerAction.SubtitleNext -> cycleTrack(TrackType.Subtitle, true)
            PlayerAction.SubtitlePrevious -> cycleTrack(TrackType.Subtitle, false)
            PlayerAction.SubtitleToggle -> {
                val subs = tracks.filter { it.type == TrackType.Subtitle }
                if (subs.isEmpty()) {
                    showFlash(Flash.Kind.Text, L.playerNoSubtitles)
                } else if (subs.any { it.selected }) {
                    selectTrack(TrackType.Subtitle, null)
                } else {
                    selectTrack(TrackType.Subtitle, subs.firstOrNull { it.language == trackChoice.textLanguage } ?: subs.first())
                }
            }
            PlayerAction.AudioNext -> cycleTrack(TrackType.Audio, true)
            PlayerAction.AudioPrevious -> cycleTrack(TrackType.Audio, false)
            is PlayerAction.SubtitleDelay -> stepSubtitleDelay(action.direction)
            is PlayerAction.SubtitleSize -> stepSubtitleSize(action.direction)
            is PlayerAction.AudioDelay -> stepAudioDelay(action.direction)
            PlayerAction.SpeedUp -> setSpeed(SeekMath.fineSpeed(status.speed, up = true))
            PlayerAction.SpeedDown -> setSpeed(SeekMath.fineSpeed(status.speed, up = false))
            PlayerAction.SpeedReset -> setSpeed(1f)
            PlayerAction.NextEpisode -> episodes.next?.let(::switchTo) ?: showFlash(Flash.Kind.Text, L.playerNoNextEpisode)
            PlayerAction.SkipSegment -> skipSegment()
            PlayerAction.MarkIntro -> markIntro()
            PlayerAction.PreviousEpisode -> episodes.previous?.let(::switchTo)
                ?: showFlash(Flash.Kind.Text, L.playerNoPreviousEpisode)
            PlayerAction.AlwaysOnTop -> onToggleAlwaysOnTop?.invoke()
                ?: showFlash(Flash.Kind.Text, L.playerNoAlwaysOnTop)
            PlayerAction.MiniPlayer -> onMiniPlayer?.invoke()
                ?: showFlash(Flash.Kind.Text, L.playerNoMiniPlayer)
            PlayerAction.Stats -> showDetails = !showDetails
            PlayerAction.Back -> onBack()
            PlayerAction.Quit -> onQuit?.invoke()
            PlayerAction.ShortcutSheet -> showShortcuts = !showShortcuts
            is PlayerAction.Screenshot -> takeScreenshot(action.withSubtitles)
            PlayerAction.AbRepeat -> pressAbRepeat()
            PlayerAction.CycleShape -> setScale(status.scale.next())
            is PlayerAction.ZoomStep -> zoomStep(action.direction.toFloat())
            PlayerAction.ZoomReset -> if (zoomAllowed()) resetZoom(say = true)
            is PlayerAction.ChapterStep -> stepChapter(action.forward)
            PlayerAction.AddBookmark -> addBookmark()
            PlayerAction.UndoSeek -> undoSeek()
            PlayerAction.GoToTime -> if (status.opened) {
                menu = null
                timePrompt = true
            }
            PlayerAction.Stay -> Unit
        }
    }

    fun loadSubtitle(path: String) {
        if (phase != Phase.Playing) return
        val name = java.io.File(path).name
        OnlineSubtitles.current?.forgetPick(com.tmplayer.online.SubtitlePicks.keysOf(current.item))
        if (engine.addSubtitle(path)) showFlash(Flash.Kind.Text, L.playerSubtitlesTrack(name)) else showFlash(Flash.Kind.Text, L.playerSubtitleLoadFailed(name))
    }

    fun copyLink() {
        scope.launch {
            val link = current.messageLink()
            if (link == null) {
                showFlash(Flash.Kind.Text, L.commonNoLinks)
            } else {
                runCatching {
                    java.awt.Toolkit.getDefaultToolkit().systemClipboard.setContents(java.awt.datatransfer.StringSelection(link), null)
                }
                showFlash(Flash.Kind.Text, L.commonLinkCopied)
            }
        }
    }
    fun openElsewhere() {
        if (!current.savable.value) {
            showFlash(Flash.Kind.Text, com.tmplayer.data.ContentProtection.NOT_SAVABLE)
            return
        }
        scope.launch {
            val file = current.localFile()
            if (file == null) {
                showFlash(Flash.Kind.Text, L.playerOpenElsewherePartial)
            } else {
                com.tmplayer.desktop.os.OpenExternal.open(file)
                showFlash(Flash.Kind.Text, L.playerOpeningElsewhere)
            }
        }
    }

    // ---- lock the screen ---------------------------------------------------------------------

    fun lockScreen() {
        // Nothing left open that holds the keyboard or a button: a menu, a sheet, a note.
        menu = null
        showEpisodes = false
        showShortcuts = false
        showDetails = false
        showOnline = false
        notePrompt = null
        speedBeforeHold?.let { engine.setSpeed(it) }
        speedBeforeHold = null
        controlsUp = false
        screenLocked = true
        unlockWanted = System.nanoTime()
        refocus()
    }

    fun unlockScreen() {
        unlockHold?.cancel()
        unlockHold = null
        screenLocked = false
        poke()
        refocus()
    }

    /** A key while locked: Enter held for [PlayerLock.HOLD_MS] unlocks, any other press shows how. */
    fun lockedKey(event: androidx.compose.ui.input.key.KeyEvent) {
        val unlockKey = PlayerLock.isUnlockKey(event.key)
        when {
            unlockKey && event.type == KeyEventType.KeyDown -> if (unlockHold == null) {
                unlockHold = scope.launch {
                    delay(PlayerLock.HOLD_MS)
                    unlockHold = null
                    unlockScreen()
                }
            }
            unlockKey && event.type == KeyEventType.KeyUp -> {
                unlockHold?.cancel()
                unlockHold = null
            }
        }
        if (event.type == KeyEventType.KeyDown || unlockKey) {
            noteInput()
            unlockWanted = System.nanoTime()
        }
    }

    /**
     * The player's keys, whichever way they come: through the picture's own focus, or from the
     * window when Compose's focus is somewhere else (see [PlayerKeyRoute]).
     */
    fun playerKey(event: androidx.compose.ui.input.key.KeyEvent): Boolean {
        // Locked: every key is the lock's, the downs and the ups, so not even the
        // window's Esc and Backspace hear one. Enter held unlocks; any key shows how.
        if (screenLocked) {
            lockedKey(event)
            return true
        }
        if (event.type != KeyEventType.KeyDown) return false
        noteInput()
        // The episode list has the keyboard while it is up: Esc closes it, and every
        // other key (the arrows, Enter) is its own, not a seek or a fullscreen. The
        // other back keys close it too, or the window would take Backspace as a way
        // out of the whole player.
        if (showEpisodes) {
            if (com.tmplayer.ui.nav.isBackKey(event)) {
                showEpisodes = false
                refocus()
                return true
            }
            return false
        }
        // The side panel has the keyboard while it is up: Esc closes it, Backspace goes back a
        // page, and the rest (the arrows, Enter, Space) are its lines', never a seek or W.
        menu?.takeIf { it.panel }?.let { open ->
            if (!com.tmplayer.ui.nav.isBackKey(event)) return false
            val parent = open.parentPage()
            if (event.key == androidx.compose.ui.input.key.Key.Backspace && parent != null) {
                menu = open.copy(page = parent)
            } else {
                menu = null
                refocus()
            }
            return true
        }
        // Sheets peel off before Esc means anything else.
        if (event.key == androidx.compose.ui.input.key.Key.Escape && (showShortcuts || showDetails || showOnline)) {
            showShortcuts = false
            showDetails = false
            showOnline = false
            refocus()
            return true
        }
        val tv = com.tmplayer.desktop.ui.BigScreen.on
        val remote = com.tmplayer.desktop.ui.remoteKeyOf(event)
        val plain = !event.isShiftPressed && !event.isCtrlPressed && !event.isAltPressed && !event.isMetaPressed
        // Tab starts the control walk (the default traversal does the moving).
        if (event.key == Key.Tab) navByKeyboard = true
        // Focus is on a control Tab walked to: Space and Enter press it and the arrows go on from
        // it (the time bar steps itself), where they were the player's play and seek (WP-47). The
        // big-screen mode has its own walk below.
        if (!tv && navByKeyboard && !keysOnPlayer && PlayerKeys.focusedControlOwns(event.key, plain)) return false
        if (tv && !keysOnPlayer && menu == null && !showShortcuts && !showDetails && !showOnline) {
            // A remote on the controls: the arrows walk them, OK presses the one it is
            // on, and Back leaves them for the picture, the controls staying up meanwhile.
            poke()
            val direction = PlayerRemote.direction(event.key)
            when {
                com.tmplayer.ui.nav.isBackKey(event) || remote == com.tmplayer.desktop.ui.RemoteKey.Back -> {
                    refocus()
                    return true
                }
                plain && direction != null -> {
                    focusManager.moveFocus(direction)
                    return true
                }
                plain && event.key in PlayerRemote.OK -> return false
            }
        } else if (tv && plain && phase == Phase.Playing && (event.key == Key.DirectionUp || event.key == Key.DirectionDown)) {
            // Up and Down on the picture bring the controls up and the remote into them;
            // the sound is the television's own volume keys.
            poke()
            scope.launch {
                androidx.compose.runtime.withFrameNanos { }
                focusManager.moveFocus(androidx.compose.ui.focus.FocusDirection.Enter)
            }
            return true
        }
        // A remote's key the JDK has no name for (Back, the media keys), from its raw code.
        PlayerRemote.actionFor(remote)?.let { action ->
            if (phase != Phase.Playing && action !in OUTSIDE_PLAYBACK) return false
            val playKey = action == PlayerAction.TogglePlay || action == PlayerAction.Play || action == PlayerAction.Pause
            if (playKey && !MediaKeyEcho.claim(MediaKeyEcho.Source.Keyboard)) return true
            dispatchNow(action)
            return true
        }
        val press = KeyPress(
            key = event.key,
            shift = event.isShiftPressed,
            ctrl = event.isCtrlPressed,
            alt = event.isAltPressed,
            meta = event.isMetaPressed,
        )
        val context = KeyContext(fullscreen = fullscreen, speedIsNormal = status.speed == 1f, mac = mac, tv = tv, escLeaves = desktop.escLeavesVideo)
        val action = PlayerKeys.actionFor(press, context) ?: return false
        // Over the loading and error sheets only the way out works.
        if (phase != Phase.Playing && action !in OUTSIDE_PLAYBACK) return false
        // The same key may already have reached the player through the media session.
        val playKey = event.key == Key.MediaPlayPause || event.key == Key.MediaPlay || event.key == Key.MediaPause
        if (playKey && !MediaKeyEcho.claim(MediaKeyEcho.Source.Keyboard)) return true
        dispatchNow(action)
        return true
    }

    // Going in or out of fullscreen remaps the window, and some window systems hand it back
    // without focus inside it: take the keyboard again once the change has settled.
    // Not while a sheet or a menu is up: those hold the keyboard themselves, some for typing.
    fun keysBack() {
        if (!showEpisodes && !showShortcuts && !showDetails && !showOnline && notePrompt == null && !timePrompt && listPicking == null && menu == null) refocus()
    }

    /** A line picked in the menu or the side panel (the overlay's onMenuAction). */
    fun menuAction(action: MenuAction) {
        noteInput()
        when (action) {
            is MenuAction.Do -> dispatch(action.action)
            is MenuAction.Track -> selectTrack(action.type, action.track)
            is MenuAction.Speed -> setSpeed(action.speed)
            is MenuAction.Shape -> setScale(action.scale)
            MenuAction.RotatePicture -> rotatePicture()
            MenuAction.ToggleDownmix -> {
                val on = !status.downmix
                engine.setDownmix(on)
                prefs.update { it.copy(downmix = on) }
                showFlash(Flash.Kind.Text, if (on) s.playerDownmixOn else s.playerDownmixOff)
            }
            MenuAction.ToggleSoftwareDecoding -> {
                // mpv applies hwdec in place; kept as the setting, as the downmix is.
                val on = !status.softwareDecoding
                engine.setSoftwareDecoding(on)
                // Kept and said only when the engine took it (PB2-20).
                if (engine.state.value.softwareDecoding == on) {
                    prefs.update { it.copy(softwareDecoding = on) }
                    showFlash(Flash.Kind.Text, if (on) s.playerDecoderSoftwareNow else s.playerDecoderHardwareNow)
                } else {
                    showFlash(Flash.Kind.Text, s.playerDecoderSwitchFailed)
                }
            }
            MenuAction.ReportStutter -> {
                val facts = engine.stutterFacts(com.tmplayer.desktop.BuildInfo.VERSION)
                showFlash(Flash.Kind.Text, s.playerStutterMarkedDesktop)
                if (facts != null) {
                    playerScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                        runCatching {
                            com.tmplayer.platform.Logger.i("Player", DesktopStutter.line(facts))
                            com.tmplayer.desktop.DesktopCrashReports.reportStutter(facts)
                        }
                    }
                }
            }
            MenuAction.ToggleVolumeBoost -> toggleVolumeBoost()
            MenuAction.ToggleNightMode -> toggleNightMode()
            MenuAction.ToggleRepeatVideo -> toggleRepeatVideo()
            is MenuAction.Stereo -> setStereo(action.stereo)
            is MenuAction.Sleep -> setSleepTimer(action.minutes)
            is MenuAction.Chapter -> {
                jumpTo(action.startMs)
                // Named from the sorted marks: mpv's own order could name another chapter (PB-08).
                val marks = Chapters.inside(status.chapters, status.durationMs)
                val index = Chapters.at(marks, action.startMs)
                if (index >= 0) showFlash(Flash.Kind.Text, Chapters.label(marks, index))
            }
            is MenuAction.SubtitleDelay -> stepSubtitleDelay(action.direction)
            is MenuAction.AudioDelay -> stepAudioDelay(action.direction)
            is MenuAction.SubtitleLook -> setSubtitleStyle(action.style)
            is MenuAction.SubtitleThisVideo -> setSubtitleThisVideo(action.on)
            MenuAction.StartOver -> startOver()
            MenuAction.LockScreen -> lockScreen()
            MenuAction.ToggleIgnoreClicks -> {
                ignoreClicks = !ignoreClicks
                showFlash(Flash.Kind.Text, if (ignoreClicks) s.playerClicksIgnored else s.playerClicksWork)
            }
            MenuAction.CopyLink -> copyLink()
            is MenuAction.SwitchCopy -> switchCopy(action.copy)
            is MenuAction.SwitchQuality -> switchQuality(action.height)
            MenuAction.Download -> showFlash(Flash.Kind.Text, current.download())
            MenuAction.OpenElsewhere -> openElsewhere()
            MenuAction.Details -> showDetails = true
            MenuAction.Shortcuts -> showShortcuts = true
            MenuAction.ToggleWatched -> toggleWatched()
            MenuAction.AddToList -> if (hasMessage(item)) {
                listPicking = com.tmplayer.data.WatchLaterRecord.of(item, current.chatTitle, System.currentTimeMillis())
            }
            MenuAction.SearchOnline -> {
                onlineMsone = false
                onlineSubMaker = false
                showOnline = true
            }
            MenuAction.SearchMsone -> {
                onlineMsone = true
                onlineSubMaker = false
                showOnline = true
            }
            MenuAction.SearchSubMaker -> {
                onlineMsone = false
                onlineSubMaker = true
                showOnline = true
            }
            is MenuAction.ChatSubtitle -> scope.launch {
                val here = current.item
                showFlash(Flash.Kind.Text, L.onlineDownloading)
                val loaded = try {
                    com.tmplayer.data.ChatSubtitleLoad.load(
                        com.tmplayer.data.LiveChatSubtitleCalls, here.chatId, action.hit, here.fileName.ifBlank { here.title },
                        here.caption, java.util.Locale.getDefault().language,
                    )
                } catch (e: kotlin.coroutines.cancellation.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    com.tmplayer.platform.JobFailures.handle("chatSubtitles", e)
                    com.tmplayer.data.ChatSubtitleLoaded.Refused(L.onlineNoticeUnreadable)
                }
                when (loaded) {
                    is com.tmplayer.data.ChatSubtitleLoaded.Refused -> {
                        showFlash(Flash.Kind.Text, loaded.reason)
                        if (loaded.reason == L.tracksInChatGone) {
                            chatHits = chatHits - action.hit
                            com.tmplayer.data.ChatSubtitleFinder.shared.forget(here.chatId, here.messageId)
                        }
                    }
                    is com.tmplayer.data.ChatSubtitleLoaded.Ready -> {
                        // mpv reads all five formats itself, a MicroDVD sub included.
                        val file = kotlinx.coroutines.withContext(Dispatchers.IO) {
                            com.tmplayer.data.ChatSubtitleLoad.write(java.io.File(com.tmplayer.desktop.DesktopPaths.cacheDir, "chat-subtitles"), here.chatId, action.hit, loaded)
                        }
                        if (file != null) OnlineSubtitles.current?.forgetPick(com.tmplayer.online.SubtitlePicks.keysOf(here))
                        if (file != null && engine.addSubtitle(file.absolutePath)) showFlash(Flash.Kind.Text, L.tracksFileLoaded(loaded.label))
                        else showFlash(Flash.Kind.Text, L.playerSubtitleLoadFailed(loaded.label))
                    }
                }
            }
            MenuAction.LoadSubtitleFile -> scope.launch {
                com.tmplayer.desktop.os.FilePicker.open(s.playerLoadSubtitleFile, *SubtitleDrop.EXTENSIONS.toTypedArray())
                    ?.let { loadSubtitle(it.path) }
                refocus()
            }
            MenuAction.AddBookmark -> addBookmark()
            is MenuAction.JumpTo -> {
                jumpTo(action.bookmark.positionMs)
                showFlash(Flash.Kind.Text, SeekMath.clock(action.bookmark.positionMs))
            }
            is MenuAction.EditBookmark -> notePrompt = NotePrompt(action.bookmark.chatId, action.bookmark.messageId, action.bookmark.positionMs, action.bookmark)
            is MenuAction.DeleteBookmark -> deleteBookmark(action.bookmark)
        }
    }

    // ---- the callbacks the drawing hands its parts, as the body wrote them inline ---------------

    fun closeEpisodes() {
        showEpisodes = false
        refocus()
    }

    fun closeMenu() {
        menu = null
        refocus()
    }

    fun openEpisodes() {
        menu = null
        showEpisodes = true
    }

    fun toggleRemaining() {
        showRemaining = !showRemaining
        val value = showRemaining
        playerScope.launch { runCatching { settings.updateTouchPrefs { it.copy(showRemaining = value) } } }
    }

    /** A hold on the picture: the phone's "Hold to speed up", and the speed before it back on letting go. */
    fun hold(on: Boolean) {
        if (on && !ignoreClicks && holdSpeed > 0f && status.playing && speedBeforeHold == null) {
            // Not remembered as the series' speed, as a pick in the speed menu is.
            speedBeforeHold = status.speed
            engine.setSpeed(holdSpeed)
            showFlash(Flash.Kind.Text, "${TouchPrefs.holdLabel(holdSpeed)}  \u25B6\u25B6")
        } else if (!on) {
            speedBeforeHold?.let { engine.setSpeed(it) }
            speedBeforeHold = null
        }
    }

    suspend fun onlineTarget(): SubtitleTarget {
        // The details' ids let MSone be searched exactly; a slow lookup only leaves the title.
        val ids = kotlinx.coroutines.withTimeoutOrNull(3_000) {
            kotlinx.coroutines.withContext(Dispatchers.IO) { OnlineMetadata.current?.idsFor(item) }
        } ?: com.tmplayer.online.VideoIds()
        return SubtitleTarget(
            fileName = item.fileName.ifBlank { item.title },
            sizeBytes = item.sizeBytes,
            hash = current.onlineHash(),
            caption = item.caption.ifBlank { null },
            languages = OnlineSubtitles.languagesFor(null),
            imdb = ids.imdb,
            tmdb = ids.tmdb,
            durationMs = engine.state.value.durationMs.takeIf { it > 0 },
        )
    }

    fun translateOnline(hit: com.tmplayer.online.SubtitleHit, wanted: SubtitleTarget): Boolean {
        val online = OnlineSubtitles.current
        translatingVideo = wanted.videoKey
        val started = online?.translate(wanted.videoKey, hit, wanted.durationMs) { file, final ->
            scope.launch {
                // The same file reloads the one track in place; the finished file is another name.
                if (engine.replaceSubtitle(file.absolutePath) && final) {
                    OnlineSubtitles.current?.rememberPick(com.tmplayer.online.SubtitlePicks.keysOf(item), hit, file)
                    showFlash(Flash.Kind.Text, L.submakerDone(hit.translateTo))
                }
            }
        } == true
        if (started) showFlash(Flash.Kind.Text, L.submakerStarted(hit.translateTo))
        return started
    }

    fun loadOnline(file: java.io.File, label: String) {
        if (engine.addSubtitle(file.absolutePath)) showFlash(Flash.Kind.Text, L.tracksFileLoaded(label))
        else showFlash(Flash.Kind.Text, L.playerSubtitleLoadFailed(label))
    }

    fun pickSubtitleFile() {
        showOnline = false
        scope.launch {
            com.tmplayer.desktop.os.FilePicker.open(s.playerLoadSubtitleFile, *SubtitleDrop.EXTENSIONS.toTypedArray())
                ?.let { loadSubtitle(it.path) }
            refocus()
        }
    }

    fun detailRows(datacenter: Int?): List<Pair<String, String>> = buildList {
        addAll(engine.details())
        add(s.playerSpeed to SeekMath.speedLabel(engine.state.value.speed))
        if (item.sizeBytes > 0) add(s.playerDetailsLabelFile to com.tmplayer.player.StreamStats.formatBytes(item.sizeBytes))
        datacenter?.let { add(s.playerDetailsLabelDatacenter to "DC $it") }
        downloaded?.let {
            val label = if (current.isDownload) s.playerDetailsLabelDownloaded else s.playerDetailsLabelCached
            add(label to s.messages.formatter.percent(it.toDouble()))
        }
        add("TDLib" to (tdlibVersion ?: if (item.chatId != 0L) s.playerDetailsUnknown else s.playerDetailsNotUsed))
    }

    fun retryOpening() {
        scope.launch { runCatching { com.tmplayer.data.Td.retryConnecting() } }
        attempt++
        refocus()
    }

    fun downloadFirst() {
        current.download()
        onBack()
    }

    fun openingSignals(): com.tmplayer.player.OpeningProgress.Signals {
        val st = engine.state.value
        val it = current.item
        return com.tmplayer.player.OpeningProgress.Signals(
            readBytes = StreamReads.delivered.get() - openingBytesAt[0],
            onDisk = (downloaded ?: 0f) >= 1f || !current.fromTelegram,
            opened = st.opened,
            bufferedMs = if (st.opened) (st.bufferedMs - st.positionMs).coerceAtLeast(0) else -1L,
            bufferTargetMs = MPV_START_BUFFER_MS,
            bytesPerSec = if (it.durationSec > 0) it.sizeBytes / it.durationSec else 0L,
        )
    }

    fun retryStall() {
        // The connections reopened and the stream asked for again from where it stands.
        scope.launch { runCatching { com.tmplayer.data.Td.retryConnecting() } }
        engine.seekToExact(liveStatus.value.positionMs)
        refocus()
    }

    fun retry() {
        attempt++
        refocus()
    }

    fun watchAgain() {
        if (engine.state.value.opened) {
            phase = Phase.Playing
            engine.seekTo(0)
            engine.play()
        } else {
            // "Still watching?" stopped the engine at the end (the sleep timer), and
            // a stopped engine has no file left to seek in: open it again from the start.
            reopenAt = 0L
            attempt++
        }
        refocus()
    }

    fun cancelNext() {
        phase = Phase.Finished
        refocus()
    }

    fun setAutoplay(on: Boolean) {
        autoplayNext = on
        playerScope.launch { runCatching { settings.setAutoplayNext(on) } }
    }

    // ---- what the body drew, in its order -------------------------------------------------------
    // Each part reads this composition's values, and hands its own parts callbacks that go through
    // [latest], the newest session: a callback that captured nothing but remembered states before
    // the split is again one object from one composition to the next, so a part whose values have
    // not changed is skipped as it was (a mouse move recomposes the screen, not the whole overlay).

    /** The picture, or the casting panel, and the clicks, holds and drags on it. */
    @Composable
    @NonRestartableComposable
    fun BoxScope.PictureLayer(latest: androidx.compose.runtime.State<PlayerSession>) {
        val p by latest
        // A resized window, another picture size or shape: the zoom stays, its move held inside.
        LaunchedEffect(videoBox, status.videoWidth, status.videoHeight, status.scale) {
            if (zoom.zoomed) applyZoom(zoom, say = false)
        }
        val casting = activeEngine as? com.tmplayer.desktop.cast.CastPlaybackEngine
        if (casting == null) {
            (engine.local as? MpvPlaybackEngine)?.let { MpvMediampPlayerSurface(it.player, Modifier.fillMaxSize()) }
        } else {
            CastingPanel(casting, status, onPlayHere = { p.playHere() })
        }

        VideoGestures(
            onClick = {
                if (!p.ignoreClicks) p.togglePlay()
                p.refocus()
            },
            onDoubleClick = { if (!p.ignoreClicks) p.onToggleFullscreen() },
            onRightClick = { at -> p.menu = if (p.menu?.panel == true) null else MenuAt(MenuPage.Main, MenuAt.Anchor.Cursor, at) },
            onMouseBack = onBack,
            scope = scope,
            canPan = { p.zoom.zoomed && p.zoomAllowed() },
            onPan = { delta -> p.zoomFrame()?.let { frame -> p.applyZoom(p.zoom.panBy(delta.x, delta.y, frame), say = false) } },
            onHold = { on -> p.hold(on) },
        )
    }

    /** The controls: the bar, the title, the buttons and their menus. */
    @Composable
    @NonRestartableComposable
    fun BoxScope.OverlayLayer(latest: androidx.compose.runtime.State<PlayerSession>, title: String, subtitle: String) {
        val p by latest
        val liveStatus = liveStatus
        val castPresence = castPresence
        CompositionLocalProvider(
            LocalPlayerCopies provides PlayerCopies(copies, current.item.id, qualities, current.item.playingQuality),
            LocalChatSubtitles provides chatHits,
            LocalStoryboard provides storyboard,
            LocalFrameSource provides frameSource,
            LocalSeekCover provides seekCover,
        ) { PlayerOverlay(
            visible = showControls,
            status = status,
            positionMs = { liveStatus.value.positionMs },
            bufferedMs = { liveStatus.value.bufferedMs },
            title = title,
            subtitle = subtitle,
            episodes = episodes,
            tracks = tracks,
            downloaded = downloaded,
            isDownload = current.isDownload,
            showRemaining = showRemaining,
            fullscreen = fullscreen,
            menu = menu,
            ignoreClicks = ignoreClicks,
            miniPlayerAvailable = onMiniPlayer != null,
            alwaysOnTopAvailable = onToggleAlwaysOnTop != null,
            fromTelegram = current.fromTelegram,
            savable = savable,
            addToList = watched != null && current.fromTelegram && hasMessage(item),
            subtitleThisVideo = if (hasMessage(item)) styleThisVideo else null,
            watchedLabel = if (watched != null && current.fromTelegram && hasMessage(item)) {
                WatchedWords.markLabel(SettingsStore.progressKey(item.chatId, item.messageId) in watchedKeys)
            } else {
                null
            },
            onHoverControls = { p.overControls = it },
            onBack = onBack,
            onTogglePlay = { p.togglePlay() },
            onSeekBy = { p.dispatch(PlayerAction.SeekBy(it)) },
            onSeekTo = { p.jumpTo(it) },
            onEpisode = { p.switchTo(it) },
            onToggleRemaining = { p.toggleRemaining() },
            onVolume = { p.setVolume(it) },
            onToggleMute = { p.toggleMute() },
            onToggleFullscreen = onToggleFullscreen,
            onMiniPlayer = { p.dispatch(PlayerAction.MiniPlayer) },
            onOpenMenu = { p.menu = it },
            onCloseMenu = { p.closeMenu() },
            sleepTimer = sleepTimerDetail(),
            loop = abLoop,
            repeatVideo = repeatVideo,
            markIntro = if (skipCtl.canMark) (if (introMarking) s.playerMarkIntroEnd else s.playerMarkIntro) else null,
            bookmarks = if (marksOn) marks else null,
            onMenuAction = { action -> p.menuAction(action) },
            onEpisodes = if (episodes.series != null) {
                { p.openEpisodes() }
            } else {
                null
            },
            castButton = { CastButton(castPresence) },
            subtitlesButton = com.tmplayer.cast.CAST_SUBTITLES || activeEngine !is com.tmplayer.desktop.cast.CastPlaybackEngine,
        ) }
    }

    /** The flash, and the notices, pills, chips and cards that come and go over the picture. */
    @Composable
    @NonRestartableComposable
    fun BoxScope.CardsLayer(latest: androidx.compose.runtime.State<PlayerSession>) {
        val p by latest
        val next = next
        FeedbackLayer(
            flash = flash,
            spinner = phase == Phase.Playing && status.buffering && !showControls,
            chip = if (phase == Phase.Playing && status.buffering && !showControls) s.playerBuffering else null,
        )

        // A button that goes away as it is clicked takes the keyboard with it, so each of
        // these hands the keys back to the player, as the skip pill does.
        resumedFrom?.let { from ->
            ResumeNotice(
                from = from,
                lifted = showControls,
                onStartOver = {
                    p.startOver()
                    p.refocus()
                },
                onTimeout = { p.resumedFrom = null },
            )
        }

        activeSkip?.let { segment ->
            val counting = skipOffer as? SkipOffer.Counting
            SkipPill(
                segment = segment,
                lifted = showControls,
                secondsLeft = counting?.secondsLeft,
                onCancel = {
                    p.cancelAutoSkip()
                    p.refocus()
                },
                onSkip = {
                    p.skipSegment()
                    p.refocus()
                },
            )
        }

        // Not while the screen is locked: nothing under the shield takes a click.
        undoOffer?.takeIf { phase == Phase.Playing && !screenLocked }?.let { offer ->
            key(offer.backToMs) {
                UndoSeekChip(backToMs = offer.backToMs, lifted = showControls, onUndo = {
                    p.undoSeek()
                    p.refocus()
                })
            }
        }

        if (nextUpVisible && next != null) {
            NextUpCard(
                label = episodes.labelFor(next),
                secondsLeft = nextUpSeconds,
                lifted = showControls,
                onPlayNow = {
                    p.switchTo(next)
                    p.refocus()
                },
                onHide = {
                    p.nextUpDismissed = true
                    p.refocus()
                },
            )
        }
    }

    /** The online subtitles, Playback details, the loader, the stall and the status sheet. */
    @Composable
    @NonRestartableComposable
    fun BoxScope.SheetsLayer(latest: androidx.compose.runtime.State<PlayerSession>, title: String, subtitle: String) {
        val p by latest
        val liveStatus = liveStatus
        val ui = ui
        /** "Play Telegram's 480p" on the sheet that says nothing is arriving: the next copy down. */
        val lighter: com.tmplayer.data.TelegramQuality? = run {
            val here = current.item
            val source = post ?: here
            if (qualities.isEmpty()) null else TelegramQualities.lighter(here.copy(qualities = source.qualities))
        }

        val lighterAction: Pair<String, () -> Unit>? = lighter?.let { q ->
            s.playerLighter(TelegramQualities.label(q.height)) to { p.switchQuality(q.height) }
        }

        if (showOnline) {
            OnlineSubtitlesPanel(
                target = { p.onlineTarget() },
                onTranslate = { hit, wanted -> p.translateOnline(hit, wanted) },
                onLoaded = { file, label -> p.loadOnline(file, label) },
                onClose = {
                    p.showOnline = false
                    p.refocus()
                },
                onPicked = { hit, file ->
                    OnlineSubtitles.current?.rememberPick(com.tmplayer.online.SubtitlePicks.keysOf(p.item), hit, file)
                },
                onPickFile = { p.pickSubtitleFile() },
                msone = onlineMsone,
                submaker = onlineSubMaker,
                preset = onlinePreset,
            )
        }

        // The Telegram datacenter the file is kept in (EG-F61): read from its remote id when the
        // details are opened, a local call; nothing for a local file or an id that does not say.
        var datacenter by remember(item.fileId) { mutableStateOf<Int?>(null) }
        LaunchedEffect(showDetails, item.fileId) {
            if (showDetails && item.fileId > 0 && item.chatId != 0L && datacenter == null) {
                datacenter = runCatching { com.tmplayer.data.Td.remoteDatacenter(item.fileId) }.getOrNull()
            }
        }
        if (showDetails) {
            DetailsPanel(
                rows = { p.detailRows(datacenter) },
                onClose = {
                    p.showDetails = false
                    p.refocus()
                },
            )
        }

        LoaderSheet(
            phase = phase,
            item = current.item,
            downloaded = downloaded,
            bufferedMs = { liveStatus.value.let { (it.bufferedMs - it.positionMs).coerceAtLeast(0) } },
            isDownload = current.isDownload,
            onBack = onBack,
            thumbnailOverride = loaderThumbnail,
            // Nothing arriving: the connections reopened and the file opened again.
            onRetry = { p.retryOpening() },
            // Kept in the queue, which carries on once the player lets go of the file.
            onDownloadFirst = if (savable && !current.isDownload) {
                { p.downloadFirst() }
            } else {
                null
            },
            lighter = lighterAction,
            startMs = openingStartMs,
            // What the opening measures: mpv's reads, whether it has opened, its cache ahead.
            signals = { p.openingSignals() },
        )

        // A stall while playing that runs on says why, with the opening's three ways on.
        PlaybackStall(
            buffering = phase == Phase.Playing && status.buffering && !status.seekPending,
            bufferedMs = { liveStatus.value.bufferedMs },
            downloaded = downloaded,
            onRetry = { p.retryStall() },
            onDownloadFirst = if (savable && !current.isDownload) {
                { p.downloadFirst() }
            } else {
                null
            },
            onBack = onBack,
            lighter = lighterAction,
        )

        StatusSheet(
            phase = phase,
            title = title,
            subtitle = subtitle,
            onRetry = { p.retry() },
            onBack = onBack,
            onFreeUpSpace = onFreeUpSpace,
            onWatchAgain = { p.watchAgain() },
            onPlayNext = {
                p.switchTo(it)
                p.refocus()
            },
            onCancelNext = { p.cancelNext() },
            onKeepWatching = { (p.phase as? Phase.StillWatching)?.let { p.keepWatching(it) } },
            nextLabel = { ui.episodes.labelFor(it) },
        )
    }

    /** The episode list, the dialogs, the "?" sheet and, over everything, the lock's shield. */
    @Composable
    @NonRestartableComposable
    fun BoxScope.DialogsLayer(latest: androidx.compose.runtime.State<PlayerSession>) {
        val p by latest
        val series = episodes.series
        if (showEpisodes && series != null) {
            val watch = remember(progressMap, watchedKeys) {
                com.tmplayer.ui.browse.SeriesWatch(
                    point = { progressMap[SettingsStore.progressKey(it.chatId, it.messageId)] },
                    finished = { SettingsStore.progressKey(it.chatId, it.messageId) in watchedKeys },
                )
            }
            EpisodesSheet(
                state = com.tmplayer.ui.player.EpisodesState(
                    series = series,
                    playing = item,
                    previous = episodes.previous,
                    previousLabel = episodes.previous?.let { s.playerPreviousUp(episodes.labelFor(it)) },
                    next = episodes.next,
                    nextLabel = episodes.next?.let { s.playerNextUp(episodes.labelFor(it)) },
                    previousCode = episodes.previousTag?.code,
                    nextCode = episodes.nextTag?.code,
                    autoplay = autoplayNext,
                ),
                watch = watch,
                actions = com.tmplayer.ui.player.EpisodesActions(
                    onPlay = { target ->
                        p.closeEpisodes()
                        if (target.id != p.item.id) p.switchTo(target)
                    },
                    onToggleWatched = { p.toggleWatchedOf(it) },
                    onAutoplay = { on -> p.setAutoplay(on) },
                ),
                onClose = { p.closeEpisodes() },
            )
        }

        notePrompt?.let { prompt ->
            BookmarkNoteDialog(
                prompt = prompt,
                onSave = { note ->
                    p.notePrompt = null
                    p.saveNote(prompt, note)
                    p.refocus()
                },
                onDismiss = {
                    p.notePrompt = null
                    p.refocus()
                },
            )
        }

        listPicking?.let { record ->
            // Nothing to write to without the store: the line is not offered then, so this stays shut.
            watched?.let { store ->
                com.tmplayer.ui.browse.ListPickerDialog(
                    record = record,
                    lists = store.lists,
                    onMessage = { line -> p.showFlash(Flash.Kind.Text, line) },
                    onDismiss = {
                        p.listPicking = null
                        p.refocus()
                    },
                )
            }
        }

        if (timePrompt) {
            GoToTimeDialog(
                durationMs = status.durationMs,
                positionMs = liveStatus.value.positionMs,
                onGo = { target ->
                    p.timePrompt = false
                    p.jumpTo(target)
                    p.showFlash(Flash.Kind.Text, SeekMath.clock(target))
                    p.refocus()
                },
                onDismiss = {
                    p.timePrompt = false
                    p.refocus()
                },
            )
        }

        if (showShortcuts) {
            ShortcutSheet(
                onClose = {
                    p.showShortcuts = false
                    p.refocus()
                },
                mac = mac,
                wheelSeeks = desktop.wheelSeeks,
            )
        }

        // Over everything, the controls and the skip and next-up cards included.
        if (screenLocked) {
            LockShield(
                showUnlock = showUnlock,
                holding = unlockHold != null,
                onWake = {
                    p.noteInput()
                    p.unlockWanted = System.nanoTime()
                },
                onUnlock = { p.unlockScreen() },
            )
        }
    }
}

/** The screen's first states and effects, in the order the body always ran them (HX1 split). */
@Composable
@NonRestartableComposable
private fun PlayerSession.Gather() {
    // One search for the Cast button per player session (FQ8), not one per reveal of the controls.
    castPresence = rememberCastBarPresence()
    // mpv on this computer, or the TV while a cast is on (CP73): a video opened with a session up
    // goes to the TV from the start.
    // When libmpv cannot load (Windows blocked it), a stand-in whose reason the status sheet shows (HX36).
    engine = remember {
        val local = try {
            engineFactory()
        } catch (e: VideoEngineUnavailable) {
            engineBlocked[0] = e.message ?: VideoEngineFailure.describe(e)
            NoEngine(engineBlocked[0].orEmpty())
        }
        com.tmplayer.desktop.cast.SwitchingEngine(local).also { e ->
            (com.tmplayer.desktop.cast.DesktopCast.state.value as? com.tmplayer.desktop.cast.DesktopCast.State.Connected)?.let { c ->
                e.switchTo(com.tmplayer.desktop.cast.CastPlaybackEngine(c.receiver) { castTitle[0] })
            }
        }
    }
    desktopState = prefs.state.collectAsState()
    DisposableEffect(engine) { onDispose { engine.close() } }
    LaunchedEffect(engine) { onEngine(engine) }

    scope = rememberCoroutineScope()
    activeEngineState = engine.activeFlow.collectAsState()
    // Every update of the engine, the clock's four a second among them. Read in handlers, in the
    // derived values below and by the small parts that draw the time, never in this body, so a
    // tick of the clock recomposes the time label and the bar rather than the whole screen.
    liveStatus = activeEngine.state.collectAsState()
    // The same status with the position and the buffer's end left as they were, which is what the
    // body reads: it changes when something other than the clock does (SteadyStatus).
    statusState = remember(liveStatus) { derivedStateOf(SteadyStatus) { liveStatus.value } }

    tracksState = activeEngine.tracks.collectAsState()
    castStateState = com.tmplayer.desktop.cast.DesktopCast.state.collectAsState()

    // Whether this video is being watched, for the downloads that step aside while one plays from
    // the network (DownloadYield). A cast counts, as its engine reports playing too.
    val watching = status.playing && !status.ended
    LaunchedEffect(watching, current) {
        val ids = listOf(current.item.fileId) + current.item.pieces.map { it.fileId }
        com.tmplayer.data.StreamingPlays.setPlaying(ids, watching)
    }
    // A failure once the video is playing (mpv lost the stream, a decoder gave up) ends in the error
    // sheet too, with Try again, instead of a still picture and nothing said (HX10). The open's own
    // failure is read where it opens.
    LaunchedEffect(engine) {
        engine.state.map { it.error }.collect { error ->
            if (error != null && phase == Phase.Playing && engine.cast == null) {
                phase = Phase.Failed(error, format = engine.state.value.failedFormat, details = engine.state.value.failedDetails)
            }
        }
    }
    // Every copy of the video playing that the player knows of: what the opening page handed over
    // (CopyHandoff, possibly across chats) and, for an episode, its copies once the show is found.
    copies = remember(current.item.id, episodes) {
        val here = current.item
        val episode = episodes.series?.episodes?.firstOrNull { ep -> ep.copies.any { it.id == here.id } }
        // The same file posted twice is one copy to switch to, not two.
        com.tmplayer.data.VideoCopies.oneOfEachFile((CopyHandoff.copiesOf(here) + episode?.copies.orEmpty() + here).distinctBy { it.id }, here.id)
    }
    LaunchedEffect(current.item.id) {
        val here = current.item
        runCatching { settings.notePlayedQuality(copies.firstOrNull { it.id == here.id } ?: here) }
    }
    LaunchedEffect(current.item.chatId, current.item.messageId) {
        val here = current.item
        if (!hasMessage(here) || !current.fromTelegram) return@LaunchedEffect
        if (here.qualities.isNotEmpty() || here.storyboard != null) {
            post = here
            return@LaunchedEffect
        }
        post = runCatching {
            com.tmplayer.data.Td.awaitAuthorizedSession()
            com.tmplayer.data.Td.refreshMedia(here.chatId, here.messageId)
        }.getOrNull()
    }
    qualities = if (current.isDownload || current.item.isJoined) emptyList() else (post?.qualities ?: current.item.qualities)
    // Telegram's storyboard (CP80): the timebar's hover pictures, where scrub pictures are on.
    val scrubSetting by remember(settings) { settings.trickplay }.collectAsState(initial = true)
    val scrubPictures = scrubSetting && com.tmplayer.data.Trickplay.PICTURES_SHOWN
    val boardFiles = (post ?: current.item).storyboard
    storyboard = remember(boardFiles, scrubPictures) {
        boardFiles?.takeIf { scrubPictures }?.let { files -> DesktopStoryboard(files, durationMs = { engine.state.value.durationMs.takeIf { it > 0 } ?: current.item.durationSec * 1000L }) }
    }
    // Fetched as the video opens, in the player's lifetime rather than the hover's, so the sprite
    // is in by the time the pointer reaches the bar (a fetch from inside the hover was cancelled,
    // and TDLib's download withdrawn, each time the pointer moved on).
    LaunchedEffect(storyboard) { runCatching { storyboard?.warm() }.onFailure { if (it is kotlinx.coroutines.CancellationException) throw it } }
    // The video's own frames for the hover where the storyboard has none: a second decoder over
    // what is on the disk (see [PlaybackEngine.previewFrame]).
    frameSource = remember(current.item.id, scrubPictures) {
        if (!scrubPictures) {
            null
        } else {
            FrameSource(durationMs = { engine.state.value.durationMs.takeIf { it > 0 } ?: current.item.durationSec * 1000L }) { at ->
                engine.previewFrame(at, FRAME_WIDTH_PX, FRAME_HEIGHT_PX)
            }
        }
    }
    seekCover = remember(current.item.id) { SeekCover(current.item.thumbnailFileId, current.item.miniThumbnail) }
    // The files are looked for the first time the menu opens on Subtitles or the main page that leads there.
    val subtitleMenuOpen = menu?.page == MenuPage.Subtitles || menu?.page == MenuPage.Main
    LaunchedEffect(subtitleMenuOpen, current.item.chatId, current.item.messageId) {
        val here = current.item
        if (!subtitleMenuOpen || chatAsked || !hasMessage(here) || !current.fromTelegram) return@LaunchedEffect
        chatAsked = true
        chatHits = try {
            (com.tmplayer.data.ChatSubtitleFinder.shared.find(
                here.chatId, here.messageId, here.fileName.ifBlank { here.title }, here.caption,
                OnlineSubtitles.languagesFor(null),
            ) as? com.tmplayer.data.ChatSubtitleFound.Hits)?.hits.orEmpty()
        } catch (e: kotlin.coroutines.cancellation.CancellationException) {
            throw e
        } catch (e: Exception) {
            com.tmplayer.platform.JobFailures.handle("chatSubtitles", e)
            emptyList()
        }
    }
    downloadedState = current.downloaded.collectAsState()
    savableState = current.savable.collectAsState()
    // The Watched list as it stands, for which way the menu's mark line reads.
    watchedKeysState = remember(watched) { watched?.watched ?: flowOf(emptyMap()) }
        .collectAsState(initial = emptyMap())
    DisposableEffect(item.fileId) {
        onDispose {
            translatingVideo?.let { video -> OnlineSubtitles.current?.let { it.translations.cancelVideo(it.translationPrefix(video)) } }
            translatingVideo = null
        }
    }
    seriesKey = remember(item) { seriesKeyOf(item) }
    // Where a speed picked for this video is remembered, and read back from. See [SpeedMemory].
    speedScopes = remember(item, seriesKey) { SpeedMemory.scopesOf(item, seriesKey) }
    progressMapState = remember(settings) { settings.watchProgress }.collectAsState(initial = emptyMap())
    // This video's bookmarks, for the menu's list and the ticks on the timebar. A file with no
    // message behind it (the dev harness) has nothing to key them by, so it has none.
    marksState = remember(bookmarks, item.chatId, item.messageId) {
        if (bookmarks != null && hasMessage(item)) bookmarks.bookmarks(item.chatId, item.messageId) else flowOf(emptyList())
    }.collectAsState(initial = emptyList())
    LaunchedEffect(undoOffer) {
        val offer = undoOffer ?: return@LaunchedEffect
        delay((offer.untilMs - System.currentTimeMillis()).coerceAtLeast(0))
        undoOffer = seekUndo.current(System.currentTimeMillis())
    }
    skipSay[0] = { showFlash(Flash.Kind.Text, it) }
}

/** The skip times, the opening, casting and the resume writes (HX1 split, same order). */
@Composable
@NonRestartableComposable
private fun PlayerSession.Opening() {
    // ---- open, resume, episodes --------------------------------------------------------------

    // The skip times, asked once per video and not again on a retry. With lookups off this
    // returns at once with nothing, and no request leaves the machine.
    LaunchedEffect(current) {
        skips = try {
            current.skipTimes()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Logger.w("PlayerScreen", "Skip times lookup failed", e)
            emptyList()
        }
        skipCtl.online(skips)
    }
    // The file's own chapters are the first and best source: free, exact, and offline.
    LaunchedEffect(skipCtl, status.chapters) { skipCtl.chapters(status.chapters) }

    LaunchedEffect(current, attempt) {
        // No video engine: nothing to open, and Try again says the same until the app restarts.
        engineBlocked[0]?.let {
            phase = Phase.Failed(it)
            return@LaunchedEffect
        }
        phase = Phase.Loading(if (item.chatId != 0L) s.playerConnectingTelegram else s.playerOpeningVideo)
        openingBytesAt[0] = StreamReads.delivered.get()
        openingStartMs = 0L
        // Telegram's own lighter copy (CP80), chosen before anything is opened: the one asked for
        // on the detail page or the copies menu, else one the line measured on this run carries
        // when it is too slow for the upload. Never for a download or an upload already here.
        if (attempt == 0 && reopenAt == null && current.fromTelegram && hasMessage(item) && item.playingQuality == 0 && !item.isJoined) {
            val wish = QualityWish.take(item.chatId, item.messageId)
            if (wish != 0) {
                val fresh = kotlinx.coroutines.withTimeoutOrNull(POST_READ_MS) {
                    post ?: runCatching { com.tmplayer.data.Td.awaitAuthorizedSession(); com.tmplayer.data.Td.refreshMedia(item.chatId, item.messageId) }.getOrNull()?.also { post = it }
                }
                val choice = when {
                    fresh == null || fresh.qualities.isEmpty() -> null
                    wish != null -> fresh.qualities.firstOrNull { it.height == wish }?.let { TelegramQualities.Choice(it, TelegramQualities.Why.SlowLine) to false }
                    com.tmplayer.desktop.DownloadIndex.fileFor(settings, item.chatId, item.messageId) != null -> null
                    runCatching { com.tmplayer.data.Td.localDownloadedBytes(item.fileId) }.getOrDefault(0L) > 0 -> null
                    else -> TelegramQualities.choose(fresh.copy(fileId = item.fileId, sizeBytes = item.sizeBytes.takeIf { it > 0 } ?: fresh.sizeBytes), saveData = false, lineBytesPerSec = com.tmplayer.player.LineSpeed.recent())?.let { it to true }
                }
                if (choice != null && fresh != null) {
                    val (picked, automatic) = choice
                    val upload = item.copy(qualities = fresh.qualities, storyboard = fresh.storyboard, startSec = fresh.startSec)
                    if (automatic) showFlash(Flash.Kind.Text, s.playerLighterPickedSlow(TelegramQualities.label(picked.quality.height)))
                    neverOpened[0] = current
                    current = current.episode(TelegramQualities.withQuality(upload, picked.quality))
                    return@LaunchedEffect
                }
            }
        }
        nextUpDismissed = false
        nextUpShown = false
        resumedFrom = null
        // The head and the index on their way while the settings below are read, for a stream
        // reached any way (the next episode, a reopen) ([com.tmplayer.player.StreamWarmup]).
        val openStartedAt = System.currentTimeMillis()
        if (current.fromTelegram && !item.isJoined && item.fileId > 0) {
            launch {
                if (com.tmplayer.desktop.DownloadIndex.fileFor(settings, item.chatId, item.messageId) == null) {
                    // From the saved place too, as the opening below reads it, unless Start over.
                    com.tmplayer.player.StreamWarmup.warm(
                        item,
                        if (ignoreSavedPosition) null else ({ settings.resumeStartFor(TelegramQualities.upload(item)) }),
                    )
                }
            }
        }
        launch {
            // What the file says of itself straight away, for the title; the chat's answer, then
            // the providers' episode names where lookups are on.
            episodes = Episodes(current = EpisodeNeighbours.tagOf(item))
            val found = runCatching { current.episodes() }.getOrDefault(episodes)
            episodes = found
            val named = runCatching {
                kotlinx.coroutines.withContext(Dispatchers.IO) { EpisodeNames.named(found, item, OnlineMetadata.current) }
            }.getOrDefault(found)
            if (named != found) episodes = named
        }
        launch { tdlibVersion = current.tdlibVersion() }
        // A Play all or Shuffle run goes on whatever Autoplay says (WP-32); the menu's switch still stops it.
        autoplayNext = current.playsAll || runCatching { settings.autoplayNextNow() }.getOrDefault(true)
        keepRepeat = runCatching { settings.keepRepeatNow() }.getOrDefault(false)
        repeatVideo = com.tmplayer.data.RepeatVideo.repeats(current.item, keepRepeat)
        showRemaining = runCatching { settings.touchPrefsNow().showRemaining }.getOrDefault(false)
        // A saved position opens a few seconds early, as Settings has it ([ResumeRewind]); Keep
        // watching reopens exactly where the file stopped, and a bookmark chosen on the detail
        // page opens the video at its moment, once.
        val start = reopenAt ?: BookmarkJump.take(item.chatId, item.messageId) ?: if (ignoreSavedPosition) {
            // Start over forgets the old place: a short watch from here leaves a saved one alone.
            runCatching { settings.clearResumePosition(item.chatId, item.messageId, item) }
            0L
        } else runCatching {
            // Resume across devices, when on: the other devices' places in first, briefly.
            com.tmplayer.data.ResumeSync.pullFor(settings)
            // Whichever copy of the video was played last: a switch of quality carries on.
            settings.resumeStartFor(TelegramQualities.upload(item))
                // Never watched here: the moment the poster chose to start at (CP80), if any.
                .takeIf { it > 0 }
                ?: PostStart.startMs(linkSec = 0, savedMs = 0, postSec = (post ?: item).startSec, durationSec = item.durationSec)
        }.getOrDefault(0L)
        val reopening = reopenAt != null
        reopenAt = null
        openingStartMs = start
        trackChoice = runCatching { settings.trackChoice(seriesKey) }.getOrDefault(TrackChoice())
        // A resumed video goes back to the tracks and speed it was playing with. See [ResumeState].
        val resumed = if (start > 0 && hasMessage(item)) {
            runCatching { settings.resumeRecord(item.chatId, item.messageId)?.state }.getOrNull()
        } else {
            null
        }
        // Offsets are remembered per message; a file on disk (the dev harness) starts in step.
        val delays = if (hasMessage(item)) {
            runCatching { settings.syncDelays(item.chatId, item.messageId) }.getOrDefault(SyncDelays())
        } else {
            SyncDelays()
        }
        // The file's saved tracks, then the series, then Settings, then the file's own defaults.
        val trackPlan = TrackRules.plan(
            resumed,
            trackChoice,
            runCatching { settings.trackPreferencesNow() }.getOrDefault(TrackPreferences()),
            Translator.messages.tag,
        )
        val ownLook = if (hasMessage(item)) runCatching { settings.videoSubtitleStyle(item.chatId, item.messageId) }.getOrNull() else null
        styleThisVideo = ownLook != null
        val prefs = OpenPrefs(
            audioLanguage = trackPlan.audioLanguage,
            audioMore = trackPlan.audioMore,
            subtitles = trackPlan.subtitles,
            // Its own speed, else the series' or course's, else the chat's or topic's: [SpeedMemory].
            speed = runCatching { settings.openingSpeed(resumed?.speed, speedScopes) }
                .getOrDefault(resumed?.speed ?: PlaybackSpeed.DEFAULT)
                .coerceIn(SeekMath.MIN_SPEED, SeekMath.MAX_SPEED),
            // A fixed shape chosen in this player carries to the next episode; a new player starts
            // from the last of Original, Fill and Stretch (HX16).
            scale = heldShape[0] ?: runCatching { VideoScale.remembered(settings.videoScaleNow()) }.getOrDefault(VideoScale.Fit),
            downmix = prefs.now.downmix,
            volume = prefs.now.volume,
            muted = prefs.now.muted,
            volumeBoost = runCatching { settings.volumeBoostNow() }.getOrDefault(false),
            nightMode = runCatching { settings.nightModeNow() }.getOrDefault(false),
            hwdec = OpenPrefs.hwdecFor(prefs.now.softwareDecoding),
            subtitleStyle = ownLook ?: runCatching { settings.subtitleStyleNow() }.getOrDefault(SubtitleStyle()),
            delays = delays,
            resume = resumed,
        )
        // A slow open (the whole video downloading first) says what it is waiting on; Back on the
        // loading screen cancels it.
        val preparing = launch { current.preparing.collect { text -> if (text != null) phase = Phase.Loading(text) } }
        val data = try {
            current.open()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Logger.w("PlayerScreen", "Could not open ${item.title}", e)
            phase = Phase.Failed(e.message ?: s.playerOpenFailed, noRoom = e is com.tmplayer.desktop.NotEnoughRoom)
            return@LaunchedEffect
        } finally {
            preparing.cancel()
        }
        phase = Phase.Loading(if (start > 0) s.playerResumingFrom(SeekMath.clock(start)) else s.playerOpeningVideo)
        Logger.i("PlayerStart", "open to media ready ${System.currentTimeMillis() - openStartedAt} ms (file ${item.fileId}, resume $start ms)")
        launch {
            // The first moment mpv is playing rather than buffering: the desktop's "first frame".
            val playing = kotlinx.coroutines.withTimeoutOrNull(120_000) {
                engine.state.first { it.opened && !it.buffering && it.error == null }
            }
            if (playing != null) Logger.i("PlayerStart", "open to playing ${System.currentTimeMillis() - openStartedAt} ms (file ${item.fileId})")
        }
        // Every file opens whole; the engine puts mpv's own zoom back as it opens.
        zoom = PictureZoom.NONE
        // What the file declares goes to the badges on its details page once mpv lists its tracks.
        // Not for Telegram's lighter copy, whose picture is not the upload the page is about.
        if (item.playingQuality == 0) engine.probeFor(item.id)
        engine.open(data, start, prefs)
        val error = engine.state.value.error
        // On a TV the casting panel says what went wrong, with Play here beside it.
        if (error != null && engine.cast == null) {
            phase = Phase.Failed(error, format = engine.state.value.failedFormat, details = engine.state.value.failedDetails)
            return@LaunchedEffect
        }
        ignoreSavedPosition = false
        // Keep watching opens the file again, and a fresh open has no loop in mpv.
        abLoop?.takeIf { it.complete }?.let(engine::setAbLoop)
        phase = Phase.Playing
        restoreRememberedSubtitle(item)
        // A video whose pieces stop at a gap says so as it starts.
        current.notice?.let { showFlash(Flash.Kind.Text, it) }
        // A file named as 3D says how to watch it, once, unless 3D is on already: never turned
        // on by itself, since without glasses the picture is two pictures or tinted.
        if (current.notice == null && !engine.state.value.stereo.on &&
            Stereo3d.detect(listOf(item.title, item.fileName, item.caption).joinToString(" ")) != null
        ) {
            showFlash(Flash.Kind.Text, s.playerStereoFound)
        }
        if (start > 0 && !reopening) resumedFrom = start
        onPlayingItemChanged(item)
        refocus()
    }

    // Casting (CP73): a session coming up moves the video to the TV at the place it had reached,
    // and the session ending (Stop casting, or Play here after the TV was lost) brings it back.
    LaunchedEffect(castState) {
        val cs = castState
        val casting = engine.cast
        when {
            cs is com.tmplayer.desktop.cast.DesktopCast.State.Connected && casting?.receiver !== cs.receiver -> {
                val at = engine.state.value.takeIf { it.opened }?.positionMs
                engine.switchTo(com.tmplayer.desktop.cast.CastPlaybackEngine(cs.receiver) { castTitle[0] }, tellReceiver = false)
                if (at != null) reopenAt = at
                attempt++
            }
            (cs is com.tmplayer.desktop.cast.DesktopCast.State.Idle) && casting != null -> {
                val at = casting.positionMs.takeIf { casting.state.value.opened || it > 0 }
                engine.switchTo(engine.local, tellReceiver = false)
                if (at != null) reopenAt = at
                attempt++
            }
        }
    }

    // Declared after the engine's own, so it is disposed first, while the engine still knows
    // where the old video was.
    DisposableEffect(current) {
        val leaving = current
        onDispose {
            // One handed straight on to a copy of Telegram's before it opened never played, and
            // the engine's place is still the video before it.
            if (leaving !== neverOpened[0]) saveResume(leaving, engine.state.value, leaving = true)
            leaving.release()
        }
    }

    LaunchedEffect(current) {
        while (true) {
            delay(RESUME_TICK_MS)
            if (engine.state.value.playing && phase == Phase.Playing) saveResume(current, engine.state.value)
        }
    }

    // A pause is a natural place to stop for the night: written now, since the heartbeat above
    // only beats while playing. Not the pause that comes with the end, which the end handles.
    LaunchedEffect(current, status.playing) {
        val s = engine.state.value
        if (!s.playing && !s.ended && s.opened && phase == Phase.Playing) saveResume(current, s)
    }
}

/** The end, "Still watching?", the next-up card and the skip pill (HX1 split, same order). */
@Composable
@NonRestartableComposable
private fun PlayerSession.Ending() {
    // The end: the next episode after a countdown, or the last frame with a way to watch again.
    LaunchedEffect(status.ended) {
        if (!status.ended || phase != Phase.Playing) return@LaunchedEffect
        // Repeat this video: again from the start, not watched,
        // no next-up and no count towards "Still watching?". A sleep timer for the end wins.
        if (com.tmplayer.data.RepeatVideo.replaysAtEnd(current.item, keepRepeat, sleepAtTheEnd)) {
            engine.seekToExact(0)
            engine.play()
            return@LaunchedEffect
        }
        if (sleepAtTheEnd && repeatVideo) {
            com.tmplayer.data.RepeatVideo.set(false)
            repeatVideo = false
        }
        if (current.incomplete) {
            // Only up to a missing piece: said again, nothing marked, nothing played next.
            current.notice?.let { showFlash(Flash.Kind.Text, it) }
            phase = Phase.Finished
            return@LaunchedEffect
        }
        recordWatched(current, status.durationMs, manual = false)
        runCatching { settings.clearResumePosition(item.chatId, item.messageId) }
        val next = episodes.next
        val autoplays = next != null && autoplayNext && !nextUpDismissed
        if (sleepAtTheEnd) {
            sleepAtTheEnd = false
            askStillWatching(s.playerStillSleepEnded, next.takeIf { autoplays }, ended = true)
            return@LaunchedEffect
        }
        if (next == null || !autoplays) {
            phase = Phase.Finished
            return@LaunchedEffect
        }
        if (StillWatching.askBeforeAutoplay(autoplayedInARow)) {
            askStillWatching(s.playerStillAutoplayLimit(StillWatching.AUTOPLAY_LIMIT), next, ended = true)
            return@LaunchedEffect
        }
        // The card already counted the last half minute down; a second countdown would be a wait for nothing.
        if (nextUpShown) {
            autoplay(next)
            return@LaunchedEffect
        }
        for (second in AUTOPLAY_COUNTDOWN_SEC downTo 1) {
            phase = Phase.Countdown(next, second)
            delay(1_000)
            // Cancel and Play now on the sheet end the countdown: the end has not changed, so
            // nothing else stops this loop from bringing it back and moving on regardless.
            if (phase != Phase.Countdown(next, second)) return@LaunchedEffect
        }
        autoplay(next)
    }

    // The sleep timer running out, and two hours of playback without a press. Idle time is
    // counted while playing only, so a video left paused overnight does not ask in the morning.
    LaunchedEffect(Unit) {
        while (true) {
            delay(STILL_WATCHING_TICK_MS)
            if (phase is Phase.StillWatching) continue
            if (sleepAt > 0 && System.currentTimeMillis() >= sleepAt) {
                sleepAt = 0L
                askStillWatching(s.playerStillSleepPaused, next = null, ended = phase == Phase.Finished)
                continue
            }
            if (phase == Phase.Playing && engine.state.value.playing) {
                idleMs += STILL_WATCHING_TICK_MS
                if (idleMs >= idleLimitMs) {
                    idleMs = 0L
                    askStillWatching(
                        if (idleLimitMs == StillWatching.IDLE_LIMIT_MS) s.playerStillIdleTwoHours else s.playerStillIdle,
                        next = null,
                        ended = false,
                    )
                }
            }
        }
    }

    // The card comes up in the last half minute, or where the closing credits begin when the skip
    // times know that and it is earlier: once the credits roll, the next episode is the offer.
    val creditsAt = SkipSegments.creditsStart(skips, status.durationMs)
    val nextUpLead = maxOf(NEXT_UP_LEAD_MS, creditsAt?.let { status.durationMs - it } ?: 0L)
    // What the clock decides, each derived from the live position so the body hears of it only
    // when the answer changes, not on every tick.
    val nearEnd by remember(liveStatus, nextUpLead) {
        derivedStateOf { liveStatus.value.let { it.durationMs - it.positionMs } in 1..nextUpLead }
    }
    val pastLead by remember(liveStatus, nextUpLead) {
        derivedStateOf { liveStatus.value.let { it.durationMs - it.positionMs } > nextUpLead }
    }
    nextUpVisible = phase == Phase.Playing && next != null && autoplayNext && !nextUpDismissed && !repeatVideo &&
        status.durationMs > 0 && nearEnd
    LaunchedEffect(nextUpVisible) { if (nextUpVisible) nextUpShown = true }
    LaunchedEffect(pastLead) { if (pastLead) nextUpShown = false }
    // Credits timed to start well before the last half minute: the card counts [CREDITS_COUNTDOWN_MS]
    // down from where they begin and then moves on, rather than counting through every name.
    val earlyCredits = creditsAt != null && status.durationMs - creditsAt > NEXT_UP_LEAD_MS
    // The countdown in whole seconds, the unit the card shows, so it changes once a second.
    nextUpSecondsState = remember(liveStatus, earlyCredits, creditsAt) {
        derivedStateOf {
            val now = liveStatus.value
            val inMs = if (earlyCredits) creditsAt!! + CREDITS_COUNTDOWN_MS - now.positionMs else now.durationMs - now.positionMs
            if (inMs <= 0) 0 else ((inMs + 999) / 1000).toInt()
        }
    }
    LaunchedEffect(earlyCredits && nextUpVisible && nextUpSeconds <= 0) {
        if (!(earlyCredits && nextUpVisible && nextUpSeconds <= 0) || next == null) return@LaunchedEffect
        recordWatched(current, status.durationMs, manual = false)
        runCatching { settings.clearResumePosition(item.chatId, item.messageId) }
        if (StillWatching.askBeforeAutoplay(autoplayedInARow)) {
            askStillWatching(s.playerStillAutoplayLimit(StillWatching.AUTOPLAY_LIMIT), next, ended = true)
        } else {
            autoplay(next)
        }
    }

    // A few times a second, from the live position: what the pill offers (a button, or the count of a
    // stretch set to skip by itself), and the jump when the count runs out. Only the answer reaches
    // the body, so a position tick that changes nothing redraws nothing.
    val skipSuppressed by rememberUpdatedState(phase != Phase.Playing || nextUpVisible)
    val skipCardCovers by rememberUpdatedState(next != null)
    val skipNow by rememberUpdatedState(this::skipSegment)
    LaunchedEffect(skipCtl) {
        while (true) {
            delay(SKIP_TICK_MS)
            val live = liveStatus.value
            val offer = if (skipSuppressed || live.durationMs <= 0) {
                null
            } else {
                skipCtl.offer(live.positionMs, live.durationMs, live.playing, System.nanoTime() / 1_000_000L, skipped, skipCardCovers)
            }
            if (offer is SkipOffer.Fire) {
                skipOffer = offer
                skipNow()
            } else if (offer != skipOffer) {
                skipOffer = offer
            }
        }
    }

    // The subtitle look is one setting for every video: a change in Settings (or in the subtitle
    // menu, which writes the same setting) reaches the video playing now.
    LaunchedEffect(engine, settings) {
        settings.subtitleStyle.collect { style ->
            // A video with a look of its own (WP-29 "This video only") does not follow the shared one.
            if (!styleThisVideo && style != engine.state.value.subtitleStyle) engine.setSubtitleStyle(style)
        }
    }
}

/** The controls coming and going, the lock's button and the keyboard's route (HX1 split, same order). */
@Composable
@NonRestartableComposable
private fun PlayerSession.Controls() {
    // ---- controls and cursor -----------------------------------------------------------------

    val menusOpen = menu != null || showShortcuts
    // "Hide the controls after" is the phone's setting, shared: zero keeps them up while playing.
    // The big screen gets the television's own default until the viewer chooses (WP-47).
    val bigScreenNow = com.tmplayer.desktop.ui.BigScreen.on
    val hideAfterMs by remember(settings, bigScreenNow) { settings.touchPrefs.map { it.timeoutFor(bigScreenNow) } }
        .collectAsState(initial = TouchPrefs().timeoutFor(bigScreenNow))
    // The phone's "Hold to speed up", shared with it: 0 is off.
    holdSpeedState = remember(settings) { settings.touchPrefs.map { it.holdSpeed } }
        .collectAsState(initial = TouchPrefs.HOLD_DEFAULT)
    // A pause, from anywhere (a click, a key, a media key or the system's media controls), puts
    // the controls up for as long as it lasts; the resume keeps them up for the button to turn back
    // into pause, then lets them go, sooner when they were down before the pause (PauseControls).
    var wasPlaying by remember { mutableStateOf(status.playing) }
    var downBeforePause by remember { mutableStateOf(false) }
    LaunchedEffect(status.playing) {
        if (status.playing == wasPlaying) return@LaunchedEffect
        wasPlaying = status.playing
        if (!status.playing) {
            downBeforePause = !controlsUp
            quickHide = false
        } else {
            quickHide = downBeforePause
            downBeforePause = false
        }
        controlsUp = true
        activity = System.nanoTime()
    }
    LaunchedEffect(activity, status.playing, menusOpen, overControls, phase, hideAfterMs) {
        if (!status.playing || menusOpen || overControls || phase != Phase.Playing) return@LaunchedEffect
        val after = PauseControls.hideAfterResumeMs(hideAfterMs, quickHide)
        if (after <= 0L) return@LaunchedEffect
        delay(after)
        controlsUp = false
    }
    showControls = phase == Phase.Playing && !screenLocked && (controlsUp || !status.playing || menusOpen)

    // The controls going away under a remote that was on one of them: the keys go back to the
    // picture, or they would reach nothing until a click.
    LaunchedEffect(showControls) {
        if (!showControls && !keysOnPlayer && com.tmplayer.desktop.ui.BigScreen.on) refocus()
    }

    dispatchNowState = rememberUpdatedState(this::dispatch)
    wheelSeeksNowState = rememberUpdatedState(desktop.wheelSeeks)

    // The Unlock button: up on each move, click or key, and gone a moment after the last, but
    // never while Enter is held toward an unlock.
    LaunchedEffect(screenLocked, unlockWanted, unlockHold) {
        showUnlock = screenLocked
        if (!screenLocked || unlockHold != null) return@LaunchedEffect
        delay(PlayerLock.SHOW_MS)
        showUnlock = false
    }
    LaunchedEffect(screenLocked) { onLockedChanged(screenLocked) }
    playerKeyNowState = rememberUpdatedState(this::playerKey)
    // A sheet or a menu that takes typing or holds the keyboard itself: the window leaves its keys alone.
    val typingUp = notePrompt != null || timePrompt || showOnline || menu != null || listPicking != null
    val typingUpNow by rememberUpdatedState(typingUp)
    val sheetUpNow by rememberUpdatedState(showEpisodes || showShortcuts || showDetails)
    LaunchedEffect(Unit) { refocus() }
    DisposableEffect(Unit) {
        val route = PlayerKeyRoute.Route(
            handle = { playerKeyNow(it) },
            focusInside = { focusInside },
            typing = { typingUpNow },
            refocus = { if (!sheetUpNow) refocus() },
        )
        PlayerKeyRoute.open(route)
        onDispose { PlayerKeyRoute.close(route) }
    }
}

/** What the player draws: the picture, the controls and every card, sheet and dialog over them (HX1 split). */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
@NonRestartableComposable
private fun PlayerSession.Body(modifier: Modifier) {
    // ---- layout ------------------------------------------------------------------------------

    // The newest session, for the callbacks the parts are handed (see PictureLayer and the rest).
    val latest = rememberUpdatedState(this)
    PlayerTheme {
        Box(
            modifier
                .fillMaxSize()
                .background(backdrop)
                .onPreviewKeyEvent { playerKeyNow(it) }
                .onFocusChanged {
                    keysOnPlayer = it.isFocused || !it.hasFocus
                    focusInside = it.hasFocus
                }
                .focusRequester(focus)
                .focusable()
                .onPointerEvent(PointerEventType.Move, PointerEventPass.Initial) { poke() }
                .onPointerEvent(PointerEventType.Press, PointerEventPass.Initial) {
                    navByKeyboard = false
                    noteInput()
                }
                .onPointerEvent(PointerEventType.Enter, PointerEventPass.Initial) { poke() }
                .onPointerEvent(PointerEventType.Exit, PointerEventPass.Initial) {
                    if (status.playing && menu == null) controlsUp = false
                }
                .onPointerEvent(PointerEventType.Scroll) { event ->
                    noteInput()
                    if (phase != Phase.Playing || screenLocked) return@onPointerEvent
                    // A wheel over a sheet, or one a list inside has scrolled with, is the sheet's,
                    // not the volume's under it as well.
                    if (showEpisodes || showShortcuts || showOnline || menu?.panel == true || event.changes.any { it.isConsumed }) return@onPointerEvent
                    val delta = event.changes.firstOrNull()?.scrollDelta ?: return@onPointerEvent
                    // Ctrl (or Cmd on a Mac) with the wheel zooms around the pointer, as a browser
                    // and VLC do; a touchpad's pinch on Windows comes as the same event.
                    val mods = event.keyboardModifiers
                    if (mods.pointerCtrl || (mac && mods.pointerMeta)) {
                        zoomStep(-delta.y, event.changes.firstOrNull()?.position)
                        return@onPointerEvent
                    }
                    // Counted into whole steps (WP-48): a trackpad swipe is dozens of small deltas.
                    val at = event.changes.firstOrNull()?.uptimeMillis ?: System.currentTimeMillis()
                    SeekMath.wheelActions(wheelStepper, delta.x, delta.y, event.keyboardModifiers.pointerShift, wheelSeeksNow, at)
                        .forEach(dispatchNow)
                }
                // Hidden with the controls only while a video plays: the loading, end and error
                // sheets have no controls to bring it back with, and the sheets are for the mouse.
                .pointerHoverIcon(
                    if (phase == Phase.Playing && !showControls && menu == null && !showEpisodes && !showOnline && !(screenLocked && showUnlock)) {
                        blankCursor
                    } else {
                        PointerIcon.Default
                    },
                )
                .subtitleDropTarget(onDrop = this::loadSubtitle, onRefused = { showFlash(Flash.Kind.Text, it) })
                .onSizeChanged { videoBox = it },
        ) {
            PictureLayer(latest)

            // The show's name, with the episode under it ("S01E04  ·  The Lighthouse") and the chat.
            val parsed = remember(item) { SeriesShelf.episodeOf(item) }
            val episode = episodes.current
            val title = (episode?.show ?: parsed.title).ifBlank { parsed.title }.ifBlank { item.title }
            val subtitle = listOfNotNull(
                episode?.label,
                current.chatTitle.takeIf { it.isNotBlank() },
            ).joinToString("  ·  ")
            castTitle[0] = com.tmplayer.desktop.cast.CastTitle(title, subtitle)

            // The first run's one line about the keys (WP-47), through the hint registry: shown with
            // the controls, once, and never again (a first run only, as the registry allows a hint
            // during playback). The big screen has a remote, not a keyboard.
            if (!com.tmplayer.desktop.ui.BigScreen.on) {
                Box(Modifier.align(Alignment.BottomCenter).padding(bottom = 112.dp)) {
                    com.tmplayer.ui.components.OneTimeHint(
                        settings,
                        com.tmplayer.data.HintIds.PLAYER_KEYS,
                        label = s.playerFirstKeysHint,
                        visible = showControls,
                        inPlayback = true,
                        style = com.tmplayer.ui.components.HintStyle.Bubble,
                    )
                }
            }

            OverlayLayer(latest, title, subtitle)
            CardsLayer(latest)
            SheetsLayer(latest, title, subtitle)
            DialogsLayer(latest)
        }
    }
}

/** The keyboard back on the picture after a fullscreen change or a return to the window (HX1 split). */
@Composable
@NonRestartableComposable
private fun PlayerSession.Refocus() {
    LaunchedEffect(Unit) { refocus() }
    // Going in or out of fullscreen, or back in the window: see keysBack.
    LaunchedEffect(fullscreen) {
        delay(FULLSCREEN_SETTLE_MS)
        keysBack()
    }
    // Back in the window (alt tab, a click on the title bar): the keys are the player's again.
    val windowFocused = LocalWindowInfo.current.isWindowFocused
    LaunchedEffect(windowFocused) {
        if (windowFocused) keysBack()
    }
}

/** How long a fullscreen change is given to land before the player takes the keyboard back. */
private const val FULLSCREEN_SETTLE_MS = 300L

/** Whether [item] came from a message, which is what the Watched list is keyed by. */
internal fun hasMessage(item: MediaItem): Boolean = item.chatId != 0L && item.messageId != 0L

/**
 * remember(item.chatId, item.messageId) in a frame of its own. PlayerScreen had well over a hundred
 * local slots before it was split into stages, and ProGuard, which computes the release build's stack map frames again, tracks
 * liveness for the first 64 only: three such remembers in a row put their longs in slots 71 to 75,
 * the compiler reused 72 for the next pair, and ProGuard still wrote 71 as a long, so the release
 * PlayerScreenKt failed the verifier and the player could not open (verifyReleaseClasses, as HX1
 * was for SeriesKt). Not inline on purpose.
 */
@Composable
private fun <T> rememberForMessage(item: MediaItem, calculation: () -> T): T =
    remember(item.chatId, item.messageId) { calculation() }

/**
 * The tracks and speed as they stand, counted the way a [ResumeState] counts them: each track's
 * place among mpv's tracks of its kind. Subtitles none of which is selected are stored as off.
 */
internal fun resumeState(tracks: List<MediaTrack>, speed: Float): ResumeState {
    val audio = tracks.filter { it.type == TrackType.Audio }
    val subs = tracks.filter { it.type == TrackType.Subtitle }
    val a = audio.indexOfFirst { it.selected }
    val s = subs.indexOfFirst { it.selected }
    return ResumeState(
        audioTrack = a.takeIf { it >= 0 },
        audioLanguage = audio.getOrNull(a)?.language,
        subtitleTrack = when {
            s >= 0 -> s
            subs.isNotEmpty() -> ResumeState.SUBTITLES_OFF
            else -> null
        },
        subtitleLanguage = subs.getOrNull(s)?.language,
        speed = speed,
    )
}

/** What is filed under one series for the track choice: the parsed name, or the video's own. */
internal fun seriesKeyOf(item: MediaItem): String {
    val name = item.fileName.ifBlank { item.title }
    return MediaName.parse(name).title.ifBlank { name }
}

/** Actions that still mean something while the loading or error sheet is up. */
private val OUTSIDE_PLAYBACK = setOf(
    PlayerAction.Back,
    PlayerAction.Quit,
    PlayerAction.ToggleFullscreen,
    PlayerAction.ExitFullscreen,
    PlayerAction.ShortcutSheet,
)

/**
 * The player's theme: the app's own dark scheme whatever the window is in, because a picture is
 * watched in the dark. The same palette as the phone's and the television's player menus, rather
 * than the grey and the stock blue the desktop player used to carry on its own.
 */
@Composable
internal fun PlayerTheme(content: @Composable () -> Unit) = TmMaterialTheme(dark = true, content = content)

private const val RESUME_TICK_MS = 10_000L

/** How long the opening waits for the message to be read again before it picks a copy (CP80). */
private const val POST_READ_MS = 2_500L

/** What mpv waits for in its cache before it plays (its `cache-pause-wait`), as the loader's buffer. */
private const val MPV_START_BUFFER_MS = 1_000L

/** The hover frame asked of the preview decoder: the 240 dp card at 2x. */
private const val FRAME_WIDTH_PX = 480
private const val FRAME_HEIGHT_PX = 270
private const val NEXT_UP_LEAD_MS = 30_000L

/** How long the card counts down from the start of timed credits before the next episode. */
private const val CREDITS_COUNTDOWN_MS = 15_000L

/** Credits or an ending that roll until the file ends: what [SkipSegments.creditsStart] reads. */
private fun isClosing(segment: SkipSegment, durationMs: Long): Boolean =
    (segment.kind == SkipKind.Credits || segment.kind == SkipKind.Ending) && segment.runsToEnd(durationMs)
private const val AUTOPLAY_COUNTDOWN_SEC = 8
private const val SEEK_RUN_MS = 1_000L
private const val STILL_WATCHING_TICK_MS = 5_000L

/** How often the skip pill looks at the playhead: a countdown of whole seconds needs no more. */
private const val SKIP_TICK_MS = 250L

/**
 * Two statuses are the same to the player screen's body when only the clock moved: the position
 * and the buffer's end are read live, by the time label, the bar and the values derived from them.
 */
private object SteadyStatus : androidx.compose.runtime.SnapshotMutationPolicy<PlaybackStatus> {
    override fun equivalent(a: PlaybackStatus, b: PlaybackStatus): Boolean =
        a.copy(positionMs = 0, bufferedMs = 0) == b.copy(positionMs = 0, bufferedMs = 0)
}
