package com.tmplayer.ui.browse

import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.tmplayer.data.FormFactor
import com.tmplayer.data.HomeRow
import com.tmplayer.data.MediaItem
import com.tmplayer.data.OfflineHome
import com.tmplayer.data.ProgressKeys
import com.tmplayer.data.ResumeRecord
import com.tmplayer.data.ShelfEntry
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
 * Offline Home (R4) as Home draws it: "On this phone" first, the other cards greyed with Needs
 * internet, a press on a greyed card opens its page (never plays), a press on a card that plays
 * plays it, a fully cached video resumes with no lookup, at the phone's two font sizes, and on a
 * television the remote starts on a card that plays and reaches a greyed one that still answers.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-port-night-xhdpi")
class OfflineHomePaneTest {

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

    private fun record(message: Long, title: String, at: Long) = ResumeRecord(
        chatId = -1L, messageId = message, fileId = message.toInt(), title = title, chatTitle = "Films",
        sizeBytes = 900_000_000, durationSec = 6_000, positionMs = 600_000, durationMs = 6_000_000, updatedAt = at,
    )

    private val saved = record(1, "Harbour Notes", at = 3)
    private val far = record(2, "Distant Lights", at = 2)
    private val cached = record(3, "Quiet Station", at = 1)

    private fun shelf(message: Long, title: String) = ShelfEntry.File(
        MediaItem(
            chatId = -5L, messageId = message, fileId = message.toInt(), title = title, sizeBytes = 1_000_000_000, durationSec = 5_000,
            mimeType = "video/mp4", thumbnailFileId = 0, miniThumbnail = null, date = 1_000, fileName = "$title.mp4",
        ),
    )

    private val rows = listOf(
        HomeRow.Continue(listOf(far, saved, cached)),
        HomeRow.Chat(-5L, "Clips", listOf(shelf(30, "Remote Clip")), loaded = true),
    )

    private val resumed = mutableListOf<Long>()
    private val played = mutableListOf<Long>()
    private val opened = mutableListOf<Long>()

    /** What the shell provides, changeable after the first frame as the reads finish. */
    private val shell = androidx.compose.runtime.mutableStateOf(OfflineHome.Online)

