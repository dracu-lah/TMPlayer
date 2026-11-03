package com.tmplayer.cast

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.tmplayer.cast.dlna.Didl
import com.tmplayer.cast.dlna.DlnaDevice
import com.tmplayer.cast.dlna.DlnaDiscovery
import com.tmplayer.cast.dlna.DlnaFeatures
import com.tmplayer.cast.dlna.DlnaLoad
import com.tmplayer.cast.dlna.DlnaRenderer
import com.tmplayer.cast.dlna.LoadRefusal
import com.tmplayer.cast.dlna.SeekOutcome
import com.tmplayer.cast.dlna.SinkCaps
import com.tmplayer.cast.dlna.dlnaServing
import com.tmplayer.cast.dlna.forDlna
import com.tmplayer.i18n.L
import com.tmplayer.platform.JobFailures
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.isActive

/**
 * [CastSupport] over DLNA (HX40 phase 3): the TVs that take a push and have no Cast, found by
 * SSDP while a picker is open ([DlnaDiscovery], which stops by itself and when the picker closes),
 * driven through [DlnaRenderer]. The phone is the control point; the video itself is served by the
 * player's own cast server, which [serverAccess] opens to the TV's address alone and gives the
 * DLNA headers.
 *
 * Every renderer call waits on a SOAP round trip, so each runs on the IO dispatcher and its answer
 * comes back through the state flows. One TV at a time; a TV that is lost ends the session in
 * [CastSessionState.Lost] and Reconnect makes a new renderer, as for Cast. The renderer is
 * polled only from the first load to the session's end, and the service that keeps the process
 * alive ([DlnaPlatform.keepAlive]) is held only while a video is on the TV.
 *
 * The seams ([discovery], [rendererFor], [platform], [clock]) are for the tests.
 */
