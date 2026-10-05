package com.tmplayer.desktop.player

import com.tmplayer.data.ResumeState
import com.tmplayer.desktop.DesktopPaths
import com.tmplayer.platform.Logger
import com.tmplayer.player.SubtitleStyle
import com.tmplayer.player.SyncDelays
import com.tmplayer.player.VideoScale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.openani.mediamp.PlaybackEvent
import org.openani.mediamp.features.Screenshots
import org.openani.mediamp.mpv.MPVHandle
import org.openani.mediamp.mpv.MpvMediampPlayer
import org.openani.mediamp.source.MediaData
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.SwingUtilities
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/** libmpv and its friends, unpacked once into the app's own folder rather than a fresh temp dir per launch. */
object MpvNatives {
    private val prepared = AtomicBoolean(false)

    fun prepare() {
        if (!prepared.compareAndSet(false, true)) return
        runCatching { MpvMediampPlayer.prepareLibraries(DesktopPaths.nativeDir.absolutePath) }
            .onFailure { Logger.w("MpvNatives", "Falling back to mediamp's temp folder", it) }
    }
}

/**
 * [PlaybackEngine] over libmpv through mediamp.
 *
 * Transport calls (play, pause, seek) go to mediamp's state machine on the Swing thread, which is
 * where it wants them; everything mediamp does not model (tracks, volume, speed, picture shape,
 * downmix) is set on mpv's properties directly, which libmpv allows from any thread.
 *
 * mediamp has no buffering signal during a seek stall, so a seek is "pending" from the moment it is
 * issued until mediamp reports it completed, and the position shown meanwhile is the target.
 *
 * @param hwdec mpv's `hwdec`; `auto-safe` by default, `no` for the force software setting.
 */
class MpvPlaybackEngine(hwdec: String = OpenPrefs.HWDEC_AUTO) : PlaybackEngine {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val player: MpvMediampPlayer

    private val handle: MPVHandle get() = player.impl as MPVHandle

    private val _state = MutableStateFlow(PlaybackStatus())
    override val state: StateFlow<PlaybackStatus> = _state.asStateFlow()

    private val _tracks = MutableStateFlow<List<MediaTrack>>(emptyList())
    override val tracks: StateFlow<List<MediaTrack>> = _tracks.asStateFlow()

    @Volatile
    private var seekTarget: Long? = null

    @Volatile
    private var seekIssuedAt = 0L

    @Volatile
    private var tracksDirty = true

    @Volatile
    private var closed = false

    /** Volume boost, as last set; [mpvVolume] scales by it. */
    @Volatile
    private var boost = false

    init {
        MpvNatives.prepare()
        player = MpvMediampPlayer(Unit, SupervisorJob() + Dispatchers.Default, configureOptions = { h ->
            h.option("hwdec", hwdec)
            // A hardware decoder that starts and then fails on a frame drops to software rather
            // than leaving a frozen picture (mpv's default is a count; "yes" is any failure).
            h.option("hwdec-software-fallback", "yes")
            h.option("audio-channels", "auto-safe")
            // Subtitles come with the file and are drawn by libass inside the frame.
            h.option("sub-auto", "no")
            h.option("sub-font-size", SubtitleStyle().size.mpvFontSize.toString())
            h.option("sub-border-size", "2.5")
            h.option("msg-level", "all=warn")
        })
        watch()
    }

    private fun watch() {
        scope.launch {
            player.state.collect { s ->
                _state.update { it.copy(playing = s.playWhenReady, buffering = s.isBuffering || it.seekPending) }
            }
        }
        scope.launch {
            player.currentPositionMillis.collect { ms ->
                if (seekTarget == null) _state.update { it.copy(positionMs = ms) }
            }
        }
        scope.launch {
            player.mediaProperties.collect { p ->
                if (p == null) return@collect
                _state.update {
                    it.copy(
                        durationMs = p.durationMillis ?: it.durationMs,
                        videoWidth = p.videoWidth ?: it.videoWidth,
                        videoHeight = p.videoHeight ?: it.videoHeight,
                    )
                }
            }
        }
        scope.launch {
            player.events.collect { e ->
                when (e) {
                    is PlaybackEvent.SeekCompleted -> landSeek(e.positionMillis)
                    is PlaybackEvent.MediaEnded -> _state.update { it.copy(ended = true, playing = false) }
                    is PlaybackEvent.ErrorOccurred -> _state.update {
                        it.copy(error = e.error.message ?: "Playback failed (${e.error.code})")
                    }
                    else -> Unit
                }
            }
        }
        scope.launch(Dispatchers.IO) {
            var tick = 0
            while (isActive) {
                delay(POLL_MS)
                if (closed || !_state.value.opened) continue
                runCatching { poll(tick++) }
            }
        }
    }

