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
            "windows-x64-msi-v2": { "url": "https://x/TMPlayer-1.20.0-windows-x64.msi", "sha256": "short", "size": 1 },
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
        assertNull(release.assets["windows-x64-msi-v2"]?.sha256)
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
        assertEquals(setOf("windows-x64-msi-v2", "linux-x64-deb"), release.assets.keys)
        assertEquals("d".repeat(64), release.assets["windows-x64-msi-v2"]?.sha256)
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
            "TMPlayer-$v-windows-x64.msi" to "windows-x64-msi-v2",
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
            assertEquals("GitHub has had too many requests from this connection. Try again in an hour.", e.message)
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
        val r = release("android-universal", "windows-x64-msi-v2", "linux-x64-appimage", "linux-x64-tarball")
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

    @Test
    fun `test versions order by number and below their release`() {
        val order = listOf("1.28.1", "1.28.2-beta.1", "1.28.2-beta.2", "1.28.2-beta.9", "1.28.2-beta.10", "1.28.2", "1.28.3-beta.1")
        for ((older, newer) in order.zipWithNext()) {
            assertTrue("$newer after $older", UpdateFeed.compare(newer, older) > 0)
            assertTrue("$older before $newer", UpdateFeed.compare(older, newer) < 0)
        }
        assertEquals(0, UpdateFeed.compare("v1.28.2-beta.3", "1.28.2-beta.3"))
    }

    // ---- test versions (GitHub pre-releases) -------------------------------------------------

    private fun ghRelease(tag: String, prerelease: Boolean, draft: Boolean = false, state: String = "uploaded") = """
        {"tag_name":"$tag","prerelease":$prerelease,"draft":$draft,
         "html_url":"https://github.com/dracu-lah/TMPlayer/releases/tag/$tag",
         "body":"- One change\n\n| Platform | File |",
         "assets":[{"name":"TMPlayer-${tag.removePrefix("v")}-universal.apk","size":9,"state":"$state",
           "digest":"sha256:${"e".repeat(64)}",
           "browser_download_url":"https://github.com/dracu-lah/TMPlayer/releases/download/$tag/TMPlayer-${tag.removePrefix("v")}-universal.apk"}]}
    """.trimIndent()

    private val list = "[" + listOf(
        ghRelease("v1.28.2-beta.10", prerelease = true),
        ghRelease("v1.28.2-beta.11", prerelease = true, draft = true),
        ghRelease("v1.28.2-beta.9", prerelease = true),
        ghRelease("v1.28.1", prerelease = false),
    ).joinToString(",") + "]"

    @Test
    fun `the release list gives its newest published test version`() {
        val newest = UpdateFeed.parseGitHubList(list)!!
        // beta.10 over beta.9, though text order says otherwise; the draft is passed over.
        assertEquals("1.28.2-beta.10", newest.version)
        assertEquals(listOf("One change"), newest.changes)
        assertEquals("e".repeat(64), newest.assets["android-universal"]?.sha256)
        assertNull(UpdateFeed.parseGitHubList("[]"))
        assertNull(UpdateFeed.parseGitHubList("{}"))
    }

    @Test
    fun `a release whose files are still uploading is not offered`() {
        val uploading = "[" + ghRelease("v1.28.2-beta.12", prerelease = true, state = "starter") + "," +
            ghRelease("v1.28.2-beta.10", prerelease = true) + "]"
        assertEquals("1.28.2-beta.10", UpdateFeed.parseGitHubList(uploading)!!.version)
    }

    @Test
    fun `the newer of the release and the test version is chosen, a tie going to the release`() {
        val stable = Release("1.28.1", "s")
        val beta = Release("1.28.2-beta.1", "b")
        assertEquals(beta, UpdateFeed.choose(stable, beta))
        assertEquals(stable, UpdateFeed.choose(stable, null))
        assertEquals(beta, UpdateFeed.choose(null, beta))
        assertNull(UpdateFeed.choose(null, null))
        val final = Release("1.28.2", "f")
        assertEquals(final, UpdateFeed.choose(final, beta))
        assertEquals(final, UpdateFeed.choose(final, Release("1.28.2", "github")))
    }

    @Test
    fun `without test versions only the stable sources are asked`() {
        val asked = mutableListOf<String>()
        val release = UpdateFeed.newest("t", testVersions = false) { url, _ ->
            asked += url
            UpdateFeed.Answer(200, feed)
        }
        assertEquals("1.20.0", release.version)
        assertEquals(listOf(UpdateFeed.FEED_URL), asked)
    }

    @Test
    fun `with test versions the release list is asked too and the newer wins`() {
        val asked = mutableListOf<String>()
        val release = UpdateFeed.newest("t", testVersions = true) { url, headers ->
            asked += url
            assertEquals("t", headers["User-Agent"])
            if (url == UpdateFeed.FEED_URL) UpdateFeed.Answer(200, feed) else UpdateFeed.Answer(200, list)
        }
        assertEquals("1.28.2-beta.10", release.version)
        assertEquals(listOf(UpdateFeed.FEED_URL, UpdateFeed.GITHUB_RELEASES), asked)

        // Once the release is out, the feed's own copy of it is what is offered.
        val final = feed.replace("1.20.0", "1.28.2")
        assertEquals(
            "1.28.2",
            UpdateFeed.newest("t", testVersions = true) { url, _ ->
                if (url == UpdateFeed.FEED_URL) UpdateFeed.Answer(200, final) else UpdateFeed.Answer(200, list)
            }.version,
        )
    }

    @Test
    fun `a release list that cannot be read costs only the test versions`() {
        val release = UpdateFeed.newest("t", testVersions = true) { url, _ ->
            if (url == UpdateFeed.FEED_URL) UpdateFeed.Answer(200, feed) else UpdateFeed.Answer(403, "")
        }
        assertEquals("1.20.0", release.version)

        // The site down too: the release list still answers.
        val fromList = UpdateFeed.newest("t", testVersions = true) { url, _ ->
            when (url) {
                UpdateFeed.FEED_URL -> throw java.io.IOException("offline")
                UpdateFeed.GITHUB_RELEASES -> UpdateFeed.Answer(200, list)
                else -> UpdateFeed.Answer(500, "")
            }
        }
        assertEquals("1.28.2-beta.10", fromList.version)
    }

    @Test
    fun `the updater asks for test versions only when the setting is on`() = runBlocking {
        val wanted = mutableListOf<Boolean>()
        var on = false
        Updates.configure(installedVersion = "1.28.1", testVersions = { on })
        Updates.fetch = { tests ->
            wanted += tests
            if (tests) UpdateFeed.parseGitHubList(list)!! else Release("1.28.1", "s")
        }
        Updates.check(quiet = true)
        assertEquals(UpdateState.Idle, Updates.state.value)

        on = true
        Updates.testVersionsSwitched(on = true, skipped = "")
        assertEquals("1.28.2-beta.10", (Updates.state.value as UpdateState.Available).release.version)

        // Off takes the test version back at once, and the stable answer has nothing newer.
        on = false
        Updates.testVersionsSwitched(on = false, skipped = "")
        assertEquals(UpdateState.Idle, Updates.state.value)
        assertEquals(listOf(false, true, false), wanted)
    }

    @Test
    fun `a test version installed is updated by its release whatever the setting`() = runBlocking {
        Updates.configure(installedVersion = "1.28.2-beta.10", testVersions = { false })
        Updates.fetch = { Release("1.28.2", "f") }
        Updates.check(quiet = true)
        assertEquals("1.28.2", (Updates.state.value as UpdateState.Available).release.version)

        // And an older release is no news to it.
        Updates.skip()
        Updates.fetch = { Release("1.28.1", "s") }
        Updates.check(quiet = true)
        assertEquals(UpdateState.Idle, Updates.state.value)
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

    @Test
    fun `a build with updates switched off never asks and never downloads`() = runBlocking {
        Updates.configure(installedVersion = "1.19.1", abis = listOf("armeabi-v7a"))
        var asked = 0
        Updates.fetch = { asked++; UpdateFeed.parseFeed(feed)!! }
        Updates.enabled = false
        try {
            assertFalse(Updates.check(quiet = true))
            assertFalse(Updates.check(quiet = false))
            assertEquals(0, asked)
            assertEquals(UpdateState.Idle, Updates.state.value)
            val release = UpdateFeed.parseFeed(feed)!!
            val dir = kotlin.io.path.createTempDirectory("updates").toFile()
            assertNull(Updates.download(release, dir))
            assertEquals(UpdateState.Idle, Updates.state.value)
            assertTrue(dir.listFiles().isNullOrEmpty())
            dir.deleteRecursively()
        } finally {
            Updates.enabled = true
        }

        // Switched back on, the same build checks as before.
        assertTrue(Updates.check(quiet = true))
        assertEquals(1, asked)
        assertTrue(Updates.state.value is UpdateState.Available)
    }

    @Test
    fun `the feed's changes are the popup's points, one each`() {
        val release = UpdateFeed.parseFeed(
            """{ "schema": 1, "version": "1.28.0", "notes": "One. Two.", "changes": ["Casting: MKV plays", " ", "Fixes"] }""",
        )!!
        assertEquals(listOf("Casting: MKV plays", "Fixes"), release.points)
    }

    @Test
    fun `an older feed's paragraph is cut into sentences`() {
        val release = UpdateFeed.parseFeed(
            """{ "schema": 1, "version": "1.27.0", "notes": "Android TV: a new top bar. Proxies (t.me/proxy links) work. A video cut into .part001 pieces plays as one. 21 languages." }""",
        )!!
        assertEquals(
            listOf("Android TV: a new top bar", "Proxies (t.me/proxy links) work", "A video cut into .part001 pieces plays as one", "21 languages"),
            release.points,
        )
    }

    @Test
    fun `the GitHub page gives its opening list and not the file table under it`() {
        val body = "- First change\n- Second: with detail\n\n\n| Platform | File |\n| --- | --- |\n- not this\n"
        assertEquals(listOf("First change", "Second: with detail"), UpdateFeed.leadingList(body))
    }
}
