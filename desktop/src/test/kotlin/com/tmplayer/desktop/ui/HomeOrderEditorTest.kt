package com.tmplayer.desktop.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.use
import com.tmplayer.ui.settings.HomeOrderRow
import com.tmplayer.ui.settings.HomeOrderSection
import com.tmplayer.ui.theme.TmMaterialTheme
import com.tmplayer.ui.theme.Tone
import org.jetbrains.skia.EncodedImageFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Settings, Home, Order of rows (EG-F7, EG-F23) on the desktop and the phone's shared list: Move up
 * and Move down on every row (the ends disabled), a drag by the handle that lands one move, a long
 * list of forty starred chats that still draws, and a picture per form factor.
 */
@OptIn(ExperimentalTestApi::class)
class HomeOrderEditorTest {

    private fun rows(count: Int) = buildList {
        add(HomeOrderRow("continue", "Continue watching"))
        add(HomeOrderRow("recent", "Recently added", subtitle = "Off"))
        repeat(count) { add(HomeOrderRow("chat-${it + 1}", "Chat ${it + 1}")) }
    }

    @Test
    fun `Move up and Move down move a row one place and the ends are disabled`() = runComposeUiTest {
        val written = mutableListOf<List<String>>()
        setContent {
            TmMaterialTheme(dark = true) {
                var current by remember { mutableStateOf(rows(2)) }
                HomeOrderSection(current, hasOrder = false, onOrder = { next ->
                    written += next
                    current = next.map { key -> current.first { it.key == key } }
                }, onReset = {}, remote = false)
            }
        }
        // Four rows: Continue, Recent, Chat 1, Chat 2. Up on the first and Down on the last do nothing.
        onAllNodesWithContentDescription("Move up")[0].assertIsNotEnabled()
        onAllNodesWithContentDescription("Move down")[3].assertIsNotEnabled()
        onAllNodesWithContentDescription("Move down")[0].assertIsEnabled()

        onAllNodesWithContentDescription("Move down")[0].performClick()
        assertEquals(listOf(listOf("recent", "continue", "chat-1", "chat-2")), written)
        // The row that moved is where it was put: its Move up is now the second row's.
        waitForIdle()
        onAllNodesWithContentDescription("Move up")[1].performClick()
        assertEquals(listOf("continue", "recent", "chat-1", "chat-2"), written.last())
        assertEquals(2, written.size)
    }

    @Test
    fun `dragging a row by its handle past the next row lands one move, written when it is let go`() = runComposeUiTest {
        val written = mutableListOf<List<String>>()
        setContent {
            TmMaterialTheme(dark = true) {
                HomeOrderSection(rows(2), hasOrder = false, onOrder = { written += it }, onReset = {}, remote = false)
            }
        }
        val handle = onAllNodesWithContentDescription("Drag to move")[0]
        handle.performTouchInput {
            down(center)
            moveBy(Offset(0f, 40f))
            moveBy(Offset(0f, 30f))
            up()
        }
        assertEquals(listOf(listOf("recent", "continue", "chat-1", "chat-2")), written)
    }

    @Test
    fun `a mouse drags the handle too`() = runComposeUiTest {
        val written = mutableListOf<List<String>>()
        setContent {
            TmMaterialTheme(dark = true) {
                HomeOrderSection(rows(2), hasOrder = false, onOrder = { written += it }, onReset = {}, remote = false)
            }
        }
        onAllNodesWithContentDescription("Drag to move")[0].performMouseInput {
            moveTo(center)
            press()
            repeat(12) { moveBy(Offset(0f, 6f)) }
            release()
        }
        assertEquals(listOf(listOf("recent", "continue", "chat-1", "chat-2")), written)
    }

    @Test
    fun `Default order shows once an order is stored and gives it up`() = runComposeUiTest {
        var reset = 0
        var has by mutableStateOf(false)
        setContent {
            TmMaterialTheme(dark = true) {
                HomeOrderSection(rows(1), hasOrder = has, onOrder = {}, onReset = { reset++ }, remote = false)
            }
        }
        assertTrue(onAllNodesWithText("Default order").fetchSemanticsNodes().isEmpty())
        has = true
        waitForIdle()
        onNodeWithText("Default order").performClick()
        assertEquals(1, reset)
    }

    @Test
    fun `forty starred chats draw, scroll and keep every row, per form factor`() {
        // Phone, desktop and television widths; the television's list is the remote's.
        listOf(Triple("phone", 411, false), Triple("desktop", 1280, false), Triple("tv", 960, true)).forEach { (name, width, remote) ->
            val png = ImageComposeScene(width, 1_400, Density(1f)) {
                TmMaterialTheme(dark = true) {
                    Surface(Modifier.fillMaxSize(), color = Tone.background) {
                        HomeOrderSection(
                            rows(40), hasOrder = true, onOrder = {}, onReset = {}, remote = remote,
                            modifier = Modifier.padding(16.dp).verticalScroll(rememberScrollState()),
                        )
                    }
                }
            }.use { scene ->
                scene.render(0)
                scene.render(500_000_000).encodeToData(EncodedImageFormat.PNG)!!.bytes
            }
            assertTrue("$name drew nothing", png.size > 1_000)
            System.getenv("TMPLAYER_SHOTS")?.takeIf { it.isNotBlank() }?.let { File(it).apply { mkdirs() }.resolve("home-order-$name.png").writeBytes(png) }
        }
    }
}
