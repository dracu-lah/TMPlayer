package com.tmplayer.desktop.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.tmplayer.data.HomeLayout
import com.tmplayer.data.HomeRows
import com.tmplayer.ui.i18n.LocalStrings
import com.tmplayer.ui.icons.TmIcons
import com.tmplayer.ui.settings.HomeOrderSection
import com.tmplayer.ui.settings.rememberHomeOrderRows
import com.tmplayer.ui.settings.seekable
import com.tmplayer.ui.theme.Tone
import kotlinx.coroutines.launch

/**
 * Settings, Home, on the desktop, as on the phone and the TV: a switch per row of Home (each on
 * by default), and a note that Recently added and the carousel follow the chats added in Add chats
 * ([com.tmplayer.data.AddedChats]). Home once had its own "Chosen chats" picker here, which repeated
 * Add chats and is gone (2026-11-01).
 */
@Composable
internal fun HomeLayoutRows(state: ShellState, chatList: List<com.tmplayer.data.ChatSummary> = emptyList()) {
    val s = LocalStrings.current
    val settings = state.settings
    val scope = rememberCoroutineScope()
    val layout by settings.homeLayout.collectAsState(initial = HomeLayout())
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
    Text(
        s.settingsHomeFollowsAddedChosen,
        style = MaterialTheme.typography.bodySmall,
        color = Tone.muted,
        modifier = Modifier.padding(start = SETTING_INSET, end = SETTING_INSET, top = 4.dp),
    )

    // The order Home draws its rows in: Move up and down, or drag a row by its handle (EG-F7, EG-F23).
    Group(s.settingsHomeOrder)
    val favourites by settings.favorites.collectAsState(initial = emptySet())
    // The live chat list is what the kids lock already filters, so its stars are the ones Home shows.
    val starred = remember(chatList, favourites) { HomeRows.favouriteChats(chatList, favourites) }
    // The named lists with a row on Home (HX43) move like any row.
    val namedLists by remember { state.watched.lists.visible }.collectAsState(initial = emptyList())
    HomeOrderSection(
        rows = rememberHomeOrderRows(layout, starred, remember(namedLists) { com.tmplayer.ui.settings.homeListInfos(namedLists) }),
        hasOrder = layout.order.isNotEmpty(),
        onOrder = { next -> scope.launch { settings.setHomeOrder(next) } },
        onReset = { scope.launch { settings.setHomeOrder(emptyList()) } },
        remote = false,
        modifier = Modifier.padding(horizontal = SETTING_INSET).seekable(s.settingsHomeOrder),
    )
}
