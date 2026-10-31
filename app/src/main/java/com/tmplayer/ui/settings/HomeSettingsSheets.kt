package com.tmplayer.ui.settings

import com.tmplayer.ui.theme.collectIsFocusVisibleAsState
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.tmplayer.data.ChatBrief
import com.tmplayer.data.ChatSummary
import com.tmplayer.data.RecentSource
import com.tmplayer.data.SettingsStore
import com.tmplayer.ui.browse.ChatAvatar
import com.tmplayer.ui.components.ChoiceLine
import com.tmplayer.ui.components.ChoiceList
import com.tmplayer.ui.components.ChoiceSheet
import com.tmplayer.ui.components.FocusOnOpen
import com.tmplayer.ui.components.TvSearchField
import com.tmplayer.ui.components.isTouch
import com.tmplayer.ui.i18n.LocalStrings
import com.tmplayer.ui.icons.TmIcons
import com.tmplayer.ui.theme.Corner
import com.tmplayer.ui.theme.Tone
import com.tmplayer.ui.theme.focusRing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Small print under a settings group, in the muted colour, lined up with the cards' rows. */
@Composable
internal fun SettingsNoteText(text: String) {
    val touch = isTouch()
    Text(
        text,
        style = if (touch) MaterialTheme.typography.bodySmall else MaterialTheme.typography.bodyMedium,
        color = Tone.muted,
        modifier = Modifier.padding(start = if (touch) 16.dp else 4.dp, end = 16.dp, top = 6.dp, bottom = 4.dp),
    )
}

/** Where Recently added reads from: Automatic (Home's own chats) or Chosen chats. */
@Composable
internal fun HomeSourceSheet(chosen: Boolean, onPick: (RecentSource) -> Unit, onClose: () -> Unit) {
    val s = LocalStrings.current
    val touch = isTouch()
    val first = remember { FocusRequester() }
    ChoiceSheet(title = s.settingsHomeRecentFrom, note = s.settingsHomeRecentFromNote, onDismiss = onClose, onClose = onClose) {
        ChoiceList {
            ChoiceLine(
                title = s.settingsHomeSourceAuto,
                detail = s.settingsHomeSourceAutoDetail,
                selected = !chosen,
                icon = if (!touch && !chosen) TmIcons.Check else null,
                modifier = if (!chosen) Modifier.focusRequester(first) else Modifier,
            ) { onPick(RecentSource.Auto) }
            ChoiceLine(
                title = s.settingsHomeSourceChosen,
                selected = chosen,
                icon = if (!touch && chosen) TmIcons.Check else null,
                modifier = if (chosen) Modifier.focusRequester(first) else Modifier,
            ) { onPick(RecentSource.Chosen) }
        }
        FocusOnOpen(first)
    }
}

/**
 * The chats Recently added reads when it reads chosen ones: every chat with a tick, a search over
 * their names, Select all (of what the search shows) and Clear, and how many are ticked. Each
 * change is saved as it is made, as the kids lock's picker does.
 *
 * Lazy, for an account of thousands of chats. The list is the browse screen's, with the last
 * sync's added while that one is still on its way.
 */
