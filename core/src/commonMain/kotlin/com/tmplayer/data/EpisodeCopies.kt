package com.tmplayer.data

import java.util.Locale
import kotlin.math.abs
import kotlin.math.ln

/**
 * Several copies of one episode, and which of them to play.
 *
 * Channels post the same episode more than once, in the sizes their viewers ask for: a 350 MB
 * copy for a phone, a 600 MB one, a 1.8 GB one for a television. Often the file names are the
 * same and only the size tells them apart. Whoever chose the small copy of episode one wants the
 * small copy of episode two, so the next copy is the one most like the last: the same markers
 * in the name where the name has any (720p, 1080p, x265), else the nearest size.
 */
object EpisodeCopies {

    /**
     * The one of [candidates] most like the copy called [likeName] at [likeSize] bytes, or null
     * when there are none. Ties keep the candidates' own order.
     */
    fun <T> closest(
        likeName: String,
        likeSize: Long,
        candidates: List<T>,
        nameOf: (T) -> String,
        sizeOf: (T) -> Long,
    ): T? {
        if (candidates.size <= 1) return candidates.firstOrNull()
        val wanted = markers(likeName)
        return candidates.withIndex().minWithOrNull(
            compareByDescending<IndexedValue<T>> { (_, it) -> (markers(nameOf(it)) intersect wanted).size }
                .thenBy { (_, it) -> sizeGap(likeSize, sizeOf(it)) }
                .thenBy { it.index },
        )?.value
    }

    /** [closest] for videos in a chat, which carry their own name and size. */
    fun closest(like: MediaItem, candidates: List<MediaItem>): MediaItem? =
        closest(nameOf(like), like.sizeBytes, candidates, ::nameOf) { it.sizeBytes }

    /**
     * What tells one copy from another on its chip: "1080p · HEVC · 1.8 GB". The resolution comes
     * from the name, else from the picture size Telegram states (most copies named only by their
     * episode still carry it); the codec only from the name. The size alone when neither says
     * anything about the picture.
     */
    fun label(item: MediaItem): String {
        val facts = MediaFacts.read(nameOf(item), item.width, item.height)
        val resolution = MediaMapper.qualityTags(nameOf(item)).firstOrNull() ?: facts.resolution
        return listOfNotNull(resolution, facts.videoCodec, MediaMapper.formatSize(item.sizeBytes).ifEmpty { null })
            .joinToString("  ·  ")
    }

    /** The markers that say how a copy was made: its resolution and its codec, in one spelling. */
    fun markers(name: String): Set<String> {
        val text = name.lowercase(Locale.ROOT)
        return buildSet {
            RESOLUTION.find(text)?.let { add(it.groupValues[1]) }
            if (UHD.containsMatchIn(text)) add("2160")
            if (HEVC.containsMatchIn(text)) add("hevc")
            if (AVC.containsMatchIn(text)) add("avc")
            if (TEN_BIT.containsMatchIn(text)) add("10bit")
        }
    }

    /** How far apart two sizes are, as a ratio, so 350 MB against 600 MB counts like 1 GB against 1.7 GB. */
    private fun sizeGap(a: Long, b: Long): Double =
        if (a <= 0 || b <= 0) Double.MAX_VALUE else abs(ln(a.toDouble() / b.toDouble()))

    private fun nameOf(item: MediaItem) = item.fileName.ifBlank { item.title }

    private val RESOLUTION = Regex("""(?<![a-z0-9])(\d{3,4})[pi](?![a-z0-9])""")
    private val UHD = Regex("""(?<![a-z0-9])(4k|uhd)(?![a-z0-9])""")
    private val HEVC = Regex("""(?<![a-z0-9])(x265|h\.?265|hevc)(?![a-z0-9])""")
    private val AVC = Regex("""(?<![a-z0-9])(x264|h\.?264|avc)(?![a-z0-9])""")
    private val TEN_BIT = Regex("""(?<![a-z0-9])10.?bit(?![a-z0-9])""")
}
