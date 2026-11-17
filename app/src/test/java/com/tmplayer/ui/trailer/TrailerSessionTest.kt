package com.tmplayer.ui.trailer

import androidx.compose.ui.input.key.Key
import com.tmplayer.data.MobileData
import com.tmplayer.online.TrailerEmbed
import com.tmplayer.online.TrailerFailure
import com.tmplayer.online.TrailerPhase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Android's in-app trailer without a WebView (TRAILERS-PLAN T3): the page's messages and the
 * ready wait through core's TrailerRules, the remote's keys, the mobile data answer, where the
 * WebView may go, what a rotation restores, and the WebView's settings in the source.
 */
class TrailerSessionTest {

    @Test
    fun `ready and playing never fall back`() {
        val session = TrailerSession()
        assertEquals(TrailerPhase.Loading, session.state.phase)
        session.onMessage("ready")
        session.onMessage("state:3")
        session.onMessage("state:1")
        assertEquals(TrailerPhase.Playing, session.state.phase)
        assertTrue(session.state.controllable)
        session.onTimeout()
        assertFalse("a trailer that is ready does not time out", session.state.fallsBack)
        session.onMessage("state:2")
        assertEquals(TrailerPhase.Paused, session.state.phase)
    }

    @Test
    fun `every YouTube error falls back, and stays failed`() {
        for (code in listOf(2, 5, 100, 101, 150, 152, 153, -1, 999)) {
            val session = TrailerSession()
            session.onMessage("ready")
            session.onMessage("error:$code")
            assertTrue("error $code", session.state.fallsBack)
            session.onMessage("state:1")
            assertTrue("a failed trailer stays failed after $code", session.state.fallsBack)
        }
        val embed = TrailerSession().apply { onMessage("error:150") }
        assertEquals(TrailerFailure.NotEmbeddable, embed.state.failure)
    }

    @Test
    fun `no ready within the wait falls back`() {
        val session = TrailerSession()
        session.onMessage("state:-1")
        session.onTimeout()
        assertTrue(session.state.fallsBack)
        assertEquals(TrailerFailure.TimedOut, session.state.failure)
    }

    @Test
    fun `garbage from the page changes nothing`() {
        val session = TrailerSession()
        for (raw in listOf(null, "", "error", "error:", "state:9", "error:abc", "x".repeat(10_000), "ready\u0000", "state:١")) {
            session.onMessage(raw)
        }
        assertEquals(TrailerPhase.Loading, session.state.phase)
        assertFalse(session.state.fallsBack)
    }

    @Test
    fun `no page at all falls back`() {
        val session = TrailerSession()
        session.onBroken()
        assertTrue(session.state.fallsBack)
        assertEquals(TrailerFailure.Broken, session.state.failure)
    }

    @Test
    fun `the remote's keys`() {
        fun down(key: Key, chrome: Boolean = false, repeat: Int = 0) = TrailerKeys.action(key, down = true, repeat = repeat, chromeShown = chrome)
        fun up(key: Key, chrome: Boolean = false) = TrailerKeys.action(key, down = false, repeat = 0, chromeShown = chrome)
        assertEquals(TrailerKeys.Action.Toggle, down(Key.DirectionCenter))
        assertEquals(TrailerKeys.Action.Toggle, down(Key.Enter))
        assertEquals(TrailerKeys.Action.Swallow, down(Key.DirectionCenter, repeat = 3))
        assertEquals(TrailerKeys.Action.Swallow, up(Key.DirectionCenter))
        // While Close is up and focused, OK is Close's.
        assertNull(down(Key.DirectionCenter, chrome = true))
        assertNull(up(Key.DirectionCenter, chrome = true))
        assertEquals(TrailerKeys.Action.SeekBack, down(Key.DirectionLeft))
        assertEquals(TrailerKeys.Action.SeekForward, down(Key.DirectionRight, chrome = true))
        assertEquals(TrailerKeys.Action.ShowChrome, down(Key.DirectionUp))
        assertEquals(TrailerKeys.Action.ShowChrome, down(Key.DirectionDown))
        assertEquals(TrailerKeys.Action.Toggle, down(Key.MediaPlayPause))
        // Back closes as it comes up, so its up never reaches the page under the dialog.
        assertEquals(TrailerKeys.Action.Swallow, down(Key.Back))
        assertEquals(TrailerKeys.Action.Close, up(Key.Back))
        assertNull(down(Key.A))

        assertEquals(TrailerEmbed.TOGGLE, TrailerKeys.script(TrailerKeys.Action.Toggle))
        assertEquals(TrailerEmbed.seekBy(-10), TrailerKeys.script(TrailerKeys.Action.SeekBack))
        assertEquals(TrailerEmbed.seekBy(10), TrailerKeys.script(TrailerKeys.Action.SeekForward))
        assertNull(TrailerKeys.script(TrailerKeys.Action.Close))
    }

