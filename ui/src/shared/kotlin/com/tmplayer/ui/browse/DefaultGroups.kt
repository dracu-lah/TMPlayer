package com.tmplayer.ui.browse

import com.tmplayer.data.ChatFolderSummary
import com.tmplayer.data.ChatSummary
import com.tmplayer.i18n.L

/**
 * "Only my folders": the Settings option that hides Telegram's default groups, Chats and Saved
 * Messages, so the sidebar lists the viewer's own Telegram folders and nothing built in besides the
 * Watch entries (Home, History, Favourites, Downloads). Shared by the phone, the television and the
 * desktop, so the three agree on what is hidden, where a hidden section falls back to, and which
 * favourites the switch would strand.
 *
 * A chat outside every folder is then listed nowhere but search, which is the point of the option.
 */
object DefaultGroups {

    /** The sections the option hides. Both go together; there is no choice per entry. */
    val TABS: Set<BrowseTab> = setOf(BrowseTab.Chats, BrowseTab.Saved)

    /** Whether [section] is one of the default groups. */
    fun isDefault(section: BrowseSection): Boolean = section is BrowseSection.Tab && section.tab in TABS

    /**
     * Where a remembered or requested [section] lands: itself, or Home when the option has hidden
     * it, so a saved screen state or a button naming Saved Messages never opens a page the sidebar
     * does not offer.
     */
    fun reachable(section: BrowseSection, hidden: Boolean): BrowseSection =
        if (hidden && isDefault(section)) BrowseSection.of(BrowseTab.Home) else section

    /**
     * The starred chats that hiding the default groups would leave out of reach: those in none of
     * the account's Telegram folders, and archived ones, which a folder's list leaves out. A starred
     * chat this list does not hold is not counted: it is already on no list, and a number the viewer
     * cannot find anywhere would only puzzle them.
     */
    fun unreachableFavorites(favorites: Set<Long>, chats: List<ChatSummary>): Set<Long> =
        chats.asSequence()
            .filter { it.id in favorites && (it.folderIds.isEmpty() || it.isArchived) }
            .map { it.id }
            .toSet()

    /**
     * The confirm prompt's words before the option goes on: what changes, the favourites it
     * removes ([unreachable], a line only when there are any), what to do first when the account
     * has no folders, and how to undo it.
     */
    fun prompt(unreachable: Int, folderCount: Int): Prompt = Prompt(
        title = L.groupsPromptTitle,
        message = if (folderCount == 0) {
            L.groupsPromptMessage + " " + L.groupsPromptNoFolders
        } else {
            L.groupsPromptMessage
        },
        detail = listOfNotNull(
            L.groupsPromptFavourites(count = unreachable).takeIf { unreachable > 0 },
            L.groupsPromptUndo,
        ).joinToString(" "),
        confirm = L.groupsPromptConfirm,
    )

    /** The words of [prompt], for whichever confirm dialog the platform draws. */
    data class Prompt(val title: String, val message: String, val detail: String, val confirm: String)

    /** The Settings row's second line: what the option does now, or that there are no folders yet. */
    fun settingDetail(hidden: Boolean, folderCount: Int): String = when {
        hidden -> L.groupsSettingOn
        folderCount == 0 -> L.groupsSettingNoFolders
        else -> L.groupsSettingOff
    }

    /**
     * What Home says on a first run, when nothing has been played or starred yet: the body text and,
     * when there is somewhere to send the viewer, the one button and where it goes.
     *
     * With the default groups showing that is Saved Messages, where a forwarded video lands. With
     * them hidden it is the first folder, and with no folders either there is no button at all,
     * only the two ways out: make a folder in Telegram, or show the groups again.
     */
    fun firstStep(hidden: Boolean, folders: List<ChatFolderSummary>): FirstStep {
        if (!hidden) return FirstStep(L.homeFirstBody, L.homeFirstOpenSaved, BrowseSection.of(BrowseTab.Saved))
        val first = folders.firstOrNull()
            ?: return FirstStep(L.groupsHomeNoFolders, button = null, target = null)
        return FirstStep(
            body = L.groupsHomeFirstBody,
            button = L.groupsOpenFolder(title = first.title),
            target = BrowseSection.Folder(first.id, first.title),
        )
    }

    /** See [firstStep]. */
    data class FirstStep(val body: String, val button: String?, val target: BrowseSection?)

    /** The chat list's first-run tip, which names Saved Messages only while it is on the sidebar. */
    fun firstVideoTip(hidden: Boolean): String = if (hidden) L.groupsFirstVideoTip else L.browseFirstVideoTip

    /** "The chats themselves stay where they are": in Chats, or with Chats hidden, in the folders. */
    fun favouritesUntouched(hidden: Boolean): String =
        if (hidden) L.groupsFavouritesUntouched else L.settingsClearFavouritesUntouched
}
