package com.tmplayer.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable

/**
 * The words in a search box that belong to one visit of a screen (UX rule 3). They survive the
 * screen turning or the process being restored, and are gone once the viewer has left the screen
 * and comes back, because a screen kept by a saveable holder (a phone's pushed Settings, a
 * desktop's page) would otherwise hand the old words back with their old results.
 *
 * [keep] is true while a page opened from a result is up, so that leaving to About from it and
 * coming back, or Back from the page, returns to the results. One place for it, so Settings on a
 * phone and on the desktop, and any later search box, cannot drift apart.
 */
@Composable
fun rememberVisitQuery(keep: Boolean = false): MutableState<String> {
    val query = rememberSaveable { mutableStateOf("") }
    val keepNow = rememberUpdatedState(keep)
    // The saved copy is taken before the screen is disposed for a turn, so clearing here never
    // loses a turned screen's words.
    DisposableEffect(Unit) { onDispose { if (!keepNow.value) query.value = "" } }
    return query
}
