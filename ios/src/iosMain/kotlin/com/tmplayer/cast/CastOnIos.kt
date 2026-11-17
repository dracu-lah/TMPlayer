package com.tmplayer.cast

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * The video page's cast button. Casting is not on iOS yet (docs/IOS.md, "Not in scope"),
 * so there is no button, as in Android's builds without a cast route.
 */
@Composable
@Suppress("UNUSED_PARAMETER")
fun CastPageButton(modifier: Modifier = Modifier) = Unit

/**
 * What casting the device has, Android's CastSupport as the player reads it. Casting is left out
 * on iOS (the maintainer, 2026-11-12; docs/IOS.md, "Not in scope"): nothing is
 * available, the answer Android's player gets where no cast route exists, and it leaves its Cast
 * button hidden.
 */
interface CastSupport {
    val available: Boolean
}

/** The casting the app has, Android's entry point. */
object Cast {
    private val none = object : CastSupport {
        override val available: Boolean = false
    }

    @Suppress("UNUSED_PARAMETER")
    fun support(context: android.content.Context): CastSupport = none
}
