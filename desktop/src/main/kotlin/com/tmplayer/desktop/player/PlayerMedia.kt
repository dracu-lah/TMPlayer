package com.tmplayer.desktop.player

import com.tmplayer.data.CacheShelf
import com.tmplayer.data.ChatRepository
import com.tmplayer.data.DownloadRunner
import com.tmplayer.desktop.DesktopPaths
import com.tmplayer.data.Failures
import com.tmplayer.data.errorMessage
import com.tmplayer.desktop.DesktopServices
import com.tmplayer.desktop.DesktopWatchCache
import com.tmplayer.desktop.DownloadIndex
import com.tmplayer.desktop.RoomOnDisk
import com.tmplayer.data.LocalFileAvailability
import com.tmplayer.data.MediaItem
import com.tmplayer.data.MediaName
import com.tmplayer.data.OfflineDownloads
import com.tmplayer.data.ResumeRecord
import com.tmplayer.data.Td
import com.tmplayer.data.valueOrNull
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

/** The episodes either side of the one playing, nulls where there are none. */
data class Episodes(val previous: MediaItem? = null, val next: MediaItem? = null)

/**
 * Where a [PlayerScreen] gets its bytes and its neighbours: Telegram in the app, a folder on disk
 * in the dev harness. Everything else about playback is the same code for both.
 */
interface PlayerMedia {
    val item: MediaItem
    val chatTitle: String

    /** The file as mediamp should open it. Throws with a message a viewer can read. */
    suspend fun open(): MediaData

    suspend fun episodes(): Episodes

    /** The same kind of source, for another episode. */
    fun episode(other: MediaItem): PlayerMedia

    /** How much of the file is on disk, 0 to 1; null where that means nothing (a local file). */
    val downloaded: StateFlow<Float?>

    suspend fun tdlibVersion(): String?

    /** The viewer left this video: stop what was only fetched for it. */
    fun release()

    /** Whether the menu offers Copy link and Download: only a Telegram video has either. */
    val fromTelegram: Boolean get() = false

    /** The t.me link to the message, or null where the chat gives none (private groups, a file). */
    suspend fun messageLink(): String? = null

    /** Queues the whole file to be kept, and says what happened in words for a notice. */
    fun download(): String = "Only Telegram videos can be downloaded"

    /**
     * The whole file on disk, for Open in another app: the download in the Downloads folder, or
     * TDLib's complete copy. Null while any of it is still to come.
     */
    suspend fun localFile(): java.io.File? = null

    /**
     * What [open] is doing while it takes its time, for the loading screen ("Downloading the
     * whole video: 42 %"); null when there is nothing to say beyond the screen's own words.
     */
    val preparing: StateFlow<String?> get() = NOTHING_TO_SAY
}

private val NOTHING_TO_SAY: StateFlow<String?> = MutableStateFlow(null)

/** Which Telegram files a player currently has open, so a late cancel never stops a new playback. */
internal object ActiveStreams {
    private val open = ConcurrentHashMap<Int, Int>()
    fun opened(fileId: Int) { open.merge(fileId, 1, Int::plus) }
    fun closed(fileId: Int) { open.computeIfPresent(fileId) { _, n -> (n - 1).takeIf { it > 0 } } }
    fun isOpen(fileId: Int): Boolean = open.containsKey(fileId)
    fun openIds(): Set<Int> = open.keys.toSet()
}

