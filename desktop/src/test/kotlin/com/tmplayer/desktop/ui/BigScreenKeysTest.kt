package com.tmplayer.desktop.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.tmplayer.data.DownloadRequest
import com.tmplayer.data.DownloadRunner
import com.tmplayer.data.MediaItem
import com.tmplayer.data.SettingsStore
import com.tmplayer.data.UpdateScheduler
import com.tmplayer.data.WatchedStore
import com.tmplayer.desktop.DesktopLayout
import com.tmplayer.desktop.DesktopPrefs
import com.tmplayer.desktop.DesktopWatchCache
import com.tmplayer.i18n.L
import com.tmplayer.ui.components.LocalRemoteArrows
import com.tmplayer.ui.components.TmAlertDialog
import com.tmplayer.ui.nav.BackHandler
import com.tmplayer.ui.nav.LocalBackStack
import com.tmplayer.ui.theme.FocusVisibility
import com.tmplayer.ui.theme.TmMaterialTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.nio.file.Files

/**
 * The big-screen mode with a remote's keys alone (plan 8.7): the TV bar and a page under it, the
 * arrows, OK and Back, the picture's menu, a dialog, a text field, and the Layout setting, driven
 * off screen with made-up videos. The window's own key handler is the outer box's.
 */
@OptIn(ExperimentalTestApi::class)
class BigScreenKeysTest {

    private val settings = SettingsStore(
        SettingsStore.openDataStore(Files.createTempDirectory("tm-tv").resolve(SettingsStore.FILE_NAME).toFile()),
    )
    private val dir = Files.createTempDirectory("tm-tv-extras").toFile()
    private val prefsFile = dir.resolve("desktop.properties")
    private val shell = ShellState(
        settings,
        object : DownloadRunner {
            override fun download(request: DownloadRequest) = Unit
            override fun cancel(fileId: Int) = Unit
            override fun pause(fileId: Int) = Unit
        },
        WatchedStore(WatchedStore.openDataStore(Files.createTempDirectory("tm-tv-watched").resolve(WatchedStore.FILE_NAME).toFile())),
        services = {
            DesktopExtras(
                prefs = DesktopPrefs(prefsFile),
                updates = UpdateScheduler(settings.updatePrefs, check = { _, _ -> false }, onSkip = {}),
                watchCache = DesktopWatchCache(settings, { dir.resolve("cache") }),
            )
        },
    )

    private val videos = (0 until 10).map { at ->
        MediaItem(
            chatId = 2, messageId = at + 1L, fileId = 0, title = "Video $at", sizeBytes = 300L * 1024 * 1024,
            durationSec = 1500, mimeType = "video/mp4", thumbnailFileId = 0, miniThumbnail = null, date = 0,
            fileName = "Video $at.mkv",
        )
    }

    @Before
    fun television() {
        BigScreen.layout = DesktopLayout.Desktop
        BigScreen.override = DesktopLayout.Tv
        FocusVisibility.visible = true
    }

    @After
    fun desktop() {
        BigScreen.override = null
        BigScreen.layout = DesktopLayout.Desktop
    }

    private fun poster(n: Int) = hasText("Video $n") and hasClickAction()
    private fun tab(label: String) = hasContentDescription(label) and hasClickAction()
    private val picture get() = hasContentDescription(L.navProfile) and hasClickAction()

    /** The window as Main.kt and DesktopShell put it together, the page's Back as Browse has it. */
    @Composable
    private fun Window(page: @Composable () -> Unit) {
        TmMaterialTheme(dark = true) {
            val focus = LocalFocusManager.current
            SideEffect { shell.focusManager = focus }
            CompositionLocalProvider(LocalBackStack provides shell.backStack, LocalRemoteArrows provides BigScreen.on) {
                Box(Modifier.size(1280.dp, 720.dp).onKeyEvent(shell::onKey)) {
                    BackHandler(enabled = shell.openChat == null && shell.destination != Destination.Home) { shell.backFromPage() }
                    NavFrame(shell, null, null) { page() }
                }
            }
        }
    }

    private fun ComposeUiTest.press(key: Key) {
        onNode(isFocused()).performKeyInput { pressKey(key) }
        waitForIdle()
    }

