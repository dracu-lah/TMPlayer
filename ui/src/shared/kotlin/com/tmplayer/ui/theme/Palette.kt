package com.tmplayer.ui.theme

import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/*
 * The app's palette, shapes and touch type scale: everything about the look that is the same on a
 * phone, a television and a desktop. Everything asks [Tone] for a role, and [Tone] answers out of
 * the one Material scheme below. The television's tv-material theme is built from these in :app.
 */

/** Amber: worth noticing, nothing has gone wrong. A newer version being out is the whole use. */
val Caution = Color(0xFFF5A524)

/**
 * Red: something failed, or the press about to happen cannot be taken back.
 *
 * Lives here rather than beside each use so there is a single literal for it.
 */
val Danger = Color(0xFFE5484D)

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
 * The list size is Material's two-line list item figure, which is what Telegram and the dialler
 * put there.
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
 * Five steps, named by what they are for. Do not add a sixth: radii a couple of dp apart read as
 * two things that should match but do not.
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
 * True when the tree being drawn is in the dark theme.
 *
 * Read at the point of use rather than passed down, because nearly every composable in the app
 * needs it and half of them are shared with the television.
 */
val LocalDarkTheme = staticCompositionLocalOf { true }

/**
 * A ring around whatever the remote is on, for the theme where the fill alone is not enough.
 *
 * In daylight [Tone.focusFill] is a pale blue, a 1.24:1 step on near-white and well under the 3:1
 * a control is meant to stand out by, so an outline in the accent carries the signal the fill
 * cannot. In the dark theme the fill is already a bright block against a near-black screen, and a
 * ring in the same colour would be invisible, so there is none.
 */
@Composable
fun Modifier.focusRing(focused: Boolean, shape: Shape): Modifier =
    if (focused && !LocalDarkTheme.current) {
        border(width = 2.dp, color = Tone.accent, shape = shape)
    } else {
        this
    }

/** The app's palette as complete Material 3 schemes, one per mode. */
object TmSchemes {

    /**
     * Dark.
     *
     * Every role Material components read is set, so no card, chip or sheet falls back to
     * Compose's lavender defaults. The hue is Telegram's, because that is what the app is: a
     * Telegram client. Tone and chroma are Material's, so a Material component drawn with it looks
     * like one.
     */
    val Dark: ColorScheme = darkColorScheme(
        primary = Color(0xFF79D0F5),
        onPrimary = Color(0xFF003546),
        primaryContainer = Color(0xFF004D64),
        onPrimaryContainer = Color(0xFFBEE9FF),
        inversePrimary = Color(0xFF00677F),
        secondary = Color(0xFFB4CAD6),
        onSecondary = Color(0xFF1F333C),
        secondaryContainer = Color(0xFF354A53),
        onSecondaryContainer = Color(0xFFD0E6F2),
        tertiary = Color(0xFFC6C2EA),
        onTertiary = Color(0xFF2F2D4D),
        tertiaryContainer = Color(0xFF464364),
        onTertiaryContainer = Color(0xFFE3DFFF),
        error = Color(0xFFFFB4AB),
        onError = Color(0xFF690005),
        errorContainer = Color(0xFF93000A),
        onErrorContainer = Color(0xFFFFDAD6),
        background = Color(0xFF0F1417),
        onBackground = Color(0xFFDEE3E7),
        surface = Color(0xFF0F1417),
        onSurface = Color(0xFFDEE3E7),
        surfaceVariant = Color(0xFF40484C),
        onSurfaceVariant = Color(0xFFC0C8CD),
        surfaceContainerLowest = Color(0xFF0A0F12),
        surfaceContainerLow = Color(0xFF171C1F),
        surfaceContainer = Color(0xFF1B2124),
        surfaceContainerHigh = Color(0xFF262B2E),
        surfaceContainerHighest = Color(0xFF313539),
        surfaceBright = Color(0xFF353A3D),
        surfaceDim = Color(0xFF0F1417),
        outline = Color(0xFF8A9297),
        outlineVariant = Color(0xFF40484C),
        inverseSurface = Color(0xFFDEE3E7),
        inverseOnSurface = Color(0xFF2B3134),
        scrim = Color(0xFF000000),
    )

