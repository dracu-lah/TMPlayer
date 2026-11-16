package com.tmplayer.ui.components

import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics

/**
 * Marks a line of text as one a screen reader speaks when it appears or changes: a rejected code,
 * a wrong PIN, a failed step. Without it TalkBack says nothing and a blind viewer is left pressing
 * a button that did not work. Polite, so it waits for what is being read. Pass [on] false for a
 * line that changes every second (a countdown), which would be read out over and over.
 */
fun Modifier.liveMessage(on: Boolean = true): Modifier =
    if (on) semantics { liveRegion = LiveRegionMode.Polite } else this
