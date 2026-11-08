package com.tmplayer.desktop.ui

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.test.withKeyDown
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.tmplayer.data.DownloadRequest
import com.tmplayer.data.DownloadRunner
import com.tmplayer.data.SettingsStore
import com.tmplayer.data.UpdateScheduler
import com.tmplayer.data.WatchedStore
import com.tmplayer.desktop.DesktopLayout
import com.tmplayer.desktop.DesktopPrefs
import com.tmplayer.desktop.DesktopWatchCache
import com.tmplayer.desktop.os.OsInfo
import com.tmplayer.i18n.L
import com.tmplayer.ui.browse.AddChatsRequest
import com.tmplayer.ui.browse.AddChatsState
import com.tmplayer.ui.components.ChooseChatsButton
import com.tmplayer.ui.components.LocalRemoteArrows
import com.tmplayer.ui.nav.BackHandler
import com.tmplayer.ui.nav.LocalBackStack
import com.tmplayer.ui.theme.FocusVisibility
import com.tmplayer.ui.theme.TmMaterialTheme
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.nio.file.Files

/**
 * The person-with-a-plus button (Choose chats) on the desktop: reached and pressed from the
 * keyboard, named for a screen reader, hidden while the setup or the gate is up, answering its
 * key, never stacking a second page on a double press, keeping its size at a large font, and on
 * the big-screen mode's bar (on Chats only, not Home) reached by the remote with Back landing on it again.
 */
@OptIn(ExperimentalTestApi::class)
class ChooseChatsButtonTest {

    private val settings = SettingsStore(
        SettingsStore.openDataStore(Files.createTempDirectory("tm-choose").resolve(SettingsStore.FILE_NAME).toFile()),
    )
    private val dir = Files.createTempDirectory("tm-choose-extras").toFile()
    private val shell = ShellState(
        settings,
        object : DownloadRunner {
            override fun download(request: DownloadRequest) = Unit
            override fun cancel(fileId: Int) = Unit
            override fun pause(fileId: Int) = Unit
        },
        WatchedStore(WatchedStore.openDataStore(Files.createTempDirectory("tm-choose-watched").resolve(WatchedStore.FILE_NAME).toFile())),
        services = {
            DesktopExtras(
                prefs = DesktopPrefs(dir.resolve("desktop.properties")),
                updates = UpdateScheduler(settings.updatePrefs, check = { _, _ -> false }, onSkip = {}),
                watchCache = DesktopWatchCache(settings, { dir.resolve("cache") }),
            )
        },
    )

    @Before
    fun clean() {
        FocusVisibility.visible = true
        AddChatsRequest.take()
        AddChatsState.open = false
        shell.chooseChatsAllowed = true
    }

    @After
    fun restore() {
        BigScreen.override = null
        BigScreen.layout = DesktopLayout.Desktop
        AddChatsRequest.take()
        AddChatsState.open = false
    }

    private val button get() = hasContentDescription(L.pickerChooseChats) and hasClickAction()
    private fun tab(label: String) = hasContentDescription(label) and hasClickAction()

    private fun ComposeUiTest.press(key: Key) {
        onNode(isFocused()).performKeyInput { pressKey(key) }
        waitForIdle()
    }

    @Test
    fun `Tab reaches the button, Enter asks for the screen once even when pressed twice`() = runComposeUiTest {
        setContent {
            TmMaterialTheme(dark = true) {
                Box(Modifier.size(600.dp, 200.dp)) { ChooseChatsButton(size = CHOOSE_CHATS_SIZE, onClick = { shell.chooseChats() }) }
            }
        }
        onNode(button).requestFocus()
        onNode(button).assertIsFocused()
        press(Key.Enter)
        press(Key.Enter)
        assertTrue("the first press asked", AddChatsRequest.take())
        assertFalse("the second made no second request to stack a page", AddChatsRequest.take())
    }

    @Test
    fun `while the screen is up or the setup is, the press and the key ask for nothing`() = runComposeUiTest {
        AddChatsState.open = true
        assertFalse(shell.chooseChats())
        AddChatsState.open = false
        shell.chooseChatsAllowed = false
        assertFalse(shell.chooseChats())
        assertFalse(AddChatsRequest.take())
    }

