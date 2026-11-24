package com.tmplayer.ui.settings

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.IconButton
import androidx.compose.material3.Icon as M3Icon
import androidx.compose.material3.Text as M3Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.tmplayer.player.LanguageOrder
import com.tmplayer.player.SubtitleMode
import com.tmplayer.player.TrackLanguages
import com.tmplayer.player.TrackPreferences
import com.tmplayer.ui.components.ChoiceLine
import com.tmplayer.ui.components.ChoiceList
import com.tmplayer.ui.components.ChoiceSection
import com.tmplayer.ui.components.ChoiceSheet
import com.tmplayer.ui.components.FocusOnOpen
import com.tmplayer.ui.components.TvChoiceRow
import com.tmplayer.ui.components.TvConfirm
import com.tmplayer.ui.components.isTouch
import com.tmplayer.ui.i18n.LocalStrings
import com.tmplayer.ui.icons.TmIcons
import com.tmplayer.ui.theme.Tone

/** Which of the three app-wide track settings is open over the page. */
enum class TrackLanguagePicker { Audio, Subtitle, Mode }

/**
 * Settings, the preferred audio languages, the preferred subtitle languages and when subtitles come
 * on, each as a [ChoiceSheet], so a remote walks them the way it walks every other picker. A pick
 * is saved; the note says where these sit in the order, since a series that remembers its own
 * choice is the first thing a viewer will meet that ignores them.
 *
 * The two language settings are ordered lists, the first match winning ([LanguageOrder]): the
 * chosen languages in their order, each movable (Left and Right on a remote, buttons on a touch
 * screen) and removable (OK on a remote, which asks first), then Add a language, which lists the
 * rest. Automatic (and Off, for subtitles) empty the list and close the sheet.
 */
@Composable
fun TrackLanguageSheet(
    picker: TrackLanguagePicker,
    prefs: TrackPreferences,
    onChange: (TrackPreferences) -> Unit,
    onClose: () -> Unit,
) {
    val s = LocalStrings.current
    if (picker == TrackLanguagePicker.Mode) {
        val current = remember { FocusRequester() }
        ChoiceSheet(title = s.settingsSubtitleMode, note = s.settingsTrackLanguagesNote, onDismiss = onClose, onClose = onClose) {
            ChoiceList {
                SubtitleMode.entries.forEach { mode ->
                    val selected = prefs.mode == mode
                    ChoiceLine(
                        title = mode.label,
                        detail = mode.detail,
                        selected = selected,
                        modifier = if (selected) Modifier.focusRequester(current) else Modifier,
                    ) {
                        onChange(prefs.copy(mode = mode))
                        onClose()
                    }
                }
            }
            FocusOnOpen(current)
        }
        return
    }
    LanguageOrderSheet(picker == TrackLanguagePicker.Audio, prefs, onChange, onClose)
}

