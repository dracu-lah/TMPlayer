package com.tmplayer.data

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * The feed, its GitHub fallback, the version order and the asset each target installs from.
 *
 * Between them they decide whether the side bar nags somebody who is already current, and whether
 * the one release that matters gets missed, so they are worth pinning down away from the network.
 */
class UpdateFeedTest {

    private val feed = """
        {
          "schema": 1,
          "version": "1.20.0",
          "versionCode": 12000,
          "published": "2026-10-11T08:00:00Z",
          "releaseUrl": "https://github.com/dracu-lah/TMPlayer/releases/tag/v1.20.0",
          "notes": "  Downloads leave the cache. ",
          "assets": {
            "android-universal": { "url": "https://x/TMPlayer-1.20.0-universal.apk", "sha256": "${"a".repeat(64)}", "size": 52000000 },
            "android-armeabi-v7a": { "url": "https://x/TMPlayer-1.20.0-armeabi-v7a.apk", "sha256": "${"b".repeat(64)}", "size": 24000000 },
            "windows-x64-msi": { "url": "https://x/TMPlayer-1.20.0-windows-x64.msi", "sha256": "short", "size": 1 },
            "linux-x64-deb": { "url": "https://x/tmplayer_1.20.0_amd64.deb", "sha256": "${"c".repeat(64)}", "size": 2 }
          }
        }
    """.trimIndent()

    @After
    fun reset() {
        Updates.fetch = { UpdateFeed.latest("test") }
        Updates.configure(installedVersion = "0")
        Updates.dismiss()
        Updates.skip()
    }

    // ---- the feed -------------------------------------------------------------------------

    @Test
    fun `reads the site's feed`() {
        val release = UpdateFeed.parseFeed(feed)!!
        assertEquals("1.20.0", release.version)
        assertEquals("https://github.com/dracu-lah/TMPlayer/releases/tag/v1.20.0", release.pageUrl)
        assertEquals("Downloads leave the cache.", release.notes)
        assertEquals(52000000L, release.assets["android-universal"]?.size)
        assertEquals("a".repeat(64), release.assets["android-universal"]?.sha256)
        // A hash that is not one is treated as no hash, so the checksum file is used instead.
        assertNull(release.assets["windows-x64-msi"]?.sha256)
        assertEquals("tmplayer_1.20.0_amd64.deb", release.assets["linux-x64-deb"]?.name)
    }

    @Test
    fun `a feed of another schema or with no version is not read`() {
        assertNull(UpdateFeed.parseFeed(feed.replace("\"schema\": 1", "\"schema\": 2")))
        assertNull(UpdateFeed.parseFeed("""{"schema":1,"assets":{}}"""))
        assertNull(UpdateFeed.parseFeed("<html>"))
    }

    @Test
    fun `reads the GitHub answer into the same shape`() {
        val json = """
            {"url":"x","html_url":"https://github.com/dracu-lah/TMPlayer/releases/tag/v2.0.1",
             "tag_name": "v2.0.1","author":{"html_url":"https://github.com/someone"},
             "assets":[
               {"name":"TMPlayer-2.0.1-windows-x64.msi","size":5,"digest":"sha256:${"d".repeat(64)}",
                "browser_download_url":"https://github.com/d/r/releases/download/v2.0.1/TMPlayer-2.0.1-windows-x64.msi"},
               {"browser_download_url":"https://github.com/d/r/releases/download/v2.0.1/tmplayer_2.0.1_amd64.deb"},
               {"name":"SHA256SUMS-2.0.1.txt","browser_download_url":"https://github.com/d/r/releases/download/v2.0.1/SHA256SUMS-2.0.1.txt"},
               {"name":"mapping-2.0.1.txt","browser_download_url":"https://github.com/d/r/releases/download/v2.0.1/mapping-2.0.1.txt"}
             ]}
        """.trimIndent()
        val release = UpdateFeed.parseGitHub(json)!!
        assertEquals("2.0.1", release.version)
        assertEquals("https://github.com/dracu-lah/TMPlayer/releases/tag/v2.0.1", release.pageUrl)
        assertEquals(setOf("windows-x64-msi", "linux-x64-deb"), release.assets.keys)
        assertEquals("d".repeat(64), release.assets["windows-x64-msi"]?.sha256)
        assertNull(release.assets["linux-x64-deb"]?.sha256)
        assertEquals("https://github.com/d/r/releases/download/v2.0.1/SHA256SUMS-2.0.1.txt", release.checksumsUrl)
        assertNull(UpdateFeed.parseGitHub("{}"))
    }

