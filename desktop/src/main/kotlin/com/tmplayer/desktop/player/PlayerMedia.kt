package com.tmplayer.desktop.player

import com.tmplayer.data.CacheShelf
import com.tmplayer.data.ChatRepository
import com.tmplayer.data.ContentProtection
import com.tmplayer.data.DownloadRunner
import com.tmplayer.data.EpisodeNeighbours
import com.tmplayer.data.EpisodeSteps
import com.tmplayer.desktop.DesktopPaths
import com.tmplayer.data.Failures
import com.tmplayer.data.errorMessage
import com.tmplayer.desktop.DesktopServices
import com.tmplayer.desktop.DesktopWatchCache
import com.tmplayer.desktop.DownloadIndex
import com.tmplayer.desktop.RoomOnDisk
import com.tmplayer.desktop.ui.LowStorageQuestion
import com.tmplayer.player.LowStorageStreams
import com.tmplayer.player.LowStorageWindow
import com.tmplayer.data.LocalFileAvailability
import com.tmplayer.data.MediaItem
import com.tmplayer.data.MediaName
import com.tmplayer.data.OfflineDownloads
import com.tmplayer.data.ResumeRecord
import com.tmplayer.data.SplitPieces
import com.tmplayer.data.Td
import com.tmplayer.data.valueOrNull
import com.tmplayer.i18n.L
import com.tmplayer.platform.Logger
import dev.g000sha256.tdl.dto.OptionValueString
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import org.openani.mediamp.source.MediaData
import org.openani.mediamp.source.UriMediaData
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/** The episodes either side of the one playing, and what to call them: see [EpisodeNeighbours]. */
typealias Episodes = EpisodeSteps

/**
 * Where a [PlayerScreen] gets its bytes and its neighbours: Telegram in the app, a folder on disk
 * in the dev harness. Everything else about playback is the same code for both.
 */
interface PlayerMedia {
    val item: MediaItem
    val chatTitle: String

    /** The file as mediamp should open it. Throws with a message a viewer can read. */
    suspend fun open(): MediaData

    /** The episodes either side of this one. */
    suspend fun episodes(): Episodes

    /**
     * Where this video's intro, recap and credits are, for the skip button: asked of the online
     * lookups once per video, and empty without asking anybody when they are off. A file on disk
     * (the dev harness) has none, since nothing names it the way a lookup needs.
     */
    suspend fun skipTimes(): List<com.tmplayer.online.SkipSegment> = emptyList()

    /** The same kind of source, for another episode. */
    fun episode(other: MediaItem): PlayerMedia

    /** How much of the file is on disk, 0 to 1; null where that means nothing (a local file). */
    val downloaded: StateFlow<Float?>

    /**
     * True while the bytes coming down are a download the viewer asked for, rather than the
     * watch cache filling for playback. Only then does the player say "downloaded"; otherwise it
     * says caching, so nobody reads the cache as a download they never started.
     */
    val isDownload: Boolean get() = false

    suspend fun tdlibVersion(): String?

    /** The viewer left this video: stop what was only fetched for it. */
    fun release()

    /** Whether the menu offers Copy link and Download: only a Telegram video has either. */
    val fromTelegram: Boolean get() = false

    /** The t.me link to the message, or null where the chat gives none (private groups, a file). */
    suspend fun messageLink(): String? = null

    /**
     * Whether the menu offers Save to Downloads and Open in another app: false for a chat with
     * "restrict saving content", whose videos may be watched here and nothing more. It can turn
     * false after [open], once Telegram has been asked about a video the caller knew little of.
     */
    val savable: StateFlow<Boolean> get() = NO_RESTRICTION

    /** Queues the whole file to be kept, and says what happened in words for a notice. */
    fun download(): String = L.playerDownloadOnlyTelegram

    /**
     * The whole file on disk, for Open in another app: the download in the Downloads folder, or
     * TDLib's complete copy. Null while any of it is still to come.
     */
    suspend fun localFile(): java.io.File? = null

    /**
     * The OpenSubtitles hash of this video, for an online subtitle search: from the whole file
     * when it is on the disk, else null and the search goes by name.
     */
    suspend fun onlineHash(): String? =
        localFile()?.let { withContext(Dispatchers.IO) { com.tmplayer.online.MovieHash.of(it) } }

