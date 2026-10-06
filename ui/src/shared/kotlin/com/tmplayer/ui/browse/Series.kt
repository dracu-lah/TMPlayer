package com.tmplayer.ui.browse

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.tmplayer.data.EpisodeCopies
import com.tmplayer.data.MediaItem
import com.tmplayer.data.MediaMapper
import com.tmplayer.data.Series
import com.tmplayer.data.SeriesEpisode
import com.tmplayer.data.SeriesProgress
import com.tmplayer.data.SeriesSeason
import com.tmplayer.data.SeriesShelf
import com.tmplayer.data.WatchPoint
import com.tmplayer.player.StreamStats
import com.tmplayer.ui.components.MediaArt
import com.tmplayer.ui.online.MetaCastRow
import com.tmplayer.ui.online.MetaCredit
import com.tmplayer.ui.online.MetaFactsLines
import com.tmplayer.ui.online.MoreLikeThisRow
import com.tmplayer.ui.online.TrailerPill
import com.tmplayer.ui.online.TrailerHost
import com.tmplayer.ui.online.TrailerQr
import com.tmplayer.ui.online.rememberMetaExtras
import com.tmplayer.ui.online.rememberTrailerLauncher
import com.tmplayer.ui.online.MetaPicture
import com.tmplayer.ui.online.rememberMeta
import com.tmplayer.ui.components.WatchedBadge
import com.tmplayer.ui.components.isTouch
import com.tmplayer.ui.i18n.LocalStrings
import com.tmplayer.ui.theme.Corner
import com.tmplayer.ui.theme.Tone
import com.tmplayer.ui.theme.focusRing

/*
 * The series view (R5), shared by the phone, the television and the desktop: the "Series" and
 * "All files" switch, a show's picture for its tile, and the panel with the season picker and the
 * episode list. Each platform puts its own chrome round these: a bottom sheet on the phone, a full
 * screen page on the television, and a page with a season dropdown on the desktop.
 */

/** What the panels need to know about watching, read from the platform's stores. */
class SeriesWatch(
    val point: (MediaItem) -> WatchPoint?,
    val finished: (MediaItem) -> Boolean,
) {
    fun progress(series: Series): SeriesProgress =
        SeriesShelf.progress(series, finished) { point(it)?.fraction ?: 0f }

    /** Watched when any copy of the episode is. */
    fun finishedEpisode(episode: SeriesEpisode): Boolean = episode.copies.any(finished)

    /** Where the viewer stopped in whichever copy of the episode got furthest. */
    fun pointOf(episode: SeriesEpisode): WatchPoint? =
        episode.copies.mapNotNull(point).maxByOrNull { it.fraction }

    /** The copy of [episode] to play: see [SeriesShelf.pick]. */
    fun pick(episode: SeriesEpisode, progress: SeriesProgress): MediaItem =
        SeriesShelf.pick(episode, finished, { point(it)?.fraction ?: 0f }, progress.like)

    companion object {
        val None = SeriesWatch({ null }, { false })
    }
}

/**
 * "Series" or "All files". Two segments in one pill: the chosen one in the accent, the other on the
 * surface. On a television the remote lands on a segment and OK picks it, like any other button.
 */
@Composable
fun SeriesViewToggle(
    seriesView: Boolean,
    onChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val s = LocalStrings.current
    val tv = !isTouch()
    Row(
        modifier
            // A grid row hands its items the full width; the pill keeps to its own.
            .wrapContentWidth(Alignment.Start)
            .clip(CircleShape)
            .background(Tone.surfaceHigh)
            .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Segment(s.seriesSeries, chosen = seriesView, tv = tv) { onChange(true) }
        Segment(s.seriesAllFiles, chosen = !seriesView, tv = tv) { onChange(false) }
    }
}

/**
 * Where a chat-list search looks: the chats by name, or the videos in every chat as well (see
 * [com.tmplayer.data.SearchScope]). The same two-segment pill as [SeriesViewToggle].
 */
@Composable
fun SearchScopeToggle(
    scope: com.tmplayer.data.SearchScope,
    onChange: (com.tmplayer.data.SearchScope) -> Unit,
    modifier: Modifier = Modifier,
) {
    val s = LocalStrings.current
    val tv = !isTouch()
    Row(
        modifier
            .wrapContentWidth(Alignment.Start)
            .clip(CircleShape)
            .background(Tone.surfaceHigh)
            .padding(3.dp)
            .semantics { contentDescription = s.browseSearchScope },
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Segment(s.browseSearchScopeChats, chosen = scope == com.tmplayer.data.SearchScope.Chats, tv = tv) {
            onChange(com.tmplayer.data.SearchScope.Chats)
        }
        Segment(s.browseSearchScopeAllVideos, chosen = scope == com.tmplayer.data.SearchScope.AllVideos, tv = tv) {
            onChange(com.tmplayer.data.SearchScope.AllVideos)
        }
    }
}

