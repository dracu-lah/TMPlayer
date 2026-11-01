package com.tmplayer.data

/**
 * Which rows Home shows (users, 2026-10-27: turn off Continue watching or Recently added). Every
 * switch is on by default, which is Home as it was.
 *
 * A row that is off is not built, so nothing is asked of Telegram for it: Recently added's search
 * across chats and its chats' reads only run while it or the carousel is on ([needsRecent]), and a
 * starred chat is read for a row of its own only while [starredRows] is on.
 *
 * Which chats Recently added and the carousel read is not a Home setting: it is the chats the
 * viewer added ([AddedChats], 2026-11-01). Once they have answered the picker, the row reads those
 * chats one by one ([chosenChats]); before that it is Home's own pick ([HomeRows.homeChats]). Home
 * once had a "Chosen chats" list of its own here, which repeated the picker and is gone.
 *
 * Watch later and New episodes have no switch: Home has not shown them since it fills itself
 * (2026-10-23), they live on the History page.
 */
data class HomeLayout(
    val carousel: Boolean = true,
    val continueWatching: Boolean = true,
    val recentlyAdded: Boolean = true,
    val starredRows: Boolean = true,
) {
    /** Whether Recently added's videos are wanted at all: for its row or for the carousel. */
    val needsRecent: Boolean get() = carousel || recentlyAdded

    /** Every row off: Home says so and offers its settings, rather than standing blank. */
    val allOff: Boolean get() = !carousel && !continueWatching && !recentlyAdded && !starredRows

    companion object {
        /**
         * Added chats Home reads for Recently added, the first in the chat list's order (its
         * recency). An account that added 3,000 chats would otherwise put 3,000 chats into every
         * build of Home; past these, an added chat still counts through the search across chats,
         * which costs nothing per chat.
         */
        const val CHOSEN_MAX = 50

        /** From the stored values, each of which may be missing; a value that does not parse is the default. */
        fun of(
            carousel: Boolean?,
            continueWatching: Boolean?,
            recentlyAdded: Boolean?,
            starredRows: Boolean?,
        ): HomeLayout = HomeLayout(
            carousel = carousel ?: true,
            continueWatching = continueWatching ?: true,
            recentlyAdded = recentlyAdded ?: true,
            starredRows = starredRows ?: true,
        )

        /**
         * The added chats ([chosen], [AddedChats.ids]) Home reads for Recently added, in [chats]'
         * order, at most [CHOSEN_MAX]. A chat that was left or deleted, or kept out by the kids
         * lock, is not in [chats], so it is skipped without a word.
         */
        fun chosenChats(chats: List<ChatSummary>, chosen: Set<Long>): List<ChatSummary> =
            if (chosen.isEmpty()) emptyList() else chats.asSequence().filter { it.id in chosen }.distinctBy { it.id }.take(CHOSEN_MAX).toList()
    }
}
