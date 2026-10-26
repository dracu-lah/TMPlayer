package com.tmplayer.data

import com.tmplayer.platform.concurrentMap
import kotlin.concurrent.Volatile

import kotlin.math.abs

/**
 * What says two videos are one, for [VideoCopies]: a key shared by every copy of a video, or null
 * for a video that is only ever a copy of itself. A seam, so the grouping the browse pages use
 * (titles, years, lengths, and the viewer's own "Not the same video") decides here as well.
 */
fun interface VideoGrouping {
    fun keyOf(item: MediaItem): String?
}

/**
 * One video posted more than once, in one chat or several: which copy to play, and whose place
 * counts.
 *
 * A film in 720p, 1080p and 4K is one film to the viewer. What is stored stays per message (a
 * place, a tick, a bookmark belong to the file they were written for), and "the same video" is
 * worked out when it is read: opening the 1080p after watching half the 720p carries on at the
 * same moment, Continue watching holds one card, and finishing or starting over in one copy
 * clears the others' places. Nothing is rewritten under another key, so a wrong grouping costs a
 * wrong suggestion, never somebody's place.
 */
object VideoCopies {

    /**
     * How copies are recognised. [SeriesShelf.sameVideoKey] (the title read off the name and the
     * year, or the show and episode) until the browse pages' grouping is plugged in here.
     */
    @Volatile
    var grouping: VideoGrouping = VideoGrouping { SeriesShelf.sameVideoKey(it) }

    fun keyOf(item: MediaItem): String? = grouping.keyOf(item)

    /**
     * [copies] with the very same file posted more than once (forwarded, or uploaded again: the
     * same Telegram file, or the same name and size) as one entry, the first of them leading, in
     * the order given. A list of copies to choose from offers a file once, however often it was
     * posted; the rest are the entry's second half, for "posted twice" or "in 2 chats".
     *
     * Copies in a chat the kids lock hides ([allowed], see [visible]) are never offered.
     */
    fun distinctFiles(
        copies: List<MediaItem>,
        allowed: Set<Long>? = ChatGate.allowed.value,
    ): List<Pair<MediaItem, List<MediaItem>>> {
        val groups = mutableListOf<MutableList<MediaItem>>()
        for (copy in visible(copies, allowed)) {
            val same = groups.firstOrNull { group -> group.any { sameFile(it, copy) } }
            if (same != null) same += copy else groups += mutableListOf(copy)
        }
        return groups.map { it.first() to it.toList() }
    }

    /** [distinctFiles], one item per file: the one [playing] where it is among a file's posts. */
    fun oneOfEachFile(
        copies: List<MediaItem>,
        playing: String,
        allowed: Set<Long>? = ChatGate.allowed.value,
    ): List<MediaItem> {
        val shown = distinctFiles(copies, allowed).map { (lead, same) -> same.firstOrNull { it.id == playing } ?: lead }
        // The video playing stays listed, ticked, whatever chat it came from.
        return if (shown.any { it.id == playing }) shown else copies.filter { it.id == playing } + shown
    }

    /**
     * [copies] without those in chats the kids lock hides: [allowed] is [ChatGate.allowed], null
     * while the lock is off.
     */
    fun visible(copies: List<MediaItem>, allowed: Set<Long>? = ChatGate.allowed.value): List<MediaItem> =
        if (allowed == null) copies else copies.filter { it.chatId in allowed }

    /** One file posted twice: the same Telegram file, or the same file name and size. */
    fun sameFile(a: MediaItem, b: MediaItem): Boolean = when {
        a.id == b.id -> true
        a.fileId > 0 && a.fileId == b.fileId -> true
        else -> a.fileName.isNotBlank() && a.fileName == b.fileName && a.sizeBytes > 0 && a.sizeBytes == b.sizeBytes
    }

    /**
     * Which video a stored record names. A record that kept its file name ([ResumeRecord.fileName])
     * is read like the live video. One written before records kept it has only its title, so it
     * counts only when that title names a year or an episode: a quality marker alone ("Lecture
     * 1080p") is too little to call two old records one video.
     */
    fun keyOf(record: ResumeRecord): String? = when {
        // "Not the same video" on it: a copy of nothing, however its title reads.
        VideoCorrections.current["${record.chatId}:${record.messageId}"]?.separate == true -> null
        record.fileName != null -> keyOf(record.toMediaItem())
        else -> keyOfTitle(record.title)
    }

