package android.os

import platform.Foundation.NSProcessInfo
import platform.Foundation.NSProcessInfoThermalState
import platform.Foundation.isLowPowerModeEnabled
import platform.Foundation.thermalState
import platform.Foundation.thermalState

/** The power state, Android's PowerManager as iOS answers it: Low Power Mode is power save, the thermal state is the thermal status. */
class PowerManager internal constructor() {
    val isPowerSaveMode: Boolean get() = NSProcessInfo.processInfo.isLowPowerModeEnabled()

    /**
     * Android's THERMAL_STATUS levels from iOS's four: nominal is none (0), fair is light (1),
     * serious is severe (3), critical is emergency (5).
     */
    val currentThermalStatus: Int
        get() = when (NSProcessInfo.processInfo.thermalState()) {
            NSProcessInfoThermalState.NSProcessInfoThermalStateFair -> THERMAL_STATUS_LIGHT
            NSProcessInfoThermalState.NSProcessInfoThermalStateSerious -> THERMAL_STATUS_SEVERE
            NSProcessInfoThermalState.NSProcessInfoThermalStateCritical -> THERMAL_STATUS_EMERGENCY
            else -> THERMAL_STATUS_NONE
        }

    companion object {
        const val THERMAL_STATUS_NONE = 0
        const val THERMAL_STATUS_LIGHT = 1
        const val THERMAL_STATUS_MODERATE = 2
        const val THERMAL_STATUS_SEVERE = 3
        const val THERMAL_STATUS_CRITICAL = 4
        const val THERMAL_STATUS_EMERGENCY = 5
        const val THERMAL_STATUS_SHUTDOWN = 6

        internal val Shared = PowerManager()
    }
}
