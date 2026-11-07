package com.tmplayer.desktop.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.tmplayer.data.MediaItem
import com.tmplayer.data.PosterPick
import com.tmplayer.data.VideoCorrection
import com.tmplayer.online.MetaInfo
import com.tmplayer.ui.browse.PickerLoad
import com.tmplayer.ui.browse.PosterChoiceThumb
import com.tmplayer.ui.browse.isCurrentChoice
import com.tmplayer.ui.browse.posterChoiceFacts
import com.tmplayer.ui.browse.posterPickerQuery
import com.tmplayer.ui.browse.rememberPosterChoices
import com.tmplayer.ui.browse.rememberSameTitleOthers
import com.tmplayer.ui.components.TmAlertDialog
import com.tmplayer.ui.i18n.LocalStrings
import com.tmplayer.ui.theme.Tone
import kotlinx.coroutines.launch

/**
 * "Choose the right poster" on the desktop: the TMDB titles the video could be in a dialog, a poster,
 * name, year, language and a line of story for each, the one on show marked. Enter or a click picks
 * ([onPick], with the other videos of the title in this chat when the box is ticked); Escape closes.
 * The first title has the keyboard when it opens; Reset puts the video back to automatic.
 */
@Composable
internal fun PosterPickerDialog(
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
    val first = remember { FocusRequester() }
    TmAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(s.posterChoose) },
        text = { PosterPickerBody(load, shown, correction, others, first, onPick, onRetry = { attempt++ }) },
        confirmButton = {
            if (correction?.pick != null || correction?.wrongMatch == true) {
                TextButton(onClick = onReset) { Text(s.posterReset) }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(s.commonCancel) } },
    )
    // After the dialog's first frame: its window is not attached when the effect first runs. Keyed
    // on the answer, so the keyboard lands on the first title (or Try again) once they are there.
    LaunchedEffect(load) {
        repeat(5) {
            withFrameNanos { }
            if (runCatching { first.requestFocus() }.isSuccess) return@LaunchedEffect
        }
    }
}

/**
 * The picker's list and its states, apart from the dialog around it so keys can test it. [first]
 * is attached to the first title (or Try again) for the caller to focus. A second press on a title
 * while the first is being written is the same press again.
 */
@Composable
internal fun PosterPickerBody(
    load: PickerLoad,
    shown: MetaInfo?,
    correction: VideoCorrection?,
    others: List<MediaItem>,
    first: FocusRequester,
    onPick: (PosterPick, List<MediaItem>) -> Unit,
    onRetry: () -> Unit,
) {
    val s = LocalStrings.current
    var alsoOthers by remember { mutableStateOf(false) }
    var chosen by remember { mutableStateOf(false) }
    val ready = load as? PickerLoad.Ready
    Column(Modifier.width(480.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(s.posterNote, color = Tone.muted, style = MaterialTheme.typography.bodySmall)
        if (ready != null && ready.choices.isNotEmpty() && others.isNotEmpty()) {
            Row(
                Modifier.fillMaxWidth().testTag("poster-others").clickable { alsoOthers = !alsoOthers },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(checked = alsoOthers, onCheckedChange = { alsoOthers = it })
                Text(s.posterOthers(others.size))
            }
        }
        when (load) {
            PickerLoad.Loading -> Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.width(18.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(12.dp))
                Text(s.commonLoading, color = Tone.muted)
            }
            is PickerLoad.Message -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(load.text, color = Tone.muted, modifier = Modifier.weight(1f))
                if (load.canRetry) TextButton(onClick = onRetry, modifier = Modifier.focusRequester(first)) { Text(s.commonTryAgain) }
            }
            is PickerLoad.Ready -> if (load.choices.isEmpty()) {
                Text(s.posterNone, color = Tone.muted)
            } else {
                // Up and Down walk the titles (a dialog leaves arrows alone), scrolling the one wanted
                // into the list first when it is not drawn yet.
                val listState = rememberLazyListState()
                val scope = rememberCoroutineScope()
                val requesters = remember(load.choices) { List(load.choices.size) { if (it == 0) first else FocusRequester() } }
                val goTo: (Int) -> Boolean = { to ->
                    if (to in requesters.indices) {
                        scope.launch {
                            listState.scrollToItem(to)
                            withFrameNanos { }
                            runCatching { requesters[to].requestFocus() }
                        }
                    }
                    true
                }
                LazyColumn(Modifier.heightIn(max = 460.dp), state = listState, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    itemsIndexed(load.choices, key = { _, c -> "${c.show}:${c.tmdbId}" }) { at, choice ->
                        val current = isCurrentChoice(choice, shown, correction)
                        var focused by remember { mutableStateOf(false) }
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .testTag("choice-${choice.tmdbId}")
                                .focusRequester(requesters[at])
                                .onFocusChanged { focused = it.isFocused }
                                .onKeyEvent { event ->
                                    when {
                                        event.type != KeyEventType.KeyDown -> false
                                        event.key == Key.DirectionDown -> goTo(at + 1)
                                        event.key == Key.DirectionUp -> goTo(at - 1)
                                        else -> false
                                    }
                                }
                                .border(if (focused) 2.dp else 1.dp, if (focused) Tone.accent else Tone.outline, RoundedCornerShape(8.dp))
                                .clickable {
                                    if (!chosen) {
                                        chosen = true
                                        onPick(choice.pick, if (alsoOthers) others else emptyList())
                                    }
                                }
                                .padding(8.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            PosterChoiceThumb(choice, width = 56.dp)
                            Column(Modifier.weight(1f)) {
                                Text(choice.title, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(
                                    (if (current) "${s.posterCurrent}  ·  " else "") + posterChoiceFacts(choice),
                                    color = if (current) Tone.accent else Tone.muted,
                                    style = MaterialTheme.typography.bodySmall,
                                )
                                if (choice.overview.isNotBlank()) {
                                    Text(choice.overview, color = Tone.muted, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