    /** The cautious key of a bare title, as [keyOf] reads an old record. */
    fun keyOfTitle(title: String): String? {
        if (title.isBlank()) return null
        val item = MediaItem(
            chatId = 0, messageId = 0, fileId = 0, title = title, sizeBytes = 0, durationSec = 0,
            mimeType = "", thumbnailFileId = 0, miniThumbnail = null, date = 0, fileName = title,
        )
        val name = SeriesShelf.episodeOf(item)
        if (name.year == null && name.episode == null) return null
        // A caption's bare "S02E04" reads as a show called "S02E04": no show at all.
        val show = SeriesShelf.keyOf(name.title)
        if (show.isEmpty() || show == SeriesShelf.keyOf(name.episodeCode.orEmpty())) return null
        return SeriesShelf.sameVideoKey(item)
    }

    /**
     * Whether [record] was written for a copy of [item]: the same message, the same Telegram file
     * (a forward shares it, the strongest evidence there is), or the same video by [keyOf].
     */
    fun sameVideo(item: MediaItem, record: ResumeRecord): Boolean {
        if (record.chatId == item.chatId && record.messageId == item.messageId) return true
        if (item.fileId > 0 && record.fileId == item.fileId) return true
        val key = keyOf(item)
        if (key != null && key == keyOf(record)) return true
        // An old record against a live video: both read the cautious way.
        return record.fileName == null && keyOfTitle(item.title)?.let { it == keyOfTitle(record.title) } == true
    }

    /**
     * The record [item] resumes from: its own, or the newest among the copies of the same video,
     * whichever was written last, with its place carried over to [item]'s length ([carryOver]).
     * Null when none was started, or the newest was started in a different cut.
     */
    fun resumeOf(item: MediaItem, records: List<ResumeRecord>): ResumeRecord? {
        val newest = records
            .filter { sameVideo(item, it) }
            .maxWithOrNull(compareBy<ResumeRecord> { it.updatedAt }.thenBy { it.chatId == item.chatId && it.messageId == item.messageId })
            ?: return null
        if (newest.chatId == item.chatId && newest.messageId == item.messageId) return newest
        val at = carryOver(newest.positionMs, newest.durationMs, item.durationSec * 1000L) ?: return null
        return newest.copy(positionMs = at)
    }

    /**
     * A place in one copy, in another: the same moment where the lengths agree within
     * [SAME_LENGTH]; scaled by the lengths where they differ by up to [SCALED_LENGTH] (a 25 fps
     * copy of a 23.976 fps film runs 4% short); null past that, since a copy that much longer or
     * shorter is a different cut and the same timestamp is a different scene. Taken as it is where
     * either length is unknown.
     */
    fun carryOver(positionMs: Long, fromMs: Long, toMs: Long): Long? {
        if (fromMs <= 0 || toMs <= 0) return positionMs
        val ratio = toMs.toDouble() / fromMs
        val gap = abs(1 - ratio)
        return when {
            gap <= SAME_LENGTH -> positionMs
            gap <= SCALED_LENGTH -> (positionMs * ratio).toLong()
            else -> null
        }
    }

    /** Lengths this close are the same cut, the difference being the container or the credits. */
    const val SAME_LENGTH = 0.03

    /** Lengths this close are the same cut at another frame rate. */
    const val SCALED_LENGTH = 0.05

    /**
     * The languages a copy's name announces, as track codes ("ta", "hi"): the audio its uploader
     * named. Empty for a name that names none, which is most of them.
     */
    fun languagesOf(item: MediaItem): Set<String> = languagesIn(item.fileName.ifBlank { item.title })

    /** [languagesOf] for a bare name. */
    fun languagesIn(name: String): Set<String> =
        name.lowercase().split(NOT_WORD).mapNotNullTo(LinkedHashSet()) { LANGUAGES[it] }

    /**
     * The picture a copy is, as a badge names it ("4K", "1080p"), read as the copies list reads it
     * ([heightOf]: the name's marker first, then the size Telegram states), so a video's facts line
     * and the copy named under Play never give one file two qualities (a "720p" badge over
     * "1080p  ·  HEVC" on a TV, 2026-10-23). Null when neither says.
     */
    fun qualityLabel(item: MediaItem): String? = heightOf(item)?.let { if (it >= 2160) "4K" else "${it}p" }

    /**
     * The picture's height a copy was made at, 2160 for 4K: from the name where it says ("1080p",
     * "4K"), else from the size Telegram states, the shorter side. Null when neither says.
     */
    fun heightOf(item: MediaItem): Int? {
        val name = item.fileName.ifBlank { item.title }
        val marked = EpisodeCopies.markers(name).firstNotNullOfOrNull { it.toIntOrNull() }
        if (marked != null) return marked
        if (item.width > 0 && item.height > 0) return snap(minOf(item.width, item.height))
        // "HDRip - 1080.mkv": the height with its "p" left off, which the release reader allows
        // where the rest of the name is release words.
        return when (MediaText.qualityTags(name).firstOrNull()) {
            "4K" -> 2160
            "1080p" -> 1080
            "720p" -> 720
            else -> null
        }
    }

