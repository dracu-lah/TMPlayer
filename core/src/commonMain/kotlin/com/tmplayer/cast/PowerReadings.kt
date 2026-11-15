package com.tmplayer.cast

import java.util.Locale

/**
 * What a computer's OS says about its battery and heat, as text, turned into [DeviceHealth] for the
 * transcode's guard ([TranscodeGuard]: stop under 15 % off the charger, or when too hot). Pure and
 * given the text the OS gives, so each platform's reading is tested without that OS; the desktop reads the files
 * and runs the commands.
 *
 * What a platform does not say stays unknown, never a guess: a desktop with no battery, a battery
 * whose plug state is not told, a computer with no CPU temperature. Unknown never stops a transcode.
 *
 * - Linux: the folders of `/sys/class/power_supply` for the battery and the charger and `/sys/class/thermal` (the thermal zones) for the CPU.
 * - macOS: `pmset -g batt` and `pmset -g therm`.
 * - Windows: the battery from `Win32_Battery` (PowerShell). Windows exposes no CPU temperature to a
 *   normal user (the ACPI zones need administrator rights and are not the CPU's), so heat is unknown there.
 */
object PowerReadings {

    /** The battery's charge and whether a charger is in, each null when not told. */
    data class Power(val percent: Int?, val charging: Boolean?) {
        companion object {
            val UNKNOWN = Power(null, null)
        }
    }

    // ---- Linux ----------------------------------------------------------------------------------

    /** One folder of `/sys/class/power_supply`: the text of its `type`, `scope`, `status`, `capacity` and `online` files (null when missing). */
    class Supply(val type: String?, val scope: String?, val status: String?, val capacity: String?, val online: String?)

    private val CHARGER_TYPES = setOf("mains", "usb", "usb_c", "usb_pd", "usb_dcp", "usb_cdp", "usb_aca", "usb_pd_drp", "wireless")

    /**
     * The batteries of the computer itself (a wireless mouse's is `scope=Device`) averaged, and whether a charger is in:
     * a charger `online`, or a battery that is charging, full or "not charging" (held at a charge limit on a charger), is plugged;
     * a battery discharging with no charger online is not. A battery whose state is not told and no charger: the charge is
     * not reported either, since "low and not plugged" cannot be told from "low and plugged" and a wrong stop is worse than none.
     */
    fun linuxPower(supplies: List<Supply>): Power {
        val batteries = supplies.filter { it.type.clean() == "battery" && it.scope.clean() != "device" }
        val percents = batteries.mapNotNull { it.capacity?.trim()?.toIntOrNull()?.takeIf { p -> p in 0..100 } }
        if (percents.isEmpty()) return Power.UNKNOWN
        val chargerIn = supplies.any { it.type.clean() in CHARGER_TYPES && it.online?.trim() == "1" }
        val states = batteries.map { it.status.clean() }
        val plugged = when {
            chargerIn || states.any { it == "charging" || it == "full" || it == "not charging" } -> true
            states.any { it == "discharging" } -> false
            else -> null
        } ?: return Power.UNKNOWN
        return Power(percents.sum() / percents.size, plugged)
    }

    private fun String?.clean(): String = this?.take(64)?.trim()?.lowercase(Locale.ROOT).orEmpty()

    /** One `thermal_zone*` folder: the text of its `type` and `temp` files (millidegrees Celsius). */
    class ThermalZone(val type: String?, val temp: String?)

    private val CPU_ZONES = setOf("x86_pkg_temp", "cpu-thermal", "cpu_thermal", "soc_thermal", "coretemp", "k10temp", "tcpu", "cpu")

    /** The hottest CPU zone, in degrees; null when there is none or none reads sensibly (a sentinel like -274000). */
    fun linuxCpuCelsius(zones: List<ThermalZone>): Double? =
        zones.filter { it.type.clean() in CPU_ZONES }
            .mapNotNull { it.temp?.trim()?.toLongOrNull()?.let { milli -> milli / 1000.0 } }
            .filter { it in 1.0..149.0 }
            .maxOrNull()

