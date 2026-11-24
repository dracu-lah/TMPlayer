package com.tmplayer.data

// :app's Android system integration, on iOS (docs/IOS.md, "Android's phone screens on
// iOS"). The classes left out of the iOS build keep their names and the members the screens call.
// Television channels, the crash reporter, the self installer and background jobs do not exist on
// iOS and answer as Android does without them. The download and folder export services are reached
// through ServiceHost, where the iOS shell plugs its own runner; until it does, they do nothing.

import android.content.Context
import android.content.Intent
import android.net.Uri
import platform.Foundation.NSLocale
import platform.Foundation.preferredLanguages
import com.tmplayer.data.TvChannels.Channel
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.cinterop.alloc
import kotlinx.cinterop.ptr
import kotlinx.cinterop.toKString

/** Android's foreground download service: its intents go to ServiceHost on iOS. */
class DownloadService {
    companion object {
        const val ACTION_DOWNLOAD = "com.tmplayer.action.DOWNLOAD"
        const val ACTION_CANCEL = "com.tmplayer.action.CANCEL_DOWNLOAD"
        const val ACTION_PAUSE = "com.tmplayer.action.PAUSE_DOWNLOAD"
        const val ACTION_RESUME = "com.tmplayer.action.RESUME_DOWNLOAD"
        const val EXTRA_FILE_ID = "fileId"
        const val EXTRA_FILE_IDS = "fileIds"
        const val EXTRA_TITLE = "title"
        const val EXTRA_SIZE = "size"
        const val EXTRA_CHAT_ID = "chatId"
        const val EXTRA_MESSAGE_ID = "messageId"
        const val EXTRA_CHAT_TITLE = "chatTitle"
        const val EXTRA_DURATION = "duration"
        const val EXTRA_MIME = "mime"
        const val EXTRA_FILE_NAME = "fileName"
    }
}

/** Android's service for moving the downloads folder: on iOS the folder never moves. */
class DownloadRelocationService

object TvChannelsPublisher {
    fun supported(context: Context): Boolean = false

    /** The promo fixture's channel pictures: iOS has no TV home screen to put them on. */
    internal var fixtureArt: ((TvChannels.Art) -> java.io.File?)? = null

    suspend fun publish(context: Context, programs: List<TvChannels.Program>, stamp: AccountStamp? = null, names: (Channel) -> String) = Unit
    suspend fun clearPrograms(context: Context) = Unit

    /** No home screen channel exists to launch the app with a show. */
    fun showFrom(intent: Intent?): TvChannels.Target.Show? = null
}

object WatchNextPublisher {
    suspend fun clearAll(context: Context) = Unit
    suspend fun fillMissingArt(context: Context) = Unit

    /** No "Play next" row exists to launch the app with a video. */
    fun videoFrom(intent: Intent?): WatchNext.Video? = null

    /** Android TV's "Play next" row; iOS has none to update. */
    @Suppress("UNUSED_PARAMETER")
    suspend fun apply(context: Context, changes: List<WatchNext.Change>, stamp: AccountStamp? = null) = Unit
}

/**
 * Crash reports go through Sentry's Android SDK, which is not in the iOS build: nothing is written
 * down, so no question is ever pending and the shell is never held.
 */
object CrashReports {
    val available: Boolean = false

    /** A stutter report goes through Sentry on Android; none is sent from iOS. */
    @Suppress("UNUSED_PARAMETER")
    fun reportStutter(facts: StutterReport.Facts): Boolean = false

    /** Android counts plays by ABI, decoder and engine for the stutter report; nothing is reported from iOS. */
    @Suppress("UNUSED_PARAMETER")
    fun notePlayStarted(abi: String, decoder: String) = Unit

    /** What Send logs says of crashes: nothing is recorded on iOS, so only a safe mode run says anything. */
    fun logLines(): List<String> = buildList {
        if (SafeMode.active) add("Safe mode: on, the last two runs crashed soon after opening")
    }

    enum class Question { Crash, NotResponding, NativeCrash }

    private val _pending = MutableStateFlow<Question?>(null)
    val pending: StateFlow<Question?> = _pending.asStateFlow()
    private val _holdShell = MutableStateFlow(false)
    val holdShell: StateFlow<Boolean> = _holdShell.asStateFlow()

