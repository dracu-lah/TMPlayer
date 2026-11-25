package com.tmplayer.ui.components

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button as TouchButton
import androidx.compose.material3.ButtonDefaults as TouchButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton as TouchTonalButton
import androidx.compose.material3.LocalContentColor as TouchContentColor
import androidx.compose.material3.MaterialTheme as TouchMaterialTheme
import androidx.compose.material3.Text as TouchText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.layout.layout
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.Button as TvButton
import androidx.tv.material3.ButtonDefaults as TvButtonDefaults
import androidx.tv.material3.LocalContentColor as TvContentColor
import androidx.tv.material3.Text as TvText
import com.tmplayer.data.FormFactor
import com.tmplayer.ui.theme.Danger
import com.tmplayer.ui.theme.FloatingTone
import com.tmplayer.ui.theme.FocusRole
import com.tmplayer.ui.theme.LocalDarkTheme
import com.tmplayer.ui.theme.LocalFocusStyle
import com.tmplayer.ui.theme.Tone
import com.tmplayer.ui.theme.focusRing
import com.tmplayer.ui.theme.tmButtonColors

/**
 * One button, drawn by whichever Material the device can actually operate.
 *
 * TV Material's button is built entirely around D-pad focus and never dispatches a tap, so touch
 * gets an ordinary `androidx.compose.material3` button and the remote gets the TV one.
 *
 * The two libraries are never mixed inside one control. They share nothing here but the palette and
 * the [content] the caller passes, which is text and glyphs rather than behaviour.
 */
@Composable
fun TmButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    /** Clearing a history, unstarring everything: the press that cannot simply be pressed again. */
    destructive: Boolean = false,
    /**
     * With [destructive], on a television: the secondary look at rest, red only under focus (WP-02),
     * for a destructive press in a menu opened many times a day, where red text at rest is noise.
     */
    quietAtRest: Boolean = false,
    loading: Boolean = false,
    /** What the button says while [loading]: "Sending code…" in place of "Send me a code". */
    busyLabel: String? = null,
    content: @Composable RowScope.() -> Unit,
) {
    if (FormFactor.isTv(LocalContext.current)) {
        TvButtonBase(
            onClick = onClick,
            modifier = modifier,
            enabled = enabled && !loading,
            colors = when {
                destructive && quietAtRest -> tvDestructiveQuietColors()
                destructive -> tvDestructiveColors()
                else -> tmButtonColors()
            },
            loading = loading,
            busyLabel = busyLabel,
            content = content,
        )
        return
    }

    // Material 3's own filled button, at Material's own height and in the scheme's own colours, so
    // it agrees with a light theme or a wallpaper palette and already meets the minimum touch
    // target.
    val scheme = TouchMaterialTheme.colorScheme
    TouchButton(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled && !loading,
        colors = when {
            // Nothing in the scheme is "the destructive button", so this is the one place a phone
            // still states a colour: error, taken from the scheme rather than from a hex.
            destructive -> TouchButtonDefaults.buttonColors(
                containerColor = scheme.error,
                contentColor = scheme.onError,
            )
            // A dark theme's primary is a pale tint, and a filled button in it is a slab of
            // near-white on a black screen. The container pair is the same hue at the tone a dark
            // theme is built for: dark button, light label.
            LocalDarkTheme.current -> TouchButtonDefaults.buttonColors(
                containerColor = scheme.primaryContainer,
                contentColor = scheme.onPrimaryContainer,
            )
            else -> TouchButtonDefaults.buttonColors()
        },
        content = { TouchFace(loading, busyLabel, content) },
    )
}

/**
 * The one TV button every [TmButton] and [TmSecondaryButton] on a television is (WP-03).
 *
 * TV Material's button grows 1.1 times under focus, so in a full width list, a dialog or a row of
 * buttons beside the screen's edge it spilled past its column and was cut. Here it never grows
 * ([FocusRole.Button]: no scale, a press sinks it to 0.98) and shows focus the way the recipe says:
 * the fill swap alone (`Tone.focusFill`, in the colours the callers pass), plus a ring under High
 * contrast only ([focusRingOnTvButton]). TV Material's own focus border is switched off, so a
 * button never draws a fill and a ring at once.
 *
 * Every TV Material clickable goes through here: `TvButtonImportGuardTest` fails on any other.
 */
