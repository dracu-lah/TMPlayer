package com.tmplayer.desktop.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import com.tmplayer.data.ChatSummary
import com.tmplayer.data.HomeLayout
import com.tmplayer.data.RecentSource
import com.tmplayer.data.SettingsStore
import com.tmplayer.ui.components.TmAlertDialog
import com.tmplayer.ui.i18n.LocalStrings
import com.tmplayer.ui.icons.TmIcons
import com.tmplayer.ui.theme.Tone
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Settings, Home, on the desktop, as on the phone and the TV: a switch per row of Home (each on
 * by default), then where Recently added and the carousel take their videos from, Automatic or
 * the chats chosen in [HomeChatsDialog].
 */
@Composable
internal fun HomeLayoutRows(state: ShellState, chatList: List<ChatSummary>) {
    val s = LocalStrings.current
    val settings = state.settings
    val scope = rememberCoroutineScope()
    val layout by settings.homeLayout.collectAsState(initial = HomeLayout())
    var picking by remember { mutableStateOf(false) }
    val metaOn = com.tmplayer.online.OnlineMetadata.current?.settings?.collectAsState()?.value?.enabled == true

    Group(s.settingsHomeRows)
    Toggle(
        s.settingsHomeCarousel,
        when {
            !layout.carousel -> s.settingsHomeCarouselOff
            // Each page needs its online picture and overview: none while lookups are off.
            !metaOn -> s.settingsHomeCarouselNoOnline
            else -> s.settingsHomeCarouselOn
        },
        layout.carousel,
        icon = TmIcons.Home,
    ) { scope.launch { settings.setHomeCarousel(it) } }
    Toggle(
        s.settingsHomeContinue,
        if (layout.continueWatching) s.settingsHomeContinueOn else s.settingsHomeContinueOff,
        layout.continueWatching,
        icon = TmIcons.History,
    ) { scope.launch { settings.setHomeContinue(it) } }
    Toggle(
        s.settingsHomeRecent,
        if (layout.recentlyAdded) s.settingsHomeRecentOn else s.settingsHomeRecentOff,
        layout.recentlyAdded,
        icon = TmIcons.Grid,
    ) { scope.launch { settings.setHomeRecent(it) } }
    Toggle(
        s.settingsHomeStarredRows,
        if (layout.starredRows) s.settingsHomeStarredRowsOn else s.settingsHomeStarredRowsOff,
        layout.starredRows,
        icon = TmIcons.StarOutline,
    ) { scope.launch { settings.setHomeStarredRows(it) } }

    Group(s.settingsHomeRecentFrom)
    Setting(
        s.settingsHomeRecentFrom,
        if (layout.chosen) s.settingsHomeRecentFromNote else s.settingsHomeSourceAutoDetail,
        icon = TmIcons.List,
    ) {
        Segments(RecentSource.entries, layout.recentSource, { if (it == RecentSource.Chosen) s.settingsHomeSourceChosen else s.settingsHomeSourceAuto }) { source ->
            scope.launch { settings.setHomeRecentSource(source) }
            // Chosen with nothing chosen yet: the picker comes up at once.
            if (source == RecentSource.Chosen && layout.recentChats.isEmpty()) picking = true
        }
    }
    if (layout.chosen) {
        // Only the chosen chats still in the list count: one left or deleted is skipped on Home too.
        val count = if (chatList.isEmpty()) layout.recentChats.size else chatList.count { it.id in layout.recentChats }
        Action(s.settingsHomeChooseChats, s.settingsHomeSourceChosenDetail(count = count), TmIcons.Check) { picking = true }
    }

    if (picking) {
        HomeChatsDialog(chatList, layout.recentChats, settings, onClose = { picking = false })
    }
}

/**
 * The chats Recently added reads when it reads chosen ones: a box per chat, a search over their
 * names, Select all (of what the search shows, also Ctrl+A in the list) and Clear, and how many
 * are ticked. Saved as each changes. Lazy, for an account of thousands of chats.
 */
@Composable
private fun HomeChatsDialog(chatList: List<ChatSummary>, chosen: Set<Long>, settings: SettingsStore, onClose: () -> Unit) {
    val s = LocalStrings.current
    val scope = rememberCoroutineScope()
    // The live list, or while it is still on its way the last sync's.
    val every by produceState(initialValue = chatList, chatList) {
        if (chatList.isNotEmpty()) return@produceState
        value = withContext(Dispatchers.IO) { runCatching { settings.cachedChatSnapshot() }.getOrNull().orEmpty() }.distinctBy { it.id }
    }
    var query by remember { mutableStateOf("") }
    val shown = remember(every, query) {
        val q = query.trim()
        if (q.isEmpty()) every else every.filter { it.title.contains(q, ignoreCase = true) }
    }
    val listedIds = remember(every) { every.mapTo(HashSet()) { it.id } }
    val count = remember(chosen, listedIds) { if (listedIds.isEmpty()) chosen.size else chosen.count { it in listedIds } }
    val allShownTicked = shown.isNotEmpty() && shown.all { it.id in chosen }
    fun save(next: Set<Long>) {
        scope.launch { settings.setHomeRecentChats(next) }
    }
    val selectAll = { save(chosen + shown.map { it.id }) }
    TmAlertDialog(
        onDismissRequest = onClose,
        title = { Text(s.settingsHomeChatsTitle) },
        text = {
            Column(
                Modifier.widthIn(max = 520.dp).onKeyEvent { event ->
                    // Ctrl+A (Cmd+A on a Mac) in the list ticks what is shown; in the search box
                    // the box takes it first, for its own text.
                    if (event.type == KeyEventType.KeyDown && event.key == Key.A && (event.isCtrlPressed || event.isMetaPressed)) {
                        selectAll()
                        true
                    } else {
                        false
                    }
                },
            ) {
                Text(s.settingsHomeChatsNote, style = MaterialTheme.typography.bodyMedium, color = Tone.muted)
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    placeholder = { Text(s.commonSearch) },
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                )
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
                    TextButton(onClick = selectAll, enabled = shown.isNotEmpty() && !allShownTicked) { Text(s.browseSelectAll) }
                    TextButton(
                        onClick = { save(if (query.isBlank()) emptySet() else chosen - shown.mapTo(HashSet()) { it.id }) },
                        enabled = shown.any { it.id in chosen } || (query.isBlank() && chosen.isNotEmpty()),
                    ) { Text(s.commonClear) }
                }
                if (shown.isEmpty()) {
                    Text(
                        s.settingsHomeChatsNoMatch,
                        style = MaterialTheme.typography.bodyMedium,
                        color = Tone.muted,
                        modifier = Modifier.padding(vertical = 16.dp),
                    )
                } else {
                    LazyColumn(Modifier.padding(top = 4.dp).heightIn(max = 420.dp)) {
                        items(shown, key = { it.id }) { chat ->
                            val ticked = chat.id in chosen
                            Row(
                                Modifier.fillMaxWidth().clickable { save(if (ticked) chosen - chat.id else chosen + chat.id) },
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Checkbox(checked = ticked, onCheckedChange = null)
                                Text(chat.title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(start = 8.dp))
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text(s.commonClose) } },
    )
}
