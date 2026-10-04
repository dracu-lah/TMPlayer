package com.tmplayer.data

import com.tmplayer.platform.Logger
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI

/** A failure the viewer can be told about in words, rather than as "something went wrong". */
internal class UpdateFailure(val text: String) : java.io.IOException(text)

/** One downloadable file of a release. [sha256] is null when the source did not say. */
data class ReleaseAsset(
    val url: String,
    val sha256: String? = null,
    val size: Long = 0,
) {
    val name: String get() = url.substringAfterLast('/')
}

/**
 * The newest TMPlayer release, as every target reads it.
 *
 * [assets] is keyed the way `site/latest.json` keys it ("android-universal", "windows-x64-msi",
 * "linux-x64-appimage" and so on, see [UpdateFeed.keyFor]), whichever source it came from, so
 * picking a package is the same lookup on the phone and on a computer. A key is simply absent when
 * a release does not carry that file, as the per-ABI APKs, the portable zip, the deb, the rpm and
 * the Flatpak are absent from every release after 1.21.0.
 */
data class Release(
    val version: String,
    val pageUrl: String,
    val notes: String = "",
    val assets: Map<String, ReleaseAsset> = emptyMap(),
    /** The release's `SHA256SUMS` file, for an asset whose own hash is not known. */
    val checksumsUrl: String? = null,
)

/**
 * Where a new version is learned of, and how two versions are put in order. One of each for the
 * phone, the TV and the desktop.
 *
 * The first place asked is `latest.json` on the project's site: a static file on a CDN, with no
 * quota. The GitHub API is the fallback, for when the site cannot be reached: it allows an
 * anonymous caller sixty requests an hour per address, shared by everything behind the same router,
 * and a conditional request answered with 304 still counts, so it cannot be the first call of
 * every launch on every device.
 */
object UpdateFeed {

    const val FEED_URL = "https://tmplayer.org/latest.json"
    private const val REPO = "dracu-lah/TMPlayer"
    private const val GITHUB_LATEST = "https://api.github.com/repos/$REPO/releases/latest"
    const val RELEASES_PAGE = "https://github.com/$REPO/releases"

    /** What an HTTP GET answered: the status and, for a 2xx, the body. */
    data class Answer(val code: Int, val body: String)

    /**
     * Asks the site, then GitHub. Throws [UpdateFailure] with a sentence for the viewer when
     * neither answered with a release.
     */
    fun latest(userAgent: String, get: (url: String, headers: Map<String, String>) -> Answer = ::httpGet): Release {
        val headers = mapOf("User-Agent" to userAgent)
        val fromSite = runCatching {
            val answer = get(FEED_URL, headers)
            if (answer.code !in 200..299) throw UpdateFailure("The site answered ${answer.code}")
            parseFeed(answer.body) ?: throw UpdateFailure("The site's feed could not be read")
        }
        fromSite.getOrNull()?.let { return it }
        Logger.w(TAG, "Update feed unavailable, asking GitHub: ${fromSite.exceptionOrNull()?.message}")

        // GitHub asks every caller to name itself and answers 403 to some that do not.
        val answer = runCatching {
            get(GITHUB_LATEST, headers + ("Accept" to "application/vnd.github+json"))
        }.getOrElse { throw UpdateFailure("Could not reach GitHub. Try again in a moment.") }
        if (answer.code !in 200..299) {
            // An anonymous caller gets sixty requests an hour per address, and a household behind
            // one address can spend them, so rate limiting is worth naming rather than reporting
            // as a network that is down.
            throw UpdateFailure(
                if (answer.code == 403 || answer.code == 429) {
                    "GitHub is rate limiting this connection. Try again in an hour."
                } else {
                    "GitHub answered ${answer.code}. Try again in a moment."
                },
            )
        }
        return parseGitHub(answer.body) ?: throw UpdateFailure("GitHub sent a release with no version on it.")
    }

    /** `latest.json`, schema 1. Null when it is not that. */
    fun parseFeed(json: String): Release? = runCatching {
        val root = JSONObject(json)
        if (root.optInt("schema", 0) != 1) return null
        val version = root.optString("version").removePrefix("v")
        if (version.isBlank()) return null
        val assets = buildMap {
            val list = root.optJSONObject("assets") ?: return@buildMap
            for (key in list.keys()) {
                val asset = list.optJSONObject(key) ?: continue
                val url = asset.optString("url").takeIf { it.isNotBlank() } ?: continue
                put(
                    key,
                    ReleaseAsset(
                        url = url,
                        sha256 = asset.optString("sha256").takeIf { it.length == 64 },
                        size = asset.optLong("size"),
                    ),
                )
            }
        }
        Release(
            version = version,
            pageUrl = root.optString("releaseUrl").ifBlank { "$RELEASES_PAGE/tag/v$version" },
            notes = root.optString("notes").trim(),
            assets = assets,
        )
    }.getOrNull()

