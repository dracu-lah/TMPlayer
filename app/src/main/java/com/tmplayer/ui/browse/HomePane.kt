package com.tmplayer.ui.browse

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import com.tmplayer.ui.components.TmIcons
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
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
import com.tmplayer.ui.components.TmSecondaryButton
import com.tmplayer.ui.components.isTouch
import com.tmplayer.ui.i18n.LocalStrings
import com.tmplayer.ui.theme.Corner
import com.tmplayer.ui.theme.Tone
import com.tmplayer.ui.theme.Tv
import kotlinx.coroutines.launch

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
 * watching that leaves videos out ends in a "See all" tile; on a phone that is a link beside the
 * row's name instead.
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
    onPlay: (MediaItem, String) -> Unit,
    /** A long press, a held OK or the info key on a video's tile: its detail panel. */
    onHoldMedia: (MediaItem, String) -> Unit,
    onSeeContinue: () -> Unit,
    onOpenChat: (Long) -> Unit,
    /**
     * True when no chat is starred, so Home has no chat rows to show and says how to get them.
     * Not needed when [rows] is empty: the empty state already says the same.
     */
    noFavourites: Boolean = false,
    /** What the first-run state says and where its button goes: Saved Messages, or a folder. */
    firstStep: DefaultGroups.FirstStep = DefaultGroups.firstStep(hidden = false, folders = emptyList()),
    /** Opens the first-run state's target. */
    onOpenSection: (BrowseSection) -> Unit = {},
) {
    val s = LocalStrings.current
    if (rows.isEmpty()) {
        // Nothing played and nothing starred: a first run, so say how to get a first video in.
        FirstVideoEmpty(firstStep, onOpenSection)
        return
    }
    val touch = isTouch()
    val first = remember { FocusRequester() }
    val scope = rememberCoroutineScope()
    var openSeries by remember { mutableStateOf<Series?>(null) }
    val listState = rememberLazyListState()

    // Tiles here may wear the show's or the film's picture (see LocalOnlineArt); a chat's grid keeps frames.
    androidx.compose.runtime.CompositionLocalProvider(com.tmplayer.ui.online.LocalOnlineArt provides true) {
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
                // Only a row that leaves something out offers the rest of it.
                val seeAll: (() -> Unit)? = when (row) {
                    is HomeRow.Continue -> onSeeContinue
                    is HomeRow.Chat -> { { onOpenChat(row.chatId) } }
                    is HomeRow.Recent -> null
                }?.takeIf { row.hasMore }
                val countLabel = if (row.totalAtLeast) s.homeSeeAllCountAtLeast(row.total) else s.homeSeeAllCount(row.total)
                // On a television "See all" sits on the heading, where it can be seen, but it only
                // takes focus when Up is pressed from this row's tiles: moving down through Home
                // goes tile row to tile row and never stops on a heading on the way.
                val seeAllFocus = remember { FocusRequester() }
                var seeAllArmed by remember { mutableStateOf(false) }
                Column {
                    RowHeading(
                        title,
                        start = start,
                        end = end,
                        seeAll = seeAll,
                        count = countLabel,
                        focus = seeAllFocus,
                        armed = seeAllArmed,
                        onLeft = { seeAllArmed = false },
                    )
                    if (!loaded) {
                        PlaceholderRow(start)
                        return@Column
                    }
                    TileRow(
                        start = start,
                        end = end,
                        modifier = if (touch || seeAll == null) Modifier else Modifier.onPreviewKeyEvent { event ->
                            if (event.type != KeyEventType.KeyDown || event.key != Key.DirectionUp) return@onPreviewKeyEvent false
                            seeAllArmed = true
                            scope.launch {
                                // A frame for the armed heading to become focusable.
                                withFrameNanos {}
                                runCatching { seeAllFocus.requestFocus() }
                            }
                            true
                        },
                    ) {
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
                                    // The detail panel, as on every other tile here: it already
                                    // offers Resume, Start over and Mark watched for a video with
                                    // a saved place, and the info key opening a short menu on this
                                    // row alone read as a different app.
                                    onLongClick = { onHoldMedia(item, record.chatTitle.ifEmpty { chatTitle(record.chatId) }) },
                                    dense = touch,
                                    // A lone card takes more of the row, so it does not sit in a third of the
                                    // width with the rest empty.
                                    modifier = Modifier.width(tileWidth(touch) * if (row.records.size == 1 && !row.hasMore) 1.5f else 1f)
                                        .then(if (isFirst && record === row.records.first()) Modifier.focusRequester(first) else Modifier),
                                )
                            }
                            is HomeRow.Chat -> entries(row.entries, watch, touch, isFirst, first, { row.title }, onPlay, onHoldMedia) { openSeries = it }
                            is HomeRow.Recent -> entries(row.entries, watch, touch, isFirst, first, chatTitle, onPlay, onHoldMedia) { openSeries = it }
                        }
                    }
                }
            }
            // Where the starred chats' rows would be, a line saying how to get them; otherwise a
            // Home of Continue watching alone gives no clue that it could hold more. Plain text,
            // never focusable: the remote has nothing to do here, and the way to star a chat is
            // in the chat list, which the hint names.
            if (noFavourites) {
                item(key = "favourites-hint", contentType = "favourites-hint") {
                    FavouritesHint(start = start, end = end)
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
    modifier: Modifier = Modifier,
    content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit,
) {
    val touch = isTouch()
    LazyRow(
        // Coming back down into a row lands on the tile it was left on, not whichever is nearest.
        modifier = modifier.fillMaxWidth().then(if (touch) Modifier else Modifier.focusRestorer()),
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

/**
 * The quiet line under Home's rows when no chat is starred: a star outline and a sentence on how
 * to add one, in the muted tone the empty states use, so it reads as a hint and not as a row.
 */
@Composable
private fun FavouritesHint(start: Dp, end: Dp) {
    val s = LocalStrings.current
    val touch = isTouch()
    val text = s.homeFavouritesHint(if (touch) "phone" else "tv")
    Row(
        Modifier.fillMaxWidth().padding(start = start, end = end, top = if (touch) 8.dp else 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(if (touch) 12.dp else 16.dp),
    ) {
        Icon(
            TmIcons.StarOutline,
            contentDescription = null,
            tint = Tone.muted.copy(alpha = 0.7f),
            modifier = Modifier.size(if (touch) 24.dp else 32.dp),
        )
        if (touch) {
            M3Text(text, style = M3MaterialTheme.typography.bodyMedium, color = Tone.muted)
        } else {
            Text(text, style = MaterialTheme.typography.bodyLarge, color = Tone.muted)
        }
    }
}

@Composable
private fun RowHeading(
    title: String,
    start: Dp,
    end: Dp,
    seeAll: (() -> Unit)?,
    count: String,
    focus: FocusRequester,
    armed: Boolean,
    onLeft: () -> Unit,
) {
    val s = LocalStrings.current
    val touch = isTouch()
    Row(
        // The button's own padding is the gap at the end, so its label lines up with the row's
        // last tile rather than sitting 12 dp short of it.
        Modifier.fillMaxWidth().padding(start = start, end = if (seeAll != null) (end - 12.dp).coerceAtLeast(0.dp) else end, bottom = if (touch) 4.dp else 0.dp)
            // The button's touch target is 48 dp; a heading without one keeps the same height, so
            // the gap between rows does not change with whether a row offers See all.
            .then(if (touch) Modifier.heightIn(min = 48.dp) else Modifier),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (touch) {
            M3Text(
                title,
                style = M3MaterialTheme.typography.titleMedium,
                color = Tone.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).alignByBaseline(),
            )
        } else {
            Text(
                title,
                style = MaterialTheme.typography.titleLarge,
                color = Tone.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).alignByBaseline(),
            )
        }
        if (seeAll != null && !touch) {
            // The count beside the words, as the desktop has it, so the row says how much more
            // there is before anyone presses anything.
            TmSecondaryButton(
                onClick = seeAll,
                modifier = Modifier
                    .alignByBaseline()
                    .focusProperties { canFocus = armed }
                    .focusRequester(focus)
                    .onFocusChanged { if (!it.hasFocus) onLeft() },
            ) {
                Text(s.homeSeeAll)
                // The label's own colour, softened, so it reads on the focused fill as well as off it.
                Text("  ·  $count", color = androidx.tv.material3.LocalContentColor.current.copy(alpha = 0.72f))
                Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                )
            }
        } else if (seeAll != null) {
            // Material's text button: a 48 dp touch target around a 40 dp pill, its label on the
            // heading's baseline, and a chevron that says it goes somewhere.
            TextButton(
                onClick = seeAll,
                contentPadding = PaddingValues(start = 12.dp, end = 6.dp),
                modifier = Modifier.alignByBaseline(),
            ) {
                M3Text(s.homeSeeAll, style = M3MaterialTheme.typography.labelLarge, color = Tone.accent)
                androidx.compose.material3.Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = Tone.accent,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}



/**
 * A row still asking Telegram: blank tiles the size of the real ones, so nothing jumps when it fills.
 *
 * The caption is the cards' own, in the same type with nothing visible in it, rather than a guessed
 * height: it then matches at any font scale, on the phone's dense tile and the television's card.
 */
@Composable
private fun PlaceholderRow(start: Dp) {
    val touch = isTouch()
    val width = tileWidth(touch)
    Row(
        Modifier.fillMaxWidth().padding(start = start, top = if (touch) 0.dp else Tv.FocusClearance, bottom = if (touch) 0.dp else Tv.FocusClearance),
        horizontalArrangement = Arrangement.spacedBy(if (touch) 8.dp else 16.dp),
    ) {
        repeat(PLACEHOLDER_TILES) {
            if (touch) {
                Column(Modifier.width(width).padding(DENSE_PAD)) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .aspectRatio(16f / 9f)
                            .clip(RoundedCornerShape(Corner.Small))
                            .background(Tone.surface),
                    )
                    Spacer(Modifier.height(6.dp))
                    M3Text(" ", style = M3MaterialTheme.typography.bodySmall, minLines = 2, maxLines = 2)
                    Spacer(Modifier.height(2.dp))
                    M3Text(" ", style = M3MaterialTheme.typography.labelSmall, maxLines = 1)
                }
            } else {
                Column(
                    Modifier
                        .width(width)
                        .clip(RoundedCornerShape(Corner.Medium))
                        .background(Tone.surface)
                        .border(1.dp, Tone.outline, RoundedCornerShape(Corner.Medium)),
                ) {
                    Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).background(Tone.surfaceHigh))
                    Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                        Text(" ", style = MaterialTheme.typography.titleMedium, minLines = 2, maxLines = 2)
                        Spacer(Modifier.height(6.dp))
                        Text(" ", style = MaterialTheme.typography.bodyMedium, maxLines = 1)
                    }
                }
            }
        }
    }
}

/**
 * A tile's width. A phone shows two and a half across upright, which says "there is more this
 * way" without a word; a television shows three and a bit beside the rail.
 */
private fun tileWidth(touch: Boolean): Dp = if (touch) 152.dp else 208.dp

/** The phone's dense tile pads its art by this much, as [MediaCard] does. */
private val DENSE_PAD = 4.dp
private const val PLACEHOLDER_TILES = 5
