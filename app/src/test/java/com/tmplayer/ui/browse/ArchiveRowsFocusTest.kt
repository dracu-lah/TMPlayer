package com.tmplayer.ui.browse

import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import com.tmplayer.data.ArchiveFile
import com.tmplayer.data.ArchiveKind
import com.tmplayer.data.FormFactor
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
 * A chat's archives on the television (U10): greyed rows at the foot of the grid that the remote
 * reaches with Down, and a press answers with the reason (never a dead key).
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w960dp-h540dp-land-television-night-xhdpi")
class ArchiveRowsFocusTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    /** A state write left on the looper must not silence Compose for the next test (HX92). */
    @get:Rule
    val drain = DrainMainLooperRule()

    private val explained = mutableListOf<String>()

    @After
    fun tearDown() {
        FormFactor.override(false)
        Motion.reduced = false
    }

    private val files = listOf(
        ArchiveFile(1, 3, 3, "Night.Train.2019.1080p.rar", 700_000_000, 0, ArchiveKind.Rar),
        ArchiveFile(1, 2, 2, "Night.Train.2019.1080p.part2.rar", 700_000_000, 0, ArchiveKind.Rar, volume = true),
        ArchiveFile(1, 1, 1, "Season.1.1080p.7z", 300_000_000, 0, ArchiveKind.SevenZip, locked = true),
    )

    private fun open(noVideos: Boolean) {
        FormFactor.override(true)
        Motion.reduced = true
        val first = FocusRequester()
        rule.setContent {
            TMPlayerTheme {
                LazyVerticalGrid(columns = GridCells.Fixed(3), modifier = Modifier.fillMaxSize().background(Tone.background)) {
                    archiveItems(files, noVideos = noVideos, first = first, onExplain = { explained += it })
                }
            }
        }
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

    private fun press(key: Key) {
        rule.onNode(androidx.compose.ui.test.isRoot()).performKeyInput { pressKey(key) }
        rule.waitForIdle()
    }

    @Test
    fun `the remote walks down the archives and a press says why`() {
        open(noVideos = false)
        rule.onNodeWithText("Night.Train.2019.1080p.rar").requestFocus()
        rule.onNodeWithText("Night.Train.2019.1080p.rar").assertIsFocused()
        press(Key.DirectionDown)
        rule.onNodeWithText("Night.Train.2019.1080p.part2.rar").assertIsFocused()
        press(Key.DirectionCenter)
        assertEquals(1, explained.size)
        assertTrue(explained.single(), explained.single().startsWith("Compressed in pieces, cannot be played"))
        press(Key.DirectionDown)
        rule.onNodeWithText("Season.1.1080p.7z").assertIsFocused()
        press(Key.DirectionCenter)
        assertTrue(explained.last(), explained.last().contains("password"))
        press(Key.DirectionUp)
        rule.onNodeWithText("Night.Train.2019.1080p.part2.rar").assertIsFocused()
    }

    @Test
    fun `with no videos the listing says so first and the first archive takes the remote`() {
        open(noVideos = true)
        rule.onNodeWithText("No playable videos in this chat.").assertExists()
        rule.onNodeWithText("3 archives here cannot be played").assertExists()
    }
}
