package com.tmplayer.desktop.cast

import com.tmplayer.cast.CastPlan
import com.tmplayer.cast.ReceiverCaps
import com.tmplayer.cast.ReceiverMedia
import com.tmplayer.cast.ReceiverState
import com.tmplayer.cast.IdleReason
import com.tmplayer.cast.dlna.DlnaDevice
import com.tmplayer.cast.dlna.DlnaFeatures
import com.tmplayer.cast.dlna.DlnaLoad
import com.tmplayer.cast.dlna.DlnaRenderer
import com.tmplayer.cast.dlna.Didl
import com.tmplayer.cast.dlna.LoadRefusal
import com.tmplayer.cast.dlna.SeekOutcome
import com.tmplayer.cast.dlna.SoapClient
import com.tmplayer.cast.dlna.SoapTransport
import com.tmplayer.cast.dlna.forDlna
import com.tmplayer.i18n.L
import com.tmplayer.platform.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.InetAddress

enum class ReceiverKind { GoogleCast, Dlna }

/**
 * One row of the picker: a TV as found, by Cast or by DLNA. A TV that answers both ways is one row
 * by its address, and Cast wins ([CastFinder]).
 */
data class CastTarget(
    val id: String,
    val name: String,
    val model: String,
    val address: InetAddress,
    val kind: ReceiverKind,
    /** What it is doing now, when it says (Cast only). */
    val status: String = "",
    val cast: CastDevice? = null,
    val dlna: DlnaDevice? = null,
) {
    companion object {
        fun of(device: CastDevice) = CastTarget(device.id, device.name, device.model, device.address, ReceiverKind.GoogleCast, device.status, cast = device)

        fun of(device: DlnaDevice) = CastTarget(
            id = "dlna:" + device.udn,
            name = device.name,
            model = listOfNotNull(device.description.manufacturer, device.description.modelName).joinToString(" ").trim(),
            address = device.address,
            kind = ReceiverKind.Dlna,
            dlna = device,
        )
    }
}

/** A subtitle file the receiver is told about beside the video (Cast side tracks). */
class SideTrack(val id: Int, val label: String, val language: String?, val url: String)

/** What the engine asks a receiver to play: neutral, so each protocol builds its own message. */
class ReceiverLoad(
    val url: String,
    val mime: String,
    val title: String,
    val subtitle: String,
    /** 0 when not known. */
    val durationMs: Long,
    val startMs: Long,
    val autoplay: Boolean,
    /** An HLS playlist (Cast only). */
    val hls: Boolean,
    /** The receiver can seek in it itself (ranges); false for a rewrap made as it is sent. */
    val seekable: Boolean,
    val sideTracks: List<SideTrack> = emptyList(),
    val activeTrackIds: List<Int> = emptyList(),
)

sealed interface ReceiverAnswer {
    data object Loaded : ReceiverAnswer

    /** The receiver turned this route down; the engine tries the next. [why] is a sentence for the viewer, when there is one. */
    data class Refused(val why: String? = null) : ReceiverAnswer
}

/**
 * The TV as the cast engine sees it, whatever it speaks: Cast over TLS ([CastReceiver]) or DLNA
 * over SOAP ([DlnaReceiver]). Everything the engine tells a TV, and everything it hears back as
 * [RemoteMediaStatus], goes through this, so the keys, the bar and the panel are the same for both.
 */
interface Receiver {
    val target: CastTarget
    val kind: ReceiverKind get() = target.kind
    val name: String get() = target.name
    val model: String get() = target.model
    val address: InetAddress get() = target.address

    /** What it plays. Known after [connect]. */
    val caps: ReceiverCaps
    val media: StateFlow<RemoteMediaStatus>
    val volume: StateFlow<RemoteVolume>

    /** The TV went away: off, off the network, or taken over by another app. */
    val lost: StateFlow<Boolean>

    /** Who may fetch the video from this computer's server. */
    val allowedClient: ((InetAddress) -> Boolean)?

    /** Paused with no request for this long closes the cast (DLNA, 30 minutes); null never. */
    val idleLimitMs: Long? get() = null

    /** The bar may move on a video the receiver seeks itself. False: "This TV does not take seeking". */
    val canSeek: Boolean get() = true

    /** The plan as this receiver can use it (DLNA drops HLS). */
    fun adapt(plan: CastPlan): CastPlan = plan

    suspend fun connect()
    suspend fun load(request: ReceiverLoad): ReceiverAnswer
    fun play()
    fun pause()
    fun stopMedia()
    fun seek(positionMs: Long)
    fun setRate(rate: Double)
    fun setActiveTracks(ids: List<Long>)
    fun setVolume(level: Double)
    fun setMuted(muted: Boolean)

    /** Ends what the TV is doing and lets go. */
    fun stopReceiver()

