package com.tmplayer.desktop.player

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.test.waitUntilExactlyOneExists
import com.tmplayer.data.WatchLaterRecord
import com.tmplayer.data.WatchedStore
import com.tmplayer.i18n.L
import com.tmplayer.ui.browse.ListPickerDialog
import com.tmplayer.ui.i18n.ProvideStrings
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.nio.file.Files

/**
 * Esc on the desktop player's list picker (HX43) is the picker's alone: it must not
 * reach the player behind it, where Esc leaves fullscreen or the player, and the window's key route
 * leaves the keys alone while the picker is up ([PlayerKeyRoute.takesKey] with typing true).
 */
@OptIn(ExperimentalTestApi::class)
class ListDialogEscTest {

    private val dir = Files.createTempDirectory("tm-list-esc").toFile()
    private val store = WatchedStore(WatchedStore.openDataStore(dir.resolve(WatchedStore.FILE_NAME))).lists

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    private val record = WatchLaterRecord(
        chatId = -100, messageId = 4, fileId = 7, title = "Clip", fileName = "clip.mp4", chatTitle = "Chat",
        sizeBytes = 1_000, durationSec = 60, mimeType = "video/mp4", addedAt = 0,
    )

    @Test
    fun escInThePickerNeverReachesThePlayer() = runComposeUiTest {
        var playerSaw = 0
        var open by mutableStateOf(true)
        setContent {
            ProvideStrings {
                PlayerTheme {
                    // Stands for the player's focusable picture: it would act on Esc.
                    Box(
                        Modifier.fillMaxSize()
                            .onPreviewKeyEvent { if (it.type == KeyEventType.KeyDown && it.key == Key.Escape) playerSaw++; false }
                            .onKeyEvent { if (it.type == KeyEventType.KeyDown && it.key == Key.Escape) playerSaw++; false }
                            .focusable(),
                    )
                    if (open) ListPickerDialog(record, store, onMessage = {}, onDismiss = { open = false })
                }
            }
        }
        // The picker draws once the lists are read from the file.
        waitUntilExactlyOneExists(hasText(L.listsAddTo), 10_000)
        onNodeWithText(L.listsAddTo).assertExists()
        // The dialog is a root of its own: the key goes in there, as the keyboard would send it.
        onNodeWithText(L.commonCancel).requestFocus()
        onNodeWithText(L.commonCancel).performKeyInput { pressKey(Key.Escape) }
        waitForIdle()
        // Whether the picker's own Esc handling ran is the platform layer's (the test scene does not
        // run it); what matters here is that the key stayed in the picker's window.
        assertEquals("Esc did not reach the player", 0, playerSaw)
    }

    @Test
    fun theWindowsKeyRouteLeavesKeysAloneWhileThePickerHoldsTheKeyboard() {
        // PlayerScreen sets typing while a list picker is up (listPicking is part of typingUp).
        assertFalse(PlayerKeyRoute.takesKey(focusInside = false, typing = true, textInput = false, pageHasFocus = true))
        assertFalse(PlayerKeyRoute.takesKey(focusInside = false, typing = true, textInput = true, pageHasFocus = false))
    }
}
