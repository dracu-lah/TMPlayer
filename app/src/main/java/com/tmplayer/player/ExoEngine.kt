package com.tmplayer.player

import android.view.SurfaceView
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import com.tmplayer.data.PlayerEngine

/**
 * ExoPlayer as an [Engine] (PR2). It wraps the player [PlayerActivity] builds, with every
 * renderer, the load control, the FFmpeg decoders and the subtitle files exactly as before; the
 * activity keeps building and driving it as it always has, and this adapter is what the engine
 * switch, the contract test and the playback details see. Nothing here changes how ExoPlayer plays.
 *
 * Takes any Media3 [Player], so the contract test can run it over a stand-in; the ExoPlayer only
 * parts (the decoder's name, dropped frames) read as unknown on anything else.
 */
@OptIn(UnstableApi::class)
class ExoEngine(override val player: Player) : Engine {

    override val kind: PlayerEngine get() = PlayerEngine.ExoPlayer

    private var released = false

    override fun prepare(source: EngineSource, startMs: Long) {
        player.setMediaItem(MediaItem.fromUri(source.address))
        if (startMs > 0) player.seekTo(startMs)
        player.prepare()
    }

    override fun play() = player.play()

    override fun pause() = player.pause()

    override fun seekTo(positionMs: Long) = player.seekTo(positionMs.coerceAtLeast(0L))

    override val positionMs: Long get() = player.currentPosition.coerceAtLeast(0L)

    override val durationMs: Long get() = player.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: 0L

    override val isPlaying: Boolean get() = player.isPlaying

    override fun tracks(): List<EngineTrack> {
        val groups = player.currentTracks.groups
        return buildList {
            groups.forEachIndexed { g, group ->
                val type = when (group.type) {
                    C.TRACK_TYPE_AUDIO -> EngineTrack.Type.Audio
                    C.TRACK_TYPE_TEXT -> EngineTrack.Type.Subtitle
                    else -> return@forEachIndexed
                }
                for (t in 0 until group.length) {
                    val format = group.getTrackFormat(t)
                    add(
                        EngineTrack(
                            id = "$g:$t",
                            type = type,
                            language = format.language,
                            label = format.label ?: format.language ?: "${t + 1}",
                            selected = group.isTrackSelected(t),
                        ),
                    )
                }
            }
        }
    }

    override fun selectAudio(id: String) = select(id, C.TRACK_TYPE_AUDIO)

    override fun selectSubtitle(id: String?) {
        if (id == null) {
            player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                .build()
            return
        }
        select(id, C.TRACK_TYPE_TEXT)
    }

    private fun select(id: String, type: Int) {
        val g = id.substringBefore(':').toIntOrNull() ?: return
        val t = id.substringAfter(':', "").toIntOrNull() ?: return
        val group = player.currentTracks.groups.getOrNull(g)?.takeIf { it.type == type } ?: return
        if (t !in 0 until group.length) return
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(type, false)
            .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, t))
            .build()
    }

    /** ExoPlayer takes subtitle files on the media item, which [SubtitleFiles] builds; nothing to do here. */
    override fun addSubtitleFile(uri: String) = Unit

    override var speed: Float
        get() = player.playbackParameters.speed
        set(value) {
            player.setPlaybackSpeed(PlaybackSpeed.sanitise(value))
        }

    override var volume: Float
        get() = player.volume
        set(value) {
            player.volume = value.coerceIn(0f, 1f)
        }

    /** The frame layout gives the surface its shape and ExoPlayer fills it: nothing to do. */
    override fun setAspect(widthOverHeight: Double?) = Unit

    override fun attachSurface(view: SurfaceView?) {
        if (view == null) player.clearVideoSurface() else player.setVideoSurfaceView(view)
    }

    override fun setVideoEnabled(enabled: Boolean) {
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, !enabled)
            .build()
    }

    override fun stats(): EngineStats {
        val exo = player as? ExoPlayer
        val format = runCatching { exo?.videoFormat }.getOrNull()
        val size = player.videoSize
        return EngineStats(
            engine = kind,
            decoder = runCatching { exo?.videoDecoderCounters }.getOrNull()?.let { format?.codecs ?: format?.sampleMimeType },
            width = size.width,
            height = size.height,
            droppedFrames = runCatching { exo?.videoDecoderCounters?.droppedBufferCount }.getOrNull() ?: 0,
            bufferedMs = (player.bufferedPosition - player.currentPosition).coerceAtLeast(0L),
        )
    }

    override fun release() {
        if (released) return
        released = true
        runCatching { player.stop() }
        runCatching { player.release() }
    }
}
