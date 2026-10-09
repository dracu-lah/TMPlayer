package com.tmplayer.desktop.player

import androidx.compose.ui.input.key.Key
import com.tmplayer.i18n.L
import com.tmplayer.ui.i18n.LocalStrings
import com.tmplayer.ui.theme.TmMaterialTheme
import com.tmplayer.desktop.os.MediaKeyEcho
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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
import com.tmplayer.data.MediaItem
import com.tmplayer.online.OnlineSubtitles
import com.tmplayer.online.SubtitleTarget
import com.tmplayer.data.MediaName
import com.tmplayer.data.EpisodeNeighbours
import com.tmplayer.data.SeriesShelf
import com.tmplayer.online.EpisodeNames
import com.tmplayer.online.OnlineMetadata
import com.tmplayer.data.ResumeRecord
import com.tmplayer.data.ResumeRules
import com.tmplayer.data.ResumeState
import com.tmplayer.data.SettingsStore
import com.tmplayer.desktop.os.UserDirs
import com.tmplayer.data.TrackChoice
import com.tmplayer.data.WatchedRecord
import com.tmplayer.data.WatchedStore
import com.tmplayer.desktop.DesktopPrefs
import com.tmplayer.desktop.WatchedWords
import com.tmplayer.data.DownloadRunner
import com.tmplayer.data.OfflineDownloads
import com.tmplayer.player.PlaybackSpeed
import com.tmplayer.player.SleepTimer
import com.tmplayer.player.StillWatching
import com.tmplayer.player.SubtitleStyle
import com.tmplayer.player.SyncDelays
import com.tmplayer.player.TouchPrefs
import com.tmplayer.player.VideoScale
import com.tmplayer.platform.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.openani.mediamp.mpv.compose.MpvMediampPlayerSurface
import java.awt.Point
import java.awt.Toolkit
import java.awt.image.BufferedImage

/** Outlives the screen, so the resume write on the way out survives the Back that caused it. */
private val playerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

/** What fills the screen besides the video. */
internal sealed interface Phase {
    data class Loading(val message: String) : Phase
    data object Playing : Phase
    data class Failed(val message: String) : Phase
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
    enum class Kind { Play, Pause, SeekBack, SeekForward, Text, Volume }
}

