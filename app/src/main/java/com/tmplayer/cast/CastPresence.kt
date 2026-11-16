package com.tmplayer.cast

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * The search that decides [CastPresence] for one screen: [start] when the screen is in sight
 * (onStart), [stop] when it is not (onStop). It looks for TVs for [scanMs] and then stops by
 * itself, so a player left open on a 1 GB phone or a page nobody touches does no network work and
 * holds no multicast lock; the picker runs its own live search while it is open. The search and
 * the picker share the two sides' counted start and stop, so neither ends the other's.
 *
 * [canSearch] is false where looking needs a permission not yet given (an Android 17 target's local
 * network access): nothing is asked from here, the button is drawn and the picker asks.
 */
class CastPresenceScan(
    private val support: CastSupport,
    private val scope: CoroutineScope,
    private val canSearch: () -> Boolean = { true },
    private val presence: CastPresence = CastPresence(),
    private val scanMs: Long = SCAN_MS,
) {
    private val _shown = MutableStateFlow(support.available && support.session.value !is CastSessionState.Idle)
    val shown: StateFlow<Boolean> = _shown.asStateFlow()

    private var watch: Job? = null
    private var timer: Job? = null
    private var discovering = false

    /** True while this screen's own search runs (for the tests). */
    val searching: Boolean get() = discovering

    fun start() {
        if (!support.available || watch != null) return
        if (!canSearch()) {
            _shown.value = true
            return
        }
        presence.scanStarted()
        discovering = true
        runCatching { support.startDiscovery() }
        publish()
        watch = scope.launch {
            combine(support.devices, support.session) { list, _ -> list }.collect { list ->
                presence.seen(list.isNotEmpty())
                publish()
            }
        }
        timer = scope.launch {
            delay(scanMs)
            endSearch()
            publish()
        }
    }

    fun stop() {
        timer?.cancel()
        timer = null
        watch?.cancel()
        watch = null
        endSearch()
    }

    private fun endSearch() {
        if (!discovering) return
        discovering = false
        presence.scanEnded()
        runCatching { support.stopDiscovery() }
    }

    private fun publish() {
        _shown.value = presence.visible(support.available, castUnderWay = support.session.value !is CastSessionState.Idle)
    }

    companion object {
        /** Long enough for a TV's answer and its description (3 s at most each), short enough to cost nothing. */
        const val SCAN_MS = 15_000L
    }
}

/**
 * Shows or hides a View Cast button for [CastPresenceScan.shown]. A button that goes while it holds
 * the focus (a keyboard or D-pad on a phone or tablet) hands it to [neighbour] first, so the keys
 * are never left on nothing.
 */
fun showCastButton(button: android.view.View, shown: Boolean, neighbour: android.view.View?) {
    if (shown) {
        button.visibility = android.view.View.VISIBLE
        return
    }
    if (button.visibility == android.view.View.GONE) return
    if (button.hasFocus()) neighbour?.takeIf { it.isShown && it.isFocusable }?.requestFocus()
    button.visibility = android.view.View.GONE
}
