package com.tmplayer.data

/**
 * How many videos a chat holds, for the picker's rows and its Recommended section (HX27).
 *
 * [Unknown] is not zero: a chat Telegram would not count for us says "videos" with no number and
 * offers another try, rather than claiming it is empty. That matters because the number decides
 * what is recommended, and a chat written off as empty would never be offered.
 *
 * Pure on purpose: the apps count through TDLib ([ChatVideoCount]) and the web app through its own
 * Telegram client, and both answer in this shape so one rule decides what a row says.
 */
sealed interface ChatCount {
    /** Telegram did not answer, or not in time. Never cached, never shown as a number. */
    data object Unknown : ChatCount

    /**
     * [videos] videos, as Telegram counts them. Video files sent as documents are not among them,
     * which is why the row says "about": the scan that reads file names finds those later.
     */
    data class Known(val videos: Int) : ChatCount
}

/**
 * What one count call came back with. The failure keeps Telegram's own words rather than a tidied
 * message, because the flood wait is a number inside them ([Failures.floodWaitSeconds]).
 */
sealed interface CountAnswer {
    /** The figure as it was given, which may be -1 for "the database has not counted this chat". */
    data class Counted(val count: Int) : CountAnswer

    data class Failed(val message: String?) : CountAnswer
}

/** The one call the counter makes, as an interface so a test can answer without Telegram. */
interface ChatCountCalls {
    /**
     * The chat's video count. [localOnly] true asks the client's own database, which answers at
     * once and may say -1 for "I do not know"; false asks the server.
     */
    suspend fun videoCount(chatId: Long, localOnly: Boolean): CountAnswer
}
