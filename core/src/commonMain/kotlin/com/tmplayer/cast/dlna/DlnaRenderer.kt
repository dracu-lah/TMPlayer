package com.tmplayer.cast.dlna

import com.tmplayer.cast.ReceiverMedia
import com.tmplayer.cast.ReceiverState
import com.tmplayer.platform.Logger
import java.io.Closeable
import java.net.InetAddress

/** A renderer found on the network and described: what the picker lists and a session talks to. */
data class DlnaDevice(
    val description: DeviceDescription,
    /** The address it answered from; the only one the cast server will serve. */
    val address: InetAddress,
    /** Where its description came from. */
    val location: String,
) {
    val udn: String get() = description.udn
    val name: String get() = description.friendlyName
}

/** Why a renderer turned a load or a play down, in the terms the screen words. */
enum class LoadRefusal {
    /** UPnP 714: it does not take this type of file. The next route may. */
    BadType,

    /** UPnP 716: it could not fetch or open the address (also what a TV says after a long pause). */
    NotFound,

    /** UPnP 701, 705, 715: busy with something else, or the transport is locked. */
    Busy,

    /** Any other error code, or none. */
    Other,
}

/** The outcome of loading a video or playing it. */
sealed interface DlnaLoad {
    data object Loaded : DlnaLoad
    data class Refused(val reason: LoadRefusal, val code: Int?, val text: String?) : DlnaLoad

    /** Nothing answered (network, timeout). */
    data class Failed(val message: String) : DlnaLoad
}

/** The outcome of a seek. */
enum class SeekOutcome { Done, NotAllowed, Refused, Failed }

/**
 * One cast to one renderer: the AVTransport and RenderingControl calls, and the polling that
 * turns the renderer's answers into [ReceiverMedia], so [com.tmplayer.cast.CastStateMapper] and
 * [com.tmplayer.cast.CastPlace] work as they do for a Chromecast.
 *
 * Calls block (a SOAP round trip each): run them off the main thread. Polling runs only between
 * [startPolling] and [close], every second while playing or loading and every five while paused
 * or stopped, so an idle cast costs one small request every five seconds and a closed one nothing.
 * Three polls in a row with no answer is [onLost]; the layer above decides whether to reconnect.
 */
