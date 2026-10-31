package com.tmplayer.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
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
