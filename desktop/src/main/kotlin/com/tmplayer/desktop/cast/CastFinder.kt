package com.tmplayer.desktop.cast

import com.tmplayer.cast.dlna.DlnaDevice
import com.tmplayer.cast.dlna.DlnaDiscovery
import com.tmplayer.cast.dlna.ManualAddress
import com.tmplayer.cast.dlna.ManualTvStore
import com.tmplayer.desktop.DesktopPaths
import com.tmplayer.ui.components.SavedTv
import java.io.File
import com.tmplayer.platform.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/**
 * The picker's one list: Cast receivers ([CastDiscovery], mDNS) and DLNA renderers
 * ([DlnaDiscovery], SSDP), found together while the picker is open (or the player bar's short
 * search for its button runs) and stopped together when the last of them closes. A TV that answers both ways is one row by its address, and Cast wins.
 *
 * [dlna] is how DLNA discovery is made, so a test can hand in one that talks to a fake on loopback.
 */
class CastFinder(
    private val castDevices: StateFlow<List<CastDevice>>,
    private val startCast: () -> Unit,
    private val stopCast: () -> Unit,
    /** The TVs added by their address; null where none are remembered (the tests that need none). */
    private val store: ManualTvStore? = null,
    /** Asks the TV at an address who it is; a seam for the tests. */
    private val resolve: (String) -> ManualAddress.Outcome = { ManualAddress.resolve(it) },
    dlna: (DlnaDiscovery.Listener) -> DlnaDiscovery,
) {

    constructor(
        cast: CastDiscovery = CastDiscovery(),
        store: ManualTvStore? = ManualTvStore(File(DesktopPaths.dataDir, "dlna-manual-tvs.txt")),
        dlna: (DlnaDiscovery.Listener) -> DlnaDiscovery = { DlnaDiscovery(it, live = true, manual = { store?.locations().orEmpty() }) },
    ) : this(cast.devices, cast::start, cast::stop, store, dlna = dlna)

    private val dlnaFound = LinkedHashMap<String, DlnaDevice>()
    private val _devices = MutableStateFlow<List<CastTarget>>(emptyList())
    val devices: StateFlow<List<CastTarget>> = _devices.asStateFlow()

    private val _searching = MutableStateFlow(false)

    /** Discovery is still asking: false once both ended by themselves or the picker closed. */
    val searching: StateFlow<Boolean> = _searching.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + com.tmplayer.platform.JobFailures.handler("CastFinder"))
    private var watching: Job? = null

    private val listener = object : DlnaDiscovery.Listener {
        override fun onFound(device: DlnaDevice) {
            synchronized(dlnaFound) { dlnaFound[device.udn] = device }
            publish()
        }

        override fun onGone(udn: String) {
            synchronized(dlnaFound) { dlnaFound.remove(udn) }
            publish()
        }

        override fun onFinished(found: Int) {
            _searching.value = false
        }

        // The first sweep is over; a live search goes on listening, quietly.
        override fun onSettled(found: Int) {
            _searching.value = false
        }
    }

    private val savedTvs = MutableStateFlow(readSaved())

    /** The TVs added by their address and remembered, for the dialog's list. */
    val saved: StateFlow<List<SavedTv>> = savedTvs.asStateFlow()

    private fun readSaved(): List<SavedTv> = store?.load().orEmpty().map { SavedTv(it.location, it.name, it.address) }

    /**
     * "Add a TV by its address": asks the TV who it is (blocks for a few seconds, call it off the
     * UI thread), and a TV found is saved, listed at once and kept by the running search.
     */
    fun add(address: String): ManualAddress.Outcome {
        val outcome = resolve(address)
        if (outcome is ManualAddress.Outcome.Found) {
            val device = outcome.device
            store?.add(device.location, device.udn, device.name)
            synchronized(dlnaFound) { dlnaFound[device.udn] = device }
            runCatching { discovery?.adopt(device) }
            savedTvs.value = readSaved()
            publish()
        }
        return outcome
    }

    fun remove(key: String) {
        store?.remove(key)
        val removed = synchronized(dlnaFound) { dlnaFound.entries.firstOrNull { (_, d) -> d.manual && d.location == key }?.also { dlnaFound.remove(it.key) } }
        savedTvs.value = readSaved()
        if (removed != null) publish()
    }

    private val discovery = runCatching { dlna(listener) }.onFailure { Logger.w(TAG, "No DLNA discovery", it) }.getOrNull()

    /** How many asked for a search (the picker, the player bar's short search for its button); counted. */
    private var users = 0

    /** Each start has its [stop]; the search runs while any is open. */
    @Synchronized
    fun start() {
        if (users++ > 0) return
        begin()
    }

    @Synchronized
    fun stop() {
        if (users == 0 || --users > 0) return
        end()
    }

    private fun begin() {
        synchronized(dlnaFound) { dlnaFound.clear() }
        _searching.value = true
        startCast()
        runCatching { discovery?.start() }.onFailure { Logger.w(TAG, "DLNA discovery did not start", it) }
        if (watching?.isActive != true) {
            watching = scope.launch { castDevices.collect { publish() } }
        }
        publish()
    }

    private fun end() {
        stopCast()
        runCatching { discovery?.stop() }
        watching?.cancel()
        watching = null
        _searching.value = false
    }

    /** The help card's Try again: the search from the start, whoever else holds it. */
    @Synchronized
    fun restart() {
        if (users == 0) return start()
        end()
        begin()
    }

    private fun publish() {
        val castRows = castDevices.value.map(CastTarget::of)
        val castAddresses = castRows.map { it.address }.toSet()
        val dlnaRows = synchronized(dlnaFound) { dlnaFound.values.toList() }
            .filter { it.address !in castAddresses }
            .map(CastTarget::of)
        _devices.value = (castRows + dlnaRows).sortedBy { it.name.lowercase() }
    }

    /** The row a lost session is reconnected to: the same id, as found now. */
    fun find(id: String): CastTarget? = devices.value.firstOrNull { it.id == id }

    private companion object {
        const val TAG = "CastFinder"
    }
}
