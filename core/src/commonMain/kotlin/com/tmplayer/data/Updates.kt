package com.tmplayer.data

import com.tmplayer.platform.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.security.MessageDigest

/** Where the update machinery is, for the side bar item, the popup and the Settings rows. */
sealed interface UpdateState {
    /** Nothing has been asked yet, or the last answer was that this is the newest build. */
    data object Idle : UpdateState
    data object Checking : UpdateState

    /**
     * A newer release is out. [skipped] is a version the viewer said to skip, re-offered because
     * they asked Settings to check: the side bar leaves it out, the popup and Settings say so.
     */
    data class Available(val release: Release, val skipped: Boolean = false) : UpdateState

    /** Downloading, 0f to 1f. Null while the server has not said how big the file is. */
    data class Downloading(val release: Release, val fraction: Float?) : UpdateState

    /** Downloaded and handed to the platform's installer, which is now in front of the app. */
    data class Ready(val release: Release, val file: File) : UpdateState

    /** What went wrong, in words, and the release it went wrong for when there was one. */
    data class Failed(val message: String, val release: Release? = null) : UpdateState
}

/** The release a state is about, whatever stage it is at. */
val UpdateState.release: Release?
    get() = when (this) {
        is UpdateState.Available -> release
        is UpdateState.Downloading -> release
        is UpdateState.Ready -> release
        is UpdateState.Failed -> release
        else -> null
    }

/**
 * Checks for a newer TMPlayer and, on Android, fetches it.
 *
 * The app is sideloaded rather than on a store, so nothing else will ever tell the viewer that a
 * new version exists. [UpdateFeed] is the only source: one small file, no account, no analytics,
 * and only when this app asks. When it asks is [UpdateScheduler]'s business.
 *
 * Downloading is one thing and installing is another. This object does the first for the APK;
 * installing is the platform's, and on Android it means handing the file to the system, which
 * shows its own confirmation, so TMPlayer is never able to install anything silently. The desktop
 * reads the same state and installs through its own `SelfUpdate`.
 *
 * Each app calls [configure] once at startup, before anything reads [installedVersion].
 */
object Updates {

    /** The releases page without the scheme, as the Android dialogs print it. */
    const val RELEASES_PAGE = "github.com/dracu-lah/TMPlayer/releases"

    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    /** This build's own version, as it is written on the releases page. */
    @Volatile
    var installedVersion: String = "0"
        private set

    /** The device's architectures, most preferred first, for picking a per-ABI APK. */
    @Volatile
    private var abis: List<String> = emptyList()

    @Volatile
    private var connectivity: Connectivity? = null

    @Volatile
    private var offers: (Release) -> Boolean = { true }

    @Volatile
    private var userAgent: String = "TMPlayer"

    /** How the release is fetched; a test swaps it for an answer of its own. */
    @Volatile
    internal var fetch: () -> Release = { UpdateFeed.latest(userAgent) }

    /**
     * What this build is and runs on.
     *
     * [abis] is Android's `Build.SUPPORTED_ABIS`; a platform without APKs passes an empty list.
     * [offers] says whether a release carries something this platform can install, so a release
     * made for one platform alone is no news on another. [connectivity] is null where the
     * platform cannot tell.
     */
    fun configure(
        installedVersion: String,
        abis: List<String> = emptyList(),
        connectivity: Connectivity? = null,
        userAgent: String = "TMPlayer/$installedVersion",
        offers: (Release) -> Boolean = { true },
    ) {
        this.installedVersion = installedVersion
        this.abis = abis
        this.connectivity = connectivity
        this.userAgent = userAgent
        this.offers = offers
    }

    private fun canTryInternet(): Boolean = connectivity?.canTryInternet() ?: true

    /** Whether the connection in use is one paid for by the byte, as far as the platform knows. */
    fun onMeteredNetwork(): Boolean = connectivity?.metered?.value ?: false

    /** The APK this device installs from [release], or null when the release has none for it. */
    fun apkFor(release: Release): ReleaseAsset? = UpdateFeed.androidAsset(release, abis)

