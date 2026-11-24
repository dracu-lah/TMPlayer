package com.tmplayer.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import com.tmplayer.i18n.Strings
import com.tmplayer.online.OnlineMetadata
import com.tmplayer.online.SkipGroup
import com.tmplayer.online.SkipMode
import com.tmplayer.ui.components.ChoiceLine
import com.tmplayer.ui.components.ChoiceList
import com.tmplayer.ui.components.ChoiceSheet
import com.tmplayer.ui.components.FocusOnOpen
import com.tmplayer.ui.i18n.LocalStrings
import com.tmplayer.ui.icons.TmIcons

/** The words for what a [group] of stretches is called in Settings. */
internal fun Strings.skipGroupName(group: SkipGroup): String = when (group) {
    SkipGroup.Recap -> settingsSkipRecap
    SkipGroup.Intro -> settingsSkipIntro
    SkipGroup.Credits -> settingsSkipCredits
    SkipGroup.Preview -> settingsSkipPreview
}

internal fun Strings.skipModeName(mode: SkipMode): String = when (mode) {
    SkipMode.Ask -> settingsSkipModeAsk
    SkipMode.Auto -> settingsSkipModeAuto
    SkipMode.Off -> commonOff
}

internal fun Strings.skipModeDetail(mode: SkipMode): String = when (mode) {
    SkipMode.Ask -> settingsSkipModeAskDetail
    SkipMode.Auto -> settingsSkipModeAutoDetail
    SkipMode.Off -> settingsSkipModeOffDetail
}

/**
 * Playback's Skip buttons (WP-08): one row for the four kinds of skip, Recaps, Intros and openings,
 * Credits and endings, Previews, which used to be four lines under the Skip intro buttons switch in
 * Home and library (that switch and its privacy line stay with the online lookups). The row opens a
 * [ChoiceSheet] that lists the four with what is chosen for each; a line opens a second sheet of Ask
 * me, Skip by itself and Off, which a remote walks like every other picker, and closing it comes
 * back to the list.
 */
@Composable
internal fun SkipButtonsRow(modifier: Modifier = Modifier) {
    val s = LocalStrings.current
    val online = OnlineMetadata.current ?: return
    var open by remember { mutableStateOf(false) }
    ActionRow(
        title = s.settingsSkipButtons,
        modifier = modifier,
        subtitle = s.settingsSkipButtonsSummary,
        icon = TmIcons.Episodes,
        onClick = { open = true },
    )
    if (!open) return
    val settings by online.settings.collectAsState()
    var picking by remember { mutableStateOf<SkipGroup?>(null) }
    val landing = remember { FocusRequester() }
    val group = picking
    if (group == null) {
        ChoiceSheet(title = s.settingsSkipButtons, note = s.settingsSkipModeNote, onDismiss = { open = false }, onClose = { open = false }) {
            ChoiceList {
                SkipGroup.entries.forEachIndexed { index, each ->
                    ChoiceLine(
                        title = s.skipGroupName(each),
                        detail = s.skipModeName(settings.skipModes.of(each)),
                        modifier = if (index == 0) Modifier.focusRequester(landing) else Modifier,
                    ) { picking = each }
                }
            }
            FocusOnOpen(landing)
        }
        return
    }
    SkipModeSheet(group, onClose = { picking = null })
}

/** The sheet of Ask me, Skip by itself and Off for one kind of skip; [onClose] comes back to the list. */
@Composable
private fun SkipModeSheet(group: SkipGroup, onClose: () -> Unit) {
    val s = LocalStrings.current
    val online = OnlineMetadata.current ?: return onClose()
    val settings by online.settings.collectAsState()
    val current = settings.skipModes.of(group)
    val landing = remember { FocusRequester() }
    ChoiceSheet(title = s.skipGroupName(group), note = s.settingsSkipModeNote, onDismiss = onClose, onClose = onClose) {
        ChoiceList {
            SkipMode.entries.forEach { mode ->
                val selected = mode == current
                ChoiceLine(
                    title = s.skipModeName(mode),
                    detail = s.skipModeDetail(mode),
                    selected = selected,
                    modifier = if (selected) Modifier.focusRequester(landing) else Modifier,
                ) {
                    online.setSkipMode(group, mode)
                    onClose()
                }
            }
        }
        FocusOnOpen(landing)
    }
}
