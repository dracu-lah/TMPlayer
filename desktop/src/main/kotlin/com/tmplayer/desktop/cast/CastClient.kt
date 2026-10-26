package com.tmplayer.desktop.cast

import com.tmplayer.cast.ReceiverMedia
import com.tmplayer.cast.RemoteTrack
import com.tmplayer.platform.Logger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509TrustManager

/** What the receiver's media session says about itself, as of its last MEDIA_STATUS. */
data class RemoteMediaStatus(
    val mediaSessionId: Int = 0,
    /** IDLE, LOADING, BUFFERING, PLAYING or PAUSED. */
    val playerState: String = "IDLE",
    /** Why it is idle: FINISHED, CANCELLED, INTERRUPTED or ERROR. */
    val idleReason: String? = null,
    val currentTimeMs: Long = 0,
    val durationMs: Long = 0,
    val rate: Double = 1.0,
    val activeTrackIds: List<Int> = emptyList(),
    /** When this status arrived, on the monotonic clock, for [positionAt]. */
    val atNanos: Long = System.nanoTime(),
    /** The contentId the status is about, so a late status of the last video is not taken for this one. */
    val contentId: String? = null,
    /** The receiver's track list (for HLS, made by the receiver from the playlist). */
    val tracks: List<RemoteTrack> = emptyList(),
    /** The receiver's supportedMediaCommands bits. */
    val supportedCommands: Long = 0,
) {
    val playing: Boolean get() = playerState == "PLAYING"

    /** The receiver changes its playback rate (PLAYBACK_RATE, 8192). */
    val supportsRate: Boolean get() = supportedCommands and 8192L != 0L

    /** As the shared state mapper reads it, on the millisecond monotonic clock. */
    fun toReceiverMedia(): ReceiverMedia = ReceiverMedia(
        state = ReceiverMedia.state(playerState),
        idleReason = ReceiverMedia.idleReason(idleReason),
        positionMs = currentTimeMs,
        atMs = atNanos / 1_000_000,
        rate = rate,
    )

    /** The position now, run forward from the status while it plays. */
    fun positionAt(nowNanos: Long = System.nanoTime()): Long {
        if (!playing) return currentTimeMs
        val elapsed = (nowNanos - atNanos) / 1_000_000
        return currentTimeMs + (elapsed * rate).toLong()
    }
}

/**
 * The receiver's own volume, as its RECEIVER_STATUS gives it. [fixed]: the device takes no volume
 * from a sender (controlType "fixed", an Android TV on HDMI-CEC); its own remote sets it.
 */
data class RemoteVolume(val level: Double = 1.0, val muted: Boolean = false, val fixed: Boolean = false)

/**
 * One Cast session with one device, spoken over the Cast protocol (CastV2) without Google's SDK,
 * which exists for Android, iOS and Chrome only.
 *
 * [connect] opens TLS to port 8009 (the receiver's certificate is its own self-signed one, so
 * the channel is encrypted but not checked against a CA: the same trust every open sender
 * extends), says CONNECT to the platform, starts the heartbeat, and launches Google's Default
 * Media Receiver (or joins it if it is already up). After that the media namespace takes LOAD,
 * PLAY, PAUSE, SEEK, STOP, SET_PLAYBACK_RATE and EDIT_TRACKS_INFO, and the platform takes the
 * volume. A missing heartbeat for [LOST_AFTER_MS], or the socket closing, makes [lost] true.
 */
