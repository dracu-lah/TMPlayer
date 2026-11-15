package com.tmplayer.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.tmplayer.ui.icons.TmIcons
import com.tmplayer.ui.theme.FocusVisibility
import com.tmplayer.ui.theme.Tone
import com.tmplayer.ui.theme.focusRing

/**
 * The clear button of every search box: a cross in a soft round disc, so it reads as "empty this
 * field" and not as Close, which dismisses a page or cancels (user, 2026-11-16). The look is
 * defined here once: a [disc] wide circle of the text colour at 12 percent, the cross in
 * [Tone.muted], centred in a [target] wide touch area. It takes the focus ring on a television.
 */
@Composable
fun ClearButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    target: Dp = 48.dp,
    disc: Dp = 28.dp,
) {
    val interactions = remember { MutableInteractionSource() }
    val focused by interactions.collectIsFocusedAsState()
    Box(
        modifier
            .size(target)
            .pressScale(interactions)
            .clip(CircleShape)
            .clickable(interactionSource = interactions, indication = null, role = Role.Button, onClickLabel = label, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(disc)
                .focusRing(focused, CircleShape)
                .clip(CircleShape)
                // Under the remote the disc fills as every focused control does, so it cannot be missed.
                .background(if (focused && FocusVisibility.visible) Tone.focusFill else Tone.text.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(TmIcons.Close, contentDescription = label, tint = if (focused && FocusVisibility.visible) Tone.onFocusFill else Tone.muted, modifier = Modifier.size(disc * 0.55f))
        }
    }
}
