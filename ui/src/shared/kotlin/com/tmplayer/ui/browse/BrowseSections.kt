package com.tmplayer.ui.browse

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Star
import androidx.compose.ui.graphics.vector.ImageVector
import com.tmplayer.data.ChatFolderSummary
import com.tmplayer.data.ChatKind
import com.tmplayer.data.ChatSummary
import com.tmplayer.data.Fuzzy
import com.tmplayer.i18n.L
import com.tmplayer.ui.components.TmIcons

// The browser's sections and the chat filter behind them, shared by the phone, the television and
// the desktop.

/**
 * The sidebar's built-in sections, in the order they appear.
 *
 * Six fixed entries in all, with Downloads: Home, History and Favourites for the viewer's own
 * watching, then Chats and Saved Messages. Chats used to be eight entries (Recent, Unread, Saved,
 * Channels, Groups, People, All, Archived) that were all one list filtered six ways, so the six
 * filters are now the [ChatFilter] chips over that one list, and Continue and Watched are the two
 * [HistoryTab]s of History.
 */
enum class BrowseTab(val icon: ImageVector) {
    /** The landing page: rows of videos rather than a list of chats. See [com.tmplayer.data.HomeRows]. */
    Home(Icons.Filled.Home),
    /** Continue watching and Previously watched, as two tabs: both are videos already played. */
    History(TmIcons.History),
    Favorites(Icons.Filled.Star),
    /** Every chat, narrowed by the [ChatFilter] chips. */
    Chats(Icons.AutoMirrored.Filled.List),
    /** Saved Messages, apart from Chats because it is the video library most people keep. */
    Saved(TmIcons.Bookmark),
    ;

    /** The sidebar's short name. */
    val label: String get() = when (this) {
        Home -> L.browseTabHome
        History -> L.browseTabHistory
        Favorites -> L.browseTabFavourites
        Chats -> L.browseTabChats
        Saved -> L.browseTabSaved
    }

    /** The heading over the list. */
    val heading: String get() = when (this) {
        Home -> L.browseTabHomeHeading
        History -> L.browseTabHistoryHeading
        Favorites -> L.browseTabFavouritesHeading
        Chats -> L.browseTabChatsHeading
        Saved -> L.browseTabSavedHeading
    }

    /** The line under the heading. */
    val blurb: String get() = when (this) {
        Home -> L.browseTabHomeBlurb
        History -> L.browseTabHistoryBlurb
        Favorites -> L.browseTabFavouritesBlurb
        Chats -> L.browseTabChatsBlurb
        Saved -> L.browseTabSavedBlurb
    }

    companion object {
        /**
         * The sections a saved screen state or an older build may still name, mapped onto the
         * entries that replaced them, so nobody comes back from an update to the default page.
         */
        fun decode(name: String): BrowseTab? = entries.firstOrNull { it.name == name } ?: when (name) {
            "Continue", "Watched" -> History
            "Recent", "Unread", "Channels", "Groups", "People", "All", "Archived" -> Chats
            else -> null
        }
    }
}

/**
 * The chips over the Chats list. Each one is a filter of the same list: All leads, Archived is last
 * because it is the one that shows what every other chip leaves out.
 */
enum class ChatFilter(val icon: ImageVector) {
    All(Icons.AutoMirrored.Filled.List),
    Unread(TmIcons.Dot),
    Channels(TmIcons.Channel),
    Groups(TmIcons.Group),
    People(Icons.Filled.Person),
    Archived(TmIcons.Archive),
    ;

    val label: String get() = when (this) {
        All -> L.browseFilterAll
        Unread -> L.browseFilterUnread
        Channels -> L.browseFilterChannels
        Groups -> L.browseFilterGroups
        People -> L.browseFilterPeople
        Archived -> L.browseFilterArchived
    }