    /**
     * The API's `releases/latest` answer, reduced to the same shape. Its assets carry a `digest`
     * ("sha256:...") these days, which is used when present; the checksum file covers the rest.
     */
    fun parseGitHub(json: String): Release? = runCatching {
        val root = JSONObject(json)
        val tag = root.optString("tag_name")
        val version = tag.removePrefix("v")
        if (version.isBlank()) return null
        var checksums: String? = null
        val assets = buildMap {
            val list = root.optJSONArray("assets") ?: return@buildMap
            for (i in 0 until list.length()) {
                val asset = list.optJSONObject(i) ?: continue
                val url = asset.optString("browser_download_url").takeIf { it.isNotBlank() } ?: continue
                val name = asset.optString("name").ifBlank { url.substringAfterLast('/') }
                if (name.startsWith("SHA256SUMS") && name.endsWith(".txt")) checksums = url
                val key = keyFor(name) ?: continue
                if (containsKey(key)) continue
                put(
                    key,
                    ReleaseAsset(
                        url = url,
                        sha256 = asset.optString("digest").removePrefix("sha256:").takeIf { it.length == 64 },
                        size = asset.optLong("size"),
                    ),
                )
            }
        }
        Release(
            version = version,
            pageUrl = root.optString("html_url").ifBlank { "$RELEASES_PAGE/tag/$tag" },
            notes = "",
            assets = assets,
            checksumsUrl = checksums,
        )
    }.getOrNull()

    /**
     * The feed key a release file name stands for, by the names CI gives them. The names CI no
     * longer makes are still recognised, so the GitHub fallback reads an older release the same way.
     */
    fun keyFor(fileName: String): String? {
        val name = fileName.lowercase()
        return when {
            name.endsWith(".apk") -> when {
                "universal" in name -> "android-universal"
                else -> ANDROID_ABIS.firstOrNull { name.endsWith("-$it.apk") }?.let { "android-$it" }
            }
            name.endsWith("-windows-x64.msi") -> "windows-x64-msi"
            name.endsWith("-windows-x64-portable.zip") -> "windows-x64-portable"
            name.endsWith(".appimage") -> "linux-x64-appimage"
            name.endsWith("_amd64.deb") -> "linux-x64-deb"
            name.endsWith(".x86_64.rpm") -> "linux-x64-rpm"
            name.endsWith(".flatpak") -> "linux-x64-flatpak"
            name.endsWith("-linux-x64.tar.gz") -> "linux-x64-tarball"
            else -> null
        }
    }

    /** x86_64 before x86, so the longer name is matched first. */
    private val ANDROID_ABIS = listOf("arm64-v8a", "armeabi-v7a", "x86_64", "x86")

    /**
     * The APK for a device with [abis] (Android's `Build.SUPPORTED_ABIS`, most preferred first):
     * the one-file release first, then a per-architecture file for older releases.
     */
    fun androidAsset(release: Release, abis: List<String>): ReleaseAsset? =
        release.assets["android-universal"] ?: abis.firstNotNullOfOrNull { release.assets["android-$it"] }

    /**
     * Whether [release] carries something a computer on [os] ("windows", "linux", "macos") can
     * install: a release made for the phone alone is no news on a computer.
     */
    fun hasDesktopPackage(release: Release, os: String): Boolean =
        release.assets.keys.any { it.startsWith("$os-") }

    fun isNewer(candidate: String, installed: String): Boolean = compare(candidate, installed) > 0

    /**
     * Semantic version order with pre-releases: 2.0.0 is newer than 2.0.0-beta.1, which is newer
     * than 2.0.0-alpha.2. A leading "v" (the tag) and build metadata are ignored, and a missing
     * piece counts as zero, so 1.0 and 1.0.0 are the same. Text order would put 2.0.10 before
     * 2.0.9, which is exactly the release where it would start mattering.
     */
    fun compare(a: String, b: String): Int {
        val (coreA, preA) = split(a)
        val (coreB, preB) = split(b)
        for (i in 0 until maxOf(coreA.size, coreB.size)) {
            val c = coreA.getOrElse(i) { 0 }.compareTo(coreB.getOrElse(i) { 0 })
            if (c != 0) return c
        }
        // A release outranks any of its own pre-releases.
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

    private fun httpGet(url: String, headers: Map<String, String>): Answer {
        val connection = URI(url).toURL().openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.instanceFollowRedirects = true
            headers.forEach(connection::setRequestProperty)
            // The status before the stream, or a 403 arrives as an empty IOException.
            val code = connection.responseCode
            if (code !in 200..299) {
                connection.errorStream?.close()
                return Answer(code, "")
            }
            return Answer(code, connection.inputStream.bufferedReader().readText())
        } finally {
            connection.disconnect()
        }
    }

    private const val TAG = "UpdateFeed"
    internal const val CONNECT_TIMEOUT_MS = 8_000
    internal const val READ_TIMEOUT_MS = 20_000
}
