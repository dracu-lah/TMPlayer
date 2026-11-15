package com.tmplayer.desktop.cast

import com.tmplayer.cast.DeviceHealth
import com.tmplayer.cast.HeatDebounce
import com.tmplayer.cast.PowerReadings
import com.tmplayer.platform.Logger
import kotlin.concurrent.thread

/**
 * The battery and the heat of this computer, for the transcode's guard (stop under 15 % off the
 * charger, or when the CPU has sat at its limit), from what the OS exposes ([PowerReadings]):
 *
 * - Linux: `/sys/class/power_supply` and `/sys/class/thermal`.
 * - macOS: `pmset -g batt` and `pmset -g therm`. Written from pmset's documented output, not run on a Mac yet.
 * - Windows: the battery through PowerShell's `Win32_Battery`. Windows gives a normal user no CPU
 *   temperature, so the heat guard does nothing there: it says so in the log and does not guess.
 *
 * A desktop with no battery, or a platform that says nothing, reads as unknown, which never stops a
 * transcode. The first reading is taken then and there (the guard looks before a transcode starts);
 * later ones come from a background thread every [intervalMs], so a slow `powershell` never holds up
 * the transcode's progress.
 */
class DesktopDeviceHealth(
    private val os: DesktopOs = DesktopOs.current(),
    private val files: SysFiles = SysFiles.Default,
    private val commands: CommandRunner = CommandRunner.Default,
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
    /** Starts the background refresh; the tests run it on the spot. */
    private val background: (Runnable) -> Unit = { task -> thread(name = "device-health", isDaemon = true) { task.run() } },
    private val intervalMs: Long = if (os == DesktopOs.Windows) WINDOWS_INTERVAL_MS else INTERVAL_MS,
) {
    private val heat = HeatDebounce()
    private var last: DeviceHealth? = null
    private var readAt = 0L
    private var refreshing = false

    fun read(): DeviceHealth {
        val (cached, due) = synchronized(this) {
            val now = clock()
            val c = last
            // Never read, or too old to trust (no transcode for a while): read now.
            if (c == null || now - readAt >= MAX_STALE_MS) {
                if (c != null) heat.reset()
                return@synchronized null to true
            }
            val stale = now - readAt >= intervalMs && !refreshing
            if (stale) refreshing = true
            c to stale
        }
        if (cached == null) return refresh()
        if (due) background { refresh() }
        return cached
    }

    private fun refresh(): DeviceHealth {
        val health = runCatching { sample() }
            .onFailure { Logger.w(TAG, "Battery and heat not read: ${it.message}") }
            .getOrDefault(DeviceHealth())
        synchronized(this) {
            last = health
            readAt = clock()
            refreshing = false
        }
        return health
    }

    private fun sample(): DeviceHealth = when (os) {
        DesktopOs.Linux -> {
            val supplies = files.list("/sys/class/power_supply").map { name ->
                val dir = "/sys/class/power_supply/$name"
                PowerReadings.Supply(
                    type = files.read("$dir/type"), scope = files.read("$dir/scope"), status = files.read("$dir/status"),
                    capacity = files.read("$dir/capacity"), online = files.read("$dir/online"),
                )
            }
            val zones = files.list("/sys/class/thermal").filter { it.startsWith("thermal_zone") }.map { name ->
                PowerReadings.ThermalZone(files.read("/sys/class/thermal/$name/type"), files.read("/sys/class/thermal/$name/temp"))
            }
            val power = PowerReadings.linuxPower(supplies)
            DeviceHealth(power.percent, power.charging, heat.apply(PowerReadings.thermalForCelsius(PowerReadings.linuxCpuCelsius(zones))))
        }
        DesktopOs.Mac -> {
            val power = PowerReadings.macPower(commands.run(listOf("pmset", "-g", "batt"), emptyMap()))
            val thermal = PowerReadings.macThermal(commands.run(listOf("pmset", "-g", "therm"), emptyMap()))
            DeviceHealth(power.percent, power.charging, heat.apply(thermal))
        }
        DesktopOs.Windows -> {
            val script = "Get-CimInstance Win32_Battery | ForEach-Object { \"\$(\$_.EstimatedChargeRemaining),\$(\$_.BatteryStatus)\" }"
            val power = PowerReadings.windowsPower(commands.run(listOf("powershell", "-NoProfile", "-NonInteractive", "-Command", script), emptyMap()))
            DeviceHealth(power.percent, power.charging, DeviceHealth.Thermal.Unknown)
        }
        DesktopOs.Other -> DeviceHealth()
    }

    private companion object {
        const val TAG = "DesktopDeviceHealth"
        const val INTERVAL_MS = 10_000L
        const val MAX_STALE_MS = 60_000L
        const val WINDOWS_INTERVAL_MS = 30_000L
    }
}
