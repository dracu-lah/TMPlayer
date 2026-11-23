package androidx.tv.material3

// TV Material on iOS: the nine names Android's shared screens import from it. On Android they draw
// a phone's text with TV Material's Text and theme too (Theme.kt provides both theme systems, the
// type scale chosen by form factor), so this is a working theme of its own, kept apart from
// Material 3's exactly as on Android: its own colour scheme, type scale, text style and content
// colour. The TV only controls (Button and its defaults) only have to draw sensibly: they are
// reached on a television alone.

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.takeOrElse
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp

typealias Typography = androidx.compose.material3.Typography

typealias Shapes = androidx.compose.material3.Shapes

@Immutable
class ColorScheme(
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
    val background: Color,
    val onBackground: Color,
    val surface: Color,
    val onSurface: Color,
    val surfaceVariant: Color,
    val onSurfaceVariant: Color,
    val surfaceTint: Color,
    val inverseSurface: Color,
    val inverseOnSurface: Color,
    val error: Color,
    val onError: Color,
    val errorContainer: Color,
    val onErrorContainer: Color,
    val border: Color,
    val borderVariant: Color,
    val scrim: Color,
)

private fun scheme(base: androidx.compose.material3.ColorScheme, values: Map<String, Color>): ColorScheme {
    fun pick(name: String, fallback: Color) = values[name]?.takeOrElse { fallback } ?: fallback
    return ColorScheme(
        primary = pick("primary", base.primary),
        onPrimary = pick("onPrimary", base.onPrimary),
        primaryContainer = pick("primaryContainer", base.primaryContainer),
        onPrimaryContainer = pick("onPrimaryContainer", base.onPrimaryContainer),
        inversePrimary = pick("inversePrimary", base.inversePrimary),
        secondary = pick("secondary", base.secondary),
        onSecondary = pick("onSecondary", base.onSecondary),
        secondaryContainer = pick("secondaryContainer", base.secondaryContainer),
        onSecondaryContainer = pick("onSecondaryContainer", base.onSecondaryContainer),
        tertiary = pick("tertiary", base.tertiary),
        onTertiary = pick("onTertiary", base.onTertiary),
        tertiaryContainer = pick("tertiaryContainer", base.tertiaryContainer),
        onTertiaryContainer = pick("onTertiaryContainer", base.onTertiaryContainer),
        background = pick("background", base.background),
        onBackground = pick("onBackground", base.onBackground),
        surface = pick("surface", base.surface),
        onSurface = pick("onSurface", base.onSurface),
        surfaceVariant = pick("surfaceVariant", base.surfaceVariant),
        onSurfaceVariant = pick("onSurfaceVariant", base.onSurfaceVariant),
        surfaceTint = pick("surfaceTint", base.surfaceTint),
        inverseSurface = pick("inverseSurface", base.inverseSurface),
        inverseOnSurface = pick("inverseOnSurface", base.inverseOnSurface),
        error = pick("error", base.error),
        onError = pick("onError", base.onError),
        errorContainer = pick("errorContainer", base.errorContainer),
        onErrorContainer = pick("onErrorContainer", base.onErrorContainer),
        border = pick("border", base.outline),
        borderVariant = pick("borderVariant", base.outlineVariant),
        scrim = pick("scrim", base.scrim),
    )
}