@Composable
internal fun HomeChatsSheet(
    chats: List<ChatSummary>,
    chosen: Set<Long>,
    onChange: (Set<Long>) -> Unit,
    onClose: () -> Unit,
) {
    val s = LocalStrings.current
    val context = LocalContext.current
    val touch = isTouch()
    val every by produceState(initialValue = chats, chats) {
        if (chats.isNotEmpty()) return@produceState
        val remembered = withContext(Dispatchers.IO) { runCatching { SettingsStore(context).cachedChatSnapshot() }.getOrNull().orEmpty() }
        value = remembered.distinctBy { it.id }
    }
    var query by rememberSaveable { mutableStateOf("") }
    val shown = remember(every, query) {
        val q = query.trim()
        if (q.isEmpty()) every else every.filter { it.title.contains(q, ignoreCase = true) }
    }
    // Ticked chats the list has: one left since it was ticked is not counted, as Home skips it.
    val listedIds = remember(every) { every.mapTo(HashSet()) { it.id } }
    val count = remember(chosen, listedIds) { if (listedIds.isEmpty()) chosen.size else chosen.count { it in listedIds } }
    val allShownTicked = shown.isNotEmpty() && shown.all { it.id in chosen }
    val first = remember { FocusRequester() }
    ChoiceSheet(title = s.settingsHomeChatsTitle, note = s.settingsHomeChatsNote, onDismiss = onClose, onClose = onClose) {
        if (touch) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                singleLine = true,
                placeholder = { Text(s.commonSearch) },
                modifier = Modifier.fillMaxWidth().focusRequester(first),
            )
        } else {
            TvSearchField(
                value = query,
                onValueChange = { query = it },
                placeholder = s.commonSearch,
                modifier = Modifier.fillMaxWidth().focusRequester(first),
            )
        }
        Row(
            Modifier.fillMaxWidth().padding(top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                s.browseSelectedCount(count = count),
                style = MaterialTheme.typography.bodyMedium,
                color = Tone.muted,
                modifier = Modifier.weight(1f),
            )
            // Select all ticks what the search shows; Clear unticks the same, or everything
            // when nothing is searched for.
            TextButton(onClick = { onChange(chosen + shown.map { it.id }) }, enabled = shown.isNotEmpty() && !allShownTicked) {
                Text(s.browseSelectAll)
            }
            TextButton(
                onClick = { onChange(if (query.isBlank()) emptySet() else chosen - shown.mapTo(HashSet()) { it.id }) },
                enabled = shown.any { it.id in chosen } || (query.isBlank() && chosen.isNotEmpty()),
            ) {
                Text(s.commonClear)
            }
        }
        if (shown.isEmpty()) {
            Text(
                s.settingsHomeChatsNoMatch,
                style = MaterialTheme.typography.bodyMedium,
                color = Tone.muted,
                modifier = Modifier.padding(vertical = 16.dp),
            )
        } else {
            ChatLines(shown, chosen, touch) { id -> onChange(if (id in chosen) chosen - id else chosen + id) }
        }
        FocusOnOpen(first)
    }
}

@Composable
private fun ColumnScope.ChatLines(shown: List<ChatSummary>, chosen: Set<Long>, touch: Boolean, onToggle: (Long) -> Unit) {
    LazyColumn(
        Modifier.weight(1f, fill = false).padding(top = 4.dp),
        verticalArrangement = Arrangement.spacedBy(if (touch) 0.dp else 6.dp),
    ) {
        items(shown, key = { it.id }) { chat ->
            val ticked = chat.id in chosen
            // The same row the "Add chats" picker draws (HX27): a real photo or the chat's own
            // colour initials, never a bare row of names, so this reads as the same feature rather
            // than a second, plainer one beside it.
            ChatTickRow(chat = chat, ticked = ticked, touch = touch, onToggle = { onToggle(chat.id) })
        }
    }
}

/**
 * One chat with its avatar, for a sheet that ticks chats rather than adding them: [HomeChatsSheet]
 * here, and the kids lock's own picker. A tick on a remote, as every multi-select list in the app
 * draws one; a phone gets a checkbox, since a radio there would read as "one of these" rather than
 * "some of these".
 */
@Composable
internal fun ChatTickRow(chat: ChatSummary, ticked: Boolean, touch: Boolean, onToggle: () -> Unit) {
    val interactions = remember { MutableInteractionSource() }
    val focused by interactions.collectIsFocusVisibleAsState()
    val shape = RoundedCornerShape(Corner.Medium)
    val ink = if (focused) Tone.onFocusFill else Tone.text
    val brief = remember(chat) { ChatBrief.of(chat) }
    Row(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(if (focused) Tone.focusFill else Color.Transparent)
            .focusRing(focused, shape)
            .semantics(mergeDescendants = true) { toggleableState = ToggleableState(ticked) }
            .combinedClickable(
                interactionSource = interactions,
                indication = if (touch) LocalIndication.current else null,
                role = Role.Checkbox,
                onClick = onToggle,
            )
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ChatAvatar(brief, if (touch) 40.dp else 48.dp)
        Text(
            chat.title,
            style = MaterialTheme.typography.titleSmall,
            color = ink,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (touch) {
            Checkbox(checked = ticked, onCheckedChange = null)
        } else if (ticked) {
            Icon(
                TmIcons.Check,
                contentDescription = null,
                tint = if (focused) Tone.onFocusFill else Tone.accent,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}
