package com.tmplayer.ui.components

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.unit.dp
import com.tmplayer.data.FormFactor
import com.tmplayer.data.WorkStatus
import com.tmplayer.i18n.L
import com.tmplayer.ui.DrainMainLooperRule
import com.tmplayer.ui.theme.TmMaterialTheme
import com.tmplayer.ui.theme.Tone
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * The work status pill and line on a television (WP-91), by remote alone: the pill appearing takes
 * no focus and moves none, Try again on a failure kept line is reached with Down in reading order,
 * and at 960 x 540 dp both sit inside the screen. Frame in `build/device-test/review/`.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w960dp-h540dp-land-television-night-mdpi")
class WorkStatusTvFocusTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    /** A state write left on the looper must not silence Compose for the next test (HX92). */
    @get:Rule
    val drain = DrainMainLooperRule()

    @After
    fun tearDown() {
        FormFactor.override(false)
    }

    private val now = 1_800_000_000_000L

    /** A window the remote works in: out of touch mode and holding focus, as on a television. */
    private fun remoteWindow() {
        val viewRoot = rule.activity.window.decorView.parent
        org.robolectric.util.ReflectionHelpers.callInstanceMethod<Any>(
            viewRoot, "ensureTouchMode",
            org.robolectric.util.ReflectionHelpers.ClassParameter.from(Boolean::class.javaPrimitiveType, false),
        )
        org.robolectric.util.ReflectionHelpers.callInstanceMethod<Any>(
            viewRoot, "windowFocusChanged",
            org.robolectric.util.ReflectionHelpers.ClassParameter.from(Boolean::class.javaPrimitiveType, true),
        )
        rule.waitForIdle()
    }

    @Test
    fun `the pill takes no focus and Try again is reached with Down`() {
        FormFactor.override(true)
        var pill by mutableStateOf<WorkStatus>(WorkStatus.Idle)
        var line by mutableStateOf<WorkStatus>(WorkStatus.Idle)
        var retries = 0
        rule.setContent {
            TmMaterialTheme(dark = true) {
                Box(Modifier.fillMaxSize().background(Tone.background).testTag("screen")) {
                    Column(Modifier.padding(48.dp)) {
                        Button(onClick = {}) { Text("Last row") }
                        WorkStatusLine(
                            ShownWork(line),
                            onRetry = {
                                retries++
                                line = WorkStatus.Syncing()
                            },
                            now = { now },
                            modifier = Modifier.testTag("line"),
                        )
                    }
                    WorkStatusPill(ShownWork(pill), now = { now }, modifier = Modifier.align(Alignment.TopEnd).padding(24.dp).testTag("pill"))
                }
            }
        }
        remoteWindow()
        val row = rule.onNode(hasText("Last row") and hasClickAction())
        row.requestFocus()
        row.assertIsFocused()

        // Updating shows in the corner: the remote stays where it was.
        pill = WorkStatus.Syncing()
        rule.waitForIdle()
        row.assertIsFocused()

        // The refresh fails with the content kept: the line comes in under the last row, focus stays.
        pill = WorkStatus.Idle
        line = WorkStatus.Failed("Telegram didn't answer.", keptAt = now - 60_000)
        rule.waitForIdle()
        row.assertIsFocused()

        row.performKeyInput { pressKey(Key.DirectionDown) }
        rule.waitForIdle()
        val retry = rule.onNode(hasText(L.commonTryAgain) and hasClickAction())
        retry.assertIsFocused()
        retry.performKeyInput { pressKey(Key.DirectionCenter) }
        rule.waitForIdle()
        assertTrue("OK on Try again retries", retries == 1)

        // Try again went with the press: the remote is on the line's words, not lost.
        rule.onNode(hasContentDescription(L.stateUpdating)).assertIsFocused()

        // It fails again: back on Try again.
        line = WorkStatus.Failed("Telegram didn't answer.", keptAt = now - 60_000)
        rule.waitForIdle()
        retry.assertIsFocused()

        // Pressed again, and this time the work is done at once: the words hold the remote and say so.
        retry.performKeyInput { pressKey(Key.DirectionCenter) }
        rule.waitForIdle()
        line = WorkStatus.Idle
        rule.waitForIdle()
        rule.onNode(hasContentDescription(L.stateUpdated)).assertIsFocused()

        // Up moves on, and the line, done, leaves.
        rule.onNode(hasContentDescription(L.stateUpdated)).performKeyInput { pressKey(Key.DirectionUp) }
        rule.waitForIdle()
        row.assertIsFocused()
        rule.onAllNodes(hasContentDescription(L.stateUpdated)).assertCountEquals(0)

        // Both inside the 960 x 540 dp screen.
        line = WorkStatus.Failed("Telegram didn't answer.", keptAt = now - 60_000)
        pill = WorkStatus.RateLimited(now + 42_000)
        rule.waitForIdle()
        val screen = rule.onNodeWithTag("screen").getBoundsInRoot()
        for (tag in listOf("pill", "line")) {
            val box = rule.onNodeWithTag(tag).getBoundsInRoot()
            assertTrue("$tag inside the screen: $box in $screen", box.left >= screen.left && box.right <= screen.right && box.top >= screen.top && box.bottom <= screen.bottom)
        }
        val frame = rule.onRoot().captureToImage().asAndroidBitmap()
        val file = File("../build/device-test/review/work-status-tv.png").absoluteFile
        file.parentFile?.mkdirs()
        file.outputStream().use { frame.compress(Bitmap.CompressFormat.PNG, 100, it) }
        assertTrue(file.length() > 0)
    }

    @Test
    fun `with a host to hand it to, Retry returns focus to the last row`() {
        FormFactor.override(true)
        var line by mutableStateOf<WorkStatus>(WorkStatus.Failed("Telegram didn't answer.", keptAt = now - 60_000))
        val lastRow = FocusRequester()
        rule.setContent {
            TmMaterialTheme(dark = true) {
                Column(Modifier.fillMaxSize().background(Tone.background).padding(48.dp)) {
                    Button(onClick = {}, modifier = Modifier.focusRequester(lastRow)) { Text("Last row") }
                    WorkStatusLine(
                        ShownWork(line),
                        onRetry = { line = WorkStatus.Idle },
                        now = { now },
                        focusAfterRetry = lastRow,
                    )
                }
            }
        }
        remoteWindow()
        val retry = rule.onNode(hasText(L.commonTryAgain) and hasClickAction())
        retry.requestFocus()
        retry.assertIsFocused()
        retry.performKeyInput { pressKey(Key.DirectionCenter) }
        rule.waitForIdle()
        rule.onNode(hasText("Last row") and hasClickAction()).assertIsFocused()
    }
}
