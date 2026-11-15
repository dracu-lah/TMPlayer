package com.tmplayer.data

import com.tmplayer.i18n.L
import dev.g000sha256.tdl.TdlClient
import dev.g000sha256.tdl.dto.Message
import dev.g000sha256.tdl.dto.MessageAudio
import dev.g000sha256.tdl.dto.MessageVoiceNote
import dev.g000sha256.tdl.dto.SearchMessagesFilterAudio
import dev.g000sha256.tdl.dto.SearchMessagesFilterVoiceNote
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * A chat's audio: music files, talks and lectures posted as audio, and voice messages, played
 * with the same player as a playlist ([PlayQueue]). Telegram keeps the two kinds apart, so each
 * is searched with its own filter and keeps its own place.
 */
object ChatAudio {

    /** One page of a chat's audio, newest first, with where each search got to. */
    data class Page(
        val items: List<MediaItem>,
        val nextAudio: Long,
        val nextVoice: Long,
        val audioDone: Boolean,
        val voiceDone: Boolean,
    ) {
        val done: Boolean get() = audioDone && voiceDone
    }

    /** How many audio files and voice messages [chatId] holds, from TDLib's count; 0 when unknown. */
    suspend fun count(td: TdlClient, chatId: Long): Int = withContext(Dispatchers.IO) {
        suspend fun one(filter: dev.g000sha256.tdl.dto.SearchMessagesFilter): Int {
            val local = td.getChatMessageCount(chatId, null, filter, true).valueOrNull?.count
            if (local != null && local >= 0) return local
            return td.getChatMessageCount(chatId, null, filter, false).valueOrNull?.count?.coerceAtLeast(0) ?: 0
        }
        // Side by side: either may be a round trip to the server, and neither waits on the other.
        coroutineScope {
            val audio = async { one(SearchMessagesFilterAudio()) }
            val voice = async { one(SearchMessagesFilterVoiceNote()) }
            audio.await() + voice.await()
        }
    }

    /** The next page after [after], or the first with null. */
    suspend fun page(td: TdlClient, chatId: Long, after: Page? = null, limit: Int = PAGE): Page = withContext(Dispatchers.IO) {
        coroutineScope {
            val chatProtected = td.getChat(chatId).valueOrNull?.hasProtectedContent ?: false
            val audio = async {
                if (after?.audioDone == true) null
                else td.searchChatMessages(chatId, null, "", null, after?.nextAudio ?: 0, 0, limit, SearchMessagesFilterAudio()).valueOrNull
            }
            val voice = async {
                if (after?.voiceDone == true) null
                else td.searchChatMessages(chatId, null, "", null, after?.nextVoice ?: 0, 0, limit, SearchMessagesFilterVoiceNote()).valueOrNull
            }
            val a = audio.await()
            val v = voice.await()
            val items = (a?.messages.orEmpty().toList() + v?.messages.orEmpty().toList())
                .mapNotNull { itemOf(it, chatProtected) }
                .sortedByDescending { it.messageId }
            Page(
                items = items,
                nextAudio = a?.nextFromMessageId ?: after?.nextAudio ?: 0,
                nextVoice = v?.nextFromMessageId ?: after?.nextVoice ?: 0,
                audioDone = after?.audioDone == true || a == null || a.messages.isEmpty() || a.nextFromMessageId == 0L,
                voiceDone = after?.voiceDone == true || v == null || v.messages.isEmpty() || v.nextFromMessageId == 0L,
            )
        }
    }

    /** [message] as something the player can play, or null for anything but audio. */
    fun itemOf(message: Message, chatProtected: Boolean = false): MediaItem? {
        if (message.schedulingState != null || message.sendingState != null) return null
        if (message.selfDestructType != null) return null
        val saveable = !chatProtected && message.canBeSaved
        return when (val content = message.content) {
            is MessageAudio -> {
                val audio = content.audio
                val named = listOf(audio.performer, audio.title).filter { it.isNotBlank() }.joinToString(" · ")
                MediaItem(
                    chatId = message.chatId,
                    messageId = message.id,
                    fileId = audio.audio.id,
                    title = named.ifBlank { audio.fileName.substringBeforeLast('.').ifBlank { L.mediaUntitledAudio } },
                    fileName = audio.fileName,
                    sizeBytes = MediaMapper.fileSize(audio.audio.size, audio.audio.expectedSize),
                    durationSec = audio.duration,
                    mimeType = audio.mimeType.ifBlank { "audio/mpeg" },
                    thumbnailFileId = audio.albumCoverThumbnail?.file?.id ?: 0,
                    miniThumbnail = audio.albumCoverMinithumbnail?.data,
                    date = message.date,
                    onDevice = MediaMapper.onDevice(audio.audio),
                    caption = MediaMapper.captionOf(content.caption.text),
                    canBeSaved = saveable,
                )
            }
            is MessageVoiceNote -> {
                val voice = content.voiceNote
                MediaItem(
                    chatId = message.chatId,
                    messageId = message.id,
                    fileId = voice.voice.id,
                    title = MediaMapper.captionOf(content.caption.text).lineSequence().firstOrNull()?.takeIf { it.isNotBlank() }
                        ?: L.mediaVoiceMessage,
                    sizeBytes = MediaMapper.fileSize(voice.voice.size, voice.voice.expectedSize),
                    durationSec = voice.duration,
                    mimeType = voice.mimeType.ifBlank { "audio/ogg" },
                    thumbnailFileId = 0,
                    miniThumbnail = null,
                    date = message.date,
                    onDevice = MediaMapper.onDevice(voice.voice),
                    caption = MediaMapper.captionOf(content.caption.text),
                    canBeSaved = saveable,
                )
            }
            else -> null
        }
    }

