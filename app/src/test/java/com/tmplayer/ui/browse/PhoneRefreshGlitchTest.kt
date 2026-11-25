package com.tmplayer.ui.browse

import android.graphics.Bitmap
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.tmplayer.data.Account
import com.tmplayer.data.FormFactor
import com.tmplayer.data.HomeRow
import com.tmplayer.data.MediaItem
import com.tmplayer.data.ShelfEntry
import com.tmplayer.data.WorkStatus
import com.tmplayer.i18n.L
import com.tmplayer.ui.DrainMainLooperRule
import com.tmplayer.ui.components.ToastLayout
import com.tmplayer.ui.theme.Motion
import com.tmplayer.ui.theme.TMPlayerTheme
import com.tmplayer.ui.theme.Tone
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import kotlin.math.abs

/**
 * The glitches after Refresh on a phone's Home (user, 2026-11-25, real phone 1080x2400 at 440 dpi):
 *
 *  - "Up to date" was a full width bar drawn under the account's picture; it is a compact Success
 *    badge, as wide as its words (280 dp at most), centred below the row of the name and the
 *    picture, and fades away.
 *  - The name sat under the clock and was drawn twice; it stays below the status bar at any scroll
 *    offset (it fades out before it reaches the bar), one copy at a time, and a refresh does not
 *    move the page.
 *
 * Pictures go to design/phone-refresh. Left to the device: the same two frames on the POCO.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w393dp-h873dp-xxhdpi")
class PhoneRefreshGlitchTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    @get:Rule
    val drain = DrainMainLooperRule()

    @After
    fun tearDown() {
        FormFactor.override(false)
        Motion.reduced = false
    }

    private val density get() = rule.activity.resources.displayMetrics.density

    /** The status bar's height in this window, 28 dp, as the phone reports it. */
    private val statusBarPx get() = (28 * density).toInt()

    private var status by mutableStateOf<WorkStatus>(WorkStatus.Idle)

    private fun statusBar() {
        val insets = WindowInsetsCompat.Builder()
            .setInsets(WindowInsetsCompat.Type.statusBars(), Insets.of(0, statusBarPx, 0, 0))
            .setInsetsIgnoringVisibility(WindowInsetsCompat.Type.statusBars(), Insets.of(0, statusBarPx, 0, 0))
            .setVisible(WindowInsetsCompat.Type.statusBars(), true)
            .build()
        fun walk(view: View) {
            if (view.javaClass.name.contains("AndroidComposeView")) ViewCompat.dispatchApplyWindowInsets(view, insets)
            if (view is ViewGroup) for (i in 0 until view.childCount) walk(view.getChildAt(i))
        }
        walk(rule.activity.window.decorView)
        rule.waitForIdle()
    }

    private fun frame(refreshing: WorkStatus) {
        FormFactor.override(false)
        Motion.reduced = true
        status = refreshing
        val nav = TouchNav(
            account = Account(name = "Asha Rao", username = "asha", miniThumbnail = null, photoFileId = 0),
            favoriteCount = 0, downloadCount = 0, watchingCount = 0, watchedCount = 0, updateVersion = null,
            onHome = {}, onChats = {}, onSearch = {}, onSettings = {},
            onFavourites = {}, onDownloads = {}, onHistory = {}, onUpdate = {},
        )
        val chrome = TouchChrome()
        rule.setContent {
            TMPlayerTheme {
                Box(Modifier.fillMaxSize().background(Tone.background)) {
                    TouchNavHost(nav, chrome, "home") {
                        CompositionLocalProvider(LocalTouchPageKey provides "home") {
                            TouchPage(TouchPageSpec(current = TouchDestination.Home, refresh = {}))
                            PhonePageFrame(
                                section = BrowseSection.of(BrowseTab.Home),
                                count = 0,
                                refresh = {},
                                refreshStatus = status,
                                pane = {
                                    Box(Modifier.fillMaxSize()) {
                                        PhoneHomeCorner(start = 16.dp, under = LocalUnderStatusBar.current)
                                    }
                                },
                            )
                        }
                    }
                }
            }
        }
        rule.waitForIdle()
        statusBar()
    }

    private fun boundsOf(matcher: SemanticsMatcher): Rect = rule.onAllNodes(matcher).fetchSemanticsNodes().first().boundsInRoot

    private fun picture(name: String) {
        val bitmap: Bitmap = rule.onRoot().captureToImage().asAndroidBitmap()
        val file = File("../design/phone-refresh/$name").absoluteFile
        file.parentFile?.mkdirs()
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        assertTrue(file.length() > 0)
    }

    // The badge ------------------------------------------------------------------------------------

    @Test
    fun `the up to date badge is compact, centred and clear of the picture and the name`() {
        frame(WorkStatus.Updated(System.currentTimeMillis()))
        rule.mainClock.advanceTimeBy(500)
        rule.waitForIdle()
        val window = rule.onRoot().fetchSemanticsNode().boundsInRoot
        val badge = rule.onNodeWithTag(REFRESH_LINE_TAG).fetchSemanticsNode().boundsInRoot
        val avatar = rule.onNodeWithTag(ACCOUNT_BUTTON_TAG).fetchSemanticsNode().boundsInRoot
        val name = boundsOf(hasContentDescription("TMPlayer", substring = true))
        assertTrue("it has size: $badge", badge.width > 0f && badge.height > 0f)
        assertFalse("not over the picture: badge $badge, picture $avatar", badge.overlaps(avatar))
        assertFalse("not over the name: badge $badge, name $name", badge.overlaps(name))
        assertTrue("below the status bar: $badge", badge.top >= statusBarPx)
        assertTrue("below the picture: $badge, $avatar", badge.top >= avatar.bottom)
        assertTrue("below the name: $badge, $name", badge.top >= name.bottom)
        assertTrue("at most 280 dp wide: ${badge.width / density}", badge.width <= ToastLayout.BadgeMax.value * density + 1f)
        assertTrue("sized to its words, not a bar: ${badge.width / density}", badge.width < window.width * 0.6f)
        assertTrue("one line, a small badge: ${badge.height / density}", badge.height <= 48f * density)
        assertEquals("centred", window.center.x, badge.center.x, 2f)
        assertTrue("its words", rule.onAllNodes(androidx.compose.ui.test.hasText(L.stateUpdated)).fetchSemanticsNodes().isNotEmpty())
        picture("up-to-date-badge.png")
    }

    @Test
    @Config(sdk = [35], qualifiers = "w873dp-h393dp-land-xxhdpi")
    fun `on its side the badge keeps clear of the picture and the name too`() {
        frame(WorkStatus.Updated(System.currentTimeMillis()))
        rule.mainClock.advanceTimeBy(500)
        rule.waitForIdle()
        val badge = rule.onNodeWithTag(REFRESH_LINE_TAG).fetchSemanticsNode().boundsInRoot
        val avatar = rule.onNodeWithTag(ACCOUNT_BUTTON_TAG).fetchSemanticsNode().boundsInRoot
        val name = boundsOf(hasContentDescription("TMPlayer", substring = true))
        assertFalse("badge $badge, picture $avatar", badge.overlaps(avatar))
        assertFalse("badge $badge, name $name", badge.overlaps(name))
        assertTrue("at most 280 dp wide", badge.width <= ToastLayout.BadgeMax.value * density + 1f)
    }

    @Test
    fun `the badge goes after its two seconds and Updating is as compact`() {
        frame(WorkStatus.Syncing(done = 0, total = null))
        rule.mainClock.advanceTimeBy(1_500)
        rule.waitForIdle()
        val updating = rule.onNodeWithTag(REFRESH_LINE_TAG).fetchSemanticsNode().boundsInRoot
        val avatar = rule.onNodeWithTag(ACCOUNT_BUTTON_TAG).fetchSemanticsNode().boundsInRoot
        assertFalse("Updating over the picture: $updating, $avatar", updating.overlaps(avatar))
        assertTrue("Updating is not a full bar: ${updating.width / density}", updating.width <= ToastLayout.BadgeMax.value * density + 1f)
        status = WorkStatus.Updated(System.currentTimeMillis())
        rule.mainClock.advanceTimeBy(500)
        rule.waitForIdle()
        assertTrue(rule.onAllNodes(androidx.compose.ui.test.hasText(L.stateUpdated)).fetchSemanticsNodes().isNotEmpty())
        status = WorkStatus.Idle
        rule.mainClock.advanceTimeBy(1_000)
        rule.waitForIdle()
        assertTrue("gone", rule.onAllNodes(androidx.compose.ui.test.hasText(L.stateUpdated)).fetchSemanticsNodes().isEmpty())
    }

    // Home's header ----------------------------------------------------------------------------------

    private fun shelf(message: Long) = ShelfEntry.File(
        MediaItem(
            chatId = -5L, messageId = message, fileId = message.toInt(), title = "Clip $message", sizeBytes = 1_000_000_000, durationSec = 5_000,
            mimeType = "video/mp4", thumbnailFileId = 0, miniThumbnail = null, date = 1_000, fileName = "Clip $message.mp4",
        ),
    )

    private fun rows(version: Int) = (1..8).map { n ->
        HomeRow.Chat(-n.toLong(), "Chat $n", listOf(shelf(n * 10L + version), shelf(n * 10L + 5)), loaded = true)
    }

    private var homeRows by mutableStateOf(rows(0))
    private var homeWork by mutableStateOf<WorkStatus>(WorkStatus.Idle)

    private fun home() {
        FormFactor.override(false)
        Motion.reduced = true
        rule.setContent {
            TMPlayerTheme {
                Box(Modifier.fillMaxSize().background(Tone.background)) {
                    CompositionLocalProvider(LocalUnderStatusBar provides 28.dp) {
                        HomePane(
                            rows = homeRows,
                            watch = SeriesWatch(point = { null }, finished = { false }),
                            art = emptyMap(),
                            chatTitle = { "Chat" },
                            start = 16.dp, end = 16.dp, bottom = 0.dp,
                            onRowShown = {},
                            onArtWanted = {},
                            onResume = {},
                            onPlay = { _, _ -> },
                            onHoldMedia = { _, _ -> },
                            onSeeContinue = {},
                            onOpenChat = {},
                            carouselOn = false,
                            rowsReady = true,
                            work = homeWork,
                        )
                    }
                }
            }
        }
        rule.waitForIdle()
    }

    private fun names() = rule.onAllNodes(hasContentDescription("TMPlayer", substring = true)).fetchSemanticsNodes()

    private fun list() = rule.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange))

    private fun scrollTo(px: Int) {
        val node = list().fetchSemanticsNodes().first()
        val now = scrolled()
        node.config.getOrNull(SemanticsActions.ScrollBy)!!.action!!.invoke(0f, (px - now).toFloat())
        rule.waitForIdle()
    }

    /** How far the page has scrolled, from where the first row (Chat 1's heading) stands. */
    private fun scrolled(): Int {
        val first = rule.onAllNodes(androidx.compose.ui.test.hasText("Chat 1")).fetchSemanticsNodes().firstOrNull() ?: return -1
        return (REST_TOP_PX - first.boundsInRoot.top).toInt()
    }

    private var REST_TOP_PX = 0f

    @Test
    fun `the name is one copy and never under the status bar at any scroll offset`() {
        home()
        val bar = 28 * density
        REST_TOP_PX = rule.onAllNodes(androidx.compose.ui.test.hasText("Chat 1")).fetchSemanticsNodes().first().boundsInRoot.top
        for (offset in listOf(0, 100, 600)) {
            scrollTo(offset)
            val all = names()
            assertTrue("one name at most at $offset px, found ${all.size}", all.size <= 1)
            all.forEach { assertTrue("the name at $offset px starts at ${it.boundsInRoot.top}, under the bar ($bar)", it.boundsInRoot.top >= bar - 1f) }
            if (offset == 0) {
                assertEquals("the name is there at the top", 1, all.size)
                picture("home-top.png")
            }
        }
    }

    @Test
    fun `a refresh at a scrolled place leaves the page where it was`() {
        home()
        REST_TOP_PX = rule.onAllNodes(androidx.compose.ui.test.hasText("Chat 1")).fetchSemanticsNodes().first().boundsInRoot.top
        scrollTo(300)
        val before = scrolled()
        assertTrue("it scrolled: $before", before in 250..350)
        // The refresh: syncing, every row answered with new tiles, then done.
        homeWork = WorkStatus.Syncing(done = 0, total = null)
        rule.waitForIdle()
        homeRows = rows(1)
        rule.waitForIdle()
        rule.mainClock.advanceTimeBy(300)
        homeWork = WorkStatus.Idle
        rule.waitForIdle()
        rule.mainClock.advanceTimeBy(1_000)
        rule.waitForIdle()
        assertEquals("the page did not move", before.toFloat(), scrolled().toFloat(), 2f)
        assertTrue("one name at most", names().size <= 1)
        names().forEach { assertTrue("under the bar: ${it.boundsInRoot}", it.boundsInRoot.top >= 28 * density - 1f) }
        picture("home-after-refresh.png")
    }

    @Test
    fun `the fade of the name is whole at the top and gone before the bar`() {
        assertEquals(1f, brandFadeOf(0, 0, 36f), 0f)
        assertEquals(0.5f, brandFadeOf(0, 18, 36f), 0.001f)
        assertEquals(0f, brandFadeOf(0, 36, 36f), 0f)
        assertEquals(0f, brandFadeOf(0, 900, 36f), 0f)
        assertEquals(0f, brandFadeOf(2, 0, 36f), 0f)
        assertTrue(abs(brandFadeOf(0, 10, 36f) - 1f) < 1f)
    }
}
