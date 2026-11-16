package com.tmplayer.desktop.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Density
import androidx.compose.ui.use
import com.tmplayer.i18n.L
import com.tmplayer.online.SkipKind
import com.tmplayer.online.SkipSegment
import org.jetbrains.skia.EncodedImageFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The "Skip intro" pill over a grey frame, with the controls down and up, so its corner and its
 * rise above the bar can be looked at in build/skip-shots. The assertions check that it drew and
 * that every kind of segment has its own words.
 */
class SkipPillRenderTest {

    @Test
    fun pillDrawsLowAndLifted() {
        for (lifted in listOf(false, true)) {
            val png = ImageComposeScene(1280, 720, Density(1f)) {
                PlayerTheme {
                    Box(Modifier.fillMaxSize().background(Color(0xFF3A3F47))) {
                        SkipPill(SkipSegment(SkipKind.Intro, 0, 90_000), lifted = lifted, onSkip = {})
                    }
                }
            }.use { scene ->
                scene.render(0)
                scene.render(1_000_000_000).encodeToData(EncodedImageFormat.PNG)!!.bytes
            }
            assertTrue(png.size > 2_000)
            File("build/skip-shots").apply { mkdirs() }.resolve("skip-pill${if (lifted) "-lifted" else ""}.png").writeBytes(png)
        }
    }

    @Test
    fun countdownDrawsWithItsCancelAndAMarkedIntroSaysSo() {
        val png = ImageComposeScene(1280, 720, Density(1f)) {
            PlayerTheme {
                Box(Modifier.fillMaxSize().background(Color(0xFF3A3F47))) {
                    SkipPill(SkipSegment(SkipKind.Opening, 0, 90_000), lifted = false, onSkip = {}, secondsLeft = 2, onCancel = {})
                }
            }
        }.use { scene ->
            scene.render(0)
            scene.render(1_000_000_000).encodeToData(EncodedImageFormat.PNG)!!.bytes
        }
        assertTrue(png.size > 2_000)
        File("build/skip-shots").apply { mkdirs() }.resolve("skip-pill-counting.png").writeBytes(png)
        assertEquals(L.playerSkipIntroMark, skipLabel(SkipSegment(SkipKind.Intro, 0, 90_000, com.tmplayer.online.SkipSource.Mark), L))
        assertEquals(L.playerSkipIntroMark, skipLabel(SkipSegment(SkipKind.Intro, 0, 90_000, com.tmplayer.online.SkipSource.Guess), L))
        assertEquals(L.playerSkipOpening, skipLabel(SkipSegment(SkipKind.Opening, 0, 90_000, com.tmplayer.online.SkipSource.Chapter), L))
    }

    @Test
    fun everyKindHasItsOwnWords() {
        val labels = SkipKind.entries.map { skipLabel(it, L) }
        assertEquals(L.playerSkipIntro, skipLabel(SkipKind.Intro, L))
        assertEquals(L.playerSkipCredits, skipLabel(SkipKind.Credits, L))
        assertEquals(labels.size, labels.toSet().size)
    }
}
