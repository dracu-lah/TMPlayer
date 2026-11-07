package com.tmplayer.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/**
 * Seeking in Matroska files without Cues: hand-built files with every Cluster layout the scan has
 * to read (CRC-32 first, sizes unknown, BlockGroups with laced frames, a Segment of unknown size, a
 * bitrate that swings tenfold, fake Cluster IDs inside frames), and one written by ffmpeg to a pipe,
 * the way live recordings come out, when ffmpeg is on the machine.
 *
 * Each seek runs the way the player's extractor runs it: a guess, a forward scan in chunks, the
 * Cluster found, another guess or play.
 */
class MkvCuelessSeekTest {

    // ---- a small EBML writer ------------------------------------------------------------------

    private fun id(value: Long): ByteArray {
        val length = when {
            value <= 0xFF -> 1
            value <= 0xFFFF -> 2
            value <= 0xFFFFFF -> 3
            else -> 4
        }
        return ByteArray(length) { i -> (value shr (8 * (length - 1 - i))).toByte() }
    }

    /** The shortest size vint, the way ffmpeg writes small elements. */
    private fun size(value: Long): ByteArray {
        var length = 1
        while (value >= (1L shl (7 * length)) - 1) length++
        val out = ByteArray(length) { i -> (value shr (8 * (length - 1 - i))).toByte() }
        out[0] = (out[0].toInt() or (0x80 shr (length - 1))).toByte()
        return out
    }

    private val unknownSize = byteArrayOf(0x01, 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte())

    private fun el(id: Long, body: ByteArray) = id(id) + size(body.size.toLong()) + body
    private fun uint(id: Long, v: Long): ByteArray {
        var n = 1
        while (n < 8 && v ushr (8 * n) != 0L) n++
        return el(id, ByteArray(n) { i -> (v shr (8 * (n - 1 - i))).toByte() })
    }
    private fun float(id: Long, v: Double) = el(id, java.nio.ByteBuffer.allocate(8).putDouble(v).array())

    private class Built(val bytes: ByteArray, val clusters: List<Pair<Long, Long>>, val durationUs: Long, val firstCluster: Long)

    /**
     * [seconds] of video in Clusters of [clusterMs], [rate] bytes a second at each second; the
     * layout options as named.
     */
    private fun build(
        seconds: Int = 1200,
        clusterMs: Int = 2000,
        rate: (Int) -> Int = { 20_000 },
        crcFirst: Boolean = false,
        unknownClusters: Boolean = false,
        blockGroups: Boolean = false,
        unknownSegment: Boolean = false,
        scaleNs: Long = 1_000_000,
        fakeIds: Boolean = false,
        audioOnly: Boolean = false,
    ): Built {
        val random = Random(7)
        val unitsPerMs = 1_000_000.0 / scaleNs
        val head = el(0x1A45DFA3, el(0x4282, "matroska".toByteArray()))
        val info = el(0x1549A966, uint(0x2AD7B1, scaleNs) + float(0x4489, seconds * 1000.0 * unitsPerMs))
        val seekHead = el(0x114D9B74, el(0x4DBB, el(0x53AB, id(0x114D9B74)) + uint(0x53AC, 0)))
        val tracks = el(0x1654AE6B, el(0xAE, uint(0xD7, 1) + uint(0x83, if (audioOnly) 2 else 1)))
        val body = ByteArrayOutputStream()
        body.write(seekHead)
        body.write(info)
        body.write(tracks)
        val clusters = mutableListOf<Pair<Long, Long>>()
        val segmentHeader = id(0x18538067).size + 8
        val fileOffset = head.size + segmentHeader
        var firstCluster = -1L
        var ms = 0
        val totalMs = seconds * 1000
        while (ms < totalMs) {
            val frames = ByteArrayOutputStream()
            val frameMs = 40
            var t = 0
            while (t < clusterMs && ms + t < totalMs) {
                val second = (ms + t) / 1000
                val payload = random.nextBytes(maxOf(4, rate(second) * frameMs / 1000))
                if (fakeIds && payload.size > 16 && random.nextInt(4) == 0) {
                    // A Cluster ID inside a frame, with a size that does not fit and no Timestamp.
                    val at = random.nextInt(payload.size - 12)
                    byteArrayOf(0x1F, 0x43, 0xB6.toByte(), 0x75, 0x88.toByte(), 0, 0, 0, 0, 0, 0, 0x10).copyInto(payload, at)
                }
                val time = (t * unitsPerMs).toInt()
                val key = if (t == 0) 0x80 else 0
                if (blockGroups) {
                    // EBML lacing of two frames, the way some muxers lace audio.
                    val lace = byteArrayOf(0x81.toByte(), (time shr 8).toByte(), time.toByte(), 0x06, 0x01) + size(payload.size / 2L) + payload
                    frames.write(el(0xA0, el(0xA1, lace) + if (t == 0) ByteArray(0) else uint(0xFB, 1)))
                } else {
                    frames.write(el(0xA3, byteArrayOf(0x81.toByte(), (time shr 8).toByte(), time.toByte(), key.toByte()) + payload))
                }
                t += frameMs
            }
            val timecode = (ms * unitsPerMs).toLong()
            var content = uint(0xE7, timecode) + frames.toByteArray()
            if (crcFirst) content = el(0xBF, random.nextBytes(4)) + content
            val cluster = id(0x1F43B675) + (if (unknownClusters) unknownSize else size(content.size.toLong())) + content
            val at = fileOffset + body.size().toLong()
            if (firstCluster < 0) firstCluster = at
            clusters += at to ms * 1000L
            body.write(cluster)
            ms += clusterMs
        }
        val segment = id(0x18538067) + (if (unknownSegment) unknownSize else ByteArray(8).also { b ->
            val v = body.size().toLong()
            for (i in 0 until 8) b[i] = (v shr (8 * (7 - i))).toByte()
            b[0] = 0x01
        })
        val bytes = head + segment + body.toByteArray()
        return Built(bytes, clusters, totalMs * 1000L, firstCluster)
    }