    /** Lets go without telling the TV anything. */
    fun close()

    /** For a Cast receiver, the LOAD error text the TV last gave. */
    val lastError: String? get() = null

    companion object {
        fun of(target: CastTarget): Receiver = when (target.kind) {
            ReceiverKind.GoogleCast -> CastReceiver(target, CastClient(requireNotNull(target.cast)))
            ReceiverKind.Dlna -> DlnaReceiver(target, requireNotNull(target.dlna))
        }
    }
}

/** [Receiver] over [CastClient]: the Cast protocol, with the LOAD message built here. */
class CastReceiver(override val target: CastTarget, val client: CastClient) : Receiver {

    override val caps: ReceiverCaps get() = ReceiverCaps.forModel(target.model)
    override val media: StateFlow<RemoteMediaStatus> get() = client.media
    override val volume: StateFlow<RemoteVolume> get() = client.volume
    override val lost: StateFlow<Boolean> get() = client.lost
    override val allowedClient: ((InetAddress) -> Boolean)? get() = null
    override val lastError: String? get() = client.errors.value

    override suspend fun connect() = client.connect()

    override suspend fun load(request: ReceiverLoad): ReceiverAnswer {
        val media = JSONObject()
            .put("contentId", request.url)
            .put("contentUrl", request.url)
            .put("contentType", request.mime)
            .put("streamType", "BUFFERED")
            .put(
                "metadata",
                JSONObject().put("metadataType", 0).put("title", request.title).apply {
                    if (request.subtitle.isNotBlank()) put("subtitle", request.subtitle)
                },
            )
        if (request.durationMs > 0) media.put("duration", request.durationMs / 1000.0)
        if (request.hls) {
            // fMP4 segments for the sound and the picture alike (CMAF), not MPEG-TS.
            media.put("hlsSegmentFormat", "fmp4").put("hlsVideoSegmentFormat", "fmp4")
        } else if (request.sideTracks.isNotEmpty()) {
            media.put(
                "tracks",
                JSONArray(
                    request.sideTracks.map { s ->
                        JSONObject().put("trackId", s.id).put("type", "TEXT").put("subtype", "SUBTITLES")
                            .put("trackContentId", s.url).put("trackContentType", "text/vtt").put("name", s.label)
                            .apply { s.language?.let { put("language", it) } }
                    },
                ),
            )
            media.put("textTrackStyle", JSONObject().put("backgroundColor", "#00000000").put("edgeType", "OUTLINE").put("edgeColor", "#000000FF"))
        }
        val answer = client.load(media, request.startMs, request.autoplay, request.activeTrackIds)
        return if (answer.optString("type") == "MEDIA_STATUS") {
            ReceiverAnswer.Loaded
        } else {
            Logger.w(TAG, "Receiver refused: $answer")
            ReceiverAnswer.Refused()
        }
    }

    override fun play() = client.play()
    override fun pause() = client.pause()
    override fun stopMedia() = client.stopMedia()
    override fun seek(positionMs: Long) = client.seek(positionMs)
    override fun setRate(rate: Double) = client.setRate(rate)
    override fun setActiveTracks(ids: List<Long>) = client.setActiveTracks(ids)
    override fun setVolume(level: Double) = client.setVolume(level)
    override fun setMuted(muted: Boolean) = client.setMuted(muted)
    override fun stopReceiver() = client.stopReceiver()
    override fun close() = client.close()

    private companion object {
        const val TAG = "CastReceiver"
    }
}

/**
 * [Receiver] over [DlnaRenderer]: SetAVTransportURI with a DIDL item, Play, Pause, Seek and the
 * polled position turned into [RemoteMediaStatus]. Polling starts with the first load and ends
 * with [close], so a session that is not casting costs no request. Every call blocks on a SOAP
 * round trip, so each runs off the caller's thread.
 */
