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
import androidx.compose.ui.input.pointer.isShiftPressed as pointerShift
import androidx.compose.ui.input.pointer.onPointerEvent
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
    data class Failed(val message: String, val noRoom: Boolean = false) : Phase
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

internal enum class MenuPage { Main, Audio, Subtitles, Speed, Shape, Sleep, Options, SubtitleStyle, Bookmarks, Copies, Stereo }

/** Where an open menu hangs: at the cursor for a right click, or under a button. */
internal data class MenuAt(val page: MenuPage, val anchor: Anchor, val at: Offset = Offset.Zero) {
    enum class Anchor { Cursor, Overflow, Subtitles, Audio, Speed }

    /**
     * The page Back returns to, or null on the page a button's own menu opens at: that menu has no
     * Back line, a click outside closes it.
     */
    fun parentPage(): MenuPage? = when (page) {
        MenuPage.Main -> null
        MenuPage.Audio -> if (anchor == Anchor.Audio) null else MenuPage.Main
        MenuPage.Subtitles -> if (anchor == Anchor.Subtitles) null else MenuPage.Main
        MenuPage.Speed -> if (anchor == Anchor.Speed) null else MenuPage.Main
        MenuPage.Sleep -> MenuPage.Options
        MenuPage.Options, MenuPage.Shape, MenuPage.Bookmarks, MenuPage.Copies, MenuPage.Stereo -> MenuPage.Main
        MenuPage.SubtitleStyle -> if (anchor == Anchor.Subtitles) MenuPage.Subtitles else MenuPage.Main
    }
}

