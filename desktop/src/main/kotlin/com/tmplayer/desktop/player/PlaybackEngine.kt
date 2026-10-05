package com.tmplayer.desktop.player

import com.tmplayer.player.SubtitleStyle
import com.tmplayer.player.SyncDelays
import com.tmplayer.player.VideoScale
import kotlinx.coroutines.flow.StateFlow
import org.openani.mediamp.source.MediaData

/**
 * The player as the overlay sees it (B1.3 of the 2026-10-03 plan).
 *
 * The plan puts this interface in `:ui`, with an Android actual over ExoPlayer; until `:ui`
 * exists it lives here, next to its only implementation, [MpvPlaybackEngine].
 */
interface PlaybackEngine {
    val state: StateFlow<PlaybackStatus>
    val tracks: StateFlow<List<MediaTrack>>

    /** Opens [data] and returns once the file is loaded, or with [PlaybackStatus.error] set. */
    suspend fun open(data: MediaData, startAtMs: Long, prefs: OpenPrefs = OpenPrefs())

    fun play()
    fun pause()
    fun togglePlay()
    fun seekTo(positionMs: Long)
    fun seekBy(deltaMs: Long)
    fun frameStep(forward: Boolean)
    fun setSpeed(speed: Float)
    fun setVolume(percent: Int)
    fun setMuted(muted: Boolean)

    /** [id] null turns the track type off (subtitles); audio always keeps one. */
    fun selectTrack(type: TrackType, id: Int?)
    fun setScale(scale: VideoScale)
    fun setDownmix(stereo: Boolean)

    /** Size, box and position for plain text subtitles; a file's own ASS styling is left alone. */
    fun setSubtitleStyle(style: SubtitleStyle)

    /** Subtitles moved against the picture; positive is later. See [SyncDelays]. */
    fun setSubtitleDelay(ms: Long)

    /** Sound moved against the picture; positive is later. */
    fun setAudioDelay(ms: Long)

    /** Loads a subtitle file from disk beside the video's own tracks and selects it. */
    fun addSubtitle(path: String): Boolean

    /** What is actually playing, as label and value pairs for the Playback details panel. */
    fun details(): List<Pair<String, String>>

    fun close()
}

/** What the next [PlaybackEngine.open] starts with, read from the settings beforehand. */
data class OpenPrefs(
    val audioLanguage: String? = null,
    val subtitleLanguage: String? = null,
    /** False turns subtitles off; true asks for them; null leaves the file's own defaults. */
    val subtitlesOn: Boolean? = null,
    val speed: Float = 1f,
    val scale: VideoScale = VideoScale.Fit,
    val downmix: Boolean = false,
    val volume: Int = 100,
    val muted: Boolean = false,
    /** mpv's `hwdec`: [HWDEC_AUTO] or [HWDEC_SOFTWARE]. */
    val hwdec: String = HWDEC_AUTO,
    val subtitleStyle: SubtitleStyle = SubtitleStyle(),
    /** This file's own offsets, or zero for a file with no message to remember them by. */
    val delays: SyncDelays = SyncDelays(),
) {
    companion object {
        /** Hardware where mpv considers it safe, software otherwise; mpv also falls back per stream. */
        const val HWDEC_AUTO = "auto-safe"
        const val HWDEC_SOFTWARE = "no"

        fun hwdecFor(softwareDecoding: Boolean) = if (softwareDecoding) HWDEC_SOFTWARE else HWDEC_AUTO
    }
}

data class PlaybackStatus(
    val opened: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    /** Where the demuxer's buffer ends, as a position in the video. */
    val bufferedMs: Long = 0,
    /** Whether the viewer wants it playing; the glyph follows this, not momentary stalls. */
    val playing: Boolean = false,
    /** Waiting for bytes: a stall mid play, or a seek that has not landed yet. */
    val buffering: Boolean = false,
    val seekPending: Boolean = false,
    val ended: Boolean = false,
    val error: String? = null,
    val videoWidth: Int = 0,
    val videoHeight: Int = 0,
    val volume: Int = 100,
    val muted: Boolean = false,
    val speed: Float = 1f,
    val scale: VideoScale = VideoScale.Fit,
    val downmix: Boolean = false,
    val subtitleStyle: SubtitleStyle = SubtitleStyle(),
    val subtitleDelayMs: Long = 0,
    val audioDelayMs: Long = 0,
) {
    val delays: SyncDelays get() = SyncDelays(subtitleDelayMs, audioDelayMs)
}

enum class TrackType { Audio, Subtitle, Video }

data class MediaTrack(
    val id: Int,
    val type: TrackType,
    val title: String?,
    val language: String?,
    val codec: String?,
    val channels: Int?,
    val selected: Boolean,
    val isDefault: Boolean,
    val external: Boolean,
) {
    /** "English (ac3 5.1)", "Signs", "Track 3". */
    val label: String
        get() {
            val name = listOfNotNull(
                title?.takeIf { it.isNotBlank() },
                language?.takeIf { it.isNotBlank() && it != "und" }?.let(::languageName),
            ).distinct().joinToString(" · ").ifBlank { "Track $id" }
            val extra = listOfNotNull(
                codec?.takeIf { it.isNotBlank() },
                channels?.takeIf { it > 0 }?.let(::channelLabel),
            ).joinToString(" ")
            return if (extra.isBlank()) name else "$name ($extra)"
        }

    private fun languageName(code: String): String =
        runCatching { java.util.Locale.forLanguageTag(code).getDisplayLanguage(java.util.Locale.ENGLISH) }
            .getOrNull()?.takeIf { it.isNotBlank() && !it.equals(code, ignoreCase = true) } ?: code

    private fun channelLabel(count: Int): String = when (count) {
        1 -> "mono"
        2 -> "stereo"
        6 -> "5.1"
        8 -> "7.1"
        else -> "${count}ch"
    }
}

/** The next track of [type] after the selected one, wrapping; for subtitles "off" is a stop. */
fun List<MediaTrack>.cycle(type: TrackType, forward: Boolean): MediaTrack? {
    val ofType = filter { it.type == type }
    if (ofType.isEmpty()) return null
    val stops: List<MediaTrack?> = if (type == TrackType.Subtitle) listOf(null) + ofType else ofType
    val at = stops.indexOfFirst { it?.selected ?: ofType.none { t -> t.selected } }.coerceAtLeast(0)
    val next = (at + if (forward) 1 else -1).mod(stops.size)
    return stops[next]
}
