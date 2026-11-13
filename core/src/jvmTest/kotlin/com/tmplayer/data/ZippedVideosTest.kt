package com.tmplayer.data

import kotlin.test.Test
import kotlin.test.assertEquals

class ZippedVideosTest {

    private val gb4 = 4_194_304_000L

    private fun named(name: String, bytes: Long = gb4) = ZippedVideos.Named(name, bytes)

    private val endgame = (1..3).map { named("Avengers.Endgame.Encore.2026.IMAX.1080p.DSNP.WEB-DL..zip.00$it") }
    private val colony = (1..18).map { named("Colony (2026) UHD 2160p BluRay SDR HEVC [(Hindi + Tamil .zip.%03d".format(it)) }

    @Test
    fun `three pieces of one zip are one video`() {
        assertEquals(1, ZippedVideos.count(endgame))
    }

    @Test
    fun `the Optimus Prime chat holds two videos in 21 pieces`() {
        assertEquals(2, ZippedVideos.count(endgame + colony))
    }

    @Test
    fun `a phone backup is not a video`() {
        assertEquals(0, ZippedVideos.count(listOf(named("IvyWalletBackup_20260930-1830.zip", 900L * 1024 * 1024))))
        assertEquals(0, ZippedVideos.count(listOf(named("IvyWalletBackup_20260930-1830.zip", 12_000L))))
    }

    @Test
    fun `a backup beside the pieces does not add to the count`() {
        assertEquals(1, ZippedVideos.count(endgame + named("IvyWalletBackup_20260930-1830.zip", 5_000L)))
    }

    @Test
    fun `a lone release named zip as large as a video is one`() {
        assertEquals(1, ZippedVideos.count(listOf(named("Show.S01E01.1080p.WEB-DL.zip", 1_500_000_000L))))
        assertEquals(0, ZippedVideos.count(listOf(named("Show.S01E01.1080p.WEB-DL.zip", 4_000_000L))))
    }

    @Test
    fun `the same set twice still counts once`() {
        assertEquals(1, ZippedVideos.count(endgame + endgame))
    }

    @Test
    fun `other documents are ignored`() {
        assertEquals(0, ZippedVideos.count(listOf(named("Taxes.pdf"), named("Movie.2020.1080p.mkv.001"), named(""))))
    }
}