    private fun poll(tick: Int) {
        val h = handle
        val pausedForCache = h.bool("paused-for-cache")
        val seeking = h.bool("seeking")
        val cacheEnd = h.double("demuxer-cache-time")
        val duration = h.double("duration")
        val target = seekTarget
        // The completion event is the normal way out; this is for the seek it never reports.
        if (target != null && System.currentTimeMillis() - seekIssuedAt > SEEK_SETTLE_MS && seeking == false && pausedForCache != true) {
            landSeek(null)
        }
        _state.update {
            it.copy(
                bufferedMs = cacheEnd?.let { s -> (s * 1000).toLong() } ?: it.bufferedMs,
                durationMs = duration?.takeIf { d -> d > 0 }?.let { d -> (d * 1000).toLong() } ?: it.durationMs,
                buffering = pausedForCache == true || it.seekPending || player.state.value.isBuffering,
                volume = h.double("volume")?.let { v -> (v / volumeScale()).roundToInt() } ?: it.volume,
                muted = h.bool("mute") ?: it.muted,
                speed = h.double("speed")?.toFloat() ?: it.speed,
                videoWidth = h.int("dwidth")?.takeIf { w -> w > 0 } ?: it.videoWidth,
                videoHeight = h.int("dheight")?.takeIf { v -> v > 0 } ?: it.videoHeight,
            )
        }
        if (tracksDirty || tick % TRACK_POLL_EVERY == 0) {
            tracksDirty = false
            _tracks.value = readTracks(h)
            val chapters = readChapters(h)
            if (chapters != _state.value.chapters) _state.update { it.copy(chapters = chapters) }
        }
    }

    /** mpv's `chapter-list`, in time order, without a mark mpv could not place. */
    private fun readChapters(h: MPVHandle): List<Chapter> {
        val count = h.int("chapter-list/count") ?: return emptyList()
        return (0 until count).mapNotNull { i ->
            val seconds = h.double("chapter-list/$i/time") ?: return@mapNotNull null
            Chapter((seconds * 1000).roundToLong().coerceAtLeast(0), h.string("chapter-list/$i/title"))
        }.sortedBy { it.startMs }
    }

    private fun landSeek(at: Long?) {
        seekTarget = null
        _state.update {
            it.copy(
                seekPending = false,
                buffering = false,
                positionMs = at ?: player.currentPositionMillis.value,
            )
        }
    }

    private fun readTracks(h: MPVHandle): List<MediaTrack> {
        val count = h.int("track-list/count") ?: return emptyList()
        return (0 until count).mapNotNull { i ->
            val type = when (h.string("track-list/$i/type")) {
                "audio" -> TrackType.Audio
                "sub" -> TrackType.Subtitle
                "video" -> TrackType.Video
                else -> return@mapNotNull null
            }
            MediaTrack(
                id = h.int("track-list/$i/id") ?: return@mapNotNull null,
                type = type,
                title = h.string("track-list/$i/title"),
                language = h.string("track-list/$i/lang"),
                codec = h.string("track-list/$i/codec"),
                channels = if (type == TrackType.Audio) h.int("track-list/$i/demux-channel-count") else null,
                selected = h.bool("track-list/$i/selected") == true,
                isDefault = h.bool("track-list/$i/default") == true,
                external = h.bool("track-list/$i/external") == true,
            )
        }
    }

