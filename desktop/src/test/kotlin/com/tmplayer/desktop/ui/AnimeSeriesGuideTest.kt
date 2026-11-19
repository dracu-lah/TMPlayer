package com.tmplayer.desktop.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
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
import java.io.File
import java.nio.file.Files

/**
 * T11, on the page: an anime counted straight through ("Sky Garden - 05.mkv") that AniList lists as
 * one entry of 40 episodes, with only its streaming list to name them (episodes 1 to 38). The page
 * lists all 40 as one season, the episodes the chat lacks quiet and named, the two nobody named read
 * "Episode 39" and "Episode 40" over the show's own picture dimmed with their number, and nothing
 * is asked once per row.
 */
@OptIn(ExperimentalTestApi::class)
class AnimeSeriesGuideTest {

    private var nextId = 1L

    private fun video(name: String): MediaItem {
        val id = nextId++
        return MediaItem(
            chatId = 7, messageId = id, fileId = id.toInt(), title = name, sizeBytes = 400_000_000,
            durationSec = 1400, mimeType = "video/mp4", thumbnailFileId = 0, miniThumbnail = null,
            date = (1000 - id).toInt(), fileName = name,
        )
    }

    @After
    fun reset() {
        OnlineMetadata.current = null
    }

    private val asked: MutableList<String> = java.util.Collections.synchronizedList(mutableListOf())

    /** How many episodes the fake AniList lists and streams: 40 by default, 500 for the scale test. */
    private var run = 40

    private val aniList = HttpTransport { request ->
        asked += request.url + (request.body?.let { if ("streamingEpisodes" in it) "#episodes" else if ("characters" in it) "#extras" else "#search" }.orEmpty())
        val body = request.body.orEmpty()
        val text = when {
            "graphql.anilist.co" !in request.url -> "{}"
            "streamingEpisodes" in body -> {
                val streaming = (1..(run - 2)).joinToString(",") { """{"title":"Episode $it - Chapter ${('A' + it % 26)}${('a' + it % 7)}o","thumbnail":null}""" }
                """{"data":{"Media":{"idMal":null,"episodes":$run,"nextAiringEpisode":null,"startDate":{"year":2019,"month":4,"day":6},"streamingEpisodes":[$streaming]}}}"""
            }
            "characters" in body -> """{"data":{"Media":{"idMal":null,"genres":[],"averageScore":0,"duration":24,"trailer":null,"characters":{"edges":[]},"recommendations":{"nodes":[]}}}}"""
            else -> """{"data":{"Page":{"media":[{"id":9001,"format":"TV","seasonYear":2019,"startDate":{"year":2019},
                "title":{"romaji":"Sky Garden","english":"Sky Garden","native":"x"},"synonyms":[],"description":"A garden.",
                "coverImage":{"large":"https://s4.anilist.co/c.jpg"},"bannerImage":null}]}}}"""
        }
        HttpResponse(if ("graphql.anilist.co" in request.url) 200 else 404, body = text.toByteArray())
    }

    private fun install() {
        val dir = Files.createTempDirectory("tm-anime").toFile()
        val store = MetadataStore(File(dir, "metadata.properties"))
        store.update { it.copy(enabled = true) }
        OnlineMetadata.current = OnlineMetadata(
            buildKey = "", store = store, cache = MetadataCache(File(dir, "cache")), appVersion = "test", omdbKey = "", http = aniList,
            // No waiting between AniList calls: the page is what is under test, not its rate limit.
            sleep = {},
        )
    }

    private fun show(vararg numbers: Int) =
        SeriesShelf.arrange(numbers.map { video("[SubsPlease] Sky Garden - %02d (1080p) [ABCD1234].mkv".format(it)) })
            .filterIsInstance<ShelfEntry.Show>().single().series

    private fun androidx.compose.ui.test.ComposeUiTest.panel(series: com.tmplayer.data.Series) {
        val watch = SeriesWatch(point = { null }, finished = { false })
        setContent {
            TmMaterialTheme(dark = true) {
                Box(Modifier.size(1280.dp, 900.dp)) {
                    SeriesPanel(
                        series, watch, onPlay = {}, modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(horizontal = 32.dp),
                        onlyMine = false, onOnlyMine = {}, onFindElsewhere = {},
                    )
                }
            }
        }
    }

    private fun androidx.compose.ui.test.ComposeUiTest.untilText(text: String, timeoutMs: Long = 30_000) {
        waitUntil(timeoutMillis = timeoutMs) { onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test
    fun `an anime counted straight through lists every episode the data knows, named, and asks once`() = runComposeUiTest {
        install()
        panel(show(3, 5, 6))
        // The episodes the chat lacks come by number with the streaming list's titles.
        untilText("Not in this chat")
        onNode(hasScrollToIndexAction()).performScrollToIndex(3)
        untilText("S1 E4")
        assertTrue("episode 4 is a quiet gap, not a video", onAllNodesWithText("Not in this chat").fetchSemanticsNodes().isNotEmpty())
        // Nothing is asked per row: a search, the run's facts and its extras.
        val calls = asked.count { "graphql.anilist.co" in it }
        assertTrue("requests: $asked", calls <= 3)
    }

    @Test
    fun `an episode nobody named reads Episode 40 over the shows own picture with its number`() = runComposeUiTest {
        install()
        panel(show(3, 5, 6))
        untilText("Not in this chat")
        onNode(hasScrollToIndexAction()).performScrollToIndex(39)
        untilText("S1 E40")
        onNodeWithText("Episode 40").assertExists()
        // The number stands in the picture's place, in a box that holds its size.
        assertEquals(1, onAllNodesWithText("40").fetchSemanticsNodes().size)
        onNode(androidx.compose.ui.test.hasContentDescription("Episode 40, not in this chat")).assertExists()
    }

    @Test
    fun `five hundred episodes list lazily and ask once`() = runComposeUiTest {
        run = 500
        install()
        panel(show(3, 5, 6, 480))
        untilText("Not in this chat")
        onNode(hasScrollToIndexAction()).performScrollToIndex(70)
        untilText("S1 E70")
        onNodeWithText("Episode 500").assertDoesNotExist()
        val calls = asked.count { "graphql.anilist.co" in it }
        assertTrue("requests: $asked", calls <= 3)
    }
}
