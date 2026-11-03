package com.tmplayer.ui.theme

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.border
import com.tmplayer.data.Accent
import androidx.compose.runtime.getValue
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
import com.tmplayer.ui.i18n.ProvideStrings
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.State
import kotlin.math.ceil
import kotlin.math.min
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
    /** Android TV's 5% of a 960dp wide screen. */
    val SafeH = 48.dp

    /** Android TV's 5% of a 540dp high screen. */
    val SafeV = 27.dp

    /**
     * Room a lazy list leaves around its tiles: a list clips what it draws to its own bounds, so a
     * tile against its edge would lose its focus border and shadow.
     */
    val FocusClearance = 8.dp
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
 * True when the dark theme is drawn on true black (the Pure black switch, with the app dark), for
 * the few things that paint the ground themselves rather than read the scheme, such as the loader.
 */
val LocalPureBlack = staticCompositionLocalOf { false }

/**
 * A ring around whatever the remote is on, for when the fill alone is not enough.
 *
 * In daylight [Tone.focusFill] is a pale blue, a 1.24:1 step on near-white and well under the 3:1
 * a control is meant to stand out by, so an outline in the accent carries the signal the fill
 * cannot.
 *
 * In the dark theme [Tone.focusFill] is already a bright block against a near-black screen, but
 * that block alone cannot tell "focused" apart from a control that is merely chosen or otherwise
 * lit the same way (HX42: a Home banner pill with no fill change at all had no focus mark once
 * Reduce motion turned its scale off). The ring there is [Tone.onFocusFill], Material's own
 * contrast-guaranteed ink for that fill, rather than the accent again: a ring in the same hue as
 * the block it sits on would still read as invisible.
 */
@Composable
fun Modifier.focusRing(focused: Boolean, shape: Shape): Modifier =
    if (focused && FocusVisibility.visible) {
        border(width = Focus.Ring, color = focusRingColor(), shape = shape)
    } else {
        this
    }

/** The colour [focusRing] draws in: see there for why it is not the accent in the dark theme. */
@Composable
@ReadOnlyComposable
fun focusRingColor(): Color = if (LocalDarkTheme.current) Tone.onFocusFill else Tone.accent

/**
 * A focus [fill] behind the content and a [Focus.Ring] in [ring] over it, painted from [focused]
 * in the draw phase alone, so a focus move draws the element again and composes nothing (HX76:
 * on a 1 GB TV stick each row or tile composed again cost a share of a frame). The stroke is the
 * one [border] draws for a rounded shape (whole pixels wide, inset by half of that, the corners
 * shrunk to match), and the fill is [androidx.compose.foundation.background]'s, so it looks as a
 * `background(...).focusRing(...)` pair did. [focused] decides alone: pass a State that already
 * holds [FocusVisibility] where a mouse click should leave no mark ([collectIsFocusVisibleAsState]).
 */
fun Modifier.focusDrawn(focused: State<Boolean>, ring: Color, shape: Shape, fill: Color = Color.Unspecified): Modifier =
    drawWithCache {
        val stroke = min(ceil(Focus.Ring.toPx()), ceil(size.minDimension / 2))
        val half = stroke / 2
        val outline = shape.createOutline(size, layoutDirection, this)
        val corner = (outline as? Outline.Rounded)?.roundRect?.topLeftCornerRadius?.let {
            CornerRadius((it.x - half).coerceAtLeast(0f), (it.y - half).coerceAtLeast(0f))
        }
        val style = Stroke(stroke)
        onDrawWithContent {
            val on = focused.value
            if (on && fill.isSpecified) drawOutline(outline, fill)
            drawContent()
            if (!on) return@onDrawWithContent
            if (corner != null) {
                drawRoundRect(
                    ring,
                    topLeft = Offset(half, half),
                    size = Size(size.width - stroke, size.height - stroke),
                    cornerRadius = corner,
                    style = style,
                )
            } else {
                drawOutline(outline, ring, style = style)
            }
        }
    }

