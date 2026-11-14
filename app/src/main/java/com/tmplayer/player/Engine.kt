package com.tmplayer.player

import android.view.SurfaceView
import androidx.media3.common.Player
import com.tmplayer.data.PlayerEngine

/**
 * One playback engine behind the player's own overlay (PR2): ExoPlayer ([ExoEngine]) or libVLC
 * ([VlcEngine]). The overlay, the subtitles' look, the progress, the next-up card, the skip pill
 * and the television's focus stay the activity's; an engine only plays.
 *
 * [player] is the engine as a Media3 [Player], which is what the overlay, the track picker and the
 * media session already drive, so they work on either engine unchanged. The rest is what changing
 * engines in the middle of a video needs ([carry], [restore]) and what the status sheet and the
 * playback details show ([stats]).
 *
 * Only one engine is alive at a time: [release] lets go of everything (decoder, surface, the
 * native library's player, a loopback stream) before another is built, which a 1 GB stick needs.
 */
interface Engine {
    val kind: PlayerEngine

    /** The engine as Media3's [Player]: the overlay, the track picker and the media session use this. */
    val player: Player

    /** Opens [source] and starts loading at [startMs]; [play] decides whether it then plays. */
    fun prepare(source: EngineSource, startMs: Long = 0L)

    fun play()

    fun pause()

    fun seekTo(positionMs: Long)

    val positionMs: Long

    /** The length, or 0 while it is not known. */
    val durationMs: Long

    val isPlaying: Boolean

    /** The soundtracks and the subtitles the file has, as far as the engine knows them yet. */
    fun tracks(): List<EngineTrack>

    /** Plays the audio track [id] (an [EngineTrack.id] of type [EngineTrack.Type.Audio]). */
    fun selectAudio(id: String)

    /** Shows the subtitle track [id], or none for null. */
    fun selectSubtitle(id: String?)

    /** Adds a subtitle file (a path or a URI) beside the file's own tracks. */
    fun addSubtitleFile(uri: String)

    var speed: Float

    /** 0 (silent) to 1 (as the file is). Louder than the file is the volume boost's job. */
    var volume: Float

    /**
     * The shape the picture is drawn in, as width over height, or null for the video's own. The
     * activity's frame layout already gives the surface its shape; this is for an engine that
     * scales inside its surface by itself (VLC), so it fills the frame it was given.
     */
    fun setAspect(widthOverHeight: Double?)

    /** Draws onto [view]; null lets go of the surface (the picture is gone, the sound plays on). */
    fun attachSurface(view: SurfaceView?)

    /** False stops decoding the picture while nobody can see it (background audio), true brings it back. */
    fun setVideoEnabled(enabled: Boolean)

    fun stats(): EngineStats

    /** Stops and lets go of everything. Never throws, and a second call does nothing. */
    fun release()

    /** What to carry into another engine: the place, the track languages, the speed and the volume. */
    fun carry(): EngineCarry {
        val tracks = tracks()
        val audio = tracks.firstOrNull { it.type == EngineTrack.Type.Audio && it.selected }
        val subtitles = tracks.filter { it.type == EngineTrack.Type.Subtitle }
        val subtitle = subtitles.firstOrNull { it.selected }
        return EngineCarry(
            positionMs = positionMs,
            playWhenReady = player.playWhenReady,
            audioLanguage = audio?.language,
            subtitleLanguage = subtitle?.language,
            subtitlesOff = subtitles.isNotEmpty() && subtitle == null,
            speed = speed,
            volume = volume,
        )
    }

    /**
     * Puts back what [carry] took from the engine before: the speed and the volume at once, the
     * track languages once the file lists its tracks (a call with no tracks known yet keeps them
     * for the next call). True when nothing is left to restore.
     */
    fun restore(carry: EngineCarry): Boolean {
        speed = carry.speed
        volume = carry.volume
        val tracks = tracks()
        if (tracks.isEmpty()) return false
        EngineCarry.pick(tracks, EngineTrack.Type.Audio, carry.audioLanguage)?.let { selectAudio(it.id) }
        when {
            carry.subtitlesOff -> selectSubtitle(null)
            carry.subtitleLanguage != null ->
                EngineCarry.pick(tracks, EngineTrack.Type.Subtitle, carry.subtitleLanguage)?.let { selectSubtitle(it.id) }
        }
        return true
    }
}

