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
 * One line per kind of skip under "Skip intro buttons": Recaps, Intros and openings, Credits and
 * endings, Previews. Each reads what is chosen and opens a [ChoiceSheet] of Ask me, Skip by
 * itself and Off, which a remote walks like every other picker. Shown only while the switch above
 * is on, since Off there already means no buttons at all.
 */
@Composable
internal fun SkipModeRow(group: SkipGroup) {
    val s = LocalStrings.current
    val online = OnlineMetadata.current ?: return
    val settings by online.settings.collectAsState()
    if (!settings.skipTimes) return
    var open by remember { mutableStateOf(false) }
    val current = settings.skipModes.of(group)
    ActionRow(
        title = s.skipGroupName(group),
        subtitle = s.skipModeName(current),
        icon = TmIcons.Episodes,
        onClick = { open = true },
    )
    if (!open) return
    val landing = remember { FocusRequester() }
    ChoiceSheet(title = s.skipGroupName(group), note = s.settingsSkipModeNote, onDismiss = { open = false }, onClose = { open = false }) {
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
                    open = false
                }
            }
        }
        FocusOnOpen(landing)
    }
}
