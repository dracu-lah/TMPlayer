package com.tmplayer.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.input.pointer.pointerInput

/**
 * A click on the dim behind a panel closes it. Pointer only: the dim is no Tab stop (a screen
 * sized, markless stop that Enter would close the panel from) and no ripple floods the picture;
 * Esc and Back are the keyboard's and the remote's way, as every panel says. The one scrim helper
 * for the phone, the television and the desktop, so no dim is a hand-rolled `clickable`.
 */
@Composable
fun Modifier.scrimDismiss(onClose: () -> Unit): Modifier = this
    .focusProperties { canFocus = false }
    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClose)

/**
 * A card that sits on a [scrimDismiss] dim: a click inside it is not a click on the dim. A tap
 * handler rather than a clickable, so the card is no Tab stop with nothing on it and a screen
 * reader hears no button that does nothing.
 */
fun Modifier.swallowClicks(): Modifier = this.pointerInput(Unit) { detectTapGestures { } }
