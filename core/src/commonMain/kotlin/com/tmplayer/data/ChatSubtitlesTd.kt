package com.tmplayer.data

import com.tmplayer.online.SubtitleText
import com.tmplayer.platform.JobFailures
import com.tmplayer.player.ChatSubtitles
import dev.g000sha256.tdl.TdlClient
import dev.g000sha256.tdl.TdlResult
import dev.g000sha256.tdl.dto.MessageDocument
import dev.g000sha256.tdl.dto.MessageReplyToMessage
import dev.g000sha256.tdl.dto.MessageTopicForum
import dev.g000sha256.tdl.dto.SearchMessagesFilterDocument
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/*
 * Subtitle files posted in the chat (EG-F48). ChatSubtitles (pure, player package) says which of a
 * chat's documents belong to a video; this file asks TDLib for them inside a budget, and turns a
 * downloaded one into text. The player lists what [ChatSubtitleFinder] found under "In this chat"
 * and hands the chosen file's text to the same loading path a picked or online subtitle takes.
 */

/** The video playing, as far as the chat knows it: what the poster linked it to. */
data class ChatSubtitleVideo(
    val date: Int = 0,
    val albumId: Long = 0,
    val replyToMessageId: Long = 0,
    val topicId: Int = 0,
)

/** What one search of the chat's documents answered. */
sealed interface ChatSubtitleDocs {
    data class Found(val docs: List<ChatSubtitles.Doc>) : ChatSubtitleDocs
    data class Failed(val message: String) : ChatSubtitleDocs
}

/** What fetching one file answered. */
sealed interface ChatSubtitleFetch {
    class Bytes(val bytes: ByteArray) : ChatSubtitleFetch
    data object TooBig : ChatSubtitleFetch

    /** The message or its file is gone from the chat (deleted, or the chat closed to the account). */
    data object Gone : ChatSubtitleFetch
    data object Offline : ChatSubtitleFetch
    class Failed(val message: String) : ChatSubtitleFetch
}

/** The three questions put to Telegram, so a test can count them. */
interface ChatSubtitleCalls {
    /** The video's links in the chat, read from what TDLib already holds; null when it is not known. */
    suspend fun video(chatId: Long, messageId: Long): ChatSubtitleVideo?

    /**
     * One search of the chat's documents: [limit] of them around [fromMessageId], [offset] negative
     * for the newer side too. This is the call the budget counts.
     */
    suspend fun documents(chatId: Long, topicId: Int, fromMessageId: Long, offset: Int, limit: Int): ChatSubtitleDocs

    /** Downloads [doc] and returns its bytes, never more than [maxBytes]. */
    suspend fun fetch(chatId: Long, doc: ChatSubtitles.Doc, maxBytes: Long): ChatSubtitleFetch
}

/** What a lookup for one video found. */
sealed interface ChatSubtitleFound {
    /** Files to offer, best first, at most [ChatSubtitles.TOP]. */
    data class Hits(val hits: List<ChatSubtitles.Hit>) : ChatSubtitleFound

    /** Nothing in the chat belongs to this video. */
    data object None : ChatSubtitleFound

    /** The kids lock leaves this chat out: no lookup was made. */
    data object Blocked : ChatSubtitleFound

    /** The search did not answer (offline, a wait Telegram asked for, a timeout): the entry stays hidden. */
    data object Unavailable : ChatSubtitleFound
}

/**
 * Finds the subtitle files for a video, with a budget of ONE search per video: the documents around
 * the video (the same album, its replies and its neighbours are all in that window) are weighed by
 * [ChatSubtitles.rank], so a chat holding two thousand subtitle files costs one call and shows ten.
 * An answer is kept for [TTL_MS], so opening the picker again, or two pickers at once, asks nothing
 * more; a failure is never kept. A FLOOD_WAIT is not waited for here: the entry simply is not
 * offered, which is cheaper than holding the player's picker for a minute.
 */
