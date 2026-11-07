package com.tmplayer.desktop.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.use
import com.tmplayer.data.ChatKind
import com.tmplayer.data.ChatSummary
import com.tmplayer.data.DownloadRequest
import com.tmplayer.data.DownloadRunner
import com.tmplayer.data.FeaturedPick
import com.tmplayer.data.MediaItem
import com.tmplayer.data.SettingsStore
import com.tmplayer.data.ShelfEntry
import com.tmplayer.data.UpdateScheduler
import com.tmplayer.data.WatchedStore
import com.tmplayer.desktop.DesktopPrefs
import com.tmplayer.desktop.DesktopWatchCache
import com.tmplayer.online.MetaQuery
import com.tmplayer.online.MetaResult
import com.tmplayer.ui.browse.SeriesWatch
import com.tmplayer.ui.theme.Motion
import com.tmplayer.ui.theme.TmMaterialTheme
import com.tmplayer.ui.theme.Tone
import org.jetbrains.skia.Color
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Paint
import org.jetbrains.skia.Rect
import org.jetbrains.skia.Surface as SkSurface
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Home's featured carousel on the desktop: how tall it is at the common window sizes, that its
 * skeleton is as tall as the filled carousel (nothing moves when the pages land), that the first
 * row still shows above the fold of a 1080p window, and Home drawn at 900x700, 1280x800,
 * 1920x1080 and 2560x1440 in both themes. With TMPLAYER_SHOTS=<dir> the frames are written there.
 */
@OptIn(ExperimentalTestApi::class)
class HomeCarouselRenderTest {

    private val settings = SettingsStore(
        SettingsStore.openDataStore(Files.createTempDirectory("tm-carousel").resolve(SettingsStore.FILE_NAME).toFile()),
    )
    private val dir = Files.createTempDirectory("tm-carousel-extras").toFile()
    private val shell = ShellState(
        settings,
        object : DownloadRunner {
            override fun download(request: DownloadRequest) = Unit
            override fun cancel(fileId: Int) = Unit
            override fun pause(fileId: Int) = Unit
        },
        WatchedStore(WatchedStore.openDataStore(Files.createTempDirectory("tm-carousel-watched").resolve(WatchedStore.FILE_NAME).toFile())),
        services = {
            DesktopExtras(
                prefs = DesktopPrefs(dir.resolve("desktop.properties")),
                updates = UpdateScheduler(settings.updatePrefs, check = { _, _ -> false }, onSkip = {}),
                watchCache = DesktopWatchCache(settings, { dir.resolve("cache") }),
            )
        },
    )

    private var reduced = false

    @Before
    fun still() {
        reduced = Motion.reduced
        Motion.reduced = true
    }

    @After
    fun restore() {
        Motion.reduced = reduced
        com.tmplayer.online.OnlineMetadata.current = null
    }

    private fun item(id: Long, chatId: Long, name: String, colour: Long, date: Int) = MediaItem(
        chatId = chatId, messageId = id, fileId = 0, title = name, sizeBytes = 885L * 1024 * 1024,
        durationSec = 5400 + (id % 10).toInt() * 60, mimeType = "video/x-matroska", thumbnailFileId = 0,
        miniThumbnail = jpeg(colour.toInt()), date = date, fileName = name, width = 1920, height = 1080,
    )

    private val bunny = item(500, 2, "Big.Buck.Bunny.2008.1080p.BluRay.x264.mkv", 0xFF46A758, 1_790_000_000)
    private val clips = listOf("Lecture 7 Compilers.mp4", "coast-walk-day-2-1080p.mp4", "Night Train 2019 1080p.mkv").mapIndexed { at, n ->
        item(700L + at, 2, n, 0xFFF5A524, 1_780_000_000 - at)
    }

