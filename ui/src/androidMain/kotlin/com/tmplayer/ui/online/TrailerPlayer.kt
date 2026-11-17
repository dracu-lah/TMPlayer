package com.tmplayer.ui.online

import androidx.compose.runtime.Composable
import com.tmplayer.online.MetaTrailer

/**
 * No player of the library's own: the Android app provides its WebView player through
 * [LocalTrailerOpener] at its root (app/.../ui/trailer/TrailerPlayer.kt, private/TRAILERS-PLAN.md
 * T3), so [TrailerLauncher] reaches it through the seam. Anything shown outside that root keeps
 * the YouTube app first, then the browser.
 */
@Composable
internal fun rememberInAppTrailers(): Boolean = false

@Composable
@Suppress("UNUSED_PARAMETER")
internal fun TrailerPlayer(
    trailer: MetaTrailer,
    onClose: () -> Unit,
    onOpenElsewhere: () -> Unit,
    fallback: @Composable () -> Unit,
) = Unit
