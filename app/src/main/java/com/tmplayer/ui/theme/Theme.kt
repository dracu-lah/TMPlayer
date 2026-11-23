package com.tmplayer.ui.theme

import com.tmplayer.ui.theme.FocusStyle
import com.tmplayer.ui.theme.LocalFocusStyle
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme as M3MaterialTheme
import androidx.compose.material3.Typography as M3Typography
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
import com.tmplayer.data.Accent
import com.tmplayer.data.ThemeChoice
import com.tmplayer.data.pureBlackGround
import com.tmplayer.ui.theme.family
import com.tmplayer.ui.theme.highContrast
import com.tmplayer.ui.theme.withCustomAccent
import com.tmplayer.ui.theme.withFamily

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
 *
 * The containers, the borders and the scrim are mapped too, though no screen here names them:
 * tv-material's own components fall back to them (a focused card's border, a dialog's dim), and
 * its defaults are a lavender that belongs to neither palette.
 */
private fun tvColors(scheme: ColorScheme, dark: Boolean) = if (dark) {
    darkColorScheme(
        primary = scheme.primary,
        onPrimary = scheme.onPrimary,
        primaryContainer = scheme.primaryContainer,
        onPrimaryContainer = scheme.onPrimaryContainer,
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
        border = scheme.outline,
        borderVariant = scheme.outlineVariant,
        scrim = scheme.scrim,
    )
} else {
    tvLightColorScheme(
        primary = scheme.primary,
        onPrimary = scheme.onPrimary,
        primaryContainer = scheme.primaryContainer,
        onPrimaryContainer = scheme.onPrimaryContainer,
        background = scheme.background,
        onBackground = scheme.onBackground,
        surface = scheme.surfaceContainerLow,
        onSurface = scheme.onSurface,
        surfaceVariant = scheme.surfaceContainerHigh,
        onSurfaceVariant = scheme.onSurface,
        inverseSurface = scheme.inverseSurface,
        inverseOnSurface = scheme.inverseOnSurface,
        error = scheme.error,
        border = scheme.outline,
        borderVariant = scheme.outlineVariant,
        scrim = scheme.scrim,
    )
}


/**
 * The television's hero and page title: the touch scale's [TmTouchTypography] display style, a
 * step larger for the distance. Both theme systems on the television carry it, because the
 * shared screens set their titles with Material 3's text and the television's own with
 * tv-material's.
 */
private fun tvDisplaySmall() =
    TextStyle(fontSize = TvTypeScale.displaySmall.sp.sp, lineHeight = 40.sp, fontWeight = TvTypeScale.displaySmall.weight, letterSpacing = (-0.5).sp)

/**
 * The television's type scale, the one place its sizes are written (WP-04). Both theme systems the
 * television carries are built from it, so they cannot drift: tv-material's [Typography] for the
 * television's own screens ([tvTypography]) and Material 3's for the shared ones
 * ([tvM3Typography]). Nothing is under [FLOOR_SP]: read from a sofa, 12 sp and 11 sp are a desk's
 * sizes. A tile caption, a row's second line, a badge and a pill are all at least 14 sp, body text
 * 15 sp or more, and a title is a visible step above body. An interface size below Default shrinks
 * spacing, not these ([UiScaled]).
 *
 * A 1080p TV at density 320 is only 960 x 540 dp, far less room than the pixel count suggests, so a
 * row that no longer fits is cut with `maxLines` and an ellipsis, never given a smaller style.
 * No line heights are set here: the defaults stay, so a row grows only by its text.
 *
 * No style carries a colour. A colour baked in here wins over [LocalContentColor], so a button
 * would paint its label in the surface colour instead of its own content colour. Anything that
 * wants muted text asks [Tone] for it at the call site.
 */
internal object TvTypeScale {
    /** The smallest any style may be, in sp. */
    const val FLOOR_SP = 14f

    /** One style of the scale: its size in sp and its weight. */
    class Step(val sp: Float, val weight: FontWeight)

