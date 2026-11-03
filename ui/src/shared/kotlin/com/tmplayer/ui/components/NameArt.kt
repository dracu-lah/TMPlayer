package com.tmplayer.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFontFamilyResolver
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.toOffset
import kotlin.math.roundToInt
import com.tmplayer.data.NameColour
import com.tmplayer.ui.theme.Tone

/**
 * The stand-in a chat, a channel or an account wears while it has no picture, and where it has
 * none at all: its colour from [NameColour] with [com.tmplayer.data.NameInitial]'s letter on it.
 * A photo always wins, on every screen (HX30 a); this only ever fills the hole a missing one
 * leaves, which used to be a grey disc with a faint letter that read as a chat still loading.
 *
 * The same eight pairs and the same corner light as a file's [GeneratedCover], so a chat's circle
 * and a video's cover belong to one family, and the same hash as the web app's
 * `webapp/src/ui/colour.ts`, so a chat is the same colour in the browser.
 *
 * The letter is a slot because the television writes it with tv-material's `Text` and the phone
 * and the desktop with Material 3's; its colour is [NameArt.onLetter].
 */
@Composable
fun NameArtTile(name: String?, modifier: Modifier = Modifier, letter: @Composable () -> Unit) {
    val brush = NameArt.brushFor(name)
    Box(
        modifier
            .fillMaxSize()
            .background(brush)
            // A light falling from the top start corner: the colour has depth rather than reading
            // as a flat swatch, the same light the generated covers carry. Built once per size
            // rather than per frame, since a list of avatars on a stick draws it on every one.
            .drawWithCache {
                val glow = Brush.radialGradient(
                    listOf(Color.White.copy(alpha = GLOW_ALPHA), Color.Transparent),
                    center = Offset(size.width * 0.15f, size.height * 0.1f),
                    radius = maxOf(size.width, size.height) * 0.85f,
                )
                onDrawBehind { drawRect(glow) }
            },
        contentAlignment = Alignment.Center,
        content = { letter() },
    )
}

/**
 * [letter] painted in [style], centred, from a text layout every avatar on screen shares: one
 * node and no paragraph laid out per avatar. A `Text` was a node, a semantics entry and a fresh
 * layout for each row a held Down brought into a TV list (HX76), for a letter that repeats. The
 * letter is decoration (the chat's name is written beside it), so nothing is said for it.
 */
@Composable
fun DrawnLetter(letter: String, style: TextStyle, modifier: Modifier = Modifier) {
    val measurer = LetterLayouts.measurer(
        LocalFontFamilyResolver.current,
        LocalDensity.current,
        LocalLayoutDirection.current,
    )
    Spacer(
        modifier.fillMaxSize().drawWithCache {
            val layout = if (letter.isEmpty()) null else measurer.measure(letter, style)
            val at = layout?.let {
                Alignment.Center.align(
                    it.size,
                    IntSize(size.width.roundToInt(), size.height.roundToInt()),
                    layoutDirection,
                ).toOffset()
            }
            onDrawBehind { if (layout != null && at != null) drawText(layout, topLeft = at) }
        },
    )
}

/**
 * The one [TextMeasurer] [DrawnLetter] lays its letters out with, made again only when the fonts,
 * the density or the direction change. Its cache holds a few dozen letters, which is the whole
 * alphabet of a screen. Touched only from the UI thread, as composition and drawing are.
 */
private object LetterLayouts {
    private var fonts: FontFamily.Resolver? = null
    private var density: Density? = null
    private var direction: LayoutDirection? = null
    private var current: TextMeasurer? = null

    fun measurer(fonts: FontFamily.Resolver, density: Density, direction: LayoutDirection): TextMeasurer {
        val kept = current
        if (kept != null && fonts == this.fonts && density == this.density && direction == this.direction) return kept
        this.fonts = fonts
        this.density = density
        this.direction = direction
        return TextMeasurer(fonts, density, direction, cacheSize = LETTERS_KEPT).also { current = it }
    }
}

/** Letters [LetterLayouts] keeps laid out: an alphabet, digits and a few others. */
private const val LETTERS_KEPT = 64

/** The colours of [NameArtTile], for a caller that paints its own frame. */
object NameArt {

    /** White: the colour tile is dark in either theme, as a picture is. */
    val onLetter: Color
        @Composable get() = Tone.onArt

    /** The gradient [name] wears, worked out once for as long as the name is the same. */
    @Composable
    fun brushFor(name: String?): Brush = remember(name) {
        val (light, deep) = NameColour.pairOf(name)
        Brush.linearGradient(listOf(Color(light), Color(deep)))
    }
}

/** How strong the corner light is: enough for depth, never a bright spot. */
private const val GLOW_ALPHA = 0.10f
