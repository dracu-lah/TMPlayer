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
import androidx.core.view.updatePaddingRelative
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
import androidx.media3.ui.AspectRatioFrameLayout
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
import com.tmplayer.data.hasAirPlay
import com.tmplayer.data.setExternalPlaybackAllowed
import com.tmplayer.data.hasPictureInPicture
import com.tmplayer.data.pictureInPictureRefusal
import com.tmplayer.data.showAirPlayPicker
import com.tmplayer.data.videoFollowsTransforms
import com.tmplayer.data.LocalDownloads
import com.tmplayer.data.LocalVideo
import com.tmplayer.data.OpeningStall
import com.tmplayer.data.StartVerdict
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
import kotlin.math.roundToInt
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
import com.tmplayer.online.SkipController
import com.tmplayer.online.SkipOffer
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

    /** The profile this player was opened for (HX88): its resume saves are never another profile's. */
    private val accountOwner = com.tmplayer.data.Accounts.stamp

    override var player: ExoPlayer? = null
        private set

    /**
     * The VLC engine while this video plays in it (PR2), or null on ExoPlayer, which [player]
     * holds. Never both: one is released before the other is built ([switchEngine]).
     */
    private var vlcEngine: VlcEngine? = null

    /**
     * The Media3 player on screen, whichever engine it is: what the overlay, the track picker, the
     * media session and the reads of the place and the pause state go through. ExoPlayer when VLC
     * is not playing, so in a build without VLC this is always [player].
     */
    private val playing: Player? get() = player ?: vlcEngine?.player

    override val trackPlayer: Player? get() = playing

    /** What the engine choice was made from, read before the player is built; null without VLC here. */
    private var engineInput: EngineInput? = null

    /** The engine this video plays in and why ([EngineSelector]), for the log and the details. */
    private var engineDecision: EngineDecision? = null

    /** Automatic engine switches made during this video ([EngineSelector.SWITCHES_PER_VIDEO]). */
    private var engineSwitches = 0

    /** The engines that failed to decode this video: both failing brings the last sheet. */
    private val enginesFailed = mutableSetOf<com.tmplayer.data.PlayerEngine>()

    /** The engine the failure sheet offers ([showFailureActions]), set by [showEngineFailure]. */
    private var engineOffer: com.tmplayer.data.PlayerEngine? = null

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

    /** The file's type, as the chat gave it. */
    private var mediaMime = ""

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

    /** The failed format in one plain line ([FailedFormat]), under the failure sentence. */
    private var statusFormat: TextView? = null

    /** "Copy details" on the failure sheet: the format line, the device's decoders, the error. */
    private var statusCopy: android.widget.Button? = null
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
    private var statusEngine: android.widget.Button? = null
    private var statusEngineAlways: android.widget.Button? = null

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
     * Each change also tells iOS's AirPlay whether the video may go to another screen (SEC2-01).
     */
    private val externalPlayback = ExternalPlaybackVerdict { setExternalPlaybackAllowed(it) }
    private var savable: Boolean?
        get() = externalPlayback.savable
        set(value) {
            externalPlayback.savable = value
        }

    /** The failure sheet's way out, and the video's own name on it. */
    private var statusBack: android.widget.Button? = null
    private var statusName: TextView? = null

    /** Set while [reloadFromScratch] is clearing the file, so a second press cannot race it. */
    private var reloading = false

    /** The transport overlay, one design on every device. Built in [onCreate], lives as long. */
    private var controls: PlayerControls? = null

    /** More options, the side panel ⋮ opens on the phone and the television (HX16). */
    private var morePanel: PlayerMorePanel? = null

    /** The ⋮ that opened [morePanel], which the remote goes back to when it closes. */
    private var moreAnchor: View? = null

    /** The speed, sleep timer and playback details sheets, on a phone and a television alike. */
    private var sheets: PlayerSheets? = null

    /** The episode list the row's Episodes button opens (CP40), on a phone and a television. */
    private var episodesList: PlayerEpisodes? = null

    /** [episodesList] is up. */
    private var episodesOpen = false

    /** The autoplay setting as the episode list shows it, read when the list opens. */
    private var autoplayNext = true

    /** "Keep repeat on" as this video's end will find it ([repeatsAtTheEnd]), read when the video opens. */
    private var keepRepeat = false

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

    /** The lines on screen as the track gave them, fitted again when the look changes. */
    private var trackCues: List<Cue> = emptyList()

    /** A bundled font the subtitles needed because the system has none for their script. */
    private var subtitleScriptFace: android.graphics.Typeface? = null

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
    private val subtitleFiles = SubtitleFiles(this, { player }, { showGestureFeedback(it) }).also { files ->
        // A file of the viewer's own, or one from the chat, replaces any online pick remembered for this video (HX25).
        files.onViewerChose = { OnlineSubtitles.current?.forgetPick(com.tmplayer.online.SubtitlePicks.keysOf(currentItem())) }
        // A growing translation is swapped in only when no scrub is under way (the swap rebuffers the video).
        files.isScrubbing = { controls?.isScrubbing == true || gestureScrubbing }
    }

    /** A swipe scrub is under way (set by the gestures); see [SubtitleFiles.isScrubbing]. */
    @Volatile
    private var gestureScrubbing = false
    private val chatSubtitleLoader = ChatSubtitleLoader(subtitleFiles)

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

    /** The subtitle track chosen is a file loaded beside the video, read whole: it may go the full minute early (PB2-11). */
    private var textSideLoaded = false

    /** [subtitleDelayUs] from [syncDelays], a track inside the file held to [SyncDelays.EMBEDDED_LEAD_MS] early. */
    private fun applySubtitleDelay() {
        subtitleDelayUs = SyncDelays.subtitleApplied(syncDelays.subtitleMs, textSideLoaded) * 1000
    }

    /** The audio half's processor, owned by the current player's sink. */
    private var audioOffset: AudioOffsetProcessor? = null

    /** How subtitles are drawn. Shared with Settings and the desktop. */
    private var subtitleStyle = SubtitleStyle()

    /** True while [subtitleStyle] is this video's own look (WP-29 "This video only"), not the one of every video. */
    private var styleThisVideo = false

    /** Volume boost as the viewer left it, read before the player is built. */
    private var volumeBoostOn = false

    /** Settings, Playback: scan while holding and jump once when let go (WP-79); off unless chosen. */
    private var scanThenJump = false

    /** The held arrow's virtual position while the scan option scans; see [RemoteScan]. */
    private val remoteScan = RemoteScan()
    private val scanIdle = Runnable { commitScan() }

    /**
     * The sound now plays as a Dolby or DTS bitstream, read from the output's own AudioTrack on every
     * audio format change (WP-24): Volume boost and Night mode cannot reach it, and say so.
     */
    private var soundBitstream = false

    /** The first frame of this video has said what Volume boost and Night mode are doing (WP-24). */
    private var soundChipShown = false

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

    /**
     * Rotate picture (#34), clockwise degrees ([PictureRotation]): drawn by the effects pipeline,
     * built for it the first time a turn is chosen as 3D's is. Kept while the same video plays
     * (a decoder or a player rebuilt for it), back to none when another opens ([readItem]).
     */
    private var pictureRotation = PictureRotation.NONE

    /**
     * Player volume (#33), 0 to 100 per cent ([PlayerVolume]), read before the player is built
     * and kept for every video. The engine's own volume ([Engine.volume]) plays it, under the
     * device's volume and the boost's lift alike.
     */
    private var playerVolumePercent = PlayerVolume.FULL

    /** Whether this video has said that Player volume is turned down ([hintPlayerVolume]). */
    private var playerVolumeHinted = false

    /** The pending write of [playerVolumePercent], so a held key writes the setting once. */
    private var playerVolumeSave: kotlinx.coroutines.Job? = null

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
    private var downloadedVideo: LocalVideo? = null
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
            controls?.previewArt = value.takeIf { Trickplay.PICTURES_SHOWN }
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
        // The rate the container gave, else the one measured from the frames ([FrameRateEstimator]).
        val containerFps = format?.frameRate ?: 0f
        val prediction = format?.let { DeviceDecode.verdictOf(it) }?.name ?: "UNKNOWN"
        val facts = com.tmplayer.data.StutterReport.Facts(
            codec = picture.family,
            profile = format?.codecs.orEmpty(),
            bitDepth = picture.bitDepth,
            width = picture.width,
            height = picture.height,
            fps = FrameRates.pick(containerFps, derivedFps),
            // A Matroska states no bit rate: the file's size over its length stands in for it.
            bitrateKbps = FrameRates.bitrateKbps(format?.let { maxOf(it.averageBitrate, it.bitrate) } ?: 0, fileSizeBytes, durationSec),
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
            symptom = figures.symptom,
            fpsSource = FrameRates.source(containerFps, derivedFps),
            source = runCatching { currentItem().locality.name.lowercase() }.getOrDefault("unknown"),
            bufferedAheadMs = runCatching { player?.let { (it.bufferedPosition - it.currentPosition).coerceAtLeast(0).toInt() } ?: -1 }.getOrDefault(-1),
            // What the playback trace of this session says (L2): decoder, line, render path, heat or memory.
            verdict = runCatching { com.tmplayer.data.StutterReport.verdictName(traceRecorder.verdict()) }.getOrDefault(com.tmplayer.data.StutterReport.UNKNOWN_VERDICT),
            // Whether the picture that stuttered was already on a changed path (RL11).
            // "+", not ",": the report keeps only letters, digits and a few signs, and a comma is not one.
            remedies = remedies.current?.taken?.joinToString("+") { it.code }.orEmpty(),
            hops = remedies.current?.hops?.size ?: 0,
            // Why this decoder (Sentry triage, 2026-11-18): the path built, the setting, what moved it to software.
            policy = builtPolicy?.shortClass().orEmpty(),
            preferSoftware = videoDecoder == com.tmplayer.data.VideoDecoder.Software,
            softwareBy = com.tmplayer.data.StutterReport.softwareByOf(
                software = watchSoftware,
                switched = videoSwitchedToSoftware,
                ladderHop = remedies.current?.hops?.any { it.remedy == Remedy.Software } == true,
                plan = remedies.current?.taken?.contains(Remedy.Software) == true,
                setting = videoDecoder == com.tmplayer.data.VideoDecoder.Software,
            ),
            processBits = if (android.os.Process.is64Bit()) 64 else 32,
        )
        val mime = format?.sampleMimeType
        val context = applicationContext
        // The codec list is read on the report's own thread: Media3's cache, but never the main thread's job.
        Thread {
            runCatching {
                // How the chip fits the picture (HX71): a hardware stutter past its rated frame rate says so.
                val fit = format?.let { HardwareDecoders.fitOf(context, it).name.lowercase() } ?: com.tmplayer.data.StutterReport.UNKNOWN
                com.tmplayer.data.CrashReports.reportStutter(facts.copy(hwDecoders = HardwareDecoders.listed(mime), hwFit = fit))
            }
        }.start()
    }

    // ---- the watchdog over the first seconds of play (HX2, [PlaybackWatch]) --------------------

    private val watch = PlaybackWatch()

    /** What is playing, for a crash report (S2): engine, decoder, codec family and size class only. */
    private val crashNote = CrashPlayingNote()

    // ---- the playback stats overlay (PR5): built and ticking only while the Advanced switch is on --

    private var statsOn = false

    // ---- subtitles found for you (U5): off unless Settings, Online subtitles has it on -----------------

    private var suggestChip: SubtitleOfferChip? = null

    /** The viewer's subtitle language for this video, or null when none can be told. */
    private var suggestLanguage: String? = null

    /** Looked at already for this video (once, whatever came of it), or the viewer let the question lapse. */
    private var suggestHandled = false
    private var suggestMeteredOk = false
    private var suggestBusy = false
    private var suggestHits: List<com.tmplayer.online.SubtitleHit> = emptyList()
    private var suggestTarget: SubtitleTarget? = null
    private var suggestCurrent: com.tmplayer.online.SubtitleHit? = null
    private val suggestRejected = mutableSetOf<String>()

    /** What a tap or Up does on the chip: the metered question's yes, or "Not this one, try the next". */
    private var suggestAction: (() -> Unit)? = null

    /** The video's remembered online subtitle has been looked for (HX25): once per video. */
    private var pickRestored = false

    /**
     * HX25: the online subtitle picked for this video before comes back by itself, before the
     * preferred language's own pick and the "find subtitles for me" search, and "Off" does too.
     * The kept file is used first (a download costs nothing); only when it is gone is it fetched
     * again, quietly, unless the line is metered or the kids lock is on, when it waits for the
     * viewer. ExoPlayer only, like the other online subtitles.
     */
    private fun restoreRememberedSubtitle(current: Tracks) {
        if (pickRestored || current.groups.isEmpty()) return
        val online = OnlineSubtitles.current ?: return
        if (online.picks == null || player == null || !hasMessage()) return
        pickRestored = true
        val keys = com.tmplayer.online.SubtitlePicks.keysOf(currentItem())
        lifecycleScope.launch {
            try {
                var found = online.restorePick(keys, null, allowDownload = false)
                if (found is OnlineSubtitles.Restored.Missing && !onMeteredLine() && com.tmplayer.data.ChatGate.allowed.value == null && online.inBuild) {
                    found = online.restorePick(keys, onlineTarget(), allowDownload = true)
                }
                if (isFinishing || isDestroyed) return@launch
                val exo = player ?: return@launch
                when (found) {
                    OnlineSubtitles.Restored.None -> Unit
                    OnlineSubtitles.Restored.Off -> {
                        suggestHandled = true
                        exo.trackSelectionParameters = exo.trackSelectionParameters.buildUpon()
                            .clearOverridesOfType(C.TRACK_TYPE_TEXT).setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build()
                    }
                    is OnlineSubtitles.Restored.Ready -> {
                        suggestHandled = true
                        subtitleFiles.attachFile(found.file, found.label)
                    }
                    is OnlineSubtitles.Restored.Missing -> found.notice?.let { suggestNote(com.tmplayer.online.OnlineWords.notice(it)) }
                }
            } catch (e: kotlin.coroutines.cancellation.CancellationException) {
                throw e
            } catch (e: Exception) {
                com.tmplayer.platform.JobFailures.handle("restoreSubtitle", e)
            }
        }
    }

    private val suggestStart = Runnable { maybeSuggestSubtitles() }
    private val suggestExpiry = Runnable {
        // A question left unanswered is not asked again for this video.
        if (suggestAction != null && !suggestMeteredOk && suggestCurrent == null) suggestHandled = true
        suggestAction = null
        suggestChip?.hide()
    }

    /** A new video: nothing of the last one's search is kept. */
    private fun resetSuggestions(plan: TrackPlan, prefs: com.tmplayer.player.TrackPreferences) {
        loopHandler.removeCallbacks(suggestStart)
        loopHandler.removeCallbacks(suggestExpiry)
        suggestChip?.hide()
        suggestAction = null
        suggestHandled = false
        pickRestored = false
        suggestMeteredOk = false
        suggestBusy = false
        suggestHits = emptyList()
        suggestTarget = null
        suggestCurrent = null
        suggestRejected.clear()
        suggestLanguage = (plan.subtitles as? SubtitlePlan.On)?.language
            ?: (plan.subtitles as? SubtitlePlan.IfForeignAudio)?.language
            ?: prefs.subtitleLanguage
            ?: com.tmplayer.player.TrackLanguages.normalise(Translator.messages.tag)
    }

    /** Whether the line in use costs the viewer by the byte (mobile data, a hotspot). Unknown reads as no. */
    private fun onMeteredLine(): Boolean = runCatching {
        (getSystemService(Context.CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager)?.isActiveNetworkMetered == true
    }.getOrDefault(false)

    /**
     * A few seconds into a video: with the switch on, a video that has no subtitle in the viewer's
     * language gets the best match fetched, or the viewer is asked first on a metered line. ExoPlayer only
     * (the files are laid over its media item); never on the TV's cast, under the kids lock or when the
     * viewer plays without subtitles.
     */
    private fun maybeSuggestSubtitles() {
        val online = OnlineSubtitles.current ?: return
        val exo = player ?: return
        if (isFinishing || isDestroyed || castUp() || exo.playbackState == Player.STATE_IDLE) return
        val current = exo.currentTracks
        val textLanguages = tracksOfType(current, C.TRACK_TYPE_TEXT).map { (group, index) -> group.getTrackFormat(index).language }
        val audio = tracksOfType(current, C.TRACK_TYPE_AUDIO)
            .firstOrNull { (group, index) -> group.isTrackSelected(index) }
            ?.let { (group, index) -> group.getTrackFormat(index).language }
        val plan = trackPlan.subtitles
        val wantsNone = plan is SubtitlePlan.Off ||
            (plan is SubtitlePlan.IfForeignAudio && com.tmplayer.player.TrackLanguages.normalise(audio).let { it == null || it in plan.viewerLanguages })
        val gate = com.tmplayer.online.SubtitleSuggestion.Gate(
            enabled = online.store.now.suggest,
            available = online.inBuild && !online.keyRefused(),
            kidsLock = com.tmplayer.data.ChatGate.allowed.value != null,
            viewerWantsNone = wantsNone,
            language = suggestLanguage,
            trackLanguages = textLanguages,
            handled = suggestHandled,
            metered = onMeteredLine(),
            meteredAccepted = suggestMeteredOk,
        )
        when (val decision = com.tmplayer.online.SubtitleSuggestion.decide(gate)) {
            is com.tmplayer.online.SubtitleSuggestion.Decision.Skipped -> Unit
            com.tmplayer.online.SubtitleSuggestion.Decision.AskMetered -> {
                suggestAction = {
                    suggestMeteredOk = true
                    suggestAction = null
                    suggestChip?.hide()
                    loopHandler.removeCallbacks(suggestExpiry)
                    maybeSuggestSubtitles()
                }
                showSuggestChip(L.onlineOfferMetered, L.onlineOfferMeteredYes)
            }
            com.tmplayer.online.SubtitleSuggestion.Decision.Fetch -> fetchSuggestion(online)
        }
    }

    private fun showSuggestChip(message: String, action: String) {
        val chip = suggestChip ?: return
        val wasShown = chip.isShown
        chip.show(message, action)
        if (!wasShown) liftCornerOffers()
        loopHandler.removeCallbacks(suggestExpiry)
        loopHandler.postDelayed(suggestExpiry, SubtitleOfferChip.SHOW_MS)
    }

    /** One quiet line, never a sheet: a rate limit, no key or a miss says so and the video plays on. */
    private fun suggestNote(text: String) {
        suggestAction = null
        suggestChip?.hide()
        showGestureFeedback(text)
    }

    private fun fetchSuggestion(online: OnlineSubtitles) {
        if (suggestBusy) return
        suggestBusy = true
        suggestHandled = true
        lifecycleScope.launch {
            try {
                val language = suggestLanguage ?: return@launch
                var target = suggestTarget
                if (target == null) {
                    // The viewer's language first, so the search asks for it whatever the series was last watched in.
                    val base = onlineTarget()
                    target = base?.copy(languages = (listOf(language) + base.languages).distinct())
                    suggestTarget = target
                }
                if (target == null) {
                    suggestNote(L.onlineOfferNone)
                    return@launch
                }
                if (suggestHits.isEmpty()) {
                    val found = online.search(target)
                    if (found.hits.isEmpty()) {
                        suggestNote(found.notice?.let(com.tmplayer.online.OnlineWords::notice) ?: L.onlineOfferNone)
                        return@launch
                    }
                    suggestHits = found.hits
                }
                tryNextSuggestion(online, target, language)
            } catch (e: kotlin.coroutines.cancellation.CancellationException) {
                throw e
            } catch (e: Exception) {
                // Whatever went wrong, the video plays on and says so in one line (HX10).
                com.tmplayer.platform.JobFailures.handle("suggestSubtitles", e)
                runCatching { suggestNote(L.onlineNoticeOffline) }
            } finally {
                suggestBusy = false
            }
        }
    }

    /** Downloads and lays over the video the best hit not turned down yet; the chip says where it came from. */
    private suspend fun tryNextSuggestion(online: OnlineSubtitles, target: SubtitleTarget, language: String) {
        val candidates = com.tmplayer.online.SubtitleSuggestion.candidates(
            suggestHits, language, suggestRejected, allowMachine = online.store.now.includeMachine,
        )
        val hit = candidates.firstOrNull()
        if (hit == null) {
            suggestCurrent = null
            suggestNote(if (suggestRejected.isEmpty()) L.onlineOfferNone else L.onlineOfferNoMore)
            return
        }
        online.blockedFor(hit)?.let {
            suggestNote(com.tmplayer.online.OnlineWords.notice(it))
            return
        }
        when (val done = online.download(hit, target)) {
            is com.tmplayer.online.DownloadResult.Done -> {
                if (isFinishing || isDestroyed || player == null) return
                subtitleFiles.attachFile(done.file, done.label)
                online.rememberPick(com.tmplayer.online.SubtitlePicks.keysOf(currentItem()), hit, done.file)
                suggestCurrent = hit
                val source = com.tmplayer.online.SubtitleSuggestion.sourceOf(hit)
                val name = OnlineSubtitles.languageName(hit.language)
                suggestAction = ::nextSuggestion
                showSuggestChip(
                    if (source.byFile) L.onlineOfferFoundFile(language = name, source = source.provider) else L.onlineOfferFound(language = name, source = source.provider),
                    L.onlineOfferNext,
                )
            }
            is com.tmplayer.online.DownloadResult.Failed -> suggestNote(com.tmplayer.online.OnlineWords.notice(done.notice))
        }
    }

    /** "Not this one, try the next": the shown hit is turned down for this video, and the next best is laid over. */
    private fun nextSuggestion() {
        val online = OnlineSubtitles.current ?: return
        val shown = suggestCurrent ?: return
        val target = suggestTarget ?: return
        val language = suggestLanguage ?: return
        if (suggestBusy) return
        suggestRejected += com.tmplayer.online.SubtitleSuggestion.keyOf(shown)
        suggestBusy = true
        suggestAction = null
        suggestChip?.hide()
        lifecycleScope.launch {
            try {
                tryNextSuggestion(online, target, language)
            } catch (e: kotlin.coroutines.cancellation.CancellationException) {
                throw e
            } catch (e: Exception) {
                com.tmplayer.platform.JobFailures.handle("nextSubtitle", e)
                runCatching { suggestNote(L.onlineNoticeOffline) }
            } finally {
                suggestBusy = false
            }
        }
    }

    // ---- tunneled playback (PR5), on a television only ----------------------------------------------

    /** Settings, Playback, Advanced, Tunneled playback; read as the video opens. */
    private var tunneledMode = com.tmplayer.player.TunneledMode.Auto

    /**
     * Settings, Playback, Advanced, Audio passthrough (on by default); read as the video opens, so
     * a change applies from the next video. Off builds the player's sound output PCM only (A2).
     */
    private var audioPassthrough = true

    /** Whether the player in hand was built with tunneling on, so an error under it can retry without. */
    private var tunnelingActive = false

    /** A playback error under tunneling happened in this screen: it is not tried again here. */
    private var tunnelingFailed = false

    /** Whether the track selector is told to tunnel for the video being opened, and why (logged). */
    private fun tunnelingDecision(): TunnelingPolicy.Decision {
        val tv = FormFactor.isTv(this)
        if (!tv) return TunnelingPolicy.Decision(false, TunnelingPolicy.Reason.NotTelevision)
        val mime = mediaMime.ifBlank { null }
        return TunnelingPolicy.decide(
            TunnelingPolicy.Input(
                mode = tunneledMode,
                television = true,
                knownDevice = com.tmplayer.data.KnownDevices.tunnelingFor(com.tmplayer.data.KnownDevices.builtIn, EngineDevice.facts(), mime),
                // A tunneled decoder never hands frames to the app: 3D, a forced shape and the app's own
                // software decoder all work on frames, so nothing is drawn onto a picture the app cannot reach.
                drawsOnPicture = stereo.on || pictureRotation != PictureRotation.NONE || videoScale != VideoScale.Fit ||
                    videoDecoder == com.tmplayer.data.VideoDecoder.Software,
                codecCanTunnel = TunnelingSupport.canTunnel(mime),
                failedBefore = tunnelingFailed,
            ),
        )
    }

    /**
     * An error while the player tunnels (a decoder that cannot, an audio track that will not start in
     * tunnel mode): once, the same place again with tunneling off. True when it took the error.
     */
    private fun retryWithoutTunneling(error: PlaybackException): Boolean {
        val exo = player ?: return false
        if (!TunnelingPolicy.retriesWithout(tunnelingActive, tunnelingFailed, EngineFailures.of(error.errorCode) == EngineFailure.Source)) return false
        tunnelingFailed = true
        tunnelingActive = false
        Log.w("TMPlayer", "Playback error ${error.errorCodeName} while tunneling: trying again without it")
        (exo.trackSelector as? DefaultTrackSelector)?.let { it.parameters = it.buildUponParameters().setTunnelingEnabled(false).build() }
        exo.prepare()
        exo.playWhenReady = true
        return true
    }
    private var statsOverlay: PlaybackStatsOverlay? = null
    private var statsScheduled = false
    private var statsWaitBase = 0L
    private val statsGate = PlaybackStatsText.Throttle()

    private val statsTick = object : Runnable {
        override fun run() {
            statsScheduled = false
            if (!statsOn || isFinishing || isDestroyed) return
            val engine = currentEngine() ?: return
            updateStats(engine)
            // Only while a video plays and is in front: paused or in the background it holds its last reading.
            if (engine.player.isPlaying && lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)) scheduleStats()
        }
    }

    private fun scheduleStats() {
        if (statsScheduled) return
        statsScheduled = true
        loopHandler.postDelayed(statsTick, PlaybackStatsText.ONE_SECOND_MS)
    }

    /** Builds the readout and starts its tick, when the switch is on; nothing at all when it is off. */
    private fun startStats() {
        if (!statsOn) return
        if (statsOverlay == null) {
            statsOverlay = PlaybackStatsOverlay(
                root = findViewById(R.id.player_root),
                below = findViewById(R.id.overlay_container),
                tv = FormFactor.isTv(this),
            )
            statsWaitBase = TdReadCounters.totalWaitedMs()
        }
        currentEngine()?.let { updateStats(it) }
        scheduleStats()
    }

    private fun stopStats() {
        loopHandler.removeCallbacks(statsTick)
        statsScheduled = false
        statsGate.reset()
        statsOverlay?.release()
        statsOverlay = null
    }

    private fun updateStats(engine: Engine) {
        val overlay = statsOverlay ?: return
        if (!statsGate.allow(SystemClock.elapsedRealtime())) return
        val stats = runCatching { engine.stats() }.getOrNull() ?: return
        val vlc = stats.engine == com.tmplayer.data.PlayerEngine.Vlc
        val total = TdReadCounters.totalWaitedMs()
        val waited = (total - statsWaitBase).coerceAtLeast(0L)
        statsWaitBase = total
        val hardware = when {
            vlc -> null
            watchDecoder.isEmpty() -> null
            else -> !DecoderNames.isSoftware(watchDecoder) && !watchSoftware
        }
        overlay.show(
            PlaybackStatsText.lines(
                PlaybackStatsText.Readout(
                    engine = if (vlc) "libVLC" else "ExoPlayer",
                    decoder = if (vlc) "" else watchDecoder,
                    hardware = hardware,
                    droppedFrames = if (vlc) stats.droppedFrames else watch.figures().dropped,
                    bufferAheadMs = if (vlc) stats.bufferedMs else (player?.let { it.bufferedPosition - it.currentPosition } ?: 0L),
                    readWaitMs = waited,
                    speed = engine.speed,
                ),
            ),
        )
    }

    /**
     * The playback trace (reliability plan L1): dropped frames, underruns, buffer, decoder and the
     * data source's waits for TDLib, every few seconds while the video plays. Ticks on [loopHandler]
     * and writes on its own thread; stopped by [letGoOfPlayer].
     */
    private val traceRecorder: PlaybackRecorder by lazy {
        PlaybackRecorder(
            context = this,
            file = PlaybackRecorder.fileIn(this),
            handler = loopHandler,
            intervalMs = TraceCadence.intervalMs(com.tmplayer.BuildConfig.BUILD_TYPE, com.tmplayer.BuildConfig.VERSION_NAME),
        )
    }

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

    /** The sound's decoder, for the playback details (the picture's is [watchDecoder]). */
    private var audioDecoderName: String? = null

    /** Whether this video got its "Stuttering?" offer or line: once per video. */
    private var stutterOffered = false

    /**
     * The frame rate measured from the frames' own times for a video whose container states none
     * (a Matroska without a default duration, HX11 and HX37), or 0 while unknown. Read it through
     * [videoFps]. [fpsEstimator] is fed on the playback thread and swapped on the main thread, so
     * a new video's estimator is a new object, never a reset one.
     */
    @Volatile private var derivedFps = 0f
    @Volatile private var fpsEstimator: FrameRateEstimator? = null

    /** Feeds [fpsEstimator] each frame's time until it settles, then lets go of the player's hook. */
    private val fpsListener = androidx.media3.exoplayer.video.VideoFrameMetadataListener { presentationTimeUs, _, _, _ ->
        val estimator = fpsEstimator
        if (estimator != null) {
            val found = estimator.add(presentationTimeUs)
            if (found != null || estimator.finished) {
                fpsEstimator = null
                if (found != null) derivedFps = found
                loopHandler.post { onFrameRateMeasured(found ?: 0f) }
            }
        }
    }

    /** A new video format reached the decoder: measure its rate only when the container gave none. */
    private fun watchFrameRate(format: androidx.media3.common.Format) {
        derivedFps = 0f
        val exo = player ?: return
        if (FrameRates.plausible(format.frameRate) > 0f) {
            fpsEstimator = null
            exo.clearVideoFrameMetadataListener(fpsListener)
        } else {
            fpsEstimator = FrameRateEstimator()
            exo.setVideoFrameMetadataListener(fpsListener)
        }
    }

    /** The rate found (0 for none), on the main thread: the hook is let go, the display is asked if it is to be. */
    private fun onFrameRateMeasured(fps: Float) {
        player?.clearVideoFrameMetadataListener(fpsListener)
        Log.i("TMPlayer", "Frame rate: container said none, measured ${if (fps > 0f) "$fps fps" else "nothing steady"}")
        if (fps > 0f && FrameRateMatch.shouldTakeOver(this)) FrameRateMatch.apply(videoSurface(), fps)
    }

    /** The video's frame rate: the container's, else the measured one, else 0 for not known. */
    private fun videoFps(): Float = FrameRates.pick(player?.videoFormat?.frameRate ?: 0f, derivedFps)

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

    /** Whether this video already said its sound cannot play here ([soundUnplayable]). */
    private val soundNotice = SoundNotice()

    /**
     * Says once per video, when its sound cannot play on this device (no decoder for it here and
     * none in FFmpeg, also after a rebuild on another path): the picture goes on, and the line says
     * why it is silent rather than leaving the viewer to guess. Not while casting or on VLC.
     */
    private fun soundUnplayable(tracks: Tracks) {
        if (!soundNotice.shouldSay(tracks, otherEngine = vlcEngine != null)) return
        val format = tracks.groups.firstOrNull { it.type == C.TRACK_TYPE_AUDIO }?.getTrackFormat(0)
        Log.w("TMPlayer", "No decoder for the sound: ${format?.sampleMimeType} ${format?.codecs.orEmpty()}")
        feedback?.message(L.playerNoSoundHere, holdMs = SEEK_NOTICE_MS)
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
        if (pictureChecked) return false
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
        playing?.pause()
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
        if (softwareChecked) return false
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
            downloadedVideo == null && joinedSource == null && hasMessage()
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
        if (unwatchable) playing?.pause()
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
        playing?.pause()
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
                    false -> playing?.play()
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
        playing?.pause()
        // The moment to carry on from: the player's, or the saved place it has not sought to yet.
        val at = playing?.currentPosition?.takeIf { it > 0 } ?: resumeMs
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
        if (watch.done || !firstFrameReady) return
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
                // A play started, the stutter rate's denominator (counted only with reporting on, [PlayCounts]).
                val decoder = watchDecoder
                val abi = android.os.Build.SUPPORTED_ABIS.firstOrNull().orEmpty()
                if (com.tmplayer.data.CrashReports.available) {
                    Thread { runCatching { com.tmplayer.data.CrashReports.notePlayStarted(abi, decoder) } }.start()
                }
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
            exo.playbackParameters.speed,
        )
        when {
            answer != null -> onWatchAnswer(answer)
            // Paused or waiting: no ticks until it plays again ([startWatch] from onIsPlayingChanged).
            playing -> loopHandler.postDelayed(watchTick, WATCH_TICK_MS)
        }
    }

    private fun onWatchAnswer(answer: PlaybackWatch.Answer) {
        // With the rate measured when the container gave none, so the remembered kind is the right bucket.
        val picture = watchPicture?.let { it.copy(fps = FrameRates.pick(it.fps, derivedFps)) }
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

    /** The error the next [showError] is about, handed over once so a later sheet of another kind shows no format. */
    private var formatErrorPending: PlaybackException? = null

    /** What the failure sheet was about, kept for "Copy details" after the player is gone. */
    private var failedFormat: FailedFormat.Of? = null

    /**
     * Puts the failed format's line under the sentence, for a codec or container failure that knew
     * its format ([FailedFormat.relevant]); nothing for an error before any format was known, a
     * lost line or a full disk. The format is kept either way for "Copy details".
     */
    private fun showFailedFormat() {
        val error = formatErrorPending
        formatErrorPending = null
        val view = statusFormat ?: return
        view.visibility = View.GONE
        failedFormat = null
        if (error == null) return
        val renderer = (error as? androidx.media3.exoplayer.ExoPlaybackException)
            ?.takeIf { it.type == androidx.media3.exoplayer.ExoPlaybackException.TYPE_RENDERER }
        val software = runCatching {
            val index = renderer?.rendererIndex
            val exo = player
            if (index != null && exo != null && index in 0 until exo.rendererCount) {
                DecoderSwitch.isFfmpeg(exo.getRenderer(index))
            } else {
                watchSoftware
            }
        }.getOrDefault(watchSoftware)
        val of = FailedFormat.of(player, renderer?.rendererFormat, software)
        failedFormat = of
        if (!FailedFormat.relevant(error)) return
        val line = FailedFormat.line(of)
        if (line.isEmpty()) return
        view.text = line
        view.visibility = View.VISIBLE
    }

    /**
     * What "Copy details" puts on the clipboard: the app and device, what the sheet says, the
     * failed format and the device's decoders for it, the file's size and length, and the error
     * underneath. Plain text a report or a forum post can carry. Nothing about the account, and no
     * title, chat or file name (AGENTS.md privacy).
     */
    private fun errorDetails(): String = buildString {
        appendLine("TMPlayer ${com.tmplayer.BuildConfig.VERSION_NAME} (${com.tmplayer.BuildConfig.VERSION_CODE})")
        appendLine("${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}, Android ${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT})${if (FormFactor.isTv(this@PlayerActivity)) ", TV" else ""}")
        appendLine("${statusTitle.text}: ${statusText.text}")
        failedFormat?.let { of ->
            // The notes in English, so a report reads the same whatever language the viewer had.
            val block = com.tmplayer.player.FormatLine.copyBlock(
                FailedFormat.line(of, "read by FFmpeg", "software decoder"),
                FailedFormat.decoders(of),
            )
            if (block.isNotEmpty()) appendLine(block)
        }
        appendLine("File: ${mediaMime.ifBlank { "?" }}, ${fileSizeBytes} bytes, ${durationSec} s, ${if (downloadedVideo != null) "downloaded" else "streamed"}")
        failedFormat?.video?.let { appendLine("Video: ${it.sampleMimeType} ${it.codecs.orEmpty()} ${it.width}x${it.height}") }
        failedFormat?.audio?.let { appendLine("Audio: ${it.sampleMimeType} ${it.codecs.orEmpty()} ${it.channelCount} ch ${it.sampleRate} Hz") }
        fileDatacenter?.let { appendLine("Datacenter: DC $it") }
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

    /** Copy details: the text of [errorDetails] on the clipboard, with a message that says it worked or did not. */
    private fun copyErrorDetails() {
        val clipboard = getSystemService(android.content.ClipboardManager::class.java)
        val copied = runCatching {
            clipboard?.setPrimaryClip(android.content.ClipData.newPlainText(L.playerCopyDetails, errorDetails()))
        }.isSuccess && clipboard != null
        com.tmplayer.ui.components.showMessage(this, if (copied) L.playerDetailsCopied else L.stateFailed)
    }

    /** The Telegram datacenter the video's file is kept in, or null when its remote id does not say (see [FileDatacenter]). */
    private var fileDatacenter: Int? = null

    /** Reads [fileDatacenter] once, off the critical path, for Playback details and Copy details. */
    private fun findDatacenter() {
        if (fileId <= 0) return
        itemScope.launch {
            afterOpening()
            fileDatacenter = runCatching { Td.remoteDatacenter(fileId) }.getOrNull()
        }
    }

    /** When the latest seek began, for its "seek to ready" log; 0 when none is pending. */
    private var seekStartedAt = 0L

    /** Logs press (or open) to first frame once per video, for the device pass to compare. */
    private fun logFirstFrame() {
        if (openedAt == 0L) return
        val now = SystemClock.elapsedRealtime()
        val fromPress = if (pressedAt > 0) "press to first frame ${now - pressedAt} ms, " else ""
        Log.i(START_TAG, "${fromPress}open to first frame ${now - openedAt} ms (file $fileId, resume $resumeMs ms, ${if (downloadedVideo != null) "download" else "stream"})")
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
    /**
     * Play all or Shuffle (WP-32, WP-87): how this player goes on after a video, [com.tmplayer.data.PlayAll.Mode.Off] for
     * every video that was not started from the press. Carried from one video to the next ([carryPlayAll]) and, as
     * extras of the intent, through a rebuild of the process; a list's own queue stays with the process only.
     */
    private var playAllMode = com.tmplayer.data.PlayAll.Mode.Off
    private var playAllSeed = 0L
    private var playAllQueue: List<MediaItem> = emptyList()
    private var shufflePool: com.tmplayer.data.PlayAll.ShufflePool? = null

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
     * The free pinch zoom over [videoScale] ([PictureZoom]): a scale and a move on the picture's
     * frame, never kept past this video. Phone only. A television gets none: it has no touch, its
     * remote already steps the picture shapes, and the arrows belong to seeking and focus.
     */
    private var zoom = PictureZoom.NONE

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
    /** Chapters, online lists and the viewer's marks, merged, with the per kind choice and the countdown. */
    private val skipCtl = SkipController { showGestureFeedback(it) }
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
    private var lastReadBytes = -1L
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

    // The TV theme and player sizes follow the app's layout, not only the system's UI mode (HX78).
    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(newBase)
        FormFactor.uiModeOverride(newBase)?.let { runCatching { applyOverrideConfiguration(it) } }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Fragments are not restored. A restored playback fragment comes back before the player is
        // rebuilt, so it would attach with nothing behind it and no transport controls. A clean
        // fragment manager means the fragment below is always built against a live player, and
        // nothing here is worth restoring: the resume position lives on disk.
        super.onCreate(null)
        // An update that installs while this is open (picture in picture, the sound playing on)
        // ends the process: the place is written first (HX5).
        com.tmplayer.data.SelfInstaller.addBeforeInstall(saveBeforeInstall)
        // A profile switch (HX88) saves the place here, for this profile, and closes the player.
        com.tmplayer.data.BeforeSwitch.addPlayer(stopBeforeSwitch) { com.tmplayer.i18n.L.profilesWaitingPlayer }
        // A PIN profile's video stays out of the recents screen (HX88).
        com.tmplayer.ui.profiles.SecureWindow.follow(this)
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
        statusFormat = findViewById(R.id.status_format)
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
        statusEngine = findViewById<android.widget.Button>(R.id.status_engine).apply {
            setOnClickListener { if (crashOfferUp) answerCrashOffer(exo = true) else switchEngine() }
        }
        statusEngineAlways = findViewById<android.widget.Button>(R.id.status_engine_always).apply {
            text = L.playerEngineAlwaysVlc
            setOnClickListener { if (crashOfferUp) answerCrashOffer(exo = false) else alwaysUseVlc() }
        }
        // The layout carries English for the preview; the words on screen come from the catalog.
        statusRetry?.text = L.commonTryAgain
        statusReload?.text = L.commonReload
        statusOpenWith?.text = L.playerOpenInAnotherApp
        statusBack?.text = L.playerGoBack
        statusLogs = PlayerErrorSheet.addSendLogs(statusOpenWith)
        // On a television Send logs is the last button, below Go back, so the first D-pad press
        // lands on the answer and the report is the quiet last stop.
        if (FormFactor.isTv(this)) {
            statusLogs?.let { logs ->
                (logs.parent as? android.view.ViewGroup)?.let { column ->
                    column.removeView(logs)
                    column.addView(logs)
                }
            }
        }
        statusCopy = findViewById<android.widget.Button>(R.id.status_copy).apply {
            text = L.playerCopyDetails
            setOnClickListener { copyErrorDetails() }
        }
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
            player = { playing },
            onVisibility = ::onControlsVisibilityChanged,
            onTogglePlay = ::togglePlayback,
            onPickSubtitles = { showTrackPicker(C.TRACK_TYPE_TEXT) },
            onPickAudio = { showTrackPicker(C.TRACK_TYPE_AUDIO) },
            onPickSpeed = ::pickSpeed,
            onQuickSpeed = ::swapSpeed,
            onCycleOrientation = ::cycleOrientation,
            onPlayEpisode = ::playEpisode,
            onEpisodes = ::openEpisodes,
            onCopies = ::openCopies,
            // The row's picture shape button: the next shape along, as W does on the desktop.
            onCycleShape = { applyScale(videoScale.next()) },
            onLock = ::lockScreen,
            // Back and forward beside play (R5): ten seconds each way, the figure on the button.
            onSeekButton = { forward -> jumpFromRemote(forward, fixedStepMs = Skip.BUTTON_MS) },
            // Icons only (user, 2026-11-19): the words under the six icons ([ControlLabels]) are not
            // shown on any opening; [showLabels] stays at its default, false.
            onBack = ::finish,
            onMore = ::showOverflow,
            onRemainingToggled = { remaining ->
                touchPrefs = touchPrefs.copy(showRemaining = remaining)
                lifecycleScope.launch {
                    runCatching { settings.updateTouchPrefs { it.copy(showRemaining = remaining) } }
                }
            },
        )
        controls?.previewArt = scrubArt.takeIf { Trickplay.PICTURES_SHOWN }
        // The text answers (and the resume offer) on both devices; the touch feedback around them
        // only ever fires from the phone's gestures.
        feedback = PlayerFeedback(findViewById(R.id.player_root), findViewById(R.id.overlay_container))
        // A tap on the brightness pill goes back to the system's level (WP-27).
        feedback?.onBrightnessReset = { setBrightness(TouchPrefs.BRIGHTNESS_SYSTEM) }
        morePanel = PlayerMorePanel(this, findViewById(R.id.player_root), moreHost)
        sheets = PlayerSheets(this, findViewById(R.id.player_root)) {
            detailsOpen = false
            holdControls()
            if (FormFactor.isTv(this)) {
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
        // The next-up card, the skip pill and the undo chip are three views built in code, and
        // nothing shows one before the video has played for a while. Built after the first frame
        // rather than in onCreate, which an ANR report from a MiTV caught inside their Buttons
        // (they are all null-safe until then).
        findViewById<View>(R.id.player_root).doOnPreDraw {
            if (!isFinishing && !isDestroyed) buildNextUpCard()
        }
        startTrickplay()
        startStillWatchingCounts()
        // Followed rather than read once, so turning it on in Settings with a video open applies
        // the next time the player is left. A phone's setting: a television never plays on unseen.
        if (!FormFactor.isTv(this)) {
            lifecycleScope.launch {
                runCatching { settings.backgroundAudio.collect { backgroundAudioOn = it } }
            }
            // Android 8 to 10 draw no buttons in the corner window themselves: ours come back here.
            if (PipActions.wanted() && hasPictureInPicture()) {
                pipReceiver = runCatching { PipActions.register(this, ::onPipButton) }.getOrNull()
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
                // And while the open profile is hidden behind its PIN (HX88): away from the app,
                // or locked again, the controls name only the app.
                kotlinx.coroutines.flow.combine(settings.appLock, com.tmplayer.data.ProfileUnlock.hidden()) { lock, hidden -> lock to hidden }.collect { (lock, hidden) ->
                    val private = (lock.pinSet && lock.onOpen) || hidden
                    if (private == sessionPrivate) return@collect
                    sessionPrivate = private
                    refreshSessionMetadata()
                }
            }
        }
        // Telegram ended the session (HX23): every request is refused from here on and the sign in
        // waits behind this window, so a video streamed from Telegram gives way to it rather than
        // ending on "Can't play this". A downloaded file plays on: it needs no session.
        lifecycleScope.launch {
            runCatching { Td.sessionEnded.collect { ended -> if (ended && downloadedVideo == null) leaveForSignIn() } }
        }
        // On a television too: the controls timeout and the remaining time readout apply there.
        lifecycleScope.launch {
            touchPrefs = runCatching { settings.touchPrefsNow() }.getOrDefault(TouchPrefs())
            applyTouchPrefs()
            // "Remember brightness" (WP-27): this video starts where the last one was left. Off by default.
            if (touchPrefs.rememberBrightness && !FormFactor.isTv(this@PlayerActivity)) {
                val saved = runCatching { settings.playerBrightnessNow() }.getOrDefault(TouchPrefs.BRIGHTNESS_SYSTEM)
                if (saved >= TouchPrefs.BRIGHTNESS_MIN) {
                    window.attributes = window.attributes.also { it.screenBrightness = saved / 100f }
                }
            }
        }
        renderControlsTitle()
        renderOrientationButton()
        controls?.setRotateAvailable(orientationHonoured())
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
            if (!pickerOpen) {
                payPauseRow()
                controls?.focusRow()
            }
        }
        // The manifest opts into the new back dispatch, and from Android 13 that means Back never
        // arrives in [dispatchKeyEvent] as a key: it comes here, and without this a locked screen,
        // the next-up card or the row up all ended the film on the first press.
        onBackPressedDispatcher.addCallback(this) {
            if (!peelOneOverlay()) leaveByBack()
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
        // FQ8: the button is drawn only once a TV is found (or a cast is under way). The search for
        // it runs 15 s each time the player comes into sight and stops by itself.
        val castButton = findViewById<View>(R.id.control_cast)?.apply { setOnClickListener { cast?.openPicker() } }
        val presence = com.tmplayer.cast.CastPresenceScan(support, lifecycleScope, canSearch = { com.tmplayer.cast.LocalNetworkPermission.granted(this) })
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                presence.start()
                try {
                    kotlinx.coroutines.awaitCancellation()
                } finally {
                    presence.stop()
                }
            }
        }
        lifecycleScope.launch {
            presence.shown.collect { shown ->
                castButton?.let { com.tmplayer.cast.showCastButton(it, shown, neighbour = findViewById(R.id.control_more)) }
            }
        }
        subtitleFiles.onCastFile = { file, mime, label -> cast?.addSubtitle(file, mime, label) == true }
        // Remembered per video, so a handover to VLC or MX Player later carries it (ExternalSubtitleFile).
        subtitleFiles.onAttached = { file ->
            if (hasMessage()) SubtitleMemory.at(this).remember(SubtitleMemory.keyOf(chatId, messageId), file.absolutePath)
        }
    }

    private val castHooks = object : PlayerCast.Hooks {
        override fun handOff(): Long? {
            val exo = playing ?: return resumeMs.takeIf { it > 0 }
            val at = exo.currentPosition
            exo.playWhenReady = false
            // Stopped, not paused: a paused player keeps reading ahead, and TDLib keeps fetching
            // for it, against the TV's reads of the same file.
            exo.stop()
            return at
        }

        override fun takeBack(positionMs: Long) {
            val exo = playing ?: return
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
            val uri = joined ?: downloadedVideo?.uri() ?: tdFileUri(fileId)
            val length = when {
                joined != null -> joined.getQueryParameter("zl")?.toLongOrNull() ?: joined.getQueryParameters("s").sumOf { it.toLongOrNull() ?: 0L }
                downloadedVideo != null -> downloadedVideo?.length() ?: 0L
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
            reportWatching()
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


    /**
     * The press that opened this video, if there was one (Play all or Shuffle, taken once by the video it started),
     * else what the intent carries from the video before it. A list's queue is the process's own: after a rebuild of
     * the process a list goes on as any video does, and a chat's order is rebuilt from the chat, the video and the seed.
     */
    private fun readPlayAll(from: Intent) {
        val pressed = com.tmplayer.data.PlayAll.take(chatId, messageId)
        if (pressed != null) {
            playAllMode = pressed.mode
            playAllSeed = pressed.seed
            playAllQueue = pressed.queue
            shufflePool = pressed.pool
            return
        }
        val mode = com.tmplayer.data.PlayAll.Mode.of(from.getIntExtra(EXTRA_PLAY_ALL, 0))
        if (mode == com.tmplayer.data.PlayAll.Mode.Off || (mode.ofList && playAllQueue.isEmpty())) {
            playAllMode = com.tmplayer.data.PlayAll.Mode.Off
            playAllSeed = 0L
            playAllQueue = emptyList()
            shufflePool = null
            return
        }
        playAllMode = mode
        playAllSeed = from.getLongExtra(EXTRA_PLAY_SEED, 0L)
    }

    /**
     * [next] to be opened as part of this run: the press is armed for the video that takes it ([com.tmplayer.data.PlayAll.take])
     * and the mode and seed go in the intent, so a player rebuilt later knows it too.
     */
    private fun carryPlayAll(intent: Intent, next: MediaItem): Intent {
        if (playAllMode == com.tmplayer.data.PlayAll.Mode.Off) return intent
        com.tmplayer.data.PlayAll.arm(
            com.tmplayer.data.PlayAll.Request(next.chatId, next.messageId, playAllMode, playAllSeed, playAllQueue, shufflePool),
        )
        return intent.putExtra(EXTRA_PLAY_ALL, playAllMode.ordinal).putExtra(EXTRA_PLAY_SEED, playAllSeed)
    }

    /** The video's own fields, from the intent that opened it or the one [continueUnseen] set. */
    private fun readItem(from: Intent) {
        fileId = from.getIntExtra(EXTRA_FILE_ID, 0)
        // A turn is about one file: the next video starts upright.
        pictureRotation = PictureRotation.NONE
        playerVolumeHinted = false
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
        readPlayAll(from)
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
        derivedFps = 0f
        fpsEstimator = null
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
        // The remedy ladder starts afresh for this video, with what the device list says here (RL6).
        remedies.newVideo(com.tmplayer.data.KnownDevices.remediesFor(com.tmplayer.data.KnownDevices.builtIn, EngineDevice.facts(), mediaMime.ifBlank { null }), active = true)
        // A scan of the video before is not this one's (PB2-15).
        cancelScan()
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
        itemScope.launch { keepRepeat = runCatching { settings.keepRepeatNow() }.getOrDefault(false) }
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
        findDatacenter()
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
                    playing?.seekTo(start)
                    resumeMs = start
                    return@launch
                }
            }
            if (resumeMs > 0) {
                playing?.seekTo(resumeMs)
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
                    playing?.seekTo(synced)
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
                    playing?.seekTo(start)
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
            val scaleRead = async { runCatching { VideoScale.remembered(settings.videoScaleNow()) }.getOrDefault(VideoScale.Fit) }
            val tracksRead = async { runCatching { settings.trackChoice(seriesKey) }.getOrDefault(TrackChoice()) }
            val trackPrefsRead = async { runCatching { settings.trackPreferencesNow() }.getOrDefault(TrackPreferences()) }
            val downmixRead = async { runCatching { settings.downmixChoiceNow() }.getOrNull() }
            val boostRead = async { runCatching { settings.volumeBoostNow() }.getOrDefault(false) }
            val scanRead = async { runCatching { settings.scanThenJumpNow() }.getOrDefault(false) }
            val nightRead = async { runCatching { settings.nightModeNow() }.getOrDefault(false) }
            val evenRead = async { runCatching { settings.evenVolumeNow() }.getOrDefault(true) }
            val playerVolumeRead = async { runCatching { settings.playerVolumeNow() }.getOrDefault(PlayerVolume.FULL) }
            val silenceRead = async { runCatching { settings.skipSilenceNow() }.getOrDefault(false) }
            val decoderRead = async { runCatching { settings.videoDecoderNow() }.getOrDefault(com.tmplayer.data.VideoDecoder.Automatic) }
            val tunnelRead = async { runCatching { settings.tunneledModeNow() }.getOrDefault(com.tmplayer.player.TunneledMode.Auto) }
            val passthroughRead = async { runCatching { settings.audioPassthroughNow() }.getOrDefault(true) }
            val statsRead = async { runCatching { settings.playbackStatsNow() }.getOrDefault(false) }
            val styleRead = async { runCatching { settings.subtitleStyleNow() }.getOrDefault(SubtitleStyle()) }
            // Only a video from a message has somewhere to keep an offset; a file opened from
            // elsewhere starts in sync and forgets any change on the way out.
            val delaysRead = async { if (hasMessage()) runCatching { settings.syncDelays(chatId, messageId) }.getOrDefault(SyncDelays()) else null }
            // A video cut into byte pieces plays as the one file it was, joined: none of its
            // pieces is a video on its own, whatever the download index or the cache holds of one.
            // A RAR, a 7z or a ZIP volume handed over (a link, Continue watching): what it is and
            // why it cannot play, rather than a decoder failure on bytes that are not a video.
            com.tmplayer.data.Archives.blockedOnOpen(mediaFileName, mediaMime, fileSizeBytes, mediaCaption)?.let { why ->
                showError(why, retryable = false)
                return@launch
            }
            val joined = mayBeJoined() && findPieces(session.client)
            if (joined && !prepareJoined(session.client)) return@launch
            // The download index first. Every way into this screen passes here, the next episode
            // and autoplay included, so a downloaded episode plays from its file however it was
            // reached.
            val downloaded = if (joined) null else LocalDownloads.videoFor(settings, chatId, messageId)
            downloadedVideo = downloaded
            if (joined) {
                // Decided for every piece in [prepareJoined].
            } else if (downloaded != null) {
                downloadComplete = true
                downloadedFraction = 1f
                if (fileSizeBytes <= 0) fileSizeBytes = downloaded.length() ?: 0L
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
            speedSwap.opened(playbackSpeed)
            controls?.showSpeed(playbackSpeed)
            resumeTracks = resumed
            // A fixed shape (16:9, 4:3...) is about this player's videos: the episode that plays
            // next in it keeps it, and only a new player goes back to the remembered one.
            scaleRead.await().let { remembered -> if (!videoScale.fixed) videoScale = remembered }
            // Read before the player is built, because the track selector is configured once and
            // a preference applied after the first frame means the first line of dialogue is in
            // the wrong language.
            tracks = tracksRead.await()
            val trackPrefs = trackPrefsRead.await()
            trackPlan = TrackRules.plan(resumed, tracks, trackPrefs, Translator.messages.tag)
            resetSuggestions(trackPlan, trackPrefs)
            settingsSubtitlesPending = trackPlan.subtitleSource == TrackSource.Settings &&
                (trackPlan.subtitles is SubtitlePlan.On || trackPlan.subtitles is SubtitlePlan.IfForeignAudio)
            downmixChoice = downmixRead.await()
            volumeBoostOn = boostRead.await()
            scanThenJump = scanRead.await()
            nightModeOn = nightRead.await()
            evenVolumeOn = evenRead.await()
            playerVolumePercent = playerVolumeRead.await()
            skipSilence = silenceRead.await()
            videoDecoder = decoderRead.await()
            tunneledMode = tunnelRead.await()
            audioPassthrough = AudioPolicy.passthroughAllowed(passthroughRead.await(), FormFactor.isTv(this@PlayerActivity))
            statsOn = statsRead.await()
            subtitleStyle = styleRead.await()
            // A look this video has of its own (WP-29) takes the place of the shared one.
            if (hasMessage()) {
                runCatching { settings.videoSubtitleStyle(chatId, messageId) }.getOrNull()?.let {
                    subtitleStyle = it
                    styleThisVideo = true
                }
            }
            applySubtitleStyle()
            delaysRead.await()?.let { syncDelays = it }
            applySubtitleDelay()
            // The saved position, given a moment more if it is still being read: set before
            // prepare(), the first request goes to where the video resumes, not to byte 0 and
            // then a seek away from it.
            if (resumeRead.isActive) withTimeoutOrNull(RESUME_WAIT_MS) { resumeRead.join() }
            if (!session.isCurrent()) return@launch
            // The engine's inputs (PR2): nothing is read, and ExoPlayer plays, unless VLC can run here.
            engineInput = runCatching { readEngineInput() }.getOrNull()
            remedies.memoryLoaded(runCatching { settings.remedyMemoryNow(RemedyFacts.today()) }.getOrDefault(com.tmplayer.data.RemedyMemory.Empty))
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
     * Whether the file plays with no seek index: Media3 knows its length but cannot seek in it
     * ([noteSeekIndex]). An MKV without Cues seeks by estimate instead ([CuelessMatroskaSeek]), so
     * this is what is left: one whose size or head could not be read.
     */
    private var noSeekIndex = false

    /**
     * Notes, as the player gets ready, whether the video can be sought in. Media3 takes every seek
     * into a file it has no seek map for as a seek to the start, so a bar that looks usable would
     * throw the viewer back to the beginning: the bar is turned off instead, and the viewer told.
     */
    private fun noteSeekIndex() {
        val exo = playing ?: return
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

    /**
     * Starts the video in the engine [EngineSelector] picks (PR2): ExoPlayer as always, or VLC when
     * this build has it, its library is on this device, the video is one VLC can take here
     * ([vlcCanTake]) and the viewer's choice, the device list or this device's history say so.
     * Without VLC here [engineInput] is null and this is [startExoPlayback], as before.
     */
    private fun startPlayback(client: TdlClient) {
        val decision = engineInput?.let { EngineSelector.choose(it.copy(stereo3d = stereo != Stereo3d.OFF)) }
        engineDecision = decision
        if (decision != null) Log.i(START_TAG, "Engine ${decision.engine.key} (${decision.reason}) for file $fileId")
        if (decision?.engine == com.tmplayer.data.PlayerEngine.Vlc && vlcCanTake()) {
            if (offerExoAfterCrashes(client)) return
            startVlcPlayback(client, carry = null)
            return
        }
        startExoPlayback(client)
    }

    /**
     * What the engine choice is made from, read with the other settings before the player is
     * built. Null, so ExoPlayer plays as it always has, unless VLC can run here at all.
     */
    private suspend fun readEngineInput(): EngineInput? {
        if (!EngineAvailability.vlcOffered) return null
        val store = settings
        engineSwitchOnError = runCatching { store.switchEngineOnErrorNow() }.getOrDefault(true)
        return EngineInput(
            choice = runCatching { store.engineChoiceNow() }.getOrDefault(EngineChoice.Automatic),
            device = EngineDevice.facts(),
            history = runCatching { store.engineHistoryNow() }.getOrDefault(EngineHistory.None),
            video = VideoFacts(mime = mediaMime.ifBlank { null }),
            vlcAvailable = true,
            vlcCrashes = EngineCrashWatch.crashes(this).count,
        )
    }

    /**
     * The viewer picked VLC and it has crashed on this device since the card last asked (PR6,
     * [EngineCrashes.offerExoPlayer]): the sheet offers ExoPlayer for this video, or VLC as chosen.
     * The choice in Settings is the viewer's and is not changed.
     */
    private var crashOfferUp = false

    private fun offerExoAfterCrashes(client: TdlClient): Boolean {
        val input = engineInput ?: return false
        val crashes = EngineCrashWatch.crashes(this)
        if (!EngineCrashes.offerExoPlayer(input.choice, crashes)) return false
        EngineCrashWatch.offered(this)
        castClient = client
        crashOfferUp = true
        // Play in ExoPlayer as the sheet's answer, focused, with Keep VLC under it ([showEngineOffer]).
        engineOffer = com.tmplayer.data.PlayerEngine.ExoPlayer
        showError(L.playerEngineCrashOffer(crashes.count), retryable = false)
        statusTitle.text = L.playerEngineCrashTitle
        statusEngineAlways?.apply {
            text = L.playerEngineKeepVlc
            visibility = View.VISIBLE
        }
        return true
    }

    /** The crash card's answer: this video in ExoPlayer ([exo]) or in VLC as the viewer chose. */
    private fun answerCrashOffer(exo: Boolean) {
        crashOfferUp = false
        val client = castClient ?: return
        hideFailureActions()
        failurePinned = false
        showStatus(if (exo) L.playerEngineTryingExoplayer else L.playerEngineTryingVlc)
        if (exo) {
            engineDecision = EngineDecision(com.tmplayer.data.PlayerEngine.ExoPlayer, EngineReason.VlcCrashed)
            startExoPlayback(client)
        } else {
            startVlcPlayback(client, carry = null)
        }
    }

    /** Settings, Playback's "Switch engine on decoder error", read with [readEngineInput]. */
    private var engineSwitchOnError = true

    /**
     * Whether VLC can play this video here: one file (not a video in pieces, which only ExoPlayer
     * joins), no cast
     * (the TV plays it), and, when it streams, a size known (the loopback server needs one) and a
     * chat not known to restrict saving: its bytes are never put on a socket, even 127.0.0.1's.
     */
    private fun vlcCanTake(): Boolean =
        joinedSource == null && !castUp() && fileId > 0 &&
            (downloadedVideo != null || (fileSizeBytes > 0 && savable != false))

    /** The engine on screen, as an [Engine]: VLC's, or ExoPlayer's wrapped for the switch. */
    private fun currentEngine(): Engine? = vlcEngine ?: player?.let(::ExoEngine)

    /** The menu's Try the other engine: offered only when there is another engine to try. */
    private fun otherEngineOffered(): Boolean {
        val input = engineInput ?: return false
        // A turned picture is drawn by Media3's pipeline, which VLC does not have.
        if (!vlcCanTake() || stereo != Stereo3d.OFF || pictureRotation != PictureRotation.NONE) return false
        val running = if (vlcEngine != null) com.tmplayer.data.PlayerEngine.Vlc else com.tmplayer.data.PlayerEngine.ExoPlayer
        return EngineSelector.toOffer(input, running) != null
    }

    /**
     * VLC for this video, read from the loopback server while it is not all downloaded and from
     * the file when it is. [carry] is what the engine before it was playing (a switch), null for
     * a fresh start at [resumeMs]. When libVLC does not start here the video goes on in
     * ExoPlayer, and VLC is not offered again in this version ([EngineAvailability.markLoadFailed]).
     */
    private fun startVlcPlayback(client: TdlClient, carry: EngineCarry?) {
        lifecycleScope.launch {
            val built = try {
                buildVlcEngine(client)
            } catch (e: Exception) {
                Log.w(START_TAG, "VLC did not start for file $fileId: ${e.message}")
                null
            }
            if (isFinishing || isDestroyed) {
                built?.first?.release()
                return@launch
            }
            if (built == null) {
                engineDecision = EngineDecision(com.tmplayer.data.PlayerEngine.ExoPlayer, EngineReason.VlcUnavailable)
                carry?.let { resumeMs = it.positionMs; pendingCarry = it }
                startExoPlayback(client)
                return@launch
            }
            val (vlc, source) = built
            vlcEngine = vlc
            // profile-notifications: sessionMetadata(), private while the profile is hidden
            vlc.mediaItem = androidx.media3.common.MediaItem.Builder().setMediaMetadata(sessionMetadata()).build()
            vlc.player.addListener(playerListener)
            // Asked before it opens, so libVLC is not paused as it starts ([VlcEngine.prepare]).
            if (carry?.playWhenReady != false) vlc.play()
            vlc.prepare(source, carry?.positionMs ?: resumeMs.coerceAtLeast(0L))
            for (file in subtitleFiles.files) vlc.addSubtitleFile(file.uri.toString())
            vlc.speed = carry?.speed ?: playbackSpeed
            vlc.volume = if (keyMuted) 0f else carry?.volume ?: PlayerVolume.gain(playerVolumePercent)
            vlc.setDelays(syncDelays.audioMs, syncDelays.subtitleMs)
            crashNote.began("libvlc")
            startStats()
            // The tracks are not listed yet: the engine puts them back as they are ([VlcEngine.restore]).
            carry?.let(vlc::restore)
            if (inBackgroundAudio) hideVideoUnseen(vlc.player)
            markStart("prepare asked", STAGE_PREPARED)
            wakeEndWatch()
            castClient = client
            attachMediaSession(vlc.player)
            attachSurface(vlc.player)
        }
    }

    /** libVLC's player over this video's bytes, or null when the library does not start here. */
    private suspend fun buildVlcEngine(client: TdlClient): Pair<VlcEngine, EngineSource>? {
        val manager = getSystemService(Context.ACTIVITY_SERVICE) as? android.app.ActivityManager
        val lowMemory = manager == null || manager.isLowRamDevice || manager.memoryClass < LOW_HEAP_MB
        val options = VlcOptions(hardwareDecoding = videoDecoder != com.tmplayer.data.VideoDecoder.Software, lowMemory = lowMemory)
        val backend = VlcRuntime.backend(this, options)
        if (backend == null) {
            EngineAvailability.markLoadFailed(this)
            return null
        }
        var stream: com.tmplayer.cast.LoopbackStream? = null
        val source = try {
            val local = downloadedVideo
            val path = local?.file?.absolutePath
            when {
                path != null -> EngineSource.File(path)
                local != null -> EngineSource.Content(local.uri().toString())
                else -> {
                    vlcStreamFailed = false
                    val opened = withContext(Dispatchers.IO) {
                        com.tmplayer.cast.LoopbackStream.start(
                            streaming = DataSourceCastSource(TdDataSource.Factory(client), tdFileUri(fileId), fileSizeBytes),
                            downloaded = null,
                            mime = mediaMime.ifBlank { "video/*" },
                            name = mediaFileName.ifBlank { "video" },
                            config = com.tmplayer.cast.LoopbackStream.Config(
                                onFailure = {
                                    vlcStreamFailed = true
                                    Log.w(START_TAG, "VLC's stream could not read: ${it.message}")
                                },
                            ),
                        )
                    }
                    stream = opened
                    EngineSource.Stream(opened.url)
                }
            }
        } catch (e: Exception) {
            backend.release()
            throw e
        }
        val app = applicationContext
        val engine = VlcEngine(
            backend,
            onRelease = { runCatching { stream?.close() } },
            // PR6: a mark while VLC plays, so a native crash is known at the next start.
            onActive = { on -> EngineCrashWatch.active(app, on) },
        )
        return engine to source
    }

    /** VLC's loopback stream gave up on a read: a failure then is the line's, not the decoder's. */
    @Volatile
    private var vlcStreamFailed = false

    /** Track languages, speed and volume to put back once the new engine lists its tracks ([switchEngine]). */
    private var pendingCarry: EngineCarry? = null

    /** Lets go of the VLC engine, its surface and its loopback stream; nothing when VLC is not playing. */
    private fun releaseVlc() {
        val vlc = vlcEngine ?: return
        vlcEngine = null
        runCatching { vlc.player.removeListener(playerListener) }
        runCatching { touchSurface?.player = null }
        vlc.release()
    }

    /** Try the other engine (the menu's line, the failure sheet's button), at the same place. */
    private fun switchEngine() {
        val input = engineInput ?: return
        val running = if (vlcEngine != null) com.tmplayer.data.PlayerEngine.Vlc else com.tmplayer.data.PlayerEngine.ExoPlayer
        val target = EngineSelector.toOffer(input.copy(stereo3d = stereo != Stereo3d.OFF), running) ?: return
        if (target == com.tmplayer.data.PlayerEngine.Vlc && !vlcCanTake()) return
        moveToEngine(target, EngineReason.UserChoice)
    }

    /**
     * The video into [target]: the place, the track languages, the speed and the volume carried,
     * the engine before it released in full first (a 1 GB stick holds one), the resume point
     * written on the way.
     */
    private fun moveToEngine(target: com.tmplayer.data.PlayerEngine, reason: EngineReason, status: String? = null) {
        val client = castClient ?: return
        // A scan waiting on the old player must not land on the new one (PB2-15).
        cancelScan()
        val carry = currentEngine()?.carry() ?: EngineCarry(positionMs = resumeMs, speed = playbackSpeed)
        Log.i(START_TAG, "Engine switch to ${target.key} ($reason) at ${carry.positionMs} ms, file $fileId")
        saveResumePosition()
        engineOffer = null
        hideFailureActions()
        failurePinned = false
        showStatus(status ?: if (target == com.tmplayer.data.PlayerEngine.Vlc) L.playerEngineTryingVlc else L.playerEngineTryingExoplayer)
        releasePlayerAndSurface()
        engineDecision = EngineDecision(target, reason)
        if (target == com.tmplayer.data.PlayerEngine.Vlc) {
            startVlcPlayback(client, carry)
        } else {
            resumeMs = carry.positionMs
            pendingCarry = carry
            startExoPlayback(client)
        }
    }

    /**
     * "Always use VLC on this device": the engine setting to VLC, then this video in it. Not
     * under the kids lock, which keeps Settings as they are (the button is not shown there).
     */
    private fun alwaysUseVlc() {
        if (com.tmplayer.data.ChatGate.allowed.value != null) return
        val store = settings
        com.tmplayer.App.backgroundScope.launch { runCatching { store.setEngineChoice(EngineChoice.Vlc) } }
        engineInput = engineInput?.copy(choice = EngineChoice.Vlc, history = EngineHistory.None)
        moveToEngine(com.tmplayer.data.PlayerEngine.Vlc, EngineReason.UserChoice)
        feedback?.message(L.playerEngineAlwaysVlcDone, holdMs = SEEK_NOTICE_MS)
    }

    /**
     * A failure when there is a second engine here (PR2). ExoPlayer failing to decode goes to VLC
     * by itself once per video, under Automatic with "Switch engine on decoder error" on
     * ([EngineSelector.afterFailure]); otherwise the failure sheet offers VLC beside its other
     * ways out ([engineOffer]). VLC failing offers ExoPlayer; both failing brings the last sheet
     * ([showBothEnginesFailed]). True when the failure was dealt with here.
     */
    private fun handleEngineError(error: PlaybackException): Boolean {
        val input = engineInput ?: return false
        val vlcRunning = vlcEngine != null
        val running = if (vlcRunning) com.tmplayer.data.PlayerEngine.Vlc else com.tmplayer.data.PlayerEngine.ExoPlayer
        val failure = if (vlcRunning) {
            if (vlcStreamFailed) EngineFailure.Source else EngineFailure.Decoder
        } else {
            EngineFailures.of(error.errorCode)
        }
        Log.w(START_TAG, "Engine ${running.key} failed (${failure.name}, ${error.errorCodeName}) on file $fileId")
        if (failure == EngineFailure.Decoder) {
            enginesFailed += running
            engineInput = input.copy(history = input.history.failed(running))
            val store = settings
            com.tmplayer.App.backgroundScope.launch { runCatching { store.noteEngineFailure(running) } }
        }
        if (!vlcRunning) {
            if (failure != EngineFailure.Decoder || !vlcCanTake()) return false
            val running0 = engineDecision ?: EngineDecision(running, EngineReason.Default)
            val auto = if (engineSwitchOnError && remedies.allowsEngineSwitch()) EngineSelector.afterFailure(input, running0, failure, engineSwitches) else null
            if (auto != null && com.tmplayer.data.PlayerEngine.Vlc !in enginesFailed) {
                engineSwitches++
                remedies.engineSwitched()
                moveToEngine(com.tmplayer.data.PlayerEngine.Vlc, EngineReason.SwitchedAfterFailure)
                feedback?.message(L.playerEngineSwitchedVlc, holdMs = SEEK_NOTICE_MS)
                return true
            }
            if (com.tmplayer.data.PlayerEngine.Vlc in enginesFailed) return showBothEnginesFailed()
            engineOffer = EngineSelector.toOffer(input.copy(stereo3d = stereo != Stereo3d.OFF), running)
            // The usual handling goes on, and its sheet carries the VLC button ([showFailureActions]).
            return false
        }
        lastPlaybackError = error
        if (failure == EngineFailure.Decoder && com.tmplayer.data.PlayerEngine.ExoPlayer in enginesFailed) return showBothEnginesFailed()
        engineOffer = com.tmplayer.data.PlayerEngine.ExoPlayer
        showError(if (failure == EngineFailure.Source) friendlyError(error) else L.playerEngineVlcFailed, retryable = true)
        return true
    }

    /** Neither engine could decode this video: Try again, Send logs and Open in another app. */
    private fun showBothEnginesFailed(): Boolean {
        engineOffer = null
        showError(L.playerEngineBothFailed, retryable = false)
        statusRetry?.apply {
            visibility = View.VISIBLE
            statusRetry?.let(::markPrimaryAction)
            requestFocus()
        }
        return true
    }

    /**
     * The failure sheet's engine buttons, when [engineOffer] names one: the other engine as the
     * answer ([primary]), and under it "Always use VLC on this device" after ExoPlayer failed,
     * unless the viewer already chose VLC or the kids lock is on.
     */
    private fun showEngineOffer(primary: Boolean): Boolean {
        val offer = engineOffer
        if (offer == null) {
            statusEngine?.visibility = View.GONE
            statusEngineAlways?.visibility = View.GONE
            return false
        }
        val vlc = offer == com.tmplayer.data.PlayerEngine.Vlc
        statusEngine?.apply {
            text = if (vlc) L.playerEnginePlayVlc else L.playerEnginePlayExoplayer
            visibility = View.VISIBLE
            if (primary) {
                markPrimaryAction(this)
                requestFocus()
            }
        }
        val always = vlc && engineInput?.choice != EngineChoice.Vlc && com.tmplayer.data.ChatGate.allowed.value == null
        statusEngineAlways?.visibility = if (always) View.VISIBLE else View.GONE
        return true
    }

    /** Builds the player, points it at the file, and puts a video surface in front of it. */
    private fun startExoPlayback(client: TdlClient) {
        val exo = buildPlayer(client).also { built ->
            markStart("player built", STAGE_BUILT)
            built.addListener(playerListener)
            // A plain file for a download, which the default data source reads directly; the TDLib
            // stream for everything else, carrying its type so the right reader is tried first.
            val source = joinedSource ?: downloadedVideo?.uri()
                ?: tdFileUri(fileId, streamType(mediaMime, mediaFileName))
            // A stream also holds the Wi-Fi up while it plays, which matters once the screen is
            // off and the sound plays on ([PlaybackService]); a file on the disk needs only the CPU.
            val local = if (joinedSource != null) joinedAllLocal else downloadedVideo != null
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
        // M's mute holds across a new engine (a remedy restart, a decoder fallback): only a new
        // video starts with the sound on again ([forgetTheLastVideo], a new screen) (PB-06).
        exo.volume = PlayerVolume.gain(playerVolumePercent, keyMuted)
        crashNote.began("media3")
        startStats()
        traceRecorder.attach(exo)
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
    private fun attachSurface(exo: Player) {
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
        controls?.setShape(videoScale)
        // On a television only while 3D is drawn for a headset, which needs a copy per eye.
        view.findViewById<View>(androidx.media3.ui.R.id.exo_content_frame)
            ?.addOnLayoutChangeListener { frame, _, _, _, _, _, _, _, _ ->
                fitSubtitlesTo(frame)
                // A phone turned or another size of picture: the zoom stays, its move held inside.
                reclampZoom()
            }
        // PlayerView sets the frame's aspect from each video size it hears, after this activity
        // heard it: a fixed shape is put back once it has, rather than fought over per event.
        (view.findViewById<View>(androidx.media3.ui.R.id.exo_content_frame) as? AspectRatioFrameLayout)
            ?.setAspectRatioListener { target, _, _ ->
                val ratio = videoScale.ratio?.toFloat() ?: return@setAspectRatioListener
                if (!shapeInPipeline() && kotlin.math.abs(target - ratio) > 0.001f) view.post { renderShape() }
            }
        // The pipeline draws the shape at the view's size, so a new size (a phone turned, a
        // window resized) is a new shape to draw. Picture in picture keeps the last one.
        view.addOnLayoutChangeListener { _, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
            if (right - left != oldRight - oldLeft || bottom - top != oldBottom - oldTop) view.post { renderShape() }
        }
        renderShape()

        if (!FormFactor.isTv(this)) {
            // Fed from dispatchTouchEvent rather than attached here: see [PlayerGestures].
            gestures = PlayerGestures(
                context = this,
                window = window,
                onSkip = ::skipBy,
                onSeekStep = { zone, total, x, y -> feedback?.seekStep(zone, total, x, y) },
                onLevel = { left, fraction, brightness -> feedback?.level(left, fraction, brightness) },
                onScrub = { delta, target, length, rate -> gestureScrubbing = true; feedback?.scrub(delta, target, length, rate) },
                onScrubEnd = { gestureScrubbing = false; feedback?.scrubEnded() },
                onPinch = ::pinchScale,
                zoomAllowed = ::zoomAllowed,
                zoomed = { zoom.zoomed },
                onZoom = ::zoomBy,
                onZoomEnd = ::zoomEnded,
                onPan = ::panZoom,
                positionMs = { playing?.currentPosition ?: 0L },
                durationMs = { playing?.duration?.takeIf { it > 0 } ?: 0L },
                onSeekTo = { at -> if (!refuseSeek()) playing?.seekTo(at) },
                onHold = ::holdFastForward,
                onTapControls = ::toggleControls,
                onTogglePlay = ::togglePlaybackFromPicture,
                isOnChrome = { x, y ->
                    controls?.isOnChrome(x, y) == true || isOnNextUpCard(x, y) || isOnSkipPill(x, y) || isOnUndoChip(x, y) ||
                        feedback?.isOnZoomChip(x, y) == true || feedback?.isOnBrightnessPill(x, y) == true
                },
                systemEdges = ::systemGestureEdges,
                onDragBegin = { controls?.hideAnimated() },
                onBrightnessSettled = { level -> saveBrightness((level * 100).roundToInt()) },
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
            // The chips stand at the start or the end of the screen, which right to left are the
            // other way round: each keeps clear of the inset on its own side (R8, 2026-11-16).
            val side = SideInsets.of(safe.left, safe.right, root.layoutDirection == View.LAYOUT_DIRECTION_RTL)
            corner?.updatePaddingRelative(end = side.end, top = safe.top + underTheBar)
            nextUpCard?.view?.updateLayoutParams<FrameLayout.LayoutParams> {
                SideInsets.atEnd(this, side.end + NextUpCard.marginPx(root))
                bottomMargin = safe.bottom + NextUpCard.bottomPx(root)
            }
            skipPill?.view?.updateLayoutParams<FrameLayout.LayoutParams> {
                SideInsets.atEnd(this, side.end + NextUpCard.marginPx(root))
                bottomMargin = safe.bottom + NextUpCard.bottomPx(root)
            }
            // "Back to" on the start side keeps clear of a cutout or the bar in landscape too.
            undoChip?.view?.updateLayoutParams<FrameLayout.LayoutParams> {
                SideInsets.atStart(this, side.start + NextUpCard.marginPx(root))
                bottomMargin = safe.bottom + NextUpCard.bottomPx(root)
            }
            suggestChip?.view?.updateLayoutParams<FrameLayout.LayoutParams> {
                SideInsets.atStart(this, side.start + NextUpCard.marginPx(root))
                bottomMargin = safe.bottom + SubtitleOfferChip.bottomOver(root)
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
        val exo = playing ?: return
        val nowPlaying = !exo.isPlaying
        if (!nowPlaying) raiseRowForPause()
        if (nowPlaying) exo.play() else exo.pause()
    }

    /**
     * The row around a pause and a resume ([PauseRow]); under the More panel or a track picker it
     * waits for them to close, so the pause never takes their focus (WP-19).
     */
    private val pauseRow = PauseRow(
        controls = { controls },
        controlsUp = { controlsUp },
        blocked = { statusOverlay.visibility == View.VISIBLE || locked || inPictureInPicture },
        stillWatching = { stillWatchingUp },
        underPanel = { morePanel?.isOpen == true || TrackPickerFragment.isOpen(supportFragmentManager) },
        pausedNow = { playing?.playWhenReady == false },
        sayPaused = { showGestureFeedback(L.commonPause) },
    )

    private fun payPauseRow() = pauseRow.pay()

    private fun raiseRowForPause() = pauseRow.raiseForPause()

    /**
     * A pause or a resume, from anywhere: the row, a key, the picture, the remote, a headset, the
     * notification, the headphones coming out. See [PauseRow] and [PauseControls].
     */
    private fun onPauseChanged(playWhenReady: Boolean, reason: Int) =
        pauseRow.changed(playWhenReady, endOfItem = reason == Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM)

    /** Adds up a run of key jumps on one side, so the figure counts 10, 20, 30 like the double tap. */
    private val jumpRun = SeekCounter()
    private var jumpRunSeconds = 0L

    /** When the last jump from a remote key landed, for [PlayerRemoteKeys.jumpStepMs]'s pacing. */
    private var lastRemoteJumpAt = 0L

    /**
     * A remote's jump, from an arrow over the bare picture or the rewind and fast forward keys:
     * the seek lands at once, and the only thing drawn is the double tap's chevrons and the run's
     * total on the side the jump went.
     */
    private fun jumpFromRemote(forward: Boolean, repeatCount: Int = 0, fixedStepMs: Long? = null, eventMs: Long? = null) {
        // A television remote is 10 s both ways; a phone's keyboard keeps Skip's 5 back and 10
        // forward, and so do Media3's increments and the notification, which read Skip directly.
        val base = fixedStepMs ?: PlayerRemoteKeys.remoteBaseMs(forward, FormFactor.isTv(this))
        // A held arrow, rewind or fast forward scans in growing steps, a few a second, on every
        // remote: one TDLib seek per key repeat made a held key stall the player on a 1 GB stick.
        // The larger steps are held to a share of the length, so a short video is not overshot.
        val now = SystemClock.uptimeMillis()
        val duration = playing?.duration ?: 0L
        // A repeat that waited behind a slow seek ([eventMs], a television's remote) is dropped, so
        // the video stops when the key is let go (PB-04).
        val step = when {
            fixedStepMs != null -> base
            eventMs != null -> PlayerRemoteKeys.pictureStepMs(repeatCount, base, eventMs, now, lastRemoteJumpAt, duration)
            else -> PlayerRemoteKeys.jumpStepMs(repeatCount, base, now, lastRemoteJumpAt, duration)
        }
        if (step <= 0) return
        lastRemoteJumpAt = now
        skipBy(if (forward) step else -step)
        val zone = if (forward) TapZone.Right else TapZone.Left
        if (jumpRun.tap(zone, SystemClock.uptimeMillis()) == 1) jumpRunSeconds = 0
        jumpRunSeconds += step / 1000
        feedback?.jump(zone, jumpRunSeconds, PlayerRemoteKeys.pace(step, base))
    }

    /**
     * The scan option (WP-79): a held arrow, from its [PlayerRemoteKeys.SCAN_FROM_REPEAT]th repeat on,
     * moves a virtual position (the bar's thumb and a card with the time) and seeks nothing; one
     * seek lands when the key is let go ([commitScan]) or none repeats for [PlayerRemoteKeys.SCAN_IDLE_MS].
     * False where the key should seek live as before: a first press and the first repeats, a video
     * with no length, a cast or the other engine, a file that cannot be sought.
     */
    private fun scanFromRemote(forward: Boolean, event: KeyEvent): Boolean {
        // A fresh press while a scan waits lands it first; the press itself is an ordinary jump.
        if (event.repeatCount == 0) {
            if (remoteScan.target != null) commitScan()
            return false
        }
        if (remoteScan.target == null && event.repeatCount < PlayerRemoteKeys.SCAN_FROM_REPEAT) return false
        val exo = playing ?: return false
        val duration = exo.duration.takeIf { it > 0L && it != C.TIME_UNSET } ?: return false
        if (castUp() || vlcEngine != null || refuseSeek()) return false
        val starting = remoteScan.target == null
        val next = remoteScan.step(exo.currentPosition, forward, event.repeatCount, event.eventTime, SystemClock.uptimeMillis(), duration, END_GUARD_MS)
        if (next != null) {
            controls?.scanTo(next)
            if (starting) hintScan()
        }
        loopHandler.removeCallbacks(scanIdle)
        loopHandler.postDelayed(scanIdle, PlayerRemoteKeys.SCAN_IDLE_MS)
        return true
    }

    /** The one seek of a scan, to where it stopped; the card and the scrub mode go away first. */
    private fun commitScan() {
        loopHandler.removeCallbacks(scanIdle)
        val to = remoteScan.commit() ?: return
        controls?.endScan()
        // Asked again on landing: a sheet, a cast or a video that cannot seek may have come since (PB2-15).
        if (refuseSeek() || castUp() || vlcEngine != null) return
        playing?.seekTo(to)
    }

    /** Drops a scan without a seek. */
    private fun cancelScan() {
        loopHandler.removeCallbacks(scanIdle)
        if (remoteScan.target == null) return
        remoteScan.cancel()
        controls?.endScan()
    }

    /** "Let go to jump here", on the first three scans, through the hint registry (WP-68). */
    private fun hintScan() {
        lifecycleScope.launch {
            val state = runCatching { settings.hintState.first() }.getOrNull()
            // A scan is the viewer steering, not a video playing on: the registry's playback rule does not apply.
            if (!com.tmplayer.ui.components.hintAllowed(state, com.tmplayer.data.HintIds.SCAN_JUMP, maxShows = 3, inPlayback = false)) return@launch
            runCatching { settings.bumpHint(com.tmplayer.data.HintIds.SCAN_JUMP) }
            showGestureFeedback(L.playerScanReleaseToJump)
        }
    }

    /**
     * Page Up and Page Down, row up or down: the previous or next chapter where the file has
     * chapters, else a minute back or forward. Once per press, so a held key is not a run of
     * seeks on a slow stick.
     */
    private fun pageFromRemote(forward: Boolean) {
        if (chapters.isNotEmpty()) stepChapter(forward) else jumpFromRemote(forward, fixedStepMs = PlayerRemoteKeys.PAGE_JUMP_MS)
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
        if (downloadedVideo != null || joinedSource != null) emptyList() else postItem?.qualities.orEmpty()

    /**
     * Plays [copy] in place of the copy playing, at the same moment ([VideoCopies.carryOver]
     * allowing for a copy a little longer or shorter), and remembers it as the copy for this video
     * and the quality for the next episode. The place is per copy: this one keeps its own line,
     * and the new copy's is read as the newest from then on.
     */
    private fun switchCopy(copy: MediaItem, remember: Boolean = true) {
        if (copy.id == currentItem().id) return
        val at = playing?.currentPosition ?: 0L
        val from = playing?.duration?.takeIf { it > 0 } ?: durationSec * 1000L
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
        if (!hasMessage() || joinedSource != null || downloadedVideo != null || playingQuality != 0) return
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
        val at = playing?.currentPosition ?: 0L
        if (at > 0) BookmarkJump.request(chatId, messageId, at)
        com.tmplayer.data.QualityWish.request(chatId, messageId, height)
        playEpisode(currentItem())
    }

    /** "Play Telegram's 480p" on the sheet that says nothing is arriving: the next copy down. */
    private fun lighterQuality(): com.tmplayer.data.TelegramQuality? {
        val fresh = postItem ?: return null
        if (downloadedVideo != null || joinedSource != null) return null
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
        if (surface == null || inPictureInPicture || pickerOpen || morePanel?.isOpen == true ||
            statusOverlay.visibility == View.VISIBLE
        ) {
            // A hold or a scrub that was under way when the sheet came up still has to end.
            if (event.actionMasked == android.view.MotionEvent.ACTION_UP ||
                event.actionMasked == android.view.MotionEvent.ACTION_CANCEL
            ) {
                gestures?.cancel()
            }
            return super.dispatchTouchEvent(event)
        }
        // A touch that starts on the zoom's figure is the figure's tap (Reset zoom), controls or not.
        if (event.actionMasked == android.view.MotionEvent.ACTION_DOWN) {
            touchOnZoomChip = feedback?.isOnZoomChip(event.rawX, event.rawY) == true ||
                feedback?.isOnBrightnessPill(event.rawX, event.rawY) == true
        }
        gestures?.onTouchEvent(event, surface.width, surface.height)
        if (controlsUp || touchOnZoomChip) return super.dispatchTouchEvent(event)
        return true
    }

    /** True for the whole of a touch that began on the zoom's figure. */
    private var touchOnZoomChip = false

    // ---- free zoom (phone) --------------------------------------------------------------------

    private fun contentFrame(): View? = touchSurface?.findViewById(androidx.media3.ui.R.id.exo_content_frame)

    /**
     * Whether a pinch zooms freely now. Not on a television (no gestures there at all), not
     * while locked, in the corner window or casting, not in 3D (a headset's two
     * halves and the anaglyph are drawn for the whole frame), and not where the video surface
     * cannot follow a view's scale (Android 6's SurfaceView, [videoFollowsTransforms]); there the
     * pinch steps the shape as it always did.
     */
    private fun zoomAllowed(): Boolean =
        !FormFactor.isTv(this) && !locked && !inPictureInPicture && !stereo.on &&
            cast?.casting != true && videoFollowsTransforms(touchSurface?.videoSurfaceView)

    /**
     * The view the picture is in, and the picture's own size there at 100 per cent: the frame as
     * laid out, or where the effects pipeline draws the shape, the box inside it.
     */
    private fun zoomFrame(): PictureZoom.Frame? {
        val view = touchSurface ?: return null
        val frame = contentFrame() ?: return null
        var width = frame.width
        var height = frame.height
        if (shapeInPipeline()) {
            videoScale.pictureSize(frame.width, frame.height, pipelineAspect()).let { (w, h) -> width = w; height = h }
        }
        return PictureZoom.Frame(view.width.toFloat(), view.height.toFloat(), width.toFloat(), height.toFloat())
            .takeIf { it.valid }
    }

    /** One step of a pinch, from the gestures, in window pixels. */
    private fun zoomBy(factor: Float, focusX: Float, focusY: Float, dx: Float, dy: Float) {
        val frame = zoomFrame() ?: return
        // The row would only be in the way of the picture being looked at.
        if (controlsUp) controls?.hideAnimated()
        val origin = IntArray(2)
        touchSurface?.getLocationInWindow(origin)
        zoom = zoom.pinch(factor, focusX - origin[0], focusY - origin[1], frame).panBy(dx, dy, frame)
        renderZoom()
        showZoom(live = true)
    }

    /** The fingers lifted: just over 100 per cent settles on 100, and the figure fades soon after. */
    private fun zoomEnded() {
        zoom = zoomFrame()?.let(zoom::settled) ?: PictureZoom.NONE
        renderZoom()
        showZoom(live = false)
    }

    /** A one finger drag on the enlarged picture. */
    private fun panZoom(dx: Float, dy: Float) {
        val frame = zoomFrame() ?: return
        zoom = zoom.panBy(dx, dy, frame)
        renderZoom()
        showZoom(live = false)
    }

    private fun showZoom(live: Boolean) {
        val percent = L.messages.formatter.scalePercent(zoom.percent)
        feedback?.zoom(L.playerZoomPercent(percent), live) { resetZoom() }
    }

    /**
     * Back to the picture as the shape draws it: the figure's tap, More options' Reset zoom. [say]
     * leaves "Zoom 100%" up a moment as the answer; a new shape says its own name instead.
     */
    private fun resetZoom(say: Boolean = true) {
        val was = zoom.zoomed
        zoom = PictureZoom.NONE
        renderZoom()
        if (say && was) showZoom(live = false) else feedback?.zoomGone()
    }

    /** The zoom held inside a frame that changed size. */
    private fun reclampZoom() {
        // The corner window is a passing size that shows no zoom: held against it, the move
        // would be lost on the way back to full screen.
        if (!zoom.zoomed || inPictureInPicture) return
        zoom = zoomFrame()?.let(zoom::clampedTo) ?: PictureZoom.NONE
        renderZoom()
    }

    /**
     * Puts [zoom] on the picture's frame as a scale about its centre and a move: the GPU does it as
     * part of drawing the window (and the window manager for a SurfaceView), so nothing is decoded
     * or allocated per frame and the FFmpeg path is the same as the hardware one. The corner window
     * always shows the whole picture; the zoom comes back as it leaves. The subtitles are not in the
     * frame and keep the fitted picture's place ([fitSubtitlesTo]), so they are never enlarged or
     * carried off the screen.
     */
    private fun renderZoom() {
        val frame = contentFrame() ?: return
        val shown = if (inPictureInPicture) PictureZoom.NONE else zoom
        if (frame.scaleX == shown.scale && frame.scaleY == shown.scale &&
            frame.translationX == shown.panX && frame.translationY == shown.panY
        ) {
            return
        }
        frame.scaleX = shown.scale
        frame.scaleY = shown.scale
        frame.translationX = shown.panX
        frame.translationY = shown.panY
    }

    /**
     * Where the free zoom cannot run ([zoomAllowed]: Android 6, 3D), a pinch steps the fitting one
     * stop, out towards filling the screen or back towards the video's own shape. Only over
     * Original, Fill and Stretch: a pinch is about filling the screen, and from a fixed shape it
     * starts at Original.
     */
    private fun pinchScale(expanding: Boolean) {
        val target = videoScale.pinch(wider = expanding)
        if (target == videoScale) return
        applyScale(target)
    }

    /**
     * Runs the video fast for as long as a finger is held on it, then puts it back.
     *
     * It returns to the speed the viewer chose, not 1x: somebody watching at 1.5x who holds to skip
     * an aside expects to be back at 1.5x when they let go.
     */
    /** True while the fast-forward key is held down at the hold speed (U3, television and keyboards). */
    private var remoteHolding = false

    private fun endRemoteHold() {
        if (!remoteHolding) return
        remoteHolding = false
        holdFastForward(false)
    }

    private fun holdFastForward(holding: Boolean) {
        val exo = playing ?: return
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
        requestedOrientation = effectiveOrientation().requested
    }

    /**
     * Whether Android does what the rotate button asks (WP-37): not on a tablet, an unfolded
     * foldable or in a window shared with another app ([ScreenOrientation.honoured]).
     */
    private fun orientationHonoured(): Boolean = ScreenOrientation.honoured(
        tv = FormFactor.isTv(this),
        smallestWidthDp = resources.configuration.smallestScreenWidthDp,
        multiWindow = android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N && isInMultiWindowMode,
    )

    /** The viewer's choice where it is honoured, otherwise the device's own way ([ScreenOrientation.Follow]). */
    private fun effectiveOrientation(): ScreenOrientation =
        if (orientationHonoured()) orientation else ScreenOrientation.Follow

    /** The rotate button comes and goes with the window: a fold, a split screen, a resize. */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (FormFactor.isTv(this)) return
        // The corner window is a window shared with others: going in and out of it wrote Follow
        // and then the viewer's choice again, which could turn a phone twice on the way out
        // (PB2-13). It keeps the orientation it had; only a real change of the answer writes it.
        if (inPictureInPicture || (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N && isInPictureInPictureMode)) return
        val honoured = orientationHonoured()
        controls?.setRotateAvailable(honoured)
        if (honoured == lastHonoured) return
        lastHonoured = honoured
        if (!locked) applyOrientation()
    }

    /** What [orientationHonoured] said at the last configuration change, so only a change of it writes the orientation. */
    private var lastHonoured: Boolean? = null

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
        // The frame's place as laid out, without the zoom's scale and move (renderZoom): the lines
        // keep the fitted picture's place, which is always on the screen, and their size.
        val parent = frame.parent as? View
        if (parent != null) {
            parent.getLocationInWindow(at)
            at[0] += frame.left
            at[1] += frame.top
        } else {
            frame.getLocationInWindow(at)
        }
        root.getLocationInWindow(origin)
        // Where the effects pipeline draws the shape, the frame is the whole view and the picture
        // a box inside it, which is where the lines belong. A headset's halves stay whole.
        var pictureWidth = frame.width
        var pictureHeight = frame.height
        if (!vr && shapeInPipeline()) {
            videoScale.pictureSize(frame.width, frame.height, pipelineAspect()).let { (width, height) ->
                at[0] += (frame.width - width) / 2
                at[1] += (frame.height - height) / 2
                pictureWidth = width
                pictureHeight = height
            }
        }
        // Clamped to the screen, for Zoom, where the picture runs off both sides.
        val left = (at[0] - origin[0]).coerceIn(0, root.width)
        val top = (at[1] - origin[1]).coerceIn(0, root.height)
        val right = (at[0] - origin[0] + pictureWidth).coerceIn(left, root.width)
        val bottom = (at[1] - origin[1] + pictureHeight).coerceIn(top, root.height)
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
        SubtitleScripts.typefaceFor(this, cues)?.let { face ->
            if (face !== subtitleScriptFace) {
                subtitleScriptFace = face
                applySubtitleStyle()
            }
        }
        subtitleView.setCues(cues)
        subtitleTwin?.setCues(cues)
    }

    /** Fits the subtitles again after 3D changed, as the picture's next layout would. */
    private fun refitSubtitles() {
        touchSurface?.findViewById<View>(androidx.media3.ui.R.id.exo_content_frame)?.let(::fitSubtitlesTo)
    }

    /**
     * A shape picked by the row's button, a pinch or the menu: drawn ([renderShape]), shown on
     * the button and over the picture. Original, Fill and Stretch are kept for the next video; a
     * fixed shape lasts for this player only, so it is never written down.
     *
     * The codec's own scaling stays on plain fit: the view sizes its surface to the chosen shape,
     * and the codec scaling the frames again inside it would cancel the choice out.
     */
    private fun applyScale(scale: VideoScale) {
        videoScale = scale
        // A shape is a fresh start for the zoom; the shape's own name is the answer on screen.
        resetZoom(say = false)
        player?.videoScalingMode = C.VIDEO_SCALING_MODE_SCALE_TO_FIT
        renderShape()
        controls?.setShape(scale, animate = true)
        showGestureFeedback(scale.label)
        updatePictureInPictureParams()
        if (!scale.fixed) lifecycleScope.launch { runCatching { settings.setVideoScale(scale.name) } }
    }

    /**
     * Draws [videoScale] the way the picture reaches the screen right now.
     *
     * The plain path: PlayerView's frame is the shape. Original, Fill and Stretch are its own
     * resize modes; a fixed shape fits a frame given that aspect, and the codec stretches the
     * frames to it.
     *
     * Through Media3's effects pipeline (a file named as 3D, or 3D turned on in this player)
     * that does nothing: the pipeline's last stage fits every frame into the surface with its
     * aspect kept, so a stretched or zoomed frame came back letterboxed and the shapes looked
     * the same (HX16). There the pipeline draws the shape itself at the view's size
     * ([pipelineEffects]) and the view only stretches its surface over the whole screen, so the
     * last stage has nothing left to fit.
     */
    private fun renderShape() {
        val view = touchSurface ?: return
        val frame = view.findViewById<View>(androidx.media3.ui.R.id.exo_content_frame) as? AspectRatioFrameLayout
        if (shapeInPipeline()) {
            view.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FILL
            sendPipelineEffects()
        } else {
            view.resizeMode = videoScale.resizeMode
            frame?.setAspectRatio(videoScale.ratio?.toFloat() ?: naturalAspect())
        }
        refitSubtitles()
    }

    /** The video's own width over height, pixel shape included, as PlayerView sets it; 0 before it is known. */
    private fun naturalAspect(): Float {
        val size = player?.videoSize ?: return 0f
        if (size.width <= 0 || size.height <= 0) return 0f
        val pixel = size.pixelWidthHeightRatio.takeIf { it.isFinite() && it > 0f } ?: 1f
        return size.width * pixel / size.height
    }

    /**
     * Whether the frames go through the effects pipeline. A video on the app's own (FFmpeg)
     * decoder does not, even in a player that has the pipeline: its shape stays on the view.
     */
    private fun shapeInPipeline(): Boolean {
        val exo = player ?: return false
        return stereoPipeline && !videoOnFfmpeg(exo)
    }

    /**
     * The picture's width over height as the pipeline hands it to the shape: one eye's while 3D
     * is on, pixel shape and rotation included. 0 while the format is not known.
     */
    private fun pipelineAspect(): Double {
        val format = player?.videoFormat ?: return 0.0
        if (format.width <= 0 || format.height <= 0) return 0.0
        val pixel = format.pixelWidthHeightRatio.takeIf { it.isFinite() && it > 0f } ?: 1f
        var width = (format.width * pixel).roundToInt().coerceAtLeast(1)
        var height = format.height
        if (format.rotationDegrees == 90 || format.rotationDegrees == 270) width = height.also { height = width }
        if (stereo.on) Stereo3d.outputSize(width, height, stereo.layout).let { (w, h) -> width = w; height = h }
        return PictureRotation.turnedAspect(width.toDouble() / height, pictureRotation)
    }

    /** The view's size in pixels, the box the pipeline shapes the picture for; the screen's before the first layout. */
    private fun shapeBox(): Pair<Int, Int> {
        // A corner window is a passing size: the full screen's shape is kept for the way back.
        if (inPictureInPicture) sentShapeBox?.let { return it }
        val view = touchSurface
        if (view != null && view.width > 0 && view.height > 0) return view.width to view.height
        val metrics = resources.displayMetrics
        return metrics.widthPixels to metrics.heightPixels
    }

    /**
     * The pipeline's effects: 3D as chosen, then the shape at the view's size. Original adds
     * nothing (the last stage fits, which is Original). Fill, Stretch and the fixed shapes are a
     * Media3 `Presentation`, a matrix the last stage folds into the pass it already makes, so a
     * 1 GB stick pays no extra pass. A headset's pair is shaped per eye by [Stereo3dEffect].
     */
    private fun pipelineEffects(): List<androidx.media3.common.Effect> {
        val (width, height) = shapeBox()
        val effects = mutableListOf<androidx.media3.common.Effect>()
        val vr = stereo.on && stereo.view == Stereo3d.View.Vr
        if (stereo.on) effects += Stereo3dEffect(stereo, videoScale, width, height)
        // Rotate picture: a matrix the last stage folds into the pass it already makes, before
        // the shape, so the turned picture is what is fitted, filled or stretched.
        if (pictureRotation != PictureRotation.NONE) {
            effects += androidx.media3.effect.ScaleAndRotateTransformation.Builder()
                .setRotationDegrees(PictureRotation.counterClockwise(pictureRotation).toFloat())
                .build()
        }
        if (vr || width <= 0 || height <= 0) return effects
        // Kept within a 4K pair's width, as the 3D shader keeps its output.
        val (boxWidth, boxHeight) = Stereo3d.boxedSize(width, height)
        val shape = when {
            videoScale == VideoScale.Fit -> null
            videoScale == VideoScale.Crop -> androidx.media3.effect.Presentation.createForWidthAndHeight(
                boxWidth, boxHeight, androidx.media3.effect.Presentation.LAYOUT_SCALE_TO_FIT_WITH_CROP,
            )
            videoScale == VideoScale.Stretch -> androidx.media3.effect.Presentation.createForWidthAndHeight(
                boxWidth, boxHeight, androidx.media3.effect.Presentation.LAYOUT_STRETCH_TO_FIT,
            )
            // A fixed shape: the picture pulled to that aspect at the size it fits the screen at.
            else -> videoScale.pictureSize(boxWidth, boxHeight, 0.0).let { (w, h) ->
                androidx.media3.effect.Presentation.createForWidthAndHeight(w, h, androidx.media3.effect.Presentation.LAYOUT_STRETCH_TO_FIT)
            }
        }
        shape?.let(effects::add)
        return effects
    }

    /** What the pipeline was last handed, so a layout pass that changes nothing sends nothing. */
    private var sentPipelineKey: String? = null

    private fun pipelineKey(box: Pair<Int, Int>): String = "$stereo/$pictureRotation/$videoScale/${box.first}x${box.second}"
    private var sentShapeBox: Pair<Int, Int>? = null

    /** Hands the pipeline [pipelineEffects] when they differ from what it already draws. */
    private fun sendPipelineEffects(force: Boolean = false) {
        val exo = player ?: return
        val box = shapeBox()
        val key = pipelineKey(box)
        if (!force && key == sentPipelineKey) return
        sentPipelineKey = key
        sentShapeBox = box
        runCatching { exo.setVideoEffects(pipelineEffects()) }
            .onFailure { Log.w("TMPlayer", "Picture shape: the effects pipeline refused the change", it) }
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
            // "On mobile data: Allow" is the viewer's answer in advance: nothing to ask.
            asks = runCatching { settings.mobileDataNow().asks }.getOrDefault(true),
        )
        return when (decision) {
            MeteredDecision.Allow -> true
            MeteredDecision.Block -> {
                showError(
                    L.playerMobileDataNever,
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

    /** The path the next player is built on ([DecoderPolicy]): the remedy ladder's, else the known device list's. */
    private fun decoderPolicyForBuild(): DecoderPolicy = remedies.policyForBuild().allowingPassthrough(audioPassthrough)

    /** The policy the player now playing was built on, for the stutter report's "why this decoder". */
    private var builtPolicy: DecoderPolicy? = null

    /**
     * Whether this player moved the picture to the FFmpeg decoder after the device's decoder
     * failed ([tryOtherDecoder], by itself or at the viewer's press): the remedy ladder does not
     * count that step, so without this a report said HARDWARE predicted, software played, and no
     * remedy taken.
     */
    private var videoSwitchedToSoftware = false

    private fun buildPlayer(client: TdlClient): ExoPlayer {
        failurePinned = false
        stereoPipeline = false
        sentPipelineKey = null
        // A new player starts with both decoders on, so each may switch by itself once again.
        decodersSwitched.clear()
        // Hardware decoders first; NextLib's FFmpeg renderers pick up the audio codecs a TV stick
        // has no silicon for: DTS and TrueHD tracks are common in video remuxes.
        // Read once here, outside the factory, so nothing depends on when the factory's own
        // constructor gets round to it.
        val maskHdr10Plus = HevcHdr10PlusMasker.affectedDevice(android.os.Build.MANUFACTURER, android.os.Build.MODEL)
        val fireTv = DeviceQuirks.isFireTv(this@PlayerActivity)
        // Null on a normal device: NextLib's one thread per core, as before.
        val ffmpegVideoThreads = com.tmplayer.data.LowEndDevice.softwareVideoThreads(
            Runtime.getRuntime().availableProcessors(),
            com.tmplayer.data.LowEndProbe.isLowEnd(this@PlayerActivity),
        )
        if (ffmpegVideoThreads != null) Log.i(START_TAG, "Low-end device: FFmpeg video decodes on $ffmpegVideoThreads threads")
        // The remedy ladder's path for this build: decoder names left out, queueing, software first ([DecoderPolicy]).
        val policy = decoderPolicyForBuild().also {
            builtPolicy = it
            Log.i(START_TAG, "${it.describe()} (file $fileId)")
        }
        videoSwitchedToSoftware = false
        // One per player: the side of each track type "Try the other decoder" turned off (PB2-01).
        val decoderGate = DecoderGate()
        // The picture's and the sound's renderers, so a subtitle track running behind ends with them (PB2-11).
        val pictureAndSound = ArrayList<Renderer>()
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
                val inner = if (maskHdr10Plus) Hdr10PlusMaskingCodecAdapterFactory(stock) else stock
                // The codec's output format (colour standard, transfer, range, crop) for the trace (RL12).
                return OutputFactsCodecAdapterFactory(inner) { facts -> loopHandler.post { traceRecorder.noteFacts(facts) } }
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
                soundBitstream = false
                soundChipShown = false
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
                    // Passthrough off (A2, or Settings): the output takes PCM only.
                    .setAudioOutputProvider(PcmOnlyOutputProvider.of(output, policy.passthroughOff))
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
                // Unless the sound's own ladder put the app's decoder first (A1, [AudioPolicy]).
                val audioMode = AudioPolicy.rendererMode(extensionRendererMode, softwareFirst = policy.softwareAudio)
                val built = ArrayList<Renderer>()
                super.buildAudioRenderers(
                    context, audioMode, mediaCodecSelector, enableDecoderFallback, audioSink, eventHandler, eventListener, built,
                )
                // Each behind the player's decoder gate, so a switch moves the track ([DecoderSwitch]).
                built.mapTo(out) { GatedRenderer(it, decoderGate).also(pictureAndSound::add) }
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
                // Hardware decoders listed first, so a picture let through past the chip's rated
                // frame rate opens the chip, not the system's software decoder (HX71).
                super.buildVideoRenderers(
                    context, extensionRendererMode, HardwareFirstCodecSelector(mediaCodecSelector), enableDecoderFallback,
                    eventHandler, eventListener, allowedVideoJoiningTimeMs, built,
                )
                // Each behind the player's decoder gate, so a switch moves the picture ([DecoderSwitch]).
                built.mapTo(out) {
                    val renderer = when {
                        it is androidx.media3.exoplayer.video.MediaCodecVideoRenderer -> LevelLenientVideoRenderer(it)
                        // On a low-end device FFmpeg gets fewer threads than cores, so the screen
                        // keeps one ([LowEndDevice.softwareVideoThreads]); elsewhere NextLib's own.
                        it is io.github.anilbeesetti.nextlib.media3ext.ffdecoder.FfmpegVideoRenderer && ffmpegVideoThreads != null ->
                            io.github.anilbeesetti.nextlib.media3ext.ffdecoder.FfmpegVideoRenderer(
                                allowedVideoJoiningTimeMs, eventHandler, eventListener, FFMPEG_MAX_DROPPED_TO_NOTIFY,
                                ffmpegVideoThreads, FFMPEG_VIDEO_BUFFERS, FFMPEG_VIDEO_BUFFERS,
                            )
                        else -> it
                    }
                    GatedRenderer(renderer, decoderGate).also(pictureAndSound::add)
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
                built.mapTo(out) { DelayedTextRenderer(it, { subtitleDelayUs }) { DelayedTextRenderer.ended(pictureAndSound) } }
            }
        }
            // Prefer software video puts the app's FFmpeg video decoder ahead of the device's;
            // a format FFmpeg has no decoder for still goes to the device's.
            .setExtensionRendererMode(policy.extensionMode(preferSoftwareVideo = videoDecoder == com.tmplayer.data.VideoDecoder.Software))
            // Decoder fallback on, the codec list without the decoders that failed, the queueing mode.
            .let(policy::applyTo)

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
        // The byte target never stops loading, or starts playback, with less than the time it needs
        // ahead: a soundtrack starting 14 s into an 80 Mbps file, or a back buffer bigger than the
        // budget, left the player stuck ([AheadFirstLoadControl]). A ceiling from the heap's size bounds it.
        val aheadFirst = AheadFirstLoadControl(
            loadControl,
            playbackUs = BUFFER_PLAYBACK_MS * 1000L,
            rebufferUs = BUFFER_REBUFFER_MS * 1000L,
            ceilingBytes = AheadFirstLoadControl.ceilingFor(bufferBytes, lowMemory = bufferBytes <= LOW_RAM_BUFFER_BYTES),
        )

        val plan = trackPlan
        val subtitles = plan.subtitles
        val tunneling = tunnelingDecision().let {
            if (policy.tunnelingOff && it.tunneled) TunnelingPolicy.Decision(false, TunnelingPolicy.Reason.FailedBefore) else it
        }
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
                .setPreferredTextLanguages(*((subtitles as? SubtitlePlan.On)?.languages.orEmpty().toTypedArray()))
                .setSelectUndeterminedTextLanguage(false)
                .setPreferredAudioLanguages(*plan.audioLanguages.toTypedArray())
                .setTunnelingEnabled(tunneling.tunneled)
                .build()
        }
        tunnelingActive = tunneling.tunneled
        Log.i("TMPlayer", "Tunneled playback ${if (tunneling.tunneled) "on" else "off"}: ${tunneling.reason} (mode ${tunneledMode.key})")

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
        // An MKV without Cues (ffmpeg writing to a pipe, cam rips) seeks by estimate and a scan to
        // the next Cluster instead of not at all ([CuelessMatroskaSeek]); Telegram's duration
        // stands in for one the file leaves out.
        //
        // A file none of Media3's extractors reads (ASF/WMV, RealMedia, an MPEG-1 system stream,
        // an Ogg with Theora) goes to FFmpeg's libavformat last ([FfmpegExtractors]), still read
        // from TDLib's window at the offsets the demuxer asks for.
        //
        // Wrapped last so a Dolby Vision track this device would not show as Dolby Vision reaches
        // the player as its HEVC base layer, which both decoders take ([DolbyVisionBaseLayer]).
        val extractors = DolbyVisionBaseLayer(
            this,
            CuelessMatroskaSeek(
                com.tmplayer.player.demux.FfmpegExtractors(
                    DefaultExtractorsFactory()
                        .setConstantBitrateSeekingEnabled(true)
                        .setTsExtractorFlags(
                            androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory.FLAG_ENABLE_HDMV_DTS_AUDIO_STREAMS or
                                androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES,
                        )
                        .setTsExtractorTimestampSearchBytes(TS_TIMESTAMP_SEARCH_BYTES),
                ),
                durationHintUs = { durationSec.takeIf { it > 0 }?.let { it * 1_000_000L } ?: C.TIME_UNSET },
            ),
        )

        return ExoPlayer.Builder(this, renderers)
            .setLoadControl(aheadFirst)
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
            // Spelt out because [letGoOfPlayer] counts on it: a release is the one wait the way
            // out of a video still makes on the main thread, and a decoder that has not answered
            // in half a second is wedged, not slow. Media3's own default, held here so a later
            // version raising it cannot bring the television's ANR back (HX35).
            .setReleaseTimeoutMs(RELEASE_TIMEOUT_MS)
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
                    // Every time the output opens (a new video, another audio track, a rebuilt sound):
                    // is it a bitstream the boost cannot reach? (WP-24)
                    override fun onAudioTrackInitialized(
                        eventTime: AnalyticsListener.EventTime,
                        audioTrackConfig: androidx.media3.exoplayer.audio.AudioSink.AudioTrackConfig,
                    ) {
                        soundBitstream = AudioPolicy.bitstreamEncoding(audioTrackConfig.encoding)
                    }

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
                        crashNote.decoder(decoderName)
                    }

                    // Logged and counted for the remedy ladder; Media3 itself retries a recoverable one.
                    override fun onVideoCodecError(eventTime: AnalyticsListener.EventTime, videoCodecError: Exception) =
                        remedies.codecError(videoCodecError)

                    override fun onAudioDecoderInitialized(
                        eventTime: AnalyticsListener.EventTime,
                        decoderName: String,
                        initializedTimestampMs: Long,
                        initializationDurationMs: Long,
                    ) {
                        audioDecoderName = decoderName
                    }

                    override fun onVideoInputFormatChanged(
                        eventTime: AnalyticsListener.EventTime,
                        format: androidx.media3.common.Format,
                        decoderReuseEvaluation: androidx.media3.exoplayer.DecoderReuseEvaluation?,
                    ) {
                        // A decoder made again or a track changed: the stretch in hand is not playback.
                        watch.interrupted()
                        crashNote.format(format.sampleMimeType, format.width, format.height)
                        watchFrameRate(format)
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
    private fun attachMediaSession(exo: Player) {
        // A session already made (a stream opened again, the next episode played in place) takes
        // the new player rather than going: the notification, the lock screen's controls and,
        // with the player out of sight, [PlaybackService]'s foreground all carry on through it.
        mediaSession?.let { session ->
            val swapped = runCatching { session.player = EpisodeStepping(exo) }.isSuccess
            if (swapped) return
            session.release()
        }
        mediaSession = runCatching {
            // profile-notifications: its metadata is sessionMetadata(), private while the profile is hidden
            MediaSession.Builder(this, EpisodeStepping(exo))
                // Distinct per activity instance: stepping to the next episode builds a second
                // activity before the first has been destroyed, and two sessions sharing an id
                // is the one thing the builder refuses outright.
                .setId("tmplayer-$chatId-$messageId-${SystemClock.elapsedRealtime()}")
                // Before Android 13 the notification draws its own buttons: seek back and
                // forward by the player's amounts either side of play, the episodes in the
                // expanded view. From 13 the system's controls have a seek bar instead.
                .setMediaButtonPreferences(BackgroundPlayback.notificationButtons(touchPrefs.doubleTapMs, touchPrefs.doubleTapMs))
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
                // The app's own icon, so Media3 never fills the artwork from the file's own cover.
                .setArtworkUri(android.net.Uri.parse("android.resource://$packageName/${applicationInfo.icon}"))
                // And the bytes, which Media3 reads first: never the file's own embedded cover.
                .apply { neutralArtwork()?.let { setArtworkData(it, MediaMetadata.PICTURE_TYPE_FRONT_COVER) } }
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

    /** The show or the video's name, as the media notification names it, for the mini player. */
    private fun displayName(): String {
        val here = currentItem()
        val parsed = SeriesShelf.episodeOf(here)
        val episode = _episodes.value.current ?: EpisodeNeighbours.tagOf(here)
        val name = (episode?.show ?: parsed.title).ifBlank { parsed.title }.ifBlank { mediaTitle }
        return listOfNotNull(name, episode?.label).filter { it.isNotBlank() }.joinToString("  ·  ")
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
        // libVLC's player takes no change of its item: its item is written and the session told.
        vlcEngine?.let { vlc ->
            // profile-notifications: sessionMetadata() above
            vlc.mediaItem = vlc.mediaItem.buildUpon().setMediaMetadata(metadata).build()
            vlc.metadataChanged()
        }
        val exo = playing ?: return
        val playing = exo.currentMediaItem ?: return
        // profile-notifications: sessionMetadata() above
        runCatching { exo.replaceMediaItem(exo.currentMediaItemIndex, playing.buildUpon().setMediaMetadata(metadata).build()) }
    }

    /**
     * The player as the media session sees it: one video, plus the chat's next and previous
     * episodes. Next and previous are the episodes and nothing else, offered only where the chat
     * has one: Media3's previous for a lone item goes back to its start, and a stray press on a
     * headset, the lock screen or picture in picture took a two hour video back to 0:00.
     */
    private inner class EpisodeStepping(exo: Player) : StateChangeOnlyPlayer(exo) {
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

        // The notification's and the lock screen's seeks jump by the viewer's double tap amount both
        // ways, as the picture does (WP-36), not by Media3's 5 back and 10 forward. [Skip.BACK_MS]
        // itself stays what the remote's keys count from.
        override fun getSeekBackIncrement(): Long = touchPrefs.doubleTapMs

        override fun getSeekForwardIncrement(): Long = touchPrefs.doubleTapMs

        override fun seekBack() {
            skipBy(-touchPrefs.doubleTapMs)
        }

        override fun seekForward() {
            skipBy(touchPrefs.doubleTapMs)
        }

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
        backgroundAudioOn && !FormFactor.isTv(this) && !isFinishing && !inPictureInPicture &&
            mediaSession != null && playing?.playWhenReady == true &&
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

    /**
     * A key reached the failure sheet (WP-21): from then on nothing may move the remote under the
     * viewer's thumb, so the late answers of [showFailureActions] (the disk's, a download's) no
     * longer take focus. Cleared by [showError] for each failure.
     */
    private var sheetKeyed = false

    /** Focus for a late button of the failure sheet, unless the viewer has already pressed a key on it. */
    private fun focusLate(view: View) {
        if (!sheetKeyed) view.requestFocus()
    }

    /** True while the sheet counts down to the next episode ([onVideoEnded]). */
    private var countingDownToNext = false

    private fun startBackgroundAudio() {
        val session = mediaSession ?: return
        val started = PlaybackService.start(
            context = this,
            session = session,
            playWhenReady = playing?.playWhenReady == true,
            onStop = { endBackgroundAudio(finish = true) },
            onCannotContinue = { stopUnseen() },
        )
        if (!started) {
            // Android would not start the service: the sound cannot go on unseen, and stopping
            // without a word would look like a crash.
            playing?.pause()
            resumeWhenSeen = true
            PlaybackService.notifyStopped(this)
            return
        }
        inBackgroundAudio = true
        resumeWhenSeen = false
        playing?.let(::hideVideoUnseen)
    }

    /** Takes the session back from the service; [finish] when playback is over with the player unseen. */
    private fun endBackgroundAudio(finish: Boolean = false) {
        if (!inBackgroundAudio) return
        inBackgroundAudio = false
        PlaybackService.stop(this)
        if (finish && !isFinishing) {
            playing?.pause()
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
        playing?.pause()
        resumeWhenSeen = true
        endBackgroundAudio()
        PlaybackService.notifyStopped(this)
    }

    /**
     * Turns the video track off for a player nobody can see, which stops the decoder working for
     * frames that land nowhere ([BackgroundPlayback.withoutVideo]). Not in picture in picture,
     * where the picture is the point.
     */
    private fun hideVideoUnseen(exo: Player) {
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
        val exo = playing ?: return
        videoBackAskedAt = SystemClock.elapsedRealtime()
        exo.trackSelectionParameters = BackgroundPlayback.withVideo(exo.trackSelectionParameters)
    }

    /**
     * Back with nothing left to peel (T12): stop and go back to the page that opened the player.
     * Paused first so the sound ends at once, not when the window is gone; the place is saved on
     * the way out ([onStop] and [onDestroy]) and everything is released ([onDestroy]). The mini
     * player that kept the video going behind browsing is gone on the phone. A second Back while
     * the first is finishing does nothing more. Casting: the TV is stopped as well, as it always
     * was ([PlayerCast.release]), and the place it reached is the one kept.
     */
    private fun leaveByBack() {
        if (isFinishing) return
        playing?.pause()
        finish()
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
        intent = carryPlayAll(intent(this, next, chatTitle).putExtra(EXTRA_OUTSIDE_APP_TASK, outside), next)
        readItem(intent)
        forgetTheLastVideo()
        openItem()
    }

    /** What belonged to the last video, cleared for the next one played in place. */
    private fun forgetTheLastVideo() {
        // M muted the last video only.
        keyMuted = false
        endTranslations()
        // The scrub card's pictures were of the last video.
        trickplay?.forget()
        scrubArt = null
        artworkShown = false
        itemJob.cancel()
        itemJob = Job(lifecycleScope.coroutineContext[Job])
        recoveryJob = null
        recoveryAttempts = 0
        reSourced = false
        downloadedVideo = null
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
        skipCtl.online(emptyList())
        skipCtl.chapters(emptyList())
        skippedSegments.clear()
        offeredSkip = null
        hideSkipPill()
        creditsSkipped = false
        pictureChecked = false
        soundNotice.newVideo()
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
                    "Subtitles: first lines at ${playing?.currentPosition ?: -1} ms, " +
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
            // What the file declares, for the badges on its details page (T8). Cheap, once per listing.
            // Not for Telegram's lighter copy, whose picture is not the upload the page is about.
            if (!tracks.isEmpty && playingQuality == 0) runCatching { com.tmplayer.data.FormatProbes.record("$chatId:$messageId", Media3Probe.of(tracks)) }
            // After a switch of engine: the languages, the speed and the volume of the one before.
            pendingCarry?.let { carry -> if (currentEngine()?.restore(carry) == true) pendingCarry = null }
            logSubtitleTracks(tracks)
            // A subtitle file beside the video may run the full minute early, a track inside it not (PB2-11).
            textSideLoaded = tracks.groups.any { group ->
                group.type == C.TRACK_TYPE_TEXT && (0 until group.length).any { group.isTrackSelected(it) && group.getTrackFormat(it).id?.contains("external-") == true }
            }
            applySubtitleDelay()
            // The class is known now: its remedy plan, and a re-open on its path when it starts elsewhere (RL6).
            if (!tracks.isEmpty && vlcEngine == null) remedies.tracksKnown()
            if (pictureUnplayable(tracks)) return
            soundUnplayable(tracks)
            if (checkSoftwareDecode(tracks)) return
            restoreResumeTracks(tracks)
            applySettingsSubtitles(tracks)
            restoreRememberedSubtitle(tracks)
            rememberTracks(tracks)
            subtitleFiles.onTracksChanged(tracks)
        }

        override fun onRenderedFirstFrame() {
            logFirstFrame()
            remedies.firstFrame()
            saySoundOptions()
            // A few seconds in, once the picture and the tracks have settled: subtitles found for the viewer (U5).
            if (OnlineSubtitles.current?.store?.now?.suggest == true) {
                loopHandler.removeCallbacks(suggestStart)
                loopHandler.postDelayed(suggestStart, SUGGEST_DELAY_MS)
            }
            hintPlayerVolume()
            hintStereo()
            // The decoder is known by now: FFmpeg's frames skip the pipeline, so the shape moves.
            renderShape()
            if (videoBackAskedAt == 0L) return
            Log.i("TMPlayer", "Background audio: picture back in ${SystemClock.elapsedRealtime() - videoBackAskedAt} ms")
            videoBackAskedAt = 0L
        }

        override fun onVideoSizeChanged(size: VideoSize) {
            // The track turned off out of sight reports no size, which is not a new shape.
            if (videoHiddenUnseen && (size.width <= 0 || size.height <= 0)) return
            videoWidth = size.width
            videoHeight = size.height
            // The size libVLC reports is the one it has; ExoPlayer's input format said it already.
            if (vlcEngine != null) crashNote.format("", size.width, size.height)
            followVideoOrientation()
            // After PlayerView's own listener has set the frame to the video's aspect: a fixed
            // shape goes back on, the subtitles find the picture, and a decoder switched to
            // FFmpeg takes the shape out of the pipeline.
            touchSurface?.post { renderShape() }
            updatePictureInPictureParams()
            if (FrameRateMatch.shouldTakeOver(this@PlayerActivity)) {
                FrameRateMatch.apply(videoSurface(), videoFps())
            }
        }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            // Heard here before Media3's notification hears it: a pause lets the service out of
            // the foreground at once, so the paused notification can be swiped away.
            if (inBackgroundAudio) PlaybackService.setPlayWhenReady(playWhenReady)
            onPauseChanged(playWhenReady, reason)
            reportWatching()
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            wakeEndWatch()
            // The remedy watches tick only while it plays: nothing posted while paused or stalled.
            remedies.playingChanged(isPlaying && vlcEngine == null && !castUp())
            // A pause and play inside one sample interval would read as a slow clock (HX37).
            if (!isPlaying) watch.interrupted()
            if (isPlaying) startWatch()
            if (isPlaying) startStats()
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
            if (!isPlaying && playing?.playWhenReady == false) saveResumePosition()
        }

        override fun onPlaybackStateChanged(state: Int) {
            // Waiting for the stream between two samples is not slow decoding (HX37).
            if (state == Player.STATE_BUFFERING) watch.interrupted()
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
                    remedies.ready()
                    // Playing: a failure sheet's offer of the other engine is spent.
                    engineOffer = null
                    markStart("ready to play", STAGE_READY)
                    openedGate.complete(Unit)
                    firstFrameReady = true
                    noteSeekIndex()
                    readChapters()
                    offerStartOver()
                    controls?.setBuffering(false)
                    if (!failurePinned) hideStatus()
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
                    val repeats = repeatsAtTheEnd()
                    if (inBackgroundAudio) PlaybackService.setPlayWhenReady(!creditsSkipped && (repeats || endLeadsOn()))
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
            if (reason != Player.DISCONTINUITY_REASON_SEEK) {
                // A skip, a transition or an internal jump moved the position: not playback either.
                watch.interrupted()
                return
            }
            if (!watch.done) watch.reset()
            remedies.seeked()
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
            formatErrorPending = error
            // Refused as signed out: no retry or new id helps, and the sign in comes next (HX23).
            if (sessionLost(error)) {
                leaveForSignIn()
                return
            }
            if (retryWithoutTunneling(error)) return
            // The remedy ladder first (RL6): another decoder, the app's, the other engine, once each.
            if (remedies.onPlayerError(error)) return
            if (switchDecoderByItself(error)) return
            // With a second engine here (PR2): VLC by itself, or its button on the sheet below.
            if (handleEngineError(error)) return
            // Waited with nothing to play and no room to load more ([AheadFirstLoadControl]): the
            // file, not the line or the decoder, so "Can't play this" would send nobody anywhere.
            if (AheadFirstLoadControl.isStuckAtCeiling(error)) {
                showError(L.playerErrorMemory, retryable = true)
                return
            }
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
        val exo = playing ?: return
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
        val exo = playing ?: return
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
        val exo = playing ?: return false
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
        if (downloadedVideo != null) return false
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
     * Lets go of the player without the main thread waiting on a decoder that has stopped
     * answering (HX35).
     *
     * Two ANRs on a Changhong television (Android 11, four 32 bit cores) caught the main thread on
     * the way out of a video while `OMX.MS.HEVC.Decoder` was wedged: one inside `ExoPlayer.release`
     * waiting on the playback thread, one in [finish]. Media3 caps each wait it makes by itself
     * (half a second for a release, two seconds for handing a surface back), but the old order
     * paid several of them in a row. Handing the surface back first asks every video renderer to
     * answer and waits out the cap for each, and with the FFmpeg renderers beside the hardware
     * ones there is more than one; the release then waited on the same stuck playback thread
     * again.
     *
     * So the player goes first and the view lets go afterwards. A released player has already
     * taken its callbacks off the surface view and marks a message to the playback thread as
     * handled instead of sending it, so the view's own detach asks nothing of that thread and no
     * later `surfaceDestroyed` reaches the player either. Nothing sees the released instance in
     * between, since this is one turn of the main thread. [ExoPlayer.stop] goes before both, so
     * the renderers are told to hand the decoder back before anything waits for them.
     *
     * Never twice: the field is cleared before the release, and a player Media3 has already let go
     * of is left alone. Only [releasePlayerAndSurface] and [onDestroy] come here; picture in
     * picture and background audio keep the player playing and go nowhere near it.
     */
    private fun letGoOfPlayer() {
        // A crash after this is not about the video that was playing (S2).
        crashNote.ended()
        stopStats()
        loopHandler.removeCallbacks(suggestStart)
        loopHandler.removeCallbacks(suggestExpiry)
        suggestChip?.hide()
        // The VLC engine when it plays (PR2): its native player, surface and stream, all of it.
        releaseVlc()
        val exo = player ?: return
        player = null
        // The recorder stops with the player, before the release (its last reading reads the player).
        runCatching { traceRecorder.stop() }
        runCatching { exo.removeListener(playerListener) }
        if (!exo.isReleased) {
            runCatching { exo.stop() }
            runCatching { exo.release() }
        }
        // After the release, which is what makes this cost nothing: see above.
        runCatching { touchSurface?.player = null }
    }

    /**
     * Takes down the player and its surface, for the callers that cannot re-prepare.
     *
     * Both [reloadFromScratch] and [resourceAgain] change the bytes underneath a media source that
     * has already been built over them, and preparing over that reads the file the press was meant
     * to get away from; [continueUnseen] moves on to another file. The player and the view let go
     * of each other in [letGoOfPlayer]; no view is left holding a released player when this
     * returns. The session alone does, for the moment until [startPlayback] hands it the new one.
     */
    private fun releasePlayerAndSurface() {
        // The media session stays and takes the next player ([attachMediaSession]), so a rebuild
        // out of sight keeps [PlaybackService] and its notification; meanwhile it holds a player
        // that is idle and still meaning to play, which keeps the foreground through the gap.
        letGoOfPlayer()
        touchSurface?.let { findViewById<FrameLayout>(R.id.playback_container).removeView(it) }
        touchSurface = null
        // The next frame starts unzoomed, as the next video does.
        zoom = PictureZoom.NONE
        feedback?.zoomGone()
        // The listener is gone before the release, so the player's own "no cues" never arrives:
        // the line on screen would stay frozen over the next video's opening.
        trackCues = emptyList()
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

    /**
     * Whether the failure is the session ending rather than the video (HX23): a read refused as
     * signed out ([com.tmplayer.data.SessionLostException]), or Telegram ending the session while
     * it played. On the Mi TV this was "Telegram lost track of file", then "Can't play this".
     */
    private fun sessionLost(error: PlaybackException): Boolean =
        downloadedVideo == null && (
            Td.sessionEnded.value ||
                generateSequence(error.cause) { it.cause }.take(MAX_CAUSE_HOPS).any { it is com.tmplayer.data.SessionLostException }
            )

    /** Closes the player for the sign in screen behind it, which says why (SessionEndedNote). */
    private fun leaveForSignIn() {
        if (isFinishing) return
        Log.i("TMPlayer", "the Telegram session ended; closing the player for the sign in")
        finish()
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
        // Play all or Shuffle (WP-32, WP-87): the press decides what follows, whatever the video's name says
        // and whatever the setting says, and no episode logic is asked.
        if (playAllMode != com.tmplayer.data.PlayAll.Mode.Off) {
            findPlayAll(here)
            return
        }
        val show = EpisodeNeighbours.showOf(here)
        if (show == null) {
            // A video that is not an episode: the chat's own next one, where the setting asks.
            findChatOrder(here)
            return
        }
        _episodes.value = EpisodeSteps(current = EpisodeNeighbours.tagOf(here))

        itemScope.launch {
            if (chatId == 0L) return@launch
            afterOpening()
            val session = Td.awaitAuthorizedSession()
            val repository = ChatRepository(session.client)
            // In a group with topics the next episode is looked for in this video's own topic, or
            // the plain listing below would step into whichever section posted last.
            val topic = here.topicId.takeIf { it != 0 }
                ?: com.tmplayer.data.runCatchingCancellable { repository.topicOf(chatId, messageId) }.getOrDefault(0)
            // A page at a time until both neighbours are found: an episode posted a page away
            // from this one is still the next one.
            val steps = EpisodeNeighbours.lookUp(here, show) { query, cursors ->
                com.tmplayer.data.runCatchingCancellable { repository.mediaPage(chatId, cursors, query = query, topicId = topic) }.getOrNull()
            }
            // Nothing next to this one in the chat, or the chat could not be asked (offline, a
            // failed search): the other episodes of the show kept in Downloads stand in, so Next
            // and autoplay work for a downloaded season without a connection.
            val found = if (steps.previous == null && steps.next == null) keptSteps(here, show) ?: steps else steps
            // A name that reads as a show but has no other episode in the chat is still a video
            // that is not an episode: the chat's order takes over.
            if (found.previous == null && found.next == null && playsChatOrder()) {
                findChatOrder(here)
                return@launch
            }
            _episodes.value = found
            refreshSessionMetadata()
            // The providers' episode names, where lookups are on: a label first, then a better one.
            val named = withContext(Dispatchers.IO) { EpisodeNames.named(found, here, OnlineMetadata.current) }
            if (named != found) {
                _episodes.value = named
                refreshSessionMetadata()
            }
        }
    }

    private suspend fun playsChatOrder(): Boolean = runCatching { settings.playChatOrderNow() }.getOrDefault(false)

    /**
     * What follows [here] for Play all or Shuffle: a list's queue is read from memory, a chat's order
     * is [findChatOrder] forced on, and a chat's Shuffle steps through [com.tmplayer.data.PlayAll.ShufflePool].
     * Stop or Back ends the run, as they end any video.
     */
    private fun findPlayAll(here: MediaItem) {
        val mode = playAllMode
        if (mode.ofList) {
            _episodes.value = com.tmplayer.data.PlayAll.listSteps(playAllQueue, here, if (mode.shuffled) playAllSeed else 0L)
            refreshSessionMetadata()
            return
        }
        findChatOrder(here, force = true)
    }

    /**
     * After a video that is not an episode (EG-F30): with the setting on, the chat's next video
     * in the order its grid is sorted in is what autoplay goes on to ([com.tmplayer.data.ChatOrder]),
     * through the same next-up card, countdown and "Still watching?" as an episode. Off the critical
     * path like [findEpisodes]; nothing is asked of Telegram with the setting off, for
     * a local file or a video with no chat, and a chat that cannot be asked (offline) leaves it as
     * it was: the video ends and stays on its last frame. The size limits the grid applies skip a
     * video above them, and another copy of the same file is not played again.
     */
    private fun findChatOrder(here: MediaItem, force: Boolean = false) {
        if (chatId == 0L) return
        itemScope.launch {
            if (!force && !playsChatOrder()) return@launch
            afterOpening()
            val session = Td.awaitAuthorizedSession()
            val repository = ChatRepository(session.client)
            val sort = runCatching { settings.videoSort(chatId).first() }.getOrDefault(com.tmplayer.data.VideoSort.DEFAULT)
            val min = runCatching { settings.minSizeBytes.first() }.getOrDefault(com.tmplayer.data.SizeFilter.DEFAULT_MIN)
            val max = runCatching { settings.maxSizeBytes.first() }.getOrDefault(com.tmplayer.data.SizeFilter.DEFAULT_MAX)
            val topic = here.topicId.takeIf { it != 0 }
                ?: com.tmplayer.data.runCatchingCancellable { repository.topicOf(chatId, messageId) }.getOrDefault(0)
            // "Play all skips watched videos" (WP-32): the way forward of a Play all or Shuffle run goes past what
            // was watched. The ticks are read once for this step; Previous still goes back to what was played.
            val seen = if (force && runCatching { settings.playAllSkipWatchedNow() }.getOrDefault(false)) {
                runCatching { watchedStore.watched.first().keys }.getOrDefault(emptySet())
            } else {
                emptySet()
            }
            val unseen = { item: MediaItem -> seen.isEmpty() || com.tmplayer.data.ProgressKeys.of(item.chatId, item.messageId) !in seen }
            val fits = { item: MediaItem -> com.tmplayer.data.SizeFilter.matches(item.sizeBytes, min, max) && unseen(item) }
            // Locals only in the reader: the activity's chatId field would tie this screen to it.
            val chat = chatId
            val fetch: suspend (Long, Boolean) -> com.tmplayer.data.ChatOrder.Window? = { anchor, newer ->
                com.tmplayer.data.runCatchingCancellable { repository.videoWindow(chat, anchor, newer, topic) }.getOrNull()
            }
            val steps = if (playAllMode == com.tmplayer.data.PlayAll.Mode.ChatShuffle) {
                // One pool for the whole run: the pages read for one video are still there for the next.
                // It holds data only, the reader is this player's for this call (PB2-06).
                val pool = shufflePool ?: com.tmplayer.data.PlayAll.ShufflePool(playAllSeed, fits).also { shufflePool = it }
                pool.steps(here, fetch)
            } else {
                com.tmplayer.data.ChatOrder.steps(
                    current = here,
                    sort = sort,
                    fits = { com.tmplayer.data.SizeFilter.matches(it.sizeBytes, min, max) },
                    fitsNext = unseen,
                    fetch = fetch,
                )
            }
            if (steps.previous == null && steps.next == null) return@launch
            _episodes.value = steps
            refreshSessionMetadata()
        }
    }

    /**
     * The episodes next to [here] among the show's videos kept in Downloads whose files are on the
     * disk, or null when none is. Local reads only: it works with no connection.
     */
    private suspend fun keptSteps(here: MediaItem, show: String): EpisodeSteps? = withContext(Dispatchers.IO) {
        val kept = com.tmplayer.data.runCatchingCancellable { settings.downloadsNow() }.getOrNull().orEmpty()
            .filter { com.tmplayer.data.EpisodeNeighbours.showOf(it.toMediaItem())?.equals(show, ignoreCase = true) == true }
            .filter { LocalDownloads.presentVideo(it) != null }
            .map { it.toMediaItem() }
        com.tmplayer.data.EpisodeNeighbours.amongKept(here, show, kept)
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
                        L.playerNotEnoughRoomStream(size = short)
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
        hasMessage() && (pieces.isNotEmpty() || com.tmplayer.data.SplitPieces.pieceOf(mediaFileName)?.certain == true || isZipName(mediaFileName))

    /** A video kept in a ZIP: the one file, or the pieces of a split one ([ZipArchive]). */
    private fun isZipName(name: String): Boolean = com.tmplayer.data.SplitPieces.baseOf(name).endsWith(".zip", ignoreCase = true)

    /**
     * The pieces of the video, from the intent or else from the chat ([SplitPieces.lookUp]), for a
     * player opened on one piece (Continue watching, a link). False when there is only the one
     * file after all, which then plays as any other.
     */
    private suspend fun findPieces(client: TdlClient): Boolean {
        // Pieces the intent carried may be a window's worth (Home lists the newest messages of a
        // chat, so a set of 18 can arrive as the last 17): a set with its first piece or a number
        // missing is looked for in the chat before it is said to be missing.
        val given = pieces
        val whole = given.size > 1 && com.tmplayer.data.SplitPieces.report(given).complete
        if (!whole && (given.isNotEmpty() || com.tmplayer.data.SplitPieces.pieceOf(mediaFileName) != null)) {
            val repository = ChatRepository(client)
            val found = com.tmplayer.data.runCatchingCancellable {
                com.tmplayer.data.SplitPieces.lookUp(currentItem(), known = given) { query, cursors ->
                    com.tmplayer.data.runCatchingCancellable { repository.mediaPage(chatId, cursors, query = query, topicId = mediaTopicId) }.getOrNull()
                }
            }.getOrNull().orEmpty()
            // With no connection to search the chat with, the pieces kept in Downloads.
            val kept = com.tmplayer.data.runCatchingCancellable {
                com.tmplayer.data.SplitPieces.among(currentItem(), settings.downloadsNow().map { it.toMediaItem() })
            }.getOrNull()?.takeIf { it.size > 1 }
            pieces = com.tmplayer.data.SplitPieces.best(given, found, kept)
        }
        // A ZIP that is not split plays through the same stream, as a set of one.
        if (pieces.isEmpty() && isZipName(mediaFileName)) pieces = listOf(currentItem())
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
        val loneZip = all.size == 1 && all[0].splitPiece == null
        val notice = if (loneZip) null else com.tmplayer.data.SplitPieces.playerNotice(all)
        val playable = if (loneZip) all else com.tmplayer.data.SplitPieces.playablePieces(all)
        // A piece missing is remembered, so "Missing parts of a video" can say when it is posted
        // (CP76); a video found whole is forgotten.
        val store = settings
        val chat = chatTitle
        if (!loneZip) com.tmplayer.App.backgroundScope.launch { runCatching { com.tmplayer.data.PieceAlerts.notePlayed(store, all, chat) } }
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
            val video = LocalDownloads.videoFor(settings, piece.chatId, piece.messageId)
            if (video != null) {
                sources += video.uri() to (video.length() ?: 0L)
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
        if (isZipName(all.first().fileName)) {
            // The zip's directory is in its last piece: a missing piece means it cannot be read.
            if (notice != null) {
                showError(notice, retryable = false)
                return false
            }
            val window = zipWindow(client, sources) ?: return false
            fileSizeBytes = window.second
            joinedSource = joinedUri(sources, window)
            return true
        }
        joinedSource = joinedUri(sources)
        return true
    }

    /**
     * Reads the ZIP's directory from the end of the joined pieces and finds the stored video in it:
     * its start and length in the joined bytes, which [joinedUri] shows to the player as the whole
     * file. Null with the reason on screen when the archive cannot be read, holds no video, or its
     * video is compressed or locked and so cannot stream.
     */
    private suspend fun zipWindow(client: TdlClient, sources: List<Pair<android.net.Uri, Long>>): Pair<Long, Long>? {
        val whole = joinedUri(sources)
        val total = sources.sumOf { it.second }
        val factory = JoinedDataSource.Factory(
            TdDataSource.Factory(client),
            DefaultDataSource.Factory(this, TdDataSource.Factory(client)),
            ::leftPiece,
        )
        val archive = object : com.tmplayer.data.ZipSource {
            override val size: Long = total
            override suspend fun read(offset: Long, length: Int): ByteArray? = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                val want = minOf(length.toLong(), total - offset).toInt()
                if (want <= 0) return@withContext ByteArray(0)
                val data = factory.createDataSource()
                try {
                    data.open(
                        androidx.media3.datasource.DataSpec.Builder().setUri(whole).setPosition(offset).setLength(want.toLong()).build(),
                    )
                    val out = ByteArray(want)
                    var got = 0
                    while (got < want) {
                        val n = data.read(out, got, want - got)
                        if (n == C.RESULT_END_OF_INPUT) break
                        got += n
                    }
                    if (got == want) out else out.copyOf(got)
                } catch (e: java.io.IOException) {
                    null
                } finally {
                    runCatching { data.close() }
                }
            }
        }
        val verdict = com.tmplayer.data.ZipArchive.inspect(archive)
        val video = verdict.entry?.takeIf { verdict.kind == com.tmplayer.data.ZipVerdict.Kind.Playable }
        if (video == null) {
            showError(verdict.reason.orEmpty(), retryable = verdict.retryable)
            return null
        }
        val start = com.tmplayer.data.ZipArchive.dataOffset(archive, video)
        if (start == null) {
            showError(L.playerZipUnreadable, retryable = true)
            return null
        }
        return start to video.uncompressedSize
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
        // A scan left waiting lands nowhere: the next video is another screen (PB2-15).
        cancelScan()
        val started = runCatching {
            startActivity(
                carryPlayAll(
                    intent(this, item, inChat).putExtra(
                        EXTRA_OUTSIDE_APP_TASK,
                        inPictureInPicture || intent.getBooleanExtra(EXTRA_OUTSIDE_APP_TASK, false),
                    ),
                    item,
                ),
            )
        }
        if (started.isFailure) {
            // Nothing took over: this player stays, and Next and Previous work again.
            handingOver = false
            showGestureFeedback(L.playerOpenFailed)
            return
        }
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
        // Only with the player on screen: one closed out of sight (the X on the mini player, the
        // paused notification swiped away) has nothing to bring back, and must not pop the app up.
        val stranded = !handingOver && isTaskRoot && !isChangingConfigurations &&
            intent.getBooleanExtra(EXTRA_OUTSIDE_APP_TASK, false) &&
            lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
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
        // Repeat this video: from the start again in this same player, not marked watched and no
        // countdown to a next video; unseen too, so the sound carries on.
        if (repeatsAtTheEnd()) {
            player?.let {
                it.seekTo(0)
                it.play()
            }
            return
        }
        // Repeat this video gives way to a sleep timer set for this video's end: the video ends,
        // and Repeat is off for whatever is played next.
        if (sleepAtTheEnd && com.tmplayer.data.RepeatVideo.repeats(currentItem(), keepRepeat)) {
            com.tmplayer.data.RepeatVideo.set(false)
        }
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
            // Play all and Shuffle play on whatever the Autoplay next setting says (PB2-07).
            val autoplay = com.tmplayer.data.PlayAll.playsOn(playAllMode, runCatching { settings.autoplayNextNow() }.getOrDefault(true))
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
        autoplay = com.tmplayer.data.PlayAll.playsOn(playAllMode, autoplayNext),
        nextUpDismissed = nextUpDismissed,
        sleepAtTheEnd = sleepAtTheEnd,
        askBeforeAutoplay = StillWatching.askBeforeAutoplay(autoplayedInARow),
        nextUpShown = nextUpShown,
        unseen = true,
    ) is BackgroundPlayback.AtTheEnd.Next

    /**
     * Whether the end now reached plays this video again: Repeat this video
     * ([com.tmplayer.data.RepeatVideo.repeats]). A replay is nobody's autoplay, so it
     * neither counts towards "Still watching?" nor shows the next-up card; the idle question still
     * comes after two hours with nothing pressed. A sleep timer for the end of the video wins.
     */
    private fun repeatsAtTheEnd(): Boolean =
        com.tmplayer.data.RepeatVideo.replaysAtEnd(currentItem(), keepRepeat, sleepAtTheEnd)

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
                quietly { playing?.seekTo(0) }
                playing?.playWhenReady = true
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
        val exo = playing ?: return
        val duration = exo.duration
        val target = (exo.currentPosition + deltaMs).coerceAtLeast(0)
        exo.seekTo(if (duration > 0) target.coerceAtMost(duration - END_GUARD_MS) else target)
    }

    /** The offsets now in force, for the track picker's timing lines. */
    override fun syncDelaysNow(): SyncDelays =
        // What is in force: a track inside the file no earlier than its buffer reaches (PB2-11). VLC keeps its own.
        if (vlcEngine != null) syncDelays else syncDelays.copy(subtitleMs = SyncDelays.subtitleApplied(syncDelays.subtitleMs, textSideLoaded))

    /**
     * Moves subtitles ([C.TRACK_TYPE_TEXT]) or sound one step later ([direction] 1) or earlier
     * (-1), or back to none ([direction] 0), and keeps the result for this file.
     */
    override fun stepDelay(trackType: Int, direction: Int) {
        // A long press (WP-29) steps a whole second: its direction is ten steps.
        val long = kotlin.math.abs(direction) >= LONG_DELAY_STEPS
        val way = if (direction < 0) -1 else if (direction > 0) 1 else 0
        syncDelays = if (trackType == C.TRACK_TYPE_TEXT) {
            syncDelays.copy(
                subtitleMs = when {
                    way == 0 -> 0
                    long -> SyncDelays.stepLong(syncDelaysNow().subtitleMs, way)
                    else -> SyncDelays.step(syncDelaysNow().subtitleMs, way)
                }.let { if (vlcEngine != null) it else SyncDelays.subtitleApplied(it, textSideLoaded) },
            )
        } else {
            syncDelays.copy(audioMs = if (way == 0) 0 else SyncDelays.stepAudio(syncDelays.audioMs, way, long))
        }
        applySubtitleDelay()
        audioOffset?.offsetUs = syncDelays.audioMs * 1000
        // VLC keeps its own offsets (a Bluetooth delay, subtitles late or early).
        vlcEngine?.setDelays(syncDelays.audioMs, syncDelays.subtitleMs)
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
        // A line on screen from a track with its own size is fitted again, so the change shows now.
        showCues(scaledForPicture(trackCues))
        if (styleThisVideo && hasMessage()) {
            // "This video only" (WP-29): the shared look stays as it was.
            val (chat, message) = chatId to messageId
            lifecycleScope.launch { runCatching { settings.setVideoSubtitleStyle(chat, message, style) } }
        } else {
            lifecycleScope.launch { runCatching { settings.setSubtitleStyle(style) } }
        }
    }

    override val subtitleThisVideo: Boolean? get() = if (hasMessage()) styleThisVideo else null

    /**
     * "This video only" (WP-29). On: the look in force now becomes this video's own and the shared look
     * is left alone from here. Off: this video's look is forgotten and it follows the shared one again.
     */
    override fun setSubtitleThisVideo(on: Boolean) {
        if (!hasMessage() || on == styleThisVideo) return
        styleThisVideo = on
        val (chat, message) = chatId to messageId
        if (on) {
            val own = subtitleStyle
            lifecycleScope.launch { runCatching { settings.setVideoSubtitleStyle(chat, message, own) } }
        } else {
            lifecycleScope.launch {
                runCatching { settings.setVideoSubtitleStyle(chat, message, null) }
                val shared = runCatching { settings.subtitleStyleNow() }.getOrNull() ?: return@launch
                // The viewer may have turned it on again while the shared look was read.
                if (styleThisVideo) return@launch
                subtitleStyle = shared
                applySubtitleStyle()
                showCues(scaledForPicture(trackCues))
            }
        }
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

    private fun subtitleTextFraction(): Float = subtitleStyle.fraction * subtitleTextScale()

    /**
     * The bottom padding, as a share of the view's height: the position setting's share, or
     * enough to keep the lines [R.dimen.player_subtitle_bottom] off the screen's edge where the
     * picture reaches it, whichever is higher. Never both, so the lines stand at the same height
     * on every picture shape and every video (user, 2026-10-18).
     */
    private fun subtitleBottomFraction(): Float {
        val share = subtitleStyle.bottomFraction
        val height = subtitleView.layoutParams?.height ?: 0
        if (height <= 0) return share
        val clearance = resources.getDimensionPixelSize(R.dimen.player_subtitle_bottom)
        val needed = (clearance - subtitleGapBelow).coerceAtLeast(0)
        return maxOf(share, needed.toFloat() / height).coerceAtMost(MAX_SUBTITLE_BOTTOM)
    }

    /**
     * The same floor for a cue that brings its own size, as an SSA line does, and the viewer's
     * Size and Position on it ([SubtitleCueFit], issue 32): scaled with the rest, or it alone
     * would stay too small to read and deaf to the setting. Picture cues are left at the shape
     * the film gave them.
     */
    private fun scaledForPicture(cues: List<Cue>): List<Cue> {
        trackCues = cues
        return SubtitleCueFit.fit(cues, subtitleStyle, subtitleTextScale())
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
            view.setStyle(SubtitleLook.captionStyle(subtitleStyle, subtitleScriptFace))
        }
    }

    /**
     * This video for an online subtitle search: its name, its size, the subtitle language last
     * chosen for the series, and the OpenSubtitles hash when both ends of the file are on the disk
     * (always for a download; for a stream, once TDLib has fetched them).
     */
    override suspend fun onlineTarget(): SubtitleTarget? {
        if (mediaTitle.isBlank()) return null
        val local = downloadedVideo
        val id = fileId
        val size = fileSizeBytes
        val hash = withContext(Dispatchers.IO) {
            runCatching {
                when {
                    // The hash is of the whole file's two ends, which for a joined video are two pieces.
                    joinedSource != null -> null
                    local != null -> local.length()?.takeIf { MovieHash.hashable(it) }?.let { size ->
                        val head = local.readAt(0, MovieHash.CHUNK)
                        val tail = local.readAt(MovieHash.tailOffset(size), MovieHash.CHUNK)
                        if (head != null && tail != null && head.size >= MovieHash.CHUNK && tail.size >= MovieHash.CHUNK) MovieHash.of(size, head, tail) else null
                    }
                    id != 0 -> withTimeoutOrNull(3_000) {
                        OnlineSubtitles.hashFromTdlib(Td.awaitConnectedSession().client, id, size)
                    }
                    else -> null
                }
            }.getOrNull()
        }
        // The details' ids let MSone be searched exactly; a slow lookup only leaves the title.
        val ids = withTimeoutOrNull(3_000) {
            withContext(Dispatchers.IO) { OnlineMetadata.current?.idsFor(currentItem()) }
        } ?: com.tmplayer.online.VideoIds()
        return SubtitleTarget(
            fileName = mediaTitle,
            sizeBytes = size,
            hash = hash,
            languages = OnlineSubtitles.languagesFor(tracks.textLanguage),
            imdb = ids.imdb,
            tmdb = ids.tmdb,
            durationMs = player?.duration?.takeIf { it > 0 && it != C.TIME_UNSET },
        )
    }

    override fun attachOnlineSubtitle(file: File, label: String) = subtitleFiles.attachFile(file, label)

    /** The video whose SubMaker translations are running, so they end with it. */
    private var translatingVideo: String? = null

    private fun endTranslations() {
        val video = translatingVideo ?: return
        translatingVideo = null
        OnlineSubtitles.current?.let { it.translations.cancelVideo(it.translationPrefix(video)) }
    }

    /**
     * Runs a SubMaker "Make <Language>" entry as a background job: the video plays on, and the
     * loaded track is swapped in place as lines arrive (at most about once a minute, each swap
     * rebuilds the item at this position), the finished one remembered for the video.
     */
    override fun startTranslation(hit: com.tmplayer.online.SubtitleHit, target: SubtitleTarget): Boolean {
        val online = OnlineSubtitles.current ?: return false
        val label = OnlineSubtitles.label(hit)
        translatingVideo = target.videoKey
        val started = online.translate(target.videoKey, hit, target.durationMs) { file, final ->
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                subtitleFiles.replaceFile(file, label)
                if (final) {
                    onlineSubtitlePicked(hit, file)
                    showGestureFeedback(L.submakerDone(hit.translateTo))
                }
            }
        }
        if (started) showGestureFeedback(L.submakerStarted(hit.translateTo))
        return started
    }

    override fun onlineSubtitlePicked(hit: com.tmplayer.online.SubtitleHit, file: File) {
        OnlineSubtitles.current?.rememberPick(com.tmplayer.online.SubtitlePicks.keysOf(currentItem()), hit, file)
    }

    override fun subtitleChoiceMade(off: Boolean, external: Boolean) {
        val online = OnlineSubtitles.current ?: return
        val keys = com.tmplayer.online.SubtitlePicks.keysOf(currentItem())
        when {
            off -> online.rememberOff(keys)
            external -> online.clearOff(keys)
            else -> online.forgetPick(keys)
        }
    }

    /** "In this chat" (EG-F48): the subtitle files posted next to this video, from one search, kept for the video. */
    override suspend fun chatSubtitles(): List<ChatSubtitles.Hit> {
        if (!hasMessage()) return emptyList()
        return chatSubtitleLoader.find(chatId, messageId, mediaFileName.ifBlank { mediaTitle }, mediaCaption, OnlineSubtitles.languagesFor(tracks.textLanguage))
    }

    override suspend fun attachChatSubtitle(hit: ChatSubtitles.Hit): String? =
        chatSubtitleLoader.attach(chatId, messageId, hit, mediaFileName.ifBlank { mediaTitle }, mediaCaption, handover = savable != false)

    override val pickSubtitleFile: (() -> Unit)?
        get() = if (FormFactor.isTv(this)) null else ({ subtitleFiles.pick() })

    fun showTrackPicker(trackType: Int) {
        TrackPickerFragment.show(supportFragmentManager, R.id.overlay_container, trackType)
    }

    // The Search key nothing took: the window's own default crashes some Android 14 TV boxes.
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean =
        com.tmplayer.SearchKeyGuard.swallows(keyCode) || super.onKeyDown(keyCode, event)

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean =
        com.tmplayer.SearchKeyGuard.swallows(keyCode) || super.onKeyUp(keyCode, event)

    /**
     * MEDIA keys always act, and so do the digits, which jump to that tenth of the video. D-pad
     * keys act on the bare picture and walk the row once it is up: the focused views own them
     * then, with one exception carved out below. Left and right over the bare picture jump the
     * way the phone's double tap does, a small seek and a flash of icon and figure on that side
     * of the frame, without raising the row; the scrub bar, reached through OK, steps the same
     * 10 s ([FineTimeBar]) and folds a burst of presses into one committed seek. Page Up and Page
     * Down step a chapter, or a minute where the file has none. The exception
     * is OK on the focused bar, which toggles playback rather than doing nothing, unless a scrub
     * is mid-flight, in which case the bar takes the press and commits.
     */
    @SuppressLint("RestrictedApi")
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // The first key on a failure sheet is the viewer's: no late answer moves the remote after it (WP-21).
        if (event.action == KeyEvent.ACTION_DOWN && statusOverlay.visibility == View.VISIBLE) sheetKeyed = true
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
        // More options up: every key is the panel's, and nothing behind it seeks or pauses. Fire
        // TV's Menu key closes it, as ⋮ again does.
        morePanel?.takeIf { it.isOpen }?.let { panel ->
            if (event.keyCode == KeyEvent.KEYCODE_MENU && remoteQuirks.menuOpensPlayerMenu) {
                if (event.action == KeyEvent.ACTION_UP && !event.isCanceled) panel.close()
                return true
            }
            // Play/Pause, Play, Pause and Stop still act under the panel (WP-19); nothing else does.
            if (panelPlaybackKey(event)) return true
            return super.dispatchKeyEvent(event)
        }
        // Paused long enough that the small mark stands in for the row (WP-21): any key but the
        // media ones only brings the row back, the play disc holding the remote, and its repeats
        // and release are taken too (PB2-03). Back does not leave, the arrows do not seek, OK does
        // not resume from a screen that shows no row.
        if (FormFactor.isTv(this) && pauseMarkKeys.onKey(
                event.keyCode,
                event.action,
                markShown = controls?.pauseMarkShown == true && statusOverlay.visibility != View.VISIBLE,
            ) { controls?.paused() }
        ) {
            return true
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
        // The fast-forward key let go (or cancelled): the held speed ends and the viewer's own returns (U3).
        if (remoteHolding && event.keyCode == KeyEvent.KEYCODE_MEDIA_FAST_FORWARD && event.action != KeyEvent.ACTION_DOWN) {
            endRemoteHold()
            return true
        }
        // OK over the bare picture pauses on its release, and only when it did not become the 1.5 s
        // handover (WP-19): one press never both pauses and hands over.
        if (event.action == KeyEvent.ACTION_UP && FormFactor.isTv(this) &&
            (event.keyCode == KeyEvent.KEYCODE_DPAD_CENTER || event.keyCode == KeyEvent.KEYCODE_ENTER) &&
            okPress.pausesOnRelease
        ) {
            if (okPress.onUp(event.isCanceled) && statusOverlay.visibility != View.VISIBLE && !controlsUp) togglePlayback()
            return true
        }
        // CC's short press, on its release (WP-19): the first toggles subtitles, a second within 3 s opens the picker.
        if (event.action == KeyEvent.ACTION_UP && event.keyCode == KeyEvent.KEYCODE_CAPTIONS && captionsKey.pending) {
            if (captionsKey.onUp(event.isCanceled) && player != null && statusOverlay.visibility != View.VISIBLE &&
                !TrackPickerFragment.isOpen(supportFragmentManager)
            ) {
                val now = android.os.SystemClock.elapsedRealtime()
                when (PlayerRemoteKeys.captionsPress(longPress = false, lastToggleAtMs = captionsToggledAt, nowMs = now)) {
                    PlayerRemoteKeys.CaptionsPress.OpenPicker -> {
                        captionsToggledAt = 0L
                        showTrackPicker(C.TRACK_TYPE_TEXT)
                    }
                    PlayerRemoteKeys.CaptionsPress.Toggle -> {
                        captionsToggledAt = now
                        toggleCaptions()
                    }
                }
            }
            return true
        }
        // Info let go before its long press (which reports a stutter, below): what is playing.
        // On release, since the press could still become the hold.
        if (event.keyCode == KeyEvent.KEYCODE_INFO && event.action == KeyEvent.ACTION_UP && FormFactor.isTv(this)) {
            if (PlayerRemoteKeys.isInfoTap(event.keyCode, event.downTime, event.eventTime, event.isCanceled, android.view.ViewConfiguration.getLongPressTimeout().toLong()) &&
                statusOverlay.visibility != View.VISIBLE && sheets?.isOpen != true && !detailsOpen && player != null
            ) {
                showPlaybackDetails()
                hintInfoHold()
            }
            return true
        }
        // A scan lands once, when its key is let go (WP-79).
        if (event.action == KeyEvent.ACTION_UP && remoteScan.target != null &&
            (event.keyCode == KeyEvent.KEYCODE_DPAD_LEFT || event.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT || event.keyCode == KeyEvent.KEYCODE_MEDIA_REWIND)
        ) {
            commitScan()
            return true
        }
        if (event.action != KeyEvent.ACTION_DOWN) return super.dispatchKeyEvent(event)
        // The quiet line about software decoding goes with the next press, which still does what
        // it does: the line asks nothing, so it never holds a key back.
        if (softwareNoteUp) {
            softwareNoteUp = false
            feedback?.dismissMessage()
        }
        val pickerOpen = TrackPickerFragment.isOpen(supportFragmentManager)
        if (pickerOpen) {
            if (panelPlaybackKey(event)) return true
            return super.dispatchKeyEvent(event)
        }

        // Before anything else claims the same key. A held select on a remote, or the menu key, is
        // the long press the browse grid answers with a menu; here the only entry that menu would
        // carry which the player has no other route to is the handover.
        // Hold Info: Report stutter (L3), on a television, where there is no menu in reach of a thumb.
        if (FormFactor.isTv(this) && statusOverlay.visibility != View.VISIBLE &&
            PlaybackMarks.isMarkKey(event.keyCode, event.isLongPress)
        ) {
            reportStutter()
            return true
        }
        // Where a television's OK began (WP-19): over the bare picture, taking nothing else's press,
        // it may become the 1.5 s handover; read before the press raises the row.
        if (FormFactor.isTv(this) && event.repeatCount == 0 &&
            (event.keyCode == KeyEvent.KEYCODE_DPAD_CENTER || event.keyCode == KeyEvent.KEYCODE_ENTER)
        ) {
            okPress.onDown(
                repeatCount = 0,
                heldMs = 0L,
                overPicture = !controlsUp && statusOverlay.visibility != View.VISIBLE && !skipHasTheRemote() &&
                    !nextUpHasTheRemote() && feedback?.offerUp != true,
            )
        }
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
        // A stretch set to skip by itself counts down over the bare picture: Back cancels it.
        if (skipHasTheRemote() && skipPill?.isCounting == true && event.keyCode == KeyEvent.KEYCODE_BACK) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) cancelAutoSkip()
            return true
        }

        // The next-up card on a television does not take the remote as it appears: OK stays pause
        // and resume and Left and Right seek over the bare picture. Right or Down walks onto it,
        // then OK is "Play now"; Left, Up or Down walks off. Back puts it away.
        if (nextUpHasTheRemote()) {
            if (event.keyCode == KeyEvent.KEYCODE_BACK) {
                nextUpDismissed = true
                hideNextUp()
                return true
            }
            when (PlayerRemoteKeys.nextUpKey(event.keyCode, nextUpEngaged)) {
                PlayerRemoteKeys.NextUpKey.Engage -> {
                    nextUpEngaged = true
                    nextUpCard?.play?.requestFocus()
                    return true
                }
                PlayerRemoteKeys.NextUpKey.Pass -> return super.dispatchKeyEvent(event)
                PlayerRemoteKeys.NextUpKey.Release -> nextUpEngaged = false
                PlayerRemoteKeys.NextUpKey.Ignore -> Unit
            }
        }

        when (event.keyCode) {
            // A remote's own Aspect (zoom) key steps the picture shape, as the row's button does,
            // with nothing to reach first (HX16).
            KeyEvent.KEYCODE_TV_ZOOM_MODE -> {
                if (event.repeatCount == 0) applyScale(videoScale.next())
                return true
            }
            // A remote's 3D key steps 3D: off, one eye, both eyes for a headset or a 3D TV, red and
            // cyan glasses, off again. The layout is the one in force, else the file's name's, else
            // side by side (HX16).
            KeyEvent.KEYCODE_3D_MODE -> {
                if (event.repeatCount == 0 && stereoOffered()) {
                    val layout = stereo.layout.takeIf { stereo.on } ?: stereoTagged ?: Stereo3d.Layout.SideBySide
                    val next = when {
                        !stereo.on -> Stereo3d(layout, Stereo3d.View.OneEye, stereo.swapEyes)
                        stereo.view == Stereo3d.View.OneEye -> stereo.copy(view = Stereo3d.View.Vr)
                        stereo.view == Stereo3d.View.Vr -> stereo.copy(view = Stereo3d.View.RedCyan)
                        else -> Stereo3d.OFF.copy(view = stereo.view, swapEyes = stereo.swapEyes)
                    }
                    applyStereo(next)?.let { why -> showGestureFeedback(why) }
                }
                return true
            }
            // The CC and the audio-language keys many remotes carry: the same pickers the row's
            // buttons open, a press away from the bare picture (the desktop's SubtitleNext and
            // AudioNext keys). Nothing while a status sheet owns the screen or nothing is loaded.
            // CC (WP-19) toggles subtitles on the first press; a second within 3 s or a long press
            // opens the picker. The audio key opens its picker at once.
            KeyEvent.KEYCODE_CAPTIONS, KeyEvent.KEYCODE_MEDIA_AUDIO_TRACK -> {
                val type = TrackKeys.pickerFor(event.keyCode)
                val ready = type != null && player != null && statusOverlay.visibility != View.VISIBLE
                if (ready && type == C.TRACK_TYPE_AUDIO && event.repeatCount == 0) {
                    showTrackPicker(type)
                } else if (ready && type == C.TRACK_TYPE_TEXT) {
                    // A short press is judged on its release; a hold only opens the picker.
                    if (captionsKey.onDown(event.repeatCount, event.isLongPress)) {
                        captionsToggledAt = 0L
                        showTrackPicker(C.TRACK_TYPE_TEXT)
                    }
                }
                return true
            }
            // Every key that changes playback shows that it did: the jumps their side figure, play
            // and pause the centre disc or glyph. With no buttons for them on a television's row,
            // the cue is the only sign the press landed.
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
                // Held (U3): from the long press the video plays at the hold speed until the key
                // lets go, as holding a finger does on the phone; the repeats do not also jump.
                if (HoldSpeedKey.startsHold(event.keyCode, event.repeatCount, event.isLongPress, remoteHolding, touchPrefs.holdSpeed, remoteQuirks.holdSeekAccelerates) && !castUp()) {
                    remoteHolding = true
                    holdFastForward(true)
                    return true
                }
                if (remoteHolding) return true
                jumpFromRemote(forward = true, repeatCount = event.repeatCount, eventMs = event.eventTime)
                return true
            }
            KeyEvent.KEYCODE_MEDIA_REWIND -> {
                // A held Rewind scans like a held Left (WP-79); held Fast forward stays 2x playback.
                if (scanThenJump && FormFactor.isTv(this) && scanFromRemote(false, event)) return true
                jumpFromRemote(forward = false, repeatCount = event.repeatCount, eventMs = event.eventTime)
                return true
            }
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                if (event.repeatCount == 0) togglePlayback()
                return true
            }
            KeyEvent.KEYCODE_MEDIA_PLAY -> {
                if (playing?.playWhenReady == false) togglePlayback()
                return true
            }
            KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                if (playing?.playWhenReady == true) togglePlayback()
                return true
            }
            // Next, previous, skip and the channel keys step the episode; with none to go to the key
            // says so rather than doing nothing. Once per press, so a held key is not a binge.
            KeyEvent.KEYCODE_MEDIA_NEXT, KeyEvent.KEYCODE_MEDIA_PREVIOUS,
            KeyEvent.KEYCODE_MEDIA_SKIP_FORWARD, KeyEvent.KEYCODE_MEDIA_SKIP_BACKWARD,
            KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_CHANNEL_DOWN,
            -> {
                val forward = PlayerRemoteKeys.episodeStep(event.keyCode) ?: return super.dispatchKeyEvent(event)
                if (event.repeatCount == 0 && !stepEpisode(forward)) {
                    showGestureFeedback(if (forward) L.playerNoNextEpisode else L.playerNoPreviousEpisode)
                }
                return true
            }
            // Page Up and Page Down on a television: a chapter, or a minute where there are none
            // (PlayerRemoteKeys.pageStep), whatever sent them. Off a television handleTouchDeviceKey
            // reads a keyboard's Page keys through PlayerKeyMap first, as the previous and next
            // episode, the desktop's and the web's meaning (PB-03, docs/agents/tv-keys.md); one the
            // map passes on (a sheet or a panel up) arrives here and goes to the focused view.
            KeyEvent.KEYCODE_PAGE_UP, KeyEvent.KEYCODE_PAGE_DOWN -> {
                val forward = PlayerRemoteKeys.pageStep(event.keyCode, FormFactor.isTv(this)) ?: return super.dispatchKeyEvent(event)
                if (event.repeatCount == 0 && player != null && statusOverlay.visibility != View.VISIBLE) pageFromRemote(forward)
                return true
            }
            // Stop saves the place and leaves, as Back does, with no question asked.
            KeyEvent.KEYCODE_MEDIA_STOP -> {
                if (event.repeatCount == 0) leaveByBack()
                return true
            }
            // Back peels the overlays off in the order they were put on: the row first, and only
            // then is the film what Back was aimed at.
            KeyEvent.KEYCODE_BACK -> {
                if (statusOverlay.visibility != View.VISIBLE && controlsUp && backPeelsTheRow()) {
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
                // A scan under way keeps the keys even with its row raised (WP-79).
                if (FormFactor.isTv(this) && (!controlsUp || remoteScan.target != null) && statusOverlay.visibility != View.VISIBLE) {
                    val forward = event.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT
                    if (scanThenJump && scanFromRemote(forward, event)) return true
                    jumpFromRemote(forward = forward, repeatCount = event.repeatCount, eventMs = event.eventTime)
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
                        // A press that could become the handover pauses on its release ([okPress]).
                        if (event.repeatCount == 0 && !okPress.pausesOnRelease) togglePlayback()
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
                // The same for the subtitle offer ("Not this one, try the next", the mobile data
                // question): Up takes it when "Back to" is not standing in its way.
                if (event.keyCode == KeyEvent.KEYCODE_DPAD_UP && FormFactor.isTv(this) &&
                    suggestChip?.isShown == true && suggestAction != null && statusOverlay.visibility != View.VISIBLE
                ) {
                    if (event.repeatCount == 0) suggestAction?.invoke()
                    return true
                }
                // Any other press over the bare picture asks for the row rather than doing
                // anything irreversible; the second press does the thing it lands on.
                // Down lands on the first button, the one thing the arrows cannot do without the row;
                // Up keeps the bar.
                if (FormFactor.isTv(this) && !controlsUp && statusOverlay.visibility != View.VISIBLE) {
                    controls?.show(focusButtons = event.keyCode == KeyEvent.KEYCODE_DPAD_DOWN)
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
        if (statusOverlay.visibility != View.VISIBLE && controlsUp && backPeelsTheRow()) {
            controls?.hideAnimated()
            return true
        }
        return false
    }

    /**
     * Whether Back takes the transport row down before it leaves the film. On a television yes: the
     * remote has no other way to clear the picture. On a phone no: with three-button navigation the
     * Back button is on screen only because the row is up, and hiding the row first looked like
     * nothing happened and took the button away with it (a webOS tester, 2026-11-07). A sheet opened
     * from the row still gets its press first.
     */
    private fun backPeelsTheRow(): Boolean =
        FormFactor.isTv(this) || menuOpen || detailsOpen || pickerOpen || episodesOpen || morePanel?.isOpen == true

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
        val exo = playing ?: return false
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
        if (statusOverlay.visibility == View.VISIBLE) return false
        // A television's OK (WP-19): only a press that began over the bare picture, held 1.5 s; with
        // the row up it is a plain OK and never leaves the app. The handover is a chooser, never a launch.
        if (FormFactor.isTv(this) && (event.keyCode == KeyEvent.KEYCODE_DPAD_CENTER || event.keyCode == KeyEvent.KEYCODE_ENTER)) {
            // The first event only set where the press began (above); repeats may reach 1.5 s.
            return event.repeatCount > 0 && okPress.onDown(event.repeatCount, event.eventTime - event.downTime, overPicture = false)
        }
        if (!event.isLongPress) return false
        return when (event.keyCode) {
            KeyEvent.KEYCODE_MENU -> true
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> controlsUp
            else -> false
        }
    }

    /** The television's OK press now down: where it began, whether it handed over (WP-19). */
    private val okPress = PlayerRemoteKeys.OkPress()

    /** The key that brought the row back from the pause mark, taken until its release (PB2-03). */
    private val pauseMarkKeys = PlayerRemoteKeys.PauseMarkKeys()

    /** The CC key press now down (WP-19). */
    private val captionsKey = PlayerRemoteKeys.CaptionsKeyPress()

    /** When the CC key last toggled subtitles, 0 for not yet: a second press soon after opens the picker. */
    private var captionsToggledAt = 0L

    /** The text track (an index among the file's subtitle tracks) the CC key last turned off, to turn it back on. */
    private var lastCaptionTrack: Int? = null

    /** That track's language: across episodes the language finds it again where its index has moved. */
    private var lastCaptionLanguage: String? = null

    /**
     * Play/Pause, Play, Pause and Stop under the More panel or a track picker (WP-19): the same
     * actions as without a panel, once per press, and the panel stays where it is. True when the key
     * was one of them on its press (its repeats are swallowed too).
     */
    private fun panelPlaybackKey(event: KeyEvent): Boolean {
        val key = PlayerRemoteKeys.panelMedia(event.keyCode) ?: return false
        if (event.action != KeyEvent.ACTION_DOWN) return false
        if (event.repeatCount > 0) return true
        when (key) {
            PlayerRemoteKeys.PanelMedia.Toggle -> togglePlayback()
            PlayerRemoteKeys.PanelMedia.Play -> if (playing?.playWhenReady == false) togglePlayback()
            PlayerRemoteKeys.PanelMedia.Pause -> if (playing?.playWhenReady == true) togglePlayback()
            PlayerRemoteKeys.PanelMedia.Stop -> leaveByBack()
        }
        return true
    }

    /**
     * The CC key's first press (WP-19): subtitles off when one shows, else on with the track last
     * turned off, the file's default or the first. Says which, or "No subtitles" for a file with none.
     */
    private fun toggleCaptions() {
        val exo = playing ?: return
        val text = tracksOfType(exo.currentTracks, C.TRACK_TYPE_TEXT)
        val selected = text.indexOfFirst { (group, index) -> group.isTrackSelected(index) }.takeIf { it >= 0 }
        val byLanguage = tracks.textLanguage?.let { want ->
            text.indexOfFirst { (group, index) -> ResumeState.sameTrack(want, group.getTrackFormat(index).language) }
        }?.takeIf { it >= 0 }
        val defaultIndex = text.indexOfFirst { (group, index) ->
            group.getTrackFormat(index).selectionFlags and C.SELECTION_FLAG_DEFAULT != 0
        }.takeIf { it >= 0 } ?: byLanguage
        val builder = exo.trackSelectionParameters.buildUpon().clearOverridesOfType(C.TRACK_TYPE_TEXT)
        val lastByLanguage = lastCaptionLanguage?.let { want ->
            text.indexOfFirst { (group, index) -> ResumeState.sameTrack(want, group.getTrackFormat(index).language) }
        }?.takeIf { it >= 0 }
        when (val plan = PlayerRemoteKeys.captionsToggle(text.size, selected, lastByLanguage ?: lastCaptionTrack, defaultIndex)) {
            PlayerRemoteKeys.CaptionsToggle.None -> showGestureFeedback(L.playerNoSubtitles)
            PlayerRemoteKeys.CaptionsToggle.Off -> {
                lastCaptionTrack = selected
                lastCaptionLanguage = selected?.let { text[it].let { (group, index) -> group.getTrackFormat(index).language } }
                    ?.takeIf { it.isNotBlank() && it != C.LANGUAGE_UNDETERMINED }
                exo.trackSelectionParameters = builder.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build()
                subtitleChoiceMade(off = true, external = false)
                showGestureFeedback(L.playerSubtitlesOff)
            }
            is PlayerRemoteKeys.CaptionsToggle.On -> {
                val (group, index) = text[plan.index]
                exo.trackSelectionParameters = builder
                    .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                    .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, index))
                    .build()
                val format = group.getTrackFormat(index)
                subtitleChoiceMade(off = false, external = format.id?.contains("external-") == true)
                showGestureFeedback(L.playerSubtitlesOnTrack(captionName(format, plan.index)))
            }
        }
    }

    /** A subtitle track's name for the toast: its label, else its language, else "Track N". */
    private fun captionName(format: androidx.media3.common.Format, position: Int): String {
        val language = format.language
            ?.takeIf { it.isNotBlank() && it != "und" }
            ?.let { com.tmplayer.i18n.Translator.messages.formatter.trackLanguage(it) ?: it }
        val label = format.label?.takeIf { it.isNotBlank() }
        return when {
            label != null && language != null && !label.contains(language, ignoreCase = true) -> "$language ($label)"
            label != null -> label
            language != null -> language
            else -> L.tracksTrackNumber((position + 1).toString())
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
        if (statusOverlay.visibility == View.VISIBLE) return false
        // A chord (Shift, Ctrl or Alt held) is the keyboard map's first: Ctrl+Left is a chapter and
        // Shift+Left a minute, where the bare arrow keeps its jump below. A chord the map has no
        // action for falls through to what the key did before.
        if ((event.isShiftPressed || event.isCtrlPressed || event.isAltPressed || event.isMetaPressed) && keyboardKey(event)) return true
        if (controlsUp) return keyboardKey(event)
        when (event.keyCode) {
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD, KeyEvent.KEYCODE_DPAD_RIGHT -> jumpFromRemote(forward = true, repeatCount = event.repeatCount)
            KeyEvent.KEYCODE_MEDIA_REWIND, KeyEvent.KEYCODE_DPAD_LEFT -> jumpFromRemote(forward = false, repeatCount = event.repeatCount)
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_SPACE,
            // Once per press: a held key repeats, and each repeat flipped playback back again.
            -> if (event.repeatCount == 0) togglePlayback()
            else -> return keyboardKey(event)
        }
        return true
    }

    /** True while M has the sound off: the engine's gain is 0 but the stored player volume was not touched. */
    private var keyMuted = false

    /**
     * A computer keyboard on a tablet, a Chromebook or a phone (WP-28), read through the one shared
     * table ([PlayerKeyMap], the web player's too), never on a television, whose remote has its own
     * rules. With the row up the keys that walk the focus (arrows, Space, Enter) stay the row's;
     * the letters, brackets and digits work with it up or down. Nothing while a sheet, menu, list or
     * panel is up (they own the keys, and the text field in a sheet keeps its letters). What the
     * player has no action for (zoom, bookmark, screenshot) is left alone, so the key does what it did.
     */
    private fun keyboardKey(event: KeyEvent): Boolean {
        if (FormFactor.isTv(this)) return false
        if (sheets?.isOpen == true || menuOpen || detailsOpen || pickerOpen || episodesOpen || morePanel?.isOpen == true) return false
        val code = PlayerKeyMap.codeOfAndroid(event.keyCode) ?: return false
        val chord = event.isShiftPressed || event.isCtrlPressed || event.isAltPressed || event.isMetaPressed
        if (controlsUp && !chord && PlayerKeyMap.walksFocus(code)) return false
        val typed = if (event.unicodeChar == '?'.code) "?" else ""
        val action = PlayerKeyMap.actionFor(
            PlayerKeyMap.Press(code, typed, event.isShiftPressed, event.isCtrlPressed, event.isAltPressed, event.isMetaPressed),
            PlayerKeyMap.Context(
                chapters = chapters.isNotEmpty(),
                paused = playing?.isPlaying == false,
                speedNormal = playbackSpeed == PlaybackSpeed.DEFAULT,
                skipShown = offeredSkip != null,
            ),
        ) ?: return false
        return performKeyboardAction(action, event.repeatCount)
    }

    /** True when the press was the player's: the keys it has no action for come back false and stay the system's. */
    private fun performKeyboardAction(action: PlayerKeyMap.Action, repeatCount: Int): Boolean {
        // One action per press for everything but a held seek: a held key repeats, and each repeat
        // would flip, step or toggle again.
        val once = repeatCount == 0
        when (action.kind) {
            PlayerKeyMap.Kind.TogglePlay -> if (once) togglePlayback()
            PlayerKeyMap.Kind.Seek -> if (once) jumpFromRemote(forward = action.value > 0, fixedStepMs = kotlin.math.abs(action.value) * 1_000L)
            PlayerKeyMap.Kind.Chapter -> if (once) {
                if (chapters.isNotEmpty()) stepChapter(action.flag) else jumpFromRemote(action.flag, fixedStepMs = PlayerRemoteKeys.PAGE_JUMP_MS)
            }
            // With no episode to go to the key says so, as the remote's Next and Previous do.
            PlayerKeyMap.Kind.Episode -> if (once && !stepEpisode(action.flag)) {
                showGestureFeedback(if (action.flag) L.playerNoNextEpisode else L.playerNoPreviousEpisode)
            }
            PlayerKeyMap.Kind.Speed -> if (once) setSpeed(PlaybackSpeed.fine(playbackSpeed, up = action.flag))
            PlayerKeyMap.Kind.SpeedReset -> if (once) setSpeed(PlaybackSpeed.DEFAULT)
            PlayerKeyMap.Kind.Jump -> if (once && !jumpToTenth(action.value)) return false
            PlayerKeyMap.Kind.Mute -> if (once) toggleKeyMute()
            PlayerKeyMap.Kind.SubtitlesToggle -> if (once) toggleCaptions()
            PlayerKeyMap.Kind.Details -> if (once && player != null) showPlaybackDetails()
            PlayerKeyMap.Kind.Sheet -> if (once) openKeyboardKeys()
            else -> return false
        }
        return true
    }

    /**
     * "?": the keyboard keys page of the More panel, opened as the More button opens the panel, so
     * the row is held under it and [PlayerMoreHost.closed] finds the state it clears (PB-09).
     */
    private fun openKeyboardKeys() {
        menuOpen = true
        holdControls()
        moreAnchor = null
        morePanel?.openKeys(keyboard = true)
    }

    /** M: the sound off and on again, said over the picture; the stored Player volume is left as it is. */
    private fun toggleKeyMute() {
        keyMuted = !keyMuted
        val gain = if (keyMuted) 0f else PlayerVolume.gain(playerVolumePercent)
        player?.volume = gain
        vlcEngine?.volume = gain
        showGestureFeedback(if (keyMuted) L.keysRowMute else L.playerVolumeLevelNow(L.messages.formatter.scalePercent(playerVolumePercent)))
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
            val on = Trickplay.PICTURES_SHOWN && runCatching { settings.trickplayNow() }.getOrDefault(true)
            controls?.previewsOff = !on
            if (!on) stopTrickplaySpans()
        }
        if (!Trickplay.PICTURES_SHOWN || FormFactor.isTv(this) || !DeviceQuirks.trickplayMemory(this)) {
            stopTrickplaySpans()
            return
        }
        lifecycleScope.launch {
            if (!runCatching { settings.trickplayNow() }.getOrDefault(true)) return@launch
            val frames = TrickplayFrames(
                scope = lifecycleScope,
                // Not for a video joined from pieces: one piece's file is not the video's.
                path = { if (joinedSource != null) null else downloadedVideo?.let { it.file?.path ?: it.uri().toString() } ?: trickplayPath },
                spans = { trickplaySpans },
                complete = { downloadedVideo != null || downloadComplete },
                sizeBytes = { downloadedVideo?.length() ?: fileSizeBytes },
                durationMs = { playing?.duration?.takeIf { it > 0 } ?: durationSec * 1_000L },
                context = this@PlayerActivity,
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
        if (!Trickplay.PICTURES_SHOWN) return
        val board = fresh.storyboard ?: return
        if (storyboardFrames != null) return
        lifecycleScope.launch {
            if (!runCatching { settings.trickplayNow() }.getOrDefault(true)) return@launch
            // Read twice at once, or the video changed while the setting was read.
            if (storyboardFrames != null || postItem !== fresh) return@launch
            storyboardFrames = StoryboardThumbnails(
                scope = lifecycleScope,
                files = board,
                durationMs = { playing?.duration?.takeIf { it > 0 } ?: durationSec * 1_000L },
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
        if (downloadedVideo != null) return
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
                        } else if (playing?.playbackState == Player.STATE_BUFFERING) {
                            showRebuffering()
                        }
                    } else if (reconnected && playing?.playbackState == Player.STATE_BUFFERING) {
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

        val exo = playing ?: return
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
                playing = playing?.isPlaying == true,
                casting = cast?.casting == true,
                sheetUp = statusOverlay.visibility == View.VISIBLE,
            )
        ) {
            return
        }
        runCatching { enterPictureInPictureMode(pictureInPictureParams()) }
    }

    /** The corner window's receiver on Android 8 to 10 ([PipActions]); unregistered in [onDestroy]. */
    private var pipReceiver: android.content.BroadcastReceiver? = null

    /** A press on one of the corner window's buttons: the same jumps as the row's, and play or pause. */
    private fun onPipButton(kind: PipActions.Kind) {
        when (kind) {
            PipActions.Kind.Back -> skipBy(-Skip.BUTTON_MS)
            PipActions.Kind.Forward -> skipBy(Skip.BUTTON_MS)
            // The row's own toggle (PB2-14): an ended video starts again, the pause bookkeeping holds,
            // and a press while buffering pauses rather than calling play() on a player already asked to play.
            PipActions.Kind.PlayPause -> togglePlayback()
        }
    }

    /** The overflow menu's and the row's picture in picture: straight into the corner. */
    private fun enterPictureInPictureNow() {
        if (!hasPictureInPicture()) return
        val entered = runCatching { enterPictureInPictureMode(pictureInPictureParams()) }.getOrDefault(false)
        // Where the reason is known (iOS: a file of mpv's has no corner window) it is said, not dropped.
        if (!entered) pictureInPictureRefusal()?.let { showGestureFeedback(it) }
    }

    /** iOS's AirPlay list for this video; for a file Apple's player does not play, the line that says why. */
    private fun showAirPlay() {
        if (!showAirPlayPicker()) showGestureFeedback(L.playerAirplayNotForThisFile)
    }

    /**
     * Keeps the system's picture in picture request in step with playback: entered on its own when
     * the viewer goes home while something plays, never over a paused video or a status sheet.
     */
    private fun updatePictureInPictureParams() {
        // Android 12 for the auto enter, 8 to 10 for the corner window's own buttons (WP-36).
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.S && !PipActions.wanted()) return
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
                playing = playing?.isPlaying == true,
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
        // the picture's own shape is known to be inside the range. A fixed picture shape is the
        // window's shape too, held inside the range.
        val forced = videoScale.ratio
        if (forced != null) {
            val ratio = forced.coerceIn(PIP_MIN_RATIO.toDouble() + 0.001, PIP_MAX_RATIO.toDouble() - 0.001)
            builder.setAspectRatio(Rational((ratio * 10_000).roundToInt(), 10_000))
        } else if (videoWidth > 0 && videoHeight > 0) {
            val ratio = videoWidth.toFloat() / videoHeight
            if (ratio in PIP_MIN_RATIO..PIP_MAX_RATIO) {
                builder.setAspectRatio(Rational(videoWidth, videoHeight))
            }
        }
        // Back, play or pause and forward, drawn by the window itself on Android 8 to 10 only.
        // Asked to play, buffering included, shows Pause: what a press of it would do (PB2-14).
        PipActions.apply(builder, this, playing = playing?.let { it.playWhenReady && it.playbackState != Player.STATE_ENDED } == true)
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
        // own play and pause over the window, fed by the media session (Android 8 to 10: [PipActions]).
        if (isInPictureInPictureMode) {
            morePanel?.closeNow()
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
        // The corner window shows the whole picture; back at full size the zoom returns.
        renderZoom()
    }

    // ---- the phone's extras: settings, overflow, lock, next up --------------------------------

    /** Hands the viewer's touch settings to everything that reads them. */
    private fun applyTouchPrefs() {
        gestures?.prefs = touchPrefs
        feedback?.hapticsEnabled = touchPrefs.haptics
        controls?.haptics = touchPrefs.haptics
        // The notification's seek buttons carry the double tap amount (WP-36).
        mediaSession?.let { session ->
            runCatching {
                session.setMediaButtonPreferences(
                    BackgroundPlayback.notificationButtons(touchPrefs.doubleTapMs, touchPrefs.doubleTapMs),
                )
            }
        }
        controls?.timeoutMs = touchPrefs.timeoutFor(FormFactor.isTv(this))
        controls?.showRemaining = touchPrefs.showRemaining
    }

    /** The left and right system gesture strips, in pixels, for the scrub's edge rule. */
    private fun systemGestureEdges(): Pair<Int, Int> {
        val insets = touchSurface?.let { ViewCompat.getRootWindowInsets(it) } ?: return 0 to 0
        val edges = insets.getInsets(WindowInsetsCompat.Type.systemGestures())
        return edges.left to edges.right
    }

    /**
     * More options: everything that does not earn a seat on the row, as a panel at the screen's
     * end edge (a sheet on a phone held upright), opened at once ([PlayerMorePanel]). ⋮ again, or
     * Fire TV's Menu key, closes it. See [PlayerMenu.phoneEntries] for what is in it.
     */
    private fun showOverflow(anchor: View) {
        val panel = morePanel ?: return
        if (panel.isOpen) {
            panel.close()
            return
        }
        moreAnchor = anchor
        menuOpen = true
        holdControls()
        panel.open()
    }

    /** What the panel reads and does: the player's own state, nothing fetched on open. */
    private val moreHost = object : PlayerMoreHost {
        override val tv: Boolean get() = FormFactor.isTv(this@PlayerActivity)
        override fun title() = mediaTitle
        override fun sideways() = sheetGoesSideways()
        override fun phoneEntries() = PlayerMenu.phoneEntries(
            pictureInPicture = hasPictureInPicture(),
            openInAnotherApp = savable == true,
            saveToDownloads = canSaveToDownloads(),
            markWatched = hasMessage(),
            paused = isPaused(),
            bookmarks = hasMessage(),
            chapters = chapters.isNotEmpty(),
            chapterList = Chapters.listable(chapters, playing?.duration ?: 0L),
            // Only once the chat is known to allow saving: a protected chat's frames stay in it.
            saveFrame = savable == true,
            // A picture to shape, as for 3D: not while a cast plays it.
            pictureShape = stereoOffered(),
            stereo = stereoOffered(),
            zoomed = zoom.zoomed,
            reportStutter = stutterReportable(),
            copyLinkAtTime = linkAtTimeOffered(),
            otherEngine = otherEngineOffered(),
            decoder = menuDecoderPlan() != null,
            repeatVideo = true,
            markIntro = skipCtl.canMark,
            removeIntroMark = skipCtl.canMark && skipCtl.hasOwnMark,
            rotatePicture = stereoOffered(),
            playerVolume = !castUp(),
            brightness = !castUp(),
            addToList = hasMessage(),
            // Not where the chat restricts saving (the cast rule), not before that is known, and not
            // for a stream while offline (a saved video plays from the phone).
            airPlay = hasAirPlay() && savable == true &&
                (downloadedVideo != null || NetworkMonitor.status.value != NetworkStatus.Offline),
            passthroughOff = passthroughOffOffered(),
        )
        override fun zoomDetail(): String? =
            zoom.takeIf { it.zoomed }?.let { L.messages.formatter.scalePercent(it.percent) }
        override fun tvEntries() = PlayerMenu.tvEntries(
            pictureInPicture = hasPictureInPicture(),
            saveToDownloads = canSaveToDownloads(),
            markWatched = hasMessage(),
            openInAnotherApp = savable == true,
            paused = isPaused(),
            bookmarks = hasMessage(),
            chapters = chapters.isNotEmpty(),
            chapterList = Chapters.listable(chapters, playing?.duration ?: 0L),
            stereo = stereoOffered(),
            pictureShape = stereoOffered(),
            reportStutter = stutterReportable(),
            copyLinkAtTime = linkAtTimeOffered(),
            otherEngine = otherEngineOffered(),
            decoder = menuDecoderPlan() != null,
            repeatVideo = true,
            markIntro = skipCtl.canMark,
            removeIntroMark = skipCtl.canMark && skipCtl.hasOwnMark,
            rotatePicture = stereoOffered(),
            playerVolume = !castUp(),
            addToList = hasMessage(),
            loadSubtitleFile = documentPickerAvailable(this@PlayerActivity),
            passthroughOff = passthroughOffOffered(),
        )
        override fun shape() = videoScale
        override fun speed() = playbackSpeed
        override fun skipSilence() = skipSilence
        override fun stereo() = stereo
        override fun stereoTagged() = stereoTagged
        override fun stereoDetail() = stereo.detailText()
        override fun volumeBoost() = volumeBoostOn
        override fun nightMode() = nightModeOn
        override fun soundBypassed() = soundBitstream
        override fun evenVolume() = evenVolumeOn
        override fun playerVolume() = playerVolumePercent
        override fun brightness() = currentBrightnessPercent()
        override fun setBrightness(percent: Int) = this@PlayerActivity.setBrightness(percent)
        override fun setPlayerVolume(percent: Int) = this@PlayerActivity.setPlayerVolume(percent)
        override fun pictureRotation() = this@PlayerActivity.pictureRotation
        override fun setPictureRotation(degrees: Int) = applyRotation(degrees) to this@PlayerActivity.pictureRotation
        override fun sleepDetail() = sleepTimerDetail()
        override fun sleepChoice() = this@PlayerActivity.sleepChoice()
        override fun loop() = abLoop
        override fun repeatVideo() = com.tmplayer.data.RepeatVideo.repeats(currentItem(), keepRepeat)
        override fun introMarking() = skipCtl.introMarking
        override fun chapter(): String? =
            if (chapters.isEmpty()) null
            else Chapters.labelAt(chapters, playing?.currentPosition ?: 0L) ?: L.playerBeforeFirstChapter
        override fun chapterList(): List<ChapterRow> {
            val length = playing?.duration ?: 0L
            return if (Chapters.listable(chapters, length)) Chapters.rows(chapters, playing?.currentPosition ?: 0L, length) else emptyList()
        }
        override fun jumpToChapter(startMs: Long) = jumpToChapterStart(startMs)
        override fun watched() = onWatchedList
        override fun engineNow(): String? = if (vlcEngine != null) L.settingsEngineVlc else L.settingsEngineExoplayer
        override fun decoderDetail(): String? = menuDecoderDetail()
        override fun bookmarks() = if (hasMessage()) bookmarkActions() else null
        override fun addToList() = listTarget()
        override fun playbackDetails() = this@PlayerActivity.playbackDetails()
        override fun onPhoneEntry(entry: PhoneMenuEntry) = onPhoneMenuEntry(entry)
        override fun onTvEntry(entry: PlayerMenuEntry) = onTvMenuEntry(entry)
        override fun setShape(scale: VideoScale) = applyScale(scale)
        override fun setSpeed(speed: Float) = this@PlayerActivity.setSpeed(speed)
        override fun setSkipSilence(on: Boolean) = this@PlayerActivity.setSkipSilence(on)
        override fun setStereo(next: Stereo3d) = applyStereo(next) to stereo
        override fun setSleep(minutes: Int?) = setSleepTimer(minutes)
        override fun closed() {
            menuOpen = false
            holdControls()
            // A pause that came under the panel raises the row now, before the remote goes back to the opener.
            payPauseRow()
            if (tv) {
                controls?.show()
                val anchor = moreAnchor?.takeIf { it.isShown }
                if (anchor != null) anchor.requestFocus() else controls?.focusRow()
            }
        }
    }

    /**
     * What the More options' "Add to a list" page (HX43) works on: this video as the card menu
     * would keep it (a video from a message, saving restricted or not, played whole or from
     * pieces), taken now so the page keeps naming the one it was opened for, and the viewer's
     * lists. Null for a file with no message, which a list cannot point at. A write says its
     * line over the picture ([showGestureFeedback]).
     */
    private fun listTarget(): PlayerListTarget? {
        if (!hasMessage()) return null
        val record = com.tmplayer.data.WatchLaterRecord.of(currentItem(), chatTitle, System.currentTimeMillis())
        return PlayerListTarget(record, watchedStore.lists) { line -> showGestureFeedback(line) }
    }

    /** What a line of the phone's panel does; its pages (shape, speed, 3D...) open in the panel. */
    private fun onPhoneMenuEntry(entry: PhoneMenuEntry) {
        when (entry) {
            PhoneMenuEntry.LockScreen -> lockScreen()
            PhoneMenuEntry.PictureInPicture -> enterPictureInPictureNow()
            PhoneMenuEntry.AirPlay -> showAirPlay()
            PhoneMenuEntry.VolumeBoost -> toggleVolumeBoost()
            PhoneMenuEntry.NightMode -> toggleNightMode()
            PhoneMenuEntry.PassthroughOff -> passthroughOffOnce()
            PhoneMenuEntry.EvenVolume -> toggleEvenVolume()
            PhoneMenuEntry.AbLoop -> pressLoop()
            PhoneMenuEntry.RepeatVideo -> toggleRepeatVideo()
            PhoneMenuEntry.NextChapter -> stepChapter(forward = true)
            PhoneMenuEntry.PreviousChapter -> stepChapter(forward = false)
            PhoneMenuEntry.NextFrame -> frameStep(forward = true)
            PhoneMenuEntry.PreviousFrame -> frameStep(forward = false)
            PhoneMenuEntry.SaveFrame -> saveFrame()
            PhoneMenuEntry.Decoder -> switchDecoderFromMenu()
            PhoneMenuEntry.OtherEngine -> switchEngine()
            PhoneMenuEntry.OpenInAnotherApp -> openInAnotherApp()
            PhoneMenuEntry.LoadSubtitleFile -> subtitleFiles.pick()
            PhoneMenuEntry.SaveToDownloads -> saveToDownloads()
            PhoneMenuEntry.MarkWatched -> toggleWatched()
            PhoneMenuEntry.ResetZoom -> resetZoom()
            PhoneMenuEntry.ReportStutter -> reportStutter()
            PhoneMenuEntry.ReportWrongPicture -> reportWrongPicture()
            PhoneMenuEntry.CopyLinkAtTime -> copyLinkAtTime()
            PhoneMenuEntry.MarkIntro -> markIntroTap()
            PhoneMenuEntry.RemoveIntroMark -> removeIntroMark()
            // Pages of the panel itself, opened there.
            PhoneMenuEntry.PictureShape, PhoneMenuEntry.Stereo, PhoneMenuEntry.Speed, PhoneMenuEntry.SleepTimer,
            PhoneMenuEntry.RotatePicture, PhoneMenuEntry.PlayerVolume, PhoneMenuEntry.Brightness,
            PhoneMenuEntry.AddBookmark, PhoneMenuEntry.Bookmarks, PhoneMenuEntry.PlaybackDetails,
            PhoneMenuEntry.AddToList, PhoneMenuEntry.Chapters,
            -> Unit
        }
    }

    /** What a line of the television's More menu does. */
    private fun onTvMenuEntry(entry: PlayerMenuEntry) {
        when (entry) {
            PlayerMenuEntry.LockScreen -> lockScreen()
            PlayerMenuEntry.PlaybackDetails -> showPlaybackDetails()
            PlayerMenuEntry.StartOver -> startOver()
            PlayerMenuEntry.Stereo -> showStereo()
            PlayerMenuEntry.PictureInPicture -> enterPictureInPictureNow()
            PlayerMenuEntry.Decoder -> switchDecoderFromMenu()
            PlayerMenuEntry.OtherEngine -> switchEngine()
            PlayerMenuEntry.OpenInAnotherApp -> openInAnotherApp()
            PlayerMenuEntry.SaveToDownloads -> saveToDownloads()
            PlayerMenuEntry.MarkWatched -> toggleWatched()
            PlayerMenuEntry.VolumeBoost -> toggleVolumeBoost()
            PlayerMenuEntry.NightMode -> toggleNightMode()
            PlayerMenuEntry.PassthroughOff -> passthroughOffOnce()
            PlayerMenuEntry.EvenVolume -> toggleEvenVolume()
            PlayerMenuEntry.AbLoop -> pressLoop()
            PlayerMenuEntry.RepeatVideo -> toggleRepeatVideo()
            PlayerMenuEntry.NextChapter -> stepChapter(forward = true)
            PlayerMenuEntry.PreviousChapter -> stepChapter(forward = false)
            PlayerMenuEntry.NextFrame -> frameStep(forward = true)
            PlayerMenuEntry.PreviousFrame -> frameStep(forward = false)
            PlayerMenuEntry.AddBookmark -> addBookmark(playing?.currentPosition ?: 0L, "")
            PlayerMenuEntry.Bookmarks -> showSheet { it.showBookmarks(bookmarkActions()) }
            PlayerMenuEntry.ReportStutter -> reportStutter()
            PlayerMenuEntry.ReportWrongPicture -> reportWrongPicture()
            PlayerMenuEntry.CopyLinkAtTime -> copyLinkAtTime()
            PlayerMenuEntry.LoadSubtitleFile -> subtitleFiles.pick()
            PlayerMenuEntry.MarkIntro -> markIntroTap()
            PlayerMenuEntry.RemoveIntroMark -> removeIntroMark()
            // Pages of the panel itself, opened there.
            PlayerMenuEntry.SleepTimer, PlayerMenuEntry.RemoteKeys, PlayerMenuEntry.Speed, PlayerMenuEntry.PictureShape,
            PlayerMenuEntry.RotatePicture, PlayerMenuEntry.PlayerVolume, PlayerMenuEntry.AddToList,
            PlayerMenuEntry.Chapters,
            -> Unit
        }
    }

    /**
     * Copy link at this time is offered for a video from a message in a chat that allows sharing
     * (the same gate as Open in another app: a protected chat's video is not passed on, nor its
     * link), and not for one played from pieces, which has no single message to point at.
     */
    private fun linkAtTimeOffered(): Boolean =
        LinkAtTime.offered(hasMessage(), mayShare = savable == true, fromPieces = joinedSource != null)

    /**
     * The video's Telegram link with the moment playing, on the clipboard ([LinkAtTime]). A chat
     * with no message links (a secret chat) says so instead of copying nothing; a failed lookup
     * says the same, since either way there is no link to give.
     */
    private fun copyLinkAtTime() {
        if (!linkAtTimeOffered()) return
        val at = playing?.currentPosition ?: 0L
        val chat = chatId
        val message = messageId
        lifecycleScope.launch {
            val link = LinkAtTime.of(runCatching { com.tmplayer.data.MessageLink.of(chat, message) }.getOrNull(), at)
            if (isFinishing || isDestroyed) return@launch
            if (link == null) {
                showGestureFeedback(L.shareNoMessageLinks)
                return@launch
            }
            val clipboard = getSystemService(android.content.ClipboardManager::class.java)
            val copied = runCatching { clipboard?.setPrimaryClip(android.content.ClipData.newPlainText("Telegram link", link)) }.isSuccess
            showGestureFeedback(if (copied) L.shareLinkCopied else L.shareNoMessageLinks)
        }
    }

    /** The trace's marks, kept for the next Send logs (L3). */
    private val stutterMarks by lazy { PlaybackMarks(PlaybackMarks.fileIn(this)) }

    /** Report stutter is offered while this device plays the video: the other screen's decoder is not in the trace. */
    private fun stutterReportable(): Boolean = player != null && !castUp()

    /**
     * Report stutter (L3): marks this moment and keeps the last minute of the playback trace for
     * the next Send logs, and says so. A second press within [PlaybackMarks.DEBOUNCE_MS], a press in
     * the middle of a seek, one with no reading yet or on a cast never throws: the first two say
     * "Already marked", the others mark with what there is or do nothing.
     */
    private fun reportStutter() = markMoment(RemedyRunner.ViewerReport.Stutter)

    /**
     * "Picture looks wrong" (RL10): the same mark as [reportStutter], and the ladder's next way to
     * show the picture, once ([RemedyRunner.viewerReported]).
     */
    private fun reportWrongPicture() = markMoment(RemedyRunner.ViewerReport.WrongPicture)

    private fun markMoment(report: RemedyRunner.ViewerReport) {
        if (!stutterReportable()) return
        val window = runCatching { traceRecorder.momentWindow(PlaybackMarks.WINDOW_MS) }.getOrDefault(emptyList())
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { stutterMarks.mark(window) }.getOrDefault(PlaybackMarks.Result.FAILED) }
            if (isFinishing || isDestroyed) return@launch
            if (result == PlaybackMarks.Result.ALREADY) return@launch showGestureFeedback(L.playerStutterMarkedAlready)
            // The ladder takes its step once; when it did, its own line says so and nothing is added.
            val outcome = runCatching { remedies.viewerReported(report) }.getOrDefault(RemedyRunner.ViewerOutcome.NoClass)
            if (outcome == RemedyRunner.ViewerOutcome.Tried) return@launch
            val wrong = report == RemedyRunner.ViewerReport.WrongPicture
            showGestureFeedback(if (wrong) L.playerReportWrongPictureMarked else L.playerStutterMarked)
        }
    }

    /** Paused by the viewer, not stalled for bytes: when the frame steps are offered. */
    private fun isPaused(): Boolean = playing?.playWhenReady == false

    /**
     * The repeat line: the start, then the end (and the loop runs), then off, as the desktop's R
     * key has it ([AbLoop.press]). The marks go on the bar and "A-B" beside the time.
     */
    private fun pressLoop() {
        val exo = player ?: return
        val clock = Translator.messages.formatter::clock
        // The two repeats exclude each other: marking a stretch turns Repeat this video off.
        if (com.tmplayer.data.RepeatVideo.repeats(currentItem(), keepRepeat)) {
            com.tmplayer.data.RepeatVideo.set(false)
        }
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
     * Repeat this video (EG-F52): the end seeks to the start and plays on, with no next-up card and
     * no count towards "Still watching?". Turning it on clears the A-B repeat, and it lapses by
     * itself when another video starts unless "Keep repeat on" is set ([com.tmplayer.data.RepeatVideo]).
     * Nothing polls: the end of the video is what asks.
     */
    private fun toggleRepeatVideo() {
        val on = !com.tmplayer.data.RepeatVideo.repeats(currentItem(), keepRepeat)
        com.tmplayer.data.RepeatVideo.set(on, currentItem())
        if (on) {
            if (abLoop != null) {
                abLoop = null
                controls?.loop = null
                loopHandler.removeCallbacks(loopWatch)
            }
            hideNextUp()
            nextUpShown = false
        }
        showGestureFeedback(if (on) L.playerRepeatVideoOn else L.playerRepeatOff)
    }

    /**
     * One frame forward or back on a paused picture. Media3 has no frame step, so this seeks by one
     * frame's length ([FrameStep]) with an exact seek, which decodes from the keyframe before it and
     * shows the very frame asked for. Slower than a nearest keyframe seek, which is fine for a step.
     */
    private fun frameStep(forward: Boolean) {
        val exo = player ?: return
        if (exo.playWhenReady) return
        val fps = videoFps()
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
        val at = playing?.currentPosition ?: 0L
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
        val exo = playing ?: return
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

    /** The Chapters page's OK (WP-26): play from the start of the chapter chosen, and say which it is. */
    private fun jumpToChapterStart(startMs: Long) {
        val exo = playing ?: return
        // A file with no seek index is not seeked (a linear read stalls a stick), and a mark past
        // the end of a malformed file lands just short of it rather than ending the video (PB-02).
        if (refuseSeek()) return
        val marks = Chapters.inside(chapters, exo.duration)
        exo.seekTo(Chapters.seekTarget(startMs, exo.duration))
        val index = Chapters.at(marks, startMs)
        if (index >= 0) showGestureFeedback(Chapters.label(marks, index))
    }

    /** The menus' chapter lines: the next chapter, or the start of this one, as [Chapters] has it. */
    private fun stepChapter(forward: Boolean) {
        val exo = playing ?: return
        if (refuseSeek()) return
        val at = exo.currentPosition
        // Only the marks inside the video: one past its end would end it, mark it watched and start the next.
        val marks = Chapters.inside(chapters, exo.duration)
        val target = if (forward) Chapters.next(marks, at) else Chapters.previous(marks, at)
        if (target == null) {
            showGestureFeedback(if (forward) L.playerLastChapter else L.playerBeforeFirstChapter)
            return
        }
        exo.seekTo(Chapters.seekTarget(marks[target].startMs, exo.duration))
        showGestureFeedback(Chapters.label(marks, target))
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
                val local = downloadedVideo
                val found = withContext(Dispatchers.IO) {
                    runCatching {
                        if (local != null) {
                            FileChapters.read(language) { offset, length -> local.readAt(offset, length) }
                        } else {
                            val client = Td.awaitAuthorizedSession().client
                            FileChapters.read(language) { offset, length -> readDownloaded(client, offset, length) }
                        }
                    }.onFailure { Log.w("TMPlayer", "Chapters not read", it) }.getOrDefault(emptyList())
                }
                if (found.isNotEmpty()) {
                    chapters = found
                    controls?.chapters = found
                    skipCtl.chapters(found)
                    wakeEndWatch()
                    return@launch
                }
                // All of it is here and there are none: nothing later will change that.
                if (local != null || downloadComplete) return@launch
            }
        }
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
        showGestureFeedback(if (FormFactor.isTv(this) && note.isEmpty()) L.playerBookmarkSavedTv(at) else L.playerBookmarkSaved(at))
    }

    /** What the bookmark sheets do with a choice: all of it lands here, on this video. */
    private fun bookmarkActions() = BookmarkSheetActions(
        bookmarks = bookmarkList,
        position = { playing?.currentPosition ?: 0L },
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
            playing?.seekTo(mark.positionMs)
            showGestureFeedback(Translator.messages.formatter.clock(mark.positionMs))
        },
    )

    /** The menu's Volume boost: switched on the sound already playing, and kept for every video. */
    private fun toggleVolumeBoost() {
        val on = !volumeBoostOn
        volumeBoostOn = on
        volumeBoost?.enabled = on
        showGestureFeedback(if (on) soundOnLine(L.playerVolumeBoostOn) else L.playerVolumeBoostOff)
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
        showGestureFeedback(if (on) soundOnLine(L.playerQuietLoudScenesOn) else L.playerQuietLoudScenesOff)
        lifecycleScope.launch { runCatching { settings.setNightMode(on) } }
    }

    /**
     * What a boost or night mode toast says (WP-24): [line] while the sound can be shaped, and the
     * plain "Not applied" while it goes to the speakers as a Dolby or DTS bitstream.
     */
    private fun soundOnLine(line: String): String = if (AudioPolicy.boostReaches(soundBitstream)) line else L.playerBoostNotApplied

    /**
     * At the first frame, once per video, a stored Volume boost or Night mode says so (WP-24), or that
     * it is not applied when the sound is a bitstream. The sound's output opens before the first
     * picture, so [soundBitstream] is known here; a later track change is read again by the menu's lines.
     */
    private fun saySoundOptions() {
        if (soundChipShown || (!volumeBoostOn && !nightModeOn)) return
        soundChipShown = true
        val line = when {
            !AudioPolicy.boostReaches(soundBitstream) -> L.playerBoostNotApplied
            volumeBoostOn -> L.playerVolumeBoostOn
            else -> L.playerNightModeChip
        }
        showGestureFeedback(line)
    }

    /** Whether "Turn off passthrough for this video" is offered: the sound is a bitstream and the sound can still be rebuilt as PCM. */
    private fun passthroughOffOffered(): Boolean =
        soundBitstream && builtPolicy?.passthroughOff == false && vlcEngine == null && !castUp()

    /**
     * "Turn off passthrough for this video" (WP-24): the ladder's own passthrough-off step (A2), taken
     * as the viewer's press through [RemedyRunner.viewerPassthroughOff], so its hop count is shared and
     * the video is rebuilt once with the sound as PCM. When it takes the step the runner says so
     * itself; otherwise the press is answered here.
     */
    private fun passthroughOffOnce() {
        val outcome = runCatching { remedies.viewerPassthroughOff() }.getOrDefault(RemedyRunner.ViewerOutcome.NoClass)
        if (outcome != RemedyRunner.ViewerOutcome.Tried) showGestureFeedback(L.playerPassthroughOffFailed)
    }

    /** 3D video is not offered while a cast plays the video. */
    private fun stereoOffered(): Boolean = cast?.connected != true && cast?.casting != true

    /**
     * Before the player is first prepared: what the file's name says about 3D, and the effects
     * pipeline built only for a file that says it is 3D, or when 3D is already on (an episode
     * that follows in a new player), so turning it on there needs no second start.
     */
    private fun prepareStereo(exo: ExoPlayer) {
        stereoTagged = Stereo3d.detect(listOf(mediaFileName, mediaCaption, mediaTitle).joinToString(" "))
        stereoHinted = false
        if (stereoPipeline) return
        // A turned picture too: the same video's player built again (a decoder change) keeps it.
        if (stereo.on || stereoTagged != null || pictureRotation != PictureRotation.NONE) {
            stereoPipeline = true
            // Before the player is attached, so read here rather than through [player].
            val box = shapeBox()
            sentPipelineKey = pipelineKey(box)
            sentShapeBox = box
            exo.setVideoEffects(pipelineEffects())
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
        return if (videoOnFfmpeg(exo)) L.playerStereoNotSoftware else null
    }

    /** Whether the picture is on the app's own (FFmpeg) decoder, whose frames skip Media3's effects. */
    private fun videoOnFfmpeg(exo: ExoPlayer): Boolean = runCatching {
        (0 until exo.rendererCount).any { index ->
            exo.getRendererType(index) == C.TRACK_TYPE_VIDEO &&
                exo.getRenderer(index).state != androidx.media3.exoplayer.Renderer.STATE_DISABLED &&
                DecoderSwitch.isFfmpeg(exo.getRenderer(index))
        }
    }.getOrDefault(false)

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
        // 3D draws for the whole frame, a headset's halves above all: the picture goes back whole.
        if (next.on) resetZoom(say = false)
        if (!next.on && !before.on) return null
        if (stereoPipeline) {
            sendPipelineEffects()
        } else if (next.on) {
            buildPipelineNow(exo)
        }
        stereoHinted = true
        // The shape moves into the pipeline with 3D (and stays there once it is built), and a
        // headset's two halves take a copy of the subtitles each, and lose it after.
        renderShape()
        showGestureFeedback(if (next.on) L.playerStereoOnNow(next.view.label()) else L.playerStereoOffNow)
        return null
    }

    /**
     * Builds the effects pipeline in a player that plays without it: it can only be handed over
     * before the player prepares, so the player stops and prepares again where it stood, playing
     * or paused as it was. A tunneled decoder never hands its frames over, so tunneling goes off
     * for this player first.
     */
    private fun buildPipelineNow(exo: ExoPlayer) {
        stereoPipeline = true
        val at = exo.currentPosition
        val play = exo.playWhenReady
        exo.stop()
        if (tunnelingActive) {
            tunnelingActive = false
            (exo.trackSelector as? DefaultTrackSelector)?.let { it.parameters = it.buildUponParameters().setTunnelingEnabled(false).build() }
        }
        sendPipelineEffects(force = true)
        exo.seekTo(at)
        exo.prepare()
        exo.playWhenReady = play
    }

    /**
     * Why the picture cannot be turned in the video playing, or null when it can: the VLC engine
     * (whose frames do not pass through Media3), an HDR picture, whose colours the pipeline is not
     * trusted with, and a video on the app's own (FFmpeg) decoder.
     */
    private fun rotationBlocked(): String? {
        if (vlcEngine != null) return L.playerRotatePictureNotVlc
        val exo = player ?: return L.playerRotatePictureNotVlc
        val color = exo.videoFormat?.colorInfo
        if (color != null && androidx.media3.common.ColorInfo.isTransferHdr(color)) return L.playerRotatePictureNotHdr
        return if (videoOnFfmpeg(exo)) L.playerRotatePictureNotSoftware else null
    }

    /**
     * Rotate picture (#34): the picture turned [degrees] clockwise as it is drawn, at once and
     * where it plays. The first turn in a player builds the effects pipeline, a moment's reload
     * at the same place ([buildPipelineNow]); every turn after that is only a new matrix. The
     * turned picture is fitted inside the screen with its shape kept, and the zoom starts over.
     * Answers with why it could not turn (the turn then stays as it was), or null.
     */
    private fun applyRotation(degrees: Int): String? {
        val turn = PictureRotation.normalise(degrees)
        if (turn == pictureRotation) return null
        if (turn != PictureRotation.NONE) rotationBlocked()?.let { return it }
        val exo = player ?: return null
        pictureRotation = turn
        resetZoom(say = false)
        if (stereoPipeline) {
            sendPipelineEffects()
        } else if (turn != PictureRotation.NONE) {
            buildPipelineNow(exo)
        }
        renderShape()
        showGestureFeedback(
            if (turn == PictureRotation.NONE) L.playerRotatePictureOff
            else L.playerRotatePictureNow(L.messages.formatter.number(turn)),
        )
        return null
    }

    /**
     * Once a video, on its first picture: Player volume turned down is said over the picture, so
     * a quiet start (or a silent one at 0%) has its reason on screen rather than seeming broken.
     */
    private fun hintPlayerVolume() {
        if (playerVolumeHinted || playerVolumePercent >= PlayerVolume.FULL) return
        playerVolumeHinted = true
        showGestureFeedback(L.playerVolumeLevelNow(L.messages.formatter.scalePercent(playerVolumePercent)))
    }

    /**
     * Player volume (#33): the engine's own gain set at once, under the device's volume, and the
     * figure said over the picture. Written down a moment after the last step, so a held key
     * writes the setting once rather than twenty times a second.
     */
    private fun setPlayerVolume(percent: Int) {
        val next = percent.coerceIn(PlayerVolume.MIN, PlayerVolume.FULL)
        playerVolumePercent = next
        keyMuted = false
        val gain = PlayerVolume.gain(next)
        player?.volume = gain
        vlcEngine?.volume = gain
        showGestureFeedback(L.playerVolumeLevelNow(L.messages.formatter.scalePercent(next)))
        playerVolumeSave?.cancel()
        playerVolumeSave = lifecycleScope.launch {
            kotlinx.coroutines.delay(VOLUME_SAVE_DELAY_MS)
            runCatching { settings.setPlayerVolume(next) }
        }
    }

    /** The window's brightness in per cent, or [TouchPrefs.BRIGHTNESS_SYSTEM] while it follows the system's (WP-27). */
    private fun currentBrightnessPercent(): Int {
        val level = window.attributes.screenBrightness
        return if (level < 0f) TouchPrefs.BRIGHTNESS_SYSTEM else (level * 100).roundToInt().coerceIn(TouchPrefs.BRIGHTNESS_MIN, 100)
    }

    /**
     * Brightness from its page (WP-27): [percent] on this window, or the system's own when it is under
     * [TouchPrefs.BRIGHTNESS_MIN] (the override released), said over the picture and kept when asked to.
     */
    private fun setBrightness(percent: Int) {
        val system = percent < TouchPrefs.BRIGHTNESS_MIN
        window.attributes = window.attributes.also {
            it.screenBrightness = if (system) android.view.WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE else percent.coerceIn(TouchPrefs.BRIGHTNESS_MIN, 100) / 100f
        }
        showGestureFeedback(if (system) L.playerBrightnessSystem else L.playerBrightnessNow(L.messages.formatter.scalePercent(percent)))
        saveBrightness(if (system) TouchPrefs.BRIGHTNESS_SYSTEM else percent)
    }

    /** Written only while "Remember brightness" is on (the store checks); the system level forgets it. */
    private fun saveBrightness(percent: Int) {
        lifecycleScope.launch { runCatching { settings.setPlayerBrightness(percent) } }
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
        val exo = playing ?: return
        if (exo.playbackState == Player.STATE_IDLE) exo.prepare()
        exo.play()
        keepScreenOn(true)
    }

    /**
     * Lets the queued downloads "Still watching?" paused carry on. Any answer counts, not only Keep
     * watching: play from a headset, and Back or Go back, which otherwise left them paused in
     * Downloads with nothing to say why.
     *
     * Off the main thread, because each of these starts a foreground service, a call into the
     * system that an ANR caught a Back press waiting on (HX35). Handed over at once rather than
     * later, so the app is still the one in front and Android still allows the service.
     */
    private fun resumeHeldDownloads() {
        val held = downloadsHeldForTheCard
        downloadsHeldForTheCard = emptyList()
        if (held.isEmpty()) return
        val context = applicationContext
        App.backgroundScope.launch { held.forEach { OfflineDownloads.resume(context, it) } }
    }

    /**
     * Whether Save to Downloads means anything for this video: not one that is a download already,
     * not one the queue has, and not one with no message to download it from again.
     */
    private fun canSaveToDownloads(): Boolean =
        savable == true && downloadedVideo == null && fileId > 0 && chatId != 0L && messageId != 0L &&
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
                    downloadedVideo != null -> L.playerAlreadyInDownloads
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

    /** The two speeds a tap on the speed button swaps between. */
    private val speedSwap = SpeedSwap()

    /** A tap on the phone's speed button: the other of the last two speeds, or false for the list. */
    private fun swapSpeed(): Boolean {
        val other = speedSwap.tap(playbackSpeed) ?: return false
        setSpeed(other)
        return true
    }

    private fun setSpeed(value: Float) {
        speedSwap.chose(playbackSpeed, value)
        playbackSpeed = value
        controls?.showSpeed(value)
        playing?.setPlaybackSpeed(value)
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
        val at = playing?.currentPosition ?: return false
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
                // Said once through the hint registry (HintIds.PLAYER); Remote keys always sits in More, Help (WP-20).
                feedback?.message(L.playerRemoteHint + "\n" + L.playerRemoteKeysHint, holdMs = HINT_MS)
            } else {
                val lines = FirstRunHint.phone(touchPrefs, L)
                feedback?.offer(lines.joinToString("\n"), L.commonGotIt, holdMs = PHONE_HINT_MS) {}
            }
        }
    }

    /** Back to the first frame, for a viewer who did not want the saved position. */
    private fun startOver() {
        val exo = playing ?: return
        // A short watch from here leaves a saved place alone, so starting over forgets it.
        lifecycleScope.launch { settings.clearResumePosition(chatId, messageId, currentItem()) }
        exo.seekTo(0)
        exo.playWhenReady = true
        showGestureFeedback(L.playerFromTheStart)
    }

    /**
     * The first Info press says that holding it reports a stutter (WP-21), once ever, through the hint
     * registry (WP-68): never while the video plays, except on the very first run, and never once the
     * tour was skipped twice. It is marked as it appears.
     */
    private fun hintInfoHold() {
        lifecycleScope.launch {
            val state = runCatching { settings.hintState.first() }.getOrNull()
            val playingNow = playing?.isPlaying == true
            if (!com.tmplayer.ui.components.hintAllowed(state, com.tmplayer.data.HintIds.INFO_HOLD, inPlayback = playingNow)) return@launch
            runCatching { settings.bumpHint(com.tmplayer.data.HintIds.INFO_HOLD) }
            feedback?.message(L.playerInfoHoldHint, holdMs = HINT_MS)
        }
    }

    /** What is actually playing: the figures a bug report needs, in words a viewer can read. */
    private fun showPlaybackDetails() {
        val (title, lines) = playbackDetails() ?: return
        showSheet { it.showDetails(title, lines) }
    }

    /** The details' heading and lines, for the sheet or the More panel's page. */
    private fun playbackDetails(): Pair<String, List<String>>? {
        val exo = player ?: return null
        val video = exo.videoFormat
        val audio = exo.audioFormat
        val lines = buildList {
            if (video != null) {
                val width = video.width.toString()
                val height = video.height.toString()
                add(
                    videoFps().takeIf { it > 0 }
                        ?.let { L.playerDetailsPictureFps(width, height, "%.3g".format(it)) }
                        ?: L.playerDetailsPicture(width, height),
                )
                video.sampleMimeType?.let { add(L.playerDetailsVideoCodec(com.tmplayer.player.demux.FfmpegFormats.codecLabel(it))) }
                watchDecoder.takeIf { it.isNotEmpty() }?.let { add(L.playerDetailsVideoDecoder(it)) }
            }
            if (audio != null) {
                add(L.playerDetailsSound(audio.channelCount.toString(), audio.sampleRate.toString()))
                audio.sampleMimeType?.let { add(L.playerDetailsAudioCodec(com.tmplayer.player.demux.FfmpegFormats.codecLabel(it))) }
                audioDecoderName?.let { add(L.playerDetailsAudioDecoder(it)) }
            }
            // The container, and whether FFmpeg read it (a file Media3's own extractors do not read).
            (video?.containerMimeType ?: audio?.containerMimeType)?.let { container ->
                val demuxer = com.tmplayer.player.demux.FfmpegFormats.demuxerOf(container)
                add(
                    if (demuxer != null) {
                        L.playerDetailsContainerFfmpeg(com.tmplayer.player.demux.FfmpegFormats.containerLabel(demuxer))
                    } else {
                        L.playerDetailsContainerValue(container.substringAfter('/'))
                    },
                )
            }
            if (fileSizeBytes > 0) add(L.playerDetailsFile(Translator.messages.formatter.bytes(fileSizeBytes)))
            fileDatacenter?.let { add(L.playerDetailsDatacenter(it.toString())) }
            val here = Translator.messages.formatter.percent(downloadedFraction.toDouble())
            add(if (isRealDownload()) L.playerDetailsDownloaded(here) else L.playerDetailsCached(here))
            add(L.playerDetailsSpeed(PlaybackSpeed.label(playbackSpeed)))
            if (!downloadComplete && speed.bytesPerSec >= StreamStats.MIN_MEANINGFUL_SPEED) {
                add(L.playerDetailsLineSpeed(Translator.messages.formatter.speed(speed.bytesPerSec)))
            }
        }
        return mediaTitle.ifBlank { L.playerPlaybackDetails } to lines
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
        // Held to the pair it is in, upright or sideways, but still turning end for end with the phone:
        // SCREEN_ORIENTATION_LOCKED kept the very rotation it was in, so a phone then held the other
        // way up (a lap, flat on a table) showed the picture upside down (WP-37).
        requestedOrientation = if (resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT) {
            android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
        } else {
            android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        }
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
                // The click is the way for a reader (TalkBack's double tap), a mouse and a keyboard;
                // a finger holds for a second with the progress bar filling, like the remote's OK.
                setOnClickListener { unlockScreen() }
                setOnTouchListener { _, event ->
                    if (event.getToolType(0) != android.view.MotionEvent.TOOL_TYPE_FINGER &&
                        event.getToolType(0) != android.view.MotionEvent.TOOL_TYPE_STYLUS
                    ) {
                        return@setOnTouchListener false
                    }
                    when (event.actionMasked) {
                        android.view.MotionEvent.ACTION_DOWN -> {
                            if (unlockHeldKey == 0) startUnlockHold(TOUCH_UNLOCK_HOLD, holdMs = LockedKeys.TOUCH_HOLD_MS)
                            true
                        }
                        android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                            if (unlockHeldKey == TOUCH_UNLOCK_HOLD) stopUnlockHold()
                            true
                        }
                        else -> true
                    }
                }
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
    private fun unlockHint(): String = when {
        FormFactor.isTv(this) -> L.playerHoldOkToUnlock
        // A screen reader double taps; a finger holds a second (WP-37).
        getSystemService(android.view.accessibility.AccessibilityManager::class.java)?.isTouchExplorationEnabled == true -> L.playerTapToUnlock
        else -> L.playerHoldToUnlock
    }

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
    private fun startUnlockHold(keyCode: Int, heldMs: Long = 0L, holdMs: Long = LockedKeys.HOLD_MS) {
        val pill = unlockPill ?: return
        val already = heldMs.coerceIn(0L, holdMs)
        val left = holdMs - already
        unlockHeldKey = keyCode
        showUnlockPill()
        unlockText?.text = L.playerKeepHoldingToUnlock
        unlockProgress?.let { bar ->
            bar.visibility = View.VISIBLE
            val from = (bar.max * already / holdMs).toInt()
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
            onCancel = ::cancelAutoSkip,
        ).also { pill ->
            pill.onLostFocus = { controls?.focusBar() }
            pill.onFocused = { controls?.poke() }
        }
        undoChip = UndoSeekChip(
            root = findViewById(R.id.player_root),
            below = findViewById(R.id.overlay_container),
            tv = FormFactor.isTv(this),
            onUndo = ::undoSeek,
        )
        suggestChip = SubtitleOfferChip(
            root = findViewById(R.id.player_root),
            below = findViewById(R.id.overlay_container),
            tv = FormFactor.isTv(this),
            onAction = { suggestAction?.invoke() },
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
                autoplayNext = runCatching { settings.autoplayNextNow() }.getOrDefault(true)
                while (true) {
                    if (playing?.isPlaying == true) delay(500) else endWatchWake.receive()
                    // Whichever engine is on screen: with VLC playing, ExoPlayer is gone, and
                    // reading it alone left a video on VLC with no Skip button and no next-up card.
                    val exo = playing ?: continue
                    // The live copy each tick: the Episodes panel's switch changes it mid-video.
                    val autoplay = com.tmplayer.data.PlayAll.playsOn(playAllMode, autoplayNext)
                    updateSkipPill(exo, autoplay)
                    if (!autoplay) {
                        // Switched off while the card was up: it must not promise a next episode.
                        if (nextUpShown || nextUpCard?.isShown == true) {
                            hideNextUp()
                            nextUpShown = false
                        }
                        continue
                    }
                    val next = _episodes.value.next ?: continue
                    val duration = exo.duration
                    if (duration <= 0 || nextUpDismissed || repeatsAtTheEnd() || locked || inPictureInPicture) continue
                    // Autoplay is going to stop and ask, or the sleep timer to stop: nothing
                    // is starting in thirty seconds, so nothing says it is.
                    if (sleepAtTheEnd || StillWatching.askBeforeAutoplay(autoplayedInARow)) continue
                    val position = exo.currentPosition
                    val left = duration - position
                    val credits = SkipSegments.creditsStart(skipCtl.segments(duration), duration)
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
        skipCtl.start(currentItem())
        val metadata = OnlineMetadata.current ?: return
        itemScope.launch {
            afterOpening()
            var seconds = durationSec
            if (seconds <= 0) {
                withTimeoutOrNull(SKIP_TIMES_WAIT_MS) {
                    while (true) {
                        val known = playing?.duration?.takeIf { it > 0 }
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
            skipCtl.online(skipSegments)
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
    private fun updateSkipPill(engine: Player, autoplay: Boolean) {
        val pill = skipPill ?: return
        val cardCoversTheEnd = autoplay && !nextUpDismissed && !repeatsAtTheEnd() && _episodes.value.next != null
        val blocked = locked || inPictureInPicture || statusOverlay.visibility == View.VISIBLE || nextUpCard?.isShown == true
        val wasShown = pill.isShown
        val offer = pill.follow(
            skip = skipCtl,
            engine = engine,
            blocked = blocked,
            // The count holds while a television's remote is busy with the row (it could not cancel).
            holdCount = FormFactor.isTv(this) && controlsUp,
            nowMs = android.os.SystemClock.elapsedRealtime(),
            skipped = skippedSegments,
            cardCoversTheEnd = cardCoversTheEnd,
        )
        offeredSkip = offer?.segment
        if (offer is SkipOffer.Fire) skipOffered()
        if (!wasShown && pill.isShown) liftCornerOffers()
        reachSkipPill()
    }

    /** Cancel on the countdown (the phone's button, a television's Back): this stretch stays a plain button. */
    private fun cancelAutoSkip() {
        val segment = offeredSkip ?: return
        skipCtl.cancel(segment)
        playing?.let { updateSkipPill(it, autoplayNext) }
    }

    /** One tap of "Mark this as the intro": the start the first time, the end and a saved mark the second. */
    private fun markIntroTap() {
        skipCtl.markTap(playing?.currentPosition ?: 0L, playing?.duration?.coerceAtLeast(0L) ?: 0L)
        wakeEndWatch()
    }

    private fun removeIntroMark() {
        skipCtl.removeMark(playing?.duration?.coerceAtLeast(0L) ?: 0L)
        wakeEndWatch()
    }

    /** The pill's press, or OK on a television: past the stretch on offer, which then stays gone. */
    private fun skipOffered() {
        val exo = playing ?: return
        val segment = offeredSkip ?: return
        val duration = exo.duration.takeIf { it > 0 } ?: return
        skippedSegments += segment.startMs
        hideSkipPill()
        exo.seekTo(segment.target(duration).coerceAtMost(duration))
    }

    private fun hideSkipPill() {
        offeredSkip = null
        skipPill?.hide()
        controls?.reachSkipPill(null)
    }

    /**
     * With the row up on a television the skip pill is part of the focus chain (WP-19): focusable
     * while it shows, and Up from the scrub bar lands on it. With the row down OK skips and the pill
     * is nobody's focus. A remote standing on a pill that goes is put back on the bar.
     */
    private fun reachSkipPill() {
        val pill = skipPill ?: return
        if (!FormFactor.isTv(this)) return
        val onOffer = controlsUp && pill.isShown && offeredSkip != null
        val lost = pill.reachable(onOffer)
        controls?.reachSkipPill(if (onOffer) pill.viewId else null)
        if (lost) controls?.focusBar()
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

    /** The remote has walked onto the next-up card ([PlayerRemoteKeys.nextUpKey]); false means OK is pause. */
    private var nextUpEngaged = false

    private fun hideNextUp() {
        nextUpEngaged = false
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
        track(App.backgroundScope.launch(com.tmplayer.data.AccountOwner(accountOwner)) {
            runCatching {
                if (store.isWatched(item.chatId, item.messageId)) {
                    store.markUnwatched(item.chatId, item.messageId)
                } else {
                    store.markWatched(WatchedRecord.of(item, title, System.currentTimeMillis(), manual = true))
                    settings.clearResumePosition(item.chatId, item.messageId, item)
                }
            }
        })
    }

    /** The list's autoplay switch: the same setting Settings shows. */
    private fun setAutoplayFromEpisodes(on: Boolean) {
        autoplayNext = on
        // The end watch reads the live copy: a paused video's loop sleeps, so it is told.
        endWatchWake.trySend(Unit)
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
        // The card never takes the remote by itself (Right or Down walks onto it); the row
        // taking focus walks it off.
        if (visible) nextUpEngaged = false
        reachSkipPill()
        liftSubtitles()
        liftCornerOffers()
        updateDownloadChip()
        if (!visible && !startOverOffered) showFirstRunHint()
    }

    /**
     * On a phone the transport row is taller than the television's, and the Skip intro pill and
     * the next-up card, which sit a fixed height off the bottom, landed on the timebar and the
     * total time in landscape. While the row is up they climb just clear of its top line, and
     * settle back once it goes. A television lifts them the same way (WP-05): its row grows
     * upward with the system font, and the "Back to" chip comes up after a scrub on the bar.
     */
    private fun liftCornerOffers() {
        val cluster = findViewById<View>(R.id.controls_cluster)
        val root = findViewById<View>(R.id.player_root)
        // WP-05: a television lifts every offer too. Its cluster stands 27 dp off the bottom and
        // grows upward with the system font, so a fixed height over it overlapped it at 1.3.
        val offers = listOfNotNull(skipPill?.view, nextUpCard?.view, undoChip?.view, suggestChip?.view)
        offers.forEach { offer ->
            // After the next layout, so an offer that has only just been shown has a height.
            offer.doOnPreDraw {
                val lift = NextUpCard.liftOverRow(root, cluster, offer, controlsUp)
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
        // Not ready (opening, or stalled) is the "Loading" state; ready and playing on a buffer
        // that ran short is "Getting the next part". A buffer the load control has stopped
        // filling (its byte cap) has nothing more coming, so it never keeps the chip up.
        val starting = exo == null || exo.playbackState != Player.STATE_READY
        val bufferFull = exo != null && !starting && !exo.isLoading
        cacheChipWanted = LoadingChip.shows(cacheChipWanted, aheadMs, isRealDownload(), starting, bufferFull)
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
            // Playing on a short buffer: no figures, the line speed is in Playback details.
            // "The next part" is for a video that is in parts; a single file is just loading more of itself.
            !starting -> if (pieces.isNotEmpty()) L.playerGettingNextPart else L.playerGettingMore
            else -> L.playerCachingPercent(percent)
        }
    }

    /**
     * Whether the bytes coming down are a download the viewer asked for (in Downloads, or queued
     * for it) rather than the watch cache filling for playback. Only a real download says
     * "downloaded"; the cache says caching, or a viewer reads it as a download they never started.
     */
    private fun isRealDownload(): Boolean =
        downloadedVideo != null || (fileId > 0 && OfflineDownloads.isDownloading(fileId))

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
            sessionArtwork = if (tinyHeap) null else withContext(Dispatchers.Default) { artworkOf(full) }
            refreshSessionMetadata()
        }
    }

    /**
     * [bitmap] as PNG bytes for the session's artwork: Telegram's thumbnail as it came, which is
     * already the size a notification draws.
     */
    private fun artworkOf(bitmap: android.graphics.Bitmap): ByteArray? = runCatching {
        // ExoPlayer copies these bytes into every metadata update, on the main thread (Sentry: an
        // out of memory error in MediaMetadata.setArtworkData on a 48 MB heap), so the smaller the
        // better: a JPEG of at most 512 px, a few kilobytes rather than a full-size PNG.
        val longest = maxOf(bitmap.width, bitmap.height).coerceAtLeast(1)
        val scale = (SESSION_ART_MAX_PX.toFloat() / longest).coerceAtMost(1f)
        val source = if (scale < 1f) {
            android.graphics.Bitmap.createScaledBitmap(
                bitmap, (bitmap.width * scale).toInt().coerceAtLeast(1), (bitmap.height * scale).toInt().coerceAtLeast(1), true,
            )
        } else bitmap
        try {
            java.io.ByteArrayOutputStream().use { out ->
                source.compress(android.graphics.Bitmap.CompressFormat.JPEG, SESSION_ART_QUALITY, out)
                out.toByteArray()
            }
        } finally {
            if (source !== bitmap) source.recycle()
        }
    }.getOrNull()

    /** A device with a heap this small keeps no session picture at all: the lock screen does without. */
    private val tinyHeap: Boolean by lazy {
        val am = getSystemService(ACTIVITY_SERVICE) as android.app.ActivityManager
        am.isLowRamDevice || am.memoryClass <= TINY_HEAP_MB
    }

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
        !FormFactor.isTv(this) && effectiveOrientation() == ScreenOrientation.Landscape

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
        val now = SystemClock.elapsedRealtime()
        val waited = now - host.shownAt
        // One decision for every screen (FQ1, StartVerdict): the stall sheet itself is
        // showSlowStart's, this is the line under the loader before it.
        val verdict = StartVerdict.of(
            StartVerdict.Facts(
                waitedMs = waited,
                quietMs = if (lastProgressAtMs > 0) now - lastProgressAtMs else waited,
                local = downloadComplete,
                deviceOffline = networkOffline,
                bytesPerSec = rate,
                speedLimited = Td.speedLimitedNow(),
            ),
            slowAfterMs = LOADER_SLOW_MS,
        )
        val speedLine = if (rate >= StreamStats.MIN_MEANINGFUL_SPEED) {
            val figure = Translator.messages.formatter.speed(rate)
            if (isRealDownload()) L.playerDownloadingRate(figure) else L.playerCachingRate(figure)
        } else {
            null
        }
        host.note = when (verdict) {
            // Telegram's own cap on an account without Premium (CP83): said, nothing offered.
            StartVerdict.State.Quota -> listOfNotNull(speedLine, L.playerSpeedLimited).joinToString("\n")
            // Nothing measurable arriving yet: said quietly, long before the stall sheet.
            StartVerdict.State.Slow, StartVerdict.State.Stalled -> L.connectSlowTitle
            StartVerdict.State.Waiting -> speedLine.takeIf { slow && !downloadComplete }
            // The offline notice already says it.
            StartVerdict.State.Offline -> null
        }
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
        lastReadBytes = -1L
        rebufferWatch = lifecycleScope.launch {
            // Telegram's connections reopened once per wait (OpeningStall.NUDGE_MS).
            var nudged = false
            while (true) {
                delay(OPENING_WATCH_TICK_MS)
                val exo = playing ?: return@launch
                if (openingFilm || exo.playbackState != Player.STATE_BUFFERING) return@launch
                val buffered = exo.bufferedPosition
                if (buffered > lastBufferedMs) {
                    if (lastBufferedMs >= 0) noteOpeningProgress()
                    lastBufferedMs = buffered
                }
                noteBytesRead()
                val local = downloadComplete || downloadedVideo != null || (joinedSource != null && joinedAllLocal)
                // Offline has its own words on the chip, and carries on by itself once back.
                if (networkOffline) {
                    lastProgressAtMs = SystemClock.elapsedRealtime()
                    continue
                }
                if (!nudged && OpeningStall.nudgeDue(local, lastProgressAtMs, SystemClock.elapsedRealtime())) {
                    nudged = true
                    Td.nudgeForStream(fileId)
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
        sheetKeyed = false
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
        showFailedFormat()

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
        statusCopy?.visibility = View.GONE
        statusFormat?.visibility = View.GONE
        formatErrorPending = null
        statusReload?.visibility = View.GONE
        statusOpenWith?.visibility = View.GONE
        statusBack?.visibility = View.GONE
        statusLighter?.visibility = View.GONE
        statusDecoder?.visibility = View.GONE
        statusEngine?.visibility = View.GONE
        statusEngineAlways?.visibility = View.GONE
        statusName?.visibility = View.GONE
    }

    /** The track types this player already moved to the other decoder by itself ([switchDecoderByItself]). */
    private val decodersSwitched = mutableSetOf<Int>()

    /** The remedy ladder for this screen's videos (RL6); its logic is [RemedyRunner]'s, these lines only reach the player. */
    private val remedies: RemedyRunner by lazy { RemedyRunner(remedyHost, RemedyRunner.clockOf(loopHandler)) }

    private val remedyHost = object : RemedyRunner.Host {
        override val sdk = android.os.Build.VERSION.SDK_INT
        override val appVersion: String = com.tmplayer.BuildConfig.VERSION_NAME
        override fun today() = RemedyFacts.today()
        override fun videoTag() = RemedyFacts.videoTag(chatId, messageId, fileId)
        override fun sample() = player?.takeIf { vlcEngine == null && !castUp() }
            ?.let { RemedyFacts.sample(it, hidden = videoHiddenUnseen || inBackgroundAudio, nowMs = SystemClock.elapsedRealtime()) }
        override fun facts(excluded: Set<String>) = RemedyFacts.ladder(
            player, watchDecoder, excluded, decoderPicked = videoDecoder != com.tmplayer.data.VideoDecoder.Automatic,
            tunneling = tunnelingActive, tunnelingPicked = tunneledMode != com.tmplayer.player.TunneledMode.Auto,
            passthrough = builtPolicy?.passthroughOff == false,
        )
        override fun classKey() = RemedyFacts.classKey(player, mediaMime.ifBlank { mediaFileName })
        override fun failedRenderer(error: PlaybackException) = RemedyFacts.failedRenderer(player, error)
        override fun engineInput() = engineInput?.takeIf { engineSwitchOnError && vlcCanTake() && stereo == Stereo3d.OFF }
        override fun engineRunning() = if (vlcEngine != null) {
            EngineDecision(com.tmplayer.data.PlayerEngine.Vlc, engineDecision?.reason ?: EngineReason.Default)
        } else {
            engineDecision?.takeIf { it.engine == com.tmplayer.data.PlayerEngine.ExoPlayer } ?: EngineDecision(com.tmplayer.data.PlayerEngine.ExoPlayer, EngineReason.Default)
        }
        // Posted: a re-open from inside the player's own callback would release it mid-dispatch.
        // False without the session a re-open needs ([moveToEngine]), so the ladder gives up instead of losing the hop.
        override fun reopen(): Boolean {
            if (castClient == null) return false
            loopHandler.post { moveToEngine(com.tmplayer.data.PlayerEngine.ExoPlayer, engineDecision?.reason ?: EngineReason.Default, L.commonLoading) }
            return true
        }
        override fun switchEngine(decision: EngineDecision): Boolean {
            if (castClient == null) return false
            if (decision.engine == com.tmplayer.data.PlayerEngine.Vlc && !vlcCanTake()) return false
            engineSwitches++
            loopHandler.post { moveToEngine(decision.engine, decision.reason, L.commonLoading) }
            return true
        }
        override fun say(text: String) {
            feedback?.message(text, holdMs = SEEK_NOTICE_MS)
        }
        override fun offer(text: String, action: String, onTap: () -> Unit) {
            feedback?.offer(text, action, holdMs = STUTTER_OFFER_MS, focus = FormFactor.isTv(this@PlayerActivity)) { onTap() }
        }
        override fun sheet(text: String) {
            playing?.pause()
            engineOffer = engineInput?.let { EngineSelector.toOffer(it, com.tmplayer.data.PlayerEngine.ExoPlayer) }?.takeIf { otherEngineOffered() }
            showError(text, retryable = true)
        }
        override fun redeliver(error: PlaybackException) = playerListener.onPlayerError(error)
        // One writer for the app, so two quick saves never land out of order.
        override fun saveMemory(memory: com.tmplayer.data.RemedyMemory) = RemedyMemoryWrites.save(settings, memory)
        override fun log(line: String) {
            Log.i(START_TAG, line)
        }
        override fun traceHop(hop: TraceHop) {
            traceRecorder.noteHop(hop)
            traceRecorder.noteCounters()
        }
        override fun traceFacts(facts: Map<String, String>) = traceRecorder.noteFacts(facts)
    }

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
        // One budget with the remedy ladder: not once it spent this level or gave up ([RemedyRunner]).
        val video = plan.trackType == C.TRACK_TYPE_VIDEO
        if (!remedies.allowsDecoderSwitch(video)) return false
        remedies.decoderSwitched(video)
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
        if (plan.trackType == C.TRACK_TYPE_VIDEO) videoSwitchedToSoftware = plan.toFfmpeg
        if (!DecoderSwitch.apply(exo, plan)) Log.w(START_TAG, "Decoder: this player has no decoder gate, the switch changes nothing")
        failurePinned = false
        showStatus(if (plan.toFfmpeg) L.playerDecoderSoftwareNow else L.playerDecoderDeviceNow)
        exo.prepare()
        exo.playWhenReady = true
    }

    /**
     * The More menu's Try the other decoder while the video plays (WP-25): the switch for the
     * picture on screen, or null where there is none to make: VLC or a cast plays it, no picture
     * yet, or the other side has no decoder for its format ([DecoderSwitch.planForPicture]).
     */
    private fun menuDecoderPlan(): DecoderSwitch.Plan? {
        if (vlcEngine != null || castUp()) return null
        return runCatching { DecoderSwitch.planForPicture(player) }.getOrNull()
    }

    /**
     * The decoder line's detail (WP-25): which decoder shows the picture now, or, before a move to
     * software, what it costs: an HDR picture shown as SDR, or a large picture a weak device may
     * not decode in time (the could-not-keep-up warning, said before rather than after).
     */
    private fun menuDecoderDetail(): String? {
        val plan = menuDecoderPlan() ?: return null
        if (!plan.toFfmpeg) return L.playerDecoderSoftwareNow
        val format = player?.videoFormat
        if (format != null) {
            val color = format.colorInfo
            if (color != null && androidx.media3.common.ColorInfo.isTransferHdr(color)) return L.playerDecoderSoftwareHdr
            if (com.tmplayer.data.SoftwareDecode.heavy(format.sampleMimeType, format.width, format.height, hardware = false, weak = weakDevice)) {
                return L.playerDecoderSoftwareSlow
            }
        }
        return L.playerDecoderDeviceNow
    }

    /**
     * The menu's Try the other decoder (WP-25), for a picture that plays wrong without an error
     * (green or choppy frames): the same [DecoderSwitch] the failure sheet uses, the player
     * stopped and prepared again where it stood, with the same tracks (the selector keeps them) and
     * the same play or pause. It is the viewer's pick ([RemedyRunner.viewerPickedDecoder]): it
     * spends this video's decoder level, so neither the ladder nor the player's own one-time switch
     * moves the picture back by itself, and the class starts there next time. A second press goes
     * back, the viewer's choice again; it never loops by itself.
     */
    private fun switchDecoderFromMenu() {
        val plan = menuDecoderPlan() ?: return
        val exo = player ?: return
        val position = exo.currentPosition
        val wasPlaying = exo.playWhenReady
        // The gate moves the track (PB2-01); a player without one has nothing to switch.
        if (!DecoderSwitch.apply(exo, plan)) {
            Log.w(START_TAG, "Decoder: this player has no decoder gate, the menu's switch changes nothing")
            return
        }
        decoderPlan = null
        if (plan.trackType == C.TRACK_TYPE_VIDEO) videoSwitchedToSoftware = plan.toFfmpeg
        decodersSwitched += plan.trackType
        // Software is learned for the class only after a clean minute on it, and never for a
        // picture too heavy to decode in software here (PB2-09).
        val format = exo.videoFormat
        val heavy = plan.toFfmpeg && format != null && com.tmplayer.data.SoftwareDecode.heavy(
            format.sampleMimeType, format.width, format.height, hardware = false, weak = weakDevice,
        )
        remedies.viewerPickedDecoder(plan.toFfmpeg, heavy = heavy)
        Log.i(START_TAG, "Decoder: the viewer moved the picture to the ${if (plan.toFfmpeg) "FFmpeg" else "device"} decoder at $position ms")
        runCatching {
            exo.stop()
            exo.seekTo(position)
            exo.prepare()
            exo.playWhenReady = wasPlaying
        }.onFailure { Log.w(START_TAG, "Decoder: the switch could not prepare the player again", it) }
        showStatus(if (plan.toFfmpeg) L.playerDecoderSoftwareNow else L.playerDecoderDeviceNow)
    }

    /**
     * Gives the failure sheet's suggested way out, the one focus lands on, the accent pill, and the
     * others the quiet one, so the sheet always has exactly one button that reads as the answer.
     */
    private fun markPrimaryAction(primary: View) {
        for (button in listOf(statusReload, statusBack, statusOpenWith, statusLighter, statusDecoder, statusEngine, statusEngineAlways)) {
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
        // Last and quietest: the logs to send with a report of it, and the details to copy.
        statusLogs?.visibility = View.VISIBLE
        // A television has no clipboard to paste from: Send logs is the way to report there.
        statusCopy?.visibility = if (FormFactor.isTv(this)) View.GONE else View.VISIBLE
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
        // The other engine (PR2): under the other decoder when there is one, else the answer.
        val engine = showEngineOffer(primary = switch == null)
        if (switch != null) {
            statusDecoder?.apply {
                markPrimaryAction(this)
                requestFocus()
            }
            return
        }
        if (engine) return
        if (fileId <= 0) return
        lifecycleScope.launch {
            // What is on the disk first, for at most a second (then the safe answer: nothing there),
            // so the sheet's buttons come together and the remote is placed once (WP-21).
            val local = downloadedVideo
            val availability = if (local != null) {
                LocalFileAvailability.Complete
            } else {
                // The cancellable form: the screen gone while it waits stops here, rather than
                // building the sheet's buttons on a destroyed screen (PB2-19).
                kotlinx.coroutines.withTimeoutOrNull(AVAILABILITY_WAIT_MS) {
                    com.tmplayer.data.runCatchingCancellable { Td.localFileAvailability(fileId) }.getOrDefault(LocalFileAvailability.Missing)
                } ?: LocalFileAvailability.Missing
            }
            if (retryable) {
                statusReload?.apply {
                    text = L.commonReload
                    visibility = View.VISIBLE
                    markPrimaryAction(this)
                    focusLate(this)
                }
            }
            val downloaded = local?.length()
                ?: com.tmplayer.data.runCatchingCancellable { Td.localDownloadedBytes(fileId) }.getOrDefault(0L)
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
                focusLate(this)
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
            val local = downloadedVideo
            val outcome = runCatching {
                if (local != null) {
                    ExternalPlayer.handOverFile(this@PlayerActivity, local.file?.absolutePath ?: local.uri().toString(), mediaTitle)
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
        // VLC opens the same source again where it stood ([VlcEngine.prepareAgain]).
        vlcEngine?.let { vlc ->
            vlc.player.prepare()
            vlc.play()
            return
        }
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
        lastReadBytes = -1L
        openingWatch = lifecycleScope.launch {
            // Telegram's connections reopened once per wait (OpeningStall.NUDGE_MS).
            var nudged = false
            while (true) {
                delay(OPENING_WATCH_TICK_MS)
                if (!openingFilm) return@launch
                val buffered = playing?.bufferedPosition ?: -1L
                if (buffered > lastBufferedMs) {
                    if (lastBufferedMs >= 0) noteOpeningProgress()
                    lastBufferedMs = buffered
                }
                noteBytesRead()
                val local = downloadComplete || downloadedVideo != null || (joinedSource != null && joinedAllLocal)
                if (networkOffline) {
                    noteOpeningProgress()
                    continue
                }
                if (!nudged && OpeningStall.nudgeDue(local, lastProgressAtMs, SystemClock.elapsedRealtime())) {
                    nudged = true
                    Td.nudgeForStream(fileId)
                }
                if (OpeningStall.stalled(opening = true, local = local, lastProgressAtMs, SystemClock.elapsedRealtime())) {
                    showSlowStart()
                    return@launch
                }
            }
        }
    }

    /**
     * Bytes the player read since the last look count as arriving. TDLib's updates follow [fileId]
     * alone, not the other pieces of a joined video, and a soundtrack that starts late in the file
     * holds the buffered position at zero while a hundred megabytes come in: either way the wait
     * read as "hasn't started" with the video on its way.
     */
    private fun noteBytesRead() {
        val read = readBytes.get()
        if (read > lastReadBytes) {
            if (lastReadBytes >= 0) noteOpeningProgress()
            lastReadBytes = read
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
            OpeningStall.Reason.NoLine -> L.playerStalledNoLinePlain
            OpeningStall.Reason.Slow -> if (stalledMidPlay) L.playerStalledMidSlow else L.playerStalledSlow
        }
        // Telegram's lighter copy (CP80), said where it applies. Not Telegram's cap on an account
        // without Premium (CP83): the cap slows a download and never stops it, and this sheet is
        // for nothing arriving at all, so it is said on the slow line only.
        val lighter = if (OpeningStall.reason(Td.link.value) == OpeningStall.Reason.Slow) lighterQuality() else null
        // Which buttons, in one place for every screen (U1); the hook for "Open in another app" is there.
        val actions = StalledSheet.actions(
            reason = OpeningStall.reason(Td.link.value),
            canDownload = canSaveToDownloads(),
            lighterCopy = lighter != null,
            canOpenInAnotherApp = savable == true,
        )
        val canDownload = StalledSheet.Action.DownloadFirst in actions
        val detail = listOfNotNull(
            // The app is holding its requests back for a flood wait: say for how long (the one line this sheet gained from the L4 follow-ups).
            StalledSheet.floodWaitSeconds(Td.floodWaitRemainingSeconds())
                ?.let { L.playerStalledFloodWait(com.tmplayer.data.Failures.waitPhrase(it)) },
            L.playerLighterHint.takeIf { StalledSheet.Action.LighterCopy in actions },
            L.playerStalledDownloadHint.takeIf { canDownload },
        ).joinToString("\n")
        statusDetail.visibility = if (detail.isNotEmpty()) View.VISIBLE else View.GONE
        statusDetail.text = detail
        statusLighter?.apply {
            text = lighter?.let { L.playerLighter(com.tmplayer.data.TelegramQualities.label(it.height)) }.orEmpty()
            visibility = if (StalledSheet.Action.LighterCopy in actions) View.VISIBLE else View.GONE
        }
        statusBack?.visibility = if (StalledSheet.Action.Back in actions) View.VISIBLE else View.GONE
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
        showStatus(L.playerTryingAgain)
        val exo = player
        if (exo == null) {
            App.backgroundScope.launch { runCatching { Td.retryConnecting() } }
            retryPlayback()
            return
        }
        // In this order: the connections reopened and the download TDLib calls active but that
        // sends nothing dropped, and only then the stream opened again. Opened first, it would take
        // the dead download for a running one and never ask for the bytes (the same stall, again
        // and again until another video was watched).
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                runCatching { Td.retryConnecting() }
                runCatching { Td.dropStalledDownload(fileId) }
            }
            val current = player ?: return@launch
            current.stop()
            current.prepare()
            current.playWhenReady = true
        }
    }

    /** Download first on [showSlowStart]: the video joins the download queue and the player steps back. */
    private fun downloadFirst() {
        if (!canSaveToDownloads()) return
        OfflineDownloads.start(this, mediaItemForCache().copy(fileId = uploadId(), sizeBytes = uploadSize()), chatTitle)
        runOnUiThread {
            android.widget.Toast.makeText(applicationContext, L.playerDownloadFirstStarted, android.widget.Toast.LENGTH_LONG).show()
        }
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
                if ((seen || inBackgroundAudio) && playing?.isPlaying == true) saveResumePosition()
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
        // Back after more than a minute away, or restored into a process that never had the PIN
        // (HX88): a PIN profile's video does not show again until its PIN is given. The player
        // closes, its place kept, and the shell behind it asks for the PIN.
        if (com.tmplayer.data.ProfileUnlock.lockedNow()) {
            Log.i("TMPlayer", "the open profile is locked again; closing the player for its PIN")
            finish()
            return
        }
        if (openingFilm) watchOpening()
        // Back in front: the session comes home from the notification, the sound never stopped,
        // and the picture comes back on (turned off only while nobody could see it).
        endBackgroundAudio()
        OnlineSubtitles.current?.translations?.setForeground(true)
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
                playing?.pause()
                finish()
            }
        }
    }

    override fun onStop() {
        super.onStop()
        // The pause mark's timers never run in the background (WP-21), and a scan that was not let go
        // lands nowhere: nothing was sought, so there is nothing to save (WP-79).
        controls?.stopPauseDim()
        cancelScan()
        // A key that was held when the window went away never reports its release.
        endRemoteHold()
        // A SubMaker translation waits while nobody can see it, and carries on when they are back.
        OnlineSubtitles.current?.translations?.setForeground(false)
        // Counted again from the start when the viewer is back ([onStart]).
        openingWatch?.cancel()
        rebufferWatch?.cancel()
        saveResumePosition(leaving = !inPictureInPicture)
        // This place out to the other devices, once the saves have settled (ResumeSync).
        com.tmplayer.data.ResumeSync.leavingPlayer(settings)
        // On a TV the video carries on with the phone put away; nothing here to pause.
        if (cast?.casting == true) return
        // In picture in picture the activity is stopped while the video is still on screen and
        // still the point of it; pausing here would stop the very thing the mode exists for.
        // With background audio on, the sound carries on from the notification instead.
        if (inPictureInPicture) return
        if (keepsPlayingUnseen()) startBackgroundAudio() else playing?.pause()
    }

    override fun onDestroy() {
        pipReceiver?.let { runCatching { unregisterReceiver(it) } }
        pipReceiver = null
        // A translation belongs to the video on screen: it ends with the player.
        endTranslations()
        crashNote.ended()
        com.tmplayer.data.SelfInstaller.removeBeforeInstall(saveBeforeInstall)
        com.tmplayer.data.BeforeSwitch.removePlayer(stopBeforeSwitch)
        loopHandler.removeCallbacks(watchTick)
        remedies.stop()
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
        // The player first and the view after it, so the main thread waits on neither (HX35).
        letGoOfPlayer()
        touchSurface = null
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

    /**
     * Tells [StreamingPlays] whether this screen is being watched, which is what lets other
     * downloads yield to it and come back when it has been paused long ([DownloadYield]). A cast
     * counts as watching: the phone is then the one feeding the TV.
     */
    private fun reportWatching() {
        com.tmplayer.data.StreamingPlays.setPlaying(
            listOf(heldFileId) + heldPieceIds,
            playing = playing?.playWhenReady == true || cast?.casting == true,
        )
    }

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

    /**
     * Before a profile switch (HX88): the place is written while this profile's stores are still
     * the open ones, waited for, and the player closes, since its video belongs to the profile
     * that is closing.
     */
    private val stopBeforeSwitch: suspend () -> Unit = {
        withContext(Dispatchers.Main) { saveResumePosition(leaving = true) }?.join()
        // And every watched mark of this player still under way: written for this profile first.
        for (job in markJobs.toList()) job.join()
        withContext(Dispatchers.Main) {
            // Written already: the close must not write again, for the next profile.
            savedForHandOver = true
            if (!isFinishing) finish()
        }
    }

    /** The app's icon as PNG bytes, made once, for the private media metadata (HX88). */
    private val neutralIcon: ByteArray? by lazy {
        runCatching {
            val drawable = packageManager.getApplicationIcon(applicationInfo)
            val size = 192
            val bitmap = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888)
            val canvas = android.graphics.Canvas(bitmap)
            drawable.setBounds(0, 0, size, size)
            drawable.draw(canvas)
            java.io.ByteArrayOutputStream().also { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        }.getOrNull()
    }

    private fun neutralArtwork(): ByteArray? = neutralIcon

    /** Writes the place; returns the write, for a caller that must wait for it ([saveBeforeInstall]). */
    private fun saveResumePosition(leaving: Boolean = false): Job? {
        if (savedForHandOver) return null
        val exo = playing ?: return null
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
                if (forget) store.clearResumePosition(chat, message, here, owner = accountOwner)
                else store.saveResumePosition(chat, message, position, duration, description, owner = accountOwner)
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
                // Its profile's turn, and only while that profile may show outside the app.
                WatchNextPublisher.apply(context, changes, stamp = accountOwner)
            }
        }
    }

    /** Whether this video came from a message, which is what the Watched list is keyed by. */
    private fun hasMessage(): Boolean = chatId != 0L && messageId != 0L

    /** This video as the Watched list keeps it, with the player's length when the message had none. */
    private fun watchedRecord(manual: Boolean): WatchedRecord {
        val known = durationSec.takeIf { it > 0 }
            ?: playing?.duration?.takeIf { it > 0 }?.let { (it / 1_000).toInt() }
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
        // As the profile this player opened under (HX88): a late mark never lands in the next
        // profile's list, and a switch waits for it ([stopBeforeSwitch]).
        track(App.backgroundScope.launch(com.tmplayer.data.AccountOwner(accountOwner)) { runCatching { store.markWatched(record) } })
    }

    /** The watched writes of this player still under way, which a switch waits for. */
    private val markJobs = java.util.concurrent.CopyOnWriteArrayList<kotlinx.coroutines.Job>()

    private fun track(job: kotlinx.coroutines.Job) {
        markJobs += job
        job.invokeOnCompletion { markJobs -= job }
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
            track(App.backgroundScope.launch(com.tmplayer.data.AccountOwner(accountOwner)) { runCatching { store.markUnwatched(chat, message) } })
            showGestureFeedback(L.playerMarkedUnwatched)
        } else {
            onWatchedList = true
            markedWatchedHere = true
            val record = watchedRecord(manual = true)
            val item = currentItem()
            track(App.backgroundScope.launch(com.tmplayer.data.AccountOwner(accountOwner)) {
                runCatching {
                    store.markWatched(record)
                    settings.clearResumePosition(chat, message, item)
                }
            })
            showGestureFeedback(L.playerMarkedWatched)
        }
    }

    companion object {
        private const val EXTRA_FILE_ID = "file_id"

        /** `SystemClock.elapsedRealtime()` when Play was pressed, for the start timing log. */
        private const val EXTRA_PRESSED_AT = "pressed_at"

        /** Play all or Shuffle (WP-32, WP-87): the [com.tmplayer.data.PlayAll.Mode] and the seed, carried to the next video of the run. */
        private const val EXTRA_PLAY_ALL = "play_all"
        private const val EXTRA_PLAY_SEED = "play_seed"

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

        /** The longest a release may hold the main thread: see [letGoOfPlayer] (HX35). */
        private const val RELEASE_TIMEOUT_MS = 500L

        /** How long into a video the subtitles found for the viewer are looked for (U5): after the picture has settled. */
        private const val SUGGEST_DELAY_MS = 4_000L
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
        internal const val EXTRA_OUTSIDE_APP_TASK = "outside_app_task"
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

        /** How long after the last Player volume step the setting is written. */
        private const val VOLUME_SAVE_DELAY_MS = 600L
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

        /** NextLib's own figures for its FFmpeg video renderer, kept when only the thread count changes. */
        private const val FFMPEG_MAX_DROPPED_TO_NOTIFY = 50
        private const val FFMPEG_VIDEO_BUFFERS = 4
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

        /** [stepDelay]'s direction for a long press: ten of the 100 ms steps, a second. */
        const val LONG_DELAY_STEPS = 10

        /** The pseudo key code of a finger held on the unlock pill (a real key is positive, none is 0). */
        private const val TOUCH_UNLOCK_HOLD = -1

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

        /** The longest the failure sheet waits for the disk's answer before it shows its buttons (WP-21). */
        private const val AVAILABILITY_WAIT_MS = 1_000L

        /** The phone's gesture hint, up to four lines: long enough to read, "Got it" ends it sooner. */
        private const val PHONE_HINT_MS = 12_000L

        /** The captions' climb out from under the raised transport row, and back. */
        private const val SUBTITLE_LIFT_MS = 200L

        /** The highest the bottom padding goes, so a short picture never pushes the lines off its top. */
        private const val MAX_SUBTITLE_BOTTOM = 0.4f

        /** A thumbnail at least this wide for its height is a frame of the film, not a logo. */
        private const val REAL_FRAME_ASPECT = 1.3f

        /** The session picture's longest edge and JPEG quality: a lock screen and a notification need no more. */
        private const val SESSION_ART_MAX_PX = 512
        private const val SESSION_ART_QUALITY = 85

        /** A Java heap limit this small (MB) or less leaves the session picture out. */
        private const val TINY_HEAP_MB = 64

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
