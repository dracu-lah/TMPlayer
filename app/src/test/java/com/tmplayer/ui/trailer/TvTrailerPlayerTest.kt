package com.tmplayer.ui.trailer

import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import com.tmplayer.data.DetailAction
import com.tmplayer.data.FormFactor
import com.tmplayer.data.MediaItem
import com.tmplayer.i18n.L
import com.tmplayer.online.HttpResponse
import com.tmplayer.online.HttpTransport
import com.tmplayer.online.MetadataCache
import com.tmplayer.online.MetadataStore
import com.tmplayer.online.OnlineMetadata
import com.tmplayer.online.TrailerEmbed
import com.tmplayer.ui.DrainMainLooperRule
import com.tmplayer.ui.browse.DetailRow
import com.tmplayer.ui.browse.LocalSeriesWatch
import com.tmplayer.ui.browse.MediaDetailScreen
import com.tmplayer.ui.browse.SeriesWatch
import com.tmplayer.ui.icons.TmIcons
import com.tmplayer.ui.online.LocalTrailerOpener
import com.tmplayer.ui.theme.Motion
import com.tmplayer.ui.theme.TMPlayerTheme
import com.tmplayer.ui.theme.Tone
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.util.ReflectionHelpers
import java.net.URLDecoder

/**
 * The television's in-app trailer, driven by the remote (TRAILERS-PLAN T3): Right from Play
 * reaches the trailer button, OK opens Android's player through the seam, its Close has the focus
 * while the trailer loads, the WebView never has it; once the trailer plays OK plays or pauses and
 * Left and Right move ten seconds through the page's bridge, Up brings Close back, and Back closes,
 * destroys the page and leaves the focus on the trailer button.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w960dp-h540dp-land-television-night-xhdpi")
class TvTrailerPlayerTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    /** A state write left on the looper must not silence Compose for the next test (HX92). */
    @get:Rule
    val drain = DrainMainLooperRule()

    @get:Rule
    val dir = TemporaryFolder()

    private val before = OnlineMetadata.current

    @After
    fun tearDown() {
        OnlineMetadata.current = before
        FormFactor.override(false)
        Motion.reduced = false
    }

    private val bunny = MediaItem(
        chatId = 2, messageId = 500, fileId = 0, title = "Big.Buck.Bunny.2008.1080p.BluRay.x264.mkv", sizeBytes = 885L * 1024 * 1024,
        durationSec = 596, mimeType = "video/x-matroska", thumbnailFileId = 0, miniThumbnail = null, date = 1_790_000_000,
        fileName = "Big.Buck.Bunny.2008.1080p.BluRay.x264.mkv", width = 1920, height = 1080,
    )

    /** A TMDB that knows one film and its trailer, and nothing else; no picture is served. */
    private val canned = HttpTransport { request ->
        val url = URLDecoder.decode(request.url, "UTF-8")
        val body = when {
            "search/movie" in url && "Big Buck Bunny" in url ->
                """{"results":[{"id":10378,"title":"Big Buck Bunny","original_title":"Big Buck Bunny","release_date":"2008-04-10","overview":"A rabbit's comeback.","poster_path":null,"backdrop_path":null}]}"""
            "/movie/10378?" in url ->
                """{"id":10378,"genres":[{"name":"Animation"}],"runtime":10,"vote_average":6.5,"vote_count":200,"credits":{"cast":[],"crew":[]},
                "videos":{"results":[{"site":"YouTube","key":"YE7VzlLtp-4","type":"Trailer","official":true,"iso_639_1":"en","published_at":"2008-05-20"}]},
                "release_dates":{"results":[]},"recommendations":{"results":[]},"similar":{"results":[]}}"""
            else -> return@HttpTransport HttpResponse(404, body = "{}".toByteArray())
        }
        HttpResponse(200, body = body.toByteArray())
    }

    private fun trailerButton() = hasClickAction() and hasText(L.metadataTrailer)

    @Test
    fun `the remote opens, drives and closes the trailer, and the focus comes back`() {
        FormFactor.override(true)
        Motion.reduced = true
        OnlineMetadata.current = OnlineMetadata(
            buildKey = "test-key",
            store = MetadataStore(dir.newFile()),
            cache = MetadataCache(dir.newFolder()),
            appVersion = "test",
            http = canned,
        )
        val pages = FakeTrailerPages()
        val opener = AndroidTrailerOpener(network = { true }, hasWebView = { true })
        val rows = listOf(DetailRow(DetailAction.Play, "Play", TmIcons.Play, onSelect = {}))
        rule.setContent {
            TMPlayerTheme {
                CompositionLocalProvider(LocalTrailerOpener provides opener, LocalSeriesWatch provides SeriesWatch.None) {
                    Box(Modifier.fillMaxSize().background(Tone.background)) {
                        MediaDetailScreen(bunny, "Film Club", null, false, rows, onClose = {})
                    }
                    TrailerPlayerHost(opener, pages, readyTimeoutMs = 10 * 60_000L)
                }
            }
        }
        remoteWindow(rule.activity.window.decorView.parent)
        rule.waitUntil(20_000) { rule.onAllNodes(trailerButton()).fetchSemanticsNodes().isNotEmpty() }
        rule.waitForIdle()

        rule.onNodeWithText("Play").requestFocus()
        press(rule.onNode(isRoot()), Key.DirectionRight)
        rule.onNode(trailerButton()).assertIsFocused()
        assertEquals("nothing loads before the press", null, pages.last)
        // The clock held, so the dialog's window can be put out of touch mode, as the whole
        // screen is on a television, before Close asks for the focus.
        rule.mainClock.autoAdvance = false
        rule.onNode(isRoot()).performKeyInput { pressKey(Key.DirectionCenter) }
        var frames = 0
        while (windowRoots().size < 2 && frames++ < 20) {
            rule.mainClock.advanceTimeByFrame()
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        }
        val main = rule.activity.window.decorView.parent
        windowRoots().filter { it !== main }.forEach(::remoteWindow)
        rule.mainClock.autoAdvance = true
        rule.waitForIdle()

        // The page, for this key, with the embed's origin, and no focus for the WebView.
        val page = pages.last ?: error("OK did not open the player")
        assertTrue(page.remote)
        assertTrue("YE7VzlLtp-4" in page.html && TrailerEmbed.HOST in page.html)
        rule.onNodeWithText(L.trailerClose).assertIsFocused()
        rule.onNodeWithText(L.trailerRemoteHint).assertExists()

        // The trailer plays: Close goes, and the keys drive the page.
        rule.runOnUiThread {
            page.events.onMessage("ready")
            page.events.onMessage("state:1")
        }
        rule.mainClock.advanceTimeBy(2_000)
        rule.waitForIdle()
        assertEquals(0, rule.onAllNodes(hasText(L.trailerClose)).fetchSemanticsNodes().size)
        val dialog = rule.onNode(isDialog())
        press(dialog, Key.DirectionCenter)
        press(dialog, Key.DirectionRight)
        press(dialog, Key.DirectionLeft)
        press(dialog, Key.MediaPlayPause)
        assertEquals(listOf(TrailerEmbed.TOGGLE, TrailerEmbed.seekBy(10), TrailerEmbed.seekBy(-10), TrailerEmbed.TOGGLE), page.scripts)

        // Up brings Close back, focused; OK there is Close's, not the player's.
        press(dialog, Key.DirectionUp)
        rule.onNodeWithText(L.trailerClose).assertIsFocused()

        // Back closes: the page is destroyed and the focus is on the trailer button again.
        press(dialog, Key.Back)
        assertEquals(0, rule.onAllNodes(isDialog()).fetchSemanticsNodes().size)
        assertEquals(1, page.destroyed)
        assertEquals(null, opener.request)
        rule.onNode(trailerButton()).assertIsFocused()
    }

    /** Every window's root (ViewRootImpl), the activity's and the dialog's. */
    private fun windowRoots(): List<Any> {
        val global = ReflectionHelpers.callStaticMethod<Any>(Class.forName("android.view.WindowManagerGlobal"), "getInstance")
        return ReflectionHelpers.getField<List<Any>>(global, "mRoots").toList()
    }

    /** A window the remote works in: out of touch mode and holding focus, as on a television. */
    private fun remoteWindow(viewRoot: Any) {
        ReflectionHelpers.callInstanceMethod<Any>(
            viewRoot, "ensureTouchMode",
            ReflectionHelpers.ClassParameter.from(Boolean::class.javaPrimitiveType, false),
        )
        ReflectionHelpers.callInstanceMethod<Any>(
            viewRoot, "windowFocusChanged",
            ReflectionHelpers.ClassParameter.from(Boolean::class.javaPrimitiveType, true),
        )
    }

    private fun press(node: SemanticsNodeInteraction, key: Key) {
        node.performKeyInput { pressKey(key) }
        rule.waitForIdle()
    }
}
