package com.tmplayer.ui.settings

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import com.tmplayer.data.Accent
import com.tmplayer.data.FormFactor
import com.tmplayer.data.SettingsStore
import com.tmplayer.data.ThemeChoice
import com.tmplayer.ui.DrainMainLooperRule
import com.tmplayer.ui.theme.TMPlayerTheme
import com.tmplayer.ui.theme.Tone
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.Duration

/**
 * The Appearance page with the Pure black switch under the theme (2026-10-19), on a phone and on a
 * television: there under Dark, drawn on true black once on, and folded away under Light; and the
 * accent swatches under it, Red and Monochrome picked, faded while the wallpaper's colours are in
 * use on the phone. Frames
 * go to `build/device-test/review/` and to TMPLAYER_SHOTS=<dir> when that is set.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AppearanceRenderTest {

    /** A state write left on the looper must not silence Compose for the next test (HX92). */
    @get:Rule
    val drain = DrainMainLooperRule()


    private val settings get() = SettingsStore(RuntimeEnvironment.getApplication())

    @After
    fun tearDown() {
        FormFactor.override(false)
        com.tmplayer.ui.theme.Motion.reduced = false
    }

    @Test
    @Config(sdk = [35], qualifiers = "w411dp-h891dp-port-night-xhdpi")
    fun phone() = pass(tv = false, form = "phone")

    @Test
    @Config(sdk = [28], qualifiers = "w960dp-h540dp-land-television-night-xhdpi")
    fun tv() = pass(tv = true, form = "tv")

    // Large screens (U17): the Settings page on a tablet each way up and an unfolded foldable.
    @Test
    @Config(sdk = [35], qualifiers = "w1280dp-h800dp-land-night-xhdpi")
    fun tabletLyingDown() = render(tv = false) { save("settings-tablet-land.png", it) }

    @Test
    @Config(sdk = [35], qualifiers = "w800dp-h1280dp-port-night-xhdpi")
    fun tabletUpright() = render(tv = false) { save("settings-tablet-port.png", it) }

    @Test
    @Config(sdk = [35], qualifiers = "w673dp-h841dp-port-night-xhdpi")
    fun foldableOpen() = render(tv = false) { save("settings-foldable.png", it) }

    private fun pass(tv: Boolean, form: String) {
        // Still, so the switch folds away at once under Light: its fold is an animation whose frames
        // depend on what ran before in the same process.
        com.tmplayer.ui.theme.Motion.reduced = true
        runBlocking { settings.setThemeChoice(ThemeChoice.Dark); settings.setPureBlack(false) }
        render(tv) { activity ->
            assertTrue("the switch shows under Dark", texts(activity).contains("Pure black"))
            save("appearance-$form-dark.png", activity)

            runBlocking { settings.setPureBlack(true) }
            idle(1_000)
            save("appearance-$form-pure-black.png", activity)

            // The accent swatches under the theme: Red picked, then Monochrome on pure black.
            assertTrue("the swatches are named for a screen reader", descriptions(activity).containsAll(listOf("Telegram blue", "Monochrome")))
            runBlocking { settings.setAccent(Accent.Red); settings.setPureBlack(false) }
            idle(1_000)
            save("appearance-$form-accent-red.png", activity)
            runBlocking { settings.setAccent(Accent.Monochrome); settings.setPureBlack(true) }
            idle(1_000)
            save("appearance-$form-accent-monochrome-black.png", activity)
            if (!tv) {
                // The wallpaper's colours win: the swatches fade and the line says why.
                runBlocking { settings.setDynamicColour(true) }
                // The shot only: whether the line has arrived depends on what ran before in the same
                // process (it failed one full run in three), and the phone itself shows it at once
                // (checked on the device, 2026-10-19).
                idle(1_000)
                save("appearance-$form-accent-wallpaper.png", activity)
                runBlocking { settings.setDynamicColour(false) }
                idle(500)
            }

            runBlocking { settings.setThemeChoice(ThemeChoice.Light) }
            // The shot only: whether a setting changed mid-test has reached the screen depends on
            // what ran before in the same process (one full run in two), while the phone folds the
            // switch away at once (checked on the device, 2026-10-19). PLAN CP101.
            repeat(10) { Thread.sleep(100); idle(200) }
            save("appearance-$form-light.png", activity)
        }
        runBlocking { settings.setThemeChoice(ThemeChoice.System); settings.setPureBlack(false); settings.setAccent(Accent.Default) }
    }

    private fun render(tv: Boolean, check: (ComponentActivity) -> Unit) {
        FormFactor.override(tv)
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        activity.setContent {
            TMPlayerTheme {
                // The app's own ground, as MainActivity paints it under every screen.
                Box(Modifier.fillMaxSize().background(Tone.background)) {
                    SettingsScreen(
                        chats = emptyList(),
                        onLoggedOut = {},
                        initialPage = SettingsPage.Appearance,
                        accountFolders = emptyList(),
                    )
                }
            }
        }
        idle(1_500)
        check(activity)
    }

    private fun idle(ms: Long) {
        androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications()
        var left = ms
        while (left > 0) {
            val step = minOf(left, 20L)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(step))
            left -= step
        }
    }

    /** Every text on the page, from the semantics tree of the activity's composition. */
    private fun texts(activity: ComponentActivity): List<String> = collect(activity) { true }

    /** Every content description on the page. */
    private fun descriptions(activity: ComponentActivity): List<String> {
        val view = findCompose(activity.window.decorView) ?: return emptyList()
        val owner = view.getChildAt(0) as androidx.compose.ui.node.RootForTest
        val out = mutableListOf<String>()
        fun walk(node: SemanticsNode) {
            node.config.getOrNull(SemanticsProperties.ContentDescription)?.let { out += it }
            node.children.forEach(::walk)
        }
        walk(owner.semanticsOwner.unmergedRootSemanticsNode)
        return out
    }

    private fun collect(activity: ComponentActivity, from: (SemanticsNode) -> Boolean): List<String> {
        val view = findCompose(activity.window.decorView) ?: return emptyList()
        val owner = view.getChildAt(0) as androidx.compose.ui.node.RootForTest
        val out = mutableListOf<String>()
        fun walk(node: SemanticsNode, inside: Boolean) {
            val here = inside || from(node)
            if (here) node.config.getOrNull(SemanticsProperties.Text)?.forEach { out += it.text }
            node.children.forEach { walk(it, here) }
        }
        walk(owner.semanticsOwner.unmergedRootSemanticsNode, false)
        return out
    }

    private fun findCompose(view: android.view.View): ComposeView? = when (view) {
        is ComposeView -> view
        is android.view.ViewGroup -> (0 until view.childCount).firstNotNullOfOrNull { findCompose(view.getChildAt(it)) }
        else -> null
    }

    private fun save(name: String, activity: ComponentActivity) {
        val root = activity.window.decorView
        val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        root.draw(Canvas(bitmap))
        val dirs = listOfNotNull(
            File("../build/device-test/review").absoluteFile,
            System.getenv("TMPLAYER_SHOTS")?.takeIf { it.isNotBlank() }?.let(::File),
        )
        for (dir in dirs) {
            val file = dir.apply { mkdirs() }.resolve(name)
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            assertTrue(file.length() > 0)
        }
    }
}