    // ---- the extractor's loop, over bytes in memory ---------------------------------------------

    private class Landed(val position: Long, val steps: Int, val bytesRead: Long)

    private fun seek(file: ByteArray, plan: MkvCuelessSeek, targetUs: Long, chunk: Int = 64 * 1024): Landed {
        val search = plan.search(targetUs)
        var from = plan.estimate(targetUs)
        var pos = from
        var read = 0L
        repeat(10_000) {
            val n = minOf(chunk.toLong(), file.size - pos).toInt().coerceAtLeast(0)
            val atEnd = pos + n >= file.size
            val buf = file.copyOfRange(pos.toInt(), (pos + n).toInt())
            read += n
            val step = when (val scan = MkvClusters.find(buf, n, atEnd, file.size - pos)) {
                is MkvClusters.Scan.Hit -> {
                    val at = pos + scan.offset
                    pos = at
                    search.found(from, at, plan.usOf(scan.timecode), if (scan.length >= 0) at + scan.length else -1)
                }
                is MkvClusters.Scan.Miss -> if (atEnd) search.nothing(from) else {
                    pos += scan.skip
                    null
                }
            }
            when (step) {
                null -> Unit
                MkvCuelessSeek.Step.Play -> return Landed(pos, search.steps, read)
                is MkvCuelessSeek.Step.ScanFrom -> {
                    from = step.position
                    pos = step.position
                }
            }
        }
        error("the search did not end")
    }

    /**
     * The seek landed on a real Cluster, close to the target or on the last one before it, or
     * within [slackUs] of the target either side where the guesses may run out first.
     */
    private fun assertLands(built: Built, plan: MkvCuelessSeek, targetUs: Long, chunk: Int = 64 * 1024, slackUs: Long = 0): Landed {
        val landed = seek(built.bytes, plan, targetUs, chunk)
        val cluster = built.clusters.firstOrNull { it.first == landed.position }
        assertNotNull("landed at ${landed.position}, not a Cluster, for ${targetUs / 1000} ms", cluster)
        val time = cluster!!.second
        val best = built.clusters.lastOrNull { it.second <= targetUs } ?: built.clusters.first()
        val close = time <= targetUs + maxOf(slackUs, MkvCuelessSeek.LATE_US) && time >= targetUs - maxOf(slackUs, MkvCuelessSeek.CLOSE_US)
        assertTrue("for ${targetUs / 1000} ms landed at ${time / 1000} ms (best ${best.second / 1000})", close || cluster == best)
        assertTrue("${landed.steps} steps for ${targetUs / 1000} ms", landed.steps <= MkvCuelessSeek.MAX_STEPS)
        return landed
    }

    private fun planFor(built: Built) = MkvCuelessSeek(
        fileLength = built.bytes.size.toLong(),
        firstCluster = built.firstCluster - 12,
        durationUs = built.durationUs,
        timecodeScaleNs = MkvClusters.timecodeScale(built.bytes.copyOf(minOf(16 * 1024, built.bytes.size))) ?: error("no scale"),
    )

