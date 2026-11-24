package com.tmplayer.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.use
import com.tmplayer.ui.browse.KeepPlaceCard
import com.tmplayer.ui.theme.TmMaterialTheme
import com.tmplayer.ui.theme.Tone
import org.jetbrains.skia.EncodedImageFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The desktop's "Sync your progress across devices?" card (FQ12): its words, Tab reaching both buttons,
 * Enter pressing them, and a picture at 1280 wide and at the window's narrowest. With
 * TMPLAYER_SHOTS=<dir> the frames are written there as `desktop-keep-place-<width>.png`.
 */
@OptIn(ExperimentalTestApi::class)
class KeepPlaceCardTest {

    @Test
    fun `Tab reaches both buttons and Enter presses them`() = runComposeUiTest {
        var yes = 0
        var no = 0
        setContent { TmMaterialTheme(dark = true) { KeepPlaceCard(onYes = { yes++ }, onNotNow = { no++ }, start = 16.dp, end = 16.dp) } }
        onNodeWithText("Sync your progress across devices?").assertExists()
        onNodeWithText("one small file in your own Saved Messages", substring = true).assertExists()
        onRoot().performKeyInput { pressKey(Key.Tab) }
        onNode(hasText("Yes, sync it") and hasClickAction()).assertIsFocused()
        onRoot().performKeyInput { pressKey(Key.Enter) }
        assertEquals(1, yes)
        // A pressed button lets go of focus (the card is about to go), so the next Tab starts from the
        // first button again: Yes, then Not now.
        repeat(2) { onRoot().performKeyInput { pressKey(Key.Tab) } }
        onNode(hasText("Not now") and hasClickAction()).assertIsFocused()
        onRoot().performKeyInput { pressKey(Key.Enter) }
        assertEquals(1, no)
        assertEquals(1, yes)
    }

    @Test
    fun `the card is drawn at the window's widest and narrowest`() {
        for (dark in listOf(true, false)) {
            for (width in listOf(1280, 640)) {
                val bytes = ImageComposeScene(width, 360, Density(1f)) {
                    TmMaterialTheme(dark = dark) {
                        Box(Modifier.fillMaxSize().background(Tone.background).padding(top = 24.dp)) {
                            KeepPlaceCard(onYes = {}, onNotNow = {}, start = 32.dp, end = 32.dp)
                        }
                    }
                }.use { scene ->
                    scene.render(0L).encodeToData(EncodedImageFormat.PNG)!!.bytes
                }
                assertTrue(bytes.size > 1_000)
                val dir = System.getenv("TMPLAYER_SHOTS")
                if (dir != null) {
                    File(dir).apply { mkdirs() }.resolve("desktop-keep-place-$width-${if (dark) "dark" else "light"}.png").writeBytes(bytes)
                }
            }
        }
    }
}
