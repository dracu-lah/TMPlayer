package com.tmplayer.desktop

import com.tmplayer.data.CacheShelf
import com.tmplayer.data.OfflineDownloads
import com.tmplayer.data.Td
import com.tmplayer.data.WatchCacheRules
import com.tmplayer.desktop.player.ActiveStreams
import com.tmplayer.player.LowStorageWindow
import com.tmplayer.i18n.L
import com.tmplayer.platform.Logger
import com.tmplayer.platform.NoTransferNotifier
import com.tmplayer.platform.TransferNotifier
import dev.g000sha256.tdl.dto.FileTypeDocument
import dev.g000sha256.tdl.dto.FileTypeVideo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

/**
 * The desktop's storage, as the rest of the app reaches it: the one time migration of old
 * downloads, moving to a new storage location, the housekeeping sweep and the room check before
 * play. Each is built once, on first use.
 */
object DesktopStorage {

    /**
     * Where transfers are shown outside the window. [NoTransferNotifier] until `Main.kt` has a
     * window to hang the real one on.
     */
    @Volatile
    var notifier: TransferNotifier = NoTransferNotifier
        set(value) {
            field = value
            DesktopServices.downloads.notifier = value
            // A transfer begun, or a result never begun, is the job's own profile's (HX88).
            DesktopServices.downloads.notifyAs = { owner, call -> com.tmplayer.desktop.os.ProfileOutside.asOwner(owner, call) }
        }

    /** Closes the player, if one is open. Set by `Main.kt` to the shell's own. */
    @Volatile
    var closePlayer: () -> Unit = {}

    val migration: DownloadMigration by lazy {
        DownloadMigration(DesktopServices.settings, notifier = { notifier }).also { run ->
            // A switch waits for the file in hand (HX88).
            com.tmplayer.data.BeforeSwitch.add({ run.stopForSwitch() })
        }
    }

    /**
     * How far a storage move has got, held here rather than in [relocation] so a screen can watch
     * it without opening the services a move needs.
     */
    val relocationProgress = kotlinx.coroutines.flow.MutableStateFlow<StorageRelocation.Moving?>(null)

    val relocation: StorageRelocation by lazy {
        StorageRelocation(DesktopServices.settings, DesktopServices.prefs, LiveHost, notifier = { notifier }, _moving = relocationProgress).also { move ->
            // A switch, a sign out or Remove all accounts waits for a move under way (HX88).
            com.tmplayer.data.BeforeSwitch.add({ move.stopForSwitch() }) { move.waitingLabel() }
        }
    }

    /** True while a storage move runs: the sweep must not judge a cache that is being replaced. */
    @Volatile
    var frozen: Boolean = false
        private set

    /**
     * The housekeeping run after sign in (B8): the watch cache's sweep (strays, and the cache back
     * under its limit), TDLib's trim of pictures and previews, and TDLib's own clean up over videos
     * as a second line, since nothing else ever invokes it on the desktop.
     *
     * TDLib's clean up has never heard of a download, so it runs only once no download can be in
     * its directory: after the migration, and while the queue is empty and nothing is playing.
     */
    suspend fun housekeeping(cache: WatchCacheRules?) {
        if (frozen) return
        runCatching { migration.run() }.onFailure { Logger.w(TAG, "Moving old downloads failed", it) }
        runCatching { cache?.sweep() }
        runCatching { Td.trimStorage() }
        val migrated = runCatching { DesktopServices.settings.downloadsMigratedNow() }.getOrDefault(false)
        if (migrated && OfflineDownloads.active.value.isEmpty() && ActiveStreams.openIds().isEmpty() && !frozen) {
            Td.optimizeStorage(
                sizeBytes = DesktopServices.prefs.now.cacheLimitBytes,
                ttlSeconds = CACHE_TTL_SECONDS,
                immunityDelaySeconds = CACHE_IMMUNITY_SECONDS,
                fileTypes = arrayOf(FileTypeVideo(), FileTypeDocument()),
            )
        }
    }

    /** The running app, for [StorageRelocation]. */
    private object LiveHost : StorageRelocation.Host {
        /** What was coming down before the move, to start again after it. */
        private var wasBusy: Set<Int> = emptySet()

