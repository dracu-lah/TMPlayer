package com.tmplayer.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** One case per kind of device the TV or phone decision has to get right (HX78). */
class FormRulesTest {

    private fun tv(s: FormSignals) = assertEquals(DeviceForm.Tv, FormRules.classify(s), FormRules.reason(s))
    private fun phone(s: FormSignals) = assertEquals(DeviceForm.Phone, FormRules.classify(s), FormRules.reason(s))

    private val phoneHardware = FormSignals(
        leanback = false, uiModeTv = false, pc = false, chromebook = false, watch = false,
        accelerometer = true, touchInput = true, battery = true,
    )
    private val boxHardware = FormSignals(
        leanback = false, uiModeTv = false, pc = false, chromebook = false, watch = false,
        accelerometer = false, touchInput = false, battery = false,
    )

    @Test fun certifiedTvs() {
        // Chromecast with Google TV, a Mi TV stick, a Fire TV: leanback, whatever else they say.
        tv(phoneHardware.copy(leanback = true))
        tv(boxHardware.copy(leanback = true))
        tv(FormSignals(leanback = true))
        // A box that reports the television UI mode without the feature.
        tv(phoneHardware.copy(uiModeTv = true))
        assertFalse(FormRules.guessed(phoneHardware.copy(leanback = true)))
    }

    @Test fun genericBoxes() {
        // A T95 that tells the truth.
        tv(boxHardware)
        assertEquals("no-accel+notouch+nobattery", FormRules.reason(boxHardware))
        assertTrue(FormRules.guessed(boxHardware))
        // Firmware that fakes a battery but has no touchscreen attached.
        tv(boxHardware.copy(battery = true))
        // Ugoos-like: a touchscreen is reported, no battery.
        tv(boxHardware.copy(touchInput = true))
        // Battery unreadable, no touch.
        tv(boxHardware.copy(battery = null))
    }

    @Test fun boxFakingEverythingStaysPhone() {
        // Touch and battery both faked: the setting and the remote nudge are the way out.
        phone(boxHardware.copy(touchInput = true, battery = true))
    }

    @Test fun phonesAndTablets() {
        phone(phoneHardware)
        // A cheap tablet without an accelerometer still has touch and a battery.
        phone(phoneHardware.copy(accelerometer = false))
        // A tablet with a keyboard: touch is still there.
        phone(phoneHardware.copy(touchInput = true))
        // A phone whose battery read failed.
        phone(phoneHardware.copy(battery = null))
    }

    @Test fun desktopsAndWatches() {
        phone(boxHardware.copy(chromebook = true))
        phone(boxHardware.copy(pc = true))
        // Waydroid on a desktop: no sensors, a mouse, the pc feature.
        phone(boxHardware.copy(pc = true, touchInput = true))
        phone(boxHardware.copy(watch = true))
    }

    @Test fun unknownLeansPhone() {
        phone(FormSignals())
        assertEquals("unknown", FormRules.reason(FormSignals()))
        // No accelerometer but nothing else known.
        phone(FormSignals(accelerometer = false))
    }
}
