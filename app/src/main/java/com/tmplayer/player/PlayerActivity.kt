package com.tmplayer.player

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.ProgressBar
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
import androidx.media3.ui.PlayerView
import androidx.media3.ui.SubtitleView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.tmplayer.App
import com.tmplayer.R
import com.tmplayer.data.ChatRepository
import com.tmplayer.data.Failures
import com.tmplayer.data.FormFactor
import com.tmplayer.data.MediaName
import com.tmplayer.data.MediaItem
import com.tmplayer.data.MediaMapper
import com.tmplayer.data.LocalFileAvailability
import com.tmplayer.data.LocalFilePolicy
import com.tmplayer.data.NetworkMonitor
import com.tmplayer.data.NetworkStatus
import com.tmplayer.data.ResumeRecord
import com.tmplayer.data.SettingsStore
import com.tmplayer.data.Td
import com.tmplayer.data.errorMessage
import com.tmplayer.data.valueOrNull
import dev.g000sha256.tdl.TdlClient
import dev.g000sha256.tdl.dto.File as TdFile
import io.github.anilbeesetti.nextlib.media3ext.ffdecoder.NextRenderersFactory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

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

    private lateinit var settings: SettingsStore
    private lateinit var subtitleView: SubtitleView
    private lateinit var statusOverlay: View
    private lateinit var statusIcon: ImageView
    private lateinit var statusTitle: TextView
    private lateinit var statusText: TextView
    private lateinit var statusDetail: TextView
    private lateinit var statusProgress: ProgressBar
    private lateinit var rebufferChip: View
    private lateinit var rebufferText: TextView
    private lateinit var downloadChip: TextView
    private var statusSpinner: View? = null

    /** The touch transport row, on a phone. Null on a TV, where leanback's fragment has it. */
    private var touchSurface: PlayerView? = null
    private var gestureHud: TextView? = null
    private val hideGestureHud = Runnable { gestureHud?.visibility = View.GONE }

    private var fileId = 0
    private var fileSizeBytes = 0L
    private var durationSec = 0
    private var resumeMs = 0L

    /** Only used to label the video in "Continue watching"; playback never needs it. */
    private var chatTitle = ""

    /**
     * The episodes either side of this one, once the chat has been asked about them.
     *
     * Empty for a video, and empty for an episode whose neighbours are not in the chat. The
     * transport row watches this and grows its two extra buttons when they arrive.
     */
    private val _episodes = MutableStateFlow(Episodes())
    val episodes: StateFlow<Episodes> = _episodes.asStateFlow()

    /** True until the picture first appears; after that a stall is a chip, not a full sheet. */
    private var openingFilm = true
    private val speed = SpeedMeter()

    /** True while the transport row is up: the download figure is shown alongside it. */
    private var controlsUp = false

    /** How far into the video the download has reached, and whether it has reached the end. */
    private var downloadedFraction = 0f
    private var downloadComplete = false

    /** True while the whole video is being fetched before playback, under the "download first" setting. */
    private var waitingForWholeFilm = false

    /** The stage the loading screen is currently reporting, kept so it can be re-rendered. */
    private var statusMessage = ""

    /** Used only while this video needs more bytes; completed videos ignore connectivity entirely. */
    private var networkOffline = false

    /** Automatic recoveries since playback was last healthy, and the one currently pending. */
    private var recoveryAttempts = 0
    private var recoveryJob: Job? = null

    /**
     * "Resuming from 1:12:40", once the saved position has been read off disk.
     *
     * It arrives from a coroutine part-way through the load, and it is the one thing on the
     * loading screen the viewer cannot work out from anywhere else, so it is carried alongside
     * every later stage message instead of being replaced by one.
     */
    private var resumeNotice: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        // Fragments are not restored. A restored playback fragment comes back before
        // the player is rebuilt, so it would attach with nothing behind it and no transport
        // controls. Starting from a clean fragment manager means the fragment below is always
        // built against a live player, and nothing here is worth restoring anyway: the resume
        // position lives on disk.
        super.onCreate(null)
        setContentView(R.layout.activity_player)
        // Two hours of a video is two hours with no button presses. Without this the TV
        // dims and then sleeps on the viewer.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        settings = SettingsStore(this)
        fileId = intent.getIntExtra(EXTRA_FILE_ID, 0)
        chatId = intent.getLongExtra(EXTRA_CHAT_ID, 0)
        messageId = intent.getLongExtra(EXTRA_MESSAGE_ID, 0)
        mediaTitle = intent.getStringExtra(EXTRA_TITLE).orEmpty()
        mediaSubtitle = intent.getStringExtra(EXTRA_SUBTITLE).orEmpty()
        chatTitle = intent.getStringExtra(EXTRA_CHAT_TITLE).orEmpty()
        fileSizeBytes = intent.getLongExtra(EXTRA_SIZE, 0)
        durationSec = intent.getIntExtra(EXTRA_DURATION, 0)

        subtitleView = findViewById(R.id.subtitles)
        statusOverlay = findViewById(R.id.status_overlay)
        statusIcon = findViewById(R.id.status_icon)
        statusTitle = findViewById(R.id.status_title)
        statusText = findViewById(R.id.status_text)
        statusDetail = findViewById(R.id.status_detail)
        statusProgress = findViewById(R.id.status_progress)
        statusSpinner = findViewById(R.id.status_spinner)
        rebufferChip = findViewById(R.id.rebuffer_chip)
        rebufferText = findViewById(R.id.rebuffer_text)
        downloadChip = findViewById(R.id.download_chip)
        gestureHud = findViewById(R.id.gesture_hud)
        subtitleView.setApplyEmbeddedStyles(true)
        // A TV's default caption size is tuned for broadcast subtitles; video subs need to be
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
            showError("There's nothing to play here.")
            return
        }

        statusTitle.text = mediaTitle.ifBlank { "Opening…" }
        showStatus("Connecting to Telegram…")

        observeDownload()
        observeConnectivity()
        startResumeHeartbeat()
        findEpisodes()

        // Reading the saved position is a disk hit; do it off the main thread and seek once
        // it lands. Opening the stream takes longer than this ever will. Under "download the
        // whole video first" there is no player to seek yet, so [startPlayback] applies the same
        // position as well; whichever of the two arrives second simply sets it again.
        lifecycleScope.launch {
            resumeMs = runCatching { settings.resumePosition(chatId, messageId) }.getOrDefault(0L)
            if (resumeMs > 0) {
                player?.seekTo(resumeMs)
                resumeNotice = "Resuming from ${StreamStats.formatClock(resumeMs)}"
                renderStatusText()
            }
        }

        lifecycleScope.launch {
            val session = Td.awaitAuthorizedSession()
            val availability = Td.localFileAvailability(fileId)
            if (
                availability != LocalFileAvailability.Complete &&
                NetworkMonitor.status.value == NetworkStatus.Offline &&
                !Td.connected.value
            ) {
                showError("This video isn't fully on this TV. Connect to the internet and try again.")
                return@launch
            }
            val downloadFirst = runCatching { settings.downloadBeforePlayingNow() }
                .getOrDefault(false)
            if (downloadFirst && !fetchWholeFilm()) return@launch
            if (!session.isCurrent()) return@launch
            startPlayback(session.client)
        }
    }

    /** Builds the player, points it at the file, and puts a video surface in front of it. */
    private fun startPlayback(client: TdlClient) {
        val exo = buildPlayer(client).also { built ->
            built.addListener(playerListener)
            built.setMediaItem(Media3Item.fromUri(tdFileUri(fileId)))
            if (resumeMs > 0) built.seekTo(resumeMs)
            built.prepare()
            built.playWhenReady = true
        }
        player = exo

        // Which surface depends only on the hardware. A remote drives leanback's transport row and
        // nothing else, a thumb drives Media3's and nothing else, and neither works on the other.
        if (FormFactor.isTv(this)) {
            // Replace unconditionally: after process death the restored fragment came up before
            // the player existed, so it has no glue and has to be rebuilt. State loss is allowed
            // because nothing here is restored anyway, and under "download the whole video first"
            // this can land after the activity has been stopped.
            supportFragmentManager.beginTransaction()
                .replace(R.id.playback_container, TvPlaybackFragment())
                .commitAllowingStateLoss()
        } else {
            attachTouchSurface(exo)
        }
    }

    /**
     * The phone's video surface and transport row, in place of leanback's.
     *
     * Leanback's playback fragment is built around a D-pad: its transport row is raised by a key
     * press and scrubbed by a key press, and there is no touch anywhere in it. On a phone that
     * leaves a picture nobody can pause, which is the whole of why playback on a phone read as
     * nothing happening at all. Media3's own view is the same player behind a control row made for
     * a thumb, so the surface is the only thing being swapped: the activity still owns the
     * ExoPlayer, and the loading sheet, the chips, the resume writes and the retry logic sit over
     * this exactly as they sit over the TV.
     */
    private fun attachTouchSurface(exo: ExoPlayer) {
        val view = PlayerView(this)
        view.layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT,
        )
        view.useController = true
        // Two spinners for one wait. TMPlayer's own loading sheet and rebuffer chip already say
        // what is happening, and they say it with a speed and a percentage.
        view.setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER)
        // The activity draws subtitles itself, into a view sized and styled for this app, and it
        // is fed straight off onCues. Leaving Media3's own subtitle view up renders every line
        // twice, slightly offset.
        view.subtitleView?.visibility = View.GONE
        view.setControllerVisibilityListener(
            PlayerView.ControllerVisibilityListener { visibility ->
                onControlsVisibilityChanged(visibility == View.VISIBLE)
            },
        )
        view.player = exo
        findViewById<FrameLayout>(R.id.playback_container).addView(view)
        touchSurface = view

        PlayerGestures(
            context = this,
            window = window,
            onSkip = ::skipBy,
            onFeedback = ::showGestureFeedback,
        ).attach(view)
    }

    /** A figure for whatever a gesture is changing, gone again shortly after the finger lifts. */
    private fun showGestureFeedback(text: String) {
        val hud = gestureHud ?: return
        hud.text = text
        hud.visibility = View.VISIBLE
        hud.removeCallbacks(hideGestureHud)
        hud.postDelayed(hideGestureHud, GESTURE_HUD_MS)
    }

    /**
     * Fetches the whole file before playback starts, for viewers who have asked for that.
     *
     * TDLib's own synchronous download does the waiting; the figures on the loading screen come
     * from the same file updates the streaming path already collects. Returns false when the
     * download failed, in which case the error is on screen and there is nothing to start.
     */
    private suspend fun fetchWholeFilm(): Boolean {
        if (Td.localFileAvailability(fileId) == LocalFileAvailability.Complete) {
            showStatus("Starting the video…")
            return true
        }
        waitingForWholeFilm = true
        showStatus("Downloading the whole video…")
        val session = Td.awaitConnectedSession()
        val result = session.client.downloadFile(
            fileId = fileId,
            priority = DOWNLOAD_PRIORITY,
            offset = 0,
            limit = 0,
            synchronous = true,
        )
        waitingForWholeFilm = false
        val error = result.errorMessage
        if (error != null) {
            showError(Failures.humanise(error))
            return false
        }
        if (Td.localFileAvailability(fileId) != LocalFileAvailability.Complete) {
            showError("The download did not finish. Connect to the internet and try again.")
            return false
        }
        showStatus("Starting the video…")
        return true
    }

    private fun buildPlayer(client: TdlClient): ExoPlayer {
        // Hardware decoders first; NextLib's FFmpeg renderers pick up the audio codecs a TV
        // stick has no silicon for: DTS and TrueHD tracks are common in video remuxes.
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
                // Subtitles stay off until asked for; nobody wants forced captions on a video.
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
            .setMediaSourceFactory(DefaultMediaSourceFactory(TdDataSource.Factory(client), extractors))
            .setSeekBackIncrementMs(TvPlayerGlue.SKIP_MS)
            .setSeekForwardIncrementMs(TvPlayerGlue.SKIP_MS)
            .build()
            .apply {
                setAudioAttributes(AudioAttributes.DEFAULT, /* handleAudioFocus = */ true)
                setHandleAudioBecomingNoisy(true)
                setWakeMode(C.WAKE_MODE_LOCAL)
            }
    }

    private val playerListener = object : Player.Listener {
        override fun onCues(cueGroup: CueGroup) {
            subtitleView.setCues(cueGroup.cues)
        }

        override fun onPlaybackStateChanged(state: Int) {
            when (state) {
                // Only the very first wait earns the full screen; later stalls get the chip.
                Player.STATE_BUFFERING ->
                    if (openingFilm) showStatus("Loading…") else showRebuffering()

                // Playing again is the only proof that a recovery worked, so the budget is
                // refilled here rather than when the retry is issued.
                Player.STATE_READY -> {
                    recoveryAttempts = 0
                    hideStatus()
                }
                Player.STATE_ENDED -> {
                    lifecycleScope.launch { settings.clearResumePosition(chatId, messageId) }
                    finish()
                }
                else -> Unit
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            if (!recoverFrom(error)) showError(friendlyError(error))
        }
    }

    /**
     * Puts a stream back on its feet after a recoverable failure, instead of ending the session.
     *
     * A streamed file fails in ways a local one does not: the connection drops for a moment, or a
     * read lands on bytes Telegram has since moved away from. None of that means the video is
     * unplayable, but the player has no way to know that, so before this the first such error was
     * the end of the film and the only way out was Back. Returns false when the error is one no
     * amount of retrying will fix, and the error sheet is the honest answer.
     */
    private fun recoverFrom(error: PlaybackException): Boolean {
        val exo = player ?: return false
        if (!isRecoverable(error)) return false
        if (recoveryAttempts >= MAX_RECOVERY_ATTEMPTS) return false

        val resumeAt = exo.currentPosition.coerceAtLeast(0)
        recoveryJob?.cancel()
        recoveryJob = lifecycleScope.launch {
            showRebuffering()
            // Being offline is a wait, not a failure, so it never spends the retry budget: on a
            // phone leaving the house that budget would be gone long before the signal came back.
            if (networkOffline) {
                combine(NetworkMonitor.status, Td.connected) { network, connected ->
                    network != NetworkStatus.Offline || connected
                }.first { it }
            } else {
                val attempt = ++recoveryAttempts
                delay(RECOVERY_BACKOFF_MS shl (attempt - 1))
            }
            val live = player ?: return@launch
            live.seekTo(resumeAt)
            live.prepare()
            live.playWhenReady = true
        }
        return true
    }

    /**
     * Whether retrying is worth anything.
     *
     * A malformed container counts, which reads oddly until you remember these bytes arrive over
     * a moving download window: a container that will not parse is far more often a read that
     * caught the file mid-move than a genuinely broken remux, and re-preparing settles it. A
     * codec this TV does not have is the opposite, and no number of attempts will conjure one.
     */
    private fun isRecoverable(error: PlaybackException): Boolean = when (error.errorCode) {
        PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
        PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
        PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE,
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
        -> true

        else -> false
    }

    private fun friendlyError(error: PlaybackException) = when (error.errorCode) {
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
        ->
            "Lost the connection to Telegram. Check this TV's internet and try again."

        PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
        PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
        ->
            "This TV can't play this video's format. A different copy may work."

        PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
        PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED,
        ->
            "TMPlayer can't play this file. A different copy may work."

        // ExoPlayer's own message names a codec or an internal class; it means nothing on a sofa
        // and reads as a crash. The exception still reaches logcat through ExoPlayer itself.
        else -> "This video wouldn't play. Try a different copy of it."
    }

    /**
     * Asks the chat what comes before and after this episode.
     *
     * Off the critical path on purpose: it costs a search, and the video starts without it. The
     * search is narrowed to the series name, which is what Telegram matches document file names
     * against, and falls back to the plain listing for a chat that names its files some other way.
     */
    private fun findEpisodes() {
        val here = MediaName.parse(mediaTitle)
        if (!here.isEpisode || chatId == 0L) return

        lifecycleScope.launch {
            val session = Td.awaitAuthorizedSession()
            val candidates = runCatching {
                val repository = ChatRepository(session.client)
                val narrowed = repository.mediaPage(chatId, query = here.title).items
                narrowed.ifEmpty { repository.mediaPage(chatId).items }
            }.getOrNull().orEmpty()
            if (candidates.isEmpty()) return@launch

            fun nameOf(item: MediaItem) = item.fileName.ifBlank { item.title }
            _episodes.value = Episodes(
                previous = MediaName.previousEpisode(mediaTitle, candidates, ::nameOf),
                next = MediaName.nextEpisode(mediaTitle, candidates, ::nameOf),
            )
        }
    }

    /**
     * Switches to another episode of the same series.
     *
     * Done by starting the activity again rather than by swapping the file under the player:
     * every one of opening the stream, the resume position, the download meter and the title is
     * set up in [onCreate] against one file, and a second path through all of that is a second
     * place for it to go wrong. The position in the episode being left is saved on the way out,
     * so backing up to it later carries on where it stopped.
     */
    fun playEpisode(item: MediaItem) {
        startActivity(intent(this, item, chatTitle))
        finish()
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
     * MEDIA keys always seek. D-pad seeks only while the transport row is hidden; once it is
     * up, leanback's own scrubbing owns those keys.
     */
    @SuppressLint("RestrictedApi")
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
                // Only asked of leanback, and only when leanback is what is on screen. A phone
                // has no such fragment, and its transport row handles the arrows of an attached
                // keyboard itself, so there is nothing here to route.
                val fragment = supportFragmentManager
                    .findFragmentById(R.id.playback_container) as? TvPlaybackFragment
                if (fragment != null && !fragment.controlsVisible()) {
                    val step = if (event.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) NUDGE_MS else -NUDGE_MS
                    skipBy(step)
                    // Fall through so leanback also raises the controls: the user needs to see
                    // where the seek landed.
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    /**
     * Feeds the loading screen from TDLib's own download updates.
     *
     * The player alone cannot say "how much longer"; it only knows whether it has enough to
     * start. Pairing its buffer with the byte rate coming off TDLib is what turns a spinner into
     * a figure the viewer can act on.
     */
    private fun observeDownload() {
        lifecycleScope.launch {
            val session = Td.awaitAuthorizedSession()
            session.client.getFile(fileId).valueOrNull?.let(::applyDownloadState)
            session.client.fileUpdates
                .filter { it.file.id == fileId }
                .collect { update -> applyDownloadState(update.file) }
        }
    }

    /** Applies both the initial file snapshot and later TDLib updates to the same meter state. */
    private fun applyDownloadState(file: TdFile) {
        if (fileSizeBytes <= 0 && file.size > 0) fileSizeBytes = file.size
        val local = file.local
        val diskFile = local.path.takeIf { it.isNotBlank() }?.let(::File)
        downloadComplete = LocalFilePolicy.evaluate(
            downloadCompleted = local.isDownloadingCompleted,
            pathPresent = diskFile != null,
            regularFile = diskFile?.isFile == true,
            length = diskFile?.length() ?: 0,
            size = file.size,
            expectedSize = file.expectedSize,
        ) == LocalFileAvailability.Complete
        downloadedFraction = StreamStats.downloadedFraction(
            downloadOffset = local.downloadOffset,
            downloadedPrefixSize = local.downloadedPrefixSize,
            size = fileSizeBytes,
            completed = downloadComplete,
        )
        // Sampled on the prefix rather than the window's far end: a seek restarts the
        // prefix at zero, and the meter reads that drop as the reset it is, where a
        // window that jumped forwards would look like a burst of speed.
        speed.sample(local.downloadedPrefixSize, SystemClock.elapsedRealtime())
        renderProgress()
    }

    /** Keeps a stream's own loading UI honest while leaving completed local playback untouched. */
    private fun observeConnectivity() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                combine(NetworkMonitor.status, Td.connected) { network, connected ->
                    network == NetworkStatus.Offline && !connected
                }.collect { offline ->
                    val reconnected = networkOffline && !offline
                    networkOffline = offline
                    if (downloadComplete) return@collect

                    if (offline) {
                        if (openingFilm) {
                            showStatus("Offline. Waiting for internet…")
                            statusDetail.text = "A fully downloaded video can play without internet."
                        } else if (player?.playbackState == Player.STATE_BUFFERING) {
                            showRebuffering()
                        }
                    } else if (reconnected && player?.playbackState == Player.STATE_BUFFERING) {
                        if (openingFilm) {
                            showStatus("Back online. Resuming…")
                        } else {
                            rebufferText.text = "Back online. Resuming…"
                        }
                    }
                }
            }
        }
    }

    private fun renderProgress() {
        val rate = StreamStats.formatSpeed(speed.bytesPerSec)

        // Waiting for the whole video: the bar is the video, not the buffer, and the wait is long
        // enough that a percentage and a time left are the only things making it bearable.
        if (waitingForWholeFilm) {
            val remaining = (fileSizeBytes - (fileSizeBytes * downloadedFraction).toLong())
                .coerceAtLeast(0)
            val left = StreamStats.formatEta(
                StreamStats.secondsForBytes(remaining, speed.bytesPerSec),
            )
            statusProgress.progress = (downloadedFraction * 1000).toInt()
            statusDetail.text = listOf(
                "${StreamStats.formatPercent(downloadedFraction)} downloaded",
                rate,
                left,
            ).filter { it.isNotBlank() }.joinToString("  ·  ")
            return
        }

        val exo = player ?: return
        val aheadMs = (exo.bufferedPosition - exo.currentPosition).coerceAtLeast(0)
        val fraction = StreamStats.progress(aheadMs, BUFFER_PLAYBACK_MS.toLong())
        val bytesPerMs = StreamStats.bytesPerMs(fileSizeBytes, durationSec)
        val eta = StreamStats.etaSeconds(
            bufferedAheadMs = aheadMs,
            requiredMs = BUFFER_PLAYBACK_MS.toLong(),
            bytesPerMs = bytesPerMs,
            speedBytesPerSec = speed.bytesPerSec,
        )

        val left = StreamStats.formatEta(eta)

        if (openingFilm) {
            statusProgress.progress = (fraction * 1000).toInt()
            statusDetail.text = listOf(rate, left).filter { it.isNotBlank() }.joinToString("  ·  ")
        } else {
            rebufferText.text = "Loading  ·  $rate"
        }
        updateDownloadChip()
    }

    /** Leanback raising or hiding the transport row; the download figure rides with it. */
    fun onControlsVisibilityChanged(visible: Boolean) {
        controlsUp = visible
        updateDownloadChip()
    }

    /**
     * The corner figure: how much of the video is down, shown only alongside the transport row.
     *
     * It gives way to the rebuffering chip, which occupies the same corner and is the more urgent
     * of the two, and it stays off entirely while a full-screen status sheet is up.
     */
    private fun updateDownloadChip() {
        val show = controlsUp &&
            statusOverlay.visibility != View.VISIBLE &&
            rebufferChip.visibility != View.VISIBLE &&
            (fileSizeBytes > 0 || downloadComplete)
        downloadChip.visibility = if (show) View.VISIBLE else View.GONE
        if (!show) return

        downloadChip.text = when {
            downloadComplete -> "Download completed"
            speed.bytesPerSec >= StreamStats.MIN_MEANINGFUL_SPEED ->
                "${StreamStats.formatPercent(downloadedFraction)} downloaded  ·  " +
                    StreamStats.formatSpeed(speed.bytesPerSec)
            else -> "${StreamStats.formatPercent(downloadedFraction)} downloaded"
        }
    }

    private fun showStatus(message: String) {
        openingFilm = true
        statusOverlay.visibility = View.VISIBLE
        statusIcon.visibility = View.GONE
        rebufferChip.visibility = View.GONE
        statusSpinner?.visibility = View.VISIBLE
        statusProgress.visibility = View.VISIBLE
        statusDetail.visibility = View.VISIBLE
        statusMessage = message
        renderStatusText()
        updateDownloadChip()
    }

    /**
     * Writes the caption, stage first, with the resume notice carried alongside it rather than
     * replaced by it.
     */
    private fun renderStatusText() {
        statusText.text = listOfNotNull(statusMessage.takeIf { it.isNotBlank() }, resumeNotice)
            .joinToString("  ·  ")
    }

    /** A stall after playback has begun: a small chip, so the video stays on screen. */
    private fun showRebuffering() {
        openingFilm = false
        statusOverlay.visibility = View.GONE
        rebufferChip.visibility = View.VISIBLE
        rebufferText.text = if (networkOffline && !downloadComplete) {
            "Offline. Waiting for internet…"
        } else {
            "Loading…"
        }
        updateDownloadChip()
    }

    private fun showError(message: String) {
        openingFilm = false
        statusOverlay.visibility = View.VISIBLE
        // The only state that earns the warning triangle, matching the error screens the rest of
        // the app already shows.
        statusIcon.visibility = View.VISIBLE
        rebufferChip.visibility = View.GONE
        statusSpinner?.visibility = View.GONE
        statusProgress.visibility = View.GONE
        statusDetail.visibility = View.GONE
        statusTitle.text = "Can't play this"
        statusText.text = "$message\n\nPress Back to pick something else."
        updateDownloadChip()
    }

    private fun hideStatus() {
        openingFilm = false
        statusOverlay.visibility = View.GONE
        rebufferChip.visibility = View.GONE
        updateDownloadChip()
    }

    /**
     * Writes the position every [RESUME_TICK_MS] while a video is on screen.
     *
     * onStop covers Back and Home, but it never runs when the power goes off at the wall or the
     * system kills the process to reclaim memory, and on a 1 GB stick the second of those is
     * routine. Without a heartbeat those are precisely the cases where an hour of a video is lost.
     */
    private fun startResumeHeartbeat() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                while (true) {
                    delay(RESUME_TICK_MS)
                    if (player?.isPlaying == true) saveResumePosition()
                }
            }
        }
    }

    override fun onStop() {
        super.onStop()
        saveResumePosition()
        player?.pause()
    }

    override fun onDestroy() {
        saveResumePosition()
        gestureHud?.removeCallbacks(hideGestureHud)
        // Dropped before the player is released so the view never holds a released instance.
        touchSurface?.player = null
        touchSurface = null
        player?.removeListener(playerListener)
        player?.release()
        player = null
        super.onDestroy()
    }

    /**
     * Runs on a scope that outlives the activity, because the write has to survive the user
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
        // Written with the position so "Continue watching" can offer the video back without the
        // chat it came from being loaded, or still being in the list at all.
        val description = ResumeRecord.encode(
            fileId = fileId,
            title = mediaTitle,
            chatTitle = chatTitle,
            sizeBytes = fileSizeBytes,
            durationSec = durationSec,
            updatedAt = System.currentTimeMillis(),
        )
        App.backgroundScope.launch {
            runCatching {
                if (watched) store.clearResumePosition(chat, message)
                else store.saveResumePosition(chat, message, position, duration, description)
            }
        }
    }

    /** What the transport row offers either side of the video: nulls where there is nothing. */
    data class Episodes(val previous: MediaItem? = null, val next: MediaItem? = null)

    companion object {
        private const val EXTRA_FILE_ID = "file_id"
        private const val EXTRA_CHAT_ID = "chat_id"
        private const val EXTRA_MESSAGE_ID = "message_id"
        private const val EXTRA_TITLE = "title"
        private const val EXTRA_SUBTITLE = "subtitle"
        private const val EXTRA_SIZE = "size"
        private const val EXTRA_DURATION = "duration"
        private const val EXTRA_CHAT_TITLE = "chat_title"

        private const val BUFFER_MIN_MS = 15_000
        private const val BUFFER_MAX_MS = 50_000
        private const val BUFFER_PLAYBACK_MS = 2_500
        private const val BUFFER_REBUFFER_MS = 5_000
        private const val TARGET_BUFFER_BYTES = 20 * 1024 * 1024
        private const val END_GUARD_MS = 1_000L
        private const val NUDGE_MS = 10_000L
        private const val RESUME_TICK_MS = 10_000L

        /** Long enough to read the figure a drag left behind, short enough to stay out of the way. */
        private const val GESTURE_HUD_MS = 900L

        /**
         * Four attempts over roughly twelve seconds. Enough to ride out a moved download window or
         * a lift lost signal, short enough that a video which really will not play says so.
         */
        private const val MAX_RECOVERY_ATTEMPTS = 4
        private const val RECOVERY_BACKOFF_MS = 800L

        /** 1..32; the same top slot the streaming path asks for. */
        private const val DOWNLOAD_PRIORITY = 32
        private const val SUBTITLE_TEXT_FRACTION = 0.065f

        fun intent(context: Context, item: MediaItem, chatTitle: String = ""): Intent =
            Intent(context, PlayerActivity::class.java).apply {
                putExtra(EXTRA_FILE_ID, item.fileId)
                putExtra(EXTRA_SIZE, item.sizeBytes)
                putExtra(EXTRA_DURATION, item.durationSec)
                putExtra(EXTRA_CHAT_ID, item.chatId)
                putExtra(EXTRA_MESSAGE_ID, item.messageId)
                putExtra(EXTRA_TITLE, item.title)
                putExtra(EXTRA_CHAT_TITLE, chatTitle)
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
