package com.tmplayer.ui.browse

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.onFocusedBoundsChanged
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.semantics.getAllSemanticsNodes
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.dp
import com.tmplayer.data.FormFactor
import com.tmplayer.data.GlobalSearch
import com.tmplayer.data.MediaItem
import com.tmplayer.data.ResultGroup
import com.tmplayer.ui.DrainMainLooperRule
import com.tmplayer.ui.theme.TMPlayerTheme
import com.tmplayer.ui.theme.Tone
import com.tmplayer.ui.theme.Tv
import org.junit.After
import org.junit.Assert.assertEquals
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
 * The television's search with results, composed as BrowseScreen hosts it (under the top bar,
 * the end margin) and walked with the remote's down key: every poster the remote lands on stands
 * wholly between the top bar and the details strip, where the row used to stand cut in half by
 * the strip. At 1080p and 720p (both 960 by 540 dp), a 720p stick at 1:1, and right to left.
 * Drawn to PNGs under `build/device-test/review/` too.
 */
/** The details strip under the TV list, in dp. */
private const val STRIP_DP = 96f

abstract class TvSearchWalk {

    @After
    fun tearDown() {
        FormFactor.override(false)
    }

    private fun video(id: Long, name: String) = MediaItem(
        chatId = 7,
        messageId = id,
        fileId = id.toInt(),
        title = name,
        sizeBytes = 900_000_000,
        durationSec = 5_400,
        mimeType = "video/mp4",
        thumbnailFileId = 0,
        miniThumbnail = null,
        date = (1000 - id).toInt(),
        fileName = name,
    )

    @OptIn(ExperimentalFoundationApi::class)
    private fun walk(out: String) {
        FormFactor.override(true)
        val groups = (1L..15L).map { id ->
            ResultGroup(key = "g$id", copies = listOf(video(id, "Night Harbour Part $id 2021 1080p.mkv")))
        }
        val state = GlobalSearchState(query = GlobalSearch.parse("night harbour"), groups = groups)
        var focused: Rect? = null
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        currentActivity = activity
        activity.setContent {
            TMPlayerTheme {
                Box(
                    Modifier.fillMaxSize().background(Tone.background).onFocusedBoundsChanged { coordinates ->
                        focused =coordinates?.takeIf { it.isAttached }?.boundsInRoot()
                    },
                ) {
                    Column(Modifier.fillMaxSize().padding(top = TV_BAR_HEIGHT, end = Tv.SafeH)) {
                        TvSearchScreen(
                            state = state,
                            text = "night harbour",
                            onText = {},
                            onSubmit = {},
                            chats = emptyList(),
                            favourites = emptySet(),
                            recentSearches = emptyList(),
                            suggestions = emptyList(),
                            progress = { null },
                            onOpenChat = {},
                            onOpenGroup = {},
                            onFilters = {},
                            onSort = {},
                            onLoadMore = {},
                            onRetry = {},
                        )
                    }
                }
            }
        }
        idle()
        // The Compose view takes the window's focus as it would on the TV, so the keys reach it.
        // Out of touch mode and with the window's focus, as a television's screen is.
        val viewRoot = activity.window.decorView.parent
        org.robolectric.util.ReflectionHelpers.callInstanceMethod<Any>(
            viewRoot, "ensureTouchMode",
            org.robolectric.util.ReflectionHelpers.ClassParameter.from(Boolean::class.javaPrimitiveType, false),
        )
        org.robolectric.util.ReflectionHelpers.callInstanceMethod<Any>(
            viewRoot, "windowFocusChanged",
            org.robolectric.util.ReflectionHelpers.ClassParameter.from(Boolean::class.javaPrimitiveType, true),
        )
        activity.findViewById<android.view.ViewGroup>(android.R.id.content).getChildAt(0).requestFocus()
        // Right to left, the view's first focus lands on the clear control (the row's last, the
        // leftmost there), whose tip is a Popup. A Popup runs a per frame loop that, under this
        // test's paused looper (no compose test clock), never lets idle() return: the class hung
        // 20 to 27 minutes (2026-11-16). On a television the field has the focus at the start (the
        // screen asks for it), so put it there before the next frame is composed.
        focusField(activity)
        idle()
        val root = activity.window.decorView
        val density = activity.resources.displayMetrics.density
        val listTop = TV_BAR_HEIGHT.value * density
        // The strip under the list and the screen's bottom margin.
        val listFoot = root.height - (STRIP_DP + Tv.SafeV.value) * density
        var posters = 0
        repeat(5) { press ->
            key(KeyEvent.KEYCODE_DPAD_DOWN)
            val bounds = focused ?: return@repeat
            // A poster is the tall thing; the field and the pills are a row's height.
            if (bounds.height < 150 * density) return@repeat
            posters++
            assertTrue(
                "press ${press + 1}: poster at $bounds, list from $listTop to $listFoot",
                bounds.top >= listTop - 1 && bounds.bottom <= listFoot + 1,
            )
        }
        assertTrue("the remote reached at least two rows of posters", posters >= 2)
        // Back up to the field: the list goes back to its start.
        repeat(6) { key(KeyEvent.KEYCODE_DPAD_UP) }
        val top = focused
        assertTrue("back at the top, focus on the field row: $top", top != null && top.height < 150 * density)

        val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        // Down once more into the results for the picture.
        repeat(3) { key(KeyEvent.KEYCODE_DPAD_DOWN) }
        root.draw(Canvas(bitmap))
        val file = File("../build/device-test/review/$out").absoluteFile
        file.parentFile?.mkdirs()
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        assertEquals(true, file.length() > 0)
    }

