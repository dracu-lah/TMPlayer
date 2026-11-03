package com.tmplayer.cast

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.mediarouter.media.MediaRouteSelector
import androidx.mediarouter.media.MediaRouter
import com.google.android.gms.cast.CastDevice
import com.google.android.gms.cast.CastMediaControlIntent
import com.google.android.gms.cast.CastStatusCodes
import com.google.android.gms.cast.HlsSegmentFormat
import com.google.android.gms.cast.HlsVideoSegmentFormat
import com.google.android.gms.cast.MediaInfo
import com.google.android.gms.cast.MediaLoadRequestData
import com.google.android.gms.cast.MediaMetadata
import com.google.android.gms.cast.MediaSeekOptions
import com.google.android.gms.cast.MediaStatus
import com.google.android.gms.cast.MediaTrack
import com.google.android.gms.cast.TextTrackStyle
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.CastOptions
import com.google.android.gms.cast.framework.CastSession
import com.google.android.gms.cast.framework.OptionsProvider
import com.google.android.gms.cast.framework.SessionManagerListener
import com.google.android.gms.cast.framework.SessionProvider
import com.google.android.gms.cast.framework.media.CastMediaOptions
import com.google.android.gms.cast.framework.media.NotificationOptions
import com.google.android.gms.cast.framework.media.RemoteMediaClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The regular build's casting: Google's Cast framework (CP73). */
internal object CastBuild {
    fun create(context: Context): CastSupport = GoogleCastSupport(context)
}

/**
 * What the Cast framework reads at start: Google's Default Media Receiver (no receiver app of
 * TMPlayer's own to host), the framework's media notification and lock screen controls while a
 * video is on the TV, and the receiver stopped when the session ends.
 */
class CastOptionsProvider : OptionsProvider {
    override fun getCastOptions(context: Context): CastOptions {
        val notification = NotificationOptions.Builder()
            .setActions(
                listOf(
                    com.google.android.gms.cast.framework.media.MediaIntentReceiver.ACTION_REWIND,
                    com.google.android.gms.cast.framework.media.MediaIntentReceiver.ACTION_TOGGLE_PLAYBACK,
                    com.google.android.gms.cast.framework.media.MediaIntentReceiver.ACTION_FORWARD,
                    com.google.android.gms.cast.framework.media.MediaIntentReceiver.ACTION_STOP_CASTING,
                ),
                intArrayOf(1, 2),
            )
            .setSkipStepMs(SKIP_MS)
            .setTargetActivityClassName("com.tmplayer.MainActivity")
            .build()
        val media = CastMediaOptions.Builder()
            .setNotificationOptions(notification)
            .setMediaSessionEnabled(true)
            .build()
        return CastOptions.Builder()
            .setReceiverApplicationId(CastMediaControlIntent.DEFAULT_MEDIA_RECEIVER_APPLICATION_ID)
            .setCastMediaOptions(media)
            .setStopReceiverApplicationWhenEndingSession(true)
            .build()
    }

    override fun getAdditionalSessionProviders(context: Context): List<SessionProvider>? = null

    private companion object {
        const val SKIP_MS = 10_000L
    }
}

/**
 * [CastSupport] over the Cast framework: MediaRouter finds the receivers while a picker is open
 * and selecting one starts a [CastSession]; its [RemoteMediaClient] loads and drives the video, and
 * its status comes back as [RemoteStatus]. Everything here runs on the main thread, as the
 * framework requires.
 */
private class GoogleCastSupport(context: Context) : CastSupport {

    private val main = Handler(Looper.getMainLooper())
    private val castContext: CastContext? = runCatching { CastContext.getSharedInstance(context) }
        .onFailure { Log.w(TAG, "No Cast framework here (no Google Play services?)", it) }
        .getOrNull()
    private val router: MediaRouter? = castContext?.let { runCatching { MediaRouter.getInstance(context) }.getOrNull() }
    private val selector = MediaRouteSelector.Builder()
        .addControlCategory(CastMediaControlIntent.categoryForCast(CastMediaControlIntent.DEFAULT_MEDIA_RECEIVER_APPLICATION_ID))
        .build()

    override val available: Boolean = castContext != null && router != null

    private val _devices = MutableStateFlow<List<CastDeviceInfo>>(emptyList())
    override val devices: StateFlow<List<CastDeviceInfo>> = _devices.asStateFlow()
    private val _session = MutableStateFlow<CastSessionState>(CastSessionState.Idle)
    override val session: StateFlow<CastSessionState> = _session.asStateFlow()
    private val _remote = MutableStateFlow(RemoteStatus())
    override val remote: StateFlow<RemoteStatus> = _remote.asStateFlow()

