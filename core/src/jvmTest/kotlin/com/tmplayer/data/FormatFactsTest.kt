package com.tmplayer.data

import com.tmplayer.data.FormatFacts.AudioTrack
import com.tmplayer.data.FormatFacts.Kind
import com.tmplayer.data.FormatFacts.Probe
import com.tmplayer.data.FormatFacts.Resolution
import com.tmplayer.data.FormatFacts.Transfer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The badge classifier (T8): sizes to resolutions, probes to tags, and what it refuses to say. */
class FormatFactsTest {

    private fun labels(probe: Probe?) = FormatFacts.tags(probe).map { it.text }

    @Test
    fun `a stated size becomes its resolution`() {
        val table = listOf(
            Triple(320, 240, Resolution.Sd),
            Triple(640, 360, Resolution.Sd),
            Triple(720, 480, Resolution.Sd),
            Triple(854, 480, Resolution.Sd),
            Triple(960, 540, Resolution.Sd),
            Triple(1024, 576, Resolution.Sd),
            Triple(1280, 720, Resolution.Hd),
            Triple(1366, 768, Resolution.Hd),
            Triple(1600, 900, Resolution.Hd),
            Triple(1920, 1080, Resolution.FullHd),
            Triple(1920, 1088, Resolution.FullHd),
            Triple(2560, 1440, Resolution.Qhd),
            Triple(3440, 1440, Resolution.Qhd),
            Triple(3840, 2160, Resolution.Uhd4k),
            Triple(4096, 2160, Resolution.Uhd4k),
            Triple(5120, 2880, Resolution.Uhd4k),
            Triple(7680, 4320, Resolution.Uhd8k),
        )
        for ((w, h, expected) in table) assertEquals("$w x $h", expected, FormatFacts.resolution(w, h))
    }

    @Test
    fun `a crop keeps the class of the picture it was cut from`() {
        assertEquals(Resolution.FullHd, FormatFacts.resolution(1920, 800))
        assertEquals(Resolution.FullHd, FormatFacts.resolution(1920, 816))
        assertEquals(Resolution.Hd, FormatFacts.resolution(1280, 536))
        assertEquals(Resolution.Uhd4k, FormatFacts.resolution(3840, 1600))
        assertEquals(Resolution.Uhd4k, FormatFacts.resolution(3840, 1608))
        assertEquals(Resolution.FullHd, FormatFacts.resolution(1440, 1080))
        assertEquals(Resolution.Sd, FormatFacts.resolution(1024, 428))
    }

    @Test
    fun `a size turned on its side is judged by its width`() {
        assertEquals(Resolution.FullHd, FormatFacts.resolution(1080, 1920))
        assertEquals(Resolution.Hd, FormatFacts.resolution(720, 1280))
        assertEquals(Resolution.Uhd4k, FormatFacts.resolution(2160, 3840))
        // A tall phone screen is no 1440p.
        assertEquals(Resolution.FullHd, FormatFacts.resolution(1080, 2400))
        assertEquals(Resolution.FullHd, FormatFacts.resolution(1080, 2340))
    }

    @Test
    fun `a strip wider than cinema is judged by its height`() {
        assertEquals(Resolution.FullHd, FormatFacts.resolution(3840, 1080))
        assertEquals(Resolution.Qhd, FormatFacts.resolution(5120, 1440))
    }

    @Test
    fun `sizes that cannot be believed give no class`() {
        assertNull(FormatFacts.resolution(0, 0))
        assertNull(FormatFacts.resolution(1920, 0))
        assertNull(FormatFacts.resolution(0, 1080))
        assertNull(FormatFacts.resolution(-1920, 1080))
        assertNull(FormatFacts.resolution(1920, -1080))
        assertNull(FormatFacts.resolution(Int.MIN_VALUE, Int.MIN_VALUE))
        assertNull(FormatFacts.resolution(Int.MAX_VALUE, Int.MAX_VALUE))
        assertNull(FormatFacts.resolution(40_000, 40_000))
        assertNull(FormatFacts.resolution(FormatFacts.MAX_SIDE + 1, 1080))
        assertEquals(Resolution.Uhd8k, FormatFacts.resolution(15_360, 8_640))
    }