    @Test
    fun `the remote walks the bar, goes down into the grid, leaves it at its top, and comes back to the same poster`() = runComposeUiTest {
        setContent { Window { VideoGrid(shell, videos, "Film Club") } }
        onNode(tab(L.browseTabHome)).requestFocus()
        onNode(tab(L.browseTabHome)).assertIsFocused()
        press(Key.DirectionRight)
        onNode(tab(L.navChats)).assertIsFocused()
        press(Key.DirectionLeft)
        press(Key.DirectionLeft)
        onNode(tab(L.commonSearch)).assertIsFocused()
        press(Key.DirectionLeft)
        onNode(picture).assertIsFocused()
        assertTrue(shell.tvBar.focused)

        // Down into the page: nothing was focused there yet, so its first poster.
        press(Key.DirectionDown)
        onNode(poster(0)).assertIsFocused()
        assertFalse(shell.tvBar.focused)
        press(Key.DirectionRight)
        onNode(poster(1)).assertIsFocused()
        press(Key.DirectionRight)
        press(Key.DirectionRight)
        onNode(poster(3)).assertIsFocused()

        // Up from the top row leaves the grid for the bar, on the page's own place (Home), not
        // whichever item stands above the poster.
        press(Key.DirectionUp)
        onNode(tab(L.browseTabHome)).assertIsFocused()

        // And Down goes back to the poster the remote was on, not the one under Home.
        press(Key.DirectionDown)
        onNode(poster(3)).assertIsFocused()
    }

    @Test
    fun `Home's rows walk with the arrows, Up leaves for the bar, and Down comes back to the same item`() = runComposeUiTest {
        kotlinx.coroutines.runBlocking { settings.setHomeCarousel(false) }
        fun item(chatId: Long, title: String, at: Int) = MediaItem(
            chatId = chatId, messageId = 100L * chatId + at, fileId = 0, title = title, sizeBytes = 300L * 1024 * 1024,
            durationSec = 1500, mimeType = "video/mp4", thumbnailFileId = 0, miniThumbnail = picture(), date = 1000 - at,
            fileName = "$title.mkv",
        )
        val chats = listOf(
            com.tmplayer.data.ChatSummary(id = 2, title = "Film Club", miniThumbnail = null, photoFileId = 0, kind = com.tmplayer.data.ChatKind.Channel),
            com.tmplayer.data.ChatSummary(id = 4, title = "Weekend series", miniThumbnail = null, photoFileId = 0, kind = com.tmplayer.data.ChatKind.Group),
        )
        val films = (0 until 8).map { item(2, "Film $it", it) }
        val shows = (0 until 8).map { item(4, "Show $it", it) }
        // Continue watching (the shows) over Recently added (the films), as Home draws them.
        val resume = shows.mapIndexed { at, show ->
            com.tmplayer.data.ResumeRecord(
                chatId = show.chatId, messageId = show.messageId, fileId = 1, title = show.title,
                chatTitle = "Weekend series", sizeBytes = show.sizeBytes, durationSec = show.durationSec,
                positionMs = 400_000L, durationMs = show.durationSec * 1000L, updatedAt = 100L - at,
            )
        }
        val rows = com.tmplayer.data.HomeRows.build(
            continueWatching = resume,
            favourites = com.tmplayer.data.HomeRows.favouriteChats(chats, setOf(2L, 4L)),
            loaded = mapOf(2L to films, 4L to shows),
            recent = films,
        )
        val art = shows.associateBy { SettingsStore.progressKey(it.chatId, it.messageId) }
        setContent { Window { HomeRowsView(shell, rows, chats, art, onRowShown = {}, onArtWanted = {}, onRefresh = {}) } }
        onNode(tab(L.browseTabHome)).requestFocus()
        fun focusedText(): String = onNode(isFocused()).fetchSemanticsNode().config
            .getOrElse(androidx.compose.ui.semantics.SemanticsProperties.Text) { emptyList() }.joinToString(" ")
        // Down into the page, and on down to the first row's cards.
        repeat(4) { if (!focusedText().contains("Film 0") && !focusedText().contains("Show 0")) press(Key.DirectionDown) }
        val firstRow = if (focusedText().contains("Film")) "Film" else "Show"
        val otherRow = if (firstRow == "Film") "Show" else "Film"
        assertTrue(focusedText(), focusedText().contains("$firstRow 0"))
        press(Key.DirectionRight)
        press(Key.DirectionRight)
        assertTrue(focusedText(), focusedText().contains("$firstRow 2"))
        // Down to the next row, and back up.
        repeat(3) { if (!focusedText().contains(otherRow)) press(Key.DirectionDown) }
        assertTrue(focusedText(), focusedText().contains(otherRow))
        // Up and up again until the bar: the item the remote left the page from comes back.
        var last = onNode(isFocused()).fetchSemanticsNode().id
        repeat(8) {
            if (!shell.tvBar.focused) {
                last = onNode(isFocused()).fetchSemanticsNode().id
                press(Key.DirectionUp)
            }
        }
        onNode(tab(L.browseTabHome)).assertIsFocused()
        press(Key.DirectionDown)
        assertEquals(last, onNode(isFocused()).fetchSemanticsNode().id)
    }

