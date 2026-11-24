package com.tmplayer.desktop.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.tmplayer.data.MediaItem
import com.tmplayer.data.Series
import com.tmplayer.data.WatchLaterRecord
import com.tmplayer.ui.browse.ListPageScaffold
import com.tmplayer.ui.i18n.LocalStrings
import com.tmplayer.ui.icons.TmIcons
import com.tmplayer.ui.nav.BackHandler

/**
 * The viewer's named lists (HX43) on the desktop, over Home: the shared page ([ListPageScaffold])
 * with Home's own cards in a grid. A card's right click menu is the grid's, with Remove from list
 * at its foot (every line the card stands for); a show's card opens the show's page. Esc, the back
 * button or the mouse's back button returns to Home.
 */
@Composable
internal fun ListsPage(
    state: ShellState,
    art: Map<String, MediaItem>,
    onLaterArtWanted: (WatchLaterRecord) -> Unit,
    onOpenSeries: (Series) -> Unit,
    onBack: () -> Unit,
) {
    val s = LocalStrings.current
    BackHandler(enabled = true) { onBack() }
    val lists by remember { state.watched.lists.visible }.collectAsState(initial = null)
    val side = pageMargin()
    ListPageScaffold(
        lists = lists,
        selectedId = state.openList?.ifEmpty { null },
        onSelect = { state.openList = it },
        store = state.watched.lists,
        onMessage = { state.notices.tryEmit(it) },
        sides = PaddingValues(horizontal = side),
        // Play all and Shuffle (WP-32): the player follows the list's order, or the seeded one, past the first video.
        onPlay = { list, shuffle ->
            val press = com.tmplayer.data.PlayAllLists.request(list.records, shuffle, com.tmplayer.data.PlayAll.newSeed(System.nanoTime()))
            val first = press?.let { p -> list.records.firstOrNull { it.chatId == p.chatId && it.messageId == p.messageId && !it.isShow } }
            if (press != null && first != null) {
                com.tmplayer.data.PlayAll.arm(press)
                state.openPlayer(first.toMediaItem(), startFromBeginning = false)
            }
        },
        modifier = Modifier.fillMaxSize().padding(top = 8.dp).testTag(DESKTOP_LIST_PAGE_TAG),
        top = {
            BarTooltip(s.commonBack, null, below = true) {
                IconButton(onClick = onBack, modifier = Modifier.padding(start = side)) { Icon(TmIcons.ArrowBack, contentDescription = s.commonBack) }
            }
        },
    ) { list, cards, actions ->
        LazyVerticalGrid(
            columns = GridCells.Adaptive(state.posterWidth),
            contentPadding = PaddingValues(start = side, end = side, bottom = 24.dp + com.tmplayer.ui.components.LocalBottomChrome.current),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
            modifier = Modifier.fillMaxWidth().weight(1f),
        ) {
            items(cards, key = { it.key }) { card ->
                Box {
                    LaterCell(
                        state, card.tile, art, onLaterArtWanted, onOpenShow = onOpenSeries,
                        onRemove = { actions.remove(list, card.tile.title, card.records.mapTo(HashSet()) { it.id }) },
                    )
                }
            }
        }
    }
}

const val DESKTOP_LIST_PAGE_TAG = "desktop-list-page"
