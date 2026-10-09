package com.tmplayer.ui.browse

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import com.tmplayer.i18n.L
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

// The sidebar's folding groups, shared by the phone's drawer, the television's rail and the
// desktop's side bar, so all three fold the same entries under the same headings.

/** A heading in the sidebar, and the entries that fold away under it. */
enum class NavGroup {
    Watch,
    Chats,
    Folders,
    ;

    /**
     * Whether the group draws a heading. Watch does not: it is the first thing in the sidebar and
     * plainly the viewer's own, so a word over it only pushed the rows down. With no heading it has
     * nothing to fold from, so it is always open.
     */
    val headed: Boolean get() = this != Watch

    /** The heading's words. */
    val label: String get() = when (this) {
        Watch -> L.navWatch
        // "Telegram" rather than "Chats": the group's first entry is itself called Chats, and a
        // heading that repeats the row under it reads as a mistake.
        Chats -> L.navTelegram
        Folders -> L.navFolders
    }

}

/**
 * One row in a group: a browse section, or the Downloads screen.
 *
 * Downloads is not a [BrowseSection] because it opens a screen of its own on the phone and the
 * television rather than filtering the chat list, but it belongs with the viewer's own watching.
 */
@androidx.compose.runtime.Immutable
sealed interface NavEntry {
    @androidx.compose.runtime.Immutable
    data class Section(val section: BrowseSection) : NavEntry

    data object Downloads : NavEntry
}

/** A group and what is in it, in the order the sidebar draws them. */
@androidx.compose.runtime.Immutable
data class NavGroupEntries(val group: NavGroup, val entries: List<NavEntry>)

/**
 * The tabs about the viewer's own watching, in the order the Watch group lists them. Home leads:
 * it is the first destination on every device, and its rows are made of the other two.
 */
private val WATCH_TABS = listOf(BrowseTab.Home, BrowseTab.History, BrowseTab.Favorites)

/** Which group a section sits in. Every section has exactly one. */
fun navGroupOf(section: BrowseSection): NavGroup = when (section) {
    is BrowseSection.Folder -> NavGroup.Folders
    is BrowseSection.Tab -> if (section.tab in WATCH_TABS) NavGroup.Watch else NavGroup.Chats
}

fun navGroupOf(entry: NavEntry): NavGroup = when (entry) {
    NavEntry.Downloads -> NavGroup.Watch
    is NavEntry.Section -> navGroupOf(entry.section)
}

/**
 * The sidebar's groups for these [sections] (see [browseSections]): Watch with Downloads at its
 * end, Chats, then Folders only when the account has any, so an account with none never gets a
 * heading over nothing. Settings and Update are not here: every platform pins them at the bottom.
 */
fun navGroups(sections: List<BrowseSection>, withDownloads: Boolean = true): List<NavGroupEntries> {
    val watch = WATCH_TABS.map(BrowseSection::of).filter { it in sections }.map { NavEntry.Section(it) } +
        listOfNotNull(NavEntry.Downloads.takeIf { withDownloads })
    val chats = sections.filter { navGroupOf(it) == NavGroup.Chats }.map { NavEntry.Section(it) }
    val folders = sections.filter { navGroupOf(it) == NavGroup.Folders }.map { NavEntry.Section(it) }
    return listOf(
        NavGroupEntries(NavGroup.Watch, watch),
        NavGroupEntries(NavGroup.Chats, chats),
        NavGroupEntries(NavGroup.Folders, folders),
    ).filter { it.entries.isNotEmpty() }
}

/**
 * Which groups are open.
 *
 * Only the viewer's choice is held, and only for as long as the app runs: every launch starts from
 * [NavGroupState.DEFAULT_OPEN], so Chats and Folders are folded each time the app opens however they
 * were left. The group holding the current destination is open on top of that, so the highlighted
 * row can never be folded out of sight, and closing another group never moves the viewer.
 */
object NavGroupState {

    /** Watch is open, because it is what somebody opening the app in the evening wants; the rest start closed. */
    val DEFAULT_OPEN: Set<NavGroup> = setOf(NavGroup.Watch)

    /** The viewer's folds for this run of the app, shared by every sidebar on screen. */
    private val held = MutableStateFlow(DEFAULT_OPEN)

    val open: StateFlow<Set<NavGroup>> = held.asStateFlow()

    /** Folds or unfolds [group]. */
    fun toggle(group: NavGroup) {
        held.update { toggled(it, group) }
    }

    /**
     * Unfolds [group] without folding anything. With Telegram's default groups hidden the folders
     * are the only way into the chats, so the shells open them rather than leaving them folded.
     */
    fun unfold(group: NavGroup) {
        held.update { it + group }
    }

    /** Back to the defaults, as on a fresh launch. */
    fun reset() {
        held.value = DEFAULT_OPEN
    }

    fun toggled(open: Set<NavGroup>, group: NavGroup): Set<NavGroup> =
        if (group in open) open - group else open + group

    /** What is drawn: the viewer's choice, plus the group the viewer is in, plus a group with no heading. */
    fun isOpen(group: NavGroup, open: Set<NavGroup>, current: NavGroup?): Boolean =
        !group.headed || group == current || group in open

    /** The current group cannot be folded, so its heading offers no toggle; nor can one without a heading. */
    fun canToggle(group: NavGroup, current: NavGroup?): Boolean = group.headed && group != current
}

/** The open state for a sidebar on screen. */
@Stable
class NavGroupsHolder internal constructor(
    private val open: Set<NavGroup>,
    private val current: NavGroup?,
) {
    fun isOpen(group: NavGroup): Boolean = NavGroupState.isOpen(group, open, current)

    fun canToggle(group: NavGroup): Boolean = NavGroupState.canToggle(group, current)

    fun toggle(group: NavGroup) {
        if (canToggle(group)) NavGroupState.toggle(group)
    }
}

/**
 * The sidebar's open groups for a screen whose current destination is in [current] (null when the
 * current page is outside every group, such as Settings).
 */
@Composable
fun rememberNavGroups(current: NavGroup?): NavGroupsHolder {
    val open by NavGroupState.open.collectAsState()
    return remember(current, open) { NavGroupsHolder(open, current) }
}

/**
 * The count beside an entry, the same on every sidebar: how many chats are starred on Favourites,
 * and how many chats have something unread on Chats (the Unread chip's count, which used to sit on
 * an Unread entry of its own). Null where there is nothing worth a badge.
 */
fun navBadge(section: BrowseSection, favoriteCount: Int, unreadChats: Int): String? = when {
    section == BrowseSection.of(BrowseTab.Favorites) && favoriteCount > 0 -> favoriteCount.toString()
    section == BrowseSection.of(BrowseTab.Chats) && unreadChats > 0 -> unreadChats.toString()
    else -> null
}
