package com.tmplayer.ui.components

import androidx.compose.ui.input.key.KeyEvent

/** The desktop has no held remote on a slow stick: a keyboard's repeats are all handled. */
@Suppress("UNUSED_PARAMETER")
fun platformStaleRepeat(event: KeyEvent): Boolean = false
