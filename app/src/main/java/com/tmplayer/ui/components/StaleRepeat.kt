package com.tmplayer.ui.components

import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.KeyEvent

// The rule lives in :ui (ui/src/shared/.../components/StaleRepeatRule.kt, with the Android clock in
// androidMain); these keep the names the TV screens in :app already import.

internal fun staleRepeat(event: KeyEvent): Boolean = platformStaleRepeat(event)

/** See [dropStaleRepeats]. */
fun Modifier.dropStaleArrowRepeats(): Modifier = dropStaleRepeats()
