package com.tmplayer.player

import android.media.MediaCodecInfo
import android.media.MediaFormat
import androidx.media3.common.util.UnstableApi
import com.tmplayer.data.LogBundle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.MediaCodecInfoBuilder
import org.robolectric.shadows.ShadowMediaCodecList

/**
 * RL12: what a decoder claims and what it outputs, read from a fake codec list and a made-up
 * output format, and that every fact it gives is one [TraceFacts] keeps (so none is lost on the way
 * into a Send logs bundle).
 */
@UnstableApi
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DecoderFactsTest {

    private fun hevcDecoder(name: String, software: Boolean = false): MediaCodecInfo {
        val caps = MediaCodecInfoBuilder.CodecCapabilitiesBuilder.newBuilder()
            .setMediaFormat(MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_HEVC, 1920, 1080))
            .setColorFormats(intArrayOf(MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface))
            .setIsEncoder(false)
            .setProfileLevels(
                arrayOf(
                    MediaCodecInfo.CodecProfileLevel().apply {
                        profile = MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10
                        level = MediaCodecInfo.CodecProfileLevel.HEVCMainTierLevel41
                    },
                ),
            )
            .build()
        return MediaCodecInfoBuilder.newBuilder()
            .setName(name)
            .setIsEncoder(false)
            .setIsSoftwareOnly(software)
            .setIsHardwareAccelerated(!software)
            .setIsVendor(!software)
            .setCapabilities(caps)
            .build()
    }

    @Test
    fun aDecodersClaimsAreListedWithItsHardwareFlags() {
        val info = hevcDecoder("OMX.amlogic.hevc.decoder.awesome2")
        val facts = DecoderFacts.claims(info, MediaFormat.MIMETYPE_VIDEO_HEVC, 1920, 1080, 24.0, sdk = 34)
        assertEquals("OMX.amlogic.hevc.decoder.awesome2", facts["dec.name"])
        assertEquals("true", facts["dec.hw"])
        assertEquals("false", facts["dec.sw"])
        assertEquals("true", facts["dec.vendor"])
        assertEquals("false", facts["dec.alias"])
        assertTrue(facts.getValue("dec.levels").isNotEmpty())
        assertTrue("the fit to this picture is answered", facts["dec.fits"] in setOf("true", "false"))
        assertTrue(facts.keys.all { it in TraceFacts.KEYS })
        assertEquals("nothing is lost cleaning it", facts, TraceFacts.clean(facts))
    }

    @Test
    fun aSoftwareDecoderSaysSoAndOlderAndroidLeavesOutWhatItLacks() {
        val software = hevcDecoder("c2.android.hevc.decoder", software = true)
        val facts = DecoderFacts.claims(software, MediaFormat.MIMETYPE_VIDEO_HEVC, 0, 0, 0.0, sdk = 34)
        assertEquals("false", facts["dec.hw"])
        assertEquals("true", facts["dec.sw"])
        assertFalse("no picture asked about, no fit", "dec.fits" in facts)
        val old = DecoderFacts.claims(hevcDecoder("OMX.vendor.hevc"), MediaFormat.MIMETYPE_VIDEO_HEVC, 1920, 1080, 30.0, sdk = 28)
        assertFalse("vendor and alias flags are Android 10's", "dec.vendor" in old || "dec.alias" in old || "dec.perf" in old)
        assertEquals("true", old["dec.hw"])
    }

    @Test
    fun theDeviceCodecListGivesTheSameFactsByName() {
        ShadowMediaCodecList.addCodec(hevcDecoder("OMX.test.hevc.decoder"))
        val facts = DecoderFacts.claims("OMX.test.hevc.decoder", MediaFormat.MIMETYPE_VIDEO_HEVC, 1280, 720, 30.0)
        assertEquals("OMX.test.hevc.decoder", facts["dec.name"])
        assertTrue("dec.hw" in facts)
        // A name the device does not list gives the name alone, never an error.
        assertEquals(mapOf("dec.name" to "nope"), DecoderFacts.claims("nope", "video/hevc", 1, 1, 1.0))
        assertNull(DecoderLister.infoOf("nope"))
    }

    @Test
    fun theOutputFormatsColoursAndCropAreRead() {
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_HEVC, 1920, 1088).apply {
            setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT2020)
            setInteger(MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.COLOR_TRANSFER_ST2084)
            setInteger(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_LIMITED)
            setInteger("crop-left", 0)
            setInteger("crop-top", 0)
            setInteger("crop-right", 1919)
            setInteger("crop-bottom", 1079)
            setInteger(MediaFormat.KEY_FRAME_RATE, 24)
            setInteger(MediaFormat.KEY_COLOR_FORMAT, 2130708361)
        }
        val facts = DecoderFacts.output(format)
        assertEquals(MediaFormat.COLOR_STANDARD_BT2020.toString(), facts["out.standard"])
        assertEquals(MediaFormat.COLOR_TRANSFER_ST2084.toString(), facts["out.transfer"])
        assertEquals(MediaFormat.COLOR_RANGE_LIMITED.toString(), facts["out.range"])
        assertEquals("1920x1088", facts["out.size"])
        assertEquals("0,0,1919,1079", facts["out.crop"])
        assertEquals("2130708361", facts["out.pixfmt"])
        assertEquals(facts, TraceFacts.clean(facts))
    }

    @Test
    fun anOutputFormatThatSaysLittleOrIsEmptyGivesWhatThereIs() {
        assertTrue(DecoderFacts.output(MediaFormat()).isEmpty())
        val partial = MediaFormat().apply { setInteger("crop-left", 0) }
        assertFalse("a crop of one side is no crop", "out.crop" in DecoderFacts.output(partial))
    }

    @Test
    fun theFactsReachTheBundleAsPlainLines() {
        val facts = DecoderFacts.claims(hevcDecoder("OMX.x.hevc"), MediaFormat.MIMETYPE_VIDEO_HEVC, 1920, 1080, 24.0, sdk = 34)
        val lines = TraceFacts.lines(facts, emptyList())
        assertTrue(lines.single().startsWith("  Decoder: dec.name=OMX.x.hevc, dec.hw=true"))
        // And they go through the bundle's scrubber and size cap like any other session line.
        val block = listOf("Session started now, 0 reading(s), verdict unknown") + lines
        val result = LogBundle.build(
            com.tmplayer.data.Feedback.Facts("1", com.tmplayer.data.Feedback.Platform.Phone, "d", "en"),
            LogBundle.Parts(sessions = listOf(block)),
        )
        assertTrue("dec.name=OMX.x.hevc" in result.text)
    }

    @Test
    fun theFormatAndTheLadderAreFactsToo() {
        val video = androidx.media3.common.Format.Builder()
            .setSampleMimeType("video/hevc").setCodecs("hvc1.2.4.L120.B0").setWidth(1920).setHeight(1080).setFrameRate(23.976f)
            .setAverageBitrate(8_000_000).build()
        val audio = androidx.media3.common.Format.Builder().setSampleMimeType("audio/eac3").setChannelCount(6).build()
        val format = SessionFacts.format(video, audio)
        assertEquals("1920x1080", format["fmt.size"])
        assertEquals("8000", format["fmt.bitrate"])
        assertEquals("audio/eac3", format["fmt.audio"])
        assertEquals("6", format["fmt.channels"])
        assertTrue(SessionFacts.format(null, null).isEmpty())
        val ladder = SessionFacts.ladder("amlogic1/28/hevc", listOf(Remedy.ExcludeDecoder, Remedy.Software), visited = 3, hops = 2)
        assertEquals(mapOf("class" to "amlogic1/28/hevc", "rung" to "ex,sw", "visited" to "3", "hops" to "2"), ladder)
        assertEquals("default", SessionFacts.ladder(null, emptyList(), 1, 0)["rung"])
        for (facts in listOf(format, ladder)) assertEquals(facts, TraceFacts.clean(facts))
    }

    @Test
    fun theSendLogsSessionBlockCarriesTheFactsAndTheHops() {
        val file = java.io.File.createTempFile("trace", ".jsonl")
        try {
            file.writeText(
                PlaybackTrace.encode(
                    listOf(
                        TraceSession(
                            1_763_200_000_000,
                            listOf(TraceSample(atMs = 1_763_200_005_000, intervalMs = 5_000, decoder = "c2.qti.hevc.decoder")),
                            mapOf("class" to "k1", "dev.sdk" to "34"),
                            listOf(TraceHop(1_763_200_003_000, "Frozen", "default", "rl", TraceHop.WORKED)),
                        ),
                        // A session an older app wrote: no facts, and the bundle still lists it.
                        TraceSession(1_763_300_000_000, listOf(TraceSample(atMs = 1_763_300_005_000, intervalMs = 5_000))),
                    ),
                ),
            )
            val blocks = com.tmplayer.data.LogReport.sessionBlocks(file)
            assertEquals(2, blocks.size)
            assertTrue(blocks[0].any { it.startsWith("  Ladder: class=k1") })
            assertTrue(blocks[0].any { it.startsWith("  Hop +0s Frozen: default -> rl, worked") })
            assertTrue(blocks[0].any { it.contains("decoder=c2.qti.hevc.decoder") })
            assertTrue(blocks[1].none { it.startsWith("  Ladder") || it.startsWith("  Hop") })
        } finally {
            file.delete()
        }
    }
}