fun darkColorScheme(
    primary: Color = Color.Unspecified, onPrimary: Color = Color.Unspecified,
    primaryContainer: Color = Color.Unspecified, onPrimaryContainer: Color = Color.Unspecified,
    inversePrimary: Color = Color.Unspecified, secondary: Color = Color.Unspecified,
    onSecondary: Color = Color.Unspecified, secondaryContainer: Color = Color.Unspecified,
    onSecondaryContainer: Color = Color.Unspecified, tertiary: Color = Color.Unspecified,
    onTertiary: Color = Color.Unspecified, tertiaryContainer: Color = Color.Unspecified,
    onTertiaryContainer: Color = Color.Unspecified, background: Color = Color.Unspecified,
    onBackground: Color = Color.Unspecified, surface: Color = Color.Unspecified,
    onSurface: Color = Color.Unspecified, surfaceVariant: Color = Color.Unspecified,
    onSurfaceVariant: Color = Color.Unspecified, surfaceTint: Color = Color.Unspecified,
    inverseSurface: Color = Color.Unspecified, inverseOnSurface: Color = Color.Unspecified,
    error: Color = Color.Unspecified, onError: Color = Color.Unspecified,
    errorContainer: Color = Color.Unspecified, onErrorContainer: Color = Color.Unspecified,
    border: Color = Color.Unspecified, borderVariant: Color = Color.Unspecified,
    scrim: Color = Color.Unspecified,
): ColorScheme = scheme(
    androidx.compose.material3.darkColorScheme(),
    mapOf(
        "primary" to primary, "onPrimary" to onPrimary, "primaryContainer" to primaryContainer,
        "onPrimaryContainer" to onPrimaryContainer, "inversePrimary" to inversePrimary, "secondary" to secondary,
        "onSecondary" to onSecondary, "secondaryContainer" to secondaryContainer, "onSecondaryContainer" to onSecondaryContainer,
        "tertiary" to tertiary, "onTertiary" to onTertiary, "tertiaryContainer" to tertiaryContainer,
        "onTertiaryContainer" to onTertiaryContainer, "background" to background, "onBackground" to onBackground,
        "surface" to surface, "onSurface" to onSurface, "surfaceVariant" to surfaceVariant,
        "onSurfaceVariant" to onSurfaceVariant, "surfaceTint" to surfaceTint, "inverseSurface" to inverseSurface,
        "inverseOnSurface" to inverseOnSurface, "error" to error, "onError" to onError, "errorContainer" to errorContainer,
        "onErrorContainer" to onErrorContainer, "border" to border, "borderVariant" to borderVariant, "scrim" to scrim,
    ),
)

fun lightColorScheme(
    primary: Color = Color.Unspecified, onPrimary: Color = Color.Unspecified,
    primaryContainer: Color = Color.Unspecified, onPrimaryContainer: Color = Color.Unspecified,
    inversePrimary: Color = Color.Unspecified, secondary: Color = Color.Unspecified,
    onSecondary: Color = Color.Unspecified, secondaryContainer: Color = Color.Unspecified,
    onSecondaryContainer: Color = Color.Unspecified, tertiary: Color = Color.Unspecified,
    onTertiary: Color = Color.Unspecified, tertiaryContainer: Color = Color.Unspecified,
    onTertiaryContainer: Color = Color.Unspecified, background: Color = Color.Unspecified,
    onBackground: Color = Color.Unspecified, surface: Color = Color.Unspecified,
    onSurface: Color = Color.Unspecified, surfaceVariant: Color = Color.Unspecified,
    onSurfaceVariant: Color = Color.Unspecified, surfaceTint: Color = Color.Unspecified,
    inverseSurface: Color = Color.Unspecified, inverseOnSurface: Color = Color.Unspecified,
    error: Color = Color.Unspecified, onError: Color = Color.Unspecified,
    errorContainer: Color = Color.Unspecified, onErrorContainer: Color = Color.Unspecified,
    border: Color = Color.Unspecified, borderVariant: Color = Color.Unspecified,
    scrim: Color = Color.Unspecified,
): ColorScheme = scheme(
    androidx.compose.material3.lightColorScheme(),
    mapOf(
        "primary" to primary, "onPrimary" to onPrimary, "primaryContainer" to primaryContainer,
        "onPrimaryContainer" to onPrimaryContainer, "inversePrimary" to inversePrimary, "secondary" to secondary,
        "onSecondary" to onSecondary, "secondaryContainer" to secondaryContainer, "onSecondaryContainer" to onSecondaryContainer,
        "tertiary" to tertiary, "onTertiary" to onTertiary, "tertiaryContainer" to tertiaryContainer,
        "onTertiaryContainer" to onTertiaryContainer, "background" to background, "onBackground" to onBackground,
        "surface" to surface, "onSurface" to onSurface, "surfaceVariant" to surfaceVariant,
        "onSurfaceVariant" to onSurfaceVariant, "surfaceTint" to surfaceTint, "inverseSurface" to inverseSurface,
        "inverseOnSurface" to inverseOnSurface, "error" to error, "onError" to onError, "errorContainer" to errorContainer,
        "onErrorContainer" to onErrorContainer, "border" to border, "borderVariant" to borderVariant, "scrim" to scrim,
    ),
)

val LocalContentColor = compositionLocalOf { Color.Black }

val LocalTextStyle = compositionLocalOf { TextStyle.Default }

