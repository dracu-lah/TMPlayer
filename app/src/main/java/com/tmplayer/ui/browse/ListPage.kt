package com.tmplayer.ui.browse

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.tmplayer.data.ListCard
import com.tmplayer.data.ListsStore
import com.tmplayer.data.MediaItem
import com.tmplayer.data.VisibleList
import com.tmplayer.data.WatchLaterRecord
import com.tmplayer.ui.components.FloatingBackButton
import com.tmplayer.ui.components.dropStaleArrowRepeats
import com.tmplayer.ui.components.pageKeys
import com.tmplayer.ui.components.pageTile
import com.tmplayer.ui.components.MenuAction
import com.tmplayer.ui.components.TvMenu
import com.tmplayer.ui.components.isTouch
import com.tmplayer.ui.i18n.LocalStrings
import com.tmplayer.ui.icons.TmIcons
import com.tmplayer.ui.theme.Tone
import com.tmplayer.ui.theme.Tv

/**
 * The viewer's lists (HX43) on a phone and a television: a page in a window of its own, as a show's
 * page is, so Back closes it and what opens from it (a show's page, a video's page, the player)
 * comes up over it and Back lands here again.
 *
 * Its cards are the grid's own [MediaCard]s; a tile asks for its picture ([onArtWanted]) only once
 * it is drawn, so a list of 5,000 asks Telegram for a screen's worth. The hold (a long press, a
 * held OK) opens the card's menu: Play or Details, and Remove from list, which takes every line
 * the card stands for.
 */
@Composable
internal fun ListPage(
    initial: String?,
    lists: List<VisibleList>?,
    store: ListsStore,
    art: Map<String, MediaItem>,
    onArtWanted: (WatchLaterRecord) -> Unit,
    onOpen: (WatchLaterRecord) -> Unit,
    onDetails: (WatchLaterRecord) -> Unit,
    onMessage: (String) -> Unit,
    onDismiss: () -> Unit,
    /** Play all and Shuffle for the shown list (WP-32): the host arms the run and plays the first video. */
    onPlay: ((VisibleList, Boolean) -> Unit)? = null,
) {
    val touch = isTouch()
    val s = LocalStrings.current
    var selected by rememberSaveable { mutableStateOf(initial?.ifEmpty { null }) }
    var menu by remember { mutableStateOf<Pair<VisibleList, ListCard>?>(null) }
    val actions = rememberListPageActions(store, onMessage)
    val sides = if (touch) PaddingValues(horizontal = 16.dp) else PaddingValues(horizontal = Tv.SafeH)
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Box(Modifier.fillMaxSize().background(Tone.background).dropStaleArrowRepeats().testTag(LIST_PAGE_TAG)) {
            ListPageScaffold(
                lists = lists,
                selectedId = selected,
                onSelect = { selected = it },
                store = store,
                onMessage = onMessage,
                sides = sides,
                onPlay = onPlay,
                modifier = Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Top))
                    .padding(top = if (touch) 64.dp else Tv.SafeV),
            ) { list, cards, _ ->
                val gridState = androidx.compose.foundation.lazy.grid.rememberLazyGridState()
                // The cards a Page or Channel key can land on (WP-12), by their lazy item key.
                val pageTiles = com.tmplayer.ui.components.rememberPageTiles()
                LazyVerticalGrid(
                    state = gridState,
                    columns = GridCells.Adaptive(if (touch) 160.dp else 240.dp),
                    contentPadding = PaddingValues(
                        start = sides.calculateLeftPadding(androidx.compose.ui.unit.LayoutDirection.Ltr),
                        end = sides.calculateRightPadding(androidx.compose.ui.unit.LayoutDirection.Ltr),
                        bottom = if (touch) 24.dp else Tv.SafeV,
                    ),
                    horizontalArrangement = Arrangement.spacedBy(if (touch) 10.dp else 20.dp),
                    verticalArrangement = Arrangement.spacedBy(if (touch) 14.dp else 20.dp),
                    modifier = Modifier.fillMaxWidth().weight(1f).dropStaleArrowRepeats().pageKeys(gridState, pageTiles).testTag(LIST_GRID_TAG),
                ) {
                    items(cards, key = { it.key }) { card ->
                        val tile = card.tile
                        LaunchedEffect(tile.key) { onArtWanted(tile) }
                        val item = art[tile.key] ?: remember(tile) { tile.toMediaItem() }
                        MediaCard(
                            item = item,
                            watched = null,
                            // A show is named as the show, its episode's picture on it.
                            title = tile.title.takeIf { tile.isShow },
                            onClick = { onOpen(tile) },
                            onLongClick = { menu = list to card },
                            dense = touch,
                            modifier = Modifier.pageTile(pageTiles, card.key),
                        )
                    }
                }
            }
            if (touch) FloatingBackButton(icon = TmIcons.ArrowBack, label = s.commonBack, onClick = onDismiss)
        }
    }
    menu?.let { (list, card) ->
        val tile = card.tile
        TvMenu(
            title = tile.title,
            subtitle = list.info.name,
            onDismiss = { menu = null },
            actions = buildList {
                add(MenuAction(if (tile.isShow) s.detailOpenDetails else s.commonPlay, if (tile.isShow) TmIcons.Info else TmIcons.Play) { menu = null; onOpen(tile) })
                if (!tile.isShow) add(MenuAction(s.detailOpenDetails, TmIcons.Info) { menu = null; onDetails(tile) })
                add(
                    MenuAction(s.listsRemove, TmIcons.Close, destructive = true) {
                        menu = null
                        actions.remove(list, tile.title, card.records.mapTo(HashSet()) { it.id })
                    },
                )
            },
        )
    }
}

const val LIST_PAGE_TAG = "list-page"
const val LIST_GRID_TAG = "list-grid"
