package com.tmplayer.ui.downloads

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.Lifecycle
import com.tmplayer.ui.DrainMainLooperRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * "Back from the player returns focus to the opener" (R2): the row that opened the player is the one
 * the page gives the remote back to when it resumes, once, and a resume with nothing opened (the
 * page's first, or after the notification shade) gives it to nothing.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w960dp-h540dp-land-television-night-xhdpi")
class OpenerFocusTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    /** A state write left on the looper must not silence Compose for the next test (HX92). */
    @get:Rule
    val drain = DrainMainLooperRule()

    private val memory = OpenerMemory()
    private val restored = mutableListOf<String>()

    private fun open() {
        rule.setContent { RestoreOpenerOnResume(memory) { restored += it } }
        rule.waitForIdle()
    }

    /** The player coming up over the page and closing again: the page stops, then resumes. */
    private fun playerOpensAndCloses() {
        rule.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        rule.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        rule.waitForIdle()
    }

    @Test
    fun `the row that opened the player gets the remote back when the page resumes`() {
        open()
        memory.opened("kept_2_21")
        playerOpensAndCloses()
        assertEquals(listOf("kept_2_21"), restored)
    }

    @Test
    fun `only once, and a resume with nothing opened gives it to nothing`() {
        open()
        assertEquals("the page's own first resume", emptyList<String>(), restored)
        memory.opened("kept_2_21")
        playerOpensAndCloses()
        playerOpensAndCloses()
        assertEquals(listOf("kept_2_21"), restored)
    }

    @Test
    fun `a second video opened is the one remembered`() {
        open()
        memory.opened("kept_2_21")
        memory.opened("cached_2_31")
        playerOpensAndCloses()
        assertEquals(listOf("cached_2_31"), restored)
    }

    @Test
    fun `the memory gives a row once and then nothing`() {
        val memory = OpenerMemory()
        assertNull(memory.takeOnReturn())
        memory.opened("a")
        assertEquals("a", memory.takeOnReturn())
        assertNull(memory.takeOnReturn())
    }
}
