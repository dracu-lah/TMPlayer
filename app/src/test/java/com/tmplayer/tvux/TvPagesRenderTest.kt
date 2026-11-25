package com.tmplayer.tvux

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.Density
import com.tmplayer.data.FormFactor
import com.tmplayer.ui.DrainMainLooperRule
import com.tmplayer.ui.theme.Motion
import com.tmplayer.ui.theme.TMPlayerTheme
import com.tmplayer.ui.theme.Tone
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * The one shared TV page check (TV fix, 2026-11-25): every button, pill, chip and tab on the TV pages below has its
 * words on its own middle line, within 2 px ([TvUxChecklist.labelsCentred]). Rendered at 1920 x 1080 (density 2.0,
 * a Mi TV) and 960 x 540 (density 1.0). A frame of each page lands in `design/tv-pages/` as proof.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w960dp-h540dp-land-television-night-xhdpi")
class TvPagesRenderTest(private val screen: String) {

    companion object {
        /** The pages with pills, chips, buttons or tabs worth measuring. */
        val PAGES = listOf(
            "home", "chatList", "history", "details", "lists", "downloads", "tvSearch", "about", "profilePicker",
            "settings:Playback", "settings:Accounts", "settings:Library",
        )

        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun screens(): List<Array<Any>> = PAGES.filter { name -> SCREENS.any { it.name == name } }.map { arrayOf<Any>(it) }
    }

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    @get:Rule
    val drain = DrainMainLooperRule()

    private val fixtures = Fixtures()

    @After
    fun tearDown() {
        fixtures.close()
        FormFactor.override(false)
        Motion.reduced = false
    }

    @Test
    fun labelsSitOnTheMiddleLine() {
        val spec = SCREENS.first { it.name == screen }
        val content = spec.content ?: return
        val ux = TvUxChecklist(rule, screen)
        FormFactor.override(true)
        Motion.reduced = false
        rule.mainClock.autoAdvance = false
        val body = content(fixtures)
        rule.setContent {
            TMPlayerTheme {
                val base = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(base.density, 1f)) {
                    Box(Modifier.fillMaxSize().background(Tone.background)) { body() }
                }
            }
        }
        if (spec.remoteFirst) ux.windowForRemote()
        repeat(6) { Thread.sleep(100); ux.settle(300) }
        ux.windowForRemote()
        repeat(10) { if (ux.focused().isEmpty()) { Thread.sleep(50); ux.settle(300) } }
        spec.prepare(ux)
        ux.labelsCentred()
        saveFrame()
        assertNoFindings(screen, ux.findings.filter { it.rule == "labelCentre" })
    }

    private fun saveFrame() {
        val bitmap = rule.onRoot().captureToImage().asAndroidBitmap()
        val dpi = rule.activity.resources.displayMetrics.density.let { if (it >= 2f) "1080" else "540" }
        val name = screen.replace(':', '-')
        val file = File("../design/tv-pages/$name-$dpi.png").absoluteFile
        file.parentFile?.mkdirs()
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}

/** The same pages on a 960 x 540 pixel panel (density 1.0). */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w960dp-h540dp-land-television-night-mdpi")
class TvPagesRender540Test(private val screen: String) {

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun screens(): List<Array<Any>> = TvPagesRenderTest.screens()
    }

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    @get:Rule
    val drain = DrainMainLooperRule()

    private val fixtures = Fixtures()

    @After
    fun tearDown() {
        fixtures.close()
        FormFactor.override(false)
        Motion.reduced = false
    }

    @Test
    fun labelsSitOnTheMiddleLine() {
        val spec = SCREENS.first { it.name == screen }
        val content = spec.content ?: return
        val ux = TvUxChecklist(rule, screen)
        FormFactor.override(true)
        Motion.reduced = false
        rule.mainClock.autoAdvance = false
        val body = content(fixtures)
        rule.setContent {
            TMPlayerTheme { Box(Modifier.fillMaxSize().background(Tone.background)) { body() } }
        }
        if (spec.remoteFirst) ux.windowForRemote()
        repeat(6) { Thread.sleep(100); ux.settle(300) }
        ux.windowForRemote()
        repeat(10) { if (ux.focused().isEmpty()) { Thread.sleep(50); ux.settle(300) } }
        spec.prepare(ux)
        ux.labelsCentred()
        assertNoFindings(screen, ux.findings.filter { it.rule == "labelCentre" })
    }
}
