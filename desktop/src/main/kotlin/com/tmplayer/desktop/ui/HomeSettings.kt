package com.tmplayer.desktop.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.tmplayer.data.HomeLayout
import com.tmplayer.ui.i18n.LocalStrings
import com.tmplayer.ui.icons.TmIcons
import com.tmplayer.ui.theme.Tone
import kotlinx.coroutines.launch

/**
 * Settings, Home, on the desktop, as on the phone and the TV: a switch per row of Home (each on
 * by default), and a note that Recently added and the carousel follow the chats added in Add chats
 * ([com.tmplayer.data.AddedChats]). Home once had its own "Chosen chats" picker here, which repeated
 * Add chats and is gone (2026-11-01).
 */
@Composable
internal fun HomeLayoutRows(state: ShellState) {
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
        s.settingsHomeFollowsAdded,
        style = MaterialTheme.typography.bodySmall,
        color = Tone.muted,
        modifier = Modifier.padding(start = SETTING_INSET, end = SETTING_INSET, top = 4.dp),
    )
}