    /**
     * What [open] is doing while it takes its time, for the loading screen ("Downloading the
     * whole video: 42 %"); null when there is nothing to say beyond the screen's own words.
     */
    val preparing: StateFlow<String?> get() = NOTHING_TO_SAY

    /**
     * What to tell the viewer as the video starts, once [open] has returned: for a video cut into
     * byte pieces with one missing, "Part 4 of 7 is not in this chat, so the video plays up to it."
     */
    val notice: String? get() = null

    /** True when the video stops short of its end (a missing piece), so its end marks nothing watched. */
    val incomplete: Boolean get() = false
}

private val NOTHING_TO_SAY: StateFlow<String?> = MutableStateFlow(null)
private val NO_RESTRICTION: StateFlow<Boolean> = MutableStateFlow(true)

/** Which Telegram files a player currently has open, so a late cancel never stops a new playback. */
internal object ActiveStreams {
    private val open = ConcurrentHashMap<Int, Int>()
    fun opened(fileId: Int) { open.merge(fileId, 1, Int::plus) }
    fun closed(fileId: Int) { open.computeIfPresent(fileId) { _, n -> (n - 1).takeIf { it > 0 } } }
    fun isOpen(fileId: Int): Boolean = open.containsKey(fileId)
    fun openIds(): Set<Int> = open.keys.toSet()
}

private val background = CoroutineScope(SupervisorJob() + Dispatchers.IO + com.tmplayer.platform.JobFailures.handler("PlayerMedia"))

/**
 * A video in a Telegram chat, streamed through TDLib, or fetched whole first when [downloadFirst]
 * (Settings, "Download the whole video first") says so.
 */