    @Test
    fun `the page gives the remote back to the item it was on, as after a video, and to its first item before that`() = runComposeUiTest {
        setContent { Window { VideoGrid(shell, videos, "Film Club") } }
        // Nothing focused on the page yet: its first poster.
        runOnIdle { assertTrue(shell.focusPage()) }
        onNode(poster(0)).assertIsFocused()
        onNode(poster(6)).requestFocus()
        // Away to the bar (a video opened from the poster takes the keys the same way), and back.
        runOnIdle { assertTrue(shell.focusBar()) }
        onNode(tab(L.browseTabHome)).assertIsFocused()
        runOnIdle { assertTrue(shell.focusPage()) }
        onNode(poster(6)).assertIsFocused()
    }

    @Test
    fun `Back climbs from a page to its place on the bar, then Home, then out of full screen`() = runComposeUiTest {
        shell.go(Destination.Chats)
        shell.fullscreen = true
        setContent { Window { VideoGrid(shell, videos, "Film Club") } }
        onNode(poster(3)).requestFocus()

        press(Key.Escape)
        assertEquals(Destination.Chats, shell.destination)
        onNode(tab(L.navChats)).assertIsFocused()

        press(Key.Backspace)
        assertEquals(Destination.Home, shell.destination)
        onNode(tab(L.browseTabHome)).assertIsFocused()
        assertTrue("full screen holds through the pages", shell.fullscreen)

        press(Key.Escape)
        assertFalse("the last Back leaves full screen", shell.fullscreen)
    }

    @Test
    fun `from Home's page Back goes up to the bar before anything leaves full screen`() = runComposeUiTest {
        shell.fullscreen = true
        setContent { Window { VideoGrid(shell, videos, "Film Club") } }
        onNode(poster(5)).requestFocus()
        press(Key.Escape)
        onNode(tab(L.browseTabHome)).assertIsFocused()
        assertTrue(shell.fullscreen)
    }

    @Test
    fun `the picture's menu keeps the remote and gives it back to the picture`() = runComposeUiTest {
        setContent { Window { VideoGrid(shell, videos, "Film Club") } }
        onNode(picture).requestFocus()
        press(Key.Enter)
        assertTrue(shell.profileOpen)
        onNode(hasText(L.browseTabFavourites) and hasClickAction()).assertIsFocused()
        // Left and Right go nowhere while the menu is open.
        press(Key.DirectionRight)
        onNode(hasText(L.browseTabFavourites) and hasClickAction()).assertIsFocused()
        press(Key.DirectionDown)
        onNode(hasText(L.navDownloads) and hasClickAction()).assertIsFocused()
        press(Key.Escape)
        assertFalse(shell.profileOpen)
        onNode(picture).assertIsFocused()

        // A place picked from it: the page opens, and the remote is on the picture again.
        press(Key.Enter)
        press(Key.DirectionDown)
        press(Key.Enter)
        assertEquals(Destination.Downloads, shell.destination)
        onNode(picture).assertIsFocused()
    }

