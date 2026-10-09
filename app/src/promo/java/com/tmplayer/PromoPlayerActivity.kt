package com.tmplayer

import androidx.compose.ui.graphics.asImageBitmap
import android.os.Bundle
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.fragment.app.FragmentActivity
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.common.TrackGroup
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.Tracks
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.util.UnstableApi
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.tmplayer.i18n.L
import com.tmplayer.data.FormFactor
import com.tmplayer.data.MediaItem
import com.tmplayer.player.PlayerControls
import com.tmplayer.player.NextUpCard
import com.tmplayer.player.PlayerFeedback
import com.tmplayer.player.PlayerTvMenu
import com.tmplayer.player.label
import com.tmplayer.player.TapZone
import com.tmplayer.player.SubtitleStyle
import com.tmplayer.player.SyncDelays
import com.tmplayer.player.TrackPickerFragment
import com.tmplayer.player.TrackPickerHost
import com.tmplayer.online.OnlineSubtitles
import com.tmplayer.online.SubtitleTarget
import androidx.media3.common.C

/**
 * Screenshot fixture for the player's overlay. Draws the real controls layout over a demo picture,
 * driven by a stand-in player that sits paused (or playing) at a fixed point in a 44 minute film,
 * so the transport can be captured and checked without a Telegram account or a video file.
 *
 *     adb shell am start -n com.tmplayer.promo/com.tmplayer.PromoPlayerActivity \
 *         [--ez tv true] [--ez playing true] [--es feedback ripple|level|scrub|hold|flash|jump|offer|hint]
 *         [--ez nextup true] [--ez menu true] [--ez overflow true] [--ez trickplay true] [--el scrub_at 1265000]
 *         [--es picker subtitles|online] [--ez subtitles true]
 *         [--es online signed_out|signed_in|quota|expired|unavailable|empty|offline|subdl|none|live]
 *
 * `nextup` raises the next-up card over the bare picture, `menu` the television's More menu,
 * `jump` the remote's side figure for a ten second jump. The stand-in player really plays and
 * pauses, so on a television OK on the focused bar and the play key show the paused cue.
 *
 * Nothing here exists in a release build.
 */
@UnstableApi
class PromoPlayerActivity : FragmentActivity(), TrackPickerHost {

    private var controls: PlayerControls? = null
    private var standIn: StandInPlayer? = null
    private var delays = SyncDelays()
    private var style = SubtitleStyle()

    override val player: Player? get() = standIn
    override fun syncDelaysNow() = delays
    override fun stepDelay(trackType: Int, direction: Int) {
        delays = delays.copy(subtitleMs = if (direction == 0) 0 else SyncDelays.step(delays.subtitleMs, direction))
    }
    override fun subtitleStyleNow() = style
    override fun changeSubtitleStyle(style: SubtitleStyle) {
        this.style = style
    }
    override suspend fun onlineTarget() = SubtitleTarget(
        fileName = "The.Coast.S01E04.1080p.WEB.H264.mkv",
        sizeBytes = 1_400_000_000,
        hash = "8e245d9679d31e12",
        languages = OnlineSubtitles.languagesFor(null),
    )
    override fun attachOnlineSubtitle(file: java.io.File, label: String) {
        android.widget.Toast.makeText(this, L.tracksFileLoaded(label), android.widget.Toast.LENGTH_SHORT).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val tv = intent.getBooleanExtra("tv", false)
        if (tv) FormFactor.override(true)
        promoLanguage(intent.getStringExtra("lang"))
        PromoOnline.install(applicationContext, intent.getStringExtra("online"))
        setContentView(R.layout.activity_player)
        findViewById<View>(android.R.id.content).layoutDirection = com.tmplayer.player.layoutDirectionFor(com.tmplayer.i18n.Translator.messages.rtl)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).hide(WindowInsetsCompat.Type.systemBars())

        findViewById<View>(R.id.status_overlay).visibility = View.GONE
        findViewById<FrameLayout>(R.id.playback_container).addView(
            ImageView(this).apply {
                setImageResource(R.drawable.demo_coast)
                scaleType = ImageView.ScaleType.CENTER_CROP
            },
        )

