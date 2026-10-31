package com.tmplayer.data

import com.tmplayer.i18n.L

/** How a chat is grouped in the navigation rail. */
enum class ChatKind(private val labelText: () -> String) {
    /**
     * The chat with yourself, which Telegram gives every account and every client names rather
     * than showing the account's own name on it.
     *
     * It gets a kind of its own because it is the one chat people use as a video library: a file
     * forwarded to Saved Messages from a phone is the usual way something arrives on this app.
     */
    Saved({ L.chatsSavedMessages }),
    Channel({ L.chatsKindChannels }),
    Group({ L.chatsKindGroups }),
    Direct({ L.chatsKindPeople }),
    ;

    /** The rail heading, in the UI language. */
    val label: String get() = labelText()
}

/**
 * One row of the chat list.
 *
 * Equality must cover every field, including the bytes of the blurred preview, or a refreshed list
 * compares equal to the old one and the StateFlow conflates it away, leaving a renamed chat on its
 * stale row. The array is compared by content, not reference: TDLib hands back a fresh array on
 * every read.
 *
 * [Immutable] is the promise Compose needs to skip a row whose chat has not changed; it is honest
 * here because nothing ever writes into that array after construction.
 */
@androidx.compose.runtime.Immutable
data class ChatSummary(
    val id: Long,
    val title: String,
    val miniThumbnail: ByteArray?,
    val photoFileId: Int,
    val kind: ChatKind,
    /**
     * Pinned in whichever list the chat lives in: the main one, or the archive for a chat that
     * has been put there. Telegram treats the two as separate pins, and a chat is only ever in
     * one of them, so one flag says it without having to name the list.
     */
    val isPinned: Boolean = false,
    /** Archived chats are kept out of every other tab, which is the whole point of archiving one. */
    val isArchived: Boolean = false,
    val unreadCount: Int = 0,
    /** Muted chats keep their unread count but say it quietly, as Telegram itself does. */
    val isMuted: Boolean = false,
    /**
     * The chat folders this chat belongs to, by folder id.
     *
     * Membership is Telegram's answer rather than this app's: a folder is a set of rules about
     * chat types, inclusions and exclusions, and evaluating those here would be a second, worse
     * implementation of something TDLib already keeps up to date. Filled in by asking about each
     * folder in turn rather than reading it off the chat, for the reason [folderMembership] gives.
     */
    val folderIds: List<Int> = emptyList(),
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ChatSummary) return false
        return id == other.id &&
            title == other.title &&
            photoFileId == other.photoFileId &&
            kind == other.kind &&
            isPinned == other.isPinned &&
            isArchived == other.isArchived &&
            unreadCount == other.unreadCount &&
            isMuted == other.isMuted &&
            folderIds == other.folderIds &&
            miniThumbnail.contentEquals(other.miniThumbnail)
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + title.hashCode()
        result = 31 * result + photoFileId
        result = 31 * result + kind.hashCode()
        result = 31 * result + isPinned.hashCode()
        result = 31 * result + isArchived.hashCode()
        result = 31 * result + unreadCount
        result = 31 * result + isMuted.hashCode()
        result = 31 * result + folderIds.hashCode()
        result = 31 * result + (miniThumbnail?.contentHashCode() ?: 0)
        return result
    }
}

/**
 * One of the viewer's Telegram folders, as much of it as a tab needs.
 *
 * The rules that decide what is in a folder are deliberately not here. TDLib evaluates them and
 * answers what is in each folder, so this carries only what has to be drawn on a chip: the name,
 * and [icon], the name of the folder's icon in Telegram ("Unread", "Channels", "Groups",
 * "Private", "Bots", "Custom" and the rest), empty when Telegram gave none.
 */
@androidx.compose.runtime.Immutable
data class ChatFolderSummary(val id: Int, val title: String, val icon: String = "")

/**
 * Puts a chat list in the order Telegram itself would draw it.
 *
 * TDLib's own ordering is by position within a list and takes no view on pinning, so pins have to
 * be lifted here or a quiet pinned chat sorts below whatever arrived this morning. Saved Messages
 * goes above even the pins: it is always wanted and has nothing to do with recency.
 *
 * [sortedWith] is stable, so within each of the three groups the order TDLib handed over survives
 * untouched, and nothing here has to know how that order was arrived at.
 */
fun arrangeChats(chats: List<ChatSummary>): List<ChatSummary> =
    chats.sortedWith(compareBy({ it.kind != ChatKind.Saved }, { !it.isPinned }))

/**
 * The result of a sync: the list TDLib now holds, and whatever stopped it being complete.
 *
 * [failure] is null both when every page arrived and when Telegram said there were no more, which
 * are the two ways of succeeding. It carries the raw TDLib message so the caller can decide
 * whether to show it, retry at the delay a flood wait names, or leave the cached list alone.
 *
 * The main list failing and the rest of the read going short are told apart (HX32), so a list
 * that is missing chats never reads as a whole one: [archiveFailure] is why the archive could not
 * be read (the archived rows already in hand are kept), and [dropped] counts the chats TDLib
 * listed but would not describe, which are left out of [chats].
 */
data class ChatSync(
    val chats: List<ChatSummary>,
    val failure: String? = null,
    val archiveFailure: String? = null,
    val dropped: Int = 0,
) {
    val complete: Boolean get() = failure == null && archiveFailure == null

    /** True when [chats] is known to be short of the account: some chat was left out or a list failed. */
    val partial: Boolean get() = !complete || dropped > 0
}

/** TDLib's answer to `loadChats` once every chat in the list is already local: code 404. */
internal fun isAllLoaded(code: Int, message: String): Boolean =
    code == 404 || message.contains("404")

/**
 * One topic of a group that has Telegram's topics turned on, as much of it as a chip needs.
 *
 * [hasVideos] is false only when Telegram counted no videos and no files in it at all: a "Rules"
 * or "Chat" topic, which the picker leaves out rather than offer as a dead end. Files are counted
 * because most long videos are posted as files, and Telegram has no count of "files that are
 * videos", so a topic of nothing but PDFs is still offered and says it has no videos when opened.
 */
@androidx.compose.runtime.Immutable
data class ForumTopicSummary(
    val id: Int,
    val name: String,
    val isPinned: Boolean = false,
    val isClosed: Boolean = false,
    val unreadCount: Int = 0,
    val hasVideos: Boolean = true,
)

/** One page of media, plus the cursors needed to ask for the next one. */
data class MediaPage(
    val items: List<MediaItem>,
    val cursors: MediaCursors,
    val endReached: Boolean,
    /** Self-destructing videos this page left out. */
    val hiddenSelfDestructing: Int = 0,
    /**
     * The compressed files this page met among the chat's documents ([Archives]), which no player
     * opens: listed apart, so a chat of ZIPs does not read as an empty one.
     */
    val archives: List<ArchiveFile> = emptyList(),
)

/**
 * Telegram searches one media kind at a time, so a chat's videos are assembled from three
 * parallel searches and each keeps its own position.
 */
data class MediaCursors(
    val video: Long = 0,
    val document: Long = 0,
    val animation: Long = 0,
    val videoDone: Boolean = false,
    val documentDone: Boolean = false,
    val animationDone: Boolean = false,
) {
    val allDone: Boolean get() = videoDone && documentDone && animationDone
}
