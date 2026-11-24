package com.tmplayer.desktop.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.tmplayer.data.VersionInfo
import com.tmplayer.desktop.BuildInfo

/**
 * This build's version, installer version and system for the version line and the support text
 * (WP-104). The version is as built: the packaged release's, or `1.0.0-dev` from a source run.
 * Nothing here reads an account.
 */
internal object DesktopVersion {
    fun info(): VersionInfo {
        val os = listOf(System.getProperty("os.name"), System.getProperty("os.version"))
            .filter { !it.isNullOrBlank() }
            .joinToString(" ")
        return VersionInfo(
            version = BuildInfo.VERSION,
            build = BuildInfo.INSTALLER_VERSION,
            device = listOf(os, System.getProperty("os.arch")).filter { !it.isNullOrBlank() }.joinToString(", "),
        )
    }
}

@Composable
internal fun rememberDesktopVersion(): VersionInfo = remember { DesktopVersion.info() }
