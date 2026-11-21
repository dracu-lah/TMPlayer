package com.tmplayer.ui.browse

import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.tmplayer.data.ChatKind
import com.tmplayer.data.ChatSummary
import com.tmplayer.data.FormFactor
import com.tmplayer.data.GlobalSearch
import com.tmplayer.ui.DrainMainLooperRule
import com.tmplayer.ui.components.LargeScreen
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
import java.io.File
import kotlin.math.abs

/**
 * Large screens, second pass (U17): the chat list and search on a tablet each way up and an open
 * foldable stand in a centred column of [LargeScreen.ColumnMax], instead of a row's title at one
 * edge of a 1280 dp screen and its count at the other. A phone upright, on its side and at twice
 * the font, and the television's two columns, keep the whole width. Each case is drawn to a PNG
 * under `build/device-test/review/` and its row measured.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LargeScreenListsTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    /** A state write left on the looper must not silence Compose for the next test (HX92). */
    @get:Rule
    val drain = DrainMainLooperRule()

    @After
    fun tearDown() {
        Motion.reduced = false
        FormFactor.override(false)
    }

    private val chats = (1..40).map {
        ChatSummary(it.toLong(), "Harbour films club $it", miniThumbnail = null, photoFileId = 0, kind = ChatKind.Channel, unreadCount = it % 7)
    }

    private fun chatList(tv: Boolean) {
        Motion.reduced = true
        FormFactor.override(tv)
        rule.setContent {
            TMPlayerTheme {
                Box(Modifier.fillMaxSize().background(Tone.background)) {
                    ChatSection(
                        chats = chats,
                        favorites = setOf(3L),
                        recentChats = chats.take(8),
                        insets = TouchInsets,
                        autoFocus = tv,
                        onOpenChat = {},
                        onHold = {},
                        launchChatId = 0,
                    )
                }
            }
        }
        rule.waitForIdle()
    }

    private fun search() {
        Motion.reduced = true
        FormFactor.override(false)
        rule.setContent {
            TMPlayerTheme {
                var text by remember { mutableStateOf(TextFieldValue("", TextRange(0))) }
                PhoneSearchScreen(
                    state = GlobalSearchState(query = GlobalSearch.parse(text.text)),
                    text = text,
                    onText = { text = it },
                    onSubmit = {},
                    onClose = {},
                    fieldModifier = Modifier.testTag("field"),
                    chats = chats,
                    favourites = emptySet(),
                    recentSearches = listOf("harbour lights", "sourdough starter"),
                    continueWatching = emptyList(),
                    continueArt = { it.toMediaItem() },
                    progress = { null },
                    onOpenChat = {},
                    onOpenGroup = {},
                    onResume = {},
                    onFilters = {},
                    onSort = {},
                    onLoadMore = {},
                    onRetry = {},
                    onRemoveRecent = {},
                    onClearRecent = { emptyList() },
                    onRestoreRecent = {},
                    onVoice = {},
                    listState = rememberLazyListState(),
                )
            }
        }
        rule.waitForIdle()
    }

    private fun shot(name: String) {
        val bitmap = rule.onRoot().captureToImage().asAndroidBitmap()
        val file = File("../build/device-test/review/$name").absoluteFile
        file.parentFile?.mkdirs()
        file.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        assertTrue(file.length() > 0)
    }

    private fun widthDp(): Dp = with(rule.density) { rule.onRoot().fetchSemanticsNode().size.width.toDp() }

    /** The left edge and width of the first chat row the list shows, in dp. */
    private fun firstRow(): Pair<Dp, Dp> {
        // The widest: the strip of recent chats over the list has narrow tiles of the same names.
        val row = rule.onAllNodes(hasText("Harbour films club", substring = true) and hasClickAction()).fetchSemanticsNodes()
            .filter { it.boundsInRoot.top > 0f }.maxByOrNull { it.boundsInRoot.width } ?: error("no chat row shown")
        return with(rule.density) { row.boundsInRoot.left.toDp() to row.boundsInRoot.width.toDp() }
    }

    private fun assertNear(expected: Dp, actual: Dp, what: String) =
        assertTrue("$what: expected about $expected, was $actual", abs(expected.value - actual.value) <= 1f)

    /** A touch list's row: the whole width on a phone, the centred column on a large screen. */
    private fun assertTouchRow(large: Boolean) {
        val (left, width) = firstRow()
        val screen = widthDp()
        if (large && screen - LargeScreen.ColumnMax > 0.dp) {
            assertNear(LargeScreen.ColumnMax, width, "row width")
            assertNear((screen - LargeScreen.ColumnMax) / 2, left, "row start")
        } else {
            assertNear(screen, width, "row width")
            assertNear(0.dp, left, "row start")
        }
    }

    @Test
    @Config(sdk = [35], qualifiers = "w411dp-h891dp-port-night-xhdpi")
    fun chatsPhone() {
        chatList(tv = false)
        shot("large-chats-phone.png")
        assertTouchRow(large = false)
    }

    @Test
    @Config(sdk = [35], qualifiers = "w891dp-h411dp-land-night-xhdpi")
    fun chatsPhoneOnItsSide() {
        chatList(tv = false)
        shot("large-chats-phone-land.png")
        assertTouchRow(large = false)
    }

    @Test
    @Config(sdk = [35], qualifiers = "w411dp-h891dp-port-night-xhdpi", fontScale = 2.0f)
    fun chatsPhoneTwiceTheFont() {
        chatList(tv = false)
        shot("large-chats-phone-font20.png")
        assertTouchRow(large = false)
    }

    @Test
    @Config(sdk = [35], qualifiers = "w1280dp-h800dp-land-night-xhdpi")
    fun chatsTabletLyingDown() {
        chatList(tv = false)
        shot("large-chats-tablet-land.png")
        assertTouchRow(large = true)
    }

    @Test
    @Config(sdk = [35], qualifiers = "w800dp-h1280dp-port-night-xhdpi")
    fun chatsTabletUpright() {
        chatList(tv = false)
        shot("large-chats-tablet-port.png")
        assertTouchRow(large = true)
    }

    @Test
    @Config(sdk = [35], qualifiers = "w673dp-h841dp-port-night-xhdpi")
    fun chatsFoldableOpen() {
        chatList(tv = false)
        shot("large-chats-foldable.png")
        // Narrower than the column already: the phone's full bleed rows.
        assertTouchRow(large = true)
    }

    @Test
    @Config(sdk = [35], qualifiers = "w1280dp-h720dp-land-television-night-xhdpi")
    fun chatsTelevisionKeepsItsTwoColumns() {
        // A television reporting 1280 by 720 dp counts as large by size alone; its list is not a touch list.
        chatList(tv = true)
        shot("large-chats-tv.png")
        // The names under the strip (the lower half of the screen), two to a line.
        val half = rule.onRoot().fetchSemanticsNode().size.height / 2f
        val rows = rule.onAllNodes(hasText("Harbour films club", substring = true)).fetchSemanticsNodes()
            .filter { it.boundsInRoot.top > half }
        val lefts = rows.map { with(rule.density) { it.boundsInRoot.left.toDp() } }.distinctBy { (it.value / 8).toInt() }
        assertEquals("two columns: $lefts", 2, lefts.size)
    }

    @Test
    @Config(sdk = [35], qualifiers = "w411dp-h891dp-port-night-xhdpi")
    fun searchPhone() {
        search()
        shot("large-search-phone.png")
        val field = with(rule.density) { rule.onNodeWithTag("field").fetchSemanticsNode().boundsInRoot.left.toDp() }
        assertNear(16.dp, field, "field start")
    }

    @Test
    @Config(sdk = [35], qualifiers = "w1280dp-h800dp-land-night-xhdpi")
    fun searchTabletLyingDown() {
        search()
        shot("large-search-tablet-land.png")
        val field = with(rule.density) { rule.onNodeWithTag("field").fetchSemanticsNode().boundsInRoot }
        val left = with(rule.density) { field.left.toDp() }
        val right = with(rule.density) { field.right.toDp() }
        val screen = widthDp()
        val gutter = (screen - LargeScreen.ColumnMax) / 2
        assertTrue("field starts in the column: $left", left >= gutter + 16.dp - 1.dp)
        assertTrue("field ends in the column: $right", right <= screen - gutter - 16.dp + 1.dp)
    }

    @Test
    @Config(sdk = [35], qualifiers = "w673dp-h841dp-port-night-xhdpi", fontScale = 2.0f)
    fun searchFoldableTwiceTheFont() {
        search()
        shot("large-search-foldable-font20.png")
        val field = with(rule.density) { rule.onNodeWithTag("field").fetchSemanticsNode().boundsInRoot.left.toDp() }
        assertNear(16.dp, field, "field start")
    }
}
