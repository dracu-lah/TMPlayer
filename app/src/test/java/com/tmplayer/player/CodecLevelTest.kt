package com.tmplayer.player

import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CodecLevelTest {

    private fun video(mime: String, codecs: String, width: Int, height: Int): Format =
        Format.Builder().setSampleMimeType(mime).setCodecs(codecs).setWidth(width).setHeight(height).build()

    @Test
    fun hevcClaimingLevel51At1080pIsAskedAt41() {
        val relaxed = CodecLevel.relaxed(video(MimeTypes.VIDEO_H265, "hvc1.2.4.L153.90", 1920, 1080))
        assertEquals("hvc1.2.4.L123.90", relaxed?.codecs)
    }

    @Test
    fun hevcHighTierBecomesMainTier() {
        assertEquals("hev1.1.6.L123.B0", CodecLevel.hevc("hev1.1.6.H150.B0"))
    }

    @Test
    fun hevcAlreadyAt41IsLeftAlone() {
        assertNull(CodecLevel.hevc("hvc1.1.6.L120.90"))
        assertNull(CodecLevel.hevc("hvc1.1.6.L123.90"))
    }

    @Test
    fun avcClaimingLevel51At1080pIsAskedAt41() {
        val relaxed = CodecLevel.relaxed(video(MimeTypes.VIDEO_H264, "avc1.640033", 1920, 800))
        assertEquals("avc1.640029", relaxed?.codecs)
    }

    @Test
    fun largerThan1080pKeepsItsLevel() {
        assertNull(CodecLevel.relaxed(video(MimeTypes.VIDEO_H265, "hvc1.2.4.L153.90", 3840, 1606)))
        assertNull(CodecLevel.relaxed(video(MimeTypes.VIDEO_H265, "hvc1.2.4.L153.90", 2560, 1440)))
    }

    @Test
    fun otherCodecsAndUnreadableStringsAreLeftAlone() {
        assertNull(CodecLevel.relaxed(video(MimeTypes.VIDEO_DOLBY_VISION, "dvhe.08.06", 1920, 1080)))
        assertNull(CodecLevel.relaxed(video(MimeTypes.VIDEO_H265, "hvc1", 1920, 1080)))
        assertNull(CodecLevel.avc("avc1.4d40"))
    }

    @Test
    fun aPortraitPictureCountsByItsLongSide() {
        assertEquals("hvc1.2.4.L123.90", CodecLevel.relaxed(video(MimeTypes.VIDEO_H265, "hvc1.2.4.L153.90", 1080, 1920))?.codecs)
    }

    @Test
    fun rateFreeAsks4kAtLevel5WithoutItsFrameRate() {
        val asked = CodecLevel.rateFree(video(MimeTypes.VIDEO_H265, "hvc1.2.4.L153.90", 3840, 1604).buildUpon().setFrameRate(60f).build())
        assertEquals("hvc1.2.4.L150.90", asked?.codecs)
        assertEquals(androidx.media3.common.Format.NO_VALUE.toFloat(), asked?.frameRate)
        assertEquals(3840, asked?.width)
        // H.264 4K goes to 5.1, 1080p to 4.1, and a claim already under its target keeps its level.
        assertEquals("avc1.640033", CodecLevel.rateFree(video(MimeTypes.VIDEO_H264, "avc1.640034", 3840, 2160))?.codecs)
        assertEquals("avc1.640029", CodecLevel.rateFree(video(MimeTypes.VIDEO_H264, "avc1.640033", 1920, 1080))?.codecs)
        val low = CodecLevel.rateFree(video(MimeTypes.VIDEO_H265, "hvc1.2.4.L120.90", 1920, 1080).buildUpon().setFrameRate(120f).build())
        assertEquals("hvc1.2.4.L120.90", low?.codecs)
    }

    @Test
    fun rateFreeLeavesWhatItCannotForgive() {
        // Nothing to change: no frame rate and a level already fine.
        assertNull(CodecLevel.rateFree(video(MimeTypes.VIDEO_H265, "hvc1.2.4.L120.90", 1920, 1080)))
        // 8K, no size, and sound are never asked again.
        assertNull(CodecLevel.rateFree(video(MimeTypes.VIDEO_H265, "hvc1.2.4.L183.90", 7680, 4320).buildUpon().setFrameRate(30f).build()))
        assertNull(CodecLevel.rateFree(video(MimeTypes.VIDEO_H265, "hvc1.2.4.L153.90", 0, 0).buildUpon().setFrameRate(60f).build()))
        assertNull(CodecLevel.rateFree(video(MimeTypes.AUDIO_AAC, "mp4a.40.2", 3840, 2160)))
        // Another codec loses only its frame rate; an unreadable string is kept as it is.
        assertEquals("vp09.02.51.10", CodecLevel.rateFree(video(MimeTypes.VIDEO_VP9, "vp09.02.51.10", 3840, 2160).buildUpon().setFrameRate(60f).build())?.codecs)
        assertEquals("hvc1", CodecLevel.rateFree(video(MimeTypes.VIDEO_H265, "hvc1", 3840, 2160).buildUpon().setFrameRate(60f).build())?.codecs)
    }
}
