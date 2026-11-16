package com.tmplayer.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.tmplayer.data.NameDiagnosis
import com.tmplayer.data.PosterWhy
import com.tmplayer.ui.browse.PosterWhyLine
import com.tmplayer.ui.theme.Tone
import com.tmplayer.ui.theme.TmMaterialTheme
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * "Why no poster?" (TS): the one line on a poster-less title page, drawn on a phone and a desktop
 * width and at a large font, with its one action. The remote's Down reaches it from the buttons
 * above (the keyboard map is the desktop shell's, as the television's remote runs through it).
 */
@OptIn(ExperimentalTestApi::class)
class PosterWhyLineTest {

    private val reasons = listOf(
        "off" to PosterWhy.Why(PosterWhy.Reason.LookupsOff),
        "wrong" to PosterWhy.Why(PosterWhy.Reason.MarkedWrong),
        "none" to PosterWhy.Why(PosterWhy.Reason.NotRecognised, NameDiagnosis.NoTitle),
        "short" to PosterWhy.Why(PosterWhy.Reason.NotRecognised, NameDiagnosis.TooShort),
        "nothing" to PosterWhy.Why(PosterWhy.Reason.NothingFound),
        "unsure" to PosterWhy.Why(PosterWhy.Reason.Unsure),
    )

    private val words = mapOf(
        "off" to "Posters are off, so nothing is looked up.",
        "wrong" to "You marked this match as wrong.",
        "none" to "This name has no title in it.",
        "short" to "This title is too short to look up safely.",
        "nothing" to "No title like this was found online.",
        "unsure" to "A title was found, but nothing here backs it up.",
    )

    @Test
    fun `each reason says its words and its one action runs`() {
        for ((name, why) in reasons) {
            runComposeUiTest {
                var turned = 0
                var fixed = 0
                setContent {
                    TmMaterialTheme(dark = true) {
                        Box(Modifier.size(400.dp, 200.dp).background(Tone.background)) {
                            PosterWhyLine(why, onTurnOn = { turned++ }, onFix = { fixed++ })
                        }
                    }
                }
                waitForIdle()
                onNodeWithText(words.getValue(name)).assertExists()
                val label = if (why.turnsOnLookups) "Turn on lookups" else "Fix match"
                onNodeWithText(label).performClick()
                waitForIdle()
                assertEquals(name, if (why.turnsOnLookups) 1 else 0, turned)
                assertEquals(name, if (why.turnsOnLookups) 0 else 1, fixed)
            }
        }
    }

    @Test
    fun `without a fix on the page the line has no action to offer for a wrong match`() = runComposeUiTest {
        setContent {
            TmMaterialTheme(dark = true) {
                Box(Modifier.size(400.dp, 200.dp)) { PosterWhyLine(PosterWhy.Why(PosterWhy.Reason.NothingFound), onTurnOn = {}, onFix = null) }
            }
        }
        waitForIdle()
        onNodeWithText(words.getValue("nothing")).assertExists()
        onAllNodesWithText("Fix match").assertCountEquals(0)
    }

    @Test
    fun `Down from the buttons reaches the line and Up goes back`() = runComposeUiTest {
        setContent {
            TmMaterialTheme(dark = true) {
                val focus = LocalFocusManager.current
                Box(
                    Modifier.size(600.dp, 400.dp).background(Tone.background).onKeyEvent { event ->
                        if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                        when (event.key) {
                            Key.DirectionDown -> focus.moveFocus(FocusDirection.Down)
                            Key.DirectionUp -> focus.moveFocus(FocusDirection.Up)
                            else -> false
                        }
                    },
                ) {
                    Column {
                        Text("Play", Modifier.clickable {}.padding(16.dp))
                        PosterWhyLine(PosterWhy.Why(PosterWhy.Reason.NothingFound), onTurnOn = {}, onFix = {})
                    }
                }
            }
        }
        waitForIdle()
        onNodeWithText("Play").requestFocus()
        onNode(isFocused()).performKeyInput { pressKey(Key.DirectionDown) }
        waitForIdle()
        onNodeWithText("Fix match").assertIsFocused()
        onNode(isFocused()).performKeyInput { pressKey(Key.DirectionUp) }
        waitForIdle()
        onNodeWithText("Play").assertIsFocused()
    }

    @Test
    fun `render shots at a phone width, a desktop width and a large font`() {
        val out = File(System.getProperty("tm.shots") ?: "build/shots").also { it.mkdirs() }
        for ((label, width, scale) in listOf(Triple("phone", 411, 1f), Triple("desktop", 900, 1f), Triple("large-font", 411, 2f))) {
            for ((name, why) in reasons.filter { it.first in setOf("off", "unsure", "none") }) {
                runComposeUiTest {
                    setContent {
                        TmMaterialTheme(dark = true) {
                            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, scale)) {
                                Box(Modifier.size(width.dp, if (scale > 1f) 300.dp else 150.dp).background(Tone.background).padding(16.dp)) {
                                    PosterWhyLine(why, onTurnOn = {}, onFix = {})
                                }
                            }
                        }
                    }
                    waitForIdle()
                    val image = onAllNodes(isRoot())[0].captureToImage()
                    javax.imageio.ImageIO.write(image.toAwtImage(), "png", File(out, "poster-why-$label-$name.png"))
                }
            }
        }
    }
}