/** Where an engine reads the video from. */
sealed interface EngineSource {
    /** A finished download in the app's own folder. */
    data class File(val path: String) : EngineSource

    /** A finished download somewhere the viewer picked (`content://`). */
    data class Content(val uri: String) : EngineSource

    /** A video still downloading, served on 127.0.0.1 ([com.tmplayer.cast.LoopbackStream]). */
    data class Stream(val url: String) : EngineSource

    /** What to hand an engine that takes a URI string. */
    val address: String
        get() = when (this) {
            is File -> if (path.startsWith("/")) "file://$path" else path
            is Content -> uri
            is Stream -> url
        }
}

/** One soundtrack or subtitle track, in the engine's own terms ([id]) and the viewer's ([language], [label]). */
data class EngineTrack(
    val id: String,
    val type: Type,
    val language: String?,
    val label: String,
    val selected: Boolean,
) {
    enum class Type { Audio, Subtitle }
}

/** What goes across when the engine changes in the middle of a video. */
data class EngineCarry(
    val positionMs: Long,
    val playWhenReady: Boolean = true,
    val audioLanguage: String? = null,
    val subtitleLanguage: String? = null,
    val subtitlesOff: Boolean = false,
    val speed: Float = 1f,
    val volume: Float = 1f,
) {
    companion object {
        /**
         * The track of [type] in [language], or null to keep the engine's own pick (no language
         * carried, or none of the tracks in it). ExoPlayer says `en` where VLC says `eng` and a
         * file may say `en-GB` or `fre`: [sameLanguage] reads them all as one.
         */
        fun pick(tracks: List<EngineTrack>, type: EngineTrack.Type, language: String?): EngineTrack? {
            if (language.isNullOrBlank()) return null
            return tracks.firstOrNull { it.type == type && sameLanguage(language, it.language) }
        }

        /** Two language tags for the same language: case, region, and two or three letter forms aside. */
        fun sameLanguage(a: String?, b: String?): Boolean {
            val left = canonical(a) ?: return false
            val right = canonical(b) ?: return false
            return left == right
        }

        private fun canonical(tag: String?): String? {
            val clean = tag?.trim()?.lowercase()?.takeIf { it.isNotEmpty() && it.length <= 40 } ?: return null
            // libVLC names a track's language in English ("English", "French") when it has no code.
            if (clean.length > 3 && clean.all { it.isLetter() || it == ' ' }) return byName(clean)
            val base = clean.split('-', '_').firstOrNull()?.takeIf { it.length in 2..3 && it.all(Char::isLetter) }
                ?: return null
            val three = if (base.length == 2) runCatching { java.util.Locale(base).isO3Language }.getOrNull()?.ifBlank { null } ?: base else base
            return BIBLIOGRAPHIC[three] ?: three
        }

        private fun byName(name: String): String? = runCatching {
            java.util.Locale.getISOLanguages().firstOrNull { code ->
                java.util.Locale(code).getDisplayLanguage(java.util.Locale.ENGLISH).equals(name, ignoreCase = true)
            }?.let { java.util.Locale(it).isO3Language }
        }.getOrNull()?.ifBlank { null }

        /** ISO 639-2 bibliographic codes, which Matroska files use, to the terminology ones Java returns. */
        private val BIBLIOGRAPHIC = mapOf(
            "alb" to "sqi", "arm" to "hye", "baq" to "eus", "bur" to "mya", "chi" to "zho", "cze" to "ces",
            "dut" to "nld", "fre" to "fra", "geo" to "kat", "ger" to "deu", "gre" to "ell", "ice" to "isl",
            "mac" to "mkd", "mao" to "mri", "may" to "msa", "per" to "fas", "rum" to "ron", "slo" to "slk",
            "tib" to "bod", "wel" to "cym",
        )
    }
}

/** What an engine says about itself for the playback details and the stutter report. */
data class EngineStats(
    val engine: PlayerEngine,
    /** The video decoder's name, when the engine says. */
    val decoder: String? = null,
    val width: Int = 0,
    val height: Int = 0,
    val droppedFrames: Int = 0,
    val bufferedMs: Long = 0L,
)