    override suspend fun open(data: MediaData, startAtMs: Long, prefs: OpenPrefs) {
        seekTarget = null
        _state.value = PlaybackStatus(
            positionMs = startAtMs,
            speed = prefs.speed,
            scale = prefs.scale,
            downmix = prefs.downmix,
            volume = prefs.volume,
            muted = prefs.muted,
            volumeBoost = prefs.volumeBoost,
            subtitleStyle = prefs.subtitleStyle,
            subtitleDelayMs = prefs.delays.subtitleMs,
            audioDelayMs = prefs.delays.audioMs,
            buffering = true,
        )
        _tracks.value = emptyList()
        val h = handle
        runCatching {
            h.setPropertyString("alang", prefs.audioLanguage.orEmpty())
            h.setPropertyString("slang", prefs.subtitleLanguage.orEmpty())
            h.setPropertyString("sid", if (prefs.subtitlesOn == false) "no" else "auto")
            h.setPropertyString("aid", "auto")
            h.setPropertyDouble("speed", prefs.speed.toDouble())
            h.setPropertyBoolean("mute", prefs.muted)
            h.setPropertyString("audio-channels", if (prefs.downmix) "stereo" else "auto-safe")
            h.setPropertyString("hwdec", prefs.hwdec)
        }
        applyVolumeBoost(prefs.volumeBoost, prefs.volume)
        applyScale(prefs.scale)
        applySubtitleStyle(prefs.subtitleStyle)
        // Set before the file loads, so the first line is already in step; mpv keeps both across files.
        runCatching {
            h.setPropertyDouble("sub-delay", prefs.delays.subtitleMs / 1000.0)
            h.setPropertyDouble("audio-delay", prefs.delays.audioMs / 1000.0)
        }
        // mpv keeps a loop across files as well, and the last video's is no use to this one.
        setAbLoop(null)
        try {
            withContext(Dispatchers.IO) {
                player.setMediaData(data, playWhenReady = true, startPositionMillis = startAtMs)
            }
            // A subtitle language asked for by name: mpv's own pick ignores slang for a track that
            // is not flagged default, so pick it here when the file has one.
            if (prefs.subtitlesOn == true) {
                val wanted = readTracks(h).filter { it.type == TrackType.Subtitle }
                val pick = wanted.firstOrNull { it.language == prefs.subtitleLanguage } ?: wanted.firstOrNull()
                if (pick != null && wanted.none { it.selected }) runCatching { h.setPropertyString("sid", pick.id.toString()) }
            }
            prefs.resume?.let { restoreTracks(h, it) }
            // The leveller goes in once there is sound to put it on; see [addLeveller].
            if (prefs.volumeBoost) addLeveller()
            tracksDirty = true
            _state.update { it.copy(opened = true, error = null, buffering = false) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Logger.w(TAG, "Open failed", e)
            _state.update { it.copy(error = e.message ?: "This video could not be opened.", buffering = false) }
        }
    }

    /**
     * The exact tracks a resumed video was playing with, over the series' languages. A place that
     * now holds a track in another language is left to them rather than guessed at.
     */
    private fun restoreTracks(h: MPVHandle, wanted: ResumeState) {
        val all = readTracks(h)
        val audio = all.filter { it.type == TrackType.Audio }
        val subs = all.filter { it.type == TrackType.Subtitle }
        wanted.audioTrack?.let { audio.getOrNull(it) }
            ?.takeIf { ResumeState.sameTrack(wanted.audioLanguage, it.language) }
            ?.let { runCatching { h.setPropertyString("aid", it.id.toString()) } }
        when {
            wanted.subtitlesOff -> runCatching { h.setPropertyString("sid", "no") }
            else -> wanted.subtitleTrack?.let { subs.getOrNull(it) }
                ?.takeIf { ResumeState.sameTrack(wanted.subtitleLanguage, it.language) }
                ?.let { runCatching { h.setPropertyString("sid", it.id.toString()) } }
        }
    }

    override fun play() {
        if (_state.value.ended) seekTo(0)
        _state.update { it.copy(playing = true, ended = false) }
        onEdt { player.play() }
    }

    override fun pause() {
        _state.update { it.copy(playing = false) }
        onEdt { player.pause() }
    }

    override fun stop() {
        // mediamp puts its position back to zero on the way out; the state keeps where it was.
        seekTarget = null
        _state.update { it.copy(playing = false, opened = false, buffering = false, seekPending = false) }
        onEdt { player.stopPlayback() }
    }

    override fun togglePlay() = if (_state.value.playing) pause() else play()

    override fun seekTo(positionMs: Long) {
        if (!_state.value.opened) return
        val target = SeekMath.clampTarget(positionMs, _state.value.durationMs)
        seekTarget = target
        seekIssuedAt = System.currentTimeMillis()
        _state.update { it.copy(positionMs = target, seekPending = true, buffering = true, ended = false) }
        onEdt { player.seekTo(target) }
    }

    override fun seekBy(deltaMs: Long) {
        val base = seekTarget ?: _state.value.positionMs
        seekTo(SeekMath.clampSeek(base, deltaMs, _state.value.durationMs))
    }

    override fun frameStep(forward: Boolean) {
        _state.update { it.copy(playing = false) }
        runCatching { handle.command(if (forward) "frame-step" else "frame-back-step") }
    }

    override fun setSpeed(speed: Float) {
        val s = speed.coerceIn(SeekMath.MIN_SPEED, SeekMath.MAX_SPEED)
        runCatching { handle.setPropertyDouble("speed", s.toDouble()) }
        _state.update { it.copy(speed = s) }
    }

    override fun setVolume(percent: Int) {
        val v = percent.coerceIn(0, 100)
        runCatching { handle.setPropertyDouble("volume", mpvVolume(v)) }
        // Turning the volume up is a way out of mute, as in every other player.
        if (v > 0 && _state.value.muted) setMuted(false)
        _state.update { it.copy(volume = v) }
    }

    override fun setMuted(muted: Boolean) {
        runCatching { handle.setPropertyBoolean("mute", muted) }
        _state.update { it.copy(muted = muted) }
    }

    override fun selectTrack(type: TrackType, id: Int?) {
        val property = when (type) {
            TrackType.Audio -> "aid"
            TrackType.Subtitle -> "sid"
            TrackType.Video -> "vid"
        }
        runCatching { handle.setPropertyString(property, id?.toString() ?: "no") }
        _tracks.update { list ->
            list.map { if (it.type == type) it.copy(selected = it.id == id) else it }
        }
        tracksDirty = true
    }

    override fun setScale(scale: VideoScale) {
        applyScale(scale)
        _state.update { it.copy(scale = scale) }
    }

    /** Fit is mpv's default; Crop fills by panscan; Stretch drops the aspect ratio. */
    private fun applyScale(scale: VideoScale) = runCatching {
        val h = handle
        when (scale) {
            VideoScale.Fit -> {
                h.setPropertyBoolean("keepaspect", true)
                h.setPropertyDouble("panscan", 0.0)
            }
            VideoScale.Crop -> {
                h.setPropertyBoolean("keepaspect", true)
                h.setPropertyDouble("panscan", 1.0)
            }
            VideoScale.Stretch -> {
                h.setPropertyDouble("panscan", 0.0)
                h.setPropertyBoolean("keepaspect", false)
            }
        }
    }

    override fun setDownmix(stereo: Boolean) {
        runCatching { handle.setPropertyString("audio-channels", if (stereo) "stereo" else "auto-safe") }
        _state.update { it.copy(downmix = stereo) }
    }

    override fun setVolumeBoost(on: Boolean) {
        applyVolumeBoost(on, _state.value.volume)
        if (!_state.value.opened) {
            _state.update { it.copy(volumeBoost = on) }
            return
        }
        if (on) addLeveller() else runCatching { handle.command("af", "remove", LEVELLER_LABEL) }
        _state.update { it.copy(volumeBoost = on) }
    }

    private fun volumeScale(): Double = if (boost) BOOSTED_VOLUME_MAX / 100.0 else 1.0

    /** The volume the viewer set, 0 to 100, as mpv's own figure under the boost as it stands. */
    private fun mpvVolume(percent: Int): Double = percent * volumeScale()

    /**
     * The gain half of the boost: mpv's ceiling and the volume itself, in the order that never
     * leaves mpv holding a volume above its ceiling.
     */
    private fun applyVolumeBoost(on: Boolean, volume: Int) {
        val h = handle
        boost = on
        if (on) {
            runCatching { h.setPropertyDouble("volume-max", BOOSTED_VOLUME_MAX.toDouble()) }
            runCatching { h.setPropertyDouble("volume", mpvVolume(volume)) }
        } else {
            runCatching { h.setPropertyDouble("volume", mpvVolume(volume)) }
            runCatching { h.setPropertyDouble("volume-max", 100.0) }
        }
    }

    /**
     * The night mode half: `dynaudnorm`, which lifts quiet dialogue and holds back the loud scenes
     * the way the Android boost's compressor does. The FFmpeg mediamp ships is built with
     * `--disable-everything` and carries no such filter, so this is tried through mpv's `af`
     * command, which puts the chain back as it was when the filter cannot be made, rather than
     * through the property, which leaves a broken chain and no sound at all. Without it the boost
     * is the gain alone.
     */
    private fun addLeveller() {
        val added = runCatching { handle.command("af", "add", "$LEVELLER_LABEL:$LEVELLER") }.getOrDefault(false)
        if (!added) Logger.i(TAG, "No dynaudnorm in this FFmpeg; Volume boost is the gain alone")
    }

    override fun setSubtitleStyle(style: SubtitleStyle) {
        applySubtitleStyle(style)
        _state.update { it.copy(subtitleStyle = style) }
    }

    /**
     * Each property on its own, so one this libmpv does not know leaves the others applied.
     *
     * The bundled libmpv is 0.41, where `background-box` draws one box behind the whole subtitle in
     * `sub-back-color` (mpv's colours are #AARRGGBB). `opaque-box`, the older style, boxes each line
     * in the outline colour instead and draws no outline, which is the fallback for a libmpv that
     * predates `background-box` (0.39). `sub-ass-override` stays at mpv's default, `scale`: these
     * options reach plain text tracks, and an ASS track keeps the look its author gave it.
     */
    private fun applySubtitleStyle(style: SubtitleStyle) {
        val h = handle
        runCatching { h.setPropertyString("sub-font-size", style.size.mpvFontSize.toString()) }
        runCatching { h.setPropertyString("sub-pos", style.position.mpvSubPos.toString()) }
        if (!style.box) {
            runCatching { h.setPropertyString("sub-border-style", "outline-and-shadow") }
            return
        }
        runCatching { h.setPropertyString("sub-back-color", BOX_COLOR) }
        val boxed = runCatching { h.setPropertyString("sub-border-style", "background-box") }.getOrDefault(false)
        if (!boxed) {
            runCatching {
                h.setPropertyString("sub-outline-color", BOX_COLOR)
                h.setPropertyString("sub-border-style", "opaque-box")
            }
        }
    }

    override fun setSubtitleDelay(ms: Long) {
        runCatching { handle.setPropertyDouble("sub-delay", ms / 1000.0) }
        _state.update { it.copy(subtitleDelayMs = ms) }
    }

    override fun setAudioDelay(ms: Long) {
        runCatching { handle.setPropertyDouble("audio-delay", ms / 1000.0) }
        _state.update { it.copy(audioDelayMs = ms) }
    }

    override fun addSubtitle(path: String): Boolean {
        if (!_state.value.opened) return false
        val ok = runCatching { handle.command("sub-add", path, "select") }.getOrDefault(false)
        tracksDirty = true
        return ok
    }

    /**
     * Through mediamp's [Screenshots], which renders the frame off the same render context the
     * picture comes from, at the size it is drawn, and writes the PNG itself. mpv's own
     * `screenshot-to-file` cannot be used: it encodes through libavcodec, and the FFmpeg mediamp
     * ships has no image encoder at all. The subtitles are drawn by that render, so a bare
     * picture hides them for the moment it takes.
     */
    override suspend fun screenshot(file: java.io.File, withSubtitles: Boolean): Boolean {
        if (!_state.value.opened) return false
        val shots = player.features[Screenshots] ?: return false
        file.parentFile?.mkdirs()
        val h = handle
        val hideSubs = !withSubtitles && h.bool("sub-visibility") == true
        if (hideSubs) runCatching { h.setPropertyBoolean("sub-visibility", false) }
        try {
            shots.takeScreenshot(file.absolutePath)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Logger.w(TAG, "Screenshot failed", e)
        } finally {
            if (hideSubs) runCatching { h.setPropertyBoolean("sub-visibility", true) }
        }
        return file.isFile && file.length() > 0
    }

    override fun setAbLoop(loop: AbLoop?) {
        val end = loop?.endMs
        runCatching {
            if (loop == null || end == null) {
                handle.setPropertyString("ab-loop-b", "no")
                handle.setPropertyString("ab-loop-a", "no")
            } else {
                handle.setPropertyDouble("ab-loop-a", loop.startMs / 1000.0)
                handle.setPropertyDouble("ab-loop-b", end / 1000.0)
            }
        }
    }

    override fun details(): List<Pair<String, String>> {
        val h = handle
        return buildList {
            val w = h.int("video-params/w") ?: _state.value.videoWidth
            val ht = h.int("video-params/h") ?: _state.value.videoHeight
            val fps = h.double("container-fps") ?: h.double("estimated-vf-fps")
            if (w > 0 && ht > 0) add("Picture" to ("$w x $ht" + (fps?.let { ", %.3g fps".format(it) } ?: "")))
            h.string("video-codec")?.let { add("Video codec" to it) }
            h.string("video-params/pixelformat")?.let { add("Pixel format" to it) }
            val hw = h.string("hwdec-current")
            add("Hardware decoding" to if (hw.isNullOrBlank() || hw == "no") "Off (software)" else hw)
            val audio = listOfNotNull(
                h.string("audio-codec-name"),
                h.int("audio-params/channel-count")?.let { "$it channels" },
                h.int("audio-params/samplerate")?.let { "$it Hz" },
            )
            if (audio.isNotEmpty()) add("Sound" to audio.joinToString(", "))
            add("Downmix to stereo" to if (_state.value.downmix) "On" else "Off")
            add(
                "Volume boost" to when {
                    !_state.value.volumeBoost -> "Off"
                    h.string("af").orEmpty().contains("dynaudnorm") -> "On, levelled"
                    else -> "On, gain only"
                },
            )
            // Read back from mpv rather than from the state, so the panel shows what is in force.
            h.double("sub-delay")?.let { add("Subtitle delay" to SyncDelays.label((it * 1000).roundToLong())) }
            h.double("audio-delay")?.let { add("Audio delay" to SyncDelays.label((it * 1000).roundToLong())) }
            h.string("file-format")?.let { add("Container" to it) }
            h.double("demuxer-cache-duration")?.let { add("Buffered ahead" to "%.1f s".format(it)) }
            h.string("current-vo")?.let { add("Video output" to it) }
            h.string("current-ao")?.let { add("Audio output" to it) }
            h.string("mpv-version")?.let { add("mpv" to it) }
            h.string("ffmpeg-version")?.let { add("FFmpeg" to it) }
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        scope.cancel()
        runCatching { player.close() }
    }

    private fun onEdt(block: () -> Unit) {
        if (closed) return
        if (SwingUtilities.isEventDispatchThread()) block() else SwingUtilities.invokeLater { if (!closed) block() }
    }

    private companion object {
        const val TAG = "MpvPlaybackEngine"
        const val POLL_MS = 250L
        const val TRACK_POLL_EVERY = 8
        const val SEEK_SETTLE_MS = 600L

        /** The subtitle box: black at 70 %, dark enough to read on snow, light enough to see through. */
        const val BOX_COLOR = "#B3000000"

        /** Volume boost's leveller, through libavfilter at FFmpeg's own settings, and its label. */
        const val LEVELLER = "lavfi=[dynaudnorm]"
        const val LEVELLER_LABEL = "@boost"
    }
}

private fun MPVHandle.string(name: String): String? = runCatching { getPropertyString(name) }.getOrNull()?.takeIf { it.isNotEmpty() }
private fun MPVHandle.int(name: String): Int? = runCatching { getPropertyInt(name) }.getOrNull()
private fun MPVHandle.double(name: String): Double? = runCatching { getPropertyDouble(name) }.getOrNull()
private fun MPVHandle.bool(name: String): Boolean? = runCatching { getPropertyBoolean(name) }.getOrNull()