    private var discovering = 0
    private var connectingId: String? = null

    private val routeCallback = object : MediaRouter.Callback() {
        override fun onRouteAdded(router: MediaRouter, route: MediaRouter.RouteInfo) = refreshRoutes()
        override fun onRouteRemoved(router: MediaRouter, route: MediaRouter.RouteInfo) = refreshRoutes()
        override fun onRouteChanged(router: MediaRouter, route: MediaRouter.RouteInfo) = refreshRoutes()
        override fun onRouteSelected(router: MediaRouter, route: MediaRouter.RouteInfo, reason: Int) = refreshRoutes()
        override fun onRouteUnselected(router: MediaRouter, route: MediaRouter.RouteInfo, reason: Int) {
            // The TV being connected to was let go by something other than us (the phone's own
            // route selected under it, as on the device test's first tap): selected again, once.
            val ours = SystemClock.elapsedRealtime() < releasingUntilMs
            if (route.id == connectingId && !ours && reselectsLeft > 0 && _session.value is CastSessionState.Connecting) {
                reselectsLeft--
                Log.i(TAG, "Route ${route.name} unselected while connecting (reason $reason); selecting it again")
                val id = route.id
                main.postDelayed({ if (connectingId == id) router.routes.firstOrNull { it.id == id }?.let(router::selectRoute) }, RESELECT_DELAY_MS)
            }
            refreshRoutes()
        }
    }

    /** Until when an unselect of the TV's route is our own letting go of a stale selection. */
    private var releasingUntilMs = 0L
    private var reselectsLeft = 0

    private var client: RemoteMediaClient? = null

    /** The session was suspended and has not come back: its end is a drop, whatever the error says. */
    private var suspended = false

    /** When the receiver's last media status arrived, for the position's run on from it. */
    private var statusAtMs = 0L

    private val mediaCallback = object : RemoteMediaClient.Callback() {
        override fun onStatusUpdated() {
            statusAtMs = SystemClock.elapsedRealtime()
            publishRemote()
        }

        override fun onMetadataUpdated() = publishRemote()
    }

    /** The TV's volume as the route has it, while a session is on (no discovery asked). */
    private val volumeCallback = object : MediaRouter.Callback() {
        override fun onRouteVolumeChanged(router: MediaRouter, route: MediaRouter.RouteInfo) = publishRemote()
        override fun onRouteChanged(router: MediaRouter, route: MediaRouter.RouteInfo) = publishRemote()
    }

    private val castListener = object : com.google.android.gms.cast.Cast.Listener() {
        override fun onVolumeChanged() = publishRemote()
    }

    private val sessions = object : SessionManagerListener<CastSession> {
        override fun onSessionStarting(session: CastSession) {
            _session.value = CastSessionState.Connecting(session.castDevice?.friendlyName.orEmpty())
        }

        override fun onSessionStarted(session: CastSession, sessionId: String) = attach(session)

        override fun onSessionStartFailed(session: CastSession, error: Int) {
            Log.w(TAG, "Cast session failed to start: $error")
            _session.value = CastSessionState.Failed(session.castDevice?.friendlyName.orEmpty())
            connectingId = null
            releaseHeldDiscovery()
            refreshRoutes()
        }

        override fun onSessionEnding(session: CastSession) = Unit

        override fun onSessionEnded(session: CastSession, error: Int) {
            connectingId = null
            releaseHeldDiscovery()
            detach()
            // Stopped by someone (Stop casting here, the notification, the system's Cast dialog),
            // a session ends with no error; one the framework gave up on ends with an error, or
            // after a suspension it never came back from ("Disconnecting due to heartbeat timeout").
            val dropped = error != CastStatusCodes.SUCCESS || suspended
            suspended = false
            Log.i(TAG, "Cast session ended (error $error, ${if (dropped) "lost" else "stopped"})")
            _session.value = if (dropped) CastSessionState.Lost(session.castDevice?.friendlyName.orEmpty()) else CastSessionState.Idle
        }

        override fun onSessionResuming(session: CastSession, sessionId: String) = Unit

        override fun onSessionResumed(session: CastSession, wasSuspended: Boolean) = attach(session)

        override fun onSessionResumeFailed(session: CastSession, error: Int) {
            detach()
            suspended = false
            Log.i(TAG, "Cast session could not be resumed (error $error)")
            _session.value = CastSessionState.Lost(session.castDevice?.friendlyName.orEmpty())
        }

        override fun onSessionSuspended(session: CastSession, reason: Int) {
            suspended = true
            _session.value = CastSessionState.Suspended(session.castDevice?.friendlyName.orEmpty())
        }
    }