        val player = StandInPlayer(
            playing = intent.getBooleanExtra("playing", false),
            withSubtitles = intent.getBooleanExtra("subtitles", false),
        )
        val controls = PlayerControls(
            root = findViewById(R.id.player_root),
            isTv = tv,
            player = { player },
            onVisibility = {},
            onTogglePlay = { player.playWhenReady = !player.playWhenReady },
            onPickSubtitles = {
                TrackPickerFragment.show(supportFragmentManager, R.id.overlay_container, C.TRACK_TYPE_TEXT)
            },
            onPickAudio = {},
            onPickSpeed = {},
            onCycleOrientation = {},
            onPlayEpisode = {},
        )
        controls.setTitle("The Coast", "S01E04 · Nature Channel · 1.4 GB")
        controls.setEpisodes(demoEpisode(3), demoEpisode(5), L.playerPreviousUp("S01E03"), L.playerNextUp("S01E05"), "S01E03", "S01E05")
        controls.timeoutMs = 0
        // `--ez trickplay true`: scrub thumbnails from a fake download of the first 1,520 seconds,
        // the same run the bar draws as buffered, so a D-pad scrub shows pictures up to there and
        // none past it. `--el scrub_at <ms>` pins one up at that point for a still.
        if (intent.getBooleanExtra("trickplay", false) &&
            com.tmplayer.data.DeviceQuirks.trickplayMemory(this)
        ) {
            controls.thumbnails = PromoThumbnails(this)
        }
        controls.show()
        // `--ez loader true`: the pre-roll loader over everything, with the sample film's art
        // standing in for the online metadata, as the desktop's PlayerLoaderRenderTest draws it.
        if (intent.getBooleanExtra("loader", false)) showPromoLoader(tv)
        this.controls = controls
        standIn = player
        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) = controls.onPlayingChanged()
        })

        val feedback = PlayerFeedback(findViewById(R.id.player_root), findViewById(R.id.overlay_container))
        val root = findViewById<View>(R.id.player_root)
        root.postDelayed({
            when (intent.getStringExtra("feedback")) {
                "ripple" -> {
                    controls.hideNow()
                    feedback?.seekStep(TapZone.Right, 30, root.width * 0.8f, root.height * 0.5f)
                }
                "level" -> {
                    controls.hideNow()
                    feedback?.level(left = false, fraction = 0.6f, brightness = false)
                }
                "scrub" -> {
                    controls.hideNow()
                    feedback?.scrub(95_000, 1_360_000, 2_634_000)
                }
                "hold" -> {
                    controls.hideNow()
                    feedback?.holdStarted(2f)
                }
                "flash" -> {
                    controls.hideNow()
                    feedback?.flashPlayPause(false)
                }
                "jump" -> {
                    controls.hideNow()
                    feedback.jump(TapZone.Right, 10)
                }
                "offer" -> {
                    controls.hideNow()
                    feedback.offer(L.playerResumingFrom("13:54"), L.playerStartOver) {}
                }
                "hint" -> {
                    controls.hideNow()
                    // As the first playback says it: the remote's keys on a television, the
                    // gestures that are on (one line each) with "Got it" on a phone.
                    if (tv) {
                        feedback.message(L.playerRemoteHint, holdMs = 60_000L)
                    } else {
                        feedback.offer(
                            com.tmplayer.player.FirstRunHint.phone(com.tmplayer.player.TouchPrefs(), L).joinToString("\n"),
                            L.commonGotIt,
                            holdMs = 60_000L,
                        ) {}
                    }
                }
            }
            val pinned = intent.getLongExtra("scrub_at", -1L)
            if (pinned >= 0 && controls.thumbnails != null) {
                controls.previewPinned = true
                controls.preview(pinned)
            }
            if (intent.getBooleanExtra("nextup", false)) {
                controls.hideNow()
                val card = NextUpCard(
                    root = findViewById(R.id.player_root),
                    below = findViewById(R.id.overlay_container),
                    tv = tv,
                    onHide = {},
                    onPlay = {},
                )
                card.show("S01E05", 12)
                if (tv) card.play.requestFocus()
            }
            if (intent.getBooleanExtra("menu", false)) {
                PlayerTvMenu(
                    activity = this,
                    root = findViewById(R.id.player_root),
                    title = { "The Coast S01E04" },
                    pictureInPicture = { true },
                    nextEpisode = { L.playerNextUp("S01E05") },
                    previousEpisode = { L.playerPreviousUp("S01E03") },
                    onEntry = {},
                    onClosed = {},
                ).open()
            }
            // `--ez overflow true`: the phone's overflow, built from the same entries as the player's.
            if (intent.getBooleanExtra("overflow", false) && !tv) {
                val popup = android.widget.PopupMenu(this, findViewById(R.id.control_more), android.view.Gravity.END)
                com.tmplayer.player.PlayerMenu.phoneEntries(
                    pictureInPicture = true,
                    openInAnotherApp = true,
                    saveToDownloads = true,
                    markWatched = true,
                ).forEachIndexed { order, entry -> popup.menu.add(0, entry.ordinal, order, entry.label()) }
                popup.show()
            }
            // `--es picker subtitles` opens the subtitle picker, `online` its "Search online" list.
            when (intent.getStringExtra("picker")) {
                "subtitles" -> TrackPickerFragment.show(supportFragmentManager, R.id.overlay_container, C.TRACK_TYPE_TEXT)
                "online" -> TrackPickerFragment.show(supportFragmentManager, R.id.overlay_container, C.TRACK_TYPE_TEXT, online = true)
            }
        }, 600)
    }

    /** The two remote keys the paused cue answers: OK on the focused bar, and play or pause. */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER ->
                    if (controls?.okOnTimeBar() == true) return true
                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                    standIn?.let { it.playWhenReady = !it.playWhenReady }
                    return true
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun showPromoLoader(tv: Boolean) {
        fun art(id: Int) = android.graphics.BitmapFactory.decodeResource(resources, id).asImageBitmap()
        val content = com.tmplayer.ui.player.LoaderContent(
            words = com.tmplayer.ui.player.LoaderWords(
                title = "Big Buck Bunny",
                line = "2008  ·  10m",
                overview = "A large and lovable rabbit deals with three tiny bullies, led by a flying squirrel, " +
                    "who are determined to squelch his happiness.",
            ),
            backdrop = art(R.drawable.promo_bbb_backdrop),
            poster = art(R.drawable.promo_bbb_poster),
        )
        findViewById<FrameLayout>(R.id.player_root).addView(
            androidx.compose.ui.platform.ComposeView(this).apply {
                setContent {
                    com.tmplayer.ui.theme.TmMaterialTheme(dark = true) {
                        com.tmplayer.ui.player.PlayerLoader(
                            content = content,
                            progress = 0.42f,
                            status = L.playerResumingFrom("13:54"),
                            note = L.playerCachingRate("160 KB/s"),
                            tv = tv,
                        )
                    }
                }
            },
        )
    }

    private fun demoEpisode(number: Int) = MediaItem(
        chatId = 1, messageId = number.toLong(), fileId = number, title = "The Coast S01E0$number",
        sizeBytes = 0, durationSec = 2634, mimeType = "video/x-matroska", thumbnailFileId = 0,
        miniThumbnail = null, date = 0, fileName = "The.Coast.S01E0$number.mkv",
    )

    /**
     * Scrub thumbnails for the stand-in film: the demo pictures in turn, one per 10 second slot,
     * answered through the same [Trickplay.covered] rule the real frames use, over a fake download
     * of the first 1,520 seconds of a 1.4 GB file.
     */
    private class PromoThumbnails(private val activity: FragmentActivity) : com.tmplayer.player.ScrubThumbnails {
        private val pictures = intArrayOf(
            R.drawable.demo_coast, R.drawable.demo_forest, R.drawable.demo_workshop,
            R.drawable.demo_kitchen, R.drawable.demo_birthday, R.drawable.demo_tutorial,
        )
        private val size = 1_400_000_000L
        private val duration = 2_634_000L
        private val spans = listOf(com.tmplayer.data.Trickplay.Span(0, size * 1_520_000 / duration))

        override fun request(positionMs: Long, onFrame: (android.graphics.Bitmap?) -> Unit) {
            if (!com.tmplayer.data.Trickplay.covered(positionMs, duration, size, spans, complete = false)) {
                onFrame(null)
                return
            }
            val slot = (com.tmplayer.data.Trickplay.bucketMs(positionMs) / com.tmplayer.data.Trickplay.STEP_MS).toInt()
            val bitmap = android.graphics.BitmapFactory.decodeResource(activity.resources, pictures[slot % pictures.size])
            onFrame(bitmap)
        }

        override fun release() = Unit
    }

    /**
     * Paused 21 minutes into a 44 minute film, with a little over half of it downloaded. With
     * [withSubtitles], two subtitle tracks (English, Malayalam) that the picker really switches.
     */
    private class StandInPlayer(
        private var playing: Boolean,
        private val withSubtitles: Boolean = false,
    ) : SimpleBasePlayer(Looper.getMainLooper()) {
        private var params: TrackSelectionParameters = TrackSelectionParameters.DEFAULT
        private val subtitleGroup = TrackGroup(
            "subtitles",
            Format.Builder().setId("en").setSampleMimeType(MimeTypes.APPLICATION_SUBRIP).setLanguage("en").build(),
            Format.Builder().setId("ml").setSampleMimeType(MimeTypes.APPLICATION_SUBRIP).setLanguage("ml").build(),
        )

        private fun tracks(): Tracks {
            if (!withSubtitles) return Tracks.EMPTY
            val off = C.TRACK_TYPE_TEXT in params.disabledTrackTypes
            val chosen = params.overrides[subtitleGroup]?.trackIndices
            val selected = BooleanArray(subtitleGroup.length) { !off && (chosen?.contains(it) ?: (it == 0)) }
            return Tracks(listOf(Tracks.Group(subtitleGroup, false, IntArray(subtitleGroup.length) { C.FORMAT_HANDLED }, selected)))
        }

        override fun getState(): State = State.Builder()
            .setAvailableCommands(Player.Commands.Builder().addAllCommands().build())
            .setTrackSelectionParameters(params)
            .setPlaylist(listOf(MediaItemData.Builder("demo").setDurationUs(2_634_000_000L).setTracks(tracks()).build()))
            .setPlayWhenReady(playing, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            .setPlaybackState(Player.STATE_READY)
            .setContentPositionMs(1_265_000)
            .setContentBufferedPositionMs(PositionSupplier.getConstant(1_520_000))
            .build()

        /** A committed scrub lands nowhere: the stand-in stays where it is, and does not crash. */
        override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int): ListenableFuture<*> =
            Futures.immediateVoidFuture()

        override fun handleSetTrackSelectionParameters(parameters: TrackSelectionParameters): ListenableFuture<*> {
            params = parameters
            return Futures.immediateVoidFuture()
        }

        override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
            playing = playWhenReady
            return Futures.immediateVoidFuture()
        }
    }
}
