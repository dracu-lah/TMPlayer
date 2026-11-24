package com.tmplayer.ios

import com.tmplayer.desktop.player.AbLoop
import com.tmplayer.desktop.player.MediaTrack
import com.tmplayer.desktop.player.OpenPrefs
import com.tmplayer.desktop.player.PlaybackEngine
import com.tmplayer.desktop.player.PlaybackStatus
import com.tmplayer.desktop.player.TrackType
import com.tmplayer.data.ResumeState
import com.tmplayer.i18n.L
import com.tmplayer.ios.media3.SubtitleText
import com.tmplayer.player.Chapter
import com.tmplayer.player.Stereo3d
import com.tmplayer.player.SubtitlePick
import com.tmplayer.player.SubtitlePlan
import com.tmplayer.player.SubtitleStyle
import com.tmplayer.player.SubtitleTrackInfo
import com.tmplayer.player.SyncDelays
import com.tmplayer.player.TrackLanguages
import com.tmplayer.player.TrackRules
import com.tmplayer.player.VideoScale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.suspendCancellableCoroutine
import org.openani.mediamp.source.MediaData
import kotlin.coroutines.resume

/**
 * Upstream's PlaybackEngine, the interface TMPlayer's player speaks, over any [VideoEngine].
 *
 * The player's own decisions live here, once for every engine, as the desktop's MpvPlaybackEngine
 * makes them: [PlaybackStatus.playing] is what the viewer asked for, play after the end starts
 * again from the beginning, raising the volume takes it out of mute, a seek stays where it was
 * sent until the engine says it landed, and the tracks a video opens with follow the viewer's
 * languages (TrackRules) and, for a resumed video, the ones it was playing with.
 */
internal class NativePlaybackEngine(private val engine: VideoEngine) : PlaybackEngine, SubtitleText, com.tmplayer.ios.media3.ReadAheadTaker {
    /** Android's buffering policy for the files this player opens, once its builder handed it over. */
    private var readAhead: com.tmplayer.ios.media3.ReadAhead? = null

    override fun readAhead(plan: com.tmplayer.ios.media3.ReadAhead) {
        readAhead = plan
    }

    private val _state = MutableStateFlow(PlaybackStatus())
    private val _tracks = MutableStateFlow<List<MediaTrack>>(emptyList())
    override val state: StateFlow<PlaybackStatus> = _state.asStateFlow()
    override val tracks: StateFlow<List<MediaTrack>> = _tracks.asStateFlow()

    /** The reports of the file now open; an older one's are dropped. */
    private val current = MutableStateFlow<EngineReports?>(null)

    private val _subtitleText = MutableStateFlow<String?>(null)
    override val subtitleText: StateFlow<String?> = _subtitleText.asStateFlow()

    /** The tracks as the engine last reported them, before the player's own picks show; for the conformance test. */
    internal val reportedTracks = MutableStateFlow<List<EngineTrack>>(emptyList())

    /** Where the seek in flight was sent, until the engine says it landed. */
    private val seekTarget = MutableStateFlow<Long?>(null)

    /** The engine's AirPlay and picture in picture, handed to the Android player's rows for the video now open. */
    private val external = (engine as? ExternalPlaybackEngine)?.let { ExternalControls(it) }

    private class ExternalControls(private val engine: ExternalPlaybackEngine) : android.app.IosExternalPlayback.Controls {
        override val usesApplePlayer: Boolean get() = engine.usesApplePlayer
        override fun showAirPlayPicker() = engine.showAirPlayPicker()
        override fun startPictureInPicture(): Boolean = engine.startPictureInPicture()
        override fun stopPictureInPicture() = engine.stopPictureInPicture()
        override fun setAutoPictureInPicture(on: Boolean) = engine.setAutoPictureInPicture(on)
        override fun setExternalPlaybackAllowed(on: Boolean) = engine.setExternalPlaybackAllowed(on)
    }