internal enum class MenuPage { Main, Audio, Subtitles, Speed, Shape, Sleep, Options, SubtitleStyle }

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
        MenuPage.Sleep, MenuPage.Shape -> MenuPage.Options
        MenuPage.Options -> MenuPage.Main
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
) {
    val s = LocalStrings.current
    val engine = remember { engineFactory() }
    val desktop by prefs.state.collectAsState()
    DisposableEffect(engine) { onDispose { engine.close() } }
    LaunchedEffect(engine) { onEngine(engine) }

    val scope = rememberCoroutineScope()
    val status by engine.state.collectAsState()
    val tracks by engine.tracks.collectAsState()

    var current by remember(media) { mutableStateOf(media) }
    var ignoreSavedPosition by remember(media) { mutableStateOf(startFromBeginning) }
    var attempt by remember { mutableIntStateOf(0) }
    var phase by remember { mutableStateOf<Phase>(Phase.Loading(s.playerOpeningVideo)) }
    var episodes by remember { mutableStateOf(Episodes()) }
    var resumedFrom by remember { mutableStateOf<Long?>(null) }
    var autoplayNext by remember { mutableStateOf(true) }
    var nextUpDismissed by remember { mutableStateOf(false) }
    var nextUpShown by remember { mutableStateOf(false) }
    var trackChoice by remember { mutableStateOf(TrackChoice()) }
    var showRemaining by remember { mutableStateOf(false) }
    var ignoreClicks by remember { mutableStateOf(false) }
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
    val item = current.item
    val seriesKey = remember(item) { seriesKeyOf(item) }
    val progressMap by remember(settings) { settings.watchProgress }.collectAsState(initial = emptyMap())

    fun showFlash(kind: Flash.Kind, text: String = "") {
        flash = Flash(kind, text, System.nanoTime())
    }

    fun refocus() {
        runCatching { focus.requestFocus() }
    }

    // ---- open, resume, episodes --------------------------------------------------------------

    LaunchedEffect(current, attempt) {
        phase = Phase.Loading(if (item.chatId != 0L) s.playerConnectingTelegram else s.playerOpeningVideo)
        nextUpDismissed = false
        nextUpShown = false
        resumedFrom = null
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
        val start = reopenAt ?: if (ignoreSavedPosition) 0L else runCatching {
            settings.resumePosition(item.chatId, item.messageId)
        }.getOrDefault(0L)
        val reopening = reopenAt != null
        reopenAt = null
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
        val prefs = OpenPrefs(
            audioLanguage = trackChoice.audioLanguage,
            subtitleLanguage = trackChoice.textLanguage,
            subtitlesOn = if (trackChoice.empty) null else trackChoice.subtitlesOn,
            speed = resumed?.speed?.coerceIn(SeekMath.MIN_SPEED, SeekMath.MAX_SPEED)
                ?: runCatching { settings.playbackSpeedNow() }.getOrDefault(PlaybackSpeed.DEFAULT),
            scale = runCatching { VideoScale.from(settings.videoScaleNow()) }.getOrDefault(VideoScale.Fit),
            downmix = prefs.now.downmix,
            volume = prefs.now.volume,
            muted = prefs.now.muted,
            volumeBoost = runCatching { settings.volumeBoostNow() }.getOrDefault(false),
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
            phase = Phase.Failed(e.message ?: s.playerOpenFailed)
            return@LaunchedEffect
        } finally {
            preparing.cancel()
        }
        phase = Phase.Loading(if (start > 0) s.playerResumingFrom(SeekMath.clock(start)) else s.playerOpeningVideo)
        engine.open(data, start, prefs)
        val error = engine.state.value.error
        if (error != null) {
            phase = Phase.Failed(error)
            return@LaunchedEffect
        }
        ignoreSavedPosition = false
        // Keep watching opens the file again, and a fresh open has no loop in mpv.
        abLoop?.takeIf { it.complete }?.let(engine::setAbLoop)
        phase = Phase.Playing
        if (start > 0 && !reopening) resumedFrom = start
        onPlayingItemChanged(item)
        refocus()
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
        val watched = ResumeRules.watched(position, duration)
        val key = SettingsStore.progressKey(it.chatId, it.messageId)
        if (watched && leaving && watchedHere.add(key)) recordWatched(m, duration, manual = false)
        val forget = watched || key in markedHere
        val description = ResumeRecord.encode(
            fileId = it.fileId,
            title = it.title,
            chatTitle = m.chatTitle,
            sizeBytes = it.sizeBytes,
            durationSec = it.durationSec.takeIf { d -> d > 0 } ?: (duration / 1000).toInt(),
            updatedAt = System.currentTimeMillis(),
            state = resumeState(engine.tracks.value, s.speed),
        )
        playerScope.launch {
            runCatching {
                if (forget) settings.clearResumePosition(it.chatId, it.messageId)
                else settings.saveResumePosition(it.chatId, it.messageId, position, duration, description)
            }
        }
    }

    // Declared after the engine's own, so it is disposed first, while the engine still knows
    // where the old video was.
    DisposableEffect(current) {
        val leaving = current
        onDispose {
            saveResume(leaving, engine.state.value, leaving = true)
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

    val left = status.durationMs - status.positionMs
    val next = episodes.next
    val nextUpVisible = phase == Phase.Playing && next != null && autoplayNext && !nextUpDismissed &&
        status.durationMs > 0 && left in 1..NEXT_UP_LEAD_MS
    LaunchedEffect(nextUpVisible) { if (nextUpVisible) nextUpShown = true }
    LaunchedEffect(left > NEXT_UP_LEAD_MS) { if (left > NEXT_UP_LEAD_MS) nextUpShown = false }

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
    LaunchedEffect(activity, status.playing, menusOpen, overControls, phase, hideAfterMs) {
        if (!status.playing || menusOpen || overControls || phase != Phase.Playing) return@LaunchedEffect
        if (hideAfterMs <= 0L) return@LaunchedEffect
        delay(hideAfterMs)
        controlsUp = false
    }
    val showControls = phase == Phase.Playing && (controlsUp || !status.playing || menusOpen)

    fun poke() {
        controlsUp = true
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
        playerScope.launch { runCatching { settings.setPlaybackSpeed(value) } }
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
        val wasPlaying = status.playing
        if (!showControls) showFlash(if (wasPlaying) Flash.Kind.Pause else Flash.Kind.Play)
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
        engine.seekTo(0)
        engine.play()
        resumedFrom = null
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
        val at = status.positionMs
        // The name the title bar shows ("Night Train S01E02"), not the release name with its dots.
        val parsed = MediaName.parse(item.fileName.ifBlank { item.title })
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
        val at = status.positionMs
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
            engine.seekBy(delta)
            seekFlash(delta)
            return
        }
        val at = status.positionMs
        val target = if (forward) Chapters.next(chapters, at) else Chapters.previous(chapters, at)
        if (target == null) {
            showFlash(Flash.Kind.Text, if (forward) L.playerLastChapter else L.playerBeforeFirstChapter)
            return
        }
        engine.seekTo(chapters[target].startMs)
        showFlash(Flash.Kind.Text, Chapters.label(chapters, target))
    }

    fun dispatch(action: PlayerAction) {
        when (action) {
            PlayerAction.TogglePlay -> togglePlay()
            PlayerAction.Play -> engine.play()
            PlayerAction.Pause -> engine.pause()
            is PlayerAction.SeekBy -> {
                engine.seekBy(action.deltaMs)
                seekFlash(action.deltaMs)
            }
            is PlayerAction.JumpToTenth -> SeekMath.tenth(status.durationMs, action.tenth)?.let {
                engine.seekTo(it)
                showFlash(Flash.Kind.Text, L.messages.formatter.percent(action.tenth / 10.0))
            }
            PlayerAction.JumpToEnd -> if (status.durationMs > 0) engine.seekTo(status.durationMs)
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
            is PlayerAction.AudioDelay -> stepAudioDelay(action.direction)
            PlayerAction.SpeedUp -> setSpeed(SeekMath.fineSpeed(status.speed, up = true))
            PlayerAction.SpeedDown -> setSpeed(SeekMath.fineSpeed(status.speed, up = false))
            PlayerAction.SpeedReset -> setSpeed(1f)
            PlayerAction.NextEpisode -> episodes.next?.let(::switchTo) ?: showFlash(Flash.Kind.Text, L.playerNoNextEpisode)
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
            is PlayerAction.ChapterStep -> stepChapter(action.forward)
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
    val mac = remember { System.getProperty("os.name").orEmpty().startsWith("Mac") }

    // ---- layout ------------------------------------------------------------------------------

    PlayerTheme {
        Box(
            modifier
                .fillMaxSize()
                .background(backdrop)
                .onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    noteInput()
                    // The episode list has the keyboard while it is up: Esc closes it, and every
                    // other key (the arrows, Enter) is its own, not a seek or a fullscreen.
                    if (showEpisodes) {
                        if (event.key == androidx.compose.ui.input.key.Key.Escape) {
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
                        return@onPreviewKeyEvent true
                    }
                    val press = KeyPress(
                        key = event.key,
                        shift = event.isShiftPressed,
                        ctrl = event.isCtrlPressed,
                        alt = event.isAltPressed,
                        meta = event.isMetaPressed,
                    )
                    val context = KeyContext(fullscreen = fullscreen, speedIsNormal = status.speed == 1f, mac = mac)
                    val action = PlayerKeys.actionFor(press, context) ?: return@onPreviewKeyEvent false
                    // Over the loading and error sheets only the way out works.
                    if (phase != Phase.Playing && action !in OUTSIDE_PLAYBACK) return@onPreviewKeyEvent false
                    // The same key may already have reached the player through the media session.
                    val playKey = event.key == Key.MediaPlayPause || event.key == Key.MediaPlay || event.key == Key.MediaPause
                    if (playKey && !MediaKeyEcho.claim(MediaKeyEcho.Source.Keyboard)) return@onPreviewKeyEvent true
                    dispatchNow(action)
                    true
                }
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
                    if (phase != Phase.Playing) return@onPointerEvent
                    val delta = event.changes.firstOrNull()?.scrollDelta ?: return@onPointerEvent
                    SeekMath.wheelAction(delta.x, delta.y, event.keyboardModifiers.pointerShift, wheelSeeksNow)
                        ?.let(dispatchNow)
                }
                .pointerHoverIcon(if (fullscreen && !showControls && menu == null) blankCursor else PointerIcon.Default)
                .subtitleDropTarget(onDrop = ::loadSubtitle, onRefused = { showFlash(Flash.Kind.Text, it) }),
        ) {
            (engine as? MpvPlaybackEngine)?.let { MpvMediampPlayerSurface(it.player, Modifier.fillMaxSize()) }

            VideoGestures(
                onClick = {
                    if (!ignoreClicks) togglePlay()
                    refocus()
                },
                onDoubleClick = { if (!ignoreClicks) onToggleFullscreen() },
                onRightClick = { at -> menu = MenuAt(MenuPage.Main, MenuAt.Anchor.Cursor, at) },
                onMouseBack = onBack,
                scope = scope,
            )

            // The show's name, with the episode under it ("S01E04  ·  The Lighthouse") and the chat.
            val parsed = remember(item) { SeriesShelf.episodeOf(item) }
            val episode = episodes.current
            val title = (episode?.show ?: parsed.title).ifBlank { parsed.title }.ifBlank { item.title }
            val subtitle = listOfNotNull(
                episode?.label,
                current.chatTitle.takeIf { it.isNotBlank() },
            ).joinToString("  ·  ")

            PlayerOverlay(
                visible = showControls,
                status = status,
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
                onSeekTo = { engine.seekTo(it) },
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
                        is MenuAction.Sleep -> setSleepTimer(action.minutes)
                        is MenuAction.SubtitleDelay -> stepSubtitleDelay(action.direction)
                        is MenuAction.AudioDelay -> stepAudioDelay(action.direction)
                        is MenuAction.SubtitleLook -> setSubtitleStyle(action.style)
                        MenuAction.StartOver -> startOver()
                        MenuAction.ToggleIgnoreClicks -> {
                            ignoreClicks = !ignoreClicks
                            showFlash(Flash.Kind.Text, if (ignoreClicks) s.playerClicksIgnored else s.playerClicksWork)
                        }
                        MenuAction.CopyLink -> copyLink()
                        MenuAction.Download -> showFlash(Flash.Kind.Text, current.download())
                        MenuAction.OpenElsewhere -> openElsewhere()
                        MenuAction.Details -> showDetails = true
                        MenuAction.Shortcuts -> showShortcuts = true
                        MenuAction.ToggleWatched -> toggleWatched()
                        MenuAction.SearchOnline -> showOnline = true
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
            )

            FeedbackLayer(
                flash = flash,
                spinner = phase == Phase.Playing && status.buffering && !showControls,
                chip = if (phase == Phase.Playing && status.buffering && !showControls) s.playerBuffering else null,
            )

            resumedFrom?.let { from ->
                ResumeNotice(
                    from = from,
                    lifted = showControls,
                    onStartOver = ::startOver,
                    onTimeout = { resumedFrom = null },
                )
            }

            if (nextUpVisible && next != null) {
                NextUpCard(
                    label = episodes.labelFor(next),
                    secondsLeft = ((left + 999) / 1000).toInt(),
                    lifted = showControls,
                    onPlayNow = { switchTo(next) },
                    onHide = { nextUpDismissed = true },
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
                bufferedMs = (status.bufferedMs - status.positionMs).coerceAtLeast(0),
                isDownload = current.isDownload,
                onBack = onBack,
                thumbnailOverride = loaderThumbnail,
            )

            StatusSheet(
                phase = phase,
                title = title,
                subtitle = subtitle,
                onRetry = { attempt++ },
                onBack = onBack,
                onWatchAgain = {
                    phase = Phase.Playing
                    engine.seekTo(0)
                    engine.play()
                },
                onPlayNext = { switchTo(it) },
                onCancelNext = { phase = Phase.Finished },
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
        }
    }

    LaunchedEffect(Unit) { refocus() }
}

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
private const val NEXT_UP_LEAD_MS = 30_000L
private const val AUTOPLAY_COUNTDOWN_SEC = 8
private const val SEEK_RUN_MS = 1_000L
private const val STILL_WATCHING_TICK_MS = 5_000L
