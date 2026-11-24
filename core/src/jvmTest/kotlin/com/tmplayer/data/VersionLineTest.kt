package com.tmplayer.data

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The support line and the update words beside the version (WP-104). */
class VersionLineTest {

    private val release = Release(version = "1.29.0", pageUrl = "https://example.org/r")

    @Test
    fun `the copy text names the app, the version, the build and the device`() {
        assertEquals(
            "TMPlayer 1.28.6 (12806099) Android 14, Google Pixel 8, phone",
            VersionLine.copyText("1.28.6", "12806099", "Android 14, Google Pixel 8, phone"),
        )
    }

    @Test
    fun `each part is cut and no line break survives`() {
        val text = VersionLine.copyText("v".repeat(1_000), "b\n".repeat(100), "d\r\n".repeat(500))
        assertFalse(text.contains('\n'))
        assertFalse(text.contains('\r'))
        val parts = Regex("^TMPlayer (.*) \\((.*)\\) (.*)$").matchEntire(text)!!.groupValues
        assertTrue(parts[1].length <= VersionLine.VERSION_MAX)
        assertTrue(parts[2].length <= VersionLine.BUILD_MAX)
        assertTrue(parts[3].length <= VersionLine.DEVICE_MAX)
    }

    @Test
    fun `a fake account never reaches the copy text`() {
        // The function takes three strings and no account, so a name can only appear if a caller
        // passes it; the real callers pass BuildConfig and the system's own words.
        val name = "Asha Raman"
        val username = "asha"
        val text = VersionLine.copyText("1.28.6-beta.3", "12806003", "Android 14, Google Pixel 8, phone")
        assertFalse(text.contains(name))
        assertFalse(text.contains(username))
    }

    @Test
    fun `pre release and preview versions are shown as built`() {
        assertEquals("TMPlayer 1.28.6-beta.3 (12806003) x", VersionLine.copyText("1.28.6-beta.3", "12806003", "x"))
        assertEquals("TMPlayer 1.28.6-test (12806003) x", VersionLine.copyText("1.28.6-test", "12806003", "x"))
        assertEquals("1.0.0-dev", VersionLine.shown("1.0.0-dev"))
    }

    @Test
    fun `the state for every update state`() {
        val last = 1_000L
        assertEquals(VersionState.UpToDate, VersionLine.state(UpdateState.Idle, true, last))
        assertEquals(VersionState.Checking, VersionLine.state(UpdateState.Checking, true, 0))
        assertEquals(VersionState.Available("1.29.0"), VersionLine.state(UpdateState.Available(release), true, 0))
        assertEquals(VersionState.Available("1.29.0"), VersionLine.state(UpdateState.Downloading(release, 0.5f), true, 0))
        assertEquals(VersionState.Available("1.29.0"), VersionLine.state(UpdateState.Ready(release, File("x")), true, 0))
        assertEquals(VersionState.Available("1.29.0"), VersionLine.state(UpdateState.Installing(release), true, 0))
        assertEquals(VersionState.Failed, VersionLine.state(UpdateState.Failed("no"), true, last))
    }

    @Test
    fun `a build with no update check says so and never claims up to date`() {
        assertEquals(VersionState.Off, VersionLine.state(UpdateState.Idle, false, 5_000L))
        assertEquals(VersionState.Off, VersionLine.state(UpdateState.Available(release), false, 5_000L))
    }

    @Test
    fun `never checked shows no state`() {
        assertEquals(VersionState.None, VersionLine.state(UpdateState.Idle, true, 0L))
    }

    @Test
    fun `a huge feed version is cut in the state`() {
        val state = VersionLine.state(UpdateState.Available(release.copy(version = "9".repeat(1_024))), true, 0)
        assertEquals(VersionLine.VERSION_MAX, (state as VersionState.Available).version.length)
    }

    @Test
    fun `the compact version is v and the number, whatever follows the dash`() {
        assertEquals("v1.28.7", VersionLine.compact("1.28.7"))
        assertEquals("v1.28.7", VersionLine.compact("1.28.7-test"))
        assertEquals("v1.28.7", VersionLine.compact("1.28.7-beta.3"))
        assertEquals("v1.28.7", VersionLine.compact(" 1.28.7+12\n"))
    }

    @Test
    fun `a version that is no number is shown as built and a huge one is cut`() {
        assertEquals("dev", VersionLine.compact("dev"))
        assertEquals("", VersionLine.compact(""))
        assertTrue(VersionLine.compact("9".repeat(1_000)).length <= VersionLine.VERSION_MAX + 1)
    }
}