    init {
        castContext?.sessionManager?.addSessionManagerListener(sessions, CastSession::class.java)
        // A session left from before (the app was killed while casting) is picked up again.
        castContext?.sessionManager?.currentCastSession?.takeIf { it.isConnected }?.let(::attach)
    }

    private fun attach(session: CastSession) {
        detach()
        suspended = false
        val device = session.castDevice
        connectingId = null
        releaseHeldDiscovery()
        session.addCastListener(castListener)
        currentSession = session
        client = session.remoteMediaClient?.also { c -> c.registerCallback(mediaCallback) }
        statusAtMs = SystemClock.elapsedRealtime()
        router?.addCallback(selector, volumeCallback, 0)
        _session.value = CastSessionState.Connected(
            name = device?.friendlyName.orEmpty(),
            model = device?.modelName.orEmpty(),
            address = runCatching { device?.inetAddress }.getOrNull(),
        )
        publishRemote()
        refreshRoutes()
    }

    private var currentSession: CastSession? = null

    private fun detach() {
        client?.let { c -> runCatching { c.unregisterCallback(mediaCallback) } }
        runCatching { router?.removeCallback(volumeCallback) }
        client = null
        currentSession?.let { runCatching { it.removeCastListener(castListener) } }
        currentSession = null
        _remote.value = RemoteStatus()
    }

    private fun publishRemote() {
        val c = client
        val s = currentSession
        val status: MediaStatus? = c?.mediaStatus
        val media = status?.let { st ->
            ReceiverMedia(
                state = when (st.playerState) {
                    MediaStatus.PLAYER_STATE_PLAYING -> ReceiverState.Playing
                    MediaStatus.PLAYER_STATE_PAUSED -> ReceiverState.Paused
                    MediaStatus.PLAYER_STATE_BUFFERING -> ReceiverState.Buffering
                    MediaStatus.PLAYER_STATE_LOADING -> ReceiverState.Loading
                    else -> ReceiverState.Idle
                },
                idleReason = when (st.idleReason) {
                    MediaStatus.IDLE_REASON_FINISHED -> IdleReason.Finished
                    MediaStatus.IDLE_REASON_CANCELED -> IdleReason.Cancelled
                    MediaStatus.IDLE_REASON_INTERRUPTED -> IdleReason.Interrupted
                    MediaStatus.IDLE_REASON_ERROR -> IdleReason.Error
                    else -> null
                },
                positionMs = st.streamPosition,
                atMs = statusAtMs,
                rate = st.playbackRate.takeIf { it > 0 } ?: 1.0,
            )
        }
        val (volume, muted, fixed) = volumeNow(s)
        _remote.value = RemoteStatus(
            media = media,
            durationMs = c?.streamDuration?.takeIf { it > 0 } ?: 0L,
            volume = volume,
            muted = muted,
            volumeFixed = fixed,
            contentId = c?.mediaInfo?.contentId,
            activeTrackIds = status?.activeTrackIds?.toList().orEmpty(),
            tracks = c?.mediaInfo?.mediaTracks.orEmpty().map { t ->
                RemoteTrack(
                    id = t.id,
                    type = when (t.type) {
                        MediaTrack.TYPE_AUDIO -> RemoteTrack.Type.Audio
                        MediaTrack.TYPE_VIDEO -> RemoteTrack.Type.Video
                        else -> RemoteTrack.Type.Text
                    },
                    name = t.name,
                    language = t.language,
                    contentId = t.contentId,
                )
            },
            supportsRate = status?.isMediaCommandSupported(MediaStatus.COMMAND_PLAYBACK_RATE) == true,
        )
    }

    private var lastVolumeLog = ""

