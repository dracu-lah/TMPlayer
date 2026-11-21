package com.tmplayer.ui.browse

import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
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
import com.tmplayer.online.MetaTrailer
import com.tmplayer.online.MetadataCache
import com.tmplayer.online.MetadataStore
import com.tmplayer.online.OnlineMetadata
import com.tmplayer.ui.DrainMainLooperRule
import com.tmplayer.ui.icons.TmIcons
import com.tmplayer.ui.online.LocalTrailerOpener
import com.tmplayer.ui.online.TrailerOpener
import com.tmplayer.ui.theme.Motion
import com.tmplayer.ui.theme.TMPlayerTheme
import com.tmplayer.ui.theme.Tone
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.net.URLDecoder

/**
 * The television's trailer button after the in-app trailer seam (private/TRAILERS-PLAN.md, T1):
 * once a film's extras arrive the button is there beside Play, keeps its name "Watch trailer", is
 * one Right away from Play by remote, and OK on it goes to the platform's player behind
 * [LocalTrailerOpener] with the title's trailer. The phone and desktop pages are held to the same
 * by the desktop's TrailerButtonTest.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w960dp-h540dp-land-television-night-xhdpi")
class TvTrailerFocusTest {

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

    private class Recording : TrailerOpener {
        val opened = mutableListOf<String>()
        override fun open(trailer: MetaTrailer, onFallback: () -> Unit) {
            opened += trailer.key
        }
    }

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
    fun `the trailer button is one Right from Play and OK sends it to the platform's player`() {
        FormFactor.override(true)
        Motion.reduced = true
        OnlineMetadata.current = OnlineMetadata(
            buildKey = "test-key",
            store = MetadataStore(dir.newFile()),
            cache = MetadataCache(dir.newFolder()),
            appVersion = "test",
            http = canned,
        )
        val player = Recording()
        val rows = listOf(DetailRow(DetailAction.Play, "Play", TmIcons.Play, onSelect = {}))
        rule.setContent {
            TMPlayerTheme {
                CompositionLocalProvider(LocalTrailerOpener provides player, LocalSeriesWatch provides SeriesWatch.None) {
                    Box(Modifier.fillMaxSize().background(Tone.background)) {
                        MediaDetailScreen(bunny, "Film Club", null, false, rows, onClose = {})
                    }
                }
            }
        }
        // A window the remote works in: out of touch mode and holding focus, as on a television.
        val viewRoot = rule.activity.window.decorView.parent
        org.robolectric.util.ReflectionHelpers.callInstanceMethod<Any>(
            viewRoot, "ensureTouchMode",
            org.robolectric.util.ReflectionHelpers.ClassParameter.from(Boolean::class.javaPrimitiveType, false),
        )
        org.robolectric.util.ReflectionHelpers.callInstanceMethod<Any>(
            viewRoot, "windowFocusChanged",
            org.robolectric.util.ReflectionHelpers.ClassParameter.from(Boolean::class.javaPrimitiveType, true),
        )
        rule.waitUntil(20_000) { rule.onAllNodes(trailerButton()).fetchSemanticsNodes().isNotEmpty() }
        rule.waitForIdle()

        rule.onNodeWithText("Play").requestFocus()
        press(Key.DirectionRight)
        rule.onNode(trailerButton()).assertIsFocused()
        press(Key.DirectionCenter)
        assertEquals("OK reached the platform's player", listOf("YE7VzlLtp-4"), player.opened)
    }

    private fun press(key: Key) {
        rule.onNode(isRoot()).performKeyInput { pressKey(key) }
        rule.waitForIdle()
    }
}