    companion object {
        /** What [com.tmplayer.data.SettingsStore.chatFilter] holds, read back; All for anything else. */
        fun decode(name: String?): ChatFilter = entries.firstOrNull { it.name == name } ?: All
    }
}

/**
 * The order of the Chats list: Telegram's own, newest activity first (what the Recent entry used
 * to be), or by name. One toggle chip at the head of the chip row flips between them.
 */
enum class ChatSort(val icon: ImageVector) {
    Recent(TmIcons.Clock),
    Name(TmIcons.Sort),
    ;

    val label: String get() = when (this) {
        Recent -> L.browseSortRecent
        Name -> L.browseSortName
    }

    /** The other order, which is what pressing the chip picks. */
    val toggled: ChatSort get() = if (this == Recent) Name else Recent

    companion object {
        fun decode(name: String?): ChatSort = entries.firstOrNull { it.name == name } ?: Recent
    }
}

/** The two tabs of History. */
enum class HistoryTab(val icon: ImageVector) {
    /** Videos part way through, kept on this device. */
    Continue(Icons.Filled.PlayArrow),
    /** Videos played to the end or marked by hand. */
    Watched(Icons.Filled.Check),
    ;

    val label: String get() = when (this) {
        Continue -> L.browseTabContinue
        Watched -> L.browseTabWatched
    }

    val heading: String get() = when (this) {
        Continue -> L.browseTabContinueHeading
        Watched -> L.browseTabWatchedHeading
    }

    val blurb: String get() = when (this) {
        Continue -> L.browseTabContinueBlurb
        Watched -> L.browseTabWatchedBlurb
    }

    companion object {
        fun decode(name: String?): HistoryTab = entries.firstOrNull { it.name == name } ?: Continue
    }
}

/**
 * A place in the rail, which is either one of the app's own tabs or one of the viewer's folders.
 *
 * Folders cannot be an enum: they are whatever this account happens to have, they may be renamed
 * while this app is looking at them, and there may be none at all. They navigate exactly like a
 * tab does, so both wear the same four properties and everything downstream, the rail, the drawer,
 * the heading, the empty state, is written once against this.
 */
@androidx.compose.runtime.Immutable
sealed interface BrowseSection {
    val label: String
    val heading: String
    val blurb: String
    val icon: ImageVector

    @androidx.compose.runtime.Immutable
    data class Tab(val tab: BrowseTab) : BrowseSection {
        override val label get() = tab.label
        override val heading get() = tab.heading
        override val blurb get() = tab.blurb
        override val icon get() = tab.icon
    }

    @androidx.compose.runtime.Immutable
    data class Folder(val id: Int, val name: String) : BrowseSection {
        override val label get() = name
        override val heading get() = name
        override val blurb get() = L.browseFolderBlurb
        override val icon get() = TmIcons.Folder
    }

    /** Home's rows, which are neither a list of chats nor one list of videos. */
    val isHome: Boolean get() = this is Tab && tab == BrowseTab.Home

    /**
     * History: lists videos held on this device rather than chats, so no chat search, no refresh,
     * and a count in videos.
     */
    val listsVideos: Boolean get() = this is Tab && tab == BrowseTab.History

    /** The one list the [ChatFilter] chips and the [ChatSort] toggle apply to. */
    val isChats: Boolean get() = this is Tab && tab == BrowseTab.Chats

    companion object {
        fun of(tab: BrowseTab): BrowseSection = Tab(tab)

        /**
         * Written down as a string so a rotation, or the process being killed behind a video,
         * comes back to the section the viewer was in rather than to the default one.
         *
         * A folder is stored by id and name together: the name is what the heading shows while the
         * folder list is still on its way from TDLib, otherwise the screen comes back titled after
         * a number.
         */
        fun encode(section: BrowseSection?): String = when (section) {
            null -> ""
            is Tab -> "tab:${section.tab.name}"
            is Folder -> "folder:${section.id}:${section.name}"
        }

        fun decode(encoded: String?): BrowseSection? {
            if (encoded.isNullOrBlank()) return null
            val body = encoded.substringAfter(':', "")
            return when {
                encoded.startsWith("tab:") -> BrowseTab.decode(body)?.let(::Tab)
                encoded.startsWith("folder:") -> {
                    val id = body.substringBefore(':').toIntOrNull() ?: return null
                    Folder(id, body.substringAfter(':', "").ifBlank { L.browseFolderFallback(id.toString()) })
                }
                else -> null
            }
        }
    }
}