    /**
     * The TV's volume, mute, and whether a sender may set it. The route MediaRouter keeps for the
     * session says how the volume is handled (an Android TV on HDMI-CEC keeps it fixed) and where
     * it stands; the session's own reading stands in where the route has none.
     */
    private fun volumeNow(s: CastSession?): Triple<Double, Boolean, Boolean> {
        val route = router?.selectedRoute?.takeIf { !it.isDefault && it.matchesSelector(selector) }
        val sessionVolume = runCatching { s?.volume }.getOrNull()
        val muted = runCatching { s?.isMute }.getOrNull() ?: false
        val fixed = route?.volumeHandling == MediaRouter.RouteInfo.PLAYBACK_VOLUME_FIXED
        val routeVolume = route?.takeIf { it.volumeHandling == MediaRouter.RouteInfo.PLAYBACK_VOLUME_VARIABLE && it.volumeMax > 0 }
            ?.let { it.volume.toDouble() / it.volumeMax }
        val volume = (routeVolume ?: sessionVolume ?: 1.0).coerceIn(0.0, 1.0)
        val log = "route=" + route?.volume + "/" + route?.volumeMax + " handling=" + route?.volumeHandling + " session=" + sessionVolume + " muted=" + muted
        if (log != lastVolumeLog) {
            lastVolumeLog = log
            Log.i(TAG, "TV volume: " + log)
        }
        return Triple(volume, muted, fixed)
    }

    private fun refreshRoutes() {
        val r = router ?: return
        val selected = r.selectedRoute
        val connected = _session.value is CastSessionState.Connected
        _devices.value = r.routes
            .filter { it.isEnabled && !it.isDefault && !it.isBluetooth && it.matchesSelector(selector) }
            .map { route ->
                val device = runCatching { CastDevice.getFromBundle(route.extras) }.getOrNull()
                CastDeviceInfo(
                    id = route.id,
                    name = route.name,
                    model = device?.modelName ?: route.description.orEmpty(),
                    connected = connected && route.id == selected.id,
                    connecting = route.id == connectingId,
                    address = runCatching { device?.inetAddress }.getOrNull(),
                )
            }
            .sortedBy { it.name.lowercase() }
    }

    override fun startDiscovery() {
        val r = router ?: return
        if (discovering++ == 0) r.addCallback(selector, routeCallback, MediaRouter.CALLBACK_FLAG_REQUEST_DISCOVERY)
        refreshRoutes()
    }

    override fun stopDiscovery() {
        val r = router ?: return
        if (discovering > 0 && --discovering == 0) {
            // The picker closes the moment a TV is tapped; the route stays looked after until the
            // session is up (or has failed), so it is not dropped from under the connection.
            if (connectingId != null) discoveryHeld = true else r.removeCallback(routeCallback)
        }
    }

    /** Discovery kept on past the picker's close for a connection under way ([stopDiscovery]). */
    private var discoveryHeld = false

    private fun releaseHeldDiscovery() {
        if (!discoveryHeld) return
        discoveryHeld = false
        if (discovering == 0) runCatching { router?.removeCallback(routeCallback) }
    }

    /**
     * Connects to the receiver [id]. MediaRouter ignores a request to select the route it already
     * has selected, and the first tap of the device test met exactly that: the TV's route was still
     * selected from an earlier session ("Ignoring attempt to select selected route"), so no session
     * started, and a moment later the phone's own route was selected again (onRouteUnselected
     * reason 3). The second tap then worked, because by then the route was no longer selected. So a
     * route selected without a session of ours is let go first (the phone's own route selected) and
     * selected afresh a beat later, which is the second tap done for the viewer.
     */
    override fun connect(id: String) {
        val r = router ?: return
        val route = r.routes.firstOrNull { it.id == id } ?: return
        connectingId = id
        _session.value = CastSessionState.Connecting(route.name)
        refreshRoutes()
        reselectsLeft = 1
        val stale = r.selectedRoute.id == route.id && castContext?.sessionManager?.currentCastSession?.isConnected != true
        if (stale) {
            Log.i(TAG, "Route ${route.name} is still selected with no session; selecting it afresh")
            releasingUntilMs = SystemClock.elapsedRealtime() + RESELECT_DELAY_MS
            r.selectRoute(r.defaultRoute)
            main.postDelayed({
                if (connectingId == id) r.routes.firstOrNull { it.id == id }?.let(r::selectRoute)
            }, RESELECT_DELAY_MS)
        } else {
            r.selectRoute(route)
        }
        // A connection that never answers is given up, so the picker does not say Connecting forever.
        main.postDelayed({
            if (connectingId == id && _session.value is CastSessionState.Connecting) {
                Log.w(TAG, "No session from ${route.name} after ${CONNECT_TIMEOUT_MS} ms")
                connectingId = null
                _session.value = CastSessionState.Failed(route.name)
                releaseHeldDiscovery()
                refreshRoutes()
            }
        }, CONNECT_TIMEOUT_MS)
    }

