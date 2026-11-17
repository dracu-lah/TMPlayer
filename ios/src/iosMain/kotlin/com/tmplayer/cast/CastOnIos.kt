package com.tmplayer.cast

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import java.io.Closeable
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

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

/** Whether a TV is in reach, which draws the player's Cast button: never on iOS, so [shown] stays false and nothing is searched for. */
@Suppress("UNUSED_PARAMETER")
class CastPresenceScan(
    support: CastSupport,
    scope: CoroutineScope,
    canSearch: () -> Boolean = { true },
) {
    private val _shown = MutableStateFlow(false)
    val shown: StateFlow<Boolean> = _shown

    fun start() = Unit

    fun stop() = Unit
}

/** Android 17's local network permission: iOS asks for its own when something needs it, and casting is not on iOS. Always granted here. */
object LocalNetworkPermission {
    @Suppress("UNUSED_PARAMETER")
    fun granted(context: android.content.Context): Boolean = true
}

/** Hides the Cast button, as Android's does when no TV is found. */
@Suppress("UNUSED_PARAMETER")
fun showCastButton(button: android.view.View, shown: Boolean, neighbour: android.view.View?) {
    button.visibility = android.view.View.GONE
}

/**
 * A video served to a player on this device over HTTP, which libVLC and external players read.
 * Neither is on iOS (the video engines read the video themselves), so no session can be started.
 */
class LoopbackStream private constructor() : Closeable {
    /** How a session behaves. */
    class Config(val onFailure: (IOException) -> Unit = {})

    val url: String = ""

    override fun close() = Unit

    companion object {
        /** Throws: there is no loopback server on iOS. */
        @Suppress("UNUSED_PARAMETER")
        fun start(
            streaming: CastSource?,
            downloaded: File?,
            mime: String,
            name: String = "video",
            config: Config = Config(),
        ): LoopbackStream = throw IOException("No loopback server on iOS")
    }
}

/** Settings' "Add a TV by its address" row: casting is not on iOS, so the row is hidden and there is no entry to open. */
const val ADD_TV_ENTRY_AVAILABLE: Boolean = false

/** The row's press: never reached on iOS, where [ADD_TV_ENTRY_AVAILABLE] hides the row. */
@Composable
fun rememberAddTvEntry(): () -> Unit = {}
