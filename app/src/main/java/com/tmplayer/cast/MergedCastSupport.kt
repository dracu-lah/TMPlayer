package com.tmplayer.cast

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * One Cast button for two ways of casting (HX40): Google's Cast framework ([google], or
 * [NoCastSupport] in the F-Droid build and without Play services) and DLNA ([dlna]).
 *
 * - **One list.** Google Cast TVs first, then DLNA ones, each by name. A TV found both ways (Sony,
 *   Philips, some LG) is one row by its address, and Cast wins. Ids carry their kind, [GOOGLE] and
 *   [DLNA], so [connect] knows where to go; the rows say which they are ([CastDeviceInfo.kind]).
 * - **One session.** Whichever side was connected to last is the session, remote, caps and server
 *   rules the player sees; the other side is let go when a TV on this side is picked (one cast at a
 *   time, and the player reloads on the new TV when it was casting). A side that is idle does not
 *   speak for the session unless the other has nothing active either.
 * - Discovery starts and stops on both sides together.
 */
class MergedCastSupport(
    private val google: CastSupport,
    private val dlna: CastSupport,
    dispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
) : CastSupport {

    override val available: Boolean = google.available || dlna.available

    private val scope = CoroutineScope(SupervisorJob() + dispatcher)

    @Volatile
    private var last: CastSupport = if (google.available) google else dlna

    private val _devices = MutableStateFlow<List<CastDeviceInfo>>(emptyList())
    override val devices: StateFlow<List<CastDeviceInfo>> = _devices.asStateFlow()
    private val _session = MutableStateFlow<CastSessionState>(CastSessionState.Idle)
    override val session: StateFlow<CastSessionState> = _session.asStateFlow()
    private val _remote = MutableStateFlow(RemoteStatus())
    override val remote: StateFlow<RemoteStatus> = _remote.asStateFlow()

    override val offLan: StateFlow<Boolean> get() = dlna.offLan

    init {
        for (side in listOf(google, dlna)) {
            scope.launch { side.devices.collect { refresh() } }
            scope.launch { side.session.collect { refresh() } }
            scope.launch { side.remote.collect { refresh() } }
        }
        refresh()
    }

    /** The side that speaks for the session now. */
    private val current: CastSupport get() = chosen()

    private fun chosen(): CastSupport {
        val first = last
        if (first.session.value !is CastSessionState.Idle) return first
        val other = if (first === google) dlna else google
        val live = other.session.value.let { it is CastSessionState.Connected || it is CastSessionState.Connecting || it is CastSessionState.Suspended }
        return if (live) other else first
    }

    private fun refresh() {
        val side = chosen()
        last = side
        _devices.value = mergeDevices(google.devices.value, dlna.devices.value)
        _session.value = side.session.value
        _remote.value = side.remote.value
    }

    override val caps: ReceiverCaps? get() = current.caps
    override val serverAccess: ServerAccess? get() = current.serverAccess
    override val refusal: String? get() = current.refusal
    override fun adapt(plan: CastPlan): CastPlan = current.adapt(plan)

    override fun startDiscovery() {
        google.startDiscovery()
        dlna.startDiscovery()
    }

    override fun stopDiscovery() {
        google.stopDiscovery()
        dlna.stopDiscovery()
    }

    override fun connect(id: String) {
        val (side, other, raw) = when {
            id.startsWith(GOOGLE) -> Triple(google, dlna, id.removePrefix(GOOGLE))
            id.startsWith(DLNA) -> Triple(dlna, google, id.removePrefix(DLNA))
            else -> return
        }
        last = side
        // The new TV first, so the player never sees an idle session between the two.
        side.connect(raw)
        if (other.session.value.let { it is CastSessionState.Connected || it is CastSessionState.Suspended || it is CastSessionState.Connecting }) other.endSession()
        refresh()
    }

    override fun endSession() {
        google.endSession()
        dlna.endSession()
        refresh()
    }

    override fun load(request: CastLoadRequest, onResult: (Boolean) -> Unit) = current.load(request, onResult)
    override fun play() = current.play()
    override fun pause() = current.pause()
    override fun seek(positionMs: Long) = current.seek(positionMs)
    override fun stopMedia() = current.stopMedia()
    override fun setVolume(level: Double) = current.setVolume(level)
    override fun setMuted(muted: Boolean) = current.setMuted(muted)
    override fun setActiveTracks(ids: List<Long>) = current.setActiveTracks(ids)
    override fun setRate(rate: Double) = current.setRate(rate)

    companion object {
        const val GOOGLE = "gc:"
        const val DLNA = "dlna:"

        /**
         * Google Cast TVs first, then the DLNA ones that are not the same TV (same address), ids
         * prefixed with their kind. Pure, for the tests.
         */
        fun mergeDevices(google: List<CastDeviceInfo>, dlna: List<CastDeviceInfo>): List<CastDeviceInfo> {
            val castAddresses = google.mapNotNull { it.address }.toSet()
            val a = google.map { it.copy(id = GOOGLE + it.id, kind = CastKind.GoogleCast) }.sortedBy { it.name.lowercase() }
            val b = dlna.filter { it.address == null || it.address !in castAddresses }
                .map { it.copy(id = DLNA + it.id, kind = CastKind.Dlna) }
                .sortedBy { it.name.lowercase() }
            return a + b
        }
    }
}
