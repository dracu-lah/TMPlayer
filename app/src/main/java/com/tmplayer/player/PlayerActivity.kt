package com.tmplayer.player

import android.annotation.SuppressLint
import android.app.PictureInPictureParams
import android.content.Context
import android.os.Looper
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.util.Rational
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import com.tmplayer.ui.components.haptic
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.addCallback
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.doOnPreDraw
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePadding
import androidx.fragment.app.FragmentActivity
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem as Media3Item
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.text.Cue
import androidx.media3.common.text.CueGroup
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.ChannelMixingAudioProcessor
import androidx.media3.common.audio.ChannelMixingMatrix
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.AudioTrackAudioOutputProvider
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.text.TextOutput
import androidx.media3.exoplayer.audio.DefaultAudioTrackBufferSizeProvider
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.session.MediaSession
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.ui.PlayerView
import androidx.media3.ui.SubtitleView
import androidx.lifecycle.Lifecycle
import com.tmplayer.data.guardedLifecycleScope as lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.withStarted
import com.tmplayer.App
import com.tmplayer.R
import com.tmplayer.data.ChatRepository
import com.tmplayer.data.CopyHandoff
import com.tmplayer.data.VideoCopies
import com.tmplayer.data.copyQuality
import com.tmplayer.data.hasPictureInPicture
import com.tmplayer.data.LocalDownloads
import com.tmplayer.data.OpeningStall
import com.tmplayer.data.start
import com.tmplayer.data.pauseAll
import com.tmplayer.data.resume
import com.tmplayer.data.Failures
import com.tmplayer.data.FormFactor
import com.tmplayer.data.DeviceQuirks
import com.tmplayer.data.RemoteQuirks
import com.tmplayer.data.Trickplay
import com.tmplayer.data.WatchNext
import com.tmplayer.data.WatchNextPublisher
import com.tmplayer.data.MediaName
import com.tmplayer.data.EpisodeNeighbours
import com.tmplayer.data.EpisodeSteps
import com.tmplayer.data.EpisodeTag
import com.tmplayer.data.SeriesShelf
import com.tmplayer.data.MessageLink
import com.tmplayer.data.MediaItem
import com.tmplayer.data.MediaMapper
import com.tmplayer.data.LocalFileAvailability
import com.tmplayer.data.MeteredDecision
import com.tmplayer.data.MeteredPolicy
import com.tmplayer.data.LocalFilePolicy
import com.tmplayer.data.NetworkMonitor
import com.tmplayer.data.NetworkStatus
import com.tmplayer.data.ResumeRecord
import com.tmplayer.data.ResumeRules
import com.tmplayer.data.ResumeState
import com.tmplayer.data.SettingsStore
import com.tmplayer.data.TrackChoice
import com.tmplayer.data.OfflineDownloads
import com.tmplayer.data.CacheShelf
import com.tmplayer.data.DiskSpace
import com.tmplayer.data.RoomOnDisk
import com.tmplayer.data.Td
import com.tmplayer.data.Thumbnails
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.unit.dp
import com.tmplayer.data.WatchCache
import com.tmplayer.data.WatchedRecord
import com.tmplayer.data.WatchedStore
import com.tmplayer.data.Bookmark
import com.tmplayer.data.BookmarkJump
import com.tmplayer.data.BookmarkStore
import com.tmplayer.data.errorMessage
import com.tmplayer.data.valueOrNull
import com.tmplayer.i18n.L
import com.tmplayer.i18n.Languages
import com.tmplayer.i18n.Translator
import dev.g000sha256.tdl.TdlClient
import dev.g000sha256.tdl.dto.File as TdFile
import io.github.anilbeesetti.nextlib.media3ext.ffdecoder.NextRenderersFactory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import com.tmplayer.online.MovieHash
import com.tmplayer.online.EpisodeNames
import com.tmplayer.online.OnlineMetadata
import com.tmplayer.online.SkipSegment
import com.tmplayer.online.SkipSegments
import com.tmplayer.online.OnlineSubtitles
import com.tmplayer.online.SubtitleTarget
import java.io.File

/**
 * Full-screen playback of one Telegram file.
 *
 * Owns the ExoPlayer so the leanback fragment and the track pickers can come and go without
 * interrupting the stream.
 */
@UnstableApi
class PlayerActivity : FragmentActivity(), TrackPickerHost {

    override var player: ExoPlayer? = null
        private set

    /**
     * Casting to a Chromecast or Google TV (CP73): the Cast button's picker and the screen that
     * stands in for the picture while the video is on the TV. Null on a television, in the F-Droid
     * build and without Google Play services.
     */
    private var cast: PlayerCast? = null

    /** The TDLib client the stream was opened with, for the cast server's reads. */
    private var castClient: TdlClient? = null

    lateinit var mediaTitle: String
        private set
    lateinit var mediaSubtitle: String
        private set

    /**
     * The file's own name and the post's caption, which [mediaTitle] is one or the other of. The
     * series view reads both to place an episode, and so does [findEpisodes].
     */
    private var mediaFileName = ""
    private var mediaCaption = ""

    /** The file's type, for telling sound alone (a chat's audio, [ChatAudio]) from a video. */
    private var mediaMime = ""
    private val isAudio: Boolean get() = mediaMime.startsWith("audio/", ignoreCase = true)

    /** What stands in for the picture while sound alone plays: its name and where it is from. */
    private var audioCard: View? = null

    /** The forum topic the post is in, or 0, for the speed remembered per topic. */
    private var mediaTopicId = 0

    private var chatId = 0L
    private var messageId = 0L

    private lateinit var settings: SettingsStore
    private lateinit var watchedStore: WatchedStore
    private lateinit var bookmarkStore: BookmarkStore

    /** This video's bookmarks as the store has them, for the menus and the bookmark sheets. */
    private val bookmarkList = MutableStateFlow<List<Bookmark>>(emptyList())

    /** Whether this video is on the Watched list, for which way the menus' mark line reads. */
    private var onWatchedList = false

    /**
     * Set when the viewer marks the video watched from this player. The position is then
     * forgotten on the way out rather than saved, or leaving would put the video straight back
     * into Continue watching a moment after they took it out.
     */
    private var markedWatchedHere = false

    /**
     * Set once leaving past 90 % has put this video on the Watched list, so going to the
     * background and back again does not mark it a second time.
     */
    private var watchedHere = false
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

    /** "Try the other decoder", on a decoding failure the other decoder might get past. See [DecoderSwitch]. */
    private var statusDecoder: android.widget.Button? = null

    /** The switch [statusDecoder] would make, worked out from the failure on screen. */
    private var decoderPlan: DecoderSwitch.Plan? = null

    /** Settings' Decoder, read before the player is built: it decides the renderers' order. */
    private var videoDecoder = com.tmplayer.data.VideoDecoder.Automatic

    /** "Play Telegram's 480p" on the sheet that says nothing is arriving (CP80). */
    private var statusLighter: android.widget.Button? = null

    /**
     * Whether Telegram lets this video be kept or handed on: false for a chat that restricts saving
     * content, null until that is known.
     *
     * Every way out of Telegram's hands (Save to Downloads, another app) waits for a true. A false
     * carried in on the intent is believed at once; a true is not, because Continue watching and
     * the downloads list build their items from records that never knew, so it is asked again. A
     * question Telegram cannot answer counts as yes, which is how the player behaved before.
     */
    private var savable: Boolean? = null

    /** The failure sheet's way out, and the video's own name on it. */
    private var statusBack: android.widget.Button? = null
    private var statusName: TextView? = null

    /** Set while [reloadFromScratch] is clearing the file, so a second press cannot race it. */
    private var reloading = false

    /** The transport overlay, one design on every device. Built in [onCreate], lives as long. */
    private var controls: PlayerControls? = null

    /** The television's More menu, the phone's overflow in its own form. Null on a phone. */
    private var tvMenu: PlayerTvMenu? = null

    /** The speed, sleep timer and playback details sheets, on a phone and a television alike. */
    private var sheets: PlayerSheets? = null

    /** The episode list the row's Episodes button opens (CP40), on a phone and a television. */
    private var episodesList: PlayerEpisodes? = null

    /** [episodesList] is up. */
    private var episodesOpen = false

    /** The autoplay setting as the episode list shows it, read when the list opens. */
    private var autoplayNext = true

    /** The overflow or the television's More menu is open. */
    private var menuOpen = false

    /**
     * One of [sheets] (speed, sleep timer, playback details) is open. Its own flag, because it is
     * opened from the overflow, whose dismissal arrives after the sheet is already up.
     */
    private var detailsOpen = false

    /** How far the picture stops short of the screen's bottom edge; see [fitSubtitlesTo]. */
    private var subtitleGapBelow = 0

    /**
     * While 3D is drawn for a VR headset or a 3D TV ([Stereo3d.View.Vr]): the subtitles' second
     * copy, over the right eye's half, the first one over the left's ([fitSubtitlesTo]). Each eye
     * then sees the whole line, where one copy across the middle was cut in two.
     */
    private var subtitleTwin: SubtitleView? = null

    /** The lines on screen, for a [subtitleTwin] that comes up while one is showing. */
    private var shownCues: List<Cue> = emptyList()

    /** A track picker, or the online subtitle list above one, is open. */
    private var pickerOpen = false

    /** What this device's remote does differently: Fire TV's Menu key and held seeks. */
    private val remoteQuirks: RemoteQuirks by lazy { DeviceQuirks.remote(this) }

    /** True once a held Menu became the long press, so its release opens nothing. */
    private var menuHeld = false

    /** Scrub thumbnails, when the setting and the device's memory allow them. See [Trickplay]. */
    private var trickplay: TrickplayFrames? = null

    /** TDLib's file for this video, while it is still TDLib's (not moved into Downloads). */
    private var trickplayPath: String? = null

    /** The byte runs seen downloaded since the video opened, merged. Main thread only. */
    private var trickplaySpans: List<Trickplay.Span> = emptyList()

    /**
     * Whether [trickplaySpans] are worth keeping: false once [startTrickplay] found the device too
     * short of memory for scrub thumbnails, or the setting off. Every TDLib update of the file
     * (ten to thirty a second while it streams) merged a fresh list there, on the main thread of
     * the 1 GB sticks that never read it.
     */
    private var trickplaySpansWanted = true

    /** Subtitle files loaded from the phone; a field because its picker registers before start. */
    private val subtitleFiles = SubtitleFiles(this, { player }, { showGestureFeedback(it) })

    /** The viewer's Downmix to stereo answer, or null for the device default. See [AudioDownmix.wanted]. */
    private var downmixChoice: Boolean? = null

    /**
     * How far this file's subtitles and sound are moved against the picture, read with the other
     * settings before the player is built and written back on every step. See [SyncDelays].
     */
    private var syncDelays = SyncDelays()

    /** [syncDelays]' subtitle half in microseconds, for [DelayedTextRenderer] on the playback thread. */
    @Volatile
    private var subtitleDelayUs = 0L

    /** The audio half's processor, owned by the current player's sink. */
    private var audioOffset: AudioOffsetProcessor? = null

    /** How subtitles are drawn. Shared with Settings and the desktop. */
    private var subtitleStyle = SubtitleStyle()

    /** Volume boost as the viewer left it, read before the player is built. */
    private var volumeBoostOn = false

    /** Night mode for sound, as the viewer left it, read before the player is built. */
    private var nightModeOn = false

    /**
     * 3D video as chosen for this player ([Stereo3d]): off for every player built, and kept for
     * an episode that follows in the same one, which is most likely 3D too.
     */
    private var stereo = Stereo3d.OFF

    /**
     * Whether this player has Media3's effects pipeline. It can only be built as the player
     * starts, so it is built up front only for a file whose name says 3D, and otherwise on the
     * first time 3D is chosen, by preparing the player again where it stands ([applyStereo]).
     * Every other video keeps the plain path to the screen, with nothing in between.
     */
    private var stereoPipeline = false

    /** The 3D layout the file's name declares, for the hint and the page's focus. */
    private var stereoTagged: Stereo3d.Layout? = null

    /** Whether the hint that the file looks 3D has been shown for this video. */
    private var stereoHinted = false

    /** Even out volume, read with the others before the player is built; see [VolumeBoostProcessor.level]. */
    private var evenVolumeOn = true

    /**
     * Skip silence, switched under the speeds and kept as a setting. Media3 drops the quiet stretches
     * in the audio sink's own chain, so it works on the PCM the sink plays and does nothing to a
     * track passed through to a receiver as a bitstream.
     */
    private var skipSilence = false

    /** The boost's processor, owned by the current player's sink. See [VolumeBoostProcessor]. */
    private var volumeBoost: VolumeBoostProcessor? = null

    /**
     * True while "Still watching?" is up. Playback is stopped under it and so are the downloads,
     * and [downloadsHeldForTheCard] are the queued ones it paused, to carry on when somebody answers.
     */
    private var stillWatchingUp = false
    private var downloadsHeldForTheCard: List<Int> = emptyList()

    /**
     * Which way up the picture is held, and whether the viewer has said so themselves.
     *
     * The flag keeps the two orientation rules from fighting: a portrait video may turn the window
     * on its own, but only until somebody presses the button, after which the choice is theirs.
     */
    private var orientation = lastOrientation
    private var orientationChosen = false

    /** The video surface, on every device. Null until playback starts. */
    private var touchSurface: PlayerView? = null

    private var fileId = 0

    /**
     * The video's file in the Downloads folder, when it is a download, and then the whole of
     * playback comes from it: no TDLib stream, no room check, no claim on the cache, no connection.
     * Set once, before the player is built.
     */
    private var downloadedFile: File? = null
    private var fileSizeBytes = 0L
    private var durationSec = 0

    // ---- Telegram's own qualities, cover, start time and storyboard (CP80) --------------------

    /** The message as Telegram has it now, once read: its lighter copies, its start time, its storyboard. */
    private var postItem: MediaItem? = null

    /** The height of Telegram's copy playing in place of the upload, or 0 for the upload. */
    private var playingQuality = 0

    /** Telegram's copies of the video as the list that opened it had them ([EXTRA_QUALITY_FILES]); empty when none came. */
    private var carriedQualities: List<com.tmplayer.data.TelegramQuality> = emptyList()

    /** The upload's file and size while [playingQuality] plays in its place. */
    private var uploadFileId = 0
    private var uploadSizeBytes = 0L

    /** A t.me link's `?t=`, in seconds, for a player opened from one; 0 for none. See [PostStart]. */
    private var startLinkSec = 0

    /** Telegram's seek thumbnails for this video, once read. See [StoryboardThumbnails]. */
    private var storyboardFrames: StoryboardThumbnails? = null

    /** The video's own thumbnail, for the scrub card's fallback where no picture of the moment comes. */
    private var scrubArt: android.graphics.Bitmap? = null
        set(value) {
            field = value
            controls?.previewArt = value
        }

    /** Why a lighter copy was picked by the player itself, said once it plays; null for none. */
    private var qualityPickedWhy: com.tmplayer.data.TelegramQualities.Why? = null

    // ---- a picture this device decodes in software (see [SoftwareDecode]) ----------------------

    /** Whether the copy playing was the viewer's choice ([QualityWish]), which the player never swaps by itself. */
    private var qualityHandPicked = false

    /** Whether Save mobile data was in force over mobile data as this video opened ([pickTelegramQuality]). */
    private var saveDataAtOpen = false

    /** Whether this video's picture was looked at for software decoding yet ([checkSoftwareDecode]). */
    private var softwareChecked = false

    /** Whether this player already went for a lighter copy over software decoding: never twice. */
    private var softwareSwapTried = false

    /**
     * The opt-in "could not keep up" event (HX2b): the picture's format, the decoder, what the
     * judge predicted and the watch measured, and the device; never the title, file, chat or
     * message. [CrashReports.reportStutter] sends nothing with the switch off.
     */
    private fun reportCouldNotKeepUp(picture: com.tmplayer.data.DecodeJudge.Picture?) {
        if (picture == null || !com.tmplayer.data.CrashReports.available) return
        val format = watchFormat
        val figures = watch.figures()
        val prediction = format?.let { DeviceDecode.verdictOf(it) }?.name ?: "UNKNOWN"
        val facts = com.tmplayer.data.StutterReport.Facts(
            codec = picture.family,
            profile = format?.codecs.orEmpty(),
            bitDepth = picture.bitDepth,
            width = picture.width,
            height = picture.height,
            fps = picture.fps,
            bitrateKbps = (format?.let { maxOf(it.averageBitrate, it.bitrate) } ?: 0).coerceAtLeast(0) / 1000,
            container = format?.containerMimeType.orEmpty(),
            decoder = watchDecoder,
            hardware = !watchSoftware,
            prediction = prediction,
            speedPercent = figures.speedPercent,
            dropped = figures.dropped,
            frames = figures.frames,
            underruns = figures.underruns,
            manufacturer = android.os.Build.MANUFACTURER.orEmpty(),
            model = android.os.Build.MODEL.orEmpty(),
            androidVersion = android.os.Build.VERSION.RELEASE.orEmpty(),
            cores = Runtime.getRuntime().availableProcessors(),
            abi = android.os.Build.SUPPORTED_ABIS.firstOrNull().orEmpty(),
            appVersion = com.tmplayer.BuildConfig.VERSION_NAME,
            sizeBucket = picture.sizeBucket,
        )
        Thread { runCatching { com.tmplayer.data.CrashReports.reportStutter(facts) } }.start()
    }

    // ---- the watchdog over the first seconds of play (HX2, [PlaybackWatch]) --------------------

    private val watch = PlaybackWatch()

    /** Frames dropped and audio underruns since this player started, as Media3 reports them. */
    private var watchDropped = 0
    private var watchUnderruns = 0

    /** The picture watched, and whether the app's software decoder has it, read as the watch starts. */
    private var watchPicture: com.tmplayer.data.DecodeJudge.Picture? = null
    private var watchSoftware = false

    /** The video decoder Media3 started, for the opt-in "could not keep up" report (HX2b). */
    private var watchDecoder = ""

    /** The video format watched, for the same report. */
    private var watchFormat: androidx.media3.common.Format? = null

    /** Whether this video got its "Stuttering?" offer or line: once per video. */
    private var stutterOffered = false

    /** One sample a second while the video plays, and none while it does not; done after the answer. */
    private val watchTick = Runnable { sampleWatch() }

    /** Whether the copy playing is the one the viewer picked for this video, which is never swapped by itself. */
    private var copyHandPicked = false

    /** The copy passed over as too heavy here, named as the picture lands ([SmootherPick]). */
    private var passedOver: String? = null

    /** What the picture is, for the lines that name it ("AV1 10-bit"), once judged. */
    private var heavyLabel: String? = null

    /** The once-per-video line that the picture decodes in software, waiting for the first frame. */
    private var softwareNote: String? = null

    /** Whether that line is on screen now, so the next key puts it away. */
    private var softwareNoteUp = false

    /** A television, or a device short of memory: where software decoding of a large picture stutters. */
    private val weakDevice: Boolean by lazy {
        com.tmplayer.data.SoftwareDecode.weak(
            FormFactor.isTv(this),
            runCatching { com.tmplayer.ui.onboarding.DeviceProbes.fit(this) }.getOrDefault(com.tmplayer.data.DeviceFit.Fine),
        )
    }

    /** The upload's id and size, whichever copy plays: what the place, a download and the copies speak of. */
    private fun uploadId(): Int = if (playingQuality != 0 && uploadFileId != 0) uploadFileId else fileId
    private fun uploadSize(): Long = if (playingQuality != 0 && uploadFileId != 0) uploadSizeBytes else fileSizeBytes
    private var resumeMs = 0L

    /** When the press that opened this video happened, from the intent ([EXTRA_PRESSED_AT]); 0 unknown. */
    @Volatile
    private var pressedAt = 0L

    /** When this player was created, for the timing log when the press is not known. */
    @Volatile
    private var openedAt = 0L

    /**
     * The opening's stages already written to the start log ([markStart]), one bit each. Atomic,
     * because the first bytes are counted on Media3's loading thread.
     */
    private val startStages = java.util.concurrent.atomic.AtomicInteger()

    /**
     * Writes one stage of this video's opening to the start log (`adb logcat -s PlayerStart`),
     * once, as the time since the press (or since the player opened when the press is not known):
     * the line a device pass reads to see where the seconds before the first frame go. Nothing
     * once the first frame is up, so a seek or a retry later adds no lines.
     */
    private fun markStart(stage: String, bit: Int) {
        val opened = openedAt
        if (opened == 0L) return
        while (true) {
            val seen = startStages.get()
            if (seen and bit != 0) return
            if (startStages.compareAndSet(seen, seen or bit)) break
        }
        val pressed = pressedAt
        val now = SystemClock.elapsedRealtime()
        val since = if (pressed > 0) "press" else "open"
        Log.i(START_TAG, "$stage at ${now - (if (pressed > 0) pressed else opened)} ms since $since (file $fileId)")
    }

    /**
     * Completed once this video's first frame is up, or its opening has failed: the lookups that
     * only feed the menus and the end of the video wait for it on a television ([afterOpening]).
     */
    private var openedGate = CompletableDeferred<Unit>()

    /**
     * On a television, waits until the picture is up (at most [OPENING_GATE_MS]) before the work
     * that is not needed for it: the episode and copy searches, the skip times, the storyboard and
     * the session's artwork. On a 1 GB stick with four small cores each of them is a TDLib search,
     * a network lookup or an image encode competing with the stream, the demuxer and the decoder
     * for the same CPU while the viewer waits. Elsewhere it returns at once, as before.
     */
    private suspend fun afterOpening() {
        if (!FormFactor.isTv(this)) return
        withTimeoutOrNull(OPENING_GATE_MS) { openedGate.await() }
    }

    /** Whether this video's picture tracks were looked at yet ([pictureUnplayable]). */
    private var pictureChecked = false

    /**
     * True, with the failure sheet up, when the video has a picture and this device can decode none
     * of it: no decoder for the format at all (VC-1, an HEVC profile, AV1 on an older chip).
     *
     * Media3 raises no error for that: its track selector leaves an unsupported video track out and
     * plays the rest, so the film played as sound over a black screen with nothing to say why. Now
     * it stops and says the format is not playable here, with Open in another app where the chat
     * allows it ([showError], not retryable). A track Media3 chose to leave out for another reason,
     * while one is playable, is not this case; nor is a picture that is only cover art.
     */
    private fun pictureUnplayable(tracks: Tracks): Boolean {
        if (pictureChecked || isAudio) return false
        val video = tracks.groups.filter { group ->
            group.type == C.TRACK_TYPE_VIDEO &&
                (0 until group.length).none { group.getTrackFormat(it).sampleMimeType?.startsWith("image/") == true }
        }
        if (video.isEmpty()) return false
        pictureChecked = true
        if (video.any { it.isSelected }) return false
        if (video.any { group -> (0 until group.length).any { group.isTrackSupported(it, true) } }) return false
        val format = video.first().getTrackFormat(0)
        Log.w("TMPlayer", "No decoder for the picture: ${format.sampleMimeType} ${format.codecs.orEmpty()} ${format.width}x${format.height}")
        player?.pause()
        val lines = nominalLines(format.width, format.height)
        showError(
            when {
                FormatRoute.dolbyVisionOnly(format) -> L.playerErrorDolbyVision
                lines >= UHD_HEIGHT -> L.playerErrorUhd(lines.toString())
                else -> L.playerErrorFormat
            },
            retryable = false,
        )
        return true
    }

    /**
     * Looks once at the picture Media3 chose and, when this device would decode it in software and
     * too slowly ([SoftwareDecode.heavy]: AV1 on a TV stick with no AV1 silicon), either plays a
     * lighter copy of Telegram's that the hardware takes, from the same moment, or (with no such
     * copy) readies the once-per-video line that it may stutter, said on a television only.
     *
     * Returns true when the player is handing over to the lighter copy, so the rest of the track
     * handling is not done for a player about to go. Never over the viewer's own choice of copy
     * ([qualityHandPicked]), a copy already playing, a joined or downloaded video (whose file is
     * the one there is), or while casting, where the TV decodes the picture.
     */
    private fun checkSoftwareDecode(tracks: Tracks): Boolean {
        if (softwareChecked || isAudio) return false
        val group = tracks.groups.firstOrNull { it.type == C.TRACK_TYPE_VIDEO && it.isSelected } ?: return false
        val format = (0 until group.length).firstOrNull { group.isTrackSelected(it) }?.let { group.getTrackFormat(it) } ?: return false
        if (format.sampleMimeType?.startsWith("image/") == true) return false
        softwareChecked = true
        if (castUp()) return false
        // One judgement for every file on every device ([DecodeJudge]): the codec list for the
        // hardware answer, the CPU and what this device remembered for the software one.
        val verdict = DeviceDecode.verdictOf(format)
        val picture = DeviceDecode.pictureOf(format)
        Log.i(
            "TMPlayer",
            "Picture judged ${verdict.name.lowercase()} here: ${format.sampleMimeType} ${format.codecs.orEmpty()} " +
                "${format.width}x${format.height} ${format.frameRate} fps, load " +
                "${picture?.let { com.tmplayer.data.DecodeJudge.load(it, DeviceDecode.cpu) } ?: "?"}% of ${DeviceDecode.cpu}",
        )
        if (verdict != com.tmplayer.data.DecodeJudge.Verdict.SOFTWARE_TOO_SLOW) return false
        heavyLabel = picture?.label
        heavyKind = picture?.let { com.tmplayer.data.DecodeMemory.keyOf(it) }
        com.tmplayer.data.SoftwareDecode.rememberHeavy(chatId, messageId)
        // A copy of the same video posted on its own that plays smoothly here: taken at the start,
        // before the viewer has seen anything, unless the viewer picked this copy themselves.
        if (!copyHandPicked && !qualityHandPicked && joinedSource == null && !castUp()) {
            smootherCopy()?.let { copy ->
                SmootherPick.offer(copy, currentItem().copyQuality())
                switchCopy(copy, remember = false)
                return true
            }
        }
        // 4K in software on this device plays at a few frames a second: with no lighter copy to
        // hand over to, the sheet says it is too large rather than starting a slide show. Only
        // when the FFmpeg decoder really took the picture: a device decoder that undersells
        // itself is left to try, and its failure says the same thing.
        val unwatchable = com.tmplayer.data.SoftwareDecode.unwatchable(
            format.sampleMimeType,
            format.width,
            format.height,
            hardware = false,
            weak = weakDevice,
        ) && DecoderSwitch.softwareVideoChosen(player)
        // The copy the viewer picked plays as picked, with the one line that names its codec.
        val swappable = playingQuality == 0 && !qualityHandPicked && !softwareSwapTried && !copyHandPicked &&
            downloadedFile == null && joinedSource == null && hasMessage()
        if (!swappable) {
            if (unwatchable) return tooLargeForSoftware(format)
            noteSoftwareDecode(playing = false)
            return false
        }
        val copies = postItem?.qualities?.takeIf { it.isNotEmpty() } ?: carriedQualities
        if (copies.isNotEmpty()) {
            when (swapForSoftware(copies)) {
                Swap.HANDED_OVER -> return true
                Swap.ASKED -> return false
                Swap.NONE -> Unit
            }
            if (unwatchable) return tooLargeForSoftware(format)
            if (askHeavy(null, copies)) return false
            noteSoftwareDecode(playing = false)
            return false
        }
        // The copies not known yet: the message is being read as the player opens ([readPost]),
        // and the swap waits a moment for it, the upload playing meanwhile (held, when it would
        // only stutter).
        softwareSwapTried = true
        if (unwatchable) player?.pause()
        itemScope.launch {
            val later = kotlinx.coroutines.withTimeoutOrNull(POST_READ_MS) { readPost() }?.qualities.orEmpty()
            val swap = if (later.isEmpty()) Swap.NONE else swapForSoftware(later)
            if (swap == Swap.NONE) {
                when {
                    unwatchable -> tooLargeForSoftware(format)
                    askHeavy(null, later) -> Unit
                    else -> noteSoftwareDecode(playing = true)
                }
            }
        }
        return false
    }

    /**
     * The failure sheet for a picture only the FFmpeg decoder takes here and too large for it
     * ([com.tmplayer.data.SoftwareDecode.unwatchable]): the same sentence as a hardware decoder
     * that gives up on 4K, naming the height and that a 1080p copy will play. Always true, for
     * [checkSoftwareDecode].
     */
    private fun tooLargeForSoftware(format: androidx.media3.common.Format): Boolean {
        Log.w("TMPlayer", "Too large to decode in software here: ${format.sampleMimeType} ${format.width}x${format.height}")
        // Stopped, not paused: a paused player keeps loading, and a stream it cannot show is
        // downloaded for nothing. TDLib's download goes too, unless the viewer asked to keep the
        // file, since stopping the player leaves TDLib fetching (3.5 MB/s on the 1 GB TV after the
        // sheet was up, 2026-10-23).
        player?.stop()
        if (fileId > 0 && !OfflineDownloads.isDownloading(fileId)) {
            val id = fileId
            lifecycleScope.launch { Td.cancelDownload(id) }
        }
        decoderPlan = null
        beyondDevice = true
        showError(L.playerErrorUhd(nominalLines(format.width, format.height).toString()), retryable = false)
        return true
    }

    /**
     * Plays the copy among [copies] that decodes in hardware, in place of an upload this device
     * decodes too slowly; true when the hand-over started, false (with the line readied) when none
     * does. The upload is remembered as heavy while the app runs, so the new player and any later
     * open go straight to the copy ([pickTelegramQuality]), which also says why. The place carries
     * over the way a switch of quality carries it, and the quality list still offers the upload.
     */
    private fun swapForSoftware(copies: List<com.tmplayer.data.TelegramQuality>): Swap {
        softwareSwapTried = true
        if (handingOver || isFinishing || castUp()) return Swap.NONE
        val pick = com.tmplayer.data.TelegramQualities.forSoftwareDecode(copies, saveDataAtOpen) { HardwareDecoders.has(this, it) }
            ?: return Swap.NONE
        // Asked first (HX2): Telegram's lighter copy, or this one anyway.
        if (askHeavy(pick, copies)) return Swap.ASKED
        handOverTo(pick, copies)
        return Swap.HANDED_OVER
    }

    /** What [swapForSoftware] did: handed over to a lighter copy, asked the viewer, or neither. */
    private enum class Swap { HANDED_OVER, ASKED, NONE }

    /** The kinds of picture whose "may stutter" question the viewer turned off, read as the video opens. */
    private var heavyAskSkips: Set<String> = emptySet()

    /** The kind of the picture judged too heavy here ([com.tmplayer.data.DecodeMemory.keyOf]). */
    private var heavyKind: String? = null

    /**
     * "This video may stutter on this TV. It uses AV1, which this TV can only decode slowly." over
     * the opening video, held paused, when this kind of picture has not had the question turned
     * off: Telegram's [lighter] copy (when there is one) or Play anyway, Back leaving a video with
     * no lighter copy. False when not asked.
     */
    private fun askHeavy(lighter: com.tmplayer.data.TelegramQuality?, copies: List<com.tmplayer.data.TelegramQuality>): Boolean {
        val kind = heavyKind
        val codec = heavyLabel ?: return false
        if (kind != null && kind in heavyAskSkips) return false
        if (sheets == null || isFinishing) return false
        player?.pause()
        // The question is the line about it: the watchdog will not say it again.
        stutterOffered = true
        showSheet { host ->
            host.showHeavyAsk(codec, deviceWord(), lighter?.let { com.tmplayer.data.TelegramQualities.label(it.height) }) { useLighter, dontAsk ->
                if (dontAsk && kind != null) {
                    heavyAskSkips = heavyAskSkips + kind
                    App.backgroundScope.launch { runCatching { settings.skipHeavyAsk(kind) } }
                }
                when (useLighter) {
                    true -> lighter?.let { handOverTo(it, copies) }
                    false -> player?.play()
                    null -> finish()
                }
            }
        }
        return true
    }

    /** Plays Telegram's [pick] in place of the upload, from the same moment. */
    private fun handOverTo(pick: com.tmplayer.data.TelegramQuality, copies: List<com.tmplayer.data.TelegramQuality>) {
        if (handingOver || isFinishing) return
        Log.i("TMPlayer", "Playing Telegram's ${pick.height}p ${pick.codec} copy instead, which decodes in hardware")
        com.tmplayer.data.SoftwareDecode.rememberHeavy(chatId, messageId)
        player?.pause()
        // The moment to carry on from: the player's, or the saved place it has not sought to yet.
        val at = player?.currentPosition?.takeIf { it > 0 } ?: resumeMs
        if (at > 0) BookmarkJump.request(chatId, messageId, at)
        // The copies go along, so the new player need not read the message again to find them.
        playEpisode(currentItem().copy(qualities = copies))
    }

    /** Readies the line that the picture decodes in software, on a television, once per video; [playing] says it now. */
    private fun noteSoftwareDecode(playing: Boolean) {
        if (!com.tmplayer.data.SoftwareDecode.noteOnce(chatId, messageId)) return
        // Said for the copy the viewer picked or the only one there is; the watchdog will not say
        // it again ([stutterOffered]).
        stutterOffered = true
        val codec = heavyLabel
        val note = when {
            codec == null -> L.playerSoftwareDecodeNote
            smootherCopy() != null -> L.playerHeavyCopyNoteOthers(codec = codec, device = deviceWord())
            else -> L.playerHeavyCopyNote(codec = codec, device = deviceWord())
        }
        if (playing && firstFrameReady) {
            softwareNoteUp = true
            feedback?.message(note, holdMs = SOFTWARE_NOTE_MS)
        } else {
            softwareNote = note
        }
    }

    /** The device word the lines use: TV or phone. */
    private fun deviceWord(): String = if (FormFactor.isTv(this)) "tv" else "phone"

    /**
     * Another copy of this video, posted on its own, that this device plays smoothly: a hardware
     * one first, then one that fits in software, then (when this one is too slow) one smaller
     * whose codec its name does not say. Nearest this copy's quality among them. Null when none.
     */
    private fun smootherCopy(): MediaItem? {
        val here = currentItem()
        val heightHere = VideoCopies.heightOf(here) ?: VideoCopies.heightOf(postItem ?: here) ?: 1080
        return copies
            .asSequence()
            .filter { it.id != here.id && !VideoCopies.sameFile(it, here) && it.chatId != 0L }
            .mapNotNull { copy ->
                val rank = when (DeviceDecode.verdictOf(copy)) {
                    com.tmplayer.data.DecodeJudge.Verdict.HARDWARE -> 0
                    com.tmplayer.data.DecodeJudge.Verdict.SOFTWARE_FITS -> 1
                    com.tmplayer.data.DecodeJudge.Verdict.UNKNOWN ->
                        if ((VideoCopies.heightOf(copy) ?: Int.MAX_VALUE) < heightHere) 2 else return@mapNotNull null
                    com.tmplayer.data.DecodeJudge.Verdict.SOFTWARE_TOO_SLOW -> return@mapNotNull null
                }
                Triple(copy, rank, kotlin.math.abs((VideoCopies.heightOf(copy) ?: 0) - heightHere))
            }
            .sortedWith(compareBy<Triple<MediaItem, Int, Int>> { it.second }.thenBy { it.third })
            .firstOrNull()?.first
    }

    /** "Playing the 720p copy. The 1080p copy is too heavy for this TV.", with Change to the copies. */
    private fun sayPassedOver(heavy: String) {
        val here = copies.firstOrNull { it.id == currentItem().id } ?: currentItem()
        feedback?.offer(
            L.playerSmootherCopyPlaying(quality = here.copyQuality(), heavy = heavy, device = deviceWord()),
            L.commonChange,
            holdMs = PASSED_OVER_MS,
            focus = FormFactor.isTv(this),
        ) { openCopies() }
    }

    /** Samples the watch once a second while the video plays ([PlaybackWatch]). */
    private fun startWatch() {
        if (watch.done || isAudio || !firstFrameReady) return
        loopHandler.removeCallbacks(watchTick)
        loopHandler.postDelayed(watchTick, WATCH_TICK_MS)
    }

    private fun sampleWatch() {
        val exo = player ?: return
        if (watch.done) return
        if (watchPicture == null) {
            exo.videoFormat?.let { format ->
                watchPicture = DeviceDecode.pictureOf(format)
                watchFormat = format
                watchSoftware = DecoderSwitch.softwareVideoChosen(exo)
            }
        }
        val playing = exo.isPlaying && !castUp()
        val answer = watch.sample(
            SystemClock.elapsedRealtime(),
            exo.currentPosition,
            playing,
            exo.videoDecoderCounters?.renderedOutputBufferCount ?: 0,
            watchDropped,
            watchUnderruns,
        )
        when {
            answer != null -> onWatchAnswer(answer)
            // Paused or waiting: no ticks until it plays again ([startWatch] from onIsPlayingChanged).
            playing -> loopHandler.postDelayed(watchTick, WATCH_TICK_MS)
        }
    }

    private fun onWatchAnswer(answer: PlaybackWatch.Answer) {
        val picture = watchPicture
        Log.i(
            "TMPlayer",
            "Watch: ${answer.name.lowercase()}, ${watch.summary()}, ${picture?.label ?: "?"} " +
                "${picture?.width}x${picture?.height} in ${if (watchSoftware) "software" else "hardware"}",
        )
        // Remembered for the next picture of this kind, when the app's own decoder had it: what a
        // hardware decoder does is the device's to say, and its list already says it.
        if (picture != null && watchSoftware) DeviceDecode.note(picture, answer == PlaybackWatch.Answer.KEPT_UP)
        if (answer == PlaybackWatch.Answer.CANNOT_KEEP_UP && !castUp()) reportCouldNotKeepUp(picture)
        if (answer == PlaybackWatch.Answer.KEPT_UP || stutterOffered || castUp()) return
        stutterOffered = true
        if (watchSoftware) com.tmplayer.data.SoftwareDecode.rememberHeavy(chatId, messageId)
        val tv = FormFactor.isTv(this)
        smootherCopy()?.let { copy ->
            feedback?.offer(
                L.playerStutterOffer(quality = copy.copyQuality(), device = deviceWord()),
                L.playerStutterSwitch,
                holdMs = STUTTER_OFFER_MS,
                focus = tv,
            ) { switchCopy(copy) }
            return
        }
        val lighter = if (playingQuality == 0) {
            com.tmplayer.data.TelegramQualities.forSoftwareDecode(telegramQualities(), saveDataAtOpen) { HardwareDecoders.has(this, it) }
        } else {
            null
        }
        if (lighter != null) {
            feedback?.offer(
                L.playerStutterOfferLighter(quality = com.tmplayer.data.TelegramQualities.label(lighter.height), device = deviceWord()),
                L.playerStutterSwitch,
                holdMs = STUTTER_OFFER_MS,
                focus = tv,
            ) { switchQuality(lighter.height) }
            return
        }
        if (watchSoftware) feedback?.message(L.playerStutterNone(device = deviceWord()), holdMs = SOFTWARE_NOTE_MS)
    }

    /** Whether a cast is connected or playing: the TV at the other end decodes the picture then. */
    private fun castUp(): Boolean = cast?.connected == true || cast?.casting == true

    /** The last error Media3 reported, for "Copy details" on the failure sheet. */
    private var lastPlaybackError: PlaybackException? = null

    /**
     * What "Copy details" puts on the clipboard: what the sheet says, the file, the device and the
     * error underneath, plain text a report or a forum post can carry. Nothing about the account.
     */
    private fun errorDetails(): String = buildString {
        appendLine("TMPlayer ${com.tmplayer.BuildConfig.VERSION_NAME} (${com.tmplayer.BuildConfig.VERSION_CODE})")
        appendLine("${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}, Android ${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT})${if (FormFactor.isTv(this@PlayerActivity)) ", TV" else ""}")
        appendLine("${statusTitle.text}: ${statusText.text}")
        appendLine("File: ${mediaFileName.ifBlank { "?" }}, ${mediaMime.ifBlank { "?" }}, ${fileSizeBytes} bytes, ${durationSec} s, ${if (downloadedFile != null) "downloaded" else "streamed"}")
        player?.videoFormat?.let { appendLine("Video: ${it.sampleMimeType} ${it.codecs.orEmpty()} ${it.width}x${it.height}") }
        player?.audioFormat?.let { appendLine("Audio: ${it.sampleMimeType} ${it.codecs.orEmpty()} ${it.channelCount} ch ${it.sampleRate} Hz") }
        lastPlaybackError?.let { e ->
            appendLine("Error: ${e.errorCodeName} (${e.errorCode})")
            var cause: Throwable? = e
            var depth = 0
            while (cause != null && depth < 4) {
                appendLine("  ${cause.javaClass.simpleName}: ${cause.message.orEmpty().take(300)}")
                cause = cause.cause
                depth++
            }
        }
    }

    /** When the latest seek began, for its "seek to ready" log; 0 when none is pending. */
    private var seekStartedAt = 0L

    /** Logs press (or open) to first frame once per video, for the device pass to compare. */
    private fun logFirstFrame() {
        if (openedAt == 0L) return
        val now = SystemClock.elapsedRealtime()
        val fromPress = if (pressedAt > 0) "press to first frame ${now - pressedAt} ms, " else ""
        Log.i(START_TAG, "${fromPress}open to first frame ${now - openedAt} ms (file $fileId, resume $resumeMs ms, ${if (downloadedFile != null) "download" else "stream"})")
        openedAt = 0L
        pressedAt = 0L
        openedGate.complete(Unit)
    }

    /**
     * The A-B repeat marked from the menu, or null. Media3 has no loop of its own between two
     * points, so [loopWatch] looks at the position a few times a second and seeks back to the start
     * once it reaches the end. Not kept: the next video, which is a new activity, starts without.
     */
    private var abLoop: AbLoop? = null
    private val loopHandler = android.os.Handler(Looper.getMainLooper())
    private val loopWatch = object : Runnable {
        override fun run() {
            val loop = abLoop?.takeIf { it.complete } ?: return
            val exo = player
            if (exo != null) loop.wrap(exo.currentPosition)?.let { quietly { exactSeekTo(exo, it) } }
            // Only while the picture moves: paused, buffering or stopped there is nothing to wrap,
            // and [onIsPlayingChanged] starts the watch again when it plays.
            if (exo?.isPlaying == true) loopHandler.postDelayed(this, LOOP_TICK_MS)
        }
    }

    /**
     * The tracks the saved position was playing with, put back once the player lists the file's
     * tracks, and then cleared. Null for a video started from the beginning.
     */
    private var resumeTracks: ResumeState? = null

    /** Only used to label the video in "Continue watching"; playback never needs it. */
    private var chatTitle = ""

    /**
     * The episodes either side of this one, once the chat has been asked about them.
     *
     * Empty for a standalone video, and for an episode whose neighbours are not in the chat. The
     * transport row watches this and grows its two extra buttons when they arrive; the title reads
     * the playing episode's code and name off it. See [EpisodeNeighbours].
     */
    private val _episodes = MutableStateFlow(EpisodeSteps())
    val episodes: StateFlow<EpisodeSteps> = _episodes.asStateFlow()

    /**
     * True until the picture first appears; after that a stall is a chip, not a full sheet. The
     * pre-roll loader rides with it: up while the film is opening, faded off the moment this goes
     * false, for the first frame or for a failure sheet underneath.
     */
    private var openingFilm = true
        set(value) {
            field = value
            syncLoader()
        }

    /** The pre-roll over the opening wait. See [PlayerLoaderHost]. */
    private var loaderHost: PlayerLoaderHost? = null
    private val speed = SpeedMeter()

    /** The loader's line, filled in stages from what the player measures. See [OpeningProgress]. */
    private var opening = OpeningProgress()

    /** Every byte the player's data sources have handed Media3, counted on its loading thread. */
    private val readBytes = java.util.concurrent.atomic.AtomicLong()

    /** [readBytes] when the loader came up, so the line counts this opening's bytes only. */
    private var openingBytesAt = 0L

    /** How fast [readBytes] grows: the speed the wait's note shows when TDLib's own reads nothing. */
    private val readSpeed = SpeedMeter()

    /** Moves the loader's line a few times a second while it is up. */
    private var loaderTick: Job? = null

    /** Counts the bytes into [readBytes]; the loader reads the count, nothing here decides anything. */
    private val byteCounter = object : androidx.media3.datasource.TransferListener {
        override fun onTransferInitializing(source: androidx.media3.datasource.DataSource, dataSpec: androidx.media3.datasource.DataSpec, isNetwork: Boolean) = Unit
        override fun onTransferStart(source: androidx.media3.datasource.DataSource, dataSpec: androidx.media3.datasource.DataSpec, isNetwork: Boolean) = Unit
        override fun onBytesTransferred(source: androidx.media3.datasource.DataSource, dataSpec: androidx.media3.datasource.DataSpec, isNetwork: Boolean, bytesTransferred: Int) {
            readBytes.addAndGet(bytesTransferred.toLong())
            // One atomic read per call once logged, nothing more on the loading thread.
            if (startStages.get() and STAGE_BYTES == 0) markStart("first bytes read", STAGE_BYTES)
        }
        override fun onTransferEnd(source: androidx.media3.datasource.DataSource, dataSpec: androidx.media3.datasource.DataSpec, isNetwork: Boolean) = Unit
    }

    /**
     * The transport controls the system draws: notification, lock screen, headset and Assistant.
     *
     * Held for the life of the activity because it is the activity that owns the player. There is
     * no service behind it on purpose: playback cannot outlive this screen, and a service would
     * only be a second lifetime to keep in step with this one.
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

    /**
     * How this video's soundtrack and subtitles are decided: its saved tracks, the series, the
     * languages in Settings, the file's defaults. See [TrackRules].
     */
    private var trackPlan = TrackRules.plan(null, TrackChoice(), TrackPreferences(), Languages.ENGLISH)

    /**
     * Whether the subtitles Settings ask for still wait on the file's track list, which a pick by
     * language and "only when the audio is foreign" both need. Cleared once applied.
     */
    private var settingsSubtitlesPending = false

    /** The subtitle tracks as last written to the log, and whether their first lines have been. See [logSubtitleTracks]. */
    private var subtitleLogLine: String? = null
    private var firstCuesLogged = false

    /** What the track choice is filed under: the series name, or this video's own. */
    private val seriesKey: String
        get() = MediaName.parse(mediaTitle).title.ifBlank { mediaTitle }

    /** Where a speed picked for this video is remembered, and read back from. See [SpeedMemory]. */
    private val speedScopes: SpeedMemory.Scopes
        get() = SpeedMemory.scopesOf(currentItem(), seriesKey)

    /** The picture's own shape, once the decoder has reported it. Zero until then. */
    private var videoWidth = 0
    private var videoHeight = 0

    /** Settings, Playback: keep the sound going when the player is left ([PlaybackService]). */
    private var backgroundAudioOn = false

    /** Settings, "Picture in picture when leaving the app" (HX9): on unless the viewer turned it off. */
    private var pipOnLeave = true

    /** True while the session is in [PlaybackService]'s hands: the player left, the sound playing on. */
    private var inBackgroundAudio = false

    /**
     * True while the video track is off because nobody can see it ([BackgroundPlayback.withoutVideo]),
     * so coming back turns on only what going away turned off.
     */
    private var videoHiddenUnseen = false

    /**
     * Set when the sound stopped out of sight for a reason other than the viewer
     * ([stopUnseen]), with "Playback stopped, tap to resume" up: coming back carries on.
     */
    private var resumeWhenSeen = false

    /** The app lock asks on open: the session names no video ([sessionMetadata]). */
    private var sessionPrivate = false

    /**
     * The work that belongs to the video now open: its searches, its saved place, its stream.
     * Cancelled and made again when [continueUnseen] changes the video in place, so nothing late
     * from the last one lands on this one. A child of the activity's own scope.
     */
    private lateinit var itemJob: Job
    private val itemScope: CoroutineScope get() = CoroutineScope(lifecycleScope.coroutineContext + itemJob)

    /**
     * When picture in picture last ended. Closing the corner window stops the activity a moment
     * later, and that is the viewer saying stop, not "keep the sound going".
     */
    private var pictureInPictureEndedAt = 0L

    /** True while the activity is a thumbnail in the corner of somebody else's screen. */
    private var inPictureInPicture = false

    private var gestures: PlayerGestures? = null

    /** The phone's gesture answers (ripple, pills). Null on a television. */
    private var feedback: PlayerFeedback? = null

    /** The phone's touch settings, read off disk once the player opens. */
    private var touchPrefs = TouchPrefs()

    /**
     * True while the screen is locked: every touch, click, wheel and key is off but the volume
     * ([LockedKeys]), the orientation is frozen, and the only thing a tap, a mouse move or a key
     * does is offer the way out: the pill on a touch screen, a held OK on a remote.
     */
    private var locked = false
    private var lockShield: FrameLayout? = null
    private var unlockPill: View? = null
    private var unlockText: TextView? = null
    private var unlockProgress: android.widget.ProgressBar? = null

    /** The select key being held toward an unlock, or 0. */
    private var unlockHeldKey = 0

    /** When the screen was locked, on the key events' clock: only a key pressed after it unlocks. */
    private var lockedAt = 0L
    private val hideUnlockPill = Runnable { unlockPill?.animate()?.alpha(0f)?.setDuration(300)?.start() }
    private val unlockAfterHold = Runnable {
        unlockHeldKey = 0
        unlockScreen()
    }

    /**
     * The "Next episode in 30" card, raised near the end of an episode with a successor. Once it
     * has been seen the end of the video goes straight to the next one; once it has been hidden the
     * end of the video stays put.
     */
    private var nextUpCard: NextUpCard? = null
    private var nextUpShown = false
    private var nextUpDismissed = false

    /** The next episode was started from the credits, so the real end of the file does not start it again. */
    private var creditsSkipped = false

    /**
     * Wakes [watchForTheEnd] while the video is not playing: it sleeps then rather than looking at
     * the clock twice a second for a playhead that is not moving, which on a 1 GB television kept
     * the main thread waking for as long as a paused video was left on screen. Sent whenever
     * something that loop reads can change without the video playing: playing or stopping, a
     * seek, the lock, picture in picture, the loading sheet, the skip times arriving.
     */
    private val endWatchWake = Channel<Unit>(Channel.CONFLATED)

    private fun wakeEndWatch() {
        endWatchWake.trySend(Unit)
    }

    /**
     * The "Skip intro" pill and what it works from: the timed stretches of this video, as
     * [OnlineMetadata.skipTimes] found them (empty while lookups are off or nobody has timed it),
     * the stretches already skipped this playback, by start, so none comes back after a seek
     * back over it, and the one the pill is offering now.
     */
    private var skipPill: SkipPill? = null
    private var skipSegments: List<SkipSegment> = emptyList()
    private val skippedSegments = mutableSetOf<Long>()
    private var offeredSkip: SkipSegment? = null

    /**
     * "Back to 12:34" after a long jump ([SeekUndo]): the chip, and what it works from. Every seek
     * reaches [noteSeek] through the player's own discontinuity report, so the bar, the double tap,
     * the digits, chapters, bookmarks and the skip pill all count without each saying so; the
     * seeks that are not the viewer's (a repeat wrapping, Watch again) run with [quietSeek] set.
     */
    private var undoChip: UndoSeekChip? = null
    private val seekUndo = SeekUndo()
    private var quietSeek = false
    private val undoExpiry = Runnable { refreshUndoChip() }

    /**
     * The file's chapter marks, read from its own bytes once they are on the disk ([MkvChapters];
     * Media3 reads none). Empty for a file without any, and until they are found.
     */
    private var chapters: List<Chapter> = emptyList()
    private var chaptersAsked = false

    /** "Send logs" on the failure sheet: the shared action, hosted in Compose. */
    private var statusLogs: View? = null

    /** True while the transport row is up: the download figure is shown alongside it. */
    private var controlsUp = false

    /** Set once the row has introduced itself over the first frames, so it only does it once. */
    private var controlsShownOnStart = false

    /** Playback has been ready once, so the resume offer has a picture to sit over. */
    private var firstFrameReady = false
    private var startOverOffered = false
    private var firstRunHintShown = false

    /** How far into the video the download has reached, and whether it has reached the end. */
    private var downloadedFraction = 0f
    private var downloadComplete = false

    /** Whether the corner chip is wanted while the cache fills: up only while the data ahead runs short ([LoadingChip]). */
    private var cacheChipWanted = true

    /** When the loading sheet was last redrawn, so [applyDownloadState] can throttle itself. */
    private var lastProgressRender = 0L

    /** True while the whole video is being fetched before playback, under the "download first" setting. */
    private var waitingForWholeFilm = false

    /** The stage the loading screen is currently reporting, kept so it can be re-rendered. */
    private var statusMessage = ""

    /** Used only while this video needs more bytes; completed videos ignore connectivity entirely. */
    private var networkOffline = false

    /**
     * The opening wait's watch ([watchOpening]): when the last new byte arrived, or the wait began,
     * and the bytes and buffered position it was measured against. [slowStartUp] is true while the
     * sheet says nothing is arriving ([showSlowStart]); its Try again and Download first borrow
     * the failure sheet's two buttons.
     */
    private var openingWatch: Job? = null
    private var lastProgressAtMs = 0L
    private var lastProgressBytes = -1L
    private var lastBufferedMs = -1L
    private var slowStartUp = false

    /**
     * The same watch for a stall once playback has begun ([watchRebuffering]): a spinner over the
     * picture that runs past [OpeningStall.STALL_MS] with no new byte turns into the same sheet,
     * worded for a video that stopped arriving rather than one that never started.
     */
    private var rebufferWatch: Job? = null
    private var stalledMidPlay = false

    /** Automatic recoveries since playback was last healthy, and the one currently pending. */
    private var recoveryAttempts = 0
    private var recoveryJob: Job? = null

    /** Set once [resourceAgain] has asked Telegram for this video again, so it is tried only once. */
    private var reSourced = false

    /**
     * "Resuming from 1:12:40", once the saved position has been read off disk.
     *
     * It arrives from a coroutine part-way through the load, and is carried alongside every later
     * stage message rather than replaced by one.
     */
    private var resumeNotice: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        // Fragments are not restored. A restored playback fragment comes back before the player is
        // rebuilt, so it would attach with nothing behind it and no transport controls. A clean
        // fragment manager means the fragment below is always built against a live player, and
        // nothing here is worth restoring: the resume position lives on disk.
        super.onCreate(null)
        // An update that installs while this is open (picture in picture, the sound playing on)
        // ends the process: the place is written first (HX5).
        com.tmplayer.data.SelfInstaller.addBeforeInstall(saveBeforeInstall)
        // Before the first layout pass, on purpose: a window that turns after it has drawn is a
        // lurch the viewer sees. Almost every video is landscape, so landscape is what the loader
        // opens in, and a portrait clip turns once, later.
        applyOrientation()
        setContentView(R.layout.activity_player)
        // The views follow the app's language, not the device's: Arabic mirrors the words and the
        // top bar, on an Android too old for a per-app language as well (see player_controls.xml).
        findViewById<View>(android.R.id.content).layoutDirection = layoutDirectionFor(Translator.messages.rtl)

        keepScreenOn(true)
        // Edge to edge, so the picture reaches the corners of the screen rather than sitting in a
        // letterbox of system chrome, and so the insets read below are real.
        WindowCompat.setDecorFitsSystemWindows(window, false)
        allowDrawingUnderTheCutout()
        setSystemBarsHidden(true)

        settings = SettingsStore(this)
        watchedStore = WatchedStore(this)
        bookmarkStore = BookmarkStore(this)
        itemJob = Job(lifecycleScope.coroutineContext[Job])
        readItem(intent)

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
            // Try again on the sheet that says nothing is arriving: see [showSlowStart].
            setOnClickListener { if (slowStartUp) tryOpeningAgain() else reloadFromScratch() }
        }
        statusOpenWith = findViewById<android.widget.Button>(R.id.status_open_with).apply {
            // The same button is Free up space on a refusal for room: see [freeUpOnFailure], and
            // Download first while nothing is arriving: see [showSlowStart].
            setOnClickListener {
                when {
                    slowStartUp -> downloadFirst()
                    freeUpOnFailure -> openFreeUpSpace()
                    else -> openInAnotherApp()
                }
            }
        }
        statusBack = findViewById<android.widget.Button>(R.id.status_back).apply {
            setOnClickListener { finish() }
        }
        statusLighter = findViewById<android.widget.Button>(R.id.status_lighter).apply {
            setOnClickListener { lighterQuality()?.let { switchQuality(it.height) } }
        }
        statusDecoder = findViewById<android.widget.Button>(R.id.status_decoder).apply {
            text = L.playerDecoderOther
            setOnClickListener { tryOtherDecoder() }
        }
        // The layout carries English for the preview; the words on screen come from the catalog.
        statusRetry?.text = L.commonTryAgain
        statusReload?.text = L.commonReload
        statusOpenWith?.text = L.playerOpenInAnotherApp
        statusBack?.text = L.playerGoBack
        statusLogs = PlayerErrorSheet.addSendLogs(statusOpenWith, ::errorDetails)
        statusName = findViewById(R.id.status_name)
        loaderHost = PlayerLoaderHost(
            root = findViewById(R.id.player_root),
            above = statusOverlay,
            tv = FormFactor.isTv(this),
            fileName = mediaFileName,
            caption = mediaCaption.ifBlank { null },
            fallbackTitle = mediaTitle,
            durationSec = durationSec,
        )
        rebufferChip = findViewById(R.id.rebuffer_chip)
        rebufferText = findViewById(R.id.rebuffer_text)
        downloadChip = findViewById(R.id.download_chip)
        controls = PlayerControls(
            root = findViewById(R.id.player_root),
            isTv = FormFactor.isTv(this),
            player = { player },
            onVisibility = ::onControlsVisibilityChanged,
            onTogglePlay = ::togglePlayback,
            onPickSubtitles = { showTrackPicker(C.TRACK_TYPE_TEXT) },
            onPickAudio = { showTrackPicker(C.TRACK_TYPE_AUDIO) },
            onPickSpeed = ::pickSpeed,
            onCycleOrientation = ::cycleOrientation,
            onPlayEpisode = ::playEpisode,
            onEpisodes = ::openEpisodes,
            onCopies = ::openCopies,
            // The row's picture shape button: the next shape along, as W does on the desktop.
            onCycleShape = { applyScale(videoScale.next()) },
            onLock = ::lockScreen,
            onBack = ::finish,
            onMore = ::showOverflow,
            onRemainingToggled = { remaining ->
                touchPrefs = touchPrefs.copy(showRemaining = remaining)
                lifecycleScope.launch {
                    runCatching { settings.updateTouchPrefs { it.copy(showRemaining = remaining) } }
                }
            },
        )
        controls?.previewArt = scrubArt
        // The text answers (and the resume offer) on both devices; the touch feedback around them
        // only ever fires from the phone's gestures.
        feedback = PlayerFeedback(findViewById(R.id.player_root), findViewById(R.id.overlay_container))
        if (FormFactor.isTv(this)) {
            tvMenu = PlayerTvMenu(
                activity = this,
                root = findViewById(R.id.player_root),
                title = { mediaTitle },
                pictureInPicture = { hasPictureInPicture() },
                saveToDownloads = ::canSaveToDownloads,
                markWatched = ::hasMessage,
                watched = { onWatchedList },
                bookmarks = ::hasMessage,
                bookmarkCount = { bookmarkList.value.size },
                openInAnotherApp = { savable == true },
                stereoOffered = ::stereoOffered,
                stereo = { stereo.detailText() },
                volumeBoost = { volumeBoostOn },
                nightMode = { nightModeOn },
                evenVolume = { evenVolumeOn },
                sleepTimer = ::sleepTimerDetail,
                onSleepTimer = ::setSleepTimer,
                sleepChoice = ::sleepChoice,
                loop = { abLoop },
                paused = ::isPaused,
                chapter = {
                    if (chapters.isEmpty()) null
                    else Chapters.labelAt(chapters, player?.currentPosition ?: 0L) ?: L.playerBeforeFirstChapter
                },
                onEntry = ::onTvMenuEntry,
                onClosed = { menuOpen = false; holdControls(); controls?.show(); controls?.focusRow() },
            )
        }
        sheets = PlayerSheets(this, findViewById(R.id.player_root)) {
            detailsOpen = false
            holdControls()
            if (tvMenu != null) {
                controls?.show()
                controls?.focusRow()
            }
        }
        episodesList = PlayerEpisodes(
            activity = this,
            root = findViewById(R.id.player_root),
            progress = settings.watchProgress,
            watched = watchedStore.watched,
            actions = com.tmplayer.ui.player.EpisodesActions(
                onPlay = ::playFromEpisodes,
                onToggleWatched = ::toggleWatchedOf,
                onAutoplay = ::setAutoplayFromEpisodes,
            ),
            onClosed = {
                episodesOpen = false
                holdControls()
                controls?.show()
                controls?.focusEpisodes()
            },
        )
        buildNextUpCard()
        startTrickplay()
        startStillWatchingCounts()
        // Followed rather than read once, so turning it on in Settings with a video open applies
        // the next time the player is left. A phone's setting: a television never plays on unseen.
        if (!FormFactor.isTv(this)) {
            lifecycleScope.launch {
                runCatching { settings.backgroundAudio.collect { backgroundAudioOn = it } }
            }
            // Followed too: turning it off with a video open updates the system's request at once,
            // so Home from here no longer shrinks the video (Android 12 decided that in advance).
            lifecycleScope.launch {
                runCatching {
                    settings.pictureInPictureOnLeave.collect {
                        pipOnLeave = it
                        updatePictureInPictureParams()
                    }
                }
            }
        }
        // With the app lock asking on open, the media notification, the lock screen's controls
        // and the system's media panel name no video: the lock keeps out anybody who picks the
        // phone up, and a title on the lock screen would tell them what was on.
        lifecycleScope.launch {
            runCatching {
                settings.appLock.collect { lock ->
                    val private = lock.pinSet && lock.onOpen
                    if (private == sessionPrivate) return@collect
                    sessionPrivate = private
                    refreshSessionMetadata()
                }
            }
        }
        // On a television too: the controls timeout and the remaining time readout apply there.
        lifecycleScope.launch {
            touchPrefs = runCatching { settings.touchPrefsNow() }.getOrDefault(TouchPrefs())
            applyTouchPrefs()
        }
        renderControlsTitle()
        renderOrientationButton()
        // When a track picker closes, focus falls off its fragment and the scrub bar catches it,
        // so the next D-pad press seeks instead of walking the row. Hand it back to the buttons.
        supportFragmentManager.addOnBackStackChangedListener {
            pickerOpen =
                TrackPickerFragment.isOpen(supportFragmentManager)
            holdControls()
            // The picker holds the row up, and with the row up the subtitles stand down where
            // they cannot clear it, which on a television is always: the look lines' preview
            // and a track just chosen showed nothing at all. See [liftSubtitles].
            liftSubtitles()
            if (!pickerOpen) controls?.focusRow()
        }
        // The manifest opts into the new back dispatch, and from Android 13 that means Back never
        // arrives in [dispatchKeyEvent] as a key: it comes here, and without this a locked screen,
        // the next-up card or the row up all ended the film on the first press.
        onBackPressedDispatcher.addCallback(this) {
            if (!peelOneOverlay()) finish()
        }
        subtitleView.setApplyEmbeddedStyles(true)
        applySubtitleStyle()

        if (fileId > 0) {
            // Once for the activity: each follows the fields, so a video changed in place
            // ([continueUnseen]) is followed too.
            watchEpisodes()
            observeDownload()
            observeConnectivity()
            startResumeHeartbeat()
        }
        setUpCasting()
        openItem()
    }

    /** The Cast button and the cast screen, where casting exists; see [PlayerCast]. */
    private fun setUpCasting() {
        if (FormFactor.isTv(this)) return
        val support = com.tmplayer.cast.Cast.support(this)
        if (!support.available) return
        cast = PlayerCast(this, findViewById(R.id.player_root), support, castHooks)
        findViewById<View>(R.id.control_cast)?.apply {
            visibility = View.VISIBLE
            setOnClickListener { cast?.openPicker() }
        }
        subtitleFiles.onCastFile = { file, mime, label -> cast?.addSubtitle(file, mime, label) == true }
    }

    private val castHooks = object : PlayerCast.Hooks {
        override fun handOff(): Long? {
            val exo = player ?: return resumeMs.takeIf { it > 0 }
            val at = exo.currentPosition
            exo.playWhenReady = false
            // Stopped, not paused: a paused player keeps reading ahead, and TDLib keeps fetching
            // for it, against the TV's reads of the same file.
            exo.stop()
            return at
        }

        override fun takeBack(positionMs: Long) {
            val exo = player ?: return
            // Back from the TV at the TV's place: that is where this carries on, so the opening's
            // "Resuming from" (the place before casting) is neither offered nor applied again.
            resumeMs = positionMs
            startOverOffered = true
            keepScreenOn(true)
            attachMediaSession(exo)
            exo.seekTo(positionMs)
            exo.prepare()
            exo.playWhenReady = true
        }

        override fun source(): Pair<com.tmplayer.cast.CastSource, String>? {
            val client = castClient ?: return null
            val joined = joinedSource
            val uri = joined ?: downloadedFile?.let { android.net.Uri.fromFile(it) } ?: tdFileUri(fileId)
            val length = when {
                joined != null -> joined.getQueryParameters("s").sumOf { it.toLongOrNull() ?: 0L }
                downloadedFile != null -> downloadedFile!!.length()
                else -> fileSizeBytes
            }
            if (length <= 0) return null
            val factory = DefaultDataSource.Factory(
                this@PlayerActivity,
                JoinedDataSource.Factory(
                    TdDataSource.Factory(client),
                    DefaultDataSource.Factory(this@PlayerActivity, TdDataSource.Factory(client)),
                    ::leftPiece,
                ),
            )
            val name = mediaFileName.ifBlank { mediaTitle }.ifBlank { "video" }
            return DataSourceCastSource(factory, uri, length) to name
        }

        override fun title(): String = mediaTitle
        override fun subtitleLine(): String = chatTitle.ifBlank { mediaSubtitle }
        override fun audioLanguage(): String? = trackPlan.audioLanguage

        override fun subtitleFiles(): List<Triple<File, String, String>> = subtitleFiles.files.mapNotNull { cfg ->
            val path = cfg.uri.path ?: return@mapNotNull null
            Triple(File(path), cfg.mimeType ?: androidx.media3.common.MimeTypes.APPLICATION_SUBRIP, cfg.label?.toString() ?: File(path).name)
        }

        override val episodes: StateFlow<EpisodeSteps> get() = this@PlayerActivity.episodes
        override fun playEpisode(item: MediaItem) = this@PlayerActivity.playEpisode(item)
        override fun onEnded() = onVideoEnded()

        override fun onCasting(casting: Boolean) {
            if (casting) {
                hideStatus()
                controls?.hideNow()
                // The Cast framework's own session is the one the lock screen, the notification
                // and a headset's buttons reach now; this one would start the stopped player.
                mediaSession?.release()
                mediaSession = null
                // The phone is a remote now: its screen may go off, the TV's controls are on the
                // lock screen and in the notification.
                keepScreenOn(false)
            } else {
                controls?.show()
            }
        }

        override fun close() = finish()
    }


    /** The video's own fields, from the intent that opened it or the one [continueUnseen] set. */
    private fun readItem(from: Intent) {
        fileId = from.getIntExtra(EXTRA_FILE_ID, 0)
        pressedAt = from.getLongExtra(EXTRA_PRESSED_AT, 0L)
        openedAt = SystemClock.elapsedRealtime()
        startStages.set(0)
        openedGate.complete(Unit)
        openedGate = CompletableDeferred()
        // What starting this screen cost after the press: on a weak TV stick a part of the wait.
        if (pressedAt > 0) Log.i(START_TAG, "player screen open at ${openedAt - pressedAt} ms since press (file $fileId)")
        // Read once: a later open of the same intent (a recreate) is not a press.
        from.removeExtra(EXTRA_PRESSED_AT)
        chatId = from.getLongExtra(EXTRA_CHAT_ID, 0)
        messageId = from.getLongExtra(EXTRA_MESSAGE_ID, 0)
        mediaTitle = from.getStringExtra(EXTRA_TITLE).orEmpty()
        mediaSubtitle = from.getStringExtra(EXTRA_SUBTITLE).orEmpty()
        mediaFileName = from.getStringExtra(EXTRA_FILE_NAME).orEmpty()
        mediaCaption = from.getStringExtra(EXTRA_CAPTION).orEmpty()
        mediaMime = from.getStringExtra(EXTRA_MIME).orEmpty()
        mediaTopicId = from.getIntExtra(EXTRA_TOPIC_ID, 0)
        chatTitle = from.getStringExtra(EXTRA_CHAT_TITLE).orEmpty()
        fileSizeBytes = from.getLongExtra(EXTRA_SIZE, 0)
        durationSec = from.getIntExtra(EXTRA_DURATION, 0)
        savable = if (from.getBooleanExtra(EXTRA_CAN_BE_SAVED, true)) null else false
        pieces = piecesFrom(from)
        startLinkSec = from.getIntExtra(EXTRA_LINK_START_SEC, 0)
        carriedQualities = qualitiesFrom(from)
        postItem = null
        playingQuality = 0
        uploadFileId = 0
        uploadSizeBytes = 0
        qualityPickedWhy = null
        qualityHandPicked = false
        saveDataAtOpen = false
        softwareSwapTried = false
        softwareChecked = false
        softwareNote = null
        softwareNoteUp = false
        watch.restart()
        loopHandler.removeCallbacks(watchTick)
        watchPicture = null
        watchSoftware = false
        stutterOffered = false
        copyHandPicked = false
        heavyLabel = null
        heavyKind = null
        passedOver = SmootherPick.take(from.getLongExtra(EXTRA_CHAT_ID, 0), from.getLongExtra(EXTRA_MESSAGE_ID, 0))
        storyboardFrames?.let { board ->
            board.release()
            storyboardFrames = null
            showScrubPictures()
        }
        joinedSource = null
        piecesNotice = null
        piecesIncomplete = false
        piecesNoticeShown = false
    }

    /**
     * Everything one video asks for as it opens: the loading sheet, the searches for its episodes,
     * copies and skip times, its saved place, and the stream itself. Run from [onCreate], and again
     * by [continueUnseen] for the next episode played in place; the work that belongs to one video
     * runs in [itemScope], which a change of video cancels.
     */
    private fun openItem() {
        if (fileId <= 0) {
            showError(L.playerNothingToPlay, retryable = false)
            return
        }

        statusTitle.text = mediaTitle.ifBlank { L.playerOpening }
        showStatus(L.playerConnecting)

        showArtwork()
        statusMeta?.text = chatTitle.ifBlank { mediaSubtitle }
        statusMeta?.visibility =
            if (statusMeta?.text.isNullOrBlank()) View.GONE else View.VISIBLE
        // The setting as the end of this video will find it ([endLeadsOn]).
        itemScope.launch { autoplayNext = runCatching { settings.autoplayNextNow() }.getOrDefault(true) }
        if (hasMessage()) {
            itemScope.launch {
                onWatchedList = runCatching { watchedStore.isWatched(chatId, messageId) }.getOrDefault(false)
            }
            itemScope.launch {
                runCatching { bookmarkStore.bookmarks(chatId, messageId).collect { bookmarkList.value = it } }
            }
        }
        findEpisodes()
        findCopies()
        findSkipTimes()
        // Telegram's lighter copies and storyboard (CP80), for the quality list and the scrub bar.
        if (hasMessage()) itemScope.launch { afterOpening(); Td.awaitAuthorizedSession(); readPost() }

        // Reading the saved position is a disk hit; do it off the main thread and seek once it
        // lands. Under "download the whole video first" there is no player to seek yet, so
        // [startPlayback] applies the same position as well; whichever arrives second sets it again.
        // A few seconds before the saved point, as Settings has it ([ResumeRewind]).
        // The place saved on this device is read first and used at once: [startPlayback] waits
        // only [RESUME_WAIT_MS] for it, and a read that misses that window prepares at 0, then
        // seeks and restarts TDLib's download, which on a weak TV stick is seconds of buffering.
        // The two slow sources, the other devices' places and the post's own start, are asked
        // for alongside, after it, and move the video only when they say something else.
        var bookmarked = false
        val resumeRead = itemScope.launch {
            // A bookmark chosen on the detail page opens the video at its moment, once.
            val jump = BookmarkJump.take(chatId, messageId)
            bookmarked = jump != null
            resumeMs = jump
                // Whichever copy of the video was played last: a switch of quality, or the 720p
                // started on the phone and the 1080p opened on the television, carries on.
                ?: runCatching { settings.resumeStartFor(currentItem()) }.getOrDefault(0L)
            // Never watched here: a link's moment, or the moment the poster chose to start at
            // (CP80) when the post is already read. A post still on its way is [resumeLate]'s.
            if (resumeMs <= 0 && hasMessage()) {
                val start = com.tmplayer.data.PostStart.startMs(linkSec = startLinkSec, savedMs = 0, postSec = postItem?.startSec ?: 0, durationSec = durationSec)
                if (start > 0) {
                    player?.seekTo(start)
                    resumeMs = start
                    return@launch
                }
            }
            if (resumeMs > 0) {
                player?.seekTo(resumeMs)
                showResumeFrom(resumeMs)
            }
        }
        itemScope.launch {
            resumeRead.join()
            if (bookmarked) return@launch
            val local = resumeMs
            // Resume across devices, when on: the other devices' places in, a couple of seconds
            // at most, so a video half watched on the phone opens there on the TV. Taken only when
            // it is well away from the local place, so a sync that agrees costs no seek.
            com.tmplayer.data.ResumeSync.pullFor(settings)
            val synced = runCatching { settings.resumeStartFor(currentItem()) }.getOrDefault(0L)
            if (synced > 0 && kotlin.math.abs(synced - local) > RESUME_SYNC_SLACK_MS) {
                if (!movedSinceResume(local)) {
                    player?.seekTo(synced)
                    resumeMs = synced
                    showResumeFrom(synced)
                }
                return@launch
            }
            // Never watched anywhere: the post's start, once the message is read.
            if (local <= 0 && resumeMs <= 0 && synced <= 0 && hasMessage()) {
                val post = postItem ?: kotlinx.coroutines.withTimeoutOrNull(POST_READ_MS) { readPost() }
                val start = com.tmplayer.data.PostStart.startMs(linkSec = startLinkSec, savedMs = 0, postSec = post?.startSec ?: 0, durationSec = durationSec)
                if (start > 0 && !movedSinceResume(0L)) {
                    player?.seekTo(start)
                    resumeMs = start
                }
            }
        }

        if (savable == null) {
            itemScope.launch {
                Td.awaitAuthorizedSession()
                savable = Td.maySave(chatId, messageId)
            }
        }

        itemScope.launch {
            val session = Td.awaitAuthorizedSession()
            markStart("session ready", STAGE_SESSION)
            // The settings the player is built with, read side by side and alongside the file's
            // own decisions below, rather than one after another once those are made: each is a
            // read of the store, and fifteen in a row came before the first request.
            val resumedRead = async {
                // A video with a saved position goes back to the speed and the tracks it was
                // playing with there, over the series' speed and languages. See [ResumeState].
                if (hasMessage()) runCatching { settings.resumeRecord(chatId, messageId)?.state }.getOrNull() else null
            }
            val scaleRead = async { runCatching { VideoScale.from(settings.videoScaleNow()) }.getOrDefault(VideoScale.Fit) }
            val tracksRead = async { runCatching { settings.trackChoice(seriesKey) }.getOrDefault(TrackChoice()) }
            val trackPrefsRead = async { runCatching { settings.trackPreferencesNow() }.getOrDefault(TrackPreferences()) }
            val downmixRead = async { runCatching { settings.downmixChoiceNow() }.getOrNull() }
            val boostRead = async { runCatching { settings.volumeBoostNow() }.getOrDefault(false) }
            val nightRead = async { runCatching { settings.nightModeNow() }.getOrDefault(false) }
            val evenRead = async { runCatching { settings.evenVolumeNow() }.getOrDefault(true) }
            val silenceRead = async { runCatching { settings.skipSilenceNow() }.getOrDefault(false) }
            val decoderRead = async { runCatching { settings.videoDecoderNow() }.getOrDefault(com.tmplayer.data.VideoDecoder.Automatic) }
            val styleRead = async { runCatching { settings.subtitleStyleNow() }.getOrDefault(SubtitleStyle()) }
            // Only a video from a message has somewhere to keep an offset; a file opened from
            // elsewhere starts in sync and forgets any change on the way out.
            val delaysRead = async { if (hasMessage()) runCatching { settings.syncDelays(chatId, messageId) }.getOrDefault(SyncDelays()) else null }
            // A video cut into byte pieces plays as the one file it was, joined: none of its
            // pieces is a video on its own, whatever the download index or the cache holds of one.
            val joined = mayBeJoined() && findPieces(session.client)
            if (joined && !prepareJoined(session.client)) return@launch
            // The download index first. Every way into this screen passes here, the next episode
            // and autoplay included, so a downloaded episode plays from its file however it was
            // reached.
            val downloaded = if (joined) null else LocalDownloads.fileFor(settings, chatId, messageId)
            downloadedFile = downloaded
            if (joined) {
                // Decided for every piece in [prepareJoined].
            } else if (downloaded != null) {
                downloadComplete = true
                downloadedFraction = 1f
                if (fileSizeBytes <= 0) fileSizeBytes = downloaded.length()
            } else {
                // An id carried in from a stored row, a channel or a notification may name another
                // file in this TDLib session ([Td.fileIdFits]), and a resume point past that file's
                // end would read as the end of the video: the message gives the current one. Asked
                // before anything is decided about the file: the room check, the watch cache and
                // Low storage mode all go by this id, and a mode granted to the old id left the
                // stream of the new one unbounded, the whole video fetched onto the disk.
                if (hasMessage() && !runCatching { Td.fileIdFits(fileId, fileSizeBytes) }.getOrDefault(true)) {
                    runCatching { Td.refreshMedia(chatId, messageId) }.getOrNull()?.let { fresh ->
                        fileId = fresh.fileId
                        fileSizeBytes = fresh.sizeBytes
                    }
                }
                pickTelegramQuality()
                val availability = Td.localFileAvailability(fileId)
                if (
                    availability != LocalFileAvailability.Complete &&
                    NetworkMonitor.status.value == NetworkStatus.Offline &&
                    !Td.connected.value
                ) {
                    showError(L.playerNotFullyDownloaded)
                    return@launch
                }
                if (!allowedOnThisConnection(availability)) return@launch
                // The grid asked [RoomOnDisk] about this very file a moment ago, and asking again
                // walks every cached video and can ask Telegram about each one: on a weak TV stick
                // that is time added to every press of Play. Trusted once, and only for the file
                // it was asked about; the next episode, autoplay and a change of copy ask again.
                val roomDecidedFor = intent.getIntExtra(EXTRA_ROOM_DECIDED, 0)
                intent.removeExtra(EXTRA_ROOM_DECIDED)
                if (roomDecidedFor != fileId && !makeRoomForThisVideo(availability)) return@launch
                // The head and the index on their way while the settings are read and the player
                // is built ([StreamWarmup]); a no-op if the press already started it.
                // From the saved place, read as [resumeRead] reads it, where the video resumes.
                if (availability != LocalFileAvailability.Complete) {
                    com.tmplayer.player.StreamWarmup.warm(fileId, fileSizeBytes, durationSec * 1000L) {
                        settings.resumeStartFor(currentItem())
                    }
                }
                holdFile(fileId)
                claimTheWatchCache()
                val streamsLow = boundTheStream(availability)
                // Fetching the whole film first is exactly what Low storage mode is there to avoid.
                val downloadFirst = !streamsLow && runCatching { settings.downloadBeforePlayingNow() }
                    .getOrDefault(false)
                if (downloadFirst && !fetchWholeFilm()) return@launch
            }
            val resumed = resumedRead.await()
            // Its own speed, else the series' or course's, else the chat's or topic's: [SpeedMemory].
            playbackSpeed = runCatching { settings.openingSpeed(resumed?.speed, speedScopes) }
                .getOrDefault(resumed?.speed?.let(PlaybackSpeed::sanitise) ?: PlaybackSpeed.DEFAULT)
            resumeTracks = resumed
            videoScale = scaleRead.await()
            // Read before the player is built, because the track selector is configured once and
            // a preference applied after the first frame means the first line of dialogue is in
            // the wrong language.
            tracks = tracksRead.await()
            val trackPrefs = trackPrefsRead.await()
            trackPlan = TrackRules.plan(resumed, tracks, trackPrefs, Translator.messages.tag)
            settingsSubtitlesPending = trackPlan.subtitleSource == TrackSource.Settings &&
                (trackPlan.subtitles is SubtitlePlan.On || trackPlan.subtitles is SubtitlePlan.IfForeignAudio)
            downmixChoice = downmixRead.await()
            volumeBoostOn = boostRead.await()
            nightModeOn = nightRead.await()
            evenVolumeOn = evenRead.await()
            skipSilence = silenceRead.await()
            videoDecoder = decoderRead.await()
            subtitleStyle = styleRead.await()
            applySubtitleStyle()
            delaysRead.await()?.let { syncDelays = it }
            subtitleDelayUs = syncDelays.subtitleMs * 1000
            // The saved position, given a moment more if it is still being read: set before
            // prepare(), the first request goes to where the video resumes, not to byte 0 and
            // then a seek away from it.
            if (resumeRead.isActive) withTimeoutOrNull(RESUME_WAIT_MS) { resumeRead.join() }
            if (!session.isCurrent()) return@launch
            markStart("file and settings decided", STAGE_DECIDED)
            startPlayback(session.client)
        }
    }

    /**
     * How many bytes the player may hold ahead. The size cap is the one that bites on a stream
     * with a high rate: 20 MB is about four seconds of a 40 Mbps remux, far short of the 50 s
     * [BUFFER_MAX_MS] asks for, so a dip in the line stalls the picture on any device that could
     * have held more. The figure goes by Android's own say on memory: the low-RAM flag (the 1 GB
     * sticks) and the heap the app is given, where the buffer lives.
     */
    private fun targetBufferBytes(): Int {
        val manager = getSystemService(Context.ACTIVITY_SERVICE) as? android.app.ActivityManager
            ?: return LOW_RAM_BUFFER_BYTES
        val heapMb = manager.memoryClass
        return when {
            manager.isLowRamDevice || heapMb < LOW_HEAP_MB -> LOW_RAM_BUFFER_BYTES
            // A TV stick or box reports a roomy heap but has 1 to 2 GB in all, shared with the
            // decoder and the picture: 1.27.1's 20 MB played there, the 64 MB that followed lagged.
            FormFactor.isTv(this) && heapMb < LARGE_HEAP_MB -> LOW_RAM_BUFFER_BYTES
            heapMb >= LARGE_HEAP_MB -> LARGE_BUFFER_BYTES
            else -> MID_BUFFER_BYTES
        }
    }

    /**
     * Whether the file plays with no seek index: Media3 knows its length but cannot seek in it,
     * an MKV written without its Cues most often ([noteSeekIndex]).
     */
    private var noSeekIndex = false

    /**
     * Notes, as the player gets ready, whether the video can be sought in. Media3 takes every seek
     * into a file it has no seek map for as a seek to the start, so a bar that looks usable would
     * throw the viewer back to the beginning: the bar is turned off instead, and the viewer told.
     */
    private fun noteSeekIndex() {
        val exo = player ?: return
        val unseekable = exo.duration != C.TIME_UNSET && !exo.isCurrentMediaItemSeekable
        if (unseekable == noSeekIndex) return
        noSeekIndex = unseekable
        controls?.seekable = !unseekable
        if (unseekable) {
            Log.i(START_TAG, "No seek index in file $fileId: seeking off")
            feedback?.message(L.playerSeekUnavailable, holdMs = SEEK_NOTICE_MS)
        }
    }

    /** True, with the reason said, when a seek asked for cannot be made ([noSeekIndex]). */
    private fun refuseSeek(): Boolean {
        if (!noSeekIndex) return false
        showGestureFeedback(L.playerSeekUnavailable)
        return true
    }

    /** Builds the player, points it at the file, and puts a video surface in front of it. */
    private fun startPlayback(client: TdlClient) {
        val exo = buildPlayer(client).also { built ->
            markStart("player built", STAGE_BUILT)
            built.addListener(playerListener)
            // A plain file for a download, which the default data source reads directly; the TDLib
            // stream for everything else, carrying its type so the right reader is tried first.
            val source = joinedSource ?: downloadedFile?.let { android.net.Uri.fromFile(it) }
                ?: tdFileUri(fileId, streamType(mediaMime, mediaFileName))
            // A stream also holds the Wi-Fi up while it plays, which matters once the screen is
            // off and the sound plays on ([PlaybackService]); a file on the disk needs only the CPU.
            val local = if (joinedSource != null) joinedAllLocal else downloadedFile != null
            built.setWakeMode(if (local) C.WAKE_MODE_LOCAL else C.WAKE_MODE_NETWORK)
            // Built out of sight (the next episode, or a stream opened again, behind the
            // notification): no video decoding until somebody can see it.
            if (inBackgroundAudio) hideVideoUnseen(built)
            subtitleFiles.metadata = sessionMetadata()
            built.setMediaItem(subtitleFiles.item(source))
            if (resumeMs > 0) built.seekTo(resumeMs)
            prepareStereo(built)
            // With a cast up (the next episode, or a video opened while casting) the TV plays it
            // and this player waits, unprepared, for the cast to end. So it does while the cast
            // screen says the TV was lost: only Play here starts it.
            if (cast?.connected != true && cast?.casting != true) {
                built.prepare()
                built.playWhenReady = true
                markStart("prepare asked", STAGE_PREPARED)
            }
        }
        player = exo
        wakeEndWatch()
        castClient = client
        attachMediaSession(exo)
        attachSurface(exo)
        cast?.let { c ->
            c.ready = true
            if (c.connected && !c.casting) c.begin(resumeMs.takeIf { it > 0 })
        }
    }

    /**
     * The video surface, the same on every device: a bare PlayerView with its own chrome off.
     *
     * The transport row is this app's, in player_controls.xml, and is the same row a thumb and a
     * D-pad drive. Leanback used to draw the television's controls and Media3 the phone's, which
     * was two apps' worth of look for one app; both are gone, and the activity still owns the
     * ExoPlayer while the loading sheet, the chips, the resume writes and the retry logic sit
     * over this surface exactly as they always did.
     */
    private fun attachSurface(exo: ExoPlayer) {
        val view = PlayerView(this)
        view.layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT,
        )
        view.useController = false
        // TMPlayer's own loading sheet and rebuffer chip already say what is happening, with a
        // speed and a percentage. A second spinner for the same wait is noise.
        view.setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER)
        // The activity draws subtitles itself, into a view sized and styled for this app, fed
        // straight off onCues. Leaving Media3's own subtitle view up renders every line twice,
        // slightly offset.
        view.subtitleView?.visibility = View.GONE
        view.player = exo
        findViewById<FrameLayout>(R.id.playback_container).addView(view)
        touchSurface = view
        view.resizeMode = videoScale.resizeMode
        controls?.setShape(videoScale)
        // On a television only while 3D is drawn for a headset, which needs a copy per eye.
        view.findViewById<View>(androidx.media3.ui.R.id.exo_content_frame)
            ?.addOnLayoutChangeListener { frame, _, _, _, _, _, _, _, _ -> fitSubtitlesTo(frame) }

        if (!FormFactor.isTv(this)) {
            // Fed from dispatchTouchEvent rather than attached here: see [PlayerGestures].
            gestures = PlayerGestures(
                context = this,
                window = window,
                onSkip = ::skipBy,
                onSeekStep = { zone, total, x, y -> feedback?.seekStep(zone, total, x, y) },
                onLevel = { left, fraction, brightness -> feedback?.level(left, fraction, brightness) },
                onScrub = { delta, target, length, rate -> feedback?.scrub(delta, target, length, rate) },
                onScrubEnd = { feedback?.scrubEnded() },
                onPinch = ::pinchScale,
                positionMs = { player?.currentPosition ?: 0L },
                durationMs = { player?.duration?.takeIf { it > 0 } ?: 0L },
                onSeekTo = { at -> if (!refuseSeek()) player?.seekTo(at) },
                onHold = ::holdFastForward,
                onTapControls = ::toggleControls,
                onTogglePlay = ::togglePlaybackFromPicture,
                isOnChrome = { x, y ->
                    controls?.isOnChrome(x, y) == true || isOnNextUpCard(x, y) || isOnSkipPill(x, y) || isOnUndoChip(x, y)
                },
                systemEdges = ::systemGestureEdges,
            ).also { it.prefs = touchPrefs }
        }

        insetTheControls()
    }

    /** The surface the frames land on, for the display requests only it can carry. */
    private fun videoSurface(): android.view.Surface? =
        (touchSurface?.videoSurfaceView as? android.view.SurfaceView)?.holder?.surface

    /**
     * Keeps the safe area off the video and on the things a finger has to reach.
     *
     * The video surface is laid out once at the full size of the window and never moves again:
     * padding it would take every pixel of status bar, gesture handle and notch out of the
     * picture. The gradient scrims keep every edge too, or an undimmed strip appears along the
     * notch and the gesture handle. Only the cluster of text, bar and buttons is padded inwards,
     * on top of the padding the layout already gives it.
     */
    private fun insetTheControls() {
        val cluster = findViewById<View>(R.id.controls_cluster)
        val corner = findViewById<View>(R.id.top_right_stack)
        val root = findViewById<View>(R.id.player_root)
        val topBar = findViewById<View>(R.id.controls_topbar)
        val phone = !FormFactor.isTv(this)
        val baseLeft = cluster.paddingLeft
        val baseRight = cluster.paddingRight
        val baseBottom = cluster.paddingBottom
        val barLeft = topBar.paddingLeft
        val barRight = topBar.paddingRight
        // On a phone the top bar owns the top edge, so the corner chips sit under it.
        val underTheBar = if (phone) (TOP_BAR_DP * resources.displayMetrics.density).toInt() else 0
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val safe = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout(),
            )
            cluster.updatePadding(
                left = baseLeft + safe.left,
                right = baseRight + safe.right,
                bottom = baseBottom + safe.bottom,
            )
            topBar.updatePadding(left = barLeft + safe.left, right = barRight + safe.right, top = safe.top)
            corner?.updatePadding(right = safe.right, top = safe.top + underTheBar)
            nextUpCard?.view?.updateLayoutParams<FrameLayout.LayoutParams> {
                rightMargin = safe.right + NextUpCard.marginPx(root)
                bottomMargin = safe.bottom + NextUpCard.bottomPx(root)
            }
            skipPill?.view?.updateLayoutParams<FrameLayout.LayoutParams> {
                rightMargin = safe.right + NextUpCard.marginPx(root)
                bottomMargin = safe.bottom + NextUpCard.bottomPx(root)
            }
            // "Back to" on the start side keeps clear of a cutout or the bar in landscape too.
            undoChip?.view?.updateLayoutParams<FrameLayout.LayoutParams> {
                leftMargin = safe.left + NextUpCard.marginPx(root)
                marginStart = safe.left + NextUpCard.marginPx(root)
                bottomMargin = safe.bottom + NextUpCard.bottomPx(root)
            }
            insets
        }
        if (phone) {
            // A drag that starts on the scrub bar near the left edge is a scrub, not the system's
            // back gesture: the bar's own strip is the one place that asks the system to stand
            // aside. Nothing else does, so the back swipe still works everywhere else.
            val timeBar = findViewById<View>(R.id.controls_timebar)
            timeBar.addOnLayoutChangeListener { view, _, _, _, _, _, _, _, _ ->
                ViewCompat.setSystemGestureExclusionRects(
                    view,
                    listOf(android.graphics.Rect(0, 0, view.width, view.height)),
                )
            }
        }
        // The video surface arrives long after the window's first inset pass, so without this the
        // controls keep the padding of whatever the insets were before there was a player at all.
        ViewCompat.requestApplyInsets(root)
    }

    /** The single tap: the one gesture that raises the transport row, and drops it again. */
    private fun toggleControls() {
        controls?.toggle()
    }

    /**
     * Play or pause from a button, a key or a headset. The row comes up first, so the centre disc
     * is on screen to turn when the pause lands; the player's listener then keeps it up for the
     * pause and lets it go after the resume ([onPauseChanged]). The disc's turn is the only
     * answer, on a phone and a television alike.
     */
    private fun togglePlayback() {
        val exo = player ?: return
        val nowPlaying = !exo.isPlaying
        if (!nowPlaying) raiseRowForPause()
        if (nowPlaying) exo.play() else exo.pause()
    }

    /** Whether the row was down when the pause came, for how soon it goes after the resume. */
    private var rowDownBeforePause = false

    /** True between [raiseRowForPause] and the pause it was for, so the row's state is read before it rose. */
    private var pauseNoted = false

    /** True once the viewer has paused this video, so the first play is not taken for a resume. */
    private var sawPause = false

    private fun raiseRowForPause() {
        if (statusOverlay.visibility == View.VISIBLE || locked || inPictureInPicture) return
        rowDownBeforePause = !controlsUp
        pauseNoted = true
        controls?.show()
    }

    /**
     * A pause or a resume, from anywhere: the row, a key, the picture, the remote, a headset, the
     * notification, the headphones coming out. See [PauseControls]: a pause brings the row up
     * for its whole length (on a television with the disc focused, so OK resumes), a resume
     * leaves it up for the disc to turn and then lets it go, sooner when it was down before.
     */
    private fun onPauseChanged(playWhenReady: Boolean, reason: Int) {
        val quiet = statusOverlay.visibility == View.VISIBLE || locked || inPictureInPicture || stillWatchingUp
        if (!playWhenReady) {
            if (reason == Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM) return
            if (!pauseNoted) rowDownBeforePause = !controlsUp
            pauseNoted = false
            sawPause = true
            if (!quiet) controls?.paused()
            return
        }
        if (!sawPause) return
        sawPause = false
        val after = PauseControls.hideAfterResumeMs(controls?.timeoutMs ?: 0L, rowDownBeforePause)
        rowDownBeforePause = false
        if (!quiet) controls?.resumed(after)
    }

    /** Adds up a run of key jumps on one side, so the figure counts 10, 20, 30 like the double tap. */
    private val jumpRun = SeekCounter()
    private var jumpRunSeconds = 0L

    /**
     * A remote's jump, from an arrow over the bare picture or the rewind and fast forward keys:
     * the seek lands at once, and the only thing drawn is the double tap's chevrons and the run's
     * total on the side the jump went.
     */
    private fun jumpFromRemote(forward: Boolean, repeatCount: Int = 0) {
        val base = if (forward) Skip.FORWARD_MS else Skip.BACK_MS
        // A held rewind or fast forward on a Fire TV remote scans in growing steps, the way
        // Amazon's own players answer it; elsewhere every repeat is the plain jump it always was.
        val step = if (remoteQuirks.holdSeekAccelerates) RemoteQuirks.holdStepMs(repeatCount, base) else base
        if (step <= 0) return
        skipBy(if (forward) step else -step)
        val zone = if (forward) TapZone.Right else TapZone.Left
        if (jumpRun.tap(zone, SystemClock.uptimeMillis()) == 1) jumpRunSeconds = 0
        jumpRunSeconds += step / 1000
        feedback?.jump(zone, jumpRunSeconds)
    }

    /** The remote's next and previous keys; false when the chat has no such episode. */
    private fun stepEpisode(forward: Boolean): Boolean {
        val found = _episodes.value
        val item = (if (forward) found.next else found.previous) ?: return false
        // From the notification or a headset with the player out of sight, where Android lets
        // no activity start: the episode plays in this one, behind the same notification.
        if (inBackgroundAudio) continueUnseen(item) else playEpisode(item)
        return true
    }

    /**
     * Play or pause from the picture itself: a double tap in its middle, or a single tap where the
     * viewer has chosen that. A single tap that pauses also raises the row, the way a paused
     * player always shows where it is; one that resumes lets the row go.
     *
     * The big glyph is only for a toggle over the bare picture: with the row up before or after,
     * its centre button has already morphed, and a glyph on top of it says the same thing twice.
     */
    private fun togglePlaybackFromPicture(@Suppress("UNUSED_PARAMETER") fromSingleTap: Boolean) {
        togglePlayback()
    }

    /**
     * Lets the window own the strip of screen the cutout sits in.
     *
     * The theme's shortEdges covers a notch on a short edge but not a hole punch or waterfall edge
     * on the long side, where the system parks the window clear of it and the video plays inside a
     * black margin. From Android 11 the window can claim the whole display and let the insets say
     * where the obstruction is, which is what the controls are then padded by.
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
     * Swiping from either edge still brings them back, because the behaviour is the transient one
     * rather than the sticky one that ignores the swipe.
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
     * Feeds the transport row's episode buttons once the chat has been asked about them.
     *
     * They stay hidden until the search comes back, and hidden for good on a video that is not
     * part of a series, so nothing appears that would do nothing when pressed.
     */
    private fun watchEpisodes() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                episodes.collect { found ->
                    controls?.setEpisodesAvailable(found.series != null)
                    episodesState()?.let { episodesList?.update(it) }
                    controls?.setEpisodes(
                        previousEpisode = found.previous,
                        nextEpisode = found.next,
                        previousLabel = episodeLabel(next = false, found.previousTag),
                        nextLabel = episodeLabel(next = true, found.nextTag),
                        previousCaption = found.previousTag?.code.orEmpty(),
                        nextCaption = found.nextTag?.code.orEmpty(),
                    )
                    renderControlsTitle()
                }
            }
        }
    }

    /**
     * The name over the scrub bar: the show's title, with the episode under it ("S01E04  ·  The
     * Lighthouse") and then the source. The episode is the series view's numbering once the chat
     * has answered, and what the file says of itself before then.
     */
    private fun renderControlsTitle() {
        val here = currentItem()
        val parsed = SeriesShelf.episodeOf(here)
        val episode = _episodes.value.current ?: EpisodeNeighbours.tagOf(here)
        val name = (episode?.show ?: parsed.title).ifBlank { parsed.title }.ifBlank { mediaTitle }
        val detail = listOfNotNull(
            episode?.label,
            // The chat, as YouTube names the channel: the length and size belong in Playback details.
            chatTitle.ifBlank { mediaSubtitle }.takeIf { it.isNotBlank() },
        ).joinToString("  ·  ")
        controls?.setTitle(name, detail)
    }

    /**
     * "Next: S01E02  ·  The Lighthouse", falling back to a bare "Next" when there is no episode to
     * name.
     */
    fun episodeLabel(next: Boolean, tag: EpisodeTag?): String {
        tag ?: return if (next) L.playerNext else L.playerPrevious
        return if (next) L.playerNextUp(tag.label) else L.playerPreviousUp(tag.label)
    }

    // ---- copies ------------------------------------------------------------------------------

    /**
     * Every copy of the video playing that this player knows of, itself among them: the ones the
     * page that opened it handed over ([CopyHandoff], which may span chats), an episode's copies
     * once the show is found, and for a film whatever a search of the chat for its title turns up.
     */
    private var copies: List<MediaItem> = emptyList()

    private fun findCopies() {
        val here = currentItem()
        setCopies(CopyHandoff.copiesOf(here))
        // The quality played counts towards the one chosen for the viewer next time.
        val played = copies.firstOrNull { it.id == here.id } ?: here
        App.backgroundScope.launch { runCatching { settings.notePlayedQuality(played) } }
        lifecycleScope.launch {
            copyHandPicked = runCatching { settings.copyPickFor(here) == here.id }.getOrDefault(false)
            heavyAskSkips = runCatching { settings.heavyAskSkipsNow() }.getOrDefault(emptySet())
        }
        if (VideoCopies.keyOf(here) == null) return
        itemScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                episodes.collect { steps ->
                    val episode = steps.series?.episodes?.firstOrNull { ep -> ep.copies.any { it.id == here.id } }
                    if (episode != null) setCopies(copies + episode.copies)
                }
            }
        }
        if (chatId == 0L || SeriesShelf.episodeOf(here).episode != null) return
        itemScope.launch {
            afterOpening()
            val session = Td.awaitAuthorizedSession()
            val title = SeriesShelf.episodeOf(here).title.ifBlank { return@launch }
            val found = runCatching { ChatRepository(session.client).mediaPage(chatId, query = title).items }.getOrNull().orEmpty()
            val key = VideoCopies.keyOf(here)
            setCopies(copies + found.filter { VideoCopies.keyOf(it) == key })
        }
    }

    /** Takes [list] as the copies, and names the one playing on the copy button when there is a choice. */
    private fun setCopies(list: List<MediaItem>) {
        val here = currentItem()
        // The same file posted twice is one copy to switch to, not two.
        val all = com.tmplayer.data.VideoCopies.oneOfEachFile((list + here).distinctBy { it.id }, here.id)
        copies = all
        // The listed copy carries Telegram's picture size, which the start arguments do not.
        val playing = all.firstOrNull { it.id == here.id } ?: here
        val qualities = telegramQualities()
        controls?.setCopy(
            when {
                playingQuality != 0 -> com.tmplayer.data.TelegramQualities.label(playingQuality)
                all.size > 1 || qualities.isNotEmpty() -> playing.copyQuality() ?: postItem?.copyQuality() ?: L.detailUploadQuality
                else -> null
            },
        )
    }

    /** The chat a copy is in, by name, for the copies list and for the player it opens. */
    private fun copyChatTitle(copy: MediaItem): String =
        if (copy.chatId == chatId) chatTitle else CopyHandoff.chatTitleOf(copy).orEmpty()

    /** The copy button: every copy, the one playing ticked; choosing another plays it from here. */
    fun openCopies() {
        val qualities = telegramQualities()
        if (copies.size <= 1 && qualities.isEmpty()) return
        val list = copies.ifEmpty { listOf(currentItem()) }
        showSheet {
            it.showCopies(
                list, currentItem().id, ::copyChatTitle, ::switchCopy,
                qualities = qualities, playingQuality = playingQuality, onQuality = ::switchQuality,
            )
        }
    }

    /** Telegram's lighter copies of the upload playing (CP80); none for a download or a joined video. */
    private fun telegramQualities(): List<com.tmplayer.data.TelegramQuality> =
        if (downloadedFile != null || joinedSource != null) emptyList() else postItem?.qualities.orEmpty()

    /**
     * Plays [copy] in place of the copy playing, at the same moment ([VideoCopies.carryOver]
     * allowing for a copy a little longer or shorter), and remembers it as the copy for this video
     * and the quality for the next episode. The place is per copy: this one keeps its own line,
     * and the new copy's is read as the newest from then on.
     */
    private fun switchCopy(copy: MediaItem, remember: Boolean = true) {
        if (copy.id == currentItem().id) return
        val at = player?.currentPosition ?: 0L
        val from = player?.duration?.takeIf { it > 0 } ?: durationSec * 1000L
        VideoCopies.carryOver(at, from, copy.durationSec * 1000L)?.takeIf { it > 0 }?.let {
            BookmarkJump.request(copy.chatId, copy.messageId, it)
        }
        // A copy the player took by itself for smoothness is not the viewer's pick.
        if (remember) App.backgroundScope.launch { runCatching { settings.setCopyPick(copy) } }
        // The new player finds the same list, chats and all.
        CopyHandoff.offer(copies, copies.associate { it.chatId to copyChatTitle(it) }.filterValues { it.isNotBlank() })
        playEpisode(copy, copyChatTitle(copy).ifBlank { chatTitle })
    }

    // ---- Telegram's own qualities (CP80) -------------------------------------------------------

    /** The copies [intent] carried in ([EXTRA_QUALITY_FILES]), or none when the arrays do not agree. */
    private fun qualitiesFrom(from: Intent): List<com.tmplayer.data.TelegramQuality> {
        val files = from.getIntArrayExtra(EXTRA_QUALITY_FILES) ?: return emptyList()
        val widths = from.getIntArrayExtra(EXTRA_QUALITY_WIDTHS) ?: return emptyList()
        val heights = from.getIntArrayExtra(EXTRA_QUALITY_HEIGHTS) ?: return emptyList()
        val codecs = from.getStringArrayExtra(EXTRA_QUALITY_CODECS) ?: return emptyList()
        val sizes = from.getLongArrayExtra(EXTRA_QUALITY_SIZES) ?: return emptyList()
        if (setOf(files.size, widths.size, heights.size, codecs.size, sizes.size).size != 1) return emptyList()
        return files.indices.map { i ->
            com.tmplayer.data.TelegramQuality(files[i], widths[i], heights[i], codecs[i], sizes[i])
        }
    }

    /** The message as Telegram has it now ([postItem]), read once per video. */
    private suspend fun readPost(): MediaItem? {
        postItem?.let { return it }
        if (!hasMessage()) return null
        val fresh = runCatching { Td.refreshMedia(chatId, messageId) }.getOrNull() ?: return null
        postItem = fresh
        lifecycleScope.launch { onPostRead(fresh) }
        return fresh
    }

    /**
     * What the message read again brings to a player already up: the quality button, the
     * storyboard, and the video's pictures where it was opened without them (the loader's and the
     * scrub card's fallback).
     */
    private fun onPostRead(fresh: MediaItem) {
        setCopies(copies)
        startStoryboard(fresh)
        if (!artworkShown) showArtwork(fresh.miniThumbnail, fresh.thumbnailFileId)
    }

    /** The video's pictures are up or on their way, from the intent or the message read again. */
    private var artworkShown = false

    /**
     * Which copy to stream, decided before anything is asked about the file: the one chosen on the
     * detail page or the player's quality list ([QualityWish]), else a lighter copy of Telegram's
     * when Save mobile data is on over mobile data or the line measured on this run is too slow
     * for the upload ([TelegramQualities.choose]); else the upload. Never for a video joined from
     * pieces, a download, or an upload partly or wholly on this device already.
     */
    private suspend fun pickTelegramQuality() {
        if (!hasMessage() || joinedSource != null || downloadedFile != null || playingQuality != 0) return
        val wish = com.tmplayer.data.QualityWish.take(chatId, messageId)
        qualityHandPicked = wish != null
        if (wish == 0) return
        // An upload this player found it would decode in software too slowly ([checkSoftwareDecode]),
        // opened again: straight to a copy the hardware takes, without starting the upload first.
        // Not while casting, where the TV decodes it.
        val softwareSwap = wish == null && weakDevice && !castUp() &&
            com.tmplayer.data.SoftwareDecode.knownHeavy(chatId, messageId)
        if (softwareSwap) softwareSwapTried = true
        // No copy chosen: nothing to read unless the player could pick one itself, which takes
        // Save mobile data in force or a line measured too slow. The usual open asks Telegram
        // nothing here, where it used to wait up to [POST_READ_MS] for the message.
        var saveData = false
        if (wish == null) {
            saveData = NetworkMonitor.metered.value && MeteredPolicy.saveMobileDataInForce(
                runCatching { settings.saveMobileDataNow() }.getOrDefault(false),
                FormFactor.isTv(this),
            )
            saveDataAtOpen = saveData
        }
        if (wish == null && !softwareSwap) {
            if (runCatching { Td.localDownloadedBytes(fileId) }.getOrDefault(0L) > 0) return
            val known = fileSizeBytes > 0 && durationSec > 0
            if (known && !com.tmplayer.data.TelegramQualities.mightChoose(fileSizeBytes, durationSec, saveData, com.tmplayer.player.LineSpeed.recent())) return
        }
        // The copies the list already had, carried in with the video; the message read again only
        // when it had none.
        val fresh = carriedQualities.takeIf { it.isNotEmpty() }?.let { currentItem().copy(qualities = it) }
            ?: kotlinx.coroutines.withTimeoutOrNull(POST_READ_MS) { readPost() }
            ?: return
        if (fresh.qualities.isEmpty()) return
        val chosen = if (wish != null) {
            fresh.qualities.firstOrNull { it.height == wish }
        } else {
            // A copy the hardware takes first, for an upload known to be too much in software;
            // else (or with none) the usual choice for the line and Save mobile data.
            val software = if (softwareSwap) {
                com.tmplayer.data.TelegramQualities.forSoftwareDecode(fresh.qualities, saveData) { HardwareDecoders.has(this, it) }
                    ?.also { qualityPickedWhy = com.tmplayer.data.TelegramQualities.Why.Software }
            } else {
                null
            }
            software ?: run {
                val upload = fresh.copy(fileId = fileId, sizeBytes = fileSizeBytes.takeIf { it > 0 } ?: fresh.sizeBytes, durationSec = durationSec.takeIf { it > 0 } ?: fresh.durationSec)
                com.tmplayer.data.TelegramQualities.choose(upload, saveData, com.tmplayer.player.LineSpeed.recent())
                    ?.also { qualityPickedWhy = it.why }?.quality
            }
        } ?: return
        uploadFileId = fileId
        uploadSizeBytes = fileSizeBytes
        fileId = chosen.fileId
        if (chosen.sizeBytes > 0) fileSizeBytes = chosen.sizeBytes
        playingQuality = chosen.height
        qualityPickedWhy?.let { why ->
            val label = com.tmplayer.data.TelegramQualities.label(chosen.height)
            val text = when (why) {
                com.tmplayer.data.TelegramQualities.Why.SaveData -> L.playerLighterPickedData(label)
                com.tmplayer.data.TelegramQualities.Why.SlowLine -> L.playerLighterPickedSlow(label)
                com.tmplayer.data.TelegramQualities.Why.Software -> L.playerLighterPickedSoftware(label)
            }
            // Long enough to read the longer line about software decoding.
            val software = why == com.tmplayer.data.TelegramQualities.Why.Software
            lifecycleScope.launch { if (software) feedback?.message(text, holdMs = SOFTWARE_NOTE_MS) else showGestureFeedback(text) }
        }
    }

    /**
     * Plays Telegram's [height] copy (0 for the upload) in place of what plays now, from the same
     * moment: a fresh player, as a switch of copy is, handed the choice through [QualityWish].
     */
    private fun switchQuality(height: Int) {
        if (height == playingQuality) return
        val at = player?.currentPosition ?: 0L
        if (at > 0) BookmarkJump.request(chatId, messageId, at)
        com.tmplayer.data.QualityWish.request(chatId, messageId, height)
        playEpisode(currentItem())
    }

    /** "Play Telegram's 480p" on the sheet that says nothing is arriving: the next copy down. */
    private fun lighterQuality(): com.tmplayer.data.TelegramQuality? {
        val fresh = postItem ?: return null
        if (downloadedFile != null || joinedSource != null) return null
        val now = if (playingQuality != 0) com.tmplayer.data.TelegramQualities.withQuality(fresh, fresh.qualities.firstOrNull { it.height == playingQuality } ?: return null) else fresh
        return com.tmplayer.data.TelegramQualities.lighter(now)
    }

    /** The video playing, as the chat lists it, for the series view's grouping. */
    private fun currentItem(): MediaItem = MediaItem(
        chatId = chatId,
        messageId = messageId,
        fileId = uploadId(),
        title = mediaTitle,
        sizeBytes = uploadSize(),
        durationSec = durationSec,
        mimeType = mediaMime,
        thumbnailFileId = 0,
        miniThumbnail = null,
        date = 0,
        fileName = mediaFileName,
        caption = mediaCaption,
        topicId = mediaTopicId,
    )

    /**
     * Every touch in the window is offered to the gestures before any view sees it.
     *
     * Two arrangements, because the picture has two states. While the transport row is up, its
     * buttons and its scrub bar are what the finger came for, so the event is offered here and then
     * passed straight on to them, which is what keeps double-tap seek working over a raised row.
     *
     * While the row is down there is nothing on screen but the picture, and the event stops here.
     * Media3's own view treats a great many touches as a reason to throw the transport row over the
     * video, so a brightness drag, a scrub or a hold would each end with the controls in the way of
     * what the gesture just did. Only [toggleControls] raises them, from a single tap.
     *
     * The loading and error sheets are the exception in both directions, and so is the track
     * picker: they carry buttons, and a button nobody can press is worse than no gesture at all.
     */
    override fun dispatchTouchEvent(event: android.view.MotionEvent): Boolean {
        // On the cast screen every touch is the screen's own; the stopped picture has no gestures.
        if (cast?.casting == true) return super.dispatchTouchEvent(event)
        val surface = touchSurface
        val pickerOpen =
            TrackPickerFragment.isOpen(supportFragmentManager)
        if (locked) {
            // The shield over everything takes the touch; it offers the way out and nothing else.
            return super.dispatchTouchEvent(event)
        }
        if (surface == null || inPictureInPicture || pickerOpen || statusOverlay.visibility == View.VISIBLE) {
            // A hold or a scrub that was under way when the sheet came up still has to end.
            if (event.actionMasked == android.view.MotionEvent.ACTION_UP ||
                event.actionMasked == android.view.MotionEvent.ACTION_CANCEL
            ) {
                gestures?.cancel()
            }
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
     * Runs the video fast for as long as a finger is held on it, then puts it back.
     *
     * It returns to the speed the viewer chose, not 1x: somebody watching at 1.5x who holds to skip
     * an aside expects to be back at 1.5x when they let go.
     */
    private fun holdFastForward(holding: Boolean) {
        val exo = player ?: return
        if (holding) {
            val speed = touchPrefs.holdSpeed.takeIf { it > 0f } ?: TouchPrefs.HOLD_DEFAULT
            exo.setPlaybackSpeed(speed)
            // The picture is what the viewer is skimming; the row would only be in the way.
            controls?.hideAnimated()
            feedback?.holdStarted(speed)
        } else {
            exo.setPlaybackSpeed(playbackSpeed)
            feedback?.holdEnded()
        }
    }

    /**
     * Asks the system for the orientation this player is set to, and remembers it for the session.
     *
     * Called before the content view on the way in, so the window opens the right way up rather
     * than turning after it has drawn.
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
        layOutSheetForThisScreen()
        showGestureFeedback(orientation.label)
    }

    private fun renderOrientationButton() {
        controls?.setOrientationIcon(
            when (orientation) {
                ScreenOrientation.Follow -> R.drawable.ic_rotate_auto
                ScreenOrientation.Landscape -> R.drawable.ic_rotate_landscape
                ScreenOrientation.Portrait -> R.drawable.ic_rotate_portrait
            },
            orientation.label,
        )
    }

    /**
     * Lays the subtitles over the picture rather than over the whole screen, on a phone.
     *
     * Media3 places and sizes a cue as a share of the view it draws in. Spread over a phone held
     * upright, that view is a tall strip with the picture a band across its middle: picture
     * subtitles (PGS and the like) came out squeezed sideways and stretched tall, and lines
     * positioned near the bottom landed in the black under the picture. Fitted to the picture,
     * they keep their shape and sit where the film put them, the same upright as sideways.
     *
     * The bottom margin is clearance from the screen's edge, so it applies only where the picture
     * runs down to that edge. A television keeps the full-screen view: its screen is the shape
     * of the picture, and a letterboxed film's subtitles stay in the bar below it.
     */
    private fun fitSubtitlesTo(frame: View) {
        val root = findViewById<View>(R.id.player_root)
        if (root.width == 0 || frame.width == 0) return
        val vr = stereo.on && stereo.view == Stereo3d.View.Vr
        if (FormFactor.isTv(this) && !vr) {
            fullScreenSubtitles()
            return
        }
        val at = IntArray(2)
        val origin = IntArray(2)
        frame.getLocationInWindow(at)
        root.getLocationInWindow(origin)
        // Clamped to the screen, for Zoom, where the picture runs off both sides.
        val left = (at[0] - origin[0]).coerceIn(0, root.width)
        val top = (at[1] - origin[1]).coerceIn(0, root.height)
        val right = (at[0] - origin[0] + frame.width).coerceIn(left, root.width)
        val bottom = (at[1] - origin[1] + frame.height).coerceIn(top, root.height)
        // The whole picture, to its bottom edge. The clearance a full-height picture needs from
        // the screen's edge (a television's overscan, a phone's rounded corners) is folded into
        // the bottom padding by [subtitleBottomFraction] instead of taken off the height here:
        // taking it off and then adding the position's share on top stacked the two, so on a
        // picture that filled the screen the lines stood clearly higher than on a letterboxed one.
        val params = subtitleView.layoutParams as FrameLayout.LayoutParams
        // For a headset each eye's half of the picture gets its own copy of the lines.
        val width = if (vr) (right - left) / 2 else right - left
        val height = (bottom - top).coerceAtLeast(0)
        if (
            params.width == width && params.height == height && subtitleGapBelow == root.height - bottom &&
            params.leftMargin == left && params.topMargin == top && params.bottomMargin == 0 &&
            (subtitleTwin != null) == vr
        ) {
            return
        }
        subtitleGapBelow = root.height - bottom
        params.gravity = Gravity.TOP or Gravity.LEFT
        params.width = width
        params.height = height
        params.leftMargin = left
        params.topMargin = top
        params.bottomMargin = 0
        // Set from inside a layout pass, so the change is posted rather than lost.
        subtitleView.post {
            subtitleView.layoutParams = params
            if (vr) {
                twinSubtitles().layoutParams = FrameLayout.LayoutParams(params).apply { leftMargin = left + width }
            } else {
                dropSubtitleTwin()
            }
            applySubtitleStyle()
            liftSubtitles()
        }
    }

    /**
     * A television's subtitles as the layout has them, over the whole screen, once 3D for a
     * headset is off again. Nothing to do while they already are.
     */
    private fun fullScreenSubtitles() {
        val params = subtitleView.layoutParams as FrameLayout.LayoutParams
        val margin = resources.getDimensionPixelSize(R.dimen.player_subtitle_bottom)
        if (subtitleTwin == null && params.width == FrameLayout.LayoutParams.MATCH_PARENT && params.bottomMargin == margin) return
        subtitleGapBelow = 0
        params.gravity = FrameLayout.LayoutParams.UNSPECIFIED_GRAVITY
        params.width = FrameLayout.LayoutParams.MATCH_PARENT
        params.height = FrameLayout.LayoutParams.MATCH_PARENT
        params.leftMargin = 0
        params.topMargin = 0
        params.bottomMargin = margin
        subtitleView.post {
            subtitleView.layoutParams = params
            dropSubtitleTwin()
            applySubtitleStyle()
            liftSubtitles()
        }
    }

    /** The right eye's copy of the subtitles, made the first time a headset needs it. */
    private fun twinSubtitles(): SubtitleView = subtitleTwin ?: SubtitleView(this).also { twin ->
        twin.setApplyEmbeddedStyles(true)
        val parent = subtitleView.parent as android.view.ViewGroup
        parent.addView(twin, parent.indexOfChild(subtitleView) + 1, FrameLayout.LayoutParams(0, 0))
        twin.visibility = subtitleView.visibility
        twin.alpha = subtitleView.alpha
        twin.translationY = subtitleView.translationY
        twin.setCues(shownCues)
        subtitleTwin = twin
    }

    private fun dropSubtitleTwin() {
        val twin = subtitleTwin ?: return
        subtitleTwin = null
        (twin.parent as? android.view.ViewGroup)?.removeView(twin)
    }

    /** Puts [cues] on screen, in both copies while a headset has two. */
    private fun showCues(cues: List<Cue>) {
        shownCues = cues
        subtitleView.setCues(cues)
        subtitleTwin?.setCues(cues)
    }

    /** Fits the subtitles again after 3D changed, as the picture's next layout would. */
    private fun refitSubtitles() {
        touchSurface?.findViewById<View>(androidx.media3.ui.R.id.exo_content_frame)?.let(::fitSubtitlesTo)
    }

    /**
     * The PlayerView owns the picture's shape on every device now, so the codec's own scaling
     * stays on plain fit: the view sizes its surface to the chosen shape, and the codec scaling
     * the frames again inside it would cancel the choice out.
     */
    private fun applyScale(scale: VideoScale) {
        videoScale = scale
        touchSurface?.resizeMode = scale.resizeMode
        player?.videoScalingMode = C.VIDEO_SCALING_MODE_SCALE_TO_FIT
        controls?.setShape(scale, animate = true)
        showGestureFeedback(scale.label)
        lifecycleScope.launch { runCatching { settings.setVideoScale(scale.name) } }
    }

    /** The speed button: every speed to pick from at once, rather than stepping through them. */
    fun pickSpeed() {
        showSheet { it.showSpeed(playbackSpeed, ::setSpeed, skipSilence, ::setSkipSilence) }
    }

    /** The speed sheet's Skip silence: on the sound already playing, and kept for every video. */
    private fun setSkipSilence(on: Boolean) {
        skipSilence = on
        player?.skipSilenceEnabled = on
        showGestureFeedback(if (on) L.playerSkipSilenceOn else L.playerSkipSilenceOff)
        lifecycleScope.launch { runCatching { settings.setSkipSilence(on) } }
    }

    /**
     * A figure for whatever a gesture is changing, gone again shortly after the finger lifts.
     *
     * It moves to the side the gesture is on, so the figure reads as an answer to the finger. This
     * is the only thing a drag or a key press puts on screen: the transport row stays where it was.
     */
    private fun showGestureFeedback(text: String, side: Int = PlayerGestures.SIDE_CENTRE) {
        feedback?.message(text, side)
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
            showStatus(L.playerStarting)
            return true
        }
        waitingForWholeFilm = true
        showStatus(L.playerDownloadingWhole)
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
            showError(L.playerDownloadUnfinished)
            return false
        }
        showStatus(L.playerStarting)
        return true
    }

    /**
     * Checks the video against the connection before a byte of it is fetched.
     *
     * A file already on disk is never questioned, and neither is a small one. Only a large pull
     * over a metered connection stops to ask, and only the first time in a session, because being
     * asked before every episode is its own kind of broken.
     *
     * Returns false when there is nothing to start: the sheet on screen is the answer.
     */
    private suspend fun allowedOnThisConnection(availability: LocalFileAvailability): Boolean {
        val wifiOnly = MeteredPolicy.wifiOnlyInForce(
            runCatching { settings.wifiOnlyDownloadsNow() }.getOrDefault(false),
            FormFactor.isTv(this),
        )
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
                    L.playerWifiOnly,
                    retryable = false,
                )
                false
            }
            MeteredDecision.Warn -> awaitMeteredConsent()
        }
    }

    /** The prompt itself: the loading sheet, holding, with one button that carries on. */
    private suspend fun awaitMeteredConsent(): Boolean {
        // The next episode reached out of sight: the question waits for the viewer.
        awaitSeen()
        val consented = CompletableDeferred<Boolean>()
        openingFilm = false
        statusOverlay.visibility = View.VISIBLE
        statusIcon.visibility = View.GONE
        rebufferChip.visibility = View.GONE
        feedback?.buffering(false)
        statusSpinner?.visibility = View.GONE
        statusProgress.visibility = View.GONE
        statusDetail.visibility = View.GONE
        statusTitle.text = L.playerMobileDataTitle
        hideFailureActions()
        statusText.text = L.playerMobileDataBody(Translator.messages.formatter.size(fileSizeBytes))
        statusRetry?.apply {
            text = L.playerPlayAnyway
            visibility = View.VISIBLE
            setOnClickListener {
                text = L.commonTryAgain
                setOnClickListener { retryPlayback() }
                consented.complete(true)
            }
            requestFocus()
        }
        val answer = consented.await()
        // Remembered for the process, not on disk: a session is the unit of consent here, and
        // writing it down would mean asking once ever, which is not the same promise.
        meteredWarningAccepted = true
        showStatus(L.playerStarting)
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
        failurePinned = false
        stereoPipeline = false
        // A new player starts with both decoders on, so each may switch by itself once again.
        decodersSwitched.clear()
        // Hardware decoders first; NextLib's FFmpeg renderers pick up the audio codecs a TV stick
        // has no silicon for: DTS and TrueHD tracks are common in video remuxes.
        // Read once here, outside the factory, so nothing depends on when the factory's own
        // constructor gets round to it.
        val maskHdr10Plus = HevcHdr10PlusMasker.affectedDevice(android.os.Build.MANUFACTURER, android.os.Build.MODEL)
        val fireTv = DeviceQuirks.isFireTv(this@PlayerActivity)
        val renderers = object : NextRenderersFactory(this@PlayerActivity) {
            /**
             * The stock codec adapters, except on the Fire TV sticks with the MediaTek MT8696
             * decoder, where a Dolby Vision profile 8 decoder gets [Hdr10PlusMaskingCodecAdapterFactory]:
             * a file that carries HDR10+ next to the Dolby Vision RPU plays as a black screen there
             * otherwise. Wrapping the factory's adapters, not its renderers, keeps every renderer
             * option Media3 and NextLib set. Elsewhere the HDR10+ layer can be what a screen without
             * Dolby Vision shows, so it is left alone.
             */
            override fun getCodecAdapterFactory(): androidx.media3.exoplayer.mediacodec.MediaCodecAdapter.Factory {
                val stock = super.getCodecAdapterFactory()
                return if (maskHdr10Plus) Hdr10PlusMaskingCodecAdapterFactory(stock) else stock
            }

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
                val folds = if (AudioDownmix.wanted(downmixChoice, FormFactor.isTv(context))) {
                    AudioDownmix.stereoFolds()
                } else {
                    emptyList()
                }
                // Last in the chain, so the offset is counted in the frames the sink actually plays.
                val offset = AudioOffsetProcessor().also {
                    it.offsetUs = syncDelays.audioMs * 1000
                    audioOffset = it
                }
                // After the fold, so the compressor hears the two channels that will be played.
                val boost = VolumeBoostProcessor().also {
                    it.enabled = volumeBoostOn
                    it.night = nightModeOn
                    it.level = evenVolumeOn
                    volumeBoost = it
                }
                val processors = if (folds.isEmpty()) {
                    arrayOf<AudioProcessor>(boost, offset)
                } else {
                    val mixer = ChannelMixingAudioProcessor()
                    folds.forEach { fold ->
                        mixer.putChannelMixingMatrix(
                            when (fold) {
                                // Spelt out because Media3 1.10 dropped the create() shorthand:
                                // ones down the diagonal, every channel passed through as it is.
                                is AudioDownmix.Fold.Untouched -> ChannelMixingMatrix(
                                    fold.channels,
                                    fold.channels,
                                    FloatArray(fold.channels * fold.channels) { index ->
                                        if (index % (fold.channels + 1) == 0) 1f else 0f
                                    },
                                )

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
                    arrayOf<AudioProcessor>(mixer, boost, offset)
                }
                // Twice Media3's passthrough allowance, and four times its AC-3 multiplier: about
                // two seconds of bitstream instead of half a second. The stock figure is what let
                // a Dolby Digital 5.1 track underrun on HDMI sinks that drain the AudioTrack in
                // bursts, and an underrun reads on screen as the picture freezing for a moment
                // and coming back.
                //
                // The PCM buffer matters just as much: when the FFmpeg renderer decodes the track
                // (every DTS film, and Dolby on a device that will not take the bitstream), the
                // sink is fed PCM. On the stick that track was seen going unfed for over a second
                // at a stretch while the decoder fought the video for the same small cores, so the
                // buffer is sized to ride out exactly that. Media3 1.11 replaced the old floor and
                // ceiling (which it now ignores) with one fixed PCM length, 500 ms by default, so
                // the length is set directly. Memory cost is a couple of megabytes at worst, on
                // the stick's terms nothing next to one video frame.
                val audioBuffers = DefaultAudioTrackBufferSizeProvider.Builder()
                    .setPassthroughBufferDurationUs(PASSTHROUGH_BUFFER_US)
                    .setAc3BufferMultiplicationFactor(AC3_BUFFER_FACTOR)
                    .setTargetPcmBufferDurationUs(PCM_BUFFER_US)
                    .build()
                // The provider goes to the AudioTrack output rather than to the sink itself, which
                // is where Media3 1.11 moved it; otherwise this is the output the sink would build.
                // On a Fire TV a bitstream track is held under its AudioFlinger's 2 MB line, see
                // [PassthroughBufferCap]; elsewhere nothing has been seen refusing the larger size.
                val output = AudioTrackAudioOutputProvider.Builder(context)
                    .setAudioTrackBufferSizeProvider(if (fireTv) PassthroughBufferCap(audioBuffers) else audioBuffers)
                    .build()
                return DefaultAudioSink.Builder(context)
                    .setEnableFloatOutput(enableFloatOutput)
                    .setEnableAudioOutputPlaybackParameters(enableAudioTrackPlaybackParams)
                    .setAudioProcessors(processors)
                    .setAudioOutputProvider(output)
                    .build()
            }

            /**
             * Sound keeps the device's decoder first whatever the Decoder setting says: "Prefer
             * software video" is about the picture, and the device's own decoder is what passes
             * Dolby through to a receiver.
             */
            override fun buildAudioRenderers(
                context: Context,
                extensionRendererMode: Int,
                mediaCodecSelector: androidx.media3.exoplayer.mediacodec.MediaCodecSelector,
                enableDecoderFallback: Boolean,
                audioSink: AudioSink,
                eventHandler: android.os.Handler,
                eventListener: androidx.media3.exoplayer.audio.AudioRendererEventListener,
                out: ArrayList<Renderer>,
            ) {
                val audioMode = if (extensionRendererMode == DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER) {
                    DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON
                } else {
                    extensionRendererMode
                }
                super.buildAudioRenderers(
                    context, audioMode, mediaCodecSelector, enableDecoderFallback, audioSink, eventHandler, eventListener, out,
                )
            }

            /**
             * The video renderers as built, with the device's decoder judged by the picture's size
             * rather than the level its file claims. See [LevelLenientVideoRenderer].
             */
            override fun buildVideoRenderers(
                context: Context,
                extensionRendererMode: Int,
                mediaCodecSelector: androidx.media3.exoplayer.mediacodec.MediaCodecSelector,
                enableDecoderFallback: Boolean,
                eventHandler: android.os.Handler,
                eventListener: androidx.media3.exoplayer.video.VideoRendererEventListener,
                allowedVideoJoiningTimeMs: Long,
                out: ArrayList<Renderer>,
            ) {
                val built = ArrayList<Renderer>()
                super.buildVideoRenderers(
                    context, extensionRendererMode, mediaCodecSelector, enableDecoderFallback,
                    eventHandler, eventListener, allowedVideoJoiningTimeMs, built,
                )
                built.mapTo(out) {
                    if (it is androidx.media3.exoplayer.video.MediaCodecVideoRenderer) LevelLenientVideoRenderer(it) else it
                }
            }

            /** Media3's own text renderers, each behind the subtitle delay. See [DelayedTextRenderer]. */
            override fun buildTextRenderers(
                context: Context,
                output: TextOutput,
                outputLooper: Looper,
                extensionRendererMode: Int,
                out: ArrayList<Renderer>,
            ) {
                val built = ArrayList<Renderer>()
                super.buildTextRenderers(context, output, outputLooper, extensionRendererMode, built)
                built.mapTo(out) { DelayedTextRenderer(it) { subtitleDelayUs } }
            }
        }
            // Prefer software video puts the app's FFmpeg video decoder ahead of the device's;
            // a format FFmpeg has no decoder for still goes to the device's.
            .setExtensionRendererMode(
                if (videoDecoder == com.tmplayer.data.VideoDecoder.Software) {
                    DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER
                } else {
                    DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON
                },
            )
            .setEnableDecoderFallback(true)

        // Sized by the memory the system gives the app ([targetBufferBytes]): small on a 1 GB
        // stick, where a generous buffer is what gets the app killed, generous elsewhere.
        val bufferBytes = targetBufferBytes()
        // The back buffer is held inside the same byte budget as the buffer ahead, from the
        // keyframe before it, so on a 20 MB budget ten seconds behind (more with a long GOP) of a
        // high rate file left a few seconds ahead, or none, and the picture stalled with the line
        // fine. There the back buffer is cut to a few seconds: a longer jump back reads the bytes
        // off the disk, where the stream already put them.
        val backBufferMs = if (bufferBytes <= LOW_RAM_BUFFER_BYTES) LOW_RAM_BACK_BUFFER_MS else BACK_BUFFER_MS
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(BUFFER_MIN_MS, BUFFER_MAX_MS, BUFFER_PLAYBACK_MS, BUFFER_REBUFFER_MS)
            // The same figures again for what Media3 calls local playback. Since 1.9.0 a file://
            // or content:// item gets one second of buffer on the theory that the whole file is
            // already on disk; this app's videos are still being written while they play, and one
            // second behind the write frontier stalls on any hiccup in the download. tdfile:// is
            // not on Media3's local list today, but the values are pinned so a future scheme or a
            // future Media3 cannot quietly put the one-second rule back.
            .setBufferDurationsMsForLocalPlayback(
                BUFFER_MIN_MS,
                BUFFER_MAX_MS,
                BUFFER_PLAYBACK_MS,
                BUFFER_REBUFFER_MS,
            )
            .setTargetBufferBytes(bufferBytes)
            .setPrioritizeTimeOverSizeThresholds(false)
            // A few seconds of what has already played, kept behind the position. Without it a
            // short back-seek falls outside the buffer, which puts the read outside TDLib's
            // download window and restarts the download from there. Deliberately short, and from
            // the last keyframe, because this is memory on a 1 GB stick.
            .setBackBuffer(backBufferMs, /* retainBackBufferFromKeyframe = */ true)
            .build()

        val plan = trackPlan
        val subtitles = plan.subtitles
        val trackSelector = DefaultTrackSelector(this).apply {
            parameters = buildUponParameters()
                // Off stays off, so a series watched without captions does not get them back on an
                // episode that marks one default. Settings' "off unless chosen" is softer: the
                // renderer stays on with the default flag ignored, which still lets a forced track
                // (a sign, a line in another language) through and leaves every track there to be
                // chosen from the picker. "Only when the audio is foreign" starts the same way and
                // is decided once the soundtrack is known ([applySettingsSubtitles]).
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, subtitles is SubtitlePlan.Off && !subtitles.allowForced)
                .setIgnoredTextSelectionFlags(
                    if (subtitles is SubtitlePlan.Off || subtitles is SubtitlePlan.IfForeignAudio) {
                        C.SELECTION_FLAG_DEFAULT
                    } else {
                        0
                    },
                )
                // What this series was last watched with, so episode two opens in the language and
                // with the captions episode one ended in; else the language Settings ask for.
                .setPreferredTextLanguage((subtitles as? SubtitlePlan.On)?.language)
                .setSelectUndeterminedTextLanguage(false)
                .setPreferredAudioLanguage(plan.audioLanguage)
                .build()
        }

        // Constant-bitrate seeking rescues formats that ship without a seek index. Not "always":
        // on a file that has an index the estimate is worse than the index, and the seek lands
        // somewhere other than where it was asked to.
        //
        // MPEG-TS as Blu-ray rips and TV recordings write it: DTS in an .m2ts is declared with the
        // Blu-ray (HDMV) stream type, which Media3 leaves unread unless asked, so the film played
        // silent; a recording whose H.264 has no IDR frames, only recovery points, never showed a
        // picture; and a long GOP pushed the timestamps a seek searches for past Media3's window,
        // so seeking landed far from the asked place. Jellyfin and Just Player set the same.
        //
        // Wrapped last so a Dolby Vision track this device would not show as Dolby Vision reaches
        // the player as its HEVC base layer, which both decoders take ([DolbyVisionBaseLayer]).
        val extractors = DolbyVisionBaseLayer(
            this,
            DefaultExtractorsFactory()
                .setConstantBitrateSeekingEnabled(true)
                .setTsExtractorFlags(
                    androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory.FLAG_ENABLE_HDMV_DTS_AUDIO_STREAMS or
                        androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES,
                )
                .setTsExtractorTimestampSearchBytes(TS_TIMESTAMP_SEARCH_BYTES),
        )

        return ExoPlayer.Builder(this, renderers)
            .setLoadControl(loadControl)
            .setTrackSelector(trackSelector)
            // tdfile:// goes to TDLib; DefaultDataSource sends file:// (a subtitle file loaded from
            // the phone, copied into the cache) to the disk and passes every other scheme down.
            .setMediaSourceFactory(
                DefaultMediaSourceFactory(
                    DefaultDataSource.Factory(
                        this,
                        // A video joined from byte pieces reads each piece the way a whole file
                        // is read: TDLib's stream, or the disk for a downloaded piece.
                        JoinedDataSource.Factory(
                            TdDataSource.Factory(client),
                            DefaultDataSource.Factory(this, TdDataSource.Factory(client)),
                            ::leftPiece,
                        ),
                    ).setTransferListener(byteCounter),
                    extractors,
                )
                    // A text track saved in a Windows code page rather than UTF-8 reads in its
                    // language's page instead of as mojibake ([SubtitleEncoding]).
                    .setSubtitleParserFactory(SubtitleEncoding(androidx.media3.extractor.text.DefaultSubtitleParserFactory())),
            )
            .setSeekBackIncrementMs(Skip.BACK_MS)
            .setSeekForwardIncrementMs(Skip.FORWARD_MS)
            // Scrubbing, chapters, skips and the resume land on the nearest keyframe: Media3's
            // default is exact, which decodes (and on a stream, downloads) from the keyframe
            // before the target up to it, seconds of waiting on a long GOP. Only a frame step and
            // the A-B repeat seek exactly ([exactSeekTo]).
            .setSeekParameters(SeekParameters.CLOSEST_SYNC)
            // On a television the playback thread wakes when a renderer next has something to
            // do (the next frame due, the audio buffer running down) instead of every 10 ms. It
            // was the busiest thread in the app while a video played on the 1 GB stick, about 13%
            // of a core, with the UI idle. A renderer that cannot say keeps the 10 ms. Left as it
            // was on phones and tablets, where nothing measured asked for it.
            .experimentalSetDynamicSchedulingEnabled(FormFactor.isTv(this))
            .build()
            .apply {
                setAudioAttributes(movieAudioAttributes, /* handleAudioFocus = */ true)
                setHandleAudioBecomingNoisy(true)
                setPlaybackSpeed(playbackSpeed)
                skipSilenceEnabled = skipSilence
                videoScalingMode = C.VIDEO_SCALING_MODE_SCALE_TO_FIT
                // Media3 only ever asks the display for a seamless rate switch. On a television
                // whose viewer set "Match content frame rate" to always, the app asks itself,
                // with the stronger request, so Media3's own signalling is turned off there.
                // See [FrameRateMatch].
                if (FrameRateMatch.shouldTakeOver(this@PlayerActivity)) {
                    setVideoChangeFrameRateStrategy(C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_OFF)
                }
                // The one signal that separates "the network stalled" from "the audio pipeline
                // ran dry": a stutter with these lines in the log is an output buffer problem,
                // one without them is the download.
                addAnalyticsListener(object : AnalyticsListener {
                    override fun onAudioUnderrun(
                        eventTime: AnalyticsListener.EventTime,
                        bufferSize: Int,
                        bufferSizeMs: Long,
                        elapsedSinceLastFeedMs: Long,
                    ) {
                        Log.w(
                            "TMPlayer",
                            "Audio underrun: buffer ${bufferSizeMs}ms, " +
                                "last fed ${elapsedSinceLastFeedMs}ms ago",
                        )
                        watchUnderruns++
                    }

                    override fun onVideoDecoderInitialized(
                        eventTime: AnalyticsListener.EventTime,
                        decoderName: String,
                        initializedTimestampMs: Long,
                        initializationDurationMs: Long,
                    ) {
                        watchDecoder = decoderName
                    }

                    override fun onVideoFrameProcessingOffset(
                        eventTime: AnalyticsListener.EventTime,
                        totalProcessingOffsetUs: Long,
                        frameCount: Int,
                    ) {
                        watch.frameOffsets(totalProcessingOffsetUs, frameCount)
                    }

                    // Media3 reports dropped frames fifty at a time: the decoder falling behind
                    // the picture, which is this device struggling, not the download. The shell
                    // offers the recommended settings once (CP68, DeviceFitHint).
                    override fun onDroppedVideoFrames(
                        eventTime: AnalyticsListener.EventTime,
                        droppedFrames: Int,
                        elapsedMs: Long,
                    ) {
                        watchDropped += droppedFrames
                        if (droppedFrames >= STUTTER_FRAMES) com.tmplayer.data.DeviceFitHint.raise()
                    }
                })
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
        // A session already made (a stream opened again, the next episode played in place) takes
        // the new player rather than going: the notification, the lock screen's controls and,
        // with the player out of sight, [PlaybackService]'s foreground all carry on through it.
        mediaSession?.let { session ->
            val swapped = runCatching { session.player = EpisodeStepping(exo) }.isSuccess
            if (swapped) return
            session.release()
        }
        mediaSession = runCatching {
            MediaSession.Builder(this, EpisodeStepping(exo))
                // Distinct per activity instance: stepping to the next episode builds a second
                // activity before the first has been destroyed, and two sessions sharing an id
                // is the one thing the builder refuses outright.
                .setId("tmplayer-$chatId-$messageId-${SystemClock.elapsedRealtime()}")
                // Before Android 13 the notification draws its own buttons: seek back and
                // forward by the player's amounts either side of play, the episodes in the
                // expanded view. From 13 the system's controls have a seek bar instead.
                .setMediaButtonPreferences(BackgroundPlayback.notificationButtons(Skip.BACK_MS, Skip.FORWARD_MS))
                .build()
        }.getOrNull()
    }

    /**
     * The name the session gives the video, which is what the media notification, the lock
     * screen and the system's media controls show: the show or the video's name over the episode and the
     * chat, and its thumbnail. With the app lock asking on open, only the app's name.
     */
    private fun sessionMetadata(): MediaMetadata {
        // Every field is set, blank where there is nothing to say: Media3 fills an unset one
        // from the file's own tags, which with the lock on is exactly what must not show.
        if (sessionPrivate) {
            return MediaMetadata.Builder()
                .setTitle(applicationInfo.loadLabel(packageManager))
                .setDisplayTitle("")
                .setArtist("")
                .setAlbumTitle("")
                .setSubtitle("")
                .setDescription("")
                .build()
        }
        val here = currentItem()
        val parsed = SeriesShelf.episodeOf(here)
        val episode = _episodes.value.current ?: EpisodeNeighbours.tagOf(here)
        val name = (episode?.show ?: parsed.title).ifBlank { parsed.title }.ifBlank { mediaTitle }
        val detail = listOfNotNull(
            episode?.label,
            chatTitle.takeIf { it.isNotBlank() },
        ).joinToString("  ·  ")
        return MediaMetadata.Builder()
            .setTitle(name)
            .setDisplayTitle(name)
            .setArtist(detail)
            .setAlbumTitle("")
            .setSubtitle(detail)
            .setDescription("")
            .setMediaType(MediaMetadata.MEDIA_TYPE_VIDEO)
            .apply { sessionArtwork?.let { setArtworkData(it, MediaMetadata.PICTURE_TYPE_FRONT_COVER) } }
            .build()
    }

    /** The thumbnail as JPEG bytes, for [sessionMetadata]; null until one has been read. */
    private var sessionArtwork: ByteArray? = null

    /**
     * Puts a changed [sessionMetadata] into the playing item: the app lock turned on or off,
     * the thumbnail read, the episode named. The same uri and subtitle files, so ExoPlayer takes
     * it as the same item and carries on without opening the file again.
     */
    private fun refreshSessionMetadata() {
        val metadata = sessionMetadata()
        if (metadata == subtitleFiles.metadata) return
        subtitleFiles.metadata = metadata
        val exo = player ?: return
        val playing = exo.currentMediaItem ?: return
        runCatching { exo.replaceMediaItem(exo.currentMediaItemIndex, playing.buildUpon().setMediaMetadata(metadata).build()) }
    }

    /**
     * The player as the media session sees it: one video, plus the chat's next and previous
     * episodes. Next and previous are the episodes and nothing else, offered only where the chat
     * has one: Media3's previous for a lone item goes back to its start, and a stray press on a
     * headset, the lock screen or picture in picture took a two hour video back to 0:00.
     */
    private inner class EpisodeStepping(exo: ExoPlayer) : androidx.media3.common.ForwardingPlayer(exo) {
        override fun getAvailableCommands(): Player.Commands {
            val found = _episodes.value
            val more = Player.Commands.Builder().addAll(super.getAvailableCommands())
                .removeAll(
                    Player.COMMAND_SEEK_TO_NEXT, Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
                    Player.COMMAND_SEEK_TO_PREVIOUS, Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
                )
            if (found.next != null) more.addAll(Player.COMMAND_SEEK_TO_NEXT, Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
            if (found.previous != null) more.addAll(Player.COMMAND_SEEK_TO_PREVIOUS, Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
            return more.build()
        }

        override fun isCommandAvailable(command: Int): Boolean = availableCommands.contains(command)

        override fun seekToNext() {
            stepEpisode(forward = true)
        }

        override fun seekToNextMediaItem() {
            stepEpisode(forward = true)
        }

        override fun seekToPrevious() {
            stepEpisode(forward = false)
        }

        override fun seekToPreviousMediaItem() {
            stepEpisode(forward = false)
        }

        /**
         * The paused media notification swiped away, which Media3 sends as a stop: with the
         * player out of sight that is the end of it, so the activity goes, and its onDestroy
         * releases the player and cancels the stream as on any other way out.
         */
        override fun stop() {
            super.stop()
            if (inBackgroundAudio) endBackgroundAudio(finish = true)
        }
    }

    /**
     * Whether leaving now keeps the sound going ([PlaybackService]): the setting is on, this is a
     * phone, something is playing (or about to), and no sheet is up. Picture in picture comes
     * first: while it is up the activity is never stopped, and closing its window means stop.
     */
    private fun keepsPlayingUnseen(): Boolean =
        // Sound alone plays on with the screen off whatever the setting: that is what it is for.
        (backgroundAudioOn || isAudio) && !FormFactor.isTv(this) && !isFinishing && !inPictureInPicture &&
            mediaSession != null && player?.playWhenReady == true &&
            // The sheet is up for a failure, a question or "Still watching?", none of which
            // plays on; the countdown to the next episode is the one sheet that does.
            (statusOverlay.visibility != View.VISIBLE || countingDownToNext) &&
            SystemClock.elapsedRealtime() - pictureInPictureEndedAt > PIP_CLOSE_GRACE_MS

    /** The failure sheet is up for something worth trying again ([showError]). */
    private var failedRetryable = false

    /**
     * The failure sheet is up ([showError]) and stays up until the viewer answers it or a new
     * player starts: the player's own later states (a buffering spell, ready) no longer take it
     * down. A 4K picture this TV cannot decode used to have its sheet hidden by the buffering that
     * followed it, leaving "Loading 12%" over a paused picture for good (2026-10-23).
     */
    private var failurePinned = false

    /** True while the sheet counts down to the next episode ([onVideoEnded]). */
    private var countingDownToNext = false

    private fun startBackgroundAudio() {
        val session = mediaSession ?: return
        val started = PlaybackService.start(
            context = this,
            session = session,
            playWhenReady = player?.playWhenReady == true,
            onStop = { endBackgroundAudio(finish = true) },
            onCannotContinue = { stopUnseen() },
        )
        if (!started) {
            // Android would not start the service: the sound cannot go on unseen, and stopping
            // without a word would look like a crash.
            player?.pause()
            resumeWhenSeen = true
            PlaybackService.notifyStopped(this)
            return
        }
        inBackgroundAudio = true
        resumeWhenSeen = false
        player?.let(::hideVideoUnseen)
    }

    /** Takes the session back from the service; [finish] when playback is over with the player unseen. */
    private fun endBackgroundAudio(finish: Boolean = false) {
        if (!inBackgroundAudio) return
        inBackgroundAudio = false
        PlaybackService.stop(this)
        if (finish && !isFinishing) {
            player?.pause()
            finish()
        }
    }

    /**
     * The sound has stopped out of sight for a reason that is not the viewer's: a failure no
     * retry got past, a question the next episode has to ask (mobile data, room on the disk), or
     * Android refusing the foreground. Says so with "Playback stopped, tap to resume" rather
     * than going quiet, hands the session back, and leaves [resumeWhenSeen] for [onStart].
     */
    private fun stopUnseen() {
        if (!inBackgroundAudio) return
        player?.pause()
        resumeWhenSeen = true
        endBackgroundAudio()
        PlaybackService.notifyStopped(this)
    }

    /**
     * Turns the video track off for a player nobody can see, which stops the decoder working for
     * frames that land nowhere ([BackgroundPlayback.withoutVideo]). Not in picture in picture,
     * where the picture is the point.
     */
    private fun hideVideoUnseen(exo: ExoPlayer) {
        if (inPictureInPicture) return
        val hidden = BackgroundPlayback.withoutVideo(exo.trackSelectionParameters) ?: return
        exo.trackSelectionParameters = hidden
        videoHiddenUnseen = true
    }

    /**
     * Puts back what [hideVideoUnseen] took, as the player comes back into view ([onStart]); not
     * before, so a player that stopped out of sight does not start decoding pictures for nobody.
     */
    private fun showVideoAgain() {
        if (!videoHiddenUnseen) return
        videoHiddenUnseen = false
        val exo = player ?: return
        videoBackAskedAt = SystemClock.elapsedRealtime()
        exo.trackSelectionParameters = BackgroundPlayback.withVideo(exo.trackSelectionParameters)
    }

    /**
     * When [showVideoAgain] asked for the picture, so the log can say how long it took to come
     * back (`adb logcat -s TMPlayer`, "picture back"): the figure a device check reads.
     */
    private var videoBackAskedAt = 0L

    /**
     * Waits until the player can be seen before something has to be asked of the viewer (mobile
     * data, room on the disk). Out of sight that is the end of the sound for now, said with
     * [stopUnseen]; the question is there when they come back.
     */
    private suspend fun awaitSeen() {
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) return
        stopUnseen()
        lifecycle.withStarted { }
    }

    /**
     * The next episode with the player out of sight, where [playEpisode]'s new activity is not
     * allowed to start: played in this activity instead, through the same opening as any video
     * ([openItem]) and into the same media session, so the notification carries on with the new
     * title and the player shows this episode when the viewer comes back. What the last video
     * had (its place, its stream, its searches) is put away first, the way leaving it would.
     */
    private fun continueUnseen(next: MediaItem) {
        if (handingOver) return
        // The one ending: its place, the Watched list and the home screen row, as on the way out.
        saveResumePosition(leaving = true)
        // The service keeps the foreground while the new player opens.
        PlaybackService.setPlayWhenReady(true)
        holdFile(0)
        releasePlayerAndSurface()
        // Its stream, now that the player and its reads are gone (see [onDestroy]).
        stopDownload()
        letGoOfStreamBounds()
        trimCache()
        val outside = intent.getBooleanExtra(EXTRA_OUTSIDE_APP_TASK, false)
        intent = intent(this, next, chatTitle).putExtra(EXTRA_OUTSIDE_APP_TASK, outside)
        readItem(intent)
        forgetTheLastVideo()
        openItem()
    }

    /** What belonged to the last video, cleared for the next one played in place. */
    private fun forgetTheLastVideo() {
        // The scrub card's pictures were of the last video.
        trickplay?.forget()
        scrubArt = null
        artworkShown = false
        itemJob.cancel()
        itemJob = Job(lifecycleScope.coroutineContext[Job])
        recoveryJob = null
        recoveryAttempts = 0
        reSourced = false
        downloadedFile = null
        downloadComplete = false
        downloadedFraction = 0f
        lastProgressRender = 0L
        waitingForWholeFilm = false
        speed.reset()
        trickplayPath = null
        trickplaySpans = emptyList()
        resumeMs = 0L
        resumeNotice = null
        resumeTracks = null
        chapters = emptyList()
        chaptersAsked = false
        controls?.chapters = emptyList()
        skipSegments = emptyList()
        skippedSegments.clear()
        offeredSkip = null
        hideSkipPill()
        creditsSkipped = false
        pictureChecked = false
        hideNextUp()
        nextUpShown = false
        nextUpDismissed = false
        firstFrameReady = false
        startOverOffered = false
        abLoop = null
        loopHandler.removeCallbacks(loopWatch)
        seekUndo.clear()
        undoChip?.hide()
        markedWatchedHere = false
        watchedHere = false
        onWatchedList = false
        videoWidth = 0
        videoHeight = 0
        copies = emptyList()
        controls?.setCopy(null)
        bookmarkList.value = emptyList()
        _episodes.value = EpisodeSteps()
        sessionArtwork = null
        loaderHost?.thumbnail = null
        loaderHost?.describe(mediaFileName, mediaCaption.ifBlank { null }, mediaTitle, durationSec)
        statusArt?.setImageDrawable(null)
        statusPoster?.setImageDrawable(null)
        renderControlsTitle()
    }

    /**
     * Two hours of a video is two hours with no button presses, so the screen is held awake while
     * something is actually on it. Held only while playing, so a video left paused overnight, or
     * an error sheet nobody came back to, does not keep the display lit.
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
            showCues(scaledForPicture(cueGroup.cues))
            if (!firstCuesLogged && cueGroup.cues.isNotEmpty()) {
                firstCuesLogged = true
                val pictures = cueGroup.cues.count { it.bitmap != null }
                Log.i(
                    "TMPlayer",
                    "Subtitles: first lines at ${player?.currentPosition ?: -1} ms, " +
                        "${cueGroup.cues.size - pictures} text, $pictures pictures; view " +
                        "${subtitleView.width}x${subtitleView.height} alpha ${subtitleView.alpha} " +
                        "shown ${subtitleView.isShown}",
                )
            }
        }

        /**
         * Files the soundtrack and subtitles now playing under this series.
         *
         * Written from what is actually selected rather than from what the picker was told, so it
         * covers every route to a track: the television's picker, Media3's own subtitle button on
         * a phone, and the file's defaults on a series being watched for the first time.
         */
        override fun onTracksChanged(tracks: Tracks) {
            if (!tracks.isEmpty) markStart("tracks known", STAGE_TRACKS)
            logSubtitleTracks(tracks)
            if (pictureUnplayable(tracks)) return
            if (checkSoftwareDecode(tracks)) return
            restoreResumeTracks(tracks)
            applySettingsSubtitles(tracks)
            rememberTracks(tracks)
            subtitleFiles.onTracksChanged(tracks)
        }

        override fun onRenderedFirstFrame() {
            logFirstFrame()
            hintStereo()
            if (videoBackAskedAt == 0L) return
            Log.i("TMPlayer", "Background audio: picture back in ${SystemClock.elapsedRealtime() - videoBackAskedAt} ms")
            videoBackAskedAt = 0L
        }

        override fun onVideoSizeChanged(size: VideoSize) {
            // The track turned off out of sight reports no size, which is not a new shape.
            if (videoHiddenUnseen && (size.width <= 0 || size.height <= 0)) return
            videoWidth = size.width
            videoHeight = size.height
            followVideoOrientation()
            if (FrameRateMatch.shouldTakeOver(this@PlayerActivity)) {
                FrameRateMatch.apply(videoSurface(), player?.videoFormat?.frameRate ?: 0f)
            }
        }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            // Heard here before Media3's notification hears it: a pause lets the service out of
            // the foreground at once, so the paused notification can be swiped away.
            if (inBackgroundAudio) PlaybackService.setPlayWhenReady(playWhenReady)
            onPauseChanged(playWhenReady, reason)
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            wakeEndWatch()
            if (isPlaying) startWatch()
            loopHandler.removeCallbacks(loopWatch)
            if (isPlaying && abLoop?.complete == true) loopHandler.post(loopWatch)
            // Started again from outside the card (a headset, the notification): the question
            // has been answered.
            if (isPlaying && stillWatchingUp) {
                stillWatchingUp = false
                resumeHeldDownloads()
                hideStatus()
            }
            // A long download before the first frame is still the viewer waiting on this screen,
            // so the loading sheet counts as something worth staying awake for.
            keepScreenOn(isPlaying || openingFilm)
            controls?.onPlayingChanged()
            updatePictureInPictureParams()
            // A pause is a natural place to stop for the night: written now, not at the next
            // heartbeat, which only beats while playing. A stall for bytes is not a pause.
            if (!isPlaying && player?.playWhenReady == false) saveResumePosition()
        }

        override fun onPlaybackStateChanged(state: Int) {
            when (state) {
                // Only the very first wait earns the full screen; later stalls get the chip.
                Player.STATE_BUFFERING -> {
                    if (!failurePinned) {
                        if (openingFilm) showStatus(L.commonLoading) else showRebuffering()
                        controls?.setBuffering(!openingFilm)
                    }
                }

                // Playing again is the only proof that a recovery worked, so the budget is
                // refilled here rather than when the retry is issued.
                Player.STATE_READY -> {
                    if (seekStartedAt > 0) {
                        Log.i(START_TAG, "seek to ready ${SystemClock.elapsedRealtime() - seekStartedAt} ms (file $fileId)")
                        seekStartedAt = 0L
                    }
                    recoveryAttempts = 0
                    markStart("ready to play", STAGE_READY)
                    // Sound alone has no first frame: ready is its picture.
                    openedGate.complete(Unit)
                    firstFrameReady = true
                    noteSeekIndex()
                    readChapters()
                    offerStartOver()
                    controls?.setBuffering(false)
                    if (!failurePinned) hideStatus()
                    showAudioCard()
                    // Once, as the picture first lands: the viewer sees the name of what they
                    // opened and where the controls live, and the row folds away on its own.
                    if (!controlsShownOnStart) {
                        controlsShownOnStart = true
                        controls?.show()
                    }
                    // A video whose pieces stop at a gap says so as it starts, long enough to read.
                    if (!piecesNoticeShown) {
                        piecesNoticeShown = true
                        piecesNotice?.let { feedback?.message(it, holdMs = PIECES_NOTICE_MS) }
                    }
                    // After the pieces' notice, which matters more, and only when that is not up.
                    softwareNote?.let { note ->
                        softwareNote = null
                        if (piecesNotice == null) {
                            softwareNoteUp = true
                            feedback?.message(note, holdMs = SOFTWARE_NOTE_MS)
                        }
                    }
                    passedOver?.let { heavy ->
                        passedOver = null
                        sayPassedOver(heavy)
                    }
                    startWatch()
                }
                // Already moved on from the credits; the file running out behind it changes nothing.
                Player.STATE_ENDED -> {
                    // Said to the service before Media3's notification hears of the end: the
                    // foreground lasts through the gap only when another episode follows.
                    if (inBackgroundAudio) PlaybackService.setPlayWhenReady(!creditsSkipped && endLeadsOn())
                    if (!creditsSkipped) onVideoEnded()
                }
                else -> Unit
            }
        }

        override fun onPositionDiscontinuity(
            oldPosition: Player.PositionInfo,
            newPosition: Player.PositionInfo,
            reason: Int,
        ) {
            // A paused playhead moved by a seek may have left the skip pill's stretch or the
            // next-up card's last half minute, or come into them.
            wakeEndWatch()
            if (reason != Player.DISCONTINUITY_REASON_SEEK) return
            if (!watch.done) watch.reset()
            seekStartedAt = SystemClock.elapsedRealtime()
            noteSeek(oldPosition.positionMs, newPosition.positionMs)
        }

        override fun onPlayerError(error: PlaybackException) {
            // Every way out of here ends in the status sheet (HX10): a recovery step that throws
            // falls back to the plain error rather than taking the app down with the video.
            try {
                handlePlayerError(error)
            } catch (e: Exception) {
                com.tmplayer.platform.JobFailures.handle("onPlayerError", e)
                runCatching { showError(friendlyError(error), retryable = true) }
            }
        }

        private fun handlePlayerError(error: PlaybackException) {
            // Worked out before anything else looks at the failure: the sheet shows the button
            // only when the other decoder can take this format.
            decoderPlan = DecoderSwitch.planFor(player, error)
            lastPlaybackError = error
            if (switchDecoderByItself(error)) return
            // A file TDLib cannot size is a stale id, not a slow network: retrying the same id
            // only spins for a minute, while asking for the message again fixes it in a second.
            if (staleFileId(error) && resourceAgain(error, sameId = { if (!recoverFrom(error)) showError(friendlyError(error), retryable = !isHopeless(error)) })) return
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
    /**
     * The file's tracks of one kind, flattened in the order the player lists them: the places a
     * [ResumeState] counts by. Each entry is the group and the track's index inside it.
     */
    private fun tracksOfType(current: Tracks, type: Int): List<Pair<Tracks.Group, Int>> =
        current.groups.filter { it.type == type }.flatMap { group -> (0 until group.length).map { group to it } }

    /**
     * One log line each time the subtitle tracks or the one chosen change, and one when the
     * chosen track's first lines arrive ([playerListener]'s onCues): what a "chosen, but nothing
     * shows" report needs from `adb logcat -s TMPlayer`. The kind of track above all, since a
     * picture track (PGS, VobSub, DVB) and a styled one (ASS) take different paths to the screen.
     * The chosen track is starred.
     */
    private fun logSubtitleTracks(tracks: Tracks) {
        val line = tracksOfType(tracks, C.TRACK_TYPE_TEXT).joinToString("; ") { (group, index) ->
            val format = group.getTrackFormat(index)
            val chosen = if (group.isTrackSelected(index)) "*" else ""
            "$chosen${format.id} ${format.sampleMimeType} ${format.codecs} ${format.language} " +
                "flags ${format.selectionFlags} supported ${group.isTrackSupported(index)}"
        }
        if (line == subtitleLogLine) return
        subtitleLogLine = line
        firstCuesLogged = false
        Log.i("TMPlayer", "Subtitles: ${line.ifEmpty { "none in this file" }}")
    }

    /** This video's tracks and speed as they stand, for the resume point. */
    private fun resumeState(exo: Player): ResumeState {
        val current = exo.currentTracks
        fun place(type: Int): Pair<Int, String?>? = tracksOfType(current, type)
            .withIndex()
            .firstOrNull { (_, entry) -> entry.first.isTrackSelected(entry.second) }
            ?.let { (at, entry) -> at to entry.first.getTrackFormat(entry.second).language }
        val audio = place(C.TRACK_TYPE_AUDIO)
        val text = place(C.TRACK_TYPE_TEXT)
        val anyText = tracksOfType(current, C.TRACK_TYPE_TEXT).isNotEmpty()
        return ResumeState(
            audioTrack = audio?.first,
            audioLanguage = audio?.second,
            subtitleTrack = text?.first ?: ResumeState.SUBTITLES_OFF.takeIf { anyText },
            subtitleLanguage = text?.second,
            speed = playbackSpeed,
        )
    }

    /**
     * Puts back the tracks the saved position was playing with, the first time the player lists
     * the file's tracks. A place that now holds a track in another language is left to the
     * series memory rather than guessed at.
     */
    private fun restoreResumeTracks(current: Tracks) {
        val wanted = resumeTracks ?: return
        val exo = player ?: return
        if (current.groups.isEmpty()) return
        resumeTracks = null
        val builder = exo.trackSelectionParameters.buildUpon()
        var changed = false
        wanted.audioTrack?.let { at ->
            val (group, index) = tracksOfType(current, C.TRACK_TYPE_AUDIO).getOrNull(at) ?: return@let
            if (group.isTrackSelected(index)) return@let
            if (!ResumeState.sameTrack(wanted.audioLanguage, group.getTrackFormat(index).language)) return@let
            builder.setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, index))
            changed = true
        }
        val text = tracksOfType(current, C.TRACK_TYPE_TEXT)
        when {
            wanted.subtitlesOff -> if (text.any { (group, index) -> group.isTrackSelected(index) }) {
                builder.clearOverridesOfType(C.TRACK_TYPE_TEXT).setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                changed = true
            }
            else -> wanted.subtitleTrack?.let { text.getOrNull(it) }?.let { (group, index) ->
                if (group.isTrackSelected(index)) return@let
                if (!ResumeState.sameTrack(wanted.subtitleLanguage, group.getTrackFormat(index).language)) return@let
                builder.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                    .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, index))
                changed = true
            }
        }
        if (changed) exo.trackSelectionParameters = builder.build()
    }

    /**
     * The subtitles Settings ask for, once the file's tracks and the soundtrack playing are known:
     * "always" in a language the file lacks falls back to its default or first track, which
     * Media3's own pick would leave off, and "only when the audio is foreign" cannot be decided
     * any earlier. Once per video; a choice made in the picker afterwards is the viewer's.
     */
    private fun applySettingsSubtitles(current: Tracks) {
        if (!settingsSubtitlesPending) return
        val exo = player ?: return
        if (current.groups.isEmpty()) return
        settingsSubtitlesPending = false
        val audio = tracksOfType(current, C.TRACK_TYPE_AUDIO)
            .firstOrNull { (group, index) -> group.isTrackSelected(index) }
            ?.let { (group, index) -> group.getTrackFormat(index).language }
        val text = tracksOfType(current, C.TRACK_TYPE_TEXT)
        val described = text.map { (group, index) ->
            val flags = group.getTrackFormat(index).selectionFlags
            SubtitleTrackInfo(
                language = group.getTrackFormat(index).language,
                isDefault = flags and C.SELECTION_FLAG_DEFAULT != 0,
                forced = flags and C.SELECTION_FLAG_FORCED != 0,
            )
        }
        val builder = exo.trackSelectionParameters.buildUpon()
        when (val pick = TrackRules.pickSubtitle(trackPlan.subtitles, audio, described)) {
            SubtitlePick.Leave -> return
            SubtitlePick.None -> {
                if (text.none { (group, index) -> group.isTrackSelected(index) }) return
                builder.clearOverridesOfType(C.TRACK_TYPE_TEXT).setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
            }
            is SubtitlePick.Track -> {
                val (group, index) = text[pick.index]
                if (group.isTrackSelected(index)) return
                builder.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                    .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, index))
            }
        }
        exo.trackSelectionParameters = builder.build()
    }

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
     * unplayable, but the player has no way to know it. Returns false when the error is one no
     * amount of retrying will fix, and the error sheet is the honest answer.
     */
    private fun recoverFrom(error: PlaybackException): Boolean {
        val exo = player ?: return false
        if (!isRecoverable(error)) return false
        if (recoveryAttempts >= MAX_RECOVERY_ATTEMPTS) return false

        val resumeAt = exo.currentPosition.coerceAtLeast(0)
        recoveryJob?.cancel()
        recoveryJob = itemScope.launch {
            showRebuffering()
            // Being offline is a wait, not a failure, so it never spends the retry budget: on a
            // phone leaving the house that budget would be gone long before the signal came back.
            // Read afresh as well as followed: [observeConnectivity] follows only while the
            // player is on screen, and out of sight the sound plays on.
            val offlineNow = NetworkMonitor.status.value == NetworkStatus.Offline && !Td.connected.value
            if (networkOffline || offlineNow) {
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
     * and the episode row all carry ids written down earlier, so a video opened after a restart, or
     * after TDLib rebuilt its database, can be pointed at a file the current session knows nothing
     * about: every read fails at once over a video that is perfectly fine. An expired file
     * reference is the same thing, Telegram asking to be given the message again.
     *
     * So before the sheet goes up, the message is fetched again and the id it carries now is used.
     * Once per activity, and only when there is a message to fetch: a second attempt would be the
     * same fetch with the same answer.
     *
     * Returns true when it has taken over, in which case the retry, or the sheet, comes later.
     * [sameId] is what follows when Telegram gives back the id already in hand: the sheet, or, for
     * a file it could not size, the usual retries.
     */
    private fun resourceAgain(
        error: PlaybackException,
        sameId: () -> Unit = { showError(friendlyError(error), retryable = !isHopeless(error)) },
    ): Boolean {
        // A file in the Downloads folder has no Telegram id to go stale.
        if (downloadedFile != null) return false
        if (reSourced || chatId == 0L || messageId == 0L) return false
        reSourced = true
        itemScope.launch {
            showStatus(L.playerAskingAgain)
            val fresh = runCatching { Td.refreshMedia(chatId, messageId) }.getOrNull()
            if (fresh == null || fresh.fileId == fileId) {
                // Nothing new to try: the id in hand is the id Telegram gives, so the failure is
                // about the video rather than about the name this app was calling it by.
                sameId()
                return@launch
            }
            fileId = fresh.fileId
            holdFile(fileId)
            fileSizeBytes = fresh.sizeBytes
            // Low storage mode and "Save mobile data" were granted to the old id, which nothing
            // reads any more: decided again for the new one, or its stream would ask TDLib for the
            // whole video.
            runCatching { boundTheStream(Td.localFileAvailability(fileId)) }
            // The old id is baked into the media source, so the player goes rather than re-prepares.
            releasePlayerAndSurface()
            retryPlayback()
        }
        return true
    }

    /**
     * Takes down the player and its surface, for the callers that cannot re-prepare.
     *
     * Both [reloadFromScratch] and [resourceAgain] change the bytes underneath a media source that
     * has already been built over them, and preparing over that reads the file the press was meant
     * to get away from; [continueUnseen] moves on to another file. The order matters: the view
     * lets go of the player before the player is released, so no view is ever holding a released
     * one. The session alone does, for the moment until [startPlayback] hands it the new player.
     */
    private fun releasePlayerAndSurface() {
        // The media session stays and takes the next player ([attachMediaSession]), so a rebuild
        // out of sight keeps [PlaybackService] and its notification; meanwhile it holds a player
        // that is idle and still meaning to play, which keeps the foreground through the gap.
        touchSurface?.player = null
        touchSurface?.let { findViewById<FrameLayout>(R.id.playback_container).removeView(it) }
        touchSurface = null
        player?.removeListener(playerListener)
        player?.release()
        player = null
        // The listener is gone before the release, so the player's own "no cues" never arrives:
        // the line on screen would stay frozen over the next video's opening.
        showCues(emptyList())
    }

    /**
     * Whether this failure is one no amount of trying can get past.
     *
     * Narrower than [isRecoverable] on purpose, and asked of a different thing. That one decides
     * whether the app should quietly try again by itself, where being wrong costs a wasted attempt.
     * This one decides whether to put a Reload button in front of the viewer, where being wrong
     * costs them the only thing on screen that could have worked: an unclassified error is very
     * often a bad cache or a stale file id, which is what Reload is for. Only a codec or a
     * container this device genuinely cannot handle is hopeless, and there the sheet offers the
     * app that can handle it instead.
     */
    private fun isHopeless(error: PlaybackException): Boolean = when (error.errorCode) {
        PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
        PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
        PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
        PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED,
        -> true

        else -> tooLargeForDecoder(error)
    }

    /**
     * A 4K video the device's decoder gave up on part way. A 1080p television's decoder (the Mi
     * TV's Amlogic among them) says it cannot do 3840x2160, is used anyway because it is the only
     * one that knows the codec, and fails at its first frame with a bare decoding error, which
     * put "Something went wrong" on the screen. No retry helps, and the sentence should say why.
     */
    private fun tooLargeForDecoder(error: PlaybackException): Boolean =
        error.errorCode == PlaybackException.ERROR_CODE_DECODING_FAILED && failedLines(error) >= UHD_HEIGHT

    /**
     * The video's size as people name it (1080, 2160): what played, or what the failing renderer
     * was handed when nothing did. See [nominalLines].
     */
    private fun failedLines(error: PlaybackException): Int {
        val format = (error as? androidx.media3.exoplayer.ExoPlaybackException)?.rendererFormat
        return maxOf(nominalLines(videoWidth, videoHeight), nominalLines(format?.width ?: 0, format?.height ?: 0))
    }

    /**
     * Whether retrying is worth anything.
     *
     * A malformed container counts, which reads oddly until you remember these bytes arrive over
     * a moving download window: a container that will not parse is far more often a read that
     * caught the file mid-move than a genuinely broken remux, and re-preparing settles it. A
     * codec this TV does not have is the opposite, and no number of attempts will conjure one.
     */
    /**
     * Whether TDLib said it has no size for the file, or no file at all ([TdByteWindow.lookUp]):
     * what an id from before a restart looks like until the message is fetched again.
     */
    private fun staleFileId(error: PlaybackException): Boolean =
        generateSequence(error.cause) { it.cause }.take(MAX_CAUSE_HOPS).any { cause ->
            val message = cause.message.orEmpty()
            message.startsWith("Unknown size for file") || message.startsWith("Telegram lost track of file")
        }

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
     * exception rather than replacing it, so the error code alone would throw all of it away.
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
            L.playerErrorConnection

        PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
        PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
        PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
        PlaybackException.ERROR_CODE_DECODING_FAILED.takeIf { tooLargeForDecoder(error) },
        ->
            // A remux with a single video track bypasses the selector's viewport constraints, so a
            // 4K stream reaches a decoder built for 1080p. Naming the resolution matters: "a
            // different copy may work" sends somebody looking for a bad file, not a smaller one.
            // Dolby Vision profile 5 says what it is, since no copy of the same file plays here.
            when {
                (error as? androidx.media3.exoplayer.ExoPlaybackException)?.rendererFormat
                    ?.let(FormatRoute::dolbyVisionOnly) == true -> L.playerErrorDolbyVision
                failedLines(error) >= UHD_HEIGHT -> L.playerErrorUhd(failedLines(error).toString())
                else -> L.playerErrorFormat
            }

        PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
        PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED,
        ->
            L.playerErrorContainer

        // ExoPlayer's own message names a codec or an internal class; it means nothing on a sofa
        // and reads as a crash. The exception still reaches logcat through ExoPlayer itself.
        else -> L.playerErrorOther
    }

    /**
     * Asks the chat what comes before and after this episode, the way the Series view orders it:
     * see [EpisodeNeighbours].
     *
     * Off the critical path on purpose: it costs a search, and the video starts without it. The
     * search is narrowed to the series name, which Telegram matches file names and captions
     * against, and falls back to the plain listing when that finds no neighbour, for a chat that
     * names its files some other way. A video whose name and caption name no episode, not even a
     * bare "E5", is a film and asks nothing.
     */
    private fun findEpisodes() {
        val here = currentItem()
        // Started from a chat's audio: the list is the order, the next file plays when one ends.
        com.tmplayer.data.PlayQueue.stepsFor(here)?.let { steps ->
            _episodes.value = steps
            return
        }
        val show = EpisodeNeighbours.showOf(here) ?: return
        _episodes.value = EpisodeSteps(current = EpisodeNeighbours.tagOf(here))

        itemScope.launch {
            if (chatId == 0L) return@launch
            afterOpening()
            val session = Td.awaitAuthorizedSession()
            val repository = ChatRepository(session.client)
            // In a group with topics the next episode is looked for in this video's own topic, or
            // the plain listing below would step into whichever section posted last.
            val topic = here.topicId.takeIf { it != 0 }
                ?: runCatching { repository.topicOf(chatId, messageId) }.getOrDefault(0)
            // A page at a time until both neighbours are found: an episode posted a page away
            // from this one is still the next one.
            val steps = EpisodeNeighbours.lookUp(here, show) { query, cursors ->
                runCatching { repository.mediaPage(chatId, cursors, query = query, topicId = topic) }.getOrNull()
            }
            _episodes.value = steps
            refreshSessionMetadata()
            // The providers' episode names, where lookups are on: a label first, then a better one.
            val named = withContext(Dispatchers.IO) { EpisodeNames.named(steps, here, OnlineMetadata.current) }
            if (named != steps) {
                _episodes.value = named
                refreshSessionMetadata()
            }
        }
    }

    /**
     * Whether this video fits, and gives up the cache if that is what it takes.
     *
     * Asked here rather than on the screen the player was started from, because [playEpisode]
     * starts this activity directly and so bypasses the grid entirely. This is the one place every
     * playback passes, and [claimTheWatchCache] runs only once the answer is yes.
     *
     * @return false when the video will not fit, in which case the error screen is already up.
     */
    private suspend fun makeRoomForThisVideo(availability: LocalFileAvailability): Boolean {
        val item = mediaItemForCache()
        val partial = if (availability == LocalFileAvailability.Partial) {
            runCatching { Td.localDownloadedBytes(fileId) }.getOrDefault(0L)
        } else {
            0L
        }
        val decision = runCatching {
            RoomOnDisk.decide(
                context = applicationContext,
                item = item,
                alreadyCached = availability == LocalFileAvailability.Complete,
                partialBytes = partial,
            )
        }.getOrNull() ?: return true // Could not measure. Refusing on a guess is worse than trying.

        return when (val plan = decision.plan) {
            is CacheShelf.Plan.NotEnoughSpace -> {
                // Asked every time a video only fits in Low storage mode, never just mentioned:
                // "Switch and play" turns the mode on and plans again, "Not now" is today's refusal.
                if (plan.fitsInLowStorage && askToSwitchToLowStorage()) {
                    runCatching { settings.setLowStorageMode(true) }
                    return makeRoomForThisVideo(availability)
                }
                // A stream with the mode off names it: the one answer that deletes nothing.
                val lowStorageOff = !runCatching { settings.lowStorageModeNow() }.getOrDefault(true) &&
                    !runCatching { settings.isKeptDownload(chatId, messageId) }.getOrDefault(true)
                val short = Translator.messages.formatter.bytes(plan.shortfallBytes)
                showError(
                    if (lowStorageOff) {
                        L.playerNotEnoughRoomLowStorage(size = short, mode = L.settingsLowStorage)
                    } else {
                        L.playerNotEnoughRoom(size = short)
                    },
                    retryable = false,
                    freeUp = true,
                )
                false
            }

            // What goes is the video the last press of Play left behind, which nobody chose to
            // keep. Nothing the viewer downloaded is ever a candidate.
            is CacheShelf.Plan.Evict -> {
                decision.evict(applicationContext)
                true
            }

            CacheShelf.Plan.Proceed -> true
        }
    }

    /** Shows the Low storage offer over the loading screen and waits for the answer. */
    private suspend fun askToSwitchToLowStorage(): Boolean {
        val host = sheets ?: return false
        // A sheet cannot go up over a player nobody can see; it waits for the viewer.
        awaitSeen()
        val answer = CompletableDeferred<Boolean>()
        host.showLowStorageOffer { answer.complete(it) }
        return answer.await()
    }

    /**
     * Lets this video stream through a rolling window ([LowStorageStreams]) when Low storage mode
     * is on. Only a video being watched: one the viewer downloaded, or is downloading, keeps every
     * byte, and one already whole on the disk has nothing to fetch.
     *
     * @return whether the mode applies to this video.
     */
    private suspend fun startLowStorageIfOn(availability: LocalFileAvailability): Boolean {
        // Whatever an earlier player of this file decided does not carry over.
        LowStorageStreams.revoke(fileId)
        if (availability == LocalFileAvailability.Complete || !hasMessage()) return false
        if (!runCatching { settings.lowStorageModeNow() }.getOrDefault(false)) return false
        if (runCatching { settings.isKeptDownload(chatId, messageId) }.getOrDefault(true)) return false
        if (OfflineDownloads.isDownloading(fileId)) return false
        val free = runCatching { DiskSpace.read(applicationContext).freeBytes }.getOrDefault(0L)
        val window = LowStorageWindow.windowBytes(free, CacheShelf.HEADROOM_BYTES)
        lowStorageHold = LowStorageStreams.allow(fileId, window)
        lowStorageFileId = fileId
        return true
    }

    /** The file [LowStorageStreams] was told about, taken back when the player goes. */
    private var lowStorageFileId = 0

    /** What [LowStorageStreams.allow] gave this player, so its exit takes back its own decision only. */
    private var lowStorageHold: Any? = null

    /**
     * Decides Low storage mode, or else "Save mobile data", for the file the player is about to
     * stream, letting go first of whatever this player was granted for another id.
     *
     * @return whether Low storage mode applies.
     */
    private suspend fun boundTheStream(availability: LocalFileAvailability): Boolean {
        letGoOfStreamBounds()
        val streamsLow = startLowStorageIfOn(availability)
        if (!streamsLow) startSavingDataIfOn(availability)
        return streamsLow
    }

    /**
     * Takes back what this player was granted by [LowStorageStreams] and [SaveDataStreams], and
     * only that: another player of the same file, opened before this one went, keeps its own.
     *
     * @return whether there was anything to take back.
     */
    private fun letGoOfStreamBounds(): Boolean {
        val had = letGoOfPieceBounds() or (lowStorageFileId != 0 || saveDataFileId != 0)
        lowStorageHold?.let { LowStorageStreams.revoke(lowStorageFileId, it) }
        saveDataHold?.let { SaveDataStreams.revoke(saveDataFileId, it) }
        lowStorageFileId = 0
        saveDataFileId = 0
        lowStorageHold = null
        saveDataHold = null
        return had
    }

    /**
     * Lets this video stream with "Save mobile data" ([SaveDataStreams]) when the setting is on:
     * on a metered connection it is then fetched only a bounded stretch ahead of the playhead,
     * extended as playback nears its end. Decided once, like Low storage mode, which wins where
     * both are on; whether the connection is metered is asked again at every request, so moving
     * between Wi-Fi and mobile data mid-video is followed. Only a video being watched: one the
     * viewer downloaded, or is downloading, is fetched to the end as asked.
     */
    private suspend fun startSavingDataIfOn(availability: LocalFileAvailability) {
        // Whatever an earlier player of this file decided does not carry over.
        SaveDataStreams.revoke(fileId)
        if (availability == LocalFileAvailability.Complete || !hasMessage()) return
        val stored = runCatching { settings.saveMobileDataNow() }.getOrDefault(false)
        if (!MeteredPolicy.saveMobileDataInForce(stored, FormFactor.isTv(this))) return
        if (runCatching { settings.isKeptDownload(chatId, messageId) }.getOrDefault(true)) return
        if (OfflineDownloads.isDownloading(fileId)) return
        saveDataHold = SaveDataStreams.allow(fileId) { NetworkMonitor.metered.value }
        saveDataFileId = fileId
    }

    /** The file [SaveDataStreams] was told about, taken back when the player goes. */
    private var saveDataFileId = 0

    /** What [SaveDataStreams.allow] gave this player, as [lowStorageHold]. */
    private var saveDataHold: Any? = null

    // A video cut into byte pieces ([SplitPieces]): the pieces, in order, played joined.

    /** The pieces of the video playing, in order, when it was cut into several files; else empty. */
    private var pieces: List<MediaItem> = emptyList()

    /** The joined stream ([joinedUri]) once [prepareJoined] has built it, for [startPlayback]. */
    private var joinedSource: android.net.Uri? = null

    /** Whether every piece plays from the disk, a download or a whole cached file. */
    private var joinedAllLocal = false

    /** "Part 4 of 7 is not in this chat, so the video plays up to it.", or null. */
    private var piecesNotice: String? = null

    /** Whether a missing piece cuts the video short, so its end is not the video's end. */
    private var piecesIncomplete = false

    /** Whether [piecesNotice] has been put up for this video yet. */
    private var piecesNoticeShown = false

    /** The pieces' file ids this player has told [WatchCache] it is playing. */
    private var heldPieceIds: List<Int> = emptyList()

    /** What [LowStorageStreams] and [SaveDataStreams] gave this player for each piece, as [lowStorageHold]. */
    private val pieceBounds = mutableListOf<Triple<Int, Any, Boolean>>()

    /** The pieces an intent carried, as the chat listed them. */
    private fun piecesFrom(from: Intent): List<MediaItem> {
        val messages = from.getLongArrayExtra(EXTRA_PIECE_MESSAGES) ?: return emptyList()
        val files = from.getIntArrayExtra(EXTRA_PIECE_FILES) ?: return emptyList()
        val sizes = from.getLongArrayExtra(EXTRA_PIECE_SIZES) ?: return emptyList()
        val names = from.getStringArrayExtra(EXTRA_PIECE_NAMES) ?: return emptyList()
        if (setOf(messages.size, files.size, sizes.size, names.size).size != 1) return emptyList()
        return messages.indices.map { i ->
            MediaItem(
                chatId = chatId,
                messageId = messages[i],
                fileId = files[i],
                title = names[i],
                sizeBytes = sizes[i],
                durationSec = 0,
                mimeType = "",
                thumbnailFileId = 0,
                miniThumbnail = null,
                date = 0,
                fileName = names[i],
            )
        }
    }

    /** Whether the video may be one cut into byte pieces: the grid said so, or its name does. */
    private fun mayBeJoined(): Boolean =
        hasMessage() && (pieces.isNotEmpty() || com.tmplayer.data.SplitPieces.pieceOf(mediaFileName)?.certain == true)

    /**
     * The pieces of the video, from the intent or else from the chat ([SplitPieces.lookUp]), for a
     * player opened on one piece (Continue watching, a link). False when there is only the one
     * file after all, which then plays as any other.
     */
    private suspend fun findPieces(client: TdlClient): Boolean {
        if (pieces.isEmpty()) {
            val repository = ChatRepository(client)
            val found = runCatching {
                com.tmplayer.data.SplitPieces.lookUp(currentItem()) { query, cursors ->
                    runCatching { repository.mediaPage(chatId, cursors, query = query, topicId = mediaTopicId) }.getOrNull()
                }
            }.getOrNull().orEmpty()
            // With no connection to search the chat with, the pieces kept in Downloads.
            pieces = found.takeIf { it.size > 1 } ?: runCatching {
                com.tmplayer.data.SplitPieces.among(currentItem(), settings.downloadsNow().map { it.toMediaItem() })
            }.getOrNull()?.takeIf { it.size > 1 } ?: found
        }
        return pieces.isNotEmpty()
    }

    /**
     * Builds the joined stream: each piece from Downloads where it is there, else streamed from
     * TDLib under its own fresh id, its exact length asked of TDLib. Checks the connection and the
     * room for the whole video, holds every piece against the cache's sweep, claims them together
     * as the cached video, and bounds each streamed piece for Low storage mode or "Save mobile
     * data". False when nothing can play, with the reason on screen.
     */
    private suspend fun prepareJoined(client: TdlClient): Boolean {
        val all = pieces
        val notice = com.tmplayer.data.SplitPieces.playerNotice(all)
        val playable = com.tmplayer.data.SplitPieces.playablePieces(all)
        // A piece missing is remembered, so "Missing parts of a video" can say when it is posted
        // (CP76); a video found whole is forgotten.
        val store = settings
        val chat = chatTitle
        com.tmplayer.App.backgroundScope.launch { runCatching { com.tmplayer.data.PieceAlerts.notePlayed(store, all, chat) } }
        if (playable.isEmpty()) {
            showError(notice ?: L.playerNothingToPlay, retryable = false)
            return false
        }
        piecesNotice = notice
        piecesIncomplete = notice != null
        val sources = ArrayList<Pair<android.net.Uri, Long>>()
        val streamed = ArrayList<MediaItem>()
        var local = true
        for (piece in playable) {
            val file = LocalDownloads.fileFor(settings, piece.chatId, piece.messageId)
            if (file != null) {
                sources += android.net.Uri.fromFile(file) to file.length()
                continue
            }
            val id = runCatching { Td.currentFileId(piece.chatId, piece.messageId, piece.fileId) }.getOrDefault(piece.fileId)
            val td = client.getFile(id).valueOrNull
            val size = td?.let { if (it.size > 0) it.size else it.expectedSize }?.takeIf { it > 0 } ?: piece.sizeBytes
            if (size <= 0) {
                showError(L.playerOpenFailed, retryable = true)
                return false
            }
            if (Td.localFileAvailability(id) != LocalFileAvailability.Complete) local = false
            sources += tdFileUri(id) to size
            streamed += piece.copy(fileId = id, sizeBytes = size)
        }
        fileSizeBytes = sources.sumOf { it.second }
        joinedAllLocal = local
        if (!local) {
            val availability = LocalFileAvailability.Partial
            if (!allowedOnThisConnection(availability)) return false
            if (!makeRoomForThisVideo(availability)) return false
        }
        holdPieces(streamed.map { it.fileId })
        if (streamed.isNotEmpty()) {
            val title = chatTitle
            val context = applicationContext
            val claimed = streamed.map { it.copy(title = mediaTitle, chatId = chatId) }
            App.backgroundScope.launch { runCatching { WatchCache.claimAll(context, claimed, title) } }
        }
        boundPieces(streamed)
        joinedSource = joinedUri(sources)
        return true
    }

    private fun holdPieces(ids: List<Int>) {
        heldPieceIds.forEach(WatchCache::stoppedPlaying)
        ids.forEach(WatchCache::startedPlaying)
        heldPieceIds = ids
    }

    /** Low storage mode, else "Save mobile data", for each streamed piece, as [boundTheStream] for one file. */
    private suspend fun boundPieces(streamed: List<MediaItem>) {
        letGoOfPieceBounds()
        if (streamed.isEmpty()) return
        val kept = runCatching { settings.isKeptDownload(chatId, messageId) }.getOrDefault(true)
        if (kept) return
        val lowStorage = runCatching { settings.lowStorageModeNow() }.getOrDefault(false)
        val saveData = MeteredPolicy.saveMobileDataInForce(
            runCatching { settings.saveMobileDataNow() }.getOrDefault(false),
            FormFactor.isTv(this),
        )
        val window = if (lowStorage) {
            val free = runCatching { DiskSpace.read(applicationContext).freeBytes }.getOrDefault(0L)
            LowStorageWindow.windowBytes(free, CacheShelf.HEADROOM_BYTES)
        } else {
            0L
        }
        for (piece in streamed) {
            val id = piece.fileId
            LowStorageStreams.revoke(id)
            SaveDataStreams.revoke(id)
            if (OfflineDownloads.isDownloading(id) || Td.localFileAvailability(id) == LocalFileAvailability.Complete) continue
            when {
                lowStorage -> pieceBounds += Triple(id, LowStorageStreams.allow(id, window), true)
                saveData -> pieceBounds += Triple(id, SaveDataStreams.allow(id) { NetworkMonitor.metered.value }, false)
            }
        }
    }

    /** Takes back what [boundPieces] was granted. True when there was anything. */
    private fun letGoOfPieceBounds(): Boolean {
        if (pieceBounds.isEmpty()) return false
        pieceBounds.forEach { (id, hold, low) ->
            if (low) LowStorageStreams.revoke(id, hold) else SaveDataStreams.revoke(id, hold)
        }
        pieceBounds.clear()
        return true
    }

    /** [stopDownload] for each streamed piece. */
    private fun stopPieceDownloads() {
        val ids = heldPieceIds.ifEmpty { pieceBounds.map { it.first } }
        for (id in ids) {
            if (OfflineDownloads.isDownloading(id)) continue
            App.backgroundScope.launch {
                if (WatchCache.isPlaying(id)) return@launch
                if (Td.localFileAvailability(id) == LocalFileAvailability.Complete) return@launch
                runCatching { Td.cancelDownload(id) }
            }
        }
    }

    /**
     * A seek moved the joined stream off a piece: its download stops, and in Low storage mode
     * its bytes go, since the window is per piece and the one being read now has its own.
     */
    private fun leftPiece(uri: android.net.Uri) {
        if (uri.scheme != "tdfile") return
        val id = uri.authority?.toIntOrNull() ?: return
        if (OfflineDownloads.isDownloading(id)) return
        App.backgroundScope.launch {
            if (Td.localFileAvailability(id) == LocalFileAvailability.Complete) return@launch
            runCatching { Td.cancelDownload(id) }
            if (LowStorageStreams.isRolling(id)) runCatching { Td.deleteFile(id) }
        }
    }

    /** This video, as the cache and the shelf want to see it. */
    private fun mediaItemForCache(): MediaItem = MediaItem(
        chatId = chatId,
        messageId = messageId,
        fileId = fileId,
        title = mediaTitle,
        sizeBytes = fileSizeBytes,
        durationSec = durationSec,
        mimeType = "",
        thumbnailFileId = 0,
        miniThumbnail = null,
        date = 0,
        fileName = mediaTitle,
    )

    /**
     * Says that this video is what the cache now holds, and gives up whatever it held before.
     *
     * Off the main thread and without waiting: it is two preference writes and a delete of a file
     * nothing is reading, and the video being opened must not wait behind any of it.
     */
    private fun claimTheWatchCache() {
        val item = mediaItemForCache()
        // The application context, not the activity: this work outlives the activity by design,
        // and holding a whole player for the length of two preference writes buys nothing.
        App.backgroundScope.launch {
            runCatching { WatchCache.claim(applicationContext, item, chatTitle) }
        }
    }

    /**
     * Switches to another episode of the same series.
     *
     * Done by starting the activity again rather than swapping the file under the player: opening
     * the stream, the resume position, the download meter and the title are all set up in
     * [onCreate] against one file, and a second path through that is a second place to go wrong.
     * The position in the episode being left is saved on the way out.
     */
    fun playEpisode(item: MediaItem) = playEpisode(item, chatTitle)

    /** [playEpisode] for a video in another chat, a copy found by a search across chats. */
    private fun playEpisode(item: MediaItem, inChat: String) {
        // Once: a double tap on the next button, or a held next key on a slow stick, reached here
        // again before this screen was gone and stacked a second player that Back then landed on.
        if (handingOver) return
        handingOver = true
        startActivity(
            intent(this, item, inChat).putExtra(
                EXTRA_OUTSIDE_APP_TASK,
                inPictureInPicture || intent.getBooleanExtra(EXTRA_OUTSIDE_APP_TASK, false),
            ),
        )
        // This screen lingers until the next one is drawn, and its player with it: still reading
        // the stream, still holding the decoders. A TV stick's hardware decoder may serve only one
        // player at a time, and its line and its cores are the new video's now. Stopped here,
        // which releases the decoders and the reads on the playback thread without the wait a
        // release would cost this one; the place is kept, so the saves on the way out still find
        // it, and [onDestroy] releases the rest as before.
        player?.let { exo ->
            saveResumePosition(leaving = true)
            // A stopped player has no tracks to report, so the later saves on the way out would
            // write the place without them: this one is the last.
            savedForHandOver = true
            exo.stop()
        }
        finish()
    }

    /** Set by [playEpisode], so the episode handing over to the next does not open the app too. */
    private var handingOver = false

    /** Set once [playEpisode] has written the place and stopped the player: nothing later saves. */
    private var savedForHandOver = false

    /**
     * An episode started from the corner window lands in the window's own task, not the app's,
     * and once the window is opened full screen again Back from it went to the launcher rather
     * than to the page it was started from. Such a player, and the episodes it steps on to,
     * bring the app's own task back as they leave.
     */
    override fun finish() {
        val stranded = !handingOver && isTaskRoot && !isChangingConfigurations &&
            intent.getBooleanExtra(EXTRA_OUTSIDE_APP_TASK, false)
        // Leaving from "Still watching?" is an answer too: somebody pressed Back.
        resumeHeldDownloads()
        super.finish()
        if (stranded) {
            runCatching {
                startActivity(
                    Intent(this, com.tmplayer.MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT),
                )
            }
        }
    }

    /**
     * What happens when the credits run out.
     *
     * The next episode starts on its own after a countdown that can be stopped, and where there is
     * no next episode the last frame stays up with a way to watch it again.
     */
    private fun onVideoEnded() {
        if (piecesIncomplete) {
            // Only up to a missing piece: said again, and nothing marked or played next.
            piecesNotice?.let { showGestureFeedback(it) }
            showFinished()
            return
        }
        recordFinished()
        lifecycleScope.launch {
            settings.clearResumePosition(chatId, messageId, currentItem())
            val next = _episodes.value.next
            val autoplay = runCatching { settings.autoplayNextNow() }.getOrDefault(true)
            hideNextUp()
            val unseen = inBackgroundAudio
            val end = BackgroundPlayback.atTheEnd(
                hasNext = next != null,
                autoplay = autoplay,
                nextUpDismissed = nextUpDismissed,
                sleepAtTheEnd = sleepAtTheEnd,
                askBeforeAutoplay = StillWatching.askBeforeAutoplay(autoplayedInARow),
                nextUpShown = nextUpShown,
                unseen = unseen,
            )
            when (end) {
                BackgroundPlayback.AtTheEnd.SleepEnded -> {
                    sleepAtTheEnd = false
                    askStillWatching(L.playerStillWatchingSleepEnd, next)
                    return@launch
                }
                BackgroundPlayback.AtTheEnd.Finished -> {
                    showFinished()
                    return@launch
                }
                BackgroundPlayback.AtTheEnd.AskFirst -> {
                    askStillWatching(L.playerStillWatchingAutoplay(StillWatching.AUTOPLAY_LIMIT), next)
                    return@launch
                }
                is BackgroundPlayback.AtTheEnd.Next -> Unit
            }
            next ?: return@launch
            // The card already counted the last half minute down in front of the viewer, and out
            // of sight there is nobody to count down to: a countdown then is a wait for nothing.
            if (!end.countdown) {
                autoplay(next)
                return@launch
            }
            countingDownToNext = true
            for (second in AUTOPLAY_COUNTDOWN_SEC downTo 1) {
                // Left mid-count with background audio on ([keepsPlayingUnseen]): nobody is
                // watching the count, and the next episode should start while Android still
                // lets the service into the foreground.
                if (inBackgroundAudio) break
                showStatus(L.playerNextTitle(_episodes.value.nextTag?.label ?: com.tmplayer.data.SeriesShelf.tileTitle(next)))
                statusDetail.text = L.playerAutoplayCountdown(second)
                showLoadingProgress(
                    (AUTOPLAY_COUNTDOWN_SEC - second).toFloat() / AUTOPLAY_COUNTDOWN_SEC,
                )
                delay(1_000)
            }
            countingDownToNext = false
            autoplay(next)
        }
    }

    /**
     * Whether the end now reached goes on to the next episode, decided at once from what is
     * known (the autoplay setting as last read), for the service's foreground; [onVideoEnded]
     * decides the same with the setting read afresh.
     */
    private fun endLeadsOn(): Boolean = BackgroundPlayback.atTheEnd(
        hasNext = _episodes.value.next != null,
        autoplay = autoplayNext,
        nextUpDismissed = nextUpDismissed,
        sleepAtTheEnd = sleepAtTheEnd,
        askBeforeAutoplay = StillWatching.askBeforeAutoplay(autoplayedInARow),
        nextUpShown = nextUpShown,
        unseen = true,
    ) is BackgroundPlayback.AtTheEnd.Next

    /** [playEpisode] for an episode nobody asked for, counted towards "Still watching?". */
    private fun autoplay(next: MediaItem) {
        autoplayedInARow++
        // Out of sight no new activity may start, so the episode plays in this one; the count
        // carries on in it as it would across activities.
        if (inBackgroundAudio) {
            continueUnseen(next)
            return
        }
        openedByAutoplay = true
        playEpisode(next)
    }

    /** The end of the last video there is: the picture stays, with a way to watch it again. */
    private fun showFinished() {
        openingFilm = false
        keepScreenOn(false)
        statusOverlay.visibility = View.VISIBLE
        statusIcon.visibility = View.GONE
        rebufferChip.visibility = View.GONE
        feedback?.buffering(false)
        statusSpinner?.visibility = View.GONE
        statusProgress.visibility = View.GONE
        statusDetail.visibility = View.GONE
        statusTitle.text = mediaTitle.ifBlank { L.playerFinished }
        statusText.text = L.playerTheEnd
        hideFailureActions()
        statusRetry?.apply {
            text = L.playerWatchAgain
            visibility = View.VISIBLE
            setOnClickListener {
                text = L.commonTryAgain
                setOnClickListener { retryPlayback() }
                quietly { player?.seekTo(0) }
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
        if (refuseSeek()) return
        val exo = player ?: return
        val duration = exo.duration
        val target = (exo.currentPosition + deltaMs).coerceAtLeast(0)
        exo.seekTo(if (duration > 0) target.coerceAtMost(duration - END_GUARD_MS) else target)
    }

    /** The offsets now in force, for the track picker's timing lines. */
    override fun syncDelaysNow(): SyncDelays = syncDelays

    /**
     * Moves subtitles ([C.TRACK_TYPE_TEXT]) or sound one step later ([direction] 1) or earlier
     * (-1), or back to none ([direction] 0), and keeps the result for this file.
     */
    override fun stepDelay(trackType: Int, direction: Int) {
        syncDelays = if (trackType == C.TRACK_TYPE_TEXT) {
            syncDelays.copy(subtitleMs = if (direction == 0) 0 else SyncDelays.step(syncDelays.subtitleMs, direction))
        } else {
            syncDelays.copy(audioMs = if (direction == 0) 0 else SyncDelays.step(syncDelays.audioMs, direction))
        }
        subtitleDelayUs = syncDelays.subtitleMs * 1000
        audioOffset?.offsetUs = syncDelays.audioMs * 1000
        if (hasMessage()) {
            val (chat, message, delays) = Triple(chatId, messageId, syncDelays)
            lifecycleScope.launch { runCatching { settings.setSyncDelays(chat, message, delays) } }
        }
    }

    override fun subtitleStyleNow(): SubtitleStyle = subtitleStyle

    /** Applies [style] to the subtitles on screen at once, and keeps it for every video after. */
    override fun changeSubtitleStyle(style: SubtitleStyle) {
        subtitleStyle = style
        applySubtitleStyle()
        lifecycleScope.launch { runCatching { settings.setSubtitleStyle(style) } }
    }

    /**
     * How much larger than its share of the picture subtitle text is drawn, for a small picture.
     *
     * Once the view is fitted to the picture ([fitSubtitlesTo]), a share of its height is a share
     * of the picture's, which upright on a phone is a band a few hundred pixels tall, and the
     * lines came out too small to read. They are drawn no smaller than they would be over a
     * picture [UPRIGHT_TEXT_SHARE] of the screen's short side high; sideways the picture is
     * taller than that, and this is 1.
     */
    private fun subtitleTextScale(): Float {
        val height = subtitleView.layoutParams?.height ?: 0
        if (height <= 0) return 1f
        val root = findViewById<View>(R.id.player_root)
        return maxOf(1f, minOf(root.width, root.height) * UPRIGHT_TEXT_SHARE / height)
    }

    private fun subtitleTextFraction(): Float = subtitleStyle.size.fraction * subtitleTextScale()

    /**
     * The bottom padding, as a share of the view's height: the position setting's share, or
     * enough to keep the lines [R.dimen.player_subtitle_bottom] off the screen's edge where the
     * picture reaches it, whichever is higher. Never both, so the lines stand at the same height
     * on every picture shape and every video (user, 2026-10-18).
     */
    private fun subtitleBottomFraction(): Float {
        val share = subtitleStyle.position.bottomFraction
        val height = subtitleView.layoutParams?.height ?: 0
        if (height <= 0) return share
        val clearance = resources.getDimensionPixelSize(R.dimen.player_subtitle_bottom)
        val needed = (clearance - subtitleGapBelow).coerceAtLeast(0)
        return maxOf(share, needed.toFloat() / height).coerceAtMost(MAX_SUBTITLE_BOTTOM)
    }

    /**
     * The same floor for a cue that brings its own size, as an SSA line does: scaled with the
     * rest, or it alone would stay too small to read. Picture cues are left at the shape the film
     * gave them.
     */
    private fun scaledForPicture(cues: List<Cue>): List<Cue> {
        val scale = subtitleTextScale()
        if (scale == 1f) return cues
        return cues.map { cue ->
            if (cue.bitmap != null || cue.textSizeType == Cue.TYPE_UNSET || cue.textSize == Cue.DIMEN_UNSET) {
                cue
            } else {
                cue.buildUpon().setTextSize(cue.textSize * scale, cue.textSizeType).build()
            }
        }
    }

    /**
     * Draws subtitles the way [subtitleStyle] says: the size and height here, the colour, edge,
     * typeface, weight and box through [SubtitleLook]. White with a black outline by default: the
     * outline is what survives a bright frame, and the optional box is for a busy one.
     */
    private fun applySubtitleStyle() {
        for (view in listOfNotNull(subtitleView, subtitleTwin)) {
            view.setFractionalTextSize(subtitleTextFraction())
            view.setBottomPaddingFraction(subtitleBottomFraction())
            view.setStyle(SubtitleLook.captionStyle(subtitleStyle))
        }
    }

    /**
     * This video for an online subtitle search: its name, its size, the subtitle language last
     * chosen for the series, and the OpenSubtitles hash when both ends of the file are on the disk
     * (always for a download; for a stream, once TDLib has fetched them).
     */
    override suspend fun onlineTarget(): SubtitleTarget? {
        if (mediaTitle.isBlank()) return null
        val local = downloadedFile
        val id = fileId
        val size = fileSizeBytes
        val hash = withContext(Dispatchers.IO) {
            runCatching {
                when {
                    // The hash is of the whole file's two ends, which for a joined video are two pieces.
                    joinedSource != null -> null
                    local != null -> MovieHash.of(local)
                    id != 0 -> withTimeoutOrNull(3_000) {
                        OnlineSubtitles.hashFromTdlib(Td.awaitConnectedSession().client, id, size)
                    }
                    else -> null
                }
            }.getOrNull()
        }
        return SubtitleTarget(
            fileName = mediaTitle,
            sizeBytes = size,
            hash = hash,
            languages = OnlineSubtitles.languagesFor(tracks.textLanguage),
        )
    }

    override fun attachOnlineSubtitle(file: File, label: String) = subtitleFiles.attachFile(file, label)

    override val pickSubtitleFile: (() -> Unit)?
        get() = if (FormFactor.isTv(this)) null else ({ subtitleFiles.pick() })

    fun showTrackPicker(trackType: Int) {
        TrackPickerFragment.show(supportFragmentManager, R.id.overlay_container, trackType)
    }

    /**
     * MEDIA keys always act, and so do the digits, which jump to that tenth of the video. D-pad
     * keys act on the bare picture and walk the row once it is up: the focused views own them
     * then, with one exception carved out below. Left and right over the bare picture jump the
     * way the phone's double tap does, a small seek and a flash of icon and figure on that side
     * of the frame, without raising the row; the scrub bar, reached through OK, strides by
     * [Skip.BAR_MS] instead and folds a burst of presses into one committed seek. The exception
     * is OK on the focused bar, which toggles playback rather than doing nothing, unless a scrub
     * is mid-flight, in which case the bar takes the press and commits.
     */
    @SuppressLint("RestrictedApi")
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // Locked means locked: Back, the media keys, the D-pad and a keyboard all offer the way
        // out rather than doing what they usually do. See [LockedKeys].
        if (locked) return lockedKey(event)
        // Casting, the volume keys are the TV's.
        cast?.takeIf { it.casting }?.let { c ->
            if (event.keyCode == KeyEvent.KEYCODE_VOLUME_UP || event.keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
                if (event.action == KeyEvent.ACTION_DOWN) c.stepVolume(event.keyCode == KeyEvent.KEYCODE_VOLUME_UP)
                return true
            }
            // A keyboard's or a headset's play and pause, for the TV and never the stopped player.
            if (event.keyCode == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE || event.keyCode == KeyEvent.KEYCODE_MEDIA_PLAY ||
                event.keyCode == KeyEvent.KEYCODE_MEDIA_PAUSE || event.keyCode == KeyEvent.KEYCODE_HEADSETHOOK
            ) {
                if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) c.togglePlay()
                return true
            }
            return super.dispatchKeyEvent(event)
        }
        // Fire TV's Menu key is the player's options, as Amazon's guidelines have it: on release,
        // so that holding it still reaches the long press below and hands the video over.
        if (event.keyCode == KeyEvent.KEYCODE_MENU && remoteQuirks.menuOpensPlayerMenu &&
            statusOverlay.visibility != View.VISIBLE
        ) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) menuHeld = false
            if (event.action == KeyEvent.ACTION_UP) {
                if (!menuHeld && !event.isCanceled) showOverflow(statusOverlay)
                return true
            }
        }
        if (event.action != KeyEvent.ACTION_DOWN) return super.dispatchKeyEvent(event)
        // The quiet line about software decoding goes with the next press, which still does what
        // it does: the line asks nothing, so it never holds a key back.
        if (softwareNoteUp) {
            softwareNoteUp = false
            feedback?.dismissMessage()
        }
        val pickerOpen = TrackPickerFragment.isOpen(supportFragmentManager)
        if (pickerOpen) return super.dispatchKeyEvent(event)

        // Before anything else claims the same key. A held select on a remote, or the menu key, is
        // the long press the browse grid answers with a menu; here the only entry that menu would
        // carry which the player has no other route to is the handover.
        if (isLongPressOnTheVideo(event)) {
            if (event.keyCode == KeyEvent.KEYCODE_MENU) menuHeld = true
            openInAnotherApp()
            return true
        }
        // A Menu press on its way to becoming the options above, or the long press: either way
        // nothing else takes it while it is down.
        if (event.keyCode == KeyEvent.KEYCODE_MENU && remoteQuirks.menuOpensPlayerMenu &&
            statusOverlay.visibility != View.VISIBLE
        ) {
            return true
        }

        // The number row and the numpad, before the focused views see anything: nothing on the
        // transport row uses a digit, so the row being up is no reason to swallow one. Only the
        // status sheet keeps them, since a digit over an error would seek a video that is not
        // playing.
        if (statusOverlay.visibility != View.VISIBLE) {
            digitOf(event.keyCode)?.let { digit ->
                if (jumpToTenth(digit)) return true
            }
        }

        if (!FormFactor.isTv(this) && handleTouchDeviceKey(event)) return true

        // The skip pill over the bare picture on a television: OK is the skip rather than the
        // pause, the way the pill's second line says. With the row up OK keeps its meaning on
        // whatever is focused, and the pill never takes focus itself. A held OK is swallowed whole
        // so its repeats do not pause the video the skip just moved.
        // An offer up over the bare picture on a television ("Stuttering? Switch", "Change"): it
        // holds the focus, so OK is its button and Back puts it away.
        if (FormFactor.isTv(this) && feedback?.offerUp == true && !controlsUp && statusOverlay.visibility != View.VISIBLE) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                    if (event.repeatCount == 0) feedback?.takeOffer()
                    return true
                }
                KeyEvent.KEYCODE_BACK -> {
                    feedback?.dismissOffer()
                    return true
                }
            }
        }

        if (skipHasTheRemote() &&
            (event.keyCode == KeyEvent.KEYCODE_DPAD_CENTER || event.keyCode == KeyEvent.KEYCODE_ENTER)
        ) {
            if (event.repeatCount == 0) skipOffered()
            return true
        }

        // The next-up card on a television: left, right and OK stay on its one button,
        // Back puts it away. Up and down still raise the row below.
        if (nextUpHasTheRemote()) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER,
                -> {
                    if (nextUpCard?.view?.hasFocus() != true) {
                        nextUpCard?.play?.requestFocus()
                        return true
                    }
                    return super.dispatchKeyEvent(event)
                }
                KeyEvent.KEYCODE_BACK -> {
                    nextUpDismissed = true
                    hideNextUp()
                    return true
                }
            }
        }

        when (event.keyCode) {
            // Every key that changes playback shows that it did: the jumps their side figure, play
            // and pause the centre disc or glyph. With no buttons for them on a television's row,
            // the cue is the only sign the press landed.
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
                jumpFromRemote(forward = true, repeatCount = event.repeatCount)
                return true
            }
            KeyEvent.KEYCODE_MEDIA_REWIND -> {
                jumpFromRemote(forward = false, repeatCount = event.repeatCount)
                return true
            }
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                if (event.repeatCount == 0) togglePlayback()
                return true
            }
            KeyEvent.KEYCODE_MEDIA_PLAY -> {
                if (player?.playWhenReady == false) togglePlayback()
                return true
            }
            KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                if (player?.playWhenReady == true) togglePlayback()
                return true
            }
            KeyEvent.KEYCODE_MEDIA_NEXT, KeyEvent.KEYCODE_MEDIA_PREVIOUS -> {
                if (stepEpisode(forward = event.keyCode == KeyEvent.KEYCODE_MEDIA_NEXT)) return true
            }
            // Back peels the overlays off in the order they were put on: the row first, and only
            // then is the film what Back was aimed at.
            KeyEvent.KEYCODE_BACK -> {
                if (statusOverlay.visibility != View.VISIBLE && controlsUp) {
                    controls?.hideAnimated()
                    return true
                }
            }
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_LEFT -> {
                // A remote's arrows over the bare picture jump, exactly as the phone's double
                // tap does: the seek lands at once and the only thing drawn is the flash of
                // icon and figure on the side the jump went, not the row. Whoever wants the
                // bar's longer strides opens the row with OK; once it is up the same arrows
                // reach the focused view through super, which on the bar is the stride and on
                // the buttons is walking the row.
                if (FormFactor.isTv(this) && !controlsUp && statusOverlay.visibility != View.VISIBLE) {
                    jumpFromRemote(forward = event.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT)
                    return true
                }
            }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                if (FormFactor.isTv(this) && statusOverlay.visibility != View.VISIBLE) {
                    // Over the bare picture OK is play or pause in one press, the way every TV
                    // player answers it; the pause raises the row with the play sign in the
                    // middle. With the row up and the bar focused it is play or pause as well.
                    // Repeats are ignored so a held OK stays the long press it is about to become.
                    if (!controlsUp) {
                        if (event.repeatCount == 0) togglePlayback()
                        return true
                    }
                    if (event.repeatCount == 0 && controls?.okOnTimeBar() == true) return true
                }
            }
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> {
                // Up while "Back to" is offered takes it, row up or down: a key that otherwise
                // only raises the row, so Left, Right and OK keep 1.16.0's meanings. The chip's
                // second line says so.
                if (event.keyCode == KeyEvent.KEYCODE_DPAD_UP && FormFactor.isTv(this) &&
                    undoChip?.isShown == true && statusOverlay.visibility != View.VISIBLE
                ) {
                    if (event.repeatCount == 0) undoSeek()
                    return true
                }
                // Any other press over the bare picture asks for the row rather than doing
                // anything irreversible; the second press does the thing it lands on.
                if (FormFactor.isTv(this) && !controlsUp && statusOverlay.visibility != View.VISIBLE) {
                    controls?.show()
                    return true
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    /** A key while the screen is locked: the volume passes, a held select key unlocks, the rest is swallowed. */
    private fun lockedKey(event: KeyEvent): Boolean {
        when (LockedKeys.kind(event.keyCode)) {
            LockedKeys.Kind.System -> return super.dispatchKeyEvent(event)
            LockedKeys.Kind.Unlock -> when (event.action) {
                // A key pressed since the lock, judged by when it went down rather than by its
                // first event: a key still held from the press that chose Lock went down before
                // it and never counts, while a hold that began after it counts even when its first
                // down never arrived. Locking with a tap leaves the window in touch mode, and
                // Android spends the first Enter on leaving touch mode, so only the repeats come.
                KeyEvent.ACTION_DOWN -> if (unlockHeldKey == 0 && event.downTime >= lockedAt) {
                    startUnlockHold(event.keyCode, heldMs = event.eventTime - event.downTime)
                }
                KeyEvent.ACTION_UP -> if (event.keyCode == unlockHeldKey) stopUnlockHold()
            }
            LockedKeys.Kind.Ignored -> if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) showUnlockPill()
        }
        return true
    }

    /**
     * Mouse, trackpad and controller motion while locked: a tablet's mouse wheel, a controller's
     * sticks. Swallowed, as the keys are; a mouse moving over the picture brings up the pill, as
     * it brings up the desktop player's Unlock button.
     */
    override fun dispatchGenericMotionEvent(event: android.view.MotionEvent): Boolean {
        if (!locked) return super.dispatchGenericMotionEvent(event)
        if (event.actionMasked == android.view.MotionEvent.ACTION_HOVER_MOVE ||
            event.actionMasked == android.view.MotionEvent.ACTION_SCROLL
        ) {
            showUnlockPill()
        }
        return true
    }

    /**
     * Back as the dispatcher delivers it: the same peeling [dispatchKeyEvent] does for the key on
     * older Androids, the unlock pill, then the next-up card, then the row. True when something
     * took the press, false when Back is for the film itself.
     */
    private fun peelOneOverlay(): Boolean {
        if (locked) {
            showUnlockPill()
            return true
        }
        if (nextUpHasTheRemote()) {
            nextUpDismissed = true
            hideNextUp()
            return true
        }
        if (statusOverlay.visibility != View.VISIBLE && controlsUp) {
            controls?.hideAnimated()
            return true
        }
        return false
    }

    /** The number row and the numpad, as one digit; null for every other key. */
    private fun digitOf(keyCode: Int): Int? = when (keyCode) {
        in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> keyCode - KeyEvent.KEYCODE_0
        in KeyEvent.KEYCODE_NUMPAD_0..KeyEvent.KEYCODE_NUMPAD_9 -> keyCode - KeyEvent.KEYCODE_NUMPAD_0
        else -> null
    }

    /**
     * A digit key jumps to that tenth of the video, 0 for the start and 5 for halfway, the way
     * desktop players have always read the number row. Only when the duration is known, because a
     * tenth of an unknown length is not a place: the key falls through untouched then.
     */
    private fun jumpToTenth(digit: Int): Boolean {
        val exo = player ?: return false
        val duration = exo.duration.takeIf { it > 0 } ?: return false
        if (refuseSeek()) return true
        exo.seekTo(duration * digit / 10)
        showGestureFeedback(Translator.messages.formatter.percent(digit / 10.0))
        return true
    }

    /**
     * A press held down on the picture itself, which is what asks to leave for another app.
     *
     * Only while the failure sheet is down and the controls are up: a held select over the bare
     * picture is the first press of a scrub for anyone trained on other players, and a held
     * select over the failure sheet is a press on whichever button has focus. Neither is a spare
     * gesture to take.
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
     * transport row on any key it recognises. The press already did the thing, and the small figure
     * this leaves behind is all the confirmation it needs.
     *
     * Only while the row is down. Once it is up those same keys are how anything on it is reached,
     * and stealing them would leave the row unusable to everything except a thumb.
     */
    private fun handleTouchDeviceKey(event: KeyEvent): Boolean {
        if (controlsUp || statusOverlay.visibility == View.VISIBLE) return false
        when (event.keyCode) {
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD, KeyEvent.KEYCODE_DPAD_RIGHT -> jumpFromRemote(forward = true)
            KeyEvent.KEYCODE_MEDIA_REWIND, KeyEvent.KEYCODE_DPAD_LEFT -> jumpFromRemote(forward = false)
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_SPACE,
            // Once per press: a held key repeats, and each repeat flipped playback back again.
            -> if (event.repeatCount == 0) togglePlayback()
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
            // TDLib's updates for every file (each thumbnail among them) are sifted off the main
            // thread, and only the latest of this file's reaches it: the meter needs no more.
            session.client.fileUpdates
                .filter { it.file.id == fileId }
                .conflate()
                .flowOn(Dispatchers.Default)
                .collect { update -> applyDownloadState(update.file) }
        }
    }

    /**
     * Frames read from the video for the scrub card: on unless the viewer turned them off, and
     * only on a phone with the memory to decode a second picture beside the film. A television
     * decodes nothing beside the film, whatever its memory (a 1 GB stick must not spend its CPU
     * on it): its card has the storyboard and the still fallback.
     */
    private fun startTrickplay() {
        lifecycleScope.launch {
            val on = runCatching { settings.trickplayNow() }.getOrDefault(true)
            controls?.previewsOff = !on
            if (!on) stopTrickplaySpans()
        }
        if (FormFactor.isTv(this) || !DeviceQuirks.trickplayMemory(this)) {
            stopTrickplaySpans()
            return
        }
        lifecycleScope.launch {
            if (!runCatching { settings.trickplayNow() }.getOrDefault(true)) return@launch
            val frames = TrickplayFrames(
                scope = lifecycleScope,
                // Not for a video joined from pieces: one piece's file is not the video's.
                path = { if (joinedSource != null) null else downloadedFile?.path ?: trickplayPath },
                spans = { trickplaySpans },
                complete = { downloadedFile != null || downloadComplete },
                sizeBytes = { downloadedFile?.length() ?: fileSizeBytes },
                durationMs = { player?.duration?.takeIf { it > 0 } ?: durationSec * 1_000L },
            )
            trickplay = frames
            showScrubPictures()
        }
    }

    /** No scrub thumbnails from the video's own frames this time: their byte runs are not kept either. */
    private fun stopTrickplaySpans() {
        trickplaySpansWanted = false
        trickplaySpans = emptyList()
    }

    /**
     * Telegram's storyboard for the video (CP80) under the scrub bar, where the setting allows
     * scrub pictures: on its own on a television and a phone too short of memory for
     * [TrickplayFrames], else asked before them. Its two small files are fetched at once.
     */
    private fun startStoryboard(fresh: MediaItem) {
        val board = fresh.storyboard ?: return
        if (storyboardFrames != null) return
        lifecycleScope.launch {
            if (!runCatching { settings.trickplayNow() }.getOrDefault(true)) return@launch
            // Read twice at once, or the video changed while the setting was read.
            if (storyboardFrames != null || postItem !== fresh) return@launch
            storyboardFrames = StoryboardThumbnails(
                scope = lifecycleScope,
                files = board,
                durationMs = { player?.duration?.takeIf { it > 0 } ?: durationSec * 1_000L },
            )
            showScrubPictures()
        }
    }

    /**
     * The scrub bar's pictures from whatever there is: the storyboard first (a tile cut from a
     * sprite already fetched, the cheapest answer), then the video's own frames on a phone.
     */
    private fun showScrubPictures() {
        val frames = trickplay
        val board = storyboardFrames
        controls?.thumbnails = when {
            frames != null && board != null -> LayeredThumbnails(board, frames)
            else -> frames ?: board
        }
    }

    /** Applies both the initial file snapshot and later TDLib updates to the same meter state. */
    private fun applyDownloadState(file: TdFile) {
        // TDLib let go of a download's file when it moved into Downloads, so what it says about
        // that id now is "nothing here", which is not true of the video on screen.
        if (downloadedFile != null) return
        if (fileSizeBytes <= 0 && file.size > 0) fileSizeBytes = file.size
        val local = file.local
        trickplayPath = local.path.takeIf { it.isNotBlank() }
        if (trickplaySpansWanted && local.downloadedPrefixSize > 0) {
            val run = Trickplay.Span(local.downloadOffset, local.downloadOffset + local.downloadedPrefixSize)
            trickplaySpans = Trickplay.merge(trickplaySpans + run)
        }
        // The disk is looked at only once TDLib says the file is whole: before that the policy's
        // answer is "not complete" whatever the file system says, and these calls ran on the main
        // thread for every update, a dozen a second.
        val diskFile = if (local.isDownloadingCompleted) local.path.takeIf { it.isNotBlank() }?.let(::File) else null
        downloadComplete = diskFile != null && LocalFilePolicy.evaluate(
            downloadCompleted = true,
            pathPresent = true,
            regularFile = diskFile.isFile,
            length = diskFile.length(),
            size = file.size,
            expectedSize = file.expectedSize,
        ) == LocalFileAvailability.Complete
        downloadedFraction = StreamStats.downloadedFraction(
            downloadOffset = local.downloadOffset,
            downloadedPrefixSize = local.downloadedPrefixSize,
            size = fileSizeBytes,
            completed = downloadComplete,
        )
        // Sampled on the prefix rather than the window's far end: a seek restarts the prefix at
        // zero and the meter reads that drop as the reset it is, where a window that jumped
        // forwards would look like a burst of speed.
        speed.sample(local.downloadedPrefixSize, SystemClock.elapsedRealtime())
        // The line as measured, for choosing a lighter copy of the next video (CP80).
        // Only while TDLib is fetching and the bytes move: a bounded stream that has its window
        // reads as a slow line otherwise.
        if (local.isDownloadingActive && !local.isDownloadingCompleted && local.downloadedPrefixSize != lastProgressBytes) {
            com.tmplayer.player.LineSpeed.note(speed.bytesPerSec)
        }
        // Any change of the prefix is TDLib doing something, a seek's reset to zero included.
        if (local.downloadedPrefixSize != lastProgressBytes) {
            lastProgressBytes = local.downloadedPrefixSize
            noteOpeningProgress()
        }
        // Throttled rather than rendered per update. TDLib emits one of these every few hundred
        // kilobytes, which at a few MB/s is a dozen a second, and each one re-lays-out the whole
        // loading sheet on the main thread while the decoder is starting up. No figure on that
        // sheet changes usefully faster than twice a second.
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
                            showStatus(L.playerOfflineWaiting)
                            statusDetail.text = L.playerOfflineHint
                        } else if (player?.playbackState == Player.STATE_BUFFERING) {
                            showRebuffering()
                        }
                    } else if (reconnected && player?.playbackState == Player.STATE_BUFFERING) {
                        if (openingFilm) {
                            showStatus(L.playerBackOnline)
                        } else {
                            rebufferText.text = L.playerBackOnline
                        }
                    }
                }
            }
        }
    }

    private fun renderProgress() {
        val rate = Translator.messages.formatter.speed(speed.bytesPerSec)

        // Waiting for the whole video: the bar is the video, not the buffer, and the wait is long
        // enough that a percentage and a time left are the only things making it bearable.
        if (waitingForWholeFilm) {
            val remaining = (fileSizeBytes - (fileSizeBytes * downloadedFraction).toLong())
                .coerceAtLeast(0)
            val left = Translator.messages.formatter.eta(
                StreamStats.secondsForBytes(remaining, speed.bytesPerSec),
            )
            showLoadingProgress(downloadedFraction)
            loaderHost?.progress = downloadedFraction
            renderLoaderStatus()
            val percent = Translator.messages.formatter.percent(downloadedFraction.toDouble())
            statusDetail.text = listOf(
                // Under "Downloading the whole video", which is the mode's own name, so it stays
                // "downloaded" here even though the bytes land in the watch cache.
                L.playerDownloadedPercent(percent),
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

        val left = Translator.messages.formatter.eta(eta)

        if (openingFilm) {
            showLoadingProgress(fraction)
            // The loader's own line is [tickLoader]'s, which follows every stage of the wait.
            renderLoaderStatus()
            statusDetail.text = listOf(rate, left).filter { it.isNotBlank() }.joinToString("  ·  ")
        } else {
            rebufferText.text = L.playerLoadingRate(rate)
        }
        updateDownloadChip()
    }

    /**
     * Turns the window to suit the video, once the decoder has said what shape it is.
     *
     * A clip shot on a phone would otherwise play as a narrow strip between two black fields with
     * the phone held sideways. A television reports a single orientation and ignores every request
     * made here, so this is touch only.
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
        layOutSheetForThisScreen()
    }

    /**
     * Leaving the app puts the video in the corner of whatever comes next, rather than stopping it.
     *
     * Only on a phone, only while something is actually playing, and never over an error sheet or
     * a countdown, none of which are worth a floating window.
     */
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (FormFactor.isTv(this)) return
        if (!hasPictureInPicture()) return
        if (!PictureInPictureRules.entersOnLeaveHint(
                sdk = android.os.Build.VERSION.SDK_INT,
                settingOn = pipOnLeave,
                tv = false,
                playing = player?.isPlaying == true,
                casting = cast?.casting == true,
                sheetUp = statusOverlay.visibility == View.VISIBLE,
            )
        ) {
            return
        }
        runCatching { enterPictureInPictureMode(pictureInPictureParams()) }
    }

    /** The overflow menu's and the row's picture in picture: straight into the corner. */
    private fun enterPictureInPictureNow() {
        if (!hasPictureInPicture()) return
        runCatching { enterPictureInPictureMode(pictureInPictureParams()) }
    }

    /**
     * Keeps the system's picture in picture request in step with playback: entered on its own when
     * the viewer goes home while something plays, never over a paused video or a status sheet.
     */
    private fun updatePictureInPictureParams() {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.S) return
        if (!hasPictureInPicture()) return
        runCatching { setPictureInPictureParams(pictureInPictureParams()) }
    }

    @androidx.annotation.RequiresApi(android.os.Build.VERSION_CODES.O)
    private fun pictureInPictureParams(): PictureInPictureParams {
        val builder = PictureInPictureParams.Builder()
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            // Never on a television: Home there means leaving the film (Decision E10), and the
            // corner window is only for a viewer who asked for it from the row or More.
            val autoEnter = PictureInPictureRules.autoEnter(
                settingOn = pipOnLeave,
                tv = FormFactor.isTv(this),
                playing = player?.isPlaying == true,
                sheetUp = statusOverlay.visibility == View.VISIBLE,
                locked = locked,
            )
            builder.setAutoEnterEnabled(autoEnter)
        }
        // The window grows out of the picture rather than out of a grey box.
        touchSurface?.let { view ->
            val bounds = android.graphics.Rect()
            if (view.getGlobalVisibleRect(bounds)) builder.setSourceRectHint(bounds)
        }
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
        wakeEndWatch()
        if (!isInPictureInPictureMode) pictureInPictureEndedAt = SystemClock.elapsedRealtime()
        // At thumbnail size there is room for the picture and nothing else. The system draws its
        // own play and pause over the window, fed by the media session.
        if (isInPictureInPictureMode) {
            controls?.hideNow()
            feedback?.clear()
            hideNextUp()
            hideSkipPill()
            undoChip?.hide()
        }
        subtitleView.visibility = if (isInPictureInPictureMode) View.GONE else View.VISIBLE
        subtitleTwin?.visibility = subtitleView.visibility
        downloadChip.visibility = View.GONE
        gestures?.controlsVisible = false
    }

    // ---- the phone's extras: settings, overflow, lock, next up --------------------------------

    /** Hands the viewer's touch settings to everything that reads them. */
    private fun applyTouchPrefs() {
        gestures?.prefs = touchPrefs
        feedback?.hapticsEnabled = touchPrefs.haptics
        controls?.timeoutMs = touchPrefs.controlsTimeoutMs
        controls?.showRemaining = touchPrefs.showRemaining
    }

    /** The left and right system gesture strips, in pixels, for the scrub's edge rule. */
    private fun systemGestureEdges(): Pair<Int, Int> {
        val insets = touchSurface?.let { ViewCompat.getRootWindowInsets(it) } ?: return 0 to 0
        val edges = insets.getInsets(WindowInsetsCompat.Type.systemGestures())
        return edges.left to edges.right
    }

    /**
     * The phone's overflow: everything that does not earn a seat on the row, and the handover
     * to another app, which used to be reachable only through a held key. See
     * [PlayerMenu.phoneEntries] for what is in it and why Speed and Start over are not.
     */
    private fun showOverflow(anchor: View) {
        menuOpen = true
        holdControls()
        tvMenu?.let { it.open(); return }
        val menu = android.widget.PopupMenu(this, anchor, Gravity.END)
        val items = menu.menu
        val entries = PlayerMenu.phoneEntries(
            pictureInPicture = hasPictureInPicture(),
            openInAnotherApp = savable == true,
            saveToDownloads = canSaveToDownloads(),
            markWatched = hasMessage(),
            paused = isPaused(),
            bookmarks = hasMessage(),
            chapters = chapters.isNotEmpty(),
            // Only once the chat is known to allow saving: a protected chat's frames stay in it.
            saveFrame = savable == true,
            // Only while the row has no button for it: a phone held upright.
            pictureShape = controls?.shapeOnRow != true,
            stereo = stereoOffered(),
        )
        // Speed and the sleep timer open the pickers' sheet rather than a submenu, so a choice
        // of one of several looks the same here as the subtitle and audio pickers.
        entries.forEachIndexed { order, entry ->
            val label = entry.label(
                sleepDetail = sleepTimerDetail(),
                watched = onWatchedList,
                loop = abLoop,
                bookmarks = bookmarkList.value.size,
                shape = videoScale,
                stereo = stereo.detailText(),
            )
            // Lock the screen heads the menu in a group of its own, under a divider, with the
            // lock the television's and the desktop's menus draw beside the same words.
            val lock = entry == PhoneMenuEntry.LockScreen
            val item = items.add(if (lock) LOCK_GROUP else 0, entry.ordinal, order, label)
            if (lock) item.setIcon(R.drawable.ic_lock)
            if (entry == PhoneMenuEntry.VolumeBoost) item.setCheckable(true).setChecked(volumeBoostOn)
            if (entry == PhoneMenuEntry.NightMode) item.setCheckable(true).setChecked(nightModeOn)
            if (entry == PhoneMenuEntry.EvenVolume) item.setCheckable(true).setChecked(evenVolumeOn)
        }
        androidx.core.view.MenuCompat.setGroupDividerEnabled(items, true)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) menu.setForceShowIcon(true)
        menu.setOnMenuItemClickListener { item ->
            when (PhoneMenuEntry.entries[item.itemId]) {
                PhoneMenuEntry.LockScreen -> lockScreen()
                PhoneMenuEntry.PictureInPicture -> enterPictureInPictureNow()
                PhoneMenuEntry.PictureShape -> showSheet { it.showShape(videoScale, ::applyScale) }
                PhoneMenuEntry.Stereo -> showStereo()
                PhoneMenuEntry.VolumeBoost -> toggleVolumeBoost()
                PhoneMenuEntry.NightMode -> toggleNightMode()
                PhoneMenuEntry.EvenVolume -> toggleEvenVolume()
                PhoneMenuEntry.SleepTimer -> showSheet { it.showSleep(sleepTimerDetail(), sleepChoice(), ::setSleepTimer) }
                PhoneMenuEntry.AbLoop -> pressLoop()
                PhoneMenuEntry.NextChapter -> stepChapter(forward = true)
                PhoneMenuEntry.PreviousChapter -> stepChapter(forward = false)
                PhoneMenuEntry.NextFrame -> frameStep(forward = true)
                PhoneMenuEntry.PreviousFrame -> frameStep(forward = false)
                PhoneMenuEntry.SaveFrame -> saveFrame()
                PhoneMenuEntry.OpenInAnotherApp -> openInAnotherApp()
                PhoneMenuEntry.LoadSubtitleFile -> subtitleFiles.pick()
                PhoneMenuEntry.SaveToDownloads -> saveToDownloads()
                PhoneMenuEntry.MarkWatched -> toggleWatched()
                PhoneMenuEntry.AddBookmark -> {
                    val at = player?.currentPosition ?: 0L
                    showSheet { it.showNewBookmark(at, bookmarkActions()) }
                }
                PhoneMenuEntry.Bookmarks -> showSheet { it.showBookmarks(bookmarkActions()) }
                PhoneMenuEntry.PlaybackDetails -> showPlaybackDetails()
            }
            true
        }
        menu.setOnDismissListener { menuOpen = false; holdControls() }
        menu.show()
    }

    /** What a line of the television's More menu does. */
    private fun onTvMenuEntry(entry: PlayerMenuEntry) {
        when (entry) {
            PlayerMenuEntry.LockScreen -> lockScreen()
            PlayerMenuEntry.PlaybackDetails -> showPlaybackDetails()
            PlayerMenuEntry.StartOver -> startOver()
            PlayerMenuEntry.Stereo -> showStereo()
            PlayerMenuEntry.PictureInPicture -> enterPictureInPictureNow()
            PlayerMenuEntry.OpenInAnotherApp -> openInAnotherApp()
            PlayerMenuEntry.SaveToDownloads -> saveToDownloads()
            PlayerMenuEntry.MarkWatched -> toggleWatched()
            PlayerMenuEntry.VolumeBoost -> toggleVolumeBoost()
            PlayerMenuEntry.NightMode -> toggleNightMode()
            PlayerMenuEntry.EvenVolume -> toggleEvenVolume()
            PlayerMenuEntry.AbLoop -> pressLoop()
            PlayerMenuEntry.NextChapter -> stepChapter(forward = true)
            PlayerMenuEntry.PreviousChapter -> stepChapter(forward = false)
            PlayerMenuEntry.NextFrame -> frameStep(forward = true)
            PlayerMenuEntry.PreviousFrame -> frameStep(forward = false)
            PlayerMenuEntry.AddBookmark -> addBookmark(player?.currentPosition ?: 0L, "")
            PlayerMenuEntry.Bookmarks -> showSheet { it.showBookmarks(bookmarkActions()) }
            // Pages of the menu itself, opened there.
            PlayerMenuEntry.SleepTimer, PlayerMenuEntry.RemoteKeys -> Unit
        }
    }

    /** Paused by the viewer, not stalled for bytes: when the frame steps are offered. */
    private fun isPaused(): Boolean = player?.playWhenReady == false

    /**
     * The repeat line: the start, then the end (and the loop runs), then off, as the desktop's R
     * key has it ([AbLoop.press]). The marks go on the bar and "A-B" beside the time.
     */
    private fun pressLoop() {
        val exo = player ?: return
        val clock = Translator.messages.formatter::clock
        val before = abLoop
        val after = AbLoop.press(before, exo.currentPosition)
        abLoop = after
        controls?.loop = after
        loopHandler.removeCallbacks(loopWatch)
        val end = after?.endMs
        when {
            after == null -> showGestureFeedback(L.playerRepeatOff)
            after == before -> showGestureFeedback(L.playerLoopTooShortMenu)
            end != null -> {
                showGestureFeedback(L.playerRepeating(clock(after.startMs), clock(end)))
                // From the start straight away, so the viewer hears the loop they just marked.
                exactSeekTo(exo, after.startMs)
                loopHandler.post(loopWatch)
            }
            else -> showGestureFeedback(L.playerLoopStartSet(clock(after.startMs)))
        }
    }

    /**
     * One frame forward or back on a paused picture. Media3 has no frame step, so this seeks by one
     * frame's length ([FrameStep]) with an exact seek, which decodes from the keyframe before it and
     * shows the very frame asked for. Slower than a nearest keyframe seek, which is fine for a step.
     */
    private fun frameStep(forward: Boolean) {
        val exo = player ?: return
        if (exo.playWhenReady) return
        val fps = exo.videoFormat?.frameRate ?: 0f
        exactSeekTo(exo, FrameStep.target(exo.currentPosition, exo.duration.coerceAtLeast(0L), fps, forward))
    }

    /**
     * One seek to the very frame at [positionMs], for a frame step and the A-B repeat; every other
     * seek keeps the player's nearest keyframe. Media3 applies the parameters in order on its own
     * thread, so the seek between the two calls is the only exact one.
     */
    private fun exactSeekTo(exo: ExoPlayer, positionMs: Long) {
        exo.setSeekParameters(SeekParameters.EXACT)
        exo.seekTo(positionMs)
        exo.setSeekParameters(SeekParameters.CLOSEST_SYNC)
    }
    /**
     * The overflow's "Save a frame": the picture on screen into Pictures/TMPlayer ([FrameSaver]),
     * answered with a ticked line and Share. Before Android 10, which would need a storage
     * permission this app does not ask for, it says so instead.
     */
    private fun saveFrame() {
        if (savable != true) return
        if (!FrameSaver.supported) {
            showGestureFeedback(L.playerFrameNeedsAndroid10)
            return
        }
        val surface = touchSurface?.videoSurfaceView as? android.view.SurfaceView
        if (surface == null || !firstFrameReady || videoWidth <= 0 || videoHeight <= 0) {
            showGestureFeedback(L.playerFrameNotYet)
            return
        }
        val at = player?.currentPosition ?: 0L
        val title = mediaFileName.ifBlank { mediaTitle }
        lifecycleScope.launch {
            // An anamorphic video's pixels are not square: the picture is saved at the shape it shows.
            val ratio = player?.videoSize?.pixelWidthHeightRatio?.takeIf { it > 0f } ?: 1f
            val frame = FrameSaver.capture(surface, (videoWidth * ratio).toInt(), videoHeight)
            val saved = frame?.let { FrameSaver.save(this@PlayerActivity, it, title, at) }
            frame?.recycle()
            if (saved == null) {
                showGestureFeedback(L.playerFrameNotSaved)
                return@launch
            }
            feedback?.offer(L.playerFrameSaved, L.commonShare, tick = true) { shareFrame(saved) }
        }
    }

    private fun shareFrame(uri: android.net.Uri) {
        val send = Intent(Intent.ACTION_SEND)
            .setType("image/png")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        runCatching { startActivity(Intent.createChooser(send, null)) }
    }

    /** Runs [seek] as one the viewer did not ask for: no "Back to" offer comes of it. */
    private inline fun quietly(seek: () -> Unit) {
        quietSeek = true
        try {
            seek()
        } finally {
            quietSeek = false
        }
    }

    /**
     * Every seek, from the player's report of it. Ignored before the first frame (the resume
     * position is not a jump) and while [quietSeek] is set; otherwise [SeekUndo] decides whether
     * it was long enough to offer the way back.
     */
    private fun noteSeek(fromMs: Long, toMs: Long) {
        if (quietSeek) {
            seekUndo.clear()
            refreshUndoChip()
            return
        }
        if (!firstFrameReady) return
        seekUndo.seeked(fromMs, toMs, SystemClock.elapsedRealtime())
        refreshUndoChip()
    }

    /** Shows the standing offer, or takes the chip away when none stands; and the expiry with it. */
    private fun refreshUndoChip() {
        val chip = undoChip ?: return
        loopHandler.removeCallbacks(undoExpiry)
        val now = SystemClock.elapsedRealtime()
        val offer = seekUndo.current(now)?.takeIf {
            !locked && !inPictureInPicture && statusOverlay.visibility != View.VISIBLE
        }
        if (offer == null) {
            chip.hide()
            return
        }
        val wasShown = chip.isShown
        chip.show(offer.backToMs)
        if (!wasShown) liftCornerOffers()
        loopHandler.postDelayed(undoExpiry, (offer.untilMs - now).coerceAtLeast(0))
    }

    /** The chip's tap, or Up on a television: back to where the viewer was before the jump. */
    private fun undoSeek() {
        val exo = player ?: return
        val back = seekUndo.take(SystemClock.elapsedRealtime()) ?: return
        loopHandler.removeCallbacks(undoExpiry)
        undoChip?.hide(taken = true)
        exo.seekTo(back)
        showGestureFeedback(Translator.messages.formatter.clock(back))
    }

    private fun isOnUndoChip(rawX: Float, rawY: Float): Boolean {
        val chip = undoChip?.view ?: return false
        if (chip.visibility != View.VISIBLE) return false
        val at = IntArray(2)
        chip.getLocationInWindow(at)
        return rawX >= at[0] && rawX < at[0] + chip.width && rawY >= at[1] && rawY < at[1] + chip.height
    }

    /** The menus' chapter lines: the next chapter, or the start of this one, as [Chapters] has it. */
    private fun stepChapter(forward: Boolean) {
        val exo = player ?: return
        val at = exo.currentPosition
        val target = if (forward) Chapters.next(chapters, at) else Chapters.previous(chapters, at)
        if (target == null) {
            showGestureFeedback(if (forward) L.playerLastChapter else L.playerBeforeFirstChapter)
            return
        }
        exo.seekTo(chapters[target].startMs)
        showGestureFeedback(Chapters.label(chapters, target))
    }

    /**
     * Reads the chapter marks of a Matroska file ([MkvChapters]) or an MP4 ([Mp4Chapters]), told
     * apart by their first bytes ([FileChapters]), from the parts already on the disk: a download
     * straight from its file, a stream through TDLib's own reads, which never move the download
     * window (a part not there yet is simply not read). Asked once the picture is up, when Media3
     * has read the head of the file; a file whose chapters sit further in (an MKV's past its
     * attached fonts, an MP4's moov at the end) is asked again a few times as the download
     * reaches them, then left without.
     */
    private fun readChapters() {
        if (chaptersAsked) return
        chaptersAsked = true
        val language = Translator.messages.tag
        itemScope.launch {
            repeat(CHAPTER_TRIES) { attempt ->
                if (attempt > 0) delay(CHAPTER_RETRY_MS)
                val local = downloadedFile
                val found = withContext(Dispatchers.IO) {
                    runCatching {
                        if (local != null) {
                            FileChapters.read(language) { offset, length -> readLocal(local, offset, length) }
                        } else {
                            val client = Td.awaitAuthorizedSession().client
                            FileChapters.read(language) { offset, length -> readDownloaded(client, offset, length) }
                        }
                    }.onFailure { Log.w("TMPlayer", "Chapters not read", it) }.getOrDefault(emptyList())
                }
                if (found.isNotEmpty()) {
                    chapters = found
                    controls?.chapters = found
                    return@launch
                }
                // All of it is here and there are none: nothing later will change that.
                if (local != null || downloadComplete) return@launch
            }
        }
    }

    private fun readLocal(file: File, offset: Long, length: Int): ByteArray? =
        java.io.RandomAccessFile(file, "r").use { raf ->
            if (offset >= raf.length()) return null
            val count = minOf(length.toLong(), raf.length() - offset).toInt()
            ByteArray(count).also { raf.seek(offset); raf.readFully(it) }
        }

    /** [length] bytes at [offset] of the file streaming, or null unless TDLib has all of them. */
    private suspend fun readDownloaded(client: TdlClient, offset: Long, length: Int): ByteArray? {
        val size = fileSizeBytes
        val wanted = if (size > 0) minOf(length.toLong(), size - offset) else length.toLong()
        if (wanted <= 0) return null
        val ready = client.getFileDownloadedPrefixSize(fileId, offset).valueOrNull?.size ?: 0L
        if (ready < wanted) return null
        return client.readFilePart(fileId, offset, wanted).valueOrNull?.data
    }

    /**
     * Saves a bookmark at [positionMs]. The television's menu calls this straight away, without
     * the keyboard, and says where a note can be added; the phone calls it from the note sheet.
     */
    private fun addBookmark(positionMs: Long, note: String) {
        if (!hasMessage()) return
        val store = bookmarkStore
        val chat = chatId
        val message = messageId
        App.backgroundScope.launch { runCatching { store.add(chat, message, positionMs, note) } }
        val at = Translator.messages.formatter.clock(positionMs)
        showGestureFeedback(if (tvMenu != null && note.isEmpty()) L.playerBookmarkSavedTv(at) else L.playerBookmarkSaved(at))
    }

    /** What the bookmark sheets do with a choice: all of it lands here, on this video. */
    private fun bookmarkActions() = BookmarkSheetActions(
        bookmarks = bookmarkList,
        position = { player?.currentPosition ?: 0L },
        add = ::addBookmark,
        rename = { mark, note ->
            val store = bookmarkStore
            App.backgroundScope.launch { runCatching { store.rename(mark.chatId, mark.messageId, mark.id, note) } }
        },
        delete = { mark ->
            val store = bookmarkStore
            App.backgroundScope.launch { runCatching { store.delete(mark.chatId, mark.messageId, mark.id) } }
            showGestureFeedback(L.playerBookmarkDeleted)
        },
        jump = { mark ->
            player?.seekTo(mark.positionMs)
            showGestureFeedback(Translator.messages.formatter.clock(mark.positionMs))
        },
    )

    /** The menu's Volume boost: switched on the sound already playing, and kept for every video. */
    private fun toggleVolumeBoost() {
        val on = !volumeBoostOn
        volumeBoostOn = on
        volumeBoost?.enabled = on
        showGestureFeedback(if (on) L.playerVolumeBoostOn else L.playerVolumeBoostOff)
        lifecycleScope.launch { runCatching { settings.setVolumeBoost(on) } }
    }

    /**
     * The menu's Night mode: the range squeezed on the sound already playing (the processor ramps,
     * so it does not click), and kept for every video. See [VolumeBoostProcessor].
     */
    private fun toggleNightMode() {
        val on = !nightModeOn
        nightModeOn = on
        volumeBoost?.night = on
        showGestureFeedback(if (on) L.playerNightModeOn else L.playerNightModeOff)
        lifecycleScope.launch { runCatching { settings.setNightMode(on) } }
    }

    /** 3D video is for a picture: not sound alone, and not while a cast plays the video. */
    private fun stereoOffered(): Boolean = !isAudio && cast?.connected != true && cast?.casting != true

    /**
     * Before the player is first prepared: what the file's name says about 3D, and the effects
     * pipeline built only for a file that says it is 3D, or when 3D is already on (an episode
     * that follows in a new player), so turning it on there needs no second start.
     */
    private fun prepareStereo(exo: ExoPlayer) {
        stereoTagged = if (isAudio) null else Stereo3d.detect(listOf(mediaFileName, mediaCaption, mediaTitle).joinToString(" "))
        stereoHinted = false
        if (stereoPipeline || isAudio) return
        if (stereo.on || stereoTagged != null) {
            exo.setVideoEffects(if (stereo.on) listOf(Stereo3dEffect(stereo)) else emptyList())
            stereoPipeline = true
        }
    }

    /** Once a video, on its first picture: a file named as 3D says where 3D video is. Nothing changes by itself. */
    private fun hintStereo() {
        if (stereoHinted || stereo.on || stereoTagged == null) return
        stereoHinted = true
        showGestureFeedback(L.playerStereoFound)
    }

    private fun showStereo() {
        showSheet { it.showStereo(stereo, stereoTagged) { next -> applyStereo(next) to stereo } }
    }

    /**
     * Why 3D cannot be drawn for the video playing, or null when it can: an HDR picture, whose
     * colours the shader would wash out, and a video on the app's own (FFmpeg) decoder, whose
     * frames do not pass through Media3's effects.
     */
    private fun stereoBlocked(exo: ExoPlayer): String? {
        val color = exo.videoFormat?.colorInfo
        if (color != null && androidx.media3.common.ColorInfo.isTransferHdr(color)) return L.playerStereoNotHdr
        val onFfmpeg = (0 until exo.rendererCount).any { index ->
            exo.getRendererType(index) == C.TRACK_TYPE_VIDEO &&
                exo.getRenderer(index).state != androidx.media3.exoplayer.Renderer.STATE_DISABLED &&
                DecoderSwitch.isFfmpeg(exo.getRenderer(index))
        }
        return if (onFfmpeg) L.playerStereoNotSoftware else null
    }

    /**
     * Puts [next] on the picture. A choice that only changes the glasses while 3D is off, or that
     * the video cannot take ([stereoBlocked]), changes nothing on screen. The first time 3D comes
     * on in a player without the pipeline, the player is prepared again where it stands, a
     * moment's wait with the place, the tracks and play or pause kept.
     *
     * Returns why 3D could not come on, for the page to say, or null.
     */
    private fun applyStereo(next: Stereo3d): String? {
        val exo = player ?: return null
        val before = stereo
        if (next.on && !before.on) {
            stereoBlocked(exo)?.let { why ->
                // Kept as a choice of glasses, so a later video can use it.
                stereo = before.copy(view = next.view, swapEyes = next.swapEyes)
                return why
            }
        }
        stereo = next
        if (!next.on && !before.on) return null
        val effects: List<androidx.media3.common.Effect> = if (next.on) listOf(Stereo3dEffect(next)) else emptyList()
        if (stereoPipeline) {
            exo.setVideoEffects(effects)
        } else if (next.on) {
            stereoPipeline = true
            val at = exo.currentPosition
            val play = exo.playWhenReady
            exo.stop()
            exo.setVideoEffects(effects)
            exo.seekTo(at)
            exo.prepare()
            exo.playWhenReady = play
        }
        stereoHinted = true
        // A headset's two halves take a copy of the subtitles each, and lose it after.
        refitSubtitles()
        showGestureFeedback(if (next.on) L.playerStereoOnNow(next.view.label()) else L.playerStereoOffNow)
        return null
    }

    /**
     * The menu's Even out volume: a quiet track lifted towards the level of other videos, on the
     * sound already playing (the lift moves slowly, so it does not jump), and kept for every video.
     */
    private fun toggleEvenVolume() {
        val on = !evenVolumeOn
        evenVolumeOn = on
        volumeBoost?.level = on
        showGestureFeedback(if (on) L.playerEvenVolumeOn else L.playerEvenVolumeOff)
        lifecycleScope.launch { runCatching { settings.setEvenVolume(on) } }
    }

    /** What the menu says about a running sleep timer, or null when there is none. */
    private fun sleepTimerDetail(): String? = when {
        sleepAtTheEnd -> SleepTimer.label(SleepTimer.END_OF_VIDEO)
        sleepAt > 0 -> SleepTimer.remaining(sleepAt - SystemClock.elapsedRealtime())
        else -> null
    }

    /** The length the running sleep timer was started with, or null when there is none. */
    private fun sleepChoice(): Int? = when {
        sleepAtTheEnd -> SleepTimer.END_OF_VIDEO
        sleepAt > 0 -> sleepMinutes
        else -> null
    }

    /**
     * Opens one of [sheets], holding the controls while it is up. Its own flag, like the details
     * dialog's was, because the overflow it opens from closes after it is already up.
     */
    private fun showSheet(open: (PlayerSheets) -> Unit) {
        val host = sheets ?: return
        open(host)
        detailsOpen = true
        holdControls()
    }

    /** Minutes from now, [SleepTimer.END_OF_VIDEO], or null to turn the timer off. */
    private fun setSleepTimer(minutes: Int?) {
        sleepMinutes = minutes ?: 0
        sleepAtTheEnd = minutes == SleepTimer.END_OF_VIDEO
        sleepAt = if (minutes != null && minutes != SleepTimer.END_OF_VIDEO) {
            SystemClock.elapsedRealtime() + minutes * 60_000L
        } else {
            0L
        }
        showGestureFeedback(
            when (minutes) {
                null -> L.playerSleepTimerOff
                SleepTimer.END_OF_VIDEO -> L.playerSleepTimerEnd
                else -> L.playerSleepTimerIn(SleepTimer.label(minutes))
            },
        )
    }

    /**
     * Starts or carries on the counts "Still watching?" keeps, and looks at them every
     * [STILL_WATCHING_TICK_MS]: the sleep timer running out, and two hours of playback without a
     * press. Not tied to the started state, since picture in picture stops the activity while the
     * video carries on, and that is exactly when nobody may be watching.
     */
    private fun startStillWatchingCounts() {
        if (!openedByAutoplay) {
            autoplayedInARow = 0
            sleepAt = 0L
            sleepAtTheEnd = false
            lastInputAt = SystemClock.elapsedRealtime()
        }
        openedByAutoplay = false
        if (lastInputAt == 0L) lastInputAt = SystemClock.elapsedRealtime()
        lifecycleScope.launch {
            while (true) {
                delay(STILL_WATCHING_TICK_MS)
                if (stillWatchingUp) continue
                val exo = player ?: continue
                val now = SystemClock.elapsedRealtime()
                if (sleepAt in 1..now) {
                    sleepAt = 0L
                    askStillWatching(L.playerStillWatchingSleep, next = null)
                } else if (exo.isPlaying && StillWatching.askAfterIdle(now - lastInputAt)) {
                    askStillWatching(L.playerStillWatchingIdle, next = null)
                }
            }
        }
    }

    /** Any key or touch is somebody watching. */
    override fun onUserInteraction() {
        super.onUserInteraction()
        lastInputAt = SystemClock.elapsedRealtime()
        autoplayedInARow = 0
    }

    /**
     * "Still watching?": the whole screen, with playback stopped and every download held.
     *
     * The player is stopped rather than paused, which closes the stream: a paused player keeps
     * reading ahead, and TDLib keeps fetching for it. Keep watching prepares it again where it
     * was. [next] is the episode autoplay was about to start, which Keep watching starts instead.
     */
    private fun askStillWatching(why: String, next: MediaItem?) {
        // Its buttons have to be reachable, and the video is stopping anyway: hours without a
        // touch or a key, or a sleep timer running out, is nobody there for the lock to keep out.
        if (locked) unlockScreen()
        stillWatchingUp = true
        hideNextUp()
        controls?.hideAnimated()
        player?.let {
            it.pause()
            it.stop()
        }
        // Not [stopDownload], which leaves alone a file this player is still holding, as it is here.
        val id = fileId
        if (!downloadComplete && id > 0 && !OfflineDownloads.isDownloading(id)) {
            App.backgroundScope.launch { runCatching { Td.cancelDownload(id) } }
        }
        downloadsHeldForTheCard = OfflineDownloads.active.value.values.filter { it.busy }.map { it.fileId }
        if (downloadsHeldForTheCard.isNotEmpty()) OfflineDownloads.pauseAll(this)

        keepScreenOn(false)
        statusOverlay.visibility = View.VISIBLE
        statusIcon.visibility = View.GONE
        rebufferChip.visibility = View.GONE
        feedback?.buffering(false)
        statusSpinner?.visibility = View.GONE
        statusProgress.visibility = View.GONE
        hideFailureActions()
        layOutSheetForThisScreen()
        statusTitle.text = L.playerStillWatching
        statusText.text = why
        statusDetail.visibility = View.VISIBLE
        statusDetail.text = next?.let { L.playerNextTitle(com.tmplayer.data.SeriesShelf.tileTitle(it)) } ?: L.playerStillWatchingPaused
        statusBack?.visibility = View.VISIBLE
        statusRetry?.apply {
            text = L.playerKeepWatching
            visibility = View.VISIBLE
            setOnClickListener {
                text = L.commonTryAgain
                setOnClickListener { retryPlayback() }
                keepWatching(next)
            }
            requestFocus()
        }
        updateDownloadChip()
    }

    private fun keepWatching(next: MediaItem?) {
        stillWatchingUp = false
        resumeHeldDownloads()
        if (next != null) {
            playEpisode(next)
            return
        }
        hideStatus()
        val exo = player ?: return
        if (exo.playbackState == Player.STATE_IDLE) exo.prepare()
        exo.play()
        keepScreenOn(true)
    }

    /**
     * Lets the queued downloads "Still watching?" paused carry on. Any answer counts, not only Keep
     * watching: play from a headset, and Back or Go back, which otherwise left them paused in
     * Downloads with nothing to say why.
     */
    private fun resumeHeldDownloads() {
        downloadsHeldForTheCard.forEach { OfflineDownloads.resume(this, it) }
        downloadsHeldForTheCard = emptyList()
    }

    /**
     * Whether Save to Downloads means anything for this video: not one that is a download already,
     * not one the queue has, and not one with no message to download it from again.
     */
    private fun canSaveToDownloads(): Boolean =
        savable == true && downloadedFile == null && fileId > 0 && chatId != 0L && messageId != 0L &&
            !OfflineDownloads.isDownloading(fileId)

    /**
     * Keeps the video being watched: it joins the download queue, which fetches whatever of it is
     * not here yet, and moves it into Downloads once this player lets go of the file.
     */
    private fun saveToDownloads() {
        if (!canSaveToDownloads()) {
            showGestureFeedback(
                when {
                    savable == null -> CHECKING_FEEDBACK
                    savable == false -> NOT_SAVABLE_FEEDBACK
                    downloadedFile != null -> L.playerAlreadyInDownloads
                    else -> L.playerAlreadyDownloading
                },
            )
            return
        }
        val item = mediaItemForCache()
        OfflineDownloads.start(this, item, chatTitle)
        showGestureFeedback(
            if (downloadComplete) {
                L.playerSavingToDownloads
            } else {
                L.playerDownloadingToDownloads
            },
        )
    }

    private fun setSpeed(value: Float) {
        playbackSpeed = value
        player?.setPlaybackSpeed(value)
        showGestureFeedback(PlaybackSpeed.label(value))
        // For the series, the course, the chat or the topic, not only this file: 1x forgets it.
        val scopes = speedScopes
        val store = settings
        App.backgroundScope.launch { runCatching { store.rememberSpeed(scopes, value) } }
    }

    /** Puts the "Resuming from" line up for [atMs] and offers the phone's way back to the start. */
    private fun showResumeFrom(atMs: Long) {
        resumeNotice = L.playerResumingFrom(Translator.messages.formatter.clock(atMs))
        renderStatusText()
        offerStartOver()
    }

    /**
     * Whether the viewer has taken the video somewhere else since it opened at [fromMs], in which
     * case a late answer about where to resume must leave it there.
     */
    private fun movedSinceResume(fromMs: Long): Boolean {
        val at = player?.currentPosition ?: return false
        return kotlin.math.abs(at - fromMs) > RESUME_SYNC_SLACK_MS
    }

    /**
     * The phone's way back to the start of a video that opened where it was left: a line over the
     * picture with "Start over", for a few seconds. It needs both the saved position and a picture,
     * which arrive in either order, so the two places that learn them both come here. A saved
     * position within the first seconds is not worth offering, and the television keeps its menu line.
     */
    private fun offerStartOver() {
        if (startOverOffered || !firstFrameReady || resumeMs < RESUME_OFFER_MIN_MS) return
        if (FormFactor.isTv(this)) return
        startOverOffered = true
        feedback?.offer(
            L.playerResumingFrom(Translator.messages.formatter.clock(resumeMs)),
            L.playerStartOver,
        ) { startOver() }
    }

    /**
     * What the row and the menus no longer show: on a phone that a double tap jumps and which swipes
     * are on (see [FirstRunHint]), with "Got it" to put it away sooner; on a television that the
     * arrows jump and More lists every key. Said once ever, the first time the row goes away over a
     * playing video, and not while the resume offer is up. The tour leaves these to this moment.
     */
    private fun showFirstRunHint() {
        if (firstRunHintShown || !firstFrameReady) return
        firstRunHintShown = true
        lifecycleScope.launch {
            val seen = runCatching { settings.playerHintSeenNow() }.getOrDefault(true)
            if (seen) return@launch
            runCatching { settings.markPlayerHintSeen() }
            if (FormFactor.isTv(this@PlayerActivity)) {
                feedback?.message(L.playerRemoteHint, holdMs = HINT_MS)
            } else {
                val lines = FirstRunHint.phone(touchPrefs, L)
                feedback?.offer(lines.joinToString("\n"), L.commonGotIt, holdMs = PHONE_HINT_MS) {}
            }
        }
    }

    /** Back to the first frame, for a viewer who did not want the saved position. */
    private fun startOver() {
        val exo = player ?: return
        // A short watch from here leaves a saved place alone, so starting over forgets it.
        lifecycleScope.launch { settings.clearResumePosition(chatId, messageId, currentItem()) }
        exo.seekTo(0)
        exo.playWhenReady = true
        showGestureFeedback(L.playerFromTheStart)
    }

    /** What is actually playing: the figures a bug report needs, in words a viewer can read. */
    private fun showPlaybackDetails() {
        val exo = player ?: return
        val video = exo.videoFormat
        val audio = exo.audioFormat
        val lines = buildList {
            if (video != null) {
                val width = video.width.toString()
                val height = video.height.toString()
                add(
                    video.frameRate.takeIf { it > 0 }
                        ?.let { L.playerDetailsPictureFps(width, height, "%.3g".format(it)) }
                        ?: L.playerDetailsPicture(width, height),
                )
                video.sampleMimeType?.let { add(L.playerDetailsVideoCodec(it.substringAfter('/'))) }
            }
            if (audio != null) {
                add(L.playerDetailsSound(audio.channelCount.toString(), audio.sampleRate.toString()))
                audio.sampleMimeType?.let { add(L.playerDetailsAudioCodec(it.substringAfter('/'))) }
            }
            if (fileSizeBytes > 0) add(L.playerDetailsFile(Translator.messages.formatter.bytes(fileSizeBytes)))
            val here = Translator.messages.formatter.percent(downloadedFraction.toDouble())
            add(if (isRealDownload()) L.playerDetailsDownloaded(here) else L.playerDetailsCached(here))
            add(L.playerDetailsSpeed(PlaybackSpeed.label(playbackSpeed)))
        }
        showSheet { it.showDetails(mediaTitle.ifBlank { L.playerPlaybackDetails }, lines) }
    }

    /**
     * Locks the screen against touches: for a phone propped up on a lap, or handed to a child.
     * Everything is off, the orientation is frozen, and a tap anywhere offers the unlock pill.
     */
    private fun lockScreen() {
        undoChip?.hide()
        if (locked) return
        locked = true
        wakeEndWatch()
        // The lock is invisible to a hand on a lap: it is felt going on and coming off.
        findViewById<View>(R.id.player_root)?.haptic(com.tmplayer.ui.components.Haptic.Confirm, touchPrefs.haptics)
        lockedAt = android.os.SystemClock.uptimeMillis()
        controls?.hideAnimated()
        feedback?.clear()
        requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LOCKED
        val root = findViewById<FrameLayout>(R.id.player_root)
        val tv = FormFactor.isTv(this)
        val shield = lockShield ?: FrameLayout(this).also { built ->
            built.isClickable = true
            built.isFocusable = true
            built.setOnClickListener { showUnlockPill() }
            val pad = (16 * resources.displayMetrics.density).toInt()
            val label = TextView(this).apply {
                setTextColor(getColor(R.color.text_primary))
                textSize = if (tv) 18f else 15f
                gravity = Gravity.CENTER
                setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_lock_open, 0, 0, 0)
                compoundDrawablePadding = pad / 2
            }
            // Fills while a select key is held, so the remote's way out shows it is working.
            val progress = android.widget.ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
                max = 1000
                visibility = View.GONE
                progressTintList = android.content.res.ColorStateList.valueOf(getColor(R.color.text_primary))
            }
            val pill = android.widget.LinearLayout(this).apply {
                orientation = android.widget.LinearLayout.VERTICAL
                background = getDrawable(R.drawable.bg_player_chip)
                setPadding(pad * 3 / 2, pad, pad * 3 / 2, pad)
                addView(label)
                addView(
                    progress,
                    android.widget.LinearLayout.LayoutParams(
                        android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                        android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                    ).apply { topMargin = pad / 2 },
                )
                // A tap, a mouse click or a stylus. Not a remote's OK: keys never reach the views
                // while locked, and a held OK is the remote's way out.
                isClickable = true
                isFocusable = false
                contentDescription = L.playerUnlock
                setOnClickListener { unlockScreen() }
            }
            built.addView(
                pill,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    Gravity.CENTER_HORIZONTAL or Gravity.BOTTOM,
                ).apply { bottomMargin = (72 * resources.displayMetrics.density).toInt() },
            )
            unlockPill = pill
            unlockText = label
            unlockProgress = progress
            lockShield = built
        }
        unlockText?.text = unlockHint()
        if (shield.parent == null) {
            root.addView(
                shield,
                FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT),
            )
        }
        updatePictureInPictureParams()
        showUnlockPill()
    }

    /** The pill's words: a remote holds OK, a touch screen taps (a held Enter works there too). */
    private fun unlockHint(): String = if (FormFactor.isTv(this)) L.playerHoldOkToUnlock else L.playerTapToUnlock

    private fun showUnlockPill() {
        val pill = unlockPill ?: return
        pill.removeCallbacks(hideUnlockPill)
        pill.animate().cancel()
        pill.alpha = 1f
        // Up for as long as a hold lasts; the clock starts once it is let go.
        if (unlockHeldKey == 0) pill.postDelayed(hideUnlockPill, UNLOCK_PILL_MS)
    }

    /**
     * A select key went down on the locked screen: unlock if it stays down for [LockedKeys.HOLD_MS].
     * [heldMs] is how long it has been down already, when its first down was not the event that
     * started this.
     */
    private fun startUnlockHold(keyCode: Int, heldMs: Long = 0L) {
        val pill = unlockPill ?: return
        val already = heldMs.coerceIn(0L, LockedKeys.HOLD_MS)
        val left = LockedKeys.HOLD_MS - already
        unlockHeldKey = keyCode
        showUnlockPill()
        unlockText?.text = L.playerKeepHoldingToUnlock
        unlockProgress?.let { bar ->
            bar.visibility = View.VISIBLE
            val from = (bar.max * already / LockedKeys.HOLD_MS).toInt()
            bar.progress = from
            bar.tag = android.animation.ObjectAnimator.ofInt(bar, "progress", from, bar.max).apply {
                duration = left
                interpolator = android.view.animation.LinearInterpolator()
                start()
            }
        }
        pill.postDelayed(unlockAfterHold, left)
    }

    /** Let go before the hold was up: still locked, and the pill says how again. */
    private fun stopUnlockHold() {
        unlockHeldKey = 0
        unlockPill?.removeCallbacks(unlockAfterHold)
        resetUnlockProgress()
        unlockText?.text = unlockHint()
        showUnlockPill()
    }

    private fun resetUnlockProgress() {
        unlockProgress?.let { bar ->
            (bar.tag as? android.animation.Animator)?.cancel()
            bar.tag = null
            bar.progress = 0
            bar.visibility = View.GONE
        }
    }

    private fun unlockScreen() {
        if (!locked) return
        locked = false
        wakeEndWatch()
        findViewById<View>(R.id.player_root)?.haptic(com.tmplayer.ui.components.Haptic.ToggleOff, touchPrefs.haptics)
        unlockHeldKey = 0
        unlockPill?.removeCallbacks(unlockAfterHold)
        resetUnlockProgress()
        unlockPill?.removeCallbacks(hideUnlockPill)
        lockShield?.let { (it.parent as? android.view.ViewGroup)?.removeView(it) }
        applyOrientation()
        updatePictureInPictureParams()
        controls?.show()
    }

    /** The next-up card, on a phone and a television alike. See [NextUpCard]. */
    private fun buildNextUpCard() {
        nextUpCard = NextUpCard(
            root = findViewById(R.id.player_root),
            below = findViewById(R.id.overlay_container),
            tv = FormFactor.isTv(this),
            onHide = {
                nextUpDismissed = true
                hideNextUp()
            },
            onPlay = { _episodes.value.next?.let(::playEpisode) },
        )
        skipPill = SkipPill(
            root = findViewById(R.id.player_root),
            below = findViewById(R.id.overlay_container),
            tv = FormFactor.isTv(this),
            onSkip = ::skipOffered,
        )
        undoChip = UndoSeekChip(
            root = findViewById(R.id.player_root),
            below = findViewById(R.id.overlay_container),
            tv = FormFactor.isTv(this),
            onUndo = ::undoSeek,
        )
        watchForTheEnd()
    }

    /**
     * Twice a second while playing: the skip pill comes and goes with the stretch the playhead is
     * in, and near the end of an episode with a successor and autoplay on, the card comes up and
     * counts the remaining seconds down.
     *
     * While the video is not playing (paused, waiting for bytes, not built yet) nothing here moves
     * on its own, so the loop sleeps until [endWatchWake] says something did, and looks once.
     *
     * "Near the end" is the last half minute, or from the start of the closing credits where
     * somebody has timed them and they begin earlier than that: credits that roll for two minutes
     * are two minutes nobody is watching for, which is when every streaming service raises its
     * next-episode offer. From there the card counts down a quarter of a minute and moves on.
     */
    private fun watchForTheEnd() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                val autoplay = runCatching { settings.autoplayNextNow() }.getOrDefault(true)
                while (true) {
                    if (player?.isPlaying == true) delay(500) else endWatchWake.receive()
                    val exo = player ?: continue
                    updateSkipPill(exo, autoplay)
                    if (!autoplay) continue
                    val next = _episodes.value.next ?: continue
                    val duration = exo.duration
                    if (duration <= 0 || nextUpDismissed || locked || inPictureInPicture) continue
                    // Autoplay is going to stop and ask, or the sleep timer to stop: nothing
                    // is starting in thirty seconds, so nothing says it is.
                    if (sleepAtTheEnd || StillWatching.askBeforeAutoplay(autoplayedInARow)) continue
                    val position = exo.currentPosition
                    val left = duration - position
                    val credits = SkipSegments.creditsStart(skipSegments, duration)
                    val lead = maxOf(NEXT_UP_LEAD_MS, credits?.let { duration - it } ?: 0L)
                    // Credits timed to start well before the last half minute: the card counts
                    // [CREDITS_COUNTDOWN_MS] down from where they begin and then moves on, as the
                    // streaming services do, rather than counting through two minutes of names.
                    val early = credits != null && duration - credits > NEXT_UP_LEAD_MS
                    val until = if (early) credits!! + CREDITS_COUNTDOWN_MS - position else left
                    if (early && until <= 0 && nextUpShown && !creditsSkipped) {
                        creditsSkipped = true
                        onVideoEnded()
                        continue
                    }
                    if (left in 1..lead && statusOverlay.visibility != View.VISIBLE) {
                        val label = _episodes.value.nextTag?.label ?: com.tmplayer.data.SeriesShelf.tileTitle(next)
                        if (nextUpCard?.show(label, (until.coerceAtLeast(0) + 999) / 1000) == true) {
                            nextUpShown = true
                            // Its head and index, so it starts as fast as this one ends.
                            com.tmplayer.player.StreamWarmup.warm(next)
                            // The card and the pill share a corner; the card is the offer then.
                            hideSkipPill()
                            liftCornerOffers()
                            if (FormFactor.isTv(this@PlayerActivity) && !controlsUp) nextUpCard?.play?.requestFocus()
                        }
                    } else if (left > lead && nextUpCard?.isShown == true) {
                        // Seeked back out of the last half minute: the offer goes, and comes back.
                        hideNextUp()
                        nextUpShown = false
                    }
                }
            }
        }
    }

    /**
     * Asks once per video where its intro and credits are, for the skip pill and the next-up
     * card. Nothing is asked while online lookups or skip times are off; [OnlineMetadata.skipTimes]
     * answers empty without going to the network then.
     *
     * A video opened without a known length waits a little for the player to report one, since
     * AniSkip matches an episode's timings by its length. A video that never reports one is asked
     * about without it. Another episode is another start of this activity (see [playEpisode]), so
     * this runs again for it from [onCreate].
     */
    private fun findSkipTimes() {
        val metadata = OnlineMetadata.current ?: return
        itemScope.launch {
            afterOpening()
            var seconds = durationSec
            if (seconds <= 0) {
                withTimeoutOrNull(SKIP_TIMES_WAIT_MS) {
                    while (true) {
                        val known = player?.duration?.takeIf { it > 0 }
                        if (known != null) {
                            seconds = (known / 1_000).toInt()
                            break
                        }
                        delay(500)
                    }
                }
            }
            val item = currentItem().copy(durationSec = seconds.coerceAtLeast(0))
            skipSegments = withContext(Dispatchers.IO) {
                runCatching { metadata.skipTimes(item) }.getOrDefault(emptyList())
            }
            wakeEndWatch()
        }
    }

    /**
     * Raises the pill for the stretch the playhead is in, or puts it away. Not over the failure
     * sheet, a locked screen or a thumbnail, and not while the next-up card is up: credits that
     * run to the end of an episode with a successor are the card's, so they get no pill at all
     * when autoplay is going to offer that card. Without a successor a credits pill skips to the
     * end, which is the finished screen.
     */
    private fun updateSkipPill(exo: ExoPlayer, autoplay: Boolean) {
        val pill = skipPill ?: return
        val duration = exo.duration
        val cardCoversTheEnd = autoplay && !nextUpDismissed && _episodes.value.next != null
        val segment = if (
            skipSegments.isEmpty() || duration <= 0 || locked || inPictureInPicture ||
            statusOverlay.visibility == View.VISIBLE || nextUpCard?.isShown == true
        ) {
            null
        } else {
            SkipSegments.active(skipSegments, exo.currentPosition, duration, skippedSegments)
                ?.takeUnless { cardCoversTheEnd && it.runsToEnd(duration) }
        }
        offeredSkip = segment
        val wasShown = pill.isShown
        if (segment == null) pill.hide() else pill.show(segment.kind)
        if (!wasShown && pill.isShown) liftCornerOffers()
    }

    /** The pill's press, or OK on a television: past the stretch on offer, which then stays gone. */
    private fun skipOffered() {
        val exo = player ?: return
        val segment = offeredSkip ?: return
        val duration = exo.duration.takeIf { it > 0 } ?: return
        skippedSegments += segment.startMs
        hideSkipPill()
        exo.seekTo(segment.target(duration).coerceAtMost(duration))
    }

    private fun hideSkipPill() {
        offeredSkip = null
        skipPill?.hide()
    }

    /** Whether the skip pill is up over the bare picture on a television, so OK is the skip. */
    private fun skipHasTheRemote(): Boolean =
        FormFactor.isTv(this) && skipPill?.isShown == true && offeredSkip != null && !controlsUp &&
            statusOverlay.visibility != View.VISIBLE

    private fun isOnSkipPill(rawX: Float, rawY: Float): Boolean {
        val pill = skipPill?.view ?: return false
        if (pill.visibility != View.VISIBLE) return false
        val at = IntArray(2)
        pill.getLocationInWindow(at)
        return rawX >= at[0] && rawX < at[0] + pill.width && rawY >= at[1] && rawY < at[1] + pill.height
    }

    private fun hideNextUp() {
        // Focus left on a gone view is focus nowhere; the next press would land on nothing.
        if (nextUpCard?.hide() == true) controls?.focusRow()
    }

    /** Whether the next-up card is up and the remote's arrows and OK belong to it. */
    private fun nextUpHasTheRemote(): Boolean =
        FormFactor.isTv(this) && nextUpCard?.isShown == true && !controlsUp &&
            statusOverlay.visibility != View.VISIBLE

    private fun isOnNextUpCard(rawX: Float, rawY: Float): Boolean {
        val card = nextUpCard?.view ?: return false
        if (card.visibility != View.VISIBLE) return false
        val at = IntArray(2)
        card.getLocationInWindow(at)
        return rawX >= at[0] && rawX < at[0] + card.width && rawY >= at[1] && rawY < at[1] + card.height
    }

    /** Keeps the row up while anything opened from it is still on screen; see [PlayerControls.held]. */
    private fun holdControls() {
        controls?.held = menuOpen || detailsOpen || pickerOpen || episodesOpen
    }

    // ---- the episode list (CP40) -------------------------------------------------------------

    /** What the episode list shows now, or null for a video the chat has not placed in a series. */
    private fun episodesState(): com.tmplayer.ui.player.EpisodesState? {
        val found = _episodes.value
        val series = found.series ?: return null
        return com.tmplayer.ui.player.EpisodesState(
            series = series,
            playing = currentItem(),
            previous = found.previous,
            previousLabel = found.previous?.let { episodeLabel(next = false, found.previousTag) },
            next = found.next,
            nextLabel = found.next?.let { episodeLabel(next = true, found.nextTag) },
            previousCode = found.previousTag?.code,
            nextCode = found.nextTag?.code,
            autoplay = autoplayNext,
        )
    }

    /** The row's Episodes button. The autoplay setting is read first, so the switch tells the truth. */
    private fun openEpisodes() {
        if (episodesState() == null || episodesOpen) return
        episodesOpen = true
        holdControls()
        lifecycleScope.launch {
            autoplayNext = runCatching { settings.autoplayNextNow() }.getOrDefault(true)
            val state = episodesState()
            if (state == null || !episodesOpen) {
                episodesOpen = false
                holdControls()
                return@launch
            }
            episodesList?.open(state)
        }
    }

    private fun refreshEpisodes() {
        episodesState()?.let { episodesList?.update(it) }
    }

    /** A row of the list, or a step at its top: through the same path as the next-episode button. */
    private fun playFromEpisodes(item: MediaItem) {
        episodesList?.close()
        if (item.id != currentItem().id) playEpisode(item)
    }

    /** A row's long press (a held OK on a television): marks that episode, or the one playing. */
    private fun toggleWatchedOf(item: MediaItem) {
        if (item.id == currentItem().id) {
            toggleWatched()
            return
        }
        if (item.chatId == 0L || item.messageId == 0L) return
        val store = watchedStore
        val title = chatTitle
        App.backgroundScope.launch {
            runCatching {
                if (store.isWatched(item.chatId, item.messageId)) {
                    store.markUnwatched(item.chatId, item.messageId)
                } else {
                    store.markWatched(WatchedRecord.of(item, title, System.currentTimeMillis(), manual = true))
                    settings.clearResumePosition(item.chatId, item.messageId, item)
                }
            }
        }
    }

    /** The list's autoplay switch: the same setting Settings shows. */
    private fun setAutoplayFromEpisodes(on: Boolean) {
        autoplayNext = on
        refreshEpisodes()
        lifecycleScope.launch { runCatching { settings.setAutoplayNext(on) } }
    }

    /** The transport row coming or going; the chips and the system bars ride with it. */
    fun onControlsVisibilityChanged(visible: Boolean) {
        controlsUp = visible
        gestures?.controlsVisible = visible
        // The system bars ride with the transport row, as they do in every video app on the
        // platform.
        setSystemBarsHidden(!visible)
        // The centre disc carries its own spinner while the row is up, so the bare one stands down.
        feedback?.controlsShown(visible)
        // The row going away hands the remote back to the next-up card, if that is up.
        if (!visible && nextUpHasTheRemote()) nextUpCard?.play?.requestFocus()
        liftSubtitles()
        liftCornerOffers()
        updateDownloadChip()
        if (!visible && !startOverOffered) showFirstRunHint()
    }

    /**
     * On a phone the transport row is taller than the television's, and the Skip intro pill and
     * the next-up card, which sit a fixed height off the bottom, landed on the timebar and the
     * total time in landscape. While the row is up they climb just clear of its top line, and
     * settle back once it goes. A television keeps them where they are: its row was measured
     * around them, and the remote never reaches them while it is up.
     */
    private fun liftCornerOffers() {
        // The "Back to" chip climbs on a television too: it comes up after a scrub on the bar,
        // with the row up, in the corner the row's title sits in.
        val tv = FormFactor.isTv(this)
        val cluster = findViewById<View>(R.id.controls_cluster)
        val root = findViewById<View>(R.id.player_root)
        val offers = if (tv) listOfNotNull(undoChip?.view) else listOfNotNull(skipPill?.view, nextUpCard?.view, undoChip?.view)
        offers.forEach { offer ->
            // After the next layout, so an offer that has only just been shown has a height.
            offer.doOnPreDraw {
                val origin = IntArray(2)
                val at = IntArray(2)
                root.getLocationInWindow(origin)
                var rowTop = Int.MAX_VALUE
                if (controlsUp) {
                    (cluster as? android.view.ViewGroup)?.let { group ->
                        for (i in 0 until group.childCount) {
                            val child = group.getChildAt(i)
                            if (child.visibility != View.VISIBLE || child.height == 0) continue
                            child.getLocationInWindow(at)
                            rowTop = minOf(rowTop, at[1] - origin[1] - child.translationY.toInt())
                        }
                    }
                }
                val gap = (8 * resources.displayMetrics.density).toInt()
                offer.getLocationInWindow(at)
                val bottom = at[1] - origin[1] - offer.translationY.toInt() + offer.height
                val lift = if (rowTop == Int.MAX_VALUE) 0 else (bottom + gap - rowTop).coerceAtLeast(0)
                offer.animate()
                    .translationY(-lift.toFloat())
                    .setDuration(SUBTITLE_LIFT_MS)
                    .start()
            }
        }
    }

    /**
     * Subtitles climb clear of the raised row rather than being covered by it, and settle back
     * once it goes. Only as far as the row actually reaches over them: a picture that stops well
     * above the row, as on a phone held upright, leaves them where they are. Posted so the first
     * raise measures a laid-out cluster rather than the zero height it had while gone.
     *
     * Never into the centre buttons, though: a phone held sideways has little room between the
     * bar and the transport, and two lines of large subtitles lifted clear of the bar landed in
     * the middle of the picture among the buttons. The climb stops two lines' height below the
     * centre cluster (one on a television, whose centre is the lone play disc), and only where
     * even the least climb that clears the row does not fit do they stand down while the row is
     * up. A television keeps its 48 dp off the edge while the row is down, and while it is up sits
     * the lines just over the row's top line rather than hiding them ([SubtitleLift]).
     */
    private fun liftSubtitles() {
        val cluster = findViewById<View>(R.id.controls_cluster)
        cluster.post {
            val tv = FormFactor.isTv(this)
            val place = SubtitleLift.place(
                controlsUp = controlsUp,
                pickerOpen = pickerOpen,
                textBottom = subtitleTextBottom(),
                rowTop = rowTop(cluster),
                reach = cluster.height - subtitleGapBelow,
                room = roomUnderCentre(lines = if (tv) 1 else 2),
                gap = (8 * resources.displayMetrics.density).toInt(),
                tv = tv,
            )
            for (view in listOfNotNull(subtitleView, subtitleTwin)) {
                view.animate()
                    .alpha(if (place.hidden) 0f else 1f)
                    .translationY(-place.lift.toFloat())
                    .setDuration(SUBTITLE_LIFT_MS)
                    .start()
            }
        }
    }

    /**
     * The top line of the raised row in the player's root, in pixels: the highest of the cluster's
     * showing children, less any slide they are part way through. Null when none is showing.
     */
    private fun rowTop(cluster: View): Int? {
        val group = cluster as? android.view.ViewGroup ?: return null
        if (cluster.visibility != View.VISIBLE) return null
        val root = findViewById<View>(R.id.player_root)
        val origin = IntArray(2)
        val at = IntArray(2)
        root.getLocationInWindow(origin)
        var top: Int? = null
        for (i in 0 until group.childCount) {
            val child = group.getChildAt(i)
            if (child.visibility != View.VISIBLE || child.height == 0) continue
            child.getLocationInWindow(at)
            val y = at[1] - origin[1] - child.translationY.toInt() - cluster.translationY.toInt()
            top = minOf(top ?: y, y)
        }
        return top
    }

    /**
     * Where the subtitles' lowest line draws at rest, in the player's root: the view's top, less
     * its climb, plus its height less the bottom padding fraction.
     */
    private fun subtitleTextBottom(): Int {
        val root = findViewById<View>(R.id.player_root)
        val origin = IntArray(2)
        val at = IntArray(2)
        root.getLocationInWindow(origin)
        subtitleView.getLocationInWindow(at)
        val height = subtitleView.height.takeIf { it > 0 } ?: subtitleView.layoutParams?.height?.coerceAtLeast(0) ?: 0
        val viewTop = at[1] - origin[1] - subtitleView.translationY.toInt()
        return (viewTop + height * (1f - subtitleBottomFraction())).toInt()
    }

    /**
     * How far subtitles can climb before [lines] lines of them would reach the centre cluster, in
     * pixels, or null when the cluster is not showing. Measured from where the view draws its
     * bottom line, with each line about 1.25 times the fractional text size.
     */
    private fun roomUnderCentre(lines: Int): Int? {
        val centre = findViewById<View>(R.id.controls_center)
        if (centre.visibility != View.VISIBLE || centre.height == 0) return null
        val root = findViewById<View>(R.id.player_root)
        val at = IntArray(2)
        val origin = IntArray(2)
        root.getLocationInWindow(origin)
        centre.getLocationInWindow(at)
        val centreBottom = at[1] - origin[1] + centre.height
        val height = subtitleView.height
        val textBottom = subtitleTextBottom()
        val reserved = 1.25f * lines * subtitleTextFraction() * height
        val gap = 8 * resources.displayMetrics.density
        return (textBottom - reserved - gap - centreBottom).toInt()
    }

    /**
     * The corner figure: how much of the video is down, shown only alongside the transport row.
     *
     * It gives way to the rebuffering chip, which occupies the same corner and is the more urgent
     * of the two, and it stays off entirely while a full-screen status sheet is up.
     *
     * A video that is all here says nothing. Complete usually means the watch cache has the whole
     * file, not that it is in Downloads, and "Download completed" pinned to every raise of the row
     * for the rest of the film read as a download that had never been asked for.
     *
     * While the watch cache fills it shows only when the data ahead runs short ([LoadingChip]):
     * "Loading 32%" over a video playing smoothly read as buffering that was not there.
     */
    private fun updateDownloadChip() {
        val exo = player
        val aheadMs = if (exo != null && exo.duration != C.TIME_UNSET) (exo.bufferedPosition - exo.currentPosition).coerceAtLeast(0) else -1L
        cacheChipWanted = LoadingChip.shows(cacheChipWanted, aheadMs, isRealDownload())
        val show = controlsUp &&
            statusOverlay.visibility != View.VISIBLE &&
            rebufferChip.visibility != View.VISIBLE &&
            fileSizeBytes > 0 &&
            !downloadComplete &&
            cacheChipWanted
        downloadChip.visibility = if (show) View.VISIBLE else View.GONE
        if (!show) return

        val percent = Translator.messages.formatter.percent(downloadedFraction.toDouble())
        val rate = Translator.messages.formatter.speed(speed.bytesPerSec)
        val moving = speed.bytesPerSec >= StreamStats.MIN_MEANINGFUL_SPEED
        downloadChip.text = when {
            isRealDownload() && moving -> L.playerDownloadedPercentSpeed(percent, rate)
            isRealDownload() -> L.playerDownloadedPercent(percent)
            moving -> L.playerCachingPercentSpeed(percent, rate)
            else -> L.playerCachingPercent(percent)
        }
    }

    /**
     * Whether the bytes coming down are a download the viewer asked for (in Downloads, or queued
     * for it) rather than the watch cache filling for playback. Only a real download says
     * "downloaded"; the cache says caching, or a viewer reads it as a download they never started.
     */
    private fun isRealDownload(): Boolean =
        downloadedFile != null || (fileId > 0 && OfflineDownloads.isDownloading(fileId))

    /**
     * Puts the video's own artwork behind the wait.
     *
     * Two pictures from two places, because they arrive at two different times. The minithumbnail
     * travels inside the message and is already in memory, so it is on screen in the first frame,
     * blown up across the whole panel where its forty-odd pixels read as a blur. The real thumbnail
     * is a file Telegram has to be asked for, so it lands a moment later, sharp, in the poster.
     *
     * A video with no thumbnail at all gets the plain sheet: everything here stays hidden until
     * there is something to draw. A video opened without its pictures (Continue watching keeps
     * none) gets them from the message once it is read again ([onPostRead]).
     */
    private fun showArtwork(
        miniBytes: ByteArray? = intent.getByteArrayExtra(EXTRA_MINI_THUMBNAIL),
        thumbnailId: Int = intent.getIntExtra(EXTRA_THUMBNAIL_ID, 0),
    ) {
        val mini = Thumbnails.mini(miniBytes)?.asAndroidBitmap()
        if (mini == null && thumbnailId <= 0) return
        artworkShown = true
        if (mini != null) {
            statusArt?.setImageBitmap(mini)
            statusPoster?.setImageBitmap(mini)
            scrubArt = mini
            revealArtwork()
        }

        if (thumbnailId <= 0) return
        itemScope.launch {
            val frame = runCatching { Thumbnails.full(thumbnailId) }.getOrNull() ?: return@launch
            // The loader takes the thumbnail only when it is shaped like a frame of the film. A
            // square or upright one is, as a rule, the channel's logo, and a logo blown up across
            // the screen is exactly what the loader is not to show.
            if (frame.height > 0 && frame.width.toFloat() / frame.height >= REAL_FRAME_ASPECT) {
                loaderHost?.thumbnail = frame
            }
            val full = frame.asAndroidBitmap()
            statusPoster?.setImageBitmap(full)
            scrubArt = full
            // Where there was a minithumbnail the backdrop keeps it: stretching a real thumbnail
            // across 1080p is a soft, ugly photograph, while stretching a forty-pixel one is a
            // blur. Where there was not, a soft backdrop still beats a flat sheet.
            if (mini == null) statusArt?.setImageBitmap(full)
            revealArtwork()
            // The notification's and the lock screen's picture too: an image encode, which a
            // television leaves until the video is up ([afterOpening]).
            afterOpening()
            sessionArtwork = withContext(Dispatchers.Default) { artworkOf(full) }
            refreshSessionMetadata()
        }
    }

    /**
     * [bitmap] as PNG bytes for the session's artwork: Telegram's thumbnail as it came, which is
     * already the size a notification draws.
     */
    private fun artworkOf(bitmap: android.graphics.Bitmap): ByteArray? = runCatching {
        java.io.ByteArrayOutputStream().use { out ->
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out)
            out.toByteArray()
        }
    }.getOrNull()

    /**
     * Brings the artwork up, and keeps the backdrop moving while the viewer waits.
     *
     * The drift is slow enough to be felt rather than watched: a still frame under a progress bar
     * reads as a screen that has hung. Two scale properties on one view, so the compositor does it.
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
        // Under the loader nobody sees the old sheet's drift; it would only cost frames.
        if (artDrift == null && loaderHost?.shown != true) {
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
     * The sheet stacked or in two columns, for the screen it is on now ([StatusSheetShape]).
     *
     * The sheet chooses by the width it is measured at, which is the space it really has; all the
     * player adds is [sheetGoesSideways], for the phone whose width cannot be believed.
     */
    private fun layOutSheetForThisScreen() {
        findViewById<StatusSheet>(R.id.status_sheet)?.askedSideways = sheetGoesSideways()
    }

    /**
     * Whether the player has itself asked for the screen sideways, which puts the poster beside
     * the words whatever width the sheet is measured at.
     *
     * The width alone is not enough on every phone: some present a portrait window rotated onto a
     * landscape panel, so the views, window bounds, display metrics, rotation and
     * Configuration.orientation all answer a 393dp portrait while the sheet is plainly drawn
     * sideways. What the player asked for is the one signal that phone cannot get wrong, and the
     * player opens sideways by default. Following the phone's own rotation, or held upright, the
     * measured width decides: side by side from 560dp, stacked with a smaller poster below that.
     */
    private fun sheetGoesSideways(): Boolean =
        !FormFactor.isTv(this) && orientation == ScreenOrientation.Landscape

    private fun showStatus(message: String) {
        openingFilm = true
        slowStartUp = false
        watchOpening()
        layOutSheetForThisScreen()
        statusOverlay.visibility = View.VISIBLE
        wakeEndWatch()
        statusIcon.visibility = View.GONE
        rebufferChip.visibility = View.GONE
        feedback?.buffering(false)
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
        renderLoaderStatus()
    }

    /** Puts the loader up or takes it down with [openingFilm]. */
    private fun syncLoader() {
        val host = loaderHost ?: return
        if (openingFilm) {
            if (!host.shown) {
                // A new wait: the line starts over and counts this opening's bytes only.
                opening = OpeningProgress(seeks = resumeMs > 0)
                openingBytesAt = readBytes.get()
                readSpeed.reset()
                host.progress = 0f
                loaderTick?.cancel()
                loaderTick = lifecycleScope.launch {
                    while (true) {
                        tickLoader()
                        delay(LOADER_TICK_MS)
                    }
                }
            }
            host.show(SystemClock.elapsedRealtime())
            stopArtDrift()
            renderLoaderStatus()
        } else {
            loaderTick?.cancel()
            loaderTick = null
            // The first frame (or a sheet underneath): the line full as it fades.
            host.progress = opening.finish()
            host.hide()
        }
    }

    /**
     * The loader's line from what Media3 and the data sources say: bytes handed to the
     * extractor, whether the container is read, whether the resume point has its first samples,
     * and the buffer ahead of it against [BUFFER_PLAYBACK_MS]. A whole-file download keeps its
     * own line, the file's share, from [renderProgress].
     */
    private fun tickLoader() {
        val host = loaderHost ?: return
        if (!openingFilm || waitingForWholeFilm) return
        val now = SystemClock.elapsedRealtime()
        val read = readBytes.get() - openingBytesAt
        readSpeed.sample(read, now)
        val exo = player
        val opened = exo != null && (exo.duration != C.TIME_UNSET || !exo.currentTracks.isEmpty)
        val ahead = if (exo != null && opened) (exo.bufferedPosition - exo.currentPosition).coerceAtLeast(0) else -1L
        opening.seeks = resumeMs > 0
        host.progress = opening.update(
            OpeningProgress.Signals(
                readBytes = read,
                onDisk = downloadComplete,
                opened = opened,
                seekDone = if (resumeMs > 0) opened && ahead > 0 else null,
                bufferedMs = ahead,
                bufferTargetMs = BUFFER_PLAYBACK_MS.toLong(),
                bytesPerSec = (StreamStats.bytesPerMs(fileSizeBytes, durationSec) * 1000).toLong(),
            ),
            now,
        )
        renderLoaderStatus()
    }

    /**
     * The loader's one status line and its slow-wait note. The stages that only mean "working"
     * (connecting, starting, loading) all read as the resume point or "Starting"; anything else
     * (offline, trying again, the next episode's name) is news and says itself. The speed joins
     * only once the wait has run past [LOADER_SLOW_MS], small and muted, and says caching unless
     * the bytes are a real download.
     */
    private fun renderLoaderStatus() {
        val host = loaderHost ?: return
        val routine = statusMessage.isBlank() || statusMessage == L.playerConnecting ||
            statusMessage == L.playerStarting || statusMessage == L.commonLoading
        val startClock = resumeMs.takeIf { it > 0 }?.let { Translator.messages.formatter.clock(it) }
        host.status = if (routine) opening.words(L, startClock) else statusMessage
        val slow = SystemClock.elapsedRealtime() - host.shownAt > LOADER_SLOW_MS
        // TDLib's download speed, or the rate the player is being handed bytes where TDLib is
        // quiet (the start already on the disk from the warm-up).
        val rate = maxOf(speed.bytesPerSec, readSpeed.bytesPerSec)
        host.note = if (slow && rate >= StreamStats.MIN_MEANINGFUL_SPEED && !downloadComplete) {
            val figure = Translator.messages.formatter.speed(rate)
            val line = if (isRealDownload()) L.playerDownloadingRate(figure) else L.playerCachingRate(figure)
            // Telegram's own cap on an account without Premium (CP83): said, nothing offered.
            if (Td.speedLimitedNow()) "$line\n${L.playerSpeedLimited}" else line
        } else if (slow && !downloadComplete) {
            // Nothing measurable arriving yet: said quietly, long before the stall sheet's minute.
            L.connectSlowTitle
        } else {
            null
        }
    }

    /**
     * Sound alone has no picture, so the black where it would be carries the file's name and its
     * chat instead, centred, under the controls. Built once and kept; gone for a video.
     */
    private fun showAudioCard() {
        if (!isAudio) {
            audioCard?.visibility = View.GONE
            return
        }
        val root = findViewById<FrameLayout>(R.id.player_root)
        val card = audioCard ?: android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            gravity = android.view.Gravity.CENTER
            val density = resources.displayMetrics.density
            setPadding((32 * density).toInt(), 0, (32 * density).toInt(), 0)
            addView(android.widget.ImageView(context).apply {
                setImageResource(R.drawable.ic_audio_language)
                setColorFilter(android.graphics.Color.WHITE)
                alpha = 0.7f
                layoutParams = android.widget.LinearLayout.LayoutParams((72 * density).toInt(), (72 * density).toInt())
            })
            addView(android.widget.TextView(context).apply {
                tag = "title"
                setTextColor(android.graphics.Color.WHITE)
                textSize = 22f
                gravity = android.view.Gravity.CENTER
                setPadding(0, (16 * density).toInt(), 0, 0)
            })
            addView(android.widget.TextView(context).apply {
                tag = "detail"
                setTextColor(android.graphics.Color.argb(184, 255, 255, 255))
                textSize = 15f
                gravity = android.view.Gravity.CENTER
                setPadding(0, (6 * density).toInt(), 0, 0)
            })
            root.addView(this, 1, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            audioCard = this
        }
        card.findViewWithTag<android.widget.TextView>("title")?.text = mediaTitle
        card.findViewWithTag<android.widget.TextView>("detail")?.text = chatTitle
        card.visibility = View.VISIBLE
    }

    /** A stall after playback has begun: a small chip, so the video stays on screen. */
    private fun showRebuffering() {
        // The sheet saying nothing is arriving stays until bytes do, or the viewer answers it.
        if (slowStartUp || failurePinned) return
        openingFilm = false
        statusOverlay.visibility = View.GONE
        val offline = networkOffline && !downloadComplete
        // A phone waits the way phone players do, with a spinner in the middle of the picture: the
        // corner chip sat on top of the double tap's ripple, since every jump on a stream stalls
        // for a moment. Being offline is news rather than a wait, so that keeps its words.
        val phone = feedback.takeUnless { FormFactor.isTv(this) }
        if (phone != null && !offline) {
            rebufferChip.visibility = View.GONE
            phone.buffering(true)
        } else {
            feedback?.buffering(false)
            rebufferChip.visibility = View.VISIBLE
            rebufferText.text = if (offline) L.playerOfflineWaiting else L.commonLoading
        }
        updateDownloadChip()
        watchRebuffering()
    }

    /** See [rebufferWatch]. Ends with the stall, or once the sheet is up. */
    private fun watchRebuffering() {
        if (rebufferWatch?.isActive == true) return
        lastProgressAtMs = SystemClock.elapsedRealtime()
        lastBufferedMs = -1L
        rebufferWatch = lifecycleScope.launch {
            while (true) {
                delay(OPENING_WATCH_TICK_MS)
                val exo = player ?: return@launch
                if (openingFilm || exo.playbackState != Player.STATE_BUFFERING) return@launch
                val buffered = exo.bufferedPosition
                if (buffered > lastBufferedMs) {
                    if (lastBufferedMs >= 0) noteOpeningProgress()
                    lastBufferedMs = buffered
                }
                val local = downloadComplete || downloadedFile != null || (joinedSource != null && joinedAllLocal)
                // Offline has its own words on the chip, and carries on by itself once back.
                if (networkOffline) {
                    lastProgressAtMs = SystemClock.elapsedRealtime()
                    continue
                }
                if (OpeningStall.stalled(opening = true, local = local, lastProgressAtMs, SystemClock.elapsedRealtime())) {
                    stalledMidPlay = true
                    showSlowStart()
                    return@launch
                }
            }
        }
    }

    /**
     * @param retryable false for a failure no amount of trying again will change: a codec this
     *   device has not got, or a container nothing here can parse. Offering a button that is
     *   certain to fail again is worse than offering none.
     */
    private fun showError(message: String, retryable: Boolean = true, freeUp: Boolean = false) {
        failurePinned = true
        val beyond = beyondDevice
        beyondDevice = false
        // The opening is over, if not the way it was meant to be: the waiting lookups go ahead.
        openedGate.complete(Unit)
        // Out of sight the sheet waits for the viewer, and the notification says it is there.
        stopUnseen()
        failedRetryable = retryable
        freeUpOnFailure = freeUp
        slowStartUp = false
        openingFilm = false
        undoChip?.hide()
        keepScreenOn(false)
        statusOverlay.visibility = View.VISIBLE
        // The only state that earns the warning triangle, matching the error screens the rest of
        // the app already shows.
        statusIcon.visibility = View.VISIBLE
        rebufferChip.visibility = View.GONE
        feedback?.buffering(false)
        statusSpinner?.visibility = View.GONE
        statusProgress.visibility = View.GONE
        statusDetail.visibility = View.GONE
        layOutSheetForThisScreen()
        statusName?.apply {
            text = mediaTitle
            visibility = if (mediaTitle.isBlank()) View.GONE else View.VISIBLE
        }
        statusTitle.text = L.playerCantPlay
        statusText.text = message

        // One attempt button, not two: the app works out whether it means a plain retry or a
        // delete and a fresh fetch. See [reloadFromScratch].
        statusRetry?.visibility = View.GONE
        showFailureActions(retryable, offerOtherApp = !beyond)
        updateDownloadChip()
    }

    /** Puts the sheet back to the one button it had, for every state that is not a failure. */
    private fun hideFailureActions() {
        slowStartUp = false
        statusLogs?.visibility = View.GONE
        statusReload?.visibility = View.GONE
        statusOpenWith?.visibility = View.GONE
        statusBack?.visibility = View.GONE
        statusLighter?.visibility = View.GONE
        statusDecoder?.visibility = View.GONE
        statusName?.visibility = View.GONE
    }

    /** The track types this player already moved to the other decoder by itself ([switchDecoderByItself]). */
    private val decodersSwitched = mutableSetOf<Int>()

    /**
     * The first decoder failure of a picture or a soundtrack goes straight to the other decoder,
     * the way the failure sheet's "Try the other decoder" would, without the viewer having to read
     * a sheet first: a device decoder that claimed a format and then choked on it (a Hi10P film, a
     * profile the chip lists and cannot do), the FFmpeg decoder failing where the device's would
     * not, or a Dolby or DTS track whose passthrough AudioTrack the device refused. Once per track
     * type per player ([FormatRoute.switchesByItself]): a second failure brings the sheet, with
     * the button to go back. Never to software for a 4K picture on a weak device, which would
     * only stutter; the sheet says so instead.
     */
    private fun switchDecoderByItself(error: PlaybackException): Boolean {
        val plan = decoderPlan ?: return false
        if (player == null) return false
        val format = (error as? androidx.media3.exoplayer.ExoPlaybackException)?.rendererFormat
        val tooSlow = weakDevice && format != null && com.tmplayer.data.SoftwareDecode.unwatchable(
            format.sampleMimeType,
            format.width,
            format.height,
            hardware = false,
            weak = true,
        )
        if (!FormatRoute.switchesByItself(plan.trackType, plan.toFfmpeg, decodersSwitched, tooSlow, firstFrameReady)) return false
        decodersSwitched += plan.trackType
        Log.w(
            "TMPlayer",
            "Decoder failed (${error.errorCodeName}) on ${format?.sampleMimeType} ${format?.codecs.orEmpty()}: " +
                "switching to the ${if (plan.toFfmpeg) "FFmpeg" else "device"} decoder",
        )
        tryOtherDecoder()
        return true
    }

    /**
     * "Try the other decoder": the failed side's renderers off ([DecoderSwitch]), and the player
     * prepared again where it stood. A player that failed keeps its position, so nothing is lost.
     */
    private fun tryOtherDecoder() {
        val plan = decoderPlan ?: return
        val exo = player ?: return
        decoderPlan = null
        hideFailureActions()
        DecoderSwitch.apply(exo, plan)
        failurePinned = false
        showStatus(if (plan.toFfmpeg) L.playerDecoderSoftwareNow else L.playerDecoderDeviceNow)
        exo.prepare()
        exo.playWhenReady = true
    }

    /**
     * Gives the failure sheet's suggested way out, the one focus lands on, the accent pill, and the
     * others the quiet one, so the sheet always has exactly one button that reads as the answer.
     */
    private fun markPrimaryAction(primary: View) {
        for (button in listOf(statusReload, statusBack, statusOpenWith, statusLighter, statusDecoder)) {
            val chosen = button === primary
            button?.setBackgroundResource(if (chosen) R.drawable.bg_control_pill_primary else R.drawable.bg_control_pill)
            button?.setTextColor(getColorStateList(if (chosen) R.color.player_primary_text else R.color.player_control_icon))
        }
    }

    /**
     * Two buttons on a failure: the one that tries, and the one that leaves.
     *
     * Reload is the only attempt on offer, and what it does underneath depends on what is wrong: a
     * fresh prepare where that is all it takes, a delete and a fresh fetch where the bytes on disk
     * are broken. [Reload.plan] picks, and a video the viewer downloaded on purpose is never
     * deleted by it. Go back is the other button, since a device with no keys cannot be told to
     * press Back.
     *
     * On a failure no attempt can fix, a codec this device has not got, Reload steps aside and the
     * button becomes the handover to an app that does have it. That is settled from disk and so
     * arrives a moment after the sheet, which is readable immediately either way.
     */
    private fun showFailureActions(retryable: Boolean, offerOtherApp: Boolean = true) {
        statusReload?.visibility = View.GONE
        statusOpenWith?.visibility = View.GONE
        // Last and quietest: the logs to send with a report of it. See [rememberSendLogs].
        statusLogs?.visibility = View.VISIBLE
        statusBack?.apply {
            visibility = View.VISIBLE
            if (!retryable) {
                markPrimaryAction(this)
                requestFocus()
            }
        }
        // Not enough space: the way out is the screen that frees it, with the sheet already up.
        if (freeUpOnFailure) {
            statusOpenWith?.apply {
                text = L.downloadsFreeUpSpace
                visibility = View.VISIBLE
                markPrimaryAction(this)
                requestFocus()
            }
            return
        }
        // A decoder that failed, with the other one able to take the format: that is the answer,
        // ahead of Reload (the same bytes would fail the same way) and of another app.
        val switch = decoderPlan
        statusDecoder?.visibility = if (switch != null) View.VISIBLE else View.GONE
        if (switch != null) {
            statusDecoder?.apply {
                markPrimaryAction(this)
                requestFocus()
            }
            return
        }
        if (fileId <= 0) return
        lifecycleScope.launch {
            if (retryable) {
                statusReload?.apply {
                    text = L.commonReload
                    visibility = View.VISIBLE
                    markPrimaryAction(this)
                    requestFocus()
                }
            }

            val local = downloadedFile
            val availability = if (local != null) {
                LocalFileAvailability.Complete
            } else {
                runCatching { Td.localFileAvailability(fileId) }.getOrDefault(LocalFileAvailability.Missing)
            }
            val downloaded = local?.length()
                ?: runCatching { Td.localDownloadedBytes(fileId) }.getOrDefault(0L)
            val state = ExternalPlayer.readiness(availability, downloaded)
            if (state == ExternalPlayer.Readiness.Nothing) return@launch
            // Offered on the failure another app fixes, and left off the one it does not: a stream
            // that would not start has nothing on disk worth handing anywhere.
            if (retryable || !offerOtherApp) return@launch
            if (savable != true) return@launch
            statusOpenWith?.apply {
                text = if (state == ExternalPlayer.Readiness.Complete) {
                    L.playerOpenInAnotherApp
                } else {
                    L.playerOpenWhatIsDownloaded
                }
                visibility = View.VISIBLE
                markPrimaryAction(this)
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

    /**
     * The failure on screen is "Not enough space", and the sheet's second button is Free up space
     * rather than Open in another app. Set by [showError] each time, so it never outlives the
     * failure it was for.
     */
    private var freeUpOnFailure = false

    /**
     * The failure on screen is a picture beyond this device's decoders ([tooLargeForSoftware]), so
     * no other app plays it either and the sheet offers none. Cleared by [showError] each time.
     */
    private var beyondDevice = false

    /** Leaves for the Downloads screen with Free up space up, which is what the refusal offers. */
    private fun openFreeUpSpace() {
        val open = Intent(this, com.tmplayer.MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra(com.tmplayer.MainActivity.EXTRA_OPEN, com.tmplayer.MainActivity.OPEN_FREE_UP_SPACE)
        runCatching { startActivity(open) }
        finish()
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
     * The failure this exists for is a partial file that is wrong rather than merely short, over
     * which every plain retry fails identically. Deleting is safe because the copy is a cache:
     * TDLib fetches it again from the message it came from, which is untouched.
     *
     * A video the viewer downloaded on purpose, or one the download service is working on, falls
     * back to a plain retry, because those bytes are not this screen's to delete: see
     * [Reload.plan]. The player's own fetch is cancelled first, so TDLib is not writing into the
     * file as it is removed, and the entry comes off the downloads list as well as off the disk so
     * nothing resumes into the hole.
     */
    private fun reloadFromScratch() {
        if (reloading) return
        reloading = true
        hideFailureActions()
        statusRetry?.visibility = View.GONE
        lifecycleScope.launch {
            val plan = runCatching { reloadPlan() }.getOrDefault(Reload.Plan.PlainRetry)
            failurePinned = false
            showStatus(Reload.status(plan))
            if (plan == Reload.Plan.StartOver) {
                val session = Td.awaitAuthorizedSession()
                runCatching {
                    Td.cancelDownload(fileId)
                    // Off the downloads list as well as off the disk: an entry left behind is a
                    // TDLib record of a file it no longer has, which Downloads draws as an empty
                    // row.
                    session.client.removeFileFromDownloads(fileId, deleteFromCache = true)
                    Td.deleteFile(fileId)
                }
            }
            reloading = false
            // The player is thrown away rather than re-prepared: its media source still holds the
            // descriptor of the file just deleted, and preparing over that reads the bytes this
            // press was here to get rid of. The surface goes with it, so [startPlayback] does not
            // stack a second one over the first: see [releasePlayerAndSurface].
            if (plan == Reload.Plan.StartOver) releasePlayerAndSurface()
            retryPlayback()
        }
    }

    /**
     * Hands this video to VLC, MX Player, or whatever else the device has.
     *
     * The resume position is written first, so a viewer who leaves for another player and comes
     * back lands where they left. The background download is deliberately not cancelled: the other
     * app is reading the same file, and pulling the rest of it out from under it would be worse
     * than not offering this at all.
     */
    fun openInAnotherApp() {
        // The menus leave the line off; this catches the held key, which has no menu to leave it off.
        if (savable != true) {
            showGestureFeedback(if (savable == null) CHECKING_FEEDBACK else NOT_SAVABLE_FEEDBACK)
            return
        }
        saveResumePosition()
        lifecycleScope.launch {
            val local = downloadedFile
            val outcome = runCatching {
                if (local != null) {
                    ExternalPlayer.handOverFile(this@PlayerActivity, local.absolutePath, mediaTitle)
                } else {
                    ExternalPlayer.handOver(this@PlayerActivity, fileId, mediaTitle)
                }
            }.getOrElse { ExternalPlayer.Handoff.Refused(L.playerHandoffRefused) }
            when (outcome) {
                is ExternalPlayer.Handoff.Started -> outcome.caution?.let(::showGestureFeedback)
                ExternalPlayer.Handoff.NothingOnDisk ->
                    showGestureFeedback(L.playerHandoffNothingOnDisk)
                is ExternalPlayer.Handoff.Refused -> showGestureFeedback(outcome.reason)
            }
        }
    }

    /**
     * Starts the whole load again from where it stopped.
     *
     * A fresh prepare rather than a fresh activity: the position, the title, the download meter and
     * the episode search are already set up against this file, so there is nothing to rebuild.
     */
    private fun retryPlayback() {
        failedRetryable = false
        failurePinned = false
        statusRetry?.visibility = View.GONE
        recoveryAttempts = 0
        showStatus(L.playerTryingAgain)
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

    /**
     * Counts the opening wait while [openingFilm] holds, and once [OpeningStall.STALL_MS] has gone
     * by with no new byte puts up [showSlowStart]. TDLib does not fail a download it cannot begin,
     * so without this a blocked network left the loader on "Starting" for as long as anybody
     * cared to look. Bytes are read from TDLib's updates ([applyDownloadState]) and, for a video in
     * pieces whose other files that does not follow, from the player's own buffered position.
     * Not while the offline notice is up, which already says what is wrong and carries on alone.
     */
    private fun watchOpening() {
        if (openingWatch?.isActive == true) return
        lastProgressAtMs = SystemClock.elapsedRealtime()
        lastBufferedMs = -1L
        openingWatch = lifecycleScope.launch {
            while (true) {
                delay(OPENING_WATCH_TICK_MS)
                if (!openingFilm) return@launch
                val buffered = player?.bufferedPosition ?: -1L
                if (buffered > lastBufferedMs) {
                    if (lastBufferedMs >= 0) noteOpeningProgress()
                    lastBufferedMs = buffered
                }
                val local = downloadComplete || downloadedFile != null || (joinedSource != null && joinedAllLocal)
                if (networkOffline) {
                    noteOpeningProgress()
                    continue
                }
                if (OpeningStall.stalled(opening = true, local = local, lastProgressAtMs, SystemClock.elapsedRealtime())) {
                    showSlowStart()
                    return@launch
                }
            }
        }
    }

    /** A byte arrived: the count starts again, and a sheet that said nothing was arriving goes. */
    private fun noteOpeningProgress() {
        lastProgressAtMs = SystemClock.elapsedRealtime()
        if (slowStartUp && stalledMidPlay) {
            // Bytes again in the middle of a video: back to the small spinner, not "Starting".
            stalledMidPlay = false
            slowStartUp = false
            statusOverlay.visibility = View.GONE
            hideFailureActions()
            showRebuffering()
        } else if (slowStartUp) {
            showStatus(L.playerStarting)
        }
    }

    /**
     * Nothing has arrived for the opening in [OpeningStall.STALL_MS]: the sheet says why, in the
     * words the line to Telegram earns, and offers Try again (the focused one), Download first
     * where the chat allows keeping a copy, and Back. Bytes arriving after all, or the first frame,
     * put the ordinary flow back.
     */
    private fun showSlowStart() {
        openingFilm = false
        statusOverlay.visibility = View.VISIBLE
        statusIcon.visibility = View.GONE
        rebufferChip.visibility = View.GONE
        feedback?.buffering(false)
        statusSpinner?.visibility = View.GONE
        statusProgress.visibility = View.GONE
        statusRetry?.visibility = View.GONE
        hideFailureActions()
        slowStartUp = true
        layOutSheetForThisScreen()
        statusName?.apply {
            text = mediaTitle
            visibility = if (mediaTitle.isBlank()) View.GONE else View.VISIBLE
        }
        statusTitle.text = if (stalledMidPlay) L.playerStalledMidTitle else L.playerStalledTitle
        statusText.text = when (OpeningStall.reason(Td.link.value)) {
            OpeningStall.Reason.NoLine -> L.playerStalledNoLine
            OpeningStall.Reason.Slow -> if (stalledMidPlay) L.playerStalledMidSlow else L.playerStalledSlow
        }
        val canDownload = canSaveToDownloads()
        // Telegram's lighter copy (CP80), and Telegram's own cap on an account without Premium
        // (CP83), each said where it applies.
        val lighter = if (OpeningStall.reason(Td.link.value) == OpeningStall.Reason.Slow) lighterQuality() else null
        val limited = Td.speedLimitedNow()
        val detail = listOfNotNull(
            L.playerSpeedLimited.takeIf { limited },
            L.playerLighterHint.takeIf { lighter != null },
            L.playerStalledDownloadHint.takeIf { canDownload },
        ).joinToString("\n")
        statusDetail.visibility = if (detail.isNotEmpty()) View.VISIBLE else View.GONE
        statusDetail.text = detail
        statusLighter?.apply {
            text = lighter?.let { L.playerLighter(com.tmplayer.data.TelegramQualities.label(it.height)) }.orEmpty()
            visibility = if (lighter != null) View.VISIBLE else View.GONE
        }
        statusBack?.visibility = View.VISIBLE
        statusOpenWith?.apply {
            text = L.playerDownloadFirst
            visibility = if (canDownload) View.VISIBLE else View.GONE
        }
        statusReload?.apply {
            text = L.commonTryAgain
            visibility = View.VISIBLE
            markPrimaryAction(this)
            requestFocus()
        }
        updateDownloadChip()
    }

    /**
     * Try again on [showSlowStart]: the connections to Telegram reopened ([Td.retryConnecting])
     * and the stream opened afresh. A plain prepare does nothing to a player that is still
     * buffering; a stop and a prepare closes the data source and opens it again, which asks TDLib
     * for the bytes once more, from where the player stands.
     */
    private fun tryOpeningAgain() {
        slowStartUp = false
        stalledMidPlay = false
        recoveryAttempts = 0
        App.backgroundScope.launch { runCatching { Td.retryConnecting() } }
        showStatus(L.playerTryingAgain)
        val exo = player
        if (exo == null) {
            retryPlayback()
            return
        }
        exo.stop()
        exo.prepare()
        exo.playWhenReady = true
    }

    /** Download first on [showSlowStart]: the video joins the download queue and the player steps back. */
    private fun downloadFirst() {
        if (!canSaveToDownloads()) return
        OfflineDownloads.start(this, mediaItemForCache().copy(fileId = uploadId(), sizeBytes = uploadSize()), chatTitle)
        android.widget.Toast.makeText(applicationContext, L.playerDownloadFirstStarted, android.widget.Toast.LENGTH_LONG).show()
        finish()
    }

    private fun hideStatus() {
        slowStartUp = false
        stalledMidPlay = false
        failedRetryable = false
        openingFilm = false
        statusOverlay.visibility = View.GONE
        wakeEndWatch()
        stopArtDrift()
        rebufferChip.visibility = View.GONE
        feedback?.buffering(false)
        updateDownloadChip()
    }

    /**
     * Writes the position every [RESUME_TICK_MS] while a video is on screen.
     *
     * onStop covers Back and Home, but it never runs when the power goes off at the wall or the
     * system kills the process to reclaim memory, and on a 1 GB stick the second is routine.
     * Started rather than resumed: in picture in picture the activity is paused while the video
     * plays on, and an hour watched in the corner went unwritten.
     */
    private fun startResumeHeartbeat() {
        lifecycleScope.launch {
            while (true) {
                delay(RESUME_TICK_MS)
                // And with the sound playing on out of sight, which is an hour as easily.
                val seen = lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
                if ((seen || inBackgroundAudio) && player?.isPlaying == true) saveResumePosition()
                // And while it is on a TV, with the phone in a pocket: playing, paused there or
                // between streams, the TV's place is the one kept.
                else if (cast?.casting == true) saveResumePosition()
            }
        }
    }

    /**
     * "Ask when the app opens" (CP57). Leaving the app locks the session, but only the main
     * activity draws the pad, so a task brought back from Recents with the player on top would
     * play on without it. The player steps aside instead, and the pad behind it asks.
     */
    override fun onStart() {
        super.onStart()
        if (openingFilm) watchOpening()
        // Back in front: the session comes home from the notification, the sound never stopped,
        // and the picture comes back on (turned off only while nobody could see it).
        endBackgroundAudio()
        showVideoAgain()
        PlaybackService.clearStopped(this)
        // It stopped out of sight for a reason that was not the viewer's ([stopUnseen]), and the
        // notification promised a tap would carry on: a failure is tried again, a pause plays.
        // A question the next episode asks (mobile data, room) is already on screen waiting.
        if (resumeWhenSeen) {
            resumeWhenSeen = false
            val exo = player
            if (failedRetryable) {
                retryPlayback()
            } else if (exo != null && statusOverlay.visibility != View.VISIBLE && exo.playbackState != Player.STATE_ENDED) {
                exo.play()
            }
        }
        if (com.tmplayer.data.AppLockSession.unlocked.value) return
        lifecycleScope.launch {
            val askOnOpen = runCatching { settings.appLock.first().onOpen }.getOrDefault(false)
            if (askOnOpen && !com.tmplayer.data.AppLockSession.unlocked.value && !isFinishing) {
                player?.pause()
                finish()
            }
        }
    }

    override fun onStop() {
        super.onStop()
        // Counted again from the start when the viewer is back ([onStart]).
        openingWatch?.cancel()
        rebufferWatch?.cancel()
        saveResumePosition(leaving = !inPictureInPicture)
        // This place out to the other devices, once the saves have settled (ResumeSync).
        com.tmplayer.data.ResumeSync.pushSoon(settings)
        // On a TV the video carries on with the phone put away; nothing here to pause.
        if (cast?.casting == true) return
        // In picture in picture the activity is stopped while the video is still on screen and
        // still the point of it; pausing here would stop the very thing the mode exists for.
        // With background audio on, the sound carries on from the notification instead.
        if (inPictureInPicture) return
        if (keepsPlayingUnseen()) startBackgroundAudio() else player?.pause()
    }

    override fun onDestroy() {
        com.tmplayer.data.SelfInstaller.removeBeforeInstall(saveBeforeInstall)
        loopHandler.removeCallbacks(watchTick)
        // Every runnable on this handler, the undo chip's expiry among them: a delayed one holds
        // the whole activity until it fires.
        loopHandler.removeCallbacksAndMessages(null)
        stopArtDrift()
        saveResumePosition(leaving = true)
        // After the write above, which took the TV's place while the cast was still up.
        cast?.release()
        cast = null
        controls?.thumbnails = null
        trickplay?.release()
        trickplay = null
        storyboardFrames?.release()
        storyboardFrames = null
        holdFile(0)
        stopDownload()
        trimCache()
        // The notification goes before the session it shows.
        endBackgroundAudio()
        // Released before the player it wraps, or it is left holding a released instance.
        mediaSession?.release()
        mediaSession = null
        // Dropped before the player is released so the view never holds a released instance.
        touchSurface?.player = null
        touchSurface = null
        player?.removeListener(playerListener)
        player?.release()
        player = null
        // Only once the player, and the reads it had waiting, are gone: a read that asked TDLib
        // again after [stopDownload] would otherwise have asked for the rest of the film unbounded,
        // and went on fetching with nobody watching. Asked again whatever the stream's bounds (it
        // used to be only for Low storage mode and "Save mobile data"); the byte window refuses
        // any request once closed as well (TdByteWindow.shut).
        letGoOfStreamBounds()
        stopDownload()
        super.onDestroy()
    }

    /**
     * Tells TDLib to stop fetching this video once nobody is watching it.
     *
     * Streaming asks for everything from the current byte to the end of the file, and TDLib honours
     * that long after the activity has gone, filling the disk with a remux nobody is watching. A
     * finished file is left alone: there is nothing to cancel, and the bytes on disk are what makes
     * offline playback work.
     */
    private fun stopDownload() {
        stopPieceDownloads()
        if (downloadComplete) return
        val id = fileId
        if (id <= 0) return
        // Not this one: a download the viewer asked to keep is being fetched by the service, and
        // closing a player that happened to be watching the same file is not a reason to abandon
        // it. Only the bytes this screen pulled in for itself are the player's to cancel.
        if (OfflineDownloads.isDownloading(id)) return
        App.backgroundScope.launch {
            // Opened again straight away: the new player's download is the same file, and a late
            // cancel would stop it.
            if (WatchCache.isPlaying(id)) return@launch
            runCatching { Td.cancelDownload(id) }
        }
    }

    /** The file this player has told [WatchCache] it is playing, so the sweep leaves it be. */
    private var heldFileId = 0

    private fun holdFile(id: Int) {
        if (id == 0) holdPieces(emptyList())
        if (id == heldFileId) return
        WatchCache.stoppedPlaying(heldFileId)
        WatchCache.startedPlaying(id)
        heldFileId = id
    }

    /**
     * Puts the cache back under its ceiling on the way out of a video.
     *
     * Leaving a video is the natural moment: something has just been fetched, and nothing is
     * playing that a deletion could interrupt. Trimming only at launch would let an evening of
     * episodes end well over the ceiling.
     */
    private fun trimCache() {
        App.backgroundScope.launch {
            runCatching { Td.trimStorage() }
            // And the videos with no owner, which the ceiling trim deliberately leaves alone.
            runCatching { WatchCache.sweep(applicationContext) }
        }
    }

    /**
     * Runs on a scope that outlives the activity, because the write has to survive the Back press
     * that triggered it.
     *
     * Past 90 % the position is forgotten on every write, but the video goes on the Watched list
     * only when [leaving]: a mark is what "Remove after watching" deletes a download on, and the
     * last tenth of a film is still being watched.
     */
    /** [saveResumePosition] before an update ends the process, waited for until it is written. */
    private val saveBeforeInstall: suspend () -> Unit = {
        withContext(Dispatchers.Main) { saveResumePosition() }?.join()
    }

    /** Writes the place; returns the write, for a caller that must wait for it ([saveBeforeInstall]). */
    private fun saveResumePosition(leaving: Boolean = false): Job? {
        if (savedForHandOver) return null
        val exo = player ?: return null
        // While the video is on a TV, the TV's place is the one to keep.
        val castAt = cast?.positionMs
        val position = castAt ?: exo.currentPosition
        val duration = if (castAt != null) cast?.durationMs ?: exo.duration else exo.duration
        // Watched from 90 %, and the position then forgotten; see [ResumeRules]. Never for a video
        // whose pieces stop at a gap: the end of what is here is not the end of the video.
        val watched = ResumeRules.watched(position, duration) && !piecesIncomplete
        val store = settings
        val chat = chatId
        val message = messageId
        val here = currentItem()
        // Written with the position so "Continue watching" can offer the video back without the
        // chat it came from being loaded, or still being in the list at all.
        val description = ResumeRecord.encode(
            fileId = uploadId(),
            title = mediaTitle,
            chatTitle = chatTitle,
            sizeBytes = uploadSize(),
            durationSec = durationSec,
            updatedAt = System.currentTimeMillis(),
            // The tracks and speed go with the position. A restore still waiting on the track
            // list is what this video is meant to play with, so that is written, not the defaults.
            state = resumeTracks?.copy(speed = playbackSpeed) ?: resumeState(exo),
            // So the record can be told from other videos' and matched to this one's copies.
            fileName = mediaFileName,
            caption = mediaCaption,
        )
        if (watched && leaving && !watchedHere) {
            watchedHere = true
            recordFinished()
        }
        val forget = watched || markedWatchedHere
        val write = App.backgroundScope.launch {
            runCatching {
                if (forget) store.clearResumePosition(chat, message, here)
                else store.saveResumePosition(chat, message, position, duration, description)
            }
        }
        if (leaving) publishWatchNext(if (markedWatchedHere && duration > 0) duration else position, duration)
        return write
    }

    /**
     * The home screen's "Play next" row, when the viewer turned it on and the launcher has one.
     * Only on the way out of a video: the row is read from the home screen, which is not on
     * screen while the video is. A video from a chat that restricts saving stays off it, along
     * with its name.
     */
    private fun publishWatchNext(position: Long, duration: Long) {
        if (!hasMessage() || !remoteQuirks.watchNextSupported || savable == false) return
        val video = WatchNext.Video(chatId, messageId, fileId, mediaTitle, chatTitle, fileSizeBytes, durationSec)
        val next = _episodes.value.next?.let { WatchNext.Video.of(it, chatTitle) }
        // A copy switched away from leaves the row, so the video keeps one entry.
        val otherCopies = copies.filter { it.id != currentItem().id && it.chatId != 0L }.map { WatchNext.Video.of(it, copyChatTitle(it)) }
        val store = settings
        val context = applicationContext
        App.backgroundScope.launch {
            runCatching {
                val changes = WatchNext.changesFor(
                    video = video,
                    positionMs = position,
                    durationMs = duration,
                    nowMs = System.currentTimeMillis(),
                    next = next,
                    enabled = store.watchNextNow(),
                    supported = true,
                    otherCopies = otherCopies,
                )
                WatchNextPublisher.apply(context, changes)
            }
        }
    }

    /** Whether this video came from a message, which is what the Watched list is keyed by. */
    private fun hasMessage(): Boolean = chatId != 0L && messageId != 0L

    /** This video as the Watched list keeps it, with the player's length when the message had none. */
    private fun watchedRecord(manual: Boolean): WatchedRecord {
        val known = durationSec.takeIf { it > 0 }
            ?: player?.duration?.takeIf { it > 0 }?.let { (it / 1_000).toInt() }
            ?: 0
        return WatchedRecord.of(
            item = mediaItemForCache().copy(durationSec = known),
            chatTitle = chatTitle,
            watchedAt = System.currentTimeMillis(),
            manual = manual,
        )
    }

    /**
     * Puts the video on the Watched list because playback reached the end. On the scope that
     * outlives the activity, for the reason [saveResumePosition] is: the end is often followed by
     * Back straight away.
     */
    private fun recordFinished() {
        if (!hasMessage()) return
        onWatchedList = true
        val record = watchedRecord(manual = false)
        val store = watchedStore
        App.backgroundScope.launch { runCatching { store.markWatched(record) } }
    }

    /** The menus' "Mark as watched" and "Mark as unwatched". */
    private fun toggleWatched() {
        if (!hasMessage()) return
        val store = watchedStore
        val chat = chatId
        val message = messageId
        if (onWatchedList) {
            onWatchedList = false
            markedWatchedHere = false
            App.backgroundScope.launch { runCatching { store.markUnwatched(chat, message) } }
            showGestureFeedback(L.playerMarkedUnwatched)
        } else {
            onWatchedList = true
            markedWatchedHere = true
            val record = watchedRecord(manual = true)
            App.backgroundScope.launch {
                runCatching {
                    store.markWatched(record)
                    settings.clearResumePosition(chat, message, currentItem())
                }
            }
            showGestureFeedback(L.playerMarkedWatched)
        }
    }

    companion object {
        private const val EXTRA_FILE_ID = "file_id"

        /** `SystemClock.elapsedRealtime()` when Play was pressed, for the start timing log. */
        private const val EXTRA_PRESSED_AT = "pressed_at"

        /** The log tag of the start and seek timings. */
        private const val START_TAG = "PlayerStart"

        /** The opening's stages, one bit each, for [markStart]. */
        private const val STAGE_SESSION = 1
        private const val STAGE_DECIDED = 2
        private const val STAGE_BUILT = 4
        private const val STAGE_PREPARED = 8
        private const val STAGE_BYTES = 16
        private const val STAGE_TRACKS = 32
        private const val STAGE_READY = 64

        /** The longest a television holds the lookups back for a picture that is slow to come. */
        private const val OPENING_GATE_MS = 15_000L

        /** How long the player waits for a saved position still being read before it starts anyway. */
        private const val RESUME_WAIT_MS = 400L

        /**
         * How far another device's place, or a late post start, has to be from where the video
         * opened before the player seeks to it, and how far the viewer has to have moved for it
         * to be left alone. A seek restarts TDLib's download, so a near agreement is not worth one.
         */
        private const val RESUME_SYNC_SLACK_MS = 10_000L
        private const val EXTRA_CHAT_ID = "chat_id"
        private const val EXTRA_MESSAGE_ID = "message_id"
        private const val EXTRA_TITLE = "title"
        private const val EXTRA_SUBTITLE = "subtitle"
        private const val EXTRA_FILE_NAME = "file_name"
        private const val EXTRA_CAPTION = "caption"
        private const val EXTRA_TOPIC_ID = "topic_id"

        /** A t.me link's `?t=` in seconds (CP80), for a player opened from a link. */
        const val EXTRA_LINK_START_SEC = "link_start_sec"

        /**
         * The file id the screen that started the player already checked for room ([RoomOnDisk]),
         * so the player does not measure the disk a second time for the same press.
         */
        const val EXTRA_ROOM_DECIDED = "room_decided"
        private const val EXTRA_SIZE = "size"
        private const val EXTRA_DURATION = "duration"
        private const val EXTRA_CHAT_TITLE = "chat_title"
        private const val EXTRA_THUMBNAIL_ID = "thumbnail_id"
        private const val EXTRA_MINI_THUMBNAIL = "mini_thumbnail"
        private const val EXTRA_CAN_BE_SAVED = "can_be_saved"
        private const val EXTRA_MIME = "mime_type"
        private const val EXTRA_QUALITY_FILES = "quality_files"
        private const val EXTRA_QUALITY_WIDTHS = "quality_widths"
        private const val EXTRA_QUALITY_HEIGHTS = "quality_heights"
        private const val EXTRA_QUALITY_CODECS = "quality_codecs"
        private const val EXTRA_QUALITY_SIZES = "quality_sizes"
        private const val EXTRA_OUTSIDE_APP_TASK = "outside_app_task"
        /** How long the note about a missing piece stays up as the video starts. */
        private const val PIECES_NOTICE_MS = 6_000L

        /** How long the lines about software decoding stay, a sentence longer than a usual flash. */
        private const val SOFTWARE_NOTE_MS = 7_000L

        /** The watchdog's sample: once a second while playing ([PlaybackWatch]). */
        private const val WATCH_TICK_MS = 1_000L

        /** How long "Stuttering? Switch" and the passed-over line stay up. */
        private const val STUTTER_OFFER_MS = 12_000L
        private const val PASSED_OVER_MS = 6_000L

        /** How long the word that a file cannot be sought in stays up. */
        private const val SEEK_NOTICE_MS = 6_000L
        private const val EXTRA_PIECE_MESSAGES = "piece_messages"
        private const val EXTRA_PIECE_FILES = "piece_files"
        private const val EXTRA_PIECE_SIZES = "piece_sizes"
        private const val EXTRA_PIECE_NAMES = "piece_names"

        /** Said when a press asks for a copy of a video Telegram does not let leave the app. */
        private val NOT_SAVABLE_FEEDBACK: String get() = L.playerNotSavable

        /** Said for the same press in the moment before Telegram has answered whether it may. */
        private val CHECKING_FEEDBACK: String get() = L.playerCheckingSavable

        private const val BUFFER_MIN_MS = 15_000
        private const val BUFFER_MAX_MS = 50_000
        /**
         * What has to be buffered before the first frame plays: 1 s, down from Media3's 2.5 s,
         * since TDLib keeps filling at its own pace whatever the player holds and a stream that
         * cannot keep a second ahead is rebuffering either way. Device check: a slow line should
         * start sooner, not stutter more (see the start timing log, PlayerStart).
         */
        private const val BUFFER_PLAYBACK_MS = 1_000

        /** After a stall, more than the start asks, so a struggling line does not stall again at once. */
        private const val BUFFER_REBUFFER_MS = 4_000
        /** The buffer on a low-RAM device: a 1 GB stick. */
        private const val LOW_RAM_BUFFER_BYTES = 20 * 1024 * 1024

        /** The buffer elsewhere, by the app's heap: about 13 s of a 40 Mbps remux, and twice that. */
        private const val MID_BUFFER_BYTES = 64 * 1024 * 1024
        private const val LARGE_BUFFER_BYTES = 128 * 1024 * 1024

        /** The heap, in MB, under which a device counts as low on memory for the buffer. */
        private const val LOW_HEAP_MB = 192
        private const val LARGE_HEAP_MB = 384

        /** See the note on the sink in [buildPlayer]: about two seconds of AC-3 bitstream. */
        private const val PASSTHROUGH_BUFFER_US = 500_000
        private const val AC3_BUFFER_FACTOR = 4

        /**
         * The decoded-audio buffer. The stick was seen leaving the AudioTrack unfed for over a
         * second while its cores were busy with the picture, and Media3's default half second
         * cannot ride that out. 1.5 s is the floor this app set before Media3 1.11, which in
         * practice was the length every PCM track got.
         */
        private const val PCM_BUFFER_US = 1_500_000

        /** Dropped frames in one Media3 report that count as a stutter (it reports at fifty). */
        private const val STUTTER_FRAMES = 30

        /** Enough for the back-seek a thumb makes when it missed a line of dialogue. */
        private const val BACK_BUFFER_MS = 10_000

        /** The back buffer under [LOW_RAM_BUFFER_BYTES]: see [buildPlayer]. */
        private const val LOW_RAM_BACK_BUFFER_MS = 3_000
        private const val END_GUARD_MS = 1_000L
        /** What a held finger runs the picture at, the same figure every player uses for it. */
        /** The phone's top bar height, which the corner chips sit under. */
        private const val TOP_BAR_DP = 56

        /** How long before the end the "Next episode" card comes up. */
        private const val NEXT_UP_LEAD_MS = 30_000L

        /** How long the card counts down from the start of timed credits before the next episode. */
        private const val CREDITS_COUNTDOWN_MS = 15_000L

        /** How long the skip times lookup waits for a video of unknown length to report one. */
        private const val SKIP_TIMES_WAIT_MS = 20_000L

        /** How often the sleep timer and the idle count are looked at. */
        private const val STILL_WATCHING_TICK_MS = 5_000L

        /** The smallest picture height, as a share of the screen's short side, subtitles are sized for. */
        private const val UPRIGHT_TEXT_SHARE = 0.6f

        /** How long the unlock pill stays up after a tap on the locked screen. */
        private const val UNLOCK_PILL_MS = 2_500L

        /** The overflow's group for Lock the screen, so a divider sets it apart. */
        private const val LOCK_GROUP = 1

        private const val RESUME_TICK_MS = 10_000L

        /** A saved position shorter than this is not worth a "Start over". */
        private const val RESUME_OFFER_MIN_MS = 15_000L

        /** How often a running A-B repeat looks at the position: the loop overruns its end by at most this. */
        private const val LOOP_TICK_MS = 100L

        /** How soon after picture in picture ends a stop counts as its window being closed. */
        private const val PIP_CLOSE_GRACE_MS = 1_500L

        /** Chapters past the head of a stream are looked for this many times, this far apart. */
        private const val CHAPTER_TRIES = 4
        private const val CHAPTER_RETRY_MS = 20_000L

        /** How long the first-run hint stays up. */
        private const val HINT_MS = 5_000L

        /** The phone's gesture hint, up to four lines: long enough to read, "Got it" ends it sooner. */
        private const val PHONE_HINT_MS = 12_000L

        /** The captions' climb out from under the raised transport row, and back. */
        private const val SUBTITLE_LIFT_MS = 200L

        /** The highest the bottom padding goes, so a short picture never pushes the lines off its top. */
        private const val MAX_SUBTITLE_BOTTOM = 0.4f

        /** A thumbnail at least this wide for its height is a frame of the film, not a logo. */
        private const val REAL_FRAME_ASPECT = 1.3f

        /** How long the opening wait runs before the loader adds the speed. */
        private const val LOADER_SLOW_MS = OpeningProgress.SPEED_NOTE_AFTER_MS
        private const val LOADER_TICK_MS = 250L

        /** How often [watchOpening] looks at the opening wait. */
        private const val OPENING_WATCH_TICK_MS = 1_000L

        /** How long the opening waits for the message to be read again before it picks a copy. */
        private const val POST_READ_MS = 2_500L

        /** Twice a second: faster than the eye needs and slower than TDLib talks. */
        private const val PROGRESS_RENDER_MS = 500L

        /** The artwork's way in: long enough to read as a fade, short enough not to be a wait. */
        private const val ART_FADE_MS = 320L

        /**
         * How strongly the backdrop is drawn.
         *
         * A gradient alone cannot do it: half of what these channels use as a thumbnail is a white
         * logo on white, which comes through the scrim as a pale grey wall the title has to fight.
         * Dimming the picture itself works on every thumbnail rather than only the dark ones.
         */
        private const val ART_ALPHA = 0.3f

        /** How far the poster rises as it arrives, in dp. */
        private const val POSTER_RISE_PX = 14f

        /**
         * The backdrop's slow drift, out and back.
         *
         * Twelve per cent over twenty seconds is about a pixel a second on a 1080p panel: felt
         * rather than watched. Anything faster reads as an effect and competes with the picture
         * that is about to start.
         */
        private const val ART_DRIFT_SCALE = 1.12f
        private const val ART_DRIFT_MS = 20_000L

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

        /** Three quarters black: dark enough to read white on, light enough to see the picture. */


        /** Anything at or above this is 4K territory, where a stick's decoder gives up. */
        private const val UHD_HEIGHT = 1600

        /**
         * The height a picture would have at 16:9, so a 4K film cropped to 3840x1606 counts (and
         * is named) as 2160 rather than slipping under [UHD_HEIGHT] or reading as "1606p".
         */
        internal fun nominalLines(width: Int, height: Int): Int =
            if (width <= 0 || height <= 0) maxOf(height, 0) else maxOf(height, width * 9 / 16)

        /**
         * How far either side of a seek target the MPEG-TS extractor looks for a timestamp:
         * three times Media3's 600 packets, enough for a broadcast recording's long GOP.
         */
        private const val TS_TIMESTAMP_SEARCH_BYTES = 1800 * 188

        /** Android's own limits on a picture-in-picture window's shape. */
        private const val PIP_MIN_RATIO = 1f / 2.39f
        private const val PIP_MAX_RATIO = 2.39f

        /**
         * Whether the viewer has already said yes to mobile data this session.
         *
         * Process-wide rather than per activity, because stepping through a series builds a new
         * activity per episode and being asked at every one is worse than not asking at all.
         */
        private var meteredWarningAccepted = false

        /**
         * The rotation lock the viewer last chose, for the length of the process.
         *
         * Process-wide for the same reason the metered warning is: stepping through a series builds
         * a new activity per episode, and a lock that had to be set again at every one is useless.
         * Disk persistence comes in through [primeOrientation].
         */
        private var lastOrientation = ScreenOrientation.DEFAULT

        /**
         * What "Still watching?" counts, for the length of the process: autoplay starts a new
         * activity per episode, and a count that began again with each one would never reach the
         * limit. [openedByAutoplay] tells the next activity it is one of those hops; any other way
         * into the player is somebody choosing a video, which starts both counts again.
         */
        private var autoplayedInARow = 0
        private var lastInputAt = 0L
        private var openedByAutoplay = false

        /**
         * The sleep timer: when it runs out ([SystemClock.elapsedRealtime], zero for none), or
         * whether it stops at the end of the video. Carried across autoplay for the same reason as
         * the counts above, and dropped when the viewer opens a video themselves.
         */
        private var sleepAt = 0L
        private var sleepAtTheEnd = false

        /** The minutes the running timer was started with, for its tick in the sleep timer's sheet. */
        private var sleepMinutes = 0

        /**
         * The lock read back off disk at startup, so it survives more than one process.
         *
         * Set from the application rather than read here, because the orientation has to be applied
         * before the activity lays anything out and there is no room in front of that for a disk
         * read. Anything unrecognised leaves the default alone: this is a remembered preference,
         * not a source of truth.
         */
        fun primeOrientation(name: String?) {
            val remembered = ScreenOrientation.entries.firstOrNull { it.name == name } ?: return
            lastOrientation = remembered
        }

        fun intent(context: Context, picked: MediaItem, chatTitle: String = ""): Intent =
            Intent(context, PlayerActivity::class.java).apply {
                // A Telegram copy (CP80) travels as the upload and a wish for that copy, so the
                // player still knows the upload's file for the place, a download and its copies.
                if (picked.playingQuality != 0) com.tmplayer.data.QualityWish.request(picked.chatId, picked.messageId, picked.playingQuality)
                val item = com.tmplayer.data.TelegramQualities.upload(picked)
                // A film's other copies go with it, for the player's copy button.
                if (item.versions.size > 1) CopyHandoff.offer(item.versions, mapOf(item.chatId to chatTitle).filterValues { it.isNotBlank() })
                putExtra(EXTRA_FILE_ID, item.fileId)
                putExtra(EXTRA_PRESSED_AT, SystemClock.elapsedRealtime())
                putExtra(EXTRA_SIZE, item.sizeBytes)
                putExtra(EXTRA_DURATION, item.durationSec)
                putExtra(EXTRA_CHAT_ID, item.chatId)
                putExtra(EXTRA_MESSAGE_ID, item.messageId)
                putExtra(EXTRA_TITLE, item.title)
                putExtra(EXTRA_FILE_NAME, item.fileName)
                putExtra(EXTRA_CAPTION, item.caption)
                putExtra(EXTRA_TOPIC_ID, item.topicId)
                putExtra(EXTRA_CHAT_TITLE, chatTitle)
                // The picture the grid was already showing, carried across rather than fetched
                // again: it is the one thing that can be on screen in the first frame, before
                // Telegram has been asked anything at all.
                putExtra(EXTRA_THUMBNAIL_ID, item.thumbnailFileId)
                putExtra(EXTRA_MINI_THUMBNAIL, item.miniThumbnail)
                putExtra(EXTRA_CAN_BE_SAVED, item.canBeSaved)
                putExtra(EXTRA_MIME, item.mimeType)
                // Telegram's copies as the list read them, so the player need not read the message
                // again before the first request to choose among them.
                if (item.qualities.isNotEmpty()) {
                    putExtra(EXTRA_QUALITY_FILES, item.qualities.map { it.fileId }.toIntArray())
                    putExtra(EXTRA_QUALITY_WIDTHS, item.qualities.map { it.width }.toIntArray())
                    putExtra(EXTRA_QUALITY_HEIGHTS, item.qualities.map { it.height }.toIntArray())
                    putExtra(EXTRA_QUALITY_CODECS, item.qualities.map { it.codec }.toTypedArray())
                    putExtra(EXTRA_QUALITY_SIZES, item.qualities.map { it.sizeBytes }.toLongArray())
                }
                // The pieces of a video cut into several files, so the player need not look for them.
                if (item.isJoined) {
                    putExtra(EXTRA_PIECE_MESSAGES, item.pieces.map { it.messageId }.toLongArray())
                    putExtra(EXTRA_PIECE_FILES, item.pieces.map { it.fileId }.toIntArray())
                    putExtra(EXTRA_PIECE_SIZES, item.pieces.map { it.sizeBytes }.toLongArray())
                    putExtra(EXTRA_PIECE_NAMES, item.pieces.map { it.fileName }.toTypedArray())
                }
                putExtra(
                    EXTRA_SUBTITLE,
                    listOf(
                        Translator.messages.formatter.duration(item.durationSec.toLong()),
                        Translator.messages.formatter.size(item.sizeBytes),
                    ).filter { it.isNotEmpty() }.joinToString("  ·  "),
                )
            }
    }
}
