package com.tmplayer.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import com.tmplayer.data.SettingsStore
import com.tmplayer.data.UiScale
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Draws [content] at the viewer's interface size (CP59): the density is multiplied by
 * [UiScale.factor], so every dp and every sp grows or shrinks together and layouts that measure
 * their space (rows, grids, panels sized from `BoxWithConstraints`) simply find less or more of it.
 *
 * The font scale is passed through untouched. An sp is already a dp times the font scale, so the
 * system's own text size still applies on top of the interface size, once, rather than twice.
 *
 * On a television ([keepTextSize], passed in because `ui/src/shared` cannot ask Android) a size
 * below Default shrinks spacing, not text: the font scale is divided by the factor, so 14 sp is
 * 14 sp of the screen at Smaller as at Default and the type floor holds at every size (WP-04). Larger
 * sizes grow the text with the rest. The phone and the desktop are unchanged.
 *
 * Always the same provider, even at [UiScale.Default], so changing the size recomposes the tree in
 * place instead of throwing its state away.
 */
@Composable
fun UiScaled(scale: UiScale, keepTextSize: Boolean = false, content: @Composable () -> Unit) {
    val base = LocalDensity.current
    val scaled = remember(base.density, base.fontScale, scale, keepTextSize) {
        val f = scale.factor
        Density(base.density * f, if (keepTextSize && f < 1f) base.fontScale / f else base.fontScale)
    }
    CompositionLocalProvider(LocalDensity provides scaled, content = content)
}

/**
 * The interface size as state, starting from the stored one rather than the default, so a viewer
 * on Largest does not see the first frame of every window drawn small and then jump.
 *
 * The first read in a process waits for the settings file for a moment at most; every composition
 * after that starts from the last value seen.
 */
@Composable
fun rememberUiScale(settings: SettingsStore): State<UiScale> {
    val initial = remember(settings) {
        UiScaleMemory.last ?: runBlocking {
            withTimeoutOrNull(FIRST_READ_MS) { settings.uiScale.first() }
        } ?: settings.unchosenUiScale
    }
    val state = settings.uiScale.collectAsState(initial = initial)
    UiScaleMemory.last = state.value
    return state
}

/**
 * The stored size, read by the app off the main thread as it starts, so the first composition
 * finds it here and does not wait on the settings file: that wait sat inside the first frame of a
 * cold start, on the 1 GB TV's slow storage among others.
 */
fun primeUiScale(scale: UiScale) {
    if (UiScaleMemory.last == null) UiScaleMemory.last = scale
}

/** The size last drawn with, for the next window or panel to start from. */
private object UiScaleMemory {
    @Volatile
    var last: UiScale? = null
}

private const val FIRST_READ_MS = 250L
