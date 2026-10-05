package com.tmplayer.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme as M3MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import com.tmplayer.ui.i18n.ProvideStrings
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
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
 * The Android entry point to the theme. The palette, the shapes, the touch type scale and [Tone]
 * live in `:ui` (Palette.kt), shared with the desktop; what is here is Android's own: the
 * television's tv-material theme built from that palette, and the wallpaper colours of a phone.
 */


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
 * The same roles at the sizes Material 3 ships for a device held in the hand.
 *
 * Material 3's own defaults for these roles, written out rather than inherited because the roles
 * here belong to `androidx.tv.material3.Typography`, whose defaults are the television's. No style
 * carries a colour, for the same reason the television's does not. The same figures, as the type
 * the stock Material components read, are [TmTouchTypography] in `:ui`; keep the two in step.
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
        dark -> TmSchemes.Dark
        else -> TmSchemes.Light
    }
}

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
    val scheme = if (touch) phoneScheme(dark, dynamic) else TmSchemes.of(dark)

    // Both themes, always, and the form factor decides only the type scale. Two theme systems are
    // live because the two devices need different controls, and a shared screen reads one theme
    // for its text and the other for its buttons, so neither may be missing.
    //
    // Material's expressive theme entry point is internal in material3 1.4.0, so the motion scheme
    // cannot be swapped wholesale here; the springs live at the call sites that want them.
    M3MaterialTheme(
        colorScheme = scheme,
        shapes = TmShapes,
        // The phone scale, given to the theme the phone's own components read, so a screen mixing
        // the two theme systems draws all of its text at one scale.
        typography = if (touch) TmTouchTypography else M3MaterialTheme.typography,
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
            ) {
                ProvideStrings(content)
            }
        }
    }
}