    private fun targets(durationUs: Long) = listOf(0L, 1_000_000L, durationUs / 2, durationUs * 9 / 10, durationUs - 1_000_000L, durationUs) +
        (1..25).map { durationUs * it / 26 + 333_000L }

    private fun assertAllSeeks(built: Built, chunk: Int = 64 * 1024, slackUs: Long = 0): Int {
        var steps = 0
        for (target in targets(built.durationUs)) steps += assertLands(built, planFor(built), target, chunk, slackUs).steps
        // And one plan for all of them, the way one playback seeks again and again.
        val plan = planFor(built)
        for (target in targets(built.durationUs)) steps += assertLands(built, plan, target, chunk, slackUs).steps
        return steps
    }

    // ---- the tests -------------------------------------------------------------------------------

    @Test
    fun evenBitrateLandsCloseInFewGuesses() {
        val built = build()
        val plan = planFor(built)
        val middle = assertLands(built, plan, built.durationUs / 2)
        assertTrue("${middle.steps} steps", middle.steps <= 2)
        // 62 seeks: most land on the first guess.
        val steps = assertAllSeeks(built)
        assertTrue("$steps guesses after the first for 62 seeks", steps <= 20)
    }

    @Test
    fun aSwingingBitrateStillLands() {
        // A dark quiet stretch a tenth of the rest, then a busy one twice the rest. A target right
        // where the bitrate jumps tenfold can run out of guesses a few Clusters off.
        assertAllSeeks(build(rate = { s -> if (s in 300..600) 2000 else if (s in 900..1000) 40_000 else 20_000 }), slackUs = 10_000_000)
    }

    @Test
    fun crcFirstUnknownSizesAndBlockGroupsAreRead() {
        assertAllSeeks(build(crcFirst = true))
        assertAllSeeks(build(unknownClusters = true, unknownSegment = true))
        assertAllSeeks(build(blockGroups = true, clusterMs = 5000))
    }

    @Test
    fun fakeClusterIdsInFramesAreSkipped() {
        assertAllSeeks(build(fakeIds = true))
    }

    @Test
    fun smallChunksAndOddScalesRead() {
        // Cluster starts falling across chunk ends, and Timestamps in tenths of a millisecond.
        assertAllSeeks(build(seconds = 300, scaleNs = 100_000), chunk = 1000)
    }

    @Test
    fun audioOnlyAndLongClusters() {
        assertAllSeeks(build(audioOnly = true, clusterMs = 12_000, rate = { 4000 }))
    }

    @Test
    fun aSecondSeekNearTheFirstIsQuick() {
        val built = build(rate = { s -> 8000 + (s % 97) * 400 })
        val plan = planFor(built)
        assertLands(built, plan, 700_000_000L)
        val again = assertLands(built, plan, 712_000_000L)
        assertTrue("${again.steps} steps", again.steps <= 2)
        assertTrue(plan.anchors.isNotEmpty())
    }

    @Test
    fun startEstimateFindsTheFirstCluster() {
        val built = build()
        val plan = planFor(built)
        val landed = seek(built.bytes, plan, 0)
        assertEquals(built.clusters.first().first, landed.position)
        assertEquals(0, landed.steps)
    }

    @Test
    fun anchorsStayBounded() {
        val plan = MkvCuelessSeek(10_000_000_000L, 1000, 36_000_000_000L)
        for (i in 0 until 5000) plan.learn(1000L + i * 1_000_000L, i * 7_000_000L)
        assertTrue(plan.anchors.size <= MkvCuelessSeek.MAX_ANCHORS)
        assertTrue(plan.anchors.zipWithNext().all { (a, b) -> a.position < b.position })
        // Noise is not learned: a time far past the end, or a place outside the file.
        val before = plan.anchors.size
        plan.learn(5000L, 99_000_000_000L)
        plan.learn(20_000_000_000L, 1_000_000L)
        assertEquals(before, plan.anchors.size)
    }

    @Test
    fun timecodeScaleIsReadFromTheHead() {
        assertEquals(1_000_000L, MkvClusters.timecodeScale(build(seconds = 10).bytes))
        assertEquals(100_000L, MkvClusters.timecodeScale(build(seconds = 10, scaleNs = 100_000).bytes))
        assertNull(MkvClusters.timecodeScale(ByteArray(64) { it.toByte() }))
        assertNull(MkvClusters.timecodeScale(ByteArray(0)))
        // Info without a TimecodeScale means the Matroska default.
        val head = el(0x1A45DFA3, el(0x4282, "webm".toByteArray()))
        val bare = head + id(0x18538067) + unknownSize + el(0x1549A966, float(0x4489, 1000.0)) + uint(0x1F43B675, 0)
        assertEquals(MkvClusters.DEFAULT_TIMECODE_SCALE_NS, MkvClusters.timecodeScale(bare))
    }

