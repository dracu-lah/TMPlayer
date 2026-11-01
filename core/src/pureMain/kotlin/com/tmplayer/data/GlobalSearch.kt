package com.tmplayer.data


import com.tmplayer.platform.currentYear

/**
 * A chat as a search result draws it: enough for an avatar and a name, read from `getChat` as the
 * page comes in, so a video from a chat that is not in the loaded list still says where it is.
 */
@androidx.compose.runtime.Immutable
data class ChatBrief(
    val id: Long,
    val title: String,
    val kind: ChatKind,
    val photoFileId: Int = 0,
    val miniThumbnail: ByteArray? = null,
) {
    override fun equals(other: Any?): Boolean =
        other is ChatBrief && id == other.id && title == other.title && kind == other.kind &&
            photoFileId == other.photoFileId && miniThumbnail.contentEquals(other.miniThumbnail)

    override fun hashCode(): Int = (id.hashCode() * 31 + title.hashCode()) * 31 + photoFileId

    companion object {
        fun of(chat: ChatSummary) = ChatBrief(chat.id, chat.title, chat.kind, chat.photoFileId, chat.miniThumbnail)
    }
}

/** Which kind of chat Telegram is asked to search: its own chat type filter, so a server side choice. */
enum class SearchChatType { All, Channels, Groups, People }

/**
 * The picture size a result must have a copy at. Read off each copy as the detail panel reads it
 * ([MediaFacts]), so it is decided here, after Telegram has answered.
 */
enum class SearchQuality(val tag: String?) {
    Any(null),
    UHD("4K"),
    FullHd("1080p"),
    Hd("720p"),
}

/** The order of the results. Only [BestMatch] is Telegram's question; the rest reorder what came back. */
enum class SearchSort { BestMatch, Newest, BestQuality, Largest }

/** The filter chips over the results. The defaults are what a search with no chip touched asks. */
data class SearchFilters(
    val chatType: SearchChatType = SearchChatType.All,
    val quality: SearchQuality = SearchQuality.Any,
    /** Leaves out the copies under [SizeFilter.DEFAULT_MIN]: trailers, samples, clips. */
    val hideSmall: Boolean = false,
    /** On by default: somebody looking for a video does not care which list its chat was filed in. */
    val includeArchived: Boolean = true,
) {
    val isDefault: Boolean get() = this == SearchFilters()

    /** What changes the question put to Telegram, as opposed to what only screens its answer. */
    val serverSide: Pair<SearchChatType, Boolean> get() = chatType to includeArchived
}

/** A client side filter that left nothing, named so the empty state can say which and offer to clear it. */
enum class HidingFilter { Quality, SmallClips, ChatType, Archived }

/**
 * What was typed, read: the words to send Telegram, and a quality that acts as a filter.
 *
 * "1080p" alone is a filter over whatever Telegram finds for it, not a title, and among other
 * words it leaves the words to search ("night train 1080p" asks for "night train" and keeps the
 * 1080p copies). A year is never only a filter: years are titles ("1917", "2012", "1984") and in
 * titles ("Blade Runner 2049"), so a year alone is searched as text, the titles holding it ranked
 * first and the films of that year after them ([year]).
 */
data class SearchQuery(
    /** The cleaned query, as typed but tidied: what the field and the history show. */
    val text: String,
    /** What Telegram is asked. Empty when there is nothing to ask. */
    val server: String,
    /** The query is one year and nothing else: films of that year rank after titles naming it. */
    val year: Int? = null,
    val quality: SearchQuality = SearchQuality.Any,
) {
    /** A quality and nothing else: results are filtered by it and not ranked by title. */
    val filterOnly: Boolean get() = quality != SearchQuality.Any && titleWords.isEmpty()

    /** The words a title is ranked against: the text without a quality marker. */
    val titleWords: List<String>
        get() = Fuzzy.tokens(text).filter { qualityOfToken(it) == null }

    val isEmpty: Boolean get() = server.isEmpty()

    companion object {
        /**
         * The quality a word names. Not "hd": it is in "HDRip", "HDTV" and "HDR", and as a word on
         * its own says nothing about the picture's size.
         */
        internal fun qualityOfToken(token: String): SearchQuality? = when (token.lowercase()) {
            "4k", "2160p", "uhd" -> SearchQuality.UHD
            "1080p", "1080i" -> SearchQuality.FullHd
            "720p" -> SearchQuality.Hd
            else -> null
        }
    }
}

