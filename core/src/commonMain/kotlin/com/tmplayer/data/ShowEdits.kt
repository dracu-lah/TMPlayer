package com.tmplayer.data

/**
 * A show's Mark as watched and Mark as unwatched (HX20), the same on the phone, the television and
 * the desktop: one [ShowMarks.Choice], written in one edit of each store however many episodes it
 * covers.
 *
 * Marking watched forgets the saved places of those episodes, as marking one video does, so the
 * show leaves Continue watching; marking unwatched takes the marks and the places both, so the
 * show starts again from its first episode rather than from one still part way.
 */
object ShowEdits {

    /**
     * Applies [choice]. [pick] is the copy of an episode the list would play ([ShowMarks.toMark]),
     * [chatTitle] the show's chat for the Watched list, [now] the time the marks get. Returns how
     * many episodes changed.
     */
    suspend fun apply(
        settings: SettingsStore,
        watched: WatchedStore,
        choice: ShowMarks.Choice,
        chatTitle: String,
        now: Long,
        pick: (SeriesEpisode) -> MediaItem = { it.item },
    ): Int {
        if (choice.episodes.isEmpty()) return 0
        if (choice.watched) {
            val items = ShowMarks.toMark(choice.episodes, pick)
            // A moment apart in watching order, so the Watched list keeps the episodes in it.
            val records = items.mapIndexed { index, item ->
                WatchedRecord.of(item, chatTitle, now - (items.size - 1 - index), manual = true)
            }
            watched.markAllWatched(records, ShowMarks.copiesOf(choice.episodes, items))
            val ids = ShowMarks.toUnmark(choice.episodes).map { ProgressKeys.of(it.chatId, it.messageId) }
            val resume = settings.takeResume(ids)
            HistoryEdits.pushLater(settings, resume.isNotEmpty())
        } else {
            val ids = ShowMarks.toUnmark(choice.episodes).map { ProgressKeys.of(it.chatId, it.messageId) }
            watched.takeMarks(ids)
            val resume = settings.takeResume(ids)
            HistoryEdits.pushLater(settings, resume.isNotEmpty())
        }
        return choice.count
    }
}
