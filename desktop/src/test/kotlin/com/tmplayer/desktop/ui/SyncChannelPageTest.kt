package com.tmplayer.desktop.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.use
import com.tmplayer.data.ChannelOption
import com.tmplayer.data.SyncChannelBlock
import com.tmplayer.data.SyncChannelChoice
import com.tmplayer.i18n.L
import com.tmplayer.i18n.Languages
import com.tmplayer.i18n.Translator
import com.tmplayer.ui.settings.SyncChannelActions
import com.tmplayer.ui.settings.SyncChannelContent
import com.tmplayer.ui.settings.SyncChannelUiState
import com.tmplayer.ui.theme.TmMaterialTheme
import com.tmplayer.ui.theme.TmSchemes
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * The sync channel page (HX87) on the desktop: the guide, the three buttons, the question that
 * stands where "Create it for me" was (Cancel focused, so a stray Enter makes nothing), the reason
 * a channel is not in use, and the frames in both themes (TMPLAYER_SHOTS=<dir> writes them).
 */
@OptIn(ExperimentalTestApi::class)
class SyncChannelPageTest {

    @Before
    fun english() {
        Translator.use(Languages.ENGLISH)
    }

    private val channel = SyncChannelChoice(-1001L, "TMPlayer sync", 42L)

    @Test
    fun `the guide shows its three steps and the create button`() = runComposeUiTest {
        setContent { TmMaterialTheme(dark = true) { SyncChannelContent(SyncChannelUiState(), SyncChannelActions()) } }
        onNodeWithText(L.syncchannelGuideStep1).assertExists()
        onNodeWithText(L.syncchannelGuideStep2).assertExists()
        onNodeWithText(L.syncchannelGuideStep3).assertExists()
        onNodeWithText(L.syncchannelButtonCreate).assertExists()
        onNodeWithText(L.syncchannelButtonChoose).assertExists()
        onNodeWithText(L.syncchannelUsingSaved).assertExists()
        // Saved Messages is in use, so there is nothing to go back to.
        onNodeWithText(L.syncchannelButtonUseSaved).assertDoesNotExist()
    }

    @Test
    fun `create asks first with Cancel focused and Cancel makes nothing`() = runComposeUiTest {
        var created = 0
        val asking = mutableStateOf(false)
        setContent {
            TmMaterialTheme(dark = true) {
                SyncChannelContent(
                    SyncChannelUiState(asking = asking.value),
                    SyncChannelActions(onAskCreate = { asking.value = true }, onCancelCreate = { asking.value = false }, onCreate = { created++ }),
                )
            }
        }
        onNodeWithText(L.syncchannelButtonCreate).performClick()
        waitForIdle()
        onNodeWithText(L.syncchannelCreateConfirmTitle).assertExists()
        onNodeWithText(L.commonCancel).assertIsFocused()
        onNodeWithText(L.commonCancel).performClick()
        waitForIdle()
        onNodeWithText(L.syncchannelCreateConfirmTitle).assertDoesNotExist()
        assertEquals("cancelling made nothing", 0, created)
        onNodeWithText(L.syncchannelButtonCreate).performClick()
        waitForIdle()
        onNodeWithText(L.syncchannelCreateConfirmAction).performClick()
        assertEquals(1, created)
    }

    @Test
    fun `a blocked channel says why and still offers the way back to Saved Messages`() = runComposeUiTest {
        val block = SyncChannelBlock.MoreMembers(3)
        setContent {
            TmMaterialTheme(dark = true) {
                SyncChannelContent(SyncChannelUiState(choice = channel, block = block, oldCopy = "Saved Messages"), SyncChannelActions())
            }
        }
        onNodeWithText(block.words()).assertExists()
        onNodeWithText(L.syncchannelUsingSaved).assertExists()
        onNodeWithText(L.syncchannelOldCopy("Saved Messages")).assertExists()
        onNodeWithText(L.syncchannelButtonUseSaved).assertExists()
    }

    @Test
    fun `a channel in use is named`() = runComposeUiTest {
        setContent { TmMaterialTheme(dark = true) { SyncChannelContent(SyncChannelUiState(choice = channel), SyncChannelActions()) } }
        onNodeWithText(L.syncchannelUsingChannel("TMPlayer sync")).assertExists()
    }

    @Test
    fun `the list of channels picks one and says when there is none`() = runComposeUiTest {
        var picked: ChannelOption? = null
        val options = mutableStateOf(listOf(ChannelOption(-1002L, "My notes channel")))
        setContent {
            TmMaterialTheme(dark = true) {
                SyncChannelContent(SyncChannelUiState(options = options.value), SyncChannelActions(onPick = { picked = it }))
            }
        }
        onNodeWithText("My notes channel").performClick()
        assertEquals(-1002L, picked?.chatId)
        options.value = emptyList()
        waitForIdle()
        onNodeWithText(L.syncchannelChooseNone).assertExists()
    }

    @Test
    fun `the page draws in both themes`() {
        val states = mapOf(
            "default" to SyncChannelUiState(),
            "blocked" to SyncChannelUiState(choice = channel, block = SyncChannelBlock.MoreMembers(2), oldCopy = "Saved Messages"),
            "options" to SyncChannelUiState(choice = channel, options = listOf(ChannelOption(1, "TMPlayer sync"), ChannelOption(2, "Notes"))),
        )
        for (dark in listOf(true, false)) for ((name, state) in states) {
            shot("sync-channel-$name-${if (dark) "dark" else "light"}", 1366, 900, dark) { SyncChannelContent(state, SyncChannelActions()) }
        }
    }

    private fun shot(name: String, w: Int, h: Int, dark: Boolean, content: @Composable () -> Unit) {
        val png = ImageComposeScene(w, h, Density(1f)) { TmMaterialTheme(dark = dark) { content() } }.use { scene ->
            scene.render(0)
            scene.render(600_000_000L).encodeToData(EncodedImageFormat.PNG)!!.bytes
        }
        val bitmap = Bitmap.makeFromImage(Image.makeFromEncoded(png))
        val ground = TmSchemes.of(dark).background.toArgb()
        var drawn = 0
        for (y in 0 until bitmap.height step 4) for (x in 0 until bitmap.width step 4) if (bitmap.getColor(x, y) != ground) drawn++
        assertTrue("$name has something on it", drawn > 50)
        val out = System.getenv("TMPLAYER_SHOTS")?.takeIf { it.isNotBlank() } ?: return
        File(out).apply { mkdirs() }.resolve("$name.png").writeBytes(png)
    }
}
