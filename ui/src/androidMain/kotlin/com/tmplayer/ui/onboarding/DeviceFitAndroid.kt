package com.tmplayer.ui.onboarding

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.tmplayer.data.DeviceFit
import com.tmplayer.data.DeviceFitOverride
import com.tmplayer.data.DeviceFitRules
import com.tmplayer.data.DeviceProbe
import com.tmplayer.data.DeviceQuirks
import com.tmplayer.data.DiskInfo
import com.tmplayer.data.FormFactor

/** Reads the device into a [DeviceProbe]: memory, Android's low RAM flag, free space, API level, ABIs. */
object DeviceProbes {

    fun read(context: Context): DeviceProbe {
        val app = context.applicationContext
        val manager = app.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val info = ActivityManager.MemoryInfo().also { manager?.getMemoryInfo(it) }
        return DeviceProbe(
            totalMemoryBytes = info.totalMem.takeIf { it > 0 },
            lowRamDevice = manager?.isLowRamDevice == true,
            // Where TDLib keeps the videos (see DiskSpace).
            freeStorageBytes = DiskInfo.of(app.filesDir).freeBytes.takeIf { it > 0 },
            sdk = Build.VERSION.SDK_INT,
            only32Bit = Build.SUPPORTED_64_BIT_ABIS.isEmpty(),
            tv = FormFactor.isTv(app),
        )
    }

    /** The verdict for this device, or the one a fixture forced. */
    fun fit(context: Context): DeviceFit = DeviceFitOverride.forced ?: DeviceFitRules.assess(read(context))
}

/**
 * This device's verdict, read once per screen: free space moves, but not between two frames, and
 * the Settings card and the setup re-read it each time they open.
 */
@Composable
fun rememberDeviceFit(): DeviceFit {
    val context = LocalContext.current
    return remember(context) { DeviceProbes.fit(context) }
}

/** Thumbnails while seeking show at all only above [com.tmplayer.data.Trickplay]'s memory line. */
@Composable
fun rememberSeekThumbnailsShown(): Boolean {
    val context = LocalContext.current
    return remember(context) { com.tmplayer.data.Trickplay.PICTURES_SHOWN && DeviceQuirks.trickplayMemory(context) }
}

/** The phone and the television have the Lighter pictures and Reduce motion rows. */
val hasPictureSettings: Boolean = true

/**
 * Whether the appearance step offers Use the wallpaper's colours: Android 12 and later have a
 * wallpaper palette to read; earlier, the switch would change nothing. Only a phone asks it.
 */
internal val wallpaperColoursOffered: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
