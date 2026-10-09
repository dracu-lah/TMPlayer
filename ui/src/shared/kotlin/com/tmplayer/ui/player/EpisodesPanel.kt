package com.tmplayer.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.tmplayer.data.DeviceForm
import com.tmplayer.data.EpisodeOrder
import com.tmplayer.data.IntroSkip
import com.tmplayer.data.MediaItem
import com.tmplayer.data.Series
import com.tmplayer.data.SeriesEpisode
import com.tmplayer.ui.browse.EpisodeRow
import com.tmplayer.ui.browse.SeasonTabs
import com.tmplayer.ui.browse.SeriesWatch
import com.tmplayer.ui.browse.seriesSummary
import com.tmplayer.ui.components.deviceForm
import com.tmplayer.ui.components.isTouch
import com.tmplayer.ui.i18n.LocalStrings
import com.tmplayer.ui.theme.Corner
import com.tmplayer.ui.theme.Tone
import com.tmplayer.ui.theme.focusRing
import kotlinx.coroutines.delay

/**
 * What the player's episode list shows: the show the video playing belongs to, the steps either
 * side of it, and the per series choices made here.
 */
@Immutable
data class EpisodesState(
    val series: Series,
    /** The video playing, which the list tints and opens on. */
    val playing: MediaItem,
    val previous: MediaItem? = null,
    /** "Previous: S01E03  ·  The Lighthouse", the step buttons' words. */
    val previousLabel: String? = null,
    val next: MediaItem? = null,
    val nextLabel: String? = null,
    /** "S01E03", for the short form a narrow screen has room for. */
    val previousCode: String? = null,
    val nextCode: String? = null,
    /** The app's autoplay setting, the same one Settings shows. */
    val autoplay: Boolean = true,
    val order: EpisodeOrder = EpisodeOrder.Number,
    /** Where this show's intro ends, as marked here, or null. */
    val introEndMs: Long? = null,
)

/** What the episode list asks of the player. Every one of these is the player's to carry out. */
class EpisodesActions(
    /** Plays an episode, or for the one already playing, closes the list. */
    val onPlay: (MediaItem) -> Unit,
    val onToggleWatched: (MediaItem) -> Unit,
    val onAutoplay: (Boolean) -> Unit,
    val onOrder: (EpisodeOrder) -> Unit,
    /** Marks the intro as ending where playback is now. */
    val onSetIntro: () -> Unit,
    val onClearIntro: () -> Unit,
)

/**
 * The player's episode list (CP40), shared by the phone, the television and the desktop: each puts
 * its own window round it. The show's name, the previous and next episode, the choices that belong
 * to watching a series (autoplay, which episode counts as next, the learned Skip intro), then the
 * seasons as tabs and the chosen season's episodes in the series view's own rows, the one playing
 * tinted, scrolled to and, under a remote, focused.
 *
 * Wide (a landscape phone, a television, a desktop window) the choices take a column at the start
 * and the episodes the rest; narrow they lead the list and scroll away with it, so a phone held
 * upright still shows the episodes rather than a screen of switches.
 *
 * [position] is read twice a second for the "Set intro end here" line's time. [trailing] sits at the
 * end of the heading, for the window's own way out.
 */
