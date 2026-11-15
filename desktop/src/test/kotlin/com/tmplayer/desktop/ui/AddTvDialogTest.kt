package com.tmplayer.desktop.ui

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runComposeUiTest
import com.tmplayer.cast.dlna.DeviceDescription
import com.tmplayer.cast.dlna.DlnaDevice
import com.tmplayer.cast.dlna.DlnaService
import com.tmplayer.cast.dlna.ManualAddress
import com.tmplayer.ui.components.AddTvDialog
import com.tmplayer.ui.components.SavedTv
import com.tmplayer.ui.theme.TmMaterialTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.util.concurrent.CountDownLatch

/**
 * "Add a TV by its address" (phone and desktop share the dialog): the field is where the keyboard
 * is, Add TV waits for a text, every way an address can fail has its own plain sentence and leaves
 * the dialog up, a TV found closes it, and the remembered TVs can be removed.
 */
@OptIn(ExperimentalTestApi::class)
class AddTvDialogTest {

    private val asked = mutableListOf<String>()
    private val removed = mutableListOf<String>()
    private var dismissed = 0

    private val found = DlnaDevice(
        DeviceDescription(
            "uuid:a", "Living room TV", null, null, null,
            DlnaService("urn:schemas-upnp-org:service:AVTransport:1", "http://192.168.1.20/av"), null, null,
        ),
        InetAddress.getByAddress(byteArrayOf(192.toByte(), 168.toByte(), 1, 20)),
        "http://192.168.1.20/description.xml",
        manual = true,
    )

    private fun androidx.compose.ui.test.ComposeUiTest.show(
        saved: List<SavedTv> = emptyList(),
        answer: (String) -> ManualAddress.Outcome = { ManualAddress.Outcome.Found(found) },
    ) {
        setContent {
            TmMaterialTheme(dark = true) {
                AddTvDialog(saved, onAdd = { asked += it; answer(it) }, onRemove = { removed += it }, onDismiss = { dismissed++ })
            }
        }
        waitForIdle()
    }

    private fun androidx.compose.ui.test.ComposeUiTest.type(text: String) {
        onNodeWithTag("add-tv-input").performTextInput(text)
        waitForIdle()
    }

    private fun androidx.compose.ui.test.ComposeUiTest.add() {
        onNode(hasText("Add TV") and hasClickAction()).performClick()
    }

    @Test
    fun `the field has the keyboard and Add TV waits for a text`() = runComposeUiTest {
        show()
        onNodeWithTag("add-tv-input").assertIsFocused()
        onNode(hasText("Add TV") and hasClickAction()).assertIsNotEnabled()
        type("192.168.1.20")
        onNode(hasText("Add TV") and hasClickAction()).assertIsEnabled()
    }

    @Test
    fun `a TV found closes the dialog and the typed text is what was asked`() = runComposeUiTest {
        show()
        type("192.168.1.20")
        add()
        waitUntil(timeoutMillis = 5_000) { dismissed == 1 }
        assertEquals(listOf("192.168.1.20"), asked)
    }

    @Test
    fun `a host name, a public address and garbage each get their own sentence and the dialog stays`() = runComposeUiTest {
        val problems = mapOf(
            "tv.local" to (ManualAddress.Problem.Hostname to "Use the TV's IP address, like 192.168.1.20, not a name."),
            "8.8.8.8" to (ManualAddress.Problem.NotLocal to "That address isn't on your home network. TMPlayer only talks to TVs on this network."),
            "banana!" to (ManualAddress.Problem.Garbage to "That isn't an address. Type the TV's IP address, like 192.168.1.20, or its description link."),
        )
        show(answer = { ManualAddress.Outcome.Invalid(problems.getValue(it).first) })
        for ((typed, expected) in problems) {
            type(typed)
            add()
            waitUntil(timeoutMillis = 5_000) { onAllNodesWithTextCount(expected.second) == 1 }
            // Typing again clears the sentence.
            onNodeWithTag("add-tv-input").performTextInput("1")
            waitForIdle()
            assertEquals(0, onAllNodesWithTextCount(expected.second))
            clearField()
        }
        assertEquals(0, dismissed)
    }

    private fun androidx.compose.ui.test.ComposeUiTest.clearField() {
        onNodeWithTag("add-tv-input").performTextClearance()
    }

    private fun androidx.compose.ui.test.ComposeUiTest.onAllNodesWithTextCount(text: String) =
        onAllNodes(hasText(text)).fetchSemanticsNodes().size

    @Test
    fun `a TV that is off and a TV with a broken description say so with the address`() = runComposeUiTest {
        var next: ManualAddress.Outcome = ManualAddress.Outcome.Silent("192.168.1.30")
        show(answer = { next })
        type("192.168.1.30")
        add()
        waitUntil(timeoutMillis = 5_000) { onAllNodesWithTextCount("Nothing answered at 192.168.1.30. Check that the TV is on and on this network.") == 1 }
        next = ManualAddress.Outcome.Broken("192.168.1.30")
        add()
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText("Something answered at 192.168.1.30", substring = true)).fetchSemanticsNodes().size == 1 }
        assertEquals(0, dismissed)
    }

    @Test
    fun `a lookup that throws is a sentence, not a crash`() = runComposeUiTest {
        show(answer = { throw IllegalStateException("boom") })
        type("192.168.1.30")
        add()
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText("Nothing answered at", substring = true)).fetchSemanticsNodes().size == 1 }
        assertEquals(0, dismissed)
    }

    @Test
    fun `while it looks the field and Add TV are off and the dialog cannot be closed by Cancel`() = runComposeUiTest {
        val gate = CountDownLatch(1)
        show(answer = { gate.await(); ManualAddress.Outcome.Found(found) })
        type("192.168.1.20")
        add()
        waitUntil(timeoutMillis = 5_000) { onAllNodesWithTextCount("Looking for the TV…") == 1 }
        onNode(hasText("Add TV") and hasClickAction()).assertIsNotEnabled()
        onNode(hasText("Cancel") and hasClickAction()).assertIsNotEnabled()
        gate.countDown()
        waitUntil(timeoutMillis = 5_000) { dismissed == 1 }
        assertTrue(asked.size == 1)
    }

    @Test
    fun `remembered TVs are listed with a Remove each`() = runComposeUiTest {
        show(saved = listOf(SavedTv("http://192.168.1.20/d.xml", "Living room", "192.168.1.20"), SavedTv("http://192.168.1.21:49152/d.xml", "", "192.168.1.21:49152")))
        onNodeWithText("Saved TVs").assertExists()
        onNodeWithText("Living room").assertExists()
        onNodeWithText("192.168.1.21:49152").assertExists()
        onNodeWithContentDescription("Remove Living room").performClick()
        onNodeWithContentDescription("Remove 192.168.1.21:49152").performClick()
        assertEquals(listOf("http://192.168.1.20/d.xml", "http://192.168.1.21:49152/d.xml"), removed)
    }
}