class DlnaReceiver(
    override val target: CastTarget,
    private val device: DlnaDevice,
    soap: SoapTransport = SoapClient(),
) : Receiver {

    private val renderer = DlnaRenderer(device, soap)
    private val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.IO + com.tmplayer.platform.JobFailures.handler("DlnaReceiver"))

    @Volatile
    private var sink = com.tmplayer.cast.dlna.SinkCaps.SAFE
    override val caps: ReceiverCaps get() = sink.caps

    private val _media = MutableStateFlow(RemoteMediaStatus())
    override val media: StateFlow<RemoteMediaStatus> = _media.asStateFlow()
    private val _volume = MutableStateFlow(RemoteVolume(fixed = !renderer.hasVolume))
    override val volume: StateFlow<RemoteVolume> = _volume.asStateFlow()
    private val _lost = MutableStateFlow(false)
    override val lost: StateFlow<Boolean> = _lost.asStateFlow()

    /** Only the TV itself may fetch the video. */
    override val allowedClient: (InetAddress) -> Boolean = { it == device.address }
    override val idleLimitMs: Long = IDLE_LIMIT_MS
    override val canSeek: Boolean get() = renderer.canSeek

    @Volatile
    private var features = DlnaFeatures()

    @Volatile
    private var refusal: String? = null
    override val lastError: String? get() = refusal

    override fun adapt(plan: CastPlan): CastPlan = plan.forDlna()

    override suspend fun connect() {
        withContext(Dispatchers.IO) {
            // The TV answers a transport question or it is not there to cast to.
            if (renderer.pollOnce() == null) throw IOException("${device.name} did not answer")
            sink = renderer.sinkCaps()
            renderer.volume()?.let { _volume.value = RemoteVolume(level = it / 100.0, fixed = false) }
        }
    }

    override suspend fun load(request: ReceiverLoad): ReceiverAnswer = withContext(Dispatchers.IO) {
        features = DlnaFeatures(profile = null, rangeSeek = request.seekable, converted = !request.seekable)
        val didl = Didl.item(
            request.title, request.url, request.mime, features,
            durationMs = request.durationMs.takeIf { it > 0 },
        )
        refusal = null
        when (val r = renderer.load(request.url, didl, startMs = request.startMs, durationMs = request.durationMs, autoplay = request.autoplay)) {
            DlnaLoad.Loaded -> {
                _media.value = RemoteMediaStatus(playerState = "LOADING", durationMs = request.durationMs, atNanos = System.nanoTime(), contentId = request.url)
                currentUrl = request.url
                renderer.startPolling(onMedia = ::absorb, onLost = { _lost.value = true })
                ReceiverAnswer.Loaded
            }
            is DlnaLoad.Refused -> {
                Logger.w(TAG, "Refused ${r.code} ${r.text}")
                val why = when (r.reason) {
                    LoadRefusal.BadType -> L.castRefusedType
                    LoadRefusal.NotFound -> L.castRefusedOpen
                    LoadRefusal.Busy -> L.castRefusedBusy
                    LoadRefusal.Other -> null
                }
                refusal = why
                ReceiverAnswer.Refused(why)
            }
            is DlnaLoad.Failed -> {
                Logger.w(TAG, "Load failed: ${r.message}")
                ReceiverAnswer.Refused()
            }
        }
    }

    @Volatile
    private var currentUrl: String? = null

    private fun absorb(m: ReceiverMedia) {
        _media.value = RemoteMediaStatus(
            playerState = when (m.state) {
                ReceiverState.Playing -> "PLAYING"
                ReceiverState.Paused -> "PAUSED"
                ReceiverState.Buffering -> "BUFFERING"
                ReceiverState.Loading -> "LOADING"
                ReceiverState.Idle -> "IDLE"
            },
            idleReason = when (m.idleReason) {
                IdleReason.Finished -> "FINISHED"
                IdleReason.Cancelled -> "CANCELLED"
                IdleReason.Interrupted -> "INTERRUPTED"
                IdleReason.Error -> "ERROR"
                null -> null
            },
            currentTimeMs = m.positionMs,
            durationMs = renderer.durationMs,
            rate = m.rate,
            atNanos = m.atMs * 1_000_000,
            contentId = currentUrl,
        )
    }

    private fun async(block: () -> Unit) {
        scope.launch { runCatching(block).onFailure { Logger.w(TAG, "Call failed", it) } }
    }

    override fun play() = async {
        val r = renderer.play()
        if (r !is DlnaLoad.Loaded) Logger.w(TAG, "Play: $r")
    }

    override fun pause() = async { renderer.pause() }
    override fun stopMedia() = async { renderer.stop() }

    override fun seek(positionMs: Long) = async {
        val r = renderer.seek(positionMs)
        if (r != SeekOutcome.Done) Logger.w(TAG, "Seek: $r")
    }

    override fun setRate(rate: Double) = Unit
    override fun setActiveTracks(ids: List<Long>) = Unit

    override fun setVolume(level: Double) {
        val l = level.coerceIn(0.0, 1.0)
        _volume.value = _volume.value.copy(level = l)
        async { renderer.setVolume((l * 100).toInt()) }
    }

    override fun setMuted(muted: Boolean) {
        _volume.value = _volume.value.copy(muted = muted)
        async { renderer.setMuted(muted) }
    }

    override fun stopReceiver() {
        // The Stop is the last thing the TV hears; it goes on its own thread, as it waits on the TV.
        Thread({
            runCatching { renderer.stop() }
            close()
        }, "dlna-stop").apply { isDaemon = true }.start()
    }

    override fun close() {
        renderer.close()
        scope.cancel()
    }

    private companion object {
        const val TAG = "DlnaReceiver"
        const val IDLE_LIMIT_MS = 30 * 60_000L
    }
}
