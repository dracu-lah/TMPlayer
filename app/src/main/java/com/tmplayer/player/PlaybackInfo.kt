package com.tmplayer.player

/**
 * The words on the playback details panel.
 *
 * Pure and free of both Android and Media3, so every line can be tested as a string rather than
 * discovered on a sofa. The activity reads the raw figures off the player, its download meter and
 * its sleep timer, because only the activity holds any of those; this object decides nothing about
 * playback and only how the figures read.
 *
 * Unknowns are skipped rather than printed as zeroes: a remux with no declared frame rate gets a
 * shorter video line, not "0 fps". The one deliberate exception is subtitles, where "Off" is an
 * answer and not an unknown.
 */
object PlaybackInfo {

    /**
     * One line per topic, joined with newlines, in the order a viewer asks the questions: what is
     * playing, what it sounds like, what is over it, how it is being played, and how the download
     * behind it is doing.
     */
    fun render(
        videoWidth: Int = 0,
        videoHeight: Int = 0,
        frameRate: Float = 0f,
        videoMimeType: String? = null,
        videoCodecs: String? = null,
        audioMimeType: String? = null,
        audioCodecs: String? = null,
        audioChannels: Int = 0,
        audioSampleRate: Int = 0,
        audioLanguage: String? = null,
        subtitlesOn: Boolean = false,
        subtitleLanguage: String? = null,
        speedLabel: String = "",
        fitLabel: String = "",
        bufferedAheadMs: Long = -1L,
        sizeBytes: Long = 0L,
        downloadedFraction: Float = 0f,
        downloadComplete: Boolean = false,
        downloadSpeedBytesPerSec: Long = 0L,
        sleepRemainingMs: Long? = null,
    ): String {
        val lines = mutableListOf<String>()

        val video = mutableListOf<String>()
        if (videoWidth > 0 && videoHeight > 0) video += "${videoWidth}x$videoHeight"
        if (frameRate > 0f) video += "${formatFps(frameRate)} fps"
        codecName(videoMimeType, videoCodecs)?.let { video += it }
        if (video.isNotEmpty()) lines += "Video: " + video.joinToString(", ")

        val audio = mutableListOf<String>()
        codecName(audioMimeType, audioCodecs)?.let { audio += it }
        channelsName(audioChannels)?.let { audio += it }
        if (audioSampleRate > 0) audio += formatSampleRate(audioSampleRate)
        audioLanguage?.takeIf { it.isNotBlank() }?.let { audio += it }
        if (audio.isNotEmpty()) lines += "Audio: " + audio.joinToString(", ")

        lines += "Subtitles: " + when {
            !subtitlesOn -> "Off"
            subtitleLanguage.isNullOrBlank() -> "On"
            else -> subtitleLanguage
        }

        val playback = listOfNotNull(
            speedLabel.takeIf { it.isNotBlank() },
            fitLabel.takeIf { it.isNotBlank() },
        )
        if (playback.isNotEmpty()) lines += "Playback: " + playback.joinToString(", ")

        if (bufferedAheadMs >= 0) lines += "Buffer: ${bufferedAheadMs / 1000} s ahead"

        val file = mutableListOf<String>()
        if (sizeBytes > 0) file += StreamStats.formatBytes(sizeBytes)
        if (downloadComplete) {
            file += "fully downloaded"
        } else if (downloadedFraction > 0f) {
            file += "${StreamStats.formatPercent(downloadedFraction)} downloaded"
            if (downloadSpeedBytesPerSec >= StreamStats.MIN_MEANINGFUL_SPEED) {
                file += StreamStats.formatSpeed(downloadSpeedBytesPerSec)
            }
        }
        if (file.isNotEmpty()) lines += "File: " + file.joinToString(", ")

        sleepRemainingMs?.let { remaining ->
            lines += if (remaining < 60_000) {
                "Sleep timer: under a minute"
            } else {
                // Rounded up, because "15 min" a moment after arming must still say 15: a timer
                // that reads one minute short of what was just asked for looks broken.
                "Sleep timer: ${(remaining + 59_999) / 60_000} min left"
            }
        }

        return lines.joinToString("\n")
    }

    /**
     * The short name a person knows a codec by, or null when nothing was declared.
     *
     * The MIME type is Media3's canonical answer and is matched first; the codecs string is the
     * container's own ("hvc1.2.4.L120", "avc1.640028", "mp4a.40.2") and rescues the files that
     * declare only that. Anything unrecognised falls back to the tail of the MIME type in
     * capitals, which at least names the thing rather than hiding it.
     */
    fun codecName(mimeType: String?, codecs: String? = null): String? {
        val mime = mimeType?.lowercase().orEmpty()
        val codec = codecs?.lowercase().orEmpty()
        return when {
            "hevc" in mime || codec.startsWith("hvc1") || codec.startsWith("hev1") -> "HEVC"
            "avc" in mime || codec.startsWith("avc") -> "H.264"
            "av01" in mime || codec.startsWith("av01") -> "AV1"
            "vp9" in mime || codec.startsWith("vp09") -> "VP9"
            "vp8" in mime || codec.startsWith("vp08") -> "VP8"
            mime == "video/dolby-vision" -> "Dolby Vision"
            mime == "video/mpeg2" -> "MPEG-2"
            mime == "video/mp4v-es" -> "MPEG-4"
            mime == "audio/mp4a-latm" || codec.startsWith("mp4a") -> "AAC"
            mime == "audio/eac3" || mime == "audio/eac3-joc" -> "E-AC-3"
            mime == "audio/ac3" -> "AC-3"
            mime == "audio/ac4" -> "AC-4"
            mime == "audio/true-hd" -> "TrueHD"
            mime == "audio/vnd.dts.hd" -> "DTS-HD"
            mime.startsWith("audio/vnd.dts") -> "DTS"
            mime == "audio/opus" -> "Opus"
            mime == "audio/flac" -> "FLAC"
            mime == "audio/mpeg" -> "MP3"
            mime == "audio/raw" -> "PCM"
            mime == "audio/vorbis" -> "Vorbis"
            '/' in mime -> mime.substringAfter('/').uppercase()
            mime.isNotBlank() -> mime.uppercase()
            else -> null
        }
    }

    /** "23.98", "29.97", "25": two decimals where the rate needs them, none where it does not. */
    private fun formatFps(rate: Float): String {
        val hundredths = Math.round(rate * 100)
        return if (hundredths % 100 == 0) {
            (hundredths / 100).toString()
        } else {
            String.format("%.2f", hundredths / 100.0).trimEnd('0').trimEnd('.')
        }
    }

    /** "stereo" and "5.1" over channel counts, because nobody says "6 channels" of a film mix. */
    private fun channelsName(count: Int): String? = when {
        count <= 0 -> null
        count == 1 -> "mono"
        count == 2 -> "stereo"
        count == 6 -> "5.1"
        count == 8 -> "7.1"
        else -> "$count channels"
    }

    private fun formatSampleRate(hz: Int): String =
        if (hz % 1000 == 0) "${hz / 1000} kHz" else String.format("%.1f kHz", hz / 1000.0)
}
