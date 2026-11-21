package com.tmplayer.ui.components

import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import com.tmplayer.data.DownloadRequest
import com.tmplayer.data.FormFactor
import com.tmplayer.data.OfflineDownloads
import com.tmplayer.ui.DrainMainLooperRule
import com.tmplayer.ui.theme.TmMaterialTheme
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.util.ReflectionHelpers
import java.time.Duration

/**
 * Cancel on a running download asks first, on a phone and under a remote: "Stop this download?" says
 * what happens, focus starts on Keep downloading, Keep puts the remote back on the Cancel button,
 * Stop cancels once, a second press while it is open changes nothing, and a download that finished
 * or went meanwhile closes the question without cancelling anything.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w960dp-h540dp-land-television-night-xhdpi")
class StopDownloadConfirmTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    /** A state write left on the looper must not silence Compose for the next test (HX92). */
    @get:Rule
    val drain = DrainMainLooperRule()

    @After
    fun tearDown() {
        FormFactor.override(false)
    }

    private fun request(id: Int) = DownloadRequest(
        fileId = id, title = "Episode $id", sizeBytes = 1000, chatId = 1, messageId = id.toLong(),
        chatTitle = "Chat", durationSec = 60, mimeType = "video/mp4", fileName = "e$id.mp4",
    )

    private fun row(id: Int, stage: OfflineDownloads.Stage) = OfflineDownloads.Progress(request(id), 400, 1000, stage)

    private val queue = MutableStateFlow(mapOf(1 to row(1, OfflineDownloads.Stage.Running), 2 to row(2, OfflineDownloads.Stage.Paused)))
    private val stopped = mutableListOf<Int>()

    /** Two Cancel buttons, one per row, the first holding the remote; "Twice" presses the first two times. */
    private fun show() {
        rule.setContent {
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
        idle()
    }

    private fun idle() {
        androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications()
        repeat(40) {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(25))
            Thread.sleep(2)
        }
        rule.waitForIdle()
    }

    private fun leaveTouchMode(root: Any?) {
        ReflectionHelpers.callInstanceMethod<Any>(root, "ensureTouchMode", ReflectionHelpers.ClassParameter.from(Boolean::class.javaPrimitiveType, false))
        ReflectionHelpers.callInstanceMethod<Any>(root, "windowFocusChanged", ReflectionHelpers.ClassParameter.from(Boolean::class.javaPrimitiveType, true))
    }

    /** The newest window (the prompt's, or the activity's once it is gone) takes the remote. */
    private fun windowTakesKeys() {
        leaveTouchMode(rule.activity.window.decorView.parent)
        org.robolectric.shadows.ShadowDialog.getLatestDialog()?.window?.decorView?.parent?.let { leaveTouchMode(it) }
        idle()
    }

    /** On [on] (the prompt's button, while the prompt is up and the page keeps its own focus under it) or the one focused node. */
    private fun press(key: Key, on: String? = null) {
        rule.onNode(if (on == null) isFocused() else hasText(on) and isFocused()).performKeyInput { pressKey(key) }
        idle()
    }

    private fun waitFor(text: String, present: Boolean = true) {
        repeat(100) {
            idle()
            if ((rule.onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty()) == present) return
        }
        error((if (present) "never showed: " else "never went: ") + text)
    }

    private fun waitFocused(text: String) {
        repeat(100) {
            idle()
            if (runCatching { rule.onNode(hasText(text)).assertIsFocused() }.isSuccess) return
        }
        rule.onNode(hasText(text)).assertIsFocused()
    }

    @Test
    fun `Cancel on a running download asks, and the remote starts on Keep downloading`() {
        FormFactor.override(true)
        show()
        windowTakesKeys()
        waitFocused("Cancel one")
        press(Key.DirectionCenter)
        waitFor("Stop this download?")
        windowTakesKeys()
        waitFocused("Keep downloading")
        assertEquals("nothing was stopped by asking", emptyList<Int>(), stopped)
    }

    @Test
    fun `Keep downloading cancels nothing and the remote is back on the Cancel button`() {
        FormFactor.override(true)
        show()
        windowTakesKeys()
        press(Key.DirectionCenter)
        waitFor("Stop this download?")
        windowTakesKeys()
        waitFocused("Keep downloading")
        press(Key.DirectionCenter, on = "Keep downloading")
        waitFor("Stop this download?", present = false)
        windowTakesKeys()
        waitFocused("Cancel one")
        assertEquals(emptyList<Int>(), stopped)
    }

    @Test
    fun `Stop download cancels that download once`() {
        FormFactor.override(true)
        show()
        windowTakesKeys()
        press(Key.DirectionCenter)
        waitFor("Stop this download?")
        windowTakesKeys()
        waitFocused("Keep downloading")
        press(Key.DirectionRight, on = "Keep downloading")
        waitFocused("Stop download")
        press(Key.DirectionCenter, on = "Stop download")
        waitFor("Stop this download?", present = false)
        assertEquals(listOf(1), stopped)
        windowTakesKeys()
        waitFocused("Cancel one")
    }

    @Test
    fun `a waiting download is cancelled at once, with no question`() {
        FormFactor.override(true)
        show()
        windowTakesKeys()
        press(Key.DirectionDown)
        waitFocused("Cancel two")
        press(Key.DirectionCenter)
        assertEquals(listOf(2), stopped)
        rule.onAllNodes(hasText("Stop this download?")).fetchSemanticsNodes().let { assertEquals(0, it.size) }
    }

    @Test
    fun `a second press while the question is open asks once`() {
        FormFactor.override(true)
        show()
        windowTakesKeys()
        press(Key.DirectionDown)
        press(Key.DirectionDown)
        waitFocused("Twice")
        press(Key.DirectionCenter)
        waitFor("Stop this download?")
        assertEquals(1, rule.onAllNodes(hasText("Stop this download?")).fetchSemanticsNodes().size)
        assertEquals(emptyList<Int>(), stopped)
    }

    @Test
    fun `a download that finished while the question was open closes it and cancels nothing`() {
        FormFactor.override(true)
        show()
        windowTakesKeys()
        press(Key.DirectionCenter)
        waitFor("Stop this download?")
        // Finished (or cancelled from the notification) before anyone answered.
        queue.value = queue.value - 1
        waitFor("Stop this download?", present = false)
        assertEquals(emptyList<Int>(), stopped)
    }

    @Test
    fun `a stage that changed while the question was open cancels the row as it is then`() {
        FormFactor.override(true)
        show()
        windowTakesKeys()
        press(Key.DirectionCenter)
        waitFor("Stop this download?")
        queue.value = queue.value + (1 to row(1, OfflineDownloads.Stage.Paused))
        idle()
        windowTakesKeys()
        waitFocused("Keep downloading")
        press(Key.DirectionRight, on = "Keep downloading")
        press(Key.DirectionCenter, on = "Stop download")
        assertEquals(listOf(1), stopped)
    }

    @Test
    @Config(sdk = [35], qualifiers = "w411dp-h891dp-port-night-xhdpi")
    fun `on a phone the question is Material's dialog and Stop download stops once`() {
        FormFactor.override(false)
        show()
        rule.onNode(hasText("Cancel one")).performClick()
        waitFor("Stop this download?")
        rule.onNode(hasText("Keep downloading")).performClick()
        waitFor("Stop this download?", present = false)
        assertEquals(emptyList<Int>(), stopped)
        rule.onNode(hasText("Cancel one")).performClick()
        waitFor("Stop this download?")
        rule.onNode(hasText("Stop download")).performClick()
        waitFor("Stop this download?", present = false)
        assertEquals(listOf(1), stopped)
    }
}