@Composable
private fun LanguageOrderSheet(
    audio: Boolean,
    prefs: TrackPreferences,
    onChange: (TrackPreferences) -> Unit,
    onClose: () -> Unit,
) {
    val s = LocalStrings.current
    val touch = isTouch()
    val landing = remember { FocusRequester() }
    // Sorted by name in the UI language, so read again whenever that changes.
    val languages = remember(s.messages.tag) { TrackLanguages.choices() }
    // Held here, so a second press before the store has answered builds on the first.
    var order by remember { mutableStateOf(if (audio) prefs.audioLanguages else prefs.subtitleLanguages) }
    var off by remember { mutableStateOf(!audio && prefs.subtitlesOff) }
    var adding by remember { mutableStateOf(false) }
    var removing by remember { mutableStateOf<String?>(null) }
    fun save(list: List<String>, subtitlesOff: Boolean = false) {
        val clean = LanguageOrder.clean(list)
        order = clean
        off = subtitlesOff
        onChange(
            when {
                audio -> prefs.withAudioLanguages(clean)
                subtitlesOff -> prefs.withSubtitleLanguages(emptyList()).copy(subtitlesOff = true)
                else -> prefs.withSubtitleLanguages(clean)
            },
        )
    }
    fun Modifier.landing(here: Boolean) = if (here) focusRequester(landing) else this
    val back = { if (adding) adding = false else onClose() }
    val available = languages.filter { it !in order }
    ChoiceSheet(
        title = when {
            adding -> s.tracksAddLanguage
            audio -> s.settingsAudioLanguage
            else -> s.settingsSubtitleLanguage
        },
        note = s.settingsTrackLanguagesNote,
        onDismiss = back,
        onClose = back,
    ) {
        ChoiceList {
            if (adding) {
                available.forEachIndexed { at, code ->
                    ChoiceLine(title = TrackLanguages.name(code), modifier = Modifier.landing(at == 0)) {
                        save(LanguageOrder.add(order, code))
                        adding = false
                    }
                }
            } else {
                val auto = order.isEmpty() && !off
                ChoiceLine(
                    title = s.settingsLanguageAutomatic,
                    detail = if (audio) s.settingsAudioLanguageAuto else s.settingsSubtitleLanguageAuto,
                    selected = auto,
                    modifier = Modifier.landing(auto),
                ) {
                    save(emptyList())
                    onClose()
                }
                if (!audio) {
                    ChoiceLine(title = s.commonOff, detail = s.settingsSubtitleLanguageOff, selected = off, modifier = Modifier.landing(off)) {
                        save(emptyList(), subtitlesOff = true)
                        onClose()
                    }
                }
                if (order.isNotEmpty()) {
                    ChoiceSection(s.tracksLanguageOrder)
                    order.forEachIndexed { at, code ->
                        // Keyed by the language, so a moved line keeps the remote with it.
                        key(code) {
                            OrderedLanguageLine(
                                code = code,
                                index = at,
                                count = order.size,
                                modifier = Modifier.landing(at == 0),
                                onMove = { by -> save(LanguageOrder.move(order, code, by)) },
                                onRemove = { if (touch) save(LanguageOrder.remove(order, code)) else removing = code },
                            )
                        }
                    }
                }
                if (available.isNotEmpty() && order.size < LanguageOrder.MAX) {
                    ChoiceLine(title = s.tracksAddLanguage, icon = TmIcons.Add) { adding = true }
                }
            }
        }
        // Again when a language is added or taken away, so the remote is never left on a line that went.
        FocusOnOpen(landing, key = Triple(adding, order.size, removing == null))
    }
    removing?.let { code ->
        TvConfirm(
            title = s.commonRemove,
            message = s.tracksRemoveLanguage(TrackLanguages.name(code)),
            confirmLabel = s.commonRemove,
            onConfirm = {
                save(LanguageOrder.remove(order, code))
                removing = null
            },
            onDismiss = { removing = null },
        )
    }
}

/**
 * One chosen language in the order. On a remote: Left moves it up, Right down (the other way round
 * in a right to left layout), OK removes it after asking. On a touch screen: its own Move up, Move
 * down and Remove buttons.
 */
@Composable
private fun OrderedLanguageLine(
    code: String,
    index: Int,
    count: Int,
    modifier: Modifier,
    onMove: (Int) -> Unit,
    onRemove: () -> Unit,
) {
    val s = LocalStrings.current
    val name = TrackLanguages.name(code)
    if (!isTouch()) {
        val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
        TvChoiceRow(
            title = name,
            trailing = "${index + 1}",
            detail = "◀ ${s.tracksMoveUp}    ▶ ${s.tracksMoveDown}",
            modifier = modifier.onPreviewKeyEvent { event ->
                val up = if (rtl) Key.DirectionRight else Key.DirectionLeft
                val down = if (rtl) Key.DirectionLeft else Key.DirectionRight
                when {
                    event.key != up && event.key != down -> false
                    event.type == KeyEventType.KeyDown -> {
                        onMove(if (event.key == up) -1 else 1)
                        true
                    }
                    else -> true
                }
            },
            onClick = onRemove,
        )
        return
    }
    Row(modifier.fillMaxWidth().heightIn(min = 56.dp).padding(start = 24.dp, end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        M3Text(name, color = Tone.text, modifier = Modifier.weight(1f))
        IconButton(onClick = { onMove(-1) }, enabled = index > 0) {
            M3Icon(TmIcons.ChevronUp, contentDescription = "${s.tracksMoveUp}: $name", modifier = Modifier.size(22.dp))
        }
        IconButton(onClick = { onMove(1) }, enabled = index < count - 1) {
            M3Icon(TmIcons.ChevronDown, contentDescription = "${s.tracksMoveDown}: $name", modifier = Modifier.size(22.dp))
        }
        IconButton(onClick = onRemove) {
            M3Icon(TmIcons.Close, contentDescription = "${s.commonRemove}: $name", modifier = Modifier.size(22.dp))
        }
    }
}
