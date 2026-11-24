package com.tmplayer.desktop.player

import com.tmplayer.data.FormatFacts
import com.tmplayer.i18n.L
import com.tmplayer.player.Stereo3d
import com.tmplayer.player.SubtitlePlan
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

    /**
     * Names the video the next [open] plays ([com.tmplayer.data.MediaItem.id]), so an engine that
     * can read its tracks stores what the file declares for the badges ([com.tmplayer.data.FormatProbes]).
     * Nothing for an engine that cannot.
     */
    fun probeFor(id: String) {}

    /** Opens [data] and returns once the file is loaded, or with [PlaybackStatus.error] set. */
    suspend fun open(data: MediaData, startAtMs: Long, prefs: OpenPrefs = OpenPrefs())

    fun play()
    fun pause()

    /**
     * Stops playback and lets go of the stream, so nothing more is fetched for it; the state keeps
     * the position it stopped at. [open] again to carry on. "Still watching?" uses it: a paused
     * player keeps reading ahead, and TDLib keeps downloading for it.
     */
    fun stop()
    fun togglePlay()
    /**
     * A seek the viewer asked for: the bar, the skips, a chapter, the skip pill. It may land on
     * the key frame at or before [positionMs], which starts the picture sooner than decoding up
     * to the exact moment ([MpvPlaybackEngine]); [seekBy] lands on one on the side it moves to.
     */
    fun seekTo(positionMs: Long)
    fun seekBy(deltaMs: Long)

    /** A seek to exactly [positionMs], for a place that must not move: a retry in place, Undo. */
    fun seekToExact(positionMs: Long) = seekTo(positionMs)
    fun frameStep(forward: Boolean)
    fun setSpeed(speed: Float)
    fun setVolume(percent: Int)
    fun setMuted(muted: Boolean)

    /** [id] null turns the track type off (subtitles); audio always keeps one. */
    fun selectTrack(type: TrackType, id: Int?)
    fun setScale(scale: VideoScale)

    /**
     * The free zoom over the shape ([com.tmplayer.player.PictureZoom]): mpv's `video-zoom`, a
     * power of two, and `video-pan-x`/`-y` in the enlarged picture's size. 0, 0, 0 is none. An
     * engine with no picture of its own (a cast) ignores it.
     */
    fun setZoom(zoom: Double, panX: Double, panY: Double) {}

    /**
     * Rotate picture (#34): the picture turned clockwise by [degrees] (one of
     * [com.tmplayer.player.PictureRotation.CHOICES]) as mpv draws it, on top of the file's own
     * rotation, fitted with its shape kept. Back to none for each file opened. An engine with no
     * picture of its own (a cast) ignores it.
     */
    fun setPictureRotation(degrees: Int) {}
    fun setDownmix(stereo: Boolean)

    /**
     * The menu's software decoding (WP-25, PO-09): mpv's `hwdec` set while the video plays, which
     * mpv applies to the decoder in place, at the same position and tracks. An engine with no
     * decoder of its own (a cast) ignores it.
     */
    fun setSoftwareDecoding(on: Boolean) {}

    /**
     * What the decoder saw at a Report stutter press (WP-25), as [com.tmplayer.data.StutterReport]
     * facts, or null where there is no picture decoded here (a cast, nothing open).
     */
    fun stutterFacts(appVersion: String): com.tmplayer.data.StutterReport.Facts? = null

    /**
     * The menu's Volume boost: the whole volume scale lifted, so full volume is mpv's
     * [BOOSTED_VOLUME_MAX] rather than 100. The volume the viewer sees and sets stays 0 to 100.
     */
    fun setVolumeBoost(on: Boolean)

    /**
     * Night mode for sound: the loud and the quiet brought closer, on the sound already playing.
     * See [MpvPlaybackEngine.setNightMode] for what the bundled FFmpeg allows.
     */
    fun setNightMode(on: Boolean) {}

    /**
     * 3D video: a 3D file's two eyes drawn as one anaglyph, or one eye alone ([Stereo3d]). It
     * lasts for this player, across the files opened in it, until set again.
     */
    fun setStereo3d(stereo: Stereo3d) {}

    /** Size, box and position for plain text subtitles; a file's own ASS styling is left alone. */
    fun setSubtitleStyle(style: SubtitleStyle)

    /** Subtitles moved against the picture; positive is later. See [SyncDelays]. */
    fun setSubtitleDelay(ms: Long)

    /** Sound moved against the picture; positive is later. */
    fun setAudioDelay(ms: Long)

    /** Loads a subtitle file from disk beside the video's own tracks and selects it. */
    fun addSubtitle(path: String): Boolean

    /**
     * A subtitle file that grows (SubMaker's translation): the same [path] again reloads the track
     * loaded from it in place, with no new entry in the list and no gap in the picture; another
     * path loads like [addSubtitle].
     */
    fun replaceSubtitle(path: String): Boolean = addSubtitle(path)

    /**
     * Writes the frame on screen to [file], a PNG at the size it is drawn, with the subtitles as
     * drawn when [withSubtitles] or the bare picture otherwise. True once the file is there.
     */
    suspend fun screenshot(file: java.io.File, withSubtitles: Boolean): Boolean

    /** Repeats from [loop]'s start once playback reaches its end; null plays straight through again. */
    fun setAbLoop(loop: AbLoop?)

    /** What is actually playing, as label and value pairs for the Playback details panel. */
    fun details(): List<Pair<String, String>>

    /**
     * A picture of the video at [positionMs] for the timebar's hover, [width] by [height] at most,
     * from a second decoder that reads only what is on the disk; null where there is none (that
     * part not here yet, or an engine without one).
     */
    suspend fun previewFrame(positionMs: Long, width: Int, height: Int): androidx.compose.ui.graphics.ImageBitmap? = null

    fun close()
}

