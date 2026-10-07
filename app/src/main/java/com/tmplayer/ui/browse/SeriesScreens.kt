package com.tmplayer.ui.browse

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme as M3MaterialTheme
import androidx.compose.material3.Text as M3Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.tmplayer.data.MediaItem
import com.tmplayer.data.Series
import com.tmplayer.data.SeriesProgress
import com.tmplayer.ui.components.PhoneSheet
import com.tmplayer.ui.components.isTouch
import com.tmplayer.ui.components.keepScrollInSheet
import com.tmplayer.ui.components.pressable
import com.tmplayer.ui.theme.Corner
import com.tmplayer.ui.theme.Tone
import com.tmplayer.ui.theme.Tv

/**
 * A show's tile in a chat's grid, in the same three dresses a video's tile wears: the phone's dense
 * cell, the phone's card, and the television's panel with its focus border and scale.
 */
@Composable
internal fun SeriesCard(
    series: Series,
    progress: SeriesProgress,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    dense: Boolean = false,
    onFocused: () -> Unit = {},
) {
    val interactions = remember { MutableInteractionSource() }
    val focused by interactions.collectIsFocusedAsState()
    val border by animateColorAsState(
        targetValue = if (focused) Tone.accent else Color.Transparent,
        animationSpec = tween(140),
        label = "seriesBorder",
    )
    LaunchedEffect(focused) { if (focused) onFocused() }
    val summary = listOfNotNull(seriesSummary(series), seriesProgressLine(progress)).joinToString("  ·  ")

    if (dense) {
        Column(
            modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(Corner.Small))
                .clickable(onClick = onClick)
                .padding(4.dp),
        ) {
            SeriesArt(series, progress, Modifier.fillMaxWidth(), compact = true)
            Spacer(Modifier.height(6.dp))
            M3Text(
                series.title,
                style = M3MaterialTheme.typography.bodySmall,
                color = Tone.text,
                maxLines = 2,
                minLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            // A third of a phone has room for the counts only; the bar on the picture says how far.
            M3Text(
                seriesSummary(series),
                style = M3MaterialTheme.typography.labelSmall,
                color = if (progress.watched > 0) Tone.accent else Tone.muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        return
    }

    val touch = isTouch()
    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Corner.Medium))
            .background(if (focused) Tone.surfaceHigh else Tone.surface)
            .border(1.dp, Tone.outline, RoundedCornerShape(Corner.Medium))
            .border(3.dp, border, RoundedCornerShape(Corner.Medium))
            .pressable(interactions, onClick),
    ) {
        SeriesArt(series, progress, Modifier.fillMaxWidth())
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Text(
                series.title,
                style = MaterialTheme.typography.titleMedium,
                color = Tone.text,
                maxLines = 2,
                minLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                summary,
                style = MaterialTheme.typography.bodyMedium,
                color = if (progress.watched > 0) Tone.accent else Tone.muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** The same show as a full-width row, for the list arrangement. */
@Composable
internal fun SeriesListRow(
    series: Series,
    progress: SeriesProgress,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onFocused: () -> Unit = {},
) {
    val interactions = remember { MutableInteractionSource() }
    val focused by interactions.collectIsFocusedAsState()
    LaunchedEffect(focused) { if (focused) onFocused() }
    val touch = isTouch()
    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Corner.Medium))
            .background(if (focused) Tone.surfaceHigh else Tone.surface)
            .border(1.dp, Tone.outline, RoundedCornerShape(Corner.Medium))
            .border(3.dp, if (focused) Tone.accent else Color.Transparent, RoundedCornerShape(Corner.Medium))
            .pressable(interactions, onClick)
            .padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SeriesArt(
            series,
            progress,
            if (touch) Modifier.weight(0.4f) else Modifier.width(176.dp),
            compact = touch,
        )
        Column(Modifier.weight(if (touch) 0.6f else 1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                series.title,
                style = MaterialTheme.typography.titleLarge,
                color = Tone.text,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                listOfNotNull(seriesSummary(series), seriesProgressLine(progress)).joinToString("  ·  "),
                style = MaterialTheme.typography.bodyMedium,
                color = if (progress.watched > 0) Tone.accent else Tone.muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * A show, opened: a bottom sheet on a phone and a page of its own on a television. Choosing an
 * episode plays it and closes this, so Back from the player lands on the chat, not on the sheet.
 */
@Composable
internal fun SeriesOpened(
    series: Series,
    watch: SeriesWatch,
    onPlay: (MediaItem) -> Unit,
    onDismiss: () -> Unit,
    onLongClick: ((MediaItem) -> Unit)? = null,
) {
    if (isTouch()) {
        SeriesSheet(series, watch, onPlay, onDismiss, onLongClick)
    } else {
        TvSeriesPage(series, watch, onPlay, onDismiss)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SeriesSheet(
    series: Series,
    watch: SeriesWatch,
    onPlay: (MediaItem) -> Unit,
    onDismiss: () -> Unit,
    onLongClick: ((MediaItem) -> Unit)?,
) {
    // Opened all the way: half a sheet of episodes is two rows, and the list is the point.
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    // The detail sheet's fill, corner and hairline, so the two sheets a grid opens look alike. The
    // episodes keep their scrolling to themselves, so reaching the top of the list does not drag
    // the sheet; the header above them still pulls it down.
    PhoneSheet(onDismissRequest = onDismiss, sheetState = state) {
        SeriesPanel(
            series = series,
            watch = watch,
            onPlay = { onDismiss(); onPlay(it) },
            onLongClick = onLongClick,
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
            modifier = Modifier.fillMaxWidth().fillMaxHeight(0.92f).keepScrollInSheet(),
        )
    }
}

/**
 * The television's page: full screen, inside the overscan, with the play button focused first.
 * A window of its own, like [com.tmplayer.ui.components.TvMenu], so Back closes it and the grid
 * behind keeps the focus it had.
 */
@Composable
private fun TvSeriesPage(
    series: Series,
    watch: SeriesWatch,
    onPlay: (MediaItem) -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Box(Modifier.fillMaxSize().background(Tone.background)) {
            SeriesPanel(
                series = series,
                watch = watch,
                onPlay = { onDismiss(); onPlay(it) },
                contentPadding = PaddingValues(start = Tv.SafeH, end = Tv.SafeH, top = 4.dp, bottom = Tv.SafeV),
                modifier = Modifier.fillMaxSize().padding(top = Tv.SafeV + 8.dp),
            )
        }
    }
}
