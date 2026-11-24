package com.tmplayer.desktop.ui

import com.tmplayer.ui.components.ToastKind
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.TextButton
import androidx.compose.runtime.collectAsState
import com.tmplayer.ui.components.TmAlertDialog
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.tmplayer.data.SettingsStore
import com.tmplayer.player.LanguageOrder
import com.tmplayer.player.SubtitleMode
import com.tmplayer.player.TrackLanguages
import com.tmplayer.player.TrackPreferences
import com.tmplayer.ui.i18n.LocalStrings
import com.tmplayer.ui.icons.TmIcons
import com.tmplayer.ui.theme.Tone
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** One line of a [ChoiceSetting]: what it reads, an optional second line, and whether it is set. */
internal class MenuChoice(val label: String, val detail: String?, val selected: Boolean, val pick: () -> Unit)

/**
 * The preferred audio languages, for the Playback page: an ordered list, the first match winning
 * ([LanguageOrder]). The row drops down Automatic and the languages not yet in the list (each pick
 * adds one at the end); under it every chosen language is a row of its own with Move up, Move down
 * and Remove, all reached by Tab and Enter as well as the pointer.
 */
@Composable
internal fun AudioLanguageSetting(settings: SettingsStore, prefs: TrackPreferences, scope: CoroutineScope) {
    val s = LocalStrings.current
    val languages = remember(s.messages.tag) { TrackLanguages.choices() }
    fun save(next: TrackPreferences) = scope.launch { settings.setTrackPreferences(next) }
    val order = prefs.audioLanguages
    ChoiceSetting(
        s.settingsAudioLanguage,
        s.settingsTrackLanguagesNote,
        TmIcons.Audio,
        if (order.isEmpty()) s.settingsLanguageAutomatic else prefs.audioSummary,
        listOf(MenuChoice(s.settingsLanguageAutomatic, s.settingsAudioLanguageAuto, order.isEmpty()) { save(prefs.withAudioLanguages(emptyList())) }) +
            languages.filter { it !in order }.map { code ->
                MenuChoice(TrackLanguages.name(code), s.tracksAddLanguage, false) { save(prefs.withAudioLanguages(LanguageOrder.add(order, code))) }
            },
    )
    OrderedLanguageRows(order) { save(prefs.withAudioLanguages(it)) }
}

/** When subtitles come on by themselves, and in which languages, for the Subtitles page. */
@Composable
internal fun SubtitleLanguageSettings(settings: SettingsStore, prefs: TrackPreferences, scope: CoroutineScope) {
    val s = LocalStrings.current
    val languages = remember(s.messages.tag) { TrackLanguages.choices() }
    fun save(next: TrackPreferences) = scope.launch { settings.setTrackPreferences(next) }
    ChoiceSetting(
        s.settingsSubtitleMode,
        prefs.mode.detail,
        TmIcons.Subtitles,
        prefs.mode.label,
        SubtitleMode.entries.map { mode -> MenuChoice(mode.label, mode.detail, prefs.mode == mode) { save(prefs.copy(mode = mode)) } },
    )
    val order = prefs.subtitleLanguages
    val auto = order.isEmpty() && !prefs.subtitlesOff
    val detail = when {
        prefs.subtitlesOff -> s.settingsSubtitleLanguageOff
        auto -> s.settingsSubtitleLanguageAuto
        else -> s.settingsTrackLanguagesNote
    }
    ChoiceSetting(
        s.settingsSubtitleLanguage,
        detail,
        TmIcons.Language,
        when {
            prefs.subtitlesOff -> s.commonOff
            auto -> s.settingsLanguageAutomatic
            else -> prefs.subtitleSummary
        },
        listOf(
            MenuChoice(s.settingsLanguageAutomatic, s.settingsSubtitleLanguageAuto, auto) {
                save(prefs.withSubtitleLanguages(emptyList()))
            },
            MenuChoice(s.commonOff, s.settingsSubtitleLanguageOff, prefs.subtitlesOff) {
                save(prefs.withSubtitleLanguages(emptyList()).copy(subtitlesOff = true))
            },
        ) + languages.filter { it !in order }.map { code ->
            MenuChoice(TrackLanguages.name(code), s.tracksAddLanguage, false) {
                save(prefs.withSubtitleLanguages(LanguageOrder.add(order, code)))
            }
        },
    )
    OrderedLanguageRows(order) { save(prefs.withSubtitleLanguages(it)) }
}

/**
 * The chosen languages as rows in their order: the position in the detail, and Move up, Move down
 * and Remove at the row's end. Nothing is drawn for an empty list.
 */