    override suspend fun open(data: MediaData, startAtMs: Long, prefs: OpenPrefs) {
        external?.let { controls ->
            android.app.IosExternalPlayback.controls = controls
            (engine as? ExternalPlaybackEngine)?.onPictureInPictureChanged = { android.app.IosExternalPlayback.pictureInPictureChanged(it) }
        }
        val reports = EngineReports(this)
        current.value = reports
        seekTarget.value = null
        _tracks.value = emptyList()
        _subtitleText.value = null
        _state.value = PlaybackStatus(
            positionMs = startAtMs.coerceAtLeast(0),
            playing = true,
            buffering = true,
            volume = prefs.volume,
            muted = prefs.muted,
            speed = prefs.speed,
            scale = prefs.scale,
            downmix = prefs.downmix,
            volumeBoost = prefs.volumeBoost,
            nightMode = prefs.nightMode,
            subtitleStyle = prefs.subtitleStyle,
            subtitleDelayMs = prefs.delays.subtitleMs,
            audioDelayMs = prefs.delays.audioMs,
            // 3D stays as chosen across the files this player opens.
            stereo = _state.value.stereo,
        )
        val subtitles = prefs.subtitles
        engine.open(
            data.uri,
            startAtMs.coerceAtLeast(0),
            EngineOpenOptions(
                speed = prefs.speed,
                volume = prefs.volume,
                muted = prefs.muted,
                volumeBoost = prefs.volumeBoost,
                downmix = prefs.downmix,
                softwareDecoding = prefs.hwdec == OpenPrefs.HWDEC_SOFTWARE,
                subtitleDelayMs = prefs.delays.subtitleMs,
                audioDelayMs = prefs.delays.audioMs,
                audioLanguages = (listOfNotNull(prefs.audioLanguage) + prefs.audioMore).flatMap(TrackLanguages::spellings).distinct(),
                subtitleLanguages = (subtitles as? SubtitlePlan.On)?.languages.orEmpty().flatMap(TrackLanguages::spellings).distinct(),
                // Off and "only when the audio is foreign" start with none; the pick below may turn some on.
                subtitlesOff = subtitles is SubtitlePlan.Off || subtitles is SubtitlePlan.IfForeignAudio,
                readAheadSeconds = readAhead?.maxBufferMs?.div(1000) ?: 0,
                readAgainBelowSeconds = readAhead?.minBufferMs?.div(1000) ?: 0,
                aheadBytes = readAhead?.aheadBytes ?: 0,
                behindBytes = readAhead?.behindBytes ?: 0,
                rebufferSeconds = readAhead?.bufferForPlaybackAfterRebufferMs?.div(1000.0) ?: 0.0,
            ),
            reports,
        )
        val outcome = state.first { it.opened || it.error != null || current.value !== reports }
        if (!outcome.opened || current.value !== reports) return
        engine.setScale(prefs.scale)
        engine.setSubtitleStyle(prefs.subtitleStyle)
        if (prefs.nightMode) engine.setNightMode(true)
        if (_state.value.stereo.on) engine.setStereo3d(_state.value.stereo)
        pickAudio(listOfNotNull(prefs.audioLanguage) + prefs.audioMore)
        pickSubtitles(subtitles)
        prefs.resume?.let(::restoreTracks)
    }

    override fun play() {
        if (_state.value.ended) seekToExact(0)
        _state.update { it.copy(playing = true, ended = false) }
        engine.play()
    }

    override fun pause() {
        _state.update { it.copy(playing = false) }
        engine.pause()
    }

    override fun stop() {
        seekTarget.value = null
        _state.update { it.copy(playing = false, opened = false, buffering = false, seekPending = false) }
        engine.stop()
    }

    override fun togglePlay() = if (_state.value.playing) pause() else play()

    override fun seekTo(positionMs: Long) = seek(positionMs, exact = false, relative = false)

    override fun seekBy(deltaMs: Long) {
        val base = seekTarget.value ?: _state.value.positionMs
        seek(base + deltaMs, exact = false, relative = true)
    }

    override fun seekToExact(positionMs: Long) = seek(positionMs, exact = true, relative = false)

    private fun seek(positionMs: Long, exact: Boolean, relative: Boolean) {
        val now = _state.value
        if (!now.opened) return
        val base = seekTarget.value ?: now.positionMs
        val target = clampTarget(positionMs, now.durationMs)
        seekTarget.value = target
        _state.update { it.copy(positionMs = target, seekPending = true, buffering = true, ended = false) }
        when {
            // After the end, and wherever the place must not move, exactly.
            exact || now.ended -> engine.seekTo(target, exact = true)
            relative -> engine.seekBy(target - base)
            else -> engine.seekTo(target, exact = false)
        }
    }