private val LocalColorScheme = staticCompositionLocalOf { darkColorScheme() }
private val LocalTypography = staticCompositionLocalOf { Typography() }
private val LocalShapes = staticCompositionLocalOf { Shapes() }

object MaterialTheme {
    val colorScheme: ColorScheme
        @Composable @ReadOnlyComposable get() = LocalColorScheme.current
    val typography: Typography
        @Composable @ReadOnlyComposable get() = LocalTypography.current
    val shapes: Shapes
        @Composable @ReadOnlyComposable get() = LocalShapes.current
}

@Composable
fun MaterialTheme(
    colorScheme: ColorScheme = MaterialTheme.colorScheme,
    shapes: Shapes = MaterialTheme.shapes,
    typography: Typography = MaterialTheme.typography,
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(
        LocalColorScheme provides colorScheme,
        LocalTypography provides typography,
        LocalShapes provides shapes,
        LocalTextStyle provides typography.bodyLarge,
        content = content,
    )
}

/** TV Material's Text: Material 3's, but styled and coloured from TV Material's own locals. */
@Composable
fun Text(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    fontSize: TextUnit = TextUnit.Unspecified,
    fontStyle: FontStyle? = null,
    fontWeight: FontWeight? = null,
    fontFamily: FontFamily? = null,
    letterSpacing: TextUnit = TextUnit.Unspecified,
    textDecoration: TextDecoration? = null,
    textAlign: TextAlign? = null,
    lineHeight: TextUnit = TextUnit.Unspecified,
    overflow: TextOverflow = TextOverflow.Clip,
    softWrap: Boolean = true,
    maxLines: Int = Int.MAX_VALUE,
    minLines: Int = 1,
    onTextLayout: (TextLayoutResult) -> Unit = {},
    style: TextStyle = LocalTextStyle.current,
) {
    androidx.compose.material3.Text(
        text = text, modifier = modifier,
        color = color.takeOrElse { style.color.takeOrElse { LocalContentColor.current } },
        fontSize = fontSize, fontStyle = fontStyle, fontWeight = fontWeight, fontFamily = fontFamily,
        letterSpacing = letterSpacing, textDecoration = textDecoration, textAlign = textAlign,
        lineHeight = lineHeight, overflow = overflow, softWrap = softWrap, maxLines = maxLines,
        minLines = minLines, onTextLayout = onTextLayout, style = style,
    )
}

@Composable
fun Text(
    text: AnnotatedString,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    fontSize: TextUnit = TextUnit.Unspecified,
    fontStyle: FontStyle? = null,
    fontWeight: FontWeight? = null,
    fontFamily: FontFamily? = null,
    letterSpacing: TextUnit = TextUnit.Unspecified,
    textDecoration: TextDecoration? = null,
    textAlign: TextAlign? = null,
    lineHeight: TextUnit = TextUnit.Unspecified,
    overflow: TextOverflow = TextOverflow.Clip,
    softWrap: Boolean = true,
    maxLines: Int = Int.MAX_VALUE,
    minLines: Int = 1,
    inlineContent: Map<String, InlineTextContent> = mapOf(),
    onTextLayout: (TextLayoutResult) -> Unit = {},
    style: TextStyle = LocalTextStyle.current,
) {
    androidx.compose.material3.Text(
        text = text, modifier = modifier,
        color = color.takeOrElse { style.color.takeOrElse { LocalContentColor.current } },
        fontSize = fontSize, fontStyle = fontStyle, fontWeight = fontWeight, fontFamily = fontFamily,
        letterSpacing = letterSpacing, textDecoration = textDecoration, textAlign = textAlign,
        lineHeight = lineHeight, overflow = overflow, softWrap = softWrap, maxLines = maxLines,
        minLines = minLines, inlineContent = inlineContent, onTextLayout = onTextLayout, style = style,
    )
}

@Composable
fun Icon(imageVector: ImageVector, contentDescription: String?, modifier: Modifier = Modifier, tint: Color = LocalContentColor.current) =
    androidx.compose.material3.Icon(imageVector, contentDescription, modifier, tint)

@Composable
fun Icon(painter: Painter, contentDescription: String?, modifier: Modifier = Modifier, tint: Color = LocalContentColor.current) =
    androidx.compose.material3.Icon(painter, contentDescription, modifier, tint)

@Composable
fun Icon(bitmap: ImageBitmap, contentDescription: String?, modifier: Modifier = Modifier, tint: Color = LocalContentColor.current) =
    androidx.compose.material3.Icon(bitmap, contentDescription, modifier, tint)

