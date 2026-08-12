package com.tmplayer.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button as TouchButton
import androidx.compose.material3.ButtonDefaults as TouchButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton as TouchTonalButton
import androidx.compose.material3.LocalContentColor as TouchContentColor
import androidx.compose.material3.MaterialTheme as TouchMaterialTheme
import androidx.compose.material3.Text as TouchText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button as TvButton
import androidx.tv.material3.ButtonDefaults as TvButtonDefaults
import androidx.tv.material3.LocalContentColor as TvContentColor
import androidx.tv.material3.Text as TvText
import com.tmplayer.data.FormFactor
import com.tmplayer.ui.theme.Danger
import com.tmplayer.ui.theme.LocalDarkTheme
import com.tmplayer.ui.theme.Tone
import com.tmplayer.ui.theme.tmButtonColors

/**
 * One button, drawn by whichever Material the device can actually operate.
 *
 * TV Material's button is built entirely around D-pad focus and never dispatches a tap: verified
 * on a touch-only phone, where the sign-in button looked pressable, did nothing to a finger, and
 * worked the instant a D-pad centre was injected. So the touch branch is an ordinary
 * `androidx.compose.material3` button and the TV branch keeps the one it has always had, with its
 * focus colours untouched.
 *
 * The two libraries are never mixed inside one control. They share nothing here but the palette
 * and the [content] the caller passes, which is text and glyphs rather than behaviour.
 */
@Composable
fun TmButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    /** Clearing a history, unstarring everything: the press that cannot simply be pressed again. */
    destructive: Boolean = false,
    loading: Boolean = false,
    /** What the button says while [loading]: "Sending code…" in place of "Send me a code". */
    busyLabel: String? = null,
    content: @Composable RowScope.() -> Unit,
) {
    if (FormFactor.isTv(LocalContext.current)) {
        TvButton(
            onClick = onClick,
            modifier = modifier,
            enabled = enabled && !loading,
            colors = if (destructive) tvDestructiveColors() else tmButtonColors(),
            content = { TvFace(loading, busyLabel, content) },
        )
        return
    }

    // Material 3's own filled button, at Material's own height and in the scheme's own colours.
    // The colour table that used to be here named the television's six literals, which on a phone
    // with a light theme or a wallpaper palette meant a button that agreed with nothing around it,
    // and the 48 dp floor it was given has been Material's minimum touch target for a while now
    // without anyone having to ask for it.
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
            // A dark theme's primary is a pale tint, so the filled button came out as a slab of
            // near-white blue on a black screen: the brightest thing in the room, and a page with
            // two of them read as a warning rather than as a next step. The container pair is the
            // same blue at the tone a dark theme is actually built for, so the button is dark, the
            // label on it is light, and the contrast is better than what it replaces.
            LocalDarkTheme.current -> TouchButtonDefaults.buttonColors(
                containerColor = scheme.primaryContainer,
                contentColor = scheme.onPrimaryContainer,
            )
            else -> TouchButtonDefaults.buttonColors()
        },
        content = { TouchFace(loading, busyLabel, content) },
    )
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
        TvButton(
            onClick = onClick,
            modifier = modifier,
            enabled = enabled && !loading,
            colors = tmButtonColors(),
            content = { TvFace(loading, busyLabel, content) },
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
 * A press that starts a network call has to say so, and the honest way to say it is to change the
 * words on the button. Swapping the words outright would resize it, and on a remote a button that
 * narrows under the focus ring drags the ring with it, which reads as the focus having jumped
 * somewhere on its own. Drawing both faces in the same box means the button is always as wide as
 * the longer of the two and never moves between them. The hidden face is taken out of the
 * semantics as well as out of the picture, so a screen reader reads one label rather than both.
 */
@Composable
private fun ButtonFace(
    loading: Boolean,
    busy: @Composable RowScope.() -> Unit,
    content: @Composable RowScope.() -> Unit,
) {
    Box(contentAlignment = Alignment.Center) {
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
private fun TvFace(loading: Boolean, busyLabel: String?, content: @Composable RowScope.() -> Unit) {
    ButtonFace(
        loading = loading,
        busy = {
            Spinner(TvContentColor.current)
            if (busyLabel != null) TvText(busyLabel)
        },
        content = content,
    )
}

@Composable
private fun TouchFace(
    loading: Boolean,
    busyLabel: String?,
    content: @Composable RowScope.() -> Unit,
) {
    ButtonFace(
        loading = loading,
        busy = {
            Spinner(TouchContentColor.current)
            if (busyLabel != null) TouchText(busyLabel)
        },
        content = content,
    )
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
