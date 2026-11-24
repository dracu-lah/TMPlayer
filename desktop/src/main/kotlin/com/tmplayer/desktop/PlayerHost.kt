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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import javax.swing.SwingUtilities

private const val MINI_AFTER_FULLSCREEN_MS = 500L

/** How long a switch waits for a closed player to let go of its files. */
private const val PLAYER_LET_GO_MS = 5_000L


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

    // Before a profile switch, a sign out or Remove all accounts (HX88): the player closes, which
    // saves its place as the profile it opened under (PlayerScreen's dispose), and the switch
    // waits for it to let go of its files. It also counts as something playing, asked about first.
    val closeForSwitch by rememberUpdatedState(onClose)
    DisposableEffect(Unit) {
        val hook: suspend () -> Unit = {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) { closeForSwitch() }
            kotlinx.coroutines.withTimeoutOrNull(PLAYER_LET_GO_MS) {
                while (com.tmplayer.desktop.player.ActiveStreams.openIds().isNotEmpty()) delay(50)
            }
        }
        com.tmplayer.data.BeforeSwitch.addPlayer(hook) { com.tmplayer.i18n.L.profilesWaitingPlayer }
        onDispose { com.tmplayer.data.BeforeSwitch.removePlayer(hook) }
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
    )

    // The profile locks (a PIN profile minimised for a minute, a switch): the video pauses.
    LaunchedEffect(engine) {
        val e = engine ?: return@LaunchedEffect
        pauseWhenLocked(com.tmplayer.data.ProfileUnlock.unlockedId) { e.pause() }
    }

    // The profile the player opened under (HX88): its title shows only while that profile is the open one.
    val openedFor = remember { com.tmplayer.data.Accounts.stamp }
    LaunchedEffect(engine, playing) {
        val e = engine ?: return@LaunchedEffect

        val name = playing.fileName.ifBlank { playing.title }
        val title = MediaName.parse(name).title.ifBlank { name }
        // Through a cast too: the OS media controls follow the TV while it plays. A PIN profile
        // hidden (the window minimised, or locked) shows the app's name only there (HX88), and the
        // title comes back the moment it shows again.
        kotlinx.coroutines.flow.combine(e.stateUpdates(), com.tmplayer.data.ProfileUnlock.hidden()) { s, hidden -> s to hidden }.collect { (s, hidden) ->
            if (s.playing && s.opened && !s.ended) keepAwake.acquire() else keepAwake.release()
            session.update(com.tmplayer.desktop.os.ProfileOutside.mediaTitle(title, hidden, owner = openedFor), s.durationMs, s.positionMs, s.playing && !s.ended, rate = s.speed)
        }
    }
}