    override fun frameStep(forward: Boolean) {
        _state.update { it.copy(playing = false) }
        engine.frameStep(forward)
    }

    override fun setSpeed(speed: Float) {
        val s = speed.coerceIn(MIN_SPEED, MAX_SPEED)
        engine.setSpeed(s)
        _state.update { it.copy(speed = s) }
    }

    override fun setVolume(percent: Int) {
        val v = percent.coerceIn(0, 100)
        engine.setVolume(v)
        // Turning the volume up is a way out of mute, as in every other player.
        if (v > 0 && _state.value.muted) setMuted(false)
        _state.update { it.copy(volume = v) }
    }

    override fun setMuted(muted: Boolean) {
        engine.setMuted(muted)
        _state.update { it.copy(muted = muted) }
    }

    override fun selectTrack(type: TrackType, id: Int?) {
        engine.selectTrack(type, id ?: EngineValues.NO_TRACK)
        _tracks.update { list -> list.map { if (it.type == type) it.copy(selected = it.id == id) else it } }
    }

    override fun setScale(scale: VideoScale) {
        engine.setScale(scale)
        _state.update { it.copy(scale = scale) }
    }

    override fun setZoom(zoom: Double, panX: Double, panY: Double) = engine.setZoom(zoom, panX, panY)

    override fun setDownmix(stereo: Boolean) {
        engine.setDownmix(stereo)
        _state.update { it.copy(downmix = stereo) }
    }

    override fun setVolumeBoost(on: Boolean) {
        engine.setVolumeBoost(on)
        _state.update { it.copy(volumeBoost = on) }
    }

    override fun setNightMode(on: Boolean) {
        engine.setNightMode(on)
        _state.update { it.copy(nightMode = on) }
    }

    override fun setStereo3d(stereo: Stereo3d) {
        _state.update { it.copy(stereo = stereo) }
        if (_state.value.opened) engine.setStereo3d(stereo)
    }

    override fun setSubtitleStyle(style: SubtitleStyle) {
        engine.setSubtitleStyle(style)
        _state.update { it.copy(subtitleStyle = style) }
    }

    override fun setSubtitleDelay(ms: Long) {
        engine.setSubtitleDelay(ms)
        _state.update { it.copy(subtitleDelayMs = ms) }
    }

    override fun setAudioDelay(ms: Long) {
        engine.setAudioDelay(ms)
        _state.update { it.copy(audioDelayMs = ms) }
    }

    override fun addSubtitle(path: String): Boolean = _state.value.opened && engine.addSubtitle(path)

    override suspend fun screenshot(file: java.io.File, withSubtitles: Boolean): Boolean {
        if (!_state.value.opened) return false
        file.parentFile?.mkdirs()
        return suspendCancellableCoroutine { done -> engine.screenshot(file.absolutePath, withSubtitles) { done.resume(it) } }
    }

    override fun setAbLoop(loop: AbLoop?) {
        val end = loop?.endMs
        if (loop == null || end == null) {
            engine.setAbLoop(EngineValues.NO_LOOP, EngineValues.NO_LOOP)
        } else {
            engine.setAbLoop(loop.startMs, end)
        }
    }

