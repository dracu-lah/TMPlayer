package com.tmplayer.desktop.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.tmplayer.data.OfflineDownloads
import com.tmplayer.data.Series
import com.tmplayer.data.SeriesSeason
import com.tmplayer.data.SettingsStore
import com.tmplayer.ui.browse.SeriesArt
import com.tmplayer.ui.browse.SeriesPanel
import com.tmplayer.ui.browse.SeriesWatch
import com.tmplayer.ui.browse.seriesProgressLine
import com.tmplayer.ui.browse.seriesSummary
import com.tmplayer.ui.components.rememberToast
import com.tmplayer.ui.i18n.LocalStrings
import com.tmplayer.ui.icons.TmIcons
import com.tmplayer.ui.nav.BackHandler
import com.tmplayer.ui.theme.Tone
import kotlinx.coroutines.launch

/**
 * The Watched list and saved positions, read the way [MediaTile] reads them, for the series view,
 * with when each was last watched for the "New" badge on a show's poster.
 */
@Composable
internal fun rememberSeriesWatch(state: ShellState): SeriesWatch {
    val progress by state.settings.watchProgress.collectAsState(initial = emptyMap())
    val watched by state.watched.watched.collectAsState(initial = emptyMap())
    val resume by state.settings.continueWatching.collectAsState(initial = emptyList())
    val times = remember(resume, watched) { com.tmplayer.data.NewEpisodes.touchTimes(resume, watched.values) }
    return remember(progress, watched, times) {
        SeriesWatch(
            point = { progress[SettingsStore.progressKey(it.chatId, it.messageId)] },
            finished = { SettingsStore.progressKey(it.chatId, it.messageId) in watched },
            touchedAt = { times[SettingsStore.progressKey(it.chatId, it.messageId)] },
        )
    }
}

/**
 * A show in a chat's grid, laid out like a video's poster: the art with its stack, the name and
 * "2 seasons · 18 eps". Hover or keyboard focus lifts it as it does a poster; a click or Enter opens
 * the show.
 */
