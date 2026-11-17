package com.tmplayer.ui.onboarding

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.IosAppContext
import com.tmplayer.data.DeviceFit
import com.tmplayer.data.DeviceFitOverride
import com.tmplayer.data.DeviceFitRules
import com.tmplayer.data.DeviceProbe
import com.tmplayer.data.DiskInfo
import platform.Foundation.NSProcessInfo

/**
 * Reads the device into a [DeviceProbe] as Android's DeviceProbes does: the memory the system
 * reports and the free space where the app keeps its videos. iOS has no low RAM flag, no API level
 * in Android's sense and no 32-bit devices left, and an iPhone or iPad is never a television.
 */
object DeviceProbes {
    fun read(context: android.content.Context): DeviceProbe = DeviceProbe(
        totalMemoryBytes = NSProcessInfo.processInfo.physicalMemory.toLong().takeIf { it > 0 },
        lowRamDevice = false,
        freeStorageBytes = DiskInfo.of(context.applicationContext.filesDir).freeBytes.takeIf { it > 0 },
        sdk = null,
        only32Bit = false,
        tv = false,
    )

    /** The verdict for this device, or the one a fixture forced. */
    fun fit(context: android.content.Context): DeviceFit = DeviceFitOverride.forced ?: DeviceFitRules.assess(read(context))

    fun fit(): DeviceFit = fit(IosAppContext)
}

/** This device's verdict, read once per screen, as on Android. */
@Composable
fun rememberDeviceFit(): DeviceFit = remember { DeviceProbes.fit() }

/**
 * Off until the player reads frames for the thumbnails on iOS (docs/IOS.md, layer 4
 * step 7); then it follows Android's memory line, [com.tmplayer.data.Trickplay.memoryAllows].
 */
@Composable
fun rememberSeekThumbnailsShown(): Boolean = false

/** The phone has the Lighter pictures and Reduce motion rows, as on Android. */
val hasPictureSettings: Boolean = true

/** iOS gives apps no wallpaper palette, so the appearance step never asks (as Android before 12). */
internal val wallpaperColoursOffered: Boolean get() = false