    /** The desktop's Playback details, worded the same, from what the engine says is playing. */
    override fun details(): List<Pair<String, String>> {
        val facts = engine.facts()
        val now = _state.value
        val formatter = L.messages.formatter
        return buildList {
            val w = facts.videoWidth.takeIf { it > 0 } ?: now.videoWidth
            val h = facts.videoHeight.takeIf { it > 0 } ?: now.videoHeight
            val fps = facts.framesPerSecond.takeIf { it > 0 }
            if (w > 0 && h > 0) {
                add(L.playerDetailsLabelPicture to (fps?.let { L.playerDetailsPictureValue(w.toString(), h.toString(), formatter.decimal(it, significant(it))) } ?: "$w x $h"))
            }
            facts.videoCodec.ifBlank { null }?.let { add(L.playerDetailsLabelVideoCodec to it) }
            facts.pixelFormat.ifBlank { null }?.let { add(L.playerDetailsPixelFormat to it) }
            add(L.playerDetailsHwdec to facts.hardwareDecoder.ifBlank { L.playerDetailsSoftware })
            val audio = listOfNotNull(
                facts.audioCodec.ifBlank { null },
                facts.audioChannels.takeIf { it > 0 }?.let { L.playerDetailsChannels(it) },
                facts.sampleRate.takeIf { it > 0 }?.let { L.playerDetailsHz(it.toString()) },
            )
            if (audio.isNotEmpty()) add(L.playerDetailsLabelSound to audio.joinToString(", "))
            add(L.playerDownmix to if (now.downmix) L.commonOn else L.commonOff)
            add(L.playerVolumeBoost to if (now.volumeBoost) L.playerDetailsBoostGain else L.commonOff)
            add(L.playerDetailsNightMode to if (now.nightMode) L.playerDetailsNightDolby else L.commonOff)
            add(L.playerDetailsSubtitleDelay to SyncDelays.label(now.subtitleDelayMs))
            add(L.playerDetailsAudioDelay to SyncDelays.label(now.audioDelayMs))
            facts.container.ifBlank { null }?.let { add(L.playerDetailsContainer to it) }
            facts.bufferedSeconds.takeIf { it > 0 }?.let { add(L.playerDetailsBuffered to L.formatSecondsShort(formatter.decimal(it, 1))) }
            facts.videoOutput.ifBlank { null }?.let { add(L.playerDetailsVideoOutput to it) }
            facts.audioOutput.ifBlank { null }?.let { add(L.playerDetailsAudioOutput to it) }
            if (facts.engine.isNotBlank()) add(facts.engine to facts.engineVersion)
            if (facts.decoder.isNotBlank()) add(facts.decoder to facts.decoderVersion)
        }
    }

    override fun close() {
        if (external != null && android.app.IosExternalPlayback.controls === external) {
            android.app.IosExternalPlayback.controls = null
            android.app.IosExternalPlayback.pictureInPictureChanged(false)
        }
        current.value = null
        seekTarget.value = null
        _subtitleText.value = null
        engine.close()
    }

    override fun drawTextSubtitles(draw: Boolean) = engine.setTextSubtitlesShown(draw)

    // What the engine reports, from any thread; a report for a file no longer open is dropped.

    internal fun onTracks(from: EngineReports, list: List<EngineTrack>) {
        if (current.value !== from) return
        reportedTracks.value = list
        _tracks.value = list.map {
            MediaTrack(
                id = it.id,
                type = it.type,
                title = it.title,
                language = it.language,
                codec = it.codec,
                channels = it.channels.takeIf { c -> c > 0 },
                selected = it.selected,
                isDefault = it.isDefault,
                external = it.external,
                forced = it.forced,
            )
        }
    }

    internal fun onChapters(from: EngineReports, list: List<EngineChapter>) {
        if (current.value !== from) return
        _state.update { it.copy(chapters = list.map { c -> Chapter(c.startMs, c.title) }) }
    }

    internal fun onOpened(from: EngineReports, durationMs: Long) {
        if (current.value !== from) return
        _state.update { it.copy(opened = true, error = null, durationMs = durationMs.coerceAtLeast(0)) }
    }

    internal fun onProgress(from: EngineReports, positionMs: Long, durationMs: Long, bufferedMs: Long) {
        if (current.value !== from) return
        val pending = seekTarget.value != null
        _state.update {
            it.copy(
                // A seek in flight shows where it was sent, not where the old frame still is.
                positionMs = if (pending) it.positionMs else positionMs.coerceAtLeast(0),
                durationMs = if (durationMs > 0) durationMs else it.durationMs,
                bufferedMs = bufferedMs.coerceAtLeast(0),
            )
        }
    }

    internal fun onPaused(from: EngineReports, paused: Boolean) {
        if (current.value !== from) return
        _state.update { if (it.ended && !paused) it else it.copy(playing = !paused) }
    }

    internal fun onBuffering(from: EngineReports, buffering: Boolean) {
        if (current.value !== from) return
        val pending = seekTarget.value != null
        _state.update { it.copy(buffering = buffering || pending) }
    }