    @Test
    fun `the size beats the name and the name is flagged`() {
        val sized = FormatFacts.resolutionOf(1280, 720, "Clip.2160p.mkv")!!
        assertEquals("720p", sized.text)
        assertFalse(sized.fromName)
        val named = FormatFacts.resolutionOf(0, 0, "Clip.2160p.mkv")!!
        assertEquals("4K", named.text)
        assertTrue(named.fromName)
        assertNull(FormatFacts.resolutionOf(0, 0, "Birthday highlights.mp4"))
        assertNull(FormatFacts.resolutionOf(0, 0, null))
        assertNull(FormatFacts.resolutionOf(0, 0, ""))
    }

    @Test
    fun `a tile wears a pill only at 720p and up`() {
        fun item(name: String, w: Int = 0, h: Int = 0) = MediaItem(
            chatId = 1, messageId = 1, fileId = 1, title = name, sizeBytes = 1, durationSec = 1,
            mimeType = "video/mp4", thumbnailFileId = 0, miniThumbnail = null, date = 1, fileName = name, width = w, height = h,
        )
        assertEquals("1080p", FormatFacts.tileTag(item("a.mp4", 1920, 1080))!!.text)
        assertEquals("720p", FormatFacts.tileTag(item("a.mp4", 1280, 720))!!.text)
        assertNull(FormatFacts.tileTag(item("a.mp4", 854, 480)))
        assertNull(FormatFacts.tileTag(item("a.480p.mp4")))
        assertNull(FormatFacts.tileTag(item("holiday.mp4")))
        val fromName = FormatFacts.tileTag(item("Night Train (2019) 1080p x265.mkv"))!!
        assertEquals("1080p", fromName.text)
        assertTrue(fromName.fromName)
    }

    @Test
    fun `high dynamic range kinds`() {
        assertEquals(listOf("HDR10"), FormatFacts.pictureTags(Probe(transfer = Transfer.Pq)).map { it.text })
        assertEquals(listOf("HLG"), FormatFacts.pictureTags(Probe(transfer = Transfer.Hlg)).map { it.text })
        assertEquals(listOf("HDR10+"), FormatFacts.pictureTags(Probe(transfer = Transfer.Pq, hdr10Plus = true)).map { it.text })
        assertEquals(listOf("Dolby Vision", "HEVC"), FormatFacts.pictureTags(Probe(videoCodecs = "dvhe.08.06", transfer = Transfer.Pq)).map { it.text })
        assertEquals(listOf("Dolby Vision", "AV1"), FormatFacts.pictureTags(Probe(videoCodecs = "dav1.10.09")).map { it.text })
        assertEquals(listOf("Dolby Vision"), FormatFacts.pictureTags(Probe(videoMime = "video/dolby-vision")).map { it.text })
        assertEquals(listOf("10-bit"), FormatFacts.pictureTags(Probe(bitDepth = 10)).map { it.text })
        // Ten bits under HDR is the HDR's own business.
        assertEquals(listOf("HDR10"), FormatFacts.pictureTags(Probe(transfer = Transfer.Pq, bitDepth = 10)).map { it.text })
        assertTrue(FormatFacts.pictureTags(Probe(transfer = Transfer.Sdr, bitDepth = 8)).isEmpty())
        assertTrue(FormatFacts.pictureTags(Probe(transfer = Transfer.Unknown)).isEmpty())
    }

