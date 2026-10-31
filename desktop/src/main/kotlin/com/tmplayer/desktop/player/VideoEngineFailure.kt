package com.tmplayer.desktop.player

import com.tmplayer.i18n.L
import com.tmplayer.platform.Logger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.openani.mediamp.source.MediaData

/**
 * libmpv could not be loaded (HX36). Windows Smart App Control and Application Control (WDAC)
 * refuse an unsigned DLL with `UnsatisfiedLinkError: An Application Control policy has blocked
 * this file`, and an incomplete install fails the same way. It is an `Error`, not an `Exception`,
 * so a plain `catch (e: Exception)` missed it and the window died; [MpvNatives.create] turns it
 * into this, whose [message] is already the sentence the player's status sheet shows.
 */
class VideoEngineUnavailable(message: String, cause: Throwable?) : RuntimeException(message, cause)

/** What the viewer is told when the video engine cannot start (HX36). */
object VideoEngineFailure {

    private val BLOCKED = Regex("""application control|smart app control|blocked|policy|0x800711c7""", RegexOption.IGNORE_CASE)

    /** True when [error], or something it was caused by, reads as the OS refusing the file. */
    fun isBlocked(error: Throwable?): Boolean {
        var at = error
        var depth = 0
        while (at != null && depth < MAX_CAUSES) {
            if (BLOCKED.containsMatchIn(at.message.orEmpty())) return true
            at = at.cause?.takeIf { it !== at }
            depth++
        }
        return false
    }

    /** The sheet's sentence for [error]: never empty, never throws. */
    fun describe(error: Throwable?, windows: Boolean = System.getProperty("os.name").orEmpty().startsWith("Windows")): String =
        if (windows && isBlocked(error)) L.playerEngineBlocked else L.playerEngineMissing

    private const val MAX_CAUSES = 8
}

/**
 * Stands in for the engine when libmpv would not load, so the player opens on its status sheet
 * with [reason] instead of the window dying. Every call is a no-op; nothing is ever opened.
 */
internal class NoEngine(reason: String) : PlaybackEngine {
    private val _state = MutableStateFlow(PlaybackStatus(error = reason))
    override val state: StateFlow<PlaybackStatus> = _state
    private val _tracks = MutableStateFlow<List<MediaTrack>>(emptyList())
    override val tracks: StateFlow<List<MediaTrack>> = _tracks

    override suspend fun open(data: MediaData, startAtMs: Long, prefs: OpenPrefs) {
        Logger.w("NoEngine", "open ignored: the video engine is not available")
    }

    override fun play() = Unit
    override fun pause() = Unit
    override fun stop() = Unit
    override fun togglePlay() = Unit
    override fun seekTo(positionMs: Long) = Unit
    override fun seekBy(deltaMs: Long) = Unit
    override fun frameStep(forward: Boolean) = Unit
    override fun setSpeed(speed: Float) = Unit
    override fun setVolume(percent: Int) = Unit
    override fun setMuted(muted: Boolean) = Unit
    override fun selectTrack(type: TrackType, id: Int?) = Unit
    override fun setScale(scale: com.tmplayer.player.VideoScale) = Unit
    override fun setDownmix(stereo: Boolean) = Unit
    override fun setVolumeBoost(on: Boolean) = Unit
    override fun setSubtitleStyle(style: com.tmplayer.player.SubtitleStyle) = Unit
    override fun setSubtitleDelay(ms: Long) = Unit
    override fun setAudioDelay(ms: Long) = Unit
    override fun addSubtitle(path: String): Boolean = false
    override suspend fun screenshot(file: java.io.File, withSubtitles: Boolean): Boolean = false
    override fun setAbLoop(loop: AbLoop?) = Unit
    override fun details(): List<Pair<String, String>> = emptyList()
    override fun close() = Unit
}
