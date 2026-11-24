package com.tmplayer.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableFloatState
import androidx.compose.ui.Modifier

/** iOS has no predictive back preview (the swipe back is the shell's): a plain Back, with the same names as Android's (WP-44). */
@Composable
@Suppress("UNUSED_PARAMETER")
fun PredictivePageBack(progress: MutableFloatState, enabled: Boolean = true, onBack: () -> Unit) {
    BackHandler(enabled = enabled, onBack = onBack)
}

@Suppress("UNUSED_PARAMETER")
fun Modifier.pageBackScale(progress: Float, reduced: Boolean): Modifier = this
