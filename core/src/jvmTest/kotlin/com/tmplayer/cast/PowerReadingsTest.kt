package com.tmplayer.cast

import com.tmplayer.cast.PowerReadings.Power
import com.tmplayer.cast.PowerReadings.Supply
import com.tmplayer.cast.PowerReadings.ThermalZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.random.Random

/** The OS's battery and heat answers as text, per platform, with the guard's verdict on each. */
class PowerReadingsTest {

    private fun battery(capacity: String?, status: String?, scope: String? = null) = Supply("Battery", scope, status, capacity, null)
    private fun charger(online: String?, type: String = "Mains") = Supply(type, null, null, null, online)

    private fun verdict(p: Power, thermal: DeviceHealth.Thermal = DeviceHealth.Thermal.Unknown) =
        TranscodeGuard.check(DeviceHealth(p.percent, p.charging, thermal))

    // ---- Linux ----------------------------------------------------------------------------------

    @Test
    fun `linux battery discharging at 12 percent stops the transcode`() {
        val p = PowerReadings.linuxPower(listOf(battery("12\n", "Discharging\n"), charger("0\n")))
        assertEquals(Power(12, false), p)
        assertEquals(TranscodeStop.BatteryLow, verdict(p))
    }

    @Test
    fun `linux battery at 12 percent on the charger goes on`() {
        for (supplies in listOf(
            listOf(battery("12", "Charging"), charger("1")),
            listOf(battery("12", "Unknown"), charger("1")),
            listOf(battery("12", "Not charging")),
            listOf(battery("12", "Full")),
            listOf(battery("12", "Discharging"), charger("1", "USB_C")),
        )) {
            val p = PowerReadings.linuxPower(supplies)
            assertEquals(true, p.charging)
            assertNull(verdict(p))
        }
    }

    @Test
    fun `linux battery at exactly 15 percent goes on, 14 stops`() {
        assertNull(verdict(PowerReadings.linuxPower(listOf(battery("15", "Discharging")))))
        assertEquals(TranscodeStop.BatteryLow, verdict(PowerReadings.linuxPower(listOf(battery("14", "Discharging")))))
    }

    @Test
    fun `linux with no battery, a mouse's battery or an unsure plug is unknown and never stops`() {
        assertEquals(Power.UNKNOWN, PowerReadings.linuxPower(emptyList()))
        assertEquals(Power.UNKNOWN, PowerReadings.linuxPower(listOf(charger("1"))))
        assertEquals(Power.UNKNOWN, PowerReadings.linuxPower(listOf(battery("5", "Discharging", scope = "Device"))))
        // Low, state not told, no charger shown: not guessed.
        val unsure = PowerReadings.linuxPower(listOf(battery("5", "Unknown")))
        assertEquals(Power.UNKNOWN, unsure)
        assertNull(verdict(unsure))
    }

    @Test
    fun `linux two batteries are averaged`() {
        assertEquals(Power(20, false), PowerReadings.linuxPower(listOf(battery("10", "Discharging"), battery("30", "Discharging"))))
    }

    @Test
    fun `linux capacity that is junk or out of range is ignored`() {
        for (c in listOf("", "x", "-1", "101", "999999999999", null)) {
            assertEquals("$c", Power.UNKNOWN, PowerReadings.linuxPower(listOf(battery(c, "Discharging"))))
        }
    }

    @Test
    fun `linux cpu temperature picks the hottest cpu zone and ignores the rest`() {
        val zones = listOf(
            ThermalZone("acpitz\n", "99000\n"), // the board, not the CPU
            ThermalZone("x86_pkg_temp\n", "67000\n"),
            ThermalZone("x86_pkg_temp", "96000"),
            ThermalZone("iwlwifi_1", "120000"),
            ThermalZone("cpu-thermal", "-274000"),
        )
        assertEquals(96.0, PowerReadings.linuxCpuCelsius(zones)!!, 0.0)
        assertNull(PowerReadings.linuxCpuCelsius(listOf(ThermalZone("acpitz", "50000"))))
        assertNull(PowerReadings.linuxCpuCelsius(listOf(ThermalZone("x86_pkg_temp", "hot"))))
        assertNull(PowerReadings.linuxCpuCelsius(emptyList()))
    }

    @Test
    fun `the thermal steps and the guard's verdict`() {
        assertEquals(DeviceHealth.Thermal.Unknown, PowerReadings.thermalForCelsius(null))
        assertEquals(DeviceHealth.Thermal.Unknown, PowerReadings.thermalForCelsius(Double.NaN))
        assertEquals(DeviceHealth.Thermal.None, PowerReadings.thermalForCelsius(60.0))
        assertEquals(DeviceHealth.Thermal.Light, PowerReadings.thermalForCelsius(85.0))
        assertEquals(DeviceHealth.Thermal.Moderate, PowerReadings.thermalForCelsius(94.9))
        assertEquals(DeviceHealth.Thermal.Severe, PowerReadings.thermalForCelsius(95.0))
        assertEquals(TranscodeStop.TooHot, verdict(Power.UNKNOWN, DeviceHealth.Thermal.Severe))
        assertNull(verdict(Power.UNKNOWN, DeviceHealth.Thermal.Moderate))
    }