/** The app's palette as complete Material 3 schemes, one per mode. */
object TmSchemes {

    /**
     * Dark.
     *
     * Every role Material components read is set, so no card, chip or sheet falls back to
     * Compose's lavender defaults. The hue is Telegram's, because that is what the app is: a
     * Telegram client, and it stays in the accent roles. The neutrals (the background, the
     * surface steps, the outline and the text) have most of their chroma taken out, so the ground
     * is a deep, nearly hueless dark and the artwork on it supplies the colour, the way a cinema
     * app's does. Muted text is 9.3:1 on the background and 8.6:1 on [Tone.surface].
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
        background = Color(0xFF0C0E10),
        onBackground = Color(0xFFF2F4F5),
        surface = Color(0xFF0C0E10),
        onSurface = Color(0xFFF2F4F5),
        surfaceVariant = Color(0xFF40484C),
        onSurfaceVariant = Color(0xFFAEB5BB),
        surfaceContainerLowest = Color(0xFF08090A),
        surfaceContainerLow = Color(0xFF15181B),
        surfaceContainer = Color(0xFF1A1D20),
        surfaceContainerHigh = Color(0xFF23272A),
        surfaceContainerHighest = Color(0xFF2D3134),
        surfaceBright = Color(0xFF33373A),
        surfaceDim = Color(0xFF0C0E10),
        outline = Color(0xFF8A9297),
        outlineVariant = Color(0xFF2E3337),
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

    /**
     * Black, for an OLED screen (the Pure black switch, [com.tmplayer.data.pureBlackGround]):
     * [Dark] with the ground at true black. See [pureBlack].
     */
    val Black: ColorScheme = Dark.pureBlack()

    /** The scheme for this mode in [accent]: the theme's ground and text, the accent's roles. */
    fun of(dark: Boolean, black: Boolean = false, accent: Accent = Accent.Default): ColorScheme {
        val base = when {
            dark && black -> Black
            dark -> Dark
            else -> Light
        }
        return base.withAccent(accent, dark)
    }
}

/**
 * The accent roles of one [Accent] in one mode: the primary, secondary and tertiary families.
 * Everything else in a scheme (the ground, the surface steps, the text, the outline, the error) is
 * the theme's and stays as it is, so an accent never changes how readable a page is.
 */
class AccentRoles(
    val primary: Color,
    val onPrimary: Color,
    val primaryContainer: Color,
    val onPrimaryContainer: Color,
    val inversePrimary: Color,
    val secondary: Color,
    val onSecondary: Color,
    val secondaryContainer: Color,
    val onSecondaryContainer: Color,
    val tertiary: Color,
    val onTertiary: Color,
    val tertiaryContainer: Color,
    val onTertiaryContainer: Color,
)

/** This scheme with [accent]'s roles for the mode; [Accent.Telegram] leaves it exactly as it is. */
fun ColorScheme.withAccent(accent: Accent, dark: Boolean): ColorScheme {
    val r = TmAccents.roles(accent, dark) ?: return this
    return copy(
        primary = r.primary, onPrimary = r.onPrimary,
        primaryContainer = r.primaryContainer, onPrimaryContainer = r.onPrimaryContainer,
        inversePrimary = r.inversePrimary,
        secondary = r.secondary, onSecondary = r.onSecondary,
        secondaryContainer = r.secondaryContainer, onSecondaryContainer = r.onSecondaryContainer,
        tertiary = r.tertiary, onTertiary = r.onTertiary,
        tertiaryContainer = r.tertiaryContainer, onTertiaryContainer = r.onTertiaryContainer,
    )
}

