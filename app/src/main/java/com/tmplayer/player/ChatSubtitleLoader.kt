package com.tmplayer.player

import com.tmplayer.data.ChatSubtitleCalls
import com.tmplayer.data.ChatSubtitleFinder
import com.tmplayer.data.ChatSubtitleFound
import com.tmplayer.data.ChatSubtitleLoad
import com.tmplayer.data.ChatSubtitleLoaded
import com.tmplayer.data.ChatSubtitleWords
import com.tmplayer.data.LiveChatSubtitleCalls
import com.tmplayer.i18n.L
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * "In this chat" for the player (EG-F48): the subtitle files posted next to the video, found with
 * one search ([ChatSubtitleFinder]) and laid over the video through [SubtitleFiles], the path a
 * picked or online subtitle takes. The track picker asks [find] when it opens for subtitles and
 * [attach] when a file is chosen; a failure of any kind is a sentence, never an exception.
 */
class ChatSubtitleLoader(
    private val files: SubtitleFiles,
    private val finder: ChatSubtitleFinder = ChatSubtitleFinder.shared,
    private val calls: ChatSubtitleCalls = LiveChatSubtitleCalls,
) {

    /** The files to offer for the video, best first; empty offline, under a kids lock that leaves the chat out, or with none. */
    suspend fun find(chatId: Long, messageId: Long, fileName: String, caption: String, preferred: List<String>): List<ChatSubtitles.Hit> =
        (finder.find(chatId, messageId, fileName, caption, preferred) as? ChatSubtitleFound.Hits)?.hits.orEmpty()

    /**
     * Downloads [hit] and lays it over the video. Null when done, else the sentence to show. A file
     * that is gone makes the finder forget this video, so the next open lists what is still there.
     */
    suspend fun attach(chatId: Long, messageId: Long, hit: ChatSubtitles.Hit, video: String, caption: String, handover: Boolean): String? {
        val loaded = ChatSubtitleLoad.load(calls, chatId, hit, video, caption, java.util.Locale.getDefault().language)
        return when (loaded) {
            is ChatSubtitleLoaded.Refused -> {
                if (loaded.reason == L.tracksInChatGone) finder.forget(chatId, messageId)
                loaded.reason
            }
            is ChatSubtitleLoaded.Ready -> withContext(Dispatchers.Main) {
                if (files.attachChat(loaded, chatId, hit, handover && hit.doc.canBeSaved)) null else L.tracksFileUnreadable(loaded.label)
            }
        }
    }

    companion object {
        fun title(hit: ChatSubtitles.Hit): String = ChatSubtitleWords.title(hit)

        fun detail(hit: ChatSubtitles.Hit): String? = ChatSubtitleWords.detail(hit)
    }
}