    fun watch(context: Context) = Unit
    fun start(context: Context, enabled: Boolean) = Unit
    fun review(context: Context, enabled: Boolean) = Unit
    fun sendPending(context: Context) = Unit
    fun stop() = Unit
    fun discardPending() = Unit
    fun handled(error: Throwable) = Unit
}

/** Android's periodic checks (new videos, the sponsor reminder); iOS has no such job yet. */
object BackgroundChecks {
    const val OPEN_SUPPORT = "support"
    const val EXTRA_OPEN_CHAT = "com.tmplayer.extra.OPEN_CHAT"
    fun schedule(context: Context) = Unit

    /**
     * iOS posts no downloads, episodes, new video or update notices yet (only the player's fixed "Playback
     * stopped" line), so Settings does not list a Notifications page of switches that do nothing (WP-38).
     */
    val postsNotices: Boolean get() = false

    /** Whether iOS lets the app post, as Android's check of its permission and switch. */
    fun canNotify(context: Context): Boolean = android.app.IosNotifications.allowed
}

/**
 * The app's language. iOS keeps a per-app language in its own Settings page, which reaches the app
 * as the first preferred language; the in-app choice is the one the screens show.
 */
object AppLocales {
    fun system(context: Context): List<String> =
        NSLocale.preferredLanguages.mapNotNull { it as? String }.filter { it.isNotBlank() }

    fun apply(context: Context, saved: String) = Unit

    fun adopt(context: Context, saved: String): String? = null
}

/** Android 12's per-app night mode for the chosen theme (WP-44). iOS draws the theme itself, so nothing here. */
object AppNightMode {
    fun apply(context: Context, choice: ThemeChoice) = Unit
}

/**
 * Transfers in progress and done. iOS shows them on the Downloads screen; a finished one is also
 * said in the app, as Android does when it cannot post to the shade.
 */
class AndroidTransferNotifier(@Suppress("UNUSED_PARAMETER") context: Context) : com.tmplayer.platform.TransferNotifier {
    override fun begin(id: Long, kind: com.tmplayer.platform.TransferNotifier.Kind, title: String) = Unit
    override fun progress(id: Long, done: Long, total: Long?, bytesPerSecond: Long?) = Unit
    override fun complete(id: Long, title: String, body: String, open: com.tmplayer.platform.TransferNotifier.OpenTarget?) {
        announce(title)
    }
    override fun fail(id: Long, title: String, reason: String, retryable: Boolean) = Unit
    override fun cancel(id: Long) = Unit
    override val capabilities: Set<com.tmplayer.platform.TransferNotifier.Capability> = emptySet()

    companion object {
        private val _completions = kotlinx.coroutines.flow.MutableSharedFlow<String>(extraBufferCapacity = 8)
        val completions: kotlinx.coroutines.flow.SharedFlow<String> = _completions

        fun announce(title: String) {
            _completions.tryEmit(title)
        }
    }
}

/** Android's "Updated to" pill follows a self update; the App Store says that itself. */
object AfterUpdate {
    suspend fun onLaunch(context: Context): String? = null
    suspend fun afterReplace(context: Context) = Unit
}

/**
 * Android's update setup reads the installer and the ABI; an iOS build never updates itself:
 * AltStore or SideStore does, from the source, as the iOS privacy note says. So checks are off
 * (upstream's Home checks when it comes back online), and the version is the bundle's,
 * "1.28.5 (build 9)", where About, Settings' Info and help row and a bug report show it. Left
 * alone it read "0" (iPad, build 7, 2026-11-14), and any release on the feed would have looked
 * newer.
 */
@Suppress("UNUSED_PARAMETER")
fun Updates.configureForAndroid(context: Context) {
    val info = platform.Foundation.NSBundle.mainBundle.infoDictionary
    val version = (info?.get("CFBundleShortVersionString") as? String)?.takeIf { it.isNotBlank() }
    val build = (info?.get("CFBundleVersion") as? String)?.takeIf { it.isNotBlank() }
    enabled = false
    configure(
        installedVersion = listOfNotNull(version, build?.let { "(build $it)" }).joinToString(" ").ifEmpty { "0" },
        offers = { false },
    )
}

