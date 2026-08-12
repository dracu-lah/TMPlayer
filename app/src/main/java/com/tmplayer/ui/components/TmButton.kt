package com.tmplayer.ui.components

import androidx.compose.foundation.layout.RowScope
import androidx.compose.material3.Button as TouchButton
import androidx.compose.material3.ButtonDefaults as TouchButtonDefaults
import androidx.compose.material3.FilledTonalButton as TouchTonalButton
import androidx.compose.material3.MaterialTheme as TouchMaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.tv.material3.Button as TvButton
import androidx.tv.material3.ButtonDefaults as TvButtonDefaults
import com.tmplayer.data.FormFactor
import com.tmplayer.ui.theme.Danger
import com.tmplayer.ui.theme.LocalDarkTheme
import com.tmplayer.ui.theme.SurfaceDark
import com.tmplayer.ui.theme.TextMuted
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
    content: @Composable RowScope.() -> Unit,
) {
    if (FormFactor.isTv(LocalContext.current)) {
        TvButton(
            onClick = onClick,
            modifier = modifier,
            enabled = enabled,
            colors = if (destructive) tvDestructiveColors() else tmButtonColors(),
            content = content,
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
        enabled = enabled,
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
        content = content,
    )
}

/** The TV button's red, kept here so the destructive press looks the same on both devices. */
@Composable
private fun tvDestructiveColors() = TvButtonDefaults.colors(
    containerColor = SurfaceDark,
    contentColor = Danger,
    focusedContainerColor = Danger,
    focusedContentColor = Color.White,
    pressedContainerColor = Danger,
    pressedContentColor = Color.White,
    disabledContainerColor = SurfaceDark,
    disabledContentColor = TextMuted,
)

/** The quieter of the pair: the one beside a primary button that backs out rather than commits. */
@Composable
fun TmSecondaryButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit,
) {
    if (FormFactor.isTv(LocalContext.current)) {
        TvButton(
            onClick = onClick,
            modifier = modifier,
            enabled = enabled,
            colors = tmButtonColors(),
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
        enabled = enabled,
        content = content,
    )
}