    @Test
    fun `codec and frame rate only where worth saying`() {
        assertEquals(listOf("HEVC"), FormatFacts.pictureTags(Probe(videoMime = "video/hevc")).map { it.text })
        assertEquals(listOf("HEVC"), FormatFacts.pictureTags(Probe(videoCodecs = "hvc1.2.4.L153.B0")).map { it.text })
        assertEquals(listOf("AV1"), FormatFacts.pictureTags(Probe(videoMime = "video/av01")).map { it.text })
        assertTrue(FormatFacts.pictureTags(Probe(videoMime = "video/avc", videoCodecs = "avc1.640028")).isEmpty())
        assertEquals(listOf("60 fps"), FormatFacts.pictureTags(Probe(frameRate = 59.94f)).map { it.text })
        assertEquals(listOf("50 fps"), FormatFacts.pictureTags(Probe(frameRate = 50f)).map { it.text })
        assertEquals(listOf("120 fps"), FormatFacts.pictureTags(Probe(frameRate = 119.88f)).map { it.text })
        assertTrue(FormatFacts.pictureTags(Probe(frameRate = 49.9f)).isEmpty())
        assertTrue(FormatFacts.pictureTags(Probe(frameRate = 23.976f)).isEmpty())
        assertTrue(FormatFacts.pictureTags(Probe(frameRate = Float.NaN)).isEmpty())
        assertTrue(FormatFacts.pictureTags(Probe(frameRate = Float.POSITIVE_INFINITY)).isEmpty())
        assertTrue(FormatFacts.pictureTags(Probe(frameRate = 9_000f)).isEmpty())
        assertTrue(FormatFacts.pictureTags(Probe(frameRate = -60f)).isEmpty())
    }

    @Test
    fun `sound kinds and layouts`() {
        fun tags(track: AudioTrack) = FormatFacts.audioTags(track).map { it.text }
        assertEquals(listOf("Atmos", "5.1"), tags(AudioTrack(mime = "audio/eac3-joc", channels = 6)))
        assertEquals(listOf("Atmos", "5.1"), tags(AudioTrack(mime = "audio/eac3", codecs = "ec+3", channels = 6)))
        assertEquals(listOf("Atmos", "7.1"), tags(AudioTrack(mime = "audio/true-hd", profile = "Dolby TrueHD + Dolby Atmos", channels = 8)))
        // Plain TrueHD and E-AC-3 are never promoted.
        assertEquals(listOf("7.1"), tags(AudioTrack(mime = "audio/true-hd", channels = 8)))
        assertEquals(listOf("5.1"), tags(AudioTrack(mime = "audio/eac3", channels = 6)))
        assertEquals(listOf("DTS", "5.1"), tags(AudioTrack(mime = "audio/vnd.dts", channels = 6)))
        assertEquals(listOf("DTS-HD", "7.1"), tags(AudioTrack(mime = "audio/vnd.dts.hd", channels = 8)))
        assertEquals(listOf("DTS-HD", "5.1"), tags(AudioTrack(mime = "audio/dts", profile = "DTS-HD MA", channels = 6)))
        assertEquals(listOf("DTS:X", "7.1"), tags(AudioTrack(mime = "audio/vnd.dts.uhd;profile=p2", channels = 8)))
        assertEquals(listOf("DTS:X", "7.1"), tags(AudioTrack(mime = "audio/dts", profile = "DTS-HD MA + DTS:X", channels = 8)))
        assertEquals(listOf("AAC", "Stereo"), tags(AudioTrack(mime = "audio/mp4a-latm", channels = 2)))
        assertEquals(listOf("Stereo"), tags(AudioTrack(mime = "audio/opus", channels = 2)))
        // A count with no common name says nothing.
        assertTrue(tags(AudioTrack(mime = "audio/opus", channels = 3)).isEmpty())
        assertTrue(tags(AudioTrack(mime = "audio/opus", channels = 1)).isEmpty())
        assertTrue(tags(AudioTrack()).isEmpty())
    }

    @Test
    fun `the best sound track speaks for the file`() {
        val stereo = AudioTrack(mime = "audio/mp4a-latm", channels = 2)
        val five = AudioTrack(mime = "audio/ac3", channels = 6)
        val atmos = AudioTrack(mime = "audio/eac3-joc", channels = 6)
        val dts = AudioTrack(mime = "audio/vnd.dts", channels = 6)
        assertEquals(atmos, FormatFacts.bestAudio(listOf(stereo, five, atmos, dts)))
        assertEquals(dts, FormatFacts.bestAudio(listOf(stereo, dts, five)))
        assertEquals(five, FormatFacts.bestAudio(listOf(stereo, five)))
        // A tie goes to the first, which is the one that plays.
        assertEquals(five, FormatFacts.bestAudio(listOf(five, five.copy(codecs = "x"))))
        assertNull(FormatFacts.bestAudio(emptyList()))
    }

