package com.tmplayer.player

import android.view.View
import android.widget.Button
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.fragment.app.FragmentActivity
import com.tmplayer.R
import com.tmplayer.data.FormFactor
import com.tmplayer.i18n.L
import com.tmplayer.ui.DrainMainLooperRule
import com.tmplayer.ui.components.ChoiceLine
import com.tmplayer.ui.components.ChoiceList
import com.tmplayer.ui.theme.TmMaterialTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.util.ReflectionHelpers

/**
 * The engine's two new places on a television, by remote alone (PR2): the More menu's Try the
 * other engine (its lines are these same [ChoiceLine]s, see [ReportStutterFocusTest]) and the
 * failure sheet's Play in VLC with Always use VLC on this device under it, in the real layout.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w960dp-h540dp-land-television-night-xhdpi")
class EngineFocusTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    /** A state write left on the looper must not silence Compose for the next test (HX92). */
    @get:Rule
    val drain = DrainMainLooperRule()

    @After
    fun tearDown() {
        FormFactor.override(false)
    }

    private fun leaveTouchMode(root: Any?) {
        ReflectionHelpers.callInstanceMethod<Any>(
            root, "ensureTouchMode",
            ReflectionHelpers.ClassParameter.from(Boolean::class.javaPrimitiveType, false),
        )
        ReflectionHelpers.callInstanceMethod<Any>(
            root, "windowFocusChanged",
            ReflectionHelpers.ClassParameter.from(Boolean::class.javaPrimitiveType, true),
        )
    }

    @Test
    fun `the remote reaches Try the other engine between its neighbours and OK chooses it`() {
        FormFactor.override(true)
        var chosen = 0
        rule.setContent {
            TmMaterialTheme(dark = true) {
                Column(androidx.compose.ui.Modifier.fillMaxSize()) {
                    ChoiceList {
                        ChoiceLine(title = L.playerPictureInPicture, onClick = {})
                        ChoiceLine(title = L.playerEngineOther, detail = L.playerEngineOtherDetail(L.settingsEngineExoplayer), onClick = { chosen++ })
                        ChoiceLine(title = L.playerOpenInAnotherApp, onClick = {})
                    }
                }
            }
        }
        leaveTouchMode(rule.activity.window.decorView.parent)
        rule.waitForIdle()

        rule.onNodeWithText(L.playerPictureInPicture).requestFocus()
        rule.onNodeWithText(L.playerPictureInPicture).performKeyInput { pressKey(Key.DirectionDown) }
        rule.waitForIdle()
        rule.onNodeWithText(L.playerEngineOther).assertIsFocused()
        rule.onNodeWithText(L.playerEngineOther).performKeyInput { pressKey(Key.DirectionCenter) }
        rule.waitForIdle()
        assertEquals(1, chosen)
        rule.onNodeWithText(L.playerEngineOther).performKeyInput { pressKey(Key.DirectionDown) }
        rule.waitForIdle()
        rule.onNodeWithText(L.playerOpenInAnotherApp).assertIsFocused()
    }

    @Test
    fun `the failure sheet's engine buttons take the focus in order on a television`() {
        FormFactor.override(true)
        val activity = Robolectric.buildActivity(FragmentActivity::class.java).setup().get()
        activity.setTheme(R.style.Theme_TMPlayer_Player)
        activity.setContentView(R.layout.activity_player)
        activity.findViewById<View>(R.id.status_overlay).visibility = View.VISIBLE
        val engine = activity.findViewById<Button>(R.id.status_engine).apply {
            text = L.playerEnginePlayVlc
            visibility = View.VISIBLE
        }
        val always = activity.findViewById<Button>(R.id.status_engine_always).apply {
            text = L.playerEngineAlwaysVlc
            visibility = View.VISIBLE
        }
        val back = activity.findViewById<Button>(R.id.status_back).apply {
            text = L.playerGoBack
            visibility = View.VISIBLE
        }
        leaveTouchMode(activity.window.decorView.parent)
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()

        // What showEngineOffer does: the other engine is the answer, so the focus starts there.
        engine.requestFocus()
        assertEquals(true, engine.isFocused)
        assertSame(always, engine.focusSearch(View.FOCUS_DOWN))
        assertSame(engine, always.focusSearch(View.FOCUS_UP))
        // Go back stays below both, the way out once the attempts have been offered.
        val below = generateSequence(always as View) { it.focusSearch(View.FOCUS_DOWN)?.takeIf { next -> next !== it } }
            .take(6).toList()
        assertEquals(true, back in below)
    }
}