    @Test
    fun `a trailer streams by the mobile data answer`() {
        // Wi-Fi, any answer.
        for (answer in MobileData.entries) assertTrue(trailerMayStream(offline = false, metered = false, mobileData = answer, television = false))
        // Offline: never in the app.
        assertFalse(trailerMayStream(offline = true, metered = false, mobileData = MobileData.Allow, television = false))
        // Mobile data: "Ask" is for large videos, a trailer is not one; "Never" sends it outside.
        assertTrue(trailerMayStream(offline = false, metered = true, mobileData = MobileData.Ask, television = false))
        assertTrue(trailerMayStream(offline = false, metered = true, mobileData = MobileData.Allow, television = false))
        assertFalse(trailerMayStream(offline = false, metered = true, mobileData = MobileData.Never, television = false))
        // A television has no "Never" in force (MeteredPolicy.wifiOnlyInForce).
        assertTrue(trailerMayStream(offline = false, metered = true, mobileData = MobileData.Never, television = true))
    }

    @Test
    fun `the WebView stays on YouTube's frame and never navigates the page`() {
        assertTrue(TrailerNavigation.allows("https://www.youtube-nocookie.com/embed/YE7VzlLtp-4?autoplay=1", mainFrame = false))
        assertTrue(TrailerNavigation.allows("https://www.youtube.com/embed/YE7VzlLtp-4", mainFrame = false))
        assertTrue(TrailerNavigation.allows("https://m.youtube.com/watch?v=YE7VzlLtp-4", mainFrame = false))
        assertFalse("the page itself never navigates", TrailerNavigation.allows("https://www.youtube.com/watch?v=YE7VzlLtp-4", mainFrame = true))
        for (url in listOf(
            "http://www.youtube.com/embed/x", "https://evil.example/", "https://youtube.com.evil.example/", "https://notyoutube.com/",
            "javascript:alert(1)", "file:///sdcard/x", "intent://x#Intent;end", "data:text/html,x", "", "https://", null,
            "https://www.youtube.com/" + "a".repeat(10_000),
        )) {
            assertFalse("$url", TrailerNavigation.allows(url, mainFrame = false))
        }
        assertTrue(TrailerNavigation.isYouTube("https://youtu.be/YE7VzlLtp-4"))
        assertTrue(TrailerNavigation.isYouTube("HTTPS://WWW.YOUTUBE.COM/watch?v=x"))
    }

    @Test
    fun `a rotation restores the trailer of this run only`() {
        assertEquals("YE7VzlLtp-4", restoredKey("123:YE7VzlLtp-4", "123"))
        assertNull("another run of the app never starts a trailer by itself", restoredKey("99:YE7VzlLtp-4", "123"))
        for (saved in listOf(null, "", "123", "123:", "123:short", "123:<script>a</s", ":YE7VzlLtp-4", "123:" + "a".repeat(100))) {
            assertNull("$saved", restoredKey(saved, "123"))
        }
    }

    private val root = listOf(File(".."), File(".")).first { File(it, "settings.gradle.kts").isFile }

    @Test
    fun `the WebView's settings lock it down`() {
        val source = File(root, "app/src/main/java/com/tmplayer/ui/trailer/TrailerWebPage.kt").readText()
        for (line in listOf(
            "allowFileAccess = false",
            "allowContentAccess = false",
            "allowFileAccessFromFileURLs = false",
            "allowUniversalAccessFromFileURLs = false",
            "mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW",
            "setSupportMultipleWindows(false)",
            "javaScriptCanOpenWindowsAutomatically = false",
            "mediaPlaybackRequiresUserGesture = false",
            "override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest)",
            "TrailerNavigation.allows(url, mainFrame)",
            "override fun onShowCustomView(",
            "override fun onHideCustomView()",
            "web.destroy()",
            "web.onPause()",
            "removeJavascriptInterface(TrailerEmbed.BRIDGE)",
            "descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS",
            "isFocusable = false",
        )) {
            assertTrue("TrailerWebPage.kt has `$line`", line in source)
        }
        // The bridge is added once, on the WebView that loads our own page with the embed's origin as its base.
        assertEquals(1, Regex("addJavascriptInterface\\(").findAll(source).count())
        val bridge = source.indexOf("addJavascriptInterface(")
        val load = source.indexOf("loadDataWithBaseURL(TrailerEmbed.BASE_ORIGIN")
        assertTrue("our page is loaded with the base origin, after the bridge is in place", load > bridge && bridge > 0)
        assertFalse("no page but ours is loaded into it", Regex("\\.loadUrl\\((?!\"about:blank\")").containsMatchIn(source))
        assertEquals("https://www.youtube-nocookie.com", TrailerEmbed.BASE_ORIGIN)
        // The library's own WebView player is gone: one place on Android holds a WebView.
        val ui = File(root, "ui/src/androidMain/kotlin/com/tmplayer/ui/online/TrailerPlayer.kt").readText()
        assertFalse("android.webkit" in ui)
    }
}
