package com.tmplayer.desktop.profiles

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.runComposeUiTest
import com.tmplayer.data.ProfileUnlock
import com.tmplayer.i18n.L
import com.tmplayer.ui.lock.ProfileFlow
import com.tmplayer.ui.lock.profileShellShown
import com.tmplayer.ui.nav.BackStack
import com.tmplayer.ui.nav.LocalBackStack
import com.tmplayer.ui.nav.backInput
import com.tmplayer.ui.theme.TmMaterialTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick

/**
 * The profiles on the desktop (HX88, B2): the gate the window draws around its shell, driven by
 * the keyboard as a desktop is ([DesktopProfilesGate]). With one profile nothing changes: the
 * shell is all there is and the
 * first Tab lands on the shell's own first control. With two, Who's watching? takes Tab, the arrows
 * and Enter; the PIN pad takes the digits typed; Esc closes what the menu opened.
 */
@OptIn(ExperimentalTestApi::class)
class DesktopProfilesGateTest {
    private val kits = ArrayList<DesktopProfileKit>()

    private fun kit(vararg names: String) = DesktopProfileKit(*names).also { kits += it }

    @After
    fun tearDown() {
        kits.forEach { it.close() }
        ProfileUnlock.lock()
    }

    /** The window as the desktop draws it: a back stack Esc unwinds, the gate, the shell in it. */
    private fun ComposeUiTest.window(flow: ProfileFlow?, shown: (Boolean) -> Unit = {}) {
        val back = BackStack()
        setContent {
            TmMaterialTheme(dark = true) {
                CompositionLocalProvider(LocalBackStack provides back) {
                    Box(Modifier.fillMaxSize().backInput(back)) {
                        shown(profileShellShown(flow))
                        DesktopProfilesGate(flow, signInStep = false) { Shell() }
                    }
                }
            }
        }
        waitForIdle()
    }

    @Composable
    private fun Shell() {
        Column {
            Button(onClick = {}) { Text(SHELL_FIRST) }
            Button(onClick = {}) { Text("Chats") }
        }
    }

    private fun ComposeUiTest.press(key: Key) {
        onRoot().performKeyInput { pressKey(key) }
        waitForIdle()
    }

    private fun ComposeUiTest.exists(text: String) = onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().isNotEmpty()

    @Test
    fun `one profile launches straight to the shell with no picker and no new focus stop`() {
        for (flow in listOf(null, kit("Asha").flow())) runComposeUiTest {
            var shown = false
            window(flow) { shown = it }
            assertFalse("no picker with one profile", exists(L.profilesWhoIsWatching))
            assertTrue(exists(SHELL_FIRST))
            assertTrue("the shell is what shows", shown)
            // The first Tab is the shell's own first control, as it was before profiles.
            press(Key.Tab)
            onNode(isFocused()).assert(hasText(SHELL_FIRST))
        }
    }

    @Test
    fun `two profiles show who is watching, walked with Tab and the arrows, opened with Enter`() {
        val kit = kit("Asha", "Ben")
        val flow = kit.flow()
        runComposeUiTest {
            var shown = true
            window(flow) { shown = it }
            assertTrue(exists(L.profilesWhoIsWatching))
            assertFalse("the shell waits behind the picker", exists(SHELL_FIRST))
            assertFalse(shown)
            press(Key.Tab)
            onNode(isFocused()).assert(hasText("Asha", substring = true))
            press(Key.DirectionRight)
            onNode(isFocused()).assert(hasText("Ben", substring = true))
            press(Key.Enter)
            waitUntil(timeoutMillis = 5_000) { exists(SHELL_FIRST) }
            assertEquals("Ben's profile is the open one", kit.ids[1], kit.switcher.profiles.value.activeId)
            assertFalse(exists(L.profilesWhoIsWatching))
            assertTrue(shown)
        }
    }

    @Test
    fun `the PIN pad takes the digits typed on the keyboard`() {
        val kit = kit("Asha")
        kit.pin(0, "2580")
        val flow = kit.flow()
        runComposeUiTest {
            window(flow)
            // One profile with a PIN: its PIN before anything of it, never the picker.
            assertTrue(exists(L.profilesPinFor(name = "Asha")))
            assertFalse(exists(L.profilesWhoIsWatching))
            assertFalse(exists(SHELL_FIRST))
            for (key in listOf(Key.Two, Key.Five, Key.Eight, Key.Zero)) press(key)
            waitUntil(timeoutMillis = 5_000) { exists(SHELL_FIRST) }
            assertEquals(kit.ids[0], ProfileUnlock.unlockedId.value)
        }
    }

