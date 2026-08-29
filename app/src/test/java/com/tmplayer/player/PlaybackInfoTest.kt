package com.tmplayer.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlaybackInfoTest {

    @Test
    fun `a full panel reads one topic per line`() {
        val text = PlaybackInfo.render(
            videoWidth = 1920,
            videoHeight = 1080,
            frameRate = 23.976f,
            videoMimeType = "video/hevc",
            audioMimeType = "audio/eac3",
            audioChannels = 6,
            audioSampleRate = 48_000,
            audioLanguage = "English",
            subtitlesOn = true,
            subtitleLanguage = "English",
            speedLabel = "1.5x",
            fitLabel = "Fit",
            bufferedAheadMs = 12_400,
            sizeBytes = 1_470_000_000,
            downloadedFraction = 0.62f,
            downloadSpeedBytesPerSec = 2 * 1024 * 1024,
            sleepRemainingMs = 14 * 60_000L,
        )
        assertEquals(
            listOf(
                "Video: 1920x1080, 23.98 fps, HEVC",
                "Audio: E-AC-3, 5.1, 48 kHz, English",
                "Subtitles: English",
                "Playback: 1.5x, Fit",
                "Buffer: 12 s ahead",
                "File: 1.37 GB, 62% downloaded, 2.0 MB/s",
                "Sleep timer: 14 min left",
            ).joinToString("\n"),
            text,
        )
    }

    @Test
    fun `unknowns are skipped rather than printed as zeroes`() {
        // A stream that has not produced a frame yet: no resolution, no codec, no buffer figure.
        // Only subtitles survive, because "Off" is an answer where "0x0" is a bug on screen.
        assertEquals("Subtitles: Off", PlaybackInfo.render())
    }

    @Test
    fun `a video line missing its frame rate simply gets shorter`() {
        val text = PlaybackInfo.render(
            videoWidth = 1280,
            videoHeight = 720,
            videoMimeType = "video/avc",
        )
        assertEquals("Video: 1280x720, H.264\nSubtitles: Off", text)
    }

    @Test
    fun `whole frame rates drop their decimals`() {
        val text = PlaybackInfo.render(videoWidth = 1920, videoHeight = 1080, frameRate = 25f)
        assertEquals("Video: 1920x1080, 25 fps\nSubtitles: Off", text)
    }

    @Test
    fun `subtitles on with no declared language still say on`() {
        val text = PlaybackInfo.render(subtitlesOn = true)
        assertEquals("Subtitles: On", text)
    }

    @Test
    fun `a finished download says so instead of quoting a stale speed`() {
        val text = PlaybackInfo.render(
            sizeBytes = 700L * 1024 * 1024,
            downloadedFraction = 1f,
            downloadComplete = true,
            downloadSpeedBytesPerSec = 5 * 1024 * 1024,
        )
        assertEquals("Subtitles: Off\nFile: 700.0 MB, fully downloaded", text)
    }

    @Test
    fun `a stalled download shows the percent and withholds the speed`() {
        val text = PlaybackInfo.render(
            sizeBytes = 1024L * 1024 * 1024,
            downloadedFraction = 0.3f,
            downloadSpeedBytesPerSec = 100,
        )
        assertEquals("Subtitles: Off\nFile: 1.00 GB, 30% downloaded", text)
    }

    @Test
    fun `sleep minutes round up so a fresh timer reads what was asked for`() {
        // A moment after arming 15 minutes, 14:59 must still read as 15.
        val text = PlaybackInfo.render(sleepRemainingMs = 14 * 60_000L + 59_000L)
        assertEquals("Subtitles: Off\nSleep timer: 15 min left", text)
    }

    @Test
    fun `the last minute of the sleep timer is words, not a zero`() {
        val text = PlaybackInfo.render(sleepRemainingMs = 40_000L)
        assertEquals("Subtitles: Off\nSleep timer: under a minute", text)
    }

    @Test
    fun `audio channel counts read the way a film mix is named`() {
        assertEquals(
            "Audio: stereo\nSubtitles: Off",
            PlaybackInfo.render(audioChannels = 2),
        )
        assertEquals(
            "Audio: mono\nSubtitles: Off",
            PlaybackInfo.render(audioChannels = 1),
        )
        assertEquals(
            "Audio: 7.1\nSubtitles: Off",
            PlaybackInfo.render(audioChannels = 8),
        )
    }

    @Test
    fun `a fractional sample rate keeps its decimal`() {
        assertEquals(
            "Audio: 44.1 kHz\nSubtitles: Off",
            PlaybackInfo.render(audioSampleRate = 44_100),
        )
    }

    @Test
    fun `codec names come off the mime type`() {
        assertEquals("HEVC", PlaybackInfo.codecName("video/hevc"))
        assertEquals("H.264", PlaybackInfo.codecName("video/avc"))
        assertEquals("AV1", PlaybackInfo.codecName("video/av01"))
        assertEquals("VP9", PlaybackInfo.codecName("video/x-vnd.on2.vp9"))
        assertEquals("AAC", PlaybackInfo.codecName("audio/mp4a-latm"))
        assertEquals("AC-3", PlaybackInfo.codecName("audio/ac3"))
        assertEquals("E-AC-3", PlaybackInfo.codecName("audio/eac3-joc"))
        assertEquals("TrueHD", PlaybackInfo.codecName("audio/true-hd"))
        assertEquals("DTS", PlaybackInfo.codecName("audio/vnd.dts"))
        assertEquals("DTS-HD", PlaybackInfo.codecName("audio/vnd.dts.hd"))
        assertEquals("Opus", PlaybackInfo.codecName("audio/opus"))
        assertEquals("PCM", PlaybackInfo.codecName("audio/raw"))
    }

    @Test
    fun `codec names fall back to the container's own string`() {
        // A remux that declares only "hvc1.2.4.L120.90" and no sample MIME type.
        assertEquals("HEVC", PlaybackInfo.codecName(null, "hvc1.2.4.L120.90"))
        assertEquals("H.264", PlaybackInfo.codecName(null, "avc1.640028"))
        assertEquals("AAC", PlaybackInfo.codecName(null, "mp4a.40.2"))
    }

    @Test
    fun `an unrecognised codec is named rather than hidden`() {
        assertEquals("WVC1", PlaybackInfo.codecName("video/wvc1"))
    }

    @Test
    fun `no codec information at all is null, so the line skips it`() {
        assertNull(PlaybackInfo.codecName(null, null))
        assertNull(PlaybackInfo.codecName("", ""))
    }
}
