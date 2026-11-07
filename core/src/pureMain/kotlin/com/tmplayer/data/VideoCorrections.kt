package com.tmplayer.data

import kotlin.concurrent.Volatile

import com.tmplayer.online.MetaKind
import com.tmplayer.online.MetaQuery
import com.tmplayer.platform.Background
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * What the viewer has told the app about one file, where its name led the app astray.
 *
 * Names are all the app has to go on, and a guess is sometimes wrong: two different films with one
 * title folded into one tile, a poster for somebody else's film, a title nobody would recognise. So
 * each of those can be put right by hand, from the video's menu, and the answer is kept on this
 * device (and in a backup), never sent anywhere.
 */
data class VideoCorrection(
    /** "Not the same video": never folded together with any other file. */
    val separate: Boolean = false,
    /** "Wrong match": no poster or overview from the providers, the file's own name instead. */
    val wrongMatch: Boolean = false,
    /** "Rename": the title to read in place of the file name, for the tile and for grouping. */
    val title: String? = null,
    /** "Choose the right poster": the TMDB title the viewer picked, followed on every screen. */
    val pick: PosterPick? = null,
) {
    val isEmpty: Boolean get() = !separate && !wrongMatch && title.isNullOrBlank() && pick == null

    /** The viewer's pick of a poster. Picking one lifts a "Wrong match", which it answers. */
    fun withPick(chosen: PosterPick?): VideoCorrection = copy(pick = chosen, wrongMatch = if (chosen != null) false else wrongMatch)

    /** "Wrong match" (or its undo). Turning a match down drops a pick: nothing is shown for it. */
    fun withWrongMatch(wrong: Boolean): VideoCorrection = copy(wrongMatch = wrong, pick = if (wrong) null else pick)

    /** Back to automatic: no pick and no "Wrong match"; a rename and "Not the same video" stay. */
    fun resetMatch(): VideoCorrection = copy(pick = null, wrongMatch = false)

    /**
     * One line per correction, so the stored value reads as what it means: `separate`, `wrong`,
     * `tmdb=film:123`, `title=Night Train (2019)`. A title is the last line and keeps any
     * characters it has.
     */
    fun encode(): String = buildList {
        if (separate) add(SEPARATE)
        if (wrongMatch) add(WRONG)
        pick?.let { add("$TMDB${if (it.show) SHOW else FILM}:${it.tmdbId}") }
        title?.trim()?.takeIf { it.isNotEmpty() }?.let { add("$TITLE${it.replace('\n', ' ')}") }
    }.joinToString("\n")

    companion object {
        private const val SEPARATE = "separate"
        private const val WRONG = "wrong"
        private const val TITLE = "title="
        private const val TMDB = "tmdb="
        private const val FILM = "film"
        private const val SHOW = "show"
        private val PICK = Regex("""tmdb=(film|show):(\d{1,9})""")

        fun decode(text: String?): VideoCorrection {
            if (text.isNullOrBlank()) return VideoCorrection()
            val lines = text.lines()
            return VideoCorrection(
                separate = SEPARATE in lines,
                wrongMatch = WRONG in lines,
                title = lines.firstOrNull { it.startsWith(TITLE) }?.removePrefix(TITLE)?.trim()?.ifEmpty { null },
                // Anything but `tmdb=film:<id>` or `tmdb=show:<id>` is no pick, and the rest still reads.
                pick = lines.firstNotNullOfOrNull { line ->
                    val found = PICK.matchEntire(line.trim()) ?: return@firstNotNullOfOrNull null
                    val id = found.groupValues[2].toIntOrNull()?.takeIf { it > 0 } ?: return@firstNotNullOfOrNull null
                    PosterPick(id, show = found.groupValues[1] == SHOW)
                },
            )
        }
    }
}

/** A TMDB title the viewer chose for a video: its id, and whether it is a show (else a film). */
data class PosterPick(val tmdbId: Int, val show: Boolean = false) {
    val kind: MetaKind get() = if (show) MetaKind.Show else MetaKind.Film

    /** The `{tmdb-123}` question this pick forces: the title and year of [base] kept where it has them. */
    fun forcing(base: MetaQuery?, fallbackTitle: String = ""): MetaQuery {
        val query = base ?: MetaQuery(title = fallbackTitle)
        // A film pick drops the episode code the file's name carried: a film has none.
        val season = if (show) query.season else null
        val episode = if (show) query.episode else null
        return query.copy(season = season, episode = episode, tmdbId = tmdbId, kind = kind, anime = false)
    }
}

