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
 * When the Cast button is drawn (FQ8, the admission gate's "appears when relevant"): only once a TV
 * has been found on this network, Google Cast or DLNA alike, or while a cast is running or being
 * set up. A person with no TV to cast to never sees the button.
 *
 * - **Found this visit.** A TV seen while this screen was up keeps the button for the rest of the
 *   visit, so the picker's own search (which starts its list empty) never takes the button away
 *   from under a finger, and nor does a TV that goes quiet.
 * - **Seen a moment ago.** A TV seen in the last [REMEMBER_MS] anywhere in the app (the player, then
 *   the detail page) shows the button at once while the search confirms it; a search that ends
 *   with nothing found forgets it, and the button goes.
 * - **A cast under way** (connecting, connected, dropped, failed) always has the button: it is the
 *   way to Stop casting and to another TV.
 *
 * Pure, the clock passed in, for the tests.
 */
class CastPresence(
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
    private val memory: Memory = Memory.process,
) {
    /** When a TV was last seen, shared by every screen of the process. */
    class Memory {
        @Volatile
        var seenAtMs: Long? = null

        companion object {
            val process = Memory()
        }
    }

    var foundThisVisit = false
        private set
    var scanning = false
        private set
    private var foundThisScan = false

    fun scanStarted() {
        scanning = true
        foundThisScan = false
    }

    fun scanEnded() {
        if (scanning && !foundThisScan && !foundThisVisit) memory.seenAtMs = null
        scanning = false
    }

    fun devices(list: List<CastDeviceInfo>) {
        if (list.isEmpty()) return
        foundThisVisit = true
        foundThisScan = true
        memory.seenAtMs = clock()
    }

    private fun rememberedRecently(): Boolean {
        val at = memory.seenAtMs ?: return false
        val age = clock() - at
        return age in 0..REMEMBER_MS
    }

    fun visible(available: Boolean, session: CastSessionState): Boolean =
        available && (session !is CastSessionState.Idle || foundThisVisit || (scanning && rememberedRecently()))

    companion object {
        const val REMEMBER_MS = 10 * 60_000L
    }
}

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
                presence.devices(list)
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
        _shown.value = presence.visible(support.available, support.session.value)
    }

    companion object {
        /** Long enough for a TV's answer and its description (3 s at most each), short enough to cost nothing. */
        const val SCAN_MS = 15_000L
    }
}
