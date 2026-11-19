package com.tmplayer.desktop.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Surface
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.use
import com.tmplayer.data.AllChatsCursor
import com.tmplayer.data.AllChatsPage
import com.tmplayer.data.DownloadRequest
import com.tmplayer.data.DownloadRunner
import com.tmplayer.data.MediaItem
import com.tmplayer.data.SettingsStore
import com.tmplayer.data.WatchedStore
import com.tmplayer.i18n.L
import com.tmplayer.online.MetaQuery
import com.tmplayer.online.PosterChoice
import com.tmplayer.online.PosterChoices
import com.tmplayer.online.WatchOffers
import com.tmplayer.online.WhereToWatch
import com.tmplayer.ui.browse.GlobalSearchViewModel
import com.tmplayer.ui.browse.TitleSource
import com.tmplayer.ui.browse.VideoSearchSource
import com.tmplayer.ui.theme.TmMaterialTheme
import com.tmplayer.ui.theme.Tone
import org.jetbrains.skia.EncodedImageFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * HX41 in the desktop's search (Ctrl+K): the titles lead the palette, Enter on one not in the
 * viewer's chats opens Where to watch, and closing it gives the keyboard back to the field with
 * the words kept. A picture of each into build/desktop-shots for a look.
 */
@OptIn(ExperimentalTestApi::class)
class SearchTitlesDesktopTest {

    private val settings = SettingsStore(
        SettingsStore.openDataStore(Files.createTempDirectory("tm-titles").resolve(SettingsStore.FILE_NAME).toFile()),
    )
    private val shell = ShellState(
        settings,
        object : DownloadRunner {
            override fun download(request: DownloadRequest) = Unit
            override fun cancel(fileId: Int) = Unit
            override fun pause(fileId: Int) = Unit
        },
        WatchedStore(WatchedStore.openDataStore(Files.createTempDirectory("tm-titles-watched").resolve(WatchedStore.FILE_NAME).toFile())),
        services = { error("not used by these pages") },
    )

    private val copy = MediaItem(
        chatId = 3, messageId = 1, fileId = 1, title = "Night.Train.2019.1080p.mkv", sizeBytes = 900L * 1024 * 1024,
        durationSec = 5_400, mimeType = "video/mp4", thumbnailFileId = 0, miniThumbnail = null, date = 1, fileName = "Night.Train.2019.1080p.mkv",
    )
    private val inChats = PosterChoice(7, false, "Night Train", 2019, "en", "A sleeper across the border.", null)
    private val notIn = PosterChoice(8, false, "Night Train Home", 2001, "en", "", null)
    private val offers = WhereToWatch.Found(WatchOffers("GB", stream = listOf("Stream One"), rent = listOf("Rent Shop")))

    private fun model(titles: List<PosterChoice>, found: List<MediaItem>) = GlobalSearchViewModel(
        VideoSearchSource { _, _, _ -> AllChatsPage(found, AllChatsCursor(videoDone = true, documentDone = true)) },
        known = { emptyList() },
        settleMs = 0,
        titleSource = object : TitleSource {
            override suspend fun titles(query: MetaQuery) = PosterChoices.Found(titles)
            override suspend fun whereToWatch(title: PosterChoice) = offers
        },
    )

    @Test
    fun `the titles lead the palette, and the sheet lists the services`() = kotlinx.coroutines.runBlocking {
        val model = model(listOf(inChats, notIn), listOf(copy))
        shell.paletteQuery = TextFieldValue("night train")
        model.type("night train")
        kotlinx.coroutines.withTimeout(5_000) {
            while (model.state.value.busy || model.state.value.titleHits.isEmpty()) kotlinx.coroutines.delay(20)
        }
        val hits = model.state.value.titleHits
        assertTrue(hits.first { it.title == inChats }.inChats)
        assertTrue(hits.first { it.title == notIn }.notInChats)
        val out = File("build/desktop-shots").apply { mkdirs() }
        for (dark in listOf(true, false)) {
            out.resolve("desktop-search-titles-${if (dark) "dark" else "light"}.png").writeBytes(shot(dark) { Box(Modifier.fillMaxSize()) { SearchPalette(shell, model, emptyList(), onOpenSeries = {}) } })
        }
        model.openWhereToWatch(notIn)
        kotlinx.coroutines.withTimeout(5_000) { while (model.state.value.watching?.second == null) kotlinx.coroutines.delay(20) }
        out.resolve("desktop-where-to-watch.png").writeBytes(shot(true) { Box(Modifier.fillMaxSize()) { SearchPalette(shell, model, emptyList(), onOpenSeries = {}) } })
        model.closeWhereToWatch()
    }

