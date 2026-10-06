package com.tmplayer.ui.online

import androidx.compose.runtime.Composable
import com.tmplayer.online.MetaTrailer

/** The desktop has no embedded player: a trailer opens in the browser, which plays it well. */
@Composable
internal fun rememberInAppTrailers(): Boolean = false

@Composable
@Suppress("UNUSED_PARAMETER")
internal fun TrailerPlayer(trailer: MetaTrailer, onClose: () -> Unit, onOpenElsewhere: () -> Unit) = Unit
