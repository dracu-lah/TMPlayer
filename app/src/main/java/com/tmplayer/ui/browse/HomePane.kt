package com.tmplayer.ui.browse

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.material3.MaterialTheme as M3MaterialTheme
import androidx.compose.material3.Text as M3Text
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.tmplayer.data.HomeRow
import com.tmplayer.data.MediaItem
import com.tmplayer.data.ResumeRecord
import com.tmplayer.data.Series
import com.tmplayer.data.SettingsStore
import com.tmplayer.data.ShelfEntry
import com.tmplayer.data.WatchPoint
import com.tmplayer.ui.components.BigEmpty
import com.tmplayer.ui.components.isTouch
import com.tmplayer.ui.components.pressable
import com.tmplayer.ui.i18n.LocalStrings
import com.tmplayer.ui.theme.Corner
import com.tmplayer.ui.theme.Focus
import com.tmplayer.ui.theme.Tone
import com.tmplayer.ui.theme.Tv
import com.tmplayer.ui.theme.focusScale

/**
 * Home: a row of what the viewer was part way through, a row per starred chat, and the newest
 * videos everywhere else (the rows are built by [com.tmplayer.data.HomeRows]).
 *
 * One vertical lazy list of horizontal lazy rows, so only the rows on screen exist and only the
 * tiles on screen in each of those. Every row and tile is keyed, and every tile is a fixed width,
 * which is what lets a 1 GB television scroll this without measuring anything twice.
 *
 * On a television the D-pad moves along a row and between rows, each row remembering the tile it
 * was left on; the first tile takes focus when Home opens. A row of a starred chat or of Continue
 * watching ends in a "See all" tile; on a phone that is a link beside the row's name instead.
 */