class ChatSubtitleFinder(
    private val calls: ChatSubtitleCalls,
    private val kids: suspend () -> Set<Long>? = { ChatGate.awaitReady() },
    private val now: () -> Long = { System.currentTimeMillis() },
    private val timeoutMs: Long = TIMEOUT_MS,
) {
    private val lock = Mutex()
    private val kept = LinkedHashMap<String, Pair<Long, ChatSubtitleFound>>()

    suspend fun find(
        chatId: Long,
        messageId: Long,
        fileName: String,
        caption: String = "",
        preferred: List<String> = emptyList(),
    ): ChatSubtitleFound {
        if (chatId == 0L || messageId == 0L) return ChatSubtitleFound.None
        if (!KidsLock.allows(kids(), chatId)) return ChatSubtitleFound.Blocked
        // Under the profile open when it was asked (HX88): an answer that lands after a switch is
        // kept under the old profile's turn, which nobody asks again.
        val key = "${Accounts.stamp.generation}|$chatId:$messageId"
        return lock.withLock {
            kept[key]?.takeIf { now() - it.first < TTL_MS }?.let { return@withLock it.second }
            val answer = try {
                withTimeoutOrNull(timeoutMs) { search(chatId, messageId, fileName, caption, preferred) }
                    ?: ChatSubtitleFound.Unavailable
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                JobFailures.handle("chatSubtitles", e)
                ChatSubtitleFound.Unavailable
            }
            if (answer != ChatSubtitleFound.Unavailable) {
                kept[key] = now() to answer
                while (kept.size > KEEP) kept.remove(kept.keys.first())
            }
            answer
        }
    }

    /** Forgets what was found for a video, for a Try again. */
    suspend fun forget(chatId: Long, messageId: Long) {
        lock.withLock { kept.remove("${Accounts.stamp.generation}|$chatId:$messageId") }
    }

    private suspend fun search(chatId: Long, messageId: Long, fileName: String, caption: String, preferred: List<String>): ChatSubtitleFound {
        val known = calls.video(chatId, messageId)
        val docs = when (val answer = calls.documents(chatId, known?.topicId ?: 0, messageId, -WINDOW / 2, WINDOW)) {
            is ChatSubtitleDocs.Found -> answer.docs
            is ChatSubtitleDocs.Failed -> return ChatSubtitleFound.Unavailable
        }
        val video = ChatSubtitles.Video(
            messageId = messageId,
            fileName = fileName,
            caption = caption,
            date = known?.date ?: 0,
            albumId = known?.albumId ?: 0,
            replyToMessageId = known?.replyToMessageId ?: 0,
        )
        val hits = ChatSubtitles.rank(video, docs, preferred)
        return if (hits.isEmpty()) ChatSubtitleFound.None else ChatSubtitleFound.Hits(hits)
    }

    companion object {
        /** The one finder of the app: its kept answers serve every picker, so a video asks Telegram once. */
        val shared: ChatSubtitleFinder by lazy { ChatSubtitleFinder(LiveChatSubtitleCalls) }

        /** Documents asked for in the one search: half of them older than the video, half newer. */
        const val WINDOW = 100

        /** The whole lookup, after which the entry is not offered. */
        const val TIMEOUT_MS = 8_000L

        /** How long a lookup stays good: the picker opened again inside it asks nothing. */
        const val TTL_MS = 5 * 60 * 1000L

        /** Videos remembered at once. */
        const val KEEP = 32
    }
}

/** A downloaded subtitle file turned into text both players read, or the reason it is not one. */
object ChatSubtitleText {

    sealed interface Result {
        /** [extension] is the format of [text] (`sub` is MicroDVD, to be rewritten as SubRip where the player cannot read it). */
        data class Text(val extension: String, val text: String, val language: String?) : Result
        data object TooBig : Result

        /** A `.sub` of pictures (VobSub): the existing refusal. */
        data object Vobsub : Result

        /** Not a subtitle: empty, binary, the wrong format or a zip with none inside. */
        data object Unreadable : Result
    }

    /** The most entries and bytes of a zip that are looked at: a season pack is dozens, not thousands. */
    private const val ZIP_ENTRIES = 600
    private const val ZIP_BYTES = 40L * 1024 * 1024