    /**
     * Which of [copies] to play without asking, in this order:
     * 1. the one the viewer chose for this video before ([chosen], an [MediaItem.id]);
     * 2. one already on this device, a download before a cached copy, so nothing is fetched;
     * 3. the one the viewer started ([started]), so a resume lands in the same file;
     * 4. among the rest, those in the viewer's audio [language], where any copy names one;
     * 5. the one nearest the quality the viewer usually plays ([habit]), lower before higher on a
     *    tie, so someone who watches 1080p is not handed a 4K file;
     * 6. the newest.
     *
     * Steps 2 to 6 leave out the copies [tooSlow] says this device would decode too slowly to
     * watch ([DecodeJudge]: AV1 on a TV stick with no AV1 silicon), as long as one copy is left:
     * a smooth copy is the one played without asking. The viewer's own pick (1) is kept whatever
     * it is.
     */
    fun pick(
        copies: List<MediaItem>,
        chosen: String? = null,
        started: (MediaItem) -> Boolean = { false },
        habit: QualityHabit = QualityHabit(),
        language: String? = null,
        tooSlow: (MediaItem) -> Boolean = { false },
    ): MediaItem? {
        if (copies.size <= 1) return copies.firstOrNull()
        chosen?.let { id -> copies.firstOrNull { it.id == id } }?.let { return it }
        val smooth = copies.filterNot(tooSlow).ifEmpty { copies }
        return pickAmong(smooth, started, habit, language)
    }

    /**
     * The copy [pick] would have played if no copy were too slow here, when that is not the one it
     * plays: what the player names when it says it played a smoother copy instead. Null otherwise.
     */
    fun passedOver(
        copies: List<MediaItem>,
        chosen: String? = null,
        started: (MediaItem) -> Boolean = { false },
        habit: QualityHabit = QualityHabit(),
        language: String? = null,
        tooSlow: (MediaItem) -> Boolean = { false },
    ): MediaItem? {
        if (copies.size <= 1) return null
        if (chosen != null && copies.any { it.id == chosen }) return null
        val plain = pickAmong(copies, started, habit, language) ?: return null
        val picked = pick(copies, chosen, started, habit, language, tooSlow) ?: return null
        return plain.takeIf { it.id != picked.id && tooSlow(it) }
    }

    /**
     * The test the app sets for [pick]: whether this device would decode a copy too slowly to
     * watch, as far as its name and size say ([DecodeJudge.pictureOfName]). False until set.
     */
    @Volatile
    var tooSlowHere: (MediaItem) -> Boolean = { false }

    /**
     * Whether this device plays a copy smoothly as far as its name says: true, false (it may
     * stutter), or null when not known, for the copies lists' "Smooth on this TV" line. Null for
     * every copy until the app sets it.
     */
    @Volatile
    var playsSmoothlyHere: (MediaItem) -> Boolean? = { null }

    /** The device word those lines use: `tv`, `phone` or `computer`. */
    @Volatile
    var deviceWord: String = "phone"

    private fun pickAmong(
        copies: List<MediaItem>,
        started: (MediaItem) -> Boolean,
        habit: QualityHabit,
        language: String?,
    ): MediaItem? {
        if (copies.size <= 1) return copies.firstOrNull()
        copies.firstOrNull { it.locality == MediaItem.Locality.Downloaded }?.let { return it }
        copies.firstOrNull { it.onDevice }?.let { return it }
        copies.firstOrNull(started)?.let { return it }
        val pool = language?.let { code -> copies.filter { code in languagesOf(it) }.ifEmpty { null } } ?: copies
        val newest = pool.maxWith(compareBy<MediaItem> { it.date }.thenBy { it.messageId })
        val usual = habit.usual ?: return newest
        return pool
            .mapNotNull { copy -> heightOf(copy)?.let { copy to it } }
            .minWithOrNull(
                compareBy<Pair<MediaItem, Int>> { abs(it.second - usual) }
                    .thenBy { it.second }
                    .thenByDescending { it.first.date },
            )?.first ?: newest
    }

    /** Whether [copies] come from more than one chat, which is when each names its chat. */
    fun spansChats(copies: List<MediaItem>): Boolean = copies.distinctBy { it.chatId }.size > 1

