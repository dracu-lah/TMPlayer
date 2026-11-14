package com.tmplayer.player

import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.TrackGroup
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.tmplayer.data.PlayerEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * One contract for both engines (PR2): every test runs against [ExoEngine] and [VlcEngine] alike.
 *
 * ExoEngine runs over [FakeMedia3Player], a Media3 player that keeps its state and its tracks the
 * way ExoPlayer reports them (two letter languages, an override per group). VlcEngine runs over
 * [FakeVlcBackend], which answers as libVLC's Java binding does (three letter and bibliographic
 * languages, ids, the Disable entry left out). Not covered here, since neither libVLC nor a decoder
 * runs on the JVM: real decoding, the surface, libVLC's own event timing, HDR, passthrough, and
 * memory and CPU on a television. Those are the device checks listed in the PR notes.
 */
@RunWith(RobolectricTestRunner::class)
class EngineContractTest {

    private class Subject(val name: String, val make: () -> Engine)

    private val subjects = listOf(
        Subject("ExoPlayer") { ExoEngine(FakeMedia3Player()) },
        Subject("VLC") { VlcEngine(FakeVlcBackend()) },
    )

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun each(body: (String, Engine) -> Unit) {
        for (subject in subjects) {
            val engine = subject.make()
            try {
                body(subject.name, engine)
            } finally {
                engine.release()
            }
        }
    }

    private fun Engine.prepared(startMs: Long = 0L): Engine {
        prepare(EngineSource.File("/downloads/video.mkv"), startMs)
        idle()
        return this
    }

    @Test
    fun `prepare starts at the place asked, play plays and pause pauses`() = each { name, engine ->
        engine.prepared(startMs = 5_000L)
        assertEquals(name, 5_000L, engine.positionMs)
        engine.play()
        idle()
        assertTrue(name, engine.isPlaying)
        assertTrue(name, engine.player.playWhenReady)
        engine.pause()
        idle()
        assertFalse(name, engine.isPlaying)
        assertFalse(name, engine.player.playWhenReady)
    }

    @Test
    fun `a seek moves the place and never before the start`() = each { name, engine ->
        engine.prepared()
        engine.seekTo(42_000L)
        idle()
        assertEquals(name, 42_000L, engine.positionMs)
        engine.seekTo(-10L)
        idle()
        assertEquals(name, 0L, engine.positionMs)
    }

    @Test
    fun `speed is kept and volume stays between silent and the file's own`() = each { name, engine ->
        engine.prepared()
        engine.speed = 1.5f
        idle()
        assertEquals(name, 1.5f, engine.speed, 0.001f)
        engine.volume = 2f
        assertEquals(name, 1f, engine.volume, 0.01f)
        engine.volume = -1f
        assertEquals(name, 0f, engine.volume, 0.01f)
        engine.volume = 0.5f
        assertEquals(name, 0.5f, engine.volume, 0.01f)
    }

    @Test
    fun `tracks are listed with their languages and picked by id`() = each { name, engine ->
        engine.prepared()
        val tracks = engine.tracks()
        val audio = tracks.filter { it.type == EngineTrack.Type.Audio }
        val text = tracks.filter { it.type == EngineTrack.Type.Subtitle }
        assertEquals(name, 2, audio.size)
        assertEquals(name, 2, text.size)
        val french = audio.first { EngineCarry.sameLanguage("fr", it.language) }
        engine.selectAudio(french.id)
        idle()
        assertTrue(name, engine.tracks().first { it.id == french.id }.selected)
        val arabic = text.first { EngineCarry.sameLanguage("ar", it.language) }
        engine.selectSubtitle(arabic.id)
        idle()
        assertTrue(name, engine.tracks().first { it.id == arabic.id }.selected)
        engine.selectSubtitle(null)
        idle()
        assertTrue(name, engine.tracks().none { it.type == EngineTrack.Type.Subtitle && it.selected })
    }

