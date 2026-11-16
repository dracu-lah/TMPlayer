package com.tmplayer.player

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import com.tmplayer.data.FormatFacts

/**
 * What Media3's track list says about the picture and the sound, as [FormatFacts.Probe] for the
 * badges (T8). Reads only what the formats state: the MIME type and codecs string, the size, the
 * frame rate, the colour transfer and bit depth, the channel count. Nothing is guessed, and a
 * field the container left out stays empty.
 *
 * HDR10+ and Atmos on TrueHD are not stated by Media3's formats, so they are not reported here
 * (E-AC-3 JOC is: its MIME type names it).
 */
@UnstableApi
object Media3Probe {

    /** The probe of [tracks]: the selected picture (else the first), every sound track with the playing one first. */
    fun of(tracks: Tracks): FormatFacts.Probe {
        val video = pick(tracks, C.TRACK_TYPE_VIDEO)?.takeIf { it.sampleMimeType?.startsWith("image/") != true }
        val playing = pick(tracks, C.TRACK_TYPE_AUDIO)
        val sound = buildList {
            if (playing != null) add(playing)
            for (group in tracks.groups) {
                if (group.type != C.TRACK_TYPE_AUDIO) continue
                for (i in 0 until group.length) {
                    if (size >= MAX_AUDIO) return@buildList
                    val format = group.getTrackFormat(i)
                    if (format !== playing) add(format)
                }
            }
        }
        val info = video?.colorInfo
        return FormatFacts.Probe(
            videoMime = video?.sampleMimeType,
            videoCodecs = video?.codecs,
            width = video?.width?.coerceAtLeast(0) ?: 0,
            height = video?.height?.coerceAtLeast(0) ?: 0,
            frameRate = video?.frameRate?.takeIf { it > 0f } ?: 0f,
            transfer = when (info?.colorTransfer) {
                C.COLOR_TRANSFER_ST2084 -> FormatFacts.Transfer.Pq
                C.COLOR_TRANSFER_HLG -> FormatFacts.Transfer.Hlg
                C.COLOR_TRANSFER_SDR, C.COLOR_TRANSFER_GAMMA_2_2, C.COLOR_TRANSFER_SRGB -> FormatFacts.Transfer.Sdr
                else -> FormatFacts.Transfer.Unknown
            },
            bitDepth = info?.lumaBitdepth?.takeIf { it > 0 } ?: 0,
            audio = sound.map { audioOf(it) },
        )
    }

    /** One sound track as a badge reads it. */
    fun audioOf(format: Format): FormatFacts.AudioTrack =
        FormatFacts.AudioTrack(mime = format.sampleMimeType, codecs = format.codecs, channels = format.channelCount.coerceAtLeast(0))

    /** The format of the selected track of [type], else of the first one listed. */
    private fun pick(tracks: Tracks, type: Int): Format? {
        val groups = tracks.groups.filter { it.type == type && it.length > 0 }
        val group = groups.firstOrNull { it.isSelected } ?: groups.firstOrNull() ?: return null
        val index = (0 until group.length).firstOrNull { group.isTrackSelected(it) } ?: 0
        return group.getTrackFormat(index)
    }

    private const val MAX_AUDIO = 16
}
