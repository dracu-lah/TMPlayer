package com.tmplayer.cast

import java.util.Locale

/** Where a link estimate came from, best first. */
enum class LinkSource {
    /** Bytes the cast server really wrote to the receiver ([LinkMeter]). */
    Measured,

    /** The platform's word on the local link (Wi-Fi rate, Ethernet speed), scaled down to what a stream gets. */
    Platform,

    /** Nothing known: the plan keeps the ladder's top rung (1080p 8 Mb/s), as before. */
    Unknown,
}

/** What a transcode plans its bitrate from: [kbps] is null when [source] is [LinkSource.Unknown]. */
data class LinkEstimate(val kbps: Int?, val source: LinkSource)

/**
 * The platform's own word on the link this device reaches the receiver by, in kb/s a stream can
 * realistically use (see [LinkSpeeds.wifiEffectiveKbps]); null when the platform says nothing. May
 * block briefly (a process, a file): [LinkEstimator] calls it off the UI thread and guards it.
 */
fun interface LinkSpeedSource {
    fun effectiveKbps(): Int?
}

/**
 * The arithmetic and the parsers of the platforms' link answers, kept pure so each is tested on
 * the text a platform gives, truncated or hostile too.
 *
 * Why no active probe: neither Cast (LOAD) nor DLNA (SetAVTransportURI) lets a sender make a
 * receiver fetch a test body without loading it as a video, which flashes an error on the TV and
 * stops what it was playing. So the link is read from the platform first and corrected by what
 * the receiver really pulled from the cast server ([LinkMeter]).
 */
object LinkSpeeds {
    /** A Wi-Fi rate is the radio's best case; a stream gets about half of it. */
    const val WIFI_SHARE = 0.5

    /** A cable gets close to its speed. */
    const val WIRED_SHARE = 0.9

    private const val MAX_MBPS = 400_000.0

    fun wifiEffectiveKbps(phyMbps: Double): Int? = effective(phyMbps, WIFI_SHARE)

    fun wiredEffectiveKbps(mbps: Double): Int? = effective(mbps, WIRED_SHARE)

    private fun effective(mbps: Double, share: Double): Int? =
        if (mbps.isFinite() && mbps >= 1.0 && mbps <= MAX_MBPS) (mbps * 1000 * share).toInt() else null

    /** `/sys/class/net/<if>/speed`: Mb/s, or -1 where the card does not say (Wi-Fi, a link that is down). */
    fun sysfsMbps(text: String?): Double? =
        text?.trim()?.take(16)?.toDoubleOrNull()?.takeIf { it >= 1.0 && it <= MAX_MBPS }

    private val IW_RATE = Regex("(tx|rx) bitrate:\\s*([0-9]{1,6}(?:\\.[0-9]{1,3})?)\\s*MBit/s", RegexOption.IGNORE_CASE)

    /** `iw dev <if> link`: the lower of the tx and rx bitrates in the output, in Mb/s. */
    fun iwMbps(output: String?): Double? {
        val text = output?.take(MAX_TEXT) ?: return null
        return IW_RATE.findAll(text).mapNotNull { it.groupValues[2].toDoubleOrNull() }.filter { it >= 1.0 && it <= MAX_MBPS }.minOrNull()
    }

    /** PowerShell's `Get-NetAdapter ... ReceiveLinkSpeed`: bits per second on a line of its own. */
    fun windowsBpsToMbps(output: String?): Double? {
        val bps = output?.take(MAX_TEXT)?.lineSequence()?.mapNotNull { it.trim().toLongOrNull() }?.firstOrNull() ?: return null
        val mbps = bps / 1_000_000.0
        return mbps.takeIf { it >= 1.0 && it <= MAX_MBPS }
    }

    private val IFCONFIG_MEDIA = Regex("media:[^\\n(]*\\((\\d{1,6})(G?)base", RegexOption.IGNORE_CASE)

    /** macOS `ifconfig <if>`: `media: autoselect (1000baseT <full-duplex>)`, in Mb/s; Wi-Fi gives none. */
    fun ifconfigMbps(output: String?): Double? {
        val m = IFCONFIG_MEDIA.find(output?.take(MAX_TEXT) ?: return null) ?: return null
        val n = m.groupValues[1].toDoubleOrNull() ?: return null
        val mbps = if (m.groupValues[2].isNotEmpty()) n * 1000 else n
        return mbps.takeIf { it >= 1.0 && it <= MAX_MBPS }
    }

    fun looksWireless(name: String): Boolean {
        val n = name.lowercase(Locale.ROOT)
        return n.startsWith("wl") || n.startsWith("wlan") || n.contains("wi-fi") || n.contains("wifi") || n.contains("wireless")
    }

    private const val MAX_TEXT = 16_384
}

/**
 * The speed at which the receiver really took bytes from the cast server: [Burst] times the
 * `write` calls (not the source's reads, which are Telegram's speed) of one response and, for
 * each [WINDOW_BYTES] written, adds a sample. A receiver that fills its buffer takes bytes at the
 * link's speed; one that is paused or full makes the writes wait, which only shows as a slow sample,
 * so the best recent sample is a floor on the link, and that is what [bestKbps] gives.
 */
class LinkMeter(private val clock: () -> Long = { System.nanoTime() / 1_000_000 }) {

    private class Sample(val kbps: Int, val at: Long)