@Composable
private fun TvButtonBase(
    onClick: () -> Unit,
    modifier: Modifier,
    enabled: Boolean,
    colors: androidx.tv.material3.ButtonColors,
    loading: Boolean,
    busyLabel: String?,
    content: @Composable RowScope.() -> Unit,
) {
    val interactions = remember { MutableInteractionSource() }
    val focused by interactions.collectIsFocusedAsState()
    // The height the caller forces on the button (a 48 dp header button), seen at the button's own edge while it is
    // measured, and read by the face inside it in the same pass: no state, no recomposition.
    val forced = remember { ForcedHeight() }
    TvButton(
        onClick = onClick,
        modifier = modifier.focusRingOnTvButton(focused).layout { measurable, constraints ->
            forced.px = if (constraints.minHeight == constraints.maxHeight) constraints.minHeight else 0
            val placeable = measurable.measure(constraints)
            layout(placeable.width, placeable.height) { placeable.place(0, 0) }
        },
        enabled = enabled,
        interactionSource = interactions,
        colors = colors,
        scale = TvButtonDefaults.scale(focusedScale = 1f, pressedScale = PRESSED_SCALE),
        border = TvButtonDefaults.border(
            border = Border.None,
            focusedBorder = Border.None,
            pressedBorder = Border.None,
            disabledBorder = Border.None,
            focusedDisabledBorder = Border.None,
        ),
        content = { TvFace(loading, busyLabel, forced, content) },
    )
}

/** The height a caller forced on a TV button, in pixels, 0 when the button wraps its content. */
private class ForcedHeight {
    var px = 0
}

/** A pressed TV button sinks a little instead of growing: the answer to the press without a layout change. */
private const val PRESSED_SCALE = 0.98f

/**
 * The [FocusRole.Button] ring on a television: only under High contrast (with its outer line and
 * keyline), round the pill inside its bounds; otherwise the fill carries the focus alone.
 */
@Composable
private fun Modifier.focusRingOnTvButton(focused: Boolean): Modifier {
    val style = LocalFocusStyle.current
    val dark = LocalDarkTheme.current
    return if (focused && FocusRole.Button.recipe.ringShown(style, dark)) focusRing(true, CircleShape) else this
}

/** The TV button's red, kept here so the destructive press looks the same on both devices. */
@Composable
private fun tvDestructiveColors() = TvButtonDefaults.colors(
    containerColor = Tone.surface,
    contentColor = Danger,
    focusedContainerColor = Danger,
    focusedContentColor = Color.White,
    pressedContainerColor = Danger,
    pressedContentColor = Color.White,
    disabledContainerColor = Tone.surface,
    disabledContentColor = Tone.muted,
)

/** [tvDestructiveColors] with the secondary button's look at rest: Danger only while the remote is on it. */
@Composable
private fun tvDestructiveQuietColors() = TvButtonDefaults.colors(
    containerColor = FloatingTone.control,
    contentColor = Tone.text,
    focusedContainerColor = Danger,
    focusedContentColor = Color.White,
    pressedContainerColor = Danger,
    pressedContentColor = Color.White,
    disabledContainerColor = Tone.surface,
    disabledContentColor = Tone.muted,
)

/** The quieter of the pair: the one beside a primary button that backs out rather than commits. */
@Composable
fun TmSecondaryButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    loading: Boolean = false,
    busyLabel: String? = null,
    content: @Composable RowScope.() -> Unit,
) {
    if (FormFactor.isTv(LocalContext.current)) {
        TvButtonBase(
            onClick = onClick,
            modifier = modifier,
            enabled = enabled && !loading,
            colors = tmButtonColors(),
            loading = loading,
            busyLabel = busyLabel,
            content = content,
        )
        return
    }

    // The tonal button is Material's answer to exactly this question: the same shape and weight as
    // the filled one beside it, in a container quiet enough that the eye still knows which of the
    // two is the one being offered.
    TouchTonalButton(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled && !loading,
        content = { TouchFace(loading, busyLabel, content) },
    )
}