/**
 * The device as a bug report and a saved log name it, "iPad (iPad13,18), iPadOS 27.0.1", where
 * Android's line has the maker, the model and the Android version: the kind iOS calls it, the exact
 * model it is (one "iPad" covers every generation) and the system's own name and version.
 */
@OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
fun appleDevice(): String {
    val device = platform.UIKit.UIDevice.currentDevice
    val machine = kotlinx.cinterop.memScoped {
        val name = alloc<platform.posix.utsname>()
        if (platform.posix.uname(name.ptr) == 0) name.machine.toKString() else ""
    }
    val kind = if (machine.isEmpty()) device.model else "${device.model} ($machine)"
    return "$kind, ${device.systemName} ${device.systemVersion}"
}

/**
 * This build's version, build number and device for the version line and the support text
 * (WP-104): the bundle's own, with the device as bug reports name it. No update state: the build
 * has no check, so the line says updates come from where it was installed.
 */
object VersionFacts {
    @Suppress("UNUSED_PARAMETER")
    fun info(context: Context): VersionInfo = VersionInfo(
        version = com.tmplayer.BuildConfig.VERSION_NAME,
        build = com.tmplayer.BuildConfig.VERSION_CODE.toString(),
        device = appleDevice(),
    )
}

/** Installing updates by itself is an Android 12 feature of the sideloaded build. */
object AutoUpdater {
    val supported: Boolean = false
    fun onBackground(context: Context) = Unit
    fun onForeground() = Unit
    suspend fun runIfDue(context: Context): Boolean = false
}

object SelfInstaller {
    suspend fun canUpdateSilently(context: Context): Boolean = false

    /** Work to finish before an update installs; nothing installs itself on iOS, so none ever runs. */
    @Suppress("UNUSED_PARAMETER")
    fun addBeforeInstall(hook: suspend () -> Unit) = Unit

    @Suppress("UNUSED_PARAMETER")
    fun removeBeforeInstall(hook: suspend () -> Unit) = Unit
}

/**
 * The facts of a stuttering video, upstream's StutterReport.Facts (core's StutterReport.kt is
 * Sentry's and stays out of iOS): the same fields, so the player builds one as it does on Android.
 */
object StutterReport {
    data class Facts(
        val codec: String,
        val profile: String,
        val bitDepth: Int,
        val width: Int,
        val height: Int,
        val fps: Float,
        val bitrateKbps: Int,
        val container: String,
        val decoder: String,
        val hardware: Boolean,
        val prediction: String,
        val speedPercent: Int,
        val dropped: Int,
        val frames: Int,
        val underruns: Int,
        val manufacturer: String,
        val model: String,
        val androidVersion: String,
        val cores: Int,
        val abi: String,
        val appVersion: String,
        val sizeBucket: Int,
        val symptom: String = "",
        val fpsSource: String = "unknown",
        val source: String = "unknown",
        val bufferedAheadMs: Int = -1,
        val verdict: String = UNKNOWN_VERDICT,
        val remedies: String = "",
        val hops: Int = 0,
        /** Why this decoder (upstream's Sentry triage): the policy class, the setting, what moved it to software. */
        val policy: String = "",
        val preferSoftware: Boolean = false,
        val softwareBy: String = "none",
        val processBits: Int = 0,
        val hwDecoders: String = "",
        /** How the device's hardware decoder fits the picture (upstream's `rated`, `level`, `rate`, `none`); iOS does not read it, so `unknown`. */
        val hwFit: String = UNKNOWN,
        val playsSame: Int = -1,
        val playsAll: Int = -1,
    )

    const val UNKNOWN_VERDICT = "unknown"

    const val UNKNOWN = "unknown"

    /** Upstream's wording of what put a video on a software decoder; nothing is reported from iOS. */
    fun softwareByOf(software: Boolean, switched: Boolean, ladderHop: Boolean, plan: Boolean, setting: Boolean): String = when {
        !software -> "none"
        switched -> "switch"
        ladderHop -> "ladder_r2"
        plan -> "plan_r2"
        setting -> "setting"
        else -> "selector"
    }

    /** The decoders listed for a picture's type as one short text (upstream's), `none` when there are none. */
    fun hwDecodersOf(decoders: List<Pair<String, Pair<Int, Int>?>>): String =
        decoders.map { PlayCounts.clean(it.first) }.filter { it.isNotEmpty() }.joinToString("+").ifEmpty { "none" }.take(PlayCounts.MAX_TEXT)

