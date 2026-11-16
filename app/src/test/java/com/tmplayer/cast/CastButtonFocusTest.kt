package com.tmplayer.cast

import android.view.View
import android.widget.LinearLayout
import androidx.activity.ComponentActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** FQ8: the player's Cast button that goes while it holds the focus hands it to its neighbour. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CastButtonFocusTest {

    private fun row(): Triple<ComponentActivity, View, View> {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        val cast = View(activity).apply { isFocusable = true; isFocusableInTouchMode = true }
        val more = View(activity).apply { isFocusable = true; isFocusableInTouchMode = true }
        val layout = LinearLayout(activity).apply {
            addView(cast, 48, 48)
            addView(more, 48, 48)
        }
        activity.setContentView(layout)
        return Triple(activity, cast, more)
    }

    @Test
    fun `a focused button that goes hands the focus to its neighbour`() {
        val (_, cast, more) = row()
        assertTrue(cast.requestFocus())
        showCastButton(cast, shown = false, neighbour = more)
        assertEquals(View.GONE, cast.visibility)
        assertTrue(more.isFocused)
    }

    @Test
    fun `the button goes and comes back`() {
        val (_, cast, more) = row()
        showCastButton(cast, shown = false, neighbour = more)
        assertEquals(View.GONE, cast.visibility)
        showCastButton(cast, shown = true, neighbour = more)
        assertEquals(View.VISIBLE, cast.visibility)
    }
}
