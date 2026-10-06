package com.tmplayer.ui.components

import android.view.WindowManager
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.BoxWithConstraintsScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import com.tmplayer.ui.theme.Floating
import com.tmplayer.ui.theme.LocalOnFloating

/**
 * The window every panel the app draws itself floats in: the television's prompts, menus and QR
 * codes, and the phone's support codes.
 *
 * A window of its own rather than a Box over the layout, because drawn inline a panel is an
 * ordinary sibling and anything composed after it would cover it. The dim behind it is the
 * window's own, set to [Floating.SCRIM_ALPHA], which is what a stock Material dialog gets on the
 * phone and the desktop. These panels used to paint an 82 per cent black layer as well, on top of
 * the window's dim, so the television's screen went near black behind every prompt.
 *
 * The content is centred in the whole screen and told it sits on a floating surface, so a button
 * at rest takes the step above the panel's fill rather than vanishing into it.
 *
 * @param ignoreRelease for a panel opened by a hold of OK, whose release would otherwise choose
 *   the first thing in it on the viewer's behalf.
 */
@Composable
fun FloatingWindow(
    onDismiss: () -> Unit,
    ignoreRelease: Boolean = false,
    content: @Composable BoxWithConstraintsScope.() -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        val window = (LocalView.current.parent as? DialogWindowProvider)?.window
        SideEffect {
            window?.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            window?.setDimAmount(Floating.SCRIM_ALPHA)
        }
        CompositionLocalProvider(LocalOnFloating provides true) {
            BoxWithConstraints(
                Modifier
                    .fillMaxSize()
                    .then(if (ignoreRelease) Modifier.ignoreStrayRelease() else Modifier),
                contentAlignment = Alignment.Center,
                content = content,
            )
        }
    }
}
