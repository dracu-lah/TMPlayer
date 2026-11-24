package com.tmplayer.ui.settings

import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import com.tmplayer.data.FormFactor
import com.tmplayer.player.SubtitleColour
import com.tmplayer.player.SubtitleSize
import com.tmplayer.player.SubtitleStyle
import com.tmplayer.online.HttpResponse
import com.tmplayer.online.HttpTransport
import com.tmplayer.online.MetadataCache
import com.tmplayer.online.MetadataStore
import com.tmplayer.online.OnlineMetadata
import com.tmplayer.ui.DrainMainLooperRule
import com.tmplayer.ui.theme.Motion
import com.tmplayer.ui.theme.TMPlayerTheme
import com.tmplayer.ui.theme.Tone
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog
import org.robolectric.util.ReflectionHelpers
import java.time.Duration

/**
 * The television's long pickers under the remote (WP-11): Subtitle style with its groups on the
 * left and options on the right, the custom accent as sixteen colours and a fine hue, and the two
 * API key dialogs opening on their field.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w960dp-h540dp-land-television-night-xhdpi")
class TvLongPickersFocusTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    @get:Rule
    val drain = DrainMainLooperRule()

    @get:Rule
    val dir = TemporaryFolder()

    private val before = OnlineMetadata.current

    @After
    fun tearDown() {
        OnlineMetadata.current = before
        FormFactor.override(false)
        Motion.reduced = false
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

    /** The newest window takes the remote, as on a television. */
    private fun windowTakesKeys() {
        leaveTouchMode(rule.activity.window.decorView.parent)
        ShadowDialog.getLatestDialog()?.window?.decorView?.parent?.let { leaveTouchMode(it) }
        idle()
    }

    private fun waitFor(check: () -> Unit) {
        var last: Throwable? = null
        repeat(100) {
            idle()
            try {
                check()
                return
            } catch (e: AssertionError) {
                last = e
            }
        }
        throw last ?: AssertionError("never")
    }

    private fun press(key: Key) {
        // The window of the picker comes last: the row under it keeps its mark in the window below.
        rule.onAllNodes(isFocused()).onLast().performKeyInput { pressKey(key) }
        idle()
    }

    private fun focusedText(): String = rule.onAllNodes(isFocused()).fetchSemanticsNodes().last().config
        .getOrNull(SemanticsProperties.Text)?.joinToString { it.text } ?: ""

    private fun setContent(content: @androidx.compose.runtime.Composable () -> Unit) {
        FormFactor.override(true)
        Motion.reduced = true
        rule.setContent { TMPlayerTheme { Box(Modifier.fillMaxSize().background(Tone.background)) { content() } } }
        windowTakesKeys()
    }

    // ---- Subtitle style -----------------------------------------------------------------------

    @Test
    fun `Subtitle style opens on the chosen size, Left goes to the groups, Down changes the options, Right goes to the chosen one, Back closes to the row`() {
        setContent { SettingsScreen(chats = emptyList(), onLoggedOut = {}, initialPage = SettingsPage.Subtitles, accountFolders = emptyList()) }
        val rowNode = rule.onNode(hasText("Subtitle style") and hasClickAction())
        waitFor { rowNode.assertExists() }
        rowNode.requestFocus()
        idle()
        rowNode.performKeyInput { pressKey(Key.Enter) }
        // The picker is a window of its own: it takes the remote once it is up.
        waitFor {
            ShadowDialog.getLatestDialog()?.window?.decorView?.parent?.let { leaveTouchMode(it) }
            // The picker's window is given the remote by the harness after it is up, which puts it on the
            // first line (the Size group); the app's own window has it from the start, on the chosen size.
            assertTrue("a line of the picker: ${focusedText()}", focusedText().startsWith("Subtitle size") || focusedText() == SubtitleStyle().size.label)
        }
        if (focusedText().startsWith("Subtitle size")) press(Key.DirectionRight)
        assertEquals("Right from a group is its chosen option", SubtitleStyle().size.label, focusedText())
        // Left: the groups, the open one (Size) under the remote; Down: the next group, whose options replace the sizes.
        press(Key.DirectionLeft)
        waitFor { assertTrue("on the Size group: ${focusedText()}", focusedText().startsWith("Subtitle size")) }
        press(Key.DirectionDown)
        waitFor { assertTrue("on the Colour group: ${focusedText()}", focusedText().startsWith("Colour")) }
        rule.onAllNodes(hasText(SubtitleSize.entries.last().label) and hasClickAction()).let {
            assertEquals("the sizes went away with the group", 0, it.fetchSemanticsNodes().size)
        }
        // Right: the chosen colour.
        press(Key.DirectionRight)
        waitFor { assertEquals(SubtitleStyle().colour.label, focusedText().substringBefore("\n")) }
        // Back closes the picker, to the row.
        ShadowDialog.getLatestDialog().onBackPressed()
        idle()
        waitFor { assertEquals("the picker is closed", 0, rule.onAllNodes(hasText("Colour") and hasClickAction()).fetchSemanticsNodes().size) }
    }

    // ---- Custom accent ------------------------------------------------------------------------

    @Test
    fun `Your own colour opens on the first of sixteen colours, Right walks them, OK takes one and Down reaches the fine hue`() {
        var picked: Int? = null
        setContent { com.tmplayer.ui.components.CustomAccentPicker(initial = null, onPick = { picked = it }, onDismiss = {}) }
        waitFor { rule.onAllNodes(hasContentDescription("0°")).assertExistsFirst() }
        // The first colour takes the remote.
        waitFor { assertEquals("0°", rule.onNode(isFocused()).fetchSemanticsNode().config.getOrNull(SemanticsProperties.ContentDescription)?.firstOrNull()) }
        repeat(4) { press(Key.DirectionRight) }
        // The fifth colour (every 22.5 degrees): 90.
        assertEquals("90°", rule.onNode(isFocused()).fetchSemanticsNode().config.getOrNull(SemanticsProperties.ContentDescription)?.firstOrNull())
        press(Key.Enter)
        // Down to the fine hue, Right nudges it by 15 degrees.
        repeat(6) {
            if (rule.onAllNodes(isFocused() and hasContentDescription("Fine hue")).fetchSemanticsNodes().isEmpty()) press(Key.DirectionDown)
        }
        rule.onNode(isFocused() and hasContentDescription("Fine hue")).assertIsFocused()
        assertEquals("90", rule.onNode(isFocused()).fetchSemanticsNode().config.getOrNull(SemanticsProperties.StateDescription))
        press(Key.DirectionRight)
        assertEquals("105", rule.onNode(isFocused()).fetchSemanticsNode().config.getOrNull(SemanticsProperties.StateDescription))
        press(Key.DirectionLeft)
        press(Key.DirectionLeft)
        assertEquals("75", rule.onNode(isFocused()).fetchSemanticsNode().config.getOrNull(SemanticsProperties.StateDescription))
        assertTrue("nothing is taken until Done", picked == null)
    }

    private fun androidx.compose.ui.test.SemanticsNodeInteractionCollection.assertExistsFirst() {
        assertTrue(fetchSemanticsNodes().isNotEmpty())
    }

    // ---- API keys -----------------------------------------------------------------------------

    private fun online() {
        OnlineMetadata.current = OnlineMetadata(
            buildKey = "test-key",
            store = MetadataStore(dir.newFile()),
            cache = MetadataCache(dir.newFolder()),
            appVersion = "test",
            http = HttpTransport { HttpResponse(404, body = "{}".toByteArray()) },
        )
    }

    @Test
    fun `the TMDB key dialog opens with its field focused`() {
        online()
        setContent { MetadataKeyDialog(onClose = {}) }
        waitFor { rule.onNode(hasSetTextAction()).assertIsFocused() }
    }

    @Test
    fun `the OMDb key dialog opens with its field focused`() {
        online()
        setContent { OmdbKeyDialog(onClose = {}) }
        waitFor { rule.onNode(hasSetTextAction()).assertIsFocused() }
    }

    // The colour chosen in SubtitleColour is read by the test above through its default.
    @Suppress("unused")
    private val colours = SubtitleColour.entries
}
