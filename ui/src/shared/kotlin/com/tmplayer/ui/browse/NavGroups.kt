package com.tmplayer.ui.browse

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import com.tmplayer.data.SettingsStore
import com.tmplayer.i18n.L
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

// The sidebar's folding groups, shared by the phone's drawer, the television's rail and the
// desktop's side bar, so all three fold the same entries under the same headings.

/** A heading in the sidebar, and the entries that fold away under it. */
enum class NavGroup {
    Watch,
    Chats,
    Folders,
    ;

    /** The heading's words. */
    val label: String get() = when (this) {
        Watch -> L.navWatch
        Chats -> L.navChats
        Folders -> L.navFolders
    }

    /** How the group is written down in [SettingsStore]: lower case, so a rename of the enum is visible. */
    val key: String get() = name.lowercase()
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

/** The tabs about the viewer's own watching, in the order the Watch group lists them. */
private val WATCH_TABS = listOf(BrowseTab.Continue, BrowseTab.Watched, BrowseTab.Favorites)

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
 * Which groups are open, as [SettingsStore] keeps them.
 *
 * Only the viewer's choice is stored. The group holding the current destination is open on top of
 * that whatever was stored, so the highlighted row can never be folded out of sight, and closing
 * another group never moves the viewer.
 */
object NavGroupState {

    /** Watch starts open, because it is what somebody opening the app in the evening wants; the rest start closed. */
    val DEFAULT_OPEN: Set<NavGroup> = setOf(NavGroup.Watch)

    /** Null is a store that has never been written, which means the defaults. Unknown keys are ignored. */
    fun decode(stored: Set<String>?): Set<NavGroup> =
        stored?.mapNotNull { key -> NavGroup.entries.firstOrNull { it.key == key } }?.toSet() ?: DEFAULT_OPEN

    fun encode(open: Set<NavGroup>): Set<String> = open.mapTo(mutableSetOf()) { it.key }

    fun toggled(open: Set<NavGroup>, group: NavGroup): Set<NavGroup> =
        if (group in open) open - group else open + group

    /** What is drawn: the stored choice, plus the group the viewer is in. */
    fun isOpen(group: NavGroup, open: Set<NavGroup>, current: NavGroup?): Boolean =
        group == current || group in open

    /** The current group cannot be folded, so its heading offers no toggle. */
    fun canToggle(group: NavGroup, current: NavGroup?): Boolean = group != current
}

/** Folds or unfolds [group] in the store, reading what is stored at the moment of writing. */
suspend fun toggleNavGroup(settings: SettingsStore, group: NavGroup) {
    settings.updateNavGroupsOpen { stored ->
        NavGroupState.encode(NavGroupState.toggled(NavGroupState.decode(stored), group))
    }
}

/** The open state for a sidebar on screen, read from and written to [SettingsStore]. */
@Stable
class NavGroupsHolder internal constructor(
    private val stored: () -> Set<NavGroup>,
    private val current: NavGroup?,
    private val scope: CoroutineScope,
    private val settings: SettingsStore,
) {
    fun isOpen(group: NavGroup): Boolean = NavGroupState.isOpen(group, stored(), current)

    fun canToggle(group: NavGroup): Boolean = NavGroupState.canToggle(group, current)

    fun toggle(group: NavGroup) {
        if (!canToggle(group)) return
        scope.launch { toggleNavGroup(settings, group) }
    }
}

/**
 * The sidebar's open groups for a screen whose current destination is in [current] (null when the
 * current page is outside every group, such as Settings).
 */
@Composable
fun rememberNavGroups(settings: SettingsStore, current: NavGroup?): NavGroupsHolder {
    val flow = remember(settings) { settings.navGroupsOpen.map(NavGroupState::decode) }
    val open by flow.collectAsState(initial = NavGroupState.DEFAULT_OPEN)
    val scope = rememberCoroutineScope()
    return remember(settings, current, open, scope) { NavGroupsHolder({ open }, current, scope, settings) }
}
