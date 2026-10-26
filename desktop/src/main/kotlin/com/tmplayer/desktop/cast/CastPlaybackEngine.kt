package com.tmplayer.desktop.cast

import com.tmplayer.cast.CastFormats
import com.tmplayer.cast.CastPhase
import com.tmplayer.cast.CastPlan
import com.tmplayer.cast.CastRefusal
import com.tmplayer.cast.CastRoute
import com.tmplayer.cast.CastServer
import com.tmplayer.cast.CastSource
import com.tmplayer.cast.CastStateMapper
import com.tmplayer.cast.CastStreams
import com.tmplayer.cast.CastView
import com.tmplayer.cast.HlsRendition
import com.tmplayer.cast.HlsTrackMatch
import com.tmplayer.cast.LanAddress
import com.tmplayer.cast.MediaProbe
import com.tmplayer.cast.MkvHls
import com.tmplayer.cast.ReceiverCaps
import com.tmplayer.desktop.player.AbLoop
import com.tmplayer.desktop.player.MediaTrack
import com.tmplayer.desktop.player.OpenPrefs
import com.tmplayer.desktop.player.PlaybackEngine
import com.tmplayer.desktop.player.PlaybackStatus
import com.tmplayer.desktop.player.TrackType
import com.tmplayer.i18n.L
import com.tmplayer.platform.Logger
import com.tmplayer.player.SubtitleStyle
import com.tmplayer.player.VideoScale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.openani.mediamp.source.MediaData
import java.io.File
import java.io.IOException
import java.util.Locale

/** What the TV is told the video is called. */
data class CastTitle(val title: String, val subtitle: String = "")

/**
 * The player as the overlay sees it, with the picture on a TV: every [PlaybackEngine] call turned
 * into the Cast protocol, and the receiver's MEDIA_STATUS turned back into [PlaybackStatus], so the
 * keys, the timebar, the volume and the menus drive the TV with no change of their own.
 *
 * [open] reads what the video is ([MediaProbe]), plans how the receiver can play it
 * ([CastFormats.plan]), serves it from this computer ([CastServer], [CastStreams]) and loads it,
 * walking down the plan's routes when the receiver turns one away. Matroska goes as HLS where it
 * can, so the receiver seeks itself and switches soundtracks and subtitles by EDIT_TRACKS_INFO; a
 * rewrapped stream has no seeking of its own on the receiver, so a seek there is a new stream
 * from the cue before it. What the overlay shows follows the receiver ([CastStateMapper]).
 */