/**
 * Every place the sidebar offers, Home first, with the viewer's folders after the chat entries.
 *
 * Folders come last because they are the only part of this list that differs per account. Keeping
 * the fixed, learnable entries first also stops the sidebar changing shape halfway down when a
 * folder is added or renamed.
 */
fun browseSections(folders: List<ChatFolderSummary>): List<BrowseSection> = buildList {
    add(BrowseSection.of(BrowseTab.Home))
    add(BrowseSection.of(BrowseTab.History))
    add(BrowseSection.of(BrowseTab.Favorites))
    add(BrowseSection.of(BrowseTab.Chats))
    add(BrowseSection.of(BrowseTab.Saved))
    folders.forEach { add(BrowseSection.Folder(it.id, it.title)) }
}

/**
 * The chats [section] lists, ranked against [query].
 *
 * [filter] and [sort] are the chips over the Chats list and apply to that section only: a folder,
 * Saved Messages and Favourites are already narrow, and keep Telegram's order.
 */
fun filterChats(
    chats: List<ChatSummary>,
    section: BrowseSection,
    favorites: Set<Long>,
    query: String,
    filter: ChatFilter = ChatFilter.All,
    sort: ChatSort = ChatSort.Recent,
): List<ChatSummary> {
    // Archived chats are hidden everywhere except the chip that exists to show them. Favourites is
    // the exception: starring is this app's own act, and a deliberately starred chat should not
    // vanish because it was archived in Telegram.
    val archived = section.isChats && filter == ChatFilter.Archived
    val listed = when {
        archived -> chats.filter { it.isArchived }
        section == BrowseSection.of(BrowseTab.Favorites) -> chats
        else -> chats.filterNot { it.isArchived }
    }
    val bySection = when (section) {
        is BrowseSection.Folder -> listed.filter { section.id in it.folderIds }
        is BrowseSection.Tab -> when (section.tab) {
            BrowseTab.Favorites -> listed.filter { it.id in favorites }
            BrowseTab.Saved -> listed.filter { it.kind == ChatKind.Saved }
            BrowseTab.Chats -> when (filter) {
                ChatFilter.All, ChatFilter.Archived -> listed
                ChatFilter.Unread -> listed.filter { it.unreadCount > 0 }
                ChatFilter.Channels -> listed.filter { it.kind == ChatKind.Channel }
                ChatFilter.Groups -> listed.filter { it.kind == ChatKind.Group }
                // Saved Messages is a private chat and belongs among the people even though it
                // also has an entry of its own.
                ChatFilter.People -> listed.filter { it.kind == ChatKind.Direct || it.kind == ChatKind.Saved }
            }
            // History is a list of videos, not chats, and Home is rows; neither reaches this filter.
            BrowseTab.Home, BrowseTab.History -> listed
        }
    }
    val ordered = if (section.isChats && sort == ChatSort.Name) {
        // The UI language's alphabet, so accented and non-Latin names land where a reader expects.
        val collator = java.text.Collator.getInstance().apply { strength = java.text.Collator.SECONDARY }
        bySection.sortedWith(compareBy(collator) { it.title.trim() })
    } else {
        bySection
    }
    // Ranked rather than filtered: a chat whose title is exactly what was typed belongs at the
    // top, and ranking forgives the accent nobody types and the mistyped letter from a remote.
    return Fuzzy.rank(ordered, query) { it.title }
}
