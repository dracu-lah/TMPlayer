package com.tmplayer.desktop.player

import com.tmplayer.data.ChatRepository
import com.tmplayer.data.LocalFileAvailability
import com.tmplayer.data.MediaItem
import com.tmplayer.data.MediaName
import com.tmplayer.data.OfflineDownloads
import com.tmplayer.data.Td
import com.tmplayer.data.valueOrNull
import com.tmplayer.platform.Logger
import dev.g000sha256.tdl.dto.OptionValueString
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import org.openani.mediamp.source.MediaData
import org.openani.mediamp.source.UriMediaData
import java.io.File
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
}

/** Which Telegram files a player currently has open, so a late cancel never stops a new playback. */
internal object ActiveStreams {
    private val open = ConcurrentHashMap<Int, Int>()
    fun opened(fileId: Int) { open.merge(fileId, 1, Int::plus) }
    fun closed(fileId: Int) { open.computeIfPresent(fileId) { _, n -> (n - 1).takeIf { it > 0 } } }
    fun isOpen(fileId: Int): Boolean = open.containsKey(fileId)
}

private val background = CoroutineScope(SupervisorJob() + Dispatchers.IO)

/** A video in a Telegram chat, streamed through TDLib. */
class TelegramPlayerMedia(
    override val item: MediaItem,
    override val chatTitle: String = "",
) : PlayerMedia {

    private val _downloaded = MutableStateFlow<Float?>(null)
    override val downloaded: StateFlow<Float?> = _downloaded.asStateFlow()
    private var watcher: Job? = null

    override suspend fun open(): MediaData {
        val session = Td.awaitAuthorizedSession()
        val fileId = Td.currentFileId(item.chatId, item.messageId, item.fileId)
        watchDownload(fileId)
        // A finished file plays straight off the disk, through mpv's own file reader.
        if (Td.localFileAvailability(fileId) == LocalFileAvailability.Complete) {
            Td.localFilePath(fileId)?.let { path ->
                _downloaded.value = 1f
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

    override fun episode(other: MediaItem): PlayerMedia = TelegramPlayerMedia(other, chatTitle)

    override suspend fun tdlibVersion(): String? = runCatching {
        (Td.awaitAuthorizedSession().client.getOption("version").valueOrNull as? OptionValueString)?.value
    }.getOrNull()

    override fun release() {
        watcher?.cancel()
    }

    private companion object {
        const val TAG = "TelegramPlayerMedia"
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