/**
 * What decides that two search results are one video, which copy a card leads with, and what is
 * an extra (a trailer, a sample) rather than the video.
 *
 * An interface because the same answers are wanted wherever copies are folded (a chat's grid, the
 * detail page's copies list), and the shared implementation of them lives with that work: the
 * search takes whichever it is given. [Default] is the cautious reading this search uses until
 * then: the same file, or the same name with a year (or the same episode) and lengths that agree.
 */
interface SearchIdentity {
    /** A cheap first cut: only results under the same bucket are compared with [sameVideo]. Null for none. */
    fun bucket(item: MediaItem): String? = SeriesShelf.sameVideoKey(item)

    /** Whether [a] and [b], already in one [bucket], are copies of one video. */
    fun sameVideo(a: MediaItem, b: MediaItem): Boolean

    /** A trailer, a teaser, a sample or a short clip, listed apart from the video it belongs to. */
    fun isExtra(item: MediaItem): Boolean

    /** The copies of one video, the one to lead with first. */
    fun copyOrder(copies: List<MediaItem>): List<MediaItem>

    /**
     * The film or show [item] was matched to online, when that is already known ([SameTitle.onlineKey]):
     * two results with one match are one card, whatever their file names say.
     */
    fun onlineKey(item: MediaItem): String? = SameTitle.onlineKey(item)

    companion object {
        val Default: SearchIdentity = object : SearchIdentity {
            override fun sameVideo(a: MediaItem, b: MediaItem): Boolean {
                if (a.fileId != 0 && a.fileId == b.fileId) return true
                // A name alone is not enough evidence across chats: "Lecture 1080p" in two groups
                // is two lectures. A year (or an episode number) has to say it, and where both
                // lengths are known they have to agree.
                val name = SeriesShelf.episodeOf(a)
                if (name.year == null && name.episode == null) return false
                // The rest is the browse pages' own test ([SameVideo]): lengths, the player's
                // learned lengths, and the viewer's "Not the same video".
                return SameVideo.same(a, b)
            }

            override fun isExtra(item: MediaItem): Boolean =
                item.extra != null || item.parsedName.extra != null || GlobalSearch.isExtra(item)

            override fun copyOrder(copies: List<MediaItem>): List<MediaItem> = GlobalSearch.copyOrder(copies)
        }
    }
}

/**
 * One result card: every copy of one video the search found, across all the chats it was posted
 * in, or one show's episodes across every chat they are in (user, 2026-10-22: a show is listed
 * once, never once per chat).
 *
 * [copies] are in [GlobalSearch.copyOrder], so the first is the one a tap plays and the card shows.
 */
@androidx.compose.runtime.Immutable
data class ResultGroup(
    val key: String,
    val copies: List<MediaItem>,
    /** The show's name, for a group of episodes of one show; null for a video. */
    val show: String? = null,
    /** A trailer, a sample or a short clip of something longer: listed, never folded into the video. */
    val extra: Boolean = false,
) {
    val lead: MediaItem get() = copies.first()

    /**
     * How many files the copies are, the same file posted twice counted once, as the video's page
     * counts its versions ([VideoCopies.distinctFiles]): the card said "4 copies" over a page of
     * "3 versions" when one file had been forwarded to a second chat.
     */
    val fileCount: Int get() = VideoCopies.distinctFiles(copies).size

    /** The chats the copies are in, the lead's first, each once. */
    val chatIds: List<Long> get() = copies.map { it.chatId }.distinct()

    /** A show with more than one episode found, which opens the series page rather than a video. */
    val isShow: Boolean get() = show != null && episodeCount > 1

    /** How many different episodes a show group holds. */
    val episodeCount: Int get() = copies.mapNotNull { SeriesShelf.sameVideoKey(it) }.distinct().size.coerceAtLeast(1)

    /** The best picture any copy has, as [MediaFacts] names it, or null when none says. */
    val bestQuality: String? get() = copies.mapNotNull(GlobalSearch::qualityOf).maxByOrNull(GlobalSearch::qualityRank)

    /** The smallest and the largest copy, in bytes, leaving out copies of unknown size. */
    val sizeRange: LongRange? get() {
        val sizes = copies.map { it.sizeBytes }.filter { it > 0 }
        return if (sizes.isEmpty()) null else sizes.min()..sizes.max()
    }

    val newest: Int get() = copies.maxOf { it.date }

    /** The lead with every copy as its [MediaItem.versions], for the detail page's copies list. */
    fun asItem(): MediaItem = if (copies.size < 2) lead else lead.copy(versions = copies).keptReads(lead)
}

