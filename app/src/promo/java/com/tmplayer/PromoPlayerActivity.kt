package com.tmplayer

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
import androidx.media3.common.Player
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
import com.tmplayer.player.TapZone

/**
 * Screenshot fixture for the player's overlay. Draws the real controls layout over a demo picture,
 * driven by a stand-in player that sits paused (or playing) at a fixed point in a 44 minute film,
 * so the transport can be captured and checked without a Telegram account or a video file.
 *
 *     adb shell am start -n com.tmplayer.promo/com.tmplayer.PromoPlayerActivity \
 *         [--ez tv true] [--ez playing true] [--es feedback ripple|level|scrub|hold|flash|jump]
 *         [--ez nextup true] [--ez menu true] [--ez trickplay true] [--el scrub_at 1265000]
 *
 * `nextup` raises the next-up card over the bare picture, `menu` the television's More menu,
 * `jump` the remote's side figure for a ten second jump. The stand-in player really plays and
 * pauses, so on a television OK on the focused bar and the play key show the paused cue.
 *
 * Nothing here exists in a release build.
 */
@UnstableApi
class PromoPlayerActivity : FragmentActivity() {

    private var controls: PlayerControls? = null
    private var standIn: StandInPlayer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val tv = intent.getBooleanExtra("tv", false)
        if (tv) FormFactor.override(true)
        promoLanguage(intent.getStringExtra("lang"))
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

        val player = StandInPlayer(playing = intent.getBooleanExtra("playing", false))
        val controls = PlayerControls(
            root = findViewById(R.id.player_root),
            isTv = tv,
            player = { player },
            onVisibility = {},
            onTogglePlay = { player.playWhenReady = !player.playWhenReady },
            onSkip = {},
            onPickSubtitles = {},
            onPickAudio = {},
            onCycleSpeed = {},
            onCycleScale = {},
            onCycleOrientation = {},
            onPlayEpisode = {},
        )
        controls.setTitle("The Coast", "S01E04 · Nature Channel · 1.4 GB")
        if (!tv) controls.setSkip(10_000, 10_000)
        controls.setEpisodes(demoEpisode(3), demoEpisode(5), L.playerPreviousCode("S01E03"), L.playerNextCode("S01E05"))
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
        this.controls = controls
        standIn = player
        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) = controls.onPlayingChanged()
        })

        val feedback = if (tv) null else PlayerFeedback(findViewById(R.id.player_root), findViewById(R.id.overlay_container))
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
                    findViewById<TextView>(R.id.gesture_hud).apply {
                        text = "10 s   ▶▶"
                        (layoutParams as FrameLayout.LayoutParams).gravity =
                            android.view.Gravity.CENTER_VERTICAL or android.view.Gravity.END
                        visibility = View.VISIBLE
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
                    speed = { 1f },
                    nextEpisode = { L.playerNextCode("S01E05") },
                    previousEpisode = { L.playerPreviousCode("S01E03") },
                    onEntry = {},
                    onSpeed = {},
                    onClosed = {},
                ).open()
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

    /** Paused 21 minutes into a 44 minute film, with a little over half of it downloaded. */
    private class StandInPlayer(private var playing: Boolean) : SimpleBasePlayer(Looper.getMainLooper()) {
        override fun getState(): State = State.Builder()
            .setAvailableCommands(Player.Commands.Builder().addAllCommands().build())
            .setPlaylist(listOf(MediaItemData.Builder("demo").setDurationUs(2_634_000_000L).build()))
            .setPlayWhenReady(playing, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            .setPlaybackState(Player.STATE_READY)
            .setContentPositionMs(1_265_000)
            .setContentBufferedPositionMs(PositionSupplier.getConstant(1_520_000))
            .build()

        /** A committed scrub lands nowhere: the stand-in stays where it is, and does not crash. */
        override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int): ListenableFuture<*> =
            Futures.immediateVoidFuture()

        override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
            playing = playWhenReady
            return Futures.immediateVoidFuture()
        }
    }
}
