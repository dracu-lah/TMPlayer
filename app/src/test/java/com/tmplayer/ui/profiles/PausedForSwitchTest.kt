package com.tmplayer.ui.profiles

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The downloads a switch paused, kept on the device per profile (HX88): they are there for that
 * profile when it is open next (a process that died mid wait included), never for another one, and
 * taken once.
 */
@RunWith(RobolectricTestRunner::class)
class PausedForSwitchTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `the paused downloads come back for their own profile, once, never for another`() {
        PausedForSwitch.record(context, null, listOf(4, 5))
        assertTrue("not another profile's", PausedForSwitch.take(context, "pbbbbb").isEmpty())
        assertEquals("the adopted profile's, after a restart too", listOf(4, 5), PausedForSwitch.take(context, null))
        assertTrue("taken once", PausedForSwitch.take(context, null).isEmpty())
    }
}
