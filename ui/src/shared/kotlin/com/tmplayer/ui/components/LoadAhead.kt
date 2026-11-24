package com.tmplayer.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.composed
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.tmplayer.ui.i18n.LocalStrings
import com.tmplayer.ui.theme.Tone
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * Asks for the next page of a list before its end comes into view: [onLoadMore] runs once the
 * last item on screen is within [ahead] items of the end, and again each time [enabled] comes
 * back (a page landed, so the list may still be short of the screen). A list pages with this,
 * passing `enabled = !loading && !done && failure == null`, so one request runs at a time and a
 * failed one waits for its Try again ([PageFoot]). The one end of list trigger for every list and
 * grid on every platform.
 */
@Composable
fun LoadMoreAhead(state: LazyListState, enabled: Boolean, ahead: Int = LIST_AHEAD, onLoadMore: () -> Unit) =
    LoadMoreAhead(state, enabled, count = 0, ahead = ahead, onLoadMore = onLoadMore)

/**
 * [LoadMoreAhead] for a list that holds [count] items, which it asks about only once its layout
 * has them all: a row composed ahead below the screen is not laid out again as pages land, and
 * its last layout kept saying the end was in view, so a Home row on a television read every
 * chat to its start by itself, at a core and a half, while nobody touched it.
 */
@Composable
fun LoadMoreAhead(state: LazyListState, enabled: Boolean, count: Int, ahead: Int = LIST_AHEAD, onLoadMore: () -> Unit) {
    LoadMoreWhen(state, enabled, onLoadMore) {
        val info = state.layoutInfo
        info.totalItemsCount >= count && nearEnd(info.visibleItemsInfo.lastOrNull()?.index, info.totalItemsCount, ahead)
    }
}

/** [LoadMoreAhead] for a grid: [ahead] counts cells, so a few rows' worth is rows times columns. */
@Composable
fun LoadMoreAhead(state: LazyGridState, enabled: Boolean, ahead: Int = GRID_AHEAD, onLoadMore: () -> Unit) {
    LoadMoreWhen(state, enabled, onLoadMore) {
        val info = state.layoutInfo
        nearEnd(info.visibleItemsInfo.lastOrNull()?.index, info.totalItemsCount, ahead)
    }
}

@Composable
private fun LoadMoreWhen(key: Any, enabled: Boolean, onLoadMore: () -> Unit, near: () -> Boolean) {
    val load by rememberUpdatedState(onLoadMore)
    LaunchedEffect(key, enabled) {
        if (!enabled) return@LaunchedEffect
        snapshotFlow(near).distinctUntilChanged().collect { if (it) load() }
    }
}

/**
 * [LoadMoreAhead] for a list that is not lazy (a sheet's column of lines): [onShown] runs when the
 * line this is set on, the list's "More" at its foot, comes into the window's view, and again
 * whenever it comes back after a page landed. The line stays a control of its own, for a remote
 * or a click, as well.
 */
fun Modifier.loadWhenShown(onShown: () -> Unit): Modifier = composed {
    val load by rememberUpdatedState(onShown)
    var inView by remember { mutableStateOf(false) }
    LaunchedEffect(inView) { if (inView) load() }
    onGloballyPositioned { place ->
        val seen = place.isAttached && place.boundsInWindow().let { it.width > 0f && it.height > 0f }
        if (seen != inView) inView = seen
    }
}

/**
 * Whether a list whose last item on screen is [lastVisible] (null with nothing laid out) of
 * [total] is within [ahead] items of its end. An empty list is not: its first page is the
 * screen's own load, not a next page.
 */
fun nearEnd(lastVisible: Int?, total: Int, ahead: Int): Boolean =
    lastVisible != null && total > 0 && lastVisible >= total - 1 - ahead.coerceAtLeast(0)

/**
 * The foot of a list that pages: [skeleton] (a row of placeholder tiles shaped like the list's
 * own) while the next page is read, or why it failed with Try again, which calls [onRetry]. Takes
 * no room otherwise, so the last tiles sit where they did once the list is read to its end.
 */
@Composable
fun PageFoot(
    loading: Boolean,
    failure: String?,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    skeleton: @Composable () -> Unit,
) {
    when {
        failure != null -> Row(
            modifier.fillMaxWidth().padding(vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                failure,
                style = MaterialTheme.typography.bodyMedium,
                color = Tone.muted,
                // Spoken as it appears: a next page that failed is otherwise silent to a screen reader.
                modifier = Modifier.weight(1f, fill = false).liveMessage(),
            )
            OutlinedButton(onClick = onRetry) { Text(LocalStrings.current.commonTryAgain) }
        }
        loading -> skeleton()
    }
}

/** Items ahead of a list's end that its next page is asked for at: a screen of rows or so. */
const val LIST_AHEAD = 8

/** Cells ahead of a grid's end: a few rows of a wide grid. */
const val GRID_AHEAD = 18
