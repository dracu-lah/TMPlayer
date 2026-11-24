package com.tmplayer.ui.components

import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableFloatState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import kotlin.coroutines.cancellation.CancellationException

/**
 * Back out of a page with the system's preview (WP-44), the way Google's apps do: while the gesture is
 * under the finger [progress] runs from 0 to 1 and [pageBackScale] shrinks the page with it; letting
 * go commits ([onBack]), pulling it back cancels and the page returns. Android 14 shows the preview;
 * below it the system sends the one final event and this is a plain Back.
 */
@Composable
fun PredictivePageBack(progress: MutableFloatState, enabled: Boolean = true, onBack: () -> Unit) {
    PredictiveBackHandler(enabled) { events ->
        try {
            events.collect { progress.floatValue = it.progress }
            progress.floatValue = 0f
            onBack()
        } catch (cancelled: CancellationException) {
            progress.floatValue = 0f
            throw cancelled
        }
    }
}

/** The page at 0.92 of its size at full progress, unless motion is reduced (then it stays put). */
fun Modifier.pageBackScale(progress: Float, reduced: Boolean): Modifier =
    if (reduced || progress <= 0f) {
        this
    } else {
        graphicsLayer {
            val scale = 1f - PAGE_BACK_SHRINK * progress.coerceIn(0f, 1f)
            scaleX = scale
            scaleY = scale
        }
    }

private const val PAGE_BACK_SHRINK = 0.08f