    @Test
    fun `Ctrl+Shift+A opens Choose chats, Ctrl+A alone does not, and the key stands down while not allowed`() = runComposeUiTest {
        setContent {
            TmMaterialTheme(dark = true) {
                Box(Modifier.size(300.dp, 100.dp).onPreviewKeyEvent(shell::onPreviewKey)) {
                    Box(Modifier.size(50.dp).testTag("probe").focusable())
                }
            }
        }
        val command = if (OsInfo.isMac) Key.MetaLeft else Key.CtrlLeft
        fun chord(withShift: Boolean) {
            onNode(isFocused()).performKeyInput {
                withKeyDown(command) {
                    if (withShift) withKeyDown(Key.ShiftLeft) { pressKey(Key.A) } else pressKey(Key.A)
                }
            }
            waitForIdle()
        }
        onNode(hasTestTag("probe")).requestFocus()
        chord(withShift = false)
        assertFalse("Ctrl+A is the picker's Select all, never this", AddChatsRequest.take())
        shell.chooseChatsAllowed = false
        chord(withShift = true)
        assertFalse("not while the setup or the gate is up", AddChatsRequest.take())
        shell.chooseChatsAllowed = true
        chord(withShift = true)
        assertTrue(AddChatsRequest.take())
    }

    @Test
    fun `a large font leaves the button its size and the title beside it its room`() = runComposeUiTest {
        setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, fontScale = 1.3f)) {
                TmMaterialTheme(dark = true) {
                    // The smallest window: a title, then the button, in 320 dp.
                    Row(Modifier.size(320.dp, 64.dp)) {
                        Text("Chats", maxLines = 1)
                        ChooseChatsButton(size = CHOOSE_CHATS_SIZE, onClick = {})
                    }
                }
            }
        }
        onNode(button).assertWidthIsEqualTo(CHOOSE_CHATS_SIZE)
        onNodeWithText("Chats").assertExists()
    }

    private fun television(onChats: Boolean = true) {
        BigScreen.layout = DesktopLayout.Desktop
        BigScreen.override = DesktopLayout.Tv
        if (onChats) shell.showChats(shell.chatSection)
    }

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

    @Test
    fun `on the big-screen bar Right from Chats reaches the button, Enter opens, and Back lands on it again`() = runComposeUiTest {
        television()
        setContent { Window { Box(Modifier.size(10.dp)) } }
        onNode(tab(L.navChats)).requestFocus()
        press(Key.DirectionRight)
        onNode(button).assertIsFocused()
        press(Key.Enter)
        assertTrue("Enter asked for the screen", AddChatsRequest.take())

        // The screen opens over the window and the remote is somewhere else in it; closing it
        // (Back) puts the remote back on the button that opened it.
        AddChatsState.open = true
        waitForIdle()
        onNode(tab(L.navChats)).requestFocus()
        AddChatsState.open = false
        waitForIdle()
        onNode(button).assertIsFocused()

        press(Key.DirectionLeft)
        onNode(tab(L.navChats)).assertIsFocused()
    }

    @Test
    fun `the big-screen bar has no button on Home, only on Chats`() = runComposeUiTest {
        television(onChats = false)
        setContent { Window { Box(Modifier.size(10.dp)) } }
        onNode(button).assertDoesNotExist()
        onNode(tab(L.navChats)).requestFocus()
        press(Key.DirectionRight)
        onNode(tab(L.navChats)).assertIsFocused()
    }

    @Test
    fun `the big-screen bar has no button while the setup or the gate is up`() = runComposeUiTest {
        television()
        shell.chooseChatsAllowed = false
        setContent { Window { Box(Modifier.size(10.dp)) } }
        onNode(button).assertDoesNotExist()
        onNode(tab(L.navChats)).requestFocus()
        press(Key.DirectionRight)
        onNode(tab(L.navChats)).assertIsFocused()
    }
}
