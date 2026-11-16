package com.tmplayer.data

import dev.g000sha256.tdl.dto.Audio
import dev.g000sha256.tdl.dto.Document
import dev.g000sha256.tdl.dto.File
import dev.g000sha256.tdl.dto.FormattedText
import dev.g000sha256.tdl.dto.LocalFile
import dev.g000sha256.tdl.dto.Message
import dev.g000sha256.tdl.dto.MessageAudio
import dev.g000sha256.tdl.dto.MessageContent
import dev.g000sha256.tdl.dto.MessageDocument
import dev.g000sha256.tdl.dto.MessageSenderUser
import dev.g000sha256.tdl.dto.MessageVoiceNote
import dev.g000sha256.tdl.dto.RemoteFile
import dev.g000sha256.tdl.dto.VoiceNote
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TMPlayer plays videos only (user, 2026-11-16): music, voice messages and audio files sent as
 * documents never become a tile in a chat's grid, Home, search, downloads or favourites. Every one
 * of those lists is built by [MediaMapper], so this pins it there: a page that mixes them with a
 * video keeps the video alone.
 */
class VideosOnlyTest {

    private val caption = FormattedText("", emptyArray())

    private fun file(id: Int) = File(id, 1_000_000, 1_000_000, LocalFile("", true, false, false, false, 0, 0, 0), RemoteFile("r$id", "u$id", false, true, 1_000_000))

    private fun message(id: Long, content: MessageContent) = Message(
        id = id, senderId = MessageSenderUser(1), receiverId = null, chatId = 7, sendingState = null, schedulingState = null,
        isOutgoing = false, isPinned = false, isFromOffline = false, canBeSaved = true, hasTimestampedMedia = false,
        isChannelPost = true, isPaidStarSuggestedPost = false, isPaidGramSuggestedPost = false, containsUnreadMention = false,
        containsUnreadPollVotes = false, date = 1_700_000_000, editDate = 0, forwardInfo = null, importInfo = null,
        interactionInfo = null, unreadReactions = emptyArray(), factCheck = null, suggestedPostInfo = null, replyTo = null,
        topicId = null, selfDestructType = null, selfDestructIn = 0.0, autoDeleteIn = 0.0, viaBotUserId = 0,
        guestBotCallerId = null, senderBusinessBotUserId = 0, senderBoostCount = 0, senderTag = "", paidMessageStarCount = 0,
        authorSignature = "", mediaAlbumId = 0, effectId = 0, restrictionInfo = null, summaryLanguageCode = "",
        content = content, ephemeralContent = null, replyMarkup = null, ephemeralMessageId = 0, chatInstance = 0,
    )

    private fun music(name: String = "Track 01.mp3", mime: String = "audio/mpeg") =
        MessageAudio(Audio(215, "Song", "Band", name, mime, null, null, emptyArray(), file(11)), caption)

    private fun voice() = MessageVoiceNote(VoiceNote(12, ByteArray(0), "audio/ogg", null, file(12)), caption, false)

    private fun document(name: String, mime: String, id: Int = 13) = MessageDocument(Document(name, mime, null, null, file(id)), caption)

    /** Audio sent as a file, the shapes a chat holds: by name, by type, and both. */
    private val audioDocuments = listOf(
        "Lecture 3.mp3" to "audio/mpeg",
        "Lecture 3.m4a" to "audio/mp4",
        "Voice.ogg" to "audio/ogg",
        "Voice.opus" to "audio/opus",
        "Album.flac" to "audio/flac",
        "Talk.wav" to "audio/x-wav",
        "Talk.aac" to "application/octet-stream",
        "untitled" to "audio/mpeg",
    )

    @Test
    fun `music and voice messages are never a video`() {
        assertNull(MediaMapper.fromMessage(message(1, music())))
        assertNull(MediaMapper.fromMessage(message(2, voice())))
        assertFalse(MediaMapper.isVideoContent(music()))
        assertFalse(MediaMapper.isVideoContent(voice()))
        // Even when the sender's client named the music like a video file.
        assertNull(MediaMapper.fromMessage(message(3, music(name = "Song.mp4", mime = "video/mp4"))))
    }

    @Test
    fun `audio sent as a file is never a video`() {
        audioDocuments.forEachIndexed { i, (name, mime) ->
            assertNull("$name $mime", MediaMapper.fromMessage(message(10L + i, document(name, mime))))
            assertFalse("$name $mime", MediaMapper.isVideoContent(document(name, mime)))
            assertFalse("$name $mime", MediaText.looksLikeVideo(name, mime))
        }
    }

    @Test
    fun `a page that mixes audio with a video lists the video alone`() {
        val page = listOf(
            message(1, music()),
            message(2, voice()),
            message(3, document("Lecture 3.mp3", "audio/mpeg")),
            message(4, document("Holiday.mkv", "application/octet-stream", id = 40)),
            message(5, document("Voice.ogg", "audio/ogg")),
        )
        val listed = MediaMapper.screen(page, chatProtected = false).items
        assertEquals(listOf(4L), listed.map { it.messageId })
        assertTrue(listed.none { it.mimeType.startsWith("audio/") })
        // The control: the same builder does make a tile of a video.
        assertNotNull(MediaMapper.fromMessage(page[3]))
    }
}
