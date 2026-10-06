package com.tmplayer.player

import android.os.Looper
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import androidx.fragment.app.FragmentActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

/**
 * The text pill and the offer, which replaced the old gesture text view and the overflow's
 * "Start over" line. Both serve the phone and the television.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PlayerFeedbackTest {

    private lateinit var root: FrameLayout
    private lateinit var feedback: PlayerFeedback

    @Before
    fun setUp() {
        val activity = Robolectric.buildActivity(FragmentActivity::class.java).setup().get()
        root = FrameLayout(activity)
        activity.setContentView(root)
        feedback = PlayerFeedback(root, null)
    }

    private fun visibleTexts(): List<String> {
        val found = mutableListOf<String>()
        fun walk(view: View) {
            if (view.visibility != View.VISIBLE) return
            if (view is TextView && view.text.isNotBlank()) found += view.text.toString()
            (view as? android.view.ViewGroup)?.let { group -> (0 until group.childCount).forEach { walk(group.getChildAt(it)) } }
        }
        walk(root)
        return found
    }

    private fun idle(ms: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))

    @Test
    fun `a message shows its text and goes away by itself`() {
        feedback.message("1.5x")
        idle(100)
        assertEquals(listOf("1.5x"), visibleTexts())
        idle(3_000)
        assertTrue(visibleTexts().isEmpty())
    }

    @Test
    fun `a hint stays as long as it was asked to`() {
        feedback.message("Double tap to jump", holdMs = 5_000L)
        idle(3_000)
        assertEquals(listOf("Double tap to jump"), visibleTexts())
        idle(3_000)
        assertTrue(visibleTexts().isEmpty())
    }

    @Test
    fun `the offer carries its button, which runs the action and puts the offer away`() {
        var started = 0
        feedback.offer("Resuming from 12:30", "Start over") { started++ }
        idle(100)
        assertEquals(listOf("Resuming from 12:30", "Start over"), visibleTexts())
        val button = findText(root, "Start over")!!
        button.performClick()
        idle(1_000)
        assertEquals(1, started)
        assertTrue(visibleTexts().isEmpty())
    }

    @Test
    fun `an offer left alone goes away on its own`() {
        feedback.offer("Resuming from 12:30", "Start over") {}
        idle(10_000)
        assertTrue(visibleTexts().isEmpty())
    }

    private fun findText(view: View, text: String): TextView? {
        if (view is TextView && view.text.toString() == text) return view
        if (view is android.view.ViewGroup) {
            for (i in 0 until view.childCount) findText(view.getChildAt(i), text)?.let { return it }
        }
        return null
    }
}