@Composable
fun EpisodesPanel(
    state: EpisodesState,
    watch: SeriesWatch,
    actions: EpisodesActions,
    position: () -> Long,
    modifier: Modifier = Modifier,
    contentPadding: Dp = 0.dp,
    /** Focus the episode playing on open: under a remote, and on a desktop for the keyboard. */
    focusCurrent: Boolean = !isTouch(),
    trailing: @Composable () -> Unit = {},
) {
    val s = LocalStrings.current
    val tv = !isTouch()
    val series = state.series
    val playingEpisode = remember(series, state.playing.id) {
        series.episodes.firstOrNull { episode -> episode.copies.any { it.id == state.playing.id } }
    }
    var season by remember(series.key) {
        mutableIntStateOf(playingEpisode?.season ?: series.seasons.first().number)
    }
    val shown = series.seasons.firstOrNull { it.number == season } ?: series.seasons.first()
    val list = rememberLazyListState()
    val currentFocus = remember { FocusRequester() }
    // Whether the playing row has really taken focus, which on a slow television can take more
    // than one frame after the window opens.
    var currentFocused by remember { mutableStateOf(false) }
    val progress = watch.progress(series)
    val next = progress.next
    val now by produceState(position()) {
        while (true) {
            value = position()
            delay(500)
        }
    }

    BoxWithConstraints(modifier) {
        val wide = maxWidth >= WIDE_FROM
        val pad = PaddingValues(horizontal = contentPadding)
        Column(Modifier.fillMaxWidth()) {
            Row(
                Modifier.fillMaxWidth().padding(pad),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
                    Text(
                        s.episodesTitle,
                        style = if (tv) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.titleLarge,
                        color = Tone.text,
                        maxLines = 1,
                    )
                    Text(
                        listOf(series.title, seriesSummary(series)).joinToString("  ·  "),
                        style = MaterialTheme.typography.bodyMedium,
                        color = Tone.muted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                trailing()
            }
            Spacer(Modifier.height(8.dp))

            // The rows, as either layout lists them. The tabs only once there is a choice to make.
            val rows: LazyListScope.() -> Unit = {
                if (series.seasons.size > 1) {
                    item(key = "seasons") {
                        SeasonTabs(
                            series.seasons,
                            shown.number,
                            onSelect = { season = it },
                            modifier = Modifier.padding(pad).padding(vertical = 8.dp),
                        )
                    }
                }
                items(shown.episodes, key = { it.item.id }) { episode ->
                    val isPlaying = playingEpisode?.sameAs(episode) == true
                    // "Up next" is the player's own next step, by the order chosen here, not where the
                    // viewer stopped across the show (the series page's meaning).
                    val stepNext = state.next?.let { n -> episode.copies.any { it.id == n.id } } == true
                    val chosen = when {
                        isPlaying -> state.playing
                        stepNext -> state.next!!
                        next != null && episode.sameAs(next) -> next.item
                        else -> watch.pick(episode, progress)
                    }
                    EpisodeRow(
                        episode = episode,
                        point = watch.pointOf(episode),
                        finished = watch.finishedEpisode(episode),
                        upNext = stepNext,
                        onClick = { actions.onPlay(chosen) },
                        onLongClick = { actions.onToggleWatched(chosen) },
                        chosen = chosen,
                        onCopy = actions.onPlay,
                        current = isPlaying,
                        holdOk = true,
                        modifier = Modifier.padding(pad).secondaryClick { actions.onToggleWatched(chosen) },
                        lineModifier = if (isPlaying) {
                            Modifier.focusRequester(currentFocus).onFocusChanged { currentFocused = it.isFocused }
                        } else {
                            Modifier
                        },
                    )
                }
            }

            if (wide) {
                Row(Modifier.fillMaxWidth().weight(1f, fill = false), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                    Column(
                        Modifier
                            .width(CHOICES_WIDTH)
                            .fillMaxHeight()
                            .verticalScroll(rememberScrollState())
                            .padding(start = contentPadding, bottom = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        Choices(state, actions, now)
                    }
                    LazyColumn(
                        state = list,
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(end = contentPadding, bottom = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                        content = rows,
                    )
                }
            } else {
                // Narrow, the steps stay put at the top, side by side, and the choices follow the
                // episodes: on a phone held upright the list is what the screen is for.
                Steps(state, actions, sideBySide = true, modifier = Modifier.padding(pad).padding(bottom = 8.dp))
                LazyColumn(
                    state = list,
                    modifier = Modifier.fillMaxWidth().weight(1f, fill = false),
                    contentPadding = PaddingValues(bottom = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    rows()
                    item(key = "choices") {
                        Column(
                            Modifier.padding(pad).padding(top = 16.dp),
                            verticalArrangement = Arrangement.spacedBy(14.dp),
                        ) { Choices(state, actions, now, withSteps = false) }
                    }
                }
            }
        }

        // The episode playing in view, and under a remote, focused: once the rows are laid out.
        val lead = if (series.seasons.size > 1) 1 else 0
        // Only once the window has focus: a window that gains it moves focus to its first control
        // (Previous), over a request made before that, which on a slow stick it often was. Then
        // tried for a few frames, in case the first layout arrives late as well.
        val window = LocalWindowInfo.current
        LaunchedEffect(series.key, wide) {
            val playingSeason = playingEpisode?.season ?: return@LaunchedEffect
            val at = series.seasons.firstOrNull { it.number == playingSeason }?.episodes
                ?.indexOfFirst { playingEpisode.sameAs(it) } ?: -1
            if (at < 0) return@LaunchedEffect
            // A touch screen places no focus, so it scrolls at once.
            if (focusCurrent) withTimeoutOrNull(WINDOW_FOCUS_WAIT_MS) { snapshotFlow { window.isWindowFocused }.first { it } }
            // The window's own first focus can land on a season tab, and on a television a
            // focused tab shows its season (right to left, that was Season 2): back to the one
            // playing before the row is looked for.
            season = playingSeason
            repeat(FOCUS_TRIES) {
                withFrameNanos { }
                if (list.layoutInfo.totalItemsCount == 0) return@repeat
                bringIntoView(list, lead + at)
                if (!focusCurrent) return@LaunchedEffect
                withFrameNanos { }
                runCatching { currentFocus.requestFocus() }
                withFrameNanos { }
                if (currentFocused) return@LaunchedEffect
            }
        }
    }
}

/** Scrolls [index] into view, one row of context above it, unless it is already wholly shown. */
private suspend fun bringIntoView(list: LazyListState, index: Int) {
    val info = list.layoutInfo
    val seen = info.visibleItemsInfo.firstOrNull { it.index == index }
    if (seen != null && seen.offset >= info.viewportStartOffset && seen.offset + seen.size <= info.viewportEndOffset) return
    list.scrollToItem((index - 1).coerceAtLeast(0))
}

/** The steps, autoplay, the next-up order, Skip intro and how to mark an episode. */
@Composable
private fun ColumnScope.Choices(
    state: EpisodesState,
    actions: EpisodesActions,
    positionMs: Long,
    withSteps: Boolean = true,
) {
    val s = LocalStrings.current
    val f = s.formatter
    if (withSteps) Steps(state, actions, sideBySide = false)

    SwitchLine(s.episodesAutoplay, state.autoplay) { actions.onAutoplay(!state.autoplay) }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Heading(s.episodesOrder)
        // Two lines rather than a two-segment pill: a translated "Episode number" does not fit
        // half a column.
        OrderLine(s.episodesOrderNumber, state.order == EpisodeOrder.Number) { actions.onOrder(EpisodeOrder.Number) }
        OrderLine(s.episodesOrderUpload, state.order == EpisodeOrder.Upload) { actions.onOrder(EpisodeOrder.Upload) }
    }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Heading(s.episodesSkipIntro)
        val end = state.introEndMs
        if (end != null) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    s.episodesIntroEnds(f.clock(end)),
                    style = MaterialTheme.typography.bodyMedium,
                    color = Tone.text,
                    modifier = Modifier.weight(1f, fill = false),
                )
                PanelButton(s.episodesIntroClear, onClick = actions.onClearIntro, quiet = true)
            }
        }
        val markable = IntroSkip.canMark(positionMs)
        PanelButton(
            label = if (markable) s.episodesIntroSet(f.clock(positionMs)) else s.episodesIntroTooEarly,
            onClick = actions.onSetIntro,
            enabled = markable,
            modifier = Modifier.fillMaxWidth(),
        )
        Text(s.episodesIntroDetail, style = MaterialTheme.typography.bodySmall, color = Tone.muted)
    }

    Text(
        when (deviceForm()) {
            DeviceForm.Tv -> s.episodesHintTv
            DeviceForm.Desktop -> s.episodesHintDesktop
            DeviceForm.Phone -> s.episodesHintTouch
        },
        style = MaterialTheme.typography.bodySmall,
        color = Tone.muted,
    )
}

