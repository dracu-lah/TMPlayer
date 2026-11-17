package com.tmplayer.ios

import com.tmplayer.desktop.player.PlaybackEngine
import com.tmplayer.desktop.player.OpenPrefs
import com.tmplayer.desktop.player.TrackType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.openani.mediamp.source.UriMediaData

/** What an engine has reported, read back as plain values for the conformance test. */
class EngineSnapshot internal constructor(
    val opened: Boolean,
    val positionMs: Long,
    val durationMs: Long,
    val bufferedMs: Long,
    val playing: Boolean,
    val buffering: Boolean,
    val seekPending: Boolean,
    val ended: Boolean,
    /** Null while nothing went wrong. */
    val error: String?,
    val videoWidth: Int,
    val videoHeight: Int,
    val chapters: Int,
    val tracks: List<EngineTrack>,
)

/**
 * The conformance test's handle on an engine (iosApp/TMPlayerTests/EngineConformanceTests.swift):
 * the engine under upstream's PlaybackEngine through the same adapter the player uses, so the test
 * drives it as the player does and reads back what it reported.
 */
class EngineCheck(private val engine: VideoEngine) {
    private val native = NativePlaybackEngine(engine)
    private val playback: PlaybackEngine = native
    private val scope = MainScope()

    fun open(source: String, startAtMs: Long) {
        scope.launch {
            try {
                playback.open(UriMediaData(source), startAtMs, OpenPrefs())
            } catch (e: CancellationException) {
                throw e
            } catch (_: Throwable) {
                // The state carries the error; the test reads it there.
            }
        }
    }

    fun snapshot(): EngineSnapshot {
        val status = playback.state.value
        return EngineSnapshot(
            opened = status.opened,
            positionMs = status.positionMs,
            durationMs = status.durationMs,
            bufferedMs = status.bufferedMs,
            playing = status.playing,
            buffering = status.buffering,
            seekPending = status.seekPending,
            ended = status.ended,
            error = status.error,
            videoWidth = status.videoWidth,
            videoHeight = status.videoHeight,
            chapters = status.chapters.size,
            tracks = playback.tracks.value.map {
                EngineTrack(it.id, it.type, it.title, it.language, it.codec, it.channels ?: 0, it.selected, it.isDefault, it.external, it.forced)
            },
        )
    }

    fun play() = playback.play()

    fun pause() = playback.pause()

    fun seekTo(positionMs: Long) = playback.seekTo(positionMs)

    fun seekToExact(positionMs: Long) = playback.seekToExact(positionMs)

    fun seekBy(deltaMs: Long) = playback.seekBy(deltaMs)

    fun setSpeed(speed: Float) = playback.setSpeed(speed)

    fun setVolume(percent: Int) = playback.setVolume(percent)

    fun setMuted(muted: Boolean) = playback.setMuted(muted)

    /** [id] [EngineValues.NO_TRACK] turns the type off. */
    fun selectTrack(type: TrackType, id: Int) = playback.selectTrack(type, id.takeIf { it >= 0 })

    fun setSubtitleDelay(ms: Long) = playback.setSubtitleDelay(ms)

    fun setAudioDelay(ms: Long) = playback.setAudioDelay(ms)

    fun addSubtitle(path: String): Boolean = playback.addSubtitle(path)

    fun screenshot(path: String, done: (Boolean) -> Unit) {
        scope.launch { done(playback.screenshot(java.io.File(path), withSubtitles = true)) }
    }

    /** The Playback details panel's lines, as "label: value". */
    fun details(): List<String> = playback.details().map { "${it.first}: ${it.second}" }

    /** The tracks exactly as the engine last reported them, the player's own picks left out. */
    fun reportedTracks(): List<EngineTrack> = native.reportedTracks.value

    /** What the engine says is in force now. */
    fun facts(): EngineFacts = engine.facts()

    fun close() {
        playback.close()
        scope.cancel()
    }
}
