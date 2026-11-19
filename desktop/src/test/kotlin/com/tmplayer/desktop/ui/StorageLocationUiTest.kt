package com.tmplayer.desktop.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.use
import com.tmplayer.ui.theme.TmMaterialTheme
import com.tmplayer.ui.theme.Tone
import org.jetbrains.skia.EncodedImageFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The Storage location row and its question (HX57, desktop): what each state offers, that Cancel
 * is there while downloads move, that the tick that carries the downloads is off and says what
 * happens, that the keyboard (Tab, Enter, Esc) reaches everything, and frames at the window sizes
 * and scales a computer meets (PNGs under `build/device-test/storage/`).
 */
@OptIn(ExperimentalTestApi::class)
class StorageLocationUiTest {

    private val gb = 1024L * 1024 * 1024

    private fun state(
        detail: String = "/mnt/big/TMPlayer",
        progress: Float? = null,
        change: Boolean = true,
        reset: Boolean = true,
        moving: Boolean = false,
        waiting: Boolean = false,
        leftover: String? = null,
    ) = StorageLocationState(detail, progress, change, reset, moving, waiting, leftover)

    private fun confirm(count: Int = 12, bytes: Long = 38 * gb, canAlso: Boolean = true) = StorageDialog.Confirm(
        root = File("/mnt/big"),
        where = "/mnt/big",
        newDownloads = "/mnt/big/TMPlayer/downloads",
        newCache = "/mnt/big/TMPlayer/cache",
        downloadCount = count,
        downloadsBytes = bytes,
        cacheBytes = (3.1 * gb).toLong(),
        warning = null,
        adopt = false,
        canAlsoMove = canAlso,
        freeLine = "412 GB free of 931 GB on /mnt/big",
    )

    private val log = mutableListOf<String>()

    @Composable
    private fun Row(state: StorageLocationState) {
        StorageLocationRow(
            state,
            onChange = { log += "change" },
            onReset = { log += "reset" },
            onCancel = { log += "cancel" },
            onForget = { log += "forget" },
            onMoveRest = { log += "rest" },
        )
    }

    private fun androidx.compose.ui.test.ComposeUiTest.row(state: StorageLocationState) {
        setContent { TmMaterialTheme(dark = true) { SettingsColumn { Row(state) } } }
        waitForIdle()
    }

    private fun androidx.compose.ui.test.ComposeUiTest.has(text: String) = onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty()

    @Test
    fun `an idle row offers Change, and Reset only when a location is chosen`() = runComposeUiTest {
        row(state(reset = false, detail = "Default folders: downloads in /home/me/Downloads/TMPlayer"))
        assertTrue(has("Change"))
        assertFalse(has("Reset"))
        assertFalse(has("Cancel"))
    }

    @Test
    fun `while downloads move the row offers Cancel and nothing that starts another move`() = runComposeUiTest {
        row(state(moving = true, progress = 0.4f, change = false, reset = false, detail = "Moving downloads, 4.0 GB of 10 GB"))
        assertTrue(has("Cancel"))
        assertFalse(has("Change"))
        assertFalse(has("Reset"))
        onNodeWithText("Cancel").performClick()
        assertEquals(listOf("cancel"), log)
    }

    @Test
    fun `a move waiting for its drive can be forgotten`() = runComposeUiTest {
        row(state(waiting = true, detail = "Waiting for /mnt/big/TMPlayer to finish moving downloads"))
        onNodeWithText("Forget the move").performClick()
        assertEquals(listOf("forget"), log)
    }

    @Test
    fun `downloads left outside the downloads folder have their own row with Move`() = runComposeUiTest {
        row(state(leftover = "3 downloads (12 GB) are outside the downloads folder. They keep playing from where they are."))
        assertTrue(has("Downloads in another folder"))
        onNodeWithText("Move").performClick()
        assertEquals(listOf("rest"), log)
    }

    @Test
    fun `Tab walks from Change to Reset and Enter presses the one it is on`() = runComposeUiTest {
        row(state())
        onNodeWithText("Change").requestFocus()
        waitForIdle()
        onNodeWithText("Change").assertIsFocused()
        onNodeWithText("Change").performKeyInput { pressKey(Key.Tab) }
        waitForIdle()
        onNodeWithText("Reset").assertIsFocused()
        onNodeWithText("Reset").performKeyInput { pressKey(Key.Enter) }
        waitForIdle()
        assertEquals(listOf("reset"), log)
    }

    private fun androidx.compose.ui.test.ComposeUiTest.question(d: StorageDialog.Confirm, onDismiss: () -> Unit = { log += "dismiss" }) {
        setContent { TmMaterialTheme(dark = true) { MoveConfirm(d, onDismiss) { log += "confirm $it" } } }
        waitForIdle()
    }

