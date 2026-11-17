package com.tmplayer.desktop.player

import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import com.tmplayer.ui.theme.collectIsFocusVisibleAsState
import com.tmplayer.ui.theme.focusDrawn

/** The light wash a player control takes under the pointer (one value for every pill and button). */
internal val OverlayHover = Color(0x33FFFFFF)

/**
 * The ring a keyboard-focused player control wears. The picture behind the controls can be any
 * colour, and Material's own focus layer is a tenth of white on it, which is not a mark a person
 * tabbing through the bar can follow. White, in [com.tmplayer.ui.theme.FocusStyle.ring] width, and
 * only while [com.tmplayer.ui.theme.FocusVisibility] says focus is shown, so a click leaves none.
 * [interactions] is the source the control's own `clickable` was given.
 */
@Composable
internal fun Modifier.overlayFocusRing(interactions: InteractionSource, shape: Shape): Modifier =
    focusDrawn(interactions.collectIsFocusVisibleAsState(), Color.White, shape)