    @Test
    fun `what one engine carries the other puts back`() {
        for (from in subjects) {
            for (to in subjects) {
                val a = from.make().prepared()
                a.selectAudio(a.tracks().first { it.type == EngineTrack.Type.Audio && EngineCarry.sameLanguage("fr", it.language) }.id)
                a.selectSubtitle(a.tracks().first { it.type == EngineTrack.Type.Subtitle && EngineCarry.sameLanguage("ar", it.language) }.id)
                a.speed = 1.25f
                a.volume = 0.6f
                a.seekTo(42_000L)
                idle()
                val carry = a.carry()
                a.release()
                val label = "${from.name} to ${to.name}"
                assertEquals(label, 42_000L, carry.positionMs)
                val b = to.make().prepared(carry.positionMs)
                assertTrue(label, b.restore(carry))
                idle()
                assertEquals(label, 42_000L, b.positionMs)
                assertEquals(label, 1.25f, b.speed, 0.001f)
                assertEquals(label, 0.6f, b.volume, 0.01f)
                val audio = b.tracks().first { it.type == EngineTrack.Type.Audio && it.selected }
                assertTrue("$label: ${audio.language}", EngineCarry.sameLanguage("fr", audio.language))
                val text = b.tracks().first { it.type == EngineTrack.Type.Subtitle && it.selected }
                assertTrue("$label: ${text.language}", EngineCarry.sameLanguage("ar", text.language))
                b.release()
            }
        }
    }

    @Test
    fun `subtitles off are carried as off`() = each { name, engine ->
        engine.prepared()
        engine.selectSubtitle(null)
        idle()
        val carry = engine.carry()
        assertTrue(name, carry.subtitlesOff)
        assertNull(name, carry.subtitleLanguage)
    }

    @Test
    fun `release twice is harmless and leaves nothing playing`() = each { name, engine ->
        engine.prepared()
        engine.play()
        idle()
        engine.release()
        engine.release()
        idle()
        assertFalse(name, engine.isPlaying)
    }

    @Test
    fun `the engine says which it is`() {
        assertEquals(PlayerEngine.ExoPlayer, ExoEngine(FakeMedia3Player()).kind)
        assertEquals(PlayerEngine.Vlc, VlcEngine(FakeVlcBackend()).kind)
    }

    // ---- VLC only: what libVLC does that ExoPlayer does not -------------------------------------

    @Test
    fun `VLC put back after a switch waits for the file's tracks`() {
        val backend = FakeVlcBackend(tracksAtOpen = false)
        val vlc = VlcEngine(backend)
        vlc.prepare(EngineSource.Stream("http://127.0.0.1:1/t/video"), 10_000L)
        val carry = EngineCarry(positionMs = 10_000L, audioLanguage = "fr", subtitleLanguage = "ar", speed = 1.25f)
        assertFalse(vlc.restore(carry))
        backend.listTracks()
        idle()
        assertTrue(vlc.tracks().first { it.selected && it.type == EngineTrack.Type.Audio }.language == "fre")
        assertEquals("ara", vlc.tracks().first { it.selected && it.type == EngineTrack.Type.Subtitle }.language)
        vlc.release()
    }

    @Test
    fun `VLC opens a paused video paused`() {
        val backend = FakeVlcBackend()
        val vlc = VlcEngine(backend)
        vlc.prepare(EngineSource.File("/downloads/video.mkv"), 0L)
        idle()
        // libVLC reads only while it plays, so it was started to open and paused as it began.
        assertTrue(backend.played)
        assertFalse(backend.playing)
        assertEquals(Player.STATE_READY, vlc.player.playbackState)
        vlc.release()
    }