    @Test
    fun `names every package CI makes`() {
        val v = "1.19.1"
        mapOf(
            "TMPlayer-$v-universal.apk" to "android-universal",
            "TMPlayer-$v-arm64-v8a.apk" to "android-arm64-v8a",
            "TMPlayer-$v-armeabi-v7a.apk" to "android-armeabi-v7a",
            "TMPlayer-$v-x86_64.apk" to "android-x86_64",
            "TMPlayer-$v-windows-x64.msi" to "windows-x64-msi",
            "TMPlayer-$v-windows-x64-portable.zip" to "windows-x64-portable",
            "TMPlayer-$v-x86_64.AppImage" to "linux-x64-appimage",
            "tmplayer_${v}_amd64.deb" to "linux-x64-deb",
            "tmplayer-$v.x86_64.rpm" to "linux-x64-rpm",
            "TMPlayer-$v.flatpak" to "linux-x64-flatpak",
            "TMPlayer-$v-linux-x64.tar.gz" to "linux-x64-tarball",
            "TMPlayer-$v-linux-x64.tar.xz" to "linux-x64-tarball",
            "TMPlayer-$v-source.tar.gz" to null,
            "mapping-$v.txt" to null,
        ).forEach { (name, key) -> assertEquals(name, key, UpdateFeed.keyFor(name)) }
    }

    @Test
    fun `the site is asked first and GitHub only when it fails`() {
        val asked = mutableListOf<String>()
        val fromSite = UpdateFeed.latest("t") { url, _ ->
            asked += url
            UpdateFeed.Answer(200, feed)
        }
        assertEquals("1.20.0", fromSite.version)
        assertEquals(listOf(UpdateFeed.FEED_URL), asked)

        asked.clear()
        val fromGitHub = UpdateFeed.latest("t") { url, headers ->
            asked += url
            assertEquals("t", headers["User-Agent"])
            if (url == UpdateFeed.FEED_URL) UpdateFeed.Answer(404, "") else UpdateFeed.Answer(200, """{"tag_name":"v2.0.0"}""")
        }
        assertEquals("2.0.0", fromGitHub.version)
        assertEquals(2, asked.size)
    }

    @Test
    fun `rate limiting is named when both fail`() {
        try {
            UpdateFeed.latest("t") { url, _ ->
                if (url == UpdateFeed.FEED_URL) throw java.io.IOException("offline") else UpdateFeed.Answer(403, "")
            }
            fail("expected a failure")
        } catch (e: java.io.IOException) {
            assertEquals("GitHub is rate limiting this connection. Try again in an hour.", e.message)
        }
    }

    // ---- assets -----------------------------------------------------------------------------

    private fun release(vararg keys: String) =
        Release("2.0.1", "page", assets = keys.associateWith { ReleaseAsset("https://x/$it") })

    @Test
    fun `the universal apk wins over a per architecture one`() {
        val r = release("android-armeabi-v7a", "android-universal")
        assertEquals("https://x/android-universal", UpdateFeed.androidAsset(r, listOf("armeabi-v7a"))?.url)
    }

    @Test
    fun `an older split release still picks the tv architecture`() {
        val r = release("android-arm64-v8a", "android-armeabi-v7a")
        assertEquals("https://x/android-armeabi-v7a", UpdateFeed.androidAsset(r, listOf("armeabi-v7a", "arm64-v8a"))?.url)
    }

    @Test
    fun `an incompatible split release is ignored`() {
        assertNull(UpdateFeed.androidAsset(release("android-x86_64"), listOf("armeabi-v7a")))
    }

    @Test
    fun `only a release with a package for this OS counts on a computer`() {
        val r = release("android-universal", "windows-x64-msi", "linux-x64-deb")
        assertTrue(UpdateFeed.hasDesktopPackage(r, "windows"))
        assertTrue(UpdateFeed.hasDesktopPackage(r, "linux"))
        assertFalse(UpdateFeed.hasDesktopPackage(r, "macos"))
        assertFalse(UpdateFeed.hasDesktopPackage(release("android-universal"), "windows"))
        assertTrue(UpdateFeed.hasDesktopPackage(release("linux-x64-tarball"), "linux"))
    }

