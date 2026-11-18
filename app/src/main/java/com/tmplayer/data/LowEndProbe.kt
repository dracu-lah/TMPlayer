package com.tmplayer.data

import android.app.ActivityManager
import android.content.Context

/**
 * This device read once into [LowEndDevice.isLowEnd]: the low RAM flag and the total memory,
 * which do not change while the app runs. False when the system will not say.
 */
object LowEndProbe {

    @Volatile
    private var answer: Boolean? = null

    fun isLowEnd(context: Context): Boolean = answer ?: runCatching {
        val manager = context.applicationContext.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val info = ActivityManager.MemoryInfo().also { manager?.getMemoryInfo(it) }
        LowEndDevice.isLowEnd(
            lowRamDevice = manager?.isLowRamDevice == true,
            totalMemoryBytes = info.totalMem.takeIf { it > 0 },
            tv = FormFactor.isTv(context),
        )
    }.getOrDefault(false).also { answer = it }
}
