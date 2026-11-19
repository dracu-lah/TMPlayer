package com.tmplayer.desktop.ui

import com.tmplayer.ui.browse.chipLabel
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.use
import com.tmplayer.data.ChatKind
import com.tmplayer.data.ChatSummary
import com.tmplayer.data.DownloadRequest
import com.tmplayer.data.DownloadRunner
import com.tmplayer.data.MediaItem
import com.tmplayer.data.SettingsStore
import com.tmplayer.data.UpdateScheduler
import com.tmplayer.data.WatchedStore
import com.tmplayer.desktop.DesktopPrefs
import com.tmplayer.desktop.DesktopWatchCache
import com.tmplayer.ui.browse.BrowseSection
import com.tmplayer.ui.browse.BrowseTab
import com.tmplayer.ui.theme.FocusVisibility
import com.tmplayer.ui.theme.TmMaterialTheme
import com.tmplayer.ui.theme.Tone
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Paint
import org.jetbrains.skia.Rect
import org.jetbrains.skia.Surface as SkSurface
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import javax.imageio.ImageIO

/**
 * Focus drawn for the keyboard only, the floating bar's gaps, and the pages at the window sizes a
 * desktop meets: the minimum window, a 1366x768 laptop, 1920x1080, 2560x1440, and a 4K screen at
 * 150 and 200 per cent. With TMPLAYER_SHOTS=<dir> the frames are written there as `fx-<name>.png`.
 */
@OptIn(ExperimentalTestApi::class)
class FocusAndSizesShotsTest {

    private val settings = SettingsStore(
        SettingsStore.openDataStore(Files.createTempDirectory("tm-fx").resolve(SettingsStore.FILE_NAME).toFile()),
    )
    private val dir = Files.createTempDirectory("tm-fx-extras").toFile()
    private val shell = ShellState(
        settings,
        object : DownloadRunner {
            override fun download(request: DownloadRequest) = Unit
            override fun cancel(fileId: Int) = Unit
            override fun pause(fileId: Int) = Unit
        },
        WatchedStore(WatchedStore.openDataStore(Files.createTempDirectory("tm-fx-watched").resolve(WatchedStore.FILE_NAME).toFile())),
        services = {
            DesktopExtras(
                prefs = DesktopPrefs(dir.resolve("desktop.properties")),
                updates = UpdateScheduler(settings.updatePrefs, check = { _, _ -> false }, onSkip = {}),
                watchCache = DesktopWatchCache(settings, { dir.resolve("cache") }),
            )
        },
    )

    @After
    fun reset() {
        FocusVisibility.visible = true
        shell.closeChat()
        shell.go(Destination.Chats)
    }

    /** The bar in each state, drawn through the same tree the window has, root tracking included. */
    @Test
    fun barFocusOnlyFromTheKeyboard() {
        for (dark in listOf(true, false)) {
            val theme = if (dark) "dark" else "light"
            runComposeUiTest {
                FocusVisibility.visible = false
                shell.showChats(BrowseSection.of(BrowseTab.Chats))
                setContent {
                    TmMaterialTheme(dark = dark) {
                        ProvideFocusVisibility {
                            Surface(Modifier.size(800.dp, 220.dp).trackFocusVisibility(), color = Tone.background) {
                                NavFrame(shell, null, null) { Box(Modifier.fillMaxSize()) }
                            }
                        }
                    }
                }
                fun shot(name: String) {
                    waitForIdle()
                    mainClock.advanceTimeBy(600)
                    // The page's own layer; a tooltip is a layer of its own over it.
                    save("fx-bar-$name-$theme.png", onAllNodes(androidx.compose.ui.test.isRoot())[0].captureToImage())
                }
                shot("default")
                // Chats lit, the pointer on Home beside it: two shapes with a gap between.
                onNode(hasContentDescription("Home")).performMouseInput { moveTo(center) }
                shot("hover-home")
                // A click on Chats: focus lands on it, and no ring is drawn.
                onNode(hasContentDescription("Chats")).performMouseInput { click(center) }
                shot("clicked")
                assertFalse(FocusVisibility.visible)
                // Tab from there: the ring is drawn on the next place.
                onNode(isFocused()).performKeyInput { pressKey(Key.Tab) }
                shot("tab")
                assertTrue(FocusVisibility.visible)
                // A click again hides it.
                onNode(hasContentDescription("Home")).performMouseInput { click(center) }
                shot("clicked-again")
                assertFalse(FocusVisibility.visible)
            }
        }
    }

