package com.tmplayer.ui.browse

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.tmplayer.ui.components.loadWhenShown
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.tmplayer.data.MediaItem
import com.tmplayer.data.PlayQueue
import com.tmplayer.i18n.Translator
import com.tmplayer.ui.components.ChoiceLine
import com.tmplayer.ui.components.ChoiceList
import com.tmplayer.ui.components.ChoiceSheet
import com.tmplayer.ui.components.FocusOnOpen
import com.tmplayer.ui.components.Spinner
import com.tmplayer.ui.components.TmButton
import com.tmplayer.ui.components.TmSecondaryButton
import com.tmplayer.ui.components.TvChoiceRow
import com.tmplayer.ui.i18n.LocalStrings
import com.tmplayer.ui.icons.TmIcons
import com.tmplayer.ui.theme.Tone
import kotlinx.coroutines.launch

/**
 * The way into a chat's audio over its videos: how many files, and the list. In a chat with
 * audio and nothing else to play, it says that first, so the chat never reads as empty.
 */
@Composable
internal fun AudioEntry(count: Int, noVideos: Boolean, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    val s = LocalStrings.current
    Column(modifier.fillMaxWidth().padding(bottom = 8.dp)) {
        if (noVideos) {
            Text(s.browseNoPlayableVideos, style = MaterialTheme.typography.titleMedium, color = Tone.text, modifier = Modifier.padding(bottom = 4.dp))
        }
        TvChoiceRow(
            title = s.gridAudioTitle,
            detail = s.gridAudioCount(count),
            icon = TmIcons.Audio,
            onClick = onOpen,
        )
    }
}

/**
 * A chat's audio as a list: Play all from the oldest, or one file, each going on to the next one
 * posted after it ([com.tmplayer.data.PlayQueue]). Pages load as the end of the list is reached,
 * and a failed or slow page says so with Try again.
 */
@Composable
internal fun AudioSheet(chatId: Long, chatTitle: String, onPlay: (MediaItem) -> Unit, onDismiss: () -> Unit) {
    val s = LocalStrings.current
    val scope = rememberCoroutineScope()
    val list = remember(chatId) { ChatAudioList(chatId, scope) }
    val state by list.state.collectAsState()
    val first = remember { FocusRequester() }
    var shuffle by remember { mutableStateOf(PlayQueue.shuffle) }
    var repeatAll by remember { mutableStateOf(PlayQueue.repeatAll) }
    var repeatOne by remember { mutableStateOf(PlayQueue.repeatOne) }
    ChoiceSheet(title = s.gridAudioTitle, note = chatTitle.ifBlank { null }, onDismiss = onDismiss, onClose = onDismiss, fullScreen = true) {
        ChoiceList {
            if (state.items.isNotEmpty()) {
                ChoiceLine(if (shuffle) s.gridAudioPlayAllShuffled else s.gridAudioPlayAll, modifier = Modifier.focusRequester(first), icon = TmIcons.Play) {
                    scope.launch {
                        val start = list.playAll() ?: return@launch
                        onDismiss()
                        onPlay(start)
                    }
                }
                // Kept for the session in PlayQueue, so the next course starts the same way.
                ChoiceLine(s.gridAudioShuffle, selected = shuffle) {
                    shuffle = !shuffle
                    PlayQueue.setShuffle(shuffle)
                }
                ChoiceLine(s.gridAudioRepeatAll, selected = repeatAll, icon = TmIcons.Repeat) {
                    repeatAll = !repeatAll
                    PlayQueue.setRepeatAll(repeatAll)
                    repeatOne = PlayQueue.repeatOne
                }
                ChoiceLine(s.gridAudioRepeatOne, selected = repeatOne, icon = TmIcons.Repeat) {
                    repeatOne = !repeatOne
                    PlayQueue.setRepeatOne(repeatOne)
                    repeatAll = PlayQueue.repeatAll
                }
            }
            state.items.forEach { item ->
                val detail = listOfNotNull(
                    Translator.messages.formatter.duration(item.durationSec.toLong()).takeIf { item.durationSec > 0 },
                    Translator.messages.formatter.size(item.sizeBytes).takeIf { item.sizeBytes > 0 },
                ).joinToString(" · ")
                ChoiceLine(item.title, detail = detail.ifBlank { null }, icon = TmIcons.Audio) {
                    onDismiss()
                    onPlay(list.queueFrom(item))
                }
            }
            when {
                state.loading -> Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Spinner(size = 18.dp, strokeWidth = 2.dp)
                    Spacer(Modifier.width(12.dp))
                    Text(s.commonLoading, color = Tone.muted)
                }
                state.failure != null -> Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(state.failure.orEmpty(), color = Tone.muted, modifier = Modifier.weight(1f))
                    TmButton(onClick = list::loadMore) { Text(s.commonTryAgain) }
                }
                !state.done -> ChoiceLine(s.gridAudioMore, icon = TmIcons.Add, modifier = Modifier.loadWhenShown(list::loadMore)) { list.loadMore() }
                state.items.isEmpty() -> Text(s.gridAudioNone, color = Tone.muted, modifier = Modifier.padding(16.dp))
            }
        }
        if (state.items.isNotEmpty()) FocusOnOpen(first, key = state.items.isNotEmpty())
    }
}
