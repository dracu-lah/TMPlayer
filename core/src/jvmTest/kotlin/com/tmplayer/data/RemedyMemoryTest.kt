package com.tmplayer.data

import com.tmplayer.player.Remedy
import com.tmplayer.player.RemedyAction
import com.tmplayer.player.RemedyEvidence
import com.tmplayer.player.RemedyPath
import com.tmplayer.player.RemedyPlan
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RemedyMemoryTest {

    private val key = "sun50iw6p1/28/hevc:10:1080:30/2/mkv/eac3"
    private val day = 20_400
    private val v1 = "1.30.0"

    private fun hop(remedy: Remedy, replaced: Remedy? = null, excluded: Set<String> = emptySet()) =
        RemedyAction.Hop(remedy, RemedyPath(excluded = excluded), RemedyEvidence.Frozen, replaced)

    @Test
    fun aMinuteOfCleanPlayMakesTheStart() {
        val memory = RemedyMemory.Empty.worked(key, listOf(Remedy.Software, Remedy.AudioSoftware), day)
        assertEquals(setOf(Remedy.Software, Remedy.AudioSoftware), memory.planFor(key, v1).start)
        // One per level: a later decoder remedy replaces the earlier one, the audio one stays.
        val later = memory.worked(key, listOf(Remedy.FlipAsync), day)
        assertEquals(setOf(Remedy.FlipAsync, Remedy.AudioSoftware), later.planFor(key, v1).start)
        assertEquals(RemedyPlan(), memory.planFor("other/key", v1))
    }

    @Test
    fun theViewersMoveBackToTheDeviceDecoderIsNoLongerTheStart() {
        // WP-25: the player's decoder line learned software, then the viewer went back.
        val software = RemedyMemory.Empty.learned(key, Remedy.Software, day).learned(key, Remedy.AudioSoftware, day)
        assertEquals(setOf(Remedy.Software, Remedy.AudioSoftware), software.planFor(key, v1).start)
        val back = software.unlearned(key, Remedy.Software, day)
        assertEquals(setOf(Remedy.AudioSoftware), back.planFor(key, v1).start)
        // Unknown class: a record with nothing learned, never a crash.
        assertEquals(emptySet(), RemedyMemory.Empty.unlearned("x", Remedy.Software, day).planFor("x", v1).start)
    }

    @Test
    fun demotedOnlyAfterTwoFailuresOnTwoVideos() {
        var memory = RemedyMemory.Empty.worked(key, listOf(Remedy.Software), day)
        memory = memory.failed(key, Remedy.Software, "video1", v1, day)
        memory = memory.failed(key, Remedy.Software, "video1", v1, day)
        assertEquals(setOf(Remedy.Software), memory.planFor(key, v1).start, "the same video twice counts once")
        memory = memory.failed(key, Remedy.Software, "video2", v1, day)
        val plan = memory.planFor(key, v1)
        assertEquals(emptySet(), plan.start)
        assertEquals(setOf(Remedy.Software), plan.skip)
    }

    @Test
    fun aFailedRemedyIsProbedOnceInANewVersion() {
        var memory = RemedyMemory.Empty
            .failed(key, Remedy.Reload, "a", v1, day)
            .failed(key, Remedy.Reload, "b", v1, day)
        assertEquals(setOf(Remedy.Reload), memory.planFor(key, v1).skip)
        assertEquals(emptySet(), memory.planFor(key, "1.31.0").skip, "tried once more after an update")
        memory = memory.failed(key, Remedy.Reload, "c", "1.31.0", day)
        assertEquals(setOf(Remedy.Reload), memory.planFor(key, "1.31.0").skip, "the probe failed: skipped again")
        // A clean play forgets the failures.
        assertEquals(emptySet(), memory.worked(key, listOf(Remedy.Reload), day).planFor(key, "1.31.0").skip)
    }

    @Test
    fun aHopCountsAndBlamesWhatItReplaced() {
        var memory = RemedyMemory.Empty.hopped(key, hop(Remedy.ExcludeDecoder, excluded = setOf("OMX.rk.video_decoder.hevc")), "a", v1, day)
        memory = memory.hopped(key, hop(Remedy.OtherEngine, replaced = Remedy.ExcludeDecoder), "a", v1, day)
        val record = memory.records.getValue(key)
        assertEquals(2, record.hops)
        assertEquals(listOf("OMX.rk.video_decoder.hevc"), record.excluded)
        assertEquals(1, record.fails.getValue(Remedy.ExcludeDecoder).count)
        assertEquals(setOf("OMX.rk.video_decoder.hevc"), memory.planFor(key, v1).exclude)
    }

    @Test
    fun crashesStartOnSoftwareThenTheEngine() {
        var memory = RemedyMemory.Empty.crashed(key, day)
        assertEquals(setOf(Remedy.Software), memory.planFor(key, v1).start)
        memory = memory.crashed(key, day)
        assertEquals(setOf(Remedy.OtherEngine), memory.planFor(key, v1).start)
        assertEquals(emptySet(), memory.worked(key, emptyList(), day).planFor(key, v1).start, "a clean play clears them")
    }

    @Test
    fun stutterIsRemembered() {
        assertTrue(RemedyMemory.Empty.stuttered(key, day).planFor(key, v1).stutteredBefore)
    }

    @Test
    fun countsAreCapped() {
        var memory = RemedyMemory.Empty
        repeat(RemedyMemory.MAX_COUNT + 50) { memory = memory.crashed(key, day).stuttered(key, day) }
        val record = memory.records.getValue(key)
        assertEquals(RemedyMemory.MAX_COUNT, record.crashes)
        assertEquals(RemedyMemory.MAX_COUNT, record.stutters)
        val stored = memory.encode().replace("|1000|1000|", "|5000|-5|")
        val read = RemedyMemory.decode(stored, day).records.getValue(key)
        assertEquals(RemedyMemory.MAX_COUNT, read.stutters, "a stored count over the cap is read as the cap")
        assertEquals(0, read.crashes, "a negative count is read as 0")
    }

    @Test
    fun staleEntriesExpire() {
        val memory = RemedyMemory.Empty.stuttered("old/key", day - RemedyMemory.STALE_DAYS - 1).stuttered(key, day)
        val read = RemedyMemory.decode(memory.encode(), day)
        assertEquals(setOf(key), read.records.keys)
    }

    @Test
    fun entriesAndKeysAreBounded() {
        var memory = RemedyMemory.Empty
        repeat(RemedyMemory.MAX_ENTRIES + 30) { memory = memory.stuttered("k$it", day) }
        assertEquals(RemedyMemory.MAX_ENTRIES, memory.records.size)
        assertTrue("k0" !in memory.records, "the least recently seen goes first")
        val long = memory.stuttered("x".repeat(500), day)
        assertTrue(long.records.keys.all { it.length <= RemedyClassKey.MAX_LENGTH })
        assertTrue(RemedyMemory.decode(long.encode(), day).records.size <= RemedyMemory.MAX_ENTRIES)
    }

    @Test
    fun roundTrip() {
        val memory = RemedyMemory.Empty
            .hopped(key, hop(Remedy.ExcludeDecoder, excluded = setOf("c2.mtk.hevc.decoder")), "a1", v1, day)
            .failed(key, Remedy.Reload, "b2", v1, day)
            .worked(key, listOf(Remedy.ExcludeDecoder, Remedy.PassthroughOff), day)
            .crashed("other/28/avc:8:720:30/64/mp4/aac", day)
        val read = RemedyMemory.decode(memory.encode(), day)
        assertEquals(memory.records, read.records)
        assertEquals(memory.encode(), read.encode())
    }

    @Test
    fun badPartsAreSkippedTheRestKept() {
        val stored = "good=sw||1|0|0|$day|;=sw||1|0|0|$day|;bad=sw;worse=zz,sw,sw,au|O M X!,c2.ok|x|y|z|$day|zz~1~a~b+ex~nope~a~b+rl~2~v~1.0"
        val read = RemedyMemory.decode(stored, day)
        assertEquals(setOf("good", "worse"), read.records.keys)
        val worse = read.records.getValue("worse")
        assertEquals(setOf(Remedy.Software, Remedy.AudioSoftware), worse.working)
        assertEquals(listOf("OMX", "c2.ok"), worse.excluded)
        assertEquals(setOf(Remedy.Reload), worse.fails.keys)
        assertEquals(0, worse.hops)
        assertEquals(RemedyMemory.Empty.records, RemedyMemory.decode("x".repeat(100_000), day).records)
    }

    @Test
    fun aRefusedBitstreamTeachesTheSoundAtOnce() {
        val crashed = RemedyMemory.Empty.crashed(key, day).worked(key, listOf(Remedy.ExcludeDecoder), day).crashed(key, day)
            .failed(key, Remedy.AudioSoftware, "a", v1, day)
        val memory = crashed.learned(key, Remedy.AudioSoftware, day)
        val record = memory.records.getValue(key)
        assertEquals(setOf(Remedy.ExcludeDecoder, Remedy.AudioSoftware), record.working, "the picture's start stays")
        assertTrue(Remedy.AudioSoftware !in record.fails, "its failure is forgotten")
        assertEquals(1, record.crashes, "crash counts are not a sound's to clear")
        // One per level: passthrough off replaces it, a lighter copy is never learned.
        assertEquals(setOf(Remedy.ExcludeDecoder, Remedy.PassthroughOff), memory.learned(key, Remedy.PassthroughOff, day).records.getValue(key).working)
        assertEquals(record.working, memory.learned(key, Remedy.LighterCopy, day).records.getValue(key).working)
        assertEquals(memory.records, RemedyMemory.decode(memory.encode(), day).records)
    }

    @Test
    fun theClassKeyIsShortAndPlain() {
        val tanix = DeviceFacts(hardware = "sun50iw6p1", board = "exdroid", sdk = 28)
        val picture = DecodeJudge.Picture(DecodeJudge.MIME_HEVC, bitDepth = 10, width = 1920, height = 1080, fps = 23.976f)
        assertEquals(
            "sun50iw6p1/28/hevc:10:1080:30/2/mkv/eac3",
            RemedyClassKey.of(tanix, picture, "hvc1.2.4.L153", "video/x-matroska", "audio/eac3"),
        )
        assertEquals("dv5", RemedyClassKey.profileOf("dvhe.05.06"))
        assertEquals("unknown/0/avc:8:480:30/-/other/-", RemedyClassKey.of(DeviceFacts(), DecodeJudge.Picture(DecodeJudge.MIME_AVC)))
        val odd = RemedyClassKey.of(
            DeviceFacts(socModel = "Ünï Côdé;=|".repeat(20), sdk = Int.MAX_VALUE),
            DecodeJudge.Picture("x/" + "y".repeat(300), width = -5, height = Int.MAX_VALUE),
            "a.b;c|d=e".repeat(30), ";|=".repeat(30), "audio/" + "z;".repeat(40),
        )
        assertTrue(odd.length <= RemedyClassKey.MAX_LENGTH, odd)
        assertTrue(odd.none { it in ";|=,+~" }, odd)
    }
}