/**
 * The pure half of the search across every chat (CP73): reading the query, turning pages of
 * Telegram's answer into one card per video, ranking and sorting them, the local matches that show
 * before Telegram answers, and the filters.
 *
 * Telegram's own search matches whole words, and a file name like `Harbour_Notes_2024.mkv` is one
 * word to it, since an underscore joins words rather than parting them: a search for "harbour"
 * can miss it. Two things answer that. A query of several words is tried again with underscores
 * ([variants]), and everything the app has already seen (chat listings, Continue watching,
 * downloads) is matched here with [Fuzzy], which treats dots, underscores and dashes as spaces and
 * forgives a typo, and merged in by message id ([localVideos]).
 *
 * Kept free of TDLib and of Compose so the whole of it runs in plain JVM tests.
 */
object GlobalSearch {

    /** Chats offered above the results before "Show all". */
    const val CHAT_PREVIEW = 3

    /** The first answer keeps asking until it has this many cards, so it never opens on two. */
    const val FIRST_GROUPS = 12

    /** ... or until it has asked this many times. */
    const val FIRST_PAGES = 3

    /** No list grows past this: a search that matched a thousand files was not specific enough. */
    const val MAX_GROUPS = 300

    /** Recent searches listed on an empty search. */
    const val RECENT_SHOWN = 8

    /** The longest query kept: longer than any real title, short enough to show in a row. */
    const val MAX_QUERY = 80

    /** Under ten minutes, with the same name as a longer copy, is a trailer or a clip of it. */
    const val SHORT_SECONDS = 600

    /**
     * The query tidied: trimmed, single spaced, a pasted link or a leading @handle taken off
     * (a handle alone keeps its name), and at most [MAX_QUERY] characters.
     */
    fun clean(raw: String): String {
        var words = raw.trim().split(WHITESPACE).filter { it.isNotEmpty() }
        words = words.filterNot { URL.matches(it) }
        if (words.size > 1 && words.first().startsWith("@")) words = words.drop(1)
        return words.joinToString(" ") { it.removePrefix("@") }.trim().take(MAX_QUERY).trim()
    }

    /**
     * Whether [query] is worth asking Telegram: two characters, or one in a script that writes a
     * word in a single character (Chinese, Japanese, Korean).
     */
    fun searchable(query: String): Boolean {
        val text = query.trim()
        if (text.isEmpty()) return false
        return text.length >= 2 || text.any(::isCjk)
    }

    /** [raw] read into what to ask and what to filter on; see [SearchQuery]. */
    fun parse(raw: String, maxYear: Int = currentYear() + 1): SearchQuery {
        val text = clean(raw)
        val tokens = text.split(' ').filter { it.isNotEmpty() }
        val qualities = tokens.mapNotNull { SearchQuery.qualityOfToken(it) }
        val rest = tokens.filter { SearchQuery.qualityOfToken(it) == null }
        val yearAlone = rest.singleOrNull()?.toIntOrNull()?.takeIf { it in 1900..maxYear }
        val server = when {
            !searchable(text) -> ""
            rest.isEmpty() -> tokens.first()
            else -> rest.joinToString(" ")
        }
        return SearchQuery(
            text = text,
            server = server.takeIf { searchable(it) }.orEmpty(),
            year = yearAlone,
            quality = qualities.firstOrNull() ?: SearchQuality.Any,
        )
    }

    /**
     * Other spellings to ask Telegram when the query found little: its words joined by underscores
     * and by dots, the way release names are written. Nothing for one word.
     */
    fun variants(server: String): List<String> {
        val words = Fuzzy.tokens(server)
        if (words.size < 2) return emptyList()
        return listOf(words.joinToString("_"), words.joinToString("."))
    }