    /**
     * [bytes] of the file named [fileName] as text. A zip gives the entry for [season] and
     * [episode] (the only one, or the first, when the names number none); anything else is read
     * in the code page of its own language tag, else of [viewerLanguage] (see [SubtitleText.decode]).
     */
    suspend fun read(
        bytes: ByteArray,
        fileName: String,
        season: Int? = null,
        episode: Int? = null,
        viewerLanguage: String? = null,
    ): Result {
        val named = ChatSubtitles.tagsOf(fileName).language
        val language = named ?: viewerLanguage
        if (bytes.isEmpty()) return Result.Unreadable
        if (SubtitleText.isZip(bytes)) {
            if (bytes.size > ChatSubtitles.MAX_ZIP_BYTES) return Result.TooBig
            // The directory is read first and bounded, so a zip of thousands of entries or of a bomb is refused unopened.
            val entries = ZipArchive.entries(BytesSource(bytes)) ?: return Result.Unreadable
            val inside = entries.filter { !it.isDirectory && ChatSubtitles.extensionOf(it.name) in SubtitleText.FORMATS }
            if (inside.isEmpty() || entries.size > ZIP_ENTRIES || inside.sumOf { it.uncompressedSize } > ZIP_BYTES) return Result.Unreadable
            if (inside.any { it.encrypted }) return Result.Unreadable
            val picked = withContext(Dispatchers.Default) {
                runCatching { SubtitleText.fromArchive(bytes, season, episode, language, strict = true) }.getOrNull()
            } ?: return Result.Unreadable
            return checked(picked.extension, picked.text, language)
        }
        if (bytes.size > ChatSubtitles.MAX_BYTES) return Result.TooBig
        val extension = ChatSubtitles.extensionOf(fileName)
        if (extension !in ChatSubtitles.EXTENSIONS) return Result.Unreadable
        val text = SubtitleText.decode(bytes, language)
        return checked(extension, text, language)
    }

    /** [text] when it has the shape of its format, so binary bytes named .srt are refused, not shown as letters. */
    private fun checked(extension: String, text: String, language: String?): Result {
        if (text.isBlank() || text.length > ChatSubtitles.MAX_BYTES) return Result.Unreadable
        // The other .sub is pictures, binary by nature: told apart before the binary test below.
        if (extension == "sub" && !MICRODVD.containsMatchIn(text)) return Result.Vobsub
        val sample = text.take(SAMPLE)
        val control = sample.count { it < ' ' && it != '\n' && it != '\r' && it != '\t' }
        val replaced = sample.count { it == '�' }
        if (control * 100 > sample.length || replaced * 20 > sample.length) return Result.Unreadable
        val fine = when (extension) {
            "srt", "vtt" -> text.contains("-->")
            "ass", "ssa" -> text.contains("[Events]", ignoreCase = true) || text.contains("Dialogue:", ignoreCase = true)
            "sub" -> true
            else -> false
        }
        return if (fine) Result.Text(extension, text, language) else Result.Unreadable
    }

    private const val SAMPLE = 4096
    private val MICRODVD = Regex("""^\s*\u0000?\{\d+\}\{\d+\}""", RegexOption.MULTILINE)

    private class BytesSource(private val bytes: ByteArray) : ZipSource {
        override val size: Long get() = bytes.size.toLong()
        override suspend fun read(offset: Long, length: Int): ByteArray? {
            if (offset < 0 || length < 0 || offset > bytes.size) return null
            val end = minOf(bytes.size.toLong(), offset + length).toInt()
            return bytes.copyOfRange(offset.toInt(), end)
        }
    }
}

/** The live TDLib client behind [ChatSubtitleCalls]. */
class TdChatSubtitleCalls(private val td: TdlClient) : ChatSubtitleCalls {

    override suspend fun video(chatId: Long, messageId: Long): ChatSubtitleVideo? = withContext(Dispatchers.IO) {
        val message = td.getMessage(chatId, messageId).valueOrNull ?: return@withContext null
        ChatSubtitleVideo(
            date = message.date,
            albumId = message.mediaAlbumId,
            replyToMessageId = replyOf(message, chatId),
            topicId = (message.topicId as? MessageTopicForum)?.forumTopicId ?: 0,
        )
    }

    override suspend fun documents(chatId: Long, topicId: Int, fromMessageId: Long, offset: Int, limit: Int): ChatSubtitleDocs =
        withContext(Dispatchers.IO) {
            val chatProtected = td.getChat(chatId).valueOrNull?.hasProtectedContent ?: false
            when (
                val found = td.searchChatMessages(
                    chatId = chatId,
                    topicId = if (topicId != 0) MessageTopicForum(topicId) else null,
                    query = "",
                    senderId = null,
                    fromMessageId = fromMessageId,
                    offset = offset,
                    limit = limit,
                    filter = SearchMessagesFilterDocument(),
                )
            ) {
                is TdlResult.Failure -> ChatSubtitleDocs.Failed(found.message)
                is TdlResult.Success -> ChatSubtitleDocs.Found(
                    found.result.messages.orEmpty().mapNotNull { message ->
                        val content = message?.content as? MessageDocument ?: return@mapNotNull null
                        // Not posted yet, withheld in this country, or a timer on it: not a file to lean on.
                        if (message.schedulingState != null || message.sendingState != null) return@mapNotNull null
                        if (!message.restrictionInfo?.restrictionReason.isNullOrEmpty() || message.selfDestructType != null) return@mapNotNull null
                        val file = content.document.document
                        ChatSubtitles.Doc(
                            messageId = message.id,
                            fileId = file.id,
                            fileName = content.document.fileName.orEmpty(),
                            size = maxOf(file.size, file.expectedSize),
                            date = message.date,
                            albumId = message.mediaAlbumId,
                            replyToMessageId = replyOf(message, chatId),
                            canBeSaved = message.canBeSaved && !chatProtected,
                        )
                    },
                )
            }
        }