/**
 * The accents other than Telegram blue, whose roles are [TmSchemes.Dark] and [TmSchemes.Light]
 * themselves.
 *
 * Built as Material 3 tonal palettes are, from a seed's hue with CIELAB lightness as the tone: in
 * the dark the primary at tone 80 with tone 20 on it and a tone 30 container under tone 90 text,
 * in daylight the primary at 40 with white on it and a tone 90 container under tone 10 text; the
 * secondary is the seed's hue with little chroma, the tertiary 60 degrees round from it. Two are
 * set by hand: Red keeps a Netflix-like red as its dark primary with white on it (4.8:1), and
 * Monochrome is greys alone, near white on the dark ground and near black in daylight.
 *
 * Every pair a word is written on passes WCAG AA (4.5:1) and every primary stands out from the
 * ground and the raised surfaces by 3:1, in light, dark and pure black; AccentContrastTest checks
 * them all.
 */
object TmAccents {

    fun roles(accent: Accent, dark: Boolean): AccentRoles? = when (accent) {
        Accent.Telegram, Accent.Custom -> null
        else -> (if (dark) darkRoles else lightRoles).getValue(accent)
    }

    /** The colour a swatch shows for [accent] in this mode: its primary. */
    fun swatch(accent: Accent, dark: Boolean): Color =
        roles(accent, dark)?.primary ?: (if (dark) TmSchemes.Dark else TmSchemes.Light).primary

    private fun c(argb: Long) = Color(argb)

    @Suppress("LongParameterList")
    private fun roles(
        p: Long, onP: Long, pc: Long, onPc: Long, inv: Long,
        s: Long, onS: Long, sc: Long, onSc: Long,
        t: Long, onT: Long, tc: Long, onTc: Long,
    ) = AccentRoles(c(p), c(onP), c(pc), c(onPc), c(inv), c(s), c(onS), c(sc), c(onSc), c(t), c(onT), c(tc), c(onTc))

    private val darkRoles: Map<Accent, AccentRoles> = mapOf(
        Accent.Red to roles(
            0xFFE50914, 0xFFFFFFFF, 0xFF8C1D18, 0xFFFFDAD5, 0xFFC0141C,
            0xFFE1BFB7, 0xFF432A24, 0xFF5C403A, 0xFFFEDBD3,
            0xFFD2C79A, 0xFF36310B, 0xFF4E4721, 0xFFEEE3B5,
        ),
        Accent.Orange to roles(
            0xFFFFB68D, 0xFF532200, 0xFF783100, 0xFFFFDBC9, 0xFF994710,
            0xFFDEC1B1, 0xFF412C20, 0xFF594235, 0xFFFBDDCD,
            0xFFC3CB9E, 0xFF2C340F, 0xFF424A24, 0xFFDFE7B9,
        ),
        Accent.Amber to roles(
            0xFFF0BF5E, 0xFF402D00, 0xFF5C4300, 0xFFFFDEA8, 0xFF7A5900,
            0xFFD4C4AD, 0xFF3A2F1C, 0xFF514531, 0xFFF1E0C8,
            0xFFACD0AB, 0xFF17371A, 0xFF2E4E2F, 0xFFC7ECC6,
        ),
        Accent.Emerald to roles(
            0xFF65DCA8, 0xFF003824, 0xFF005236, 0xFF82F9C3, 0xFF006C49,
            0xFFAFCDBE, 0xFF1C3529, 0xFF324C3F, 0xFFCBEAD9,
            0xFF89D1E3, 0xFF003640, 0xFF004E5B, 0xFFA7EDFF,
        ),
        Accent.Teal to roles(
            0xFF59DBC8, 0xFF003731, 0xFF005048, 0xFF78F7E4, 0xFF006B5F,
            0xFFA8CEC7, 0xFF133631, 0xFF2B4D47, 0xFFC4EAE3,
            0xFF94CEED, 0xFF003547, 0xFF004D66, 0xFFC0E8FF,
        ),
        Accent.Violet to roles(
            0xFFD6BAFF, 0xFF351D78, 0xFF513492, 0xFFEBDCFF, 0xFF6C4BAD,
            0xFFCDC2DB, 0xFF352D41, 0xFF4C4358, 0xFFE9DEF8,
            0xFFF4B6C2, 0xFF51212D, 0xFF6A3743, 0xFFFFD9E0,
        ),
        Accent.Pink to roles(
            0xFFFFB0CF, 0xFF640039, 0xFF8D0053, 0xFFFFD8E6, 0xFFAC276B,
            0xFFDFBECA, 0xFF432933, 0xFF5B3F4A, 0xFFFCDAE6,
            0xFFEDBCA3, 0xFF4A2715, 0xFF643D29, 0xFFFFDBCA,
        ),
        Accent.Monochrome to roles(
            0xFFE4E4E4, 0xFF1A1A1A, 0xFF474747, 0xFFF0F0F0, 0xFF3A3A3A,
            0xFFC6C6C6, 0xFF2F2F2F, 0xFF3D3D3D, 0xFFE2E2E2,
            0xFFBDBDBD, 0xFF292929, 0xFF404040, 0xFFE6E6E6,
        ),
    )