    @Test
    fun `the minimal release still reaches every device`() {
        // What CI publishes since 1.21.0: one APK, the MSI, the AppImage and the tarball.
        val r = release("android-universal", "windows-x64-msi", "linux-x64-appimage", "linux-x64-tarball")
        for (abis in listOf(listOf("armeabi-v7a"), listOf("arm64-v8a", "armeabi-v7a"), listOf("x86_64"))) {
            assertEquals("https://x/android-universal", UpdateFeed.androidAsset(r, abis)?.url)
        }
        assertTrue(UpdateFeed.hasDesktopPackage(r, "windows"))
        assertTrue(UpdateFeed.hasDesktopPackage(r, "linux"))
    }

    // ---- order ------------------------------------------------------------------------------

    @Test
    fun `versions compare as numbers with pre-releases below their release`() {
        assertTrue(UpdateFeed.isNewer("0.5.1", "0.5.0"))
        assertFalse(UpdateFeed.isNewer("0.5.0", "0.5.0"))
        assertFalse(UpdateFeed.isNewer("0.4.9", "0.5.0"))
        // Text order would put 0.10.0 behind 0.9.0, which is where this starts to matter.
        assertTrue(UpdateFeed.isNewer("0.10.0", "0.9.0"))
        assertFalse(UpdateFeed.isNewer("0.9.0", "0.10.0"))
        assertTrue(UpdateFeed.isNewer("2.0.10", "2.0.9"))
        assertTrue(UpdateFeed.isNewer("v2.1.0", "2.0.0"))
        assertTrue(UpdateFeed.isNewer("2.0.0", "2.0.0-alpha.1"))
        assertTrue(UpdateFeed.isNewer("2.0.0-beta.1", "2.0.0-alpha.2"))
        assertTrue(UpdateFeed.isNewer("2.0.0-alpha.10", "2.0.0-alpha.9"))
        assertTrue(UpdateFeed.isNewer("2.0.0-alpha.1", "2.0.0-alpha"))
        assertFalse(UpdateFeed.isNewer("2.0.0-alpha.1", "2.0.0"))
        assertFalse(UpdateFeed.isNewer("1.17.0", "2.0.0-alpha.1"))
        assertTrue(UpdateFeed.isNewer("0.6.0-rc1", "0.5.0"))
        assertFalse(UpdateFeed.isNewer("0.5.0-beta", "0.5.0"))
    }

    @Test
    fun `a shorter version is padded rather than misread`() {
        assertTrue(UpdateFeed.isNewer("1.0", "0.9.9"))
        assertFalse(UpdateFeed.isNewer("1.0", "1.0.0"))
        assertTrue(UpdateFeed.isNewer("1.0.1", "1.0"))
    }

    // ---- the state the side bar reads ---------------------------------------------------------

    @Test
    fun `a newer release with something for this device is available`() = runBlocking {
        Updates.configure(installedVersion = "1.19.1", abis = listOf("armeabi-v7a"), offers = { Updates.apkFor(it) != null })
        Updates.fetch = { UpdateFeed.parseFeed(feed)!! }
        assertTrue(Updates.check(quiet = true))
        assertEquals("1.20.0", (Updates.state.value as UpdateState.Available).release.version)

        // A release with nothing this device installs is no news.
        Updates.skip()
        Updates.configure(installedVersion = "1.19.1", offers = { false })
        Updates.check(quiet = true)
        assertEquals(UpdateState.Idle, Updates.state.value)
    }

    @Test
    fun `a skipped version is passed over quietly and re-offered when asked`() = runBlocking {
        Updates.configure(installedVersion = "1.19.1")
        Updates.fetch = { UpdateFeed.parseFeed(feed)!! }
        Updates.check(quiet = true, skipped = "1.20.0")
        assertEquals(UpdateState.Idle, Updates.state.value)
        Updates.check(quiet = false, skipped = "1.20.0")
        assertEquals(true, (Updates.state.value as UpdateState.Available).skipped)
    }

    @Test
    fun `a quiet failure keeps what was already known`() = runBlocking {
        Updates.configure(installedVersion = "1.19.1")
        Updates.fetch = { UpdateFeed.parseFeed(feed)!! }
        Updates.check(quiet = true)
        Updates.fetch = { throw java.io.IOException("offline") }
        assertFalse(Updates.check(quiet = true))
        assertTrue(Updates.state.value is UpdateState.Available)
        assertFalse(Updates.check(quiet = false))
        assertEquals("Could not reach GitHub. Try again in a moment.", (Updates.state.value as UpdateState.Failed).message)
    }
}
