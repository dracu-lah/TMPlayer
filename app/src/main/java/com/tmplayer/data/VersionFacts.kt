package com.tmplayer.data

import android.content.Context
import android.os.Build
import com.tmplayer.BuildConfig

/**
 * This build's version, build number and device, for the version line and the support text
 * (WP-104). Android's; the iOS build has its own with the same name in `AndroidSystemOnIos.kt`.
 * Nothing here reads an account.
 */
object VersionFacts {
    fun info(context: Context): VersionInfo = VersionInfo(
        // As built: a pre-release keeps `-beta.N`, the preview build its `-test`.
        version = BuildConfig.VERSION_NAME,
        build = BuildConfig.VERSION_CODE.toString(),
        device = line(context),
    )

    /** "Android 14, Google Pixel 8, phone": the system, the maker and model, and the kind of screen. */
    fun line(context: Context): String {
        val kind = runCatching {
            when {
                FormFactor.isTv(context) -> "TV"
                context.resources.configuration.smallestScreenWidthDp >= 600 -> "tablet"
                else -> "phone"
            }
        }.getOrDefault("phone")
        val device = listOfNotNull(Build.MANUFACTURER, Build.MODEL).filter { it.isNotBlank() }.joinToString(" ")
        return listOf("Android ${Build.VERSION.RELEASE ?: ""}".trim(), device, kind).filter { it.isNotBlank() }.joinToString(", ")
    }
}