class TelegramPlayerMedia(
    override val item: MediaItem,
    override val chatTitle: String = "",
    private val downloads: DownloadRunner? = null,
    private val cache: DesktopWatchCache? = null,
    private val downloadFirst: suspend () -> Boolean = { DesktopServices.settings.downloadBeforePlayingNow() },
    /** The download of a message, from the index, when it is whole in the Downloads folder. */
    private val indexed: suspend (chatId: Long, messageId: Long) -> File? = { chatId, messageId ->
        DownloadIndex.fileFor(DesktopServices.settings, chatId, messageId)
    },
) : PlayerMedia {

    override val fromTelegram: Boolean get() = true

    private val _savable = MutableStateFlow(item.canBeSaved)
    override val savable: StateFlow<Boolean> = _savable.asStateFlow()

    /** The file played straight off the disk, counted open in [ActiveStreams] until [release]. */
    @Volatile
    private var openedFromDisk: Int? = null

    private val _downloaded = MutableStateFlow<Float?>(null)
    override val downloaded: StateFlow<Float?> = _downloaded.asStateFlow()
    private var watcher: Job? = null

    /** The file in the Downloads folder this is playing from, when it is a download. */
    @Volatile
    private var downloadFile: File? = null

    /** The TDLib file id as this session knows it, once [open] has asked. */
    @Volatile
    private var playingId: Int = item.fileId

    override val isDownload: Boolean get() = downloadFile != null || OfflineDownloads.isDownloading(playingId)

    override suspend fun open(): MediaData {
        // An item rebuilt from a saved record (Continue watching, Downloads) carries no word on
        // the chat's setting, so Telegram is asked once, off the path to the first frame.
        if (item.canBeSaved) {
            background.launch { if (!Td.maySave(item.chatId, item.messageId)) _savable.value = false }
        }
        // A download plays from the Downloads folder (B3.5), before TDLib is asked anything: it is
        // not in TDLib's cache at all any more, and it plays with no connection.
        // A video cut into byte pieces plays joined, whatever is kept of any one piece.
        joinedPieces()?.let { return openJoined(it) }
        indexed(item.chatId, item.messageId)?.let { file ->
            downloadFile = file
            _downloaded.value = 1f
            return UriMediaData(file.absolutePath)
        }
        ensureRoom()
        val session = Td.awaitAuthorizedSession()
        // A Telegram copy (CP80) is asked for by its height, so a stale id finds the same copy.
        val fileId = Td.currentFileId(item.chatId, item.messageId, item.fileId, item.playingQuality)
        playingId = fileId
        watchDownload(fileId)
        val streamsLow = startLowStorageIfOn(fileId)
        // The player claims the cache for every video it opens (the phone's rule): this one is
        // the cached video now, and the one before it goes, unless either is a download.
        cache?.let { c -> background.launch { runCatching { c.claim(item, chatTitle, fileId) } } }
        // Fetching the whole film first is exactly what Low storage mode is there to avoid.
        if (!streamsLow && Td.localFileAvailability(fileId) != LocalFileAvailability.Complete &&
            runCatching { downloadFirst() }.getOrDefault(false)
        ) {
            fetchWhole(fileId)
        }
        // A finished file plays straight off the disk, through mpv's own file reader.
        if (Td.localFileAvailability(fileId) == LocalFileAvailability.Complete) {
            Td.localFilePath(fileId)?.let { path ->
                _downloaded.value = 1f
                ActiveStreams.opened(fileId)
                openedFromDisk = fileId
                return UriMediaData(path)
            }
        }
        val bytes = TdStreamBytes(session.client, fileId)
        // The first read says where the download starts: a resumed video is not asked for from
        // byte 0 and then sought away from.
        bytes.start()
        ActiveStreams.opened(fileId)
        val name = item.fileName.ifBlank { item.title }
        return TdMediaData(
            bytes = bytes,
            uri = "tdfile/$fileId/" + name.replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { "video" },
            // The mode is taken back only once the bytes are closed, so no late read can ask TDLib
            // for the rest of the film unbounded.
            onClose = {
                stopDownload(fileId)
                // Only this open's own decision: a later open of the same file has its own, which
                // this late close must not take, or that stream fetches the whole video.
                if (lowStorageFileId == fileId) lowStorageHold?.let { LowStorageStreams.revoke(fileId, it) }
            },
        )
    }

    // A video cut into byte pieces ([SplitPieces]), played as one stream of their bytes joined.

    @Volatile
    private var joinedNotice: String? = null

    @Volatile
    private var joinedIncomplete = false

    override val notice: String? get() = joinedNotice
    override val incomplete: Boolean get() = joinedIncomplete

    /** The pieces, joined into one item, once [open] found them: what Download keeps. */
    @Volatile
    private var joinedItem: MediaItem? = null

    /**
     * The pieces of this video, in order: from the grid, else searched for in the chat, else among
     * the Downloads for a player with no connection. Null for any other video.
     */
    private suspend fun joinedPieces(): List<MediaItem>? {
        if (item.chatId == 0L) return null
        if (item.isJoined) return item.pieces
        if (item.splitPiece?.certain != true) return null
        val found = runCatching {
            val repository = ChatRepository(Td.awaitAuthorizedSession().client)
            SplitPieces.lookUp(item) { query, cursors ->
                runCatching { repository.mediaPage(item.chatId, cursors, query = query, topicId = item.topicId) }.getOrNull()
            }
        }.getOrNull().orEmpty()
        if (found.size > 1) return found
        val kept = runCatching { SplitPieces.among(item, DesktopServices.settings.downloadsNow().map { it.toMediaItem() }) }.getOrNull()
        return kept?.takeIf { it.size > 1 } ?: found.ifEmpty { null }
    }

    /**
     * The pieces as one stream ([JoinedStreamBytes]): each from Downloads where it is there, else
     * streamed from TDLib under its current id, opened only when mpv reads into it. Room is made
     * for the whole video, every streamed piece is claimed as the cached video and bounded for
     * Low storage mode, and a piece a seek leaves behind stops downloading.
     */
    private suspend fun openJoined(all: List<MediaItem>): MediaData {
        val notice = SplitPieces.playerNotice(all)
        val playable = SplitPieces.playablePieces(all)
        // Remembered for "Missing parts of a video" (CP76); a video found whole is forgotten.
        background.launch { runCatching { com.tmplayer.data.PieceAlerts.notePlayed(DesktopServices.settings, all, chatTitle) } }
        if (playable.isEmpty()) throw IOException(notice ?: L.playerNothingToPlay)
        joinedNotice = notice
        joinedIncomplete = notice != null
        joinedItem = SplitPieces.joined(all)
        ensureRoom()
        val session = Td.awaitAuthorizedSession()
        val client = session.client
        class Source(val piece: MediaItem, val file: File?, val fileId: Int, val size: Long)
        val sources = playable.map { piece ->
            val file = indexed(piece.chatId, piece.messageId)
            if (file != null) {
                Source(piece, file, 0, file.length())
            } else {
                val id = runCatching { Td.currentFileId(piece.chatId, piece.messageId, piece.fileId) }.getOrDefault(piece.fileId)
                val td = client.getFile(id).valueOrNull
                val size = td?.let { if (it.size > 0) it.size else it.expectedSize }?.takeIf { it > 0 } ?: piece.sizeBytes
                Source(piece, null, id, size)
            }
        }
        if (sources.any { it.size <= 0 }) throw IOException(L.playerOpenFailed)
        val streamed = sources.filter { it.file == null }
        playingId = streamed.firstOrNull()?.fileId ?: playingId
        if (streamed.isNotEmpty()) {
            cache?.let { c -> background.launch { runCatching { c.claimAll(streamed.map { it.piece.copy(title = item.title) to it.fileId }, chatTitle) } } }
            boundPieces(streamed.map { it.fileId })
        }
        _downloaded.value = if (streamed.isEmpty()) 1f else null
        val open = java.util.Collections.synchronizedSet(HashSet<Int>())
        val bytes = JoinedStreamBytes(
            sizes = sources.map { it.size },
            open = { index, from ->
                val source = sources[index]
                if (source.file != null) {
                    LocalFileBytes(source.file)
                } else {
                    TdStreamBytes(client, source.fileId).also {
                        it.start(from)
                        if (open.add(source.fileId)) ActiveStreams.opened(source.fileId)
                    }
                }
            },
            onLeft = { index ->
                val id = sources[index].fileId
                if (id != 0 && open.remove(id)) {
                    val lowStorage = LowStorageStreams.isRolling(id)
                    stopDownload(id)
                    // In Low storage mode the window is per piece: the one left goes.
                    if (lowStorage) background.launch { runCatching { Td.deleteFile(id) } }
                }
            },
        )
        val name = SplitPieces.baseOf(playable.first().fileName.ifBlank { item.fileName })
        return TdMediaData(
            bytes = bytes,
            uri = "tdjoin/${playable.first().fileId}/" + name.replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { "video" },
            onClose = {
                open.toList().forEach(::stopDownload)
                open.clear()
                letGoOfPieceBounds()
            },
        )
    }

    /** What [LowStorageStreams] gave each streamed piece, taken back once the bytes are closed. */
    private val pieceHolds = java.util.concurrent.ConcurrentHashMap<Int, Any>()

    private suspend fun boundPieces(ids: List<Int>) {
        letGoOfPieceBounds()
        val settings = DesktopServices.settings
        if (!runCatching { settings.lowStorageModeNow() }.getOrDefault(false)) return
        if (runCatching { settings.isKeptDownload(item.chatId, item.messageId) }.getOrDefault(true)) return
        val window = LowStorageWindow.windowBytes(DesktopPaths.disk().freeBytes, CacheShelf.HEADROOM_BYTES)
        for (id in ids) {
            LowStorageStreams.revoke(id)
            if (OfflineDownloads.isDownloading(id)) continue
            if (runCatching { Td.localFileAvailability(id) }.getOrNull() == LocalFileAvailability.Complete) continue
            pieceHolds[id] = LowStorageStreams.allow(id, window)
        }
    }

    private fun letGoOfPieceBounds() {
        pieceHolds.forEach { (id, hold) -> LowStorageStreams.revoke(id, hold) }
        pieceHolds.clear()
    }

    /** The file [LowStorageStreams] was told about, taken back at [release]. */
    @Volatile
    private var lowStorageFileId = 0

    /** What [LowStorageStreams.allow] gave this open, so its close takes back that and nothing newer. */
    @Volatile
    private var lowStorageHold: Any? = null

    /**
     * Lets this video stream through a rolling window when Low storage mode is on. Only a video
     * being watched: one the viewer downloaded, or is downloading, keeps every byte.
     */
    private suspend fun startLowStorageIfOn(fileId: Int): Boolean {
        // Whatever an earlier open of this file decided does not carry over.
        LowStorageStreams.revoke(fileId)
        if (item.chatId == 0L) return false
        val settings = DesktopServices.settings
        if (!runCatching { settings.lowStorageModeNow() }.getOrDefault(false)) return false
        if (runCatching { Td.localFileAvailability(fileId) }.getOrNull() == LocalFileAvailability.Complete) return false
        if (runCatching { settings.isKeptDownload(item.chatId, item.messageId) }.getOrDefault(true)) return false
        if (OfflineDownloads.isDownloading(fileId)) return false
        val window = LowStorageWindow.windowBytes(DesktopPaths.disk().freeBytes, CacheShelf.HEADROOM_BYTES)
        lowStorageHold = LowStorageStreams.allow(fileId, window)
        lowStorageFileId = fileId
        return true
    }

    private val _preparing = MutableStateFlow<String?>(null)
    override val preparing: StateFlow<String?> = _preparing.asStateFlow()

    /**
     * The whole file, before a frame plays, as Android's `fetchWholeFilm` does: one synchronous
     * TDLib download, its progress on the loading screen. Back on that screen cancels this
     * coroutine, and with it the download, unless the viewer is also keeping the file.
     */
    private suspend fun fetchWhole(fileId: Int) = coroutineScope {
        val progress = launch {
            _downloaded.collect { f ->
                _preparing.value = f?.let { L.playerDownloadingWholePercent(L.messages.formatter.number((it * 100).toInt())) } ?: L.playerDownloadingWholeVideo
            }
        }
        try {
            val session = Td.awaitConnectedSession()
            val result = session.client.downloadFile(
                fileId = fileId,
                priority = WHOLE_FILE_PRIORITY,
                offset = 0,
                limit = 0,
                synchronous = true,
            )
            result.errorMessage?.let { throw IOException(Failures.humanise(it)) }
            if (Td.localFileAvailability(fileId) != LocalFileAvailability.Complete) {
                throw IOException(L.playerDownloadIncomplete)
            }
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) {
                if (!OfflineDownloads.isDownloading(fileId)) runCatching { Td.cancelDownload(fileId) }
            }
            throw cancelled
        } finally {
            progress.cancel()
            _preparing.value = null
        }
    }

    private fun watchDownload(fileId: Int) {
        watcher?.cancel()
        watcher = background.launch {
            val client = Td.awaitAuthorizedSession().client
            client.fileUpdates.filter { it.file.id == fileId }.collect { update ->
                val f = update.file
                val size = if (f.size > 0) f.size else f.expectedSize
                _downloaded.value = when {
                    f.local.isDownloadingCompleted -> 1f
                    // In tenths of a percent: TDLib reports every few hundred kilobytes, and each
                    // new figure recomposed the player screen. The screen shows whole percents,
                    // and the stall watch still sees a tenth of one move.
                    size > 0 -> (kotlin.math.floor(f.local.downloadedSize.toDouble() / size * 1000) / 1000).toFloat().coerceIn(0f, 1f)
                    else -> null
                }
            }
        }
    }

    /**
     * Tells TDLib to stop fetching once nobody is watching, as Android's player does on its way
     * out: streaming asks for everything to the end of the file, and TDLib would otherwise keep
     * filling the disk. A download the viewer asked to keep is not the player's to cancel.
     */
    private fun stopDownload(fileId: Int) {
        ActiveStreams.closed(fileId)
        if (OfflineDownloads.isDownloading(fileId)) return
        background.launch {
            // Opened again straight away (a retry, the same episode): leave the new one be.
            if (ActiveStreams.isOpen(fileId)) return@launch
            runCatching { Td.cancelDownload(fileId) }
        }
    }

    /** [OnlineMetadata.skipTimes][com.tmplayer.online.OnlineMetadata.skipTimes], off the UI thread. */
    override suspend fun skipTimes(): List<com.tmplayer.online.SkipSegment> {
        val online = com.tmplayer.online.OnlineMetadata.current ?: return emptyList()
        return withContext(Dispatchers.IO) { online.skipTimes(item) }
    }

    /**
     * The chat searched for the show's name first, then listed plainly when that finds no
     * neighbour, a page at a time until both are found ([EpisodeNeighbours.lookUp]). A video that
     * names no episode, not even a bare "E5", is a film and asks nothing.
     */
    override suspend fun episodes(): Episodes {
        // Started from a chat's audio: the list is the order, the next file plays when one ends.
        com.tmplayer.data.PlayQueue.stepsFor(item)?.let { return it }
        val tag = EpisodeNeighbours.tagOf(item)
        val show = EpisodeNeighbours.showOf(item) ?: return Episodes()
        if (item.chatId == 0L) return Episodes(current = tag)
        val session = Td.awaitAuthorizedSession()
        val repository = ChatRepository(session.client)
        // In a group with topics, the neighbours are looked for in this video's own topic.
        val topic = item.topicId.takeIf { it != 0 }
            ?: runCatching { repository.topicOf(item.chatId, item.messageId) }.getOrDefault(0)
        return EpisodeNeighbours.lookUp(item, show) { query, cursors ->
            runCatching { repository.mediaPage(item.chatId, cursors, query = query, topicId = topic) }
                .onFailure { Logger.w(TAG, "Episode lookup failed", it) }.getOrNull()
        }
    }

    override fun episode(other: MediaItem): PlayerMedia =
        TelegramPlayerMedia(other, chatTitle, downloads, cache, downloadFirst, indexed)

    override suspend fun messageLink(): String? = runCatching {
        Td.client.getMessageLink(item.chatId, item.messageId, 0, 0, "", false, false).valueOrNull?.link
    }.getOrNull()?.takeIf { it.isNotBlank() }

    /**
     * Save to Downloads (B4): a video being watched can be kept from the player, whether it is
     * streaming or already whole in the cache. The queue moves it into Downloads once the player
     * lets go of the file, which for the video on screen is when playback stops.
     */
    override fun download(): String {
        val runner = downloads ?: return L.downloadsUnavailable
        if (!_savable.value) return ContentProtection.NOT_SAVABLE
        if (downloadFile != null) return L.downloadsAlreadyIn
        val row = OfflineDownloads.active.value[playingId]
        if (row != null && row.busy) {
            return if (row.stage == OfflineDownloads.Stage.Moving) L.downloadsSavesWhenStopped else L.downloadsAlreadyDownloading
        }
        // A joined video keeps every piece; any other file under the id this session plays it as.
        // A Telegram copy playing (CP80) downloads the upload, which is what a download keeps.
        if (item.playingQuality != 0) {
            val upload = com.tmplayer.data.TelegramQualities.upload(item)
            OfflineDownloads.start(runner, upload, chatTitle)
            return L.downloadsDownloadingTitle(item.title)
        }
        OfflineDownloads.start(runner, joinedItem ?: item.copy(fileId = playingId), chatTitle)
        return if (_downloaded.value == 1f) L.downloadsSavingWhenStopped else L.downloadsDownloadingTitle(item.title)
    }

    override suspend fun localFile(): File? {
        // One piece's file is not the video: nothing to hand another app.
        if (joinedItem != null) return null
        downloadFile?.let { return it }
        return runCatching { Td.localFilePath(playingId) }.getOrNull()?.let(::File)
    }

    /** A stream is hashed from TDLib's partial file once both ends are there, without moving it. */
    override suspend fun onlineHash(): String? = if (joinedItem != null) null else localFile()?.let { withContext(Dispatchers.IO) { com.tmplayer.online.MovieHash.of(it) } }
        ?: runCatching {
            withTimeoutOrNull(3_000) {
                com.tmplayer.online.OnlineSubtitles.hashFromTdlib(Td.awaitConnectedSession().client, playingId, item.sizeBytes)
            }
        }.getOrNull()

    /**
     * Makes room on the cache's drive before a stream starts (B8): the least recently played
     * cached videos go if that is what it takes, and a video that cannot fit even then is refused
     * here, with a sentence, rather than by TDLib halfway through.
     */
    private suspend fun ensureRoom() {
        if (cache == null || item.chatId == 0L) return
        val settings = DesktopServices.settings
        val fileId = runCatching { Td.currentFileId(item.chatId, item.messageId, item.fileId) }.getOrDefault(item.fileId)
        if (runCatching { Td.localFileAvailability(fileId) }.getOrNull() == LocalFileAvailability.Complete) return
        val records = runCatching { settings.cachedVideosNow() }.getOrDefault(emptyList())
        val byId = mutableMapOf<Int, ResumeRecord>()
        val held = records.map { record ->
            val id = runCatching { Td.currentFileId(record.chatId, record.messageId, record.fileId) }.getOrDefault(record.fileId)
            byId[id] = record
            CacheShelf.Held(id, runCatching { Td.localDownloadedBytes(id) }.getOrDefault(0L), record.updatedAt)
        }
        val disk = DesktopPaths.disk()
        // A download the viewer keeps needs room for all of it whatever the mode; a stream in Low
        // storage mode needs room for its window.
        val kept = runCatching { settings.isKeptDownload(item.chatId, item.messageId) }.getOrDefault(false)
        val lowStorage = !kept && runCatching { settings.lowStorageModeNow() }.getOrDefault(false)
        val decision = RoomOnDisk.decide(
            sizeBytes = item.sizeBytes,
            partialBytes = runCatching { Td.localDownloadedBytes(fileId) }.getOrDefault(0L),
            cached = held,
            spared = ActiveStreams.openIds() + OfflineDownloads.active.value.keys + fileId,
            freeBytes = disk.freeBytes,
            totalBytes = disk.totalBytes,
            lowStorage = lowStorage,
            streaming = !kept,
        )
        when (decision) {
            RoomOnDisk.Decision.Proceed -> Unit
            is RoomOnDisk.Decision.Evict -> decision.fileIds.forEach { id ->
                runCatching { Td.deleteFile(id) }
                byId[id]?.let { runCatching { settings.forgetCachedVideo(it.chatId, it.messageId) } }
            }
            is RoomOnDisk.Decision.NotEnoughSpace -> {
                // Asked every time the video would fit in Low storage mode; "Not now" is the refusal.
                if (decision.fitsInLowStorage && LowStorageQuestion.ask(item)) {
                    runCatching { settings.setLowStorageMode(true) }
                    return ensureRoom()
                }
                throw com.tmplayer.desktop.NotEnoughRoom(RoomOnDisk.refusal(decision.shortBytes, DesktopPaths.filesDir))
            }
        }
    }

    override suspend fun tdlibVersion(): String? = runCatching {
        (Td.awaitAuthorizedSession().client.getOption("version").valueOrNull as? OptionValueString)?.value
    }.getOrNull()

    override fun release() {
        watcher?.cancel()
        openedFromDisk?.let(ActiveStreams::closed)
        openedFromDisk = null
    }

    private companion object {
        const val TAG = "TelegramPlayerMedia"

        /** Android's player asks for the whole film at the same priority. */
        const val WHOLE_FILE_PRIORITY = 32
    }
}


