package com.tmplayer.cast

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

/**
 * Android 17's local network protection, kept behind the target SDK so nothing changes while the app
 * targets 35 (see "Local network permission" in docs/BUILDING.md, with the sources).
 *
 * Verified against developer.android.com/privacy-and-security/local-network-permission: from API 37
 * an app that TARGETS 37 or higher needs the runtime permission `ACCESS_LOCAL_NETWORK` (group
 * NEARBY_DEVICES) for any TCP connection and any UDP unicast, multicast or broadcast to or from the
 * LAN, which covers SSDP discovery, the description fetch, SOAP and the cast server. An app targeting
 * 36 or lower is granted it implicitly with INTERNET, and the docs say not to declare or request it
 * there. `NEARBY_WIFI_DEVICES` was only the Android 16 opt-in test switch and is deprecated.
 *
 * So before the target is raised to 37 the manifest needs `<uses-permission
 * android:name="android.permission.ACCESS_LOCAL_NETWORK" />` and this flow starts to ask; until then
 * [required] is false and [granted] is always true.
 */
object LocalNetworkPermission {
    const val NAME = "android.permission.ACCESS_LOCAL_NETWORK"

    /** The first Android version with the permission; also the first target SDK that needs it. */
    const val FIRST_SDK = 37

    /** This device and this build both ask for it: Android 17 or newer, and an app that targets it. */
    fun required(sdkInt: Int, targetSdk: Int): Boolean = sdkInt >= FIRST_SDK && targetSdk >= FIRST_SDK

    fun required(context: Context): Boolean = required(Build.VERSION.SDK_INT, context.applicationInfo.targetSdkVersion)

    /** True where the permission is not needed, or has been granted. */
    fun granted(context: Context): Boolean =
        !required(context) || ContextCompat.checkSelfPermission(context, NAME) == PackageManager.PERMISSION_GRANTED
}
