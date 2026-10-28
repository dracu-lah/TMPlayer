package com.tmplayer.desktop.player

import com.tmplayer.data.ResumeState
import com.tmplayer.desktop.DesktopPaths
import com.tmplayer.i18n.L
import com.tmplayer.platform.Logger
import com.tmplayer.player.Stereo3d
import com.tmplayer.player.SubtitlePick
import com.tmplayer.player.SubtitlePlan
import com.tmplayer.player.SubtitleStyle
import com.tmplayer.player.SubtitleTrackInfo
import com.tmplayer.player.SyncDelays
import com.tmplayer.player.TrackLanguages
import com.tmplayer.player.TrackRules
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
import kotlinx.coroutines.sync.withLock
import androidx.compose.ui.graphics.toComposeImageBitmap
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

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + com.tmplayer.platform.JobFailures.handler("MpvPlaybackEngine"))

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

    /** Counts the seeks, so a keyframe seek's follower lets a newer seek land itself. */
    private val seekGeneration = java.util.concurrent.atomic.AtomicInteger()

    @Volatile
    private var tracksDirty = true

    @Volatile
    private var closed = false

    /** 3D video as last set, and what mpv was last given for it: the 3D and the frame size. */
    @Volatile
    private var stereo = Stereo3d.OFF

    @Volatile
    private var stereoApplied: Triple<Stereo3d, Int, Int>? = null

    /**
     * The shader file mpv was last given. mpv reads a shader file once per path and keeps it for
     * the player's life, so a file written again under the same name is never read again: each
     * change gets a file of its own, and the one before is deleted.
     */
    private var stereoShaderFile: java.io.File? = null

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
            // A read ahead worth the name. mpv's cache is "auto", which means on for a network
            // stream only, and a Telegram stream reaches it as a plain seekable input, so it read
            // about a second ahead and every dip in the line showed. Thirty seconds, in memory
            // sized by the machine's, with a little kept behind for a jump back, and a stall
            // waits only a second of cache before it plays on.
            h.option("cache", "yes")
            h.option("demuxer-readahead-secs", "30")
            h.option("demuxer-max-bytes", "${demuxerMaxMiB()}MiB")
            h.option("demuxer-max-back-bytes", "${BACK_CACHE_MIB}MiB")
            h.option("cache-pause-wait", "1")
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
            // mediamp says the position on every video frame, and each copy into the state used
            // to recompose the whole player screen at the frame rate. About four a second reach
            // it now, which is all the clock and the bar can show; [poll] brings the last one in
            // once the picture stops, so a paused position is exact.
            var lastAt = 0L
            player.currentPositionMillis.collect { ms ->
                if (seekTarget != null) return@collect
                val now = System.currentTimeMillis()
                if (now - lastAt < POSITION_EVERY_MS) return@collect
                lastAt = now
                _state.update { it.copy(positionMs = ms) }
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
                    is PlaybackEvent.ErrorOccurred -> {
                        Logger.w(TAG, "Playback failed: ${e.error.code} ${e.error.message}")
                        _state.update { it.copy(error = MpvErrors.describe(e.error.message, e.error.code.toString())) }
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
        // The position the throttle in [watch] may have left behind, once the picture has stopped.
        val stillAt = if (seekTarget == null && !player.state.value.playWhenReady && !_state.value.ended) {
            player.currentPositionMillis.value.takeIf { it > 0 }
        } else {
            null
        }
        _state.update {
            it.copy(
                positionMs = stillAt ?: it.positionMs,
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
        // A video opened with 3D on, or the next file at another size, gets its shader here.
        if (stereo.on || stereoApplied != null) applyStereo()
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
                forced = h.bool("track-list/$i/forced") == true,
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
            nightMode = prefs.nightMode,
            subtitleStyle = prefs.subtitleStyle,
            subtitleDelayMs = prefs.delays.subtitleMs,
            audioDelayMs = prefs.delays.audioMs,
            buffering = true,
            stereo = stereo,
        )
        _tracks.value = emptyList()
        val h = handle
        runCatching {
            // mpv compares these as written, so every spelling a file might use: `ja,jpn`.
            h.setPropertyString("alang", TrackLanguages.spellings(prefs.audioLanguage).joinToString(","))
            val subtitles = prefs.subtitles
            h.setPropertyString(
                "slang",
                TrackLanguages.spellings((subtitles as? SubtitlePlan.On)?.language).joinToString(","),
            )
            // Off and "only when the audio is foreign" start with none; [pickSubtitles] puts a
            // forced track or the foreign audio's subtitles on once the tracks are listed.
            val startOff = subtitles is SubtitlePlan.Off || subtitles is SubtitlePlan.IfForeignAudio
            h.setPropertyString("sid", if (startOff) "no" else "auto")
            h.setPropertyString("aid", "auto")
            h.setPropertyDouble("speed", prefs.speed.toDouble())
            h.setPropertyBoolean("mute", prefs.muted)
            h.setPropertyString("audio-channels", if (prefs.downmix) "stereo" else "auto-safe")
            h.setPropertyString("hwdec", prefs.hwdec)
        }
        applyVolumeBoost(prefs.volumeBoost, prefs.volume)
        // Before the file loads, so the decoder and the downmix start with it.
        applyNightOptions(prefs.nightMode)
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
            pickSubtitles(h, prefs.subtitles)
            prefs.resume?.let { restoreTracks(h, it) }
            // The leveller goes in once there is sound to put it on; see [addLeveller].
            if (prefs.volumeBoost) addLeveller()
            if (prefs.nightMode) addCompressor()
            tracksDirty = true
            _state.update { it.copy(opened = true, error = null, buffering = false) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Logger.w(TAG, "Open failed", e)
            val error = if (MpvErrors.codeIn(e.message) != null) MpvErrors.describe(e.message, null) else e.message ?: L.playerOpenFailed
            _state.update { it.copy(error = error, buffering = false) }
        }
    }

    /**
     * The subtitles [plan] wants, once the file's tracks and the soundtrack mpv chose are known.
     * mpv's own pick ignores `slang` for a track that is not flagged default, and cannot know
     * whether the audio is foreign, so both are settled here ([TrackRules.pickSubtitle]). A
     * language the series remembers but this file lacks still gets its first track, as before.
     */
    private fun pickSubtitles(h: MPVHandle, plan: SubtitlePlan) {
        val all = readTracks(h)
        val subs = all.filter { it.type == TrackType.Subtitle }
        val audio = all.firstOrNull { it.type == TrackType.Audio && it.selected }?.language
        val described = subs.map { SubtitleTrackInfo(it.language, isDefault = it.isDefault, forced = it.forced) }
        val pick = when (val picked = TrackRules.pickSubtitle(plan, audio, described)) {
            SubtitlePick.Leave -> if (plan is SubtitlePlan.On && subs.none { it.selected } && subs.isNotEmpty()) {
                SubtitlePick.Track(0)
            } else {
                picked
            }
            else -> picked
        }
        when (pick) {
            SubtitlePick.Leave -> Unit
            SubtitlePick.None -> if (subs.any { it.selected }) runCatching { h.setPropertyString("sid", "no") }
            is SubtitlePick.Track -> subs[pick.index].takeIf { !it.selected }
                ?.let { runCatching { h.setPropertyString("sid", it.id.toString()) } }
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
        if (_state.value.ended) seekToExact(0)
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

    /**
     * To the key frame at or before [positionMs], as Android's CLOSEST_SYNC: mpv shows it as soon
     * as it is decoded, where mediamp's own seek (`absolute+exact`) decodes on from it to the very
     * frame first, up to a few seconds of picture for a file with sparse key frames.
     */
    override fun seekTo(positionMs: Long) = seek(positionMs, exact = false, relative = false)

    /**
     * By [deltaMs] from where it stands, to a key frame on the side it moves to: mpv's relative
     * seek looks forward for a forward skip, where an absolute one to the key frame before the
     * target could land behind where the skip started, over and over on a long key frame gap.
     */
    override fun seekBy(deltaMs: Long) {
        val base = seekTarget ?: _state.value.positionMs
        seek(SeekMath.clampSeek(base, deltaMs, _state.value.durationMs), exact = false, relative = true)
    }

    /** Through mediamp's seek, exact: Undo, a retry in place, Watch again from the end. */
    override fun seekToExact(positionMs: Long) = seek(positionMs, exact = true, relative = false)

    private fun seek(positionMs: Long, exact: Boolean, relative: Boolean) {
        if (!_state.value.opened) return
        val ended = _state.value.ended
        val base = seekTarget ?: _state.value.positionMs
        val target = SeekMath.clampTarget(positionMs, _state.value.durationMs)
        seekTarget = target
        seekIssuedAt = System.currentTimeMillis()
        val generation = seekGeneration.incrementAndGet()
        _state.update { it.copy(positionMs = target, seekPending = true, buffering = true, ended = false) }
        // After the end mediamp's own seek, which also takes it out of its ended state.
        if (exact || ended) {
            onEdt { player.seekTo(target) }
            return
        }
        onEdt {
            val sent = runCatching {
                if (relative) {
                    handle.command("seek", seconds(target - base), "relative+keyframes")
                } else {
                    handle.command("seek", seconds(target), "absolute+keyframes")
                }
            }.getOrDefault(false)
            if (sent) followKeyframeSeek(generation) else player.seekTo(target)
        }
    }

    /**
     * Lands a seek sent to mpv directly, which mediamp neither issued nor reports: once mpv says it
     * is no longer seeking, at the key frame it reached. A newer seek takes over; the poll's own
     * fallback ([poll]) still stands behind this.
     */
    private fun followKeyframeSeek(generation: Int) {
        scope.launch(Dispatchers.IO) {
            val started = System.currentTimeMillis()
            var sawSeeking = false
            while (System.currentTimeMillis() - started < KEYFRAME_SEEK_FOLLOW_MS) {
                delay(KEYFRAME_SEEK_POLL_MS)
                if (closed || generation != seekGeneration.get() || seekTarget == null) return@launch
                val h = handle
                val seeking = h.bool("seeking")
                if (seeking == true) sawSeeking = true
                // mpv may not have started on it at the first look: give it a moment before an
                // idle "not seeking" counts as done.
                if (seeking == false && (sawSeeking || System.currentTimeMillis() - started >= KEYFRAME_SEEK_START_MS)) {
                    val at = h.double("time-pos")?.takeIf { it >= 0 }?.let { (it * 1000).roundToLong() }
                    if (generation == seekGeneration.get()) landSeek(at)
                    return@launch
                }
            }
        }
    }

    private fun seconds(ms: Long): String = String.format(java.util.Locale.ROOT, "%.3f", ms / 1000.0)

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
            // HX16: the fixed shapes fit until their aspect override is wired in.
            else -> {
                h.setPropertyBoolean("keepaspect", true)
                h.setPropertyDouble("panscan", 0.0)
            }
        }
    }

    override fun setStereo3d(stereo: Stereo3d) {
        this.stereo = stereo
        _state.update { it.copy(stereo = stereo) }
        if (_state.value.opened) runCatching { applyStereo() }
    }

    /**
     * mpv's user shader for 3D ([Stereo3d.mpvShader]), hooked before scaling, so hardware decoding
     * stays on and the subtitles, drawn after it, stay sharp. The shader keeps the frame's size;
     * the shape of one eye comes from `video-aspect-override`. Waits for the frame's size, which
     * the shader is written for, and does nothing while what mpv has is already right.
     */
    @Synchronized
    private fun applyStereo() {
        val h = handle
        val wanted = stereo
        if (!wanted.on) {
            if (stereoApplied == null) return
            runCatching { h.setPropertyString("glsl-shaders", "") }
            runCatching { stereoShaderFile?.delete() }
            stereoShaderFile = null
            runCatching { h.setPropertyString("video-aspect-override", "no") }
                .onFailure { runCatching { h.setPropertyString("video-aspect-override", "-1") } }
            stereoApplied = null
            return
        }
        val width = h.int("width")?.takeIf { it > 0 } ?: return
        val height = h.int("height")?.takeIf { it > 0 } ?: return
        val next = Triple(wanted, width, height)
        if (stereoApplied == next) return
        runCatching {
            // A new name every time: mpv would keep drawing the first shader it read at an old
            // one, so a change of layout (side by side to top and bottom), glasses or eyes after
            // the first did nothing but change the shape.
            val file = java.io.File.createTempFile("tmplayer-3d-", ".glsl").apply { deleteOnExit() }
            file.writeText(Stereo3d.mpvShader(wanted, width, height))
            h.setPropertyString("glsl-shaders", file.absolutePath)
            stereoShaderFile?.takeIf { it != file }?.delete()
            stereoShaderFile = file
            Stereo3d.aspect(width, height, wanted)?.let { aspect ->
                h.setPropertyString("video-aspect-override", String.format(java.util.Locale.ROOT, "%.6f", aspect))
            }
            stereoApplied = next
        }.onFailure { Logger.w(TAG, "3D shader not applied", it) }
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

    /**
     * Night mode, as far as the bundled FFmpeg allows. mediamp builds it with `--disable-everything`
     * and only a handful of filters (aresample, anull and the like), so there is no `acompressor`
     * or `dynaudnorm` to squeeze the range the way the Android processor does. What is done:
     *
     * - A compressor is still asked for through `af`, which fails cleanly on this build and works
     *   the day the FFmpeg carries one ([addCompressor]).
     * - Dolby Digital and Dolby Digital Plus tracks carry their own dynamic range data, which mpv
     *   ignores by default (`ad-lavc-ac3drc` 0): turned to full, the track's own night mode.
     * - A surround track folded to two speakers keeps its centre channel, where the dialogue is,
     *   3 dB up, and the surrounds and the low effects channel down (swresample's mix levels).
     *
     * The decoder reads its option when it starts, so the track is selected again to take it,
     * which costs a moment of silence on the switch and none on an open.
     */
    override fun setNightMode(on: Boolean) {
        applyNightOptions(on)
        _state.update { it.copy(nightMode = on) }
        if (!_state.value.opened) return
        if (on) addCompressor() else runCatching { handle.command("af", "remove", NIGHT_LABEL) }
        val h = handle
        val aid = runCatching { h.getPropertyString("aid") }.getOrNull()
        if (!aid.isNullOrBlank() && aid != "no") {
            runCatching {
                h.setPropertyString("aid", "no")
                h.setPropertyString("aid", aid)
            }
        }
    }

    private fun applyNightOptions(on: Boolean) {
        val h = handle
        runCatching { h.setPropertyString("ad-lavc-ac3drc", if (on) NIGHT_AC3_DRC else "0") }
        runCatching { h.setPropertyString("audio-swresample-o", if (on) NIGHT_DOWNMIX else DIALOGUE_DOWNMIX) }
    }

    private fun addCompressor() {
        val added = runCatching { handle.command("af", "add", "$NIGHT_LABEL:$NIGHT_COMPRESSOR") }.getOrDefault(false)
        if (!added) Logger.i(TAG, "No acompressor in this FFmpeg; Night mode is Dolby range control and the dialogue lift")
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
     * Each property on its own ([SubtitleStyle.mpvProperties]), so one this libmpv does not know
     * leaves the others applied.
     *
     * The bundled libmpv is 0.41, where `background-box` draws one box behind the whole subtitle in
     * `sub-back-color` (mpv's colours are #AARRGGBB). `opaque-box`, the older style, boxes each line
     * in the outline colour instead and draws no outline, which is the fallback for a libmpv that
     * predates `background-box` (0.39). `sub-ass-override` stays at mpv's default, `scale`: these
     * options reach plain text tracks, and an ASS track keeps the look its author gave it. PGS and
     * VobSub tracks are pictures and libass leaves them as they are.
     */
    private fun applySubtitleStyle(style: SubtitleStyle) {
        val h = handle
        // Lines inside the picture, never down in a wide video's black bars: where the phone and
        // the television put them, so a letterboxed video has its subtitles at the same height
        // on every device (PlayerActivity.subtitleBottomFraction).
        runCatching { h.setPropertyString("sub-use-margins", "no") }
        for ((name, value) in style.mpvProperties(subtitleFont(style.face))) {
            val set = runCatching { h.setPropertyString(name, value) }.getOrDefault(false)
            if (!set && name == "sub-border-style" && value == "background-box") {
                runCatching {
                    h.setPropertyString("sub-outline-color", SubtitleStyle.mpvColour(style.boxLevel.argb))
                    h.setPropertyString("sub-border-style", "opaque-box")
                }
            }
        }
    }

    /**
     * The family libass asks the system's font provider for. Fontconfig (Linux) knows the generic
     * names; Windows' DirectWrite and macOS' CoreText want a real family, so each gets one it
     * ships with. A missing family falls back to libass's default, never to no text.
     */
    private fun subtitleFont(face: com.tmplayer.player.SubtitleFace): String {
        val os = System.getProperty("os.name").orEmpty().lowercase()
        return when (face) {
            com.tmplayer.player.SubtitleFace.Default -> "sans-serif"
            com.tmplayer.player.SubtitleFace.Serif -> when {
                "win" in os -> "Georgia"
                "mac" in os -> "Times New Roman"
                else -> "serif"
            }
            com.tmplayer.player.SubtitleFace.Monospace -> when {
                "win" in os -> "Consolas"
                "mac" in os -> "Menlo"
                else -> "monospace"
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
            if (w > 0 && ht > 0) add(L.playerDetailsLabelPicture to (fps?.let { L.playerDetailsPictureValue(w.toString(), ht.toString(), "%.3g".format(L.messages.locale, it)) } ?: "$w x $ht"))
            h.string("video-codec")?.let { add(L.playerDetailsLabelVideoCodec to it) }
            h.string("video-params/pixelformat")?.let { add(L.playerDetailsPixelFormat to it) }
            val hw = h.string("hwdec-current")
            add(L.playerDetailsHwdec to if (hw.isNullOrBlank() || hw == "no") L.playerDetailsSoftware else hw)
            val audio = listOfNotNull(
                h.string("audio-codec-name"),
                h.int("audio-params/channel-count")?.let { L.playerDetailsChannels(it) },
                h.int("audio-params/samplerate")?.let { L.playerDetailsHz(it.toString()) },
            )
            if (audio.isNotEmpty()) add(L.playerDetailsLabelSound to audio.joinToString(", "))
            add(L.playerDownmix to if (_state.value.downmix) L.commonOn else L.commonOff)
            add(
                L.playerVolumeBoost to when {
                    !_state.value.volumeBoost -> L.commonOff
                    h.string("af").orEmpty().contains("dynaudnorm") -> L.playerDetailsBoostLevelled
                    else -> L.playerDetailsBoostGain
                },
            )
            add(
                L.playerDetailsNightMode to when {
                    !_state.value.nightMode -> L.commonOff
                    h.string("af").orEmpty().contains("acompressor") -> L.playerDetailsNightCompressed
                    else -> L.playerDetailsNightDolby
                },
            )
            // Read back from mpv rather than from the state, so the panel shows what is in force.
            h.double("sub-delay")?.let { add(L.playerDetailsSubtitleDelay to SyncDelays.label((it * 1000).roundToLong())) }
            h.double("audio-delay")?.let { add(L.playerDetailsAudioDelay to SyncDelays.label((it * 1000).roundToLong())) }
            h.string("file-format")?.let { add(L.playerDetailsContainer to it) }
            h.double("demuxer-cache-duration")?.let { add(L.playerDetailsBuffered to L.formatSecondsShort(L.messages.formatter.decimal(it, 1))) }
            h.string("current-vo")?.let { add(L.playerDetailsVideoOutput to it) }
            h.string("current-ao")?.let { add(L.playerDetailsAudioOutput to it) }
            h.string("mpv-version")?.let { add("mpv" to it) }
            h.string("ffmpeg-version")?.let { add("FFmpeg" to it) }
        }
    }

    /**
     * mediamp's frame preview: a second mpv on the same media, seeking to keyframes for the
     * timebar's hover. Its reads go through [PreviewReads], so a stream's preview decodes only what
     * is on the disk and never moves the playing video's download.
     */
    private val previewLock = kotlinx.coroutines.sync.Mutex()

    override suspend fun previewFrame(positionMs: Long, width: Int, height: Int): androidx.compose.ui.graphics.ImageBitmap? {
        if (closed || !_state.value.opened) return null
        val preview = runCatching { player.features[org.openani.mediamp.features.FramePreview] }.getOrNull() ?: return null
        val frame = previewLock.withLock {
            PreviewReads.active.incrementAndGet()
            try {
                try {
                    preview.getPreviewFrame(positionMs, width, height)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    // Mostly a part of a stream not on the disk yet; the hover shows its fallback.
                    Logger.w(TAG, "Preview frame at $positionMs ms not had: ${e.message}")
                    null
                }
            } finally {
                PreviewReads.active.decrementAndGet()
            }
        } ?: return null
        if (frame.width <= 0 || frame.height <= 0 || frame.pixels.size < frame.width * frame.height) return null
        val image = java.awt.image.BufferedImage(frame.width, frame.height, java.awt.image.BufferedImage.TYPE_INT_RGB)
        image.setRGB(0, 0, frame.width, frame.height, frame.pixels, 0, frame.width)
        return image.toComposeImageBitmap()
    }

    override fun close() {
        if (closed) return
        closed = true
        scope.cancel()
        runCatching { player.close() }
        runCatching { stereoShaderFile?.delete() }
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

        /** How often, how long, and after how long an idle mpv counts, for a keyframe seek. */
        const val KEYFRAME_SEEK_POLL_MS = 30L
        const val KEYFRAME_SEEK_FOLLOW_MS = 15_000L
        const val KEYFRAME_SEEK_START_MS = 120L

        /** What the demuxer keeps behind the position, for a jump back of a few seconds. */
        const val BACK_CACHE_MIB = 48

        /** How often the position reaches the state while playing: about four times a second. */
        const val POSITION_EVERY_MS = 250L

        /**
         * The demuxer's read ahead, by the machine's memory: 128 MiB under 8 GB, 256 MiB under
         * 16 GB, 512 MiB above, enough for the thirty seconds of a high rate remux. It is mpv's
         * own memory, outside the JVM's heap. The machine's memory is taken as four times the
         * heap the JVM may grow to, its default share of it with no -Xmx set (none is): the
         * runtime image leaves java.management out, which would say it directly.
         */
        fun demuxerMaxMiB(): Int {
            val gib = Runtime.getRuntime().maxMemory() * 4 / (1024L * 1024 * 1024)
            return when {
                gib >= 15 -> 512
                gib >= 7 -> 256
                else -> 128
            }
        }

        /** The subtitle box: black at 70 %, dark enough to read on snow, light enough to see through. */
        const val BOX_COLOR = "#B3000000"

        /** Volume boost's leveller, through libavfilter at FFmpeg's own settings, and its label. */
        const val LEVELLER = "lavfi=[dynaudnorm]"
        const val LEVELLER_LABEL = "@boost"

        /** The Android processor's Night mode figures: -15 dBFS, 6 to 1, 6 dB of make up. */
        const val NIGHT_COMPRESSOR = "lavfi=[acompressor=threshold=0.18:ratio=6:attack=5:release=250:makeup=2]"
        const val NIGHT_LABEL = "@night"

        /** A Dolby track's own range control at full. */
        const val NIGHT_AC3_DRC = "1"

        /**
         * The fold to stereo when Night mode is off: the centre, where a film keeps its dialogue,
         * at full rather than swresample's 3 dB down, the surrounds 3 dB down and the low effects
         * channel well down, as the phone's fold (AudioDownmix.dialogueCoefficients) has them.
         * Folded the textbook way the voices sank under the music and the effects.
         */
        const val DIALOGUE_DOWNMIX = "clev=1.0,slev=0.7071,lfe_mix_level=0.35"

        /** The centre 3 dB up, the surrounds 6 dB down and no low effects, when folding to stereo. */
        const val NIGHT_DOWNMIX = "clev=1.414,slev=0.5,lfe_mix_level=0"
    }
}

private fun MPVHandle.string(name: String): String? = runCatching { getPropertyString(name) }.getOrNull()?.takeIf { it.isNotEmpty() }
private fun MPVHandle.int(name: String): Int? = runCatching { getPropertyInt(name) }.getOrNull()
private fun MPVHandle.double(name: String): Double? = runCatching { getPropertyDouble(name) }.getOrNull()
private fun MPVHandle.bool(name: String): Boolean? = runCatching { getPropertyBoolean(name) }.getOrNull()
