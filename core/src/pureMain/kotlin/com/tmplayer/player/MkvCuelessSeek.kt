package com.tmplayer.player

import kotlin.concurrent.Volatile

/**
 * Seeking in a Matroska file that has no Cues, the index a seek is normally looked up in.
 *
 * ffmpeg writing to a pipe or a live stream, and the tools behind many cam and line-audio rips,
 * leave the Cues out. Media3's Matroska extractor then plays the file from the start only, so a
 * two hour video could not be sought in at all. The file is still made of Clusters, each starting
 * with the time of its first frame, so a seek can find its place without an index:
 *
 * 1. guess where the time is from the file's size and length (the bitrate of a video is roughly
 *    even over its length), a little early, since the scan goes forward;
 * 2. read forward from there to the next Cluster ([MkvClusters.find]) and read its time;
 * 3. close enough before the time asked for: play from that Cluster. Else guess again between the
 *    Clusters already seen on either side, and stop after [MAX_STEPS] guesses at the closest
 *    Cluster before the time.
 *
 * Every Cluster read is kept ([anchors]), so a later seek near one already made starts closer.
 * The player decodes from the Cluster's first keyframe up to the time asked for, as it does after
 * any seek to a keyframe before the target.
 *
 * Positions are byte offsets in the file. [firstCluster] is where a scan for the first Cluster
 * starts (a few bytes before it); [fileLength] is the whole file, which stands in for the end of the
 * Segment, so a Segment of unknown size is no different.
 */