    @Test
    fun `a wrong PIN typed keeps the profile shut`() {
        val kit = kit("Asha")
        kit.pin(0, "2580")
        runComposeUiTest {
            window(kit.flow())
            for (key in listOf(Key.One, Key.One, Key.One, Key.One)) press(key)
            mainClock.advanceTimeBy(1_000)
            waitForIdle()
            assertFalse(exists(SHELL_FIRST))
            assertEquals(null, ProfileUnlock.unlockedId.value)
        }
    }

    @Test
    fun `Esc closes a PIN pad and the picker the menu opened`() {
        val kit = kit("Asha", "Ben")
        kit.pin(1, "2580")
        val flow = kit.flow()
        runComposeUiTest {
            window(flow)
            // Into Asha's (no PIN), then the menu's Switch profile.
            press(Key.Tab)
            press(Key.Enter)
            waitUntil(timeoutMillis = 5_000) { exists(SHELL_FIRST) }
            runOnIdle { flow.ask() }
            waitForIdle()
            assertTrue(exists(L.profilesWhoIsWatching))
            // The menu's picker puts the focus on the open profile's tile; Ben has a PIN: his pad,
            // and Esc goes back to the row.
            onNode(isFocused()).assert(hasText("Asha", substring = true))
            press(Key.DirectionRight)
            press(Key.Enter)
            waitUntil(timeoutMillis = 5_000) { exists(L.profilesPinFor(name = "Ben")) }
            press(Key.Escape)
            waitUntil(timeoutMillis = 5_000) { !exists(L.profilesPinFor(name = "Ben")) }
            assertTrue(exists(L.profilesWhoIsWatching))
            // Esc on the row the menu opened: back to the open profile as it was.
            press(Key.Escape)
            waitUntil(timeoutMillis = 5_000) { exists(SHELL_FIRST) }
            assertEquals(kit.ids[0], kit.switcher.profiles.value.activeId)
        }
    }

    @Test
    fun `Esc closes the options panel`() {
        val kit = kit("Asha", "Ben")
        val flow = kit.flow()
        runComposeUiTest {
            window(flow)
            val tile = flow.model(kit.switcher.profiles.value, false).tiles.first()
            runOnIdle { flow.options(tile) }
            waitForIdle()
            assertTrue(exists(L.profilesRename))
            press(Key.Escape)
            waitUntil(timeoutMillis = 5_000) { !exists(L.profilesRename) }
            assertTrue(exists(L.profilesWhoIsWatching))
        }
    }

    private companion object {
        const val SHELL_FIRST = "Home"
    }

    @Test
    fun `a list picker open when the profile locks goes with the shell and does not come back`() {
        val kit = kit("Asha")
        kit.pin(0, "2580")
        val lists = com.tmplayer.data.WatchedStore(
            com.tmplayer.data.WatchedStore.openDataStore(
                java.nio.file.Files.createTempDirectory("tm-gate-lists").resolve(com.tmplayer.data.WatchedStore.FILE_NAME).toFile(),
            ),
        ).lists
        val record = com.tmplayer.data.WatchLaterRecord(
            chatId = 1, messageId = 2, fileId = 3, title = "Harbour Notes", fileName = "Harbour Notes.mkv", chatTitle = "Chat",
            sizeBytes = 10, durationSec = 60, mimeType = "video/x-matroska", addedAt = 0,
        )
        val flow = kit.flow()
        runComposeUiTest {
            setContent {
                TmMaterialTheme(dark = true) {
                    CompositionLocalProvider(LocalBackStack provides BackStack()) {
                        com.tmplayer.desktop.ui.GatedShell(flow, signInStep = false, lists = lists, onMessage = {}, onOpenList = {}) {
                            val pick = com.tmplayer.ui.browse.LocalListPicker.current
                            Button(onClick = { pick?.invoke(record) }) { Text(SHELL_FIRST) }
                        }
                    }
                }
            }
            waitForIdle()
            for (key in listOf(Key.Two, Key.Five, Key.Eight, Key.Zero)) press(key)
            waitUntil(timeoutMillis = 5_000) { exists(SHELL_FIRST) }
            onNodeWithText(SHELL_FIRST).performClick()
            waitUntil(timeoutMillis = 5_000) { exists(L.listsAddTo) }
            assertTrue(exists("Harbour Notes"))

            // Away more than a minute: the PIN pad, and nothing of the picker over it.
            ProfileUnlock.lock()
            waitUntil(timeoutMillis = 5_000) { exists(L.profilesPinFor(name = "Asha")) }
            assertFalse(exists(L.listsAddTo))
            assertFalse(exists("Harbour Notes"))

            // Unlocked again: the shell, with the picker closed.
            for (key in listOf(Key.Two, Key.Five, Key.Eight, Key.Zero)) press(key)
            waitUntil(timeoutMillis = 5_000) { exists(SHELL_FIRST) }
            assertFalse(exists(L.listsAddTo))
        }
    }
}