    /** Telegram's stated sizes are rarely the round figure: 1920x800 is a 1080p release cropped wide. */
    private fun snap(shortSide: Int): Int = STEPS.firstOrNull { shortSide <= it * 1.05 } ?: shortSide

    private val STEPS = listOf(240, 360, 480, 576, 720, 1080, 1440, 2160, 4320)

    private val NOT_WORD = Regex("""[^\p{L}\p{N}]+""")

    /** The audio languages release names spell out, as [MediaName] strips them from titles. */
    private val LANGUAGES = mapOf(
        "tamil" to "ta", "tam" to "ta", "telugu" to "te", "tel" to "te", "hindi" to "hi", "hin" to "hi",
        "malayalam" to "ml", "mal" to "ml", "kannada" to "kn", "kan" to "kn", "marathi" to "mr",
        "bengali" to "bn", "punjabi" to "pa", "english" to "en", "eng" to "en", "korean" to "ko",
        "japanese" to "ja", "chinese" to "zh", "spanish" to "es", "french" to "fr", "russian" to "ru",
        "german" to "de", "italian" to "it", "turkish" to "tr", "arabic" to "ar",
    )
}

/**
 * The qualities the viewer has played, counted, for [VideoCopies.pick]: "1080 twelve times, 720
 * three times". Kept small and plain, a line of `height:count` pairs, since one setting is all it is.
 */
data class QualityHabit(val counts: Map<Int, Int> = emptyMap()) {

    /** The quality played most, the higher on a tie, or null before anything was played. */
    val usual: Int?
        get() = counts.entries.maxWithOrNull(compareBy<Map.Entry<Int, Int>> { it.value }.thenBy { it.key })?.key

    /**
     * One more play at [height]. Counts are halved once one passes [CAP], so a habit that changes
     * (a new television) wins within a few weeks rather than never.
     */
    fun noted(height: Int?): QualityHabit {
        if (height == null || height <= 0) return this
        val next = counts + (height to (counts[height] ?: 0) + 1)
        val trimmed = if (next.values.any { it > CAP }) next.mapValues { it.value / 2 }.filterValues { it > 0 } else next
        return QualityHabit(trimmed)
    }

    fun encode(): String = counts.entries.sortedBy { it.key }.joinToString(",") { "${it.key}:${it.value}" }

    companion object {
        const val CAP = 40

        fun decode(text: String?): QualityHabit = QualityHabit(
            text.orEmpty().split(',').mapNotNull { pair ->
                val (height, count) = pair.split(':').takeIf { it.size == 2 } ?: return@mapNotNull null
                val h = height.trim().toIntOrNull()?.takeIf { it > 0 } ?: return@mapNotNull null
                val c = count.trim().toIntOrNull()?.takeIf { it > 0 } ?: return@mapNotNull null
                h to c
            }.toMap(),
        )
    }
}

/** The short name a copy goes by on a button: "1080p", "4K", else its size. */
fun MediaItem.copyQuality(): String = VideoCopies.qualityLabel(this) ?: MediaText.formatSize(sizeBytes)

/**
 * The copies a page knew of, handed to the player it opens: a film's [MediaItem.versions], which
 * may span chats when they came from a search across them. The player only gets the one copy it
 * plays through its start arguments, and asking the chat again would miss the other chats.
 * Kept by every copy's [MediaItem.id], so a switch to another copy finds the same list.
 */
object CopyHandoff {
    private class Offer(val copies: List<MediaItem>, val chatTitles: Map<Long, String>)

    private val offers = concurrentMap<String, Offer>()

    /**
     * Remembers [copies], with the name of each chat they are in where known. Names offered for
     * these copies before are kept: the player re-offers what a search across chats handed it with
     * only its own chat's name.
     */
    fun offer(copies: List<MediaItem>, chatTitles: Map<Long, String> = emptyMap()) {
        if (copies.size <= 1) return
        // This lives as long as the process does: a long session starts over rather than grows.
        if (offers.size > MAX) offers.clear()
        val known = copies.mapNotNull { offers[it.id]?.chatTitles }.fold(emptyMap<Long, String>()) { all, more -> all + more }
        val offer = Offer(copies, known + chatTitles)
        copies.forEach { offers[it.id] = offer }
    }

    /** The copies offered for [item], itself included, or just [item] when none were. */
    fun copiesOf(item: MediaItem): List<MediaItem> = offers[item.id]?.copies ?: listOf(item)

    /** The chat a copy is in, by name, where the page that offered it said. */
    fun chatTitleOf(item: MediaItem): String? = offers[item.id]?.chatTitles?.get(item.chatId)

    private const val MAX = 400
}
