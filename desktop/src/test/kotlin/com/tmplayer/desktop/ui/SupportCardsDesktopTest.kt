package com.tmplayer.desktop.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.use
import com.tmplayer.data.SupportRegion
import com.tmplayer.ui.about.SupportActions
import com.tmplayer.ui.about.SupportDialog
import com.tmplayer.ui.about.SupportStep
import com.tmplayer.ui.theme.Motion
import com.tmplayer.ui.theme.TmMaterialTheme
import com.tmplayer.ui.theme.Tone
import org.jetbrains.skia.EncodedImageFormat
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The support window on the desktop (D1): both region orders drawn light and dark (PNGs into
 * TMPLAYER_SHOTS when set), the keyboard reaching every control in reading order, Enter pressing,
 * Esc going one level up, the copy button's honesty, and nothing opened without a press.
 */
@OptIn(ExperimentalTestApi::class)
class SupportCardsDesktopTest {

    private val opened = mutableListOf<String>()
    private val copied = mutableListOf<String>()
    private var closed = 0
    private var never = 0
    private var copyWorks = true

    @After
    fun tearDown() {
        Motion.reduced = false
    }

    private val actions
        get() = SupportActions(
            openLink = { opened += it; true },
            copy = { text -> copied += text; copyWorks },
        )

    @Composable
    private fun Window(order: SupportRegion.Order, asking: Boolean = true, start: SupportStep = SupportStep.Ways) {
        SupportDialog(
            order = order,
            title = "Support TMPlayer",
            text = "Voluntary. It unlocks nothing: every feature is free for everyone.",
            actions = actions,
            onClose = { closed++ },
            onNever = if (asking) ({ never++ }) else null,
            start = start,
        )
    }

    private fun androidx.compose.ui.test.ComposeUiTest.show(order: SupportRegion.Order, asking: Boolean = true) {
        Motion.reduced = true
        setContent { TmMaterialTheme(dark = true) { Window(order, asking) } }
        waitForIdle()
        mainClock.advanceTimeBy(300)
        waitForIdle()
    }

    private fun androidx.compose.ui.test.ComposeUiTest.key(key: Key) {
        onNode(androidx.compose.ui.test.isFocused()).performKeyInput { pressKey(key) }
        waitForIdle()
        mainClock.advanceTimeBy(100)
        waitForIdle()
    }

    @Test
    fun `the keyboard starts on the first card and Tab reaches every control in reading order`() = runComposeUiTest {
        show(SupportRegion.order("US", null))
        onNode(hasText("Buy me a coffee") and hasClickAction()).assertIsFocused()
        key(Key.Tab)
        onNode(hasText("Pay by UPI (India)") and hasClickAction()).assertIsFocused()
        key(Key.Tab)
        onNode(hasText("Sponsor on GitHub") and hasClickAction()).assertIsFocused()
        key(Key.Tab)
        onNode(hasText("Star on GitHub") and hasClickAction()).assertIsFocused()
        key(Key.Tab)
        onNode(hasText("Don't show again") and hasClickAction()).assertIsFocused()
        key(Key.Tab)
        onNode(hasText("Maybe later") and hasClickAction()).assertIsFocused()
        assertTrue("nothing opens by itself: $opened", opened.isEmpty())
    }

    @Test
    fun `Enter on Buy Me a Coffee opens its page once, and nothing else`() = runComposeUiTest {
        show(SupportRegion.order("US", null), asking = false)
        key(Key.Enter)
        assertEquals(listOf("https://buymeacoffee.com/nevil.dev"), opened)
    }

    @Test
    fun `in India UPI leads, its page has the ID, Copy says so only when the copy worked, Esc goes back`() = runComposeUiTest {
        show(SupportRegion.order("IN", null), asking = false)
        onNode(hasText("Pay by UPI") and hasClickAction()).assertIsFocused()
        key(Key.Enter)
        onNodeWithText("UPI ID: nevilnicks4321@okaxis").assertExists()
        // A computer has no UPI app, so there is no such button; the code and the ID are the way.
        onNodeWithText("Open in a UPI app").assertDoesNotExist()
        copyWorks = false
        onNodeWithText("Copy the UPI ID").performClick()
        waitForIdle()
        onNodeWithText("UPI ID copied").assertDoesNotExist()
        copyWorks = true
        onNodeWithText("Copy the UPI ID").performClick()
        waitForIdle()
        onNodeWithText("UPI ID copied").assertExists()
        assertEquals(listOf("nevilnicks4321@okaxis", "nevilnicks4321@okaxis"), copied)
        assertTrue(opened.isEmpty())
    }

    @Test
    fun `answers are honoured`() = runComposeUiTest {
        show(SupportRegion.order("US", null))
        onNodeWithText("Don't show again").performClick()
        onNodeWithText("Maybe later").performClick()
        assertEquals(1, never)
        assertEquals(1, closed)
    }

    private fun render(dark: Boolean, order: SupportRegion.Order, asking: Boolean, step: SupportStep = SupportStep.Ways, height: Int = 900): ByteArray =
        ImageComposeScene(900, height, Density(1f)) {
            Motion.reduced = true
            TmMaterialTheme(dark = dark) {
                Surface(Modifier.fillMaxSize(), color = Tone.background) { Window(order, asking, step) }
            }
        }.use { scene ->
            scene.render(0)
            Thread.sleep(500)
            scene.render(500_000_000)
            scene.render(1_000_000_000).encodeToData(EncodedImageFormat.PNG)!!.bytes
        }

    @Test
    fun `both orders and both pages draw`() {
        val out = System.getenv("TMPLAYER_SHOTS")?.takeIf { it.isNotBlank() }?.let { File(it).apply { mkdirs() } }
        for (dark in listOf(true, false)) {
            val suffix = if (dark) "dark" else "light"
            val shots = mapOf(
                "support-cards-desktop-elsewhere-$suffix.png" to render(dark, SupportRegion.order("US", null), asking = true),
                "support-cards-desktop-india-$suffix.png" to render(dark, SupportRegion.order("IN", null), asking = false),
                "support-cards-desktop-upi-$suffix.png" to render(dark, SupportRegion.order("IN", null), asking = false, step = SupportStep.Upi, height = 1100),
            )
            for ((name, png) in shots) {
                assertTrue(name, png.size > 2000)
                out?.resolve(name)?.writeBytes(png)
            }
        }
    }
}