    @Test
    fun `a VLC error reaches the player's listener as a decoding failure`() {
        val backend = FakeVlcBackend()
        val vlc = VlcEngine(backend)
        vlc.prepared()
        var heard: androidx.media3.common.PlaybackException? = null
        vlc.player.addListener(object : Player.Listener {
            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                heard = error
            }
        })
        backend.fail()
        idle()
        assertEquals(androidx.media3.common.PlaybackException.ERROR_CODE_DECODING_FAILED, heard?.errorCode)
        assertEquals(VlcEngine.VLC_ERROR, heard?.message)
        vlc.release()
    }

    @Test
    fun `the track picker's override picks the VLC track`() {
        val vlc = VlcEngine(FakeVlcBackend()).prepared() as VlcEngine
        val group = vlc.player.currentTracks.groups.first { it.type == C.TRACK_TYPE_AUDIO && !it.isSelected }
        vlc.player.trackSelectionParameters = vlc.player.trackSelectionParameters.buildUpon()
            .setOverrideForType(androidx.media3.common.TrackSelectionOverride(group.mediaTrackGroup, 0))
            .build()
        idle()
        assertTrue(vlc.player.currentTracks.groups.first { it.mediaTrackGroup.id == group.mediaTrackGroup.id }.isSelected)
        vlc.player.trackSelectionParameters = vlc.player.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
            .build()
        idle()
        assertTrue(vlc.player.currentTracks.groups.none { it.type == C.TRACK_TYPE_TEXT && it.isSelected })
        vlc.release()
    }

    @Test
    fun `shapes reach libVLC as ratios it reads`() {
        assertNull(VlcEngine.aspectText(null))
        assertNull(VlcEngine.aspectText(0.0))
        assertNull(VlcEngine.aspectText(Double.NaN))
        assertEquals("16:9", VlcEngine.aspectText(16.0 / 9.0))
        assertEquals("4:3", VlcEngine.aspectText(4.0 / 3.0))
        assertEquals("47:20", VlcEngine.aspectText(2.35))
        assertEquals("1:1", VlcEngine.aspectText(1.0))
    }
}

/** libVLC as its Java binding answers, for [EngineContractTest]. */
class FakeVlcBackend(private val tracksAtOpen: Boolean = true) : VlcBackend {
    var played = false
    var playing = false
    private var opened: EngineSource? = null
    private var time = 0L
    private var listed = false
    private val all = listOf(
        VlcTrack(1, EngineTrack.Type.Audio, "Track 1 - [English]", "eng"),
        VlcTrack(2, EngineTrack.Type.Audio, "Track 2 - [French]", "fre"),
        VlcTrack(3, EngineTrack.Type.Subtitle, "Track 1 - [English]", "eng"),
        VlcTrack(4, EngineTrack.Type.Subtitle, "Track 2 - [Arabic]", "ara"),
    )
    override var audioTrack = -1
        private set
    override var spuTrack = -1
        private set
    override var rate = 1f
    override var volumePercent = 100
    override var listener: ((VlcEvent) -> Unit)? = null

    override fun open(source: EngineSource, startMs: Long) {
        opened = source
        time = startMs
        listed = tracksAtOpen
        if (listed) {
            audioTrack = 1
            spuTrack = 3
        }
    }

    fun listTracks() {
        listed = true
        audioTrack = 1
        spuTrack = 3
        listener?.invoke(VlcEvent.TracksChanged)
    }

    fun fail() = listener?.invoke(VlcEvent.Error) ?: Unit

    override fun play() {
        played = true
        playing = true
        listener?.invoke(VlcEvent.Opening)
        listener?.invoke(VlcEvent.Buffering(100f))
        listener?.invoke(VlcEvent.Playing)
    }

    override fun pause() {
        playing = false
        listener?.invoke(VlcEvent.Paused)
    }

    override fun stop() {
        playing = false
    }

    override fun seekTo(positionMs: Long) {
        time = positionMs
    }

    override val timeMs: Long get() = time
    override val lengthMs: Long get() = if (opened != null) 3_600_000L else -1L
    override val isPlaying: Boolean get() = playing
    override fun tracks(): List<VlcTrack> = if (listed) all else emptyList()

    override fun setAudioTrack(id: Int): Boolean {
        if (all.none { it.id == id && it.type == EngineTrack.Type.Audio }) return false
        audioTrack = id
        return true
    }

    override fun setSpuTrack(id: Int): Boolean {
        if (id != -1 && all.none { it.id == id && it.type == EngineTrack.Type.Subtitle }) return false
        spuTrack = id
        return true
    }

    override fun addSubtitle(uri: String) = true
    override fun setAspect(ratio: String?) = Unit
    override fun attach(view: android.view.SurfaceView?) = Unit
    override fun setVideoTrackEnabled(enabled: Boolean) = Unit
    var delays = 0L to 0L
    override fun setDelays(audioUs: Long, subtitleUs: Long) {
        delays = audioUs to subtitleUs
    }
    override val videoWidth: Int get() = 1920
    override val videoHeight: Int get() = 1080

