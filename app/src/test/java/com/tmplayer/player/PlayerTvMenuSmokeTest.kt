package com.tmplayer.player

import android.os.Looper
import android.widget.FrameLayout
import androidx.fragment.app.FragmentActivity
import com.tmplayer.data.FormFactor
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The television player's More menu, composed for real on the JVM.
 *
 * 1.22.0 shipped a menu whose content called itself through ComposeView.Content, and every video
 * on every television died of a stack overflow five seconds in. Nothing caught it because the
 * phone never builds this menu and no test drew it. Composing it here is the whole check: a
 * screen that cannot compose throws out of the looper and fails the build.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "television")
class PlayerTvMenuSmokeTest {

    private lateinit var activity: FragmentActivity
    private lateinit var root: FrameLayout

    @Before
    fun setUp() {
        FormFactor.override(true)
        activity = Robolectric.buildActivity(FragmentActivity::class.java).setup().get()
        root = FrameLayout(activity)
        activity.setContentView(root)
    }

    @After
    fun tearDown() {
        FormFactor.override(false)
    }

    private fun menu() = PlayerTvMenu(
        activity = activity,
        root = root,
        title = { "Bethlehem Kudumba Unit" },
        pictureInPicture = { true },
        speed = { 1f },
        saveToDownloads = { true },
        markWatched = { true },
        watched = { false },
        onEntry = {},
        onSpeed = {},
        onClosed = {},
    )

    @Test
    fun `the closed menu composes over the player`() {
        val menu = menu()
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(menu.isOpen)
    }

    @Test
    fun `the open menu composes`() {
        val menu = menu()
        shadowOf(Looper.getMainLooper()).idle()
        menu.open()
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(menu.isOpen)
    }

    @Test
    fun `the speed, sleep timer and details sheets compose`() {
        val sheets = PlayerSheets(activity, root, onClosed = {})
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(sheets.isOpen)
        sheets.showSpeed(1.5f) {}
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(sheets.isOpen)
        sheets.showSleep(running = "23 minutes left", chosen = 30) {}
        shadowOf(Looper.getMainLooper()).idle()
        sheets.showDetails("Bethlehem Kudumba Unit", listOf("Picture 1920 x 1080", "File 1.4 GB"))
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(sheets.isOpen)
    }
}
