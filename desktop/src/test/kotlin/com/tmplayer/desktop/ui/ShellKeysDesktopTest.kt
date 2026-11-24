package com.tmplayer.desktop.ui

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import com.tmplayer.data.DownloadRequest
import com.tmplayer.data.DownloadRunner
import com.tmplayer.data.SettingsStore
import com.tmplayer.data.UpdateScheduler
import com.tmplayer.data.WatchedStore
import com.tmplayer.desktop.DesktopPrefs
import com.tmplayer.desktop.DesktopWatchCache
import com.tmplayer.ui.theme.TmMaterialTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

/**
 * The shell's keyboard sheet and its keys (WP-51): "?" and F1 open it, "/" is a search and not the
 * sheet, Ctrl+F on a page with no search field opens the search across every chat; the window
 * title, and the smallest window.
 */
@OptIn(ExperimentalTestApi::class, androidx.compose.ui.InternalComposeUiApi::class)
class ShellKeysDesktopTest {

    private val settings = SettingsStore(
        SettingsStore.openDataStore(Files.createTempDirectory("tm-shellkeys").resolve(SettingsStore.FILE_NAME).toFile()),
    )
    private val dir = Files.createTempDirectory("tm-shellkeys-extras").toFile()
    private val shell = ShellState(
        settings,
        object : DownloadRunner {
            override fun download(request: DownloadRequest) = Unit
            override fun cancel(fileId: Int) = Unit
            override fun pause(fileId: Int) = Unit
        },
        WatchedStore(WatchedStore.openDataStore(Files.createTempDirectory("tm-shellkeys-w").resolve(WatchedStore.FILE_NAME).toFile())),
        services = {
            DesktopExtras(
                prefs = DesktopPrefs(dir.resolve("desktop.properties")),
                updates = UpdateScheduler(settings.updatePrefs, check = { _, _ -> false }, onSkip = {}),
                watchCache = DesktopWatchCache(settings, { dir.resolve("cache") }),
            )
        },
    )

    /** A key down as the window gets it: [key], the character it [types] (0 for none), [shift] held. */
    private fun down(key: Key, types: Char? = null, shift: Boolean = false, ctrl: Boolean = false) =
        KeyEvent(
            key = key,
            type = KeyEventType.KeyDown,
            codePoint = types?.code ?: 0,
            isShiftPressed = shift,
            isCtrlPressed = ctrl,
        )

    @Test
    fun `question mark and F1 open the sheet and again close it`() {
        assertFalse(shell.shortcutsOpen)
        assertTrue(shell.onKey(down(Key.Slash, '?', shift = true)))
        assertTrue(shell.shortcutsOpen)
        assertTrue(shell.onKey(down(Key.F1)))
        assertFalse(shell.shortcutsOpen)
        assertTrue(shell.onKey(down(Key.F1)))
        assertTrue(shell.shortcutsOpen)
    }

    @Test
    fun `a slash is a search and never the sheet, on a US key and a German one`() {
        assertTrue(shell.onKey(down(Key.Slash, '/')))
        assertFalse(shell.shortcutsOpen)
        // German: "/" is Shift+7, and types "/".
        assertTrue(shell.onKey(down(Key.Seven, '/', shift = true)))
        assertFalse(shell.shortcutsOpen)
        // Another layout's "?" key opens the sheet by what it types.
        assertTrue(shell.onKey(down(Key.Unknown, '?', shift = true)))
        assertTrue(shell.shortcutsOpen)
    }

    @Test
    fun `Ctrl+F on a page with no search field opens the search across every chat`() {
        assertFalse(shell.paletteOpen)
        shell.focusSearch()
        assertTrue(shell.paletteOpen)
    }

    @Test
    fun `the sheet lists the shell's keys by OS and closes by its button`() = runComposeUiTest {
        shell.shortcutsOpen = true
        setContent {
            TmMaterialTheme(dark = true) {
                if (shell.shortcutsOpen) ShellShortcutsDialog(onClose = { shell.shortcutsOpen = false }, mac = false)
            }
        }
        waitForIdle()
        onNodeWithText("Keyboard shortcuts").assertExists()
        onNodeWithText("Ctrl+K").assertExists()
        onNodeWithText("?, F1").assertExists()
        onNodeWithText("Close").performClick()
        waitForIdle()
        assertEquals(0, onAllNodes(hasText("Keyboard shortcuts")).fetchSemanticsNodes().size)
        assertFalse(shell.shortcutsOpen)
    }

    @Test
    fun `the Mac spells the command key Cmd`() {
        val rows = ShellKeys.groups(mac = true).flatMap { it.second }.map { it.second }
        assertTrue(rows.any { it.startsWith("Cmd+") })
        assertTrue(rows.none { it.contains("Ctrl+") })
        val other = ShellKeys.groups(mac = false).flatMap { it.second }.map { it.second }
        assertTrue(other.any { it == "Ctrl+K" })
    }

    @Test
    fun `the title is the page or the video, and only the name while a profile is hidden`() {
        assertEquals("Home - TMPlayer", WindowTitle.of(hidden = false, page = "Home", video = null))
        assertEquals("Night Train - TMPlayer", WindowTitle.of(hidden = false, page = "Home", video = "Night Train"))
        assertEquals("TMPlayer", WindowTitle.of(hidden = true, page = "Home", video = "Night Train"))
        // A video of a profile no longer open (SEC2-08): not named.
        val a = com.tmplayer.data.AccountStamp(1L, null)
        val b = com.tmplayer.data.AccountStamp(2L, "pb")
        assertEquals("Night Train - TMPlayer", WindowTitle.of(false, "Home", "Night Train", videoOwner = a, current = a))
        assertEquals("TMPlayer", WindowTitle.of(false, "Home", "Night Train", videoOwner = a, current = b))
        assertEquals("TMPlayer", WindowTitle.of(hidden = false, page = null, video = " "))
    }

    @Test
    fun `the smallest window follows the screen down to 800 by 480`() {
        assertEquals(960 to 600, WindowFloor.minimum(1f, 1920, 1040))
        // 1366 by 768 with a taskbar, at Largest: no more than the screen holds.
        assertEquals(1248 to 728, WindowFloor.minimum(1.3f, 1366, 728))
        // A scaled-down screen: 1280 by 720 logical at 150 % is 853 by 480, held at the floor.
        assertEquals(853 to 480, WindowFloor.minimum(1f, 853, 440))
        assertEquals(800 to 480, WindowFloor.minimum(1f, 700, 300))
    }
}