    private val lightRoles: Map<Accent, AccentRoles> = mapOf(
        Accent.Red to roles(
            0xFFC0141C, 0xFFFFFFFF, 0xFFFFDAD5, 0xFF410003, 0xFFFFB4AB,
            0xFF755751, 0xFFFFFFFF, 0xFFFEDBD3, 0xFF2D150F,
            0xFF675F37, 0xFFFFFFFF, 0xFFEEE3B5, 0xFF211C00,
        ),
        Accent.Orange to roles(
            0xFF994710, 0xFFFFFFFF, 0xFFFFDBC9, 0xFF2E1500, 0xFFFFB68D,
            0xFF72594C, 0xFFFFFFFF, 0xFFFBDDCD, 0xFF2A1709,
            0xFF5A623A, 0xFFFFFFFF, 0xFFDFE7B9, 0xFF1A1E00,
        ),
        Accent.Amber to roles(
            0xFF7A5900, 0xFFFFFFFF, 0xFFFFDEA8, 0xFF251A00, 0xFFF0BF5E,
            0xFF6A5D48, 0xFFFFFFFF, 0xFFF1E0C8, 0xFF241A03,
            0xFF456646, 0xFFFFFFFF, 0xFFC7ECC6, 0xFF022100,
        ),
        Accent.Emerald to roles(
            0xFF006C49, 0xFFFFFFFF, 0xFF82F9C3, 0xFF002114, 0xFF65DCA8,
            0xFF496457, 0xFFFFFFFF, 0xFFCBEAD9, 0xFF062015,
            0xFF106777, 0xFFFFFFFF, 0xFFA7EDFF, 0xFF001F26,
        ),
        Accent.Teal to roles(
            0xFF006B5F, 0xFFFFFFFF, 0xFF78F7E4, 0xFF00201C, 0xFF59DBC8,
            0xFF42655F, 0xFFFFFFFF, 0xFFC4EAE3, 0xFF00201C,
            0xFF236580, 0xFFFFFFFF, 0xFFC0E8FF, 0xFF001F2B,
        ),
        Accent.Violet to roles(
            0xFF6C4BAD, 0xFFFFFFFF, 0xFFEBDCFF, 0xFF17065F, 0xFFD6BAFF,
            0xFF645A70, 0xFFFFFFFF, 0xFFE9DEF8, 0xFF1F182B,
            0xFF844F5A, 0xFFFFFFFF, 0xFFFFD9E0, 0xFF380A18,
        ),
        Accent.Pink to roles(
            0xFFAC276B, 0xFFFFFFFF, 0xFFFFD8E6, 0xFF3E0021, 0xFFFFB0CF,
            0xFF735661, 0xFFFFFFFF, 0xFFFCDAE6, 0xFF2C141E,
            0xFF7E5540, 0xFFFFFFFF, 0xFFFFDBCA, 0xFF2F1500,
        ),
        Accent.Monochrome to roles(
            0xFF1F1F1F, 0xFFFFFFFF, 0xFFDADADA, 0xFF141414, 0xFFE4E4E4,
            0xFF5C5C5C, 0xFFFFFFFF, 0xFFE2E2E2, 0xFF1B1B1B,
            0xFF535353, 0xFFFFFFFF, 0xFFDCDCDC, 0xFF191919,
        ),
    )
}