class CastClient(val device: CastDevice) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + com.tmplayer.platform.JobFailures.handler("CastClient"))
    private val requestIds = AtomicInteger(1)
    private val pending = ConcurrentHashMap<Int, CompletableDeferred<JSONObject>>()
    private val senderId = "sender-" + Integer.toHexString(SecureRandom().nextInt() and 0x7FFFFFFF)

    private var socket: SSLSocket? = null
    private var output: OutputStream? = null

    @Volatile
    private var lastHeard = System.nanoTime()

    /** The Default Media Receiver's transport, once launched: where media messages go. */
    @Volatile
    var transportId: String? = null
        private set

    @Volatile
    var appSessionId: String? = null
        private set

    private val _media = MutableStateFlow(RemoteMediaStatus())
    val media: StateFlow<RemoteMediaStatus> = _media.asStateFlow()

    private val _volume = MutableStateFlow(RemoteVolume())
    val volume: StateFlow<RemoteVolume> = _volume.asStateFlow()

    private val _lost = MutableStateFlow(false)

    /** The connection broke: the TV went off, left the network, or another app took it over. */
    val lost: StateFlow<Boolean> = _lost.asStateFlow()

    /** Errors the receiver reported for a load (LOAD_FAILED and the like), newest last. */
    private val _errors = MutableStateFlow<String?>(null)
    val errors: StateFlow<String?> = _errors.asStateFlow()

    suspend fun connect() {
        val context = SSLContext.getInstance("TLS")
        context.init(null, arrayOf(TrustTheDevice), SecureRandom())
        val s = context.socketFactory.createSocket() as SSLSocket
        s.connect(InetSocketAddress(device.address, device.port), CONNECT_TIMEOUT_MS)
        s.soTimeout = 0
        s.startHandshake()
        socket = s
        output = s.outputStream
        val input = s.inputStream
        scope.launch { readLoop(input) }
        send(CastNamespaces.PLATFORM, CastNamespaces.CONNECTION, JSONObject().put("type", "CONNECT").put("userAgent", "TMPlayer"))
        scope.launch { heartbeat() }
        launchReceiver()
    }

    private suspend fun launchReceiver() {
        val status = request(CastNamespaces.PLATFORM, CastNamespaces.RECEIVER, JSONObject().put("type", "GET_STATUS"))
        absorbReceiver(status)
        if (transportId == null || runningApp(status) != CastNamespaces.DEFAULT_MEDIA_RECEIVER) {
            transportId = null
            val launched = request(
                CastNamespaces.PLATFORM,
                CastNamespaces.RECEIVER,
                JSONObject().put("type", "LAUNCH").put("appId", CastNamespaces.DEFAULT_MEDIA_RECEIVER),
                timeoutMs = LAUNCH_TIMEOUT_MS,
            )
            absorbReceiver(launched)
            // A launch is answered by RECEIVER_STATUS once the app is up; wait for its transport.
            withTimeoutOrNull(LAUNCH_TIMEOUT_MS) { while (transportId == null) delay(100) }
        }
        val transport = transportId ?: throw IOException("The receiver did not start")
        send(transport, CastNamespaces.CONNECTION, JSONObject().put("type", "CONNECT").put("userAgent", "TMPlayer"))
        runCatching { request(transport, CastNamespaces.MEDIA, JSONObject().put("type", "GET_STATUS")) }
    }

    private fun runningApp(status: JSONObject): String? =
        status.optJSONObject("status")?.optJSONArray("applications")?.optJSONObject(0)?.optString("appId")

    private fun absorbReceiver(message: JSONObject) {
        val status = message.optJSONObject("status") ?: return
        status.optJSONObject("volume")?.let { v ->
            _volume.value = RemoteVolume(
                level = v.optDouble("level", _volume.value.level).takeIf { !it.isNaN() } ?: _volume.value.level,
                muted = v.optBoolean("muted", _volume.value.muted),
                fixed = v.optString("controlType") == "fixed",
            )
        }
        val apps = status.optJSONArray("applications")
        val app = (0 until (apps?.length() ?: 0)).map { apps!!.getJSONObject(it) }
            .firstOrNull { it.optString("appId") == CastNamespaces.DEFAULT_MEDIA_RECEIVER }
        if (app != null) {
            transportId = app.optString("transportId").ifBlank { null }
            appSessionId = app.optString("sessionId").ifBlank { null }
        } else if (transportId != null && apps != null) {
            // Something else took the screen (the viewer opened YouTube on the TV).
            Logger.i(TAG, "Default Media Receiver gone from ${device.name}")
            transportId = null
            _lost.value = true
        }
    }

    /** Loads [media] (the LOAD message's own "media" object) and starts it at [startMs]. */
    suspend fun load(media: JSONObject, startMs: Long, autoplay: Boolean = true, activeTrackIds: List<Int> = emptyList()): JSONObject {
        val transport = transportId ?: throw IOException("Not connected")
        _errors.value = null
        val body = JSONObject()
            .put("type", "LOAD")
            .put("media", media)
            .put("autoplay", autoplay)
            .put("currentTime", startMs / 1000.0)
        if (activeTrackIds.isNotEmpty()) body.put("activeTrackIds", JSONArray(activeTrackIds))
        appSessionId?.let { body.put("sessionId", it) }
        return request(transport, CastNamespaces.MEDIA, body, timeoutMs = LOAD_TIMEOUT_MS)
    }

    fun play() = mediaCommand("PLAY")
    fun pause() = mediaCommand("PAUSE")
    fun stopMedia() = mediaCommand("STOP")

    fun seek(positionMs: Long) = mediaCommand("SEEK") { it.put("currentTime", positionMs / 1000.0) }

    fun setRate(rate: Double) = mediaCommand("SET_PLAYBACK_RATE") { it.put("playbackRate", rate) }

    fun setActiveTracks(ids: List<Long>) = mediaCommand("EDIT_TRACKS_INFO") { it.put("activeTrackIds", JSONArray(ids)) }

    fun setVolume(level: Double) {
        _volume.value = _volume.value.copy(level = level.coerceIn(0.0, 1.0))
        scope.launch {
            runCatching {
                send(CastNamespaces.PLATFORM, CastNamespaces.RECEIVER, JSONObject().put("type", "SET_VOLUME").put("requestId", requestIds.getAndIncrement()).put("volume", JSONObject().put("level", level.coerceIn(0.0, 1.0))))
            }
        }
    }

    fun setMuted(muted: Boolean) {
        _volume.value = _volume.value.copy(muted = muted)
        scope.launch {
            runCatching {
                send(CastNamespaces.PLATFORM, CastNamespaces.RECEIVER, JSONObject().put("type", "SET_VOLUME").put("requestId", requestIds.getAndIncrement()).put("volume", JSONObject().put("muted", muted)))
            }
        }
    }

    private fun mediaCommand(type: String, fill: (JSONObject) -> Unit = {}) {
        val transport = transportId ?: return
        val session = _media.value.mediaSessionId.takeIf { it > 0 } ?: return
        val body = JSONObject().put("type", type).put("mediaSessionId", session).put("requestId", requestIds.getAndIncrement())
        fill(body)
        scope.launch { runCatching { send(transport, CastNamespaces.MEDIA, body) } }
    }

    /** Stops the receiver app (the TV goes back to its idle screen) and closes the channel. */
    fun stopReceiver() {
        val session = appSessionId
        runCatching {
            if (session != null) send(CastNamespaces.PLATFORM, CastNamespaces.RECEIVER, JSONObject().put("type", "STOP").put("sessionId", session).put("requestId", requestIds.getAndIncrement()))
        }
        close()
    }

    /** Leaves the receiver as it is and closes the channel. */
    fun close() {
        runCatching { transportId?.let { send(it, CastNamespaces.CONNECTION, JSONObject().put("type", "CLOSE")) } }
        runCatching { socket?.close() }
        socket = null
        pending.values.forEach { it.completeExceptionally(IOException("Closed")) }
        pending.clear()
        scope.cancel()
    }

    private suspend fun request(destination: String, namespace: String, body: JSONObject, timeoutMs: Long = REQUEST_TIMEOUT_MS): JSONObject {
        val id = requestIds.getAndIncrement()
        body.put("requestId", id)
        val answer = CompletableDeferred<JSONObject>()
        pending[id] = answer
        try {
            send(destination, namespace, body)
            return withTimeoutOrNull(timeoutMs) { answer.await() } ?: throw IOException("${body.optString("type")} timed out")
        } finally {
            pending.remove(id)
        }
    }

    @Synchronized
    private fun send(destination: String, namespace: String, body: JSONObject) {
        val out = output ?: throw IOException("Not connected")
        CastMessage.write(out, CastMessage(senderId, destination, namespace, body.toString()))
    }

    private fun readLoop(input: InputStream) {
        try {
            while (scope.isActive) {
                val message = CastMessage.read(input) ?: break
                lastHeard = System.nanoTime()
                val payload = message.payload ?: continue
                val json = runCatching { JSONObject(payload) }.getOrNull() ?: continue
                handle(message, json)
            }
        } catch (e: Exception) {
            if (scope.isActive) Logger.i(TAG, "Channel to ${device.name} closed: ${e.message}")
        }
        if (scope.isActive) _lost.value = true
    }

    private fun handle(message: CastMessage, json: JSONObject) {
        val type = json.optString("type")
        when (message.namespace) {
            CastNamespaces.HEARTBEAT -> if (type == "PING") {
                runCatching { send(message.sourceId, CastNamespaces.HEARTBEAT, JSONObject().put("type", "PONG")) }
            }
            CastNamespaces.CONNECTION -> if (type == "CLOSE" && message.sourceId == transportId) {
                transportId = null
                _lost.value = true
            }
            CastNamespaces.RECEIVER -> if (type == "RECEIVER_STATUS") absorbReceiver(json)
            CastNamespaces.MEDIA -> when (type) {
                "MEDIA_STATUS" -> absorbMedia(json)
                "LOAD_FAILED", "LOAD_CANCELLED", "INVALID_REQUEST", "INVALID_PLAYER_STATE", "ERROR" -> {
                    _errors.value = type + (json.optString("reason").takeIf { it.isNotBlank() }?.let { " ($it)" } ?: "")
                }
            }
        }
        val id = json.optInt("requestId", 0)
        if (id != 0) pending[id]?.complete(json)
    }

    private fun absorbMedia(json: JSONObject) {
        val list = json.optJSONArray("status") ?: return
        if (list.length() == 0) {
            _media.value = _media.value.copy(playerState = "IDLE", atNanos = System.nanoTime())
            return
        }
        val s = list.getJSONObject(0)
        val before = _media.value
        val mediaInfo = s.optJSONObject("media")
        val tracks = s.optJSONArray("activeTrackIds")
        _media.value = RemoteMediaStatus(
            mediaSessionId = s.optInt("mediaSessionId", before.mediaSessionId),
            playerState = s.optString("playerState", before.playerState),
            idleReason = s.optString("idleReason").ifBlank { null },
            currentTimeMs = (s.optDouble("currentTime", before.currentTimeMs / 1000.0) * 1000).toLong(),
            durationMs = mediaInfo?.optDouble("duration", 0.0)?.takeIf { it > 0 && !it.isNaN() }?.let { (it * 1000).toLong() } ?: before.durationMs,
            rate = s.optDouble("playbackRate", before.rate),
            activeTrackIds = tracks?.let { a -> (0 until a.length()).map { a.getInt(it) } } ?: before.activeTrackIds,
            atNanos = System.nanoTime(),
            contentId = mediaInfo?.optString("contentId")?.ifBlank { null } ?: before.contentId,
            tracks = mediaInfo?.optJSONArray("tracks")?.let(::tracksOf) ?: if (mediaInfo == null) before.tracks else emptyList(),
            supportedCommands = s.optLong("supportedMediaCommands", before.supportedCommands),
        )
        // The status's own "volume" is the stream's, not the TV's: the TV's comes with RECEIVER_STATUS.
    }

    private fun tracksOf(a: JSONArray): List<RemoteTrack> = (0 until a.length()).mapNotNull { i ->
        val t = a.optJSONObject(i) ?: return@mapNotNull null
        RemoteTrack(
            id = t.optLong("trackId"),
            type = when (t.optString("type")) {
                "AUDIO" -> RemoteTrack.Type.Audio
                "VIDEO" -> RemoteTrack.Type.Video
                else -> RemoteTrack.Type.Text
            },
            name = t.optString("name").ifBlank { null },
            language = t.optString("language").ifBlank { null },
            contentId = t.optString("trackContentId").ifBlank { null },
        )
    }

    private suspend fun heartbeat() {
        while (scope.isActive) {
            delay(HEARTBEAT_MS)
            runCatching { send(CastNamespaces.PLATFORM, CastNamespaces.HEARTBEAT, JSONObject().put("type", "PING")) }
                .onFailure { _lost.value = true }
            if ((System.nanoTime() - lastHeard) / 1_000_000 > LOST_AFTER_MS) {
                Logger.i(TAG, "${device.name} stopped answering")
                _lost.value = true
                runCatching { socket?.close() }
                break
            }
        }
    }

    /**
     * Accepts the receiver's certificate. Every Cast device presents one it made itself, signed by
     * no public authority; Google's own senders check it against a device certificate chain this
     * open implementation has no root for. The channel is still encrypted, and it only ever goes
     * to an address mDNS found on the local network.
     */
    private object TrustTheDevice : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    companion object {
        private const val TAG = "CastClient"
        private const val CONNECT_TIMEOUT_MS = 5_000
        private const val REQUEST_TIMEOUT_MS = 8_000L
        private const val LAUNCH_TIMEOUT_MS = 20_000L
        private const val LOAD_TIMEOUT_MS = 30_000L
        private const val HEARTBEAT_MS = 5_000L
        const val LOST_AFTER_MS = 16_000L
    }
}