    /** Whether [item] is sound alone: the player shows its name and cover rather than a picture. */
    fun isAudio(item: MediaItem): Boolean = item.mimeType.startsWith("audio/", ignoreCase = true)

    const val PAGE = 50
}

/**
 * More of a chat's audio for a queue that has only the pages read so far: [more] reads the next
 * page and answers its files, or null when there are none left (or the chat cannot be asked).
 * Lives on past the list that started it, so a queue of thousands goes on filling while it plays.
 */
fun interface QueueFeed {
    suspend fun more(): List<MediaItem>?
}

/**
 * The list a chat's audio was started from, so the player goes on to the next file when one ends
 * (and back with Previous), the way it goes on to a show's next episode. Set when a file is played
 * from the list, and left alone by everything else: a video played afterwards is not in it.
 *
 * The order is a [QueuePlan]: as posted (oldest first) or shuffled, and over again after the last
 * file when repeat is on. Shuffle and repeat are kept for the session, so a choice made on the
 * list is still there for the next course. A chat of thousands is read a page at a time: when the
 * file playing is within [PREFETCH] of an end the queue can still grow at, the [QueueFeed] is asked
 * for the next page in the background, so playback never waits on a whole chat.
 */
object PlayQueue {
    /** How many files from the end of what is loaded the next page is asked for. */
    const val PREFETCH = 5

    @Volatile
    private var plan: QueuePlan = QueuePlan.Empty

    @Volatile
    private var shuffleOn = false

    @Volatile
    private var repeatOn = false

    @Volatile
    private var repeatOneOn = false

    @Volatile
    private var feed: QueueFeed? = null

    @Volatile
    private var filling = false

    private val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.IO)

    /** The files in the order they play. */
    val items: List<MediaItem> get() = plan.order

    val shuffle: Boolean get() = shuffleOn
    val repeatAll: Boolean get() = repeatOn

    /** Repeat one: the file playing plays again when it ends ([atEnd]); off when Repeat all is on. */
    val repeatOne: Boolean get() = repeatOneOn

    /**
     * [list] as the queue, in the order posted or shuffled as set; a shuffle plays [current]
     * first (the file the viewer chose, or a random one when null). [more] reads the pages the
     * list has not.
     */
    fun set(list: List<MediaItem>, current: MediaItem? = null, more: QueueFeed? = null) {
        feed = more
        plan = QueuePlan.of(list, shuffle = shuffleOn, repeatAll = repeatOn, current = current, repeatOne = repeatOneOn)
    }

    fun clear() {
        feed = null
        plan = QueuePlan.Empty.withRepeatAll(repeatOn).withRepeatOne(repeatOneOn)
    }

    /** Back to nothing queued and both modes off (a sign out, a test). */
    fun reset() {
        shuffleOn = false
        repeatOn = false
        repeatOneOn = false
        clear()
    }

    /** Shuffle on or off from here: [current] keeps playing, and the files not yet heard follow in the new order. */
    fun setShuffle(on: Boolean, current: MediaItem? = null) {
        shuffleOn = on
        plan = plan.withShuffle(on, current)
    }

    fun setRepeatAll(on: Boolean) {
        repeatOn = on
        if (on) repeatOneOn = false
        plan = plan.withRepeatAll(on)
    }

    /** Repeat one on or off; on turns Repeat all off, the modes being off, one, all. */
    fun setRepeatOne(on: Boolean) {
        repeatOneOn = on
        if (on) repeatOn = false
        plan = plan.withRepeatOne(on)
    }

    /**
     * What the player does when [item] ends: [QueuePlan.AtEnd.Replay] under Repeat one for a file
     * of the queue (video and files from elsewhere are never repeated), else the usual next.
     */
    fun atEnd(item: MediaItem): QueuePlan.AtEnd =
        if (ChatAudio.isAudio(item)) plan.atEnd(item) else QueuePlan.AtEnd.Continue

    /** More files of the chat joined to the queue, around [current] if it is playing. */
    fun add(more: List<MediaItem>, current: MediaItem? = null) {
        plan = plan.merged(more, current)
    }

    /** The files either side of [item], or null when it was not started from the list. */
    fun stepsFor(item: MediaItem): EpisodeSteps? {
        val now = plan
        val steps = now.stepsFor(item) ?: return null
        val at = now.positionOf(item)
        // Pages come newest first, so the posted order grows at its start (older files) and a
        // shuffle anywhere: read the next page when the file playing nears that edge, so Next
        // never meets an end that is not one.
        val nearEdge = if (now.shuffle) now.size - 1 - at < PREFETCH else at < PREFETCH
        if (feed != null && nearEdge) fill(item)
        return steps
    }

    private fun fill(current: MediaItem) {
        val source = feed ?: return
        if (filling) return
        filling = true
        scope.launch {
            try {
                val page = source.more()
                // Null is the end of the chat (or no way to ask); an empty page only had nothing playable.
                if (page == null) {
                    if (feed === source) feed = null
                } else if (feed === source && page.isNotEmpty()) {
                    add(page, current)
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                // The next file still plays from what is loaded; the next start of one asks again.
            } finally {
                filling = false
            }
        }
    }
}
