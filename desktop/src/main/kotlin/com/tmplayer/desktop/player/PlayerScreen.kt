package com.tmplayer.desktop.player

import androidx.compose.ui.input.key.Key
import com.tmplayer.desktop.os.MediaKeyEcho
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
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
import com.tmplayer.data.MediaName
import com.tmplayer.data.ResumeRecord
import com.tmplayer.data.SettingsStore
import com.tmplayer.data.TrackChoice
import com.tmplayer.data.WatchedRecord
import com.tmplayer.data.WatchedStore
import com.tmplayer.desktop.DesktopPrefs
import com.tmplayer.desktop.WatchedWords
import com.tmplayer.player.PlaybackSpeed
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
}

/** One flash of feedback over the picture; [id] restarts the animation for a repeat. */
internal data class Flash(val kind: Kind, val text: String, val id: Long) {
    enum class Kind { Play, Pause, SeekBack, SeekForward, Text, Volume }
}

internal enum class MenuPage { Main, Audio, Subtitles, Speed, Shape }

/** Where an open menu hangs: at the cursor for a right click, or under a button. */
internal data class MenuAt(val page: MenuPage, val anchor: Anchor, val at: Offset = Offset.Zero) {
    enum class Anchor { Cursor, Overflow, Subtitles, Audio }
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
 * @param engineFactory the engine; libmpv in the app, a fake in the UI tests that drive the mouse.
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
    engineFactory: () -> PlaybackEngine = { MpvPlaybackEngine(OpenPrefs.hwdecFor(prefs.now.softwareDecoding)) },
) {
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
    var phase by remember { mutableStateOf<Phase>(Phase.Loading("Opening")) }
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
    var menu by remember { mutableStateOf<MenuAt?>(null) }
    var showDetails by remember { mutableStateOf(detailsOpen) }
    var showShortcuts by remember { mutableStateOf(false) }
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

    val focus = remember { FocusRequester() }
    val item = current.item
    val seriesKey = remember(item) { seriesKeyOf(item) }

    fun showFlash(kind: Flash.Kind, text: String = "") {
        flash = Flash(kind, text, System.nanoTime())
    }

    fun refocus() {
        runCatching { focus.requestFocus() }
    }

    // ---- open, resume, episodes --------------------------------------------------------------

    LaunchedEffect(current, attempt) {
        phase = Phase.Loading(if (item.chatId != 0L) "Connecting to Telegram" else "Opening")
        nextUpDismissed = false
        nextUpShown = false
        resumedFrom = null
        launch { episodes = runCatching { current.episodes() }.getOrDefault(Episodes()) }
        launch { tdlibVersion = current.tdlibVersion() }
        autoplayNext = runCatching { settings.autoplayNextNow() }.getOrDefault(true)
        showRemaining = runCatching { settings.touchPrefsNow().showRemaining }.getOrDefault(false)
        val start = if (ignoreSavedPosition) 0L else runCatching {
            settings.resumePosition(item.chatId, item.messageId)
        }.getOrDefault(0L)
        trackChoice = runCatching { settings.trackChoice(seriesKey) }.getOrDefault(TrackChoice())
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
            speed = runCatching { settings.playbackSpeedNow() }.getOrDefault(PlaybackSpeed.DEFAULT),
            scale = runCatching { VideoScale.from(settings.videoScaleNow()) }.getOrDefault(VideoScale.Fit),
            downmix = prefs.now.downmix,
            volume = prefs.now.volume,
            muted = prefs.now.muted,
            hwdec = OpenPrefs.hwdecFor(prefs.now.softwareDecoding),
            subtitleStyle = runCatching { settings.subtitleStyleNow() }.getOrDefault(SubtitleStyle()),
            delays = delays,
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
            phase = Phase.Failed(e.message ?: "This video could not be opened.")
            return@LaunchedEffect
        } finally {
            preparing.cancel()
        }
        phase = Phase.Loading(if (start > 0) "Resuming from ${SeekMath.clock(start)}" else "Opening")
        engine.open(data, start, prefs)
        val error = engine.state.value.error
        if (error != null) {
            phase = Phase.Failed(error)
            return@LaunchedEffect
        }
        ignoreSavedPosition = false
        phase = Phase.Playing
        if (start > 0) resumedFrom = start
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

    fun saveResume(m: PlayerMedia, s: PlaybackStatus) {
        if (!s.opened) return
        val it = m.item
        val position = s.positionMs
        val duration = s.durationMs
        val watched = SeekMath.watched(position, duration, SettingsStore.END_MARGIN_MS)
        if (watched) recordWatched(m, duration, manual = false)
        val forget = watched || SettingsStore.progressKey(it.chatId, it.messageId) in markedHere
        val description = ResumeRecord.encode(
            fileId = it.fileId,
            title = it.title,
            chatTitle = m.chatTitle,
            sizeBytes = it.sizeBytes,
            durationSec = it.durationSec.takeIf { d -> d > 0 } ?: (duration / 1000).toInt(),
            updatedAt = System.currentTimeMillis(),
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
            saveResume(leaving, engine.state.value)
            leaving.release()
        }
    }

    LaunchedEffect(current) {
        while (true) {
            delay(RESUME_TICK_MS)
            if (engine.state.value.playing && phase == Phase.Playing) saveResume(current, engine.state.value)
        }
    }

    fun switchTo(next: MediaItem) {
        saveResume(current, engine.state.value)
        current = current.episode(next)
    }

    // The end: the next episode after a countdown, or the last frame with a way to watch again.
    LaunchedEffect(status.ended) {
        if (!status.ended || phase != Phase.Playing) return@LaunchedEffect
        recordWatched(current, status.durationMs, manual = false)
        runCatching { settings.clearResumePosition(item.chatId, item.messageId) }
        val next = episodes.next
        if (next == null || !autoplayNext || nextUpDismissed) {
            phase = Phase.Finished
            return@LaunchedEffect
        }
        // The card already counted the last half minute down; a second countdown would be a wait for nothing.
        if (nextUpShown) {
            switchTo(next)
            return@LaunchedEffect
        }
        for (second in AUTOPLAY_COUNTDOWN_SEC downTo 1) {
            phase = Phase.Countdown(next, second)
            delay(1_000)
        }
        switchTo(next)
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
        val noun = if (type == TrackType.Audio) "Audio" else "Subtitles"
        showFlash(Flash.Kind.Text, if (track == null) "$noun off" else "$noun: ${track.label}")
    }

    fun cycleTrack(type: TrackType, forward: Boolean) {
        if (tracks.none { it.type == type }) {
            showFlash(Flash.Kind.Text, if (type == TrackType.Audio) "No other audio" else "No subtitles")
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
        showFlash(Flash.Kind.Volume, "$value%")
    }

    fun toggleMute() {
        val muted = !status.muted
        engine.setMuted(muted)
        prefs.update { it.copy(muted = muted) }
        showFlash(Flash.Kind.Volume, if (muted) "Muted" else "${status.volume}%")
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
        showFlash(Flash.Kind.Text, "Subtitle delay ${SyncDelays.label(ms)}")
        saveDelays()
    }

    fun stepAudioDelay(direction: Int) {
        val ms = if (direction == 0) 0L else SyncDelays.step(engine.state.value.audioDelayMs, direction)
        engine.setAudioDelay(ms)
        showFlash(Flash.Kind.Text, "Audio delay ${SyncDelays.label(ms)}")
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
        val text = if (seconds >= 60 && seconds % 60 == 0L) "${seconds / 60} min" else "$seconds s"
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
            showFlash(Flash.Kind.Text, "Marked as unwatched")
        } else {
            markedHere.add(key)
            val chat = item.chatId
            val message = item.messageId
            recordWatched(current, status.durationMs, manual = true) { settings.clearResumePosition(chat, message) }
            showFlash(Flash.Kind.Text, "Marked as watched")
        }
    }

    fun startOver() {
        engine.seekTo(0)
        engine.play()
        resumedFrom = null
        showFlash(Flash.Kind.Text, "From the start")
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
                showFlash(Flash.Kind.Text, "${action.tenth * 10}%")
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
                    showFlash(Flash.Kind.Text, "No subtitles")
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
            PlayerAction.NextEpisode -> episodes.next?.let(::switchTo) ?: showFlash(Flash.Kind.Text, "No next episode")
            PlayerAction.PreviousEpisode -> episodes.previous?.let(::switchTo)
                ?: showFlash(Flash.Kind.Text, "No previous episode")
            PlayerAction.AlwaysOnTop -> onToggleAlwaysOnTop?.invoke()
                ?: showFlash(Flash.Kind.Text, "Always on top is not available here")
            PlayerAction.MiniPlayer -> onMiniPlayer?.invoke()
                ?: showFlash(Flash.Kind.Text, "The mini player is not available here")
            PlayerAction.Stats -> showDetails = !showDetails
            PlayerAction.Back -> onBack()
            PlayerAction.Quit -> onQuit?.invoke()
            PlayerAction.ShortcutSheet -> showShortcuts = !showShortcuts
        }
    }

    val dispatchNow by rememberUpdatedState(::dispatch)
    val wheelSeeksNow by rememberUpdatedState(desktop.wheelSeeks)

    fun loadSubtitle(path: String) {
        if (phase != Phase.Playing) return
        val name = java.io.File(path).name
        if (engine.addSubtitle(path)) showFlash(Flash.Kind.Text, "Subtitles: $name") else showFlash(Flash.Kind.Text, "Could not load $name")
    }

    fun copyLink() {
        scope.launch {
            val link = current.messageLink()
            if (link == null) {
                showFlash(Flash.Kind.Text, "This chat has no links to its messages")
            } else {
                runCatching {
                    java.awt.Toolkit.getDefaultToolkit().systemClipboard.setContents(java.awt.datatransfer.StringSelection(link), null)
                }
                showFlash(Flash.Kind.Text, "Link copied")
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
                showFlash(Flash.Kind.Text, "Only a video that is all here can open in another app")
            } else {
                com.tmplayer.desktop.os.OpenExternal.open(file)
                showFlash(Flash.Kind.Text, "Opening in another app")
            }
        }
    }
    val mac = remember { System.getProperty("os.name").orEmpty().startsWith("Mac") }

    // ---- layout ------------------------------------------------------------------------------

    MaterialTheme(colorScheme = playerColors) {
        Box(
            modifier
                .fillMaxSize()
                .background(Color.Black)
                .onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    // Sheets peel off before Esc means anything else.
                    if (event.key == androidx.compose.ui.input.key.Key.Escape && (showShortcuts || showDetails)) {
                        showShortcuts = false
                        showDetails = false
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
                .onPointerEvent(PointerEventType.Enter, PointerEventPass.Initial) { poke() }
                .onPointerEvent(PointerEventType.Exit, PointerEventPass.Initial) {
                    if (status.playing && menu == null) controlsUp = false
                }
                .onPointerEvent(PointerEventType.Scroll) { event ->
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

            val parsed = remember(item) { MediaName.parse(item.fileName.ifBlank { item.title }) }
            val title = parsed.title.ifBlank { item.title }
            val subtitle = listOfNotNull(
                parsed.episodeCode,
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
                onCycleSpeed = { setSpeed(SeekMath.nextSpeedStop(status.speed)) },
                onCycleScale = { setScale(status.scale.next()) },
                onVolume = ::setVolume,
                onToggleMute = ::toggleMute,
                onToggleFullscreen = onToggleFullscreen,
                onMiniPlayer = { dispatch(PlayerAction.MiniPlayer) },
                onOpenMenu = { menu = it },
                onCloseMenu = {
                    menu = null
                    refocus()
                },
                onMenuAction = { action ->
                    when (action) {
                        is MenuAction.Do -> dispatch(action.action)
                        is MenuAction.Track -> selectTrack(action.type, action.track)
                        is MenuAction.Speed -> setSpeed(action.speed)
                        is MenuAction.Shape -> setScale(action.scale)
                        MenuAction.ToggleDownmix -> {
                            val on = !status.downmix
                            engine.setDownmix(on)
                            prefs.update { it.copy(downmix = on) }
                            showFlash(Flash.Kind.Text, if (on) "Downmix to stereo on" else "Downmix to stereo off")
                        }
                        is MenuAction.SubtitleDelay -> stepSubtitleDelay(action.direction)
                        is MenuAction.AudioDelay -> stepAudioDelay(action.direction)
                        is MenuAction.SubtitleLook -> setSubtitleStyle(action.style)
                        MenuAction.StartOver -> startOver()
                        MenuAction.ToggleIgnoreClicks -> {
                            ignoreClicks = !ignoreClicks
                            showFlash(Flash.Kind.Text, if (ignoreClicks) "Clicks on the video are ignored" else "Clicks on the video work again")
                        }
                        MenuAction.CopyLink -> copyLink()
                        MenuAction.Download -> showFlash(Flash.Kind.Text, current.download())
                        MenuAction.OpenElsewhere -> openElsewhere()
                        MenuAction.Details -> showDetails = true
                        MenuAction.Shortcuts -> showShortcuts = true
                        MenuAction.ToggleWatched -> toggleWatched()
                    }
                },
            )

            FeedbackLayer(
                flash = flash,
                spinner = phase == Phase.Playing && status.buffering && !showControls,
                chip = if (phase == Phase.Playing && status.buffering && !showControls) "Buffering" else null,
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
                    next = next,
                    secondsLeft = ((left + 999) / 1000).toInt(),
                    lifted = showControls,
                    onPlayNow = { switchTo(next) },
                    onHide = { nextUpDismissed = true },
                )
            }

            if (showDetails) {
                DetailsPanel(
                    rows = {
                        buildList {
                            addAll(engine.details())
                            add("Speed" to SeekMath.speedLabel(engine.state.value.speed))
                            if (item.sizeBytes > 0) add("File" to com.tmplayer.player.StreamStats.formatBytes(item.sizeBytes))
                            downloaded?.let { add("Downloaded" to "${(it * 100).toInt()}%") }
                            add("TDLib" to (tdlibVersion ?: if (item.chatId != 0L) "unknown" else "not used"))
                        }
                    },
                    onClose = {
                        showDetails = false
                        refocus()
                    },
                )
            }

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
            )

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

private val playerColors = darkColorScheme(
    primary = Color(0xFF8AB4F8),
    onPrimary = Color(0xFF0B1A2E),
    surface = Color(0xFF1C1C1E),
    onSurface = Color.White,
    surfaceContainer = Color(0xFF242426),
)

private const val RESUME_TICK_MS = 10_000L
private const val NEXT_UP_LEAD_MS = 30_000L
private const val AUTOPLAY_COUNTDOWN_SEC = 8
private const val SEEK_RUN_MS = 1_000L
