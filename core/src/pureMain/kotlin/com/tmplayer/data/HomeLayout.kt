package com.tmplayer.data

/**
 * Where Recently added (and the carousel, which is drawn from it) takes its videos from.
 *
 * [Auto] is Home as it has been since 2026-10-21: the starred chats, then Saved Messages, the
 * pinned chats and the chats with the most videos ([HomeRows.homeChats]). [Chosen] is the chats
 * the viewer ticked in Settings, and only those.
 */
enum class RecentSource {
    Auto,
    Chosen,
    ;

    companion object {
        /** The stored name back to a source; anything else, a missing value included, is [Auto]. */
        fun of(raw: String?): RecentSource {
            val name = raw?.trim().orEmpty()
            return entries.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: Auto
        }
    }
}

/**
 * Which rows Home shows (users, 2026-10-27: turn off Continue watching or Recently added, and say
 * which chats feed Recently added). Every switch is on by default, which is Home as it was.
 *
 * A row that is off is not built, so nothing is asked of Telegram for it: Recently added's search
 * across chats and its chats' reads only run while it or the carousel is on ([needsRecent]), and a
 * starred chat is read for a row of its own only while [starredRows] is on.
 *
 * Watch later and New episodes have no switch: Home has not shown them since it fills itself
 * (2026-10-23), they live on the History page.
 */
data class HomeLayout(
    val carousel: Boolean = true,
    val continueWatching: Boolean = true,
    val recentlyAdded: Boolean = true,
    val starredRows: Boolean = true,
    val recentSource: RecentSource = RecentSource.Auto,
    /** The chats ticked for [RecentSource.Chosen]; kept when the source goes back to [RecentSource.Auto]. */
    val recentChats: Set<Long> = emptySet(),
) {
    /** Whether Recently added's videos are wanted at all: for its row or for the carousel. */
    val needsRecent: Boolean get() = carousel || recentlyAdded

    /** Every row off: Home says so and offers its settings, rather than standing blank. */
    val allOff: Boolean get() = !carousel && !continueWatching && !recentlyAdded && !starredRows

    val chosen: Boolean get() = recentSource == RecentSource.Chosen

    companion object {
        /**
         * Chosen chats Home reads for Recently added, the first in the chat list's order (its
         * recency). Select all on an account of 3,000 chats would otherwise put 3,000 chats into
         * every build of Home; past these, a chosen chat still counts through the search across
         * chats, which costs nothing per chat.
         */
        const val CHOSEN_MAX = 50

        /** Chosen chats kept in the setting: more than any account has, so a bad file stays small. */
        const val STORED_MAX = 10_000

        /** From the stored values, each of which may be missing; a value that does not parse is the default. */
        fun of(
            carousel: Boolean?,
            continueWatching: Boolean?,
            recentlyAdded: Boolean?,
            starredRows: Boolean?,
            source: String?,
            chats: String?,
        ): HomeLayout = HomeLayout(
            carousel = carousel ?: true,
            continueWatching = continueWatching ?: true,
            recentlyAdded = recentlyAdded ?: true,
            starredRows = starredRows ?: true,
            recentSource = RecentSource.of(source),
            recentChats = decodeChats(chats),
        )

        /** The stored ids, comma or line separated; anything that is not a chat id is skipped. */
        fun decodeChats(raw: String?): Set<Long> {
            if (raw.isNullOrBlank()) return emptySet()
            val out = LinkedHashSet<Long>()
            for (part in raw.splitToSequence(',', '\n')) {
                val id = part.trim().toLongOrNull() ?: continue
                if (id == 0L) continue
                out += id
                if (out.size >= STORED_MAX) break
            }
            return out
        }

        fun encodeChats(ids: Collection<Long>): String =
            ids.asSequence().filter { it != 0L }.distinct().take(STORED_MAX).joinToString(",")

        /**
         * The chosen chats Home reads, in [chats]' order, at most [CHOSEN_MAX]. A chat that was
         * left or deleted, hidden, or kept out by the kids lock is not in [chats], so it is
         * skipped without a word.
         */
        fun chosenChats(chats: List<ChatSummary>, chosen: Set<Long>): List<ChatSummary> =
            if (chosen.isEmpty()) emptyList() else chats.asSequence().filter { it.id in chosen }.distinctBy { it.id }.take(CHOSEN_MAX).toList()
    }
}
