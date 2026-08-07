package com.tmplayer.ui.components

import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

/**
 * A short confirmation, for an action whose result the viewer cannot see happen.
 *
 * Deliberately rare. Most of this app answers for itself — a card that vanishes, a pill that
 * changes its label, a list that redraws — and a message about something already on screen is
 * noise. What earns one is the opposite case: the effect lands somewhere the viewer is not
 * looking, on a screen they are about to leave, or nowhere visible at all.
 *
 * Android's own toast is used rather than a composable of our own: it survives the screen that
 * raised it going away, which is exactly the case that needs it.
 */
@Composable
fun rememberToast(): (String) -> Unit {
    val context = LocalContext.current
    return remember(context) {
        { message: String -> Toast.makeText(context, message, Toast.LENGTH_SHORT).show() }
    }
}