@Immutable
class Border(val border: BorderStroke, val inset: Dp = 0.dp, val shape: Shape = RoundedCornerShape(8.dp)) {
    companion object {
        val None = Border(BorderStroke(0.dp, Color.Transparent), 0.dp, RoundedCornerShape(0.dp))
    }
}

@Immutable
class ButtonShape(val shape: Shape, val focusedShape: Shape, val pressedShape: Shape, val disabledShape: Shape, val focusedDisabledShape: Shape)

@Immutable
class ButtonColors(
    val containerColor: Color,
    val contentColor: Color,
    val focusedContainerColor: Color,
    val focusedContentColor: Color,
    val pressedContainerColor: Color,
    val pressedContentColor: Color,
    val disabledContainerColor: Color,
    val disabledContentColor: Color,
)

@Immutable
class ButtonBorder(val border: Border, val focusedBorder: Border, val pressedBorder: Border, val disabledBorder: Border, val focusedDisabledBorder: Border)

@Immutable
class ButtonScale(val scale: Float, val focusedScale: Float, val pressedScale: Float, val disabledScale: Float, val focusedDisabledScale: Float)

object ButtonDefaults {
    val ContentPadding = PaddingValues(start = 16.dp, top = 10.dp, end = 16.dp, bottom = 10.dp)

    fun shape(
        shape: Shape = RoundedCornerShape(50),
        focusedShape: Shape = shape,
        pressedShape: Shape = shape,
        disabledShape: Shape = shape,
        focusedDisabledShape: Shape = disabledShape,
    ): ButtonShape = ButtonShape(shape, focusedShape, pressedShape, disabledShape, focusedDisabledShape)

    @Composable
    fun colors(
        containerColor: Color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f),
        contentColor: Color = MaterialTheme.colorScheme.surface,
        focusedContainerColor: Color = MaterialTheme.colorScheme.onSurface,
        focusedContentColor: Color = MaterialTheme.colorScheme.inverseOnSurface,
        pressedContainerColor: Color = focusedContainerColor,
        pressedContentColor: Color = focusedContentColor,
        disabledContainerColor: Color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
        disabledContentColor: Color = contentColor,
    ): ButtonColors = ButtonColors(
        containerColor, contentColor, focusedContainerColor, focusedContentColor,
        pressedContainerColor, pressedContentColor, disabledContainerColor, disabledContentColor,
    )

    fun border(
        border: Border = Border.None,
        focusedBorder: Border = border,
        pressedBorder: Border = focusedBorder,
        disabledBorder: Border = border,
        focusedDisabledBorder: Border = disabledBorder,
    ): ButtonBorder = ButtonBorder(border, focusedBorder, pressedBorder, disabledBorder, focusedDisabledBorder)

    /** How far the button grows under focus (1.1 in TV Material); the stand-in never grows. */
    fun scale(
        scale: Float = 1f,
        focusedScale: Float = 1.1f,
        pressedScale: Float = 1f,
        disabledScale: Float = 1f,
        focusedDisabledScale: Float = 1f,
    ): ButtonScale = ButtonScale(scale, focusedScale, pressedScale, disabledScale, focusedDisabledScale)
}

/** A TV Material button, drawn with its resting colours, shape and border: a television only control. */
@Composable
fun Button(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    enabled: Boolean = true,
    scale: ButtonScale = ButtonDefaults.scale(),
    shape: ButtonShape = ButtonDefaults.shape(),
    colors: ButtonColors = ButtonDefaults.colors(),
    border: ButtonBorder = ButtonDefaults.border(),
    contentPadding: PaddingValues = ButtonDefaults.ContentPadding,
    interactionSource: androidx.compose.foundation.interaction.MutableInteractionSource? = null,
    content: @Composable RowScope.() -> Unit,
) {
    androidx.compose.material3.Surface(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        interactionSource = interactionSource,
        shape = shape.shape,
        color = if (enabled) colors.containerColor else colors.disabledContainerColor,
        contentColor = if (enabled) colors.contentColor else colors.disabledContentColor,
        border = border.border.border.takeIf { it.width > 0.dp },
    ) {
        CompositionLocalProvider(LocalContentColor provides if (enabled) colors.contentColor else colors.disabledContentColor) {
            Row(Modifier.padding(contentPadding), verticalAlignment = Alignment.CenterVertically, content = content)
        }
    }
}