@Composable
internal fun SeriesPoster(
    series: Series,
    watch: SeriesWatch,
    onOpen: () -> Unit,
    nav: KeyboardNav? = null,
    index: Int = 0,
    /** A 2:3 poster card, as in a chat's grid of films and shows. */
    poster: Boolean = false,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    var hasFocus by remember { mutableStateOf(false) }
    // The lift and the ring are the keyboard's; a click leaves none behind.
    val focused = hasFocus && com.tmplayer.ui.theme.FocusVisibility.visible
    val scale by animateFloatAsState(if (hovered || focused) 1.05f else 1f)
    val requester = remember { FocusRequester() }
    val progress = watch.progress(series)
    val behaviour = Modifier
            .hoverable(interaction)
            .then(if (nav != null) Modifier.navCell(nav, index, requester) else Modifier.focusRequester(requester))
            .onFocusChanged { hasFocus = it.isFocused }
            .onPreviewKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown && (event.key == Key.Enter || event.key == Key.NumPadEnter || event.key == Key.Spacebar)) {
                    onOpen()
                    true
                } else {
                    false
                }
            }
            .clickable(interactionSource = interaction, indication = null, onClick = onOpen)
    Column(
        behaviour,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .graphicsLayer { scaleX = scale; scaleY = scale }
                .then(if (focused) Modifier.border(com.tmplayer.ui.theme.Focus.Ring, Tone.accent, MaterialTheme.shapes.medium) else Modifier),
        ) {
            SeriesArt(series, progress, Modifier.fillMaxWidth(), poster = poster, fresh = remember(series, watch) { watch.newEpisodes(series) > 0 })
        }
        // The poster's own lines (see Poster), so a show sits level with the videos beside it.
        if (poster) {
            Text(series.title, style = MaterialTheme.typography.bodyMedium, maxLines = 2, minLines = 2, overflow = TextOverflow.Ellipsis)
        } else {
            Text(series.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text(
            listOfNotNull(seriesSummary(series), seriesProgressLine(progress)).joinToString("  ·  "),
            style = if (poster) MaterialTheme.typography.labelSmall else MaterialTheme.typography.labelMedium,
            color = if (progress.watched > 0) Tone.accent else Tone.muted,
            // A poster is three quarters of a card wide, where "2 seasons · 8 eps · 1 of 8
            // watched" lost its end even in English; it gets a second line.
            maxLines = if (poster) 2 else 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * A show opened on the desktop: the shared panel, with the seasons in a dropdown rather than tabs,
 * which suits a pointer and keeps a show of twelve seasons on one line. Back, Escape or the arrow
 * returns to the chat's grid.
 *
 * Download season and Download unwatched go through [downloadSelected], the grid's own batch, once
 * every episode kept or coming down in any copy is left out; the toast is the batch's sentence.
 */
@Composable
internal fun SeriesPage(
    state: ShellState,
    shown: Series,
    watch: SeriesWatch,
    onClose: () -> Unit,
    startOpen: Boolean = false,
) {
    // Every episode its chat holds, not only those the screen behind had read.
    val series = com.tmplayer.ui.browse.rememberWholeSeries(shown)
    val s = LocalStrings.current
    BackHandler(enabled = true) { onClose() }
    val scope = rememberCoroutineScope()
    val toast = rememberToast()
    val history by state.settings.downloadHistory.collectAsState(initial = emptyList())
    val index = remember(history) { history.associateBy { "${it.chatId}:${it.messageId}" } }
    // Over the page and its header, with none of its own: the window's buttons show on their own.
    CoverWindowControls()
    androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize()) {
        // A large window's page keeps a wider margin, in step with its larger type.
        val margin = if (maxWidth >= 1200.dp) 56.dp else 32.dp
        SeriesPanel(
            series = series,
            watch = watch,
            // The Play pill plays, always: it names the episode it starts, and one that opened
            // the pane instead was pressed again and again for the same pane.
            onPlay = { state.openPlayer(it, startFromBeginning = false) },
            onStartOver = { state.openPlayer(it, startFromBeginning = true) },
            // An episode's card is a tile: its page first, as a poster opens it (Settings, "Open a
            // video's page first"), and the card has the keyboard again when the pane closes.
            onOpenEpisode = if (state.detailFirst) {
                { episode, refocus ->
                    state.openDetail(DetailRequest(episode, state.chatTitleOf(episode.chatId), outsideChat = true, onClosed = refocus))
                }
            } else {
                null
            },
            contentPadding = PaddingValues(start = margin, end = margin, bottom = 24.dp),
            modifier = Modifier.fillMaxSize(),
            seasonPicker = { selected, onSelect ->
                SeasonDropdown(series.seasons, selected, onSelect, startOpen)
            },
            onOpenItem = { state.openDetail(DetailRequest(it, state.chatTitleOf(it.chatId), outsideChat = true)) },
            onDownload = { request ->
                scope.launch {
                    val active = OfflineDownloads.active.value
                    val chosen = request.copies { it.id in index || active[it.fileId]?.busy == true }
                    toast(
                        if (chosen.isEmpty()) {
                            s.browseAlreadyDownloaded
                        } else {
                            downloadSelected(state.downloads, state.settings, chosen, state.chatTitleOf(chosen.first().chatId), index)
                        },
                    )
                }
            },
            // The season's buttons say Downloaded once every episode is, from the same index.
            downloadedIds = index.keys,
            chatTitle = state.chatTitleOf(series.cover.chatId),
            // Over the backdrop on a dark disc, in a band that fills in as the page scrolls.
            onBack = onClose,
        )
    }
}

/** "Season 2 · 4 episodes" on a button, and every season in the menu under it. */
@Composable
internal fun SeasonDropdown(
    seasons: List<SeriesSeason>,
    selected: Int,
    onSelect: (Int) -> Unit,
    startOpen: Boolean = false,
) {
    val s = LocalStrings.current
    var open by remember { mutableStateOf(startOpen) }
    val current = seasons.firstOrNull { it.number == selected } ?: seasons.first()
    Box {
        OutlinedButton(onClick = { open = true }) {
            Text(
                s.seriesSeason(current.number) + "  ·  " + s.seriesEpisodesCount(current.episodes.size),
                style = MaterialTheme.typography.labelLarge,
            )
            Icon(TmIcons.ChevronDown, contentDescription = s.seriesSeasonPicker, modifier = Modifier.size(20.dp))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            seasons.forEach { season ->
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(
                                s.seriesSeason(season.number),
                                color = if (season.number == selected) Tone.accent else Color.Unspecified,
                            )
                            Text(
                                s.seriesEpisodesCount(season.episodes.size),
                                style = MaterialTheme.typography.bodySmall,
                                color = Tone.muted,
                            )
                        }
                    },
                    onClick = {
                        onSelect(season.number)
                        open = false
                    },
                )
            }
        }
    }
}