class DlnaRenderer(
    val device: DlnaDevice,
    private val soap: SoapTransport = SoapClient(),
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
    private val sleep: (Long) -> Unit = { Thread.sleep(it) },
) : Closeable {

    private val lock = Any()
    private var playback = DlnaPlayback()
    private var loaded: Loaded? = null
    private var pendingStartMs = -1L

    @Volatile
    private var closed = false

    @Volatile
    private var lostSent = false

    private var failures = 0
    private var poller: Thread? = null
    private var onMedia: (ReceiverMedia) -> Unit = {}
    private var onLost: () -> Unit = {}

    /** The last state the renderer reported, null before the first poll after a load. */
    @Volatile
    var media: ReceiverMedia? = null
        private set

    /** What the renderer allows now; see [canSeek]. */
    @Volatile
    var actions: TransportActions = TransportActions.UNKNOWN
        private set

    /** The video's length: the renderer's own once it says one, else the probed one. */
    val durationMs: Long get() = synchronized(lock) { playback.durationMs }

    /** The place on the TV, as [DlnaPlayback] keeps it. */
    val placeMs: Long get() = synchronized(lock) { playback.place }

    /** False when the renderer's own list of allowed actions leaves Seek out. */
    val canSeek: Boolean get() = actions.seek

    private class Loaded(val uri: String, val didl: String, val durationMs: Long)

    private val av get() = device.description.avTransport

    private fun avCall(action: String, vararg args: Pair<String, String>): SoapResult =
        soap.call(av, action, listOf("InstanceID" to "0") + args)

    /** The renderer's Sink list as [SinkCaps]; an unanswered call gives the safe set. */
    fun sinkCaps(): SinkCaps {
        val cm = device.description.connectionManager ?: return SinkCaps.SAFE
        return when (val r = soap.call(cm, "GetProtocolInfo", emptyList())) {
            is SoapResult.Ok -> SinkCaps.parse(r["Sink"])
            else -> SinkCaps.SAFE
        }
    }

    /**
     * Hands the renderer [uri] with its [didl] and, when [autoplay], plays it. A renderer busy
     * with something else (701) is stopped and asked again once. [startMs] is applied by the first
     * poll that sees the renderer playing, since many ignore a seek before that.
     */
    fun load(uri: String, didl: String, startMs: Long = 0, durationMs: Long = 0, autoplay: Boolean = true): DlnaLoad {
        var set = avCall("SetAVTransportURI", "CurrentURI" to uri, "CurrentURIMetaData" to didl)
        if (set is SoapResult.Fault && refusal(set.code) == LoadRefusal.Busy) {
            avCall("Stop")
            sleep(BUSY_RETRY_MS)
            set = avCall("SetAVTransportURI", "CurrentURI" to uri, "CurrentURIMetaData" to didl)
        }
        asFailure(set)?.let { return it }
        synchronized(lock) {
            playback = DlnaPlayback(durationMs).also { it.loaded(startMs, clock()) }
            loaded = Loaded(uri, didl, durationMs)
            pendingStartMs = if (startMs > 0) startMs else -1
            media = null
            failures = 0
        }
        if (!autoplay) return DlnaLoad.Loaded
        return asFailure(avCall("Play", "Speed" to "1")) ?: DlnaLoad.Loaded
    }

    /**
     * Plays. A renderer that answers with a not-found, bad type or busy error (a TV that timed
     * the stream out during a long pause, error 716) gets the video loaded again at the place it
     * was at, once.
     */
    fun play(): DlnaLoad {
        val first = asFailure(avCall("Play", "Speed" to "1"))
        if (first == null) {
            synchronized(lock) { playback.playedHere() }
            return DlnaLoad.Loaded
        }
        val again = synchronized(lock) { loaded?.let { it to playback.place } } ?: return first
        if (first !is DlnaLoad.Refused) return first
        Logger.i(TAG, "Play refused (${first.code}), loading again at ${again.second} ms")
        return load(again.first.uri, again.first.didl, again.second, again.first.durationMs)
    }

    fun pause(): Boolean {
        val ok = avCall("Pause") is SoapResult.Ok
        if (ok) synchronized(lock) { playback.pausedHere() }
        return ok
    }

    fun stop(): Boolean {
        synchronized(lock) { playback.stoppedHere() }
        return avCall("Stop") is SoapResult.Ok
    }

    /** Seeks to [ms]; not asked at all when the renderer's allowed actions leave Seek out. */
    fun seek(ms: Long): SeekOutcome {
        if (!actions.seek) return SeekOutcome.NotAllowed
        val r = avCall("Seek", "Unit" to "REL_TIME", "Target" to UpnpTime.format(ms))
        return when (r) {
            is SoapResult.Ok -> {
                synchronized(lock) { playback.seeking(ms, clock()) }
                SeekOutcome.Done
            }
            is SoapResult.Fault -> SeekOutcome.Refused
            is SoapResult.Failed -> SeekOutcome.Failed
        }
    }

    // RenderingControl: volume and mute, only where the renderer has the service.

    val hasVolume: Boolean get() = device.description.renderingControl != null

    fun volume(): Int? {
        val rc = device.description.renderingControl ?: return null
        val r = soap.call(rc, "GetVolume", listOf("InstanceID" to "0", "Channel" to "Master")) as? SoapResult.Ok ?: return null
        return r["CurrentVolume"]?.toIntOrNull()?.coerceIn(0, 100)
    }

    fun setVolume(percent: Int): Boolean {
        val rc = device.description.renderingControl ?: return false
        val args = listOf("InstanceID" to "0", "Channel" to "Master", "DesiredVolume" to percent.coerceIn(0, 100).toString())
        return soap.call(rc, "SetVolume", args) is SoapResult.Ok
    }

    fun setMuted(muted: Boolean): Boolean {
        val rc = device.description.renderingControl ?: return false
        val args = listOf("InstanceID" to "0", "Channel" to "Master", "DesiredMute" to if (muted) "1" else "0")
        return soap.call(rc, "SetMute", args) is SoapResult.Ok
    }

    // Polling.

    /** Sets who hears each poll and the loss; [startPolling] does this and starts the thread. */
    fun listen(onMedia: (ReceiverMedia) -> Unit, onLost: () -> Unit) {
        synchronized(lock) {
            this.onMedia = onMedia
            this.onLost = onLost
        }
    }

    /** Starts the poll thread. [onMedia] hears every poll; [onLost] hears, once, that the renderer is gone. */
    fun startPolling(onMedia: (ReceiverMedia) -> Unit, onLost: () -> Unit) {
        synchronized(lock) {
            if (poller != null || closed) return
            this.onMedia = onMedia
            this.onLost = onLost
            poller = Thread({ pollLoop() }, "dlna-poll").apply {
                isDaemon = true
                start()
            }
        }
    }

    private fun pollLoop() {
        while (!closed && !lostSent) {
            try {
                pollOnce()
                sleep(if (media?.state == ReceiverState.Playing || media?.state == ReceiverState.Buffering || media == null) PLAYING_POLL_MS else IDLE_POLL_MS)
            } catch (_: InterruptedException) {
                return
            } catch (e: Exception) {
                Logger.w(TAG, "Poll failed", e)
                failed()
            }
        }
    }

    /** One poll, public for the tests; null when the renderer did not answer. */
    fun pollOnce(): ReceiverMedia? {
        val transport = avCall("GetTransportInfo")
        val position = avCall("GetPositionInfo")
        val ok = transport as? SoapResult.Ok
        if (ok == null) {
            failed()
            return null
        }
        failures = 0
        val pos = position as? SoapResult.Ok
        val now = clock()
        val m = synchronized(lock) {
            playback.update(
                ok["CurrentTransportState"], ok["CurrentTransportStatus"],
                UpnpTime.parseMs(pos?.get("RelTime")), UpnpTime.parseMs(pos?.get("TrackDuration")), now,
            )
        }
        if (media?.state != m.state) refreshActions()
        media = m
        val start = synchronized(lock) { pendingStartMs.takeIf { it > 0 && (m.state == ReceiverState.Playing || m.state == ReceiverState.Paused) } }
        if (start != null) {
            synchronized(lock) { pendingStartMs = -1 }
            if (seek(start) == SeekOutcome.Done) Logger.i(TAG, "Started at $start ms")
        }
        try {
            onMedia(m)
        } catch (e: Exception) {
            Logger.w(TAG, "Listener failed", e)
        }
        return m
    }

    private fun refreshActions() {
        val r = avCall("GetCurrentTransportActions") as? SoapResult.Ok ?: return
        TransportActions.parse(r["Actions"])?.let { actions = it }
    }

    private fun failed() {
        if (++failures >= LOST_AFTER && !lostSent) markGone()
    }

    /** The renderer said goodbye (SSDP byebye) or stopped answering: [onLost], once. */
    fun markGone() {
        if (lostSent || closed) return
        lostSent = true
        onLost()
    }

    override fun close() {
        closed = true
        poller?.interrupt()
        poller = null
    }

    private fun asFailure(r: SoapResult): DlnaLoad? = when (r) {
        is SoapResult.Ok -> null
        is SoapResult.Fault -> DlnaLoad.Refused(refusal(r.code), r.code, r.description)
        is SoapResult.Failed -> DlnaLoad.Failed(r.message)
    }

    private fun refusal(code: Int?): LoadRefusal = when (code) {
        714 -> LoadRefusal.BadType
        716 -> LoadRefusal.NotFound
        701, 705, 715 -> LoadRefusal.Busy
        else -> LoadRefusal.Other
    }

    private companion object {
        const val TAG = "DlnaRenderer"
        const val PLAYING_POLL_MS = 1_000L
        const val IDLE_POLL_MS = 5_000L
        const val LOST_AFTER = 3
        const val BUSY_RETRY_MS = 400L
    }
}
