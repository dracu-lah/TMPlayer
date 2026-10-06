package com.tmplayer.ui.browse

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Check
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

/** The rail's built-in sections, in the order they appear. */
enum class BrowseTab(val icon: ImageVector) {
    Continue(Icons.Filled.PlayArrow),
    Watched(Icons.Filled.Check),
    Favorites(Icons.Filled.Star),
    // A clock, not the circular-arrow reload glyph: that one belongs to the video grid's refresh
    // action, and one picture must not stand for two unrelated things.
    Recent(TmIcons.Clock),
    Unread(TmIcons.Dot),
    Saved(TmIcons.Bookmark),
    Channels(TmIcons.Channel),
    Groups(TmIcons.Group),
    People(Icons.Filled.Person),
    All(Icons.AutoMirrored.Filled.List),
    Archived(TmIcons.Archive),
    ;

    /** The rail's short name. */
    val label: String get() = when (this) {
        Continue -> L.browseTabContinue
        Watched -> L.browseTabWatched
        Favorites -> L.browseTabFavourites
        Recent -> L.browseTabRecent
        Unread -> L.browseTabUnread
        Saved -> L.browseTabSaved
        Channels -> L.browseTabChannels
        Groups -> L.browseTabGroups
        People -> L.browseTabPeople
        All -> L.browseTabAll
        Archived -> L.browseTabArchived
    }

    /** The heading over the list. */
    val heading: String get() = when (this) {
        Continue -> L.browseTabContinueHeading
        Watched -> L.browseTabWatchedHeading
        Favorites -> L.browseTabFavouritesHeading
        Recent -> L.browseTabRecentHeading
        Unread -> L.browseTabUnreadHeading
        Saved -> L.browseTabSavedHeading
        Channels -> L.browseTabChannelsHeading
        Groups -> L.browseTabGroupsHeading
        People -> L.browseTabPeopleHeading
        All -> L.browseTabAllHeading
        Archived -> L.browseTabArchivedHeading
    }

    /** The line under the heading. */
    val blurb: String get() = when (this) {
        Continue -> L.browseTabContinueBlurb
        Watched -> L.browseTabWatchedBlurb
        Favorites -> L.browseTabFavouritesBlurb
        Recent -> L.browseTabRecentBlurb
        Unread -> L.browseTabUnreadBlurb
        Saved -> L.browseTabSavedBlurb
        Channels -> L.browseTabChannelsBlurb
        Groups -> L.browseTabGroupsBlurb
        People -> L.browseTabPeopleBlurb
        All -> L.browseTabAllBlurb
        Archived -> L.browseTabArchivedBlurb
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

    /** True for the one section that lists videos held on this device rather than chats. */
    val isContinue: Boolean get() = this is Tab && tab == BrowseTab.Continue

    /** The list of finished videos, also videos rather than chats, and also read off this device. */
    val isWatched: Boolean get() = this is Tab && tab == BrowseTab.Watched

    /** Either list of videos: no chat search, no refresh, and a count in videos. */
    val listsVideos: Boolean get() = isContinue || isWatched

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
                encoded.startsWith("tab:") ->
                    BrowseTab.entries.firstOrNull { it.name == body }?.let(::Tab)
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
 * Every place the rail offers, with the viewer's folders after the chat tabs.
 *
 * [withWatched] adds "Previously watched" right after Continue watching. It is opt in so a screen
 * that has not learned to draw that list yet (and filters out [BrowseSection.isContinue] to keep
 * chats only) is not handed a tab it would render as a chat list.
 *
 * Folders come last because they are the only part of this list that differs per account. Keeping
 * the fixed, learnable tabs first also stops the rail changing shape halfway down when a folder is
 * added or renamed.
 */
fun browseSections(
    folders: List<ChatFolderSummary>,
    withWatched: Boolean = false,
): List<BrowseSection> = buildList {
    add(BrowseSection.of(BrowseTab.Continue))
    if (withWatched) add(BrowseSection.of(BrowseTab.Watched))
    add(BrowseSection.of(BrowseTab.Favorites))
    add(BrowseSection.of(BrowseTab.Recent))
    add(BrowseSection.of(BrowseTab.Unread))
    add(BrowseSection.of(BrowseTab.Saved))
    add(BrowseSection.of(BrowseTab.Channels))
    add(BrowseSection.of(BrowseTab.Groups))
    add(BrowseSection.of(BrowseTab.People))
    add(BrowseSection.of(BrowseTab.All))
    add(BrowseSection.of(BrowseTab.Archived))
    folders.forEach { add(BrowseSection.Folder(it.id, it.title)) }
}

fun filterChats(
    chats: List<ChatSummary>,
    section: BrowseSection,
    favorites: Set<Long>,
    query: String,
): List<ChatSummary> {
    // Archived chats are hidden everywhere except the tab that exists to hold them. Favourites is
    // the exception: starring is this app's own act, and a deliberately starred chat should not
    // vanish because it was archived in Telegram.
    val listed = when {
        section == BrowseSection.of(BrowseTab.Archived) -> chats.filter { it.isArchived }
        section == BrowseSection.of(BrowseTab.Favorites) -> chats
        else -> chats.filterNot { it.isArchived }
    }
    val byTab = when (section) {
        is BrowseSection.Folder -> listed.filter { section.id in it.folderIds }
        is BrowseSection.Tab -> when (section.tab) {
            BrowseTab.Favorites -> listed.filter { it.id in favorites }
            BrowseTab.Unread -> listed.filter { it.unreadCount > 0 }
            BrowseTab.Saved -> listed.filter { it.kind == ChatKind.Saved }
            BrowseTab.Channels -> listed.filter { it.kind == ChatKind.Channel }
            BrowseTab.Groups -> listed.filter { it.kind == ChatKind.Group }
            // Saved Messages is a private chat and belongs among the people even though it also
            // has a tab of its own.
            BrowseTab.People -> listed.filter {
                it.kind == ChatKind.Direct || it.kind == ChatKind.Saved
            }
            // Continue watching and Watched are lists of videos, not chats; they never reach this
            // filter.
            BrowseTab.Continue, BrowseTab.Watched, BrowseTab.Recent, BrowseTab.All, BrowseTab.Archived -> listed
        }
    }
    // Ranked rather than filtered: a chat whose title is exactly what was typed belongs at the
    // top, and ranking forgives the accent nobody types and the mistyped letter from a remote.
    return Fuzzy.rank(byTab, query) { it.title }
}
