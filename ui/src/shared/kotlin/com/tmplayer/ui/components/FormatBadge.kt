package com.tmplayer.ui.components

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.tmplayer.data.FormatFacts
import com.tmplayer.data.FormatProbes
import com.tmplayer.data.MediaItem
import com.tmplayer.ui.i18n.LocalStrings
import com.tmplayer.ui.icons.TmIcons
import com.tmplayer.ui.theme.Corner
import com.tmplayer.ui.theme.Motion
import com.tmplayer.ui.theme.Tone

/*
 * Format badges (T8): the plain text pills that say what a file declares about its picture and
 * sound, "4K", "1080p", "HDR10", "Dolby Vision", "Atmos", "5.1". Our own words in our own shape.
 * No brand logo: Dolby, DTS, HDR10+ and "4K Ultra HD" are licensed marks, so none is drawn or
 * bundled, only the generic speaker on sound pills.
 *
 * What a badge says comes from core's [FormatFacts]; this file only draws it. A badge is not a
 * control: it takes no focus, no press and no ripple, so a tile or a row that wears one keeps its
 * own single focus stop, and the remote never lands on a label.
 */

/**
 * One pill. [onArt] is the look for a picture (a poster's corner): the tile's black plate with
 * white text, readable on any thumbnail, the same as [TileBadge]. Off art it is an outline on the
 * page, as the facts line has always worn the quality.
 *
 * A badge read from the file name ([FormatFacts.Tag.fromName]) is drawn hollow and quieter: a
 * word an uploader typed, not a fact the file declares.
 *
 * One line at least [TileBadgeHeight] tall on art, and taller when the font is scaled up, never
 * clipped or wrapped. A sound pill carries a small speaker when [icons] is on.
 */
@Composable
fun FormatBadge(
    tag: FormatFacts.Tag,
    modifier: Modifier = Modifier,
    onArt: Boolean = false,
    icons: Boolean = !onArt,
    /** The pill's own colour where the page's text colour would not read (a focused row's fill); null is the page's. */
    ink: Color? = null,
) {
    val hollow = tag.fromName
    val shape = if (onArt) CircleShape else RoundedCornerShape(Corner.ExtraSmall)
    val tone = ink ?: when {
        onArt -> Color.White
        hollow -> Tone.muted
        else -> Tone.text
    }
    val frame = when {
        onArt && hollow -> Modifier.background(TileBadgeFill.copy(alpha = 0.36f), shape).border(1.dp, Color.White.copy(alpha = 0.6f), shape)
        onArt -> Modifier.background(TileBadgeFill, shape)
        else -> Modifier.border(1.dp, tone.copy(alpha = if (hollow) 0.35f else 0.6f), shape)
    }
    val speaker = icons && tag.kind == FormatFacts.Kind.Audio
    Row(
        modifier
            .then(if (onArt) Modifier.heightIn(min = TileBadgeHeight) else Modifier)
            .then(frame)
            .padding(
                start = if (speaker) 5.dp else if (onArt) 8.dp else 6.dp,
                end = if (onArt) 8.dp else 6.dp,
                top = if (onArt) 0.dp else 1.dp,
                bottom = if (onArt) 0.dp else 1.dp,
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (speaker) Icon(TmIcons.VolumeUp, contentDescription = null, tint = tone, modifier = Modifier.size(12.dp))
        Text(
            // Stereo is the one tag that is a word, shown in the viewer's language; the rest read the same everywhere.
            if (tag.text == FormatFacts.STEREO) LocalStrings.current.tracksStereo else tag.text,
            style = if (onArt) MaterialTheme.typography.labelSmall else MaterialTheme.typography.labelMedium,
            color = tone,
            maxLines = 1,
            overflow = TextOverflow.Clip,
        )
    }
}

/**
 * The pills of one video in a row that wraps a whole pill at a time, with nothing drawn for an
 * empty list (no gap, no placeholder). A pill that arrives later (a played video's probe) slides
 * the row's height in with the standard motion rather than jumping.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FormatBadgeRow(
    tags: List<FormatFacts.Tag>,
    modifier: Modifier = Modifier,
    ink: Color? = null,
) {
    if (tags.isEmpty()) return
    FlowRow(
        modifier.animateContentSize(Motion.standard(Motion.Fast)),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        tags.forEach { FormatBadge(it, ink = ink) }
    }
}

/**
 * The badges of [item]: its size's resolution at once, and what a player saw in its tracks
 * ([FormatProbes]) once one has played it, looked at again when a probe is stored. Never asks
 * TDLib or the disk; empty when nothing is known.
 */
@Composable
fun rememberFormatTags(item: MediaItem): List<FormatFacts.Tag> {
    val version by FormatProbes.changes.collectAsState()
    return remember(item.id, item.width, item.height, item.fileName, version) { FormatFacts.rowFor(item, FormatProbes[item.id]) }
}
