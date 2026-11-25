package com.tmplayer.desktop.ui

import com.tmplayer.desktop.os.OsInfo
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.test.withKeyDown
import androidx.compose.ui.unit.dp
import com.tmplayer.data.DownloadRequest
import com.tmplayer.data.DownloadRunner
import com.tmplayer.data.OfflineHome
import com.tmplayer.data.ResumeRecord
import com.tmplayer.data.SettingsStore
import com.tmplayer.data.WatchedStore
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

/**
 * The window's content behind the profiles' gate (HX88, review D3 and P3): Home's offline downloads
 * are the open profile's only, never the last one's after a switch, and the window's shortcuts leave
 * a hidden shell alone while Who's watching? or a PIN is drawn in its place.
 */
@OptIn(ExperimentalTestApi::class)
class ShellBehindGateTest {

    private fun record(id: Long, title: String) = ResumeRecord(
        chatId = 1, messageId = id, fileId = id.toInt(), title = title, chatTitle = "Chat", sizeBytes = 10,
        durationSec = 60, positionMs = 0, durationMs = 60_000, updatedAt = 0, localPath = "/d/$title.mkv",
    )

    @Test
    fun `offline downloads are read again for the next profile, the last one's never shown`() = runComposeUiTest {
        val ofA = listOf(record(1, "Harbour Notes"))
        val ofB = CompletableDeferred<List<ResumeRecord>>()
        var owner by mutableStateOf("a")
        var seen: OfflineHome? = null
        setContent {
            val reads: Map<String, suspend () -> List<ResumeRecord>> = mapOf("a" to { ofA }, "b" to { ofB.await() })
            seen = rememberOfflineHome(offline = true, owner = owner, read = reads.getValue(owner))
        }
        waitUntil(timeoutMillis = 5_000) { seen?.downloads == ofA }
        owner = "b"
        waitForIdle()
        assertTrue("nothing of the last profile", seen!!.downloads.isEmpty())
        assertFalse("not settled until the new read is in", seen!!.settled)
        val mine = listOf(record(2, "Lighthouse"))
        ofB.complete(mine)
        waitUntil(timeoutMillis = 5_000) { seen?.downloads == mine }
        assertTrue(seen!!.settled)
    }

    private val shell = ShellState(
        SettingsStore(SettingsStore.openDataStore(Files.createTempDirectory("tm-gate-keys").resolve(SettingsStore.FILE_NAME).toFile())),
        object : DownloadRunner {
            override fun download(request: DownloadRequest) = Unit
            override fun cancel(fileId: Int) = Unit
            override fun pause(fileId: Int) = Unit
        },
        WatchedStore(WatchedStore.openDataStore(Files.createTempDirectory("tm-gate-keys-watched").resolve(WatchedStore.FILE_NAME).toFile())),
        services = { error("not used here") },
    )

    @Test
    fun `the window's shortcuts leave the shell alone while the gate shows, its own keys still work`() = runComposeUiTest {
        // The shell's command key: Cmd on a Mac, Ctrl elsewhere.
        val command = if (OsInfo.isMac) Key.MetaLeft else Key.CtrlLeft
        var quits = 0
        shell.quit = { quits++ }
        setContent {
            Box(Modifier.size(200.dp).testTag("window").onPreviewKeyEvent(shell::onPreviewKey).focusable())
        }
        val window = onNodeWithTag("window")
        window.requestFocus()

        shell.shellShown = false
        window.performKeyInput { withKeyDown(command) { pressKey(Key.K) } }
        window.performKeyInput { withKeyDown(command) { pressKey(Key.Comma) } }
        window.performKeyInput { withKeyDown(command) { pressKey(Key.J) } }
        assertFalse(shell.paletteOpen)
        assertEquals(Destination.Home, shell.destination)
        window.performKeyInput { withKeyDown(command) { pressKey(Key.Q) } }
        assertEquals(1, quits)

        // Shown again: the shortcuts are the shell's as before.
        shell.shellShown = true
        window.performKeyInput { withKeyDown(command) { pressKey(Key.K) } }
        assertTrue(shell.paletteOpen)
    }
}