    @Test
    fun `a whole row reads picture first then sound`() {
        val probe = Probe(
            videoMime = "video/hevc", width = 3840, height = 2160, frameRate = 24f, transfer = Transfer.Pq, bitDepth = 10,
            audio = listOf(AudioTrack(mime = "audio/eac3-joc", channels = 6), AudioTrack(mime = "audio/mp4a-latm", channels = 2)),
        )
        assertEquals(listOf("4K", "HDR10", "HEVC", "Atmos", "5.1"), labels(probe))
        assertEquals(
            listOf(Kind.Resolution, Kind.Hdr, Kind.Codec, Kind.Audio, Kind.Channels),
            FormatFacts.tags(probe).map { it.kind },
        )
    }

    @Test
    fun `no probe says only the size, and an empty probe says nothing more`() {
        val size = FormatFacts.Tag(Kind.Resolution, "1080p")
        assertEquals(listOf(size), FormatFacts.tags(null, size))
        assertEquals(listOf(size), FormatFacts.tags(Probe(), size))
        assertTrue(FormatFacts.tags(null).isEmpty())
        assertTrue(FormatFacts.tags(Probe()).isEmpty())
        // The probe's own size wins over the message's.
        assertEquals("4K", FormatFacts.tags(Probe(width = 3840, height = 2160), size).first().text)
    }

    @Test
    fun `dolby vision profile is read from the codecs string`() {
        assertEquals(7, FormatFacts.dolbyVisionProfile("dvhe.07.06"))
        assertEquals(8, FormatFacts.dolbyVisionProfile(" DVH1.08.06 "))
        assertEquals(9, FormatFacts.dolbyVisionProfile("dvav.09.05"))
        assertEquals(10, FormatFacts.dolbyVisionProfile("dav1.10.09"))
        assertNull(FormatFacts.dolbyVisionProfile("hvc1.2.4.L153.B0"))
        assertNull(FormatFacts.dolbyVisionProfile("dvhe"))
        assertNull(FormatFacts.dolbyVisionProfile(null))
        assertNull(FormatFacts.dolbyVisionProfile(""))
    }

    @Test
    fun `the cache is bounded and keeps the latest`() {
        FormatProbes.clear()
        val probe = Probe(videoMime = "video/hevc")
        repeat(FormatProbes.CAPACITY + 50) { FormatProbes.record("v$it", probe) }
        assertNull(FormatProbes["v0"])
        assertEquals(probe, FormatProbes["v${FormatProbes.CAPACITY + 49}"])
        // Nothing worth keeping is not kept; a same probe does not count as a change.
        FormatProbes.record("empty", Probe())
        assertNull(FormatProbes["empty"])
        val before = FormatProbes.changes.value
        FormatProbes.record("v${FormatProbes.CAPACITY + 49}", probe)
        assertEquals(before, FormatProbes.changes.value)
        FormatProbes.clear()
    }

    @Test
    fun `garbage never throws`() {
        val text = listOf("", " ", "\u0000", "a".repeat(100_000), "dvhe.", "dvhe.99999999999.1", "audio/\uD83D", "ec+3".repeat(10_000))
        val numbers = listOf(0, -1, 1, Int.MIN_VALUE, Int.MAX_VALUE, 40_000)
        for (t in text) for (n in numbers) {
            val track = AudioTrack(mime = t, codecs = t, profile = t, channels = n)
            val probe = Probe(
                videoMime = t, videoCodecs = t, width = n, height = -n, frameRate = n.toFloat(), bitDepth = n,
                transfer = Transfer.entries[(n and 3)], hdr10Plus = n > 0, audio = List(500) { track },
            )
            FormatFacts.tags(probe)
            FormatFacts.rowFor(
                MediaItem(1, 1, 1, t, 1, 1, t, 0, null, 1, fileName = t, width = n, height = n),
                probe,
            )
            FormatProbes.record(t, probe)
            FormatProbes[t]
        }
        FormatFacts.resolutionOf(Int.MAX_VALUE, 5, "x".repeat(1_000_000))
        FormatFacts.frameRateText(Float.NEGATIVE_INFINITY)
        FormatProbes.clear()
    }
}