class DlnaCastSupport(
    private val platform: DlnaPlatform,
    private val discovery: (DlnaDiscovery.Listener) -> DlnaDiscovery = { DlnaDiscovery(it) },
    private val rendererFor: (DlnaDevice) -> DlnaRenderer = { DlnaRenderer(it, clock = { SystemClock.elapsedRealtime() }) },
    dispatcher: kotlinx.coroutines.CoroutineDispatcher = Dispatchers.IO,
) : CastSupport {

    override val available: Boolean = true

    private val scope = CoroutineScope(SupervisorJob() + dispatcher + JobFailures.handler(TAG))

    private val _devices = MutableStateFlow<List<CastDeviceInfo>>(emptyList())
    override val devices: StateFlow<List<CastDeviceInfo>> = _devices.asStateFlow()
    private val _session = MutableStateFlow<CastSessionState>(CastSessionState.Idle)
    override val session: StateFlow<CastSessionState> = _session.asStateFlow()
    private val _remote = MutableStateFlow(RemoteStatus())
    override val remote: StateFlow<RemoteStatus> = _remote.asStateFlow()
    private val _offLan = MutableStateFlow(false)
    override val offLan: StateFlow<Boolean> = _offLan.asStateFlow()

    private val found = LinkedHashMap<String, DlnaDevice>()
    private var searching: DlnaDiscovery? = null
    private var pickers = 0
    private var connectingId: String? = null
    private var connectJob: Job? = null

    @Volatile
    private var renderer: DlnaRenderer? = null

    @Volatile
    private var connected: DlnaDevice? = null

    @Volatile
    private var sink: SinkCaps = SinkCaps.SAFE

    @Volatile
    private var currentUrl: String? = null

    @Volatile
    private var refused: String? = null

    @Volatile
    private var fixedVolume = true

    @Volatile
    private var volumeLevel = 1.0

    @Volatile
    private var muted = false

    override val caps: ReceiverCaps? get() = if (connected != null) sink.caps else null
    override val refusal: String? get() = refused
    override val serverAccess: ServerAccess?
        get() {
            val tv = connected ?: return null
            return ServerAccess(allowed = { it == tv.address }, serving = { dlnaServing(it) }, idleLimitMs = IDLE_LIMIT_MS)
        }

    override fun adapt(plan: CastPlan): CastPlan = plan.forDlna()

    private val listener = object : DlnaDiscovery.Listener {
        override fun onFound(device: DlnaDevice) {
            synchronized(found) { found[ID_PREFIX + device.udn] = device }
            publishDevices()
        }

        override fun onGone(udn: String) {
            synchronized(found) { found.remove(ID_PREFIX + udn) }
            publishDevices()
        }

        // Ended by itself (quiet for 10 s, or nothing for 20 s): the Wi-Fi lock is not needed any longer.
        override fun onFinished(found: Int) = platform.releaseMulticast()
    }

    private fun publishDevices() {
        val tv = connected
        val rows = synchronized(found) { found.toMap() }
        _devices.value = rows.map { (id, d) ->
            CastDeviceInfo(
                id = id,
                name = d.name,
                model = listOfNotNull(d.description.manufacturer, d.description.modelName).joinToString(" ").trim(),
                connected = tv != null && tv.udn == d.udn && _session.value is CastSessionState.Connected,
                connecting = id == connectingId,
                kind = CastKind.Dlna,
                address = d.address,
            )
        }.sortedBy { it.name.lowercase() }
    }

    @Synchronized
    override fun startDiscovery() {
        if (pickers++ > 0) return
        _offLan.value = !platform.hasLan()
        synchronized(found) { found.clear() }
        publishDevices()
        platform.holdMulticast()
        searching = runCatching { discovery(listener).also { it.start() } }
            .onFailure {
                Log.w(TAG, "DLNA discovery did not start", it)
                platform.releaseMulticast()
            }
            .getOrNull()
    }

    @Synchronized
    override fun stopDiscovery() {
        if (pickers > 0 && --pickers > 0) return
        runCatching { searching?.stop() }
        searching = null
        platform.releaseMulticast()
    }

    override fun connect(id: String) {
        val device = synchronized(found) { found[id] } ?: return
        connectJob?.cancel()
        dropRenderer(tellTv = true)
        connectingId = id
        _session.value = CastSessionState.Connecting(device.name)
        publishDevices()
        connectJob = scope.launch {
            val r = rendererFor(device)
            // The TV answers a transport question, or it is not there to cast to.
            val ok = runCatching { r.pollOnce() != null }.getOrDefault(false)
            // Another TV was tapped while this one was being asked: this answer is stale.
            if (!isActive) {
                r.close()
                return@launch
            }
            if (!ok) {
                Log.w(TAG, "${device.name} did not answer")
                r.close()
                connectingId = null
                _session.value = CastSessionState.Failed(device.name)
                publishDevices()
                return@launch
            }
            sink = runCatching { r.sinkCaps() }.getOrDefault(SinkCaps.SAFE)
            fixedVolume = !r.hasVolume
            volumeLevel = runCatching { r.volume() }.getOrNull()?.let { it / 100.0 } ?: 1.0
            if (!isActive) {
                r.close()
                return@launch
            }
            muted = false
            refused = null
            renderer = r
            connected = device
            connectingId = null
            _session.value = CastSessionState.Connected(device.name, publishedModel(device), device.address, CastKind.Dlna)
            publishDevices()
        }
    }

    private fun publishedModel(d: DlnaDevice): String =
        listOfNotNull(d.description.manufacturer, d.description.modelName).joinToString(" ").trim()

    /** Ends the session: the TV's player stops, polling and the service go. */
    override fun endSession() {
        connectJob?.cancel()
        connectingId = null
        val wasUp = connected != null
        dropRenderer(tellTv = true)
        if (wasUp || _session.value !is CastSessionState.Idle) _session.value = CastSessionState.Idle
        publishDevices()
    }

    /** Lets go of the renderer; the Stop that tells the TV goes off the caller's thread. */
    private fun dropRenderer(tellTv: Boolean) {
        val r = renderer
        renderer = null
        connected = null
        currentUrl = null
        _remote.value = RemoteStatus()
        platform.releaseKeepAlive()
        if (r != null) {
            scope.launch {
                if (tellTv) runCatching { r.stop() }
                r.close()
            }
        }
    }

    override fun load(request: CastLoadRequest, onResult: (Boolean) -> Unit) {
        val r = renderer ?: return onResult(false)
        val tv = connected ?: return onResult(false)
        scope.launch {
            val features = DlnaFeatures(profile = null, rangeSeek = request.seekable, converted = !request.seekable)
            val didl = Didl.item(request.title, request.url, request.mime, features, durationMs = request.durationMs.takeIf { it > 0 })
            refused = null
            val ok = when (val result = r.load(request.url, didl, startMs = request.startMs, durationMs = request.durationMs, autoplay = request.autoplay)) {
                DlnaLoad.Loaded -> {
                    currentUrl = request.url
                    _remote.value = RemoteStatus(durationMs = request.durationMs, volume = volumeLevel, muted = muted, volumeFixed = fixedVolume, contentId = request.url, canSeek = r.canSeek)
                    r.startPolling(onMedia = { absorb(r, it) }, onLost = { lost(r, tv) })
                    platform.keepAlive(request.title, tv.name)
                    true
                }
                is DlnaLoad.Refused -> {
                    Log.w(TAG, "Refused ${result.code} ${result.text}")
                    refused = when (result.reason) {
                        LoadRefusal.BadType -> L.castRefusedType
                        LoadRefusal.NotFound -> L.castRefusedOpen
                        LoadRefusal.Busy -> L.castRefusedBusy
                        LoadRefusal.Other -> null
                    }
                    false
                }
                is DlnaLoad.Failed -> {
                    Log.w(TAG, "Load failed: ${result.message}")
                    false
                }
            }
            onResult(ok)
        }
    }

    private fun absorb(from: DlnaRenderer, m: ReceiverMedia) {
        if (renderer !== from) return
        _remote.value = RemoteStatus(
            media = m,
            durationMs = from.durationMs,
            volume = volumeLevel,
            muted = muted,
            volumeFixed = fixedVolume,
            contentId = currentUrl,
            canSeek = from.canSeek,
        )
    }

    private fun lost(from: DlnaRenderer, tv: DlnaDevice) {
        if (renderer !== from) return
        Log.i(TAG, "${tv.name} stopped answering")
        renderer = null
        connected = null
        platform.releaseKeepAlive()
        from.close()
        _session.value = CastSessionState.Lost(tv.name)
        publishDevices()
    }

    private fun async(block: (DlnaRenderer) -> Unit) {
        val r = renderer ?: return
        scope.launch { runCatching { block(r) }.onFailure { Log.w(TAG, "Call failed", it) } }
    }

    override fun play() = async { r ->
        val result = r.play()
        if (result !is DlnaLoad.Loaded) Log.w(TAG, "Play: $result")
    }

    override fun pause() = async { r -> r.pause() }

    override fun seek(positionMs: Long) = async { r ->
        val result = r.seek(positionMs)
        if (result != SeekOutcome.Done) Log.w(TAG, "Seek: $result")
    }

    /** Stops what is playing; the session stays, so the next video can load. The service is let go: nothing plays. */
    override fun stopMedia() {
        val r = renderer ?: return
        platform.releaseKeepAlive()
        scope.launch { runCatching { r.stop() } }
    }

    override fun setVolume(level: Double) {
        val l = level.coerceIn(0.0, 1.0)
        volumeLevel = l
        _remote.value = _remote.value.copy(volume = l)
        async { r -> r.setVolume((l * 100).toInt()) }
    }

    override fun setMuted(muted: Boolean) {
        this.muted = muted
        _remote.value = _remote.value.copy(muted = muted)
        async { r -> r.setMuted(muted) }
    }

    override fun setActiveTracks(ids: List<Long>) = Unit
    override fun setRate(rate: Double) = Unit

    companion object {
        private const val TAG = "TMPlayerDlna"

        /** Ids in this support's own list; [MergedCastSupport] prefixes them again with the kind. */
        const val ID_PREFIX = "udn:"

        /** Paused with no request from the TV for this long closes the port; Play opens it again at the place. */
        const val IDLE_LIMIT_MS = 30 * 60_000L

        fun create(context: Context): DlnaCastSupport = DlnaCastSupport(AndroidDlnaPlatform(context))
    }
}