    private fun focusField(activity: ComponentActivity) {
        val nodes = mutableListOf<androidx.compose.ui.semantics.SemanticsNode>()
        fun walkViews(view: android.view.View) {
            if (view is androidx.compose.ui.platform.ViewRootForTest) {
                nodes += view.semanticsOwner.getAllSemanticsNodes(mergingEnabled = true)
            }
            if (view is android.view.ViewGroup) for (i in 0 until view.childCount) walkViews(view.getChildAt(i))
        }
        walkViews(activity.window.decorView)
        val field = nodes.firstOrNull { node ->
            node.config.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.Text)?.any { it.text == "night harbour" } == true &&
                node.config.getOrNull(androidx.compose.ui.semantics.SemanticsActions.RequestFocus) != null
        }
        assertTrue("the search field is in the semantics tree", field != null)
        field?.config?.getOrNull(androidx.compose.ui.semantics.SemanticsActions.RequestFocus)?.action?.invoke()
    }

    private fun key(code: Int) {
        currentActivity?.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
        currentActivity?.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
        idle()
    }

    private var currentActivity: ComponentActivity? = null

    private fun idle() = repeat(20) { shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(100)) }

    protected fun run(out: String) = walk(out)

}

/**
 * One more screen of the TV Search walk ([TvSearchWalk]), in a class of its own so it runs in a fresh process: a
 * second remote walk in the same process found the frames no longer coming and never reached the
 * posters (the same render test frame stall CP101 describes).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TvSearchTelevision720AtOneToOneRenderTest : TvSearchWalk() {

    /** A state write left on the looper must not silence Compose for the next test (HX92). */
    @get:Rule
    val drain = DrainMainLooperRule()

    @Test
    @Config(sdk = [35], qualifiers = "w1280dp-h720dp-land-television-night-mdpi")
    fun television720AtOneToOne() = run("tv-search-720-mdpi.png")
}

/**
 * One more screen of the TV Search walk ([TvSearchWalk]), in a class of its own so it runs in a fresh process: a
 * second remote walk in the same process found the frames no longer coming and never reached the
 * posters (the same render test frame stall CP101 describes).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TvSearchTelevisionRtlRenderTest : TvSearchWalk() {

    /** A state write left on the looper must not silence Compose for the next test (HX92). */
    @get:Rule
    val drain = DrainMainLooperRule()

    @Test
    @Config(sdk = [35], qualifiers = "ar-ldrtl-w960dp-h540dp-land-television-night-xhdpi")
    fun televisionRtl() = run("tv-search-rtl.png")
}

/**
 * One more screen of the TV Search walk ([TvSearchWalk]), in a class of its own so it runs in a fresh process: a
 * second remote walk in the same process found the frames no longer coming and never reached the
 * posters (the same render test frame stall CP101 describes).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TvSearchTelevision1080RenderTest : TvSearchWalk() {

    /** A state write left on the looper must not silence Compose for the next test (HX92). */
    @get:Rule
    val drain = DrainMainLooperRule()

    @Test
    @Config(sdk = [35], qualifiers = "w960dp-h540dp-land-television-night-xhdpi")
    fun television1080() = run("tv-search-1080.png")
}

/** The TV Search walk at 720p, and the rule for how far a poster scrolls in. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TvSearchRenderTest : TvSearchWalk() {

    /** A state write left on the looper must not silence Compose for the next test (HX92). */
    @get:Rule
    val drain = DrainMainLooperRule()

    @Test
    @Config(sdk = [35], qualifiers = "w960dp-h540dp-land-television-night-tvdpi")
    fun television720() = run("tv-search-720.png")

    @Test
    fun `a poster comes in just far enough to stand whole, and one in view stays`() {
        // In view with room round it: nothing moves.
        assertEquals(0f, WholeInView.distance(offset = 40f, size = 200f, container = 330f, clearance = 16f))
        // Cut at the foot: its foot to the list's foot, less the clearance.
        assertEquals(126f, WholeInView.distance(offset = 240f, size = 200f, container = 330f, clearance = 16f))
        // Cut at the top: its top to the list's top, less the clearance.
        assertEquals(-66f, WholeInView.distance(offset = -50f, size = 200f, container = 330f, clearance = 16f))
        // Taller than the list: shown from its top.
        assertEquals(84f, WholeInView.distance(offset = 100f, size = 400f, container = 330f, clearance = 16f))
    }
}
