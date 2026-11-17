package androidx.activity.compose

import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionContext

/** Hands the activity's content to the iOS shell, which draws it (see [ComponentActivity.content]). */
@Suppress("UNUSED_PARAMETER")
fun ComponentActivity.setContent(parent: CompositionContext? = null, content: @Composable () -> Unit) {
    this.content = content
}
