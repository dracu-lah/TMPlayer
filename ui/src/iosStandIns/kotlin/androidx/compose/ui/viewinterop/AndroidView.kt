package androidx.compose.ui.viewinterop

import android.content.Context
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.SideEffect
import androidx.compose.foundation.layout.Spacer
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext

/**
 * A View inside Compose, Android's AndroidView. The View tree on iOS is drawn by its own host
 * (com.tmplayer.ios.AndroidViewHost), which cannot sit inside a composable's place, so the View is
 * made and kept up to date as Android's is, and the place it would fill is left empty. Where the
 * View is a player's picture, the engine has no surface to draw on and the screen shows no video.
 */
@Composable
fun <T : View> AndroidView(
    factory: (Context) -> T,
    modifier: Modifier = Modifier,
    update: (T) -> Unit = {},
) {
    val context = LocalContext.current
    val view = remember { factory(context) }
    SideEffect { update(view) }
    Spacer(modifier)
}