        override suspend fun pause() {
            frozen = true
            val runner = DesktopServices.downloads
            wasBusy = OfflineDownloads.active.value.values.filter { it.busy }.map { it.fileId }.toSet()
            OfflineDownloads.pauseAll(runner)
            withContext(Dispatchers.Main) { closePlayer() }
            // The player lets go of its file as it is taken down; a move into Downloads that was
            // waiting for it can then finish before the cache it is in goes.
            withTimeoutOrNull(PLAYER_LET_GO_MS) { while (ActiveStreams.openIds().isNotEmpty()) delay(100) }
            runner.settleMoves()
        }

        override suspend fun clearCache() {
            // Old downloads still in the cache go to the Downloads folder first, or clearing the
            // cache would take them with it.
            runCatching { migration.run() }
            if (!Td.clearEverythingCached()) Logger.w(TAG, "TDLib did not clear its cache before the move")
        }

        override suspend fun restart(root: File?) {
            DesktopPaths.useStorageRoot(root)
            Td.restart(DesktopPaths)
        }

        override suspend fun resume() {
            frozen = false
            val runner = DesktopServices.downloads
            OfflineDownloads.active.value.values
                .filter { it.fileId in wasBusy && !it.busy }
                .sortedBy { it.order }
                .forEach { OfflineDownloads.resume(runner, it.fileId) }
            wasBusy = emptySet()
        }
    }

    private const val TAG = "DesktopStorage"
    private const val PLAYER_LET_GO_MS = 5_000L
    private const val CACHE_TTL_SECONDS = 30 * 24 * 60 * 60
    private const val CACHE_IMMUNITY_SECONDS = 10 * 60
}

/**
 * Whether a video about to stream has room on the cache's drive (B8), with the cache's limit in
 * place of the phone's one video: the least recently played cached videos go first, and only as
 * many as the room needs.
 */
/**
 * A video refused before it starts because the drive is short even with the cache emptied: its
 * own type, so the player's error sheet can offer Free up space rather than only Try again.
 */
class NotEnoughRoom(message: String) : java.io.IOException(message)

object RoomOnDisk {

    sealed interface Decision {
        data object Proceed : Decision

        /** Room once these cached videos are deleted. */
        data class Evict(val fileIds: List<Int>) : Decision

        /**
         * Not even with the cache emptied: [shortBytes] more is needed. [fitsInLowStorage] when Low
         * storage mode is off and the video would stream in it, so the refusal can ask to switch.
         */
        data class NotEnoughSpace(val shortBytes: Long, val fitsInLowStorage: Boolean = false) : Decision
    }

    /**
     * @param partialBytes what is already on disk of the video, from an earlier watch.
     * @param cached the cached videos, with when each was last played.
     * @param spared ids that may not go (the video itself, anything playing or downloading).
     * @param freeBytes free space on the cache's drive; zero total means it could not be read.
     */
    fun decide(
        sizeBytes: Long,
        partialBytes: Long,
        cached: List<CacheShelf.Held>,
        spared: Set<Int>,
        freeBytes: Long,
        totalBytes: Long,
        lowStorage: Boolean = false,
        streaming: Boolean = false,
    ): Decision {
        if (sizeBytes <= 0 || totalBytes <= 0) return Decision.Proceed
        val candidates = cached.distinctBy { it.fileId }.filter { it.fileId !in spared }.sortedBy { it.updatedAt }
        val remaining = (sizeBytes - partialBytes).coerceAtLeast(0)
        // In Low storage mode a stream needs room for its rolling window, sized to what there is.
        val everything = freeBytes + candidates.sumOf { it.bytes.coerceAtLeast(0) }
        val windowNeeded = LowStorageWindow.roomNeeded(
            remaining,
            LowStorageWindow.windowBytes(everything, CacheShelf.HEADROOM_BYTES),
        ) + CacheShelf.HEADROOM_BYTES
        val needed = if (lowStorage) windowNeeded else remaining + CacheShelf.HEADROOM_BYTES
        if (freeBytes >= needed) return Decision.Proceed
        var room = freeBytes
        val victims = mutableListOf<Int>()
        for (held in candidates) {
            if (room >= needed) break
            victims += held.fileId
            room += held.bytes.coerceAtLeast(0)
        }
        return if (room >= needed) {
            Decision.Evict(victims)
        } else {
            Decision.NotEnoughSpace(needed - room, fitsInLowStorage = !lowStorage && streaming && room >= windowNeeded)
        }
    }

    /** What the player says when there is not room, before a byte is fetched. */
    fun refusal(shortBytes: Long, where: File): String =
        L.playerNoSpace(com.tmplayer.data.StorageRelocationPlan.size(shortBytes), where.path)
}
