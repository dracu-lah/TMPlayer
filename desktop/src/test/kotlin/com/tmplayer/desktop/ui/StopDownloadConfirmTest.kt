package com.tmplayer.desktop.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.runComposeUiTest
import com.tmplayer.data.DownloadRequest
import com.tmplayer.data.OfflineDownloads
import com.tmplayer.ui.theme.TmMaterialTheme
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Cancel on a running download asks first on the desktop too ([rememberStopDownload]): the prompt
 * says what happens and starts on Keep downloading (so Enter pressed out of habit keeps it), Keep
 * leaves the Cancel button focused, Stop cancels once, a waiting download goes at once, a second
 * press while it is open asks once, and a download that finished meanwhile closes the question.
 */
@OptIn(ExperimentalTestApi::class)
class StopDownloadConfirmTest {

    private fun request(id: Int) = DownloadRequest(
        fileId = id, title = "Episode $id", sizeBytes = 1000, chatId = 1, messageId = id.toLong(),
        chatTitle = "Chat", durationSec = 60, mimeType = "video/mp4", fileName = "e$id.mp4",
    )

    private fun row(id: Int, stage: OfflineDownloads.Stage) = OfflineDownloads.Progress(request(id), 400, 1000, stage)

    private val queue = MutableStateFlow(mapOf(1 to row(1, OfflineDownloads.Stage.Running), 2 to row(2, OfflineDownloads.Stage.Paused)))
    private val stopped = mutableListOf<Int>()

    private fun androidx.compose.ui.test.ComposeUiTest.show() {
        setContent {
            TmMaterialTheme(dark = true) {
                val ask = rememberStopDownload(active = queue) { stopped += it.fileId }
                val first = remember { FocusRequester() }
                LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
                Column {
                    Button(onClick = { queue.value[1]?.let(ask) }, modifier = Modifier.focusRequester(first)) { Text("Cancel one") }
                    Button(onClick = { queue.value[2]?.let(ask) }) { Text("Cancel two") }
                    Button(onClick = { queue.value[1]?.let { ask(it); ask(it) } }) { Text("Twice") }
                }
            }
        }
        waitForIdle()
    }

    private fun asking(test: androidx.compose.ui.test.ComposeUiTest) =
        test.onAllNodes(hasText("Stop this download?")).fetchSemanticsNodes().size

    @Test
    fun `Cancel on a running download asks, starting on Keep downloading`() = runComposeUiTest {
        show()
        onNodeWithText("Cancel one").performClick()
        waitForIdle()
        assertEquals(1, asking(this))
        onNodeWithText("Keep downloading").assertIsFocused()
        assertEquals(emptyList<Int>(), stopped)
    }

    @Test
    fun `Enter on Keep downloading cancels nothing`() = runComposeUiTest {
        show()
        onNodeWithText("Cancel one").performClick()
        waitForIdle()
        onNodeWithText("Keep downloading").performKeyInput { pressKey(Key.Enter) }
        waitForIdle()
        assertEquals(0, asking(this))
        assertEquals(emptyList<Int>(), stopped)
    }

    @Test
    fun `Stop download cancels that download once`() = runComposeUiTest {
        show()
        onNodeWithText("Cancel one").performClick()
        waitForIdle()
        onNodeWithText("Stop download").performClick()
        waitForIdle()
        assertEquals(0, asking(this))
        assertEquals(listOf(1), stopped)
    }

    @Test
    fun `a waiting download is cancelled at once`() = runComposeUiTest {
        show()
        onNodeWithText("Cancel two").performClick()
        waitForIdle()
        assertEquals(0, asking(this))
        assertEquals(listOf(2), stopped)
    }

    @Test
    fun `a second press while the question is open asks once`() = runComposeUiTest {
        show()
        onNodeWithText("Twice").performClick()
        waitForIdle()
        assertEquals(1, asking(this))
        assertEquals(emptyList<Int>(), stopped)
    }

    @Test
    fun `a download that finished while the question was open closes it and cancels nothing`() = runComposeUiTest {
        show()
        onNodeWithText("Cancel one").performClick()
        waitForIdle()
        assertEquals(1, asking(this))
        queue.value = queue.value - 1
        waitForIdle()
        assertEquals(0, asking(this))
        assertEquals(emptyList<Int>(), stopped)
    }
}