@Composable
private fun OrderedLanguageRows(order: List<String>, change: (List<String>) -> Unit) {
    val s = LocalStrings.current
    order.forEachIndexed { at, code ->
        val name = TrackLanguages.name(code)
        Setting(name, "${s.tracksLanguageOrder}: ${at + 1} / ${order.size}", icon = TmIcons.Language) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { change(LanguageOrder.move(order, code, -1)) }, enabled = at > 0) {
                    Icon(TmIcons.ChevronUp, contentDescription = "${s.tracksMoveUp}: $name", modifier = Modifier.size(20.dp))
                }
                IconButton(onClick = { change(LanguageOrder.move(order, code, 1)) }, enabled = at < order.size - 1) {
                    Icon(TmIcons.ChevronDown, contentDescription = "${s.tracksMoveDown}: $name", modifier = Modifier.size(20.dp))
                }
                IconButton(onClick = { change(LanguageOrder.remove(order, code)) }) {
                    Icon(TmIcons.Close, contentDescription = "${s.commonRemove}: $name", modifier = Modifier.size(20.dp))
                }
            }
        }
    }
}

/**
 * A row reading what is set at its end; pressing it (a click, Enter or Space) drops [choices]
 * down from there, the set one ticked.
 */
@Composable
internal fun ChoiceSetting(title: String, detail: String, icon: ImageVector, current: String, choices: List<MenuChoice>) {
    var open by remember { mutableStateOf(false) }
    Setting(title, detail, icon = icon, onClick = { open = true }) {
        Box {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(current, style = MaterialTheme.typography.bodyMedium, color = Tone.muted, maxLines = 1)
                Spacer(Modifier.width(6.dp))
                Icon(TmIcons.ChevronDown, contentDescription = null, tint = Tone.muted, modifier = Modifier.size(18.dp))
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }, modifier = Modifier.heightIn(max = 420.dp)) {
                for (choice in choices) {
                    DropdownMenuItem(
                        text = {
                            Column {
                                Text(
                                    choice.label,
                                    color = if (choice.selected) Tone.accent else MaterialTheme.colorScheme.onSurface,
                                )
                                choice.detail?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Tone.muted) }
                            }
                        },
                        trailingIcon = if (choice.selected) {
                            { Icon(TmIcons.Check, contentDescription = null, tint = Tone.accent, modifier = Modifier.size(18.dp)) }
                        } else {
                            null
                        },
                        onClick = {
                            open = false
                            choice.pick()
                        },
                    )
                }
            }
        }
    }
}

/**
 * Remembered languages, for the Subtitles page: how many series the player has learnt, and a
 * dialog listing each by name with "Sound: x, Subtitles: y" and Forget, and Forget all.
 */
@Composable
internal fun LearntLanguagesSetting(settings: SettingsStore, scope: CoroutineScope) {
    val s = LocalStrings.current
    val learnt by settings.learntLanguages.collectAsState(initial = emptyList())
    var open by remember { mutableStateOf(false) }
    var askForgetAll by remember { mutableStateOf(false) }
    val toast = com.tmplayer.ui.components.rememberToast()
    Action(
        s.settingsLearntLanguages,
        if (learnt.isEmpty()) s.settingsLearntLanguagesEmpty else s.settingsLearntLanguagesCount(learnt.size),
        TmIcons.History,
    ) { open = true }
    if (!open) return
    TmAlertDialog(
        onDismissRequest = { open = false },
        title = { Text(s.settingsLearntLanguages) },
        text = {
            Column(Modifier.widthIn(max = 520.dp).heightIn(max = 460.dp).verticalScroll(rememberScrollState())) {
                Text(
                    if (learnt.isEmpty()) s.settingsLearntLanguagesEmpty else s.settingsLearntLanguagesNote,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Tone.muted,
                )
                learnt.forEach { series ->
                    Row(Modifier.padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(series.name, style = MaterialTheme.typography.bodyLarge, color = Tone.text, maxLines = 2)
                            Text(series.line(), style = MaterialTheme.typography.bodyMedium, color = Tone.muted, maxLines = 2)
                        }
                        TextButton(onClick = { scope.launch { settings.forgetLearntLanguages(series.key) } }) {
                            Text(s.settingsLearntLanguagesForget)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { open = false }) { Text(s.commonClose) } },
        dismissButton = if (learnt.isEmpty()) {
            null
        } else {
            { TextButton(onClick = { askForgetAll = true }) { Text(s.settingsLearntLanguagesForgetAll) } }
        },
    )
    if (askForgetAll) {
        // Over the list: one click there would otherwise drop every series' languages at once.
        ConfirmDialog(
            title = s.settingsLearntLanguagesForgetAllTitle,
            message = s.settingsLearntLanguagesForgetAllMessage,
            confirmLabel = s.settingsLearntLanguagesForgetAll,
            onConfirm = {
                askForgetAll = false
                scope.launch {
                    settings.forgetAllLearntLanguages()
                    toast(s.settingsLearntLanguagesForgetAllDone, ToastKind.Success)
                }
            },
            onDismiss = { askForgetAll = false },
        )
    }
}
