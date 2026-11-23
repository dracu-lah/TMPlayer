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
    /** Added by hand by its address: it stays in the list however long it is quiet. */
    val manual: Boolean = false,
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
 * A poll with no answer waits longer before the next (1, 2, 4, then 5 s), and three in a row is
 * [onLost]; the layer above decides whether to reconnect.
 *
 * What real TVs need, and what is done for it here:
 *  - A TV still playing something (this video, another app's) is sent Stop before the next
 *    SetAVTransportURI, and polls are held while a load runs, so its passing STOPPED is not news.
 *  - Metadata a TV will not take (714, 402, 501, 720, a fault with no code) is sent again in its
 *    plainest form and then left out ([Didl.fallbacks]); a 701 to the Play straight after a load
 *    (still loading) is asked again a moment later.
 *  - A Seek in REL_TIME that the TV says it has no such mode for (710, 402) goes as ABS_TIME, and the
 *    unit that worked is kept; a 701 to a Seek (still changing state) is asked again once.
 *  - A TV that was told to play and sits in STOPPED for ten seconds without ever playing is a
 *    failure ([failure], [DlnaPlayback]), split by whether it ever came to fetch the video
 *    ([noteFetched]); with [watchFetches], one that has not come for it 15 s after Play is too,
 *    whatever state it reports.
 */
class DlnaRenderer(
    val device: DlnaDevice,
    private val soap: SoapTransport = SoapClient(),
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
    private val sleep: (Long) -> Unit = { Thread.sleep(it) },
    /**
     * The cast server tells this renderer of each request from the TV ([noteFetched]), so a TV
     * that was told to play and never came for the video is a failure even while it claims to be
     * PLAYING (gmrender-resurrect does, for an address nothing answers at). Off for a URL served
     * by somebody else.
     */
    private val watchFetches: Boolean = false,
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

    /** A load is running: polls wait, so the STOPPED between its Stop and its Play is not reported. */
    @Volatile
    private var loading = false

    /** The TV connected to the cast server since the last load. */
    @Volatile
    private var fetched = false

    /** When Play was last taken after a load (the clock's ms), -1 before. */
    @Volatile
    private var playSentAt = -1L

    /** The Seek unit this TV took last, once one has worked. */
    @Volatile
    private var seekUnit: String? = null
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

    /** Why the video on the TV failed after it was loaded, or null; cleared by each load. */
    @Volatile
    var failure: DlnaFailure? = null
        private set

    /** The cast server saw the TV connect: it can reach this device. Cheap, called per connection. */
    fun noteFetched() {
        fetched = true
    }

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
        loading = true
        try {
            failure = null
            fetched = false
            playSentAt = -1
            val before = media?.state
            if (before == ReceiverState.Playing || before == ReceiverState.Paused || before == ReceiverState.Buffering) {
                // Some TVs keep the old video on, or answer the new address with 701 or 705, while one plays.
                avCall("Stop")
            }
            var metadata = didl
            var set = setUri(uri, metadata)
            if (set is SoapResult.Fault && refusal(set.code) == LoadRefusal.Busy) {
                avCall("Stop")
                sleep(BUSY_RETRY_MS)
                set = setUri(uri, metadata)
            }
            val first = set
            if (set is SoapResult.Fault && metadataRefused(set)) {
                for (plainer in Didl.fallbacks(didl)) {
                    Logger.i(TAG, "SetAVTransportURI refused (${(set as? SoapResult.Fault)?.code}), sending ${if (plainer.isEmpty()) "no metadata" else "plain metadata"}")
                    metadata = plainer
                    set = setUri(uri, metadata)
                    if (set !is SoapResult.Fault || !metadataRefused(set)) break
                }
            }
            // The first refusal says the most (a 714 names the type); a retry that got no answer says that.
            asFailure(if (set is SoapResult.Fault && first is SoapResult.Fault) first else set)?.let { return it }
            synchronized(lock) {
                playback = DlnaPlayback(durationMs).also { it.loaded(startMs, clock()) }
                loaded = Loaded(uri, metadata, durationMs)
                pendingStartMs = if (startMs > 0) startMs else -1
                media = null
                failures = 0
            }
            if (!autoplay) return DlnaLoad.Loaded
            var play = avCall("Play", "Speed" to "1")
            var tries = 0
            while (play is SoapResult.Fault && play.code == 701 && tries++ < PLAY_RETRIES) {
                // Not ready yet: the TV is still opening the address it was just given.
                sleep(PLAY_RETRY_MS)
                play = avCall("Play", "Speed" to "1")
            }
            if (play is SoapResult.Fault && play.code == 701 && startedByItself()) play = SoapResult.Ok(emptyMap())
            asFailure(play)?.let { return it }
            val now = clock()
            synchronized(lock) { playback.playedHere(now) }
            playSentAt = now
            return DlnaLoad.Loaded
        } finally {
            loading = false
        }
    }

    private fun setUri(uri: String, metadata: String): SoapResult =
        avCall("SetAVTransportURI", "CurrentURI" to uri, "CurrentURIMetaData" to metadata)

    /** A TV that starts a video when it is given one, and then calls a Play a transition it cannot make. */
    private fun startedByItself(): Boolean {
        val state = (avCall("GetTransportInfo") as? SoapResult.Ok)?.get("CurrentTransportState")?.trim()?.uppercase(java.util.Locale.ROOT)
        return state == "PLAYING" || state == "TRANSITIONING"
    }

    private fun metadataRefused(f: SoapResult.Fault): Boolean = f.code == null || f.code in METADATA_REFUSED

    /**
     * Plays. A renderer that answers with a not-found, bad type or busy error (a TV that timed
     * the stream out during a long pause, error 716) gets the video loaded again at the place it
     * was at, once.
     */
    fun play(): DlnaLoad {
        val first = asFailure(avCall("Play", "Speed" to "1"))
        if (first == null) {
            val now = clock()
            synchronized(lock) { playback.playedHere(now) }
            if (playSentAt < 0) playSentAt = now
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
        val target = UpnpTime.format(ms)
        val units = seekUnit?.let { listOf(it) } ?: SEEK_UNITS
        var r: SoapResult = SoapResult.Failed("No seek sent")
        for (unit in units) {
            r = avCall("Seek", "Unit" to unit, "Target" to target)
            if (r is SoapResult.Fault && r.code == 701) {
                // Still between states (loading, or the last seek): once more, a moment later.
                sleep(SEEK_RETRY_MS)
                r = avCall("Seek", "Unit" to unit, "Target" to target)
            }
            if (r is SoapResult.Ok) {
                seekUnit = unit
                break
            }
            // Only "no such seek mode" is worth the other unit; a bad target is bad in both.
            if (r !is SoapResult.Fault || r.code !in SEEK_UNIT_REFUSED) break
        }
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
                val wait = when {
                    // A TV that did not answer gets more time each round, up to the idle pace.
                    failures > 0 -> minOf(PLAYING_POLL_MS shl (failures - 1).coerceAtMost(4), IDLE_POLL_MS)
                    media?.state == ReceiverState.Playing || media?.state == ReceiverState.Buffering || media == null -> PLAYING_POLL_MS
                    else -> IDLE_POLL_MS
                }
                sleep(wait)
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
        if (loading) return media
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
        var m = synchronized(lock) {
            playback.update(
                ok["CurrentTransportState"], ok["CurrentTransportStatus"],
                UpnpTime.parseMs(pos?.get("RelTime")), UpnpTime.parseMs(pos?.get("TrackDuration")), now,
            )
        }
        if (loading) return media
        val asked = playSentAt
        if (watchFetches && !fetched && failure == null && asked >= 0 && now - asked >= NEVER_FETCHED_MS && m.idleReason != com.tmplayer.cast.IdleReason.Cancelled) {
            // It cannot be playing a video it never asked for, whatever it says.
            failure = DlnaFailure.NeverFetched
            Logger.w(TAG, "The TV says ${ok["CurrentTransportState"]} but never came for the video")
        }
        if (failure == DlnaFailure.NeverFetched) {
            // A TV that came after all (a very slow start) is no failure; until then, it is one.
            if (fetched) failure = null else m = m.copy(state = ReceiverState.Idle, idleReason = com.tmplayer.cast.IdleReason.Error)
        }
        if (m.state == ReceiverState.Idle && m.idleReason == com.tmplayer.cast.IdleReason.Error && failure == null) {
            val started = synchronized(lock) { playback.started }
            failure = when {
                started -> DlnaFailure.StoppedWithError
                fetched -> DlnaFailure.StoppedBeforePlaying
                else -> DlnaFailure.NeverFetched
            }
            Logger.w(TAG, "The video failed on the TV: $failure")
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
        const val PLAY_RETRIES = 2
        const val PLAY_RETRY_MS = 700L
        const val SEEK_RETRY_MS = 500L
        const val NEVER_FETCHED_MS = 15_000L

        /** Faults that may be about the metadata rather than the video: invalid args, action failed, 714, 720, and none named. */
        val METADATA_REFUSED = setOf(402, 501, 714, 720)

        /** REL_TIME is what every renderer is meant to take; ABS_TIME for those that only know that one. */
        val SEEK_UNITS = listOf("REL_TIME", "ABS_TIME")

        /** Seek mode not supported, or the arguments not understood. */
        val SEEK_UNIT_REFUSED = setOf(402, 710)
    }
}
