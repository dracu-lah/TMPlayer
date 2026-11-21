package com.tmplayer.ui.browse

import androidx.activity.ComponentActivity
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.tmplayer.ui.DrainMainLooperRule
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The floating bar returns after one second of the list standing still: every move starts the
 * wait again, a bar that is already out has nothing to wait for, and a pull down shows it at once.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w360dp-h800dp-xhdpi")
class BarRestTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    /** A state write left on the looper must not silence Compose for the next test (HX92). */
    @get:Rule
    val drain = DrainMainLooperRule()

    private val bar = BarVisibility()

    private fun push(dy: Float) = rule.runOnIdle {
        bar.connection.onPostScroll(Offset(0f, dy), Offset.Zero, NestedScrollSource.UserInput)
        // A scroll writes its state outside any composition; the app's main looper delivers that to
        // the flow, and the test's paused looper is told to.
        Snapshot.sendApplyNotifications()
    }

    private fun open() {
        rule.setContent { BarRest(bar) }
        rule.mainClock.autoAdvance = false
        rule.mainClock.advanceTimeBy(50)
    }

    @Test
    fun `a bar pushed out returns once the list has rested for a second`() {
        open()
        push(-100f)
        rule.mainClock.advanceTimeBy(50)
        assertFalse("pushed past the threshold hides it", bar.shown)
        rule.mainClock.advanceTimeBy(BAR_REST_MS - 200)
        assertFalse("still inside the second", bar.shown)
        rule.mainClock.advanceTimeBy(400)
        assertTrue("a second of rest brings it back", bar.shown)
    }

    @Test
    fun `every move starts the wait again`() {
        open()
        push(-100f)
        rule.mainClock.advanceTimeBy(900)
        push(-20f)
        rule.mainClock.advanceTimeBy(900)
        assertFalse("900 ms since the last move", bar.shown)
        push(-20f)
        rule.mainClock.advanceTimeBy(900)
        assertFalse(bar.shown)
        rule.mainClock.advanceTimeBy(300)
        assertTrue(bar.shown)
    }

    @Test
    fun `it can be hidden again after it returned, and a pull down shows it at once`() {
        open()
        push(-100f)
        rule.mainClock.advanceTimeBy(BAR_REST_MS + 200)
        assertTrue(bar.shown)
        push(-100f)
        rule.mainClock.advanceTimeBy(50)
        assertFalse("the threshold counts from zero again", bar.shown)
        push(60f)
        rule.mainClock.advanceTimeBy(50)
        assertTrue("a pull down needs no wait", bar.shown)
    }

    @Test
    fun `a small push never hides it, so the wait has nothing to do`() {
        open()
        rule.mainClock.advanceTimeBy(BAR_REST_MS * 3)
        assertTrue(bar.shown)
        push(-10f)
        rule.mainClock.advanceTimeBy(BAR_REST_MS * 2)
        assertTrue(bar.shown)
    }
}
