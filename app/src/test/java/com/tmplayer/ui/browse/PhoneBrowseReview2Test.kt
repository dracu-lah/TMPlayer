package com.tmplayer.ui.browse

import android.os.Looper
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getAllSemanticsNodes
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.tmplayer.data.ChatKind
import com.tmplayer.data.ChatSummary
import com.tmplayer.data.DetailAction
import com.tmplayer.data.FormFactor
import com.tmplayer.data.ForumTopicSummary
import com.tmplayer.data.MediaItem
import com.tmplayer.data.TelegramQuality
import com.tmplayer.data.WatchedStore
import com.tmplayer.ui.DrainMainLooperRule
import com.tmplayer.ui.icons.TmIcons
import com.tmplayer.ui.theme.TMPlayerTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.nio.file.Files

/**
 * The phone's detail pages, lists page, topic tabs and the Add chats picker, reviewed control by
 * control (review wave R5): every pill and round button read as a button, a finger's 48 dp round
 * the slim chips and tabs, a fixed height that cut a label at a large font, rows of buttons that
 * ran off a 320 dp screen at 2x, and the Telegram copies' names.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PhoneBrowseReview2Test {

    /** A state write left on the looper must not silence Compose for the next test (HX92). */
    @get:Rule
    val drain = DrainMainLooperRule()

    @After
    fun tearDown() {
        FormFactor.override(false)
    }

    private fun open(
        fontScale: Float = 1f,
        direction: LayoutDirection? = null,
        content: @Composable () -> Unit,
    ): ComponentActivity {
        FormFactor.override(false)
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        activity.window.decorView.dispatchWindowFocusChanged(true)
        activity.setContent {
            val base = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(base.density, fontScale),
                LocalLayoutDirection provides (direction ?: LocalLayoutDirection.current),
            ) { TMPlayerTheme { content() } }
        }
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

    /** The pressable node that reads [text] (by its words or its description). */
    private fun pressable(activity: ComponentActivity, text: String): SemanticsNode? = nodes(activity).firstOrNull { node ->
        node.config.getOrNull(SemanticsActions.OnClick) != null && (words(node).any { it == text } || text in described(node))
    }

    private fun dp(activity: ComponentActivity, px: Float) = px / activity.resources.displayMetrics.density

    private fun assertInside(activity: ComponentActivity, node: SemanticsNode, what: String) {
        val w = activity.window.decorView.width.toFloat()
        val b = node.boundsInRoot
        assertTrue("$what: $b left of the screen", b.left >= -1f)
        assertTrue("$what: $b right of the screen (${w}px)", b.right <= w + 1f)
    }

    private fun row(action: DetailAction, label: String, destructive: Boolean = false) =
        DetailRow(action = action, label = label, icon = TmIcons.Delete, destructive = destructive, onSelect = {})

    // Roles ---------------------------------------------------------------------------------

    @Test
    @Config(sdk = [35], qualifiers = "w360dp-h740dp-port-night-xhdpi")
    fun `the detail page's pills and round buttons are read as buttons`() {
        val activity = open {
            Column {
                HeroPill(label = "Play now", icon = TmIcons.Play, onClick = {})
                RoundAction(label = "Add to list", icon = TmIcons.Download, onClick = {})
                PlayButton("Resume S1E2", onClick = {})
                CopyChip("1080p", chosen = false, onClick = {})
                WideTextAction("Remove it", TmIcons.Delete, onClick = {})
                DetailActionRow(row(DetailAction.RemoveDownload, "Delete video"))
            }
        }
        for (name in listOf("Play now", "Add to list", "Resume S1E2", "1080p", "Remove it", "Delete video")) {
            val node = pressable(activity, name)
            assertNotNull("$name is pressable", node)
            assertEquals("$name reads as a button", Role.Button, node!!.config.getOrNull(SemanticsProperties.Role))
        }
    }

    @Test
    @Config(sdk = [35], qualifiers = "w360dp-h740dp-port-night-xhdpi")
    fun `the synopsis is a button while there is more to read`() {
        val long = (1..80).joinToString(" ") { "word$it" }
        val activity = open { Synopsis(long) }
        val node = pressable(activity, "More")
        assertNotNull("More is pressable", node)
        assertEquals(Role.Button, node!!.config.getOrNull(SemanticsProperties.Role))
    }

    // A finger's target -------------------------------------------------------------------------

    @Test
    @Config(sdk = [35], qualifiers = "w360dp-h740dp-port-night-xhdpi")
    fun `chips, topic tabs and the Play button give a finger 48 dp`() {
        val activity = open {
            Column {
                CopyChip("1080p", chosen = false, onClick = {})
                PlayButton("Play S1E2", onClick = {})
                TopicTabs(TopicBar(listOf(ForumTopicSummary(id = 1, name = "General"), ForumTopicSummary(id = 2, name = "News")), 0), onSelect = {})
                WideTextAction("Remove it", TmIcons.Delete, onClick = {})
            }
        }
        for (name in listOf("1080p", "Play S1E2", "General", "News", "Remove it")) {
            val node = pressable(activity, name)
            assertNotNull("$name is pressable", node)
            val height = dp(activity, node!!.size.height.toFloat())
            assertTrue("$name is $height dp tall", height >= 47.9f)
        }
    }

    // A large font -------------------------------------------------------------------------

    @Test
    @Config(sdk = [35], qualifiers = "w320dp-h640dp-port-night-xhdpi")
    fun `the Play button keeps its whole label at a 2x font`() {
        val activity = open(fontScale = 2f) { PlayButton("Resume S1E2", onClick = {}) }
        val button = pressable(activity, "Resume S1E2")
        assertNotNull(button)
        val layout = textLayout(button!!)
        assertNotNull("the label has a layout", layout)
        assertTrue("the label is whole on its lines", layout!!.lineCount <= 2 && !layout.didOverflowHeight)
        assertTrue("the button holds its label (${button.size.height}px)", button.size.height >= layout.size.height)
        assertInside(activity, button, "Play button")
    }

    @Test
    @Config(sdk = [35], qualifiers = "w320dp-h640dp-port-night-xhdpi")
    fun `a detail action line wraps a long label at 2x instead of cutting it`() {
        val label = "Mark the whole season as watched"
        val activity = open(fontScale = 2f) { DetailActionRow(row(DetailAction.RemoveDownload, label)) }
        val node = nodes(activity).firstOrNull { node -> words(node).any { it == label } }
        assertNotNull(node)
        val layout = textLayout(node!!)
        assertNotNull(layout)
        assertTrue("the label shows to its last word (${layout!!.lineCount} lines)", !layout.isLineEllipsized(layout.lineCount - 1))
    }

    @Test
    @Config(sdk = [35], qualifiers = "w320dp-h640dp-port-night-xhdpi")
    fun `the list page's Rename, On Home and Delete stay on a 320 dp screen at 2x, in both directions`() {
        for (direction in listOf(LayoutDirection.Ltr, LayoutDirection.Rtl)) {
            val dir = Files.createTempDirectory("tm-lists-r5").toFile()
            try {
                val store = WatchedStore(WatchedStore.openDataStore(dir.resolve(WatchedStore.FILE_NAME))).lists
                val lists = runBlocking {
                    store.create("Weekend")
                    store.visible.first()
                }
                val activity = open(fontScale = 2f, direction = direction) {
                    ListPageScaffold(
                        lists = lists,
                        selectedId = lists.first().info.id,
                        onSelect = {},
                        store = store,
                        onMessage = {},
                        grid = { _, _, _ -> },
                    )
                }
                for (name in listOf("Rename list", "Show on Home", "Delete list")) {
                    val node = nodes(activity).firstOrNull { node -> words(node).any { it == name } && node.config.getOrNull(SemanticsActions.OnClick) != null }
                    assertNotNull("$direction: a button reads $name among " + nodes(activity).map { words(it) + described(it) }, node)
                    assertInside(activity, node!!, "$direction $name")
                }
            } finally {
                dir.deleteRecursively()
            }
        }
    }

    // Reading out ------------------------------------------------------------------------------

    @Test
    @Config(sdk = [35], qualifiers = "w360dp-h740dp-port-night-xhdpi")
    fun `Telegram's copies are each named for a screen reader, not all by the upload's quality`() {
        val q720 = TelegramQuality(720, 1280, 720, "h264", 900_000_000)
        val q480 = TelegramQuality(480, 854, 480, "h264", 450_000_000)
        val item = MediaItem(
            chatId = 1, messageId = 2, fileId = 10, title = "Clip", sizeBytes = 2_000_000_000L, durationSec = 7_200,
            mimeType = "video/mp4", thumbnailFileId = 0, miniThumbnail = null, date = 0, onDevice = false,
            width = 1920, height = 1080, qualities = listOf(q720, q480),
        )
        val activity = open { TelegramQualityLine(item) }
        // The line is shut; its one press opens the copies.
        nodes(activity).first { it.config.getOrNull(SemanticsActions.OnClick) != null }.config.getOrNull(SemanticsActions.OnClick)?.action?.invoke()
        idle()
        val names = nodes(activity).filter { it.config.getOrNull(SemanticsProperties.Role) == Role.RadioButton }.map { described(it).joinToString() }
        assertEquals("three copies: the upload and two of Telegram's: $names", 3, names.size)
        assertEquals("every copy has its own name: $names", 3, names.toSet().size)
        assertTrue("720p is named: $names", names.any { "720" in it })
        assertTrue("480p is named: $names", names.any { "480" in it })
    }

    // The Add chats picker -------------------------------------------------------------------------

    @Test
    @Config(sdk = [35], qualifiers = "w320dp-h640dp-port-night-xhdpi")
    fun `the picker's Try again and Send logs wrap at 2x instead of running off the screen`() {
        val model = AddChatsViewModel(write = {})
        val activity = open(fontScale = 2f) {
            AddChatsScreen(
                chats = (1L..6L).map { ChatSummary(it, "Chat $it", miniThumbnail = null, photoFileId = 0, kind = ChatKind.Channel, isArchived = false) }, listComplete = true, starred = emptySet(), model = model, onClose = {},
                partial = true, sendLogs = { Text("Send logs", Modifier.widthIn(min = 150.dp).clickable {}.padding(16.dp)) },
            )
        }
        val again = pressable(activity, "Try again")
        val logs = pressable(activity, "Send logs")
        assertNotNull("Try again is there", again)
        assertNotNull("Send logs is there", logs)
        assertInside(activity, again!!, "Try again")
        assertInside(activity, logs!!, "Send logs")
        assertTrue("Send logs keeps its 150 dp, not squeezed beside Try again (${dp(activity, logs.size.width.toFloat())} dp)", dp(activity, logs.size.width.toFloat()) >= 149f)
        model.stop()
    }

    private fun textLayout(node: SemanticsNode): TextLayoutResult? {
        val out = mutableListOf<TextLayoutResult>()
        fun walk(n: SemanticsNode) {
            n.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(out)
            n.children.forEach(::walk)
        }
        walk(node)
        return out.firstOrNull()
    }
}
