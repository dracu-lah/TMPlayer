package com.tmplayer.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.hasSetTextAction
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
import com.tmplayer.desktop.DesktopPrefs
import com.tmplayer.desktop.DesktopWatchCache
import com.tmplayer.ui.components.scrimDismiss
import com.tmplayer.ui.components.swallowClicks
import com.tmplayer.i18n.L
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
 * The review wave's fixes in the desktop's second chunk (R4): one box with its words, a dim and
 * the card on it that are no Tab stops, and a poster that keeps its buttons while one has focus.
 */
@OptIn(ExperimentalTestApi::class)
class ControlReviewR4Test {

    @Before
    fun shown() {
        FocusVisibility.visible = true
    }

    @After
    fun restore() {
        FocusVisibility.visible = true
    }

    private fun hasRole(role: Role) = SemanticsMatcher("role is $role") { it.config.getOrNull(SemanticsProperties.Role) == role }

    private fun toggle(state: ToggleableState) =
        SemanticsMatcher("toggle is $state") { it.config.getOrNull(SemanticsProperties.ToggleableState) == state }

    @Test
    fun `a check row is one checkbox, the words tick it and Space ticks it`() = runComposeUiTest {
        var on by mutableStateOf(false)
        setContent {
            TmMaterialTheme(dark = true) {
                Box(Modifier.size(400.dp, 100.dp)) { CheckRow(on, { on = it }, "Also delete downloads", Modifier.testTag("row")) }
            }
        }
        onNodeWithTag("row").assert(hasRole(Role.Checkbox)).assert(toggle(ToggleableState.Off))
        // The words, not the box, take the click.
        onNodeWithText("Also delete downloads").performClick()
        assertTrue(on)
        onNodeWithTag("row").assert(toggle(ToggleableState.On))
        onNodeWithTag("row").requestFocus()
        onNodeWithTag("row").assertIsFocused()
        onNode(isFocused()).performKeyInput { pressKey(Key.Spacebar) }
        assertFalse(on)
    }

    @Test
    fun `a dim closes on a click, the card on it does not, and the card is no button`() = runComposeUiTest {
        var closed = 0
        setContent {
            TmMaterialTheme(dark = true) {
                Box(Modifier.size(300.dp).background(Color.Black).testTag("dim").scrimDismiss { closed++ }) {
                    Box(Modifier.size(100.dp).background(Color.White).testTag("card").swallowClicks())
                }
            }
        }
        onNodeWithTag("card", useUnmergedTree = true).performClick()
        assertEquals("a click on the card is not a click on the dim", 0, closed)
        onNodeWithTag("dim").performClick()
        assertEquals(1, closed)
        val card = onNodeWithTag("card", useUnmergedTree = true).fetchSemanticsNode()
        assertFalse("a reader hears no button on the card", card.config.contains(SemanticsActions.OnClick))
    }

    // ---- a poster -----------------------------------------------------------------------------

    private val settings = SettingsStore(
        SettingsStore.openDataStore(Files.createTempDirectory("tm-r4").resolve(SettingsStore.FILE_NAME).toFile()),
    )
    private val extras = Files.createTempDirectory("tm-r4-extras").toFile()
    private val shell = ShellState(
        settings,
        object : DownloadRunner {
            override fun download(request: DownloadRequest) = Unit
            override fun cancel(fileId: Int) = Unit
            override fun pause(fileId: Int) = Unit
        },
        WatchedStore(WatchedStore.openDataStore(Files.createTempDirectory("tm-r4-watched").resolve(WatchedStore.FILE_NAME).toFile())),
        services = {
            DesktopExtras(
                prefs = DesktopPrefs(extras.resolve("desktop.properties")),
                updates = UpdateScheduler(settings.updatePrefs, check = { _, _ -> false }, onSkip = {}),
                watchCache = DesktopWatchCache(settings, { extras.resolve("cache") }),
            )
        },
    )

    private val video = MediaItem(
        chatId = 2, messageId = 1, fileId = 0, title = "The Long Road S01E01",
        sizeBytes = 300L * 1024 * 1024, durationSec = 1500, mimeType = "video/mp4",
        thumbnailFileId = 0, miniThumbnail = null, date = 0, fileName = "The Long Road S01E01.mkv",
    )

    @Test
    fun `a poster answers as a button, and Play and More stay while the keyboard is on one of them`() = runComposeUiTest {
        setContent {
            TmMaterialTheme(dark = true) {
                Box(Modifier.size(320.dp, 260.dp).background(Color.Black)) { MediaTile(shell, video, "Film Club") }
            }
        }
        val tile = onAllNodes(hasRole(Role.Button))
        assertTrue("the poster is a button", tile.fetchSemanticsNodes().isNotEmpty())
        tile[0].requestFocus()
        waitForIdle()
        onNode(hasContentDescription(L.playerPlay)).assertExists()
        val more = onNode(hasContentDescription(L.commonMore))
        more.assertExists()
        // The keyboard on the More button itself: the buttons must not vanish under it.
        more.requestFocus()
        waitForIdle()
        onNode(hasContentDescription(L.commonMore)).assertExists()
        onNode(hasContentDescription(L.playerPlay)).assertExists()
    }

    // ---- dialogs with a field -------------------------------------------------------------------

    @Test
    fun `the key dialog opens with the keyboard in its field and Enter saves the key`() = runComposeUiTest {
        val online = MetaFixture.install("on")
        var closed = 0
        setContent { TmMaterialTheme(dark = true) { MetadataKeyDialog(onClose = { closed++ }) } }
        waitForIdle()
        onNode(hasSetTextAction()).assertIsFocused()
        onNode(hasSetTextAction()).performTextInput("my-key-123")
        onNode(isFocused()).performKeyInput { pressKey(Key.Enter) }
        waitForIdle()
        assertEquals(1, closed)
        assertEquals("my-key-123", online.store.now.ownKey.trim())
    }

    @Test
    fun `the rename dialog opens with the keyboard in its field`() = runComposeUiTest {
        setContent { TmMaterialTheme(dark = true) { RenameDialog("Night Train", onSave = {}, onDismiss = {}) } }
        waitForIdle()
        onNode(hasSetTextAction()).assertIsFocused()
    }
}
