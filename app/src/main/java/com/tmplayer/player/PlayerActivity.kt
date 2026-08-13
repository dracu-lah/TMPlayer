package com.tmplayer.player

import android.annotation.SuppressLint
import android.app.PictureInPictureParams
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.os.Bundle
import android.os.SystemClock
import android.util.Rational
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePadding
import androidx.fragment.app.FragmentActivity
import androidx.leanback.app.GuidedStepSupportFragment
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem as Media3Item
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.text.CueGroup
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.ChannelMixingAudioProcessor
import androidx.media3.common.audio.ChannelMixingMatrix
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.session.MediaSession
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.DefaultTimeBar
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
import com.tmplayer.data.MeteredDecision
import com.tmplayer.data.MeteredPolicy
import com.tmplayer.data.LocalFilePolicy
import com.tmplayer.data.NetworkMonitor
import com.tmplayer.data.NetworkStatus
import com.tmplayer.data.ResumeRecord
import com.tmplayer.data.SettingsStore
import com.tmplayer.data.TrackChoice
import com.tmplayer.data.OfflineDownloads
import com.tmplayer.data.Td
import com.tmplayer.data.Thumbnails
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
import kotlinx.coroutines.CompletableDeferred
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

    /** The loading screen's artwork: the blurred backdrop, its scrim, and the sharp poster. */
    private var statusArt: ImageView? = null
    private var statusScrim: View? = null
    private var statusPoster: ImageView? = null
    private var statusMeta: TextView? = null
    private var artDrift: android.animation.ObjectAnimator? = null
    private var statusRetry: android.widget.Button? = null

    /**
     * The failure sheet's other two ways forward: throw the local copy away and stream it again,
     * and leave for an app whose decoders are not this one's.
     */
    private var statusReload: android.widget.Button? = null
    private var statusOpenWith: android.widget.Button? = null

    /** The failure sheet's way out, and the video's own name on it. */
    private var statusBack: android.widget.Button? = null
    private var statusName: TextView? = null

    /** Set while [reloadFromScratch] is clearing the file, so a second press cannot race it. */
    private var reloading = false

    /** The phone's episode buttons; null on a TV, where the transport row grows its own. */
    private var episodeRow: View? = null
    private var previousEpisodeButton: android.widget.Button? = null
    private var nextEpisodeButton: android.widget.Button? = null

    /** The phone's picture shape and speed buttons, and the row holding them. Null on a TV. */
    private var pictureRow: View? = null
    private var scaleButton: android.widget.Button? = null
    private var speedButton: android.widget.Button? = null
    private var rotationButton: ImageView? = null

    /**
     * Which way up the picture is held, and whether the viewer has said so themselves.
     *
     * The flag is what keeps the two orientation rules from fighting: a portrait video is allowed
     * to turn the window on its own, but only until somebody presses the button, after which the
     * choice is theirs and the decoder does not get a vote.
     */
    private var orientation = lastOrientation
    private var orientationChosen = false

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

    /**
     * The transport controls the system draws: notification, lock screen, headset and Assistant.
     *
     * Held for the life of the activity because it is the activity that owns the player. There is
     * no service behind it on purpose: playback cannot outlive this screen anyway, and a service
     * would only be a second lifetime to keep in step with this one.
     */
    private var mediaSession: MediaSession? = null

    /** How the picture is fitted to the screen, and the speed it plays at. Both are remembered. */
    private var videoScale = VideoScale.Fit
    private var playbackSpeed = PlaybackSpeed.DEFAULT

    /**
     * The soundtrack and subtitles this series was last watched with, read before the player is
     * built and written back whenever the viewer changes either.
     */
    private var tracks = TrackChoice()

    /** What the track choice is filed under: the series name, or this video's own. */
    private val seriesKey: String
        get() = MediaName.parse(mediaTitle).title.ifBlank { mediaTitle }

    /** The picture's own shape, once the decoder has reported it. Zero until then. */
    private var videoWidth = 0
    private var videoHeight = 0

    /** True while the activity is a thumbnail in the corner of somebody else's screen. */
    private var inPictureInPicture = false

    private var gestures: PlayerGestures? = null

    /** True while the transport row is up: the download figure is shown alongside it. */
    private var controlsUp = false

    /** How far into the video the download has reached, and whether it has reached the end. */
    private var downloadedFraction = 0f
    private var downloadComplete = false

    /** When the loading sheet was last redrawn, so [applyDownloadState] can throttle itself. */
    private var lastProgressRender = 0L

    /** True while the whole video is being fetched before playback, under the "download first" setting. */
    private var waitingForWholeFilm = false

    /** The stage the loading screen is currently reporting, kept so it can be re-rendered. */
    private var statusMessage = ""

    /** Used only while this video needs more bytes; completed videos ignore connectivity entirely. */
    private var networkOffline = false

    /** Automatic recoveries since playback was last healthy, and the one currently pending. */
    private var recoveryAttempts = 0
    private var recoveryJob: Job? = null

    /** Set once [resourceAgain] has asked Telegram for this video again, so it is tried only once. */
    private var reSourced = false

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
        // Before the first layout pass, on purpose. The activity used to open in whatever the phone
        // was holding, draw the whole loading screen in portrait, and turn only once the decoder
        // reported a wide picture: every single play began with a lurch. Almost every video is
        // landscape, so landscape is what the loader opens in, and a portrait clip turns once at
        // the point that is genuinely a surprise rather than at the point that never is.
        applyOrientation()
        setContentView(R.layout.activity_player)

        keepScreenOn(true)
        // Edge to edge, so the picture reaches the corners of the screen rather than sitting in a
        // letterbox of system chrome, and so the insets read below are real.
        WindowCompat.setDecorFitsSystemWindows(window, false)
        allowDrawingUnderTheCutout()
        setSystemBarsHidden(true)

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
        statusArt = findViewById(R.id.status_art)
        statusScrim = findViewById(R.id.status_scrim)
        statusPoster = findViewById(R.id.status_poster)
        statusMeta = findViewById(R.id.status_meta)
        statusRetry = findViewById<android.widget.Button>(R.id.status_retry).apply {
            setOnClickListener { retryPlayback() }
        }
        statusReload = findViewById<android.widget.Button>(R.id.status_reload).apply {
            setOnClickListener { reloadFromScratch() }
        }
        statusOpenWith = findViewById<android.widget.Button>(R.id.status_open_with).apply {
            setOnClickListener { openInAnotherApp() }
        }
        statusBack = findViewById<android.widget.Button>(R.id.status_back).apply {
            setOnClickListener { finish() }
        }
        statusName = findViewById(R.id.status_name)
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
            showError("There's nothing to play here.", retryable = false)
            return
        }

        statusTitle.text = mediaTitle.ifBlank { "Opening…" }
        showStatus("Connecting to Telegram…")

        showArtwork()
        statusMeta?.text = mediaSubtitle.ifBlank { chatTitle }
        statusMeta?.visibility =
            if (statusMeta?.text.isNullOrBlank()) View.GONE else View.VISIBLE
        if (!FormFactor.isTv(this)) wireEpisodeButtons()
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
                showError("This video isn't fully downloaded. Connect to the internet and try again.")
                return@launch
            }
            if (!allowedOnThisConnection(availability)) return@launch
            val downloadFirst = runCatching { settings.downloadBeforePlayingNow() }
                .getOrDefault(false)
            if (downloadFirst && !fetchWholeFilm()) return@launch
            playbackSpeed = runCatching { settings.playbackSpeedNow() }
                .getOrDefault(PlaybackSpeed.DEFAULT)
            videoScale = runCatching { VideoScale.from(settings.videoScaleNow()) }
                .getOrDefault(VideoScale.Fit)
            // Read before the player is built, because the track selector is configured once and
            // a preference applied after the first frame means the first line of dialogue is in
            // the wrong language.
            tracks = runCatching { settings.trackChoice(seriesKey) }
                .getOrDefault(TrackChoice())
            // The buttons were drawn with the defaults before any of this had been read off disk.
            scaleButton?.text = videoScale.label
            speedButton?.text = PlaybackSpeed.label(playbackSpeed)
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
        attachMediaSession(exo)

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
        // D2's other half: with the text renderer live there is finally something for these to
        // choose, so the phone gets Media3's own subtitle and settings buttons. Both were dead
        // weight before, which is why they were off.
        view.setShowSubtitleButton(true)
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

        view.resizeMode = videoScale.resizeMode
        view.setControllerShowTimeoutMs(CONTROLLER_TIMEOUT_MS)
        modernise(view)

        // Fed from dispatchTouchEvent rather than attached here: see [PlayerGestures].
        gestures = PlayerGestures(
            context = this,
            window = window,
            onSkip = ::skipBy,
            onFeedback = ::showGestureFeedback,
            onPinch = ::pinchScale,
            positionMs = { player?.currentPosition ?: 0L },
            durationMs = { player?.duration?.takeIf { it > 0 } ?: 0L },
            onSeekTo = { at -> player?.seekTo(at) },
            onHold = ::holdFastForward,
            onTapControls = ::toggleControls,
            onTogglePlay = ::togglePlayback,
        )

        insetTheControlsOnly(view)
    }

    /**
     * Keeps the safe area off the video and on the things a finger has to reach.
     *
     * This was the whole of both complaints about the picture. The insets were being applied as
     * padding to the PlayerView itself, and the video surface is that view's own child: every pixel
     * of status bar, gesture handle and notch was therefore taken out of the picture, so a phone
     * with a cutout played the film inside a black margin rather than edge to edge. Worse, the
     * inset changes whenever the system bars come and go, and the bars ride with the transport row,
     * so raising the controls re-padded the surface and the video visibly jumped and resized under
     * them.
     *
     * The surface is now laid out once, at the full size of the window, and never moves again.
     *
     * The controls were then inset by padding Media3's controller, which was a second version of
     * the same mistake one level down. That controller is not just the buttons: its first child is
     * the dim wash over the whole picture, and the transport row carries the gradient. Padding the
     * parent moved both of them inwards, so with the controls up the picture stayed bright in a
     * strip along the notch and another along the gesture handle, while everything between them
     * darkened. The controller now keeps every pixel of the window, and the inset is applied inside
     * it, to the transport row and the scrub bar, which are the only things a finger has to reach.
     */
    private fun insetTheControlsOnly(view: PlayerView) {
        val controller = view.findViewById<View>(androidx.media3.ui.R.id.exo_controller)
        val bottomBar = view.findViewById<View>(androidx.media3.ui.R.id.exo_bottom_bar)
        val timeBar = view.findViewById<View>(androidx.media3.ui.R.id.exo_progress)
        val corner = findViewById<View>(R.id.top_right_stack)
        val root = findViewById<View>(R.id.player_root)
        val barHeight = bottomBar?.layoutParams?.height ?: 0
        val timeBarMargin = (timeBar?.layoutParams as? ViewGroup.MarginLayoutParams)?.bottomMargin ?: 0
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val safe = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout(),
            )
            // The controller itself keeps every edge. Padding it moved the dim and the gradient in
            // with the buttons, which is the band of undimmed picture the notch and the gesture
            // handle were sitting on.
            controller?.updatePadding(left = 0, right = 0, top = 0, bottom = 0)
            // The row grows by the bottom inset rather than being padded into its fixed height, so
            // the gradient reaches the bottom of the screen while the buttons stay their own size.
            bottomBar?.let {
                it.updatePadding(left = safe.left, right = safe.right, bottom = safe.bottom)
                if (barHeight > 0) {
                    it.layoutParams = it.layoutParams.also { lp -> lp.height = barHeight + safe.bottom }
                }
            }
            // The scrub bar is a sibling of that row, pinned to the bottom by a margin of its own.
            (timeBar?.layoutParams as? ViewGroup.MarginLayoutParams)?.let { lp ->
                lp.bottomMargin = timeBarMargin + safe.bottom
                lp.leftMargin = safe.left
                lp.rightMargin = safe.right
                timeBar.layoutParams = lp
            }
            corner?.updatePadding(right = safe.right, top = safe.top)
            insets
        }
        // The video surface arrives long after the window's first inset pass, so without this the
        // controls keep the padding of whatever the insets were before there was a player at all.
        ViewCompat.requestApplyInsets(root)
    }

    /** The single tap: the one gesture that raises the transport row, and drops it again. */
    private fun toggleControls() {
        val view = touchSurface ?: return
        if (controlsUp) view.hideController() else view.showController()
    }

    /** The double tap in the middle of the picture, and the play or pause key on a phone. */
    private fun togglePlayback() {
        val exo = player ?: return
        if (exo.isPlaying) {
            exo.pause()
            showGestureFeedback("❙❙")
        } else {
            exo.play()
            showGestureFeedback("▶")
        }
    }

    /**
     * The app's own colours on Media3's controller, and a scrim under it.
     *
     * Media3's stock row is a grey scrub bar and white glyphs on nothing, which is fine over a
     * dark scene and close to invisible over a bright one. The played portion and the scrubber
     * take the app's accent, the rest goes to a translucent white so the bar reads against any
     * frame, and a gradient behind the row does what every streaming app does: darkens the bottom
     * of the picture only while there is something down there to read.
     *
     * All of it is looked up rather than declared, because the alternative is forking Media3's
     * controller layout wholesale and inheriting the maintenance of every button in it.
     */
    private fun modernise(view: PlayerView) {
        runCatching {
            val accent = ContextCompat.getColor(this, R.color.accent)
            view.findViewById<DefaultTimeBar>(androidx.media3.ui.R.id.exo_progress)?.apply {
                setPlayedColor(accent)
                setScrubberColor(accent)
                setBufferedColor(BUFFERED_COLOR)
                setUnplayedColor(UNPLAYED_COLOR)
            }
            view.findViewById<View>(androidx.media3.ui.R.id.exo_bottom_bar)
                ?.setBackgroundResource(R.drawable.bg_player_scrim)
        }
    }

    /**
     * Lets the window own the strip of screen the cutout sits in.
     *
     * The theme asks for shortEdges, which covers a phone held sideways with the notch on a short
     * edge, and stops at the one case people notice: a hole punch or a waterfall edge on the long
     * side, where the system still parks the window clear of it and the video plays inside a black
     * margin down one side. From Android 11 the window can simply claim the whole display and let
     * the insets say where the obstruction is, which is what the controls are then padded by.
     *
     * Only on a phone. A television has no cutout, and asking for one is a window flag change on a
     * screen where leanback has already decided the geometry.
     */
    private fun allowDrawingUnderTheCutout() {
        if (FormFactor.isTv(this)) return
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.P) return
        val mode = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        } else {
            WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        window.attributes = window.attributes.also { it.layoutInDisplayCutoutMode = mode }
    }

    /**
     * Hides the status and navigation bars while the picture is up, and puts them back with the
     * transport row.
     *
     * Nothing did this before, so on a phone the two system bars sat permanently over a
     * full-screen video: the top of the picture wore a clock and the bottom wore a gesture
     * handle, for the whole film. Swiping from either edge still brings them back, because the
     * behaviour is the transient one rather than the sticky one that ignores the swipe.
     */
    private fun setSystemBarsHidden(hidden: Boolean) {
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        if (hidden) {
            controller.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    /**
     * Shows the phone's previous and next buttons once the chat has been asked about them.
     *
     * They are hidden until the search comes back, and hidden for good on a video that is not part
     * of a series, so nothing appears that would do nothing when pressed.
     */
    private fun wireEpisodeButtons() {
        wirePictureButtons()
        episodeRow = findViewById(R.id.episode_row)
        previousEpisodeButton = findViewById(R.id.previous_episode)
        nextEpisodeButton = findViewById(R.id.next_episode)
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                episodes.collect { found ->
                    previousEpisodeButton?.apply {
                        visibility = if (found.previous != null) View.VISIBLE else View.GONE
                        text = episodeLabel("Prev", found.previous)
                        setOnClickListener { found.previous?.let(::playEpisode) }
                    }
                    nextEpisodeButton?.apply {
                        visibility = if (found.next != null) View.VISIBLE else View.GONE
                        text = episodeLabel("Next", found.next)
                        setOnClickListener { found.next?.let(::playEpisode) }
                    }
                    updateEpisodeRow()
                }
            }
        }
    }

    /**
     * The picture shape and the playback speed, as two buttons a thumb can reach.
     *
     * Both already existed on the television, where they are buttons on the transport row, and
     * both were technically reachable on a phone: the shape by pinching the picture, the speed by
     * finding Media3's gear menu. Neither is something a viewer discovers, and the shape in
     * particular is the control people go looking for the moment a film turns up with black bars
     * down the sides. Each button carries its current value, so it says what the last press did.
     */
    private fun wirePictureButtons() {
        pictureRow = findViewById(R.id.picture_row)
        rotationButton = findViewById<ImageView>(R.id.rotation_button)?.apply {
            setOnClickListener { cycleOrientation() }
        }
        renderOrientationButton()
        scaleButton = findViewById<android.widget.Button>(R.id.scale_button)?.apply {
            text = videoScale.label
            setOnClickListener { cycleScale() }
        }
        speedButton = findViewById<android.widget.Button>(R.id.speed_button)?.apply {
            text = PlaybackSpeed.label(playbackSpeed)
            setOnClickListener { cycleSpeed() }
        }
    }

    /**
     * "Next S01E02", falling back to a bare "Next" when the name carries no episode code.
     *
     * The fallback is not dead wood: a series can be numbered in a way the parser reads well enough
     * to order but not well enough to name, and a button labelled "Next" is still better than one
     * labelled with a guess.
     */
    fun episodeLabel(direction: String, item: MediaItem?): String {
        val name = item?.fileName?.ifBlank { item.title } ?: return direction
        val code = MediaName.parse(name).episodeCode ?: return direction
        return "$direction $code"
    }

    /** The buttons ride with the transport row, so they are never over the picture on their own. */
    private fun updateEpisodeRow() {
        val found = _episodes.value
        val room = controlsUp && statusOverlay.visibility != View.VISIBLE
        episodeRow?.visibility =
            if (room && (found.previous != null || found.next != null)) View.VISIBLE else View.GONE
        // The shape and speed buttons are offered on every video, series or not, and ride with the
        // controller for the same reason the episode buttons do.
        pictureRow?.visibility = if (room) View.VISIBLE else View.GONE
    }

    /**
     * Every touch in the window is offered to the gestures before any view sees it.
     *
     * Two arrangements, because the picture has two states. While the transport row is up, its
     * buttons and its scrub bar are what the finger came for, so the event is offered here and then
     * passed straight on to them; that is what keeps double-tap seek working over a raised row,
     * which it did not when this was hung off the video view instead.
     *
     * While the row is down there is nothing on screen but the picture, and the event stops here.
     * That is the fix for the third complaint: Media3's own view treats a great many touches as a
     * reason to throw the whole transport row over the video, so a brightness drag, a scrub, a hold
     * and even the first half of a double tap each ended with the controls in the way of the thing
     * the gesture had just done. Now only [toggleControls] raises them, from a single tap, and
     * every other gesture leaves the picture alone.
     *
     * The loading and error sheets are the exception in both directions: they carry a Try again
     * button, and a button nobody can press is worse than no gesture at all.
     */
    override fun dispatchTouchEvent(event: android.view.MotionEvent): Boolean {
        val surface = touchSurface
        if (surface == null || inPictureInPicture || statusOverlay.visibility == View.VISIBLE) {
            return super.dispatchTouchEvent(event)
        }
        gestures?.onTouchEvent(event, surface.width, surface.height)
        if (controlsUp) return super.dispatchTouchEvent(event)
        return true
    }

    /**
     * A pinch on the picture steps the fitting one stop, out towards filling the screen or back
     * towards the video's own shape.
     */
    private fun pinchScale(expanding: Boolean) {
        val stops = VideoScale.entries
        val at = videoScale.ordinal
        val target = if (expanding) (at + 1).coerceAtMost(stops.lastIndex) else (at - 1).coerceAtLeast(0)
        if (target == at) return
        applyScale(stops[target])
    }

    /**
     * Runs the film fast for as long as a finger is held on it, then puts it back.
     *
     * The speed the viewer chose is what it returns to, not 1x: somebody watching a lecture at
     * 1.5x who holds to skip an aside expects to be back at 1.5x when they let go, and handing
     * them normal speed instead means fixing it by hand every time.
     */
    private fun holdFastForward(holding: Boolean) {
        val exo = player ?: return
        if (holding) {
            exo.setPlaybackSpeed(HOLD_SPEED)
            showGestureFeedback("${HOLD_SPEED.toInt()}x  ▶▶")
        } else {
            exo.setPlaybackSpeed(playbackSpeed)
        }
    }

    /**
     * Asks the system for the orientation this player is set to, and remembers it for the session.
     *
     * Called before the content view on the way in, which is the whole point: a window that turns
     * after it has drawn is a lurch the viewer sees, and a window that is asked before it has
     * drawn anything simply opens the right way up.
     */
    private fun applyOrientation() {
        if (FormFactor.isTv(this)) return
        requestedOrientation = orientation.requested
    }

    /** The button: the next state along, applied and said out loud. */
    private fun cycleOrientation() {
        orientation = orientation.next()
        orientationChosen = true
        // Only an explicit press is worth carrying to the next episode, and to the next launch.
        // A tall clip turning the window on its own is about that one video, not about how this
        // viewer watches things.
        lastOrientation = orientation
        lifecycleScope.launch { runCatching { settings.setScreenOrientation(orientation.name) } }
        applyOrientation()
        renderOrientationButton()
        showGestureFeedback(orientation.label)
    }

    private fun renderOrientationButton() {
        val button = rotationButton ?: return
        button.setImageResource(
            when (orientation) {
                ScreenOrientation.Follow -> R.drawable.ic_rotate_auto
                ScreenOrientation.Landscape -> R.drawable.ic_rotate_landscape
                ScreenOrientation.Portrait -> R.drawable.ic_rotate_portrait
            },
        )
        button.contentDescription = orientation.label
    }

    /** The next picture shape along, from the television's own button. */
    fun cycleScale() = applyScale(videoScale.next())

    /**
     * Which lever shapes the picture, and why only one of them may move at a time.
     *
     * There are two, and on a phone they were both being pulled. The PlayerView sizes its own
     * surface to the chosen shape, and the codec's scaling mode then scaled the frames again
     * inside that surface: Crop asked the view to grow past the screen and asked the decoder to
     * crop within it at the same time, so the two transforms cancelled and Crop landed on the same
     * letterboxed picture as Fit, while Stretch came out narrower than the video's own shape. Two
     * of the three stops did nothing a viewer could see, on a screen with 217px of black down each
     * side of a 1920x1056 film.
     *
     * The phone therefore leaves the codec on plain scale-to-fit and lets the view own the shape.
     * The television has no PlayerView to set a resize mode on: it draws onto leanback's bare
     * surface, where the scaling mode is the only lever there is, and it has two positions, which
     * is why Stretch is not offered there and Crop is what a second press reaches.
     */
    private fun scalingModeFor(scale: VideoScale): Int = when {
        !FormFactor.isTv(this) -> C.VIDEO_SCALING_MODE_SCALE_TO_FIT
        scale == VideoScale.Fit -> C.VIDEO_SCALING_MODE_SCALE_TO_FIT
        else -> C.VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING
    }

    private fun applyScale(scale: VideoScale) {
        videoScale = scale
        touchSurface?.resizeMode = scale.resizeMode
        player?.videoScalingMode = scalingModeFor(scale)
        tvFragment()?.showVideoScale(scale)
        scaleButton?.text = scale.label
        showGestureFeedback(scale.label)
        lifecycleScope.launch { runCatching { settings.setVideoScale(scale.name) } }
    }

    /** The next speed along, applied now and remembered for the next video. */
    fun cycleSpeed() {
        val next = PlaybackSpeed.next(playbackSpeed)
        playbackSpeed = next
        player?.setPlaybackSpeed(next)
        tvFragment()?.showPlaybackSpeed(next)
        speedButton?.text = PlaybackSpeed.label(next)
        showGestureFeedback(PlaybackSpeed.label(next))
        lifecycleScope.launch { runCatching { settings.setPlaybackSpeed(next) } }
    }

    private fun tvFragment(): TvPlaybackFragment? =
        supportFragmentManager.findFragmentById(R.id.playback_container) as? TvPlaybackFragment

    /**
     * A figure for whatever a gesture is changing, gone again shortly after the finger lifts.
     *
     * It moves to the side the gesture is on, because a brightness reading that appears on the
     * right of the picture while the finger is on the left reads as a coincidence rather than as
     * an answer. This is the only thing a drag or a key press puts on screen: the transport row
     * stays where it was.
     */
    private fun showGestureFeedback(text: String, side: Int = PlayerGestures.SIDE_CENTRE) {
        val hud = gestureHud ?: return
        hud.text = text
        (hud.layoutParams as? FrameLayout.LayoutParams)?.let { params ->
            val gravity = android.view.Gravity.CENTER_VERTICAL or when (side) {
                PlayerGestures.SIDE_LEFT -> android.view.Gravity.START
                PlayerGestures.SIDE_RIGHT -> android.view.Gravity.END
                else -> android.view.Gravity.CENTER_HORIZONTAL
            }
            if (params.gravity != gravity) {
                params.gravity = gravity
                hud.layoutParams = params
            }
        }
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

    /**
     * Checks the video against the connection before a byte of it is fetched.
     *
     * Nothing distinguished Wi-Fi from mobile data anywhere in the app before this, so opening a
     * large video on a train quietly started pulling all of it. A file already on disk is never
     * questioned, and neither is a small one; only a large pull over a metered connection stops to
     * ask, and only the first time in a session, because being asked before every episode is its
     * own kind of broken.
     *
     * Returns false when there is nothing to start: the sheet on screen is the answer.
     */
    private suspend fun allowedOnThisConnection(availability: LocalFileAvailability): Boolean {
        val wifiOnly = runCatching { settings.wifiOnlyDownloadsNow() }.getOrDefault(false)
        val decision = MeteredPolicy.decide(
            metered = NetworkMonitor.metered.value,
            wifiOnly = wifiOnly,
            alreadyDownloaded = availability == LocalFileAvailability.Complete,
            warnedThisSession = meteredWarningAccepted,
            sizeBytes = fileSizeBytes,
        )
        return when (decision) {
            MeteredDecision.Allow -> true
            MeteredDecision.Block -> {
                showError(
                    "This video isn't downloaded yet, and TMPlayer is set to use Wi-Fi only. " +
                        "Connect to Wi-Fi, or turn that off in Settings.",
                    retryable = false,
                )
                false
            }
            MeteredDecision.Warn -> awaitMeteredConsent()
        }
    }

    /** The prompt itself: the loading sheet, holding, with one button that carries on. */
    private suspend fun awaitMeteredConsent(): Boolean {
        val consented = CompletableDeferred<Boolean>()
        openingFilm = false
        statusOverlay.visibility = View.VISIBLE
        statusIcon.visibility = View.GONE
        rebufferChip.visibility = View.GONE
        statusSpinner?.visibility = View.GONE
        statusProgress.visibility = View.GONE
        statusDetail.visibility = View.GONE
        statusTitle.text = "You're on mobile data"
        hideFailureActions()
        statusText.text = "This video is ${MediaMapper.formatSize(fileSizeBytes)}. " +
            "Playing it now will use that much of your allowance."
        statusRetry?.apply {
            text = "Play anyway"
            visibility = View.VISIBLE
            setOnClickListener {
                text = "Try again"
                setOnClickListener { retryPlayback() }
                consented.complete(true)
            }
            requestFocus()
        }
        val answer = consented.await()
        // Remembered for the process, not on disk: a session is the unit of consent here, and
        // writing it down would mean asking once ever, which is not the same promise.
        meteredWarningAccepted = true
        showStatus("Starting the video…")
        return answer
    }

    /**
     * Movie, not the default "unknown". It is what tells the system this is long-form video, which
     * is what the volume curve, the ducking behaviour and any connected audio device's own
     * processing all key off. The same attributes ask for the output's channel count, because the
     * answer depends on what the audio is for.
     */
    private val movieAudioAttributes = AudioAttributes.Builder()
        .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
        .setUsage(C.USAGE_MEDIA)
        .build()

    private fun buildPlayer(client: TdlClient): ExoPlayer {
        // Hardware decoders first; NextLib's FFmpeg renderers pick up the audio codecs a TV
        // stick has no silicon for: DTS and TrueHD tracks are common in video remuxes.
        val renderers = object : NextRenderersFactory(this@PlayerActivity) {
            /**
             * The stock sink, plus the stereo fold described in [AudioDownmix].
             *
             * Built here rather than around the finished player because this is the only hook
             * Media3 offers: the sink is created inside the factory and handed straight to the
             * audio renderer.
             */
            override fun buildAudioSink(
                context: Context,
                enableFloatOutput: Boolean,
                enableAudioTrackPlaybackParams: Boolean,
            ): AudioSink {
                val folds = AudioDownmix.folds(FormFactor.isTv(context))
                val processors = if (folds.isEmpty()) {
                    emptyArray()
                } else {
                    val mixer = ChannelMixingAudioProcessor()
                    folds.forEach { fold ->
                        mixer.putChannelMixingMatrix(
                            when (fold) {
                                is AudioDownmix.Fold.Untouched ->
                                    ChannelMixingMatrix.create(fold.channels, fold.channels)

                                is AudioDownmix.Fold.ConstantPower ->
                                    ChannelMixingMatrix.createForConstantPower(
                                        fold.channels,
                                        AudioDownmix.STEREO,
                                    )

                                is AudioDownmix.Fold.Wide -> ChannelMixingMatrix(
                                    fold.channels,
                                    AudioDownmix.STEREO,
                                    fold.coefficients,
                                )
                            },
                        )
                    }
                    arrayOf<AudioProcessor>(mixer)
                }
                return DefaultAudioSink.Builder(context)
                    .setEnableFloatOutput(enableFloatOutput)
                    .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                    .setAudioProcessors(processors)
                    .build()
            }
        }
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
            .setEnableDecoderFallback(true)

        // Deliberately small: on a 1 GB stick a generous buffer is what gets the app killed.
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(BUFFER_MIN_MS, BUFFER_MAX_MS, BUFFER_PLAYBACK_MS, BUFFER_REBUFFER_MS)
            .setTargetBufferBytes(TARGET_BUFFER_BYTES)
            .setPrioritizeTimeOverSizeThresholds(false)
            // A few seconds of what has already played, kept behind the position. Without it a
            // back-seek of two seconds fell outside the buffer, which put the read outside TDLib's
            // download window, which restarted the download from there: the commonest small
            // gesture in the player was also the most expensive thing it could do. Deliberately
            // short, and from the last keyframe, because this is memory on a 1 GB stick.
            .setBackBuffer(BACK_BUFFER_MS, /* retainBackBufferFromKeyframe = */ true)
            .build()

        val trackSelector = DefaultTrackSelector(this).apply {
            parameters = buildUponParameters()
                // Not disabled outright any more. Turning the whole text renderer off meant a
                // phone viewer could not enable an embedded subtitle track by any route at all:
                // the only picker in the app is leanback's, which does not exist on a phone.
                // Selecting none by default keeps captions off until asked for, which is the
                // behaviour that was wanted, while leaving the track there to be chosen.
                // Off stays off. Without this, a series watched without captions gets them back on
                // the next episode whenever that file marks a subtitle track default or forced.
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, !tracks.empty && !tracks.subtitlesOn)
                // What this series was last watched with. Episode two opens in the language and
                // with the captions episode one ended in, which is the whole point of choosing
                // them: before this, every episode arrived on the file's own default track and
                // the choice had to be made again from the sofa.
                .setPreferredTextLanguage(tracks.textLanguage.takeIf { tracks.subtitlesOn })
                .setSelectUndeterminedTextLanguage(false)
                .setPreferredAudioLanguage(tracks.audioLanguage)
                .build()
        }

        // Constant-bitrate seeking rescues formats that ship without a seek index.
        // Constant-bitrate seeking rescues formats that ship without a seek index. "Always" also
        // applies it to files that do have one, where the estimate is worse than the index and a
        // seek lands somewhere other than where it was asked to; it is only worth it for a file
        // with no other way to seek at all.
        val extractors = DefaultExtractorsFactory()
            .setConstantBitrateSeekingEnabled(true)

        return ExoPlayer.Builder(this, renderers)
            .setLoadControl(loadControl)
            .setTrackSelector(trackSelector)
            .setMediaSourceFactory(DefaultMediaSourceFactory(TdDataSource.Factory(client), extractors))
            .setSeekBackIncrementMs(Skip.BACK_MS)
            .setSeekForwardIncrementMs(Skip.FORWARD_MS)
            .build()
            .apply {
                setAudioAttributes(movieAudioAttributes, /* handleAudioFocus = */ true)
                setHandleAudioBecomingNoisy(true)
                setWakeMode(C.WAKE_MODE_LOCAL)
                setPlaybackSpeed(playbackSpeed)
                videoScalingMode = scalingModeFor(videoScale)
            }
    }

    /**
     * Hands the player to the system so the transport controls exist outside this screen.
     *
     * That is the notification, the lock screen, the headset's pause button, a car's steering
     * wheel and the Assistant, none of which the app draws or handles itself. It is also what
     * makes picture in picture's own play and pause buttons work.
     */
    private fun attachMediaSession(exo: ExoPlayer) {
        mediaSession?.release()
        mediaSession = runCatching {
            MediaSession.Builder(this, exo)
                // Distinct per activity instance: stepping to the next episode builds a second
                // activity before the first has been destroyed, and two sessions sharing an id
                // is the one thing the builder refuses outright.
                .setId("tmplayer-$chatId-$messageId-${SystemClock.elapsedRealtime()}")
                .build()
        }.getOrNull()
    }

    /**
     * Two hours of a video is two hours with no button presses, so the screen is held awake while
     * something is actually on it. Held only while playing: a video left paused overnight, or an
     * error sheet nobody came back to, used to keep a stick's display lit until the power went off.
     */
    private fun keepScreenOn(on: Boolean) {
        if (on) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    private val playerListener = object : Player.Listener {
        override fun onCues(cueGroup: CueGroup) {
            subtitleView.setCues(cueGroup.cues)
        }

        /**
         * Files the soundtrack and subtitles now playing under this series.
         *
         * Written from what is actually selected rather than from what the picker was told, so it
         * covers every route to a track: the television's picker, Media3's own subtitle button on
         * a phone, and the file's defaults on a series being watched for the first time.
         */
        override fun onTracksChanged(tracks: Tracks) {
            rememberTracks(tracks)
        }

        override fun onVideoSizeChanged(size: VideoSize) {
            videoWidth = size.width
            videoHeight = size.height
            followVideoOrientation()
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            // A long download before the first frame is still the viewer waiting on this screen,
            // so the loading sheet counts as something worth staying awake for.
            keepScreenOn(isPlaying || openingFilm)
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
                Player.STATE_ENDED -> onVideoEnded()
                else -> Unit
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            if (recoverFrom(error)) return
            if (resourceAgain(error)) return
            showError(friendlyError(error), retryable = !isHopeless(error))
        }
    }

    /**
     * Keeps the stored choice in step with what is playing, and writes it when it moves.
     *
     * The language is what identifies a track between two files: the group indices and the track
     * order are the container's, and the next episode was remuxed by the same person on a
     * different evening. A track with no language declared at all is remembered only as "captions
     * were on", which the next episode honours by leaving its own default alone.
     */
    private fun rememberTracks(current: Tracks) {
        fun language(type: Int): String? = current.groups
            .firstOrNull { it.type == type && it.isSelected }
            ?.let { group ->
                (0 until group.length)
                    .firstOrNull { group.isTrackSelected(it) }
                    ?.let { group.getTrackFormat(it).language }
            }
            ?.takeIf { it.isNotBlank() && it != C.LANGUAGE_UNDETERMINED }

        val subtitlesOn = current.groups.any { it.type == C.TRACK_TYPE_TEXT && it.isSelected }
        val updated = TrackChoice(
            audioLanguage = language(C.TRACK_TYPE_AUDIO) ?: tracks.audioLanguage,
            textLanguage = language(C.TRACK_TYPE_TEXT) ?: tracks.textLanguage.takeIf { subtitlesOn },
            subtitlesOn = subtitlesOn,
        )
        if (updated == tracks) return
        tracks = updated
        val key = seriesKey
        val store = settings
        App.backgroundScope.launch { runCatching { store.setTrackChoice(key, updated) } }
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
     * Asks Telegram for this video again, for the failure that looks like a broken file and is not.
     *
     * A TDLib file id belongs to the session that issued it. Continue watching, the downloads list
     * and the episode row all carry ids that were written down earlier, so a video opened after a
     * restart, or after TDLib rebuilt its database, can be pointed at a file the current session
     * knows nothing about. Every read then fails at once, nothing downloads, and the sheet says
     * "This video wouldn't play" over a file that is perfectly fine: the id is what is stale, not
     * the video. The same is true of an expired file reference, which is Telegram asking to be
     * given the message again rather than the file.
     *
     * So before the sheet goes up, the message is fetched again and the id it carries now is used.
     * Once per activity, and only when there is a message to fetch: a second attempt would be the
     * same fetch with the same answer, and the sheet is then the honest one.
     *
     * Returns true when it has taken over, in which case the retry, or the sheet, comes later.
     */
    private fun resourceAgain(error: PlaybackException): Boolean {
        if (reSourced || chatId == 0L || messageId == 0L) return false
        reSourced = true
        lifecycleScope.launch {
            showStatus("Asking Telegram for this video again…")
            val fresh = runCatching { Td.refreshMedia(chatId, messageId) }.getOrNull()
            if (fresh == null || fresh.fileId == fileId) {
                // Nothing new to try: the id in hand is the id Telegram gives, so the failure is
                // about the video rather than about the name this app was calling it by.
                showError(friendlyError(error), retryable = !isHopeless(error))
                return@launch
            }
            fileId = fresh.fileId
            fileSizeBytes = fresh.sizeBytes
            // The old id is baked into the media source, so the player goes rather than re-prepares.
            releasePlayerAndSurface()
            retryPlayback()
        }
        return true
    }

    /**
     * Takes down the player, its session and its surface, for the two callers that cannot re-prepare.
     *
     * Both [reloadFromScratch] and [resourceAgain] change the bytes underneath a media source that
     * has already been built over them, and preparing over that reads the file the press was meant
     * to get away from. The order matters: the view lets go of the player before the player is
     * released, so nothing is ever holding a released one.
     */
    private fun releasePlayerAndSurface() {
        mediaSession?.release()
        mediaSession = null
        touchSurface?.player = null
        touchSurface?.let { findViewById<FrameLayout>(R.id.playback_container).removeView(it) }
        touchSurface = null
        player?.removeListener(playerListener)
        player?.release()
        player = null
    }

    /**
     * Whether this failure is one no amount of trying can get past.
     *
     * Narrower than [isRecoverable] on purpose, and asked of a different thing. That one decides
     * whether the app should quietly try again by itself, where being wrong costs a wasted attempt
     * on every stall. This one decides whether to put a Reload button in front of the viewer, where
     * being wrong costs them the only thing on the screen that could have worked: an unclassified
     * error is very often a bad cache or a stale file id, which is exactly what Reload is for. Only
     * a codec or a container this device genuinely cannot handle is hopeless, and there the sheet
     * offers the app that can handle it instead.
     */
    private fun isHopeless(error: PlaybackException): Boolean = when (error.errorCode) {
        PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
        PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
        PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED,
        -> true

        else -> false
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

    /**
     * The sentence to put on the error sheet.
     *
     * The cause chain comes first. [TdDataSource] already turns a flood wait, an expired file
     * reference or a full disk into a sentence naming the actual problem, and Media3 wraps that
     * exception rather than replacing it; reading only the error code threw all of that away and
     * told every viewer to try a different copy of a file that was perfectly fine.
     */
    private fun friendlyError(error: PlaybackException): String =
        specificCause(error) ?: byErrorCode(error)

    private fun specificCause(error: PlaybackException): String? {
        var cause: Throwable? = error.cause
        var hops = 0
        while (cause != null && hops < MAX_CAUSE_HOPS) {
            val message = cause.message?.trim()
            if (!message.isNullOrEmpty()) {
                val humanised = Failures.humanise(message)
                if (humanised != Failures.DEFAULT) return humanised
            }
            cause = cause.cause
            hops++
        }
        return null
    }

    private fun byErrorCode(error: PlaybackException) = when (error.errorCode) {
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
        ->
            "Lost the connection to Telegram. Check this device's internet and try again."

        PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
        PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
        ->
            // A remux with a single video track bypasses the selector's viewport constraints, so
            // a 4K stream reaches a decoder built for 1080p and dies with a class name in it. The
            // resolution is the whole of what went wrong and it is worth saying so: "a different
            // copy may work" sends somebody looking for a bad file rather than a smaller one.
            if (videoHeight >= UHD_HEIGHT) {
                "This video is ${videoHeight}p, which this device's decoder can't manage. " +
                    "A 1080p copy will play."
            } else {
                "This device can't play this video's format. A different copy may work."
            }

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

    /**
     * What happens when the credits run out.
     *
     * The activity used to close the instant the video ended, which for one video of a series
     * meant being thrown back to the grid to find the next one by hand, and for anything else
     * meant the screen going away before the viewer had registered that it was over. Now the next
     * episode starts on its own after a countdown that can be stopped, and where there is no next
     * episode the last frame stays up with a way to watch it again.
     */
    private fun onVideoEnded() {
        lifecycleScope.launch {
            settings.clearResumePosition(chatId, messageId)
            val next = _episodes.value.next
            val autoplay = runCatching { settings.autoplayNextNow() }.getOrDefault(true)
            if (next == null || !autoplay) {
                showFinished()
                return@launch
            }
            for (second in AUTOPLAY_COUNTDOWN_SEC downTo 1) {
                showStatus("Next: ${next.title}")
                statusDetail.text = "Starting in $second…  Press Back to stop."
                showLoadingProgress(
                    (AUTOPLAY_COUNTDOWN_SEC - second).toFloat() / AUTOPLAY_COUNTDOWN_SEC,
                )
                delay(1_000)
            }
            playEpisode(next)
        }
    }

    /** The end of the last video there is: the picture stays, with a way to watch it again. */
    private fun showFinished() {
        openingFilm = false
        keepScreenOn(false)
        statusOverlay.visibility = View.VISIBLE
        statusIcon.visibility = View.GONE
        rebufferChip.visibility = View.GONE
        statusSpinner?.visibility = View.GONE
        statusProgress.visibility = View.GONE
        statusDetail.visibility = View.GONE
        statusTitle.text = mediaTitle.ifBlank { "Finished" }
        statusText.text = "That's the end."
        hideFailureActions()
        statusRetry?.apply {
            text = "Watch again"
            visibility = View.VISIBLE
            setOnClickListener {
                text = "Try again"
                setOnClickListener { retryPlayback() }
                player?.seekTo(0)
                player?.playWhenReady = true
                hideStatus()
                keepScreenOn(true)
            }
            requestFocus()
        }
        updateDownloadChip()
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

        // Before anything else claims the same key. A held select on a remote, or the menu key,
        // is the long press on a video that the browse grid already answers with a menu; here the
        // one entry that menu would carry which the player has no other route to is the handover.
        if (isLongPressOnTheVideo(event)) {
            openInAnotherApp()
            return true
        }

        if (!FormFactor.isTv(this) && handleTouchDeviceKey(event)) return true

        when (event.keyCode) {
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
                skipBy(Skip.FORWARD_MS)
                return true
            }
            KeyEvent.KEYCODE_MEDIA_REWIND -> {
                skipBy(-Skip.BACK_MS)
                return true
            }
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_LEFT -> {
                // Only asked of leanback, and only when leanback is what is on screen. A phone
                // has no such fragment, and its transport row handles the arrows of an attached
                // keyboard itself, so there is nothing here to route.
                val fragment = supportFragmentManager
                    .findFragmentById(R.id.playback_container) as? TvPlaybackFragment
                if (fragment != null && !fragment.controlsVisible()) {
                    val step = if (event.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) Skip.FORWARD_MS else -Skip.BACK_MS
                    skipBy(step)
                    // Fall through so leanback also raises the controls: the user needs to see
                    // where the seek landed.
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    /**
     * A press held down on the picture itself, which is what asks to leave for another app.
     *
     * Only while the failure sheet is down and the controls are up: a held select over a bare
     * picture is how leanback and Media3 both start a scrub, and a held select over the failure
     * sheet is a press on whichever button has focus. Neither is a spare gesture, and taking one
     * of them would break something that works to make room for something that has a button of
     * its own two lines below.
     */
    private fun isLongPressOnTheVideo(event: KeyEvent): Boolean {
        if (!event.isLongPress) return false
        if (statusOverlay.visibility == View.VISIBLE) return false
        return when (event.keyCode) {
            KeyEvent.KEYCODE_MENU -> true
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> controlsUp
            else -> false
        }
    }

    /**
     * A key on a phone: a paired remote, a headset, a Bluetooth keyboard, a game controller.
     *
     * Answered here and consumed, rather than left to Media3's view, which raises the whole
     * transport row on any key it recognises. Pressing play on a headset and being handed a control
     * overlay across the picture was the third complaint's other half: the press already did the
     * thing, and the small figure this leaves behind is all the confirmation it needs.
     *
     * Only while the row is down. Once it is up those same keys are how anything on it is reached,
     * and stealing them would leave the row unusable to everything except a thumb.
     */
    private fun handleTouchDeviceKey(event: KeyEvent): Boolean {
        if (controlsUp || statusOverlay.visibility == View.VISIBLE) return false
        when (event.keyCode) {
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                skipBy(Skip.FORWARD_MS)
                showGestureFeedback("${Skip.FORWARD_MS / 1000} s   ▶▶", PlayerGestures.SIDE_RIGHT)
            }
            KeyEvent.KEYCODE_MEDIA_REWIND, KeyEvent.KEYCODE_DPAD_LEFT -> {
                skipBy(-Skip.BACK_MS)
                showGestureFeedback("◀◀   ${Skip.BACK_MS / 1000} s", PlayerGestures.SIDE_LEFT)
            }
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_SPACE,
            -> togglePlayback()
            else -> return false
        }
        return true
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
        // Sampled rather than rendered per update. TDLib emits one of these every few hundred
        // kilobytes, which at a few MB/s is a dozen a second, and each one re-laid-out the whole
        // loading sheet on the main thread at exactly the moment the decoder was starting up. No
        // figure on that sheet changes usefully faster than twice a second.
        val now = SystemClock.elapsedRealtime()
        if (now - lastProgressRender >= PROGRESS_RENDER_MS || downloadComplete) {
            lastProgressRender = now
            renderProgress()
        }
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
            showLoadingProgress(downloadedFraction)
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
            showLoadingProgress(fraction)
            statusDetail.text = listOf(rate, left).filter { it.isNotBlank() }.joinToString("  ·  ")
        } else {
            rebufferText.text = "Loading  ·  $rate"
        }
        updateDownloadChip()
    }

    /**
     * Turns the window to suit the video, once the decoder has said what shape it is.
     *
     * The manifest used to nail this activity to landscape, which is right for almost every film
     * and wrong for the one case where it is most obviously wrong: a clip shot on a phone played
     * as a narrow strip between two black fields, with the phone held sideways. A television
     * reports a single orientation and ignores every request made here, so this is touch only.
     *
     * It has one vote and the viewer has the other, and the viewer's wins: once the rotation button
     * has been pressed, a tall video arriving later does not get to undo that. A wide video is left
     * entirely alone, since the player already opened sideways for it.
     */
    private fun followVideoOrientation() {
        if (FormFactor.isTv(this)) return
        if (orientationChosen) return
        if (videoWidth <= 0 || videoHeight <= 0) return
        if (videoWidth >= videoHeight) return
        // Not locked to portrait: a tall video is still watchable sideways, and somebody
        // lying down should be allowed to decide that for themselves.
        orientation = ScreenOrientation.Follow
        applyOrientation()
        renderOrientationButton()
    }

    /**
     * Leaving the app puts the video in the corner of whatever comes next, rather than stopping it.
     *
     * Before this, pressing Home during a film ended the film: [onStop] paused it and coming back
     * meant finding the resume point again. Only on a phone, only while something is actually
     * playing, and never over an error sheet or a countdown, none of which are worth a floating
     * window.
     */
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (FormFactor.isTv(this)) return
        if (!packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)) return
        if (player?.isPlaying != true) return
        if (statusOverlay.visibility == View.VISIBLE) return
        runCatching { enterPictureInPictureMode(pictureInPictureParams()) }
    }

    private fun pictureInPictureParams(): PictureInPictureParams {
        val builder = PictureInPictureParams.Builder()
        // Android refuses anything narrower than 1:2.39 or wider than 2.39:1, and a video that
        // falls outside that takes the whole request down with it, so it is only offered when
        // the picture's own shape is known to be inside the range.
        if (videoWidth > 0 && videoHeight > 0) {
            val ratio = videoWidth.toFloat() / videoHeight
            if (ratio in PIP_MIN_RATIO..PIP_MAX_RATIO) {
                builder.setAspectRatio(Rational(videoWidth, videoHeight))
            }
        }
        return builder.build()
    }

    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: Configuration,
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        inPictureInPicture = isInPictureInPictureMode
        // At thumbnail size there is room for the picture and nothing else. The system draws its
        // own play and pause over the window, fed by the media session.
        touchSurface?.useController = !isInPictureInPictureMode
        if (isInPictureInPictureMode) touchSurface?.hideController()
        subtitleView.visibility = if (isInPictureInPictureMode) View.GONE else View.VISIBLE
        episodeRow?.visibility = View.GONE
        pictureRow?.visibility = View.GONE
        downloadChip.visibility = View.GONE
        gestures?.controlsVisible = false
    }

    /** Leanback raising or hiding the transport row; the download figure rides with it. */
    fun onControlsVisibilityChanged(visible: Boolean) {
        controlsUp = visible
        gestures?.controlsVisible = visible
        // The system bars ride with the transport row: raising one to reach for a control and
        // being handed the other is what every video app on the platform does.
        setSystemBarsHidden(!visible)
        updateEpisodeRow()
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

    /**
     * Puts the video's own artwork behind the wait.
     *
     * Two pictures from two places, because they arrive at two different times. The minithumbnail
     * travels inside the message and is already in memory, so it is on screen in the first frame,
     * blown up across the whole panel where its forty-odd pixels read as a blur rather than as a
     * bad photograph. The real thumbnail is a file Telegram has to be asked for, so it lands a
     * moment later, sharp, in the poster at the centre.
     *
     * A video with no thumbnail at all, which is most of what a re-upload channel posts, simply
     * gets the sheet as it was: everything here is hidden until there is something to draw.
     */
    private fun showArtwork() {
        val mini = Thumbnails.mini(intent.getByteArrayExtra(EXTRA_MINI_THUMBNAIL))
        if (mini != null) {
            statusArt?.setImageBitmap(mini)
            statusPoster?.setImageBitmap(mini)
            revealArtwork()
        }

        val thumbnailId = intent.getIntExtra(EXTRA_THUMBNAIL_ID, 0)
        if (thumbnailId <= 0) return
        lifecycleScope.launch {
            val full = runCatching { Thumbnails.full(thumbnailId) }.getOrNull() ?: return@launch
            statusPoster?.setImageBitmap(full)
            // Where there was a minithumbnail the backdrop keeps it, on purpose: stretching a real
            // thumbnail across 1080p is a soft, ugly photograph, while stretching a forty-pixel
            // one is a blur. Where there was not, this is the only picture there is, and a soft
            // backdrop beats the flat sheet it would otherwise be.
            if (mini == null) statusArt?.setImageBitmap(full)
            revealArtwork()
        }
    }

    /**
     * Brings the artwork up, and keeps the backdrop moving while the viewer waits.
     *
     * The drift is slow enough to be felt rather than watched: a still frame under a progress bar
     * reads as a screen that has stopped, and this is precisely the moment a viewer is deciding
     * whether the app has hung. Two scale properties on one view, which is a compositor job.
     */
    private fun revealArtwork() {
        val art = statusArt ?: return
        val poster = statusPoster
        if (art.visibility != View.VISIBLE) {
            art.alpha = 0f
            art.visibility = View.VISIBLE
            statusScrim?.visibility = View.VISIBLE
            art.animate().alpha(ART_ALPHA).setDuration(ART_FADE_MS).start()
        }
        if (poster != null && poster.visibility != View.VISIBLE) {
            poster.alpha = 0f
            poster.translationY = POSTER_RISE_PX * resources.displayMetrics.density
            poster.visibility = View.VISIBLE
            poster.clipToOutline = true
            poster.animate().alpha(1f).translationY(0f).setDuration(ART_FADE_MS).start()
        }
        if (artDrift == null) {
            artDrift = android.animation.ObjectAnimator.ofPropertyValuesHolder(
                art,
                android.animation.PropertyValuesHolder.ofFloat(View.SCALE_X, 1f, ART_DRIFT_SCALE),
                android.animation.PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f, ART_DRIFT_SCALE),
            ).apply {
                duration = ART_DRIFT_MS
                repeatCount = android.animation.ValueAnimator.INFINITE
                repeatMode = android.animation.ValueAnimator.REVERSE
                start()
            }
        }
    }

    /**
     * Which of the two bars is up: the one with a figure on it, or the one without.
     *
     * Nothing knows how long a Telegram download will take until some of it has arrived, so the
     * first seconds have no honest percentage to show. An indeterminate bar says "working" without
     * claiming a number, and it is replaced the moment there is one.
     */
    private fun showLoadingProgress(fraction: Float) {
        val known = fraction > 0f
        statusProgress.visibility = if (known) View.VISIBLE else View.GONE
        statusSpinner?.visibility = if (known) View.GONE else View.VISIBLE
        if (known) statusProgress.progress = (fraction * 1000).toInt()
    }

    /** The backdrop stops moving as soon as nobody is looking at it. */
    private fun stopArtDrift() {
        artDrift?.cancel()
        artDrift = null
        statusArt?.scaleX = 1f
        statusArt?.scaleY = 1f
    }

    /**
     * Stacks the loading and failure sheet, or lays it out in two columns where stacking will not
     * fit.
     *
     * A phone held sideways has around 390dp of height, and the sheet is a poster, a title, a
     * message and up to three buttons: stacked, that overflows, and the buttons that are the whole
     * point of the failure screen end up below the bottom of the display. Turned on its side it is
     * a picture on the left and everything readable on the right, which is both the shape that
     * fits and the better looking of the two. A television, and a phone held upright, have the
     * height and keep the stack.
     *
     * Applied every time the sheet is put up rather than once at startup, and it reads both ways.
     * This activity chooses its own orientation and does not get recreated when it changes, so the
     * screen it was built against is regularly not the screen it ends up on: measured at [onCreate]
     * a video that opens upright and then turns sideways is measured while it is still upright.
     */
    private fun layOutSheetForThisScreen() {
        val sheet = findViewById<LinearLayout>(R.id.status_sheet) ?: return
        val column = findViewById<LinearLayout>(R.id.status_column) ?: return
        val side = findViewById<LinearLayout>(R.id.status_side) ?: return
        val sideBySide = sheetGoesSideways()
        val wanted = if (sideBySide) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
        if (sheet.orientation == wanted) return
        sheet.orientation = wanted

        val density = resources.displayMetrics.density
        fun px(dp: Float) = (dp * density).toInt()

        // The picture side takes a narrow, fixed slice: it is there to be recognised, not read, and
        // every point it does not use is a point the message and the buttons do.
        side.updateLayoutParams<LinearLayout.LayoutParams> {
            marginEnd = if (sideBySide) px(POSTER_GAP_DP) else 0
        }
        statusPoster?.updateLayoutParams<LinearLayout.LayoutParams> {
            width = if (sideBySide) {
                px(SIDE_POSTER_WIDTH_DP)
            } else {
                resources.getDimensionPixelSize(R.dimen.player_poster_width)
            }
            height = if (sideBySide) {
                px(SIDE_POSTER_HEIGHT_DP)
            } else {
                resources.getDimensionPixelSize(R.dimen.player_poster_height)
            }
            // The gap between the two moves with them: what was space underneath the poster is now
            // space to the right of the pair.
            bottomMargin = if (sideBySide) px(NAME_GAP_DP) else px(POSTER_STACK_GAP_DP)
        }
        // The name wraps to the width of the picture it belongs to rather than running the width of
        // the screen, which is what keeps the two of them reading as one thing on the left.
        statusName?.apply {
            maxWidth = if (sideBySide) px(SIDE_POSTER_WIDTH_DP) else px(NAME_STACK_MAX_WIDTH_DP)
            maxLines = if (sideBySide) SIDE_NAME_LINES else STACK_NAME_LINES
        }
        // Weighted rather than wrapped, so a long message takes the width that is left over
        // instead of pushing the picture off the side of the screen.
        column.updateLayoutParams<LinearLayout.LayoutParams> {
            width = if (sideBySide) 0 else LinearLayout.LayoutParams.WRAP_CONTENT
            weight = if (sideBySide) 1f else 0f
        }
        // The bars run the width of the column they are in rather than a width chosen for a screen
        // with nothing beside them, so a download's progress reads at a glance rather than as a
        // short bar floating in the middle of the space it was given.
        listOfNotNull(statusProgress, statusSpinner).forEach { bar ->
            bar.updateLayoutParams<LinearLayout.LayoutParams> {
                width = if (sideBySide) {
                    LinearLayout.LayoutParams.MATCH_PARENT
                } else {
                    resources.getDimensionPixelSize(R.dimen.player_progress_width)
                }
            }
        }
        // Both columns read from the same left edge, and the words run into the width they have
        // been given rather than sitting in a centred block with the middle of the screen empty on
        // either side of them. Centred is right for a stacked sheet, where the column is the whole
        // screen; beside a picture it is two things centred against nothing in particular.
        val flow = if (sideBySide) Gravity.START or Gravity.CENTER_VERTICAL else Gravity.CENTER
        sheet.gravity = flow
        column.gravity = flow
        side.gravity = flow
        listOfNotNull(statusName, statusTitle, statusMeta, statusText, statusDetail).forEach {
            it.gravity = if (sideBySide) Gravity.START else Gravity.CENTER
        }
    }

    /**
     * Whether the poster goes beside the words rather than above them.
     *
     * Asked of the device and nothing else. The obvious question, "is this window landscape?",
     * cannot be asked here: the phone this was found on presents a portrait window rotated onto a
     * landscape panel, so the activity's own window bounds, display metrics, rotation and
     * Configuration.orientation all four answer "portrait" while the sheet is plainly being drawn
     * sideways. Anything measured from inside the app is measuring the lie.
     *
     * A handset gets the compact shape either way round, and it is the better of the two upright as
     * well: a small picture with the name beside it, and the whole of the failure and its buttons in
     * one glance. A television has the room for the poster it was designed around and keeps it.
     */
    private fun sheetGoesSideways(): Boolean = !FormFactor.isTv(this)

    private fun showStatus(message: String) {
        openingFilm = true
        layOutSheetForThisScreen()
        statusOverlay.visibility = View.VISIBLE
        statusIcon.visibility = View.GONE
        rebufferChip.visibility = View.GONE
        statusDetail.visibility = View.VISIBLE
        statusRetry?.visibility = View.GONE
        hideFailureActions()
        showLoadingProgress(if (statusProgress.visibility == View.VISIBLE) {
            statusProgress.progress / 1000f
        } else {
            0f
        })
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

    /**
     * @param retryable false for a failure no amount of trying again will change: a codec this
     *   device has not got, or a container nothing here can parse. Offering a button that is
     *   certain to fail again is worse than offering none.
     */
    private fun showError(message: String, retryable: Boolean = true) {
        openingFilm = false
        keepScreenOn(false)
        statusOverlay.visibility = View.VISIBLE
        // The only state that earns the warning triangle, matching the error screens the rest of
        // the app already shows.
        statusIcon.visibility = View.VISIBLE
        rebufferChip.visibility = View.GONE
        statusSpinner?.visibility = View.GONE
        statusProgress.visibility = View.GONE
        statusDetail.visibility = View.GONE
        layOutSheetForThisScreen()
        statusName?.apply {
            text = mediaTitle
            visibility = if (mediaTitle.isBlank()) View.GONE else View.VISIBLE
        }
        statusTitle.text = "Can't play this"
        statusText.text = message

        // Try again and Reload were two buttons for one intention, and the difference between them
        // was a paragraph about caches. There is one button now, and the app works out which of the
        // two things it means: see [reloadFromScratch].
        statusRetry?.visibility = View.GONE
        showFailureActions(retryable)
        updateDownloadChip()
    }

    /** Puts the sheet back to the one button it had, for every state that is not a failure. */
    private fun hideFailureActions() {
        statusReload?.visibility = View.GONE
        statusOpenWith?.visibility = View.GONE
        statusBack?.visibility = View.GONE
        statusName?.visibility = View.GONE
    }

    /**
     * Two buttons on a failure: the one that tries, and the one that leaves.
     *
     * This screen used to offer three, and the viewer was asked to tell Try again from Reload from
     * scratch by reading a paragraph about caches. That is this app's problem to solve, not
     * theirs. Reload is now the only attempt on offer, and what it does underneath depends on what
     * is actually wrong: a fresh prepare where that is all it takes, and a delete and a fresh fetch
     * where the bytes on disk are the thing that is broken. [Reload.plan] picks, exactly as before,
     * and a video the viewer downloaded on purpose is still never deleted by it.
     *
     * Go back is the other one, because "Press Back to pick something else" was an instruction
     * about a key on a device that has no keys.
     *
     * A failure no attempt can fix, a codec this device has not got, is where Reload steps aside:
     * pressing it would be honest work with a certain outcome. There the button is the handover to
     * an app that does have the codec, which is settled from disk and so arrives a moment after
     * the sheet does. The sheet is readable immediately either way.
     */
    private fun showFailureActions(retryable: Boolean) {
        statusReload?.visibility = View.GONE
        statusOpenWith?.visibility = View.GONE
        statusBack?.apply {
            visibility = View.VISIBLE
            if (!retryable) requestFocus()
        }
        if (fileId <= 0) return
        lifecycleScope.launch {
            if (retryable) {
                statusReload?.apply {
                    text = "Reload"
                    visibility = View.VISIBLE
                    requestFocus()
                }
            }

            val availability = runCatching { Td.localFileAvailability(fileId) }
                .getOrDefault(LocalFileAvailability.Missing)
            val downloaded = runCatching { Td.localDownloadedBytes(fileId) }.getOrDefault(0L)
            val state = ExternalPlayer.readiness(availability, downloaded)
            if (state == ExternalPlayer.Readiness.Nothing) return@launch
            // Offered on the failure another app fixes, and left off the one it does not: a stream
            // that would not start has nothing on disk worth handing anywhere.
            if (retryable) return@launch
            statusOpenWith?.apply {
                text = if (state == ExternalPlayer.Readiness.Complete) {
                    "Open in another app"
                } else {
                    "Open what's downloaded"
                }
                visibility = View.VISIBLE
                requestFocus()
            }
            // Said on the sheet rather than in a dialog on the way out: the viewer is being asked
            // to choose between two buttons, and the difference between them is this sentence.
            val caution = ExternalPlayer.caution(state, downloaded, fileSizeBytes)
            if (caution != null) {
                statusDetail.text = caution
                statusDetail.visibility = View.VISIBLE
            }
        }
    }

    /** Which of the two reloads this video gets, read off the two things that veto the destructive one. */
    private suspend fun reloadPlan(): Reload.Plan = Reload.plan(
        fileId = fileId,
        keptForOffline = runCatching { settings.isKeptDownload(chatId, messageId) }
            .getOrDefault(true),
        queuedForOffline = OfflineDownloads.isDownloading(fileId),
    )

    /**
     * Reload: throw away what is on disk for this video, then open the stream again from nothing.
     *
     * The failure this exists for is a partial file that is wrong rather than merely short, and
     * every plain retry over it fails identically, which is what makes "Can't play this" look like
     * a broken app rather than a broken cache. Deleting is safe here because the copy is a cache:
     * TDLib fetches it again from the message it came from, which is untouched.
     *
     * Safe in the other direction too. A video the viewer downloaded on purpose, or one the
     * download service is working on, falls back to a plain retry, because those bytes are not
     * this screen's to delete: see [Reload.plan]. The player's own fetch is cancelled first, so
     * TDLib is not writing into the file as it is being removed, and the entry comes out of the
     * downloads list as well as off the disk, so nothing resumes into the hole that was left.
     */
    private fun reloadFromScratch() {
        if (reloading) return
        reloading = true
        hideFailureActions()
        statusRetry?.visibility = View.GONE
        lifecycleScope.launch {
            val plan = runCatching { reloadPlan() }.getOrDefault(Reload.Plan.PlainRetry)
            showStatus(Reload.status(plan))
            if (plan == Reload.Plan.StartOver) {
                val session = Td.awaitAuthorizedSession()
                runCatching {
                    Td.cancelDownload(fileId)
                    // Off the downloads list as well as off the disk. Leaving the entry behind
                    // leaves TDLib with a record of a file it no longer has, which the Downloads
                    // screen would draw as a row with nothing under it.
                    session.client.removeFileFromDownloads(fileId, deleteFromCache = true)
                    Td.deleteFile(fileId)
                }
            }
            reloading = false
            // The player is thrown away rather than re-prepared: its media source is still holding
            // the descriptor of a file that has just been deleted, and preparing over that reads
            // the bytes this press was here to get rid of.
            // The surface goes with it, so [startPlayback] does not stack a second one over the
            // first: see [releasePlayerAndSurface].
            if (plan == Reload.Plan.StartOver) releasePlayerAndSurface()
            retryPlayback()
        }
    }

    /**
     * Hands this video to VLC, MX Player, or whatever else the device has.
     *
     * The resume position is written first, so a viewer who leaves for another player and comes
     * back lands where they left rather than at the start. The background download is deliberately
     * not cancelled: the other app is reading the same file, and pulling the rest of it out from
     * under it would be the one thing worse than not offering this at all.
     */
    fun openInAnotherApp() {
        saveResumePosition()
        lifecycleScope.launch {
            val outcome = runCatching {
                ExternalPlayer.handOver(this@PlayerActivity, fileId, mediaTitle)
            }.getOrElse { ExternalPlayer.Handoff.Refused("That video can't be handed to another app.") }
            when (outcome) {
                is ExternalPlayer.Handoff.Started -> outcome.caution?.let(::showGestureFeedback)
                ExternalPlayer.Handoff.NothingOnDisk ->
                    showGestureFeedback("Nothing of this video is on the device yet.")
                is ExternalPlayer.Handoff.Refused -> showGestureFeedback(outcome.reason)
            }
        }
    }

    /**
     * Starts the whole load again from where it stopped.
     *
     * A fresh prepare rather than a fresh activity: the position, the title, the download meter
     * and the episode search are all already set up against this file, and the failures that reach
     * this button are the ones a second attempt fixes, so there is nothing to rebuild.
     */
    private fun retryPlayback() {
        statusRetry?.visibility = View.GONE
        recoveryAttempts = 0
        showStatus("Trying again…")
        val exo = player
        if (exo != null) {
            exo.prepare()
            exo.playWhenReady = true
            return
        }
        lifecycleScope.launch {
            val session = Td.awaitAuthorizedSession()
            if (session.isCurrent()) startPlayback(session.client)
        }
    }

    private fun hideStatus() {
        openingFilm = false
        statusOverlay.visibility = View.GONE
        stopArtDrift()
        rebufferChip.visibility = View.GONE
        updateEpisodeRow()
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
        // In picture in picture the activity is stopped while the video is still on screen and
        // still the point of it; pausing here would stop the very thing the mode exists for.
        if (!inPictureInPicture) player?.pause()
    }

    override fun onDestroy() {
        stopArtDrift()
        saveResumePosition()
        stopDownload()
        trimCache()
        gestureHud?.removeCallbacks(hideGestureHud)
        // Released before the player it wraps, or it is left holding a released instance.
        mediaSession?.release()
        mediaSession = null
        // Dropped before the player is released so the view never holds a released instance.
        touchSurface?.player = null
        touchSurface = null
        player?.removeListener(playerListener)
        player?.release()
        player = null
        super.onDestroy()
    }

    /**
     * Tells TDLib to stop fetching this video once nobody is watching it.
     *
     * Streaming asks for everything from the current byte to the end of the file, and TDLib
     * honours that long after the activity has gone: backing out of a 12 GB remux left the stick
     * pulling all 12 GB down in the background, filling the disk it had just been asked to make
     * room in. A finished file is left alone, since there is nothing left to cancel and the
     * bytes already on disk are what makes offline playback work.
     */
    private fun stopDownload() {
        if (downloadComplete) return
        val id = fileId
        if (id <= 0) return
        // Not this one: a download the viewer asked to keep is being fetched by the service, and
        // closing a player that happened to be watching the same file is not a reason to abandon
        // it. Only the bytes this screen pulled in for itself are the player's to cancel.
        if (OfflineDownloads.isDownloading(id)) return
        App.backgroundScope.launch {
            runCatching { Td.cancelDownload(id) }
        }
    }

    /**
     * Puts the cache back under its ceiling on the way out of a video.
     *
     * It ran once, at launch, which is the one moment nothing has been downloaded yet. An evening
     * of four episodes on a stick therefore ended four episodes over the ceiling, and the trim
     * that was meant to prevent it did not run again until the app was next started, by which
     * time the disk was full and the viewer was in Settings looking for something to delete.
     * Leaving a video is the natural moment: something has just been fetched, and nothing is
     * playing that a deletion could interrupt.
     */
    private fun trimCache() {
        App.backgroundScope.launch { runCatching { Td.trimStorage() } }
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
        private const val EXTRA_THUMBNAIL_ID = "thumbnail_id"
        private const val EXTRA_MINI_THUMBNAIL = "mini_thumbnail"

        private const val BUFFER_MIN_MS = 15_000
        private const val BUFFER_MAX_MS = 50_000
        private const val BUFFER_PLAYBACK_MS = 2_500
        private const val BUFFER_REBUFFER_MS = 5_000
        private const val TARGET_BUFFER_BYTES = 20 * 1024 * 1024

        /** Enough for the back-seek a thumb makes when it missed a line of dialogue. */
        private const val BACK_BUFFER_MS = 10_000
        private const val END_GUARD_MS = 1_000L
        /** What a held finger runs the picture at, the same figure every player uses for it. */
        private const val HOLD_SPEED = 2f

        /** The buffered and unbuffered halves of the scrub bar, over any frame. */
        private const val BUFFERED_COLOR = 0x66FFFFFF.toInt()
        private const val UNPLAYED_COLOR = 0x33FFFFFF.toInt()
        private const val RESUME_TICK_MS = 10_000L

        /** Twice a second: faster than the eye needs and slower than TDLib talks. */
        private const val PROGRESS_RENDER_MS = 500L

        /** The artwork's way in: long enough to read as a fade, short enough not to be a wait. */
        private const val ART_FADE_MS = 320L

        /**
         * How strongly the backdrop is drawn.
         *
         * A gradient alone was not enough, and could not be: half of what these channels use as a
         * thumbnail is a white logo on white, which came through the scrim as a pale grey wall
         * with the title fighting it. Dimming the picture itself is the part that works on every
         * thumbnail rather than on the dark ones, and it leaves a photograph perfectly legible as
         * a backdrop while it is at it.
         */
        private const val ART_ALPHA = 0.3f

        /** How far the poster rises as it arrives, in dp. */
        private const val POSTER_RISE_PX = 14f

        /**
         * The backdrop's slow drift, out and back.
         *
         * Twelve per cent over twenty seconds is about a pixel a second on a 1080p panel: felt
         * rather than watched, which is the whole intent. Anything faster reads as an effect and
         * competes with the picture that is about to start.
         */
        private const val ART_DRIFT_SCALE = 1.12f
        private const val ART_DRIFT_MS = 20_000L

        /** Long enough to read the figure a drag left behind, short enough to stay out of the way. */
        private const val GESTURE_HUD_MS = 900L

        /** Long enough to read the row and reach for something on it, short enough to get out. */
        private const val CONTROLLER_TIMEOUT_MS = 3_500

        /** Long enough to read the next title and to stop it; short enough not to be a wait. */
        private const val AUTOPLAY_COUNTDOWN_SEC = 8

        /**
         * Four attempts over roughly twelve seconds. Enough to ride out a moved download window or
         * a lift lost signal, short enough that a video which really will not play says so.
         */
        private const val MAX_RECOVERY_ATTEMPTS = 4
        private const val RECOVERY_BACKOFF_MS = 800L

        /** 1..32; the same top slot the streaming path asks for. */
        private const val DOWNLOAD_PRIORITY = 32

        /** Deep enough for Media3's own wrapping, short enough not to walk a cycle. */
        private const val MAX_CAUSE_HOPS = 6

        private const val SUBTITLE_TEXT_FRACTION = 0.065f

        /** The gap between the poster and the words, beside them and above them. */
        private const val POSTER_GAP_DP = 24f
        private const val POSTER_STACK_GAP_DP = 22f

        /** The picture's slice of a sideways sheet, and the name that sits under it. */
        private const val SIDE_POSTER_WIDTH_DP = 176f
        private const val SIDE_POSTER_HEIGHT_DP = 99f
        private const val NAME_GAP_DP = 10f
        private const val SIDE_NAME_LINES = 3
        private const val STACK_NAME_LINES = 2
        private const val NAME_STACK_MAX_WIDTH_DP = 720f

        /** Anything at or above this is 4K territory, where a stick's decoder gives up. */
        private const val UHD_HEIGHT = 1600

        /** Android's own limits on a picture-in-picture window's shape. */
        private const val PIP_MIN_RATIO = 1f / 2.39f
        private const val PIP_MAX_RATIO = 2.39f

        /**
         * Whether the viewer has already said yes to mobile data this session.
         *
         * Process-wide rather than per activity, because stepping through a series builds a new
         * activity per episode and being asked again at every one is the prompt becoming the
         * problem it was added to solve.
         */
        private var meteredWarningAccepted = false

        /**
         * The rotation lock the viewer last chose, for the length of the process.
         *
         * Process-wide for the same reason the metered warning is: stepping through a series builds
         * a new activity per episode, and a lock that had to be set again at every one is a button
         * that has stopped being useful. Not written to disk, because the only settings store in the
         * app belongs to another part of the codebase and this needs a key added to it.
         */
        private var lastOrientation = ScreenOrientation.DEFAULT

        /**
         * The lock read back off disk at startup, so it survives more than one process.
         *
         * Set from the application rather than read here, because the orientation has to be
         * applied before the activity lays anything out and there is no room in front of that for
         * a disk read. Anything unrecognised leaves the default alone: this is a remembered
         * preference, not a source of truth, and the wrong way up is not worth a crash.
         */
        fun primeOrientation(name: String?) {
            val remembered = ScreenOrientation.entries.firstOrNull { it.name == name } ?: return
            lastOrientation = remembered
        }

        fun intent(context: Context, item: MediaItem, chatTitle: String = ""): Intent =
            Intent(context, PlayerActivity::class.java).apply {
                putExtra(EXTRA_FILE_ID, item.fileId)
                putExtra(EXTRA_SIZE, item.sizeBytes)
                putExtra(EXTRA_DURATION, item.durationSec)
                putExtra(EXTRA_CHAT_ID, item.chatId)
                putExtra(EXTRA_MESSAGE_ID, item.messageId)
                putExtra(EXTRA_TITLE, item.title)
                putExtra(EXTRA_CHAT_TITLE, chatTitle)
                // The picture the grid was already showing, carried across rather than fetched
                // again: the loading screen is drawn from it, and it is the one thing that can be
                // on screen in the first frame, before Telegram has been asked anything at all.
                putExtra(EXTRA_THUMBNAIL_ID, item.thumbnailFileId)
                putExtra(EXTRA_MINI_THUMBNAIL, item.miniThumbnail)
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
