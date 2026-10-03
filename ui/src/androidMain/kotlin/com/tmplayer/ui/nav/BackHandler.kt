package com.tmplayer.ui.nav

import androidx.compose.runtime.Composable
import androidx.activity.compose.BackHandler as ActivityBackHandler

/**
 * Takes the system back gesture or the remote's Back while [enabled], and calls [onBack] instead.
 *
 * The shared screens' one way to claim Back. On Android it is the activity's own back dispatcher,
 * exactly as before; the desktop's version answers Esc, Backspace, Alt+Left and the mouse's back
 * button through a [BackStack] (jvmMain).
 */
@Composable
fun BackHandler(enabled: Boolean = true, onBack: () -> Unit) {
    ActivityBackHandler(enabled = enabled, onBack = onBack)
}
