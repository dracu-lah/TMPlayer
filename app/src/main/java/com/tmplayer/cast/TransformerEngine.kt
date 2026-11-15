package com.tmplayer.cast

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.Metadata
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.ChannelMixingAudioProcessor
import androidx.media3.common.audio.ChannelMixingMatrix
import androidx.media3.common.util.Clock
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSourceBitmapLoader
import androidx.media3.effect.FrameDropEffect
import androidx.media3.effect.Presentation
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.muxer.BufferInfo
import androidx.media3.muxer.FragmentedMp4Muxer
import androidx.media3.muxer.Muxer
import androidx.media3.transformer.AudioEncoderSettings
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultAssetLoaderFactory
import androidx.media3.transformer.DefaultDecoderFactory
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import com.google.common.collect.ImmutableList
import java.io.File
import java.io.FilterOutputStream
import java.io.IOException
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.channels.Channels
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The phone's [TranscodeEngine]: Media3 Transformer on its own looper thread. The video's bytes
 * come through [CastSourceDataSource] (Media3's extractors: Matroska, MP4, MPEG-TS, AVI), the
 * device's MediaCodec decoders decode them, GL scales the picture down, drops frames above the
 * target rate and tone maps HDR to SDR, the hardware H.264 and AAC encoders encode, and a live
 * [FragmentedMp4Muxer] writes the fragments straight onto the receiver's request: no file. A seek
 * is a new engine clipped from the new time ([MediaItem.ClippingConfiguration]); output time
 * starts at 0 there, as the rewrap's does.
 *
 * Nothing here is reached in the JVM tests: [EngineTranscoder] and [TranscodeSlot], which drive
 * it, are tested on a fake engine in core.
 */
@UnstableApi
class TransformerEngine(context: Context) : TranscodeEngine {

    private val app = context.applicationContext
    private val finished = AtomicBoolean(false)
    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var transformer: Transformer? = null
    private var onDone: ((Throwable?) -> Unit)? = null

    override fun start(job: TranscodeJob, out: OutputStream, made: (Long) -> Unit, done: (Throwable?) -> Unit) {
        onDone = done
        clearScratch(app)
        val t = HandlerThread("cast-transcode").apply { start() }
        thread = t
        val h = Handler(t.looper)
        handler = h
        h.post {
            try {
                val tr = build(job, out, made, t)
                transformer = tr
                tr.start(composition(job), scratchFile(app).path)
            } catch (e: Exception) {
                finish(e)
            }
        }
    }

    override fun cancel() {
        val h = handler ?: return finish(IOException("Cancelled"))
        h.post {
            runCatching { transformer?.cancel() }
            // Transformer says nothing to its listener on a cancel.
            finish(IOException("Cancelled"))
        }
    }

    private fun finish(error: Throwable?) {
        if (!finished.compareAndSet(false, true)) return
        transformer = null
        thread?.quitSafely()
        clearScratch(app)
        onDone?.invoke(error)
    }

    private fun build(job: TranscodeJob, out: OutputStream, made: (Long) -> Unit, t: HandlerThread): Transformer {
        val target = job.route.target
        val language = job.route.audioTrack?.let { n -> job.probe.audio.firstOrNull { it.number == n }?.language }
        val encoders = DefaultEncoderFactory.Builder(app)
            .setRequestedVideoEncoderSettings(
                VideoEncoderSettings.Builder()
                    .setBitrate(target.videoKbps.coerceAtLeast(300) * 1000)
                    // A keyframe every two seconds: each fragment starts on one.
                    .setiFrameIntervalSeconds(2f)
                    .build(),
            )
            .setRequestedAudioEncoderSettings(AudioEncoderSettings.Builder().setBitrate(target.audioKbps.coerceAtLeast(64) * 1000).build())
            .setEnableFallback(true)
            .build()
        val assets = DefaultAssetLoaderFactory(
            app,
            DefaultDecoderFactory.Builder(app).setEnableDecoderFallback(true).build(),
            Clock.DEFAULT,
            DefaultMediaSourceFactory(CastSourceDataSource.Factory(job.source)),
            DataSourceBitmapLoader(app),
        ) { context ->
            // The soundtrack the viewer chose, by its language: Transformer takes one per type.
            DefaultTrackSelector(context).apply {
                if (language != null) setParameters(buildUponParameters().setPreferredAudioLanguage(language))
            }
        }
        return Transformer.Builder(app)
            .setLooper(t.looper)
            .apply { if (!target.copyVideo) setVideoMimeType(MimeTypes.VIDEO_H264) }
            .apply { if (target.audio == TranscodeAudio.Aac) setAudioMimeType(MimeTypes.AUDIO_AAC) }
            .setEncoderFactory(encoders)
            .setAssetLoaderFactory(assets)
            .setMuxerFactory(LiveMuxerFactory(out, made))
            // The receiver pausing holds the muxer's writes; that is not a stuck export.
            .setMaxDelayBetweenMuxerSamplesMs(C.TIME_UNSET)
            .addListener(object : Transformer.Listener {
                override fun onCompleted(composition: Composition, exportResult: ExportResult) = finish(null)

                override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                    Log.w(TAG, "Transcode failed: ${exportException.errorCodeName}", exportException)
                    finish(IOException(exportException.errorCodeName + ": " + (exportException.message ?: ""), exportException))
                }
            })
            .build()
    }

    private fun composition(job: TranscodeJob): Composition {
        val target = job.route.target
        val item = MediaItem.Builder()
            .setUri(Uri.parse("tmplayer-cast://video/" + job.name.substringAfterLast('/').ifBlank { "video" }))
            .setClippingConfiguration(MediaItem.ClippingConfiguration.Builder().setStartPositionMs(job.startMs.coerceAtLeast(0)).build())
            .build()
        val videoEffects = if (target.copyVideo) emptyList() else listOf(
            Presentation.createForWidthAndHeight(target.width, target.height, Presentation.LAYOUT_SCALE_TO_FIT),
            FrameDropEffect.createDefaultFrameDropEffect(target.maxFps.toFloat()),
        )
        val audioProcessors: List<AudioProcessor> = if (target.audio == TranscodeAudio.Aac) listOf(stereo()) else emptyList()
        val edited = EditedMediaItem.Builder(item)
            .setRemoveAudio(target.audio == TranscodeAudio.None)
            .setEffects(Effects(audioProcessors, videoEffects))
            .build()
        return Composition.Builder(EditedMediaItemSequence.Builder(edited).build())
            .setTransmuxVideo(target.copyVideo)
            .setTransmuxAudio(target.audio == TranscodeAudio.Copy)
            .apply { if (target.toneMap) setHdrMode(Composition.HDR_MODE_TONE_MAP_HDR_TO_SDR_USING_OPEN_GL) }
            .build()
    }

    /** Every channel layout folded to stereo, as the receiver's AAC is: Media3 mixes by its matrices. */
    private fun stereo(): ChannelMixingAudioProcessor = ChannelMixingAudioProcessor().apply {
        // A layout Media3 has no matrix for goes through as it is (AAC carries 5.1 too).
        for (channels in 1..8) runCatching { putChannelMixingMatrix(ChannelMixingMatrix.createForConstantGain(channels, 2)) }
    }

    /**
     * The muxer Transformer writes through: Media3's fragmented MP4 writer on a channel over the
     * receiver's request, the output path Transformer passes left alone. It hears every sample's
     * time, so the screen knows how far the transcode has got.
     */
    private class LiveMuxerFactory(private val out: OutputStream, private val made: (Long) -> Unit) : Muxer.Factory {
        override fun create(path: String): Muxer {
            // Closing the muxer must not close the server's socket stream under it.
            val keep = object : FilterOutputStream(out) {
                override fun write(b: ByteArray, off: Int, len: Int) = out.write(b, off, len)
                override fun close() = out.flush()
            }
            val inner = FragmentedMp4Muxer.Builder(Channels.newChannel(keep)).setFragmentDurationMs(FRAGMENT_MS).build()
            return object : Muxer {
                private var latestUs = 0L
                override fun addTrack(format: Format): Int = inner.addTrack(format)
                override fun writeSampleData(trackId: Int, byteBuffer: ByteBuffer, bufferInfo: BufferInfo) {
                    inner.writeSampleData(trackId, byteBuffer, bufferInfo)
                    if (bufferInfo.presentationTimeUs > latestUs) {
                        latestUs = bufferInfo.presentationTimeUs
                        made(latestUs)
                    }
                }
                override fun addMetadataEntry(metadataEntry: Metadata.Entry) {
                    // An entry this muxer does not take (it checks) is left out, not a failed export.
                    runCatching { inner.addMetadataEntry(metadataEntry) }
                }
                override fun close() = inner.close()
            }
        }

        override fun getSupportedSampleMimeTypes(trackType: Int): ImmutableList<String> = when (trackType) {
            C.TRACK_TYPE_VIDEO -> FragmentedMp4Muxer.SUPPORTED_VIDEO_SAMPLE_MIME_TYPES
            C.TRACK_TYPE_AUDIO -> FragmentedMp4Muxer.SUPPORTED_AUDIO_SAMPLE_MIME_TYPES
            else -> ImmutableList.of()
        }
    }

    companion object {
        private const val TAG = "CastTranscode"
        private const val FRAGMENT_MS = 2_000L

        /** The folder Transformer is given a path in; the live muxer never writes there, and it is emptied anyway. */
        private fun scratchDir(context: Context) = File(context.cacheDir, "cast-transcode")

        private fun scratchFile(context: Context) = File(scratchDir(context).apply { mkdirs() }, "out.mp4")

        /** No partial file left behind: at every start and end, and once when the app opens a cast. */
        fun clearScratch(context: Context) {
            runCatching { scratchDir(context).listFiles()?.forEach { it.delete() } }
        }
    }
}