/** What the next [PlaybackEngine.open] starts with, read from the settings beforehand. */
data class OpenPrefs(
    /** The soundtrack's language, from [com.tmplayer.player.TrackRules.plan]; null for the file's. */
    val audioLanguage: String? = null,
    /** The soundtrack languages after [audioLanguage], in order (Settings' ordered list); first match wins. */
    val audioMore: List<String> = emptyList(),
    /** What to do about subtitles, decided the same way. */
    val subtitles: SubtitlePlan = SubtitlePlan.FileDefault,
    val speed: Float = 1f,
    val scale: VideoScale = VideoScale.Fit,
    val downmix: Boolean = false,
    val volume: Int = 100,
    val muted: Boolean = false,
    val volumeBoost: Boolean = false,
    val nightMode: Boolean = false,
    /** mpv's `hwdec`: [HWDEC_AUTO] or [HWDEC_SOFTWARE]. */
    val hwdec: String = HWDEC_AUTO,
    val subtitleStyle: SubtitleStyle = SubtitleStyle(),
    /** This file's own offsets, or zero for a file with no message to remember them by. */
    val delays: SyncDelays = SyncDelays(),
    /** The exact tracks a resumed video was playing with, put back over the languages above. */
    val resume: com.tmplayer.data.ResumeState? = null,
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
    /** With [error]: the failed format in one plain line, and the block "Copy details" adds ([MpvFormats]); empty when no format was known. */
    val failedFormat: String = "",
    val failedDetails: String = "",
    val videoWidth: Int = 0,
    val videoHeight: Int = 0,
    val volume: Int = 100,
    val muted: Boolean = false,
    val speed: Float = 1f,
    val scale: VideoScale = VideoScale.Fit,
    val downmix: Boolean = false,
    val volumeBoost: Boolean = false,
    val nightMode: Boolean = false,
    /** Decoding in software now (mpv `hwdec=no`), from the setting or the menu's switch (WP-25). */
    val softwareDecoding: Boolean = false,
    /** The picture is decoded here (mpv), not on a cast's TV: the decoder lines of the menu need it (WP-25). */
    val decodedHere: Boolean = false,
    val subtitleStyle: SubtitleStyle = SubtitleStyle(),
    val subtitleDelayMs: Long = 0,
    val audioDelayMs: Long = 0,
    /** The file's chapters in order, empty for a file without any. */
    val chapters: List<Chapter> = emptyList(),
    /** 3D video as chosen, kept across the files this player opens ([PlaybackEngine.setStereo3d]). */
    val stereo: Stereo3d = Stereo3d.OFF,
    /** Rotate picture, clockwise degrees ([PlaybackEngine.setPictureRotation]); 0 for each file opened. */
    val pictureRotation: Int = 0,
) {
    val delays: SyncDelays get() = SyncDelays(subtitleDelayMs, audioDelayMs)
}

/**
 * mpv's `volume-max` with Volume boost on, and where full volume lands. mpv's volume curve is
 * cubic, so 150 is about +10.6 dB, close to the Android boost's +9 dB.
 */
const val BOOSTED_VOLUME_MAX = 150

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
    /** Flagged forced: the few lines a film subtitles on purpose, signs and foreign dialogue. */
    val forced: Boolean = false,
    /** The codec's profile in words where the engine gives one ("Dolby TrueHD + Dolby Atmos"), for the badges. */
    val profile: String? = null,
) {
    /** "English (ac3 5.1)", "English (Atmos 5.1)", "Signs", "Track 3". */
    val label: String
        get() {
            val name = listOfNotNull(
                title?.takeIf { it.isNotBlank() },
                language?.takeIf { it.isNotBlank() && it != "und" }?.let(::languageName),
            ).distinct().joinToString(" · ").ifBlank { L.playerTrackNumber(id.toString()) }
            // A sound track says its kind and layout as the badges do (Atmos, DTS-HD, 5.1); a codec
            // or count with no such name keeps its plain word.
            val tags = if (type == TrackType.Audio) FormatFacts.audioTags(FormatFacts.AudioTrack("audio/${codec.orEmpty()}", null, profile, channels ?: 0)) else emptyList()
            val extra = listOfNotNull(
                tags.firstOrNull { it.kind == FormatFacts.Kind.Audio }?.text ?: codec?.takeIf { it.isNotBlank() },
                tags.firstOrNull { it.kind == FormatFacts.Kind.Channels && it.text != FormatFacts.STEREO }?.text ?: channels?.takeIf { it > 0 }?.let(::channelLabel),
            ).joinToString(" ")
            return if (extra.isBlank()) name else "$name ($extra)"
        }

    private fun languageName(code: String): String =
        L.messages.formatter.trackLanguage(code) ?: code

    private fun channelLabel(count: Int): String = when (count) {
        1 -> L.playerChannelsMono
        2 -> L.playerChannelsStereo
        6 -> "5.1"
        8 -> "7.1"
        else -> L.playerChannelsCount(count.toString())
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
