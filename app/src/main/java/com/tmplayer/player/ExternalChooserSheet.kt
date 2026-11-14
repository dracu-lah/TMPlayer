package com.tmplayer.player

import android.graphics.drawable.Drawable
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.tmplayer.ui.components.ChoiceList
import com.tmplayer.ui.components.ChoiceSheet
import com.tmplayer.ui.components.FocusOnOpen
import com.tmplayer.ui.components.PhoneSheet
import com.tmplayer.ui.components.TmButton
import com.tmplayer.ui.components.TmSecondaryButton
import com.tmplayer.ui.components.isTouch
import com.tmplayer.ui.i18n.LocalStrings
import com.tmplayer.ui.icons.TmIcons
import com.tmplayer.ui.theme.Corner
import com.tmplayer.ui.theme.LocalOnFloating
import com.tmplayer.ui.theme.Tone
import com.tmplayer.ui.theme.focusRing

/**
 * Our own "Open with" (PR4): TMPlayer and every installed player, each with its icon and name, and
 * two buttons, Always (remembered as the default player) and Just once. Android's chooser is not
 * used because it cannot remember a pick in our terms and cannot be handed a stream.
 *
 * A line selects (it does not leave): the buttons decide, so a stray press never launches an app.
 * [gone] is the default app that is no longer installed, said above the list. On a phone it is a
 * bottom sheet, on a television the shared picker panel, whose lines and buttons the remote
 * reaches in reading order: the first line on open, Down through the list, then the two buttons.
 */
@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun ExternalChooserSheet(
    apps: List<InstalledPlayers.Found>,
    gone: String?,
    onChoose: (choice: com.tmplayer.player.PlayerChoice, always: Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    val s = LocalStrings.current
    val names = remember(apps) { ExternalPlay.displayNames(apps.map { it.app }) }
    // Line 0 is TMPlayer, then the apps; an app is picked first when there is one, as the
    // viewer opened this to leave.
    var picked by rememberSaveable { mutableIntStateOf(if (apps.isEmpty()) 0 else 1) }
    val index = picked.coerceIn(0, apps.size)
    val choice: PlayerChoice = if (index == 0) PlayerChoice.TmPlayer else apps[index - 1].app.choice
    val first = remember { FocusRequester() }
    val note = gone?.let { s.externalChooserGone(app = it) } ?: s.externalChooserNote

    val body: @Composable ColumnScope.() -> Unit = {
        ChooserLine(
            title = s.externalTmplayer,
            detail = s.externalTmplayerDetail,
            icon = null,
            selected = index == 0,
            modifier = Modifier.focusRequester(first),
        ) { picked = 0 }
        apps.forEachIndexed { i, found ->
            ChooserLine(title = names[i], detail = null, icon = found.icon, selected = index == i + 1) { picked = i + 1 }
        }
    }
    val buttons: @Composable () -> Unit = {
        CompositionLocalProvider(LocalOnFloating provides true) {
            // Whole buttons wrap on a narrow screen or a long translation rather than breaking a word inside one.
            FlowRow(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                TmSecondaryButton(onClick = { onChoose(choice, true) }) { Text(s.externalAlways) }
                TmButton(onClick = { onChoose(choice, false) }) { Text(s.externalJustOnce) }
            }
        }
    }

    if (isTouch()) {
        PhoneSheet(onDismissRequest = onDismiss) {
            val scroll = rememberScrollState()
            Column(Modifier.padding(horizontal = 24.dp).padding(bottom = 24.dp)) {
                Text(s.externalChooserTitle, style = MaterialTheme.typography.titleLarge, color = Tone.text, modifier = Modifier.padding(top = 8.dp))
                Text(note, style = MaterialTheme.typography.bodyMedium, color = Tone.muted, modifier = Modifier.padding(top = 4.dp, bottom = 8.dp))
                Column(Modifier.weight(1f, fill = false).heightIn(max = 360.dp).verticalScroll(scroll), content = body)
                Spacer(Modifier.size(16.dp))
                buttons()
            }
        }
    } else {
        ChoiceSheet(title = s.externalChooserTitle, note = note, onDismiss = onDismiss) {
            ChoiceList(body)
            Spacer(Modifier.size(12.dp))
            buttons()
            FocusOnOpen(first)
        }
    }
}

/**
 * One line of the chooser: an app's icon (or TMPlayer's mark), its name, a radio on a phone and a
 * tick under the remote. At least 48 dp high, the name wraps to two lines before it is cut, and
 * the focused line fills with the focus colour.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun ChooserLine(
    title: String,
    detail: String?,
    icon: Drawable?,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val touch = isTouch()
    val interactions = remember { MutableInteractionSource() }
    val focused by interactions.collectIsFocusedAsState()
    val shape = RoundedCornerShape(Corner.Medium)
    val ink = if (focused) Tone.onFocusFill else Tone.text
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = if (touch) 56.dp else 60.dp)
            .clip(shape)
            .background(if (focused) Tone.focusFill else if (selected) Tone.accent.copy(alpha = 0.14f) else androidx.compose.ui.graphics.Color.Transparent)
            .focusRing(focused, shape)
            .selectable(selected = selected, interactionSource = interactions, indication = null, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AppIcon(icon, selected, ink)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (detail != null) {
                Text(detail, style = MaterialTheme.typography.bodyMedium, color = if (focused) ink else Tone.muted, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        if (selected) {
            androidx.compose.material3.Icon(TmIcons.Check, contentDescription = null, tint = if (focused) ink else Tone.accent, modifier = Modifier.size(22.dp))
        } else {
            Spacer(Modifier.size(22.dp))
        }
    }
}

/** An installed app's icon, 40 dp, or TMPlayer's own play mark when [icon] is null. */
@Composable
private fun AppIcon(icon: Drawable?, selected: Boolean, ink: androidx.compose.ui.graphics.Color) {
    if (icon == null) {
        Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
            androidx.compose.material3.Icon(TmIcons.Play, contentDescription = null, tint = if (selected) Tone.accent else ink, modifier = Modifier.size(28.dp))
        }
        return
    }
    val bitmap = remember(icon) { runCatching { icon.toBitmap(96, 96).asImageBitmap() }.getOrNull() }
    if (bitmap != null) {
        Image(bitmap, contentDescription = null, modifier = Modifier.size(40.dp).clip(RoundedCornerShape(8.dp)))
    } else {
        Spacer(Modifier.size(40.dp))
    }
}