@Composable
private fun Segment(label: String, chosen: Boolean, tv: Boolean, onClick: () -> Unit) {
    val interactions = remember { MutableInteractionSource() }
    val focused by interactions.collectIsFocusedAsState()
    // Which segment is chosen and where the remote is are two different answers, and in the dark
    // theme the focus fill is the accent, so focus is a ring rather than a fill here.
    val fill = when {
        chosen -> Tone.accent
        focused -> Tone.text.copy(alpha = 0.12f)
        else -> Color.Transparent
    }
    val ink = if (chosen) Tone.onAccent else Tone.text
    Box(
        Modifier
            .height(if (tv) 40.dp else 34.dp)
            .clip(CircleShape)
            .background(fill)
            .border(if (focused) 2.dp else 0.dp, if (focused) Tone.text else Color.Transparent, CircleShape)
            .semantics {
                role = Role.Tab
                selected = chosen
            }
            .clickable(interactionSource = interactions, indication = if (tv) null else androidx.compose.foundation.LocalIndication.current, onClick = onClick)
            .padding(horizontal = if (tv) 20.dp else 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            style = if (tv) MaterialTheme.typography.titleSmall else MaterialTheme.typography.labelLarge,
            color = ink,
            maxLines = 1,
        )
    }
}

/** "2 seasons · 18 eps", the tile's second line. */
@Composable
fun seriesSummary(series: Series): String =
    LocalStrings.current.seriesSummary(series.seasons.size, series.episodeCount)

/** "3 of 18 watched", "All watched", or nothing for a show not yet started. */
@Composable
fun seriesProgressLine(progress: SeriesProgress): String? {
    val s = LocalStrings.current
    return when {
        progress.finished -> s.seriesAllWatched
        progress.watched > 0 -> s.seriesProgress(progress.watched, progress.total)
        else -> null
    }
}

/**
 * A show's picture: the newest episode's thumbnail with a stack of edges behind it, so a tile that
 * opens onto many videos does not look like one video. The episode count sits in the corner, the
 * share watched runs along the bottom, and the tick appears once every episode is watched. With
 * posters and overviews on, the show's own wide picture replaces the episode's (never its poster,
 * which would not fill a 16:9 tile).
 */
@Composable
fun SeriesArt(
    series: Series,
    progress: SeriesProgress,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    val s = LocalStrings.current
    val cover = series.cover
    val meta = rememberMeta(cover, showOnly = true)
    val edge = if (compact) 3.dp else 4.dp
    // The whole thing keeps a video tile's 16:9, edges included, so a show sits level with the
    // videos beside it in a row.
    Column(modifier.aspectRatio(16f / 9f)) {
        // The stack: two shortening edges above the picture, read as more of the same behind it.
        Box(
            Modifier
                .padding(horizontal = 12.dp)
                .fillMaxWidth()
                .height(edge)
                .clip(RoundedCornerShape(topStart = Corner.ExtraSmall, topEnd = Corner.ExtraSmall))
                .background(Tone.muted.copy(alpha = 0.35f)),
        )
        Box(
            Modifier
                .padding(horizontal = 6.dp)
                .fillMaxWidth()
                .height(edge)
                .clip(RoundedCornerShape(topStart = Corner.ExtraSmall, topEnd = Corner.ExtraSmall))
                .background(Tone.muted.copy(alpha = 0.6f)),
        )
        Box(
            Modifier
                .fillMaxWidth()
                .weight(1f)
                .clip(RoundedCornerShape(Corner.Small)),
        ) {
            MediaArt(cover.miniThumbnail, cover.thumbnailFileId, Modifier.fillMaxSize()) {
                Text(
                    series.title.take(1).uppercase(),
                    style = MaterialTheme.typography.headlineMedium,
                    color = Tone.muted,
                )
            }
            // The backdrop only: the 2:3 poster is for the show's page, and cropped into this frame
            // it would be a band across its middle. Without one, the newest episode's frame stays.
            MetaPicture(meta?.backdropUrl, Modifier.fillMaxSize())
            Plate(
                s.seriesEpisodesCount(series.episodeCount),
                compact,
                Modifier.align(Alignment.TopEnd),
            )
            val fraction = if (progress.total == 0) 0f else progress.watched.toFloat() / progress.total
            if (fraction > 0f) Bar(fraction, Modifier.align(Alignment.BottomStart))
            if (progress.finished) {
                WatchedBadge(
                    size = if (compact) 18.dp else 26.dp,
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(start = if (compact) 4.dp else 6.dp, bottom = if (compact) 10.dp else 12.dp),
                )
            }
        }
    }
}

