package com.tmplayer.player

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
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
import androidx.media3.ui.SubtitleView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.tmplayer.App
import com.tmplayer.R
import com.tmplayer.data.ChatRepository
import com.tmplayer.data.Failures
import com.tmplayer.data.FilmLookup
import com.tmplayer.data.FilmName
import com.tmplayer.data.MediaItem
import com.tmplayer.data.MediaMapper
import com.tmplayer.data.RemoteImages
import com.tmplayer.data.ResumeRecord
import com.tmplayer.data.SettingsStore
import com.tmplayer.data.Td
import com.tmplayer.data.Tmdb
import com.tmplayer.data.errorMessage
import io.github.anilbeesetti.nextlib.media3ext.ffdecoder.NextRenderersFactory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.delay
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

    private var fileId = 0
    private var fileSizeBytes = 0L
    private var durationSec = 0
    private var resumeMs = 0L

    /** Only used to label the film in "Continue watching"; playback never needs it. */
    private var chatTitle = ""

    /**
     * The episodes either side of this one, once the chat has been asked about them.
     *
     * Empty for a film, and empty for an episode whose neighbours are not in the chat. The
     * transport row watches this and grows its two extra buttons when they arrive.
     */
    private val _episodes = MutableStateFlow(Episodes())
    val episodes: StateFlow<Episodes> = _episodes.asStateFlow()

    /** The poster for whatever is playing, for the thumbnail beside the title. Null until found. */
    private val _art = MutableStateFlow<Bitmap?>(null)
    val art: StateFlow<Bitmap?> = _art.asStateFlow()

    /** True until the picture first appears; after that a stall is a chip, not a full sheet. */
    private var openingFilm = true
    private val speed = SpeedMeter()

    /** True while the transport row is up: the download figure is shown alongside it. */
    private var controlsUp = false

    /** How far into the film the download has reached, and whether it has reached the end. */
    private var downloadedFraction = 0f
    private var downloadComplete = false

    /** True while the whole film is being fetched before playback, under the "download first" setting. */
    private var waitingForWholeFilm = false

    /** The stage the loading screen is currently reporting, kept so it can be re-rendered. */
    private var statusMessage = ""

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
        // Two hours of a film is two hours with no button presses. Without this the TV
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
            showError("There's nothing to play here.")
            return
        }

        statusTitle.text = mediaTitle.ifBlank { "Opening…" }
        showStatus("Connecting to Telegram…")

        observeDownload()
        startResumeHeartbeat()
        findEpisodes()
        findArt()

        // Reading the saved position is a disk hit; do it off the main thread and seek once
        // it lands. Opening the stream takes longer than this ever will. Under "download the
        // whole film first" there is no player to seek yet, so [startPlayback] applies the same
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
            val downloadFirst = runCatching { settings.downloadBeforePlayingNow() }
                .getOrDefault(false)
            if (downloadFirst && !fetchWholeFilm()) return@launch
            startPlayback()
        }
    }

    /** Builds the player, points it at the file, and puts the leanback surface in front of it. */
    private fun startPlayback() {
        player = buildPlayer().also { exo ->
            exo.addListener(playerListener)
            exo.setMediaItem(Media3Item.fromUri(tdFileUri(fileId)))
            if (resumeMs > 0) exo.seekTo(resumeMs)
            exo.prepare()
            exo.playWhenReady = true
        }

        // Replace unconditionally: after process death the restored fragment came up before
        // the player existed, so it has no glue and has to be rebuilt. State loss is allowed
        // because nothing here is restored anyway, and under "download the whole film first"
        // this can land after the activity has been stopped.
        supportFragmentManager.beginTransaction()
            .replace(R.id.playback_container, TvPlaybackFragment())
            .commitAllowingStateLoss()
    }

    /**
     * Fetches the whole file before playback starts, for viewers who have asked for that.
     *
     * TDLib's own synchronous download does the waiting; the figures on the loading screen come
     * from the same file updates the streaming path already collects. Returns false when the
     * download failed, in which case the error is on screen and there is nothing to start.
     */
    private suspend fun fetchWholeFilm(): Boolean {
        waitingForWholeFilm = true
        showStatus("Downloading the whole film…")
        val result = Td.client.downloadFile(
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
        showStatus("Starting the film…")
        return true
    }

    private fun buildPlayer(): ExoPlayer {
        // Hardware decoders first; NextLib's FFmpeg renderers pick up the audio codecs a TV
        // stick has no silicon for: DTS and TrueHD tracks are common in movie remuxes.
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
            "Lost the connection to Telegram. Check this TV's internet and try again."

        PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
        PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
        ->
            "This TV can't play this film's video format. A different copy of the film may work."

        PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
        PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED,
        ->
            "TMPlayer can't play this file. A different copy of the film may work."

        // ExoPlayer's own message names a codec or an internal class; it means nothing on a sofa
        // and reads as a crash. The exception still reaches logcat through ExoPlayer itself.
        else -> "This film wouldn't play. Try a different copy of it."
    }

    /**
     * Asks the chat what comes before and after this episode.
     *
     * Off the critical path on purpose: it costs a search, and the film starts without it. The
     * search is narrowed to the series name, which is what Telegram matches document file names
     * against, and falls back to the plain listing for a chat that names its files some other way.
     */
    private fun findEpisodes() {
        val here = FilmName.parse(mediaTitle)
        if (!here.isEpisode || chatId == 0L) return

        lifecycleScope.launch {
            val candidates = runCatching {
                // Built inside the guard: it reaches for the TDLib client, which is not there at
                // all if the process was killed behind the player and is coming back up.
                val repository = ChatRepository()
                val narrowed = repository.mediaPage(chatId, query = here.title).items
                narrowed.ifEmpty { repository.mediaPage(chatId).items }
            }.getOrNull().orEmpty()
            if (candidates.isEmpty()) return@launch

            fun nameOf(item: MediaItem) = item.fileName.ifBlank { item.title }
            _episodes.value = Episodes(
                previous = FilmName.previousEpisode(mediaTitle, candidates, ::nameOf),
                next = FilmName.nextEpisode(mediaTitle, candidates, ::nameOf),
            )
        }
    }

    /**
     * The poster, for the thumbnail leanback draws beside the title on the transport row.
     *
     * Almost always a cache hit: the details panel looked the same film up to draw the screen the
     * viewer pressed Play on, and both the answer and the image are kept on disk.
     */
    private fun findArt() {
        lifecycleScope.launch {
            val details = (Tmdb.lookup(mediaTitle) as? FilmLookup.Found)?.details ?: return@launch
            _art.value = RemoteImages.load(details.posterUrl)
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

    /**
     * Feeds the loading screen from TDLib's own download updates.
     *
     * The player alone cannot say "how much longer"; it only knows whether it has enough to
     * start. Pairing its buffer with the byte rate coming off TDLib is what turns a spinner into
     * a figure the viewer can act on.
     */
    private fun observeDownload() {
        lifecycleScope.launch {
            Td.client.fileUpdates
                .filter { it.file.id == fileId }
                .collect { update ->
                    val file = update.file
                    if (fileSizeBytes <= 0 && file.size > 0) fileSizeBytes = file.size
                    val local = file.local
                    downloadComplete = local.isDownloadingCompleted
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
        }
    }

    private fun renderProgress() {
        val rate = StreamStats.formatSpeed(speed.bytesPerSec)

        // Waiting for the whole film: the bar is the film, not the buffer, and the wait is long
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
     * The corner figure: how much of the film is down, shown only alongside the transport row.
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

    /** A stall after playback has begun: a small chip, so the film stays on screen. */
    private fun showRebuffering() {
        openingFilm = false
        statusOverlay.visibility = View.GONE
        rebufferChip.visibility = View.VISIBLE
        rebufferText.text = "Loading…"
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
     * Writes the position every [RESUME_TICK_MS] while a film is on screen.
     *
     * onStop covers Back and Home, but it never runs when the power goes off at the wall or the
     * system kills the process to reclaim memory, and on a 1 GB stick the second of those is
     * routine. Without a heartbeat those are precisely the cases where an hour of a film is lost.
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
        // Written with the position so "Continue watching" can offer the film back without the
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

    /** What the transport row offers either side of the film: nulls where there is nothing. */
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