/**
 * The two things a button can say, stacked, with only one of them visible.
 *
 * A press that starts a network call says so by changing the words on the button. Swapping them
 * outright would resize it, and on a remote a button that narrows under the focus ring drags the
 * ring with it, which reads as focus having jumped on its own. Drawing both faces in one box keeps
 * the button as wide as the longer of the two. The hidden face is cleared from the semantics too,
 * so a screen reader reads one label rather than both.
 */
@Composable
private fun ButtonFace(
    loading: Boolean,
    busy: @Composable RowScope.() -> Unit,
    content: @Composable RowScope.() -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier, contentAlignment = Alignment.Center) {
        Face(hidden = loading, content = content)
        Face(hidden = !loading, content = busy)
    }
}

@Composable
private fun Face(hidden: Boolean, content: @Composable RowScope.() -> Unit) {
    Row(
        modifier = Modifier
            .alpha(if (hidden) 0f else 1f)
            .then(if (hidden) Modifier.clearAndSetSemantics {} else Modifier),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

/**
 * The busy face, in the television's Material.
 *
 * Its content colour and its text style are read from inside the button rather than passed in,
 * because that is the only place either of them exists: the two Materials each provide their own,
 * and a spinner drawn in the other library's default is black on a dark button.
 */
@Composable
private fun TvFace(loading: Boolean, busyLabel: String?, forced: ForcedHeight, content: @Composable RowScope.() -> Unit) {
    // TV Material lays its content row at the top of the button's box: given a height taller than the row's own
    // 40 dp (a 48 dp header button) the words sat 4 dp above the middle (Find a setting, Mi TV, 2026-11-25). When the
    // caller forces a height, the face asks for as much (up to what the row may take), so the row is as tall as the
    // button and the face is centred in it. A button that wraps its content (no forced height) is left alone.
    ButtonFace(
        loading = loading,
        busy = {
            BusySpinner(loading, TvContentColor.current)
            if (busyLabel != null) TvText(busyLabel)
        },
        content = content,
        modifier = Modifier.layout { measurable, constraints ->
            val placeable = measurable.measure(constraints)
            val height = maxOf(placeable.height, forced.px.coerceAtMost(constraints.maxHeight))
            layout(placeable.width, height) { placeable.place(0, (height - placeable.height) / 2) }
        },
    )
}

@Composable
private fun TouchFace(
    loading: Boolean,
    busyLabel: String?,
    content: @Composable RowScope.() -> Unit,
) {
    // Most screens are written once for TV and phone, so the label inside is often the TV Text,
    // which reads the TV content colour and not this button's. Without the bridge a light theme drew
    // "Open Saved Messages" in the TV default, dark teal on the teal button.
    CompositionLocalProvider(TvContentColor provides TouchContentColor.current) {
        ButtonFace(
            loading = loading,
            busy = {
                BusySpinner(loading, TouchContentColor.current)
                if (busyLabel != null) TouchText(busyLabel)
            },
            content = content,
        )
    }
}

/**
 * The busy face's spinner, composed only while [loading]. At rest its room is held by a blank of
 * the same size, so the button keeps the width of its wider face, but nothing animates: an
 * indeterminate indicator left composed under an invisible face asks for a frame every vsync,
 * which on a screen full of buttons is a redraw that never stops (and a 1 GB stick's battery and
 * smoothness spent on nothing).
 */
@Composable
private fun BusySpinner(loading: Boolean, color: Color) {
    if (loading) {
        Spinner(color)
    } else {
        Spacer(Modifier.size(SPINNER + 8.dp, SPINNER))
    }
}

/** Small enough to sit on the text's own line, and in the label's colour rather than the accent. */
@Composable
private fun Spinner(color: Color) {
    CircularProgressIndicator(
        modifier = Modifier.size(SPINNER),
        color = color,
        strokeWidth = 2.dp,
    )
    Spacer(Modifier.size(8.dp))
}

private val SPINNER = 16.dp