    @Test
    fun garbageNeverThrows() {
        val random = Random(11)
        repeat(2000) {
            val data = random.nextBytes(random.nextInt(0, 300))
            // Cluster IDs sprinkled in, so the checks past the ID are reached.
            if (data.size > 8 && it % 2 == 0) byteArrayOf(0x1F, 0x43, 0xB6.toByte(), 0x75).copyInto(data, random.nextInt(data.size - 4))
            MkvClusters.find(data, data.size, it % 3 == 0, if (it % 5 == 0) -1 else random.nextLong(-5, 500))
            MkvClusters.find(data, random.nextInt(-5, data.size + 5), false)
            MkvClusters.timecodeScale(data)
        }
    }

    // ---- a file ffmpeg wrote to a pipe -------------------------------------------------------------

    /** The Clusters of a file, walked from its top: (start, Timestamp), for files with known sizes. */
    private fun walk(bytes: ByteArray): List<Pair<Long, Long>> {
        fun vint(at: Int, marker: Boolean): Pair<Long, Int> {
            val first = bytes[at].toInt() and 0xFF
            var n = 1
            while (first and (0x80 shr (n - 1)) == 0) n++
            var v = if (marker) first.toLong() else (first and (0xFF shr n)).toLong()
            for (k in 1 until n) v = (v shl 8) or (bytes[at + k].toLong() and 0xFF)
            return v to n
        }
        val out = mutableListOf<Pair<Long, Long>>()
        var p = 0
        val (_, ebmlIdLength) = vint(0, true)
        val (ebmlSize, ebmlSizeLength) = vint(ebmlIdLength, false)
        p = ebmlIdLength + ebmlSizeLength + ebmlSize.toInt()
        val (_, segmentIdLength) = vint(p, true)
        p += segmentIdLength + vint(p + segmentIdLength, false).second
        while (p < bytes.size - 8) {
            val (id, idLength) = vint(p, true)
            val (size, sizeLength) = vint(p + idLength, false)
            val body = p + idLength + sizeLength
            if (id == MkvClusters.ID_CLUSTER) {
                // ffmpeg starts each Cluster with a CRC-32.
                var child = body
                if (vint(child, true).first == 0xBFL) child += 6
                val (tid, tidLength) = vint(child, true)
                check(tid == 0xE7L)
                val (tsize, tsizeLength) = vint(child + tidLength, false)
                var t = 0L
                for (k in 0 until tsize.toInt()) t = (t shl 8) or (bytes[child + tidLength + tsizeLength + k].toLong() and 0xFF)
                out += p.toLong() to t * 1000
            }
            p = body + size.toInt()
        }
        return out
    }

    @Test
    fun ffmpegPipeOutputSeeks() {
        val ffmpeg = listOf("/usr/bin/ffmpeg", "/usr/local/bin/ffmpeg", "/opt/homebrew/bin/ffmpeg").map(::File).firstOrNull { it.canExecute() }
        assumeTrue("ffmpeg is not installed", ffmpeg != null)
        val out = File.createTempFile("cueless", ".mkv")
        try {
            val process = ProcessBuilder(
                ffmpeg!!.path, "-hide_banner", "-loglevel", "error",
                "-f", "lavfi", "-i", "testsrc2=size=160x90:rate=25:duration=180",
                "-f", "lavfi", "-i", "sine=frequency=440:sample_rate=22050:duration=180",
                "-c:v", "libx264", "-preset", "veryfast", "-g", "50", "-crf", "30",
                "-c:a", "aac", "-b:a", "24k", "-shortest", "-f", "matroska", "pipe:1",
            ).redirectOutput(out).redirectError(ProcessBuilder.Redirect.DISCARD).start()
            assumeTrue("ffmpeg did not finish", process.waitFor(120, TimeUnit.SECONDS) && process.exitValue() == 0)
            val bytes = out.readBytes()
            // Written to a pipe: no Cues, which is the case this is for.
            assertTrue(bytes.indices.none { i -> i + 3 < bytes.size && bytes[i] == 0x1C.toByte() && bytes[i + 1] == 0x53.toByte() && bytes[i + 2] == 0xBB.toByte() && bytes[i + 3] == 0x6B.toByte() })
            val clusters = walk(bytes)
            assertTrue(clusters.size > 20)
            val built = Built(bytes, clusters, 180_000_000L, clusters.first().first)
            assertAllSeeks(built)
        } finally {
            out.delete()
        }
    }
}