    @Test
    fun `the carousel is wide and tall without being most of the window`() {
        // (page width, page height) -> expected height: the width's 0.42 until 0.62 of the window stops it.
        fun h(w: Int, h: Int) = featuredHeight(w.dp, h.dp).value
        // The page is the window's width: the desktop's bar floats over it.
        assertEquals(300f, h(700, 660), 0f)
        assertEquals(0.42f * 1060, h(1060, 760), 0.5f)
        assertEquals(0.62f * 1000, h(1700, 1000), 0.5f)
        assertEquals(0.62f * 1380, h(2340, 1380), 0.5f)
        // Never under its floor, never over its two shares.
        assertEquals(300f, h(400, 400), 0f)
        for ((w, hh) in listOf(900 to 700, 1280 to 800, 1920 to 1080, 2560 to 1440)) {
            val page = w
            val tall = featuredHeight(page.dp, (hh - 40).dp)
            assertTrue("$w x $hh: $tall", tall.value <= 0.62f * (hh - 40) + 0.5f && tall.value <= maxOf(300f, 0.42f * page) + 0.5f)
            assertTrue("$w x $hh is taller than the old 0.34 / 0.5: $tall", tall.value >= minOf(0.34f * page, 0.5f * (hh - 40)))
        }
    }

    @Test
    fun `the skeleton is as tall as the filled carousel`() = runComposeUiTest {
        var filled by mutableStateOf<List<FeaturedPick>?>(null)
        val height = featuredHeight(1060.dp, 900.dp)
        mainClock.autoAdvance = false
        setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f)) {
                TmMaterialTheme(dark = true) {
                    Surface(Modifier.size(1060.dp, 900.dp), color = Tone.background) {
                        androidx.compose.foundation.layout.Column {
                          Box(Modifier.testTag("carousel")) {
                            FeaturedCarousel(
                                picks = filled, height = height, side = 24.dp, watch = SeriesWatch.None,
                                chatTitle = { "Film Club" }, onPlay = {}, onDetails = {},
                            )
                          }
                        }
                    }
                }
            }
        }
        mainClock.advanceTimeByFrame()
        val waiting = onNodeWithTag("carousel").getBoundsInRoot()
        assertEquals(height.value, waiting.bottom.value - waiting.top.value, 0.5f)
        filled = listOf(FeaturedPick(ShelfEntry.File(bunny)))
        repeat(5) { mainClock.advanceTimeByFrame() }
        val arrived = onNodeWithTag("carousel").getBoundsInRoot()
        assertEquals("the carousel moved or resized when its pages landed", waiting, arrived)
    }

    @Test fun `the first row shows above the fold at 900x700`() = aboveFold(900, 700)
    @Test fun `the first row shows above the fold at 1280x800`() = aboveFold(1280, 800)
    @Test fun `the first row shows above the fold at 1920x1080`() = aboveFold(1920, 1080)
    @Test fun `the first row shows above the fold at 2560x1440`() = aboveFold(2560, 1440)

    private fun aboveFold(w: Int, h: Int) = runComposeUiTest {
        val online = MetaFixture.install("on")
        val (chats, rows) = library(online)
        shell.go(Destination.Home)
        setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f)) {
                TmMaterialTheme(dark = true) {
                    Surface(Modifier.size(w.dp, h.dp), color = Tone.background) {
                        Box(Modifier.size(w.dp, h.dp)) {
                            NavFrame(shell, null, null) {
                                HomeRowsView(shell, rows, chats, emptyMap(), onRowShown = {}, onArtWanted = {}, onRefresh = {})
                            }
                        }
                    }
                }
            }
        }
        waitForIdle()
        val heading = onAllNodesWithText("Recently added", substring = true).fetchSemanticsNodes().firstOrNull()
        assertTrue("$w x $h: no Recently added heading", heading != null)
        val top = heading!!.boundsInRoot.top
        assertTrue("$w x $h: the first row's heading is at $top, not above the fold", top + 24f <= h)
    }

    @Test
    fun homeAtFourSizes() = kotlinx.coroutines.runBlocking {
        val online = MetaFixture.install("on")
        val (chats, rows) = library(online)
        shell.go(Destination.Home)
        for ((w, h) in listOf(900 to 700, 1280 to 800, 1920 to 1080, 2560 to 1440)) {
            for (dark in listOf(true, false)) {
                val png = render(w, h, dark) {
                    HomeRowsView(shell, rows, chats, emptyMap(), onRowShown = {}, onArtWanted = {}, onRefresh = {})
                }
                save("home-$w-${if (dark) "dark" else "light"}.png", png)
            }
        }
    }

    private val episodes = (1..6).map { n -> item(600L + n, 4, "Harbour.Lights.S02E%02d.1080p.WEB-DL.mkv".format(n), 0xFF2AABEE, 1_790_000_000 + n) }

    /** The made-up library: Bunny and Harbour Lights with their pictures looked up, clips, three starred chats. */
    private fun library(online: com.tmplayer.online.OnlineMetadata): Pair<List<ChatSummary>, List<com.tmplayer.data.HomeRow>> {
        kotlinx.coroutines.runBlocking {
            for (video in listOf(bunny, episodes[4])) {
                val q = MetaQuery.of(video.fileName)!!
                online.lookup(q)
                val info = (online.lookup(q.showOnly()) as? MetaResult.Found)?.info ?: continue
                online.extras(info)
                for (url in listOfNotNull(info.posterUrl, info.backdropUrl, info.wideUrl)) {
                    for (width in listOf(240, 342, 480, 800, 1280)) com.tmplayer.data.Thumbnails.online(url, width) { online.image(url) }
                }
            }
        }
        val chats = listOf(
            ChatSummary(id = 2, title = "Film Club", miniThumbnail = jpeg(0xFFE5484D.toInt()), photoFileId = 0, kind = ChatKind.Channel, unreadCount = 0, isPinned = false),
            ChatSummary(id = 4, title = "Weekend series", miniThumbnail = jpeg(0xFFF5A524.toInt()), photoFileId = 0, kind = ChatKind.Group, unreadCount = 0, isPinned = false),
            ChatSummary(id = 6, title = "Old lectures", miniThumbnail = jpeg(0xFF0090FF.toInt()), photoFileId = 0, kind = ChatKind.Channel, unreadCount = 0, isPinned = false),
        )
        shell.noteChatTitles(chats)
        com.tmplayer.online.KnownMedia.note(clips + bunny + episodes)
        val film = listOf(bunny)
        val rows = com.tmplayer.data.HomeRows.build(
            continueWatching = emptyList(),
            favourites = com.tmplayer.data.HomeRows.favouriteChats(chats, setOf(2L, 4L, 6L)),
            loaded = mapOf(2L to film, 4L to episodes, 6L to clips),
            recent = film + episodes + clips,
        )
        return chats to rows
    }

    private fun render(width: Int, height: Int, dark: Boolean, page: @Composable () -> Unit): ByteArray =
        ImageComposeScene(width, height, Density(1f)) {
            TmMaterialTheme(dark = dark) {
                Surface(Modifier.fillMaxSize(), color = Tone.background) {
                    NavFrame(shell, null, null) { page() }
                }
            }
        }.use { scene ->
            // The carousel's fill (lookups, at most 1.5 s) and the pictures decode off the composition thread.
            var t = 0L
            repeat(30) {
                scene.render(t)
                Thread.sleep(150)
                t += 150_000_000
            }
            scene.render(t + 1_000_000_000).encodeToData(EncodedImageFormat.PNG)!!.bytes
        }

    private fun save(name: String, png: ByteArray) {
        assertTrue(png.size > 1000)
        val out = System.getenv("TMPLAYER_SHOTS")?.takeIf { it.isNotBlank() } ?: return
        File(out).apply { mkdirs() }.resolve(name).writeBytes(png)
    }

    private fun jpeg(argb: Int): ByteArray {
        val surface = SkSurface.makeRasterN32Premul(40, 24)
        surface.canvas.drawRect(Rect.makeWH(40f, 24f), Paint().apply { color = argb })
        surface.canvas.drawRect(Rect.makeXYWH(0f, 14f, 40f, 10f), Paint().apply { color = Color.makeRGB(16, 20, 24) })
        return surface.makeImageSnapshot().encodeToData(EncodedImageFormat.JPEG, 80)!!.bytes
    }
}