private val background = CoroutineScope(SupervisorJob() + Dispatchers.IO)

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

    override suspend fun open(): MediaData {
        // A download plays from the Downloads folder (B3.5), before TDLib is asked anything: it is
        // not in TDLib's cache at all any more, and it plays with no connection.
        indexed(item.chatId, item.messageId)?.let { file ->
            downloadFile = file
            _downloaded.value = 1f
            return UriMediaData(file.absolutePath)
        }
        ensureRoom()
        val session = Td.awaitAuthorizedSession()
        val fileId = Td.currentFileId(item.chatId, item.messageId, item.fileId)
        playingId = fileId
        watchDownload(fileId)
        // The player claims the cache for every video it opens (the phone's rule): this one is
        // the cached video now, and the one before it goes, unless either is a download.
        cache?.let { c -> background.launch { runCatching { c.claim(item, chatTitle, fileId) } } }
        if (Td.localFileAvailability(fileId) != LocalFileAvailability.Complete &&
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
        bytes.start(0)
        ActiveStreams.opened(fileId)
        val name = item.fileName.ifBlank { item.title }
        return TdMediaData(
            bytes = bytes,
            uri = "tdfile/$fileId/" + name.replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { "video" },
            prefetchTail = TailPrefetch.wanted(name, item.mimeType),
            onClose = { stopDownload(fileId) },
        )
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
                _preparing.value = "Downloading the whole video" + (f?.let { ": ${(it * 100).toInt()} %" } ?: "")
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
                throw IOException("The download did not finish. Check the connection and try again.")
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
                    size > 0 -> (f.local.downloadedSize.toFloat() / size).coerceIn(0f, 1f)
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

    override suspend fun episodes(): Episodes {
        val name = item.fileName.ifBlank { item.title }
        val here = MediaName.parse(name)
        if (!here.isEpisode || item.chatId == 0L) return Episodes()
        val session = Td.awaitAuthorizedSession()
        val candidates = runCatching {
            val repository = ChatRepository(session.client)
            val narrowed = repository.mediaPage(item.chatId, query = here.title).items
            narrowed.ifEmpty { repository.mediaPage(item.chatId).items }
        }.onFailure { Logger.w(TAG, "Episode lookup failed", it) }.getOrNull().orEmpty()
        return episodesAmong(name, candidates)
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
        val runner = downloads ?: return "Downloads are not available here"
        if (downloadFile != null) return "Already in Downloads"
        val row = OfflineDownloads.active.value[playingId]
        if (row != null && row.busy) {
            return if (row.stage == OfflineDownloads.Stage.Moving) "Saves to Downloads when playback stops" else "Already downloading"
        }
        OfflineDownloads.start(runner, item.copy(fileId = playingId), chatTitle)
        return if (_downloaded.value == 1f) "Saving to Downloads. It finishes when playback stops" else "Downloading ${item.title}"
    }

    override suspend fun localFile(): File? {
        downloadFile?.let { return it }
        return runCatching { Td.localFilePath(playingId) }.getOrNull()?.let(::File)
    }

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
        val decision = RoomOnDisk.decide(
            sizeBytes = item.sizeBytes,
            partialBytes = runCatching { Td.localDownloadedBytes(fileId) }.getOrDefault(0L),
            cached = held,
            spared = ActiveStreams.openIds() + OfflineDownloads.active.value.keys + fileId,
            freeBytes = disk.freeBytes,
            totalBytes = disk.totalBytes,
        )
        when (decision) {
            RoomOnDisk.Decision.Proceed -> Unit
            is RoomOnDisk.Decision.Evict -> decision.fileIds.forEach { id ->
                runCatching { Td.deleteFile(id) }
                byId[id]?.let { runCatching { settings.forgetCachedVideo(it.chatId, it.messageId) } }
            }
            is RoomOnDisk.Decision.NotEnoughSpace ->
                throw java.io.IOException(RoomOnDisk.refusal(decision.shortBytes, DesktopPaths.filesDir))
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

/** The neighbours of [name] among [candidates], by the same parser the phone uses. */
fun episodesAmong(name: String, candidates: List<MediaItem>): Episodes {
    fun nameOf(item: MediaItem) = item.fileName.ifBlank { item.title }
    return Episodes(
        previous = MediaName.previousEpisode(name, candidates, ::nameOf),
        next = MediaName.nextEpisode(name, candidates, ::nameOf),
    )
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
        return TdMediaData(bytes, "growing/${file.name}", prefetchTail = TailPrefetch.wanted(file.name, ""))
    }

    override suspend fun episodes(): Episodes {
        val siblings = file.parentFile?.listFiles { f -> f.isFile && f.extension.lowercase() in VIDEO_EXTENSIONS }
            .orEmpty().map(::itemFor)
        return episodesAmong(file.name, siblings)
    }

    override fun episode(other: MediaItem): PlayerMedia =
        LocalPlayerMedia(File(file.parentFile, other.fileName), growingBytesPerSecond, scratch)

    override suspend fun tdlibVersion(): String? = null

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
