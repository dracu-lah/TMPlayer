package com.tmplayer.data

/**
 * What Telegram said when a kept entry (Continue watching, Watch later, Watched, a download) was
 * looked up again by its message: the video as it is now, gone for good, or no answer worth
 * acting on.
 *
 * Only [Gone] lets anything kept be removed. A network that is down, a FLOOD_WAIT, a chat TDLib has
 * not loaded yet (a fresh database, a backup restored on another device) or an error nobody has
 * met before are all [Unreachable]: the entry stays, and the next look decides.
 */
sealed interface MessageLookup {
    data class Found(val item: MediaItem) : MessageLookup

    data class Gone(val reason: GoneReason) : MessageLookup

    data object Unreachable : MessageLookup
}

/** Why a kept video cannot be had any more. */
enum class GoneReason {
    /** The message was deleted from its chat. */
    Deleted,

    /** The chat is closed to this account: left, kicked, or a channel made private. */
    ChatClosed,

    /** The message is there but holds no video any more: edited into a photo or a text. */
    NotVideo,
}

/** The rules for telling a kept entry that is gone from one Telegram merely did not answer for. */
object StaleEntries {

    /**
     * [MessageLookup.Gone] or [MessageLookup.Unreachable] from a failed `getMessage` ([code] and
     * [message] as TDLib gives them).
     *
     * TDLib answers 404 for a message that does not exist (its documented answer for getMessage),
     * and the server's MESSAGE_ID_INVALID says the same. A chat closed to the account comes back as
     * CHANNEL_PRIVATE and its relatives, or TDLib's own "Can't access the chat". "Chat not found"
     * is not gone: TDLib says it for a chat it has not loaded yet, which after a fresh sign in or a
     * restored backup is every chat until the list arrives.
     */
    fun classify(code: Int, message: String?): MessageLookup {
        val raw = message?.trim().orEmpty()
        if (raw.isEmpty() && code != NOT_FOUND) return MessageLookup.Unreachable
        // Waits and outages first: a FLOOD_WAIT on a message that also reads as missing is a wait.
        if (code == FLOOD || code == TOO_MANY || code >= SERVER || raw.contains("FLOOD_WAIT")) return MessageLookup.Unreachable
        if (CHAT_CLOSED.any { raw.contains(it, ignoreCase = true) }) return MessageLookup.Gone(GoneReason.ChatClosed)
        if (raw.contains("Chat not found", ignoreCase = true)) return MessageLookup.Unreachable // i18n-ok: TDLib text, matched
        if (code == NOT_FOUND || MESSAGE_GONE.any { raw.contains(it, ignoreCase = true) }) return MessageLookup.Gone(GoneReason.Deleted)
        return MessageLookup.Unreachable
    }

    /**
     * Whether a kept entry should leave the lists for [lookup]: only when Telegram said it is gone
     * and there is no copy of it on this device to play instead (a download stays playable after
     * its message goes, so its place in Continue watching is still worth keeping).
     */
    fun shouldForget(lookup: MessageLookup, keptOnDevice: Boolean): Boolean =
        lookup is MessageLookup.Gone && !keptOnDevice

    /**
     * Where a download's file stands, from what the disk says: [fileThere] (a regular file at the
     * recorded path), [length] of it, the [recordedSize] and whether the [folderThere] it was put
     * in still exists. A whole file is [DownloadPlace.Present]; one shorter than recorded or gone
     * from a folder that is still there is [DownloadPlace.Missing] (a clean up tool, a file
     * manager); with the folder gone as well it is [DownloadPlace.FolderGone], which is what an
     * unplugged drive or a removed card looks like, and may come back.
     */
    fun downloadPlace(fileThere: Boolean, length: Long, recordedSize: Long, folderThere: Boolean): DownloadPlace = when {
        fileThere && length > 0 && (recordedSize <= 0 || length >= recordedSize) -> DownloadPlace.Present
        !fileThere && !folderThere -> DownloadPlace.FolderGone
        else -> DownloadPlace.Missing
    }

    private const val NOT_FOUND = 404
    private const val FLOOD = 420
    private const val TOO_MANY = 429
    private const val SERVER = 500

    private val CHAT_CLOSED = listOf(
        "CHANNEL_PRIVATE",
        "CHAT_FORBIDDEN",
        "USER_BANNED_IN_CHANNEL",
        "CHANNEL_INVALID",
        "Can't access the chat", // i18n-ok: TDLib text, matched
    )

    private val MESSAGE_GONE = listOf(
        "MESSAGE_ID_INVALID",
        "Message not found", // i18n-ok: TDLib text, matched
        "MESSAGE_NOT_FOUND",
    )
}

/** A download's file against the disk ([StaleEntries.downloadPlace]). */
enum class DownloadPlace {
    Present,
    Missing,
    FolderGone,
}