/**
 * The previous and next episode, labelled with where they lead. One above the other in the wide
 * layout's column; [sideBySide] on a narrow screen, each half the width.
 */
@Composable
private fun Steps(state: EpisodesState, actions: EpisodesActions, sideBySide: Boolean, modifier: Modifier = Modifier) {
    val s = LocalStrings.current
    val previous = state.previous
    val next = state.next
    if (previous == null && next == null) return
    @Composable
    fun PreviousButton(m: Modifier) = previous?.let {
        val short = state.previousCode?.takeIf { sideBySide }?.let { code -> s.playerPreviousUp(code) }
        PanelButton(short ?: state.previousLabel ?: s.playerPreviousEpisode, onClick = { actions.onPlay(it) }, modifier = m, icon = SkipPrevious, lines = if (sideBySide) 1 else 2)
    }
    @Composable
    fun NextButton(m: Modifier) = next?.let {
        val short = state.nextCode?.takeIf { sideBySide }?.let { code -> s.playerNextUp(code) }
        PanelButton(short ?: state.nextLabel ?: s.playerNextEpisode, onClick = { actions.onPlay(it) }, modifier = m, icon = SkipNext, lines = if (sideBySide) 1 else 2)
    }
    if (sideBySide) {
        Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PreviousButton(Modifier.weight(1f))
            NextButton(Modifier.weight(1f))
        }
    } else {
        Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            PreviousButton(Modifier.fillMaxWidth())
            NextButton(Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun Heading(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, color = Tone.accent, maxLines = 1)
}

/**
 * A button of the panel: the surface step at rest, the focus fill and ring under a remote or a
 * keyboard. [quiet] draws it without the fill, for a small action beside a line of text. A button
 * that cannot be used now ([enabled] false) is drawn faint and still takes focus, so the remote is
 * never dropped.
 */
@Composable
private fun PanelButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    quiet: Boolean = false,
    lines: Int = 2,
) {
    val tv = !isTouch()
    val interactions = remember { MutableInteractionSource() }
    val focused by interactions.collectIsFocusedAsState()
    val shape = RoundedCornerShape(Corner.Medium)
    val ink = if (focused) Tone.onFocusFill else Tone.text
    Row(
        modifier
            .heightIn(min = if (tv) 48.dp else 44.dp)
            .clip(shape)
            .background(
                when {
                    focused -> Tone.focusFill
                    quiet -> Color.Transparent
                    else -> Tone.surfaceHigh
                },
            )
            .then(if (!focused) Modifier.border(1.dp, Tone.outline, shape) else Modifier)
            .focusRing(focused, shape)
            .clickable(
                interactionSource = interactions,
                indication = if (tv) null else androidx.compose.foundation.LocalIndication.current,
                role = Role.Button,
            ) { if (enabled) onClick() }
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        val faint = Modifier.alpha(if (enabled) 1f else 0.5f)
        if (icon != null) {
            // Not mirrored right to left: the step glyphs point the way time runs, which is left
            // to right in every language, as the player's own transport keeps it.
            Icon(icon, contentDescription = null, tint = ink, modifier = faint.size(20.dp))
        }
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            color = ink,
            maxLines = lines,
            overflow = TextOverflow.Ellipsis,
            modifier = faint,
        )
    }
}

