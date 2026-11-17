package com.tmplayer.ui.online

import androidx.compose.runtime.Composable
import com.tmplayer.online.MetaTrailer
import platform.Foundation.NSURL
import platform.UIKit.UIApplication

/** A trailer opens where iOS sends its address: the YouTube app when installed, Safari otherwise. */
@Composable
internal fun rememberTrailerOpener(): (MetaTrailer) -> Boolean = ::open

private fun open(trailer: MetaTrailer): Boolean {
    val url = NSURL.URLWithString(trailer.watchUrl) ?: return false
    UIApplication.sharedApplication.openURL(url, options = emptyMap<Any?, Any>(), completionHandler = null)
    return true
}