    override fun release() {
        playing = false
    }
}

/** ExoPlayer as the overlay sees it, for [EngineContractTest]: state, tracks and overrides. */
@androidx.annotation.OptIn(UnstableApi::class)
class FakeMedia3Player : SimpleBasePlayer(Looper.getMainLooper()) {
    private var item: MediaItem? = null
    private var state = Player.STATE_IDLE
    private var playWhenReady = false
    private var position = 0L
    private var speed = 1f
    private var volume = 1f
    private var parameters = TrackSelectionParameters.DEFAULT

    private val groups = listOf(
        group("a-en", MimeTypes.AUDIO_AAC, "en"),
        group("a-fr", MimeTypes.AUDIO_AAC, "fr"),
        group("t-en", MimeTypes.TEXT_VTT, "en"),
        group("t-ar", MimeTypes.TEXT_VTT, "ar"),
    )

    private fun group(id: String, mime: String, language: String) =
        TrackGroup(id, Format.Builder().setId(id).setSampleMimeType(mime).setLanguage(language).build())

    private fun tracks(): Tracks {
        if (state == Player.STATE_IDLE) return Tracks.EMPTY
        val chosen = parameters.overrides.keys.map { it.id }.toSet()
        return Tracks(
            groups.map { g ->
                val type = g.type
                val off = type in parameters.disabledTrackTypes
                val overridden = groups.any { it.type == type && it.id in chosen }
                val selected = !off && if (overridden) g.id in chosen else g.id == groups.first { it.type == type }.id
                Tracks.Group(g, false, intArrayOf(C.FORMAT_HANDLED), booleanArrayOf(selected))
            },
        )
    }

    override fun getState(): State {
        val builder = State.Builder()
            .setAvailableCommands(Player.Commands.Builder().addAllCommands().build())
            .setPlayWhenReady(playWhenReady, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            .setPlaybackState(if (item == null) Player.STATE_IDLE else state)
            .setPlaybackParameters(PlaybackParameters(speed))
            .setVolume(volume)
            .setTrackSelectionParameters(parameters)
        item?.let {
            builder.setPlaylist(listOf(MediaItemData.Builder("fake").setMediaItem(it).setDurationUs(3_600_000_000L).setTracks(tracks()).build()))
                .setCurrentMediaItemIndex(0)
                .setContentPositionMs(position)
        }
        return builder.build()
    }

    override fun handleSetMediaItems(mediaItems: MutableList<MediaItem>, startIndex: Int, startPositionMs: Long): ListenableFuture<*> {
        item = mediaItems.firstOrNull()
        if (startPositionMs != C.TIME_UNSET) position = startPositionMs
        return Futures.immediateVoidFuture()
    }

    override fun handlePrepare(): ListenableFuture<*> {
        state = Player.STATE_READY
        return Futures.immediateVoidFuture()
    }

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        this.playWhenReady = playWhenReady
        return Futures.immediateVoidFuture()
    }

    override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int): ListenableFuture<*> {
        position = positionMs.coerceAtLeast(0L)
        return Futures.immediateVoidFuture()
    }

    override fun handleSetPlaybackParameters(playbackParameters: PlaybackParameters): ListenableFuture<*> {
        speed = playbackParameters.speed
        return Futures.immediateVoidFuture()
    }

    override fun handleSetVolume(volume: Float, volumeOperationType: Int): ListenableFuture<*> {
        this.volume = volume
        return Futures.immediateVoidFuture()
    }

    override fun handleSetTrackSelectionParameters(trackSelectionParameters: TrackSelectionParameters): ListenableFuture<*> {
        parameters = trackSelectionParameters
        return Futures.immediateVoidFuture()
    }

    override fun handleStop(): ListenableFuture<*> {
        state = Player.STATE_IDLE
        playWhenReady = false
        return Futures.immediateVoidFuture()
    }

    override fun handleRelease(): ListenableFuture<*> = Futures.immediateVoidFuture()

    override fun handleClearVideoOutput(videoOutput: Any?): ListenableFuture<*> = Futures.immediateVoidFuture()

    override fun handleSetVideoOutput(videoOutput: Any): ListenableFuture<*> = Futures.immediateVoidFuture()
}