/** A line with a switch at its end, the whole line the target. */
@Composable
private fun SwitchLine(label: String, on: Boolean, onToggle: () -> Unit) {
    val tv = !isTouch()
    val interactions = remember { MutableInteractionSource() }
    val focused by interactions.collectIsFocusedAsState()
    val shape = RoundedCornerShape(Corner.Medium)
    val s = LocalStrings.current
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = if (tv) 52.dp else 48.dp)
            .clip(shape)
            .background(if (focused) Tone.focusFill else Tone.surfaceHigh)
            .focusRing(focused, shape)
            .semantics {
                role = Role.Switch
                stateDescription = if (on) s.commonOn else s.commonOff
            }
            .clickable(
                interactionSource = interactions,
                indication = if (tv) null else androidx.compose.foundation.LocalIndication.current,
                onClick = onToggle,
            )
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            color = if (focused) Tone.onFocusFill else Tone.text,
            modifier = Modifier.weight(1f),
        )
        // Drawn rather than Material's Switch: the same track on a phone, a television and a
        // desktop, and nothing in it that takes focus away from the line.
        val track = if (on) Tone.accent else Tone.muted.copy(alpha = 0.45f)
        Box(
            Modifier.size(width = 40.dp, height = 24.dp).clip(CircleShape).background(track)
                .then(if (focused) Modifier.border(2.dp, Tone.onFocusFill, CircleShape) else Modifier),
            contentAlignment = Alignment.CenterStart,
        ) {
            Box(
                Modifier
                    .offset(x = if (on) 19.dp else 3.dp)
                    .size(18.dp)
                    .clip(CircleShape)
                    .background(if (on) Tone.onAccent else Color.White),
            )
        }
    }
}