    val displayLarge = Step(57f, FontWeight.Normal)
    val displayMedium = Step(45f, FontWeight.Normal)
    val displaySmall = Step(36f, FontWeight.Bold)
    val headlineLarge = Step(32f, FontWeight.Bold)
    val headlineMedium = Step(26f, FontWeight.SemiBold)
    val headlineSmall = Step(24f, FontWeight.Normal)
    val titleLarge = Step(22f, FontWeight.SemiBold)
    val titleMedium = Step(18f, FontWeight.Medium)
    val titleSmall = Step(15f, FontWeight.Medium)
    val bodyLarge = Step(16f, FontWeight.Normal)
    val bodyMedium = Step(15f, FontWeight.Normal)
    val bodySmall = Step(14f, FontWeight.Normal)
    val labelLarge = Step(15f, FontWeight.Medium)
    val labelMedium = Step(14f, FontWeight.Medium)
    val labelSmall = Step(14f, FontWeight.Medium)

    /** Every style by its role's name, for the tests and for anything that walks the scale. */
    val all: Map<String, Step> = linkedMapOf(
        "displayLarge" to displayLarge, "displayMedium" to displayMedium, "displaySmall" to displaySmall,
        "headlineLarge" to headlineLarge, "headlineMedium" to headlineMedium, "headlineSmall" to headlineSmall,
        "titleLarge" to titleLarge, "titleMedium" to titleMedium, "titleSmall" to titleSmall,
        "bodyLarge" to bodyLarge, "bodyMedium" to bodyMedium, "bodySmall" to bodySmall,
        "labelLarge" to labelLarge, "labelMedium" to labelMedium, "labelSmall" to labelSmall,
    )

    /** The scale as tv-material's styles: size and weight only. */
    fun tvTypography(): Typography {
        fun Step.style() = TextStyle(fontSize = sp.sp, fontWeight = weight)
        val base = Typography()
        return Typography(
            displayLarge = base.displayLarge.copy(fontSize = displayLarge.sp.sp, fontWeight = displayLarge.weight),
            displayMedium = base.displayMedium.copy(fontSize = displayMedium.sp.sp, fontWeight = displayMedium.weight),
            displaySmall = tvDisplaySmall(),
            headlineLarge = headlineLarge.style(),
            headlineMedium = headlineMedium.style(),
            headlineSmall = base.headlineSmall.copy(fontSize = headlineSmall.sp.sp, fontWeight = headlineSmall.weight),
            titleLarge = titleLarge.style(),
            titleMedium = titleMedium.style(),
            titleSmall = base.titleSmall.copy(fontSize = titleSmall.sp.sp, fontWeight = titleSmall.weight),
            bodyLarge = bodyLarge.style(),
            bodyMedium = bodyMedium.style(),
            bodySmall = bodySmall.style(),
            labelLarge = labelLarge.style(),
            labelMedium = labelMedium.style(),
            labelSmall = labelSmall.style(),
        )
    }

    /** The scale as Material 3's styles for the shared screens: [base]'s line heights and spacing, this size and weight. */
    fun tvM3Typography(base: M3Typography = M3Typography()): M3Typography {
        fun TextStyle.at(step: Step) = copy(fontSize = step.sp.sp, fontWeight = step.weight)
        return base.copy(
            displayLarge = base.displayLarge.at(displayLarge),
            displayMedium = base.displayMedium.at(displayMedium),
            displaySmall = tvDisplaySmall(),
            headlineLarge = base.headlineLarge.at(headlineLarge),
            headlineMedium = base.headlineMedium.at(headlineMedium),
            headlineSmall = base.headlineSmall.at(headlineSmall),
            titleLarge = base.titleLarge.at(titleLarge),
            titleMedium = base.titleMedium.at(titleMedium),
            titleSmall = base.titleSmall.at(titleSmall),
            bodyLarge = base.bodyLarge.at(bodyLarge),
            bodyMedium = base.bodyMedium.at(bodyMedium),
            bodySmall = base.bodySmall.at(bodySmall),
            labelLarge = base.labelLarge.at(labelLarge),
            labelMedium = base.labelMedium.at(labelMedium),
            labelSmall = base.labelSmall.at(labelSmall),
        )
    }
}

/**
 * Buttons the user can actually read: a calm raised surface at rest, accent when focused.
 *
 * The TV Material default is a near-white pill in every state, which gives the remote no strong
 * signal about where focus currently is.
 */
