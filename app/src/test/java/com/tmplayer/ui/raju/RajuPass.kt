package com.tmplayer.ui.raju

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.onAllNodesWithText
import java.io.File

/** Shared bits of the second Raju pass: screenshots to design/raju-pass-2 and the 48 dp rule. */
internal object RajuPass {
    /** Writes the root of the current composition as `design/raju-pass-2/<name>.png` (cwd is the module). */
    @OptIn(ExperimentalTestApi::class)
    fun shot(rule: AndroidComposeTestRule<*, ComponentActivity>, name: String) {
        val bitmap = rule.onAllNodes(isRoot())[0].captureToImage().asAndroidBitmap()
        val file = File("../design/raju-pass-2/$name.png").absoluteFile
        file.parentFile?.mkdirs()
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    /**
     * Every pressable thing on screen answers to a thumb: its touch bounds are at least [minDp] each way.
     * Returns the offenders as text so a test can name them.
     */
    fun smallTargets(
        rule: AndroidComposeTestRule<*, ComponentActivity>,
        minDp: Float = 48f,
        except: (SemanticsNode) -> Boolean = { false },
    ): List<String> {
        val density = rule.activity.resources.displayMetrics.density
        val min = (minDp - 0.5f) * density
        return rule.onAllNodes(hasClickAction()).fetchSemanticsNodes().filter { node ->
            val b = node.touchBoundsInRoot
            !except(node) && b.width > 0f && b.height > 0f && (b.width < min || b.height < min)
        }.map { node ->
            val b = node.touchBoundsInRoot
            "%.0fx%.0f dp %s".format(b.width / density, b.height / density, node.config.toString().take(90))
        }
    }

    fun count(rule: AndroidComposeTestRule<*, ComponentActivity>, text: String, substring: Boolean = false): Int =
        rule.onAllNodesWithText(text, substring = substring).fetchSemanticsNodes().size
}
