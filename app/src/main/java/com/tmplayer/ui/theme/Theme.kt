package com.tmplayer.ui.theme

import android.os.Build
import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme as M3MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography as M3Typography
import androidx.compose.material3.darkColorScheme as M3DarkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme as M3LightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
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
import androidx.tv.material3.lightColorScheme as tvLightColorScheme
import com.tmplayer.data.FormFactor
import com.tmplayer.data.SettingsStore
import com.tmplayer.data.ThemeChoice

/*
 * Everything asks [Tone] for a role, and [Tone] answers out of the one Material scheme below.
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
 * The phone's scheme, handed to the television's Material, so both devices are the same app.
 *
 * TV Material's scheme carries a subset of the roles, so this is the mapping between them. It is
 * built per composition rather than declared, because the phone's scheme is itself a choice:
 * light, dark, and on the television neither is a constant.
 */
private fun tvColors(scheme: ColorScheme, dark: Boolean) = if (dark) {
    darkColorScheme(
        primary = scheme.primary,
        onPrimary = scheme.onPrimary,
        background = scheme.background,
        onBackground = scheme.onBackground,
        surface = scheme.surfaceContainerLow,
        onSurface = scheme.onSurface,
        surfaceVariant = scheme.surfaceContainerHigh,
        onSurfaceVariant = scheme.onSurface,
        // TV Material paints a filled Button with `onSurface` and labels it with
        // `inverseOnSurface`, so both must be set or the label is invisible on the pill.
        inverseSurface = scheme.inverseSurface,
        inverseOnSurface = scheme.inverseOnSurface,
        error = scheme.error,
    )
} else {
    tvLightColorScheme(
        primary = scheme.primary,
        onPrimary = scheme.onPrimary,
        background = scheme.background,
        onBackground = scheme.onBackground,
        surface = scheme.surfaceContainerLow,
        onSurface = scheme.onSurface,
        surfaceVariant = scheme.surfaceContainerHigh,
        onSurfaceVariant = scheme.onSurface,
        inverseSurface = scheme.inverseSurface,
        inverseOnSurface = scheme.inverseOnSurface,
        error = scheme.error,
    )
}


/**
 * 10-foot UI, sized against the screen this actually runs on.
 *
 * A 1080p TV at density 320 is only 960 x 540 dp, far less room than the pixel count suggests.
 * Type is large enough to read from a sofa but small enough that a heading plus a search row does
 * not consume half the height and push content off the bottom edge.
 *
 * No style carries a colour. A colour baked in here wins over [LocalContentColor], so a button
 * would paint its label in the surface colour instead of its own content colour. Anything that
 * wants muted text asks [Tone] for it at the call site.
 */
