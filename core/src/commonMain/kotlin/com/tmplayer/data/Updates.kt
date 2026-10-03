package com.tmplayer.data

import com.tmplayer.platform.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** A failure the viewer can be told about in words, rather than as "something went wrong". */
private class UpdateFailure(val text: String) : java.io.IOException(text)

/** A release on GitHub, reduced to the three things this app does anything with. */
data class Release(
    val version: String,
    val apkUrl: String,
    val sizeBytes: Long,
)

/** Where the update machinery is, for both the rail badge and the Settings rows. */
sealed interface UpdateState {
    /** Nothing has been asked yet, or the last answer was that this is the newest build. */
    data object Idle : UpdateState
    data object Checking : UpdateState
    data class Available(val release: Release) : UpdateState

    /** Downloading, 0f to 1f. Null while the server has not said how big the file is. */
    data class Downloading(val release: Release, val fraction: Float?) : UpdateState

    /** Downloaded and handed to the platform's installer, which is now in front of the app. */
    data class Ready(val release: Release, val file: File) : UpdateState
    data class Failed(val message: String) : UpdateState
}

/**
 * Checks GitHub for a newer TMPlayer and installs it.
 *
 * The app is sideloaded rather than on a store, so nothing else will ever tell the viewer that a
 * new version exists. The releases page is the only source: one HTTP call to the public API, no
 * account, no analytics, and only when this app asks.
 *
 * Downloading is one thing and installing is another. This object does the first; installing is
 * the platform's, and on Android it means handing the file to the system, which shows its own
 * confirmation, so TMPlayer is never able to install anything silently.
 *
 * Each app calls [configure] once at startup, before anything reads [installedVersion].
 */
object Updates {

    private const val LATEST_URL = "https://api.github.com/repos/dracu-lah/TMPlayer/releases/latest"
    const val RELEASES_PAGE = "github.com/dracu-lah/TMPlayer/releases"

    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    /** This build's own version, as it is written on the releases page. */
    @Volatile
    var installedVersion: String = "0"
        private set

    /** The device's architectures, most preferred first, for picking a per-ABI asset. */
    @Volatile
    private var abis: List<String> = emptyList()

    @Volatile
    private var connectivity: Connectivity? = null

    /**
     * What this build is and runs on. [abis] is Android's `Build.SUPPORTED_ABIS`; a platform
     * without per-architecture assets passes an empty list.
     */
    fun configure(installedVersion: String, abis: List<String>, connectivity: Connectivity) {
        this.installedVersion = installedVersion
        this.abis = abis
        this.connectivity = connectivity
    }

    private fun canTryInternet(): Boolean = connectivity?.canTryInternet() ?: true

    /** Set once a check has run, so the quiet launch check does not repeat all evening. */
    @Volatile
    private var checkedThisLaunch = false

    /**
     * Asks GitHub what the newest release is.
     *
     * [quiet] is the check that runs on launch: it says nothing on failure and does not report
     * being up to date, because neither is news the viewer asked for. The Settings button passes
     * false, where silence would look like a button that does nothing.
     */
    suspend fun check(quiet: Boolean = false) {
        if (quiet && checkedThisLaunch) return
        if (_state.value is UpdateState.Downloading) return

        if (!canTryInternet()) {
            _state.value = if (quiet) {
                UpdateState.Idle
            } else {
                UpdateState.Failed("Connect to the internet to check for updates.")
            }
            return
        }
        checkedThisLaunch = true

        if (!quiet) _state.value = UpdateState.Checking
        val attempt = withContext(Dispatchers.IO) { runCatching { fetchLatest() } }
        val release = attempt.getOrNull()
        // A rate-limited API, a release with no APK on it and a stick with no route out all reach
        // the viewer as much the same sentence, so the cause is logged as well as reported.
        attempt.exceptionOrNull()?.let { Logger.w(TAG, "Update check failed", it) }

        _state.value = when {
            release != null && isNewer(release.version, installedVersion) ->
                UpdateState.Available(release)

            release != null -> UpdateState.Idle
            quiet -> UpdateState.Idle
            else -> UpdateState.Failed(
                attempt.exceptionOrNull().let { if (it is UpdateFailure) it.text else null }
                    ?: "Could not reach GitHub. Try again in a moment.",
            )
        }
    }

