package com.tmplayer.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Typography
import androidx.tv.material3.darkColorScheme

val Background = Color(0xFF0E0E12)
val SurfaceDark = Color(0xFF17171E)
val SurfaceRaised = Color(0xFF23232D)
val Accent = Color(0xFF2AABEE)
val TextPrimary = Color(0xFFEDEDF2)
val TextMuted = Color(0xFF9A9AA5)

/** Amber: worth noticing, nothing has gone wrong. A newer version being out is the whole use. */
val Caution = Color(0xFFF5A524)

private val colors = darkColorScheme(
    primary = Accent,
    onPrimary = Color.White,
    background = Background,
    onBackground = TextPrimary,
    surface = SurfaceDark,
    onSurface = TextPrimary,
    surfaceVariant = SurfaceRaised,
    onSurfaceVariant = TextPrimary,
    // TV Material paints a filled Button with `onSurface` and labels it with `inverseOnSurface`.
    // Leaving these at their defaults is what put white text on a white pill.
    inverseSurface = TextPrimary,
    inverseOnSurface = Background,
)

/**
 * 10-foot UI, sized against the screen this actually runs on.
 *
 * A 1080p TV at density 320 is only 960 x 540 dp, far less room than the pixel count suggests.
 * Type is large enough to read from a sofa but small enough that a heading plus a search row does
 * not consume half the height and push content off the bottom edge.
 *
 * No style carries a colour. A colour baked in here wins over [LocalContentColor], which is how
 * a button ends up painting its label in the surface colour instead of its own content colour.
 * Anything that wants muted text asks for [TextMuted] explicitly at the call site.
 */
private val typography = Typography(
    headlineLarge = TextStyle(fontSize = 32.sp, fontWeight = FontWeight.Bold),
    headlineMedium = TextStyle(fontSize = 26.sp, fontWeight = FontWeight.SemiBold),
    titleLarge = TextStyle(fontSize = 21.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.Medium),
    bodyLarge = TextStyle(fontSize = 15.sp),
    bodyMedium = TextStyle(fontSize = 13.sp),
    // Defined rather than left to inherit. TV Material's default bodySmall is 12sp, a desk size,
    // and the two places that use it carry the resume position on a Continue card and the
    // "nothing is deleted from Telegram" reassurance in every destructive prompt, so the least
    // readable text on those screens was the text that mattered most.
    bodySmall = TextStyle(fontSize = 13.sp),
    labelLarge = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Medium),
)

/**
 * Overscan margin. Televisions crop the outermost few percent of the panel, so anything drawn
 * closer than this to an edge may simply not exist as far as the viewer is concerned.
 */
object Tv {
    val SafeH = 32.dp
    val SafeV = 20.dp
}

/**
 * Buttons the user can actually read: a calm raised surface at rest, accent when focused.
 *
 * The TV Material default is a near-white pill in every state, which is far louder than this
 * app wants and gives the remote no strong signal about where focus currently is.
 */
@Composable
fun tmButtonColors() = ButtonDefaults.colors(
    containerColor = SurfaceRaised,
    contentColor = TextPrimary,
    focusedContainerColor = Accent,
    focusedContentColor = Color.White,
    pressedContainerColor = Accent,
    pressedContentColor = Color.White,
    disabledContainerColor = SurfaceDark,
    disabledContentColor = TextMuted,
)

@Composable
fun TMPlayerTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = colors,
        typography = typography,
    ) {
        // Without this the default content colour is undefined at the root of the tree and every
        // unstyled Text inherits whatever Compose falls back to.
        CompositionLocalProvider(LocalContentColor provides TextPrimary, content = content)
    }
}
