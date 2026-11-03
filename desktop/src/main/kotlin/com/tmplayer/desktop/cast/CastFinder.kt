package com.tmplayer.desktop.cast

import com.tmplayer.cast.dlna.DlnaDevice
import com.tmplayer.cast.dlna.DlnaDiscovery
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
 * ([DlnaDiscovery], SSDP), found together while the picker is open and stopped together when it
 * closes. A TV that answers both ways is one row by its address, and Cast wins.
 *
 * [dlna] is how DLNA discovery is made, so a test can hand in one that talks to a fake on loopback.
 */
class CastFinder(
    private val castDevices: StateFlow<List<CastDevice>>,
    private val startCast: () -> Unit,
    private val stopCast: () -> Unit,
    dlna: (DlnaDiscovery.Listener) -> DlnaDiscovery,
) {

    constructor(
        cast: CastDiscovery = CastDiscovery(),
        dlna: (DlnaDiscovery.Listener) -> DlnaDiscovery = { DlnaDiscovery(it) },
    ) : this(cast.devices, cast::start, cast::stop, dlna)

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
    }

    private val discovery = runCatching { dlna(listener) }.onFailure { Logger.w(TAG, "No DLNA discovery", it) }.getOrNull()

    @Synchronized
    fun start() {
        synchronized(dlnaFound) { dlnaFound.clear() }
        _searching.value = true
        startCast()
        runCatching { discovery?.start() }.onFailure { Logger.w(TAG, "DLNA discovery did not start", it) }
        if (watching?.isActive != true) {
            watching = scope.launch { castDevices.collect { publish() } }
        }
        publish()
    }

    @Synchronized
    fun stop() {
        stopCast()
        runCatching { discovery?.stop() }
        watching?.cancel()
        watching = null
        _searching.value = false
    }

    /** The help card's Try again. */
    fun restart() {
        stop()
        start()
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