private val typography = Typography(
    headlineLarge = TextStyle(fontSize = 32.sp, fontWeight = FontWeight.Bold),
    headlineMedium = TextStyle(fontSize = 26.sp, fontWeight = FontWeight.SemiBold),
    titleLarge = TextStyle(fontSize = 21.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.Medium),
    bodyLarge = TextStyle(fontSize = 15.sp),
    bodyMedium = TextStyle(fontSize = 13.sp),
    // Defined rather than left to inherit: TV Material's default bodySmall is 12sp, a desk size,
    // and the places that use it carry text worth reading from a sofa.
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
 * Buttons the user can actually read: a calm raised surface at rest, accent when focused.
 *
 * The TV Material default is a near-white pill in every state, which gives the remote no strong
 * signal about where focus currently is.
 */
@Composable
fun tmButtonColors() = ButtonDefaults.colors(
    containerColor = Tone.surfaceHigh,
    contentColor = Tone.text,
    focusedContainerColor = Tone.focusFill,
    focusedContentColor = Tone.onFocusFill,
    pressedContainerColor = Tone.focusFill,
    pressedContentColor = Tone.onFocusFill,
    disabledContainerColor = Tone.surface,
    disabledContentColor = Tone.muted,
)

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

/**
 * The same roles at the sizes Material 3 ships for a device held in the hand.
 *
 * Material 3's own defaults for these roles, written out rather than inherited because the roles
 * here belong to `androidx.tv.material3.Typography`, whose defaults are the television's. No style
 * carries a colour, for the same reason the television's does not.
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
 * [phoneTypography] again, as the type the stock Material components read.
 *
 * The figures appear twice because the two theme systems have their own `Typography` classes and
 * neither will take the other's. Keep the two in step.
 */
private val m3PhoneTypography = M3Typography(
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
 * The app's palette as a complete Material 3 scheme, dark.
 *
 * Every role Material components read is set, so no card, chip or sheet falls back to Compose's
 * lavender defaults. The hue is Telegram's, because that is what the app is: a Telegram client.
 * Tone and chroma are Material's, so a Material component drawn with it looks like one.
 */
private val darkScheme = M3DarkColorScheme(
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
private val lightScheme = M3LightColorScheme(
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

/** Material's own shape scale, wired to [Corner] so a stock component and a hand-drawn one agree. */
private val m3Shapes = Shapes(
    extraSmall = RoundedCornerShape(Corner.ExtraSmall),
    small = RoundedCornerShape(Corner.Small),
    medium = RoundedCornerShape(Corner.Medium),
    large = RoundedCornerShape(Corner.Large),
    extraLarge = RoundedCornerShape(Corner.ExtraLarge),
)

/**
 * The scheme the phone should draw with, given the viewer's choice and the phone's own.
 *
 * Dynamic colour is the Android 12 convention and is what makes an app look like it belongs on the
 * device it is installed on: the palette comes from the wallpaper, so TMPlayer matches Photos and
 * Messages rather than insisting on its own blue over the top of them. Anyone who would rather
 * have the blue can say so, and a phone older than Android 12 has nothing to offer either way.
 */
@Composable
private fun phoneScheme(dark: Boolean, dynamic: Boolean): ColorScheme {
    val context = LocalContext.current
    val wallpaper = dynamic && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    return when {
        wallpaper && dark -> dynamicDarkColorScheme(context)
        wallpaper -> dynamicLightColorScheme(context)
        dark -> darkScheme
        else -> lightScheme
    }
}

/**
 * True when the tree being drawn is the phone's, which is the only tree with a light mode.
 *
 * Read at the point of use rather than passed down, because nearly every composable in the app
 * needs it and half of them are shared with the television.
 */
val LocalDarkTheme = staticCompositionLocalOf { true }

@Composable
fun TMPlayerTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val touch = !FormFactor.isTv(context)
    val settings = remember(context) { SettingsStore(context) }

    // Seeded with what the system is already doing, so the first frame is not painted in the wrong
    // mode and then corrected a frame later once DataStore answers.
    val systemDark = isSystemInDarkTheme()
    val choice by settings.themeChoice.collectAsState(initial = ThemeChoice.Default)
    val dynamic by settings.dynamicColour.collectAsState(initial = false)

    // A television has no system light mode to follow, so "System" there means dark. Light and
    // Dark are the viewer's own answer, on either device: a television in a bright room is real.
    val dark = when {
        choice == ThemeChoice.Light -> false
        choice == ThemeChoice.Dark -> true
        !touch -> true
        else -> systemDark
    }

    // One scheme for both devices. The television has no wallpaper to take colours from, but
    // everything else about it is the phone's palette.
    val scheme = if (touch) phoneScheme(dark, dynamic) else if (dark) darkScheme else lightScheme

    // Both themes, always, and the form factor decides only the type scale. Two theme systems are
    // live because the two devices need different controls, and a shared screen reads one theme
    // for its text and the other for its buttons, so neither may be missing.
    //
    // Material's expressive theme entry point is internal in material3 1.4.0, so the motion scheme
    // cannot be swapped wholesale here; the springs live at the call sites that want them.
    M3MaterialTheme(
        colorScheme = scheme,
        shapes = m3Shapes,
        // The phone scale, given to the theme the phone's own components read, so a screen mixing
        // the two theme systems draws all of its text at one scale.
        typography = if (touch) m3PhoneTypography else M3MaterialTheme.typography,
    ) {
        MaterialTheme(
            colorScheme = tvColors(scheme, dark),
            typography = if (touch) phoneTypography else typography,
        ) {
            CompositionLocalProvider(
                // Without this the root content colour is undefined and every unstyled Text
                // inherits Compose's fallback, so a light theme would draw dark theme grey.
                LocalContentColor provides scheme.onSurface,
                LocalDarkTheme provides dark,
                content = content,
            )
        }
    }
}
