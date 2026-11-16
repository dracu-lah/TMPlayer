package com.tmplayer.ui.components

import com.tmplayer.ui.theme.focusScale
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.tmplayer.ui.browse.AddChatsRequest
import com.tmplayer.ui.i18n.LocalStrings
import com.tmplayer.ui.icons.TmIcons
import com.tmplayer.ui.theme.Motion
import com.tmplayer.ui.theme.Tone
import com.tmplayer.ui.theme.collectIsFocusVisibleAsState
import com.tmplayer.ui.theme.focusRing

/**
 * The one-tap way into the Choose chats screen (add and remove chats): a person with a plus, on
 * the top bar of Home and of the Chats list on the phone, the television and the desktop.
 *
 * It opens the screen Settings' "Choose chats" row opens, through [AddChatsRequest], which each
 * shell answers once (the phone's and the television's refuses a second request while the screen
 * is up, so a double tap never stacks two pages) and from which Back returns to where the viewer
 * was. It does no work of its own, so a 3,000 chat account pays nothing for it on the bar.
 *
 * Named by `picker.choose_chats`: the tooltip on a pointer ([IconTip], with [shortcut] where the
 * desktop has a key), the name under the remote's focus on a television, and the screen reader's
 * description. It is a fixed [size] square whatever the font scale, so a large font never pushes
 * the controls beside it, and it never mirrors in right to left (a person is a person).
 *
 * [onArt] puts the glyph in the colours for a picture with a soft disc behind it (Home's carousel).
 * [modifier] is the button's place in its parent (an `align`, a padding): the tip's box is what the
 * parent lays out. [focus] is the remote's or the keyboard's handle on the button itself.
 */
@Composable
fun ChooseChatsButton(
    modifier: Modifier = Modifier,
    focus: FocusRequester? = null,
    size: Dp = ChooseChatsButtonSize,
    onArt: Boolean = false,
    shortcut: String? = null,
    side: TipSide = TipSide.Below,
    /**
     * Writes the name beside the glyph, as a pill, where the font is at its normal size: a bare
     * person-plus was the only way to add chats and nobody tapped it (Raju 4.3). At a large font
     * the pill would push its neighbours, so the glyph stands alone again, as everywhere else.
     */
    labelled: Boolean = false,
    onClick: () -> Unit = { AddChatsRequest.ask() },
) {
    val label = LocalStrings.current.pickerChooseChats
    val written = labelled && LocalDensity.current.fontScale <= LABEL_MAX_FONT_SCALE
    val interactions = remember { MutableInteractionSource() }
    val focused by interactions.collectIsFocusVisibleAsState()
    val still = rememberLessMotion()
    val scale by animateFloatAsState(
        targetValue = if (still || !focused) 1f else focusScale,
        animationSpec = Motion.standard(Motion.Fast),
        label = "chooseChatsScale",
    )
    val fill by animateColorAsState(
        when {
            focused -> Tone.focusFill
            onArt -> Color.Black.copy(alpha = 0.32f)
            else -> Color.Transparent
        },
        Motion.standard(Motion.Fast),
        label = "chooseChatsFill",
    )
    val ink = when {
        focused -> Tone.onFocusFill
        onArt -> Tone.onArt
        else -> Tone.text
    }
    // A box of its own for the caller's place: a tooltip box does not pass an `align` on to its parent.
    Box(modifier) {
        IconTip(label, shortcut = shortcut, side = side) {
            Row(
                Modifier
                    .then(if (focus != null) Modifier.focusRequester(focus) else Modifier)
                    .then(if (written) Modifier.height(size).widthIn(min = size) else Modifier.size(size))
                    .pressScale(interactions)
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                    }
                    .clip(CircleShape)
                    .background(fill)
                    .focusRing(focused, CircleShape)
                    .clickable(interactionSource = interactions, indication = androidx.compose.foundation.LocalIndication.current, role = Role.Button, onClick = onClick)
                    .semantics { contentDescription = label }
                    .then(if (written) Modifier.padding(start = 12.dp, end = 16.dp) else Modifier),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(TmIcons.PersonAdd, contentDescription = null, tint = ink, modifier = Modifier.size(size * ICON_SHARE))
                if (written) {
                    Text(
                        label,
                        style = MaterialTheme.typography.labelLarge,
                        color = ink,
                        maxLines = 1,
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
            }
        }
    }
}

/** A thumb's smallest target; the television's and the desktop's bars pass their row's height. */
val ChooseChatsButtonSize: Dp = 48.dp

private const val ICON_SHARE = 0.5f

/** The largest font scale at which the pill's word still fits beside the title (Raju 4.3 asks for 1.0). */
private const val LABEL_MAX_FONT_SCALE = 1.15f
