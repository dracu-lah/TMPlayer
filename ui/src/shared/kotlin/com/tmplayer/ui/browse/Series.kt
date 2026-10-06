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
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
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
 * share watched runs along the bottom, and the tick appears once every episode is watched.
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
) {
    val s = LocalStrings.current
    val tv = !isTouch()
    val interactions = remember { MutableInteractionSource() }
    val focused by interactions.collectIsFocusedAsState()
    val item = episode.item
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

    Column(modifier) {
        Row(
            Modifier.fillMaxWidth().padding(contentPadding.horizontalOnly()),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    series.title,
                    style = if (tv) MaterialTheme.typography.headlineMedium else MaterialTheme.typography.titleLarge,
                    color = Tone.text,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    listOfNotNull(seriesSummary(series), seriesProgressLine(progress)).joinToString("  ·  "),
                    style = MaterialTheme.typography.bodyMedium,
                    color = Tone.muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
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
            val started = (watch.point(target.item)?.positionMs ?: 0L) > 0
            PlayButton(
                label = if (started) s.seriesResume(target.code) else s.seriesPlay(target.code),
                onClick = { onPlay(target.item) },
                modifier = Modifier.focusRequester(playFocus),
            )
            Box(Modifier.weight(1f)) { seasonPicker(shown.number) { season = it } }
        }
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
                EpisodeRow(
                    episode = episode,
                    point = watch.point(episode.item),
                    finished = watch.finished(episode.item),
                    upNext = next != null && episode.item.id == next.item.id,
                    onClick = { onPlay(episode.item) },
                    onLongClick = onLongClick?.let { { it(episode.item) } },
                )
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

private val EPISODE_ART_TOUCH: Dp = 128.dp
private val EPISODE_ART_TV: Dp = 176.dp