class MkvCuelessSeek(
    val fileLength: Long,
    firstCluster: Long,
    val durationUs: Long,
    private val timecodeScaleNs: Long = MkvClusters.DEFAULT_TIMECODE_SCALE_NS,
) {

    /** A Cluster seen: where it starts, the time of its first frame, and where it ends (-1: unknown). */
    class Anchor(val position: Long, val timeUs: Long, val end: Long = -1)

    /** What a [Search] wants next. */
    sealed interface Step {
        /** Play from the Cluster just found: the input is at its first byte. */
        data object Play : Step

        /** Scan for the next Cluster from [position]. */
        data class ScanFrom(val position: Long) : Step
    }

    private val start = Anchor(firstCluster.coerceIn(0, maxOf(0, fileLength - 1)), 0)
    private val end = Anchor(fileLength, durationUs)

    /** The Clusters seen so far, in file order. Written by the loading thread, read by any. */
    @Volatile
    var anchors: List<Anchor> = emptyList()
        private set

    /** The time, in microseconds, of a Cluster's raw Timestamp value. */
    fun usOf(timecode: Long): Long {
        if (timecode < 0) return -1
        val us = timecode.toDouble() * timecodeScaleNs / 1000.0
        return if (us >= Long.MAX_VALUE / 2) Long.MAX_VALUE / 2 else us.toLong()
    }

    /** Where a scan for the Cluster that holds [timeUs] starts. */
    fun estimate(timeUs: Long): Long {
        if (timeUs <= 0) return start.position
        val wanted = timeUs - LEAD_US
        val all = anchors
        val lo = all.lastOrNull { it.timeUs <= wanted }?.takeIf { it.position > start.position } ?: start
        val hi = all.firstOrNull { it.timeUs > wanted && it.position > lo.position } ?: end
        return interpolate(lo, hi, wanted).coerceIn(start.position, maxOf(start.position, fileLength - 1))
    }

    /** Remembers a Cluster seen at [position] starting at [timeUs], ending at [end] (-1: unknown). */
    fun learn(position: Long, timeUs: Long, end: Long = -1) {
        if (position < 0 || position >= fileLength || !plausible(timeUs)) return
        val list = anchors.toMutableList()
        val at = list.binarySearch { it.position.compareTo(position) }
        val anchor = Anchor(position, timeUs, end)
        if (at >= 0) list[at] = anchor else list.add(-at - 1, anchor)
        while (list.size > MAX_ANCHORS) list.removeAt(closestPair(list))
        anchors = list
    }

    /** Whether a Cluster time can belong to this file: anything past its end by more is noise. */
    fun plausible(timeUs: Long): Boolean = timeUs >= 0 && timeUs <= durationUs + maxOf(PAST_END_US, durationUs / 20)

    /** A seek to [targetUs], one guess at a time. */
    fun search(targetUs: Long): Search = Search(targetUs.coerceIn(0, maxOf(0, durationUs)))

    inner class Search(val targetUs: Long) {
        /** Guesses made so far. */
        var steps = 0
            private set

        /** Scans that started at or past here found only Clusters after the target, or none. */
        private var upper = fileLength

        /** True once the search settled on a known Cluster and only has to reach it. */
        private var settling = false

        /**
         * Which side of the target the last guess landed on (-1 before, 1 after, 0 none yet),
         * and the weights of the two sides' distances in the next guess. A guess landing on the
         * same side twice halves the weight of the other side (the Illinois rule), so a stretch
         * where the bitrate climbs or drops is closed in on from both sides instead of crept up
         * on from one, which plain interpolation does.
         */
        private var side = 0
        private var loWeight = 1.0
        private var hiWeight = 1.0

        /**
         * How far before the target a guess aims, since the scan goes forward to the next Cluster.
         * Doubled each time a guess lands past the target: a guess right on the target's place
         * finds the Cluster after it whenever Clusters are longer than this.
         */
        private var lead = LEAD_US

        private fun landed(now: Int) {
            if (now == side) {
                if (now < 0) hiWeight /= 2 else loWeight /= 2
            } else {
                loWeight = 1.0
                hiWeight = 1.0
            }
            side = now
            lead = if (now > 0) minOf(lead * 2, MAX_LEAD_US) else LEAD_US
        }

        /** A Cluster found at [at] (starting [timeUs], ending at [end] or -1) by a scan from [from]. */
        fun found(from: Long, at: Long, timeUs: Long, end: Long = -1): Step {
            if (settling) return Step.Play
            if (!plausible(timeUs)) return next(from = at + 1)
            learn(at, timeUs, end)
            if (timeUs <= targetUs + LATE_US && timeUs >= targetUs - CLOSE_US) return Step.Play
            if (timeUs > targetUs) {
                upper = minOf(upper, from)
                landed(1)
            } else {
                landed(-1)
            }
            return next(here = at)
        }

        /** A scan from [from] reached the end of the file, or gave up, without finding a Cluster. */
        fun nothing(from: Long): Step {
            if (settling) return Step.Play
            upper = minOf(upper, from)
            landed(1)
            return next()
        }

        private fun next(here: Long = -1, from: Long = -1): Step {
            steps++
            val all = anchors
            val lo = all.lastOrNull { it.timeUs <= targetUs && it.position < upper } ?: start
            val hi = all.firstOrNull { it.timeUs > targetUs && it.position > lo.position } ?: end
            val ceiling = minOf(upper, hi.position)
            val adjacent = lo.end >= 0 && lo.end >= ceiling
            if (from >= 0 && from < ceiling && steps < MAX_STEPS) return Step.ScanFrom(from)
            // The gap's length in time at the bitrate between the two Clusters around it.
            val bytesPerUs = (hi.position - lo.position).toDouble() / maxOf(1L, hi.timeUs - lo.timeUs)
            val gapUs = if (bytesPerUs > 0) (ceiling - lo.position) / bytesPerUs else 0.0
            if (adjacent || gapUs <= MIN_GAP_US) return settle(lo, here)
            if (steps >= MAX_STEPS) {
                // Out of guesses with a gap still open: the nearer of the two Clusters on either
                // side, even the one after, rather than one far back.
                val after = hi !== end && hi.timeUs - targetUs < targetUs - lo.timeUs
                return settle(if (after) hi else lo, here)
            }
            val aim = maxOf(lo.timeUs, targetUs - lead)
            val below = (aim - lo.timeUs) * loWeight
            val above = (hi.timeUs - aim).coerceAtLeast(0) * hiWeight
            var guess = if (below + above > 0) lo.position + ((hi.position - lo.position) * (below / (below + above))).toLong() else -1
            if (guess <= lo.position || guess >= ceiling) guess = lo.position + (ceiling - lo.position) / 2
            return Step.ScanFrom(guess)
        }

        /** Plays from [to], a Cluster already found. */
        private fun settle(to: Anchor, here: Long): Step {
            if (to.position == here) return Step.Play
            settling = true
            return Step.ScanFrom(to.position)
        }
    }

    private fun interpolate(lo: Anchor, hi: Anchor, timeUs: Long): Long {
        val span = hi.timeUs - lo.timeUs
        if (span <= 0 || hi.position <= lo.position) return lo.position
        val fraction = ((timeUs - lo.timeUs).toDouble() / span).coerceIn(0.0, 1.0)
        return lo.position + ((hi.position - lo.position) * fraction).toLong()
    }

    /** The anchor whose loss costs least: the one nearest its neighbour. */
    private fun closestPair(list: List<Anchor>): Int {
        var best = 1
        var gap = Long.MAX_VALUE
        for (i in 1 until list.size) {
            val d = list[i].position - list[i - 1].position
            if (d < gap) {
                gap = d
                best = i
            }
        }
        return best
    }

    companion object {
        /** Close enough before the target: the player decodes this much from the Cluster's keyframe. */
        const val CLOSE_US = 5_000_000L

        /** A Cluster this little after the target is played from too: the picture starts that much late. */
        const val LATE_US = 500_000L

        /** Guesses aim this far before the target, so the next Cluster after the guess comes before it. */
        const val LEAD_US = 2_500_000L

        /** The most [Search] aims before the target after landing past it again and again. */
        private const val MAX_LEAD_US = 60_000_000L

        /** Each guess is a fresh read from Telegram at another place, so not many. */
        const val MAX_STEPS = 8

        /**
         * No other Cluster is looked for in a gap this short (in time, at the bitrate around it):
         * the Cluster before it is as close as another guess would get.
         */
        const val MIN_GAP_US = 1_000_000L

        /** Enough for every seek of a long evening; the nearest pairs go first beyond it. */
        const val MAX_ANCHORS = 512

        private const val PAST_END_US = 60_000_000L
    }
}