/**
 * Every [VideoCorrection] on this device, by [MediaItem.id], for the code that groups and names
 * videos: [SeriesShelf] reads [current] as its default, so a correction holds on every screen that
 * arranges a chat (the grid, Home, the series pages) without each of them passing it along.
 *
 * Kept by [SettingsStore] under [PREFIX] names, one per file, which is also how a backup carries
 * them. A screen that draws an arrangement collects [state], so a correction redraws it at once.
 */
object VideoCorrections {

    /** The stored names: `fix_<chat>_<message>`. */
    const val PREFIX = "fix_"

    private val corrections = MutableStateFlow<Map<String, VideoCorrection>>(emptyMap())

    val state: StateFlow<Map<String, VideoCorrection>> = corrections.asStateFlow()

    val current: Map<String, VideoCorrection> get() = corrections.value

    @Volatile
    private var following = false

    /** Keeps [state] in step with [store], for the life of the process. Once is enough. */
    fun follow(store: CorrectionsSource) {
        if (following) return
        following = true
        Background.scope.launch { store.corrections.collect { corrections.value = it } }
    }

    /** Sets every correction at once. For tests, and for a process with no store to follow. */
    fun set(all: Map<String, VideoCorrection>) {
        corrections.value = all
    }

    /** One file's correction, after a change made here, ahead of the store's own echo. */
    internal fun put(id: String, correction: VideoCorrection) {
        corrections.value = if (correction.isEmpty) corrections.value - id else corrections.value + (id to correction)
    }

    fun of(item: MediaItem, all: Map<String, VideoCorrection> = current): VideoCorrection? = all[item.id]

    /**
     * The lookup's question for [item] ([MediaItem.metaQuery]), forced to the title the viewer
     * picked for it where they did ("Choose the right poster"); [base] otherwise. A pick holds
     * even for a file whose name asks nothing by itself.
     */
    fun queryOf(item: MediaItem, base: MetaQuery? = item.metaQuery, all: Map<String, VideoCorrection> = current): MetaQuery? {
        val correction = all[item.id]
        val pick = correction?.pick ?: return base
        return pick.forcing(base, correction.title ?: item.title)
    }

    /**
     * Other files in [pool] that stand for the same title as [item] in its chat and are not its
     * own copies: the ones "Also use it for the others" reaches. A film needs the same year, a
     * show the same name. Never a file that is an extra (a trailer, a sample).
     */
    fun sameTitleIn(item: MediaItem, pool: Collection<MediaItem>): List<MediaItem> {
        val own = filesOf(item).mapTo(HashSet()) { it.id }
        val wanted = item.parsedName
        if (wanted.titleKey.isEmpty() || wanted.extra != null) return emptyList()
        return pool.asSequence()
            .flatMap { filesOf(it).asSequence() }
            .filter { it.chatId == item.chatId && it.id !in own && it.extra == null }
            .filter { other ->
                val read = other.parsedName
                read.extra == null && read.titleKey == wanted.titleKey && read.isSeries == wanted.isSeries &&
                    (wanted.isSeries || read.year == wanted.year)
            }
            .distinctBy { it.id }
            .toList()
    }

    /** The longest title a rename keeps. */
    const val TITLE_MAX = 120

    /**
     * Every file a tile stands for: its copies and their pieces. A rename or a wrong match is
     * written to all of them, so it holds whichever copy the tile shows next time.
     */
    fun filesOf(item: MediaItem): List<MediaItem> =
        item.versions.ifEmpty { listOf(item) }.flatMap { copy -> listOf(copy) + copy.parts }.distinctBy { it.id }

    /** The title to show for a saved record of a video: the viewer's own where they gave one. */
    fun titleFor(chatId: Long, messageId: Long, fallback: String): String =
        current["$chatId:$messageId"]?.title ?: fallback

    /** The stored name for [chatId] and [messageId]'s correction. */
    fun keyName(chatId: Long, messageId: Long): String = "$PREFIX${chatId}_$messageId"

    /** The [MediaItem.id] a stored name stands for, or null for a name that is not one. */
    fun idOf(name: String): String? {
        if (!name.startsWith(PREFIX)) return null
        val parts = name.removePrefix(PREFIX).split('_')
        if (parts.size != 2) return null
        val chat = parts[0].toLongOrNull() ?: return null
        val message = parts[1].toLongOrNull() ?: return null
        return "$chat:$message"
    }
}

/** Where the corrections are stored: SettingsStore in the apps. */
interface CorrectionsSource {
    val corrections: kotlinx.coroutines.flow.Flow<Map<String, VideoCorrection>>
}
