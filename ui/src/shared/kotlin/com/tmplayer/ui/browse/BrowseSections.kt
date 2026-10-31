package com.tmplayer.ui.browse

import androidx.compose.ui.graphics.vector.ImageVector
import com.tmplayer.data.ChatFolderSummary
import com.tmplayer.data.ChatKind
import com.tmplayer.data.ChatSummary
import com.tmplayer.data.Fuzzy
import com.tmplayer.i18n.L
import com.tmplayer.ui.icons.TmIcons

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
    Home(TmIcons.Home),
    /** Continue watching and Previously watched, as two tabs: both are videos already played. */
    History(TmIcons.History),
    Favorites(TmIcons.StarOutline),
    /** Every chat, narrowed by the [ChatFilter] chips. */
    Chats(TmIcons.Chats),
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
    All(TmIcons.Chats),
    Unread(TmIcons.Unread),
    Channels(TmIcons.Channel),
    Groups(TmIcons.Group),
    People(TmIcons.Person),
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

    /** The picture over the list when this filter leaves nothing: a ticked bubble for nothing unread. */
    val emptyIcon: ImageVector get() = when (this) {
        All -> TmIcons.ChatsMany
        Unread -> TmIcons.ChatsDone
        else -> icon
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

/** The tabs of History. */
enum class HistoryTab(val icon: ImageVector) {
    /** Videos part way through, kept on this device. */
    Continue(TmIcons.Play),
    /** Single videos put aside for later, without starring their chats. */
    WatchLater(TmIcons.Clock),
    /** Videos played to the end or marked by hand. */
    Watched(TmIcons.Check),
    ;

    /** The picture over the tab while it is empty, a little fuller than the tab's own glyph. */
    val emptyIcon: ImageVector get() = when (this) {
        Continue -> TmIcons.PlayCircle
        WatchLater -> TmIcons.Clock
        Watched -> TmIcons.CheckCircle
    }

    val label: String get() = when (this) {
        Continue -> L.browseTabContinue
        WatchLater -> L.browseTabWatchLater
        Watched -> L.browseTabWatched
    }

    val heading: String get() = when (this) {
        Continue -> L.browseTabContinueHeading
        WatchLater -> L.browseTabWatchLaterHeading
        Watched -> L.browseTabWatchedHeading
    }

    val blurb: String get() = when (this) {
        Continue -> L.browseTabContinueBlurb
        WatchLater -> L.browseTabWatchLaterBlurb
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
 * Every place the browser can show, Home first, then the viewer's folders.
 *
 * The sidebar offers the fixed entries ([navGroups]): Home, History, Favourites and Chats. The
 * folders are not sidebar entries but chips on the Chats page ([chatPageChips]), so they are
 * listed here for the page to find a remembered folder by, not for a rail to draw. Saved Messages
 * has no entry of its own: it is a chat among the others, and the People chip lists it.
 */
fun browseSections(folders: List<ChatFolderSummary>): List<BrowseSection> = buildList {
    add(BrowseSection.of(BrowseTab.Home))
    add(BrowseSection.of(BrowseTab.History))
    add(BrowseSection.of(BrowseTab.Favorites))
    add(BrowseSection.of(BrowseTab.Chats))
    folders.forEach { add(BrowseSection.Folder(it.id, it.title)) }
}

/**
 * The sidebar entry that stands for [this] section: a folder is shown on the Chats page, so it is
 * Chats that the sidebar marks as current.
 */
val BrowseSection.navSection: BrowseSection
    get() = if (this is BrowseSection.Folder) BrowseSection.of(BrowseTab.Chats) else this

/** The Chats page, by its own tab or by one of its folder chips. */
val BrowseSection.onChatsPage: Boolean
    get() = isChats || this is BrowseSection.Folder

/**
 * One chip over the Chats page: "All chats" or one of TMPlayer's own filters ([Filter]), or one
 * of the account's Telegram folders ([Folder]), whose [Folder.iconName] is the icon Telegram gave
 * it.
 */
@androidx.compose.runtime.Immutable
sealed interface ChatPageChip {
    val label: String
    val icon: ImageVector

    data class Filter(val filter: ChatFilter) : ChatPageChip {
        override val label: String get() = if (filter == ChatFilter.All) L.browseFilterAllChats else filter.label
        override val icon: ImageVector get() = filter.icon
    }

    data class Folder(val folder: BrowseSection.Folder, val iconName: String = "") : ChatPageChip {
        override val label: String get() = folder.name
        override val icon: ImageVector get() = folderIcon(iconName)
    }
}

/**
 * The glyph for a Telegram folder's icon name: the four Telegram suggests for its own folders
 * (Unread, Channels, Groups, Private) and a star for Favorite get the app's own icons for the same
 * things; every other name, the emoji-like ones a person picks, is a folder.
 */
fun folderIcon(name: String): ImageVector = when (name) {
    "Unread" -> TmIcons.Unread
    "Channels" -> TmIcons.Channel
    "Groups" -> TmIcons.Group
    "Private" -> TmIcons.Person
    "Favorite" -> TmIcons.StarOutline
    else -> TmIcons.Folder
}

/** The one of TMPlayer's filters a Telegram folder already is, by its icon or its name. */
private fun filterOf(folder: ChatFolderSummary): ChatFilter? {
    val byIcon = when (folder.icon) {
        "Unread" -> ChatFilter.Unread
        "Channels" -> ChatFilter.Channels
        "Groups" -> ChatFilter.Groups
        "Private" -> ChatFilter.People
        else -> null
    }
    return byIcon ?: ChatFilter.entries.firstOrNull { it != ChatFilter.All && it.label.equals(folder.title.trim(), ignoreCase = true) }
}

/**
 * The chips over the Chats page, as Telegram's own folder tab bar has them: "All chats" at
 * [mainListPosition] (where Telegram puts the main list, first unless the viewer moved it) among
 * the account's [folders] in Telegram's order, its suggested folders (Unread, Personal, Groups,
 * Channels, Bots) included when the viewer added them. Then TMPlayer's own filters (Unread,
 * Channels, Groups, People, Archived) that no folder already is, so there is never a second
 * "Unread"; Archived is always there, since a Telegram folder never lists the archive.
 */
fun chatPageChips(folders: List<ChatFolderSummary>, mainListPosition: Int = 0): List<ChatPageChip> {
    val tabs: MutableList<ChatPageChip> = folders.mapTo(ArrayList()) { ChatPageChip.Folder(BrowseSection.Folder(it.id, it.title), it.icon) }
    tabs.add(mainListPosition.coerceIn(0, tabs.size), ChatPageChip.Filter(ChatFilter.All))
    val covered = folders.mapNotNull(::filterOf).toSet()
    val extras = ChatFilter.entries.filter { it != ChatFilter.All && it !in covered }.map { ChatPageChip.Filter(it) }
    return tabs + extras
}

/** Whether [chip] is the one lit for [section] under [filter]. */
fun ChatPageChip.isSelected(section: BrowseSection, filter: ChatFilter): Boolean = when (this) {
    is ChatPageChip.Filter -> section.isChats && this.filter == filter
    is ChatPageChip.Folder -> section is BrowseSection.Folder && section.id == folder.id
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
        // A folder lists what Telegram puts in it, archived channels included (HX32): the members
        // come from TDLib's own folder list, which leaves an archived chat out only when the
        // folder is set to exclude the archive, so hiding them again here emptied folders of
        // archived movie channels.
        section is BrowseSection.Folder -> chats
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

/**
 * The picture over an empty tab, the same on the phone, the television and the desktop: a search
 * that found nothing, an open folder, or the section's own glyph.
 */
fun emptyTabIcon(
    tab: BrowseSection,
    query: String,
    filter: ChatFilter = ChatFilter.All,
    history: HistoryTab? = null,
): ImageVector = when {
    query.isNotBlank() -> TmIcons.SearchRemove
    history != null -> history.emptyIcon
    tab is BrowseSection.Folder -> TmIcons.FolderOpen
    tab.isChats -> filter.emptyIcon
    else -> tab.icon
}