    @Test
    fun pagesAtWindowSizes() {
        val chats = listOf(
            chat(2, "Film Club", ChatKind.Channel, 12, 0xFFE5484D.toInt()),
            chat(4, "Weekend series", ChatKind.Group, 3, 0xFFF5A524.toInt()),
            chat(6, "Old lectures", ChatKind.Channel, 0, 0xFF0090FF.toInt()),
            chat(8, "Documentaries in 4K", ChatKind.Channel, 0, 0xFF46A758.toInt()),
        )
        val chips = com.tmplayer.ui.browse.ChatFilter.entries
        val items = (0 until 14).map { video(100L + it, "Video number ${it + 1}", jpeg(COLOURS[it % COLOURS.size])) }
        val pages: List<Pair<String, @Composable () -> Unit>> = listOf(
            "chats" to {
                Column(Modifier.fillMaxSize()) {
                    PageHeader(BrowseTab.Chats.heading)
                    ChoiceChips(chips.map { chip -> Choice(chip.name, chip.chipLabel, chip.icon, selected = chip == chips.first()) {} })
                    val nav = rememberKeyboardNav(ListSurface(rememberLazyListState()))
                    ChatList(chats, setOf(2L), rememberLazyListState(), nav, onOpen = {}, onStar = {})
                }
            },
            "grid" to { VideoGrid(shell, items, "Film Club") },
            "history" to { ContinuePage(shell) },
        )
        // Window size in pixels, and the OS scale it is drawn at.
        val sizes = listOf(
            Triple("min", 960 to 600, 1f),
            Triple("1366", 1366 to 768, 1f),
            Triple("1080p", 1920 to 1080, 1f),
            Triple("1440p", 2560 to 1440, 1f),
            Triple("4k150", 3840 to 2160, 1.5f),
            Triple("4k200", 3840 to 2160, 2f),
        )
        for ((page, content) in pages) {
            for ((label, size, scale) in sizes) {
                save("fx-$page-$label.png", render(size.first, size.second, scale, dark = true, content))
            }
            save("fx-$page-1366-light.png", render(1366, 768, 1f, dark = false, content))
        }
    }

    private fun render(width: Int, height: Int, scale: Float, dark: Boolean, page: @Composable () -> Unit): ByteArray =
        ImageComposeScene(width, height, Density(scale)) {
            TmMaterialTheme(dark = dark) {
                Surface(Modifier.fillMaxSize(), color = Tone.background) {
                    NavFrame(shell, null, null) { page() }
                }
            }
        }.use { scene ->
            scene.render(0)
            Thread.sleep(400)
            scene.render(500_000_000)
            scene.render(1_000_000_000).encodeToData(EncodedImageFormat.PNG)!!.bytes
        }

    private fun save(name: String, image: androidx.compose.ui.graphics.ImageBitmap) {
        val dir = System.getenv("TMPLAYER_SHOTS")?.takeIf { it.isNotBlank() } ?: return
        ImageIO.write(image.toAwtImage(), "png", File(dir).apply { mkdirs() }.resolve(name))
    }

    private fun save(name: String, png: ByteArray) {
        assertTrue(png.size > 1000)
        val dir = System.getenv("TMPLAYER_SHOTS")?.takeIf { it.isNotBlank() } ?: return
        File(dir).apply { mkdirs() }.resolve(name).writeBytes(png)
    }

    private fun video(id: Long, title: String, thumb: ByteArray? = null) = MediaItem(
        chatId = 2, messageId = id, fileId = id.toInt(), title = title, sizeBytes = 900L * 1024 * 1024, durationSec = 2600,
        mimeType = "video/x-matroska", thumbnailFileId = 0, miniThumbnail = thumb, date = 1_790_000_000 - id.toInt(),
    )

    private fun chat(id: Long, title: String, kind: ChatKind, unread: Int, argb: Int) = ChatSummary(
        id = id, title = title, miniThumbnail = jpeg(argb), photoFileId = 0, kind = kind, unreadCount = unread,
    )

    private fun jpeg(argb: Int): ByteArray {
        val surface = SkSurface.makeRasterN32Premul(40, 24)
        surface.canvas.drawRect(Rect.makeWH(40f, 24f), Paint().apply { color = argb })
        return surface.makeImageSnapshot().encodeToData(EncodedImageFormat.JPEG, 80)!!.bytes
    }

    private companion object {
        val COLOURS = listOf(0xFF12A594.toInt(), 0xFF2AABEE.toInt(), 0xFF46A758.toInt(), 0xFF8E4EC6.toInt(), 0xFFE5484D.toInt(), 0xFFF5A524.toInt())
    }
}
