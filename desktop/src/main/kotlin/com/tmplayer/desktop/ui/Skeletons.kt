package com.tmplayer.desktop.ui

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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.tmplayer.ui.components.Shimmer
import com.tmplayer.ui.components.SkeletonBox
import com.tmplayer.ui.components.rememberShimmer
import com.tmplayer.ui.components.rememberSkeletonLabel
import com.tmplayer.ui.components.skeletonSemantics
import com.tmplayer.ui.theme.Avatar

/**
 * Lines of [style] as grey bars, exactly as tall as the text they stand for (a line height each),
 * so the real words take their place without moving anything under them.
 */
@Composable
internal fun SkeletonText(
    shimmer: Shimmer,
    style: TextStyle,
    modifier: Modifier = Modifier,
    lines: Int = 1,
    fraction: Float = 1f,
) {
    val line = with(LocalDensity.current) { style.lineHeight.toDp() }
    Column(modifier) {
        repeat(lines) { index ->
            Box(Modifier.fillMaxWidth().height(line), contentAlignment = Alignment.CenterStart) {
                // The last of several lines runs short, as a wrapped name does.
                val share = if (lines > 1 && index == lines - 1) fraction * 0.6f else fraction
                SkeletonBox(shimmer, Modifier.fillMaxWidth(share).height(line * 0.62f), RoundedCornerShape(4.dp))
            }
        }
    }
}

/**
 * Stand-in for a chat's grid while its videos load: the [VideoGrid]'s own columns, padding and
 * gaps, and cards shaped as [Poster] draws them, 2:3 with two lines of name for a chat of films
 * and shows, 16:9 with one otherwise, so the posters land where their placeholders stood.
 */
@Composable
internal fun VideoGridSkeleton(
    /** The grid's own [GridCells.Adaptive] minimum, so the columns match. */
    cellWidth: Dp,
    posters: Boolean,
    modifier: Modifier = Modifier,
    /** False for cards that write their name on the art (Continue watching): the facts line only. */
    nameBelow: Boolean = true,
    /** What loads, for a screen reader; the page's own label (StateBox) where null. */
    label: String? = null,
) {
    val shimmer = rememberShimmer()
    val words = rememberSkeletonLabel(label)
    LazyVerticalGrid(
        columns = GridCells.Adaptive(cellWidth),
        contentPadding = PaddingValues(start = 24.dp, end = 32.dp, top = 8.dp, bottom = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
        userScrollEnabled = false,
        modifier = modifier.fillMaxSize().skeletonSemantics(words),
    ) {
        items(SKELETON_CARDS) { index -> PosterSkeleton(shimmer, posters, index, nameBelow) }
    }
}

/** One [Poster] as placeholders: the art, the name and the line of facts under it. */
@Composable
internal fun PosterSkeleton(shimmer: Shimmer, poster: Boolean, index: Int = 0, nameBelow: Boolean = true, modifier: Modifier = Modifier) {
    val type = MaterialTheme.typography
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        SkeletonBox(
            shimmer,
            Modifier.padding(bottom = 4.dp).fillMaxWidth().aspectRatio(if (poster) 2f / 3f else 16f / 9f),
            MaterialTheme.shapes.medium,
        )
        val name = NAME_FRACTIONS[index % NAME_FRACTIONS.size]
        if (poster) {
            if (nameBelow) SkeletonText(shimmer, type.bodyMedium, lines = 2, fraction = name)
            SkeletonText(shimmer, type.labelSmall, fraction = 0.5f)
        } else {
            if (nameBelow) SkeletonText(shimmer, type.titleSmall, fraction = name)
            SkeletonText(shimmer, type.labelMedium, fraction = 0.45f)
        }
    }
}

/**
 * Stand-in for the chat list: rows of the [ChatRow]'s height, an avatar, a name and the kind of
 * chat under it, with the star button's room at the end.
 */
@Composable
internal fun ChatListSkeleton(modifier: Modifier = Modifier, label: String? = null) {
    val shimmer = rememberShimmer()
    val type = MaterialTheme.typography
    val words = rememberSkeletonLabel(label)
    LazyColumn(
        modifier = modifier.fillMaxSize().skeletonSemantics(words).padding(end = 12.dp),
        contentPadding = PaddingValues(horizontal = pageMargin() - 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
        userScrollEnabled = false,
    ) {
        items(SKELETON_ROWS) { index ->
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                SkeletonBox(shimmer, Modifier.size(Avatar.Compact + 8.dp), CircleShape)
                Column(Modifier.weight(1f)) {
                    SkeletonText(shimmer, type.titleMedium, fraction = NAME_FRACTIONS[index % NAME_FRACTIONS.size] * 0.6f)
                    SkeletonText(shimmer, type.bodySmall, fraction = 0.18f)
                }
                // The star button's 48 dp, so the row is as tall as a real one.
                Spacer(Modifier.width(48.dp).height(48.dp))
            }
        }
    }
}

/** Two [ChatListSkeleton] rows, at the chat list's foot while its older chats are read. */
@Composable
internal fun MoreChatsSkeleton(label: String? = null) {
    val shimmer = rememberShimmer()
    val type = MaterialTheme.typography
    val words = rememberSkeletonLabel(label)
    Column(Modifier.skeletonSemantics(words), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        repeat(2) { index ->
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                SkeletonBox(shimmer, Modifier.size(Avatar.Compact + 8.dp), CircleShape)
                Column(Modifier.weight(1f)) {
                    SkeletonText(shimmer, type.titleMedium, fraction = NAME_FRACTIONS[index % NAME_FRACTIONS.size] * 0.6f)
                    SkeletonText(shimmer, type.bodySmall, fraction = 0.18f)
                }
                Spacer(Modifier.width(48.dp).height(48.dp))
            }
        }
    }
}

/** The page header's line under the title while what it counts is still loading. */
@Composable
internal fun SubtitleSkeleton(modifier: Modifier = Modifier, label: String? = null) {
    val shimmer = rememberShimmer()
    val words = rememberSkeletonLabel(label)
    SkeletonText(shimmer, MaterialTheme.typography.bodyMedium, modifier.width(96.dp).skeletonSemantics(words))
}

/** Enough to fill a tall window; the grid draws only the ones that are in sight. */
private const val SKELETON_CARDS = 36
private const val SKELETON_ROWS = 16

/** Names differ in length; bars all one width read as a barcode rather than a shelf. */
private val NAME_FRACTIONS = listOf(0.86f, 0.62f, 0.94f, 0.7f, 0.8f, 0.55f, 0.9f)
