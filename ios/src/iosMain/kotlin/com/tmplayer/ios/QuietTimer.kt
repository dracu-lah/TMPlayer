package com.tmplayer.ios

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.time.TimeSource

/**
 * Upstream's Home carousel times each page by animating a value that nothing draws, from where it
 * stands to 1, linearly (`progress` in HomeCarousel.kt). On Android that is a frame callback and
 * nothing more. On iOS every frame an animation waits for is a frame Compose draws whole, so Home
 * was drawn 60 times a second with nothing on it moving: 43 % of a core on the iPhone 13, against
 * 3 % without the carousel (2026-11-12). The iOS build calls this in its place (ios/build.gradle.kts):
 * the same time, waited out with a timer. The value reaches 1 at the same moment, and a pause (a
 * touch, Home out of sight) leaves it where the animation would have, so the page carries on from
 * there as on Android.
 */
object QuietTimer {
    suspend fun run(progress: Animatable<Float, AnimationVector1D>, durationMs: Long, clock: TimeSource = TimeSource.Monotonic) {
        val from = progress.value
        val started = clock.markNow()
        try {
            if (durationMs > 0) delay(durationMs)
        } finally {
            val done = if (durationMs <= 0) 1f else (started.elapsedNow().inWholeMilliseconds.toFloat() / durationMs).coerceIn(0f, 1f)
            withContext(NonCancellable) { progress.snapTo(from + (1f - from) * done) }
        }
    }
}