    /**
     * [page] folded into [existing]: a copy of a video already on screen joins its card, which
     * stays where it is. With the best match the new cards are ranked among themselves and go
     * after, so paging never moves a card the viewer is looking at; any other order is the whole
     * list's, and is applied to all of it. At most [MAX_GROUPS] cards. Every video once, by
     * message id, whichever source found it.
     *
     * An episode goes under its show in its own chat, since a show opens on that chat's series
     * page. Anything else joins a card only when [identity] says it is the same video as a copy
     * there; an extra only ever joins other extras.
     */
    fun merge(
        existing: List<ResultGroup>,
        page: List<MediaItem>,
        query: SearchQuery,
        sort: SearchSort = SearchSort.BestMatch,
        identity: SearchIdentity = SearchIdentity.Default,
    ): List<ResultGroup> {
        if (page.isEmpty()) return existing
        val seen = existing.flatMapTo(HashSet()) { group -> group.copies.map { it.id } }
        val fresh = page.filter { seen.add(it.id) }
        if (fresh.isEmpty()) return existing

        class Building(val key: String, val copies: MutableList<MediaItem>, val show: String?, val extra: Boolean, val old: ResultGroup?)
        val building = existing.map { Building(it.key, it.copies.toMutableList(), it.show, it.extra, it) }.toMutableList()
        val byKey = building.associateByTo(HashMap()) { it.key }
        val buckets = HashMap<String, MutableList<Building>>()
        // One film or show by its online match, and one film by its name and year ([SameTitle]):
        // what the file names alone do not tie together.
        val byOnline = HashMap<String, Building>()
        val byFilm = HashMap<String, Building>()
        fun bucketOf(item: MediaItem, extra: Boolean) = identity.bucket(item)?.let { (if (extra) "x|" else "v|") + it }
        fun note(group: Building, item: MediaItem) {
            if (group.extra) return
            // A show's card is where its match leads, ahead of a lone file matched to it.
            val online = identity.onlineKey(item)
            if (online != null) {
                if (group.show != null) byOnline[online] = group else byOnline.getOrPut(online) { group }
            }
            val film = if (group.show == null) SameTitle.filmKey(item) else null
            if (film != null) byFilm.getOrPut(film) { group }
        }
        building.filter { it.show == null }.forEach { group -> bucketOf(group.copies.first(), group.extra)?.let { buckets.getOrPut(it) { mutableListOf() } += group } }
        building.forEach { group -> group.copies.forEach { note(group, it) } }

        /** The show [item] belongs to without being an episode of it: a season pack, or a file matched to it online. */
        fun showFor(item: MediaItem): Building? {
            identity.onlineKey(item)?.let { key -> byOnline[key]?.takeIf { it.show != null }?.let { return it } }
            val name = SeriesShelf.episodeOf(item)
            if (!name.isSeries && name.year != null) return null
            val title = SeriesShelf.keyOf(name.title).ifEmpty { return null }
            return byKey["show:$title"]
        }

        // Episodes first, so a season pack or a file matched to the show finds the show's card.
        val (episodes, others) = fresh.partition { !identity.isExtra(it) && showOf(it) != null }
        for (item in episodes + others) {
            val extra = identity.isExtra(item)
            val show = if (extra) null else showOf(item)
            if (show != null) {
                // One card per show, whichever chats its episodes are in.
                val key = "show:${SeriesShelf.keyOf(show)}"
                val group = byKey.getOrPut(key) { Building(key, mutableListOf(), show, false, null).also { building += it } }
                group.copies += item
                note(group, item)
                continue
            }
            if (!extra) {
                val owner = showFor(item)
                    ?: identity.onlineKey(item)?.let { byOnline[it] }
                    ?: SameTitle.filmKey(item)?.let { byFilm[it] }?.takeIf { group -> group.copies.any { SameTitle.sameLength(it, item) } }
                if (owner != null) {
                    owner.copies += item
                    note(owner, item)
                    continue
                }
            }
            val bucket = bucketOf(item, extra)
            val match = bucket?.let { buckets[it] }?.firstOrNull { group -> group.copies.any { identity.sameVideo(it, item) } }
            if (match != null) {
                match.copies += item
                note(match, item)
            } else {
                val key = (if (extra) EXTRA else "video:") + item.id
                val group = Building(key, mutableListOf(item), null, extra, null)
                building += group
                byKey[key] = group
                if (bucket != null) buckets.getOrPut(bucket) { mutableListOf() } += group
                note(group, item)
            }
        }

        // A video card from an earlier page that turns out to be the show's (matched online, or a
        // season pack under its name) joins the show's card now that there is one.
        val gone = HashSet<Building>()
        for (group in building) {
            if (group.show != null || group.extra) continue
            val owner = group.copies.firstNotNullOfOrNull { showFor(it) } ?: continue
            owner.copies += group.copies
            gone += group
        }
        building.removeAll(gone)

        val built = building.map { group ->
            val old = group.old
            if (old != null && group.copies.size == old.copies.size) old else ResultGroup(group.key, identity.copyOrder(group.copies), group.show, group.extra)
        }
        val kept = built.filterIndexed { index, _ -> building[index].old != null }
        val appended = built.filterIndexed { index, _ -> building[index].old == null }
        val all = if (sort == SearchSort.BestMatch) kept + rank(appended, query) else order(kept + appended, query, sort)
        return all.take(MAX_GROUPS)
    }

