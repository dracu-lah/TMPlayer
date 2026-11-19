package com.tmplayer.player

import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.tmplayer.data.ListsStore
import com.tmplayer.data.NamedListRules
import com.tmplayer.data.VisibleList
import com.tmplayer.data.WatchLaterRecord
import com.tmplayer.ui.browse.ListNameDialog
import com.tmplayer.ui.components.ChoiceLine
import com.tmplayer.ui.components.ChoiceList
import com.tmplayer.ui.components.ChoiceSheet
import com.tmplayer.ui.components.FocusOnOpen
import com.tmplayer.ui.i18n.LocalStrings
import com.tmplayer.ui.icons.TmIcons
import com.tmplayer.ui.theme.Tone

/**
 * What the More options' "Add to a list" page works on: the [record] as it was when the page was
 * opened (so a video that moves on to the next episode under the open page is not added by
 * mistake), the viewer's [lists], and [say] for the line a write answers with.
 */
class PlayerListTarget(
    val record: WatchLaterRecord,
    val lists: ListsStore,
    val say: (String) -> Unit,
)

/** The page's New list line, for the tests. */
internal const val PLAYER_LISTS_NEW_TAG = "player_lists_new"

/**
 * "Add to a list" as a page of the player's More options (HX43), over [lists] as they are (null
 * while they are read): a line per list, with the tick where the video already is (a press takes it
 * out, as in the card menu), then New list, which asks for a name and puts the video in the new
 * list. Pressing writes at once and the page stays, so the tick is the answer; Back is [onBack].
 *
 * On a remote the focus starts on the first list (on New list when there is none yet) and goes
 * back to the New list line when the name dialog closes, so the player's focus never falls to the
 * window.
 */
@Composable
internal fun PlayerListsSheet(
    record: WatchLaterRecord,
    lists: List<VisibleList>?,
    onPick: (VisibleList, Boolean) -> Unit,
    onNew: (String) -> Unit,
    onBack: () -> Unit,
) {
    val s = LocalStrings.current
    var naming by remember { mutableStateOf(false) }
    // Once New list has been pressed, the remote returns to it after the name dialog.
    var usedNew by remember { mutableStateOf(false) }
    val first = remember { FocusRequester() }
    val newList = remember { FocusRequester() }
    val shown = lists.orEmpty()
    val canMake = lists != null && shown.size < NamedListRules.MAX_LISTS
    ChoiceSheet(title = s.listsAddTo, note = record.title, onDismiss = onBack) {
        ChoiceList {
            shown.forEachIndexed { index, list ->
                val inIt = record.id in list.ids
                ChoiceLine(
                    title = list.info.name,
                    detail = if (inIt) s.listsRemove else s.browseVideosCount(list.records.size),
                    icon = if (inIt) TmIcons.CheckCircle else TmIcons.List,
                    modifier = if (index == 0) Modifier.focusRequester(first) else Modifier,
                ) { onPick(list, inIt) }
            }
            if (canMake) {
                ChoiceLine(
                    title = s.listsNew,
                    detail = s.listsNameHint,
                    icon = TmIcons.Add,
                    modifier = Modifier.focusRequester(newList).testTag(PLAYER_LISTS_NEW_TAG),
                ) { usedNew = true; naming = true }
            }
        }
        if (lists != null && shown.isEmpty()) {
            Text(
                s.listsNone,
                style = MaterialTheme.typography.bodyMedium,
                color = Tone.muted,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
        // The first list, or New list when there is none, and again when the name dialog is gone
        // (not while it is up: its own window holds the remote then).
        if (lists != null && !naming) FocusOnOpen(if (canMake && (usedNew || shown.isEmpty())) newList else first)
    }
    if (naming) {
        ListNameDialog(
            title = s.listsNew,
            initial = "",
            confirm = s.listsCreate,
            onSave = { name -> naming = false; onNew(name) },
            onDismiss = { naming = false },
        )
    }
}