    private fun show(touch: Boolean, fontScale: Float = 1f, offline: OfflineHome) {
        shell.value = offline
        FormFactor.override(!touch)
        Motion.reduced = true
        // A television's window has the focus before Home is up, as the app's has: Home's own
        // request for its first card then lands, rather than the window's default first focus.
        if (!touch) focusWindow()
        rule.setContent {
            val base = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(base.density, fontScale),
                LocalOfflineHome provides shell.value,
            ) {
                TMPlayerTheme {
                    Box(Modifier.fillMaxSize().background(Tone.background)) {
                        OfflineHomeScope(rows) { shown ->
                            HomePane(
                                rows = shown,
                                watch = SeriesWatch(point = { null }, finished = { false }),
                                art = emptyMap(),
                                chatTitle = { "Films" },
                                start = 16.dp, end = 16.dp, bottom = 0.dp,
                                onRowShown = {},
                                onArtWanted = {},
                                onResume = { resumed += it.messageId },
                                onPlay = { item, _ -> played += item.messageId },
                                onHoldMedia = { item, _ -> opened += item.messageId },
                                onSeeContinue = {},
                                onOpenChat = {},
                                carouselOn = false,
                            )
                        }
                    }
                }
            }
        }
        settle()
    }

    /** Waits for the watch cache's answer (a background read) when Home is not holding for a shell read, then for Home's first focus. */
    private fun settle() {
        rule.waitForIdle()
        if (shell.value.settled) {
            rule.waitUntil(5_000) { rule.onAllNodes(hasClickAction()).fetchSemanticsNodes().isNotEmpty() }
        }
        // Home's first focus is asked for a frame after its first row is in.
        rule.mainClock.advanceTimeBy(400)
        rule.waitForIdle()
    }

    // The saved one is a download, the cached one is complete in the watch cache.
    private val offline = OfflineHome(true, downloads = listOf(saved), cached = setOf(ProgressKeys.of(cached.chatId, cached.messageId)))

    @Test
    fun `offline the phone leads with On this phone and greys the rest`() {
        show(touch = true, offline = offline)
        rule.onNodeWithText(L.homeRowOnPhone).assertIsDisplayed()
        // Distant Lights and the chat's clip cannot play, so they say so; the other two do not.
        rule.onAllNodesWithText(L.homeNeedsInternet).assertCountEquals(2)
        rule.onNodeWithText(L.gridBadgeDownloaded).assertIsDisplayed()
    }

    @Test
    fun `the first offline frame is held until the downloads are read, then nothing playable is grey`() {
        // The shell has not finished reading the download index: Home holds its rows.
        show(touch = true, offline = OfflineHome(true, settled = false))
        rule.onAllNodesWithText(L.homeNeedsInternet).assertCountEquals(0)
        rule.onNodeWithText("Harbour Notes", substring = true).assertDoesNotExist()
        rule.onNodeWithText("Distant Lights", substring = true).assertDoesNotExist()
        // The read lands: the saved video is a download and plays, and only the others are grey.
        shell.value = offline
        settle()
        rule.onNodeWithText("Harbour Notes", substring = true).assertIsDisplayed()
        rule.onAllNodesWithText(L.homeNeedsInternet).assertCountEquals(2)
        rule.onNodeWithText(L.gridBadgeDownloaded).assertIsDisplayed()
    }

    @Test
    @Config(sdk = [35], qualifiers = "w960dp-h540dp-land-television-night-xhdpi")
    fun `on a television the held first frame has no card to focus, and the first focus is on one that plays`() {
        show(touch = false, offline = OfflineHome(true, settled = false))
        assertTrue("nothing to focus in the held frame", focusedNow().isEmpty())
        rule.onAllNodes(hasClickAction() and hasAnyDescendant(hasText("", substring = true)), useUnmergedTree = true)
            .assertCountEquals(0)
        shell.value = offline
        settle()
        val focused = focusedNow()
        assertTrue("the remote is on a card that plays, not a greyed one: $focused", !focused.contains("[greyed]"))
    }

    @Test
    fun `a press on a greyed card opens its page and a press on one that plays plays it`() {
        show(touch = true, offline = offline)
        rule.onNodeWithText("Distant Lights", substring = true).performClick()
        assertEquals("the greyed card opened its page", listOf(far.messageId), opened)
        assertTrue("and played nothing", resumed.isEmpty() && played.isEmpty())
        // A fully cached video resumes at once, with no page and no lookup.
        rule.onNodeWithText("Quiet Station", substring = true).performClick()
        assertEquals(listOf(cached.messageId), resumed)
        rule.onNodeWithText("Harbour Notes", substring = true).performClick()
        assertEquals(listOf(cached.messageId, saved.messageId), resumed)
    }

    @Test
    fun `a remote video from a chat row opens its page offline`() {
        show(touch = true, offline = offline)
        rule.onNodeWithText("Remote Clip", substring = true).performClick()
        assertEquals(listOf(30L), opened)
        assertTrue(played.isEmpty())
    }

    @Test
    fun `at 2 point 0 font the states still read and a greyed press still opens the page`() {
        show(touch = true, fontScale = 2f, offline = offline)
        rule.onNodeWithText(L.homeRowOnPhone).assertIsDisplayed()
        rule.onNodeWithText("Distant Lights", substring = true).performClick()
        assertEquals(listOf(far.messageId), opened)
    }

    @Test
    fun `online nothing is greyed and nothing is moved`() {
        show(touch = true, offline = OfflineHome.Online)
        rule.onAllNodesWithText(L.homeNeedsInternet).assertCountEquals(0)
        rule.onNodeWithText(L.homeRowOnPhone).assertDoesNotExist()
        rule.onNodeWithText("Distant Lights", substring = true).performClick()
        assertEquals(listOf(far.messageId), resumed)
    }

    private fun press(key: Key) {
        rule.onNode(isRoot()).performKeyInput { pressKey(key) }
        rule.waitForIdle()
        // Down moves the rows in a coroutine of its own: give it frames.
        rule.mainClock.advanceTimeBy(600)
        rule.waitForIdle()
    }

    private fun focusedNow(): String =
        rule.onAllNodes(isFocused() and hasClickAction()).fetchSemanticsNodes().joinToString { node ->
            // The tile's own text is in its children: the name and the facts line.
            fun texts(n: androidx.compose.ui.semantics.SemanticsNode): List<String> =
                (n.config.getOrNull(SemanticsProperties.Text)?.map { it.text }.orEmpty()) + n.children.flatMap { texts(it) }
            texts(node).joinToString(" / ") + (if (node.config.getOrNull(SemanticsProperties.StateDescription) != null) " [greyed]" else "")
        }

    @Test
    @Config(sdk = [35], qualifiers = "w960dp-h540dp-land-television-night-xhdpi")
    fun `on a television the first card, where the remote starts, plays`() {
        show(touch = false, offline = offline)
        // The cards in reading order: the one the remote's start focus is put on is the first.
        val cards = rule.onAllNodes(hasClickAction() and hasAnyDescendant(hasText("", substring = true)), useUnmergedTree = true)
            .fetchSemanticsNodes()
            .sortedWith(compareBy({ it.boundsInRoot.top }, { it.boundsInRoot.left }))
        fun name(node: androidx.compose.ui.semantics.SemanticsNode): String {
            fun texts(n: androidx.compose.ui.semantics.SemanticsNode): List<String> =
                (n.config.getOrNull(SemanticsProperties.Text)?.map { it.text }.orEmpty()) + n.children.flatMap { texts(it) }
            return texts(node).joinToString(" / ")
        }
        val first = cards.first()
        assertTrue(name(first), name(first).contains("Harbour Notes"))
        assertTrue("it plays: no Needs internet on it", first.config.getOrNull(SemanticsProperties.StateDescription) == null)
        // OK on it plays it.
        rule.onNode(hasClickAction() and hasAnyDescendant(hasText("Harbour Notes", substring = true)), useUnmergedTree = true).requestFocus()
        rule.waitForIdle()
        press(Key.DirectionCenter)
        assertEquals(listOf(saved.messageId), resumed)
    }

    @Test
    @Config(sdk = [35], qualifiers = "w960dp-h540dp-land-television-night-xhdpi")
    fun `on a television a greyed card takes the remote and OK opens its page`() {
        show(touch = false, offline = offline)
        // The Continue row holds only what cannot play now, and its cards are focusable all the same.
        rule.onNode(hasClickAction() and hasAnyDescendant(hasText("Distant Lights", substring = true)), useUnmergedTree = true).requestFocus()
        rule.waitForIdle()
        assertTrue(focusedNow(), focusedNow().contains("Distant Lights"))
        press(Key.DirectionCenter)
        assertEquals(listOf(far.messageId), opened)
        assertTrue(resumed.isEmpty())
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
