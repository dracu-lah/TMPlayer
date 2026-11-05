package com.tmplayer.data

import android.app.UiModeManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.hardware.Sensor
import android.hardware.SensorManager
import android.os.BatteryManager

/**
 * Reads [FormSignals] from this device (HX78). Each read stands alone and a failed one is null,
 * so odd firmware that throws from a sensor or battery query only loses that one signal.
 */
object FormSignalsAndroid {

    fun read(context: Context): FormSignals {
        val app = context.applicationContext
        val pm = app.packageManager
        fun feature(name: String): Boolean? = runCatching { pm.hasSystemFeature(name) }.getOrNull()
        return FormSignals(
            leanback = feature(PackageManager.FEATURE_LEANBACK),
            uiModeTv = runCatching {
                (app.getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager)
                    ?.let { it.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION }
            }.getOrNull(),
            pc = feature(FEATURE_PC),
            chromebook = listOfNotNull(feature(FEATURE_ARC), feature(FEATURE_ARC_MANAGEMENT)).let { found ->
                if (found.isEmpty()) null else found.any { it }
            },
            watch = feature(PackageManager.FEATURE_WATCH),
            accelerometer = runCatching {
                (app.getSystemService(Context.SENSOR_SERVICE) as? SensorManager)
                    ?.let { it.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) != null }
            }.getOrNull(),
            touchInput = runCatching { touch(app.resources.configuration.touchscreen) }.getOrNull(),
            battery = runCatching { battery(app) }.getOrNull(),
        )
    }

    /** The touchscreen the window manager found among the attached input devices; null when it does not say. */
    private fun touch(value: Int): Boolean? = when (value) {
        Configuration.TOUCHSCREEN_NOTOUCH -> false
        Configuration.TOUCHSCREEN_UNDEFINED -> null
        else -> true
    }

    /**
     * The battery's sticky broadcast, read without a receiver. A protected system broadcast, so no
     * export flag is needed on Android 14. Null when there is no broadcast or it does not say.
     */
    private fun battery(app: Context): Boolean? {
        val sticky = app.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return null
        if (!sticky.hasExtra(BatteryManager.EXTRA_PRESENT)) return null
        return sticky.getBooleanExtra(BatteryManager.EXTRA_PRESENT, true)
    }

    /** `PackageManager.FEATURE_PC`, API 27; the string works on every version. */
    private const val FEATURE_PC = "android.hardware.type.pc"
    private const val FEATURE_ARC = "org.chromium.arc"
    private const val FEATURE_ARC_MANAGEMENT = "org.chromium.arc.device_management"
}
