package com.tmplayer

import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.tmplayer.data.ChatKind
import com.tmplayer.data.ChatSummary
import com.tmplayer.ui.DrainMainLooperRule
import com.tmplayer.ui.browse.GridKeys
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * WP-39: where Back from Downloads and from Settings goes survives a turn, a fold and a split screen
 * resize (the activity is recreated for the ones it does not handle itself), as the screen itself does;
 * and Ctrl+A reaches the open grid only.
 */
@RunWith(RobolectricTestRunner::class)
class StateSurvivesRecreationTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    @get:Rule
    val drain = DrainMainLooperRule()

    @After
    fun tearDown() {
        GridKeys.selectAll = null
    }

    private val chat = ChatSummary(
        id = 42L,
        title = "Night Train",
        miniThumbnail = null,
        photoFileId = 7,
        kind = ChatKind.Group,
    )

    @Test
    fun backTargetsComeBackAfterARecreate() {
        val restoration = StateRestorationTester(rule)
        var downloads: Screen = Screen.Chats
        var settings: Screen = Screen.Chats
        var set: (Screen, Screen) -> Unit = { _, _ -> }
        restoration.setContent {
            var d by rememberSaveable(stateSaver = ScreenSaver) { mutableStateOf<Screen>(Screen.Chats) }
            var s by rememberSaveable(stateSaver = ScreenSaver) { mutableStateOf<Screen>(Screen.Chats) }
            downloads = d
            settings = s
            set = { a, b -> d = a; s = b }
        }
        rule.runOnIdle { set(Screen.Media(chat), Screen.Downloads) }
        rule.runOnIdle {
            assertEquals(Screen.Downloads, settings)
        }
        restoration.emulateSavedInstanceStateRestore()
        rule.runOnIdle {
            assertEquals("Back from Downloads goes to the chat it came from", Screen.Media(chat).chat.id, (downloads as Screen.Media).chat.id)
            assertEquals("Back from Settings goes to Downloads", Screen.Downloads, settings)
        }
    }

    @Test
    fun ctrlAReachesOnlyAnOpenGrid() {
        assertFalse("no grid open: the key is left alone", GridKeys.askSelectAll())
        var ticked = 0
        GridKeys.selectAll = { ticked++; true }
        assertTrue(GridKeys.askSelectAll())
        assertEquals(1, ticked)
        GridKeys.selectAll = { false }
        assertFalse("an empty grid leaves the key alone", GridKeys.askSelectAll())
    }
}