    @Test
    fun `a spike is not a stop, three hot readings in a row are`() {
        val d = HeatDebounce(3)
        val severe = DeviceHealth.Thermal.Severe
        assertEquals(DeviceHealth.Thermal.Moderate, d.apply(severe))
        assertEquals(DeviceHealth.Thermal.None, d.apply(DeviceHealth.Thermal.None))
        assertEquals(DeviceHealth.Thermal.Moderate, d.apply(severe))
        assertEquals(DeviceHealth.Thermal.Moderate, d.apply(severe))
        assertEquals(severe, d.apply(severe))
        d.reset()
        assertEquals(DeviceHealth.Thermal.Moderate, d.apply(severe))
        assertEquals(DeviceHealth.Thermal.Unknown, d.apply(DeviceHealth.Thermal.Unknown))
    }

    // ---- macOS ----------------------------------------------------------------------------------

    @Test
    fun `mac on battery and on the charger`() {
        val onBattery = "Now drawing from 'Battery Power'\n -InternalBattery-0 (id=4653155)\t12%; discharging; 0:25 remaining present: true\n"
        assertEquals(Power(12, false), PowerReadings.macPower(onBattery))
        assertEquals(TranscodeStop.BatteryLow, verdict(PowerReadings.macPower(onBattery)))
        val onCharger = "Now drawing from 'AC Power'\n -InternalBattery-0 (id=4653155)\t12%; charging; 1:10 remaining present: true\n"
        assertEquals(Power(12, true), PowerReadings.macPower(onCharger))
        assertNull(verdict(PowerReadings.macPower(onCharger)))
        assertEquals(Power(100, true), PowerReadings.macPower("Now drawing from 'AC Power'\n -InternalBattery-0 (id=1)\t100%; charged; 0:00 remaining present: true"))
    }

    @Test
    fun `mac with no battery, a ups or odd text is unknown`() {
        assertEquals(Power.UNKNOWN, PowerReadings.macPower("Now drawing from 'AC Power'\n"))
        assertEquals(Power.UNKNOWN, PowerReadings.macPower("Now drawing from 'UPS Power'\n -InternalBattery-0\t50%; discharging"))
        assertEquals(Power.UNKNOWN, PowerReadings.macPower(null))
        assertEquals(Power.UNKNOWN, PowerReadings.macPower("-InternalBattery-0 (id=1)\t200%; discharging"))
    }

    @Test
    fun `mac thermal from the cpu speed limit or the no warning note`() {
        assertEquals(DeviceHealth.Thermal.None, PowerReadings.macThermal("CPU_Scheduler_Limit = 100\nCPU_Available_CPUs = 8\nCPU_Speed_Limit = 100\n"))
        assertEquals(DeviceHealth.Thermal.Moderate, PowerReadings.macThermal("CPU_Speed_Limit = 75"))
        assertEquals(DeviceHealth.Thermal.Severe, PowerReadings.macThermal("CPU_Speed_Limit = 40"))
        assertEquals(
            DeviceHealth.Thermal.None,
            PowerReadings.macThermal("Note: No thermal warning level has been recorded\nNote: No performance warning level has been recorded\n"),
        )
        assertEquals(DeviceHealth.Thermal.Unknown, PowerReadings.macThermal("something else"))
        assertEquals(DeviceHealth.Thermal.Unknown, PowerReadings.macThermal(null))
    }

    // ---- Windows --------------------------------------------------------------------------------

    @Test
    fun `windows battery status codes`() {
        assertEquals(Power(10, false), PowerReadings.windowsPower("10,1\r\n"))
        assertEquals(TranscodeStop.BatteryLow, verdict(PowerReadings.windowsPower("10,1")))
        assertEquals(Power(10, false), PowerReadings.windowsPower("10,5"))
        assertEquals(Power(10, true), PowerReadings.windowsPower("10,6"))
        assertEquals(Power(10, true), PowerReadings.windowsPower("10,2"))
        assertNull(verdict(PowerReadings.windowsPower("10,2")))
        assertEquals(Power(100, true), PowerReadings.windowsPower("100,3"))
    }

    @Test
    fun `windows with no battery, an unknown status or an error is unknown`() {
        assertEquals(Power.UNKNOWN, PowerReadings.windowsPower(""))
        assertEquals(Power.UNKNOWN, PowerReadings.windowsPower(null))
        assertEquals(Power.UNKNOWN, PowerReadings.windowsPower("10,10"))
        assertEquals(Power.UNKNOWN, PowerReadings.windowsPower("Get-CimInstance : Access denied"))
        assertEquals(Power.UNKNOWN, PowerReadings.windowsPower(",1"))
        assertEquals(Power.UNKNOWN, PowerReadings.windowsPower("500,1"))
    }

    // ---- hostile text ---------------------------------------------------------------------------

    @Test
    fun `hostile text never throws`() {
        val rnd = Random(3)
        val texts = listOf("", "\u0000".repeat(100), "9".repeat(100_000), "%".repeat(1000), "\n".repeat(100_000), "CPU_Speed_Limit = " + "9".repeat(5000)) +
            List(300) { String(CharArray(rnd.nextInt(0, 300)) { rnd.nextInt(0, 0xFFFF).toChar() }) }
        for (t in texts) {
            PowerReadings.macPower(t)
            PowerReadings.macThermal(t)
            PowerReadings.windowsPower(t)
            PowerReadings.linuxPower(listOf(Supply(t, t, t, t, t)))
            PowerReadings.linuxCpuCelsius(listOf(ThermalZone(t, t)))
        }
    }
}
