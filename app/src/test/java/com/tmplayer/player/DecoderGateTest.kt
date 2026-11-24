package com.tmplayer.player

import android.os.Handler
import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.Timeline
import androidx.media3.common.TrackGroup
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.RendererCapabilities
import androidx.media3.exoplayer.audio.AudioRendererEventListener
import androidx.media3.exoplayer.metadata.MetadataOutput
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.TrackGroupArray
import androidx.media3.exoplayer.text.TextOutput
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.exoplayer.trackselection.TrackSelectorResult
import androidx.media3.exoplayer.upstream.DefaultBandwidthMeter
import androidx.media3.exoplayer.video.VideoRendererEventListener
import io.github.anilbeesetti.nextlib.media3ext.ffdecoder.NextRenderersFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * "Try the other decoder" against Media3's real track selection (PB2-01): two video renderers and
 * two sound renderers that both take the format, as the device's decoder and FFmpeg's do, in the
 * order the player builds them. The switch must move the track's selection to the other renderer,
 * both ways, for the picture and for the sound. The old way (the selector's disabled renderers)
 * is kept as a case too: it leaves the track with no renderer at all, which played as sound over
 * a black picture.
 */
@UnstableApi
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DecoderGateTest {

    /** A renderer that takes every format of its type, as both decoders answer for a common file. */
    private class Takes(private val type: Int, private val name: String) : RendererCapabilities {
        override fun getName() = name
        override fun getTrackType() = type
        override fun supportsFormat(format: Format) = RendererCapabilities.create(
            if (MimeTypes.getTrackType(format.sampleMimeType) == type) C.FORMAT_HANDLED else C.FORMAT_UNSUPPORTED_TYPE,
        )
        override fun supportsMixedMimeTypeAdaptation() = RendererCapabilities.ADAPTIVE_NOT_SUPPORTED
    }

    private val context = RuntimeEnvironment.getApplication()
    private val gate = DecoderGate()

    /** The player's order: device video, FFmpeg video, device sound, FFmpeg sound. */
    private val gated: Array<RendererCapabilities> = arrayOf(
        GatedCapabilities(Takes(C.TRACK_TYPE_VIDEO, "MediaCodecVideoRenderer"), gate, ffmpeg = false),
        GatedCapabilities(Takes(C.TRACK_TYPE_VIDEO, "FfmpegVideoRenderer"), gate, ffmpeg = true),
        GatedCapabilities(Takes(C.TRACK_TYPE_AUDIO, "MediaCodecAudioRenderer"), gate, ffmpeg = false),
        GatedCapabilities(Takes(C.TRACK_TYPE_AUDIO, "FfmpegAudioRenderer"), gate, ffmpeg = true),
    )

    private val groups = TrackGroupArray(
        TrackGroup(Format.Builder().setSampleMimeType(MimeTypes.VIDEO_H265).setWidth(1920).setHeight(1080).build()),
        TrackGroup(Format.Builder().setSampleMimeType(MimeTypes.AUDIO_AAC).setChannelCount(2).setSampleRate(48_000).build()),
    )

    private fun select(capabilities: Array<RendererCapabilities>, selector: DefaultTrackSelector = DefaultTrackSelector(context)): TrackSelectorResult {
        selector.init({}, DefaultBandwidthMeter.getSingletonInstance(context))
        return selector.selectTracks(capabilities, groups, MediaSource.MediaPeriodId(Any()), Timeline.EMPTY)
    }

    /** The renderer index the track of [type] was selected on, or null when none took it. */
    private fun TrackSelectorResult.on(type: Int): Int? {
        val picked = (0 until length).filter { isRendererEnabled(it) && selections[it] != null && gated[it].trackType == type }
        return picked.singleOrNull()
    }

    @Test
    fun theFirstRendererTakesATieAsBefore() {
        val result = select(gated)
        assertEquals("device video", 0, result.on(C.TRACK_TYPE_VIDEO))
        assertEquals("device sound", 2, result.on(C.TRACK_TYPE_AUDIO))
    }

    @Test
    fun thePictureMovesToFfmpegAndBack() {
        gate.choose(C.TRACK_TYPE_VIDEO, toFfmpeg = true)
        val soft = select(gated)
        assertEquals("the picture is on FFmpeg's renderer", 1, soft.on(C.TRACK_TYPE_VIDEO))
        assertEquals("the sound stays where it was", 2, soft.on(C.TRACK_TYPE_AUDIO))
        gate.choose(C.TRACK_TYPE_VIDEO, toFfmpeg = false)
        assertEquals("back on the device's renderer", 0, select(gated).on(C.TRACK_TYPE_VIDEO))
    }

    @Test
    fun theSoundMovesToFfmpegAndBack() {
        gate.choose(C.TRACK_TYPE_AUDIO, toFfmpeg = true)
        val soft = select(gated)
        assertEquals("the sound is on FFmpeg's renderer", 3, soft.on(C.TRACK_TYPE_AUDIO))
        assertEquals("the picture stays where it was", 0, soft.on(C.TRACK_TYPE_VIDEO))
        gate.choose(C.TRACK_TYPE_AUDIO, toFfmpeg = false)
        assertEquals(2, select(gated).on(C.TRACK_TYPE_AUDIO))
    }

    @Test
    fun withFfmpegFirstTheSwitchGoesToTheDevice() {
        // Prefer software video: FFmpeg's renderer is ahead and takes the tie.
        val preferred = arrayOf(gated[1], gated[0], gated[2], gated[3])
        gate.choose(C.TRACK_TYPE_VIDEO, toFfmpeg = false)
        val result = DefaultTrackSelector(context).let { select(preferred, it) }
        val on = (0 until result.length).single { result.isRendererEnabled(it) && result.selections[it] != null && preferred[it].trackType == C.TRACK_TYPE_VIDEO }
        assertEquals("the device's renderer took the picture", "MediaCodecVideoRenderer", preferred[on].name)
    }

    @Test
    fun theSelectorsDisabledRendererLeavesTheTrackWithNoRenderer() {
        // Why the gate (PB2-01): Media3 maps the group to the first renderer that takes it, then
        // drops a disabled renderer's selection, so the picture plays on nothing.
        val plain: Array<RendererCapabilities> = arrayOf(
            Takes(C.TRACK_TYPE_VIDEO, "MediaCodecVideoRenderer"),
            Takes(C.TRACK_TYPE_VIDEO, "FfmpegVideoRenderer"),
            Takes(C.TRACK_TYPE_AUDIO, "MediaCodecAudioRenderer"),
            Takes(C.TRACK_TYPE_AUDIO, "FfmpegAudioRenderer"),
        )
        val selector = DefaultTrackSelector(context)
        selector.setParameters(selector.buildUponParameters().setRendererDisabled(0, true))
        val result = select(plain, selector)
        assertNull("no picture renderer", result.selections[1])
        assertNull(result.selections[0])
    }

    @Test
    fun applyReachesTheGateTheRenderersShare() {
        val factory = NextRenderersFactory(context)
        factory.setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
        val built = factory.createRenderers(
            Handler(Looper.getMainLooper()),
            object : VideoRendererEventListener {},
            object : AudioRendererEventListener {},
            TextOutput { },
            MetadataOutput { },
        ).toList()
        val shared = DecoderGate()
        val wrapped = built.map {
            if (it.trackType == C.TRACK_TYPE_VIDEO || it.trackType == C.TRACK_TYPE_AUDIO) GatedRenderer(it, shared) else it
        }
        val video = wrapped.filterIsInstance<GatedRenderer>().filter { it.trackType == C.TRACK_TYPE_VIDEO }
        assertTrue("both video sides are gated", video.any { it.ffmpeg } && video.any { !it.ffmpeg })
        assertTrue("FFmpeg is still told apart behind the gate", wrapped.filterIsInstance<GatedRenderer>().any { DecoderSwitch.isFfmpeg(it) })
        assertTrue(DecoderSwitch.apply(wrapped, DecoderSwitch.Plan(C.TRACK_TYPE_VIDEO, toFfmpeg = true)))
        assertFalse("the device's picture side is off", video.single { !it.ffmpeg }.open())
        assertTrue(video.single { it.ffmpeg }.open())
        assertTrue("the sound is untouched", wrapped.filterIsInstance<GatedRenderer>().filter { it.trackType == C.TRACK_TYPE_AUDIO }.all { it.open() })
        assertFalse("a player without gated renderers is not switched", DecoderSwitch.apply(built, DecoderSwitch.Plan(C.TRACK_TYPE_VIDEO, toFfmpeg = true)))
    }
}