/**
 * A file on disk, for the dev harness. With [growingBytesPerSecond] set it is streamed through the
 * same [TdMediaData] path as Telegram, off a copy that fills at that rate.
 */
class LocalPlayerMedia(
    private val file: File,
    private val growingBytesPerSecond: Long? = null,
    private val scratch: File = File(System.getProperty("java.io.tmpdir"), "tmplayer-dev"),
) : PlayerMedia {

    override val item: MediaItem = itemFor(file)
    override val chatTitle: String = file.parentFile?.name.orEmpty()
    override val downloaded: StateFlow<Float?> = MutableStateFlow(null)

    override suspend fun open(): MediaData {
        if (!file.isFile) throw java.io.IOException("No such file: $file")
        val rate = growingBytesPerSecond ?: return UriMediaData(file.absolutePath)
        val bytes = GrowingFileBytes(file, File(scratch, "growing-${file.name}.part"), rate)
        return TdMediaData(bytes, "growing/${file.name}")
    }

    override suspend fun episodes(): Episodes {
        val siblings = file.parentFile?.listFiles { f -> f.isFile && f.extension.lowercase() in VIDEO_EXTENSIONS }
            .orEmpty().map(::itemFor)
        return EpisodeNeighbours.around(item, siblings)
    }

    override fun episode(other: MediaItem): PlayerMedia =
        LocalPlayerMedia(File(file.parentFile, other.fileName), growingBytesPerSecond, scratch)

    override suspend fun tdlibVersion(): String? = null

    override suspend fun onlineHash(): String? = withContext(Dispatchers.IO) { com.tmplayer.online.MovieHash.of(file) }

    override fun release() = Unit

    companion object {
        private val VIDEO_EXTENSIONS = setOf("mkv", "mp4", "webm", "avi", "mov", "m4v", "ts")

        /** A local file as the item the screen expects; chat 0 and a path hash keep its resume point apart. */
        fun itemFor(file: File) = MediaItem(
            chatId = 0L,
            messageId = file.absolutePath.hashCode().toLong(),
            fileId = 0,
            title = file.name,
            sizeBytes = file.length(),
            durationSec = 0,
            mimeType = "",
            thumbnailFileId = 0,
            miniThumbnail = null,
            date = 0,
            fileName = file.name,
            onDevice = true,
        )
    }
}
