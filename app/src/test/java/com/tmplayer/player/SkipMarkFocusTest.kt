package com.tmplayer.player

import android.view.View
import android.widget.FrameLayout
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
import com.tmplayer.data.FormFactor
import com.tmplayer.i18n.L
import com.tmplayer.online.SkipKind
import com.tmplayer.online.SkipSegment
import com.tmplayer.online.SkipSource
import com.tmplayer.ui.DrainMainLooperRule
import com.tmplayer.ui.components.ChoiceLine
import com.tmplayer.ui.components.ChoiceList
import com.tmplayer.ui.theme.TmMaterialTheme
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
import org.robolectric.util.ReflectionHelpers

/**
 * "Mark this as the intro" and the skip pill on a television, by remote alone: the menu line sits
 * where [PlayerMenu] puts it and OK presses it, and the pill never takes focus (OK belongs to the
 * activity while it is up, Back cancels a countdown) yet says so on its second line.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w960dp-h540dp-land-television-night-xhdpi")
class SkipMarkFocusTest {

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
    fun `the remote walks the menu to Mark this as the intro and OK presses it`() {
        FormFactor.override(true)
        val lines = PlayerMenu.tvEntries(pictureInPicture = false, bookmarks = true, markIntro = true, removeIntroMark = true)
        // Right after the bookmarks, before the lines about other apps.
        assertEquals(
            listOf(PlayerMenuEntry.Bookmarks, PlayerMenuEntry.MarkIntro, PlayerMenuEntry.RemoveIntroMark),
            lines.dropWhile { it != PlayerMenuEntry.Bookmarks }.take(3),
        )
        var pressed = 0
        rule.setContent {
            TmMaterialTheme(dark = true) {
                Column(androidx.compose.ui.Modifier.fillMaxSize()) {
                    ChoiceList {
                        ChoiceLine(title = L.playerBookmarks, onClick = {})
                        ChoiceLine(title = L.playerMarkIntro, onClick = { pressed++ })
                        ChoiceLine(title = L.playerMarkIntroRemove, onClick = {})
                    }
                }
            }
        }
        leaveTouchMode(rule.activity.window.decorView.parent)
        rule.waitForIdle()

        rule.onNodeWithText(L.playerBookmarks).requestFocus()
        rule.onNodeWithText(L.playerBookmarks).performKeyInput { pressKey(Key.DirectionDown) }
        rule.waitForIdle()
        rule.onNodeWithText(L.playerMarkIntro).assertIsFocused()
        rule.onNodeWithText(L.playerMarkIntro).performKeyInput { pressKey(Key.DirectionCenter) }
        rule.waitForIdle()
        assertEquals(1, pressed)
        rule.onNodeWithText(L.playerMarkIntro).performKeyInput { pressKey(Key.DirectionDown) }
        rule.waitForIdle()
        rule.onNodeWithText(L.playerMarkIntroRemove).assertIsFocused()
    }

    @Test
    fun `the line is offered only for an episode and the removal only with a mark`() {
        assertFalse(PlayerMenuEntry.MarkIntro in PlayerMenu.tvEntries(pictureInPicture = true, bookmarks = true))
        assertFalse(PhoneMenuEntry.MarkIntro in PlayerMenu.phoneEntries(true, true, true, true, bookmarks = true))
        val withMark = PlayerMenu.phoneEntries(true, true, true, true, bookmarks = true, markIntro = true)
        assertTrue(PhoneMenuEntry.MarkIntro in withMark)
        assertFalse(PhoneMenuEntry.RemoveIntroMark in withMark)
        assertTrue(PhoneMenuEntry.RemoveIntroMark in PlayerMenu.phoneEntries(true, true, true, true, markIntro = true, removeIntroMark = true))
        // The line says what the next press does.
        assertEquals(L.playerMarkIntro, PhoneMenuEntry.MarkIntro.label())
        assertEquals(L.playerMarkIntroEnd, PhoneMenuEntry.MarkIntro.label(introMarking = true))
    }

    private fun pill(tv: Boolean, onSkip: () -> Unit = {}, onCancel: () -> Unit = {}): SkipPill {
        val root = FrameLayout(rule.activity)
        val below = View(rule.activity)
        root.addView(below)
        rule.activity.setContentView(root)
        return SkipPill(root, below, tv, onSkip, onCancel)
    }

    @Test
    fun `on a television the pill is never focusable, says OK to skip, and a countdown says Back to cancel`() {
        val pill = pill(tv = true)
        val intro = SkipSegment(SkipKind.Opening, 60_000, 150_000)
        pill.show(intro)
        assertTrue(pill.isShown)
        assertFalse(pill.view.isFocusable)
        assertFalse(pill.view.isClickable)
        assertFalse(pill.isCounting)
        pill.show(intro, secondsLeft = 3)
        assertTrue(pill.isCounting)
        assertFalse(pill.view.isFocusable)
        pill.hide()
        assertFalse(pill.isShown)
        assertFalse(pill.isCounting)
    }

    @Test
    fun `on a phone the pill skips on a tap and its countdown has its own Cancel`() {
        var skipped = 0
        var cancelled = 0
        val pill = pill(tv = false, onSkip = { skipped++ }, onCancel = { cancelled++ })
        pill.show(SkipSegment(SkipKind.Intro, 60_000, 150_000, SkipSource.Guess), secondsLeft = 2)
        assertTrue(pill.isCounting)
        val cancel = (pill.view.getChildAt(pill.view.childCount - 1))
        assertEquals(View.VISIBLE, cancel.visibility)
        cancel.performClick()
        assertEquals(1, cancelled)
        assertEquals(0, skipped)
        pill.view.performClick()
        assertEquals(1, skipped)
        // Not counting: no Cancel.
        pill.show(SkipSegment(SkipKind.Intro, 60_000, 150_000, SkipSource.Mark))
        assertEquals(View.GONE, cancel.visibility)
    }
}
