package com.tmplayer.ui.online

import androidx.compose.runtime.Stable
import androidx.compose.runtime.staticCompositionLocalOf
import com.tmplayer.online.MetaTrailer

/**
 * How this platform plays a trailer inside TMPlayer: the one seam each platform fills in
 * (private/TRAILERS-PLAN.md). Every trailer button goes through [TrailerLauncher], which asks
 * [com.tmplayer.online.TrailerRules.route] first (the viewer's `trailers_in_app` setting, a valid
 * key, [online]) and only then calls [open]; with the setting off it never does.
 *
 * A platform provides its implementation once at its root:
 * `CompositionLocalProvider(LocalTrailerOpener provides mine) { ... }`. The player it shows loads
 * [com.tmplayer.online.TrailerEmbed.pageHtml] (or, on the web, the embed URL), feeds the page's
 * messages to [com.tmplayer.online.TrailerRules.next], and when that answers `fallsBack` (or
 * nothing is ready after `READY_TIMEOUT_MS`) closes, shows `trailer.unavailable_in_app` and calls
 * `onFallback`, on the main thread, once. The fallback is today's external open (the YouTube app,
 * else the browser, else the QR code on a television), done by the launcher.
 */
@Stable
interface TrailerOpener {
    /** False while the device knows it has no network: the trailer goes straight to the external open. */
    val online: Boolean get() = true

    /** Plays [trailer] in the app, or calls [onFallback] (once, on the main thread) when it cannot. */
    fun open(trailer: MetaTrailer, onFallback: () -> Unit)

    companion object {
        /**
         * Nothing of the platform's own: every trailer goes to [onFallback], the external open, as
         * before this seam. The default until a platform provides its player.
         */
        val External: TrailerOpener = object : TrailerOpener {
            override fun open(trailer: MetaTrailer, onFallback: () -> Unit) = onFallback()
        }
    }
}

/** The platform's [TrailerOpener]; [TrailerOpener.External] unless the platform's root provides one. */
val LocalTrailerOpener = staticCompositionLocalOf { TrailerOpener.External }