    /** [DeviceHealth.Thermal] for a CPU at [celsius]: Severe from 95, where the chip is about to throttle itself. Null: unknown. */
    fun thermalForCelsius(celsius: Double?): DeviceHealth.Thermal = when {
        celsius == null || !celsius.isFinite() -> DeviceHealth.Thermal.Unknown
        celsius >= SEVERE_CELSIUS -> DeviceHealth.Thermal.Severe
        celsius >= 90.0 -> DeviceHealth.Thermal.Moderate
        celsius >= 80.0 -> DeviceHealth.Thermal.Light
        else -> DeviceHealth.Thermal.None
    }

    const val SEVERE_CELSIUS = 95.0

    // ---- macOS ----------------------------------------------------------------------------------

    private val PERCENT = Regex("(\\d{1,3})%")

    /** `pmset -g batt`: "Now drawing from 'AC Power'" or "'Battery Power'", and the internal battery's percentage. No internal battery: unknown. */
    fun macPower(output: String?): Power {
        val text = output?.take(MAX_TEXT) ?: return Power.UNKNOWN
        val line = text.lineSequence().firstOrNull { "InternalBattery" in it } ?: return Power.UNKNOWN
        val percent = PERCENT.find(line)?.groupValues?.get(1)?.toIntOrNull()?.takeIf { it in 0..100 } ?: return Power.UNKNOWN
        val plugged = when {
            "'AC Power'" in text -> true
            "'Battery Power'" in text -> false
            else -> return Power.UNKNOWN
        }
        return Power(percent, plugged)
    }

    private val SPEED_LIMIT = Regex("CPU_Speed_Limit\\s*=\\s*(\\d{1,3})")

    /** `pmset -g therm`: the CPU speed limit the OS has set for heat (100 is none); "no thermal warning" says none; anything else is unknown. */
    fun macThermal(output: String?): DeviceHealth.Thermal {
        val text = output?.take(MAX_TEXT) ?: return DeviceHealth.Thermal.Unknown
        val limit = SPEED_LIMIT.find(text)?.groupValues?.get(1)?.toIntOrNull()
        return when {
            limit != null && limit <= 50 -> DeviceHealth.Thermal.Severe
            limit != null && limit < 100 -> DeviceHealth.Thermal.Moderate
            limit != null -> DeviceHealth.Thermal.None
            "No thermal warning level has been recorded" in text -> DeviceHealth.Thermal.None
            else -> DeviceHealth.Thermal.Unknown
        }
    }

    // ---- Windows --------------------------------------------------------------------------------

    /** Win32_Battery's BatteryStatus: on a charger (2 on AC, 3 full, 6 to 9 charging, 11 partly charged) or running on the battery (1, 4, 5). */
    private val ON_CHARGER = setOf(2, 3, 6, 7, 8, 9, 11)
    private val ON_BATTERY = setOf(1, 4, 5)

    /** Lines of `EstimatedChargeRemaining,BatteryStatus`, one per battery. A computer with none prints nothing. */
    fun windowsPower(output: String?): Power {
        val rows = output?.take(MAX_TEXT)?.lineSequence()?.take(8)?.mapNotNull { line ->
            val parts = line.trim().split(',')
            val percent = parts.getOrNull(0)?.trim()?.toIntOrNull()?.takeIf { it in 0..100 }
            val status = parts.getOrNull(1)?.trim()?.toIntOrNull()
            if (percent == null || status == null) null else percent to status
        }?.toList().orEmpty()
        if (rows.isEmpty()) return Power.UNKNOWN
        val plugged = when {
            rows.any { it.second in ON_CHARGER } -> true
            rows.all { it.second in ON_BATTERY } -> false
            else -> return Power.UNKNOWN
        }
        return Power(rows.sumOf { it.first } / rows.size, plugged)
    }

    private const val MAX_TEXT = 16_384
}

/**
 * A CPU touching its limit for one reading is a spike, not a reason to stop a cast: Severe is only
 * passed on after [needed] readings in a row at Severe or worse, and shows as Moderate until then.
 */
class HeatDebounce(private val needed: Int = 3) {
    private var streak = 0

    @Synchronized
    fun reset() {
        streak = 0
    }

    @Synchronized
    fun apply(thermal: DeviceHealth.Thermal): DeviceHealth.Thermal {
        if (thermal < DeviceHealth.Thermal.Severe) {
            streak = 0
            return thermal
        }
        streak++
        return if (streak >= needed) thermal else DeviceHealth.Thermal.Moderate
    }
}