    /**
     * Asks the feed what the newest release is. Returns whether an answer came back, which is
     * what decides whether the check counts towards the throttle.
     *
     * [quiet] is the scheduled check: it says nothing on failure, does not report being up to
     * date, and passes over [skipped], because none of that is news the viewer asked for. The
     * Settings button passes false, where silence would look like a button that does nothing and
     * a skipped release is offered again with a note.
     */
    suspend fun check(quiet: Boolean = false, skipped: String = ""): Boolean {
        val before = _state.value
        // A download or a finished one is further along than anything a check could say.
        if (before is UpdateState.Downloading || before is UpdateState.Ready) return false

        if (!canTryInternet()) {
            if (!quiet) _state.value = UpdateState.Failed("Connect to the internet to check for updates.")
            return false
        }

        if (!quiet) _state.value = UpdateState.Checking
        val attempt = withContext(Dispatchers.IO) { runCatching { fetch() } }
        val release = attempt.getOrNull()
        // A rate-limited API, a release with nothing for this device and a stick with no route out
        // all reach the viewer as much the same sentence, so the cause is logged as well.
        attempt.exceptionOrNull()?.let { Logger.w(TAG, "Update check failed", it) }

        // Another check or a download may have moved on while this one waited on the network.
        val current = _state.value
        if (current is UpdateState.Downloading || current is UpdateState.Ready) return release != null

        _state.value = when {
            release == null && quiet -> before.takeIf { it is UpdateState.Available } ?: UpdateState.Idle
            release == null -> UpdateState.Failed(
                (attempt.exceptionOrNull() as? UpdateFailure)?.text
                    ?: UpdateWords.UNREACHABLE,
                // What was already known survives a failed check, for [dismiss] to put back.
                before.release,
            )
            !UpdateFeed.isNewer(release.version, installedVersion) || !offers(release) -> UpdateState.Idle
            release.version == skipped && quiet -> UpdateState.Idle
            else -> UpdateState.Available(release, skipped = release.version == skipped)
        }
        return release != null
    }

    /**
     * Fetches the APK into [dir], checks it against the hash the feed gave, and once it is whole
     * moves the state to [UpdateState.Ready] and returns it for the platform to install. Null when
     * it did not arrive, with the state already saying why.
     *
     * Android passes its cache directory: once the system has installed the APK there is no reason
     * to keep a second copy of the app around on a stick with eight gigabytes on it.
     */
    suspend fun download(release: Release, dir: File): File? {
        val asset = apkFor(release)
        if (asset == null) {
            _state.value = UpdateState.Failed("The newest release has no APK this device can install.", release)
            return null
        }
        if (!canTryInternet()) {
            _state.value = UpdateState.Failed(UpdateWords.UNREACHABLE, release)
            return null
        }
        _state.value = UpdateState.Downloading(release, null)

        val attempt = withContext(Dispatchers.IO) {
            runCatching {
                dir.mkdirs()
                // One file, overwritten: a half-finished download from last time is worthless.
                dir.listFiles()?.forEach { it.delete() }
                val target = File(dir, "TMPlayer-${release.version}.apk")

                val connection = (URI(asset.url).toURL().openConnection() as HttpURLConnection).apply {
                    connectTimeout = UpdateFeed.CONNECT_TIMEOUT_MS
                    readTimeout = UpdateFeed.READ_TIMEOUT_MS
                    instanceFollowRedirects = true
                    setRequestProperty("User-Agent", userAgent)
                }
                val digest = MessageDigest.getInstance("SHA-256")
                try {
                    connection.inputStream.use { input ->
                        val total = connection.contentLengthLong.takeIf { it > 0 } ?: asset.size
                        target.outputStream().use { output ->
                            val buffer = ByteArray(64 * 1024)
                            var done = 0L
                            while (true) {
                                val read = input.read(buffer)
                                if (read < 0) break
                                output.write(buffer, 0, read)
                                digest.update(buffer, 0, read)
                                done += read
                                if (total > 0) {
                                    _state.value = UpdateState.Downloading(release, done.toFloat() / total)
                                }
                            }
                        }
                    }
                } finally {
                    connection.disconnect()
                }
                val actual = digest.digest().joinToString("") { "%02x".format(it) }
                if (asset.sha256 != null && !actual.equals(asset.sha256, ignoreCase = true)) {
                    target.delete()
                    throw UpdateFailure(UpdateWords.DAMAGED)
                }
                target
            }
        }
        attempt.exceptionOrNull()?.let { Logger.w(TAG, "Update download failed", it) }
        val file = attempt.getOrNull()
        if (file == null || file.length() == 0L) {
            _state.value = UpdateState.Failed(
                (attempt.exceptionOrNull() as? UpdateFailure)?.text
                    ?: UpdateWords.UNREACHABLE,
                release,
            )
            return null
        }

        _state.value = UpdateState.Ready(release, file)
        return file
    }

    /** For the platform's installer to report that it could not take the file. */
    fun installFailed(message: String) {
        _state.value = UpdateState.Failed(message, _state.value.release)
    }

    /** For a platform that refuses the download before it starts, Wi-Fi only being the one. */
    fun refuse(message: String) {
        _state.value = UpdateState.Failed(message, _state.value.release)
    }

    /**
     * Clears a finished outcome once the viewer has read it.
     *
     * A release deliberately survives being dismissed, failed or handed to an installer that was
     * then cancelled: the side bar goes on saying a newer version is out until it has actually
     * been installed or skipped.
     */
    fun dismiss() {
        when (val current = _state.value) {
            is UpdateState.Failed -> _state.value = current.release?.let { UpdateState.Available(it) } ?: UpdateState.Idle
            is UpdateState.Ready -> _state.value = UpdateState.Available(current.release)
            else -> Unit
        }
    }

    /** "Skip this version": the item and the popup go, for this version only. */
    fun skip() {
        if (_state.value !is UpdateState.Downloading) _state.value = UpdateState.Idle
    }

    private const val TAG = "Updates"
}
