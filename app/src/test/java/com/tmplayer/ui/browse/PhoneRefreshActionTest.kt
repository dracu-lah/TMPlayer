package com.tmplayer.ui.browse

import android.os.Looper
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getAllSemanticsNodes
import androidx.compose.ui.semantics.getOrNull
import com.tmplayer.data.WorkStatus
import com.tmplayer.i18n.L
import com.tmplayer.ui.DrainMainLooperRule
import com.tmplayer.ui.components.AskedRefresh
import com.tmplayer.ui.theme.TMPlayerTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Refresh without a gesture on the phone's Home and Chats (WP-40): the page offers "Refresh" as a
 * TalkBack and Switch Access action (the pull has no word for them), the action runs the page's own
 * refresh once even when pressed twice while it runs, a page with nothing to fetch offers none, and a
 * failed refresh says so with Try again over the page.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PhoneRefreshActionTest {

    @get:Rule
    val drain = DrainMainLooperRule()

    private fun open(content: @Composable () -> Unit): ComponentActivity {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        activity.window.decorView.dispatchWindowFocusChanged(true)
        activity.setContent { TMPlayerTheme { content() } }
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

    private fun refreshAction(activity: ComponentActivity) = nodes(activity)
        .flatMap { it.config.getOrNull(SemanticsActions.CustomActions).orEmpty() }
        .firstOrNull { it.label == L.commonRefresh }

    private fun page(refresh: (() -> Unit)?, status: WorkStatus = WorkStatus.Idle) = open {
        PhonePageFrame(
            section = BrowseSection.of(BrowseTab.Chats),
            count = 0,
            refresh = refresh,
            refreshStatus = status,
            pane = { Text("The list") },
        )
    }

    @Test
    @Config(sdk = [35], qualifiers = "w360dp-h740dp-port-xhdpi")
    fun `a page with a refresh offers Refresh as a screen reader action that runs it`() {
        var runs = 0
        val activity = page(refresh = { runs++ })
        val action = refreshAction(activity)
        assertNotNull("TalkBack offers Refresh", action)
        assertTrue(action!!.action!!.invoke())
        assertEquals(1, runs)
    }

    @Test
    @Config(sdk = [35], qualifiers = "w360dp-h740dp-port-xhdpi")
    fun `the action goes through the guard, so a second press while it runs starts nothing`() {
        var runs = 0
        val asked = AskedRefresh(floodWait = { 0 })
        val activity = page(refresh = { asked.ask { runs++ } })
        val action = refreshAction(activity)!!
        action.action!!.invoke()
        action.action!!.invoke()
        action.action!!.invoke()
        assertEquals("one refresh, not three", 1, runs)
    }

    @Test
    @Config(sdk = [35], qualifiers = "w360dp-h740dp-port-xhdpi")
    fun `a page with nothing to fetch offers no Refresh action`() {
        val activity = page(refresh = null)
        assertNull(refreshAction(activity))
    }

    @Test
    @Config(sdk = [35], qualifiers = "w360dp-h740dp-port-xhdpi")
    fun `a refresh that could not update says so over the page with Try again, never blanking the list`() {
        val activity = page(refresh = {}, status = WorkStatus.Failed("", keptAt = System.currentTimeMillis()))
        val all = nodes(activity)
        // The line's words are its spoken description (one live region node); the buttons' are text.
        val words = all.flatMap { it.config.getOrNull(SemanticsProperties.Text).orEmpty().map { t -> t.text } } +
            all.flatMap { it.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty() }
        assertTrue("the list stays", "The list" in words)
        assertTrue("it says it could not update: $words", words.any { it.startsWith("Could not update") })
        assertTrue("Try again is there: $words", L.commonTryAgain in words)
    }
}
