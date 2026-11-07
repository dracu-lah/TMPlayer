package com.tmplayer.ui.browse

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import com.tmplayer.data.MediaItem
import com.tmplayer.data.PosterPick
import com.tmplayer.data.VideoCorrection
import com.tmplayer.online.MetaInfo
import com.tmplayer.ui.components.ChoiceLine
import com.tmplayer.ui.components.ChoiceList
import com.tmplayer.ui.components.ChoiceSheet
import com.tmplayer.ui.components.FocusOnOpen
import com.tmplayer.ui.components.Spinner
import com.tmplayer.ui.components.TmButton
import com.tmplayer.ui.i18n.LocalStrings
import com.tmplayer.ui.icons.TmIcons
import com.tmplayer.ui.theme.Tone

/**
 * "Choose the right poster" on the phone and the television: the TMDB titles the video could be,
 * a poster, name, year, language and a line of story for each, the one on show marked. Pressing a
 * title picks it ([onPick], with whether the others of its title in this chat follow); Back closes.
 * On a remote the first title has focus when it opens, and the page under it keeps the focus it
 * had on the row that opened this, which is where it is back to when this closes.
 *
 * @param shown the title the tile wears now, for the mark.
 * @param onReset back to automatic: the pick and "Wrong match" both removed.
 */
@Composable
internal fun PosterPickerSheet(
    item: MediaItem,
    correction: VideoCorrection?,
    shown: MetaInfo?,
    onPick: (PosterPick, List<MediaItem>) -> Unit,
    onReset: () -> Unit,
    onDismiss: () -> Unit,
) {
    val s = LocalStrings.current
    var attempt by remember { mutableIntStateOf(0) }
    val query = remember(item.id, correction?.title) { posterPickerQuery(item, correction) }
    val load = rememberPosterChoices(query, attempt)
    val others = rememberSameTitleOthers(item)
    // Off by default: the others are a second, larger change, asked once and only when there are any.
    var alsoOthers by remember { mutableStateOf(false) }
    // A second press while the first is being written is the same press again.
    var chosen by remember { mutableStateOf(false) }
    val first = remember { FocusRequester() }
    val ready = load as? PickerLoad.Ready
    val hasReset = correction?.pick != null || correction?.wrongMatch == true
    ChoiceSheet(
        title = s.posterChoose,
        note = s.posterNote,
        onDismiss = onDismiss,
        onClose = onDismiss,
        // The remote lands on Close when there is nothing else to stand on (a message, no titles).
        closeFocus = first.takeIf { ready == null || ready.choices.isEmpty() },
        fullScreen = true,
    ) {
        ChoiceList {
            if (ready != null && ready.choices.isNotEmpty() && others.isNotEmpty()) {
                ChoiceLine(s.posterOthers(others.size), selected = alsoOthers) { alsoOthers = !alsoOthers }
            }
            when (load) {
                PickerLoad.Loading -> Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Spinner(size = 18.dp, strokeWidth = 2.dp)
                    Spacer(Modifier.width(12.dp))
                    Text(s.commonLoading, color = Tone.muted)
                }
                is PickerLoad.Message -> Row(
                    Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(load.text, color = Tone.muted, modifier = Modifier.weight(1f))
                    if (load.canRetry) {
                        TmButton(onClick = { attempt++ }, modifier = Modifier.focusRequester(first)) { Text(s.commonTryAgain) }
                    }
                }
                is PickerLoad.Ready -> {
                    if (load.choices.isEmpty()) Text(s.posterNone, color = Tone.muted, modifier = Modifier.padding(16.dp))
                    load.choices.forEachIndexed { at, choice ->
                        val current = isCurrentChoice(choice, shown, correction)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            PosterChoiceThumb(choice, modifier = Modifier.padding(start = 8.dp))
                            ChoiceLine(
                                choice.title,
                                modifier = (if (at == 0) Modifier.focusRequester(first) else Modifier).weight(1f),
                                detail = posterChoiceDetail(choice, current),
                                selected = current,
                            ) {
                                if (!chosen) {
                                    chosen = true
                                    onPick(choice.pick, if (alsoOthers) others else emptyList())
                                }
                            }
                        }
                    }
                }
            }
            if (hasReset) {
                ChoiceLine(s.posterReset, icon = TmIcons.Refresh) { if (!chosen) { chosen = true; onReset() } }
            }
        }
        FocusOnOpen(first, key = load)
    }
}
