package com.tmplayer.desktop

import com.tmplayer.desktop.os.OsInfo
import com.tmplayer.platform.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URI
import java.util.Locale

/** The newest release on GitHub, as much of it as the notice needs. */
data class LatestRelease(val version: String, val pageUrl: String, val assetNames: List<String>)

/**
 * Tells the viewer, once, that a newer TMPlayer is out (B1.4: the in app button opens the
 * download page instead of installing). Never downloads or installs anything.
 *
 * Why not `:core`'s `Updates`: that one picks an APK asset and fails a release without one, and
 * its state machine is the phone's download and install flow. The desktop needs the tag, the page
 * and whether there is a package for this OS, so it asks the same endpoint for those itself and
 * leaves the phone's selector alone.
 *
 * At most one check a day, remembered in [DesktopPrefs], and none at all with the setting off.
 */
class DesktopUpdates(
    private val prefs: DesktopPrefs,
    private val installed: String = BuildInfo.VERSION,
    private val os: String = OsInfo.osTag,
    private val fetch: suspend () -> LatestRelease? = ::fetchLatest,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val _available = MutableStateFlow<LatestRelease?>(null)

    /** A release worth telling the viewer about, until they act on the notice. */
    val available: StateFlow<LatestRelease?> = _available.asStateFlow()

    /** The launch check: quiet, at most daily, and only with the setting on. */
    suspend fun checkIfDue() {
        if (!isDue(prefs.now, now())) return
        check(quiet = true)
    }

    /**
     * Asks GitHub now. Returns what the viewer should be told when they asked (the Settings
     * button); the quiet launch check ignores it.
     */
    suspend fun check(quiet: Boolean = false): String {
        val release = runCatching { fetch() }
            .onFailure { Logger.w(TAG, "update check failed: ${it.message}") }
            .getOrNull()
        // A failed check is tried again next launch rather than tomorrow.
        if (release == null) return "Could not reach GitHub. Try again in a moment."
        prefs.update { it.copy(lastUpdateCheck = now()) }
        val newer = isNewer(release.version, installed) && hasPackageFor(os, release.assetNames)
        if (!newer) {
            _available.value = null
            return "TMPlayer $installed is the newest version"
        }
        // A release the viewer closed the notice for stays closed on the quiet check.
        if (!quiet || prefs.now.dismissedRelease != release.version) _available.value = release
        return "TMPlayer ${release.version} is out"
    }

    /** "Not now": the notice goes, and this release is not offered again on launch. */
    fun dismiss() {
        val release = _available.value ?: return
        prefs.update { it.copy(dismissedRelease = release.version) }
        _available.value = null
    }

    companion object {
        private const val TAG = "DesktopUpdates"
        const val DAY_MS = 24 * 60 * 60_000L
        private const val REPO = "dracu-lah/TMPlayer"
        private const val LATEST_URL = "https://api.github.com/repos/$REPO/releases/latest"
        const val RELEASES_PAGE = "https://github.com/$REPO/releases"

        fun isDue(settings: DesktopSettings, now: Long): Boolean =
            settings.checkForUpdates && (now - settings.lastUpdateCheck >= DAY_MS || now < settings.lastUpdateCheck)

        /**
         * Semantic version order with pre-releases: 2.0.0 is newer than 2.0.0-beta.1, which is
         * newer than 2.0.0-alpha.2. A leading "v" (the tag) is ignored. Text order would put
         * 2.0.10 before 2.0.9.
         */
        fun isNewer(candidate: String, installed: String): Boolean = compare(candidate, installed) > 0

        internal fun compare(a: String, b: String): Int {
            val (coreA, preA) = split(a)
            val (coreB, preB) = split(b)
            for (i in 0 until maxOf(coreA.size, coreB.size)) {
                val c = coreA.getOrElse(i) { 0 }.compareTo(coreB.getOrElse(i) { 0 })
                if (c != 0) return c
            }
            if (preA.isEmpty() || preB.isEmpty()) return preB.size.coerceAtMost(1) - preA.size.coerceAtMost(1)
            for (i in 0 until maxOf(preA.size, preB.size)) {
                val x = preA.getOrNull(i) ?: return -1
                val y = preB.getOrNull(i) ?: return 1
                val nx = x.toIntOrNull()
                val ny = y.toIntOrNull()
                val c = when {
                    nx != null && ny != null -> nx.compareTo(ny)
                    nx != null -> -1
                    ny != null -> 1
                    else -> x.compareTo(y)
                }
                if (c != 0) return c
            }
            return 0
        }

        private fun split(version: String): Pair<List<Int>, List<String>> {
            val clean = version.trim().removePrefix("v").removePrefix("V").substringBefore('+')
            val core = clean.substringBefore('-')
            val pre = clean.substringAfter('-', "")
            return core.split('.').map { it.takeWhile(Char::isDigit).toIntOrNull() ?: 0 } to
                (if (pre.isEmpty()) emptyList() else pre.split('.'))
        }

        /**
         * Whether the release carries something this OS can install: a release made for the phone
         * alone is no news on a computer.
         */
        fun hasPackageFor(os: String, assetNames: List<String>): Boolean {
            val names = assetNames.map { it.lowercase(Locale.ROOT) }
            val endings = when (os) {
                "windows" -> listOf(".msi", ".exe", "windows.zip")
                "macos" -> listOf(".dmg", ".pkg")
                else -> listOf(".deb", ".rpm", ".appimage", ".flatpak", "linux-x64.tar.gz", "linux.tar.gz")
            }
            return names.any { name -> endings.any(name::endsWith) }
        }

        /** The tag and the asset names, read off the API's answer without a JSON library. */
        internal fun parse(json: String): LatestRelease? {
            val tag = Regex("\"tag_name\"\\s*:\\s*\"([^\"]+)\"").find(json)?.groupValues?.get(1) ?: return null
            val assets = Regex("\"browser_download_url\"\\s*:\\s*\"([^\"]+)\"").findAll(json)
                .map { it.groupValues[1].substringAfterLast('/') }
                .toList()
            return LatestRelease(tag.removePrefix("v"), "$RELEASES_PAGE/tag/$tag", assets)
        }

        private suspend fun fetchLatest(): LatestRelease? = withContext(Dispatchers.IO) {
            val connection = URI(LATEST_URL).toURL().openConnection() as HttpURLConnection
            try {
                connection.connectTimeout = 8_000
                connection.readTimeout = 20_000
                connection.setRequestProperty("Accept", "application/vnd.github+json")
                connection.setRequestProperty("User-Agent", "TMPlayer-desktop/${BuildInfo.VERSION}")
                if (connection.responseCode !in 200..299) {
                    Logger.w(TAG, "GitHub answered ${connection.responseCode}")
                    return@withContext null
                }
                parse(connection.inputStream.bufferedReader().readText())
            } finally {
                connection.disconnect()
            }
        }
    }
}
