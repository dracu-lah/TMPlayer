package com.tmplayer.player

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.text.TextLayoutResult
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import com.tmplayer.R
import com.tmplayer.data.FormFactor
import com.tmplayer.i18n.L
import com.tmplayer.ui.DrainMainLooperRule
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog
import java.io.File
import java.time.Duration

/**
 * The subtitles picker's touch controls at twice the system font on the narrowest phone (R8):
 * Earlier and Later, the timing figure, the segments' names and Default look each fit their
 * control, wrapping to a second line or shrinking a step rather than being cut mid letter as the
 * fixed 48 dp buttons and single lines did. Drawn to `build/device-test/review/r8-*.png`.
 */
@OptIn(ExperimentalTestApi::class, UnstableApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TouchAdjustFontTest {

    /** A state write left on the looper must not silence Compose for the next test (HX92). */
    @get:Rule
    val drain = DrainMainLooperRule()

    @get:Rule
    val rule = createEmptyComposeRule()

    @After
    fun tearDown() {
        FormFactor.override(false)
        RuntimeEnvironment.setFontScale(1f)
    }

    private fun idle() {
        androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications()
        repeat(40) { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(25)) }
        rule.waitForIdle()
    }

    private fun open(fontScale: Float): ChatSubtitlesFocusTest.Host {
        FormFactor.override(false)
        RuntimeEnvironment.setFontScale(fontScale)
        val controller = Robolectric.buildActivity(ChatSubtitlesFocusTest.Host::class.java).create()
        val activity = controller.get()
        activity.setTheme(R.style.Theme_TMPlayer_Player)
        activity.setContentView(R.layout.activity_player)
        controller.start().resume().visible()
        TrackPickerFragment.show(activity.supportFragmentManager, R.id.overlay_container, C.TRACK_TYPE_TEXT)
        idle()
        return activity
    }

    private fun shot(activity: ChatSubtitlesFocusTest.Host, name: String) {
        val root = activity.window.decorView
        val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        root.draw(canvas)
        ShadowDialog.getLatestDialog()?.window?.decorView?.draw(canvas)
        val file = File("../build/device-test/review/$name").absoluteFile
        file.parentFile?.mkdirs()
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    /** The words of the touch controls, every one of which has to fit whole. */
    private fun controlWords(): List<String> =
        listOf(L.tracksEarlier, L.tracksLater, SyncDelays.label(0L), L.tracksDelayNone, L.subtitlesDefaultLook) +
            SubtitleSize.entries.map { it.label } +
            SubtitlePosition.entries.map { it.label }

    private fun assertWordsFit() {
        val cut = mutableListOf<String>()
        for (word in controlWords().distinct()) {
            val nodes = rule.onAllNodesWithText(word).fetchSemanticsNodes()
            assertTrue("\"$word\" is not on the panel", nodes.isNotEmpty())
            for (node in nodes) {
                val layouts = mutableListOf<TextLayoutResult>()
                node.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(layouts)
                if (layouts.any { it.hasVisualOverflow }) cut += word
                // Not broken inside a word either: no more lines than the words it has.
                val words = word.trim().split(Regex("\\s+")).size
                if (layouts.any { it.lineCount > words }) cut += "$word (broken inside a word)"
            }
        }
        assertTrue("cut off at this font: $cut", cut.isEmpty())
    }

    @Test
    @Config(sdk = [35], qualifiers = "w360dp-h740dp-port-night-xhdpi")
    fun `the narrowest phone at twice the font keeps every control's word whole`() {
        val activity = open(fontScale = 2f)
        shot(activity, "r8-subtitles-adjust-2x.png")
        assertWordsFit()
    }

    @Test
    @Config(sdk = [35], qualifiers = "w360dp-h740dp-port-night-xhdpi")
    fun `the narrowest phone at the system font keeps every control's word whole`() {
        val activity = open(fontScale = 1f)
        shot(activity, "r8-subtitles-adjust-1x.png")
        assertWordsFit()
    }
}
