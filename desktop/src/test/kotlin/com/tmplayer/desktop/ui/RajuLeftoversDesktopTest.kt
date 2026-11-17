package com.tmplayer.desktop.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.use
import com.tmplayer.data.AuthState
import com.tmplayer.i18n.L
import com.tmplayer.ui.components.BrandLoader
import com.tmplayer.ui.connect.FirstOpenPromiseView
import com.tmplayer.ui.theme.TmMaterialTheme
import com.tmplayer.ui.theme.Tone
import org.jetbrains.skia.EncodedImageFormat
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The Raju pass 2 leftovers on the desktop: 1.1 the loader's one line on a first open, 2.4 the sign in
 * footer's help as words that Tab reaches and Enter opens (the Settings changes are in
 * SettingsMapDesktopTest). The frames go to design/raju-pass-2/.
 */
@OptIn(ExperimentalTestApi::class)
class RajuLeftoversDesktopTest {

    private fun androidx.compose.ui.test.ComposeUiTest.has(text: String) =
        assertTrue("missing: $text", onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty())

    @Test
    fun `the sign in footer says Get help in words, Tab reaches it and it opens the proxy and the logs`() = runComposeUiTest {
        setContent {
            TmMaterialTheme(dark = true) {
                Surface(Modifier.fillMaxSize(), color = Tone.background) { SignInScreen(AuthState.Phone()) }
            }
        }
        has(L.connectGetHelp)
        val target = hasText(L.connectGetHelp) and hasClickAction() and isFocused()
        var reached = false
        repeat(12) {
            if (!reached && onAllNodes(target).fetchSemanticsNodes().isNotEmpty()) reached = true
            if (!reached) {
                onRoot().performKeyInput { pressKey(Key.Tab) }
                waitForIdle()
            }
        }
        assertTrue("Tab reaches the help link", reached || onAllNodes(target).fetchSemanticsNodes().isNotEmpty())
        onNode(hasText(L.connectGetHelp) and hasClickAction()).performClick()
        waitForIdle()
        has(L.loginProxySettings)
        has(L.logsSave)
    }

    @Test
    fun `the loader carries the one line of what is coming`() = runComposeUiTest {
        setContent {
            TmMaterialTheme(dark = true) {
                BrandLoader(label = L.stateConnecting, sweepAt = 0.5f, below = { FirstOpenPromiseView() })
            }
        }
        has(L.stateFirstOpenPromise)
        frame()
    }

    private fun frame() {
        ImageComposeScene(1000, 700, Density(1f)) {
            TmMaterialTheme(dark = true) {
                BrandLoader(label = L.stateConnecting, sweepAt = 0.5f, below = { FirstOpenPromiseView() })
            }
        }.use { scene ->
            scene.render(0)
            val image = scene.render(600_000_000)
            val file = File("../design/raju-pass-2/desktop-loader-promise.png").absoluteFile
            file.parentFile?.mkdirs()
            file.writeBytes(image.encodeToData(EncodedImageFormat.PNG)!!.bytes)
        }
    }
}