/** The bytes of a Matroska file that a seek without Cues reads: the start of each Cluster. */
object MkvClusters {

    const val DEFAULT_TIMECODE_SCALE_NS = 1_000_000L

    /** Bytes a Cluster start needs after it to be told from noise: ID, size, a CRC-32 and its Timestamp. */
    const val LOOKAHEAD = 48

    private const val ID_EBML = 0x1A45DFA3L
    private const val ID_SEGMENT = 0x18538067L
    private const val ID_INFO = 0x1549A966L
    private const val ID_TIMECODE_SCALE = 0x2AD7B1L
    const val ID_CLUSTER = 0x1F43B675L
    private const val ID_TIMESTAMP = 0xE7L
    private const val ID_CRC32 = 0xBFL
    private const val ID_VOID = 0xECL
    private const val MAX_VOID = 4096L

    /** What a scan of some bytes found. */
    sealed interface Scan {
        /** A Cluster starts at [offset]; its whole element is [length] bytes (-1: unknown) and its Timestamp [timecode]. */
        data class Hit(val offset: Int, val length: Long, val timecode: Long) : Scan

        /** None in the bytes; the next scan starts [skip] bytes on. */
        data class Miss(val skip: Int) : Scan
    }

    /**
     * The first Cluster that starts in the first [length] bytes of [buf]. [atEnd] says the bytes run
     * to the end of the file; otherwise a start too near the end to be checked is left for the next
     * scan. [bytesLeft] is how many bytes the file has from [buf]'s first one (-1: unknown), which a
     * Cluster's stated size must fit in.
     */
    fun find(buf: ByteArray, length: Int, atEnd: Boolean, bytesLeft: Long = -1): Scan {
        val n = length.coerceIn(0, buf.size)
        val lastStart = if (atEnd) n - 4 else n - LOOKAHEAD
        var i = 0
        while (i <= lastStart) {
            if (buf[i] == 0x1F.toByte() && buf[i + 1] == 0x43.toByte() && buf[i + 2] == 0xB6.toByte() && buf[i + 3] == 0x75.toByte()) {
                val hit = clusterAt(buf, i, n, if (bytesLeft >= 0) bytesLeft - i else -1)
                if (hit is Scan.Hit) return hit
                // A start whose children run past the bytes read (a long Void first) is read again
                // with more bytes after it, unless it is already the first byte.
                if (hit === Incomplete && !atEnd && i > 0) return Scan.Miss(i)
            }
            i++
        }
        return Scan.Miss(if (atEnd) n else maxOf(0, lastStart + 1))
    }

    /** A Cluster start that may be one: more bytes are needed to tell. */
    private val Incomplete = Scan.Miss(0)

