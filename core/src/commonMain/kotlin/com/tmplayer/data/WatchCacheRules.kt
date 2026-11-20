package com.tmplayer.data

import com.tmplayer.i18n.L
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/** The few TDLib file calls the watch cache needs, behind an interface so the rules can be tested. */
interface TdFiles {
    suspend fun currentFileId(chatId: Long, messageId: Long, storedFileId: Int): Int
    suspend fun deleteFile(fileId: Int)
    suspend fun localDownloadedBytes(fileId: Int): Long
    suspend fun localPathAnyway(fileId: Int): String?

    /** The real thing, through [Td]. */
    object Live : TdFiles {
        override suspend fun currentFileId(chatId: Long, messageId: Long, storedFileId: Int) =
            Td.currentFileId(chatId, messageId, storedFileId)
        override suspend fun deleteFile(fileId: Int) = Td.deleteFile(fileId)
        override suspend fun localDownloadedBytes(fileId: Int) = Td.localDownloadedBytes(fileId)
        override suspend fun localPathAnyway(fileId: Int) = Td.localPathAnyway(fileId)
    }
}

/** How much playing may leave behind. */
sealed interface CacheRule {
    /**
     * One video: the next one played replaces it. The phone and the TV, where a copy of last
     * night's video nobody asked to keep is a gigabyte of a small disk spent on nothing.
     */
    data object OneVideo : CacheRule

    /**
     * As many videos as fit under [capBytes], the least recently played going first. The desktop,
     * where there is disk to spare and playing a film again without fetching it is worth having.
     */
    data class UnderCap(val capBytes: Long) : CacheRule
}

/**
 * The videos playing left behind: who claims the disk, who gives it up, and what to do about the
 * copies nothing has a record of. One set of rules for Android and the desktop.
 *
 * A download is a video the viewer asked to keep. A cached video is one that landed only because
 * somebody pressed Play, and [rule] says how many of those there may be. [claim] runs from the
 * player for every video it opens, episodes it moved on to by itself included, so a binge never
 * leaves a series behind. [sweep] takes the bytes nothing has a record of.
 *
 * [strays] covers bytes with no record at all. TDLib keeps files in a directory this app owns, and
 * anything in there that is not a download and not a cached video we know about is a stray: named
 * from its own file name, measured from disk, and deletable like anything else. That is the
 * difference between gigabytes the viewer can see and get rid of, and gigabytes simply missing.
 *
 * Three kinds of file always survive: the video being played, anything the viewer downloaded, and
 * anything the download queue is fetching this moment.
 *
 * @param filesRoot TDLib's files directory, asked each time, since a storage move changes it.
 * @param busy the file ids the download queue holds; [OfflineDownloads] in the app.
 * @param playing the file ids a player has open right now.
 * @param rule how much may be cached, asked at each claim so a changed setting applies at once.
 * @param sparePlaying whether [evictAllBut] and [clearAll] also leave alone every file in
 *   [playing]. The desktop does; Android has always relied on the claim of the video now playing
 *   alone, and keeps doing so. [UnderCap][CacheRule.UnderCap] eviction spares them regardless.
 */
