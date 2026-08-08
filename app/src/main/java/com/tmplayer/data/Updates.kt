package com.tmplayer.data

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.FileProvider
import com.tmplayer.BuildConfig
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

    /** Downloaded and handed to Android's installer, which is now in front of the app. */
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
 * Downloading is one thing and installing is another. This object does the first and hands the
 * file to Android for the second, which is the only way an app may install anything: the system
 * shows its own confirmation, and TMPlayer is never able to install anything silently.
 */
object Updates {

    private const val LATEST_URL = "https://api.github.com/repos/dracu-lah/TMPlayer/releases/latest"
    const val RELEASES_PAGE = "github.com/dracu-lah/TMPlayer/releases"

    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    /** This build's own version, as it is written on the releases page. */
    val installedVersion: String get() = BuildConfig.VERSION_NAME

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

        if (!NetworkMonitor.canTryInternet()) {
            _state.value = if (quiet) {
                UpdateState.Idle
            } else {
                UpdateState.Failed("Connect to the internet to check for updates.")
            }
            return
        }
        checkedThisLaunch = true

        if (!quiet) _state.value = UpdateState.Checking
        val release = withContext(Dispatchers.IO) { runCatching { fetchLatest() }.getOrNull() }

        _state.value = when {
            release != null && isNewer(release.version, installedVersion) ->
                UpdateState.Available(release)

            release != null -> UpdateState.Idle
            quiet -> UpdateState.Idle
            else -> UpdateState.Failed("Could not reach GitHub. Try again in a moment.")
        }
    }

    /**
     * Fetches the APK for this TV and hands it to the system installer.
     *
     * The file lands in the cache directory: once Android has installed it there is no reason to
     * keep a second copy of the app around on a stick with eight gigabytes on it.
     */
    suspend fun downloadAndInstall(context: Context, release: Release) {
        if (!NetworkMonitor.canTryInternet()) {
            _state.value = UpdateState.Failed("Connect to the internet to download the update.")
            return
        }
        _state.value = UpdateState.Downloading(release, null)

        val file = withContext(Dispatchers.IO) {
            runCatching {
                val dir = File(context.cacheDir, "updates").apply { mkdirs() }
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
            return
        }

        _state.value = UpdateState.Ready(release, file)
        runCatching { context.startActivity(installIntent(context, file)) }
            .onFailure {
                _state.value = UpdateState.Failed(
                    "TMPlayer could not open Android's installer. Install it by hand from " +
                        "$RELEASES_PAGE.",
                )
            }
    }

    /** Whether the TV will let TMPlayer hand an APK to the installer at all. */
    fun canInstall(context: Context): Boolean =
        context.packageManager.canRequestPackageInstalls()

    /**
     * The system screen where "allow apps from this source" is turned on.
     *
     * Android will not take that answer from inside this app, so the viewer is sent to the
     * system's own switch and comes back with Back.
     */
    fun unknownSourcesIntent(context: Context): Intent =
        Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
            .setData(android.net.Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

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

    private fun installIntent(context: Context, file: File): Intent {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", file)
        return Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    private fun fetchLatest(): Release? {
        val connection = (URL(LATEST_URL).openConnection() as HttpURLConnection).apply {
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            setRequestProperty("Accept", "application/vnd.github+json")
        }
        val body = connection.use { it.inputStream.bufferedReader().readText() }
        val json = JSONObject(body)
        val version = json.optString("tag_name").removePrefix("v")
        if (version.isBlank()) return null

        val assets = json.optJSONArray("assets") ?: return null
        val asset = selectApkAsset(assets, Build.SUPPORTED_ABIS.orEmpty()) ?: return null
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

    private const val CONNECT_TIMEOUT_MS = 8_000
    private const val READ_TIMEOUT_MS = 20_000
}