    internal fun onSeekLanded(from: EngineReports, positionMs: Long) {
        if (current.value !== from) return
        seekTarget.value = null
        _state.update { it.copy(positionMs = positionMs.coerceAtLeast(0), seekPending = false, buffering = false) }
    }

    internal fun onVideoSize(from: EngineReports, width: Int, height: Int) {
        if (current.value !== from) return
        _state.update { it.copy(videoWidth = width, videoHeight = height) }
    }

    internal fun onSubtitleText(from: EngineReports, text: String?) {
        if (current.value !== from) return
        _subtitleText.value = text?.takeIf { it.isNotEmpty() }
    }

    internal fun onEnded(from: EngineReports) {
        if (current.value !== from) return
        seekTarget.value = null
        _state.update { it.copy(ended = true, playing = false, buffering = false, seekPending = false) }
    }

    internal fun onFailed(from: EngineReports, message: String) {
        if (current.value !== from) return
        seekTarget.value = null
        _state.update { it.copy(error = message.ifBlank { "playback failed" }, playing = false, buffering = false, seekPending = false) }
    }

    // The tracks a video opens with: the same picks as the desktop's open, on any engine.

    /** The soundtrack in the first of [languages] the file has (Settings' ordered list), first match winning. */
    private fun pickAudio(languages: List<String>) {
        val audio = _tracks.value.filter { it.type == TrackType.Audio }
        for (language in languages) {
            if (audio.any { it.selected && TrackLanguages.same(language, it.language) }) return
            val track = audio.firstOrNull { TrackLanguages.same(language, it.language) } ?: continue
            selectTrack(TrackType.Audio, track.id)
            return
        }
    }

    private fun pickSubtitles(plan: SubtitlePlan) {
        val all = _tracks.value
        val subs = all.filter { it.type == TrackType.Subtitle }
        val audio = all.firstOrNull { it.type == TrackType.Audio && it.selected }?.language
        val described = subs.map { SubtitleTrackInfo(it.language, isDefault = it.isDefault, forced = it.forced) }
        val pick = when (val picked = TrackRules.pickSubtitle(plan, audio, described)) {
            SubtitlePick.Leave -> if (plan is SubtitlePlan.On && subs.none { it.selected } && subs.isNotEmpty()) SubtitlePick.Track(0) else picked
            else -> picked
        }
        when (pick) {
            SubtitlePick.Leave -> Unit
            SubtitlePick.None -> if (subs.any { it.selected }) selectTrack(TrackType.Subtitle, null)
            is SubtitlePick.Track -> subs.getOrNull(pick.index)?.takeIf { !it.selected }?.let { selectTrack(TrackType.Subtitle, it.id) }
        }
    }

    /** A resumed video's own tracks, over the languages; a place now holding another language is left alone. */
    private fun restoreTracks(wanted: ResumeState) {
        val all = _tracks.value
        val audio = all.filter { it.type == TrackType.Audio }
        val subs = all.filter { it.type == TrackType.Subtitle }
        wanted.audioTrack?.let { audio.getOrNull(it) }
            ?.takeIf { ResumeState.sameTrack(wanted.audioLanguage, it.language) }
            ?.let { selectTrack(TrackType.Audio, it.id) }
        when {
            wanted.subtitlesOff -> selectTrack(TrackType.Subtitle, null)
            else -> wanted.subtitleTrack?.let { subs.getOrNull(it) }
                ?.takeIf { ResumeState.sameTrack(wanted.subtitleLanguage, it.language) }
                ?.let { selectTrack(TrackType.Subtitle, it.id) }
        }
    }

    private companion object {
        // The desktop's SeekMath, whose file also holds the desktop's keyboard handling.
        const val END_GUARD_MS = 1_000L
        const val MIN_SPEED = 0.25f
        const val MAX_SPEED = 4f

        /** Not past a second before the end, where a seek would only end the video. */
        fun clampTarget(target: Long, duration: Long): Long {
            val floor = target.coerceAtLeast(0)
            return if (duration > END_GUARD_MS) floor.coerceAtMost(duration - END_GUARD_MS) else floor
        }

        /** Three significant digits, as the desktop's "%.3g": 23.976 is 24.0, 59.94 is 59.9. */
        fun significant(value: Double): Int = when {
            value >= 100 -> 0
            value >= 10 -> 1
            else -> 2
        }
    }
}