@Composable
fun tmButtonColors() = ButtonDefaults.colors(
    containerColor = FloatingTone.control,
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
    displaySmall = TmTouchTypography.displaySmall,
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
private fun phoneScheme(dark: Boolean, dynamic: Boolean, black: Boolean, accent: Accent): ColorScheme {
    val context = LocalContext.current
    val wallpaper = dynamic && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    return when {
        // The wallpaper's accent on a true black ground: the two choices do not fight.
        wallpaper && dark && black -> dynamicDarkColorScheme(context).pureBlack()
        wallpaper && dark -> dynamicDarkColorScheme(context)
        wallpaper -> dynamicLightColorScheme(context)
        // The wallpaper wins over the accent: it is the viewer's other answer to the same question.
        else -> TmSchemes.of(dark, black, accent)
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
    // The interface size (CP59), for every composition that draws in this theme: the app, and the
    // player's panels and menus. The video surface is a View sized by the player, so it is not.
    val scale by rememberUiScale(settings)

    // A television has no system light mode to follow, so "System" there means dark. Light and
    // Dark are the viewer's own answer, on either device: a television in a bright room is real.
    val dark = choice.isDark(systemDark = systemDark || !touch)

    // One scheme for both devices. The television has no wallpaper to take colours from, but
    // everything else about it is the phone's palette. Pure black is a switch under the theme and
    // applies whenever the app is dark, System on a dark phone included.
    val pureBlack by settings.pureBlack.collectAsState(initial = false)
    val black = pureBlackGround(choice, pureBlack, systemDark = systemDark || !touch)
    val accent by settings.accent.collectAsState(initial = Accent.Default)
    // The viewer's own colour, the typeface and high contrast (Appearance, REELBOX-PARITY).
    val customAccent by settings.customAccent.collectAsState(initial = null)
    val typeface by settings.typeface.collectAsState(initial = com.tmplayer.data.AppTypeface.Default)
    val highContrast by settings.highContrast.collectAsState(initial = false)
    // The television's focus ring with its contrasting outer line (T8); a phone has no such switch.
    val focusContrast by settings.highContrastFocus.collectAsState(initial = false)
    val family = typeface.family()
    // A picked accent washes in over a moment rather than snapping (animateAccentRoles).
    val scheme = animateAccentRoles(
        (if (touch) phoneScheme(dark, dynamic, black, accent) else TmSchemes.of(dark, black, accent))
            .let { if (touch && dynamic && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) it else it.withCustomAccent(accent, customAccent, dark) }
            .highContrast(highContrast),
    )

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
        typography = if (touch) {
            TmTouchTypography
        } else {
            TvTypeScale.tvM3Typography()
        }.withFamily(family),
    ) {
        MaterialTheme(
            colorScheme = tvColors(scheme, dark),
            typography = (if (touch) phoneTypography else TvTypeScale.tvTypography()).withTvFamily(family),
        ) {
            CompositionLocalProvider(
                // Without this the root content colour is undefined and every unstyled Text
                // inherits Compose's fallback, so a light theme would draw dark theme grey.
                LocalContentColor provides scheme.onSurface,
                LocalDarkTheme provides dark,
                LocalPureBlack provides black,
                // One definition of the focus ring, edge and scale: the phone's as it was, the
                // television's thicker and bigger (FocusStyle).
                LocalFocusStyle provides remember(touch, focusContrast) {
                    if (touch) FocusStyle.Standard else FocusStyle.tv(focusContrast)
                },
            ) {
                UiScaled(scale, keepTextSize = !touch) { ProvideStrings(content) }
            }
        }
    }
}

/** The television's and the phone's tv-material type scale set in [family]; itself when null. */
private fun Typography.withTvFamily(family: androidx.compose.ui.text.font.FontFamily?): Typography {
    if (family == null) return this
    fun TextStyle.f() = copy(fontFamily = family)
    return copy(
        displayLarge = displayLarge.f(), displayMedium = displayMedium.f(), displaySmall = displaySmall.f(),
        headlineLarge = headlineLarge.f(), headlineMedium = headlineMedium.f(), headlineSmall = headlineSmall.f(),
        titleLarge = titleLarge.f(), titleMedium = titleMedium.f(), titleSmall = titleSmall.f(),
        bodyLarge = bodyLarge.f(), bodyMedium = bodyMedium.f(), bodySmall = bodySmall.f(),
        labelLarge = labelLarge.f(), labelMedium = labelMedium.f(), labelSmall = labelSmall.f(),
    )
}
