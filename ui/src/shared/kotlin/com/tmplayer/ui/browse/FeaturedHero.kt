package com.tmplayer.ui.browse

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.tmplayer.data.FeaturedPick
import com.tmplayer.data.SeriesShelf
import com.tmplayer.i18n.Messages
import com.tmplayer.online.MetaExtras
import com.tmplayer.online.MetaInfo
import com.tmplayer.ui.i18n.LocalStrings
import com.tmplayer.ui.online.MetaScoresRow
import com.tmplayer.ui.online.rememberMetaExtras
import com.tmplayer.ui.theme.Motion
import com.tmplayer.ui.theme.Tone
import com.tmplayer.ui.theme.rememberReducedMotion

/*
 * What the Home carousel says under a page's name on every device, after Reelbox's hero: a line of
 * facts (Film or Series, the year, two genres, the length or the seasons), the first few sites'
 * scores with their marks, and a few lines of the overview, and the row of dots that says which
 * page is up.
 */

/**
 * "Film  ·  2026  ·  Drama, Thriller  ·  2h 02m", or "Series  ·  2024  ·  Crime  ·  3 seasons":
 * the kind, the year (the provider's, else the name's), up to two genres and a film's length or a
 * show's seasons where the provider's [extras] know them. A film with no answer still has its
 * length from Telegram.
 */
fun featuredFactsLine(pick: FeaturedPick, meta: MetaInfo?, extras: MetaExtras?, s: Messages): String {
    val series = pick.series
    val item = pick.item
    val year = meta?.year ?: SeriesShelf.episodeOf(item).year
    val genres = extras?.genres?.take(2)?.joinToString(", ")?.ifBlank { null }
    val length = if (series != null) {
        (extras?.seasons ?: series.seasons.count { it.number > 0 }.takeIf { it > 1 })?.let { s.metadataSeasons(it) }
    } else {
        (extras?.runtimeMin?.let { it * 60L } ?: item.durationSec.toLong().takeIf { it > 0 })?.let { s.formatter.duration(it) }
    }
    return listOfNotNull(
        if (pick.isShow) s.homeFeaturedSeries else s.homeFeaturedFilm,
        year?.toString(),
        genres,
        length,
    ).joinToString(FACTS_SEPARATOR)
}

/**
 * The facts line and, where the provider has one, the overview cut to [overviewLines] lines, under
 * a carousel page's name. The words stand on the page's own ground, so they take the page's
 * text colours in either theme. The extras are asked for here, once per title, and kept with the
 * rest of the answers; until they arrive the line has what the name and Telegram say.
 */
@Composable
fun FeaturedInfo(
    pick: FeaturedPick,
    meta: MetaInfo?,
    modifier: Modifier = Modifier,
    overviewLines: Int = 3,
    textAlign: TextAlign = TextAlign.Start,
) {
    val s = LocalStrings.current
    val extras = rememberMetaExtras(meta)
    val facts = remember(pick, meta, extras, s) { featuredFactsLine(pick, meta, extras, s) }
    val overview = meta?.overview?.trim().orEmpty()
    Column(
        modifier,
        verticalArrangement = Arrangement.spacedBy(6.dp),
        horizontalAlignment = when (textAlign) {
            TextAlign.Center -> Alignment.CenterHorizontally
            else -> Alignment.Start
        },
    ) {
        Text(
            facts,
            style = MaterialTheme.typography.bodyMedium,
            color = Tone.text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = textAlign,
        )
        // The first three sites' scores, on one line, each with its mark.
        extras?.let { MetaScoresRow(it, maxLines = 1, limit = 3, centered = textAlign == TextAlign.Center) }
        if (overview.isNotEmpty() && overviewLines > 0) {
            Text(
                overview,
                style = MaterialTheme.typography.bodyMedium,
                color = Tone.muted,
                maxLines = overviewLines,
                overflow = TextOverflow.Ellipsis,
                textAlign = textAlign,
            )
        }
    }
}

/**
 * Which page is up, as Reelbox draws it: the page up a short pill, the others small dots, the pill
 * sliding from one to the next as the pages turn. With [onPick] each mark is a button for its page
 * (a pointer's click, and the keyboard on a desktop where [focusable]); without it the row is only
 * a picture, the page's own description saying which page it is.
 */
@Composable
fun CarouselDots(
    count: Int,
    current: Int,
    modifier: Modifier = Modifier,
    onPick: ((Int) -> Unit)? = null,
    focusable: Boolean = true,
) {
    if (count < 2) return
    val s = LocalStrings.current
    Row(
        modifier.then(if (onPick == null) Modifier.clearAndSetSemantics { } else Modifier),
        horizontalArrangement = Arrangement.spacedBy(0.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        for (page in 0 until count) {
            Dot(
                on = page == current,
                label = s.homeFeaturedPage(page + 1, count),
                onClick = onPick?.let { pick -> { pick(page) } },
                focusable = focusable,
            )
        }
    }
}

@Composable
private fun Dot(on: Boolean, label: String, onClick: (() -> Unit)?, focusable: Boolean) {
    val still = rememberReducedMotion() || Motion.reduced
    val interactions = remember { MutableInteractionSource() }
    val focused by interactions.collectIsFocusedAsState()
    val width by animateDpAsState(
        if (on) PILL_WIDTH else DOT_SIZE,
        Motion.standard(if (still) 0 else Motion.Slow),
        label = "dotWidth",
    )
    val ink by animateColorAsState(
        when {
            on -> Tone.text
            focused -> Tone.text.copy(alpha = 0.7f)
            else -> Tone.text.copy(alpha = OFF_ALPHA)
        },
        Motion.standard(Motion.Fast),
        label = "dotInk",
    )
    Box(
        Modifier
            // The mark is small; the target round it is not.
            .then(
                if (onClick == null) {
                    Modifier
                } else {
                    Modifier
                        .focusProperties { canFocus = focusable }
                        .clip(CircleShape)
                        .clickable(interactionSource = interactions, indication = null, role = Role.Button, onClick = onClick)
                        .semantics {
                            contentDescription = label
                            selected = on
                            role = Role.Button
                        }
                },
            )
            .padding(horizontal = DOT_GAP / 2, vertical = TARGET_PAD),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .width(width)
                .height(DOT_SIZE)
                .clip(CircleShape)
                .background(ink),
        )
    }
}

private const val FACTS_SEPARATOR = "  ·  "

/** A page not up: a quiet mark, still visible over a picture's fade. */
private const val OFF_ALPHA = 0.32f

private val DOT_SIZE = 6.dp
private val PILL_WIDTH = 24.dp
private val DOT_GAP = 6.dp

/** Above and below each mark, so a pointer or a finger has something to hit. */
private val TARGET_PAD = 8.dp
