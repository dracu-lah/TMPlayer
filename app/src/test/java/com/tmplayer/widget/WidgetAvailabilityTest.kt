package com.tmplayer.widget

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The widget receiver is on a phone or tablet and off on a television, set once and never twice. */
@RunWith(RobolectricTestRunner::class)
class WidgetAvailabilityTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val receiver = ComponentName(context, ContinueWidgetProvider::class.java)

    private fun state() = context.packageManager.getComponentEnabledSetting(receiver)

    @Test
    fun aTelevisionSwitchesItOffAndAPhoneLeavesItAlone() {
        val default = PackageManager.COMPONENT_ENABLED_STATE_DEFAULT
        val enabled = PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        val disabled = PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        assertEquals(disabled, WidgetAvailability.change(default, television = true))
        assertEquals(disabled, WidgetAvailability.change(enabled, television = true))
        assertNull(WidgetAvailability.change(disabled, television = true))
        assertNull(WidgetAvailability.change(default, television = false))
        assertNull(WidgetAvailability.change(enabled, television = false))
    }

    @Test
    fun aPhoneOrTabletComingBackFromTelevisionGetsItBack() {
        assertEquals(
            PackageManager.COMPONENT_ENABLED_STATE_DEFAULT,
            WidgetAvailability.change(PackageManager.COMPONENT_ENABLED_STATE_DISABLED, television = false),
        )
    }

    @Test
    fun syncOnATelevisionDisablesTheReceiverOnce() {
        WidgetAvailability.sync(context, television = true)
        assertEquals(PackageManager.COMPONENT_ENABLED_STATE_DISABLED, state())
        // Again: nothing to change, and nothing throws.
        WidgetAvailability.sync(context, television = true)
        assertEquals(PackageManager.COMPONENT_ENABLED_STATE_DISABLED, state())
    }

    @Test
    fun syncOnAPhoneKeepsTheReceiverEnabled() {
        WidgetAvailability.sync(context, television = false)
        val now = state()
        assert(now != PackageManager.COMPONENT_ENABLED_STATE_DISABLED)
        // The manifest's own value is enabled.
        assert(context.packageManager.getReceiverInfo(receiver, 0).enabled)
    }

    @Test
    fun syncOnAPhoneReEnablesAReceiverTheTelevisionSwitchedOff() {
        WidgetAvailability.sync(context, television = true)
        WidgetAvailability.sync(context, television = false)
        assert(state() != PackageManager.COMPONENT_ENABLED_STATE_DISABLED)
        assert(context.packageManager.getReceiverInfo(receiver, 0).enabled)
    }
}
