package com.tmplayer.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSOperationQueue
import platform.UIKit.UIAccessibilityIsReduceMotionEnabled
import platform.UIKit.UIAccessibilityReduceMotionStatusDidChangeNotification

/** iOS's Reduce Motion (Settings, Accessibility, Motion), followed as it changes. See [Motion]. */
@Composable
fun rememberReducedMotion(): Boolean {
    var reduced by remember { mutableStateOf(UIAccessibilityIsReduceMotionEnabled()) }
    DisposableEffect(Unit) {
        val observer = NSNotificationCenter.defaultCenter.addObserverForName(
            UIAccessibilityReduceMotionStatusDidChangeNotification,
            null,
            NSOperationQueue.mainQueue,
        ) { _ -> reduced = UIAccessibilityIsReduceMotionEnabled() }
        onDispose { NSNotificationCenter.defaultCenter.removeObserver(observer) }
    }
    return reduced
}

/**
 * Whether the system lets animations run, which Android's Views ask before they move: on iOS the
 * same switch is Reduce Motion, so the answer is its opposite.
 */
@Suppress("UNUSED_PARAMETER")
fun systemAnimatorsEnabled(context: android.content.Context): Boolean = !UIAccessibilityIsReduceMotionEnabled()

/** iOS's own fonts already cover Chinese, Japanese, Korean, the Indic scripts and Arabic. */
@Composable
internal fun ProvideFallbackFonts(content: @Composable () -> Unit) = content()
