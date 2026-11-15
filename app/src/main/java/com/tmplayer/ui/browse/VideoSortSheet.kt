package com.tmplayer.ui.browse

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import com.tmplayer.ui.components.ChoiceLine
import com.tmplayer.ui.components.ChoiceList
import com.tmplayer.ui.components.ChoiceSection
import com.tmplayer.ui.components.ChoiceSheet
import com.tmplayer.ui.components.FocusOnOpen
import com.tmplayer.ui.components.isTouch
import com.tmplayer.ui.i18n.LocalStrings

/**
 * A chat's Sort menu under a remote: the panel every picker uses, with the same groups as the
 * phone's popover ([videoMenuGroups]): Play random, Sort videos by, Show and the filters by what a
 * video is, each a subheader over its lines with the one in force marked. A pick applies it and
 * closes the panel, so the grid is seen to change behind it.
 */
@Composable
internal fun VideoSortSheet(state: VideoMenuState, onDismiss: () -> Unit) {
    val s = LocalStrings.current
    // The remote opens on the order in force, not on the first line, as every other picker does.
    val chosen = remember { FocusRequester() }
    val groups = videoMenuGroups(state, s)
    // Play random, when offered, is the first group; the remote still opens on the order in force.
    val orderGroup = groups.indexOfFirst { it.title == s.gridSortLabel }
    ChoiceSheet(title = s.gridSort, onDismiss = onDismiss, onClose = onDismiss, ignoreRelease = !isTouch()) {
        ChoiceList {
            groups.forEachIndexed { index, group ->
                ChoiceSection(group.title)
                group.choices.forEach { choice ->
                    ChoiceLine(
                        choice.label,
                        modifier = if (index == orderGroup && choice.chosen) Modifier.focusRequester(chosen) else Modifier,
                        selected = choice.chosen,
                        icon = choice.icon,
                        onClick = choice.onPick,
                    )
                }
            }
        }
        FocusOnOpen(chosen)
    }
}
