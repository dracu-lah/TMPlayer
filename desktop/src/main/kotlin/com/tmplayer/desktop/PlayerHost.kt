package com.tmplayer.desktop

import com.tmplayer.desktop.cast.stateUpdates

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.tmplayer.data.MediaItem
import com.tmplayer.data.MediaName
import com.tmplayer.data.SettingsStore
import com.tmplayer.desktop.os.KeepAwake
import com.tmplayer.desktop.os.MediaKeyEcho
import com.tmplayer.desktop.os.MediaSession
import com.tmplayer.desktop.os.MediaSessionCallbacks
import com.tmplayer.desktop.player.PlaybackEngine
import com.tmplayer.desktop.player.PlayerScreen
import com.tmplayer.desktop.player.TelegramPlayerMedia
import com.tmplayer.desktop.ui.PlayRequest
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.awt.LocalAwtWindow
import com.tmplayer.desktop.os.MiniPlayerWindow
import com.tmplayer.desktop.ui.MiniBarInfo
import com.tmplayer.desktop.ui.MiniLink
import com.tmplayer.player.MiniPlayerState
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import javax.swing.SwingUtilities

private const val MINI_AFTER_FULLSCREEN_MS = 500L

/**
 * The player as the shell shows it, tied to the OS: the screen stays awake while a video plays,
 * and the desktop's media controls (MPRIS on Linux, SMTC on Windows) see the title and drive the
 * engine.
 */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
fun PlayerHost(
    request: PlayRequest,
    onClose: () -> Unit,
    settings: SettingsStore,
    fullscreen: Boolean,
    onToggleFullscreen: () -> Unit,
    onToggleAlwaysOnTop: () -> Unit,
    onQuit: () -> Unit,
    onRaise: () -> Unit,
    /** The player's "Not enough space": closed, and Downloads open on Free up space. */
    onFreeUpSpace: (() -> Unit)? = null,
    /** The mini player (U8): the shell's docking, and the player drawn nowhere while docked. */
    mini: MiniLink? = null,
) {
    var engine by remember { mutableStateOf<PlaybackEngine?>(null) }
    var playing by remember { mutableStateOf<MediaItem>(request.item) }
    val keepAwake = remember { KeepAwake.create() }
    val awtWindow = LocalAwtWindow.current as? java.awt.Frame
    // Lock the screen holds off the OS media controls too: a keyboard's media keys arrive this
    // way as often as they arrive as keys, and the lock ignores those. Raise still works.
    val locked = remember { java.util.concurrent.atomic.AtomicBoolean(false) }
    val session = remember {
        // Called on a D-Bus or SMTC thread; the engine wants the Swing one.
        fun onEdt(action: (PlaybackEngine) -> Unit) = SwingUtilities.invokeLater {
            if (!locked.get()) engine?.let(action)
        }
        // A media key can also reach the focused window as a key press; see [MediaKeyEcho].
        fun playPause(action: (PlaybackEngine) -> Unit) {
            if (MediaKeyEcho.claim(MediaKeyEcho.Source.Session)) onEdt(action)
        }
        MediaSession.create(object : MediaSessionCallbacks {
            override fun onPlay() = playPause { it.play() }
            override fun onPause() = playPause { it.pause() }
            override fun onPlayPause() = playPause { it.togglePlay() }
            override fun onStop() = SwingUtilities.invokeLater { if (!locked.get()) onClose() }
            override fun onSeekBy(offsetMs: Long) = onEdt { it.seekBy(offsetMs) }
            override fun onSeekTo(positionMs: Long) = onEdt { it.seekTo(positionMs) }
            override fun onRaise() = SwingUtilities.invokeLater(onRaise)
        }, awtWindow)
    }
    DisposableEffect(Unit) {
        onDispose {
            keepAwake.close()
            session.release()
        }
    }

    // The mini player shrinks this window; whatever closes the player puts it back.
    val windowMini = remember(awtWindow) { awtWindow?.let(::MiniPlayerWindow) }
    DisposableEffect(windowMini) { onDispose { windowMini?.leave() } }
    val scope = rememberCoroutineScope()
    val isFullscreen by rememberUpdatedState(fullscreen)

    val media = remember(request.item.chatId, request.item.messageId) {
        TelegramPlayerMedia(request.item, request.chatTitle, DesktopServices.downloads, DesktopServices.watchCache)
    }
    PlayerScreen(
        media = media,
        startFromBeginning = request.startFromBeginning,
        onBack = onClose,
        fullscreen = fullscreen,
        onToggleFullscreen = onToggleFullscreen,
        settings = settings,
        watched = DesktopServices.watched,
        bookmarks = DesktopServices.bookmarks,
        prefs = DesktopServices.prefs,
        onToggleAlwaysOnTop = onToggleAlwaysOnTop,
        onMiniPlayer = windowMini?.let { m ->
            {
                scope.launch {
                    // Out of fullscreen first, and only then smaller, or the window manager
                    // finishes leaving fullscreen on top of the new size.
                    if (isFullscreen && !m.active) {
                        onToggleFullscreen()
                        delay(MINI_AFTER_FULLSCREEN_MS)
                    }
                    m.toggle()
                }
            }
        },
        onQuit = onQuit,
        onPlayingItemChanged = { playing = it },
        onEngine = { engine = it },
        onLockedChanged = locked::set,
        downloads = DesktopServices.downloads,
        onFreeUpSpace = onFreeUpSpace,
        onDock = mini?.let { link -> { context -> link.leave(context.copy(inPictureInPicture = windowMini?.active == true)) } },
        docked = mini?.docked == true,
        onDockedStopped = { needsAnswer -> mini?.stopped(needsAnswer) },
    )

    // The bar's play and pause drives the one engine, through a cast too.
    DisposableEffect(engine, mini) {
        mini?.togglePlay = { engine?.togglePlay() }
        onDispose { mini?.togglePlay = {} }
    }
    val dockedNow = mini?.docked == true
    LaunchedEffect(engine, playing, dockedNow) {
        val e = engine ?: return@LaunchedEffect
        val name = playing.fileName.ifBlank { playing.title }
        val title = MediaName.parse(name).title.ifBlank { name }
        // Through a cast too: the OS media controls follow the TV while it plays.
        e.stateUpdates().collect { s ->
            if (s.playing && s.opened && !s.ended) keepAwake.acquire() else keepAwake.release()
            session.update(title, s.durationMs, s.positionMs, s.playing && !s.ended)
            // The bar reads this, and only while it is up: no recomposition for it at other times.
            if (dockedNow) {
                mini?.info = MiniBarInfo(title, s.playing && !s.ended, MiniPlayerState.progress(s.positionMs, s.durationMs))
            }
        }
    }
}
