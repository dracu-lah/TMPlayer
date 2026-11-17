package com.tmplayer.online

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * YouTube's embed for a trailer (private/TRAILERS-PLAN.md): only an eleven character id gets
 * into a URL or a page, the URL is the privacy enhanced host with no tracking extras, and the page
 * holds no text but the escaped labels and the key, and speaks the bridge every platform binds.
 */
class TrailerEmbedTest {

    private val labels = TrailerPageLabels("Trailer", "Loading the trailer…")

    @Test
    fun onlyAnElevenCharacterYouTubeIdIsAKey() {
        for (key in listOf("YE7VzlLtp-4", "dQw4w9WgXcQ", "___________", "-----------", "aZ09_-aZ09_")) {
            assertTrue(TrailerEmbed.isValidKey(key), key)
        }
        val bad = listOf(
            null, "", "short", "YE7VzlLtp-", "YE7VzlLtp-44", "YE7VzlLtp 4", "YE7VzlLtp+4", "YE7VzlLtp/4", "YE7VzlLtp.4",
            "YE7Vz\"><x>4", "YE7VzlLtp\n4", "١٢٣٤٥٦٧٨٩٠١", "ＡＢＣＤＥＦＧＨＩＪＫ", "YE7VzlLtpé4", "YE7VzlLtp\u00004", "a".repeat(100_000),
        )
        for (key in bad) assertFalse(TrailerEmbed.isValidKey(key), key.toString().take(20))
    }

    @Test
    fun theEmbedIsTheNoCookieHostWithItsPlayerSwitchesAndTheOrigin() {
        assertEquals(
            "https://www.youtube-nocookie.com/embed/YE7VzlLtp-4?autoplay=1&playsinline=1&rel=0&enablejsapi=1&origin=https%3A%2F%2Fwww.youtube-nocookie.com",
            TrailerEmbed.embedUrl("YE7VzlLtp-4"),
        )
        assertEquals(
            "https://www.youtube-nocookie.com/embed/YE7VzlLtp-4?autoplay=1&playsinline=1&rel=0&enablejsapi=1&origin=http%3A%2F%2Flocalhost%3A5173",
            TrailerEmbed.embedUrl("YE7VzlLtp-4", "http://localhost:5173"),
        )
        assertNull(TrailerEmbed.embedUrl("nope"))
        assertNull(TrailerEmbed.embedUrl("YE7Vz\"><x>4"))
        for (origin in listOf("", "file:///", "javascript:alert(1)", "https://", "https://a.b/path", "https://a b", "https://x:port", "https://x:123456", "https://ex\"ample.com")) {
            assertNull(TrailerEmbed.embedUrl("YE7VzlLtp-4", origin), origin)
        }
    }

    @Test
    fun theEmbedCarriesNoTrackingExtras() {
        val url = assertNotNull(TrailerEmbed.embedUrl("YE7VzlLtp-4"))
        for (extra in listOf("widget_referrer", "modestbranding", "iv_load_policy", "cc_load_policy", "list=", "playlist", "mute", "controls=0")) {
            assertFalse(extra in url, extra)
        }
    }

    @Test
    fun thePageLoadsTheApiAndTheEmbedAndOffersPlayPauseAndToggle() {
        val page = assertNotNull(TrailerEmbed.pageHtml("YE7VzlLtp-4", labels))
        assertTrue(page.startsWith("<!doctype html>"))
        assertTrue("src='https://www.youtube.com/iframe_api'" in page)
        assertTrue("src=\"https://www.youtube-nocookie.com/embed/YE7VzlLtp-4?autoplay=1&amp;playsinline=1&amp;rel=0&amp;enablejsapi=1&amp;origin=https%3A%2F%2Fwww.youtube-nocookie.com\"" in page)
        for (fn in listOf("window.play=", "window.pause=", "window.toggle=", "window.seekBy=", "onYouTubeIframeAPIReady")) assertTrue(fn in page, fn)
        // The one bridge, in the three forms the platforms bind.
        assertTrue("var B='TMPlayerTrailer'" in page)
        assertTrue("window[B].post(m)" in page)
        assertTrue("window.webkit.messageHandlers[B]" in page)
        assertTrue("window.cefQuery" in page)
        for (message in listOf("say('ready')", "say('state:'+e.data)", "say('error:'+e.data)", "say('error:-1')")) assertTrue(message in page, message)
        assertEquals("play()", TrailerEmbed.PLAY)
        assertEquals("pause()", TrailerEmbed.PAUSE)
        assertEquals("toggle()", TrailerEmbed.TOGGLE)
        assertEquals("seekBy(10)", TrailerEmbed.seekBy(10))
        assertEquals("seekBy(-60)", TrailerEmbed.seekBy(-9_999))
    }

    @Test
    fun thePageHasNoTextButTheEscapedLabelsAndTheKey() {
        val page = assertNotNull(TrailerEmbed.pageHtml("YE7VzlLtp-4", TrailerPageLabels("<script>alert('x')</script>", "\"&'<>")))
        assertFalse("<script>alert" in page)
        assertTrue("&lt;script&gt;alert(&#39;x&#39;)&lt;/script&gt;" in page)
        assertTrue("&quot;&amp;&#39;&lt;&gt;" in page)
        assertNull(TrailerEmbed.pageHtml("bad\"key<x>", labels))
        assertNull(TrailerEmbed.pageHtml(null, labels))
        // The page is plain ASCII but for the labels, so nothing else slipped in.
        val bare = assertNotNull(TrailerEmbed.pageHtml("YE7VzlLtp-4", TrailerPageLabels("T", "L")))
        assertTrue(bare.all { it.code in 9..126 }, "non ASCII in the page")
    }

    @Test
    fun thePagesMessagesAreReadAndAnythingElseIsNothing() {
        assertEquals(TrailerMessage.Ready, TrailerEmbed.parseMessage("ready"))
        assertEquals(TrailerMessage.Ready, TrailerEmbed.parseMessage(" ready\n"))
        assertEquals(TrailerMessage.State(1), TrailerEmbed.parseMessage("state:1"))
        assertEquals(TrailerMessage.State(-1), TrailerEmbed.parseMessage("state:-1"))
        assertEquals(TrailerMessage.Error(150), TrailerEmbed.parseMessage("error:150"))
        assertEquals(TrailerMessage.Error(-1), TrailerEmbed.parseMessage("error:-1"))
        for (raw in listOf(null, "", "Ready", "state", "state:", "state:x", "state:9", "state:99999", "error:1000", "error:", "error:1:2", "play:1", "state:１", "state:١", "x".repeat(10_000))) {
            assertNull(TrailerEmbed.parseMessage(raw), raw.toString().take(20))
        }
    }
}