    /** The show [item] is an episode of, by its own name, or null for anything that is not an episode. */
    fun showOf(item: MediaItem): String? {
        val name = SeriesShelf.episodeOf(item)
        if (name.episode == null) return null
        return name.title.takeIf { SeriesShelf.keyOf(it).isNotEmpty() }
    }

    /**
     * A trailer, a teaser, a sample, or a film's name on something under [SHORT_SECONDS]: what a
     * channel posts beside the film, not a copy of it. An episode is never one by length alone,
     * since short episodes are common.
     */
    fun isExtra(item: MediaItem): Boolean {
        if (EXTRA_WORDS.containsMatchIn(Fuzzy.normalize(item.nameForParsing))) return true
        val name = SeriesShelf.episodeOf(item)
        if (name.episode != null) return false
        return item.durationSec in 1 until SHORT_SECONDS && SeriesShelf.sameVideoKey(item) != null && name.year != null
    }

    /** [groups] in [sort]'s order: see [rank] for the best match. */
    fun order(groups: List<ResultGroup>, query: SearchQuery, sort: SearchSort): List<ResultGroup> {
        if (sort == SearchSort.BestMatch) return rank(groups, query)
        // The sort keys read file names, so each card's are worked out once, not per comparison.
        class Keyed(val group: ResultGroup, val quality: Int, val largest: Long, val newest: Int)
        val keyed = groups.map { Keyed(it, it.bestQuality?.let(::qualityRank) ?: 0, it.sizeRange?.last ?: 0L, it.newest) }
        val comparator = when (sort) {
            SearchSort.Newest -> compareBy<Keyed> { it.group.extra }.thenByDescending { it.newest }
            SearchSort.BestQuality -> compareByDescending<Keyed> { it.quality }.thenBy { it.group.extra }.thenByDescending { it.newest }
            else -> compareByDescending<Keyed> { it.largest }.thenByDescending { it.newest }
        }
        return keyed.sortedWith(comparator).map { it.group }
    }

    /**
     * Best first: an exact title, then one that starts with the query, then one holding every
     * word, then a file name that matches, and a mention in a caption last. For a query that is a
     * year, the films of that year come next. Extras go after the video they are extras of. Ties
     * go to more copies (a widely posted video is the likelier one), then a better picture, then
     * the newest. A query that is only a quality has no title to rank by, so it is newest first.
     *
     * Each card is scored once before sorting: the score reads file names, and doing that inside
     * the comparator would read each one a dozen times on a stick that has no time for it.
     */
    fun rank(groups: List<ResultGroup>, query: SearchQuery): List<ResultGroup> {
        class Scored(val group: ResultGroup, val relevance: Int, val year: Boolean, val quality: Int)
        return groups.map { group ->
            Scored(
                group,
                if (query.filterOnly) 0 else relevance(group, query),
                query.year != null && SeriesShelf.episodeOf(group.lead).year == query.year,
                group.bestQuality?.let(::qualityRank) ?: 0,
            )
        }.sortedWith(
            compareByDescending<Scored> { it.relevance }
                .thenByDescending { it.year }
                .thenBy { it.group.extra }
                .thenByDescending { it.group.copies.size }
                .thenByDescending { it.quality }
                .thenByDescending { it.group.newest },
        ).map { it.group }
    }

