package com.tmplayer.ui.onboarding

import com.tmplayer.ui.theme.collectIsFocusVisibleAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.tmplayer.data.DeviceForm
import com.tmplayer.data.SettingsStore
import com.tmplayer.data.ThemeChoice
import com.tmplayer.data.UiScale
import com.tmplayer.i18n.Strings
import com.tmplayer.ui.components.deviceForm
import com.tmplayer.ui.i18n.LocalStrings
import com.tmplayer.ui.icons.TmIcons
import com.tmplayer.ui.theme.Corner
import com.tmplayer.ui.theme.Tone
import com.tmplayer.ui.theme.focusRing
import com.tmplayer.ui.theme.rememberUiScale
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The first run's Appearance step: the theme (System, Light, Dark) with the Pure black switch
 * under it, the accent colour, the interface size (Smaller to Largest, CP59) and, on a phone with Android 12 or later, the wallpaper's colours. Each answer is
 * written as it is picked, and the whole app is drawn from those settings, so this very screen
 * turns light or dark and grows or shrinks at once: the step is its own preview, with a small
 * mock of Home beside it to show a row of posters in the new look.
 *
 * It opens on what is set now, which on a fresh install is the device's default (System, and the
 * interface size the device starts at), so Next alone keeps it as it is. Everything here stays in
 * Settings, under Appearance.
 */
@Composable
fun AppearanceStep(
    settings: SettingsStore,
    onNext: () -> Unit,
    onSkipAll: () -> Unit,
    modifier: Modifier = Modifier,
    before: Int = 0,
    groups: List<Int> = listOf(1),
    /** Whether something follows, so the button says Next rather than Done. */
    moreAfter: Boolean = true,
    /**
     * Takes the recommended settings and ends the setup: a button beside Next in Skip all's place
     * (ON-8), one press from the start focus on a remote (ON-20). Null keeps the quiet Skip all.
     */
    onUseRecommended: (() -> Unit)? = null,
) {
    val s = LocalStrings.current
    val scope = rememberCoroutineScope()
    val form = deviceForm()
    val theme by settings.themeChoice.collectAsState(initial = ThemeChoice.Default)
    val scale by rememberUiScale(settings)
    val wallpaper by settings.dynamicColour.collectAsState(initial = false)
    val pureBlack by settings.pureBlack.collectAsState(initial = false)
    val accent by settings.accent.collectAsState(initial = com.tmplayer.data.Accent.Default)
    val primaryFocus = remember { FocusRequester() }
    var focused by remember { mutableStateOf(false) }

    // Back on the first page is not Skip all (ON-9): a back gesture or a remote's Back meant as
    // "go back" must not end the whole setup unseen. It is left to the system (the phone leaves
    // the app, and opens on this page again), and the shortcut is the button beside Next.

    val last = before + 1 == groups.sum() && !moreAfter
    StepFrame(
        group = s.setupGroupAppearance,
        position = FlowPosition(before, groups),
        primary = if (last) s.setupDone else s.setupNext,
        onPrimary = onNext,
        onBack = null,
        onSkipAll = if (last) null else onSkipAll,
        modifier = modifier,
        primaryFocus = primaryFocus,
        onPrimaryFocused = { focused = true },
        onUseRecommended = if (last) null else onUseRecommended,
    ) { wide ->
        val controls: @Composable () -> Unit = {
            Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
                Section(s.settingsTheme, themeDetail(s, theme, form)) {
                    Pills(ThemeChoice.entries, theme, { it.label }) { scope.launch { settings.setThemeChoice(it) } }
                }
                // Pure black, the usual switch under the theme, offered wherever the app can be
                // dark; the gap above it is the column's, so it folds away with no space left over.
                com.tmplayer.ui.components.RevealUnder(theme.offersPureBlack) {
                    OptionRow(
                        title = s.settingsPureBlack,
                        detail = s.themeBlackDetail,
                        selected = pureBlack,
                        role = Role.Switch,
                    ) { scope.launch { settings.setPureBlack(!pureBlack) } }
                }
                // The accent colour; on a phone using the wallpaper's colours the wallpaper wins, so
                // the swatches are faded there and the line says why.
                val wallpaperWins = form == DeviceForm.Phone && wallpaperColoursOffered && wallpaper
                Section(s.settingsAccent, if (wallpaperWins) s.settingsAccentWallpaper else s.settingsAccentDetail) {
                    com.tmplayer.ui.components.AccentSwatches(
                        accent,
                        { picked -> scope.launch { settings.setAccent(picked) } },
                        enabled = !wallpaperWins,
                    )
                }
                Section(s.interfaceSize, s.interfaceSizeDetail) {
                    Pills(UiScale.entries, scale, { it.label }) { scope.launch { settings.setUiScale(it) } }
                }
                if (form == DeviceForm.Phone && wallpaperColoursOffered) {
                    OptionRow(
                        title = s.settingsWallpaperColours,
                        detail = if (wallpaper) s.settingsWallpaperColoursOn else s.settingsWallpaperColoursOff,
                        selected = wallpaper,
                        role = Role.Switch,
                    ) { scope.launch { settings.setDynamicColour(!wallpaper) } }
                }
            }
        }
        if (wide) {
            Row(
                Modifier.fillMaxSize(),
                horizontalArrangement = Arrangement.spacedBy(40.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(
                    Modifier.weight(1f).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    StepHeading(s.setupAppearanceTitle, s.setupAppearanceBody)
                    Preview(Modifier.widthIn(max = 360.dp).fillMaxWidth())
                    Text(s.setupLater, style = MaterialTheme.typography.bodySmall, color = Tone.muted)
                }
                Column(
                    Modifier.weight(1.3f).fillMaxHeight().verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.Center,
                ) {
                    controls()
                }
            }
        } else {
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                StepHeading(s.setupAppearanceTitle, s.setupAppearanceBody)
                Preview(Modifier.fillMaxWidth())
                controls()
                Text(s.setupLater, style = MaterialTheme.typography.bodySmall, color = Tone.muted)
            }
        }
    }

    // A remote or a keyboard starts on Next, as on every step.
    if (form != DeviceForm.Phone) {
        LaunchedEffect(Unit) {
            repeat(FOCUS_TRIES) {
                withFrameNanos { }
                if (it > 0) delay(FOCUS_RETRY_MS)
                if (focused) return@LaunchedEffect
                runCatching { primaryFocus.requestFocus() }
            }
        }
    }
}

