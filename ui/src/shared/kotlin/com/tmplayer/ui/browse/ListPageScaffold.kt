package com.tmplayer.ui.browse

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.tmplayer.data.ListCard
import com.tmplayer.data.ListPages
import com.tmplayer.data.ListsStore
import com.tmplayer.data.NamedListRules
import com.tmplayer.data.PlayAllLists
import com.tmplayer.data.VisibleList
import com.tmplayer.ui.components.isTouch
import com.tmplayer.ui.i18n.LocalStrings
import com.tmplayer.ui.theme.Tone
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * A list's page (HX43), the same on the phone, the television and the desktop: "Your lists" over a
 * chip for each list (the one shown is marked) and New list, then the shown list's own buttons
 * (Rename, Show on Home, Delete), then its cards, which each platform draws as its own grid does
 * ([grid]): a show is one card, in the order the lines were added.
 *
 * The cards are worked out off the main thread from the stored lines alone ([ListPages.cards]), so
 * a list of 5,000 opens at once and asks Telegram for nothing but the pictures of the cards drawn.
 * On a television the remote starts on the shown list's chip; Down goes to its buttons, then the cards.
 */
@Composable
fun ListPageScaffold(
    lists: List<VisibleList>?,
    selectedId: String?,
    onSelect: (String) -> Unit,
    store: ListsStore,
    onMessage: (String) -> Unit,
    modifier: Modifier = Modifier,
    /** The page's own space at the sides, to line the heading up with the grid. */
    sides: PaddingValues = PaddingValues(horizontal = 16.dp),
    gap: Dp = 12.dp,
    /** Above the heading: a phone's back button, say. */
    top: (@Composable () -> Unit)? = null,
    /**
     * Play all and Shuffle for the shown list (WP-32, WP-87), `shuffle` true for the second: the host arms
     * [com.tmplayer.data.PlayAll] with [PlayAllLists.queue] and opens the first video. Null leaves both out.
     */
    onPlay: ((list: VisibleList, shuffle: Boolean) -> Unit)? = null,
    grid: @Composable ColumnScope.(list: VisibleList, cards: List<ListCard>, actions: ListPageActions) -> Unit,
) {
    val s = LocalStrings.current
    val tv = !isTouch()
    val actions = rememberListPageActions(store, onMessage)
    var naming by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<VisibleList?>(null) }
    var deleting by remember { mutableStateOf<VisibleList?>(null) }
    // The list asked for, or the first one when it is gone (deleted on another device) or none was.
    val shown = lists?.let { all -> all.firstOrNull { it.info.id == selectedId } ?: all.firstOrNull() }
    val cards by produceState<List<ListCard>?>(null, shown?.records) {
        val records = shown?.records ?: return@produceState
        value = withContext(Dispatchers.Default) { ListPages.cards(records) }
    }
    val chipFocus = remember { FocusRequester() }
    LaunchedEffect(tv, shown?.info?.id != null) {
        if (!tv) return@LaunchedEffect
        repeat(10) {
            if (runCatching { chipFocus.requestFocus() }.getOrDefault(false)) return@LaunchedEffect
            delay(50)
        }
    }

    // Back from the Delete question (or Cancel) gives the remote back to the chip that asked; a confirmed
    // delete of the last list leaves it to the effect above, which takes the remote to the New chip.
    val deleteFocus = remember { FocusRequester() }
    var backToDelete by remember { mutableStateOf(false) }
    LaunchedEffect(backToDelete, shown?.info?.id) {
        if (!backToDelete) return@LaunchedEffect
        withFrameNanos { }
        backToDelete = false
        if (tv) runCatching { deleteFocus.requestFocus() }
    }

    // F2 renames and Delete deletes the list shown, each through its own dialog (WP-51). Nothing
    // happens while a dialog is up or with no list shown, and the key is then left alone.
    val listKeys = Modifier.onPreviewKeyEvent { event ->
        val list = shown
        if (list == null || naming || renaming != null || deleting != null) return@onPreviewKeyEvent false
        when (listKeyFor(event)) {
            ListKey.Rename -> { renaming = list; true }
            ListKey.Delete -> { deleting = list; true }
            null -> false
        }
    }
    Column(modifier.then(listKeys), verticalArrangement = Arrangement.spacedBy(gap)) {
        top?.invoke()
        Text(
            s.listsHeading,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
            color = Tone.text,
            modifier = Modifier.padding(sides),
        )
        // A plain row that scrolls: at most a hundred chips, and the remote walks it as any row.
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(sides).testTag(LIST_CHIPS_TAG),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            lists.orEmpty().forEach { list ->
                val chosen = list.info.id == shown?.info?.id
                CopyChip(
                    label = list.info.name,
                    chosen = chosen,
                    onClick = { onSelect(list.info.id) },
                    description = "${list.info.name}, ${s.browseVideosCount(list.records.size)}",
                    modifier = if (chosen || (shown == null && list === lists?.firstOrNull())) Modifier.focusRequester(chipFocus) else Modifier,
                )
            }
            if ((lists?.size ?: 0) < NamedListRules.MAX_LISTS) {
                CopyChip(
                    label = "+ ${s.listsNew}",
                    chosen = false,
                    onClick = { naming = true },
                    description = s.listsNew,
                    modifier = if (lists?.isEmpty() == true) Modifier.focusRequester(chipFocus) else Modifier,
                )
            }
        }
        if (shown != null) {
            // Wraps: Rename, On Home and Delete in one line ran off a narrow phone at a large font.
            @OptIn(ExperimentalLayoutApi::class)
            FlowRow(Modifier.padding(sides), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                // Play all and Shuffle lead the list's buttons: what a list is for (a list of one video has no Shuffle).
                val playable = PlayAllLists.playable(shown.records)
                if (onPlay != null && playable >= 1) CopyChip(s.browsePlayAll, chosen = false, onClick = { onPlay(shown, false) })
                if (onPlay != null && playable >= 2) CopyChip(s.browseShuffle, chosen = false, onClick = { onPlay(shown, true) })
                CopyChip(s.listsRename, chosen = false, onClick = { renaming = shown })
                CopyChip(s.listsOnHome, chosen = shown.info.home, onClick = { actions.setHome(shown.info.id, !shown.info.home) }, description = "${s.listsOnHome}: ${if (shown.info.home) s.commonOn else s.commonOff}")
                CopyChip(s.listsDelete, chosen = false, onClick = { deleting = shown }, modifier = Modifier.focusRequester(deleteFocus))
            }
        }
        when {
            lists == null -> Unit
            shown == null -> Message(s.listsNone, sides)
            shown.records.isEmpty() -> Message(s.listsEmpty, sides)
            else -> cards?.let { grid(shown, it, actions) }
        }
    }

    if (naming) {
        ListNameDialog(
            title = s.listsNew,
            initial = "",
            confirm = s.listsCreate,
            onSave = { name ->
                naming = false
                listWrite(onMessage, s) {
                    val made = store.create(name)
                    if (made != null) {
                        kotlinx.coroutines.withContext(Dispatchers.Main) { onSelect(made.id) }
                        ""
                    } else if ((lists?.size ?: 0) >= NamedListRules.MAX_LISTS) {
                        s.listsTooMany(NamedListRules.MAX_LISTS)
                    } else {
                        s.listsNameEmpty
                    }
                }
            },
            onDismiss = { naming = false },
        )
    }
    renaming?.let { list ->
        ListNameDialog(
            title = s.listsRename,
            initial = list.info.name,
            confirm = s.listsSave,
            onSave = { name -> renaming = null; actions.rename(list.info.id, name) },
            onDismiss = { renaming = null },
        )
    }
    deleting?.let { list -> ListDeleteDialog(list, onDelete = { actions.delete(list) }, onDismiss = { deleting = null; backToDelete = true }) }
}

@Composable
private fun Message(text: String, sides: PaddingValues) {
    Box(Modifier.fillMaxWidth().padding(sides).padding(vertical = 24.dp)) {
        Text(text, style = MaterialTheme.typography.bodyLarge, color = Tone.muted, maxLines = 4, overflow = TextOverflow.Ellipsis)
    }
}

const val LIST_CHIPS_TAG = "list-chips"

/** What a key does on a list's page. */
enum class ListKey { Rename, Delete }

/**
 * The key a list's page answers: F2 renames, Delete deletes (Cmd+Backspace on a Mac, where the
 * key marked Delete is Backspace). Any other modifier leaves the key to whoever else wants it.
 */
fun listKeyFor(event: KeyEvent): ListKey? {
    if (event.type != KeyEventType.KeyDown) return null
    if (event.isAltPressed || event.isCtrlPressed || event.isShiftPressed) return null
    val plain = !event.isMetaPressed
    return when {
        event.key == Key.F2 && plain -> ListKey.Rename
        event.key == Key.Delete && plain -> ListKey.Delete
        event.key == Key.Backspace && event.isMetaPressed -> ListKey.Delete
        else -> null
    }
}
