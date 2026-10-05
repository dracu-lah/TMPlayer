package com.tmplayer

import android.os.Bundle
import android.os.Looper
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.fragment.app.FragmentActivity
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.util.UnstableApi
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
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
 *         [--ez tv true] [--ez playing true] [--es feedback ripple|level|scrub|hold|flash]
 *         [--ez nextup true] [--ez menu true]
 *
 * `nextup` raises the next-up card over the bare picture, `menu` the television's More menu.
 *
 * Nothing here exists in a release build.
 */
@UnstableApi
class PromoPlayerActivity : FragmentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val tv = intent.getBooleanExtra("tv", false)
        if (tv) FormFactor.override(true)
        setContentView(R.layout.activity_player)
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
            onTogglePlay = {},
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
        controls.setEpisodes(demoEpisode(3), demoEpisode(5), "Previous S01E03", "Next S01E05")
        controls.timeoutMs = 0
        controls.show()

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
                    onEntry = {},
                    onSpeed = {},
                    onClosed = {},
                ).open()
            }
        }, 600)
    }

    private fun demoEpisode(number: Int) = MediaItem(
        chatId = 1, messageId = number.toLong(), fileId = number, title = "The Coast S01E0$number",
        sizeBytes = 0, durationSec = 2634, mimeType = "video/x-matroska", thumbnailFileId = 0,
        miniThumbnail = null, date = 0, fileName = "The.Coast.S01E0$number.mkv",
    )

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

        override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
            playing = playWhenReady
            return Futures.immediateVoidFuture()
        }
    }
}
