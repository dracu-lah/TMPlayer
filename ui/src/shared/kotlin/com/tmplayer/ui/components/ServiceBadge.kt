package com.tmplayer.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.tmplayer.data.NameInitial
import com.tmplayer.ui.theme.Tone

/** The tile sizes a screen picks from (HX93: 24 to 32 dp). */
object ServiceBadgeSize {
    /** A picker line's leading slot. */
    val Line = 24.dp

    /** A credit line, and the name beside the tile. */
    val Credit = 28.dp

    /** A settings row's chip. */
    val Row = 32.dp
}

/**
 * Where TMPlayer names an outside service, its logo: a rounded tile of [size] with the service's
 * own bundled mark on a white chip that reads on a dark theme, a light one and a television, or,
 * where the service offers no mark TMPlayer may use ([Service.logo] null), its initial in its
 * [com.tmplayer.data.NameColour]. With [showName] the name follows the tile, in the text colour.
 *
 * The tile is decoration. The name is read once by whatever sits beside it (the row's title, or
 * [showName]'s own text), so the tile has no description, and it never takes focus or a press: it
 * is drawn, not clicked, so a television's focus order is the row's own. The mark is not mirrored
 * for a right-to-left language, tinted, stretched or turned (the brand rules). It is a vector built
 * once per service, so a long list decodes nothing.
 */
@Composable
fun ServiceBadge(
    service: Service,
    modifier: Modifier = Modifier,
    size: Dp = ServiceBadgeSize.Credit,
    showName: Boolean = false,
    nameColor: Color = Tone.text,
) {
    if (!showName) {
        Tile(service, modifier, size)
        return
    }
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Tile(service, Modifier, size)
        Text(
            service.label,
            style = MaterialTheme.typography.bodyMedium,
            color = nameColor,
            modifier = Modifier.padding(start = 10.dp),
        )
    }
}

@Composable
private fun Tile(service: Service, modifier: Modifier, size: Dp) {
    val shape = RoundedCornerShape(size * CORNER)
    val logo = service.logo
    val frame = modifier
        .size(size)
        .clip(shape)
        // Decoration: the name beside it is the one the screen reader says.
        .clearAndSetSemantics {}
    if (logo != null) {
        Box(
            frame
                .background(LogoChip)
                .border(1.dp, Tone.outline, shape)
                .padding(size * LOGO_INSET),
            contentAlignment = Alignment.Center,
        ) {
            Image(logo, contentDescription = null, contentScale = ContentScale.Fit)
        }
    } else {
        Box(
            frame.background(NameArt.brushFor(service.label)),
            contentAlignment = Alignment.Center,
        ) {
            // The size of the letter follows the tile, not the font scale: a large font grows the
            // row's words, and a tile that grew with it would crowd the line.
            val letter = with(LocalDensity.current) { (size * LETTER).toSp() }
            Text(
                NameInitial.of(service.label) ?: service.label.take(1),
                color = NameArt.onLetter,
                fontSize = letter,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

/** White in both themes: a brand's own colours are drawn for white, and a dark theme still reads them. */
private val LogoChip = Color.White
private const val CORNER = 0.25f
private const val LOGO_INSET = 0.1f
private const val LETTER = 0.5f

/**
 * Several services named in one sentence (a consent dialog that says "TMDB, TVmaze and AniList"):
 * each as a [ServiceBadge] with its name, wrapping onto a second line at a large font or a narrow
 * width instead of running off the edge.
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun ServiceBadgeRow(
    services: List<Service>,
    modifier: Modifier = Modifier,
    size: Dp = ServiceBadgeSize.Line,
) {
    androidx.compose.foundation.layout.FlowRow(
        modifier,
        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(16.dp),
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp),
    ) {
        services.forEach { ServiceBadge(it, size = size, showName = true) }
    }
}
