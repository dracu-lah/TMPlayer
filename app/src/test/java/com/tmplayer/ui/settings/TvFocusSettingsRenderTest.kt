package com.tmplayer.ui.settings

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.dp
import com.tmplayer.data.FormFactor
import com.tmplayer.data.SettingsStore
import com.tmplayer.data.ThemeChoice
import com.tmplayer.ui.components.TvChoiceRow
import com.tmplayer.ui.theme.FocusContrast
import com.tmplayer.ui.theme.Motion
import com.tmplayer.ui.theme.TMPlayerTheme
import com.tmplayer.ui.theme.Tone
import com.tmplayer.ui.theme.contrastRatio
import com.tmplayer.ui.theme.floatingSurface
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * The television's Appearance page with the remote on a row, in dark and Pure black, with and
 * without High contrast focus (the light theme does not take on this page in the harness, and is
 * read back on the picker and the tiles in [TvFocusRenderTest]): the frames in `build/device-test/review/` only, since every row
 * of it draws its focus with the same [com.tmplayer.ui.theme.focusRing] the picker line is read
 * back through in [TvFocusRenderTest]. Its own class: a Settings page left in the process throws
 * the focus requests of the next test off.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w960dp-h540dp-land-television-night-xhdpi")
class TvFocusSettingsRenderTest {

    private val settings get() = SettingsStore(RuntimeEnvironment.getApplication())

    @After
    fun tearDown() {
        FormFactor.override(false)
        Motion.reduced = false
    }

    private enum class Page(val choice: ThemeChoice, val black: Boolean) {
        Dark(ThemeChoice.Dark, false),
        Black(ThemeChoice.Dark, true),
    }

    /**
     * The television's Appearance page with the remote on a row (its landing row, then one down):
     * the frames only, since every row of it draws its focus with the same [focusRing].
     */
    private fun shoot(page: Page, high: Boolean) {
        FormFactor.override(true)
        runBlocking {
            settings.setThemeChoice(page.choice)
            settings.setPureBlack(page.black)
            settings.setHighContrastFocus(high)
        }
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        activity.setContent {
            TMPlayerTheme {
                Box(Modifier.fillMaxSize().background(Tone.background)) {
                    com.tmplayer.ui.settings.SettingsScreen(
                        chats = emptyList(),
                        onLoggedOut = {},
                        initialPage = com.tmplayer.ui.settings.SettingsPage.Appearance,
                        accountFolders = emptyList(),
                    )
                }
            }
        }
        idle(1_000)
        // Set once the page is up, as AppearanceRenderTest does: set before, the page's own start-up
        // read the defaults.
        runBlocking {
            settings.setThemeChoice(page.choice)
            settings.setPureBlack(page.black)
            settings.setHighContrastFocus(high)
        }
        repeat(10) { Thread.sleep(100); idle(200) }
        windowForRemote(activity)
        for (n in 1..2) {
            activity.dispatchKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_DPAD_DOWN))
            activity.dispatchKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, android.view.KeyEvent.KEYCODE_DPAD_DOWN))
            idle(500)
        }
        save("focus-tv-${page.name.lowercase()}-${if (high) "high" else "standard"}-settings.png", capture(activity))
    }

    /** A window the remote works in: out of touch mode and holding focus, as on a television. */
    private fun windowForRemote(activity: ComponentActivity) {
        val viewRoot = activity.window.decorView.parent
        org.robolectric.util.ReflectionHelpers.callInstanceMethod<Any>(
            viewRoot, "ensureTouchMode",
            org.robolectric.util.ReflectionHelpers.ClassParameter.from(Boolean::class.javaPrimitiveType, false),
        )
        org.robolectric.util.ReflectionHelpers.callInstanceMethod<Any>(
            viewRoot, "windowFocusChanged",
            org.robolectric.util.ReflectionHelpers.ClassParameter.from(Boolean::class.javaPrimitiveType, true),
        )
        idle(300)
    }

    private fun capture(activity: ComponentActivity): Bitmap {
        val root = activity.window.decorView
        val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        root.draw(Canvas(bitmap))
        return bitmap
    }

    private fun idle(ms: Long) {
        androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications()
        var left = ms
        while (left > 0) {
            val step = minOf(left, 20L)
            shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(step))
            left -= step
        }
    }

    private fun save(name: String, bitmap: Bitmap) {
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

    @Test
    fun darkStandard() = shoot(Page.Dark, high = false)

    @Test
    fun darkHigh() = shoot(Page.Dark, high = true)

    @Test
    fun blackStandard() = shoot(Page.Black, high = false)

    @Test
    fun blackHigh() = shoot(Page.Black, high = true)
}
