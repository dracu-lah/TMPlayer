package com.tmplayer.desktop

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class DesktopUpdatesTest {

    private val prefs = DesktopPrefs(Files.createTempDirectory("tm-upd").resolve("desktop.properties").toFile())
    private var clock = 10 * DesktopUpdates.DAY_MS
    private var answer: LatestRelease? = null
    private var fetches = 0

    private fun updates(installed: String = "2.0.0", os: String = "windows") = DesktopUpdates(
        prefs, installed, os,
        fetch = {
            fetches++
            answer
        },
        now = { clock },
    )

    private fun release(version: String, vararg assets: String) =
        LatestRelease(version, "https://github.com/dracu-lah/TMPlayer/releases/tag/v$version", assets.toList())

    @Test
    fun `versions order like semver`() {
        assertTrue(DesktopUpdates.isNewer("2.0.1", "2.0.0"))
        assertTrue(DesktopUpdates.isNewer("2.0.10", "2.0.9"))
        assertTrue(DesktopUpdates.isNewer("v2.1.0", "2.0.0"))
        assertTrue(DesktopUpdates.isNewer("2.0.0", "2.0.0-alpha.1"))
        assertTrue(DesktopUpdates.isNewer("2.0.0-beta.1", "2.0.0-alpha.2"))
        assertTrue(DesktopUpdates.isNewer("2.0.0-alpha.10", "2.0.0-alpha.9"))
        assertTrue(DesktopUpdates.isNewer("2.0.0-alpha.1", "2.0.0-alpha"))
        assertFalse(DesktopUpdates.isNewer("2.0.0", "2.0.0"))
        assertFalse(DesktopUpdates.isNewer("2.0.0-alpha.1", "2.0.0"))
        assertFalse(DesktopUpdates.isNewer("1.17.0", "2.0.0-alpha.1"))
    }

    @Test
    fun `only a release with a package for this OS counts`() {
        val assets = listOf("TMPlayer-2.0.1.apk", "TMPlayer-2.0.1.msi", "tmplayer_2.0.1_amd64.deb")
        assertTrue(DesktopUpdates.hasPackageFor("windows", assets))
        assertTrue(DesktopUpdates.hasPackageFor("linux", assets))
        assertFalse(DesktopUpdates.hasPackageFor("macos", assets))
        assertFalse(DesktopUpdates.hasPackageFor("windows", listOf("TMPlayer-1.18.0.apk")))
        assertTrue(DesktopUpdates.hasPackageFor("linux", listOf("TMPlayer-2.0.1-linux-x64.tar.gz")))
    }

    @Test
    fun `parses the api answer`() {
        val json = """
            {"url":"x","html_url":"https://github.com/dracu-lah/TMPlayer/releases/tag/v2.0.1",
             "tag_name": "v2.0.1","author":{"html_url":"https://github.com/someone"},
             "assets":[{"name":"a.msi","browser_download_url":"https://github.com/d/r/releases/download/v2.0.1/TMPlayer-2.0.1.msi"},
                       {"browser_download_url":"https://github.com/d/r/releases/download/v2.0.1/TMPlayer.apk"}]}
        """.trimIndent()
        val parsed = DesktopUpdates.parse(json)!!
        assertEquals("2.0.1", parsed.version)
        assertEquals("https://github.com/dracu-lah/TMPlayer/releases/tag/v2.0.1", parsed.pageUrl)
        assertEquals(listOf("TMPlayer-2.0.1.msi", "TMPlayer.apk"), parsed.assetNames)
        assertNull(DesktopUpdates.parse("{}"))
    }

    @Test
    fun `checks at most once a day and never with the setting off`() = runBlocking {
        answer = release("2.0.1", "TMPlayer.msi")
        val u = updates()
        u.checkIfDue()
        assertEquals("2.0.1", u.available.value?.version)
        u.checkIfDue()
        assertEquals(1, fetches)
        clock += DesktopUpdates.DAY_MS
        u.checkIfDue()
        assertEquals(2, fetches)
        prefs.update { it.copy(checkForUpdates = false) }
        clock += 2 * DesktopUpdates.DAY_MS
        u.checkIfDue()
        assertEquals(2, fetches)
    }

    @Test
    fun `a failed check is retried on the next launch`() = runBlocking {
        answer = null
        updates().checkIfDue()
        updates().checkIfDue()
        assertEquals(2, fetches)
    }

    @Test
    fun `nothing for the same or an older version`() = runBlocking {
        answer = release("2.0.0", "TMPlayer.msi")
        val u = updates()
        u.checkIfDue()
        assertNull(u.available.value)
    }

    @Test
    fun `a dismissed release is not offered again on launch, but is when asked`() = runBlocking {
        answer = release("2.0.1", "TMPlayer.msi")
        val u = updates()
        u.checkIfDue()
        u.dismiss()
        assertNull(u.available.value)
        clock += DesktopUpdates.DAY_MS
        val fresh = updates()
        fresh.checkIfDue()
        assertNull(fresh.available.value)
        assertEquals("TMPlayer 2.0.1 is out", fresh.check(quiet = false))
        assertEquals("2.0.1", fresh.available.value?.version)
        // A newer one than the dismissed release is news again.
        answer = release("2.0.2", "TMPlayer.msi")
        clock += DesktopUpdates.DAY_MS
        val later = updates()
        later.checkIfDue()
        assertEquals("2.0.2", later.available.value?.version)
    }
}