    @Test
    fun `a dialog takes the remote on its safe button, its arrows walk the buttons, and Back closes it`() = runComposeUiTest {
        var confirmed by mutableIntStateOf(0)
        setContent {
            Window {
                var open by remember { mutableStateOf(false) }
                Column {
                    Button(onClick = { open = true }) { Text("Remove the copy") }
                    if (open) {
                        TmAlertDialog(
                            onDismissRequest = { open = false },
                            confirmButton = { TextButton(onClick = { confirmed++; open = false }) { Text("Remove") } },
                            dismissButton = { TextButton(onClick = { open = false }) { Text("Keep") } },
                            text = { Text("TMPlayer's copy goes once it is saved.") },
                        )
                    }
                }
            }
        }
        val opener = hasText("Remove the copy") and hasClickAction()
        val keep = hasText("Keep") and hasClickAction()
        val remove = hasText("Remove") and hasClickAction()
        // The dialog is a layer of its own, with its own focus; the window's stays on the opener.
        fun on(node: androidx.compose.ui.test.SemanticsMatcher, key: Key) {
            onNode(node).performKeyInput { pressKey(key) }
            waitForIdle()
        }
        onNode(opener).requestFocus()
        on(opener, Key.Enter)
        onNode(keep).assertIsFocused()
        on(keep, Key.DirectionRight)
        onNode(remove).assertIsFocused()
        on(remove, Key.DirectionLeft)
        onNode(keep).assertIsFocused()
        // Backspace is the remote's Back: the dialog goes, nothing is removed, and the remote is
        // back on the button that opened it.
        on(keep, Key.Backspace)
        onNodeWithText("TMPlayer's copy goes once it is saved.").assertDoesNotExist()
        assertEquals(0, confirmed)
        onNode(opener).assertIsFocused()

        on(opener, Key.Enter)
        on(keep, Key.DirectionRight)
        on(remove, Key.Enter)
        assertEquals(1, confirmed)
    }

    @Test
    fun `Backspace in a text field deletes, and Esc there is Back`() = runComposeUiTest {
        var backs by mutableIntStateOf(0)
        var text by mutableStateOf("abc")
        setContent {
            Window {
                BackHandler { backs++ }
                BasicTextField(text, { text = it })
            }
        }
        onNode(hasSetTextAction()).requestFocus()
        onNode(hasSetTextAction()).performKeyInput { pressKey(Key.MoveEnd) }
        onNode(hasSetTextAction()).performKeyInput { pressKey(Key.Backspace) }
        waitForIdle()
        assertEquals("ab", text)
        assertEquals(0, backs)
        onNode(hasSetTextAction()).performKeyInput { pressKey(Key.Escape) }
        waitForIdle()
        assertEquals(1, backs)
    }

    @Test
    fun `on the desktop layout a grid keeps the arrow at its edge and Back leaves full screen first`() = runComposeUiTest {
        BigScreen.override = DesktopLayout.Desktop
        shell.fullscreen = true
        setContent { Window { VideoGrid(shell, videos, "Film Club") } }
        assertFalse("no TV bar on the desktop", shell.tvBar.shown)
        onNode(poster(1)).requestFocus()
        press(Key.DirectionUp)
        onNode(poster(1)).assertIsFocused()
        press(Key.Escape)
        assertFalse(shell.fullscreen)
    }

    @Test
    fun `a layout picked in Settings is kept, wins over the launch flag, and switches at once`() {
        BigScreen.override = DesktopLayout.Tv
        pickLayout(shell, DesktopLayout.Desktop)
        assertFalse(BigScreen.on)
        assertEquals(null, BigScreen.override)
        assertEquals(DesktopLayout.Desktop, DesktopPrefs(prefsFile).now.layout)

        pickLayout(shell, DesktopLayout.Tv)
        assertTrue(BigScreen.on)
        assertTrue("a television's app fills the screen", shell.fullscreen)
        assertEquals(DesktopLayout.Tv, DesktopPrefs(prefsFile).now.layout)

        pickLayout(shell, DesktopLayout.Desktop)
        assertFalse(BigScreen.on)
        assertFalse(shell.fullscreen)
        assertEquals(DesktopLayout.Desktop, DesktopPrefs(prefsFile).now.layout)
    }

    /** A tiny JPEG standing in for Telegram's minithumbnail: Home shows only videos with a picture. */
    private fun picture(): ByteArray {
        val surface = org.jetbrains.skia.Surface.makeRasterN32Premul(40, 24)
        surface.canvas.drawRect(org.jetbrains.skia.Rect.makeWH(40f, 24f), org.jetbrains.skia.Paint().apply { color = 0xFF2AABEE.toInt() })
        return surface.makeImageSnapshot().encodeToData(org.jetbrains.skia.EncodedImageFormat.JPEG, 80)!!.bytes
    }
}