class WatchCacheRules(
    private val settings: SettingsStore,
    private val filesRoot: () -> File,
    private val td: TdFiles = TdFiles.Live,
    private val busy: () -> Set<Int> = { OfflineDownloads.active.value.keys },
    private val playing: () -> Set<Int> = { emptySet() },
    private val now: () -> Long = System::currentTimeMillis,
    private val rule: () -> CacheRule = { CacheRule.OneVideo },
    private val sparePlaying: Boolean = true,
    /**
     * The open profile's turn (HX88). A sweep or a clear is the open profile's, over [filesRoot]
     * (its own TDLib folder); it stops the moment another profile is open, before its walk and
     * before each delete, so it never walks one profile's folder against another's records.
     */
    private val stamp: () -> AccountStamp = { Accounts.stamp },
    /**
     * The TDLib folder of the profile a sweep works for, from that profile's own folder name,
     * never from whatever folder is current (in a switch the next profile's is pointed at before
     * the open one changes). Null: [filesRoot], as before (one profile, the desktop).
     */
    private val rootFor: ((AccountStamp) -> File)? = null,
    /** Whether a sweep of [AccountStamp] may still act: its profile open and no switch under way. */
    private val mayAct: (AccountStamp) -> Boolean = { owner -> owner == stamp() && OwnedSessions.mayAct(owner, current = owner) },
) {
    /**
     * Held for the whole of a claim, because stepping through a series overlaps two of them.
     *
     * A claim is a read of the cached list followed by deletes decided from it, and the player
     * starts the next episode before the last has finished: without the lock, episode N's claim
     * could delete the video episode N+1 had just started. Claims queue.
     */
    private val claiming = Mutex()

    /**
     * The files of claims that have not finished yet, spared by every eviction.
     *
     * The lock alone orders two claims but does not stop the second from deleting the first's
     * video, since by then the record is simply another cached video that is not the one being
     * kept. Backing out of one episode straight into another would lose the bytes now on screen.
     */
    private val inFlight = java.util.Collections.synchronizedSet(mutableSetOf<Int>())

    /**
     * Records [item] (playing as [fileId], which may differ from the id it was listed with) as a
     * cached video, then applies [rule]: under [CacheRule.OneVideo] every other cached video goes,
     * under [CacheRule.UnderCap] the least recently played go until the rest fit. Idempotent:
     * opening the same video twice claims it twice and deletes nothing more.
     */
    suspend fun claim(item: MediaItem, chatTitle: String, fileId: Int = item.fileId) =
        claimAll(listOf(item to fileId), chatTitle)

    /**
     * [claim] for one video held in several files: the byte pieces of a video cut to fit
     * Telegram's limit ([SplitPieces]), each with the id it plays as. Every piece is recorded, and
     * under either rule all of them are kept together, as the one video the cache now holds.
     */
    suspend fun claimAll(pieces: List<Pair<MediaItem, Int>>, chatTitle: String) = owned { claiming.withLock {
        if (pieces.isEmpty()) return@withLock
        val ids = pieces.mapTo(HashSet()) { it.second }
        inFlight += ids
        try {
            for ((item, fileId) in pieces) {
                val kept = runCatching { settings.isKeptDownload(item.chatId, item.messageId) }.getOrDefault(false)
                // A download is not cache and must never be recorded as it: the next claim would
                // delete a video the viewer chose to keep.
                if (!kept) runCatching { settings.rememberCachedVideo(item.copy(fileId = fileId), chatTitle) }
            }
            when (val current = rule()) {
                CacheRule.OneVideo -> evictAllBut(ids)
                is CacheRule.UnderCap -> evictOverCap(current.capBytes, ids)
            }
        } finally {
            inFlight -= ids
        }
    } }

    /**
     * As the profile open when the work started (HX88): every delete after checks it is still
     * that profile's turn ([deleteAndForget]), the claim path's too.
     */
    private suspend fun <T> owned(block: suspend () -> T): T =
        if (kotlinx.coroutines.currentCoroutineContext()[AccountOwner] != null) block() else withContext(AccountOwner(stamp())) { block() }

    /** The profile of the work under way: its [AccountOwner], or the one open now. */
    private suspend fun ownerNow(): AccountStamp = kotlinx.coroutines.currentCoroutineContext()[AccountOwner]?.stamp ?: stamp()

    /**
     * Deletes every cached video except [keepFileId], and forgets the records once they are gone.
     *
     * A record is only dropped when the bytes actually went. TDLib can refuse a delete, and a
     * record removed for a file still on disk loses track of those bytes for good.
     */
    suspend fun evictAllBut(keepFileId: Int) = evictAllBut(setOf(keepFileId))

    /** [evictAllBut] keeping every one of [keepFileIds]: the pieces of one video cut into several. */
    suspend fun evictAllBut(keepFileIds: Set<Int>): Unit = owned { evictAllButOwned(keepFileIds) }

    private suspend fun evictAllButOwned(keepFileIds: Set<Int>) {
        val cached = runCatching { settings.cachedVideosNow() }.getOrDefault(emptyList())
        if (cached.isEmpty()) return
        val busyNow = busy()
        val playingNow = if (sparePlaying) playing() else emptySet()
        val keptIds = keptFileIds()
        for (record in cached) {
            val fileId = resolve(record)
            if (fileId in keepFileIds || record.fileId in keepFileIds) continue
            if (fileId in busyNow || fileId in inFlight || fileId in playingNow) continue
            if (forgetIfDownloaded(record)) continue
            // The same file downloaded from another message: the record stays, since it is still
            // the cached copy as far as this chat is concerned, and the file stays with the download.
            if (fileId in keptIds) continue
            deleteAndForget(record, fileId)
        }
    }

    /**
     * Deletes the least recently played cached videos until what is left fits under [capBytes].
     *
     * The size counted is what is on disk, not the video's full size, so a film abandoned ten
     * minutes in costs what it actually takes. What cannot be deleted (the one being kept, one
     * playing, one the queue is fetching, one in a claim) still counts towards the cap, so the
     * others make room for it rather than the cap being quietly exceeded.
     *
     * @return how many bytes went.
     */
    suspend fun evictOverCap(capBytes: Long, keepFileId: Int? = null): Long =
        evictOverCap(capBytes, listOfNotNull(keepFileId).toSet())

    /** [evictOverCap] sparing every one of [keepFileIds]. */
    suspend fun evictOverCap(capBytes: Long, keepFileIds: Set<Int>): Long = owned { evictOverCapOwned(capBytes, keepFileIds) }

    private suspend fun evictOverCapOwned(capBytes: Long, keepFileIds: Set<Int>): Long {
        val cached = runCatching { settings.cachedVideosNow() }.getOrDefault(emptyList())
        if (cached.isEmpty()) return 0L
        val spared = (busy() + playing() + inFlight.toSet() + keptFileIds() + keepFileIds).toMutableSet()
        val byId = mutableMapOf<Int, ResumeRecord>()
        val held = mutableListOf<CacheShelf.Held>()
        for (record in cached) {
            if (forgetIfDownloaded(record)) continue
            val fileId = resolve(record)
            if (record.fileId in keepFileIds) spared += fileId
            byId[fileId] = record
            held += CacheShelf.Held(fileId, bytesOf(fileId), record.updatedAt)
        }
        var freed = 0L
        for (fileId in lruVictims(held, capBytes, spared)) {
            val record = byId[fileId] ?: continue
            freed += deleteAndForget(record, fileId)
        }
        return freed
    }

    /** One file on disk nothing in the app has a name for. */
    data class Stray(val path: String, val bytes: Long, val modifiedAt: Long) {

        /** The file's own name, which is the only identifying thing about it. */
        val fileName: String get() = File(path).name

        /**
         * Something to call it, taken from the file itself.
         *
         * TDLib names a file after the one on Telegram where it knows the name, and after nothing
         * in particular where it does not, so many are called things like "75". A row headed "75"
         * reads as a name the viewer ought to recognise; "Cached video" says plainly that there is
         * nothing to recognise. The file name goes on the line below either way.
         */
        val title: String
            get() {
                val stem = fileName.substringBeforeLast('.').replace('_', ' ').trim()
                return if (stem.any { it.isLetter() }) stem else L.downloadsCachedVideo
            }
    }

    /**
     * Video files under TDLib's directory that are neither a download nor a cached video we know.
     *
     * @param knownPaths the local paths of everything the app can name, asked of TDLib by the
     *   caller: only it knows which records are worth resolving.
     */
    fun strays(knownPaths: Set<String>, owner: AccountStamp = stamp()): List<Stray> =
        straysIn(rootFor?.invoke(owner) ?: filesRoot(), knownPaths)

    /** Removes one stray from the disk. TDLib refetches it if the video is ever played again. */
    fun forget(stray: Stray, owner: AccountStamp = stamp()): Boolean {
        // Only while the profile whose folder it is in is open and no switch is under way (HX88).
        if (!mayAct(owner)) return false
        val folder = (rootFor?.invoke(owner) ?: filesRoot()).absoluteFile
        if (!File(stray.path).absoluteFile.startsWith(folder)) return false
        return runCatching { File(stray.path).delete() }.getOrDefault(false)
    }

    /**
     * Takes the videos nothing owns, without being asked, and under [CacheRule.UnderCap] brings the
     * cache back under its cap.
     *
     * Only strays otherwise. A record the app can name is the cache's or the viewer's, and both of
     * those are decided by [claim]; this is for the bytes that have no record at all. Anything a
     * download is fetching this moment is accounted for and left alone, or the queue would find
     * its own part-loaded file deleted underneath it.
     *
     * Every known file must answer with a path first. A stray is defined by absence, so an id
     * TDLib will not resolve is indistinguishable from a video nobody owns: sweeping on a partial
     * answer would delete the viewer's own downloads. A sweep skipped costs a little disk until
     * the next one; a sweep guessed costs a video. Nothing written in the last ten minutes counts
     * either, since a file being fetched is touched with every chunk.
     *
     * @return how many bytes went.
     */
    suspend fun sweep(): Long = withContext(Dispatchers.IO) {
        val owner = stamp()
        // Not while a switch is under way, and the folder of the profile this sweep is for,
        // resolved from that profile, never the one a switch is opening.
        if (!mayAct(owner)) return@withContext 0L
        val folder = rootFor?.invoke(owner) ?: filesRoot()
        val capped = (rule() as? CacheRule.UnderCap)?.let { evictOverCap(it.capBytes) } ?: 0L
        // A download with a path is in the Downloads folder, not in TDLib's: TDLib was told to let
        // go of it, so it has no path to give, and asking would make the sweep give up every time.
        val records = runCatching { settings.downloadHistory.first() }.getOrDefault(emptyList())
            .filter { it.localPath == null } +
            runCatching { settings.cachedVideosNow() }.getOrDefault(emptyList())
        // Resolved against this session. A saved id that no longer resolves would leave the video
        // it names unaccounted for, and an unaccounted video is what this deletes.
        val known = buildList {
            for (record in records) add(resolve(record))
            addAll(busy())
        }.distinct()
        val accounted = known.mapNotNull { runCatching { td.localPathAnyway(it) }.getOrNull() }
        if (accounted.size != known.size) return@withContext capped

        // Asked last, after the slow resolving above, so a video opened while that ran is spared.
        // Best effort: a path TDLib will not give yet is covered by the age rule below.
        val open = playing().mapNotNull { runCatching { td.localPathAnyway(it) }.getOrNull() }
        val settled = now() - STRAY_MIN_AGE_MS
        var freed = capped
        // Another profile opened while the records were resolved: its folder and its records are
        // not this sweep's, and nothing is walked or deleted.
        if (!mayAct(owner)) return@withContext capped
        for (stray in straysIn(folder, (accounted + open).toSet())) {
            if (!mayAct(owner)) break
            if (stray.modifiedAt > settled) continue
            if (forget(stray, owner)) freed += stray.bytes
        }
        freed
    }

    /**
     * Empties the cache: every cached video that is not a download, and the strays beside them.
     * Downloads and whatever the queue is fetching are left alone.
     *
     * @return how many bytes went.
     */
    suspend fun clearAll(): Long = withContext(Dispatchers.IO) {
        val owner = stamp()
        if (!mayAct(owner)) return@withContext 0L
        val kept = runCatching { settings.downloadHistory.first() }.getOrDefault(emptyList())
        val keptIds = kept.map { it.fileId }.toSet()
        // On the message too, because one video can hold two file ids.
        val keptMessages = kept.map { it.chatId to it.messageId }.toSet()
        val busyNow = busy()
        val playingNow = if (sparePlaying) playing() else emptySet()
        var freed = 0L
        for (record in runCatching { settings.cachedVideosNow() }.getOrDefault(emptyList())) {
            if (!mayAct(owner)) return@withContext freed
            val fileId = resolve(record)
            // After the lookup, which suspends: never the next profile's file of that id.
            if (!mayAct(owner)) return@withContext freed
            if (fileId in keptIds || record.fileId in keptIds || fileId in busyNow || fileId in playingNow) continue
            if ((record.chatId to record.messageId) in keptMessages) continue
            freed += deleteAndForget(record, fileId)
        }
        freed + sweep()
    }

    /**
     * The saved id belongs to the session that wrote it. After a restart it resolves to nothing:
     * a delete would quietly remove nothing, the zero read after it would pass for "the bytes
     * went", and the record would be dropped with the gigabytes still on the disk. The message is
     * the durable identity, so the id is re-asked from it.
     */
    private suspend fun resolve(record: ResumeRecord): Int =
        runCatching { td.currentFileId(record.chatId, record.messageId, record.fileId) }.getOrDefault(record.fileId)

    /**
     * By file id as well as by message, because one remote video forwarded into two chats is one
     * file in TDLib: a record from the chat it was watched in must not spend the copy the viewer
     * downloaded from the other.
     */
    private suspend fun keptFileIds(): Set<Int> =
        runCatching { settings.downloadHistory.first() }.getOrDefault(emptyList()).map { it.fileId }.toSet()

    /**
     * Downloaded since it was cached: it belongs to the viewer now, so the cache lets go of the
     * record without touching the file. True when that is what happened.
     */
    private suspend fun forgetIfDownloaded(record: ResumeRecord): Boolean {
        val downloaded = runCatching { settings.isKeptDownload(record.chatId, record.messageId) }.getOrDefault(false)
        if (downloaded) runCatching { settings.forgetCachedVideo(record.chatId, record.messageId) }
        return downloaded
    }

    private suspend fun bytesOf(fileId: Int): Long =
        runCatching { td.localDownloadedBytes(fileId) }.getOrDefault(0L)

    /** Deletes, then forgets the record only if the bytes really went. Returns the bytes freed. */
    private suspend fun deleteAndForget(record: ResumeRecord, fileId: Int): Long {
        // Only for the profile the work is for, while it is open and no switch is under way.
        if (!mayAct(ownerNow())) return 0L
        val held = bytesOf(fileId)
        // Again after the read, which suspends: the live client may be the next profile's by now.
        if (!mayAct(ownerNow())) return 0L
        runCatching { td.deleteFile(fileId) }
        if (bytesOf(fileId) > 0) return 0L
        runCatching { settings.forgetCachedVideo(record.chatId, record.messageId) }
        return held
    }

    companion object {
        /** Nothing touched more recently than this is a stray: it may be arriving or playing. */
        const val STRAY_MIN_AGE_MS = 10 * 60_000L

        /**
         * The directories a video can be in.
         *
         * Photos, thumbnails, profile photos and stickers are deliberately absent: they are small,
         * deleting them makes the browse grid grey for no gain, and Settings has its own button
         * for the viewer who wants that space anyway. `temp` is here because a watch abandoned part
         * way leaves its bytes there, and half a video is still half a gigabyte.
         */
        val MEDIA_DIRECTORIES = listOf("videos", "documents", "animations", "video_notes", "temp")

        /** [strays] against a directory rather than a running app, so the walk can be tested. */
        fun straysIn(root: File, knownPaths: Set<String>): List<Stray> {
            if (!root.isDirectory) return emptyList()
            val known = knownPaths.map(::canonical).toSet()
            return MEDIA_DIRECTORIES
                .map { File(root, it) }
                .filter { it.isDirectory }
                .flatMap { directory -> directory.walkTopDown().maxDepth(3).toList() }
                .filter { it.isFile && it.length() > 0 && canonical(it.absolutePath) !in known }
                .map { Stray(it.absolutePath, it.length(), it.lastModified()) }
                .sortedByDescending { it.bytes }
        }

        /**
         * Which cached videos to delete, oldest played first, so that what is left fits [capBytes].
         *
         * Everything in [held] counts towards the cap; only what is not in [spared] may be chosen.
         * When the spared files alone exceed the cap, every other one goes and the cap stays
         * exceeded until they are let go, which is the honest outcome: deleting the video on
         * screen to honour a number is not.
         */
        fun lruVictims(held: List<CacheShelf.Held>, capBytes: Long, spared: Set<Int>): List<Int> {
            val unique = held.distinctBy { it.fileId }
            var total = unique.sumOf { it.bytes.coerceAtLeast(0) }
            if (total <= capBytes) return emptyList()
            val victims = mutableListOf<Int>()
            for (candidate in unique.filter { it.fileId !in spared }.sortedBy { it.updatedAt }) {
                if (total <= capBytes) break
                victims += candidate.fileId
                total -= candidate.bytes.coerceAtLeast(0)
            }
            return victims
        }

        /**
         * One spelling for one file, so two names for the same path cannot disagree.
         *
         * An Android app's data directory is reachable as both `/data/data/<package>` and
         * `/data/user/0/<package>`, and TDLib may still spell a cached file with the directory's
         * old name, now a link. Comparing the strings would make every file the app owns look like
         * a video nobody owns: a download counted as cache, and a download deleted.
         */
        private fun canonical(path: String): String = runCatching { File(path).canonicalPath }.getOrDefault(path)
    }
}
