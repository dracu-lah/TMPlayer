package com.tmplayer.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaFactsTest {

    @Test
    fun `a scene release names its codecs, audio and source`() {
        val facts = MediaFacts.read("Harbour.Notes.S01E04.1080p.WEB-DL.DDP5.1.x265-GROUP.mkv")
        assertEquals("1080p", facts.resolution)
        assertEquals("HEVC", facts.videoCodec)
        assertEquals("E-AC3 5.1", facts.audio)
        assertEquals("WEB-DL", facts.source)
        assertEquals("MKV", facts.container)
        assertNull(facts.dynamicRange)
        assertTrue(facts.tracks.isEmpty())
    }

    @Test
    fun `a stated picture size beats the name, and says its pixels`() {
        val facts = MediaFacts.read("clip-720p.mp4", width = 1920, height = 1080)
        assertEquals("1080p", facts.resolution)
        assertEquals("1920 × 1080", facts.pixels)
    }

    @Test
    fun `either side decides the class of a stated size`() {
        assertEquals("4K", MediaFacts.resolutionOf(3840, 1600))
        assertEquals("1080p", MediaFacts.resolutionOf(1080, 1920))
        assertEquals("720p", MediaFacts.resolutionOf(1280, 536))
        assertEquals("SD", MediaFacts.resolutionOf(640, 360))
        assertNull(MediaFacts.resolutionOf(0, 0))
    }

    @Test
    fun `H dot 264 is a codec and never a channel layout`() {
        val facts = MediaFacts.read("Film.2024.720p.BluRay.H.264.AAC2.0.mp4")
        assertEquals("H.264", facts.videoCodec)
        assertEquals("AAC 2.0", facts.audio)
        assertEquals("Blu-ray", facts.source)
    }

    @Test
    fun `dynamic range and Atmos`() {
        val facts = MediaFacts.read("Film.2160p.UHD.BluRay.DV.HDR10.TrueHD.Atmos.7.1.HEVC.mkv")
        assertEquals("4K", facts.resolution)
        assertEquals("Dolby Vision", facts.dynamicRange)
        assertEquals("TrueHD Atmos 7.1", facts.audio)
        assertEquals("HEVC", facts.videoCodec)
    }

    @Test
    fun `tracks the name announces`() {
        val facts = MediaFacts.read("Show (2025) Dual Audio [Hindi + English] 1080p ESub.mkv")
        assertEquals(listOf(MediaFacts.Track.DualAudio, MediaFacts.Track.Subtitles), facts.tracks)
        assertEquals(
            listOf(MediaFacts.Track.MultiAudio),
            MediaFacts.read("Show.S01E01.MULTi.1080p.mkv").tracks,
        )
    }

    @Test
    fun `a plain name says nothing it does not know`() {
        val facts = MediaFacts.read("birthday highlights.mp4")
        assertNull(facts.resolution)
        assertNull(facts.videoCodec)
        assertNull(facts.audio)
        assertNull(facts.source)
        assertEquals("MP4", facts.container)
        // An episode code with a dot after it is not a channel layout.
        assertNull(MediaFacts.read("Show.S01E02.1080p.mkv").audio)
        // Nor is a word that merely contains a codec's letters.
        assertNull(MediaFacts.read("savvy.dtsomething.mkv").audio)
    }
}
