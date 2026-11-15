package com.tmplayer.desktop.cast

import com.tmplayer.cast.DeviceHealth
import com.tmplayer.cast.TranscodeGuard
import com.tmplayer.cast.TranscodeStop
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The desktop's battery and heat guard on fake files and commands: what each OS says, and when it stops a transcode. */
class DesktopDeviceHealthTest {

    private class Fs(val files: MutableMap<String, String>) : SysFiles {
        override fun read(path: String) = files[path]
        override fun exists(path: String) = path in files
        override fun list(path: String): List<String> =
            files.keys.filter { it.startsWith("$path/") }.map { it.removePrefix("$path/").substringBefore('/') }.distinct().sorted()
    }

    private class Commands(val answers: MutableMap<String, String?>) : CommandRunner {
        override fun run(args: List<String>, env: Map<String, String>): String? = answers[args.take(3).joinToString(" ")]
    }

    private class Clock(var now: Long = 0) : () -> Long {
        override fun invoke() = now
    }

    private fun linux(status: String, capacity: String, cpu: String? = null, mains: String? = "0"): Fs {
        val f = mutableMapOf(
            "/sys/class/power_supply/BAT0/type" to "Battery\n",
            "/sys/class/power_supply/BAT0/status" to "$status\n",
            "/sys/class/power_supply/BAT0/capacity" to "$capacity\n",
        )
        if (mains != null) {
            f["/sys/class/power_supply/AC/type"] = "Mains\n"
            f["/sys/class/power_supply/AC/online"] = "$mains\n"
        }
        if (cpu != null) {
            f["/sys/class/thermal/thermal_zone0/type"] = "x86_pkg_temp\n"
            f["/sys/class/thermal/thermal_zone0/temp"] = "$cpu\n"
        }
        return Fs(f)
    }

    private fun health(
        os: DesktopOs,
        fs: SysFiles = Fs(mutableMapOf()),
        commands: CommandRunner = Commands(mutableMapOf()),
        clock: Clock = Clock(),
        tasks: MutableList<Runnable>? = null,
    ) = DesktopDeviceHealth(os, fs, commands, clock, { r -> if (tasks != null) tasks += r else r.run() })

    @Test
    fun `linux off the charger under 15 percent stops, on the charger it does not`() {
        assertEquals(TranscodeStop.BatteryLow, TranscodeGuard.check(health(DesktopOs.Linux, linux("Discharging", "9")).read()))
        assertNull(TranscodeGuard.check(health(DesktopOs.Linux, linux("Charging", "9", mains = "1")).read()))
        assertNull(TranscodeGuard.check(health(DesktopOs.Linux, linux("Discharging", "80")).read()))
    }

    @Test
    fun `a desktop with no battery reads as unknown and never stops`() {
        val h = health(DesktopOs.Linux, Fs(mutableMapOf("/sys/class/power_supply/AC/type" to "Mains", "/sys/class/power_supply/AC/online" to "1"))).read()
        assertEquals(DeviceHealth(), h)
        assertNull(TranscodeGuard.check(h))
        assertNull(TranscodeGuard.check(health(DesktopOs.Linux, Fs(mutableMapOf())).read()))
    }

    @Test
    fun `linux heat stops only when the cpu stays at its limit`() {
        val fs = linux("Discharging", "90", cpu = "97000")
        val clock = Clock()
        val h = health(DesktopOs.Linux, fs, clock = clock)
        // The first reading is a spike (Moderate); the third in a row is Severe.
        assertNull(TranscodeGuard.check(h.read()))
        clock.now += 11_000
        h.read()
        clock.now += 11_000
        h.read()
        clock.now += 11_000
        assertEquals(TranscodeStop.TooHot, TranscodeGuard.check(h.read()))
        // It cools down: the readings clear.
        fs.files["/sys/class/thermal/thermal_zone0/temp"] = "70000\n"
        clock.now += 11_000
        h.read()
        clock.now += 11_000
        assertNull(TranscodeGuard.check(h.read()))
    }

    @Test
    fun `readings are kept for the interval and refreshed in the background`() {
        val fs = linux("Discharging", "80")
        val clock = Clock()
        val tasks = ArrayList<Runnable>()
        val h = health(DesktopOs.Linux, fs, clock = clock, tasks = tasks)
        assertEquals(80, h.read().batteryPercent)
        fs.files["/sys/class/power_supply/BAT0/capacity"] = "10\n"
        clock.now += 5_000
        assertEquals(80, h.read().batteryPercent)
        assertEquals(0, tasks.size)
        clock.now += 6_000
        // Stale: the answer is still the old one, and one refresh is started, not two.
        assertEquals(80, h.read().batteryPercent)
        assertEquals(80, h.read().batteryPercent)
        assertEquals(1, tasks.size)
        tasks.single().run()
        assertEquals(10, h.read().batteryPercent)
    }

    @Test
    fun `a reading too old to trust is taken again on the spot`() {
        val fs = linux("Discharging", "80")
        val clock = Clock()
        val tasks = ArrayList<Runnable>()
        val h = health(DesktopOs.Linux, fs, clock = clock, tasks = tasks)
        h.read()
        fs.files["/sys/class/power_supply/BAT0/capacity"] = "10\n"
        clock.now += 61_000
        assertEquals(10, h.read().batteryPercent)
        assertEquals(0, tasks.size)
    }

    @Test
    fun `mac reads pmset for the battery and the heat`() {
        val c = Commands(
            mutableMapOf(
                "pmset -g batt" to "Now drawing from 'Battery Power'\n -InternalBattery-0 (id=1)\t11%; discharging; 0:20 remaining present: true",
                "pmset -g therm" to "CPU_Speed_Limit = 100",
            ),
        )
        val h = health(DesktopOs.Mac, commands = c).read()
        assertEquals(DeviceHealth(11, false, DeviceHealth.Thermal.None), h)
        assertEquals(TranscodeStop.BatteryLow, TranscodeGuard.check(h))
    }

    @Test
    fun `windows reads the battery through powershell and says nothing of heat`() {
        val c = Commands(mutableMapOf("powershell -NoProfile -NonInteractive" to "9,1\r\n"))
        val h = health(DesktopOs.Windows, commands = c).read()
        assertEquals(DeviceHealth(9, false, DeviceHealth.Thermal.Unknown), h)
        assertEquals(TranscodeStop.BatteryLow, TranscodeGuard.check(h))
    }

    @Test
    fun `no powershell, no pmset, an unknown os or a reader that throws is unknown`() {
        assertEquals(DeviceHealth(), health(DesktopOs.Windows, commands = Commands(mutableMapOf())).read())
        assertEquals(DeviceHealth(), health(DesktopOs.Mac, commands = Commands(mutableMapOf())).read())
        assertEquals(DeviceHealth(), health(DesktopOs.Other).read())
        val throwing = object : SysFiles {
            override fun read(path: String): String? = error("denied")
            override fun exists(path: String) = false
            override fun list(path: String): List<String> = listOf("BAT0")
        }
        assertEquals(DeviceHealth(), health(DesktopOs.Linux, throwing).read())
    }
}