    /**
     * Fetches the release's file into [dir] and, once it is whole, moves the state to
     * [UpdateState.Ready] and returns it for the platform to install. Null when it did not arrive,
     * with the state already saying why.
     *
     * Android passes its cache directory: once the system has installed the APK there is no reason
     * to keep a second copy of the app around on a stick with eight gigabytes on it.
     */
    suspend fun download(release: Release, dir: File): File? {
        if (!canTryInternet()) {
            _state.value = UpdateState.Failed("Connect to the internet to download the update.")
            return null
        }
        _state.value = UpdateState.Downloading(release, null)

        val file = withContext(Dispatchers.IO) {
            runCatching {
                dir.mkdirs()
                // One file, overwritten: a half-finished download from last time is worthless.
                dir.listFiles()?.forEach { it.delete() }
                val target = File(dir, "TMPlayer-${release.version}.apk")

                val connection = (URL(release.apkUrl).openConnection() as HttpURLConnection).apply {
                    connectTimeout = CONNECT_TIMEOUT_MS
                    readTimeout = READ_TIMEOUT_MS
                    instanceFollowRedirects = true
                }
                connection.inputStream.use { input ->
                    val total = connection.contentLengthLong.takeIf { it > 0 } ?: release.sizeBytes
                    target.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        var done = 0L
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            output.write(buffer, 0, read)
                            done += read
                            if (total > 0) {
                                _state.value = UpdateState.Downloading(release, done.toFloat() / total)
                            }
                        }
                    }
                }
                connection.disconnect()
                target
            }.getOrNull()
        }

        if (file == null || file.length() == 0L) {
            _state.value = UpdateState.Failed("The download did not finish. Try again.")
            return null
        }

        _state.value = UpdateState.Ready(release, file)
        return file
    }

    /** For the platform's installer to report that it could not take the file. */
    fun installFailed(message: String) {
        _state.value = UpdateState.Failed(message)
    }

    /**
     * Clears a finished outcome once the viewer has read it.
     *
     * An available release deliberately survives being dismissed: the rail goes on saying a newer
     * version is out until it has actually been installed.
     */
    fun dismiss() {
        if (_state.value is UpdateState.Failed || _state.value is UpdateState.Ready) {
            _state.value = UpdateState.Idle
        }
    }

    private fun fetchLatest(): Release? {
        val connection = (URL(LATEST_URL).openConnection() as HttpURLConnection).apply {
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            setRequestProperty("Accept", "application/vnd.github+json")
            // GitHub asks every caller to name itself and answers 403 to some that do not. The
            // default here is whatever the platform puts on the wire, which is not a name.
            setRequestProperty("User-Agent", "TMPlayer/$installedVersion")
        }
        val body = connection.use {
            // Check the status before reading the stream, or a 403 arrives as an empty
            // IOException. An anonymous caller gets sixty requests an hour per address, and a
            // household behind one address can spend them, so rate limiting is worth naming
            // rather than reporting as a network that is down.
            val code = it.responseCode
            if (code !in 200..299) {
                it.errorStream?.close()
                throw UpdateFailure(
                    if (code == 403 || code == 429) {
                        "GitHub is rate limiting this connection. Try again in an hour."
                    } else {
                        "GitHub answered $code. Try again in a moment."
                    },
                )
            }
            it.inputStream.bufferedReader().readText()
        }
        val json = JSONObject(body)
        val version = json.optString("tag_name").removePrefix("v")
        if (version.isBlank()) throw UpdateFailure("GitHub sent a release with no version on it.")

        val assets = json.optJSONArray("assets")
        val asset = assets?.let { selectApkAsset(it, abis.toTypedArray()) }
            ?: throw UpdateFailure("The newest release has no APK this device can install.")
        return Release(
            version = version,
            apkUrl = asset.optString("browser_download_url"),
            sizeBytes = asset.optLong("size"),
        )
    }

    /** Prefers the one-file release, with older architecture-specific releases as a fallback. */
    internal fun selectApkAsset(assets: JSONArray, supportedAbis: Array<out String>): JSONObject? {
        fun apkAt(index: Int): JSONObject? {
            val asset = assets.optJSONObject(index) ?: return null
            return asset.takeIf { it.optString("name").lowercase().endsWith(".apk") }
        }

        for (i in 0 until assets.length()) {
            val asset = apkAt(i) ?: continue
            if (asset.optString("name").contains("universal", ignoreCase = true)) return asset
        }
        for (abi in supportedAbis) {
            for (i in 0 until assets.length()) {
                val asset = apkAt(i) ?: continue
                if (asset.optString("name").contains(abi, ignoreCase = true)) return asset
            }
        }
        return null
    }

    /**
     * Compares two dotted versions a piece at a time.
     *
     * Text order would have "0.10.0" losing to "0.9.0", which is exactly the release where it
     * would start mattering.
     */
    internal fun isNewer(candidate: String, installed: String): Boolean {
        val left = candidate.split('.').map { it.takeWhile(Char::isDigit).toIntOrNull() ?: 0 }
        val right = installed.split('.').map { it.takeWhile(Char::isDigit).toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(left.size, right.size)) {
            val a = left.getOrElse(i) { 0 }
            val b = right.getOrElse(i) { 0 }
            if (a != b) return a > b
        }
        return false
    }

    private inline fun <T> HttpURLConnection.use(block: (HttpURLConnection) -> T): T =
        try {
            block(this)
        } finally {
            disconnect()
        }

    private const val TAG = "Updates"

    private const val CONNECT_TIMEOUT_MS = 8_000
    private const val READ_TIMEOUT_MS = 20_000
}