    /** The same scheme in daylight. */
    val Light: ColorScheme = lightColorScheme(
        primary = Color(0xFF00677F),
        onPrimary = Color(0xFFFFFFFF),
        primaryContainer = Color(0xFFB6EAFF),
        onPrimaryContainer = Color(0xFF001F28),
        inversePrimary = Color(0xFF79D0F5),
        secondary = Color(0xFF4C616B),
        onSecondary = Color(0xFFFFFFFF),
        secondaryContainer = Color(0xFFCFE6F1),
        onSecondaryContainer = Color(0xFF071E26),
        tertiary = Color(0xFF5D5B7D),
        onTertiary = Color(0xFFFFFFFF),
        tertiaryContainer = Color(0xFFE3DFFF),
        onTertiaryContainer = Color(0xFF1A1836),
        error = Color(0xFFBA1A1A),
        onError = Color(0xFFFFFFFF),
        errorContainer = Color(0xFFFFDAD6),
        onErrorContainer = Color(0xFF410002),
        background = Color(0xFFF6FAFD),
        onBackground = Color(0xFF171C1F),
        surface = Color(0xFFF6FAFD),
        onSurface = Color(0xFF171C1F),
        surfaceVariant = Color(0xFFDCE4E9),
        onSurfaceVariant = Color(0xFF40484C),
        surfaceContainerLowest = Color(0xFFFFFFFF),
        surfaceContainerLow = Color(0xFFF0F4F8),
        surfaceContainer = Color(0xFFEAEEF2),
        surfaceContainerHigh = Color(0xFFE4E9EC),
        surfaceContainerHighest = Color(0xFFDEE3E7),
        surfaceBright = Color(0xFFF6FAFD),
        surfaceDim = Color(0xFFD6DBDF),
        outline = Color(0xFF70787D),
        outlineVariant = Color(0xFFC0C8CD),
        inverseSurface = Color(0xFF2B3134),
        inverseOnSurface = Color(0xFFECF1F5),
        scrim = Color(0xFF000000),
    )

    fun of(dark: Boolean): ColorScheme = if (dark) Dark else Light
}

/** Material's own shape scale, wired to [Corner] so a stock component and a hand-drawn one agree. */
val TmShapes = Shapes(
    extraSmall = RoundedCornerShape(Corner.ExtraSmall),
    small = RoundedCornerShape(Corner.Small),
    medium = RoundedCornerShape(Corner.Medium),
    large = RoundedCornerShape(Corner.Large),
    extraLarge = RoundedCornerShape(Corner.ExtraLarge),
)

/**
 * Material 3's own sizes for a device held in the hand or sat at a desk, written out so the stock
 * components read them. No style carries a colour: a colour baked in here would win over the
 * content colour, so a button would paint its label in the surface colour.
 *
 * The television's tv-material theme in :app carries the same figures in its own `Typography`
 * class, since neither theme system will take the other's. Keep the two in step.
 */
val TmTouchTypography = Typography(
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
 * The Material 3 theme alone, for a tree that has no television half: the desktop app.
 *
 * The Android app keeps its own entry point, which adds the tv-material theme beside this one and
 * takes the phone's colours from the wallpaper when asked to.
 */
@Composable
fun TmMaterialTheme(
    dark: Boolean,
    scheme: ColorScheme = TmSchemes.of(dark),
    content: @Composable () -> Unit,
) {
    MaterialTheme(colorScheme = scheme, shapes = TmShapes, typography = TmTouchTypography) {
        CompositionLocalProvider(
            LocalContentColor provides scheme.onSurface,
            LocalDarkTheme provides dark,
            content = content,
        )
    }
}