private val blankCursor: PointerIcon by lazy {
    PointerIcon(
        Toolkit.getDefaultToolkit().createCustomCursor(
            BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB), Point(0, 0), "blank",
        ),
    )
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
    engineFactory: () -> PlaybackEngine = { MpvPlaybackEngine(OpenPrefs.hwdecFor(prefs.now.softwareDecoding)) },
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
    val s = LocalStrings.current
    // What the TV is told the video is called, read when a cast loads it.
    val castTitle = remember { arrayOf(com.tmplayer.desktop.cast.CastTitle("")) }
    // mpv on this computer, or the TV while a cast is on (CP73): a video opened with a session up
    // goes to the TV from the start.
    val engine = remember {
        com.tmplayer.desktop.cast.SwitchingEngine(engineFactory()).also { e ->
            (com.tmplayer.desktop.cast.DesktopCast.state.value as? com.tmplayer.desktop.cast.DesktopCast.State.Connected)?.let { c ->
                e.switchTo(com.tmplayer.desktop.cast.CastPlaybackEngine(c.client) { castTitle[0] })
            }
        }
    }
    val desktop by prefs.state.collectAsState()
    DisposableEffect(engine) { onDispose { engine.close() } }
    LaunchedEffect(engine) { onEngine(engine) }

    val scope = rememberCoroutineScope()
    val activeEngine by engine.activeFlow.collectAsState()
    // Every update of the engine, the clock's four a second among them. Read in handlers, in the
    // derived values below and by the small parts that draw the time, never in this body, so a
    // tick of the clock recomposes the time label and the bar rather than the whole screen.
    val liveStatus = activeEngine.state.collectAsState()
    // The same status with the position and the buffer's end left as they were, which is what the
    // body reads: it changes when something other than the clock does (SteadyStatus).
    val status by remember(liveStatus) { derivedStateOf(SteadyStatus) { liveStatus.value } }
    val tracks by activeEngine.tracks.collectAsState()
    val castState by com.tmplayer.desktop.cast.DesktopCast.state.collectAsState()

    var current by remember(media) { mutableStateOf(media) }
    var ignoreSavedPosition by remember(media) { mutableStateOf(startFromBeginning) }
    var attempt by remember { mutableIntStateOf(0) }
    var phase by remember { mutableStateOf<Phase>(Phase.Loading(s.playerOpeningVideo)) }
    // A failure once the video is playing (mpv lost the stream, a decoder gave up) ends in the error
    // sheet too, with Try again, instead of a still picture and nothing said (HX10). The open's own
    // failure is read where it opens.
    LaunchedEffect(engine) {
        engine.state.map { it.error }.collect { error ->
            if (error != null && phase == Phase.Playing && engine.cast == null) phase = Phase.Failed(error)
        }
    }
    // The opening's start point and [StreamReads] when it began, for the loader's staged line.
    var openingStartMs by remember { mutableStateOf(0L) }
    val openingBytesAt = remember { longArrayOf(0L) }
    var episodes by remember { mutableStateOf(Episodes()) }
    // Every copy of the video playing that the player knows of: what the opening page handed over
    // (CopyHandoff, possibly across chats) and, for an episode, its copies once the show is found.
    val copies = remember(current.item.id, episodes) {
        val here = current.item
        val episode = episodes.series?.episodes?.firstOrNull { ep -> ep.copies.any { it.id == here.id } }
        // The same file posted twice is one copy to switch to, not two.
        com.tmplayer.data.VideoCopies.oneOfEachFile((CopyHandoff.copiesOf(here) + episode?.copies.orEmpty() + here).distinctBy { it.id }, here.id)
    }
    LaunchedEffect(current.item.id) {
        val here = current.item
        runCatching { settings.notePlayedQuality(copies.firstOrNull { it.id == here.id } ?: here) }
    }
    // The message as Telegram has it now (CP80): its lighter copies, start time and storyboard.
    // The grid's item already carries them; one rebuilt from a record is read again.
    var post by remember(current.item.chatId, current.item.messageId) { mutableStateOf<MediaItem?>(null) }
    // The media handed on to a Telegram copy before it ever opened, whose leaving saves nothing.
    val neverOpened = remember { arrayOfNulls<PlayerMedia>(1) }
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
    val qualities = if (current.isDownload || current.item.isJoined) emptyList() else (post?.qualities ?: current.item.qualities)
    // Telegram's storyboard (CP80): the timebar's hover pictures, where scrub pictures are on.
    val scrubPictures by remember(settings) { settings.trickplay }.collectAsState(initial = true)
    val boardFiles = (post ?: current.item).storyboard
    val storyboard = remember(boardFiles, scrubPictures) {
        boardFiles?.takeIf { scrubPictures }?.let { files -> DesktopStoryboard(files, durationMs = { engine.state.value.durationMs.takeIf { it > 0 } ?: current.item.durationSec * 1000L }) }
    }
    // The video's own frames for the hover where there is no storyboard: a second decoder over
    // what is on the disk (see [PlaybackEngine.previewFrame]).
    val frameSource = remember(current.item.id, scrubPictures) {
        if (!scrubPictures) null else FrameSource { at -> engine.previewFrame(at, FRAME_WIDTH_PX, FRAME_HEIGHT_PX) }
    }
    var resumedFrom by remember { mutableStateOf<Long?>(null) }
    var autoplayNext by remember { mutableStateOf(true) }
    var nextUpDismissed by remember { mutableStateOf(false) }
    var nextUpShown by remember { mutableStateOf(false) }
    var trackChoice by remember { mutableStateOf(TrackChoice()) }
    var showRemaining by remember { mutableStateOf(false) }
    var ignoreClicks by remember { mutableStateOf(false) }
    // Lock the screen ([PlayerLock]): whether it is on, when the Unlock button was last asked
    // for (it fades [PlayerLock.SHOW_MS] after), and the hold of Enter toward an unlock.
    var screenLocked by remember { mutableStateOf(lockedAtStart) }
    var unlockWanted by remember { mutableLongStateOf(if (lockedAtStart) System.nanoTime() else 0L) }
    var showUnlock by remember { mutableStateOf(lockedAtStart) }
    var unlockHold by remember { mutableStateOf<Job?>(null) }
    var tdlibVersion by remember { mutableStateOf<String?>(null) }

    var controlsUp by remember { mutableStateOf(true) }
    var activity by remember { mutableLongStateOf(0L) }
    var overControls by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(MenuPage.entries.firstOrNull { it.name.equals(menuOpen, ignoreCase = true) }?.let { MenuAt(it, MenuAt.Anchor.Cursor, Offset(240f, 120f)) }) }
    var showDetails by remember { mutableStateOf(detailsOpen) }
    var showOnline by remember { mutableStateOf(onlineOpen) }
    var showShortcuts by remember { mutableStateOf(shortcutsOpen) }
    var showEpisodes by remember { mutableStateOf(episodesOpen) }
    var flash by remember { mutableStateOf<Flash?>(null) }
    var seekRun by remember { mutableStateOf(0L to 0L) } // (accumulated ms, last at)
    val downloaded by current.downloaded.collectAsState()
    val savable by current.savable.collectAsState()
    // The Watched list as it stands, for which way the menu's mark line reads.
    val watchedKeys by remember(watched) { watched?.watched ?: flowOf(emptyMap()) }
        .collectAsState(initial = emptyMap())
    // Videos marked watched from this player. Their position is forgotten on the way out rather
    // than saved, or leaving would put them straight back into Continue watching (as on Android).
    val markedHere = remember { mutableSetOf<String>() }
    // Videos already put on the Watched list for being left past 90 %, so leaving the same one
    // twice (switching away, then closing) marks it once.
    val watchedHere = remember { mutableSetOf<String>() }
    // Where this video's intro, recap and credits are, from the online lookups, and the ones
    // already skipped (by start), which do not offer themselves again in this playback. Both
    // belong to the video playing: an episode switch starts with none until its own answer.
    var skips by remember(current) { mutableStateOf(emptyList<SkipSegment>()) }
    var skipped by remember(current) { mutableStateOf(emptySet<Long>()) }
    // A-B repeat, for the video playing only: a new video starts with none.
    var abLoop by remember(current) { mutableStateOf<AbLoop?>(null) }

    // "Still watching?" and the sleep timer (CP07). The counts live for the whole player session,
    // so they carry across the episodes autoplay moves on to, and any input starts them again.
    var autoplayedInARow by remember { mutableIntStateOf(0) }
    var idleMs by remember { mutableLongStateOf(0L) }
    var sleepAt by remember { mutableLongStateOf(0L) }
    var sleepAtTheEnd by remember { mutableStateOf(false) }
    var heldDownloads by remember { mutableStateOf(emptyList<Int>()) }
    // Where Keep watching opens the video again, past the saved resume point and its rules.
    var reopenAt by remember { mutableStateOf<Long?>(null) }

    val focus = remember { FocusRequester() }
    // Whether the keys are on the player itself rather than on one of its controls: in the
    // big-screen mode a remote walks into the controls with Up and Down (plan 8.7).
    var keysOnPlayer by remember { mutableStateOf(true) }
    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
    val item = current.item
    val seriesKey = remember(item) { seriesKeyOf(item) }
    // Where a speed picked for this video is remembered, and read back from. See [SpeedMemory].
    val speedScopes = remember(item, seriesKey) { SpeedMemory.scopesOf(item, seriesKey) }
    val progressMap by remember(settings) { settings.watchProgress }.collectAsState(initial = emptyMap())
    // This video's bookmarks, for the menu's list and the ticks on the timebar. A file with no
    // message behind it (the dev harness) has nothing to key them by, so it has none.
    val marksOn = bookmarks != null && hasMessage(item)
    val marks by remember(bookmarks, item.chatId, item.messageId) {
        if (bookmarks != null && hasMessage(item)) bookmarks.bookmarks(item.chatId, item.messageId) else flowOf(emptyList())
    }.collectAsState(initial = emptyList())
    // The note dialog: for a new bookmark at a position, or for one already made.
    var notePrompt by remember { mutableStateOf<NotePrompt?>(null) }
    // "Back to 12:34" after a long jump, for the video playing only. See [SeekUndo].
    val seekUndo = remember(current) { SeekUndo() }
    var undoOffer by remember(current) { mutableStateOf<SeekUndo.Offer?>(null) }
    // The "Go to a time" dialog ({cmd}+G).
    var timePrompt by remember { mutableStateOf(false) }
    LaunchedEffect(undoOffer) {
        val offer = undoOffer ?: return@LaunchedEffect
        delay((offer.untilMs - System.currentTimeMillis()).coerceAtLeast(0))
        undoOffer = seekUndo.current(System.currentTimeMillis())
    }

    fun showFlash(kind: Flash.Kind, text: String = "") {
        flash = Flash(kind, text, System.nanoTime())
    }

    fun refocus() {
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
        engine.seekTo(target)
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
    }

    LaunchedEffect(current, attempt) {
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
        autoplayNext = runCatching { settings.autoplayNextNow() }.getOrDefault(true)
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
        val prefs = OpenPrefs(
            audioLanguage = trackPlan.audioLanguage,
            subtitles = trackPlan.subtitles,
            // Its own speed, else the series' or course's, else the chat's or topic's: [SpeedMemory].
            speed = runCatching { settings.openingSpeed(resumed?.speed, speedScopes) }
                .getOrDefault(resumed?.speed ?: PlaybackSpeed.DEFAULT)
                .coerceIn(SeekMath.MIN_SPEED, SeekMath.MAX_SPEED),
            scale = runCatching { VideoScale.from(settings.videoScaleNow()) }.getOrDefault(VideoScale.Fit),
            downmix = prefs.now.downmix,
            volume = prefs.now.volume,
            muted = prefs.now.muted,
            volumeBoost = runCatching { settings.volumeBoostNow() }.getOrDefault(false),
            nightMode = runCatching { settings.nightModeNow() }.getOrDefault(false),
            hwdec = OpenPrefs.hwdecFor(prefs.now.softwareDecoding),
            subtitleStyle = runCatching { settings.subtitleStyleNow() }.getOrDefault(SubtitleStyle()),
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
        engine.open(data, start, prefs)
        val error = engine.state.value.error
        // On a TV the casting panel says what went wrong, with Play here beside it.
        if (error != null && engine.cast == null) {
            phase = Phase.Failed(error)
            return@LaunchedEffect
        }
        ignoreSavedPosition = false
        // Keep watching opens the file again, and a fresh open has no loop in mpv.
        abLoop?.takeIf { it.complete }?.let(engine::setAbLoop)
        phase = Phase.Playing
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
            cs is com.tmplayer.desktop.cast.DesktopCast.State.Connected && casting?.client !== cs.client -> {
                val at = engine.state.value.takeIf { it.opened }?.positionMs
                engine.switchTo(com.tmplayer.desktop.cast.CastPlaybackEngine(cs.client) { castTitle[0] }, tellReceiver = false)
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
                if (forget) settings.clearResumePosition(it.chatId, it.messageId, it)
                else settings.saveResumePosition(it.chatId, it.messageId, position, duration, description)
            }
            // Out to the other devices once the saves stop (a pause, the player closed): the
            // push waits for them to settle, so the heartbeat never edits the message (ResumeSync).
            com.tmplayer.data.ResumeSync.pushSoon(settings)
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

    /** "Play Telegram's 480p" on the sheet that says nothing is arriving: the next copy down. */
    val lighter: com.tmplayer.data.TelegramQuality? = run {
        val here = current.item
        val source = post ?: here
        if (qualities.isEmpty()) null else TelegramQualities.lighter(here.copy(qualities = source.qualities))
    }

    val lighterAction: Pair<String, () -> Unit>? = lighter?.let { q ->
        s.playerLighter(TelegramQualities.label(q.height)) to { switchQuality(q.height) }
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

    // The end: the next episode after a countdown, or the last frame with a way to watch again.
    LaunchedEffect(status.ended) {
        if (!status.ended || phase != Phase.Playing) return@LaunchedEffect
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
        showFlash(Flash.Kind.Text, if (on) L.playerNightModeOn else L.playerNightModeOff)
        playerScope.launch { runCatching { settings.setNightMode(on) } }
    }

    val next = episodes.next
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
    val nextUpVisible = phase == Phase.Playing && next != null && autoplayNext && !nextUpDismissed &&
        status.durationMs > 0 && nearEnd
    LaunchedEffect(nextUpVisible) { if (nextUpVisible) nextUpShown = true }
    LaunchedEffect(pastLead) { if (pastLead) nextUpShown = false }
    // Credits timed to start well before the last half minute: the card counts [CREDITS_COUNTDOWN_MS]
    // down from where they begin and then moves on, rather than counting through every name.
    val earlyCredits = creditsAt != null && status.durationMs - creditsAt > NEXT_UP_LEAD_MS
    // The countdown in whole seconds, the unit the card shows, so it changes once a second.
    val nextUpSeconds by remember(liveStatus, earlyCredits, creditsAt) {
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

    // The segment the pill offers to skip. Credits that run to the end are the next-up card's
    // when there is a next episode, so they get no pill of their own; without one, the pill
    // skips to the end like any other. Never while the card is up, which has the same corner.
    // Derived from the live position, so the body hears of it as a pill comes and goes only.
    val skipAtPosition by remember(liveStatus, skips, skipped) {
        derivedStateOf { liveStatus.value.let { SkipSegments.active(skips, it.positionMs, it.durationMs, skipped) } }
    }
    val activeSkip = if (phase == Phase.Playing && status.durationMs > 0 && !nextUpVisible) {
        skipAtPosition?.takeUnless { next != null && isClosing(it, status.durationMs) }
    } else {
        null
    }

    /** The pill, or G: past the segment, which is then left alone for the rest of this playback. */
    fun skipSegment() {
        val segment = activeSkip ?: return
        skipped = skipped + segment.startMs
        jumpTo(segment.target(status.durationMs))
    }

    // The subtitle look is one setting for every video: a change in Settings (or in the subtitle
    // menu, which writes the same setting) reaches the video playing now.
    LaunchedEffect(engine, settings) {
        settings.subtitleStyle.collect { style ->
            if (style != engine.state.value.subtitleStyle) engine.setSubtitleStyle(style)
        }
    }

    // ---- controls and cursor -----------------------------------------------------------------

    val menusOpen = menu != null || showShortcuts
    // "Hide the controls after" is the phone's setting, shared: zero keeps them up while playing.
    val hideAfterMs by remember(settings) { settings.touchPrefs.map { it.controlsTimeoutMs } }
        .collectAsState(initial = TouchPrefs.TIMEOUT_DEFAULT_MS)
    // The phone's "Hold to speed up", shared with it: 0 is off.
    val holdSpeed by remember(settings) { settings.touchPrefs.map { it.holdSpeed } }
        .collectAsState(initial = TouchPrefs.HOLD_DEFAULT)
    // The speed to go back to when a hold ends; null while no hold has changed it.
    var speedBeforeHold by remember { mutableStateOf<Float?>(null) }
    // A pause, from anywhere (a click, a key, a media key or the system's media controls), puts
    // the controls up for as long as it lasts; the resume keeps them up for the button to turn back
    // into pause, then lets them go, sooner when they were down before the pause (PauseControls).
    var wasPlaying by remember { mutableStateOf(status.playing) }
    var downBeforePause by remember { mutableStateOf(false) }
    var quickHide by remember { mutableStateOf(false) }
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
    val showControls = phase == Phase.Playing && !screenLocked && (controlsUp || !status.playing || menusOpen)

    // The controls going away under a remote that was on one of them: the keys go back to the
    // picture, or they would reach nothing until a click.
    LaunchedEffect(showControls) {
        if (!showControls && !keysOnPlayer && com.tmplayer.desktop.ui.BigScreen.on) refocus()
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
        engine.setScale(scale)
        showFlash(Flash.Kind.Text, scale.label)
        playerScope.launch { runCatching { settings.setVideoScale(scale.name) } }
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
        val style = now.copy(size = next)
        engine.setSubtitleStyle(style)
        scope.launch { runCatching { settings.setSubtitleStyle(style) } }
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

    /** From the subtitle menu: shown on the picture at once, and kept as the setting for every video. */
    fun setSubtitleStyle(style: SubtitleStyle) {
        engine.setSubtitleStyle(style)
        playerScope.launch { runCatching { settings.setSubtitleStyle(style) } }
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

    /** R: the loop's start, then its end (and the loop runs), then off. */
    fun pressAbRepeat() {
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
        val chapters = status.chapters
        if (chapters.isEmpty()) {
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
            is PlayerAction.ChapterStep -> stepChapter(action.forward)
            PlayerAction.AddBookmark -> addBookmark()
            PlayerAction.UndoSeek -> undoSeek()
            PlayerAction.GoToTime -> if (status.opened) {
                menu = null
                timePrompt = true
            }
        }
    }

    val dispatchNow by rememberUpdatedState(::dispatch)
    val wheelSeeksNow by rememberUpdatedState(desktop.wheelSeeks)

    fun loadSubtitle(path: String) {
        if (phase != Phase.Playing) return
        val name = java.io.File(path).name
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
    val mac = com.tmplayer.desktop.os.OsInfo.isMac

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

    // The Unlock button: up on each move, click or key, and gone a moment after the last, but
    // never while Enter is held toward an unlock.
    LaunchedEffect(screenLocked, unlockWanted, unlockHold) {
        showUnlock = screenLocked
        if (!screenLocked || unlockHold != null) return@LaunchedEffect
        delay(PlayerLock.SHOW_MS)
        showUnlock = false
    }
    LaunchedEffect(screenLocked) { onLockedChanged(screenLocked) }

    // ---- layout ------------------------------------------------------------------------------

    PlayerTheme {
        Box(
            modifier
                .fillMaxSize()
                .background(backdrop)
                .onPreviewKeyEvent { event ->
                    // Locked: every key is the lock's, the downs and the ups, so not even the
                    // window's Esc and Backspace hear one. Enter held unlocks; any key shows how.
                    if (screenLocked) {
                        lockedKey(event)
                        return@onPreviewKeyEvent true
                    }
                    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    noteInput()
                    // The episode list has the keyboard while it is up: Esc closes it, and every
                    // other key (the arrows, Enter) is its own, not a seek or a fullscreen. The
                    // other back keys close it too, or the window would take Backspace as a way
                    // out of the whole player.
                    if (showEpisodes) {
                        if (com.tmplayer.ui.nav.isBackKey(event)) {
                            showEpisodes = false
                            refocus()
                            return@onPreviewKeyEvent true
                        }
                        return@onPreviewKeyEvent false
                    }
                    // Sheets peel off before Esc means anything else.
                    if (event.key == androidx.compose.ui.input.key.Key.Escape && (showShortcuts || showDetails || showOnline)) {
                        showShortcuts = false
                        showDetails = false
                        showOnline = false
                        refocus()
                        return@onPreviewKeyEvent true
                    }
                    val tv = com.tmplayer.desktop.ui.BigScreen.on
                    val remote = com.tmplayer.desktop.ui.remoteKeyOf(event)
                    val plain = !event.isShiftPressed && !event.isCtrlPressed && !event.isAltPressed && !event.isMetaPressed
                    if (tv && !keysOnPlayer && menu == null && !showShortcuts && !showDetails && !showOnline) {
                        // A remote on the controls: the arrows walk them, OK presses the one it is
                        // on, and Back leaves them for the picture, the controls staying up meanwhile.
                        poke()
                        val direction = PlayerRemote.direction(event.key)
                        when {
                            com.tmplayer.ui.nav.isBackKey(event) || remote == com.tmplayer.desktop.ui.RemoteKey.Back -> {
                                refocus()
                                return@onPreviewKeyEvent true
                            }
                            plain && direction != null -> {
                                focusManager.moveFocus(direction)
                                return@onPreviewKeyEvent true
                            }
                            plain && event.key in PlayerRemote.OK -> return@onPreviewKeyEvent false
                        }
                    } else if (tv && plain && phase == Phase.Playing && (event.key == Key.DirectionUp || event.key == Key.DirectionDown)) {
                        // Up and Down on the picture bring the controls up and the remote into them;
                        // the sound is the television's own volume keys.
                        poke()
                        scope.launch {
                            androidx.compose.runtime.withFrameNanos { }
                            focusManager.moveFocus(androidx.compose.ui.focus.FocusDirection.Enter)
                        }
                        return@onPreviewKeyEvent true
                    }
                    // A remote's key the JDK has no name for (Back, the media keys), from its raw code.
                    PlayerRemote.actionFor(remote)?.let { action ->
                        if (phase != Phase.Playing && action !in OUTSIDE_PLAYBACK) return@onPreviewKeyEvent false
                        val playKey = action == PlayerAction.TogglePlay || action == PlayerAction.Play || action == PlayerAction.Pause
                        if (playKey && !MediaKeyEcho.claim(MediaKeyEcho.Source.Keyboard)) return@onPreviewKeyEvent true
                        dispatchNow(action)
                        return@onPreviewKeyEvent true
                    }
                    val press = KeyPress(
                        key = event.key,
                        shift = event.isShiftPressed,
                        ctrl = event.isCtrlPressed,
                        alt = event.isAltPressed,
                        meta = event.isMetaPressed,
                    )
                    val context = KeyContext(fullscreen = fullscreen, speedIsNormal = status.speed == 1f, mac = mac, tv = tv)
                    val action = PlayerKeys.actionFor(press, context) ?: return@onPreviewKeyEvent false
                    // Over the loading and error sheets only the way out works.
                    if (phase != Phase.Playing && action !in OUTSIDE_PLAYBACK) return@onPreviewKeyEvent false
                    // The same key may already have reached the player through the media session.
                    val playKey = event.key == Key.MediaPlayPause || event.key == Key.MediaPlay || event.key == Key.MediaPause
                    if (playKey && !MediaKeyEcho.claim(MediaKeyEcho.Source.Keyboard)) return@onPreviewKeyEvent true
                    dispatchNow(action)
                    true
                }
                .onFocusChanged { keysOnPlayer = it.isFocused || !it.hasFocus }
                .focusRequester(focus)
                .focusable()
                .onPointerEvent(PointerEventType.Move, PointerEventPass.Initial) { poke() }
                .onPointerEvent(PointerEventType.Press, PointerEventPass.Initial) { noteInput() }
                .onPointerEvent(PointerEventType.Enter, PointerEventPass.Initial) { poke() }
                .onPointerEvent(PointerEventType.Exit, PointerEventPass.Initial) {
                    if (status.playing && menu == null) controlsUp = false
                }
                .onPointerEvent(PointerEventType.Scroll) { event ->
                    noteInput()
                    if (phase != Phase.Playing || screenLocked) return@onPointerEvent
                    // A wheel over a sheet, or one a list inside has scrolled with, is the sheet's,
                    // not the volume's under it as well.
                    if (showEpisodes || showShortcuts || showOnline || event.changes.any { it.isConsumed }) return@onPointerEvent
                    val delta = event.changes.firstOrNull()?.scrollDelta ?: return@onPointerEvent
                    SeekMath.wheelAction(delta.x, delta.y, event.keyboardModifiers.pointerShift, wheelSeeksNow)
                        ?.let(dispatchNow)
                }
                // Hidden with the controls only while a video plays: the loading, end and error
                // sheets have no controls to bring it back with, and the sheets are for the mouse.
                .pointerHoverIcon(
                    if (fullscreen && phase == Phase.Playing && !showControls && menu == null && !showEpisodes && !showOnline && !(screenLocked && showUnlock)) {
                        blankCursor
                    } else {
                        PointerIcon.Default
                    },
                )
                .subtitleDropTarget(onDrop = ::loadSubtitle, onRefused = { showFlash(Flash.Kind.Text, it) }),
        ) {
            val casting = activeEngine as? com.tmplayer.desktop.cast.CastPlaybackEngine
            if (casting == null) {
                (engine.local as? MpvPlaybackEngine)?.let { MpvMediampPlayerSurface(it.player, Modifier.fillMaxSize()) }
            } else {
                CastingPanel(casting, status, onPlayHere = ::playHere)
            }

            VideoGestures(
                onClick = {
                    if (!ignoreClicks) togglePlay()
                    refocus()
                },
                onDoubleClick = { if (!ignoreClicks) onToggleFullscreen() },
                onRightClick = { at -> menu = MenuAt(MenuPage.Main, MenuAt.Anchor.Cursor, at) },
                onMouseBack = onBack,
                scope = scope,
                onHold = { on ->
                    if (on && !ignoreClicks && holdSpeed > 0f && status.playing && speedBeforeHold == null) {
                        // Not remembered as the series' speed, as a pick in the speed menu is.
                        speedBeforeHold = status.speed
                        engine.setSpeed(holdSpeed)
                        showFlash(Flash.Kind.Text, "${TouchPrefs.holdLabel(holdSpeed)}  \u25B6\u25B6")
                    } else if (!on) {
                        speedBeforeHold?.let { engine.setSpeed(it) }
                        speedBeforeHold = null
                    }
                },
            )

            // The show's name, with the episode under it ("S01E04  ·  The Lighthouse") and the chat.
            val parsed = remember(item) { SeriesShelf.episodeOf(item) }
            val episode = episodes.current
            val title = (episode?.show ?: parsed.title).ifBlank { parsed.title }.ifBlank { item.title }
            val subtitle = listOfNotNull(
                episode?.label,
                current.chatTitle.takeIf { it.isNotBlank() },
            ).joinToString("  ·  ")
            castTitle[0] = com.tmplayer.desktop.cast.CastTitle(title, subtitle)

            CompositionLocalProvider(
                LocalPlayerCopies provides PlayerCopies(copies, current.item.id, qualities, current.item.playingQuality),
                LocalStoryboard provides storyboard,
                LocalFrameSource provides frameSource,
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
                watchedLabel = if (watched != null && current.fromTelegram && hasMessage(item)) {
                    WatchedWords.markLabel(SettingsStore.progressKey(item.chatId, item.messageId) in watchedKeys)
                } else {
                    null
                },
                onHoverControls = { overControls = it },
                onBack = onBack,
                onTogglePlay = ::togglePlay,
                onSeekBy = { dispatch(PlayerAction.SeekBy(it)) },
                onSeekTo = ::jumpTo,
                onEpisode = ::switchTo,
                onToggleRemaining = {
                    showRemaining = !showRemaining
                    val value = showRemaining
                    playerScope.launch { runCatching { settings.updateTouchPrefs { it.copy(showRemaining = value) } } }
                },
                onVolume = ::setVolume,
                onToggleMute = ::toggleMute,
                onToggleFullscreen = onToggleFullscreen,
                onMiniPlayer = { dispatch(PlayerAction.MiniPlayer) },
                onOpenMenu = { menu = it },
                onCloseMenu = {
                    menu = null
                    refocus()
                },
                sleepTimer = sleepTimerDetail(),
                loop = abLoop,
                bookmarks = if (marksOn) marks else null,
                onMenuAction = { action ->
                    noteInput()
                    when (action) {
                        is MenuAction.Do -> dispatch(action.action)
                        is MenuAction.Track -> selectTrack(action.type, action.track)
                        is MenuAction.Speed -> setSpeed(action.speed)
                        is MenuAction.Shape -> setScale(action.scale)
                        MenuAction.ToggleDownmix -> {
                            val on = !status.downmix
                            engine.setDownmix(on)
                            prefs.update { it.copy(downmix = on) }
                            showFlash(Flash.Kind.Text, if (on) s.playerDownmixOn else s.playerDownmixOff)
                        }
                        MenuAction.ToggleVolumeBoost -> toggleVolumeBoost()
                        MenuAction.ToggleNightMode -> toggleNightMode()
                        is MenuAction.Stereo -> setStereo(action.stereo)
                        is MenuAction.Sleep -> setSleepTimer(action.minutes)
                        is MenuAction.SubtitleDelay -> stepSubtitleDelay(action.direction)
                        is MenuAction.AudioDelay -> stepAudioDelay(action.direction)
                        is MenuAction.SubtitleLook -> setSubtitleStyle(action.style)
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
                        MenuAction.SearchOnline -> showOnline = true
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
                },
                onEpisodes = if (episodes.series != null) {
                    {
                        menu = null
                        showEpisodes = true
                    }
                } else {
                    null
                },
                castButton = { CastButton() },
                subtitlesButton = com.tmplayer.cast.CAST_SUBTITLES || activeEngine !is com.tmplayer.desktop.cast.CastPlaybackEngine,
            ) }

            // Sound alone: its name and chat where the picture would be, unless the file carries
            // a cover, which mpv draws as the picture.
            if (com.tmplayer.data.ChatAudio.isAudio(current.item) && phase == Phase.Playing && status.videoWidth == 0) {
                Column(
                    Modifier.align(Alignment.Center).padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    androidx.compose.material3.Icon(com.tmplayer.ui.icons.TmIcons.Audio, contentDescription = null, tint = Color.White.copy(alpha = 0.7f), modifier = Modifier.size(72.dp))
                    Text(current.item.title, color = Color.White, fontSize = 22.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                    if (current.chatTitle.isNotBlank()) Text(current.chatTitle, color = Color.White.copy(alpha = 0.72f), fontSize = 15.sp)
                }
            }

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
                        startOver()
                        refocus()
                    },
                    onTimeout = { resumedFrom = null },
                )
            }

            activeSkip?.let { segment ->
                SkipPill(kind = segment.kind, lifted = showControls, onSkip = {
                    skipSegment()
                    refocus()
                })
            }

            // Not while the screen is locked: nothing under the shield takes a click.
            undoOffer?.takeIf { phase == Phase.Playing && !screenLocked }?.let { offer ->
                key(offer.backToMs) {
                    UndoSeekChip(backToMs = offer.backToMs, lifted = showControls, onUndo = {
                        undoSeek()
                        refocus()
                    })
                }
            }

            if (nextUpVisible && next != null) {
                NextUpCard(
                    label = episodes.labelFor(next),
                    secondsLeft = nextUpSeconds,
                    lifted = showControls,
                    onPlayNow = {
                        switchTo(next)
                        refocus()
                    },
                    onHide = {
                        nextUpDismissed = true
                        refocus()
                    },
                )
            }

            if (showOnline) {
                OnlineSubtitlesPanel(
                    target = {
                        SubtitleTarget(
                            fileName = item.fileName.ifBlank { item.title },
                            sizeBytes = item.sizeBytes,
                            hash = current.onlineHash(),
                            caption = item.caption.ifBlank { null },
                            languages = OnlineSubtitles.languagesFor(null),
                        )
                    },
                    onLoaded = { file, label ->
                        if (engine.addSubtitle(file.absolutePath)) showFlash(Flash.Kind.Text, L.tracksFileLoaded(label))
                        else showFlash(Flash.Kind.Text, L.playerSubtitleLoadFailed(label))
                    },
                    onClose = {
                        showOnline = false
                        refocus()
                    },
                    preset = onlinePreset,
                )
            }

            if (showDetails) {
                DetailsPanel(
                    rows = {
                        buildList {
                            addAll(engine.details())
                            add(s.playerSpeed to SeekMath.speedLabel(engine.state.value.speed))
                            if (item.sizeBytes > 0) add(s.playerDetailsLabelFile to com.tmplayer.player.StreamStats.formatBytes(item.sizeBytes))
                            downloaded?.let {
                                val label = if (current.isDownload) s.playerDetailsLabelDownloaded else s.playerDetailsLabelCached
                                add(label to s.messages.formatter.percent(it.toDouble()))
                            }
                            add("TDLib" to (tdlibVersion ?: if (item.chatId != 0L) s.playerDetailsUnknown else s.playerDetailsNotUsed))
                        }
                    },
                    onClose = {
                        showDetails = false
                        refocus()
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
                onRetry = {
                    scope.launch { runCatching { com.tmplayer.data.Td.retryConnecting() } }
                    attempt++
                    refocus()
                },
                // Kept in the queue, which carries on once the player lets go of the file.
                onDownloadFirst = if (savable && !current.isDownload) {
                    {
                        current.download()
                        onBack()
                    }
                } else {
                    null
                },
                lighter = lighterAction,
                startMs = openingStartMs,
                // What the opening measures: mpv's reads, whether it has opened, its cache ahead.
                signals = {
                    val st = engine.state.value
                    val it = current.item
                    com.tmplayer.player.OpeningProgress.Signals(
                        readBytes = StreamReads.delivered.get() - openingBytesAt[0],
                        onDisk = (downloaded ?: 0f) >= 1f || !current.fromTelegram,
                        opened = st.opened,
                        bufferedMs = if (st.opened) (st.bufferedMs - st.positionMs).coerceAtLeast(0) else -1L,
                        bufferTargetMs = MPV_START_BUFFER_MS,
                        bytesPerSec = if (it.durationSec > 0) it.sizeBytes / it.durationSec else 0L,
                    )
                },
            )

            // A stall while playing that runs on says why, with the opening's three ways on.
            PlaybackStall(
                buffering = phase == Phase.Playing && status.buffering && !status.seekPending,
                bufferedMs = { liveStatus.value.bufferedMs },
                downloaded = downloaded,
                onRetry = {
                    // The connections reopened and the stream asked for again from where it stands.
                    scope.launch { runCatching { com.tmplayer.data.Td.retryConnecting() } }
                    engine.seekToExact(liveStatus.value.positionMs)
                    refocus()
                },
                onDownloadFirst = if (savable && !current.isDownload) {
                    {
                        current.download()
                        onBack()
                    }
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
                onRetry = {
                    attempt++
                    refocus()
                },
                onBack = onBack,
                onFreeUpSpace = onFreeUpSpace,
                onWatchAgain = {
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
                },
                onPlayNext = {
                    switchTo(it)
                    refocus()
                },
                onCancelNext = {
                    phase = Phase.Finished
                    refocus()
                },
                onKeepWatching = { (phase as? Phase.StillWatching)?.let(::keepWatching) },
                nextLabel = { episodes.labelFor(it) },
            )

            val series = episodes.series
            if (showEpisodes && series != null) {
                val watch = remember(progressMap, watchedKeys) {
                    com.tmplayer.ui.browse.SeriesWatch(
                        point = { progressMap[SettingsStore.progressKey(it.chatId, it.messageId)] },
                        finished = { SettingsStore.progressKey(it.chatId, it.messageId) in watchedKeys },
                    )
                }
                fun closeEpisodes() {
                    showEpisodes = false
                    refocus()
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
                            closeEpisodes()
                            if (target.id != item.id) switchTo(target)
                        },
                        onToggleWatched = ::toggleWatchedOf,
                        onAutoplay = { on ->
                            autoplayNext = on
                            playerScope.launch { runCatching { settings.setAutoplayNext(on) } }
                        },
                    ),
                    onClose = ::closeEpisodes,
                )
            }

            notePrompt?.let { prompt ->
                BookmarkNoteDialog(
                    prompt = prompt,
                    onSave = { note ->
                        notePrompt = null
                        saveNote(prompt, note)
                        refocus()
                    },
                    onDismiss = {
                        notePrompt = null
                        refocus()
                    },
                )
            }

            if (timePrompt) {
                GoToTimeDialog(
                    durationMs = status.durationMs,
                    positionMs = liveStatus.value.positionMs,
                    onGo = { target ->
                        timePrompt = false
                        jumpTo(target)
                        showFlash(Flash.Kind.Text, SeekMath.clock(target))
                        refocus()
                    },
                    onDismiss = {
                        timePrompt = false
                        refocus()
                    },
                )
            }

            if (showShortcuts) {
                ShortcutSheet(
                    onClose = {
                        showShortcuts = false
                        refocus()
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
                        noteInput()
                        unlockWanted = System.nanoTime()
                    },
                    onUnlock = ::unlockScreen,
                )
            }
        }
    }

    LaunchedEffect(Unit) { refocus() }
    // Going in or out of fullscreen remaps the window, and some window systems hand it back
    // without focus inside it: take the keyboard again once the change has settled.
    // Not while a sheet or a menu is up: those hold the keyboard themselves, some for typing.
    fun keysBack() {
        if (!showEpisodes && !showShortcuts && !showDetails && !showOnline && notePrompt == null && !timePrompt && menu == null) refocus()
    }
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

/** The hover frame asked of the preview decoder: twice the 160 dp tip at 1x, room for 2x. */
private const val FRAME_WIDTH_PX = 320
private const val FRAME_HEIGHT_PX = 180
private const val NEXT_UP_LEAD_MS = 30_000L

/** How long the card counts down from the start of timed credits before the next episode. */
private const val CREDITS_COUNTDOWN_MS = 15_000L

/** Credits or an ending that roll until the file ends: what [SkipSegments.creditsStart] reads. */
private fun isClosing(segment: SkipSegment, durationMs: Long): Boolean =
    (segment.kind == SkipKind.Credits || segment.kind == SkipKind.Ending) && segment.runsToEnd(durationMs)
private const val AUTOPLAY_COUNTDOWN_SEC = 8
private const val SEEK_RUN_MS = 1_000L
private const val STILL_WATCHING_TICK_MS = 5_000L

/**
 * Two statuses are the same to the player screen's body when only the clock moved: the position
 * and the buffer's end are read live, by the time label, the bar and the values derived from them.
 */
private object SteadyStatus : androidx.compose.runtime.SnapshotMutationPolicy<PlaybackStatus> {
    override fun equivalent(a: PlaybackStatus, b: PlaybackStatus): Boolean =
        a.copy(positionMs = 0, bufferedMs = 0) == b.copy(positionMs = 0, bufferedMs = 0)
}