    override fun endSession() {
        castContext?.sessionManager?.endCurrentSession(true)
    }

    override fun load(request: CastLoadRequest, onResult: (Boolean) -> Unit) {
        val c = client ?: return onResult(false)
        val metadata = MediaMetadata(MediaMetadata.MEDIA_TYPE_MOVIE).apply {
            putString(MediaMetadata.KEY_TITLE, request.title)
            if (request.subtitle.isNotBlank()) putString(MediaMetadata.KEY_SUBTITLE, request.subtitle)
        }
        val tracks = request.subtitles.map { s ->
            MediaTrack.Builder(s.id, MediaTrack.TYPE_TEXT)
                .setName(s.name)
                .setSubtype(MediaTrack.SUBTYPE_SUBTITLES)
                .setContentId(s.url)
                .setContentType("text/vtt")
                .apply { s.language?.let { setLanguage(it) } }
                .build()
        }
        val info = MediaInfo.Builder(request.url)
            .setContentUrl(request.url)
            .setContentType(request.mime)
            .setStreamType(MediaInfo.STREAM_TYPE_BUFFERED)
            .setMetadata(metadata)
            .apply {
                if (request.hls) {
                    // fMP4 segments for the sound and the picture alike (CMAF), not MPEG-TS.
                    setHlsSegmentFormat(HlsSegmentFormat.FMP4)
                    setHlsVideoSegmentFormat(HlsVideoSegmentFormat.FMP4)
                }
                if (request.durationMs > 0) setStreamDuration(request.durationMs)
                if (tracks.isNotEmpty()) {
                    setMediaTracks(tracks)
                    setTextTrackStyle(
                        TextTrackStyle().apply {
                            backgroundColor = 0x00000000
                            edgeType = TextTrackStyle.EDGE_TYPE_OUTLINE
                            edgeColor = 0x000000FF
                        },
                    )
                }
            }
            .build()
        val load = MediaLoadRequestData.Builder()
            .setMediaInfo(info)
            .setAutoplay(request.autoplay)
            .setCurrentTime(request.startMs)
            .apply { if (request.activeTrackIds.isNotEmpty()) setActiveTrackIds(request.activeTrackIds.toLongArray()) }
            .build()
        c.load(load).setResultCallback { result ->
            val ok = result.status.isSuccess
            if (!ok) Log.w(TAG, "Receiver refused the load: ${result.status}")
            main.post { onResult(ok) }
        }
    }

    override fun play() {
        client?.play()
    }

    override fun pause() {
        client?.pause()
    }

    override fun seek(positionMs: Long) {
        client?.seek(MediaSeekOptions.Builder().setPosition(positionMs).build())
    }

    override fun stopMedia() {
        client?.stop()
    }

    override fun setVolume(level: Double) {
        val v = level.coerceIn(0.0, 1.0)
        val route = router?.selectedRoute?.takeIf { !it.isDefault && it.volumeHandling == MediaRouter.RouteInfo.PLAYBACK_VOLUME_VARIABLE && it.volumeMax > 0 }
        if (route != null) route.requestSetVolume(Math.round(v * route.volumeMax).toInt())
        else runCatching { currentSession?.volume = v }
        if (v > 0 && _remote.value.muted) runCatching { currentSession?.isMute = false }
        // Shown at once; the TV's own word follows.
        _remote.value = _remote.value.copy(volume = v, muted = if (v > 0) false else _remote.value.muted)
    }

    override fun setMuted(muted: Boolean) {
        runCatching { currentSession?.isMute = muted }
        _remote.value = _remote.value.copy(muted = muted)
    }

    override fun setActiveTracks(ids: List<Long>) {
        val c = client ?: return
        c.setActiveMediaTracks(ids.toLongArray()).setResultCallback { r ->
            if (!r.status.isSuccess) Log.w(TAG, "Receiver refused tracks " + ids + ": " + r.status)
        }
    }

    override fun setRate(rate: Double) {
        client?.setPlaybackRate(rate)
    }

    private companion object {
        const val TAG = "TMPlayerCast"

        /** Between letting a stale route go and selecting it again. */
        const val RESELECT_DELAY_MS = 300L

        /** How long a tapped TV has to start a session. */
        const val CONNECT_TIMEOUT_MS = 30_000L
    }
}
