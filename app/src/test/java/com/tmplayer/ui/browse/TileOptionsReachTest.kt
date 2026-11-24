package com.tmplayer.ui.browse

import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.unit.dp
import com.tmplayer.data.FormFactor
import com.tmplayer.data.HintIds
import com.tmplayer.data.SettingsStore
import com.tmplayer.i18n.L
import com.tmplayer.ui.DrainMainLooperRule
import com.tmplayer.ui.components.HintSlot
import com.tmplayer.ui.components.LocalHintSlot
import com.tmplayer.ui.components.LocalTileOptionsHint
import com.tmplayer.ui.components.TILE_OPTIONS_HINT_SHOWS
import com.tmplayer.ui.components.TileOptionsHint
import com.tmplayer.ui.theme.Motion
import com.tmplayer.ui.theme.TMPlayerTheme
import com.tmplayer.ui.theme.Tone
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * WP-14: the touch only actions of a video tile are reachable and named on a remote. A focused tile
 * says "Hold OK or press Menu for options" through the hint registry (the first five focuses, one
 * hint per visit), and the header's Options pill names the tile the remote left, a button for a
 * remote with no Menu key. The Menu key and the held OK on the tile are Holdable's own (tv-keys.md,
 * TvKeyOwnershipTest); the details page's button row is [TvDetailRowTest].
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w960dp-h540dp-land-television-night-xhdpi")
class TileOptionsReachTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    /** A state write left on the looper must not silence Compose for the next test (HX92). */
    @get:Rule
    val drain = DrainMainLooperRule()

    @get:Rule
    val folder = TemporaryFolder()

    @After
    fun tearDown() {
        FormFactor.override(false)
        Motion.reduced = false
    }

    private val settings by lazy {
        SettingsStore(SettingsStore.openDataStore(File(folder.root, "prefs/${SettingsStore.FILE_NAME}")))
    }

    private val first = FocusRequester()
    private val second = FocusRequester()

    private fun open(slot: HintSlot = HintSlot(), withHint: Boolean = true) {
        FormFactor.override(true)
        Motion.reduced = true
        rule.setContent {
            TMPlayerTheme {
                CompositionLocalProvider(
                    LocalTileOptionsHint provides if (withHint) TileOptionsHint(settings, L.browseHoldOrMenuHint) else null,
                    LocalHintSlot provides slot,
                ) {
                    Box(Modifier.fillMaxSize().background(Tone.background)) {
                        androidx.compose.foundation.layout.Row {
                            VideoTile(title = "One", onClick = {}, onLongClick = {}, modifier = Modifier.width(240.dp).focusRequester(first)) {}
                            VideoTile(title = "Two", onClick = {}, onLongClick = {}, modifier = Modifier.width(240.dp).focusRequester(second)) {}
                        }
                    }
                }
            }
        }
        rule.waitForIdle()
    }

    private fun hint() = rule.onAllNodesWithText0()

    private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.onAllNodesWithText0() =
        onAllNodes(androidx.compose.ui.test.hasText(L.browseHoldOrMenuHint))

    @Test
    fun `a focused video tile says Hold OK or press Menu, and the registry counts it`() {
        open()
        assertEquals(0, hint().fetchSemanticsNodes().size)
        rule.runOnIdle { first.requestFocus() }
        rule.waitForIdle()
        rule.onNodeWithText("Hold OK or press Menu for options").assertExists()
        assertEquals(1, runBlocking { settings.hintCount(HintIds.TILE_OPTIONS) })
    }

    @Test
    fun `it stops after five focuses and a sixth shows nothing`() {
        runBlocking { repeat(TILE_OPTIONS_HINT_SHOWS) { settings.bumpHint(HintIds.TILE_OPTIONS) } }
        open()
        rule.runOnIdle { first.requestFocus() }
        rule.waitForIdle()
        assertEquals(0, hint().fetchSemanticsNodes().size)
        assertEquals(TILE_OPTIONS_HINT_SHOWS, runBlocking { settings.hintCount(HintIds.TILE_OPTIONS) })
    }

    @Test
    fun `one hint per screen visit, so a screen whose slot is taken shows none`() {
        val slot = HintSlot().also { it.claim("long_list") }
        open(slot)
        rule.runOnIdle { first.requestFocus() }
        rule.waitForIdle()
        assertEquals(0, hint().fetchSemanticsNodes().size)
        // And nothing was counted for a hint nobody saw.
        assertEquals(0, runBlocking { settings.hintCount(HintIds.TILE_OPTIONS) })
    }

    @Test
    fun `a screen that provides no hint draws none`() {
        open(withHint = false)
        rule.runOnIdle { first.requestFocus() }
        rule.waitForIdle()
        assertEquals(0, hint().fetchSemanticsNodes().size)
    }

    // The header's Options pill ----------------------------------------------------------------

    private val target = mutableStateOf<OptionsTarget?>(null)
    private var opened = 0

    private fun openWithPill(name: String? = null) {
        FormFactor.override(true)
        Motion.reduced = true
        // A window the remote works in: out of touch mode and holding focus, as on a television.
        val viewRoot = rule.activity.window.decorView.parent
        org.robolectric.util.ReflectionHelpers.callInstanceMethod<Any>(
            viewRoot, "ensureTouchMode",
            org.robolectric.util.ReflectionHelpers.ClassParameter.from(Boolean::class.javaPrimitiveType, false),
        )
        org.robolectric.util.ReflectionHelpers.callInstanceMethod<Any>(
            viewRoot, "windowFocusChanged",
            org.robolectric.util.ReflectionHelpers.ClassParameter.from(Boolean::class.javaPrimitiveType, true),
        )
        target.value = name?.let { OptionsTarget("media-1", it, null, null) }
        rule.setContent {
            TMPlayerTheme {
                Box(Modifier.fillMaxSize().background(Tone.background)) {
                    Column {
                        androidx.compose.foundation.layout.Row { OptionsPill(target) { opened++ } }
                        VideoTile(
                            title = "One", onClick = {}, onLongClick = {},
                            onFocused = { if (target.value == null) target.value = OptionsTarget("media-1", "Big Buck Bunny", null, null) },
                            modifier = Modifier.width(240.dp).focusRequester(first),
                        ) {}
                    }
                }
            }
        }
        rule.waitForIdle()
    }

    @Test
    fun `the Options pill is hidden until a tile has been focused, then names it`() {
        openWithPill()
        assertEquals(0, rule.onAllNodes(hasContentDescription("Options for Big Buck Bunny")).fetchSemanticsNodes().size)
        rule.runOnIdle { first.requestFocus() }
        rule.waitForIdle()
        rule.onNodeWithContentDescription("Options for Big Buck Bunny").assertExists()
    }

    @Test
    fun `Up from the tile reaches the Options pill and OK on it opens that tile's menu`() {
        openWithPill()
        rule.runOnIdle { first.requestFocus() }
        rule.waitForIdle()
        rule.onNodeWithContentDescription("Options for Big Buck Bunny").assertExists()
        // The remote's own path: Up from the tile lands on the pill above it.
        rule.onNode(androidx.compose.ui.test.isRoot()).performKeyInput { pressKey(Key.DirectionUp) }
        rule.waitForIdle()
        rule.onNodeWithContentDescription("Options for Big Buck Bunny").assertIsFocused()
        // The pill's own tip is a second window now; the key goes to the first.
        rule.onAllNodes(androidx.compose.ui.test.isRoot())[0].performKeyInput { pressKey(Key.DirectionCenter) }
        rule.waitForIdle()
        assertEquals("OK opened the menu", 1, opened)
    }

    @Test
    fun `a long name is cut at 24 characters with an ellipsis, and a short one is left alone`() {
        val long = "Averylongvideonamethatgoesonandon"
        assertEquals(OPTIONS_NAME_MAX, optionsNameClipped(long).length)
        assertTrue(optionsNameClipped(long).endsWith("…"))
        assertEquals("Short", optionsNameClipped("Short"))
        assertEquals("a".repeat(OPTIONS_NAME_MAX), optionsNameClipped("a".repeat(OPTIONS_NAME_MAX)))
    }

    @Test
    fun `the pill reads the tile it names to a screen reader, from its description`() {
        openWithPill(name = "Clip 20")
        val node = rule.onNodeWithContentDescription("Options for Clip 20").fetchSemanticsNode()
        assertEquals(listOf("Options for Clip 20"), node.config.getOrNull(SemanticsProperties.ContentDescription))
    }

    @Test
    fun `two tiles of the same name keep their own keys`() {
        val a = OptionsTarget("media-1", "Same", null, null)
        val b = OptionsTarget("media-2", "Same", null, null)
        assertEquals(a.name, b.name)
        assertTrue(a.key != b.key)
    }
}
