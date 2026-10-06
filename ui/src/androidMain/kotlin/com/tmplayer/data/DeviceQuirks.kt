package com.tmplayer.data

import android.app.ActivityManager
import android.content.Context
import android.os.Build

/**
 * The Android half of [FireOs] and [Trickplay]'s memory line: reads the device once and answers
 * with the shared, tested rules.
 */
object DeviceQuirks {

    @Volatile
    private var fire: FireOs.Kind? = null

    /** Which Fire OS device this is, if any. Cached: hardware does not change under a process. */
    fun fireOs(context: Context): FireOs.Kind = fire ?: detect(context).also { fire = it }

    /** Whether this is a Fire TV. */
    fun isFireTv(context: Context): Boolean = fireOs(context) == FireOs.Kind.Tv

    /** What the remote and the system around it change, for this device. */
    fun remote(context: Context): RemoteQuirks = RemoteQuirks.of(fireOs(context), FormFactor.isTv(context))

    /** Whether this device has the memory for scrub thumbnails at all, whatever the setting says. */
    fun trickplayMemory(context: Context): Boolean {
        val manager = context.applicationContext.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            ?: return false
        val info = ActivityManager.MemoryInfo()
        manager.getMemoryInfo(info)
        return Trickplay.memoryAllows(info.totalMem, manager.isLowRamDevice)
    }

    /**
     * Answers [fireOs] with [kind] from here on. For the screenshot fixture only, the same way
     * [FormFactor.override] is: nothing in a release build calls it.
     */
    fun override(kind: FireOs.Kind) {
        fire = kind
    }

    private fun detect(context: Context): FireOs.Kind {
        val pm = context.applicationContext.packageManager
        val features = if (pm.hasSystemFeature(FireOs.FIRE_TV_FEATURE)) setOf(FireOs.FIRE_TV_FEATURE) else emptySet()
        return FireOs.detect(Build.MANUFACTURER.orEmpty(), Build.MODEL.orEmpty(), features)
    }
}