/** WCAG's contrast ratio between two opaque colours, from 1 (the same) to 21 (black on white). */
fun contrastRatio(a: Color, b: Color): Float {
    val x = a.luminance()
    val y = b.luminance()
    return (maxOf(x, y) + 0.05f) / (minOf(x, y) + 0.05f)
}

/**
 * [target] with its accent roles eased in over a moment, so picking a swatch washes the new
 * colour across the screen rather than snapping; the ground and the text change at once.
 */
@Composable
fun animateAccentRoles(target: ColorScheme): ColorScheme {
    val spec = Motion.standard<Color>()
    val primary by animateColorAsState(target.primary, spec, label = "primary")
    val onPrimary by animateColorAsState(target.onPrimary, spec, label = "onPrimary")
    val primaryContainer by animateColorAsState(target.primaryContainer, spec, label = "primaryContainer")
    val onPrimaryContainer by animateColorAsState(target.onPrimaryContainer, spec, label = "onPrimaryContainer")
    val secondaryContainer by animateColorAsState(target.secondaryContainer, spec, label = "secondaryContainer")
    val onSecondaryContainer by animateColorAsState(target.onSecondaryContainer, spec, label = "onSecondaryContainer")
    return target.copy(
        primary = primary,
        onPrimary = onPrimary,
        primaryContainer = primaryContainer,
        onPrimaryContainer = onPrimaryContainer,
        secondaryContainer = secondaryContainer,
        onSecondaryContainer = onSecondaryContainer,
    )
}

/**
 * This dark scheme with its ground at true black, so an OLED panel turns those pixels off. The
 * accent, the text and the outline stay as they are; the surfaces step up from black by the same
 * small amounts the dark scheme steps up from its own ground, so a card, a sheet or a focused row
 * still stands apart. Applied to the wallpaper's scheme too, on a phone that takes its colours
 * from it.
 */
fun ColorScheme.pureBlack(): ColorScheme = copy(
    background = Color.Black,
    surface = Color.Black,
    surfaceDim = Color.Black,
    surfaceContainerLowest = Color.Black,
    surfaceContainerLow = Color(0xFF0B0C0D),
    surfaceContainer = Color(0xFF111315),
    surfaceContainerHigh = Color(0xFF1A1D1F),
    surfaceContainerHighest = Color(0xFF23272A),
    surfaceBright = Color(0xFF2B2F32),
    outlineVariant = Color(0xFF26292C),
)

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
 *
 * [Typography.displaySmall] is the one style above the headlines: the title of a hero or a page,
 * bold and set a little tight, the only big type on a screen.
 */
val TmTouchTypography = Typography(
    displaySmall = TextStyle(fontSize = 32.sp, lineHeight = 36.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp),
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
    /** Dark on a true black ground ([TmSchemes.Black]); only with [dark]. */
    black: Boolean = false,
    /** The accent colour, Telegram blue unless the viewer picked another. */
    accent: Accent = Accent.Default,
    /** The viewer's own colour as ARGB, used when [accent] is [Accent.Custom]. */
    customAccent: Int? = null,
    /** Second lines and edges brought forward ([highContrast]). */
    highContrast: Boolean = false,
    typeface: com.tmplayer.data.AppTypeface = com.tmplayer.data.AppTypeface.Default,
    scheme: ColorScheme = animateAccentRoles(
        TmSchemes.of(dark, black, accent).withCustomAccent(accent, customAccent, dark).highContrast(highContrast),
    ),
    content: @Composable () -> Unit,
) {
    MaterialTheme(colorScheme = scheme, shapes = TmShapes, typography = TmTouchTypography.withFamily(typeface.family())) {
        CompositionLocalProvider(
            LocalContentColor provides scheme.onSurface,
            LocalDarkTheme provides dark,
            LocalPureBlack provides (dark && black),
        ) {
            ProvideFallbackFonts { ProvideStrings(content) }
        }
    }
}