    @Test
    fun `the question starts on Cancel, with the downloads tick off and the words saying they stay`() = runComposeUiTest {
        question(confirm())
        onNodeWithText("Cancel").assertIsFocused()
        assertTrue(has("Also move the 12 downloads already here (38.0 GB)"))
        assertTrue(has("412 GB free of 931 GB on /mnt/big"))
        assertTrue(onAllNodes(hasText("stay where they are and keep playing", substring = true)).fetchSemanticsNodes().isNotEmpty())
        onNodeWithText("Move").performClick()
        assertEquals(listOf("confirm false"), log)
    }

    @Test
    fun `ticking the downloads changes the words and what is confirmed`() = runComposeUiTest {
        question(confirm())
        onNodeWithText("Also move the 12 downloads already here (38.0 GB)").performClick()
        waitForIdle()
        assertTrue(onAllNodes(hasText("move to /mnt/big/TMPlayer/downloads", substring = true)).fetchSemanticsNodes().isNotEmpty())
        onNodeWithText("Move").performClick()
        assertEquals(listOf("confirm true"), log)
    }

    @Test
    fun `when the downloads do not fit the tick is not offered and nothing is confirmed with them`() = runComposeUiTest {
        question(confirm(canAlso = false))
        assertFalse(has("Also move the 12 downloads already here (38.0 GB)"))
        assertTrue(onAllNodes(hasText("Not enough room there", substring = true)).fetchSemanticsNodes().isNotEmpty())
        onNodeWithText("Move").performClick()
        assertEquals(listOf("confirm false"), log)
    }

    @Test
    fun `with no downloads there is no tick`() = runComposeUiTest {
        question(confirm(count = 0, bytes = 0))
        assertFalse(onAllNodes(hasText("Also move", substring = true)).fetchSemanticsNodes().isNotEmpty())
    }

    @Test
    fun `Escape closes the question`() = runComposeUiTest {
        question(confirm())
        onNodeWithText("Cancel").performKeyInput { pressKey(Key.Escape) }
        waitForIdle()
        assertEquals(listOf("dismiss"), log)
    }

    /** Frames of the row and the question at the sizes and scales of a computer. */
    @Test
    fun `frames at window sizes and scales`() {
        // Window sizes in dp (the minimum window, a laptop, a monitor); the frame is that times the scale.
        val windows = listOf(960 to 600, 1366 to 768, 1920 to 1080)
        val scales = listOf(1f, 1.5f, 2f)
        for (dark in listOf(false, true)) {
            val theme = if (dark) "dark" else "light"
            for ((w, h) in windows) {
                for (scale in scales) {
                    val name = "${w}x$h-${(scale * 100).toInt()}-$theme"
                    save("row-$name.png", render(w, h, scale, dark) { RowStates() })
                    save("question-$name.png", render(w, h, scale, dark) {
                        MoveConfirm(confirm(), {}) {}
                    })
                    save("question-nofit-$name.png", render(w, h, scale, dark) {
                        MoveConfirm(confirm(canAlso = false), {}) {}
                    })
                }
            }
        }
    }

    @Composable
    private fun RowStates() {
        Box(Modifier.fillMaxSize().padding(16.dp)) {
            SettingsColumn {
                Group("Storage location")
                Row(state())
                Row(state(moving = true, progress = 0.4f, change = false, reset = false, detail = "Moving downloads, 15.2 GB of 38.0 GB"))
                Row(state(waiting = true, detail = "Waiting for E:\\TMPlayer to finish moving downloads"))
                Row(state(leftover = "3 downloads (12.0 GB) are outside the downloads folder. They keep playing from where they are."))
            }
        }
    }

    private fun render(width: Int, height: Int, scale: Float, dark: Boolean, content: @Composable () -> Unit): ByteArray =
        ImageComposeScene((width * scale).toInt(), (height * scale).toInt(), Density(scale)) {
            TmMaterialTheme(dark = dark) {
                Surface(Modifier.fillMaxSize(), color = Tone.background) { content() }
            }
        }.use { scene ->
            scene.render(0)
            Thread.sleep(200)
            scene.render(500_000_000)
            scene.render(1_500_000_000).encodeToData(EncodedImageFormat.PNG)!!.bytes
        }

    private fun save(name: String, png: ByteArray) {
        assertTrue(png.size > 1000)
        File("../build/device-test/storage").absoluteFile.apply { mkdirs() }.resolve(name).writeBytes(png)
    }
}
