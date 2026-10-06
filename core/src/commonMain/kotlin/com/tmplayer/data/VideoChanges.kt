package com.tmplayer.data

import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Something that happened to the videos of one chat while the app was open: a video posted, an
 * edit that put a video in a message, messages deleted. Announced by [Td.videoChanges] as TDLib
 * reports it, so a grid or Home can show it without the viewer pulling to refresh.
 */
sealed interface VideoChange {
    val chatId: Long

    /** A new message holding a video, already mapped: nothing has to be fetched to show it. */
    data class Added(override val chatId: Long, val item: MediaItem) : VideoChange

    /** A message whose content changed and is now a video; the new version has to be read again. */
    data class Edited(override val chatId: Long, val messageId: Long) : VideoChange

    /** Messages deleted for good. Whether they were videos is not known, so a listing checks its own. */
    data class Removed(override val chatId: Long, val messageIds: Set<Long>) : VideoChange
}

/** What changed in one chat over one window, with the window's contradictions already settled. */
data class VideoBatch(
    val chatId: Long,
    /** Newest first. */
    val added: List<MediaItem>,
    val edited: Set<Long>,
    val removed: Set<Long>,
) {
    val isEmpty: Boolean get() = added.isEmpty() && edited.isEmpty() && removed.isEmpty()
}

object VideoChanges {

    /**
     * Folds a window of [events] into one batch per chat, in the order the chats first appear.
     *
     * A video posted and deleted inside the same window never shows; an edit to a message that was
     * then deleted is dropped; one posted twice (TDLib can repeat itself) is listed once, as its
     * latest version.
     */
    fun coalesce(events: List<VideoChange>): List<VideoBatch> {
        class Pending {
            val added = LinkedHashMap<Long, MediaItem>()
            val edited = LinkedHashSet<Long>()
            val removed = LinkedHashSet<Long>()
        }
        val byChat = LinkedHashMap<Long, Pending>()
        for (event in events) {
            val pending = byChat.getOrPut(event.chatId) { Pending() }
            when (event) {
                is VideoChange.Added -> {
                    pending.removed -= event.item.messageId
                    pending.added[event.item.messageId] = event.item
                }
                is VideoChange.Edited -> if (event.messageId !in pending.removed) {
                    // An edit to a video posted in this same window: its new version is read as
                    // an edit, and the post stands for where it goes.
                    pending.edited += event.messageId
                }
                is VideoChange.Removed -> event.messageIds.forEach { id ->
                    pending.added -= id
                    pending.edited -= id
                    pending.removed += id
                }
            }
        }
        return byChat.map { (chatId, pending) ->
            VideoBatch(
                chatId = chatId,
                added = pending.added.values.sortedWith(NEWEST_FIRST),
                edited = pending.edited,
                removed = pending.removed,
            )
        }.filterNot { it.isEmpty }
    }

    /**
     * [items] (a listing, newest first) with [batch] applied: the deleted gone, the edited replaced
     * by [edits] (null for one that no longer holds a video), and [added] put where their date
     * says, which for a new post is the top. Videos already listed are not listed twice.
     *
     * [added] is passed separately from the batch so the caller can run it through its own filters
     * (the size limits) first. Returns [items] itself when nothing in it changed, so a screen can
     * tell cheaply that it has nothing to redraw.
     */
    fun merge(
        items: List<MediaItem>,
        batch: VideoBatch,
        added: List<MediaItem> = batch.added,
        edits: Map<Long, MediaItem?> = emptyMap(),
    ): List<MediaItem> {
        var changed = false
        val kept = ArrayList<MediaItem>(items.size + added.size)
        for (item in items) {
            if (item.chatId != batch.chatId) {
                kept += item
                continue
            }
            when {
                item.messageId in batch.removed -> changed = true
                item.messageId in edits -> {
                    val replacement = edits[item.messageId]
                    if (replacement != item) changed = true
                    if (replacement != null) kept += replacement
                }
                else -> kept += item
            }
        }
        val listed = kept.mapTo(HashSet()) { it.chatId to it.messageId }
        val wasListed = items.mapTo(HashSet()) { it.chatId to it.messageId }
        // An edit that turned a message into a video the listing never had is a new video to it.
        val editedIn = edits.values.filterNotNull().filter { (it.chatId to it.messageId) !in wasListed }
        val fresh = (added + editedIn).filter { (it.chatId to it.messageId) !in listed }
            .distinctBy { it.chatId to it.messageId }
        if (fresh.isEmpty()) return if (changed) kept else items
        return (kept + fresh).sortedWith(NEWEST_FIRST)
    }

    private val NEWEST_FIRST = compareByDescending<MediaItem> { it.date }.thenByDescending { it.messageId }
}

/**
 * Collects changes for [windowMs] after the first one arrives, then hands the window over as one
 * coalesced list. A channel posting a season's worth of episodes in a burst lands as one redraw
 * rather than ten, and a lone post still shows within the window.
 */
fun Flow<VideoChange>.batched(windowMs: Long): Flow<List<VideoBatch>> = channelFlow {
    val lock = Mutex()
    val pending = ArrayList<VideoChange>()
    var timer: Job? = null
    collect { event ->
        lock.withLock {
            pending += event
            if (timer == null) {
                timer = launch {
                    delay(windowMs)
                    val window = lock.withLock {
                        val taken = pending.toList()
                        pending.clear()
                        timer = null
                        taken
                    }
                    val batches = VideoChanges.coalesce(window)
                    if (batches.isNotEmpty()) send(batches)
                }
            }
        }
    }
}
