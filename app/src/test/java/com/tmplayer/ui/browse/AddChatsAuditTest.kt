package com.tmplayer.ui.browse

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getAllSemanticsNodes
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import com.tmplayer.data.ChatFolderSummary
import com.tmplayer.data.ChatKind
import com.tmplayer.data.ChatSummary
import com.tmplayer.data.FormFactor
import com.tmplayer.ui.DrainMainLooperRule
import com.tmplayer.ui.theme.TmMaterialTheme
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * The Add more chats picker audited on every form factor (HX56), by machine: a phone upright and
 * on its side, a phone at the largest system font, a tablet, a foldable opened out and a
 * television. For each, with Telegram folders in the list (HX29) and the picker opened as Settings
 * opens it (not the mandatory choice): the controls that matter (Back, Search, Done, the scopes and
 * the folders) are on screen, none is clipped by the window or drawn over another, a touch
 * screen's controls are big enough to press (48 dp), and a frame goes to
 * `build/device-test/review/` (and TMPLAYER_SHOTS=<dir>). The television's remote is
 * [AddChatsTvKeysTest]'s.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AddChatsAuditTest {

    /** A state write left on the looper must not silence Compose for the next test (HX92). */
    @get:Rule
    val drain = DrainMainLooperRule()

    @After
    fun tearDown() {
        FormFactor.override(false)
        idle()
    }

    @Test
    @Config(sdk = [35], qualifiers = "w411dp-h891dp-port-night-xhdpi")
    fun phone() = audit("phone", tv = false, fontScale = 1f)

    @Test
    @Config(sdk = [35], qualifiers = "w891dp-h411dp-land-night-xhdpi")
    fun `phone on its side`() = audit("phone-landscape", tv = false, fontScale = 1f)

    @Test
    @Config(sdk = [35], qualifiers = "w320dp-h640dp-port-night-xhdpi")
    fun `smallest phone at a 200 percent font`() = audit("phone-large-font", tv = false, fontScale = 2f)

    @Test
    @Config(sdk = [35], qualifiers = "w800dp-h1280dp-port-night-xhdpi")
    fun tablet() = audit("tablet", tv = false, fontScale = 1f)

    @Test
    @Config(sdk = [35], qualifiers = "w673dp-h841dp-port-night-xhdpi")
    fun `foldable opened out`() = audit("foldable", tv = false, fontScale = 1f)

    @Test
    @Config(sdk = [28], qualifiers = "w960dp-h540dp-land-television-night-xhdpi")
    fun tv() = audit("tv", tv = true, fontScale = 1f)

    private fun audit(name: String, tv: Boolean, fontScale: Float) {
        FormFactor.override(tv)
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        val model = AddChatsViewModel(counts = null, write = {})
        activity.setContent {
            val base = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(base.density, fontScale)) {
                TmMaterialTheme(dark = true) {
                    AddChatsScreen(
                        chats = CHATS,
                        listComplete = true,
                        starred = emptySet(),
                        model = model,
                        onClose = {},
                        folders = FOLDERS,
                    )
                }
            }
        }
        idle()
        // A Robolectric window lays a screen out when it draws.
        val bitmap = draw(activity)
        idle()
        val frame = draw(activity)
        save("add-chats-audit-$name.png", frame)
        val root = activity.window.decorView
        val window = Rect(0f, 0f, root.width.toFloat(), root.height.toFloat())
        val density = activity.resources.displayMetrics.density
        val all = nodes(activity)
        fun find(text: String, clickable: Boolean = true) = all.firstOrNull { node ->
            words(node).any { it == text } && (!clickable || node.config.getOrNull(SemanticsActions.OnClick) != null)
        }

        val problems = mutableListOf<String>()
        // What a viewer needs on the first screen: leaving, finding, choosing, finishing.
        val must = buildList<Pair<String, String>> {
            if (!tv) add("Back" to "Back")
            // Saved Messages is ticked for a fresh start (HX86), so Done is Done and one chat is chosen.
            add("Done" to "Done")
            add("count" to "1 chat chosen")
            // The All chats switch is on the first screen of every form factor (HX86).
            add("All chats switch" to "All chats")
            add("scope All" to "All")
            add("scope Channels" to "Channels")
            add("scope Archived" to "Archived")
            add("folder Films" to "Films")
            add("folder Family" to "Family")
            // The phone's field has a placeholder of this name, the television's rail an entry.
            add("search" to "Search")
        }
        val checked = mutableListOf<Pair<String, SemanticsNode>>()
        for ((label, text) in must) {
            val node = find(text, clickable = label != "search" && label != "count") ?: all.firstOrNull { words(it).any { w -> w == text } }
            if (node == null) {
                problems += "$label ($text) is not on the screen at all"
                continue
            }
            checked += label to node
        }
        // The two bars that scroll sideways (the scopes) may run past the window; everything else may not.
        for ((label, node) in checked) {
            val r = node.boundsInRoot
            val scrolls = label.startsWith("scope") || label.startsWith("folder")
            if (!scrolls) {
                if (r.width <= 0f || r.height <= 0f) problems += "$label has no room on the screen ($r)"
                if (r.left < -1f || r.top < -1f || r.right > window.right + 1f || r.bottom > window.bottom + 1f) {
                    problems += "$label $r is clipped by the window $window"
                }
            }
            // A chip scrolled out of the bar has no visible box to measure.
            if (!tv && node.config.getOrNull(SemanticsActions.OnClick) != null && !(scrolls && r.height <= 0f)) {
                // A scrolling bar's chip may be cut by the window at its end: its height counts, not its visible width.
                val smallest = (if (scrolls) r.height else minOf(r.width, r.height)) / density
                if (smallest < MIN_TARGET_DP) problems += "$label is ${"%.0f".format(smallest)} dp across, under $MIN_TARGET_DP dp"
            }
        }
        // No two of the controls are drawn over each other.
        val bars = checked.filter { (label, _) -> !label.startsWith("scope") && !label.startsWith("folder") }
        for (i in bars.indices) for (j in i + 1 until bars.size) {
            val a = bars[i].second.boundsInRoot
            val b = bars[j].second.boundsInRoot
            if (a.overlaps(b)) problems += "${bars[i].first} and ${bars[j].first} overlap ($a, $b)"
        }
        // A whole chat row is on the first screen, above the foot (not at the largest font on the smallest phone, where the header alone is a screen).
        if (fontScale <= 1f) {
            val row = find("Saved Messages", clickable = false)
            val foot = checked.firstOrNull { it.first == "Done" }?.second
            val rowBox = row?.boundsInRoot
            if (rowBox == null) problems += "no chat row on the first screen"
            else if (!tv && foot != null && rowBox.bottom > foot.boundsInRoot.top) problems += "the first chat row $rowBox is under the foot"
        }
        println("AUDIT| $name: ${all.count { it.config.getOrNull(SemanticsActions.OnClick) != null }} pressable controls, problems: $problems")
        assertTrue("$name: ${problems.joinToString("; ")}", problems.isEmpty())
        assertTrue(bitmap.width > 0)
        model.stop()
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

    private fun words(node: SemanticsNode): List<String> =
        node.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } +
            listOfNotNull(node.config.getOrNull(SemanticsProperties.ContentDescription)?.firstOrNull())

    private fun draw(activity: ComponentActivity): Bitmap {
        val root = activity.window.decorView
        val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        root.draw(Canvas(bitmap))
        return bitmap
    }

    private fun save(name: String, bitmap: Bitmap) {
        val dirs = listOfNotNull(
            File("../build/device-test/review").absoluteFile,
            System.getenv("TMPLAYER_SHOTS")?.takeIf { it.isNotBlank() }?.let(::File),
        )
        for (dir in dirs) {
            val file = dir.apply { mkdirs() }.resolve(name)
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            assertTrue(file.length() > 500)
        }
    }

    private companion object {
        /** Android's smallest comfortable touch target. */
        const val MIN_TARGET_DP = 48

        fun chat(id: Long, title: String, kind: ChatKind = ChatKind.Channel, folders: List<Int> = emptyList()) =
            ChatSummary(id, title, miniThumbnail = null, photoFileId = 0, kind = kind, folderIds = folders)

        val FOLDERS = listOf(
            ChatFolderSummary(7, "Films", icon = "Custom"),
            ChatFolderSummary(8, "Family", icon = "Custom"),
        )

        val CHATS: List<ChatSummary> = listOf(chat(100, "Saved Messages", ChatKind.Saved)) +
            listOf(
                chat(101, "Harbour Notes", folders = listOf(7)),
                chat(102, "Lantern Club", ChatKind.Group, folders = listOf(8)),
                chat(104, "Mira", ChatKind.Direct, folders = listOf(8)),
            ) + (1..60).map { chat(1_000L + it, "Chat number $it") }
    }
}
