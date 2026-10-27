package com.tmplayer.data

import kotlinx.coroutines.flow.first

/**
 * What one Remove from history (or Clear history, or Remove from Watch later) took, kept so
 * Undo puts exactly that back: the places as stored, the watched ticks and the Watch later lines.
 * [count] is how many cards went, for the line that says so.
 */
class HistoryUndo internal constructor(
    internal val resume: Map<String, Any>,
    internal val marks: List<WatchedRecord>,
    internal val later: List<WatchLaterRecord>,
    val count: Int,
) {
    /** Nothing was there to take, as when the same card is removed twice. */
    val isEmpty: Boolean get() = resume.isEmpty() && marks.isEmpty() && later.isEmpty()
}

/**
 * History's one removal, the same on every platform (user, 2026-10-27): "Remove from history" takes
 * a video out of Continue watching and off the Watched list together, so nobody meets two actions
 * that sound alike, and "Clear history" does that for every card. Each hands back a [HistoryUndo].
 *
 * With resume across devices on ([ResumeSync]) the change is pushed a little later, so the other
 * devices drop it too and it does not come back on the next sync; an Undo before then is pushed
 * the same way.
 */
object HistoryEdits {

    /** Every copy a Continue watching card stands for ([HomeRows.foldCopies]), by [ProgressKeys]. */
    fun idsOf(card: ResumeRecord, all: List<ResumeRecord>): List<String> =
        HomeRows.copiesOf(card, all).map { ProgressKeys.of(it.chatId, it.messageId) }

    /** Every copy a Watched card stands for ([HomeRows.foldWatched]). */
    fun idsOf(card: WatchedRecord, all: List<WatchedRecord>): List<String> {
        val item = HomeRows.watchedItem(card, all)
        return (listOf(item) + item.versions).map { ProgressKeys.of(it.chatId, it.messageId) }.distinct()
    }

    /** One video and every copy of it, from its page or hold menu. */
    fun idsOf(item: MediaItem): List<String> =
        (listOf(item) + item.versions).map { ProgressKeys.of(it.chatId, it.messageId) }.distinct()

    /** Remove from history for the videos [ids] name: their places and their watched ticks. */
    suspend fun remove(settings: SettingsStore, watched: WatchedStore, ids: Collection<String>, count: Int): HistoryUndo {
        val resume = settings.takeResume(ids)
        val marks = watched.takeMarks(ids)
        pushLater(settings, resume.isNotEmpty())
        return HistoryUndo(resume, marks, emptyList(), count)
    }

    /** Remove from history for Continue watching cards, each with the copies folded into it. */
    suspend fun removeContinueCards(settings: SettingsStore, watched: WatchedStore, cards: List<ResumeRecord>): HistoryUndo {
        if (cards.isEmpty()) return HistoryUndo(emptyMap(), emptyList(), emptyList(), 0)
        val all = settings.continueWatching.first()
        return remove(settings, watched, cards.flatMap { idsOf(it, all) }.distinct(), cards.size)
    }

    /** Remove from history for Watched cards, each with the copies folded into it. */
    suspend fun removeWatchedCards(settings: SettingsStore, watched: WatchedStore, cards: List<WatchedRecord>): HistoryUndo {
        if (cards.isEmpty()) return HistoryUndo(emptyMap(), emptyList(), emptyList(), 0)
        val all = watched.history.first()
        return remove(settings, watched, cards.flatMap { idsOf(it, all) }.distinct(), cards.size)
    }

    /**
     * Remove from history for [item] from its page or menu: its own copies, and the copies of it
     * Continue watching folded into its card, so none comes back in its place.
     */
    suspend fun removeVideo(settings: SettingsStore, watched: WatchedStore, item: MediaItem): HistoryUndo {
        val continuing = settings.continueWatching.first()
        val card = continuing.firstOrNull { it.chatId == item.chatId && it.messageId == item.messageId }
        val ids = idsOf(item) + (card?.let { idsOf(it, continuing) } ?: emptyList())
        return remove(settings, watched, ids.distinct(), 1)
    }

    /**
     * Clear history: every card on Continue watching and on Watched. Only what the lists show, so
     * the kids lock's hidden chats keep theirs. Watch later is a list of plans, not history, and stays.
     */
    suspend fun clear(settings: SettingsStore, watched: WatchedStore): HistoryUndo {
        val continuing = settings.continueWatching.first()
        val finished = watched.history.first()
        val ids = continuing.map { ProgressKeys.of(it.chatId, it.messageId) } + finished.map { it.key }
        val count = HomeRows.foldCopies(continuing).size + HomeRows.foldWatched(finished).size
        return remove(settings, watched, ids.distinct(), count)
    }

    /** Takes [ids] off Watch later. */
    suspend fun removeLater(watched: WatchedStore, ids: Collection<String>, count: Int): HistoryUndo =
        HistoryUndo(emptyMap(), emptyList(), watched.watchLater.take(ids), count)

    /** Empties Watch later, as the list shows it. */
    suspend fun clearLater(watched: WatchedStore): HistoryUndo {
        val all = watched.watchLater.items.first()
        return removeLater(watched, all.map { it.key }, all.size)
    }

    /** Puts back what [undo] took. Safe to call twice: what is there already stays. */
    suspend fun undo(settings: SettingsStore, watched: WatchedStore, undo: HistoryUndo) {
        settings.putBackResume(undo.resume)
        watched.putBackMarks(undo.marks)
        watched.watchLater.putBack(undo.later)
        pushLater(settings, undo.resume.isNotEmpty())
    }

    private suspend fun pushLater(settings: SettingsStore, changed: Boolean) {
        if (changed && runCatching { settings.resumeSyncNow() }.getOrDefault(false)) ResumeSync.pushSoon(settings)
    }
}
