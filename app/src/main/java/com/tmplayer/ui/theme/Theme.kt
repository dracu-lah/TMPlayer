package com.tmplayer.ui.theme

import androidx.compose.material3.MaterialTheme as M3MaterialTheme
import androidx.compose.material3.darkColorScheme as M3ColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Typography
import androidx.tv.material3.darkColorScheme
import com.tmplayer.data.FormFactor

val Background = Color(0xFF0E0E12)
val SurfaceDark = Color(0xFF17171E)
val SurfaceRaised = Color(0xFF23232D)
val Accent = Color(0xFF2AABEE)
val TextPrimary = Color(0xFFEDEDF2)
val TextMuted = Color(0xFF9A9AA5)

/** Amber: worth noticing, nothing has gone wrong. A newer version being out is the whole use. */
val Caution = Color(0xFFF5A524)

/**
 * Red: something failed, or the press about to happen cannot be taken back.
 *
 * Lives here rather than beside each use because four files had grown their own copy of the same
 * literal, and once one of them widened its visibility the rest stopped compiling.
 */
val Danger = Color(0xFFE5484D)

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
 * The three sizes a chat's picture is ever drawn at.
 *
 * There were five, none of them the same as each other and none of them a Material figure: 54 in
 * the phone list, 40 on the television's rail, 32 in the drawer's footer, 64 on a card. The list
 * one is the one that matters, and 56 is what Material's two-line list item is drawn with, which
 * is what Telegram and the dialler put there.
 */
object Avatar {
    /** A row in a list of chats. */
    val List = 56.dp

    /** Beside a name in an app bar, a rail or a footer, where the picture is a label. */
    val Compact = 40.dp

    /** The television's chat card, where the picture is the card. */
    val Card = 64.dp
}

/**
 * The corner radii the app is allowed to use, which is Material's shape scale and nothing else.
 *
 * There were nine of them in the source at one point, several a couple of dp apart: 14 next to 16
 * next to 20, chosen a screen at a time. Nobody can see two dp, but everybody can see that two
 * things which should match do not, and reading a number off a neighbouring file is how a scale
 * turns into a list. Five steps, named by what they are for.
 *
 * Anything genuinely round (a pill, a track, a rule, an avatar) uses `CircleShape` instead. Its
 * radius is half its own height, so it is geometry rather than taste and does not belong here.
 */
object Corner {
    /** Badges and small markers drawn over art. */
    val ExtraSmall = 4.dp

    /** Thumbnails and inline artwork. */
    val Small = 8.dp

    /** Text fields, list rows and tiles. */
    val Medium = 12.dp

    /** Cards and the panels inside a screen. */
    val Large = 16.dp

    /** Dialogs and sheets, which sit above everything else and are shaped to say so. */
    val ExtraLarge = 28.dp
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

/**
 * The same roles at the sizes Material 3 ships for a device held in the hand.
 *
 * This is the single change that stops the phone build reading as a prototype. Every screen inside
 * the pane was drawing at the 10-foot scale: a chat row's title came out at 21sp semibold over a
 * 13sp subtitle, where a phone expects 16sp medium over 14sp. Nothing about the layouts was wrong
 * so much as the type was three sizes too big for the device, and the rest of the redesign is
 * unreadable against a scale that is fighting it.
 *
 * The values are Material 3's own defaults for these roles, written out rather than inherited
 * because the roles here belong to `androidx.tv.material3.Typography`, whose defaults are the
 * television's. No style carries a colour, for the same reason the television's does not.
 */
private val phoneTypography = Typography(
    headlineLarge = TextStyle(fontSize = 32.sp, lineHeight = 40.sp),
    headlineMedium = TextStyle(fontSize = 28.sp, lineHeight = 36.sp),
    headlineSmall = TextStyle(fontSize = 24.sp, lineHeight = 32.sp),
    titleLarge = TextStyle(fontSize = 22.sp, lineHeight = 28.sp),
    titleMedium = TextStyle(fontSize = 16.sp, lineHeight = 24.sp, fontWeight = FontWeight.Medium),
    titleSmall = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 16.sp),
    labelLarge = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium),
    labelMedium = TextStyle(fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium),
    labelSmall = TextStyle(fontSize = 11.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium),
)

/**
 * The app's palette as ordinary Material 3, for every stock component on the touch side.
 *
 * TV Material's controls are built around D-pad focus and never dispatch a tap, so the phone's
 * dialogs, sheets, switches, sliders and app bars all come from `androidx.compose.material3`. They
 * need the app's colours to be recognisably the same product as the television, which is what this
 * provides, once, at the root rather than at each screen that happens to use one.
 */
private val m3Colors = M3ColorScheme(
    primary = Accent,
    onPrimary = Color.White,
    background = Background,
    onBackground = TextPrimary,
    surface = SurfaceDark,
    onSurface = TextPrimary,
    surfaceVariant = SurfaceRaised,
    onSurfaceVariant = TextPrimary,
    surfaceContainerLow = SurfaceDark,
    surfaceContainerHigh = SurfaceRaised,
    error = Danger,
)

@Composable
fun TMPlayerTheme(content: @Composable () -> Unit) {
    val touch = !FormFactor.isTv(LocalContext.current)

    // Both themes, always, and the form factor decides only the type scale.
    //
    // Two theme systems are live in this app because the two devices genuinely need different
    // controls, and a screen that is shared between them ends up reading one theme for its text
    // and the other for its buttons. Providing both here means neither is ever missing, and it is
    // what lets the whole touch tree, including Settings, the sign-in and every dialog, move to
    // the phone scale without threading a theme through fourteen files by hand.
    M3MaterialTheme(colorScheme = m3Colors) {
        MaterialTheme(
            colorScheme = colors,
            typography = if (touch) phoneTypography else typography,
        ) {
            // Without this the default content colour is undefined at the root of the tree and
            // every unstyled Text inherits whatever Compose falls back to.
            CompositionLocalProvider(LocalContentColor provides TextPrimary, content = content)
        }
    }
}
