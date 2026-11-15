package com.tmplayer.data

import dev.g000sha256.tdl.dto.Message
import dev.g000sha256.tdl.dto.MessageDocument

/**
 * The archive in [message] worth a row under the chat's videos ([Archives.worthListing]: named
 * like a video, or large enough to be one), or null when it holds anything else. Only documents:
 * a video Telegram sent as a video is never an archive, whatever it is called. The rest of
 * [Archives] is pure and shared with the browser build.
 */
internal fun archiveOf(message: Message): ArchiveFile? {
    val content = message.content as? MessageDocument ?: return null
    val document = content.document
    val name = document.fileName.orEmpty()
    val mime = document.mimeType.orEmpty()
    val kind = Archives.kindOf(name, mime) ?: return null
    val size = document.document.size.takeIf { it > 0 } ?: document.document.expectedSize
    if (!Archives.worthListing(name, size, mime)) return null
    return ArchiveFile(
        chatId = message.chatId,
        messageId = message.id,
        fileId = document.document.id,
        fileName = name,
        sizeBytes = size,
        date = message.date,
        kind = kind,
        volume = Archives.isVolume(name),
        locked = Archives.looksLocked(name, content.caption.text),
    )
}
