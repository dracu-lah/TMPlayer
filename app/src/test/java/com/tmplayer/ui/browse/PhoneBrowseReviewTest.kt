package com.tmplayer.ui.browse

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getAllSemanticsNodes
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.tmplayer.data.ChatInfo
import com.tmplayer.data.ChatKind
import com.tmplayer.data.ChatSummary
import com.tmplayer.i18n.L
import com.tmplayer.ui.DrainMainLooperRule
import com.tmplayer.ui.theme.TMPlayerTheme
import com.tmplayer.ui.icons.TmIcons
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
 * The phone's browse screens, reviewed control by control (review wave R2): the selection bars at
 * a 2x font, the profile popover's rows, the chat tile, a switch's state, the chat sheet's link
 * and its buttons, the Retry / Send logs row, and the pills' roles.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PhoneBrowseReviewTest {

    /** A state write left on the looper must not silence Compose for the next test (HX92). */
    @get:Rule
    val drain = DrainMainLooperRule()

    private fun open(fontScale: Float = 1f, content: @Composable () -> Unit): ComponentActivity {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        activity.window.decorView.dispatchWindowFocusChanged(true)
        activity.setContent {
            val base = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(base.density, fontScale)) {
                TMPlayerTheme { content() }
            }
        }
        idle()
        return activity
    }

    private fun idle() = repeat(10) { shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(100)) }

    private fun nodes(activity: ComponentActivity, merged: Boolean = true): List<SemanticsNode> {
        val out = mutableListOf<SemanticsNode>()
        fun walk(view: View) {
            if (view is ViewRootForTest) out += view.semanticsOwner.getAllSemanticsNodes(mergingEnabled = merged)
            if (view is ViewGroup) for (i in 0 until view.childCount) walk(view.getChildAt(i))
        }
        walk(activity.window.decorView)
        return out
    }

    private fun described(node: SemanticsNode) = node.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty()
    private fun words(node: SemanticsNode) = node.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text }

    private fun byWords(activity: ComponentActivity, text: String) = nodes(activity).firstOrNull { node -> words(node).any { it == text } }
    private fun byName(activity: ComponentActivity, name: String) = nodes(activity).firstOrNull { node -> name in described(node) }

    private fun assertInside(activity: ComponentActivity, node: SemanticsNode) {
        val w = activity.window.decorView.width.toFloat()
        val b = node.boundsInRoot
        assertTrue("$b left of the screen", b.left >= 0f)
        assertTrue("$b right of the screen", b.right <= w + 1f)
    }

    // The selection bars at a large font -------------------------------------------------

    @Test
    @Config(sdk = [35], qualifiers = "w320dp-h640dp-port-night-xhdpi")
    fun `a chat's selection bar keeps the count on screen at a 2x font, its actions as named icons`() {
        val activity = open(fontScale = 2f) {
            SelectionBar(count = 3, onDownload = {}, onSelectAll = {}, onCancel = {}, edge = 16.dp)
        }
        val count = byWords(activity, L.gridSelectedCount(3))
        assertNotNull("the count is drawn", count)
        assertInside(activity, count!!)
        val minWidth = activity.resources.displayMetrics.density * 72f
        assertTrue("the count has room to be read (${count.boundsInRoot.width}px)", count.boundsInRoot.width >= minWidth)
        for (name in listOf(L.gridSelectAll, L.gridDownloadSelected)) {
            val button = byName(activity, name)
            assertNotNull("$name is still on the bar, named", button)
            assertInside(activity, button!!)
            assertTrue("$name can be pressed", button.config.getOrNull(SemanticsActions.OnClick) != null)
        }
    }

    @Test
    @Config(sdk = [35], qualifiers = "w320dp-h640dp-port-night-xhdpi")
    fun `a chat's selection bar keeps its words at a normal font`() {
        val activity = open {
            SelectionBar(count = 3, onDownload = {}, onSelectAll = {}, onCancel = {}, edge = 16.dp)
        }
        assertNotNull(byWords(activity, L.gridSelectAll))
        assertNotNull(byWords(activity, L.gridDownloadSelected))
    }

    @Test
    @Config(sdk = [35], qualifiers = "w320dp-h640dp-port-night-xhdpi")
    fun `History's selection bar keeps the count on screen at a 2x font, its actions as named icons`() {
        val insets = BrowseInsets(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 16.dp)
        val activity = open(fontScale = 2f) {
            HistorySelectionBar(
                count = 3, all = false, insets = insets, removeLabel = "Remove (3)",
                onSelectAll = {}, onSelectNone = {}, onRemove = {}, onDone = {},
            )
        }
        val count = byWords(activity, L.browseSelectedCount(3))
        assertNotNull("the count is drawn", count)
        assertInside(activity, count!!)
        val minWidth = activity.resources.displayMetrics.density * 72f
        assertTrue("the count has room to be read (${count.boundsInRoot.width}px)", count.boundsInRoot.width >= minWidth)
        for (name in listOf(L.browseSelectAll, "Remove (3)", L.commonDone)) {
            val button = byName(activity, name)
            assertNotNull("$name is on the bar, named", button)
            assertInside(activity, button!!)
        }
    }

    // The profile popover ----------------------------------------------------------------

    @Test
    @Config(sdk = [35], qualifiers = "w320dp-h640dp-port-night-xhdpi")
    fun `a popover row is a thumb's 48 dp tall and grows with a long word at a 2x font`() {
        val density = 2f
        val short = open {
            PopoverRow(TmIcons.History, L.browseTabHistory, null, onClick = {})
        }
        val row = nodes(short).first { it.config.getOrNull(SemanticsProperties.Role) == Role.Button }
        assertTrue("48 dp at least, was ${row.boundsInRoot.height}px", row.boundsInRoot.height >= short.resources.displayMetrics.density * 48f - 1f)

        val large = open(fontScale = density) {
            PopoverRow(TmIcons.Clock, "A watch later label long enough to wrap at a large font", "12", onClick = {})
        }
        val tall = nodes(large).first { it.config.getOrNull(SemanticsProperties.Role) == Role.Button }
        assertTrue("a wrapped label makes the row taller, not cut", tall.boundsInRoot.height > row.boundsInRoot.height)
    }

    // The recent chat tile ---------------------------------------------------------------

    private fun chat(title: String) = ChatSummary(7, title, null, 0, ChatKind.Channel)

    private fun titleFits(activity: ComponentActivity, title: String): Boolean {
        // Unmerged: the tile's own node is the whole card, and the text's is what is measured.
        val node = nodes(activity, merged = false).firstOrNull { n -> words(n).any { it == title } } ?: return false
        val layout = ArrayList<TextLayoutResult>()
        node.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(layout)
        val text = layout.firstOrNull() ?: return false
        return node.size.height >= text.multiParagraph.height.toInt()
    }

    @Test
    @Config(sdk = [35], qualifiers = "w411dp-h891dp-port-night-xhdpi")
    fun `the recent chat tile shows its whole name line at 1_3x and 2x fonts`() {
        for (scale in listOf(1f, 1.3f, 2f)) {
            val activity = open(fontScale = scale) {
                ChatTile(
                    chat = chat("Harbour Lights"),
                    favorite = false,
                    opensOnLaunch = false,
                    onClick = {},
                    onHold = {},
                )
            }
            assertTrue("the name is not cut vertically at a $scale font", titleFits(activity, "Harbour Lights"))
        }
    }

    // A switch and its words -------------------------------------------------------------

    @Test
    @Config(sdk = [35], qualifiers = "w320dp-h640dp-port-night-xhdpi")
    fun `a filter switch line says on or off to a screen reader and toggles on a press`() {
        var on = false
        var told: Boolean? = null
        val activity = open(fontScale = 2f) {
            SwitchLine("Hide small videos and clips from the results", on) { told = it }
        }
        val line = nodes(activity).first { it.config.getOrNull(SemanticsProperties.Role) == Role.Switch }
        assertEquals(ToggleableState.Off, line.config.getOrNull(SemanticsProperties.ToggleableState))
        assertTrue("a wrapped label is not cut at 56 dp", line.boundsInRoot.height >= activity.resources.displayMetrics.density * 56f)
        line.config[SemanticsActions.OnClick].action?.invoke()
        idle()
        assertEquals("a press says what it was turned to", true, told)
    }

    // The chat sheet ---------------------------------------------------------------------

    private class NoApp(base: Context) : ContextWrapper(base) {
        var started = 0
        override fun startActivity(intent: Intent?) {
            started++
            throw ActivityNotFoundException("nothing opens it")
        }
    }

    private class Records(base: Context) : ContextWrapper(base) {
        var last: Intent? = null
        override fun startActivity(intent: Intent?) {
            last = intent
        }
    }

    @Test
    @Config(sdk = [35], qualifiers = "w320dp-h640dp-port-night-xhdpi")
    fun `opening a chat's link with nothing to open it says so instead of crashing`() {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        val none = NoApp(activity)
        assertFalse(openLink(none, "https://t.me/harbour"))
        assertEquals(1, none.started)
        val works = Records(activity)
        assertTrue(openLink(works, "https://t.me/harbour"))
        assertEquals("https://t.me/harbour", works.last?.dataString)
    }

    @Test
    @Config(sdk = [35], qualifiers = "w320dp-h640dp-port-night-xhdpi")
    fun `the chat sheet's username is a thumb's size and its Share and Copy link stay on screen at 2x`() {
        val info = ChatInfo(chat("Harbour Lights"), 0, "harbour_lights", "About the harbour", 1200)
        val activity = open(fontScale = 2f) {
            Column { ChatInfoBody(info, "Harbour Lights", 0, null, touch = true) }
        }
        val username = nodes(activity).first { node -> words(node).any { it == "@harbour_lights" } }
        assertTrue("48 dp tall", username.boundsInRoot.height >= activity.resources.displayMetrics.density * 48f - 1f)
        assertEquals(Role.Button, username.config.getOrNull(SemanticsProperties.Role))
        for (label in listOf(L.commonShare, L.commonCopyLink)) {
            val button = nodes(activity).firstOrNull { node -> words(node).any { it == label } }
            assertNotNull("$label is drawn", button)
            assertInside(activity, button!!)
        }
    }

    // Retry and Send logs ----------------------------------------------------------------

    @Test
    @Config(sdk = [35], qualifiers = "w320dp-h640dp-port-night-xhdpi")
    fun `the topic search failure keeps Retry on screen at a 2x font`() {
        val activity = open(fontScale = 2f) { TopicsFailed("Telegram did not answer", onRetry = {}) }
        val retry = byWords(activity, L.commonRetry)
        assertNotNull(retry)
        assertInside(activity, retry!!)
    }

    @Test
    @Config(sdk = [35], qualifiers = "w320dp-h640dp-port-night-xhdpi")
    fun `a note and its button wrap at a 2x font instead of squeezing the words into a column`() {
        val note = "Only the newest 500 messages of this chat were searched."
        val activity = open(fontScale = 2f) { SearchNoteRow(note, onDeeper = {}) }
        val words = byWords(activity, note)
        val button = byWords(activity, L.commonKeepLooking)
        assertNotNull(words)
        assertNotNull(button)
        assertInside(activity, button!!)
        val room = activity.resources.displayMetrics.density * 200f
        assertTrue("the words keep a readable width (${words!!.boundsInRoot.width}px)", words.boundsInRoot.width >= room)
        assertTrue("the button sits under the words", button.boundsInRoot.top >= words.boundsInRoot.bottom - 1f)
    }

    // The pills --------------------------------------------------------------------------

    @Test
    @Config(sdk = [35], qualifiers = "w320dp-h640dp-port-night-xhdpi")
    fun `header actions and pills are buttons, and a worded one is named once`() {
        val activity = open {
            Column {
                HeaderAction(label = "Select", icon = TmIcons.Checklist, onClick = {})
                HeaderAction(label = "Only icon", icon = TmIcons.Checklist, onClick = {}, showLabel = false)
                PillButton(label = "Clear", icon = TmIcons.Close, onClick = {})
            }
        }
        val buttons = nodes(activity).filter { it.config.getOrNull(SemanticsProperties.Role) == Role.Button }
        assertEquals("three buttons", 3, buttons.size)
        val worded = buttons.first { "Select" in words(it) }
        assertTrue("a worded action is not also named by its icon: ${described(worded)}", described(worded).isEmpty())
        val iconOnly = buttons.first { "Only icon" in described(it) }
        assertNull(iconOnly.config.getOrNull(SemanticsProperties.Text))
    }
}
