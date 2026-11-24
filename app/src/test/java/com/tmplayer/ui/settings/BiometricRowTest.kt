package com.tmplayer.ui.settings

import com.tmplayer.ui.lock.BiometricState
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * SEC2-07: the app lock asks for a Class 3 biometric only, so a phone with only a weaker sensor sees no row,
 * and a person who had the switch on keeps a disabled row that says the PIN is used.
 */
class BiometricRowTest {

    @Test
    fun `a phone without a strong sensor shows no row, unless the switch was on`() {
        assertEquals(BiometricRow.Hidden, biometricRowOf(BiometricState.Unavailable, switchedOn = false))
        assertEquals(BiometricRow.Weaker, biometricRowOf(BiometricState.Unavailable, switchedOn = true))
    }

    @Test
    fun `a strong sensor shows the row, enrolled or not, whatever the switch was`() {
        assertEquals(BiometricRow.Ready, biometricRowOf(BiometricState.Ready, switchedOn = false))
        assertEquals(BiometricRow.Ready, biometricRowOf(BiometricState.Ready, switchedOn = true))
        assertEquals(BiometricRow.NoneEnrolled, biometricRowOf(BiometricState.NoneEnrolled, switchedOn = false))
        assertEquals(BiometricRow.NoneEnrolled, biometricRowOf(BiometricState.NoneEnrolled, switchedOn = true))
    }
}