    /** 5 exact title, 4 prefix, 3 every word in the title, 2 the file name, 1 anything else, 0 nothing. */
    fun relevance(group: ResultGroup, query: SearchQuery): Int {
        val wanted = query.titleWords
        if (wanted.isEmpty()) return 0
        val joined = wanted.joinToString(" ")
        // The parsed name, without its year or tags, so "Night.Train.2019" is exactly "night train".
        val parsed = Fuzzy.normalize(group.show ?: SeriesShelf.episodeOf(group.lead).title.ifBlank { titleOf(group) })
        val title = Fuzzy.normalize(titleOf(group))
        return when {
            parsed == joined || title == joined -> 5
            parsed.startsWith(joined) || title.startsWith(joined) -> 4
            wanted.all { it in title.split(' ') } -> 3
            group.copies.any { Fuzzy.score(it.fileName.ifBlank { it.title }, joined) > 0 } -> 2
            group.copies.any { Fuzzy.score(it.searchText, joined) > 0 } -> 1
            else -> 0
        }
    }

    /** The name a card goes by: the show's, or the tile title, never a shortened poster title. */
    fun titleOf(group: ResultGroup): String = group.show?.takeIf { group.isShow } ?: SeriesShelf.tileTitle(group.asItem())

    /** On this device first, then the best picture, then the newest. */
    fun copyOrder(copies: List<MediaItem>): List<MediaItem> {
        if (copies.size < 2) return copies.toList()
        // Each copy's quality read once, not on every comparison of the sort.
        return copies.map { it to (qualityOf(it)?.let(::qualityRank) ?: 0) }
            .sortedWith(
                compareByDescending<Pair<MediaItem, Int>> { it.first.onDevice }
                    .thenByDescending { it.second }
                    .thenByDescending { it.first.date }
                    .thenByDescending { it.first.messageId },
            )
            .map { it.first }
    }

    /** "4K", "1080p", "720p", "SD", or null when neither the name nor the stated size says. */
    fun qualityOf(item: MediaItem): String? =
        // The file name's tags are read once per item ([MediaItem.qualityTags]).
        (if (item.fileName.isBlank()) MediaText.qualityTags(item.title) else item.qualityTags).firstOrNull()
            ?: MediaFacts.resolutionOf(item.width, item.height)

    fun qualityRank(quality: String): Int = when (quality.uppercase()) {
        "4K" -> 4
        "1080P" -> 3
        "720P" -> 2
        "SD" -> 1
        else -> 0
    }

    /**
     * [groups] screened by the client side filters and by a quality the query named: each card
     * keeps only its copies that pass, and a card with none left goes. Order is kept, so a filter
     * changed while results are on screen never asks Telegram again.
     */
    fun filter(groups: List<ResultGroup>, filters: SearchFilters, query: SearchQuery? = null): List<ResultGroup> {
        val quality = filters.quality.takeIf { it != SearchQuality.Any } ?: query?.quality ?: SearchQuality.Any
        if (quality == SearchQuality.Any && !filters.hideSmall) return groups
        return groups.mapNotNull { group ->
            val kept = group.copies.filter { copy ->
                (quality.tag == null || qualityOf(copy) == quality.tag) &&
                    (!filters.hideSmall || SizeFilter.matches(copy.sizeBytes, SizeFilter.DEFAULT_MIN, SizeFilter.DEFAULT_MAX))
            }
            when {
                kept.isEmpty() -> null
                kept.size == group.copies.size -> group
                else -> group.copy(copies = kept)
            }
        }
    }

