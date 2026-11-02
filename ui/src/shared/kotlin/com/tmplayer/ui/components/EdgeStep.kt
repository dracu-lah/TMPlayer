package com.tmplayer.ui.components

import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.snap
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp

/**
 * A list or row that keeps still while the focused item stands between its margins ([start],
 * [end]) and otherwise moves just far enough to bring it inside them, at once rather than slid.
 *
 * The television's own rule stands every focused item a third of the way in and slides there,
 * which is a scroll and a fresh layout of the list on every frame of the slide for every press
 * of the remote: on the Mi TV that was most of a held Down in the chat picker (HX76), and the
 * reason Home's rows step. Off ([enabled] false), the platform's own rule stands.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun EdgeStep(start: Dp, end: Dp, enabled: Boolean = true, content: @Composable () -> Unit) {
    if (!enabled) {
        content()
        return
    }
    val density = LocalDensity.current
    val startPx = with(density) { start.toPx() }
    val endPx = with(density) { end.toPx() }
    val spec = remember(startPx, endPx) {
        object : BringIntoViewSpec {
            @Deprecated("Matches the interface; the snap is the point.")
            override val scrollAnimationSpec: AnimationSpec<Float> = snap()

            override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float = when {
                offset < startPx -> offset - startPx
                offset + size > containerSize - endPx -> offset + size - (containerSize - endPx)
                else -> 0f
            }
        }
    }
    CompositionLocalProvider(LocalBringIntoViewSpec provides spec, content = content)
}
