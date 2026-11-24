package com.tmplayer.ui.browse

import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.unit.dp
import com.tmplayer.data.FormFactor
import com.tmplayer.data.HomeRow
import com.tmplayer.data.MediaItem
import com.tmplayer.data.ShelfEntry
import com.tmplayer.data.WorkStatus
import com.tmplayer.i18n.L
import com.tmplayer.ui.DrainMainLooperRule
import com.tmplayer.ui.theme.Motion
import com.tmplayer.ui.theme.TMPlayerTheme
import com.tmplayer.ui.theme.Tone
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Android Home says what its rows are doing (WP-93): while the first rows are built, placeholder
 * rows and from a second on "Loading your videos" (LS-05); rows that could not be read, or a
 * Telegram slow enough that rows are still out, a line in Home's foot with Try again (LS-06). On a
 * television Try again is reached with Down from the last row, and the line moves no row.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-port-night-xhdpi")
class HomeStatusPaneTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    /** A state write left on the looper must not silence Compose for the next test (HX92). */
    @get:Rule
    val drain = DrainMainLooperRule()

    @After
    fun tearDown() {
        HomeFocusMemory.forget()
        FormFactor.override(false)
        Motion.reduced = false
    }

    private fun shelf(message: Long, title: String) = ShelfEntry.File(
        MediaItem(
            chatId = -5L, messageId = message, fileId = message.toInt(), title = title, sizeBytes = 1_000_000_000, durationSec = 5_000,
            mimeType = "video/mp4", thumbnailFileId = 0, miniThumbnail = null, date = 1_000, fileName = "$title.mp4",
        ),
    )

    private val rows = listOf(HomeRow.Chat(-5L, "Clips", listOf(shelf(30, "Remote Clip"), shelf(31, "Second Clip")), loaded = true))

    private var work by mutableStateOf<WorkStatus>(WorkStatus.Idle)
    private var stalled by mutableStateOf(false)
    private var retried = 0
    private var refreshed = 0

    private fun show(touch: Boolean, shown: List<HomeRow>, ready: Boolean = true) {
        FormFactor.override(!touch)
        Motion.reduced = true
        if (!touch) focusWindow()
        rule.setContent {
            TMPlayerTheme {
                Box(Modifier.fillMaxSize().background(Tone.background)) {
                    HomePane(
                        rows = shown,
                        watch = SeriesWatch(point = { null }, finished = { false }),
                        art = emptyMap(),
                        chatTitle = { "Clips" },
                        start = 16.dp, end = 16.dp, bottom = 0.dp,
                        onRowShown = {},
                        onArtWanted = {},
                        onResume = {},
                        onPlay = { _, _ -> },
                        onHoldMedia = { _, _ -> },
                        onSeeContinue = {},
                        onOpenChat = {},
                        carouselOn = false,
                        rowsReady = ready,
                        work = work,
                        stalled = stalled,
                        onRetryRows = { retried++ },
                        onRefresh = { refreshed++ },
                    )
                }
            }
        }
    }

    @Test
    fun `home waiting for its first rows shows placeholder rows and from a second the words`() {
        rule.mainClock.autoAdvance = false
        show(touch = true, shown = emptyList(), ready = false)
        rule.mainClock.advanceTimeBy(500)
        rule.waitForIdle()
        rule.onAllNodesWithText(L.homeLoadingRows).assertCountEquals(0)
        // One node for a screen reader, named by what loads, an indeterminate progress bar.
        val waiting = rule.onNode(hasContentDescription(L.homeLoadingRows)).fetchSemanticsNode()
        assertTrue(waiting.config.getOrNull(SemanticsProperties.ProgressBarRangeInfo) != null)
        rule.mainClock.advanceTimeBy(700)
        rule.waitForIdle()
        rule.onAllNodesWithText(L.homeLoadingRows, useUnmergedTree = true).assertCountEquals(1)
        // Nothing to press while it waits.
        rule.onAllNodes(hasClickAction()).assertCountEquals(0)
    }

    @Test
    fun `rows that could not be read are said in the foot with try again`() {
        work = WorkStatus.Partial(okCount = 3, failedCount = 2, what = L.homeRowsFailed(count = 2))
        show(touch = true, shown = rows)
        rule.waitForIdle()
        rule.onAllNodesWithText("2 chats could not be read for now.", useUnmergedTree = true).assertCountEquals(1)
        rule.onNode(hasText(L.commonTryAgain) and hasClickAction()).performClick()
        rule.waitForIdle()
        assertEquals(1, retried)
        // A failure of every row is the line under the title's on a phone, not the foot's.
        work = WorkStatus.Failed("", keptAt = 1_800_000_000_000L)
        rule.waitForIdle()
        rule.onAllNodes(hasText(L.commonTryAgain) and hasClickAction()).assertCountEquals(0)
    }

    @Test
    @Config(sdk = [35], qualifiers = "w960dp-h540dp-land-television-night-xhdpi")
    fun `on a television try again under the last row is reached with Down and no row moves`() {
        show(touch = false, shown = rows)
        rule.waitForIdle()
        rule.mainClock.advanceTimeBy(400)
        rule.waitForIdle()
        val tile = rule.onNode(hasClickAction() and hasAnyDescendant(hasText("Remote Clip", substring = true)), useUnmergedTree = true)
        tile.requestFocus()
        rule.waitForIdle()
        val before = tile.getBoundsInRoot()

        work = WorkStatus.Failed("", keptAt = 1_800_000_000_000L)
        rule.waitForIdle()
        tile.assertIsFocused()
        assertEquals("the line moves no row", before, tile.getBoundsInRoot())
        rule.onAllNodesWithText("Could not update.", substring = true, useUnmergedTree = true).assertCountEquals(1)

        rule.onNode(isRoot()).performKeyInput { pressKey(Key.DirectionDown) }
        rule.waitForIdle()
        rule.mainClock.advanceTimeBy(600)
        rule.waitForIdle()
        val retry = rule.onNode(hasText(L.commonTryAgain) and hasClickAction())
        retry.assertIsFocused()
        retry.performKeyInput { pressKey(Key.DirectionCenter) }
        rule.waitForIdle()
        assertEquals(1, retried)
    }

    @Test
    @Config(sdk = [35], qualifiers = "w960dp-h540dp-land-television-night-xhdpi")
    fun `on a television a stall says telegram is slow with retry`() {
        stalled = true
        show(touch = false, shown = rows)
        rule.waitForIdle()
        rule.onAllNodesWithText(L.commonSlowAnswer, useUnmergedTree = true).assertCountEquals(1)
        rule.onNode(hasText(L.commonRetry) and hasClickAction()).performClick()
        rule.waitForIdle()
        assertEquals(1, refreshed)
        stalled = false
        rule.waitForIdle()
        rule.onAllNodesWithText(L.commonSlowAnswer, useUnmergedTree = true).assertCountEquals(0)
    }

    private fun focusWindow() {
        val viewRoot = rule.activity.window.decorView.parent
        org.robolectric.util.ReflectionHelpers.callInstanceMethod<Any>(
            viewRoot, "ensureTouchMode",
            org.robolectric.util.ReflectionHelpers.ClassParameter.from(Boolean::class.javaPrimitiveType, false),
        )
        org.robolectric.util.ReflectionHelpers.callInstanceMethod<Any>(
            viewRoot, "windowFocusChanged",
            org.robolectric.util.ReflectionHelpers.ClassParameter.from(Boolean::class.javaPrimitiveType, true),
        )
        rule.waitForIdle()
    }
}