    /**
     * Which of the filters in force each, on its own, empties [groups], for an empty state that
     * says what hides the results and clears it in one tap. Empty when nothing was found at all.
     */
    fun hiddenBy(groups: List<ResultGroup>, filters: SearchFilters, query: SearchQuery): List<HidingFilter> {
        if (groups.isEmpty()) {
            return buildList {
                if (filters.chatType != SearchChatType.All) add(HidingFilter.ChatType)
                if (!filters.includeArchived) add(HidingFilter.Archived)
            }
        }
        return buildList {
            if (filters.quality != SearchQuality.Any && filter(groups, SearchFilters(quality = filters.quality)).isEmpty()) add(HidingFilter.Quality)
            if (filters.hideSmall && filter(groups, SearchFilters(hideSmall = true)).isEmpty()) add(HidingFilter.SmallClips)
            if (isEmpty() && filters.quality != SearchQuality.Any && filters.hideSmall) {
                add(HidingFilter.Quality)
                add(HidingFilter.SmallClips)
            }
            if (query.quality != SearchQuality.Any && filter(groups, SearchFilters(), query).isEmpty()) add(HidingFilter.Quality)
        }.distinct()
    }

    /** The chats whose names answer [query], best first, starred chats ahead of the rest on a tie. */
    fun localChats(chats: List<ChatSummary>, query: String, favourites: Set<Long> = emptySet()): List<ChatSummary> {
        val text = clean(query)
        if (text.isBlank()) return emptyList()
        val scored = chats.map { it to Fuzzy.score(it.title, text) }.filter { it.second > 0 }
        return scored.sortedWith(
            compareByDescending<Pair<ChatSummary, Int>> { it.second }.thenByDescending { it.first.id in favourites },
        ).map { it.first }
    }

    /**
     * Whether [item] answers [query] by anything the app knows about it: the tile title, the file
     * name, the caption, and [names] (the online title, in its own language and in English).
     * [Fuzzy] folds case, accents, dots, underscores and dashes, and forgives a typo.
     */
    fun matchesLocally(item: MediaItem, query: SearchQuery, names: List<String> = emptyList()): Boolean {
        if (query.filterOnly) {
            val token = query.server
            return Fuzzy.score(item.searchText, token) > 0
        }
        val text = query.titleWords.joinToString(" ").ifBlank { return false }
        val fields = listOf(SeriesShelf.tileTitle(item), item.fileName, item.searchText) + names
        return fields.any { it.isNotBlank() && Fuzzy.score(it, text) > 0 }
    }

    /**
     * The videos the app already knows of that answer [query], as cards, before Telegram has said
     * anything and whatever it says: what chat listings loaded, Continue watching, downloads.
     * Found without a connection, and merged with Telegram's pages by message id, so a file
     * Telegram's word search cannot see is still found. [names] gives a video's online titles.
     */
    fun localVideos(
        known: List<MediaItem>,
        query: SearchQuery,
        allowed: Set<Long>? = null,
        names: (MediaItem) -> List<String> = { emptyList() },
    ): List<MediaItem> {
        if (query.isEmpty) return emptyList()
        return known.asSequence()
            .filter { KidsLock.allows(allowed, it.chatId) }
            .distinctBy { it.id }
            .filter { matchesLocally(it, query, names(it)) }
            .toList()
    }

    /**
     * The recent chats row of an empty search: starred chats first, then the rest in Telegram's
     * own order, which is already newest activity first.
     */
    fun recentChats(chats: List<ChatSummary>, favourites: Set<Long>, limit: Int = 12): List<ChatSummary> {
        val (starred, rest) = chats.filter { !it.isArchived }.partition { it.id in favourites }
        return (starred + rest).take(limit)
    }

    /** Whether the first answer should ask for another page before it is shown as finished. */
    fun wantsMore(groups: Int, pagesAsked: Int, done: Boolean): Boolean =
        !done && groups < FIRST_GROUPS && pagesAsked < FIRST_PAGES

    private fun isCjk(c: Char): Boolean {
        // The Unicode blocks: CJK Unified Ideographs and Extension A, Hiragana, Katakana, Hangul.
        val code = c.code
        return code in 0x4E00..0x9FFF || code in 0x3400..0x4DBF || code in 0x3040..0x309F ||
            code in 0x30A0..0x30FF || code in 0xAC00..0xD7AF
    }

    private const val EXTRA = "extra:"
    private val WHITESPACE = Regex("\\s+")
    private val URL = Regex("""^(https?://|www\.|t\.me/|telegram\.me/)\S*$""", RegexOption.IGNORE_CASE)
    private val EXTRA_WORDS = Regex("""(?<![a-z0-9])(trailer|teaser|sample|promo)(?![a-z0-9])""")
}