/** The sentence under the theme's choices, as Settings words it on this device. */
private fun themeDetail(s: Strings, theme: ThemeChoice, form: DeviceForm): String = when (form) {
    DeviceForm.Tv -> theme.tvDescription
    DeviceForm.Desktop -> if (theme == ThemeChoice.System) s.themeSystemDesktopDetail else theme.description
    DeviceForm.Phone -> theme.description
}

/** A setting's name, its choices, and the line that says what the chosen one does. */
@Composable
private fun Section(title: String, detail: String, choices: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium, color = Tone.text)
        choices()
        Text(detail, style = MaterialTheme.typography.bodySmall, color = Tone.muted)
    }
}

/**
 * One setting's choices as a row of pills that wraps when the words do not fit (four sizes in a
 * long language on a narrow phone). The chosen one is filled in the accent with a check. Arrow
 * keys move between them on a desktop as a remote's D-pad does; Enter or OK picks.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun <T> Pills(options: List<T>, selected: T, label: (T) -> String, onPick: (T) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEachIndexed { i, option ->
            Pill(label(option), option == selected, first = i == 0, last = i == options.lastIndex) { onPick(option) }
        }
    }
}

@Composable
private fun Pill(label: String, selected: Boolean, first: Boolean, last: Boolean, onClick: () -> Unit) {
    val interactions = remember { MutableInteractionSource() }
    val focused = interactions.collectIsFocusVisibleAsState().value
    val touch = deviceForm() == DeviceForm.Phone
    // With a remote or a keyboard the chosen pill is only tinted, as the TV's Downloads chips are:
    // in the dark theme the focus fill is the accent itself, so a solid accent would look focused.
    val fill = when {
        focused -> Tone.focusFill
        selected && touch -> Tone.accent
        selected -> Tone.accent.copy(alpha = 0.16f)
        else -> Tone.surfaceHigh
    }
    val ink = when {
        focused -> Tone.onFocusFill
        selected && touch -> Tone.onAccent
        selected -> Tone.accent
        else -> Tone.text
    }
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    Row(
        Modifier
            // Left and Right stay in this row: past its end there is nothing to move to, rather
            // than the nearest pill of the row below.
            .onPreviewKeyEvent { event ->
                event.type == KeyEventType.KeyDown && when (event.key) {
                    Key.DirectionRight -> if (rtl) first else last
                    Key.DirectionLeft -> if (rtl) last else first
                    else -> false
                }
            }
            .arrowsMoveFocus()
            .height(40.dp)
            .clip(CircleShape)
            .background(fill)
            .focusRing(focused, CircleShape)
            .semantics { this.selected = selected }
            .clickable(
                interactionSource = interactions,
                indication = if (touch) LocalIndication.current else null,
                role = Role.RadioButton,
                onClick = onClick,
            )
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (selected) {
            Icon(TmIcons.Check, contentDescription = null, tint = ink, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
        }
        Text(label, style = MaterialTheme.typography.labelLarge, color = ink, maxLines = 1)
    }
}

/**
 * A small mock of Home in the current theme and size: a row's title and three posters with how far
 * each has been watched. Drawn from the same colours and type as the app, so it changes with every
 * pick; it has no words of its own beyond the row's title.
 */
@Composable
private fun Preview(modifier: Modifier) {
    val s = LocalStrings.current
    Column(
        modifier
            .clip(RoundedCornerShape(Corner.Large))
            .background(Tone.surface)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(s.homeRowContinue, style = MaterialTheme.typography.titleSmall, color = Tone.text, maxLines = 1)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            for (watched in listOf(0.7f, 0.35f, 0.9f)) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .aspectRatio(2f / 3f)
                            .clip(RoundedCornerShape(Corner.Medium))
                            .background(Tone.surfaceHigh),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(TmIcons.Play, contentDescription = null, tint = Tone.muted, modifier = Modifier.size(22.dp))
                    }
                    Box(Modifier.fillMaxWidth().height(4.dp).clip(CircleShape).background(Tone.outline)) {
                        Box(Modifier.fillMaxWidth(watched).fillMaxHeight().background(Tone.accent))
                    }
                    Box(Modifier.fillMaxWidth(0.8f).height(8.dp).clip(CircleShape).background(Tone.surfaceHigh))
                }
            }
        }
    }
}