    /** The word a stutter's cause is reported as, upstream's: the cause's name in lower case. */
    fun verdictName(cause: com.tmplayer.player.StutterCause): String = cause.name.lowercase()
}

/** A store build has no updater (docs/agents/telegram-terms.md): nothing installs itself on iOS. */
fun updateScheduler(context: Context): UpdateScheduler = UpdateScheduler(SettingsStore(context).updatePrefs)

val UPDATE_WAITS_FOR_WIFI: String get() = com.tmplayer.i18n.L.updateWaitsForWifi

fun Updates.workPausedByInstall(): Boolean = false

fun Updates.canInstall(context: Context): Boolean = false

fun Updates.unknownSourcesIntent(context: Context): Intent = Intent()

suspend fun Updates.downloadAndInstall(context: Context, release: Release) = Unit

/**
 * The network as iOS's path monitor reports it, as Android's NetworkMonitor reads ConnectivityManager:
 * online when the path is satisfied, and metered when iOS calls it expensive (cellular, a personal
 * hotspot) or constrained (Low Data Mode). Unknown until the first answer, which comes at once.
 */
object NetworkMonitor : Connectivity {
    private val _status = MutableStateFlow(NetworkStatus.Unknown)
    override val status: StateFlow<NetworkStatus> = _status.asStateFlow()
    private val _metered = MutableStateFlow(false)
    override val metered: StateFlow<Boolean> = _metered.asStateFlow()

    private val started = java.util.concurrent.atomic.AtomicBoolean(false)

    // The monitor lives as long as the process; holding it keeps it from being released.
    private var monitor: platform.Network.nw_path_monitor_t = null

    fun start(context: Context) {
        if (!started.compareAndSet(false, true)) return
        android.net.ConnectivityManager.meteredNow = { _metered.value }
        val created = platform.Network.nw_path_monitor_create()
        platform.Network.nw_path_monitor_set_update_handler(created) { path ->
            val online = platform.Network.nw_path_get_status(path) == platform.Network.nw_path_status_satisfied
            _status.value = if (online) NetworkStatus.Online else NetworkStatus.Offline
            _metered.value = online && (platform.Network.nw_path_is_expensive(path) || platform.Network.nw_path_is_constrained(path))
        }
        platform.Network.nw_path_monitor_set_queue(created, platform.darwin.dispatch_queue_create("org.tmplayer.network", null))
        platform.Network.nw_path_monitor_start(created)
        monitor = created
    }
}

/** A foreground service start, handed to whatever the iOS shell runs in its place. */
fun Context.startForegroundServiceCompat(intent: Intent) {
    startForegroundService(intent)
}

/**
 * Picture in picture is AVKit's, so only Apple's player's files (MP4 and MOV) can do it, not mpv's:
 * the device says whether it does the feature at all, and the row for a file of mpv's says why not
 * when it is pressed (WP-31, docs/IOS.md).
 */
fun Context.hasPictureInPicture(): Boolean = android.app.IosExternalPlayback.pictureInPictureSupported

/** The AirPlay row is always on iOS; for a file of mpv's it says [com.tmplayer.i18n.L.playerAirplayNotForThisFile] when pressed. */
fun Context.hasAirPlay(): Boolean = true

fun Context.showAirPlayPicker(): Boolean = android.app.IosExternalPlayback.showAirPlayPicker()

/** The engine's AirPlay follows the player's savable verdict: off until the chat is known to allow saving (SEC2-01). */
fun Context.setExternalPlaybackAllowed(on: Boolean) {
    android.app.IosExternalPlayback.externalPlaybackAllowed = on
}

fun Context.pictureInPictureRefusal(): String? =
    if (android.app.IosExternalPlayback.appliesToThisVideo) null else com.tmplayer.i18n.L.playerPipNotForThisFile

fun videoFollowsTransforms(videoView: android.view.View?): Boolean = true

/** VoiceOver has its own timing; the row keeps its own (WP-64 reads VoiceOver). */
fun android.view.accessibility.AccessibilityManager.recommendedControlsTimeout(timeoutMs: Long): Long = timeoutMs
