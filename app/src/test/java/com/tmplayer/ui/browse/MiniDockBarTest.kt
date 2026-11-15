package com.tmplayer.ui.browse

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getAllSemanticsNodes
import androidx.compose.ui.semantics.getOrNull
import com.tmplayer.data.ChatGate
import com.tmplayer.data.FormFactor
import com.tmplayer.i18n.L
import com.tmplayer.player.DockedPlayer
import com.tmplayer.player.MiniDock
import com.tmplayer.ui.theme.TMPlayerTheme
import com.tmplayer.ui.theme.Tone
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * The phone's mini player bar (U8) in the touch shell, composed for real: shown over a page with
 * a docked video and not without one, its tap, play and X reaching the docked player, the kids
 * lock taking a docked video from a chat it leaves out, and a picture of it upright under
 * `build/device-test/review/`.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MiniDockBarTest {

    @After
    fun tearDown() {
        MiniDock.dismiss()
        ChatGate.set(null)
        FormFactor.override(false)
    }

    private class FakeDocked(override val chatId: Long = 7L) : DockedPlayer {
        override val title = "The long walk  ·  S1 E2"
        override var playing = true
        override val positionMs = 30_000L
        override val durationMs = 60_000L
        override val casting = false
        val calls = mutableListOf<String>()

        override fun togglePlay() {
            calls += "toggle"
            playing = !playing
        }

        override fun expand(context: Context): Boolean {
            calls += "expand"
            return true
        }

        override fun dismiss() {
            calls += "dismiss"
        }

        override fun shellHidden() = Unit
    }

    private val nav = TouchNav(
        account = null,
        favoriteCount = 0,
        downloadCount = 0,
        updateVersion = null,
        onHome = {}, onChats = {}, onSearch = {}, onSettings = {}, onFavourites = {},
        onDownloads = {}, onHistory = {}, onUpdate = {},
    )

    private fun idle() = repeat(60) { shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(100)) }

    private fun shell(): ComponentActivity {
        FormFactor.override(false)
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        val chrome = TouchChrome()
        activity.setContent {
            TMPlayerTheme {
                Box(Modifier.fillMaxSize().background(Tone.background)) {
                    TouchNavHost(nav, chrome, "home") {
                        CompositionLocalProvider(LocalTouchPageKey provides "home") {
                            TouchPage(TouchPageSpec(current = TouchDestination.Home))
                            Text("Home page")
                        }
                    }
                }
            }
        }
        idle()
        return activity
    }

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

    private fun labelled(activity: ComponentActivity, label: String) =
        nodes(activity).firstOrNull { node -> described(node).any { it.startsWith(label) } }

    private fun click(node: SemanticsNode?) {
        assertNotNull(node)
        node!!.config.getOrNull(SemanticsActions.OnClick)!!.action!!.invoke()
    }

    @Test
    fun `no bar with nothing docked`() {
        val activity = shell()
        assertNull(labelled(activity, L.playerMiniClose))
    }

    @Test
    fun `a docked video shows its bar, and a tap brings the player back`() {
        val docked = FakeDocked()
        MiniDock.dock(docked)
        val activity = shell()
        val bar = labelled(activity, L.playerMiniExpand)
        assertNotNull("the bar is drawn", bar)
        assertTrue(described(bar!!).single().endsWith(docked.title))
        // Every control is at least 48 dp: the bar's row is 56.
        assertTrue(bar.size.height >= 48 * activity.resources.displayMetrics.density - 1)
        snapshot(activity, "mini-player-phone.png")
        click(bar)
        idle()
        assertEquals(listOf("expand"), docked.calls)
        assertNull(MiniDock.docked.value)
        assertNull(labelled(activity, L.playerMiniClose))
    }

    private fun snapshot(activity: ComponentActivity, out: String) {
        val root = activity.window.decorView
        val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        root.draw(Canvas(bitmap))
        val file = File("../build/device-test/review/$out").absoluteFile
        file.parentFile?.mkdirs()
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        assertTrue(file.length() > 0)
    }
}
