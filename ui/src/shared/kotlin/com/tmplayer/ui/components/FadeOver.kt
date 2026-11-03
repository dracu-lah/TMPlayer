package com.tmplayer.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import com.tmplayer.ui.theme.Motion
import com.tmplayer.ui.theme.rememberReducedMotion

/**
 * A picture replaced by a better one: the new one fades in over the old, which stays fully drawn
 * under it until the fade ends. A cross-fade lowers both at once, so a card dipped to the surface
 * half way from its blurred preview to its picture and read as a flash (user, 2026-10-22: "the
 * blurred cards swapping to the good image does not look good"). The first [target] is drawn at
 * once, without a fade; with reduced motion every later one is too.
 */
@Composable
fun <T : Any> FadeOver(
    target: T,
    modifier: Modifier = Modifier,
    durationMs: Int = Motion.Slow,
    content: @Composable (T) -> Unit,
) {
    // None on a television: the better picture replaces the preview at once, with no second
    // picture drawn under it for the length of a fade. Nothing of the fade is composed there
    // either (no Animatable, effect or second box): every avatar of a picker row a held Down
    // brings in paid for them (HX76). The flag is fixed for the process, so the groups never swap.
    if (Motion.television) {
        Box(modifier, propagateMinConstraints = true) { key(target) { content(target) } }
        return
    }
    val still = rememberReducedMotion() || Motion.reduced
    var top by remember { mutableStateOf(target) }
    var under by remember { mutableStateOf<T?>(null) }
    val alpha = remember { Animatable(1f) }
    LaunchedEffect(target) {
        if (target == top) return@LaunchedEffect
        under = top
        top = target
        if (still) {
            alpha.snapTo(1f)
        } else {
            alpha.snapTo(0f)
            alpha.animateTo(1f, tween(durationMs))
        }
        under = null
    }
    Box(modifier) {
        under?.let { old -> key(old) { content(old) } }
        key(top) {
            // still (reduced motion): no graphicsLayer and no Animatable read, so the draw pass
            // records no layer per tile for a fade that never runs.
            if (still) {
                Box(Modifier.matchParentSize()) { content(top) }
            } else {
                Box(Modifier.matchParentSize().graphicsLayer { this.alpha = alpha.value }) { content(top) }
            }
        }
    }
}