@Composable
internal fun HomePane(
    rows: List<HomeRow>,
    watch: SeriesWatch,
    /** Continue watching videos as Telegram describes them, for their pictures. */
    art: Map<String, MediaItem>,
    chatTitle: (Long) -> String,
    start: Dp,
    end: Dp,
    bottom: Dp,
    onRowShown: (HomeRow) -> Unit,
    onArtWanted: (ResumeRecord) -> Unit,
    onResume: (ResumeRecord) -> Unit,
    onHoldRecord: (ResumeRecord) -> Unit,
    onPlay: (MediaItem, String) -> Unit,
    /** A long press, a held OK or the info key on a video's tile: its detail panel. */
    onHoldMedia: (MediaItem, String) -> Unit,
    onSeeContinue: () -> Unit,
    onOpenChat: (Long) -> Unit,
) {
    val s = LocalStrings.current
    if (rows.isEmpty()) {
        BigEmpty(s.homeEmpty, icon = BrowseTab.Home.icon)
        return
    }
    val touch = isTouch()
    val first = remember { FocusRequester() }
    var openSeries by remember { mutableStateOf<Series?>(null) }
    val listState = rememberLazyListState()

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = if (touch) 4.dp else Tv.FocusClearance, bottom = bottom),
        verticalArrangement = Arrangement.spacedBy(if (touch) 16.dp else 20.dp),
    ) {
        items(rows, key = { it.key }, contentType = { it::class }) { row ->
            val loaded = when (row) {
                is HomeRow.Continue -> true
                is HomeRow.Chat -> row.loaded
                is HomeRow.Recent -> row.loaded
            }
            // Asked for when the row first comes on screen, and again after a refresh empties it.
            LaunchedEffect(row.key, loaded) { if (!loaded) onRowShown(row) }
            val isFirst = row === rows.first()
            val title = when (row) {
                is HomeRow.Continue -> s.homeRowContinue
                is HomeRow.Chat -> row.title
                is HomeRow.Recent -> s.homeRowRecent
            }
            val seeAll: (() -> Unit)? = when (row) {
                is HomeRow.Continue -> onSeeContinue
                is HomeRow.Chat -> { { onOpenChat(row.chatId) } }
                is HomeRow.Recent -> null
            }
            Column {
                RowHeading(title, start = start, end = end, seeAll = seeAll.takeIf { touch })
                if (!loaded) {
                    PlaceholderRow(start)
                    return@Column
                }
                TileRow(start = start, end = end) {
                    when (row) {
                        is HomeRow.Continue -> items(row.records, key = { "r-${it.chatId}_${it.messageId}" }) { record ->
                            val key = SettingsStore.progressKey(record.chatId, record.messageId)
                            LaunchedEffect(key) { onArtWanted(record) }
                            val item = art[key] ?: remember(record) { record.toMediaItem() }
                            MediaCard(
                                item = item,
                                watched = WatchPoint(record.positionMs, record.durationMs),
                                onClick = { onResume(record) },
                                onFocused = {},
                                onLongClick = { onHoldRecord(record) },
                                dense = touch,
                                modifier = Modifier.width(tileWidth(touch))
                                    .then(if (isFirst && record === row.records.first()) Modifier.focusRequester(first) else Modifier),
                            )
                        }
                        is HomeRow.Chat -> entries(row.entries, watch, touch, isFirst, first, { row.title }, onPlay, onHoldMedia) { openSeries = it }
                        is HomeRow.Recent -> entries(row.entries, watch, touch, isFirst, first, chatTitle, onPlay, onHoldMedia) { openSeries = it }
                    }
                    if (!touch && seeAll != null) {
                        item(key = "see-all", contentType = "see-all") { SeeAllTile(onClick = seeAll) }
                    }
                }
            }
        }
    }

    // Somewhere for the remote to stand the moment Home opens. Keyed on the first row, so a row
    // arriving above (Continue appearing after a first play) moves focus to the new top.
    val firstKey = rows.first().key
    val firstReady = when (val top = rows.first()) {
        is HomeRow.Chat -> top.loaded
        is HomeRow.Recent -> top.loaded
        is HomeRow.Continue -> true
    }
    LaunchedEffect(firstKey, firstReady) {
        if (!touch && firstReady) runCatching { first.requestFocus() }
    }

    openSeries?.let { series ->
        SeriesOpened(
            series = series,
            watch = watch,
            onPlay = { onPlay(it, chatTitle(it.chatId)) },
            onDismiss = { openSeries = null },
            onLongClick = { onHoldMedia(it, chatTitle(it.chatId)) },
        )
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.entries(
    entries: List<ShelfEntry>,
    watch: SeriesWatch,
    touch: Boolean,
    isFirstRow: Boolean,
    first: FocusRequester,
    chatTitle: (Long) -> String,
    onPlay: (MediaItem, String) -> Unit,
    onHold: (MediaItem, String) -> Unit,
    onOpenSeries: (Series) -> Unit,
) {
    items(entries, key = { it.key }, contentType = { it::class }) { entry ->
        val focus = if (isFirstRow && entry === entries.first()) Modifier.focusRequester(first) else Modifier
        val modifier = Modifier.width(tileWidth(touch)).then(focus)
        when (entry) {
            is ShelfEntry.File -> MediaCard(
                item = entry.item,
                watched = watch.point(entry.item),
                finished = watch.finished(entry.item),
                onClick = { onPlay(entry.item, chatTitle(entry.item.chatId)) },
                onLongClick = { onHold(entry.item, chatTitle(entry.item.chatId)) },
                onFocused = {},
                dense = touch,
                modifier = modifier,
            )
            is ShelfEntry.Show -> SeriesCard(
                series = entry.series,
                progress = remember(entry.series, watch) { watch.progress(entry.series) },
                onClick = { onOpenSeries(entry.series) },
                dense = touch,
                modifier = modifier,
            )
        }
    }
}

/** One horizontal lazy row, padded to line up with the heading, remembering its tile for the D-pad. */
@Composable
private fun TileRow(
    start: Dp,
    end: Dp,
    content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit,
) {
    val touch = isTouch()
    LazyRow(
        // Coming back down into a row lands on the tile it was left on, not whichever is nearest.
        modifier = Modifier.fillMaxWidth().then(if (touch) Modifier else Modifier.focusRestorer()),
        contentPadding = PaddingValues(
            start = start,
            end = end,
            // Room for a focused tile to grow into without being clipped by the row.
            top = if (touch) 0.dp else Tv.FocusClearance,
            bottom = if (touch) 0.dp else Tv.FocusClearance,
        ),
        horizontalArrangement = Arrangement.spacedBy(if (touch) 8.dp else 16.dp),
        content = content,
    )
}

@Composable
private fun RowHeading(title: String, start: Dp, end: Dp, seeAll: (() -> Unit)?) {
    val s = LocalStrings.current
    val touch = isTouch()
    Row(
        Modifier.fillMaxWidth().padding(start = start, end = if (seeAll != null) end - 8.dp else end, bottom = if (touch) 4.dp else 0.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (touch) {
            M3Text(
                title,
                style = M3MaterialTheme.typography.titleMedium,
                color = Tone.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        } else {
            Text(
                title,
                style = MaterialTheme.typography.titleLarge,
                color = Tone.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        }
        if (seeAll != null) {
            TextButton(onClick = seeAll) { M3Text(s.homeSeeAll, color = Tone.accent) }
        }
    }
}

/** The end of a row on a television: the rest of that chat, or all of Continue watching. */
@Composable
private fun SeeAllTile(onClick: () -> Unit) {
    val s = LocalStrings.current
    val interactions = remember { MutableInteractionSource() }
    val focused by interactions.collectIsFocusedAsState()
    val border by animateColorAsState(
        targetValue = if (focused) Tone.accent else Color.Transparent,
        animationSpec = tween(140),
        label = "seeAllBorder",
    )
    Column(
        Modifier
            .width(SEE_ALL_WIDTH)
            .aspectRatio(SEE_ALL_WIDTH / (tileWidth(false) * 9f / 16f))
            .focusScale(focused)
            .clip(RoundedCornerShape(Corner.Medium))
            .background(if (focused) Tone.surfaceHigh else Tone.surface)
            .border(1.dp, Tone.outline, RoundedCornerShape(Corner.Medium))
            .border(Focus.Edge, border, RoundedCornerShape(Corner.Medium))
            .pressable(interactions, onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null, tint = Tone.accent, modifier = Modifier.size(28.dp))
        Spacer(Modifier.height(8.dp))
        Text(s.homeSeeAll, style = MaterialTheme.typography.titleMedium, color = Tone.text)
    }
}

/** A row still asking Telegram: blank tiles the size of the real ones, so nothing jumps when it fills. */
@Composable
private fun PlaceholderRow(start: Dp) {
    val touch = isTouch()
    val width = tileWidth(touch)
    Row(
        Modifier.fillMaxWidth().padding(start = start, top = if (touch) 0.dp else Tv.FocusClearance),
        horizontalArrangement = Arrangement.spacedBy(if (touch) 8.dp else 16.dp),
    ) {
        repeat(PLACEHOLDER_TILES) {
            Column(Modifier.width(width)) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .aspectRatio(16f / 9f)
                        .clip(RoundedCornerShape(Corner.Small))
                        .background(Tone.surface),
                )
                Spacer(Modifier.height(if (touch) 40.dp else 72.dp))
            }
        }
    }
}

/**
 * A tile's width. A phone shows two and a half across upright, which says "there is more this
 * way" without a word; a television shows three and a bit beside the rail.
 */
private fun tileWidth(touch: Boolean): Dp = if (touch) 152.dp else 208.dp

private val SEE_ALL_WIDTH = 132.dp
private const val PLACEHOLDER_TILES = 5
