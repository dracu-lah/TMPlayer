package com.tmplayer.desktop.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.tmplayer.data.MediaItem
import com.tmplayer.data.SeriesShelf
import com.tmplayer.data.ShelfEntry
import com.tmplayer.online.HttpRequest
import com.tmplayer.online.HttpResponse
import com.tmplayer.online.HttpTransport
import com.tmplayer.online.MetadataCache
import com.tmplayer.online.MetadataStore
import com.tmplayer.online.OnlineMetadata
import com.tmplayer.ui.browse.SeriesPanel
import com.tmplayer.ui.browse.SeriesWatch
import com.tmplayer.ui.theme.TmMaterialTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URLDecoder
import java.nio.file.Files
import java.io.File

/**
 * T9, on the page: every season and episode the metadata knows, merged with the videos in the
 * chats. A canned TMDB lists four seasons of a made-up show (the last not aired), the chat holds
 * five episodes. Missing is quiet, never plays, says what it is and offers one way on; a wrong
 * match and "only what I have" list the chat's own and call nothing missing.
 */
@OptIn(ExperimentalTestApi::class)
class SeriesGuideTest {

    private var nextId = 1L

    private fun video(name: String): MediaItem {
        val id = nextId++
        return MediaItem(
            chatId = 7, messageId = id, fileId = id.toInt(), title = name, sizeBytes = 400_000_000,
            durationSec = 2600, mimeType = "video/mp4", thumbnailFileId = 0, miniThumbnail = null,
            date = (1000 - id).toInt(), fileName = name,
        )
    }

    @After
    fun reset() {
        OnlineMetadata.current = null
    }

    private fun install(seasons: String, season1: String = FOUR_EPISODES): OnlineMetadata {
        val dir = Files.createTempDirectory("tm-guide").toFile()
        val store = MetadataStore(File(dir, "metadata.properties"))
        store.update { it.copy(enabled = true) }
        val online = OnlineMetadata(
            buildKey = "render-key",
            store = store,
            cache = MetadataCache(File(dir, "cache")),
            appVersion = "test",
            omdbKey = "",
            http = Canned(seasons, season1, asked),
        )
        OnlineMetadata.current = online
        return online
    }

    /** A made-up TMDB: one show, "Harbour Lights", its season list as given and season 1's episodes named. */
    private val asked: MutableList<String> = java.util.Collections.synchronizedList(mutableListOf())

    private class Canned(private val seasons: String, private val season1: String, private val asked: MutableList<String>) : HttpTransport {
        override suspend fun send(request: HttpRequest): HttpResponse {
            val url = URLDecoder.decode(request.url, "UTF-8")
            asked += url
            val body = when {
                "search/tv" in url && "Harbour Lights" in url ->
                    """{"results":[{"id":7002,"name":"Harbour Lights","original_name":"Harbour Lights","first_air_date":"2020-02-01","overview":"A boatyard.","poster_path":null,"backdrop_path":null}]}"""
                "/tv/7002/season/1" in url -> season1
                "/tv/7002/season/" in url -> """{"episodes":[]}"""
                "/tv/7002?" in url -> """{"id":7002,"genres":[],"number_of_seasons":4,"seasons":[$seasons],
                    "aggregate_credits":{"cast":[]},"videos":{"results":[]},"content_ratings":{"results":[]},"recommendations":{"results":[]},"similar":{"results":[]}}"""
                else -> """{"results":[]}"""
            }
            return HttpResponse(200, body = body.toByteArray())
        }
    }

    private companion object {
        const val FOUR_EPISODES = """{"episodes":[
                        {"episode_number":1,"name":"First light","air_date":"2020-02-01"},
                        {"episode_number":2,"name":"Second tide","air_date":"2020-02-08","runtime":44,"overview":"The fog comes in."},
                        {"episode_number":3,"name":"Third bell","air_date":"2020-02-15"},
                        {"episode_number":4,"name":"Fourth fog","air_date":"2020-02-22"}]}"""
    }

    private val fourSeasons = """
        {"season_number":0,"episode_count":9,"air_date":"2020-01-01"},
        {"season_number":1,"episode_count":4,"air_date":"2020-02-01"},
        {"season_number":2,"episode_count":3,"air_date":"2021-02-01"},
        {"season_number":3,"episode_count":4,"air_date":"2022-02-01"},
        {"season_number":4,"episode_count":0,"air_date":"2099-02-01"}"""

    private fun show(vararg names: String) =
        SeriesShelf.arrange(names.map(::video)).filterIsInstance<ShelfEntry.Show>().single().series

