package com.tmplayer.desktop.cast

import com.tmplayer.desktop.player.AbLoop
import com.tmplayer.desktop.player.MediaTrack
import com.tmplayer.desktop.player.OpenPrefs
import com.tmplayer.desktop.player.PlaybackEngine
import com.tmplayer.desktop.player.PlaybackStatus
import com.tmplayer.desktop.player.TrackType
import com.tmplayer.i18n.L
import com.tmplayer.platform.Logger
import com.tmplayer.player.SubtitleStyle
import com.tmplayer.player.VideoScale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.openani.mediamp.source.MediaData
import java.io.File

/**
 * The desktop's one cast session, shared by every player window it opens: which device, whether
 * the channel is up, and the finding of devices while the picker is open. A video opened while
 * a session is up goes to the TV from the start; the next episode follows it there.
 */
object DesktopCast {

    sealed interface State {
        data object Idle : State
        data class Connecting(val device: CastDevice) : State
        data class Connected(val device: CastDevice, val client: CastClient) : State

        /** The channel broke: the TV went off or left the network. Reconnect, or play here. */
        data class Lost(val device: CastDevice) : State

        data class Failed(val device: CastDevice, val message: String) : State
    }

    val discovery = CastDiscovery()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + com.tmplayer.platform.JobFailures.handler("DesktopCast"))
    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()
    private var watching: Job? = null

    fun connect(device: CastDevice) {
        (state.value as? State.Connected)?.let { if (it.device.id == device.id) return else it.client.stopReceiver() }
        watching?.cancel()
        _state.value = State.Connecting(device)
        scope.launch {
            val client = CastClient(device)
            try {
                client.connect()
            } catch (e: Exception) {
                Logger.w(TAG, "Could not connect to ${device.name}", e)
                runCatching { client.close() }
                if ((state.value as? State.Connecting)?.device == device) _state.value = State.Failed(device, L.castConnectFailed(device.name))
                return@launch
            }
            if ((state.value as? State.Connecting)?.device != device) {
                client.close()
                return@launch
            }
            _state.value = State.Connected(device, client)
            watching = scope.launch {
                client.lost.first { it }
                if ((state.value as? State.Connected)?.client === client) _state.value = State.Lost(device)
                runCatching { client.close() }
            }
        }
    }

    /** Ends the cast: the TV's player stops and the session closes. */
    fun disconnect() {
        watching?.cancel()
        (state.value as? State.Connected)?.client?.stopReceiver()
        _state.value = State.Idle
    }

    /** Forgets a lost or failed session without telling the TV anything. */
    fun forget() {
        watching?.cancel()
        _state.value = State.Idle
    }

    fun reconnect() {
        val device = when (val s = state.value) {
            is State.Lost -> s.device
            is State.Failed -> s.device
            else -> return
        }
        connect(discovery.devices.value.firstOrNull { it.id == device.id } ?: device)
    }

    private const val TAG = "DesktopCast"
}

/**
 * The engine the player screen holds: this computer's mpv, or the TV while a cast is on. Every
 * call goes to whichever is [active]. [state] and [tracks] are the active engine's own flows, so
 * a composable reading them through [activeFlow] follows a switch; code that collects for longer
 * uses [updates], which carries on across one.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SwitchingEngine(val local: PlaybackEngine) : PlaybackEngine {

    private val _active = MutableStateFlow(local)
    val activeFlow: StateFlow<PlaybackEngine> = _active.asStateFlow()
    val active: PlaybackEngine get() = _active.value
    val cast: CastPlaybackEngine? get() = active as? CastPlaybackEngine

    override val state: StateFlow<PlaybackStatus> get() = active.state
    override val tracks: StateFlow<List<MediaTrack>> get() = active.tracks

    /** The active engine's status, switch after switch. */
    val updates: Flow<PlaybackStatus> = _active.flatMapLatest { it.state }

    /**
     * Hands playback to [engine]. Leaving this computer stops mpv, which lets go of its stream so
     * TDLib serves the TV alone; leaving the TV closes the cast engine and its server (telling the
     * receiver to stop only when [tellReceiver]). The caller opens the video again on the new
     * engine, at the place the old one reached.
     */
    fun switchTo(engine: PlaybackEngine, tellReceiver: Boolean = true) {
        val old = _active.value
        if (old === engine) return
        when {
            old === local -> runCatching { local.stop() }
            old is CastPlaybackEngine -> runCatching { if (tellReceiver) old.close() else old.abandon() }
        }
        _active.value = engine
    }

    override suspend fun open(data: MediaData, startAtMs: Long, prefs: OpenPrefs) = active.open(data, startAtMs, prefs)
    override fun play() = active.play()
    override suspend fun previewFrame(positionMs: Long, width: Int, height: Int) = active.previewFrame(positionMs, width, height)
    override fun pause() = active.pause()
    override fun stop() = active.stop()
    override fun togglePlay() = active.togglePlay()
    override fun seekTo(positionMs: Long) = active.seekTo(positionMs)
    override fun seekBy(deltaMs: Long) = active.seekBy(deltaMs)
    override fun seekToExact(positionMs: Long) = active.seekToExact(positionMs)
    override fun frameStep(forward: Boolean) = active.frameStep(forward)
    override fun setSpeed(speed: Float) = active.setSpeed(speed)
    override fun setVolume(percent: Int) = active.setVolume(percent)
    override fun setMuted(muted: Boolean) = active.setMuted(muted)
    override fun selectTrack(type: TrackType, id: Int?) = active.selectTrack(type, id)
    override fun setScale(scale: VideoScale) = active.setScale(scale)
    override fun setDownmix(stereo: Boolean) = active.setDownmix(stereo)
    override fun setVolumeBoost(on: Boolean) = active.setVolumeBoost(on)
    override fun setNightMode(on: Boolean) = active.setNightMode(on)
    override fun setStereo3d(stereo: com.tmplayer.player.Stereo3d) = active.setStereo3d(stereo)
    override fun setSubtitleStyle(style: SubtitleStyle) = active.setSubtitleStyle(style)
    override fun setSubtitleDelay(ms: Long) = active.setSubtitleDelay(ms)
    override fun setAudioDelay(ms: Long) = active.setAudioDelay(ms)
    override fun addSubtitle(path: String): Boolean = active.addSubtitle(path)
    override suspend fun screenshot(file: File, withSubtitles: Boolean): Boolean = active.screenshot(file, withSubtitles)
    override fun setAbLoop(loop: AbLoop?) = active.setAbLoop(loop)
    override fun details(): List<Pair<String, String>> = active.details()

    override fun close() {
        (active as? CastPlaybackEngine)?.close()
        local.close()
    }
}

/** [PlaybackEngine.state] as a flow that follows a [SwitchingEngine] across a switch. */
fun PlaybackEngine.stateUpdates(): Flow<PlaybackStatus> = (this as? SwitchingEngine)?.updates ?: state
