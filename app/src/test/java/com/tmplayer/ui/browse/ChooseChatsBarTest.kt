package com.tmplayer.ui.browse

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getAllSemanticsNodes
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.tmplayer.data.FormFactor
import com.tmplayer.ui.theme.TMPlayerTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * The Choose chats button (a person with a plus) on the phone's Chats bar and on the
 * television's top bar (Chats only; Home's corner has none): where it stands at the smallest width, on its side, at a large
 * font and right to left (never over the name or off the screen), and the remote reaching it,
 * pressing it, and coming back to it. Pictures go to `build/device-test/review/`.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ChooseChatsBarTest {

    @Before
    fun clean() {
        AddChatsRequest.take()
    }

    @After
    fun tearDown() {
        FormFactor.override(false)
        AddChatsRequest.take()
    }

    private val label get() = com.tmplayer.i18n.L.pickerChooseChats

    private fun open(content: @Composable () -> Unit): ComponentActivity {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        activity.window.decorView.dispatchWindowFocusChanged(true)
        activity.setContent { content() }
        idle()
        return activity
    }

    private fun idle() = repeat(10) { shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(100)) }

    private fun nodes(activity: ComponentActivity): List<SemanticsNode> {
        val out = mutableListOf<SemanticsNode>()
        fun walk(view: View) {
            if (view is ViewRootForTest) out += view.semanticsOwner.getAllSemanticsNodes(mergingEnabled = true)
            if (view is ViewGroup) for (i in 0 until view.childCount) walk(view.getChildAt(i))
        }
        walk(activity.window.decorView)
        return out
    }

    private fun described(node: SemanticsNode) = node.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty()
    private fun words(node: SemanticsNode) = node.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text }

    private fun button(activity: ComponentActivity) = nodes(activity).firstOrNull { label in described(it) }
    private fun brand(activity: ComponentActivity) = nodes(activity).firstOrNull { node -> described(node).any { it.startsWith("TMPlayer") } }
    private fun focused(activity: ComponentActivity) = nodes(activity).firstOrNull { it.config.getOrNull(SemanticsProperties.Focused) == true }

    private fun draw(activity: ComponentActivity, out: String) {
        val root = activity.window.decorView
        val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        root.draw(Canvas(bitmap))
        val file = File("../build/device-test/review/$out").absoluteFile
        file.parentFile?.mkdirs()
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        assertTrue(file.length() > 0)
    }

    private fun assertInside(activity: ComponentActivity, bounds: Rect) {
        val w = activity.window.decorView.width.toFloat()
        val h = activity.window.decorView.height.toFloat()
        assertTrue("$bounds left of the screen", bounds.left >= 0f)
        assertTrue("$bounds right of the screen", bounds.right <= w)
        assertTrue("$bounds top of the screen", bounds.top >= 0f)
        assertTrue("$bounds foot of the screen", bounds.bottom <= h)
    }

    private fun assertApart(a: Rect, b: Rect) = assertFalse("$a overlaps $b", a.overlaps(b))

    // The phone's Chats bar ---------------------------------------------------------------

    private fun chatsBar(fontScale: Float) = open {
        val base = LocalDensity.current
        CompositionLocalProvider(
            LocalDensity provides Density(base.density, fontScale),
        ) {
            TMPlayerTheme {
                PhonePageFrame(
                    section = BrowseSection.of(BrowseTab.Chats),
                    count = 0,
                    refresh = null,
                    pane = { Text("The list") },
                )
            }
        }
    }

    @Test
    @Config(sdk = [35], qualifiers = "w320dp-h640dp-port-night-xhdpi")
    fun `the Chats bar carries the button at 320 dp and a 1_3 font, clear of the title`() {
        val activity = chatsBar(fontScale = 1.3f)
        val node = button(activity)
        assertNotNull("the Chats bar has Choose chats", node)
        assertInside(activity, node!!.boundsInRoot)
        val title = nodes(activity).first { "Chats" in words(it) }
        assertApart(node.boundsInRoot, title.boundsInRoot)
        val px = activity.resources.displayMetrics.density * 48f
        assertEquals("a thumb's target whatever the font", px, node.boundsInRoot.width, 1f)
        assertTrue("it is pressable", node.config.getOrNull(SemanticsActions.OnClick) != null)
        draw(activity, "choose-chats-phone-chats-320-font13.png")
    }

    @Test
    @Config(sdk = [35], qualifiers = "w640dp-h320dp-land-night-xhdpi")
    fun `the Chats bar on its side keeps the button on the screen`() {
        val activity = chatsBar(fontScale = 1f)
        assertInside(activity, button(activity)!!.boundsInRoot)
        draw(activity, "choose-chats-phone-chats-landscape.png")
    }

    @Test
    @Config(sdk = [35], qualifiers = "ar-ldrtl-w320dp-h640dp-port-night-xhdpi")
    fun `the Chats bar right to left puts the button on the left, still on the screen`() {
        val activity = chatsBar(fontScale = 1f)
        val node = button(activity)!!
        assertInside(activity, node.boundsInRoot)
        assertTrue("at the start of a right to left bar", node.boundsInRoot.center.x < activity.window.decorView.width / 2f)
    }

    @Test
    @Config(sdk = [35], qualifiers = "w320dp-h640dp-port-night-xhdpi")
    fun `a press asks for the screen once even when pressed twice`() {
        val activity = chatsBar(fontScale = 1f)
        val click = button(activity)!!.config.getOrNull(SemanticsActions.OnClick)!!.action!!
        click()
        click()
        idle()
        assertTrue(AddChatsRequest.take())
        assertFalse("the second press made no second page", AddChatsRequest.take())
    }

    // Home's corner over the picture --------------------------------------------------------

    private fun homeCorner(sideways: Boolean, fontScale: Float = 1f) = open {
        val base = LocalDensity.current
        CompositionLocalProvider(
            LocalDensity provides Density(base.density, fontScale),
        ) {
            TMPlayerTheme {
                Box(Modifier.fillMaxSize().background(Color(0xFF334455))) {
                    PhoneHomeCorner(sideways, start = 16.dp, end = 16.dp, under = 24.dp)
                }
            }
        }
    }

    @Test
    @Config(sdk = [35], qualifiers = "w320dp-h640dp-port-night-xhdpi")
    fun `Home's corner has the name and no button, Choose chats is on Chats and in Settings`() {
        val activity = homeCorner(sideways = false, fontScale = 1.3f)
        assertNull(button(activity))
        assertInside(activity, brand(activity)!!.boundsInRoot)
    }

    @Test
    @Config(sdk = [35], qualifiers = "w640dp-h320dp-land-night-xhdpi")
    fun `Home's corner on its side keeps the name on the screen with no button`() {
        val activity = homeCorner(sideways = true, fontScale = 1.3f)
        assertNull(button(activity))
        assertInside(activity, brand(activity)!!.boundsInRoot)
    }

    // The television's top bar --------------------------------------------------------------

    private val nav = TouchNav(
        account = null,
        favoriteCount = 0,
        downloadCount = 0,
        updateVersion = null,
        onHome = {}, onChats = {}, onSearch = {}, onSettings = {}, onFavourites = {},
        onDownloads = {}, onHistory = {}, onUpdate = {},
    )

    /** What BrowseScreen's return to the bar does: asked for a few frames, the bar's own entry taking the first. */
    private fun land(activity: ComponentActivity, on: FocusRequester) = repeat(4) {
        runCatching { on.requestFocus() }
        idle()
    }

    private fun press(activity: ComponentActivity, code: Int) {
        val now = SystemClock.uptimeMillis()
        activity.dispatchKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, code, 0))
        activity.dispatchKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, code, 0))
        idle()
    }

    @Test
    @Config(sdk = [28], qualifiers = "w960dp-h540dp-land-television-night-xhdpi")
    fun `a bar without the action has no button`() {
        FormFactor.override(true)
        val barFocus = FocusRequester()
        val activity = open {
            TMPlayerTheme {
                TvTopBar(
                    nav = nav, current = TvBarTab.Home, inProfile = false, searchOpen = false,
                    onHome = {}, onChats = {}, onSearch = {}, onRefresh = null,
                    currentFocus = barFocus, contentFocus = FocusRequester(), overArt = false,
                )
            }
        }
        assertNull(button(activity))
    }
}
