package com.tmplayer.ui.components

import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.Button as TouchButton
import androidx.compose.material3.ButtonDefaults as TouchButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button as TvButton
import androidx.tv.material3.ButtonDefaults as TvButtonDefaults
import com.tmplayer.data.FormFactor
import com.tmplayer.ui.theme.Accent
import com.tmplayer.ui.theme.Danger
import com.tmplayer.ui.theme.SurfaceDark
import com.tmplayer.ui.theme.TextMuted
import com.tmplayer.ui.theme.TextPrimary
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

    TouchButton(
        onClick = onClick,
        // Material's own button is already 40 dp tall, which is short of what a fingertip is
        // entitled to, and this screen has nothing to gain from the saved 8 dp.
        modifier = modifier.heightIn(min = 48.dp),
        enabled = enabled,
        colors = TouchButtonDefaults.buttonColors(
            containerColor = if (destructive) Danger else Accent,
            contentColor = Color.White,
            disabledContainerColor = SurfaceDark,
            disabledContentColor = TextMuted,
        ),
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

    TouchButton(
        onClick = onClick,
        modifier = modifier.heightIn(min = 48.dp),
        enabled = enabled,
        colors = TouchButtonDefaults.buttonColors(
            containerColor = SurfaceDark,
            contentColor = TextPrimary,
            disabledContainerColor = SurfaceDark,
            disabledContentColor = TextMuted,
        ),
        content = content,
    )
}