    private fun androidx.compose.ui.test.ComposeUiTest.panel(
        series: com.tmplayer.data.Series,
        onlyMine: Boolean = false,
        onFind: ((String) -> Unit)? = null,
    ) {
        val watch = SeriesWatch(point = { null }, finished = { false })
        setContent {
            TmMaterialTheme(dark = true) {
                Box(Modifier.size(1280.dp, 900.dp)) {
                    SeriesPanel(
                        series, watch, onPlay = {}, modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(horizontal = 32.dp),
                        onlyMine = onlyMine, onOnlyMine = {}, onFindElsewhere = onFind,
                    )
                }
            }
        }
    }

    private fun androidx.compose.ui.test.ComposeUiTest.untilText(text: String, timeoutMs: Long = 8_000) {
        waitUntil(timeoutMillis = timeoutMs) { onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test
    fun `seasons the chat lacks are listed, quiet, and a missing episode is a dimmed card with a tag that opens Find in other chats`() = runComposeUiTest {
        install(fourSeasons)
        val series = show(
            "Harbour.Lights.S01E01.mkv", "Harbour.Lights.S01E03.mkv", "Harbour.Lights.S02E01.mkv", "Harbour.Lights.S02E02.mkv",
            "Harbour.Lights.S00E01.mkv",
        )
        panel(series, onFind = {})
        // Every season the metadata knows, in the picker, specials last, the season never aired with its day.
        untilText("Season 3")
        onNodeWithText("Season 4").assertExists()
        onNodeWithText("Specials").assertExists()
        assertTrue(onAllNodesWithText("Not in your chats yet").fetchSemanticsNodes().isNotEmpty())
        // Season 1 holds episodes 1 and 3: the gap comes by number with the data's title and words.
        onNodeWithText("Season 1").performClick()
        waitForIdle()
        onNode(hasScrollToIndexAction()).performScrollToIndex(3)
        untilText("Second tide")
        onNodeWithText("Play S01E02").assertDoesNotExist()
        // One line for a screen reader; a missing episode is the one dimmed card with something to press:
        // "Find in other chats" (an upcoming one, below, is no control).
        val gap = onNode(hasContentDescription("Episode 2, not in this chat"))
        gap.assertExists()
        assertTrue(gap.fetchSemanticsNode().config.getOrNull(SemanticsProperties.Role) == androidx.compose.ui.semantics.Role.Button)
        gap.performClick()
        untilText("Find in other chats")
        // The words under the dimmed card.
        onNodeWithText("The fog comes in.").assertExists()
        assertTrue(onAllNodesWithText("Not in this chat").fetchSemanticsNodes().isNotEmpty())
    }

    @Test
    fun `an upcoming episode says when it airs in its tag and is read as airing`() = runComposeUiTest {
        install(
            fourSeasons,
            season1 = """{"episodes":[
                {"episode_number":1,"name":"First light","air_date":"2020-02-01"},
                {"episode_number":2,"name":"Second tide","air_date":"2020-02-08"},
                {"episode_number":3,"name":"Third bell","air_date":"2099-02-08"}]}""",
        )
        panel(show("Harbour.Lights.S01E01.mkv", "Harbour.Lights.S01E02.mkv"))
        untilText("Season 3")
        onNodeWithText("Season 1").performClick()
        waitForIdle()
        onNode(hasScrollToIndexAction()).performScrollToIndex(4)
        waitUntil(timeoutMillis = 8_000) { onAllNodes(hasContentDescription("Episode 3, airs ", substring = true)).fetchSemanticsNodes().isNotEmpty() }
        onAllNodesWithText("Not in this chat").assertCountEquals(0)
        onNodeWithText("Airs Feb 8, 2099", substring = true).assertExists()
        // Nothing to press on a day not yet come.
        onNode(hasContentDescription("Episode 3, airs ", substring = true)).assertHasNoClickAction()
    }

    @Test
    fun `a season of a thousand episodes stays a lazy list and its gaps are cheap`() = runComposeUiTest {
        val thousand = (1..1000).joinToString(",", prefix = "{\"episodes\":[", postfix = "]}") {
            """{"episode_number":$it,"name":"Part $it","air_date":"2020-02-01","runtime":24,"overview":"What happens in part $it."}"""
        }
        install("""{"season_number":1,"episode_count":1000,"air_date":"2020-02-01"}""", season1 = thousand)
        // The chat holds every tenth episode: 900 gaps between 100 cards.
        panel(show(*(1..1000 step 10).map { "Harbour.Lights.S01E%03d.mkv".format(it) }.toTypedArray()))
        waitUntil(timeoutMillis = 30_000) { onAllNodesWithText("Season 1").fetchSemanticsNodes().isNotEmpty() }
        waitForIdle()
        // A thousand episodes are listed a hundred at a time (the page's ranges); the range is a lazy list too.
        onNode(hasScrollToIndexAction()).performScrollToIndex(3)
        waitUntil(timeoutMillis = 30_000) { onAllNodes(hasContentDescription("not in this chat", substring = true)).fetchSemanticsNodes().isNotEmpty() }
        val composed = onAllNodes(hasContentDescription("not in this chat", substring = true)).fetchSemanticsNodes().size
        assertTrue("only the rows on screen are composed: $composed", composed in 1..40)
        val started = System.nanoTime()
        onNode(hasScrollToIndexAction()).performScrollToIndex(95)
        waitForIdle()
        assertTrue("a long jump is quick: ${(System.nanoTime() - started) / 1_000_000} ms", System.nanoTime() - started < 10_000_000_000L)
        assertTrue(onAllNodes(hasContentDescription("not in this chat", substring = true)).fetchSemanticsNodes().size in 1..40)
    }

    @Test
    fun `twenty seasons of fifty episodes ask once for the season on screen, not once per row`() = runComposeUiTest {
        val twenty = (1..20).joinToString(",") { """{"season_number":$it,"episode_count":50,"air_date":"20%02d-02-01"}""".format(it) }
        install(twenty)
        val names = (1..20).flatMap { season -> (1..3).map { "Harbour.Lights.S%02dE%02d.mkv".format(season, it) } }
        val started = System.nanoTime()
        panel(show(*names.toTypedArray()))
        untilText("50 episodes")
        onNodeWithText("Season 20").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick)
        waitForIdle()
        onNode(hasScrollToIndexAction()).performScrollToIndex(40)
        untilText("S20 E40")
        assertTrue("a few seconds at most: ${(System.nanoTime() - started) / 1_000_000} ms", System.nanoTime() - started < 20_000_000_000L)
        // The show, its extras, and the two seasons looked at: nothing per row.
        val seasonCalls = asked.count { "/season/" in it }
        assertTrue("season requests: $seasonCalls", seasonCalls <= 2)
        assertTrue("requests: ${asked.size}", asked.size <= 8)
    }

    @Test
    fun `an unaired season shows its day and offers nothing to find`() = runComposeUiTest {
        install(fourSeasons)
        val series = show("Harbour.Lights.S01E01.mkv", "Harbour.Lights.S01E02.mkv")
        panel(series, onFind = {})
        untilText("Season 4")
        onNodeWithText("Season 4").performClick()
        waitForIdle()
        onNode(hasScrollToIndexAction()).performScrollToIndex(1)
        untilText("Airs", timeoutMs = 4_000)
        onAllNodesWithText("Find in other chats").assertCountEquals(0)
    }

    @Test
    fun `a wrong match and only what I have list the chat alone`() = runComposeUiTest {
        install(fourSeasons)
        // Ten episodes of season 1 in the chat where the metadata's show has four: not the same show.
        val wrong = show(*(1..10).map { "Harbour.Lights.S01E%02d.mkv".format(it) }.toTypedArray())
        panel(wrong)
        waitForIdle()
        Thread.sleep(800)
        waitForIdle()
        onAllNodesWithText("Not in your chats yet").assertCountEquals(0)
        onAllNodesWithText("Season 3").assertCountEquals(0)
    }

    @Test
    fun `only what I have hides every season and episode the chat lacks`() = runComposeUiTest {
        install(fourSeasons)
        val series = show("Harbour.Lights.S01E01.mkv", "Harbour.Lights.S01E03.mkv", "Harbour.Lights.S02E01.mkv")
        panel(series, onlyMine = true)
        waitForIdle()
        Thread.sleep(800)
        waitForIdle()
        onAllNodesWithText("Season 3").assertCountEquals(0)
        onAllNodesWithText("Not in your chats yet").assertCountEquals(0)
    }

    @Test
    fun `without metadata the page is as it was`() = runComposeUiTest {
        OnlineMetadata.current = null
        val series = show("Harbour.Lights.S01E01.mkv", "Harbour.Lights.S01E03.mkv")
        panel(series)
        waitForIdle()
        onAllNodesWithText("Not in your chats yet").assertCountEquals(0)
        onNodeWithText("Season 1").assertExists()
    }
}