class CastPlaybackEngine(
    val client: CastClient,
    private val describe: () -> CastTitle,
) : PlaybackEngine {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + com.tmplayer.platform.JobFailures.handler("CastPlaybackEngine"))
    private val _state = MutableStateFlow(PlaybackStatus())
    override val state: StateFlow<PlaybackStatus> = _state.asStateFlow()
    private val _tracks = MutableStateFlow<List<MediaTrack>>(emptyList())
    override val tracks: StateFlow<List<MediaTrack>> = _tracks.asStateFlow()

    private val _note = MutableStateFlow<String?>(null)

    /** A line for the casting panel: what was converted, what will not play, what cannot show. */
    val note: StateFlow<String?> = _note.asStateFlow()

    private val _phase = MutableStateFlow(CastPhase.Starting)

    /** What the TV is doing, for the casting panel. */
    val phase: StateFlow<CastPhase> = _phase.asStateFlow()

    private var server: CastServer? = null
    private var streams: CastStreams? = null
    private var data: MediaData? = null
    private var source: CastSource? = null
    private var name = "video"
    private var probe: MediaProbe? = null
    private var plan: CastPlan? = null
    private var routeIndex = 0
    private var route: CastRoute? = null
    private var published: CastStreams.Published? = null
    private var audio: Int? = null

    private class Sub(val id: Int, val label: String, val language: String?, val url: String)

    private val subs = ArrayList<Sub>()
    private var activeSub: Int? = null
    private var hlsAudio: HlsRendition? = null
    private var hlsText: HlsRendition? = null
    private var tracksSent = false
    private val mapper = CastStateMapper()
    private var wantPlaying = true
    private var speed = 1f
    private var loadedAt = 0L
    private var loading = false
    /** Where the TV is, taken only from a playing, paused or buffering TV, so a reload or fallback keeps it. */
    private var place = com.tmplayer.cast.CastPlace()
    private val targetMs: Long get() = place.ms
    private var watcher: Job? = null

    private fun nowMs() = System.nanoTime() / 1_000_000

    override suspend fun open(data: MediaData, startAtMs: Long, prefs: OpenPrefs) {
        withContext(Dispatchers.IO) {
            closeData()
            this@CastPlaybackEngine.data = data
            _state.value = PlaybackStatus(buffering = true, volume = volumePercent())
            _phase.value = CastPhase.Starting
            subs.clear()
            activeSub = null
            hlsText = null
            place = com.tmplayer.cast.CastPlace(startAtMs)
            try {
                val src = StreamBytesCastSource.of(data) ?: throw IOException(L.castCannotCastContainer)
                source = src
                name = StreamBytesCastSource.nameOf(data)
                val p = MediaProbe.of(src, name)
                probe = p
                val preferred = prefs.audioLanguage?.let { lang -> p.audio.firstOrNull { sameLanguage(it.language, lang) }?.number }
                val caps = ReceiverCaps.forModel(client.device.model)
                val pl = CastFormats.plan(p, caps, preferred, canConvertAudio = false, canTranscodeVideo = false)
                plan = pl
                Logger.i(TAG, "Plan for $name on ${client.device.model}: $pl ($p)")
                if (!pl.castable) {
                    throw IOException(
                        when (pl.refusal) {
                            CastRefusal.VideoCodec -> L.castCannotCastPicture
                            CastRefusal.Resolution -> L.castPictureTooLarge
                            else -> L.castCannotCastContainer
                        },
                    )
                }
                audio = pl.audioTrack
                speed = prefs.speed
                ensureServer()
                routeIndex = 0
                watch()
                startRoute(startAtMs)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Logger.w(TAG, "Cast open failed", e)
                _state.value = PlaybackStatus(error = e.message ?: failureText())
                _phase.value = CastPhase.Failed
            }
        }
    }

    /** The TV turned the video down: 4K where it may only play 1080p, or the plain failure. */
    private fun failureText(): String =
        if (CastFormats.mayBeTooLarge(probe?.video, ReceiverCaps.forModel(client.device.model))) L.castLoadFailedLarge else L.castLoadFailed

    private fun ensureServer() {
        if (server?.running == true) return
        val bind = LanAddress.forReceiver(client.device.address) ?: throw IOException(L.castConnectFailed(client.device.name))
        val s = CastServer(bind)
        s.start()
        server = s
        streams = CastStreams(s)
    }

    /** Loads the plan's current route at [atMs], moving down the plan while the receiver refuses. */
    private suspend fun startRoute(atMs: Long) {
        val pl = plan ?: return
        while (routeIndex < pl.routes.size) {
            val r = pl.routes[routeIndex]
            if (load(r, atMs)) return
            routeIndex++
        }
        throw IOException(failureText())
    }

    /** One LOAD; true when the receiver took it. */
    private suspend fun load(r: CastRoute, atMs: Long): Boolean {
        val st = streams ?: return false
        val src = source ?: return false
        val p = probe ?: return false
        loading = true
        place.expect(atMs, nowMs())
        publishState()
        try {
            val chosen = when (r) {
                is CastRoute.Hls -> r.copy(audioTrack = audio?.takeIf { a -> r.audio.any { it.number == a } } ?: r.audioTrack)
                is CastRoute.Remux -> r.copy(audioTrack = audio?.takeIf { a -> p.audio.firstOrNull { it.number == a }?.codec in CastFormats.MP4_AUDIO })
                else -> r
            }
            val pub = st.publish(src, p, chosen, atMs, name)
            val title = describe()
            val media = JSONObject()
                .put("contentId", pub.url)
                .put("contentUrl", pub.url)
                .put("contentType", pub.mime)
                .put("streamType", "BUFFERED")
                .put(
                    "metadata",
                    JSONObject().put("metadataType", 0).put("title", title.title).apply {
                        if (title.subtitle.isNotBlank()) put("subtitle", title.subtitle)
                    },
                )
            val duration = pub.durationMs.takeIf { it > 0 } ?: if (p.durationMs > 0) (p.durationMs - pub.offsetMs).coerceAtLeast(0) else 0
            if (duration > 0) media.put("duration", duration / 1000.0)
            if (pub.hls) {
                // fMP4 segments for the sound and the picture alike (CMAF), not MPEG-TS.
                media.put("hlsSegmentFormat", "fmp4").put("hlsVideoSegmentFormat", "fmp4")
            } else if (subs.isNotEmpty()) {
                media.put(
                    "tracks",
                    JSONArray(
                        subs.map { s ->
                            JSONObject().put("trackId", s.id).put("type", "TEXT").put("subtype", "SUBTITLES")
                                .put("trackContentId", s.url).put("trackContentType", "text/vtt").put("name", s.label)
                                .apply { s.language?.let { put("language", it) } }
                        },
                    ),
                )
                media.put("textTrackStyle", JSONObject().put("backgroundColor", "#00000000").put("edgeType", "OUTLINE").put("edgeColor", "#000000FF"))
            }
            val answer = client.load(
                media,
                if (pub.seekable) atMs else 0L,
                autoplay = wantPlaying,
                activeTrackIds = if (pub.hls) emptyList() else listOfNotNull(activeSub),
            )
            val type = answer.optString("type")
            if (type != "MEDIA_STATUS") {
                Logger.w(TAG, "Receiver refused ${chosen::class.simpleName}: $answer")
                return false
            }
            published = pub
            route = chosen
            loadedAt = System.nanoTime()
            mapper.loaded(nowMs(), wantPlaying)
            if (pub.hls) {
                hlsAudio = pub.renditions.firstOrNull { it.kind == HlsRendition.Kind.Audio && it.trackNumber == audio }
                    ?: pub.renditions.firstOrNull { it.kind == HlsRendition.Kind.Audio && it.isDefault }
                hlsText = pub.renditions.firstOrNull { it.fileId != null && it.fileId == activeSub?.toLong() }
                    ?: hlsText?.let { prev -> pub.renditions.firstOrNull { it.uri == prev.uri } }
                tracksSent = false
            }
            if (speed != 1f) client.setRate(speed.toDouble())
            _note.value = noteFor(chosen)
            _state.value = _state.value.copy(opened = true, error = null, durationMs = p.durationMs.takeIf { it > 0 } ?: duration)
            publishTracks()
            return true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Logger.w(TAG, "Load failed", e)
            return false
        } finally {
            loading = false
            publishState()
        }
    }

    private fun noteFor(r: CastRoute): String? {
        val p = probe ?: return null
        val audioCodec = p.audio.firstOrNull { it.number == audio }?.codec
        return when {
            r is CastRoute.TranscodeVideo -> L.castConvertingComputer
            r is CastRoute.ConvertAudio -> L.castSoundConvertedComputer
            r is CastRoute.Hls -> if (r.audio.isEmpty() && p.audio.isNotEmpty()) L.castSilent else null
            audioCodec != null && CastFormats.audioSupport(audioCodec, ReceiverCaps.forModel(client.device.model)) == com.tmplayer.cast.Support.No -> L.castSilent
            r is CastRoute.Remux && r.audioTrack == null && p.audio.isNotEmpty() -> L.castSilent
            com.tmplayer.cast.CAST_SUBTITLES && p.text.any { it.isText } && subs.isEmpty() -> L.castSubtitlesInside
            else -> null
        }
    }

    /** Keeps [state] in step with the receiver, and moves to the next route if the receiver fails early. */
    private fun watch() {
        watcher?.cancel()
        watcher = scope.launch {
            launch { client.volume.collect { publishState() } }
            launch { client.media.collect { m -> onMedia(m) } }
            while (true) {
                delay(TICK_MS)
                publishState()
            }
        }
    }

    private suspend fun onMedia(m: RemoteMediaStatus) {
        val pub = published ?: return
        if (m.contentId != null && m.contentId != pub.url) return
        publishState()
        if (_phase.value == CastPhase.Failed && !loading) {
            val sinceLoad = (System.nanoTime() - loadedAt) / 1_000_000
            val pl = plan
            if (pl != null && sinceLoad < EARLY_FAILURE_MS && routeIndex + 1 < pl.routes.size) {
                Logger.i(TAG, "Receiver failed on ${route?.let { it::class.simpleName }}, trying the next way")
                routeIndex++
                published = null
                runCatching { startRoute(targetMs) }.onFailure { _state.value = _state.value.copy(error = failureText()) }
            }
            return
        }
        if (pub.hls && !tracksSent && m.tracks.isNotEmpty()) {
            tracksSent = true
            // A soundtrack the TV left out of its list: what it has on instead, so the menu shows that.
            hlsAudio = HlsTrackMatch.settle(hlsAudio, pub.renditions, m.tracks, m.activeTrackIds.map { it.toLong() })
            hlsAudio?.trackNumber?.let { audio = it }
            sendTracks(force = false)
        }
        if (pub.hls) publishTracks()
    }

    private fun sendTracks(force: Boolean) {
        val pub = published ?: return
        val m = client.media.value
        val map = HlsTrackMatch.match(pub.renditions, m.tracks)
        val active = m.activeTrackIds.map { it.toLong() }
        val ids = HlsTrackMatch.activeIds(map, m.tracks, active, hlsAudio, hlsText)
        Logger.i(TAG, "Tracks ${m.tracks} matched $map; active $active -> $ids")
        if (force || ids.toSet() != active.toSet()) client.setActiveTracks(ids)
    }

    private fun publishState() {
        val pub = published
        val m = client.media.value
        val mine = pub != null && (m.contentId == null || m.contentId == pub.url)
        val v = client.volume.value
        val p = probe
        val duration = p?.durationMs?.takeIf { it > 0 } ?: pub?.durationMs ?: 0L
        val media = if (mine) m.toReceiverMedia().let { it.copy(positionMs = it.positionMs + pub.offsetMs) } else null
        val view = if (pub == null && !loading) {
            CastView(CastPhase.Starting, targetMs, wantPlaying)
        } else {
            mapper.map(media, loading, nowMs(), targetMs, duration)
        }
        // A failed or reloading receiver says 0, which must not become the place ([CastPlace]).
        if (!loading && mine) place.heard(view, nowMs())
        _phase.value = view.phase
        val error = when (view.phase) {
            CastPhase.Failed -> _state.value.error ?: failureText()
            // The TV playing again clears an old complaint.
            CastPhase.Playing, CastPhase.Paused, CastPhase.Buffering -> null
            else -> _state.value.error
        }
        _state.value = _state.value.copy(
            opened = pub != null,
            positionMs = place.ms,
            durationMs = duration,
            bufferedMs = place.ms,
            playing = view.showsPause,
            buffering = view.busy,
            seekPending = loading,
            ended = view.phase == CastPhase.Ended,
            error = error,
            volume = (v.level * 100).toInt(),
            muted = v.muted,
            speed = speed,
        )
    }

    private fun publishTracks() {
        val p = probe ?: return
        val pub = published
        if (pub?.hls == true) {
            // Only the soundtracks the TV kept, the one it has on marked.
            val m = client.media.value
            val audioTracks = HlsTrackMatch.audioOffers(pub.renditions, m.tracks, m.activeTrackIds.map { it.toLong() }, hlsAudio).mapNotNull { o ->
                val a = p.audio.firstOrNull { it.number == o.rendition.trackNumber } ?: return@mapNotNull null
                MediaTrack(a.number, TrackType.Audio, a.name, a.language, a.codec, a.channels.takeIf { it > 0 }, o.selected, a.isDefault, false)
            }
            val texts = pub.renditions.filter { it.kind == HlsRendition.Kind.Text }.mapIndexed { i, r ->
                MediaTrack(TEXT_BASE + i, TrackType.Subtitle, r.name, r.language, "webvtt", null, r.uri == hlsText?.uri, false, r.fileId != null)
            }
            _tracks.value = audioTracks + texts
            return
        }
        val audioTracks = p.audio.map { a ->
            MediaTrack(a.number, TrackType.Audio, a.name, a.language, a.codec, a.channels.takeIf { it > 0 }, a.number == audio, a.isDefault, false)
        }
        val subTracks = subs.map { s -> MediaTrack(s.id, TrackType.Subtitle, s.label, s.language, "webvtt", null, s.id == activeSub, false, true) }
        _tracks.value = audioTracks + subTracks
    }

    private fun volumePercent(): Int = (client.volume.value.level * 100).toInt()

    // ---- the controls ------------------------------------------------------------------------

    override fun play() {
        wantPlaying = true
        when (_phase.value) {
            CastPhase.Ended -> {
                seekTo(0)
                client.play()
            }
            CastPhase.Stopped, CastPhase.Failed -> retry()
            else -> {
                mapper.requested(true, nowMs())
                client.play()
            }
        }
        publishState()
    }

    override fun pause() {
        wantPlaying = false
        mapper.requested(false, nowMs())
        client.pause()
        publishState()
    }

    override fun stop() {
        wantPlaying = false
        client.stopMedia()
        _state.value = _state.value.copy(playing = false)
    }

    override fun togglePlay() = if (_state.value.playing && !_state.value.ended) pause() else play()

    /** After the TV refused or was stopped: the same way again from where it was, or the plan from the top. */
    fun retry() {
        _state.value = _state.value.copy(error = null)
        val at = targetMs
        scope.launch {
            val r = route
            val ok = if (r != null && published != null) load(r, at) else runCatching { routeIndex = 0; startRoute(at) }.isSuccess
            if (!ok) _state.value = _state.value.copy(error = failureText())
        }
    }

    override fun seekTo(positionMs: Long) {
        val pub = published ?: return
        val target = positionMs.coerceAtLeast(0).let { t -> _state.value.durationMs.takeIf { it > 0 }?.let { t.coerceAtMost(it - 500) } ?: t }
        place.expect(target, nowMs())
        _state.value = _state.value.copy(positionMs = target, seekPending = true)
        if (pub.seekable) {
            client.seek(target)
        } else {
            // A rewrap starts again from the cue before the target.
            val r = route ?: return
            scope.launch { if (!load(r, target)) _state.value = _state.value.copy(error = failureText()) }
        }
    }

    override fun seekBy(deltaMs: Long) = seekTo(_state.value.positionMs + deltaMs)

    override fun frameStep(forward: Boolean) = Unit

    override fun setSpeed(speed: Float) {
        this.speed = speed
        client.setRate(speed.toDouble())
        publishState()
    }

    override fun setVolume(percent: Int) {
        if (client.volume.value.fixed) return
        client.setVolume(percent.coerceIn(0, 100) / 100.0)
    }

    override fun setMuted(muted: Boolean) = client.setMuted(muted)

    override fun selectTrack(type: TrackType, id: Int?) {
        val pub = published
        when (type) {
            TrackType.Audio -> {
                if (id == null) return
                if (pub?.hls == true) {
                    val m = client.media.value
                    val r = HlsTrackMatch.renditionFor(id, pub.renditions, m.tracks) ?: return
                    if (r == HlsTrackMatch.playing(pub.renditions, m.tracks, m.activeTrackIds.map { it.toLong() })) return
                    audio = id
                    hlsAudio = r
                    publishTracks()
                    if (HlsTrackMatch.match(pub.renditions, client.media.value.tracks)[r] != null) {
                        sendTracks(force = true)
                    } else {
                        // No match in the receiver's list: the playlist again, starting on that soundtrack.
                        val at = targetMs
                        route?.let { rt -> scope.launch { load(rt, at) } }
                    }
                    return
                }
                if (id == audio) return
                val r = route
                if (r is CastRoute.Direct) {
                    // The receiver plays the file's own soundtrack; no other is reachable.
                    _note.value = L.castAudioCannotSwitch
                    return
                }
                audio = id
                publishTracks()
                val at = _state.value.positionMs
                scope.launch { load(CastRoute.Remux(id), at) }
            }
            TrackType.Subtitle -> {
                if (pub?.hls == true) {
                    val texts = pub.renditions.filter { it.kind == HlsRendition.Kind.Text }
                    hlsText = id?.let { texts.getOrNull(it - TEXT_BASE) }
                    activeSub = hlsText?.fileId?.toInt()
                    publishTracks()
                    sendTracks(force = true)
                    return
                }
                activeSub = id
                client.setActiveTracks(listOfNotNull(id?.toLong()))
                publishTracks()
            }
            TrackType.Video -> Unit
        }
    }

    override fun addSubtitle(path: String): Boolean {
        // Casting goes without subtitles for now (CAST_SUBTITLES): the TV's stream is left alone.
        if (!com.tmplayer.cast.CAST_SUBTITLES) return false
        val st = streams ?: return false
        val file = File(path)
        val bytes = runCatching { file.readBytes() }.getOrNull() ?: return false
        val id = SUB_BASE + subs.size
        val url = runCatching { st.subtitle(id, bytes, file.extension.ifBlank { "srt" }, file.nameWithoutExtension) }.getOrNull() ?: return false
        subs += Sub(id, file.nameWithoutExtension, null, url)
        activeSub = id
        publishTracks()
        // Side tracks are declared in the LOAD, and HLS renditions in the playlist, so the video
        // is loaded again where it is.
        val r = route ?: return true
        val at = _state.value.positionMs
        scope.launch { load(r, at) }
        return true
    }

    override fun setScale(scale: VideoScale) = Unit
    override fun setDownmix(stereo: Boolean) = Unit
    override fun setVolumeBoost(on: Boolean) = Unit
    override fun setSubtitleStyle(style: SubtitleStyle) = Unit
    override fun setSubtitleDelay(ms: Long) = Unit
    override fun setAudioDelay(ms: Long) = Unit
    override suspend fun screenshot(file: File, withSubtitles: Boolean): Boolean = false
    override fun setAbLoop(loop: AbLoop?) = Unit

    override fun details(): List<Pair<String, String>> {
        val p = probe
        val out = ArrayList<Pair<String, String>>()
        out += L.castCastingTo(client.device.name) to client.device.model
        p?.video?.let { out += "Video" to "${it.codec} ${it.width}x${it.height}" }
        p?.audio?.firstOrNull { it.number == audio }?.let { out += "Audio" to "${it.codec} ${it.channels}ch" }
        route?.let { out += "Route" to (if (it is CastRoute.Hls) "HLS (${MkvHls.CONTENT_TYPE})" else it.toString()) }
        return out
    }

    /** Where the video is, for the switch back to this computer. */
    val positionMs: Long get() = _state.value.positionMs

    override fun close() {
        watcher?.cancel()
        runCatching { client.stopMedia() }
        scope.cancel()
        runCatching { server?.stop() }
        server = null
        closeData()
    }

    /** Lets go of the stream and the server without telling the receiver anything (it is gone). */
    fun abandon() {
        watcher?.cancel()
        scope.cancel()
        runCatching { server?.stop() }
        server = null
        closeData()
    }

    private fun closeData() {
        runCatching { data?.close() }
        data = null
        source = null
        published = null
    }

    private fun sameLanguage(a: String?, b: String?): Boolean {
        if (a.isNullOrBlank() || b.isNullOrBlank()) return false
        if (a.equals(b, ignoreCase = true)) return true
        return runCatching { Locale.forLanguageTag(a).isO3Language == Locale.forLanguageTag(b).isO3Language }.getOrDefault(false)
    }

    private companion object {
        const val TAG = "CastEngine"
        const val TICK_MS = 250L
        const val EARLY_FAILURE_MS = 20_000L
        const val SUB_BASE = 1000
        const val TEXT_BASE = 2000
    }
}
