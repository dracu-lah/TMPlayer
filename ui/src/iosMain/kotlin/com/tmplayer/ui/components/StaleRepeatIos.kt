package com.tmplayer.ui.components

import androidx.compose.ui.input.key.KeyEvent

/** iPhone and iPad have no D-pad remote: no repeat is ever stale. */
@Suppress("UNUSED_PARAMETER")
fun platformStaleRepeat(event: KeyEvent): Boolean = false
