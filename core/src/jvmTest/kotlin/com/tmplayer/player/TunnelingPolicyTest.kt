package com.tmplayer.player

import com.tmplayer.data.DeviceFacts
import com.tmplayer.data.KnownDevice
import com.tmplayer.data.KnownDevices
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TunnelingPolicyTest {

    private fun input(
        mode: TunneledMode = TunneledMode.Auto,
        television: Boolean = true,
        knownDevice: Boolean? = null,
        drawsOnPicture: Boolean = false,
        codecCanTunnel: Boolean = true,
        failedBefore: Boolean = false,
    ) = TunnelingPolicy.Input(mode, television, knownDevice, drawsOnPicture, codecCanTunnel, failedBefore)

    @Test
    fun autoIsOffUnlessTheKnownDeviceListSaysOtherwise() {
        assertEquals(TunnelingPolicy.Reason.AutoOff, TunnelingPolicy.decide(input()).reason)
        assertFalse(TunnelingPolicy.decide(input(knownDevice = null)).tunneled)
        assertFalse(TunnelingPolicy.decide(input(knownDevice = false)).tunneled)
        assertTrue(TunnelingPolicy.decide(input(knownDevice = true)).tunneled)
    }

    @Test
    fun onTunnelsAndOffNeverDoes() {
        assertTrue(TunnelingPolicy.decide(input(mode = TunneledMode.On)).tunneled)
        assertFalse(TunnelingPolicy.decide(input(mode = TunneledMode.Off, knownDevice = true)).tunneled)
    }

    @Test
    fun aPhoneNeverTunnelsWhateverIsStored() {
        val d = TunnelingPolicy.decide(input(mode = TunneledMode.On, television = false, knownDevice = true))
        assertFalse(d.tunneled)
        assertEquals(TunnelingPolicy.Reason.NotTelevision, d.reason)
    }

    @Test
    fun nothingTurnsItOnWhenThePictureIsDrawnOnOrTheDeviceLacksTheCapability() {
        for (mode in TunneledMode.entries) {
            assertFalse(TunnelingPolicy.decide(input(mode = mode, knownDevice = true, drawsOnPicture = true)).tunneled, "draws on picture, $mode")
            assertFalse(TunnelingPolicy.decide(input(mode = mode, knownDevice = true, codecCanTunnel = false)).tunneled, "no capability, $mode")
        }
        assertEquals(TunnelingPolicy.Reason.DrawsOnPicture, TunnelingPolicy.decide(input(mode = TunneledMode.On, drawsOnPicture = true)).reason)
        assertEquals(TunnelingPolicy.Reason.NoCodecSupport, TunnelingPolicy.decide(input(mode = TunneledMode.On, codecCanTunnel = false)).reason)
    }

    @Test
    fun aFailureEndsItForTheRestOfTheScreen() {
        val d = TunnelingPolicy.decide(input(mode = TunneledMode.On, knownDevice = true, failedBefore = true))
        assertFalse(d.tunneled)
        assertEquals(TunnelingPolicy.Reason.FailedBefore, d.reason)
    }

    @Test
    fun anErrorUnderTunnelingRetriesOnceAndNotForTheSource() {
        assertTrue(TunnelingPolicy.retriesWithout(tunneling = true, alreadyRetried = false, sourceFailure = false))
        assertFalse(TunnelingPolicy.retriesWithout(tunneling = true, alreadyRetried = true, sourceFailure = false))
        assertFalse(TunnelingPolicy.retriesWithout(tunneling = false, alreadyRetried = false, sourceFailure = false))
        assertFalse(TunnelingPolicy.retriesWithout(tunneling = true, alreadyRetried = false, sourceFailure = true))
    }

    @Test
    fun theKnownDeviceListCarriesATunnelingEntryWithNoEngineAtAll() {
        val feed = """{"devices":[{"id":"box","chipsets":["amlogic1"],"tunneling":true},{"id":"bad","chipsets":["x1x1x"],"tunneling":"yes"}]}"""
        val list = KnownDevices.parse(feed)
        assertEquals(listOf("box"), list.map { it.id }, "a tunneling-only entry is kept, a non-boolean one with nothing else is dropped")
        val device = DeviceFacts(hardware = "amlogic1")
        assertEquals(true, KnownDevices.tunnelingFor(list, device, null))
        assertNull(KnownDevices.tunnelingFor(list, DeviceFacts(hardware = "other"), null))
        // It says nothing about the engine: the selector does not see it as a preference.
        assertNull(KnownDevices.find(list, device, null))
    }

    @Test
    fun theFirstEntryThatStatesItWins() {
        val list = listOf(
            KnownDevice(id = "a", chipsets = listOf("amlogic1"), tunneling = false),
            KnownDevice(id = "b", chipsets = listOf("amlogic1"), tunneling = true),
        )
        assertEquals(false, KnownDevices.tunnelingFor(list, DeviceFacts(hardware = "amlogic1"), null))
    }

    @Test
    fun theShippedListDoesNotTurnItOnAnywhereYet() {
        assertNull(KnownDevices.tunnelingFor(KnownDevices.builtIn, DeviceFacts(hardware = "sun50iw6p1"), null))
    }
}