    @Test
    fun `Enter on a title not in the chats opens Where to watch and Close gives the field back`() = runComposeUiTest {
        val model = model(listOf(notIn), emptyList())
        shell.paletteQuery = TextFieldValue("night train home", TextRange(16))
        shell.paletteSubmit = true
        setContent { TmMaterialTheme(dark = true) { Box(Modifier.size(1000.dp, 700.dp)) { SearchPalette(shell, model, emptyList(), onOpenSeries = {}) } } }
        waitUntil(timeoutMillis = 5_000) { model.state.value.titleHits.isNotEmpty() && !model.state.value.busy }
        waitForIdle()
        onNodeWithText(L.searchNotInChats).assertExists()
        // Up to the first line, the title, then Enter on the field opens it.
        repeat(6) { onNode(hasSetTextAction()).performKeyInput { pressKey(Key.DirectionUp) } }
        onNode(hasSetTextAction()).performKeyInput { pressKey(Key.Enter) }
        waitUntil(timeoutMillis = 5_000) { model.state.value.watching?.second != null }
        assertEquals(notIn, model.state.value.watching?.first)
        waitForIdle()
        onNodeWithText("Stream One").assertExists()
        onNodeWithTag("where-to-watch-close").performClick()
        waitForIdle()
        assertNull(model.state.value.watching)
        onNode(hasSetTextAction() and isFocused()).assertExists()
        assertEquals("night train home", shell.paletteQuery.text)
    }

    @Test
    fun `titles landing above the videos leave the highlight on the video, so Enter opens what was highlighted`() = runComposeUiTest {
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        val model = GlobalSearchViewModel(
            VideoSearchSource { _, _, _ -> AllChatsPage(listOf(copy), AllChatsCursor(videoDone = true, documentDone = true)) },
            known = { emptyList() },
            settleMs = 0,
            titleSource = object : TitleSource {
                override suspend fun titles(query: MetaQuery): PosterChoices {
                    gate.await()
                    return PosterChoices.Found(listOf(notIn, inChats))
                }
                override suspend fun whereToWatch(title: PosterChoice) = offers
            },
        )
        shell.paletteOpen = true
        shell.paletteQuery = TextFieldValue("night train", TextRange(11))
        shell.paletteSubmit = true
        setContent { TmMaterialTheme(dark = true) { Box(Modifier.size(1000.dp, 700.dp)) { if (shell.paletteOpen) SearchPalette(shell, model, emptyList(), onOpenSeries = {}) } } }
        waitUntil(timeoutMillis = 5_000) { model.state.value.groups.isNotEmpty() && !model.state.value.busy }
        waitForIdle()
        // Down to the video (the line under Add more chats); then TMDB answers and two titles land above it.
        onNode(hasSetTextAction()).performKeyInput { pressKey(Key.DirectionDown) }
        waitForIdle()
        gate.complete(Unit)
        waitUntil(timeoutMillis = 5_000) { model.state.value.titleHits.size == 2 }
        waitForIdle()
        onNode(hasSetTextAction()).performKeyInput { pressKey(Key.Enter) }
        waitUntil(timeoutMillis = 5_000) { !shell.paletteOpen }
        // The video opened, not the title that took its place in the list.
        assertNull(model.state.value.watching)
        assertEquals(copy.id, shell.detail?.item?.id)
        shell.closeDetail()
    }

    private fun shot(dark: Boolean, page: @androidx.compose.runtime.Composable () -> Unit): ByteArray =
        ImageComposeScene(1280, 800, Density(1f)) {
            TmMaterialTheme(dark = dark) { Surface(Modifier.fillMaxSize(), color = Tone.background) { page() } }
        }.use { scene ->
            scene.render(0)
            Thread.sleep(200)
            scene.render(500_000_000)
            scene.render(1_000_000_000).encodeToData(EncodedImageFormat.PNG)!!.bytes
        }
}
