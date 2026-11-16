package com.tmplayer.player

import com.tmplayer.data.OfflineDownloads

/**
 * The videos streaming in Low storage mode right now, and what each has on disk.
 *
 * The player decides, once per video, whether the mode applies: the setting is on, and the video
 * is only being watched, not one the viewer downloaded or is downloading. It says so with [allow]
 * and takes it back with [revoke]. [TdByteWindow] asks here before every download request, so
 * the state outlives the reopen at every seek, and a video the viewer starts downloading halfway
 * through is never deleted again from that moment on.
 *
 * Held per file id rather than per reader: the player opens a fresh reader at each seek, and the
 * ranges on disk belong to the file.
 */
object LowStorageStreams {

    private class State(val window: Long, var ranges: List<LowStorageWindow.Range>)

    private val files = HashMap<Int, State>()

    /**
     * Where each file's index starts, learned from its head (the warm-up or the stream's first
     * read: [StreamIndex.locate]), kept apart from [files] because the warm-up finds it before the
     * player allows the mode. Bounded: the oldest is forgotten first.
     */
    private val indexes = LinkedHashMap<Int, Long>()
    private const val MAX_INDEXES = 64

    private fun indexAt(fileId: Int): Long = indexes[fileId] ?: LowStorageWindow.NO_INDEX

    /**
     * Lets [fileId] stream through a window of [windowBytes].
     *
     * @return this player's hold on the decision, for [revoke] on the way out.
     */
    @Synchronized
    fun allow(fileId: Int, windowBytes: Long): Any = State(windowBytes, emptyList()).also { files[fileId] = it }

    /** Back to streaming the whole file, as without the mode. */
    @Synchronized
    fun revoke(fileId: Int) {
        files.remove(fileId)
    }

    /**
     * Takes back what [allow] returned [hold] for, and only that: a player of the same file opened
     * since then (the same video again, a copy that is the same file) has made its own decision,
     * and the old player's late exit must not leave the new one streaming the whole file.
     */
    @Synchronized
    fun revoke(fileId: Int, hold: Any) {
        if (files[fileId] === hold) files.remove(fileId)
    }

    @Synchronized
    fun isRolling(fileId: Int): Boolean = fileId in files && !OfflineDownloads.isDownloading(fileId)

    /**
     * The request for a download of [fileId] from [offset], or null when the mode does not apply
     * and the download goes to the end of the file as it always has. Records what the request
     * leaves on disk, so call it only for a request that is about to be made.
     */
    @Synchronized
    fun plan(fileId: Int, offset: Long, size: Long, roll: Boolean = false): LowStorageWindow.Request? {
        val state = files[fileId] ?: return null
        // The download queue shares this file in TDLib: it is the viewer's now, and not ours to
        // delete or to cut short.
        if (OfflineDownloads.isDownloading(fileId)) return null
        val request = LowStorageWindow.plan(state.ranges, offset, size, state.window, roll, indexAt(fileId))
        state.ranges = request.ranges
        return request
    }

    /**
     * What to do with [run] bytes TDLib already has on the disk at [offset] of [fileId], away from
     * its running download ([LowStorageWindow.onDisk]), recording them as held when they are to be
     * read. Null when the mode does not apply.
     */
    @Synchronized
    fun onDisk(fileId: Int, offset: Long, run: Long, size: Long): LowStorageWindow.OnDisk.Use? {
        val state = files[fileId] ?: return null
        if (OfflineDownloads.isDownloading(fileId)) return null
        val answer = LowStorageWindow.onDisk(state.ranges, offset, run, size, state.window, indexAt(fileId))
        state.ranges = answer.ranges
        return answer.use
    }

    /** Whether a request from [offset] of [fileId] would delete the file first. Records nothing. */
    @Synchronized
    fun wouldDelete(fileId: Int, offset: Long, size: Long): Boolean {
        val state = files[fileId] ?: return false
        if (OfflineDownloads.isDownloading(fileId)) return false
        return LowStorageWindow.plan(state.ranges, offset, size, state.window, indexStart = indexAt(fileId)).deleteFirst
    }

    /** Whether [position] of [fileId] lies in a stretch the mode asked for or found on the disk. */
    @Synchronized
    fun holds(fileId: Int, position: Long): Boolean {
        val state = files[fileId] ?: return false
        return state.ranges.any { position >= it.start && position < it.end }
    }

    /**
     * Records a probe of `[start, end)` of [fileId], [size] bytes long, about to be fetched, if it
     * keeps the disk within the window and its slack ([LowStorageWindow.probeFits]); false, and
     * nothing recorded, when it would not, and the read is fetched as a seek instead.
     */
    @Synchronized
    fun probe(fileId: Int, start: Long, end: Long, size: Long): Boolean {
        val state = files[fileId] ?: return true
        if (OfflineDownloads.isDownloading(fileId)) return true
        val index = indexAt(fileId)
        if (!LowStorageWindow.probeFits(state.ranges, start, end, size, state.window, index)) return false
        state.ranges = LowStorageWindow.merge(state.ranges + LowStorageWindow.indexRange(start, end, size, index))
        return true
    }

    /**
     * [fileId]'s index starts at [start] of its [size] bytes (HX79): from then on a read anywhere
     * in it is an index read, held beside the window. A start that is no index ([LowStorageWindow.indexOf])
     * is ignored and what was known stays.
     */
    @Synchronized
    fun knowIndex(fileId: Int, start: Long, size: Long) {
        if (LowStorageWindow.indexOf(start, size) == null) return
        indexes.remove(fileId)
        indexes[fileId] = start
        while (indexes.size > MAX_INDEXES) indexes.remove(indexes.keys.first())
    }

    @Synchronized
    fun knowsIndex(fileId: Int): Boolean = fileId in indexes

    @Synchronized
    fun forgetIndex(fileId: Int) {
        indexes.remove(fileId)
    }

    /** Whether a read at [position] of [fileId] is in its index ([LowStorageWindow.nearTheEnd], with the index known here). */
    @Synchronized
    fun nearTheEnd(fileId: Int, position: Long, size: Long): Boolean =
        LowStorageWindow.nearTheEnd(position, size, indexAt(fileId))

    /** Whether a read at [position] should roll [fileId]'s window on before it is served. */
    @Synchronized
    fun rollDue(fileId: Int, position: Long, size: Long): Boolean {
        val state = files[fileId] ?: return false
        if (OfflineDownloads.isDownloading(fileId)) return false
        return LowStorageWindow.shouldRoll(position, state.ranges, size)
    }
}