/** One of the next-up orders: a radio and its words, the whole line the target. */
@Composable
private fun OrderLine(label: String, chosen: Boolean, onClick: () -> Unit) {
    val tv = !isTouch()
    val interactions = remember { MutableInteractionSource() }
    val focused by interactions.collectIsFocusedAsState()
    val shape = RoundedCornerShape(Corner.Medium)
    val ink = if (focused) Tone.onFocusFill else Tone.text
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = if (tv) 48.dp else 44.dp)
            .clip(shape)
            .background(
                when {
                    focused -> Tone.focusFill
                    chosen -> Tone.accent.copy(alpha = 0.14f)
                    else -> Color.Transparent
                },
            )
            .focusRing(focused, shape)
            .semantics {
                role = Role.RadioButton
                selected = chosen
            }
            .clickable(
                interactionSource = interactions,
                indication = if (tv) null else androidx.compose.foundation.LocalIndication.current,
                onClick = onClick,
            )
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        val ring = if (focused) ink else if (chosen) Tone.accent else Tone.muted
        Box(Modifier.size(20.dp).border(2.dp, ring, CircleShape), contentAlignment = Alignment.Center) {
            if (chosen) Box(Modifier.size(10.dp).clip(CircleShape).background(ring))
        }
        Text(label, style = MaterialTheme.typography.labelLarge, color = ink, modifier = Modifier.weight(1f))
    }
}

/**
 * A right click, which a mouse sends and a finger and a remote never do: marks the episode on the
 * desktop. Watched on the way in, before the row's own click handling sees the press.
 */
private fun Modifier.secondaryClick(onClick: () -> Unit): Modifier = pointerInput(onClick) {
    awaitPointerEventScope {
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            if (event.type == PointerEventType.Press && event.buttons.isSecondaryPressed) {
                event.changes.forEach { it.consume() }
                onClick()
            }
        }
    }
}

private fun glyph(name: String, pathData: String): ImageVector = ImageVector.Builder(
    name = name,
    defaultWidth = 24.dp,
    defaultHeight = 24.dp,
    viewportWidth = 24f,
    viewportHeight = 24f,
).addPath(
    pathData = PathParser().parsePathString(pathData).toNodes(),
    fill = SolidColor(Color.Black),
).build()

private val SkipNext: ImageVector by lazy { glyph("SkipNext", "M6 18L14.5 12L6 6z M16 6h2v12h-2z") }
private val SkipPrevious: ImageVector by lazy { glyph("SkipPrevious", "M6 6h2v12H6z M9.5 12L18 18V6z") }

/** From this width the choices take a column of their own beside the episodes. */
private val WIDE_FROM: Dp = 640.dp

private val CHOICES_WIDTH: Dp = 280.dp

/** How long the open-time focus waits for the window to take focus before trying anyway. */
private const val WINDOW_FOCUS_WAIT_MS = 2_000L

/** Frames the open-time scroll and focus are tried for before giving up. */
private const val FOCUS_TRIES = 30
