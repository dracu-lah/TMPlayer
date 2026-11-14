package com.tmplayer.ui.settings

import androidx.activity.ComponentActivity
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.input.key.Key
import com.tmplayer.data.FormFactor
import com.tmplayer.data.SettingsStore
import com.tmplayer.i18n.L
import com.tmplayer.player.EngineChoice
import com.tmplayer.player.ExternalApp
import com.tmplayer.player.InstalledPlayers
import com.tmplayer.player.PlayerChoice
import com.tmplayer.ui.theme.TmMaterialTheme
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Settings, Playback, the default player and engine rows (PR4, PR5): what is on the page on a
 * phone and on a television, that Advanced stays folded until asked, that VLC is not offered while
 * its library is not in the build, and that every switch writes the store. The page has no
 * duplicate of "Open videos in another app": it is the Default player.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PlaybackSettingsTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val settings get() = SettingsStore(RuntimeEnvironment.getApplication())

    @After
    fun tearDown() {
        FormFactor.override(false)
    }

    private fun show(touch: Boolean) {
        FormFactor.override(!touch)
        rule.setContent {
            TmMaterialTheme(dark = true) {
                var open by remember { mutableStateOf(false) }
                LazyColumn { playerRows(settings, touch, open) { open = !open } }
            }
        }
        rule.waitForIdle()
    }

    @Test
    @Config(sdk = [35], qualifiers = "w411dp-h891dp-port-night-xhdpi")
    fun `the phone shows the player rows and folds Advanced away`() {
        show(touch = true)
        rule.onNodeWithText("Default player").assertIsDisplayed()
        rule.onNodeWithText("Player engine").assertIsDisplayed()
        rule.onNodeWithText("Switch engine on decoder error").assertIsDisplayed()
        rule.onNodeWithText("Suggest VLC when playback cannot keep up").assertIsDisplayed()
        rule.onNodeWithText("Advanced").assertIsDisplayed()
        // One home per job: no second row for opening in another app.
        rule.onNodeWithText("Open videos in another app").assertDoesNotExist()
        rule.onNodeWithText("Playback stats overlay").assertDoesNotExist()
    }

    @Test
    @Config(sdk = [35], qualifiers = "w960dp-h540dp-land-television-night-xhdpi")
    fun `the television shows its own Advanced rows once opened`() {
        show(touch = false)
        rule.onNodeWithText("Playback stats overlay").assertDoesNotExist()
        rule.onNodeWithText("Tunneled playback").assertDoesNotExist()
        rule.onNodeWithText("Advanced").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Match the video's frame rate").assertIsDisplayed()
        rule.onNodeWithText("Tunneled playback").assertIsDisplayed()
        rule.onNodeWithText("Playback stats overlay").assertIsDisplayed()
    }

    @Test
    @Config(sdk = [35], qualifiers = "w960dp-h540dp-land-television-night-xhdpi")
    fun `the remote walks the rows in order and opens Advanced without losing its place`() {
        show(touch = false)
        // A window the remote works in, as on a television.
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

        val rows = listOf("Default player", "Player engine", "Switch engine on decoder error", "Suggest VLC when playback cannot keep up", "Advanced")
        rule.onNode(hasText(rows.first()) and hasClickAction()).requestFocus()
        for ((index, title) in rows.withIndex()) {
            rule.onNode(hasText(title) and hasClickAction()).assertIsFocused()
            if (index < rows.lastIndex) rule.onNode(hasText(title) and hasClickAction()).performKeyInput { pressKey(Key.DirectionDown) }
            rule.waitForIdle()
        }
        // OK on Advanced opens it, and the remote stays on that row.
        rule.onNode(hasText("Advanced") and hasClickAction()).performKeyInput { pressKey(Key.DirectionCenter) }
        rule.waitForIdle()
        rule.onNodeWithText("Playback stats overlay").assertIsDisplayed()
        rule.onNode(hasText("Advanced") and hasClickAction()).assertIsFocused()
        // Down goes into the opened group, in reading order: frame-rate matching first.
        rule.onNode(hasText("Advanced") and hasClickAction()).performKeyInput { pressKey(Key.DirectionDown) }
        rule.waitForIdle()
        rule.onNode(hasText("Match the video's frame rate") and hasClickAction()).assertIsFocused()
    }

    @Test
    @Config(sdk = [35], qualifiers = "w411dp-h891dp-port-night-xhdpi")
    fun `a phone has no frame-rate or tunneled row, only the stats overlay`() {
        show(touch = true)
        rule.onNodeWithText("Advanced").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Playback stats overlay").assertIsDisplayed()
        rule.onNodeWithText("Match the video's frame rate").assertDoesNotExist()
        rule.onNodeWithText("Tunneled playback").assertDoesNotExist()
    }

    @Test
    @Config(sdk = [35], qualifiers = "w411dp-h891dp-port-night-xhdpi")
    fun `the engine list leaves VLC out while its library is not in the build, and the switches write the store`() {
        runBlocking { settings.setEngineChoice(EngineChoice.Automatic); settings.setSwitchEngineOnError(true) }
        show(touch = true)
        rule.onNodeWithText("Player engine").performClick()
        rule.waitForIdle()
        // The row's own second line says Automatic too, so two nodes: the row and the sheet's line.
        rule.onAllNodesWithText("Automatic").assertCountEquals(2)
        rule.onNodeWithText("ExoPlayer").assertIsDisplayed()
        rule.onNodeWithText("VLC").assertDoesNotExist()
        rule.onNodeWithText("ExoPlayer").performClick()
        rule.waitForIdle()
        assertEquals(EngineChoice.ExoPlayer, runBlocking { settings.engineChoiceNow() })

        rule.onNodeWithText("Switch engine on decoder error").performClick()
        rule.waitForIdle()
        assertFalse(runBlocking { settings.switchEngineOnErrorNow() })
    }

    @Test
    @Config(sdk = [35], qualifiers = "w411dp-h891dp-port-night-xhdpi")
    fun `a stored VLC that cannot run here is said, not lost`() {
        runBlocking { settings.setEngineChoice(EngineChoice.Vlc) }
        show(touch = true)
        rule.onNodeWithText("VLC is chosen, but this version has no VLC engine. ExoPlayer plays for now").assertIsDisplayed()
        assertEquals(EngineChoice.Vlc, runBlocking { settings.engineChoiceNow() })
    }

    @Test
    @Config(sdk = [35], qualifiers = "w411dp-h891dp-port-night-xhdpi")
    fun `the default player sheet resets to Ask and explains an empty list`() {
        runBlocking { settings.setDefaultPlayer(PlayerChoice.App("org.videolan.vlc", "org.videolan.vlc.Video")) }
        show(touch = true)
        rule.onNodeWithText("Default player").performClick()
        rule.waitForIdle()
        // VLC is not installed on this test device: the row said so and the sheet offers a way out.
        rule.onNodeWithText("Reset to Ask every time").assertIsDisplayed()
        rule.onNodeWithText("Ask every time").assertIsDisplayed()
        rule.onNodeWithText("Reset to Ask every time").performClick()
        rule.waitForIdle()
        assertEquals(PlayerChoice.Ask, runBlocking { settings.defaultPlayerNow() })
    }

    @Test
    fun `the row's second line says why a default may not apply`() {
        val vlc = InstalledPlayers.Found(ExternalApp("org.videolan.vlc", "org.videolan.vlc.Video", "VLC"), null)
        val app = PlayerChoice.App("org.videolan.vlc", "org.videolan.vlc.Video")
        assertEquals(L.settingsDefaultPlayerLocked, defaultPlayerSummary(L, app, listOf(vlc), locked = true))
        assertEquals(L.settingsDefaultPlayerAppDetail(app = "VLC"), defaultPlayerSummary(L, app, listOf(vlc), locked = false))
        // Restored from a backup on a device without the app, or the app removed since.
        assertEquals(L.settingsDefaultPlayerGone(app = "VLC"), defaultPlayerSummary(L, app, emptyList(), locked = false))
        assertEquals(L.settingsDefaultPlayerAsk, defaultPlayerSummary(L, PlayerChoice.Ask, listOf(vlc), locked = false))
        // A TV with no player installed: the row explains instead of offering nothing.
        assertEquals(L.settingsDefaultPlayerNone, defaultPlayerSummary(L, PlayerChoice.TmPlayer, emptyList(), locked = false))
        assertEquals(L.externalTmplayer, defaultPlayerSummary(L, PlayerChoice.TmPlayer, listOf(vlc), locked = false))
        assertTrue(defaultPlayerSummary(L, PlayerChoice.TmPlayer, null, locked = false).isNotBlank())
    }
}
