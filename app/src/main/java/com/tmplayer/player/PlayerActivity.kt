package com.tmplayer.player

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.widget.TextView
import androidx.fragment.app.FragmentActivity
import androidx.leanback.app.GuidedStepSupportFragment
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem as Media3Item
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.text.CueGroup
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.SubtitleView
import androidx.lifecycle.lifecycleScope
import com.tmplayer.App
import com.tmplayer.R
import com.tmplayer.data.MediaItem
import com.tmplayer.data.MediaMapper
import com.tmplayer.data.SettingsStore
import com.tmplayer.data.Td
import io.github.anilbeesetti.nextlib.media3ext.ffdecoder.NextRenderersFactory
import kotlinx.coroutines.launch

/**
 * Full-screen playback of one Telegram file.
 *
 * Owns the ExoPlayer so the leanback fragment and the track pickers can come and go without
 * interrupting the stream.
 */
@UnstableApi
class PlayerActivity : FragmentActivity() {

    var player: ExoPlayer? = null
        private set

    lateinit var mediaTitle: String
        private set
    lateinit var mediaSubtitle: String
        private set

    private var chatId = 0L
    private var messageId = 0L
    private var durationHintSec = 0

    private lateinit var settings: SettingsStore
    private lateinit var subtitleView: SubtitleView
    private lateinit var statusOverlay: View
    private lateinit var statusText: TextView
    private var statusSpinner: View? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_player)

        settings = SettingsStore(this)
        val fileId = intent.getIntExtra(EXTRA_FILE_ID, 0)
        chatId = intent.getLongExtra(EXTRA_CHAT_ID, 0)
        messageId = intent.getLongExtra(EXTRA_MESSAGE_ID, 0)
        durationHintSec = intent.getIntExtra(EXTRA_DURATION, 0)
        mediaTitle = intent.getStringExtra(EXTRA_TITLE).orEmpty()
        mediaSubtitle = intent.getStringExtra(EXTRA_SUBTITLE).orEmpty()

        subtitleView = findViewById(R.id.subtitles)
        statusOverlay = findViewById(R.id.status_overlay)
        statusText = findViewById(R.id.status_text)
        statusSpinner = findViewById(R.id.status_spinner)
        subtitleView.setApplyEmbeddedStyles(true)
        // A TV's default caption size is tuned for broadcast subtitles; movie subs need to be
        // legible from a sofa, with an outline that survives a bright frame behind them.
        subtitleView.setFractionalTextSize(SUBTITLE_TEXT_FRACTION)
        subtitleView.setStyle(
            CaptionStyleCompat(
                Color.WHITE,
                Color.TRANSPARENT,
                Color.TRANSPARENT,
                CaptionStyleCompat.EDGE_TYPE_OUTLINE,
                Color.BLACK,
                null,
            ),
        )

        if (fileId <= 0) {
            showError("This message has no file to play.")
            return
        }

        showStatus("Starting ${mediaTitle.ifBlank { "playback" }}…")

        player = buildPlayer().also { exo ->
            exo.addListener(playerListener)
            exo.setMediaItem(Media3Item.fromUri(tdFileUri(fileId)))
            exo.prepare()
            exo.playWhenReady = true
        }

        // Reading the saved position is a disk hit; do it off the main thread and seek once
        // it lands. Opening the stream takes longer than this ever will.
        lifecycleScope.launch {
            val resumeMs = runCatching { settings.resumePosition(chatId, messageId) }.getOrDefault(0L)
            if (resumeMs > 0) player?.seekTo(resumeMs)
        }

        // Replace unconditionally: after process death the restored fragment came up before
        // the player existed, so it has no glue and has to be rebuilt.
        supportFragmentManager.beginTransaction()
            .replace(R.id.playback_container, TvPlaybackFragment())
            .commit()
    }

    private fun buildPlayer(): ExoPlayer {
        // Hardware decoders first; NextLib's FFmpeg renderers pick up the audio codecs a TV
        // stick has no silicon for — DTS and TrueHD tracks are common in movie remuxes.
        val renderers = NextRenderersFactory(this)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
            .setEnableDecoderFallback(true)

        // Deliberately small: on a 1 GB stick a generous buffer is what gets the app killed.
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(BUFFER_MIN_MS, BUFFER_MAX_MS, BUFFER_PLAYBACK_MS, BUFFER_REBUFFER_MS)
            .setTargetBufferBytes(TARGET_BUFFER_BYTES)
            .setPrioritizeTimeOverSizeThresholds(false)
            .setBackBuffer(0, false)
            .build()

        val trackSelector = DefaultTrackSelector(this).apply {
            parameters = buildUponParameters()
                // Subtitles stay off until asked for; nobody wants forced captions on a film.
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                .setPreferredAudioLanguage(null)
                .build()
        }

        // Constant-bitrate seeking rescues formats that ship without a seek index.
        val extractors = DefaultExtractorsFactory()
            .setConstantBitrateSeekingEnabled(true)
            .setConstantBitrateSeekingAlwaysEnabled(true)

        return ExoPlayer.Builder(this, renderers)
            .setLoadControl(loadControl)
            .setTrackSelector(trackSelector)
            .setMediaSourceFactory(DefaultMediaSourceFactory(TdDataSource.Factory(), extractors))
            .setSeekBackIncrementMs(TvPlayerGlue.SKIP_MS)
            .setSeekForwardIncrementMs(TvPlayerGlue.SKIP_MS)
            .build()
            .apply {
                setAudioAttributes(AudioAttributes.DEFAULT, /* handleAudioFocus = */ true)
                setHandleAudioBecomingNoisy(true)
            }
    }

    private val playerListener = object : Player.Listener {
        override fun onCues(cueGroup: CueGroup) {
            subtitleView.setCues(cueGroup.cues)
        }

        override fun onPlaybackStateChanged(state: Int) {
            when (state) {
                Player.STATE_BUFFERING -> showStatus("Buffering…")
                Player.STATE_READY -> hideStatus()
                Player.STATE_ENDED -> {
                    lifecycleScope.launch { settings.clearResumePosition(chatId, messageId) }
                    finish()
                }
                else -> Unit
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            showError(friendlyError(error))
        }
    }

    private fun friendlyError(error: PlaybackException) = when (error.errorCode) {
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
        ->
            "Lost the connection to Telegram. Check the network and try again."

        PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
        PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
        ->
            "This device can't decode that video track. Audio-only formats are covered, but the video codec isn't."

        PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
        PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED,
        ->
            "TMPlayer can't read this container format."

        else -> error.message ?: "Playback failed."
    }

    /** Skips [deltaMs], clamped so a burst of remote presses can't run off either end. */
    fun skipBy(deltaMs: Long) {
        val exo = player ?: return
        val duration = exo.duration
        val target = (exo.currentPosition + deltaMs).coerceAtLeast(0)
        exo.seekTo(if (duration > 0) target.coerceAtMost(duration - END_GUARD_MS) else target)
    }

    fun showTrackPicker(trackType: Int) {
        GuidedStepSupportFragment.add(
            supportFragmentManager,
            TrackPickerFragment.forType(trackType),
            R.id.overlay_container,
        )
    }

    /**
     * MEDIA keys always seek. D-pad seeks only while the transport row is hidden — once it is
     * up, leanback's own scrubbing owns those keys.
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN) return super.dispatchKeyEvent(event)
        val pickerOpen = GuidedStepSupportFragment.getCurrentGuidedStepSupportFragment(supportFragmentManager) != null
        if (pickerOpen) return super.dispatchKeyEvent(event)

        when (event.keyCode) {
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
                skipBy(TvPlayerGlue.SKIP_MS)
                return true
            }
            KeyEvent.KEYCODE_MEDIA_REWIND -> {
                skipBy(-TvPlayerGlue.SKIP_MS)
                return true
            }
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_LEFT -> {
                val fragment = supportFragmentManager.findFragmentById(R.id.playback_container)
                val controlsUp = (fragment as? TvPlaybackFragment)?.controlsVisible() ?: true
                if (!controlsUp) {
                    val step = if (event.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) NUDGE_MS else -NUDGE_MS
                    skipBy(step)
                    // Fall through so leanback also raises the controls: the user needs to see
                    // where the seek landed.
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun showStatus(message: String) {
        statusOverlay.visibility = View.VISIBLE
        statusSpinner?.visibility = View.VISIBLE
        statusText.text = message
    }

    private fun showError(message: String) {
        statusOverlay.visibility = View.VISIBLE
        statusSpinner?.visibility = View.GONE
        statusText.text = "$message\n\nPress Back to return."
    }

    private fun hideStatus() {
        statusOverlay.visibility = View.GONE
    }

    override fun onStop() {
        super.onStop()
        saveResumePosition()
        player?.pause()
    }

    override fun onDestroy() {
        saveResumePosition()
        player?.removeListener(playerListener)
        player?.release()
        player = null
        // Playing a film is the moment the cache grows, so trim right after it.
        Td.trimCacheInBackground(settings)
        super.onDestroy()
    }

    /**
     * Runs on a scope that outlives the activity — the write has to survive the user pressing
     * Back, which is exactly when it matters.
     */
    private fun saveResumePosition() {
        val exo = player ?: return
        val position = exo.currentPosition
        val duration = exo.duration
        val watched = duration > 0 && position >= duration - SettingsStore.END_MARGIN_MS
        val store = settings
        val chat = chatId
        val message = messageId
        App.backgroundScope.launch {
            runCatching {
                if (watched) store.clearResumePosition(chat, message)
                else store.saveResumePosition(chat, message, position)
            }
        }
    }

    companion object {
        private const val EXTRA_FILE_ID = "file_id"
        private const val EXTRA_CHAT_ID = "chat_id"
        private const val EXTRA_MESSAGE_ID = "message_id"
        private const val EXTRA_TITLE = "title"
        private const val EXTRA_SUBTITLE = "subtitle"
        private const val EXTRA_DURATION = "duration"

        private const val BUFFER_MIN_MS = 15_000
        private const val BUFFER_MAX_MS = 50_000
        private const val BUFFER_PLAYBACK_MS = 2_500
        private const val BUFFER_REBUFFER_MS = 5_000
        private const val TARGET_BUFFER_BYTES = 20 * 1024 * 1024
        private const val END_GUARD_MS = 1_000L
        private const val NUDGE_MS = 10_000L
        private const val SUBTITLE_TEXT_FRACTION = 0.065f

        fun intent(context: Context, item: MediaItem): Intent =
            Intent(context, PlayerActivity::class.java).apply {
                putExtra(EXTRA_FILE_ID, item.fileId)
                putExtra(EXTRA_CHAT_ID, item.chatId)
                putExtra(EXTRA_MESSAGE_ID, item.messageId)
                putExtra(EXTRA_TITLE, item.title)
                putExtra(EXTRA_DURATION, item.durationSec)
                putExtra(
                    EXTRA_SUBTITLE,
                    listOf(
                        MediaMapper.formatDuration(item.durationSec),
                        MediaMapper.formatSize(item.sizeBytes),
                    ).filter { it.isNotEmpty() }.joinToString("  ·  "),
                )
            }
    }
}