@Composable
private fun Plate(text: String, compact: Boolean, modifier: Modifier) {
    Text(
        text,
        style = if (compact) MaterialTheme.typography.labelSmall else MaterialTheme.typography.labelMedium,
        color = Color.White,
        maxLines = 1,
        modifier = modifier
            .padding(if (compact) 4.dp else 6.dp)
            .clip(RoundedCornerShape(Corner.ExtraSmall))
            .background(Color.Black.copy(alpha = 0.72f))
            .padding(horizontal = if (compact) 4.dp else 6.dp, vertical = if (compact) 1.dp else 2.dp),
    )
}

/** The progress line along the bottom of a picture, as on a video's own tile. */
@Composable
private fun Bar(fraction: Float, modifier: Modifier) {
    Box(
        modifier
            .fillMaxWidth()
            .height(6.dp)
            .background(Color.Black.copy(alpha = 0.55f)),
    ) {
        Box(Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).fillMaxHeight().background(Tone.accent))
    }
}

/**
 * The seasons as a row of tabs. On a television the season under the remote is the one shown, so
 * walking Left and Right along the row pages through the seasons and Down enters the episodes.
 */
@Composable
fun SeasonTabs(
    seasons: List<SeriesSeason>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester? = null,
) {
    val s = LocalStrings.current
    val tv = !isTouch()
    Row(
        modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        seasons.forEach { season ->
            val interactions = remember { MutableInteractionSource() }
            val focused by interactions.collectIsFocusedAsState()
            val chosen = season.number == selected
            LaunchedEffect(focused) { if (focused && tv) onSelect(season.number) }
            val fill = when {
                focused -> Tone.focusFill
                chosen -> Tone.accent.copy(alpha = 0.18f)
                else -> Tone.surfaceHigh
            }
            Column(
                Modifier
                    .then(if (chosen && focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
                    .clip(RoundedCornerShape(Corner.Medium))
                    .background(fill)
                    .border(
                        if (chosen && !focused) 1.5.dp else 0.dp,
                        if (chosen && !focused) Tone.accent else Color.Transparent,
                        RoundedCornerShape(Corner.Medium),
                    )
                    .focusRing(focused, RoundedCornerShape(Corner.Medium))
                    .semantics {
                        role = Role.Tab
                        this.selected = chosen
                    }
                    .clickable(interactionSource = interactions, indication = null) { onSelect(season.number) }
                    .padding(horizontal = if (tv) 20.dp else 16.dp, vertical = if (tv) 10.dp else 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    s.seriesSeason(season.number),
                    style = if (tv) MaterialTheme.typography.titleMedium else MaterialTheme.typography.labelLarge,
                    fontWeight = if (chosen) FontWeight.SemiBold else FontWeight.Normal,
                    color = when {
                        focused -> Tone.onFocusFill
                        chosen -> Tone.accent
                        else -> Tone.text
                    },
                    maxLines = 1,
                )
                Text(
                    s.seriesEpisodesCount(season.episodes.size),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (focused) Tone.onFocusFill.copy(alpha = 0.85f) else Tone.muted,
                    maxLines = 1,
                )
            }
        }
    }
}

/**
 * One episode: its picture with progress and tick, "Episode 2" and its code, the file's own name,
 * and the running time, size and where the viewer stopped.
 *
 * An episode the chat holds in several copies (350 MB, 600 MB, 1.8 GB) is still one row. The row
 * plays [chosen], the copy most like the one the viewer watched last, and a line of chips under it
 * names every copy by its quality and size so another can be picked with [onCopy].
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun EpisodeRow(
    episode: SeriesEpisode,
    point: WatchPoint?,
    finished: Boolean,
    upNext: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    chosen: MediaItem = episode.item,
    onCopy: ((MediaItem) -> Unit)? = null,
) {
    val tv = !isTouch()
    if (episode.copies.size <= 1 || onCopy == null) {
        EpisodeLine(episode, chosen, point, finished, upNext, onClick, modifier, onLongClick)
        return
    }
    // DOWN from the row goes to the chip of the copy it plays, not to whichever chip sits nearest
    // the row's middle (which, on a wide television row, was the last one).
    val chips = remember(episode.copies.size) { List(episode.copies.size) { FocusRequester() } }
    val landing = chips[episode.copies.indexOfFirst { it.id == chosen.id }.coerceAtLeast(0)]
    Column(modifier.fillMaxWidth()) {
        EpisodeLine(episode, chosen, point, finished, upNext, onClick, Modifier.focusProperties { down = landing }, onLongClick)
        Row(
            Modifier
                .fillMaxWidth()
                .padding(
                    // Under the text, past the picture and the row's own padding and gap.
                    start = if (tv) EPISODE_ART_TV + 12.dp + 16.dp else EPISODE_ART_TOUCH + 8.dp + 12.dp,
                    bottom = 4.dp,
                ),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            episode.copies.forEachIndexed { at, copy ->
                CopyChip(copy, chosen = copy.id == chosen.id, onClick = { onCopy(copy) }, modifier = Modifier.focusRequester(chips[at]))
            }
        }
    }
}

/**
 * One copy of an episode, by its quality and size: "1080p · 1.8 GB". Under the remote it takes the
 * focus fill, the ring and the grow every other television control does.
 */
@Composable
internal fun CopyChip(copy: MediaItem, chosen: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val s = LocalStrings.current
    val tv = !isTouch()
    val interactions = remember { MutableInteractionSource() }
    val focused by interactions.collectIsFocusedAsState()
    val label = EpisodeCopies.label(copy)
    val shape = RoundedCornerShape(Corner.ExtraSmall)
    Text(
        label,
        style = MaterialTheme.typography.labelMedium,
        color = when {
            focused -> Tone.onFocusFill
            chosen -> Tone.accent
            else -> Tone.muted
        },
        maxLines = 1,
        modifier = modifier
            .clip(shape)
            .background(if (focused) Tone.focusFill else Color.Transparent)
            .focusRing(focused, shape)
            .border(
                1.dp,
                when {
                    focused -> Color.Transparent
                    chosen -> Tone.accent.copy(alpha = 0.6f)
                    else -> Tone.muted.copy(alpha = 0.4f)
                },
                shape,
            )
            .semantics { contentDescription = s.seriesCopy(label) }
            .clickable(interactionSource = interactions, indication = if (tv) null else androidx.compose.foundation.LocalIndication.current, onClick = onClick)
            .padding(horizontal = 8.dp, vertical = if (tv) 4.dp else 2.dp),
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun EpisodeLine(
    episode: SeriesEpisode,
    item: MediaItem,
    point: WatchPoint?,
    finished: Boolean,
    upNext: Boolean,
    onClick: () -> Unit,
    modifier: Modifier,
    onLongClick: (() -> Unit)?,
) {
    val s = LocalStrings.current
    val tv = !isTouch()
    val interactions = remember { MutableInteractionSource() }
    val focused by interactions.collectIsFocusedAsState()
    val shape = RoundedCornerShape(Corner.Medium)
    Row(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(if (focused) Tone.surfaceHigh else Color.Transparent)
            .border(if (focused) 3.dp else 0.dp, if (focused) Tone.accent else Color.Transparent, shape)
            .then(
                if (onLongClick != null && !tv) {
                    Modifier.combinedClickable(interactionSource = interactions, indication = androidx.compose.foundation.LocalIndication.current, onClick = onClick, onLongClick = onLongClick)
                } else {
                    Modifier.clickable(interactionSource = interactions, indication = if (tv) null else androidx.compose.foundation.LocalIndication.current, onClick = onClick)
                },
            )
            .padding(horizontal = if (tv) 12.dp else 8.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(if (tv) 16.dp else 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .width(if (tv) EPISODE_ART_TV else EPISODE_ART_TOUCH)
                .aspectRatio(16f / 9f)
                .clip(RoundedCornerShape(Corner.Small)),
        ) {
            MediaArt(item.miniThumbnail, item.thumbnailFileId, Modifier.fillMaxSize()) {
                Text("E${episode.episode}", style = MaterialTheme.typography.titleMedium, color = Tone.muted)
            }
            val fraction = when {
                point != null && point.fraction > 0f -> point.fraction
                finished -> 1f
                else -> 0f
            }
            if (fraction > 0f) Bar(fraction, Modifier.align(Alignment.BottomStart))
            if (finished) {
                WatchedBadge(
                    size = if (tv) 24.dp else 20.dp,
                    modifier = Modifier.align(Alignment.BottomStart).padding(start = 4.dp, bottom = 10.dp),
                )
            }
            if (upNext && !finished) {
                Icon(
                    Icons.Filled.PlayArrow,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .size(if (tv) 40.dp else 32.dp)
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.55f))
                        .padding(4.dp),
                )
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    s.seriesEpisode(episode.episode),
                    style = if (tv) MaterialTheme.typography.titleMedium else MaterialTheme.typography.titleSmall,
                    color = Tone.text,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                )
                Text(
                    episode.code,
                    style = MaterialTheme.typography.labelMedium,
                    color = Tone.muted,
                    maxLines = 1,
                )
                if (upNext && !finished) {
                    Text(
                        s.seriesUpNextBadge,
                        style = MaterialTheme.typography.labelSmall,
                        color = Tone.onAccent,
                        maxLines = 1,
                        modifier = Modifier
                            .clip(RoundedCornerShape(Corner.ExtraSmall))
                            .background(Tone.accent)
                            .padding(horizontal = 6.dp, vertical = 1.dp),
                    )
                }
            }
            Text(
                item.title,
                style = if (tv) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.bodySmall,
                color = Tone.muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val stopped = point?.takeIf { it.positionMs > 0 && !finished }
                ?.let { s.seriesStoppedAt(StreamStats.formatClock(it.positionMs)) }
            val meta = listOfNotNull(
                MediaMapper.formatDuration(item.durationSec).ifEmpty { null },
                MediaMapper.formatSize(item.sizeBytes).ifEmpty { null },
                stopped ?: s.seriesWatchedLabel.takeIf { finished },
            ).joinToString("  ·  ")
            if (meta.isNotEmpty()) {
                Text(
                    meta,
                    style = MaterialTheme.typography.labelMedium,
                    color = if (stopped != null || finished) Tone.accent else Tone.muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * A show opened: its name, how far through it the viewer is, a button straight to the next
 * episode, the season picker and the episodes of the chosen season.
 *
 * [seasonPicker] is the platform's: tabs on a phone and a television ([SeasonTabs], the default),
 * a dropdown on the desktop. On a television the play button takes focus first, so OK straight
 * after opening a show carries on where the viewer left it.
 */
@Composable
fun SeriesPanel(
    series: Series,
    watch: SeriesWatch,
    onPlay: (MediaItem) -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    onLongClick: ((MediaItem) -> Unit)? = null,
    seasonPicker: @Composable (selected: Int, onSelect: (Int) -> Unit) -> Unit = { selected, onSelect ->
        SeasonTabs(series.seasons, selected, onSelect)
    },
    /** Beside the heading, for the platform's own close or back control. */
    trailing: @Composable () -> Unit = {},
    /** Opens a video's detail, for "More like this"; null leaves that row out. */
    onOpenItem: ((MediaItem) -> Unit)? = onLongClick,
) {
    val s = LocalStrings.current
    val tv = !isTouch()
    val progress = watch.progress(series)
    val next = progress.next
    var season by rememberSaveable(series.key) {
        mutableIntStateOf(next?.season ?: series.seasons.first().number)
    }
    val shown = series.seasons.firstOrNull { it.number == season } ?: series.seasons.first()
    val playFocus = remember { FocusRequester() }
    LaunchedEffect(series.key) { if (tv) runCatching { playFocus.requestFocus() } }

    val meta = rememberMeta(series.cover, showOnly = true)
    val extras = rememberMetaExtras(meta)
    val trailers = rememberTrailerLauncher()

    Column(modifier) {
        Row(
            Modifier.fillMaxWidth().padding(contentPadding.horizontalOnly()),
            verticalAlignment = if (meta?.posterUrl != null) Alignment.Top else Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            meta?.posterUrl?.let { url ->
                Box(
                    Modifier
                        .width(if (tv) SERIES_POSTER_TV else SERIES_POSTER)
                        .aspectRatio(2f / 3f)
                        .clip(RoundedCornerShape(Corner.Small))
                        .background(Tone.surfaceHigh),
                ) {
                    MetaPicture(url, Modifier.fillMaxSize(), maxWidth = 240, contentDescription = s.metadataPoster)
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    meta?.title?.takeIf { it.isNotBlank() } ?: series.title,
                    style = if (tv) MaterialTheme.typography.headlineMedium else MaterialTheme.typography.titleLarge,
                    color = Tone.text,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    listOfNotNull(meta?.year?.toString(), seriesSummary(series), seriesProgressLine(progress)).joinToString("  ·  "),
                    style = MaterialTheme.typography.bodyMedium,
                    color = Tone.muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                MetaFactsLines(extras, Modifier.padding(top = 2.dp), maxLines = 1)
                meta?.overview?.takeIf { it.isNotBlank() }?.let { overview ->
                    Spacer(Modifier.height(6.dp))
                    Text(
                        overview,
                        style = MaterialTheme.typography.bodyMedium,
                        color = Tone.text,
                        maxLines = if (tv) 3 else 4,
                        overflow = TextOverflow.Ellipsis,
                    )
                    MetaCredit(meta, Modifier.padding(top = 2.dp))
                }
            }
            trailing()
        }
        Spacer(Modifier.height(12.dp))
        Row(
            Modifier.fillMaxWidth().padding(contentPadding.horizontalOnly()),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val target = next ?: series.episodes.first()
            val started = (watch.pointOf(target)?.positionMs ?: 0L) > 0
            PlayButton(
                label = if (started) s.seriesResume(target.code) else s.seriesPlay(target.code),
                onClick = { onPlay(next?.item ?: watch.pick(target, progress)) },
                modifier = Modifier.focusRequester(playFocus),
            )
            extras?.trailer?.let { trailer -> TrailerPill(onClick = { trailers.open(trailer) }) }
            Box(Modifier.weight(1f)) { seasonPicker(shown.number) { season = it } }
        }
        trailers.unopened?.takeIf { it == extras?.trailer }?.let {
            TrailerQr(it, Modifier.padding(contentPadding.horizontalOnly()).padding(top = 12.dp))
        }
        TrailerHost(trailers)
        Spacer(Modifier.height(12.dp))
        val list = rememberLazyListState()
        LaunchedEffect(shown.number) { list.scrollToItem(0) }
        LazyColumn(
            state = list,
            modifier = Modifier.fillMaxWidth().weight(1f, fill = false),
            contentPadding = contentPadding,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            items(shown.episodes, key = { it.item.id }) { episode ->
                val chosen = if (next != null && episode.sameAs(next)) next.item else watch.pick(episode, progress)
                EpisodeRow(
                    episode = episode,
                    point = watch.pointOf(episode),
                    finished = watch.finishedEpisode(episode),
                    upNext = next != null && episode.sameAs(next),
                    onClick = { onPlay(chosen) },
                    onLongClick = onLongClick?.let { { it(chosen) } },
                    chosen = chosen,
                    onCopy = onPlay,
                )
            }
            // The cast and "More like this" after the episodes: composed only once scrolled to.
            if (meta != null && extras != null) {
                if (extras.cast.isNotEmpty()) {
                    item(key = "meta-cast") { MetaCastRow(extras.cast, Modifier.padding(top = 16.dp)) }
                }
                if (extras.similar.isNotEmpty() && onOpenItem != null) {
                    item(key = "meta-more") { MoreLikeThisRow(meta, extras, onOpenItem, Modifier.padding(top = 16.dp)) }
                }
            }
        }
    }
}

@Composable
private fun PlayButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val interactions = remember { MutableInteractionSource() }
    val focused by interactions.collectIsFocusedAsState()
    val tv = !isTouch()
    Row(
        modifier
            .height(if (tv) 48.dp else 40.dp)
            .clip(CircleShape)
            .background(Tone.accent)
            .border(if (focused) 2.dp else 0.dp, if (focused) Tone.text else Color.Transparent, CircleShape)
            .clickable(interactionSource = interactions, indication = if (tv) null else androidx.compose.foundation.LocalIndication.current, onClick = onClick)
            .padding(start = 14.dp, end = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        val ink = Tone.onAccent
        Icon(Icons.Filled.PlayArrow, contentDescription = null, tint = ink, modifier = Modifier.size(22.dp))
        Text(label, style = MaterialTheme.typography.labelLarge, color = ink, maxLines = 1)
    }
}

private fun PaddingValues.horizontalOnly(): PaddingValues =
    PaddingValues(
        start = calculateLeftPadding(androidx.compose.ui.unit.LayoutDirection.Ltr),
        end = calculateRightPadding(androidx.compose.ui.unit.LayoutDirection.Ltr),
    )

private val SERIES_POSTER: Dp = 96.dp
private val SERIES_POSTER_TV: Dp = 112.dp
private val EPISODE_ART_TOUCH: Dp = 128.dp
private val EPISODE_ART_TV: Dp = 176.dp