    private val samples = ArrayDeque<Sample>()

    /** One response's bytes, added as they are written. Not thread safe: one per response thread. */
    inner class Burst {
        private var bytes = 0L
        private var nanos = 0L

        fun add(written: Int, writeNanos: Long) {
            if (written <= 0 || writeNanos < 0) return
            bytes += written
            nanos += writeNanos
            if (bytes >= WINDOW_BYTES) {
                if (nanos > 0) record(bytes, nanos)
                bytes = 0
                nanos = 0
            }
        }
    }

    fun burst(): Burst = Burst()

    /** One sample: [bytes] written in [nanos] of write time. Nonsense (a zero time, a speed past 100 Gb/s) is dropped. */
    fun record(bytes: Long, nanos: Long) {
        if (bytes <= 0 || nanos <= 0) return
        val kbps = bytes * 8.0 * 1_000_000 / nanos
        if (!kbps.isFinite() || kbps < 1 || kbps > MAX_KBPS) return
        synchronized(samples) {
            samples.addLast(Sample(kbps.toInt(), clock()))
            while (samples.size > MAX_SAMPLES) samples.removeFirst()
        }
    }

    /** The best sample of the last [TTL_MS], or null when there is none. */
    fun bestKbps(): Int? = synchronized(samples) {
        val now = clock()
        while (samples.isNotEmpty() && now - samples.first().at > TTL_MS) samples.removeFirst()
        samples.maxOfOrNull { it.kbps }
    }

    fun clear() = synchronized(samples) { samples.clear() }

    companion object {
        /** Bytes per sample: past the socket's own buffer (a few hundred KB), which swallows the first writes. */
        const val WINDOW_BYTES = 2L * 1024 * 1024
        const val TTL_MS = 5 * 60_000L
        private const val MAX_SAMPLES = 16
        private const val MAX_KBPS = 100_000_000.0
    }
}

/**
 * Turns a link reading into the rung a transcode plans with, and holds it so the bitrate does not
 * flap: down at once when the link clearly cannot carry the rung (under [DOWN_MARGIN] of what it
 * needs), up only when it clears the higher rung by [UP_MARGIN] and the rung has held [HOLD_UP_MS].
 */
class LinkLadder(private val clock: () -> Long = { System.nanoTime() / 1_000_000 }) {
    private var held: Int? = null
    private var changedAt = 0L

    /**
     * The link rate, in kb/s, the plan should see for [rawKbps] (the best reading): the lowest rate
     * that [TranscodePlanner] turns into the held rung. Null when nothing is known.
     */
    @Synchronized
    fun hold(rawKbps: Int?): Int? {
        val raw = rawKbps?.takeIf { it > 0 } ?: return held?.let(::linkFor)
        val ladder = TranscodePlanner.LADDER
        val budget = raw * TranscodePlanner.LINK_SHARE
        val fit = ladder.indexOfFirst { it.kbps <= budget }.let { if (it < 0) ladder.lastIndex else it }
        val now = clock()
        val current = held
        if (current == null) {
            held = fit
            changedAt = now
        } else if (fit > current) {
            // Worse: at once, but not for a dip that leaves the rung nearly carried.
            if (budget < ladder[current].kbps * DOWN_MARGIN) {
                held = fit
                changedAt = now
            }
        } else if (fit < current && now - changedAt >= HOLD_UP_MS) {
            val up = ladder.indexOfFirst { it.kbps * UP_MARGIN <= budget }.let { if (it < 0) ladder.lastIndex else it }
            if (up < current) {
                held = up
                changedAt = now
            }
        }
        return held?.let(::linkFor)
    }

    @Synchronized
    fun reset() {
        held = null
        changedAt = 0
    }

    private fun linkFor(index: Int): Int = (TranscodePlanner.LADDER[index].kbps / TranscodePlanner.LINK_SHARE).toInt() + 1

    companion object {
        const val DOWN_MARGIN = 0.9
        const val UP_MARGIN = 1.25
        const val HOLD_UP_MS = 60_000L
    }
}

/**
 * The link to the receiver as a transcode's plan sees it: what the cast server measured, else what
 * the platform says, else nothing ([LinkSource.Unknown], and the plan keeps 1080p 8 Mb/s). Asked
 * when a cast is planned and again whenever a transcode stream starts, so a better reading counts
 * from the next stream; [LinkLadder] keeps the rung steady between askings.
 */
class LinkEstimator(
    private val platform: LinkSpeedSource? = null,
    val meter: LinkMeter = LinkMeter(),
    private val ladder: LinkLadder = LinkLadder(),
) {
    fun estimate(): LinkEstimate {
        val measured = meter.bestKbps()
        val platformKbps = if (measured == null) runCatching { platform?.effectiveKbps() }.getOrNull()?.takeIf { it > 0 } else null
        val kbps = ladder.hold(measured ?: platformKbps)
        // A reading that lapsed (the samples are 5 minutes old) leaves the held rung, still said as the last source.
        val source = when {
            kbps == null -> LinkSource.Unknown
            measured != null -> LinkSource.Measured
            else -> LinkSource.Platform
        }
        return LinkEstimate(kbps, source)
    }

    /** A new receiver: what was learned about the last one no longer counts. */
    fun reset() {
        meter.clear()
        ladder.reset()
    }
}
