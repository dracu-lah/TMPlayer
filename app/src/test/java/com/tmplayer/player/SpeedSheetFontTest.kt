package com.tmplayer.player

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.text.TextLayoutResult
import com.tmplayer.data.FormFactor
import com.tmplayer.ui.DrainMainLooperRule
import com.tmplayer.ui.theme.TMPlayerTheme
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
 * The phone's speed page at twice the system font on the narrowest phone (R8): every speed tile
 * and the fine control's figure keep the speed on one line, whole, where four tiles a row left
 * "0.75x" broken across two lines and cut. Drawn to `build/device-test/review/r8-speed-2x.png`.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SpeedSheetFontTest {

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

    @Test
    @Config(sdk = [35], qualifiers = "w360dp-h740dp-port-night-xhdpi")
    fun `every speed stays whole on one line at twice the font`() {
        FormFactor.override(false)
        RuntimeEnvironment.setFontScale(2f)
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        activity.setContent {
            TMPlayerTheme {
                SpeedSheet(current = 1.35f, onPick = {}, onDismiss = {}, onClose = {})
            }
        }
        idle()

        val root = activity.window.decorView
        val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        root.draw(canvas)
        ShadowDialog.getLatestDialog()?.window?.decorView?.draw(canvas)
        val file = File("../build/device-test/review/r8-speed-2x.png").absoluteFile
        file.parentFile?.mkdirs()
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }

        val cut = mutableListOf<String>()
        for (word in (PlaybackSpeed.CHOICES + 1.35f).map { PlaybackSpeed.label(it) }.distinct()) {
            val nodes = rule.onAllNodesWithText(word).fetchSemanticsNodes()
            assertTrue("\"$word\" is not on the page", nodes.isNotEmpty())
            for (node in nodes) {
                val layouts = mutableListOf<TextLayoutResult>()
                node.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(layouts)
                if (layouts.any { it.hasVisualOverflow || it.lineCount > 1 }) cut += word
            }
        }
        assertTrue("cut or broken at twice the font: $cut", cut.isEmpty())
    }
}
