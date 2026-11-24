package com.tmplayer.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.tmplayer.data.VersionFacts
import com.tmplayer.data.VersionInfo

/** This build's version facts (WP-104), read once per composition of the caller. */
@Composable
fun rememberVersionInfo(): VersionInfo {
    val context = LocalContext.current
    return remember(context) { VersionFacts.info(context) }
}