    override suspend fun fetch(chatId: Long, doc: ChatSubtitles.Doc, maxBytes: Long): ChatSubtitleFetch = withContext(Dispatchers.IO) {
        val file = td.getFile(doc.fileId).valueOrNull
        if (file != null && maxOf(file.size, file.expectedSize) > maxBytes) return@withContext ChatSubtitleFetch.TooBig
        if (file?.local?.isDownloadingCompleted != true) {
            val done = withTimeoutOrNull(DOWNLOAD_TIMEOUT_MS) { td.downloadFile(doc.fileId, PRIORITY, 0, 0, true) }
                ?: return@withContext ChatSubtitleFetch.Offline
            if (done is TdlResult.Failure) return@withContext failed(chatId, doc, done.code, done.message)
        }
        when (val part = td.readFilePart(doc.fileId, 0, 0)) {
            is TdlResult.Failure -> failed(chatId, doc, part.code, part.message)
            is TdlResult.Success -> part.result.data.let { if (it.size > maxBytes) ChatSubtitleFetch.TooBig else ChatSubtitleFetch.Bytes(it) }
        }
    }

    /** A download that failed: gone when the message itself is, else the reason as Telegram gave it. */
    private suspend fun failed(chatId: Long, doc: ChatSubtitles.Doc, code: Int, message: String): ChatSubtitleFetch {
        if (Failures.floodWaitSeconds(message) != null) return ChatSubtitleFetch.Failed(message)
        return when (val lookup = td.getMessage(chatId, doc.messageId)) {
            is TdlResult.Failure ->
                if (StaleEntries.classify(lookup.code, lookup.message) is MessageLookup.Gone) ChatSubtitleFetch.Gone else ChatSubtitleFetch.Offline
            is TdlResult.Success -> if (code == 404 || message.contains("not found", ignoreCase = true)) ChatSubtitleFetch.Gone else ChatSubtitleFetch.Failed(message)
        }
    }

    private fun replyOf(message: dev.g000sha256.tdl.dto.Message, chatId: Long): Long =
        (message.replyTo as? MessageReplyToMessage)?.takeIf { it.chatId == chatId }?.messageId ?: 0L

    private companion object {
        const val DOWNLOAD_TIMEOUT_MS = 20_000L
        const val PRIORITY = 16
    }
}

/**
 * [TdChatSubtitleCalls] on whatever session is signed in and connected, waited for only a moment:
 * a device with no connection answers "unavailable" (the entry stays hidden, the local picker is
 * untouched) instead of holding the picker.
 */
object LiveChatSubtitleCalls : ChatSubtitleCalls {
    private const val WAIT_MS = 3_000L

    private suspend fun client(): TdlClient? = Td.awaitConnectedSessionOrNull(WAIT_MS)?.client

    override suspend fun video(chatId: Long, messageId: Long): ChatSubtitleVideo? =
        client()?.let { TdChatSubtitleCalls(it).video(chatId, messageId) }

    override suspend fun documents(chatId: Long, topicId: Int, fromMessageId: Long, offset: Int, limit: Int): ChatSubtitleDocs =
        client()?.let { TdChatSubtitleCalls(it).documents(chatId, topicId, fromMessageId, offset, limit) }
            ?: ChatSubtitleDocs.Failed("offline")

    override suspend fun fetch(chatId: Long, doc: ChatSubtitles.Doc, maxBytes: Long): ChatSubtitleFetch =
        client()?.let { TdChatSubtitleCalls(it).fetch(chatId, doc, maxBytes) } ?: ChatSubtitleFetch.Offline
}

/** A chosen chat file, downloaded and read: the text to lay over the video, or the sentence saying why not. */
sealed interface ChatSubtitleLoaded {
    /** [extension] is `sub` for MicroDVD, which a player that cannot read it has rewritten as SubRip. */
    data class Ready(val extension: String, val text: String, val label: String, val language: String?) : ChatSubtitleLoaded
    data class Refused(val reason: String) : ChatSubtitleLoaded
}