    /**
     * The Cluster starting at [at] when its size and first children read as one; [Incomplete]
     * when they run past [limit]; null when they are not a Cluster's.
     */
    private fun clusterAt(buf: ByteArray, at: Int, limit: Int, left: Long): Scan? {
        val size = vint(buf, at + 4, limit, keepMarker = false) ?: return incomplete(buf, at + 4, limit)
        val headerLength = 4 + size.length
        val unknown = size.value == allOnes(size.length)
        if (!unknown) {
            if (size.value < 3) return null
            if (left >= 0 && headerLength + size.value > left) return null
        }
        val contentEnd = if (unknown) Long.MAX_VALUE else at + headerLength + size.value
        var p = at + headerLength
        repeat(3) {
            val id = vint(buf, p, limit, keepMarker = true) ?: return incomplete(buf, p, limit)
            if (id.length > 4) return null
            val childSize = vint(buf, p + id.length, limit, keepMarker = false) ?: return incomplete(buf, p + id.length, limit)
            if (childSize.value == allOnes(childSize.length)) return null
            val body = p + id.length + childSize.length
            if (body + childSize.value > contentEnd) return null
            when (id.value) {
                ID_TIMESTAMP -> {
                    if (childSize.value !in 1..8) return null
                    if (body + childSize.value > limit) return Incomplete
                    var value = 0L
                    for (k in 0 until childSize.value.toInt()) value = (value shl 8) or (buf[body + k].toLong() and 0xFF)
                    if (value < 0) return null
                    return Scan.Hit(at, if (unknown) -1 else headerLength + size.value, value)
                }
                ID_CRC32 -> if (childSize.value != 4L) return null
                ID_VOID -> if (childSize.value > MAX_VOID) return null
                else -> return null
            }
            p = (body + childSize.value).toInt()
        }
        return null
    }

    /**
     * The TimecodeScale (nanoseconds per Timestamp unit) from the head of a file, from its Info;
     * the Matroska default when Info says none, null when the head is not Matroska or holds no Info
     * before its first Cluster.
     */
    fun timecodeScale(head: ByteArray, length: Int = head.size): Long? {
        val n = length.coerceIn(0, head.size)
        val ebmlId = vint(head, 0, n, keepMarker = true) ?: return null
        if (ebmlId.value != ID_EBML) return null
        val ebmlSize = vint(head, ebmlId.length, n, keepMarker = false) ?: return null
        var p = ebmlId.length.toLong() + ebmlSize.length + ebmlSize.value
        if (p >= n) return null
        val segmentId = vint(head, p.toInt(), n, keepMarker = true) ?: return null
        if (segmentId.value != ID_SEGMENT) return null
        val segmentSize = vint(head, p.toInt() + segmentId.length, n, keepMarker = false) ?: return null
        p += segmentId.length + segmentSize.length
        while (p < n) {
            val id = vint(head, p.toInt(), n, keepMarker = true) ?: return null
            val size = vint(head, p.toInt() + id.length, n, keepMarker = false) ?: return null
            if (size.value == allOnes(size.length)) return null
            val body = p + id.length + size.length
            when (id.value) {
                ID_CLUSTER -> return null
                ID_INFO -> return infoScale(head, body, minOf(n.toLong(), body + size.value))
            }
            p = body + size.value
        }
        return null
    }

    private fun infoScale(head: ByteArray, from: Long, until: Long): Long? {
        var p = from
        while (p < until) {
            val id = vint(head, p.toInt(), until.toInt(), keepMarker = true) ?: return null
            val size = vint(head, p.toInt() + id.length, until.toInt(), keepMarker = false) ?: return null
            val body = p + id.length + size.length
            if (id.value == ID_TIMECODE_SCALE) {
                if (size.value !in 1..8 || body + size.value > until) return null
                var value = 0L
                for (k in 0 until size.value.toInt()) value = (value shl 8) or (head[(body + k).toInt()].toLong() and 0xFF)
                return value.takeIf { it in 1..1_000_000_000L }
            }
            p = body + size.value
        }
        return if (p == until) DEFAULT_TIMECODE_SCALE_NS else null
    }

    private class Vint(val value: Long, val length: Int)

    /** [Incomplete] when the bytes ran out at [at], null when the byte there starts no integer. */
    private fun incomplete(buf: ByteArray, at: Int, limit: Int): Scan? =
        if (at >= limit || at >= buf.size || (buf[at].toInt() and 0xFF) != 0) Incomplete else null

    private fun allOnes(length: Int): Long = (1L shl (7 * length)) - 1

    /** An EBML variable length integer at [at], or null when it is not one or runs past [limit]. */
    private fun vint(buf: ByteArray, at: Int, limit: Int, keepMarker: Boolean): Vint? {
        if (at < 0 || at >= limit || at >= buf.size) return null
        val first = buf[at].toInt() and 0xFF
        if (first == 0) return null
        var length = 1
        while (first and (0x80 shr (length - 1)) == 0) length++
        if (at + length > limit || at + length > buf.size) return null
        var value = if (keepMarker) first.toLong() else (first and (0xFF shr length)).toLong()
        for (k in 1 until length) value = (value shl 8) or (buf[at + k].toLong() and 0xFF)
        return Vint(value, length)
    }
}