/** What a listed file says of itself, in the viewer's words: shared by the phone, the television and the desktop. */
object ChatSubtitleWords {
    /** The row's title: the file's name as it was posted. */
    fun title(hit: ChatSubtitles.Hit): String = hit.doc.fileName

    /** Under the title: the language its name gives, Forced, hearing impaired, and Compressed for a zip; null for none of them. */
    fun detail(hit: ChatSubtitles.Hit): String? = listOfNotNull(
        hit.language?.let { com.tmplayer.online.OnlineSubtitles.languageName(it) },
        if (hit.forced) com.tmplayer.i18n.L.tracksForced else null,
        if (hit.hearing) com.tmplayer.i18n.L.onlineHearingImpaired else null,
        if (hit.archive) com.tmplayer.i18n.L.tracksInChatCompressed else null,
    ).ifEmpty { null }?.joinToString("  ·  ")
}

/** Downloads a listed chat file and reads it, the same on phone, television and desktop. */
object ChatSubtitleLoad {

    /**
     * The file of [hit] from [chatId], read as subtitle text. [video] is the playing video's file
     * name and [caption], whose episode picks the entry of a season zip. Every way it can fail is a
     * sentence in [ChatSubtitleLoaded.Refused]: a deleted message, no connection, a file that is too
     * big, VobSub pictures, bytes that are not a subtitle.
     */
    suspend fun load(
        calls: ChatSubtitleCalls,
        chatId: Long,
        hit: ChatSubtitles.Hit,
        video: String,
        caption: String = "",
        viewerLanguage: String? = null,
    ): ChatSubtitleLoaded {
        val limit = if (hit.archive) ChatSubtitles.MAX_ZIP_BYTES else ChatSubtitles.MAX_BYTES
        val bytes = when (val got = calls.fetch(chatId, hit.doc, limit)) {
            is ChatSubtitleFetch.Bytes -> got.bytes
            ChatSubtitleFetch.TooBig -> return ChatSubtitleLoaded.Refused(com.tmplayer.i18n.L.tracksFileTooBig)
            ChatSubtitleFetch.Gone -> return ChatSubtitleLoaded.Refused(com.tmplayer.i18n.L.tracksInChatGone)
            ChatSubtitleFetch.Offline -> return ChatSubtitleLoaded.Refused(com.tmplayer.i18n.L.onlineNoticeOffline)
            is ChatSubtitleFetch.Failed -> return ChatSubtitleLoaded.Refused(Failures.humanise(got.message))
        }
        val parsed = MediaName.parse(video, caption)
        return when (val read = ChatSubtitleText.read(bytes, hit.doc.fileName, parsed.season, parsed.episode, viewerLanguage)) {
            is ChatSubtitleText.Result.Text -> ChatSubtitleLoaded.Ready(read.extension, read.text, label(hit), read.language)
            ChatSubtitleText.Result.TooBig -> ChatSubtitleLoaded.Refused(com.tmplayer.i18n.L.tracksFileTooBig)
            ChatSubtitleText.Result.Vobsub -> ChatSubtitleLoaded.Refused(com.tmplayer.i18n.L.tracksFileVobsub)
            ChatSubtitleText.Result.Unreadable -> ChatSubtitleLoaded.Refused(com.tmplayer.i18n.L.onlineNoticeUnreadable)
        }
    }

    /** The track's name: the file's name without its extension. */
    fun label(hit: ChatSubtitles.Hit): String =
        hit.doc.fileName.substringBeforeLast('.', hit.doc.fileName).ifBlank { hit.doc.fileName }

    /**
     * Writes [loaded] under [folder] as UTF-8 and returns the file, keeping the newest [KEEP] files
     * there (older ones are deleted), so the copies stay bounded. Null when it cannot be written.
     */
    fun write(folder: java.io.File, chatId: Long, hit: ChatSubtitles.Hit, loaded: ChatSubtitleLoaded.Ready, text: String = loaded.text, extension: String = loaded.extension): java.io.File? =
        runCatching {
            folder.mkdirs()
            val old = folder.listFiles { f -> f.isFile }?.sortedBy { it.lastModified() }.orEmpty()
            old.take((old.size - KEEP + 1).coerceAtLeast(0)).forEach { it.delete() }
            val file = java.io.File(folder, "chat${chatId}_${hit.doc.messageId}.$extension")
            file.writeText(text, Charsets.UTF_8)
            file
        }.getOrNull()

    private const val KEEP = 40
}
