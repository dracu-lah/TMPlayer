package com.tmplayer.ui.online

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.tmplayer.online.MetaTrailer

/**
 * Opens a trailer in the YouTube app (`vnd.youtube:`), else whatever opens a YouTube link. False
 * when nothing on the device can, which on a television without YouTube is common: the caller
 * then shows the link as a QR code for a phone.
 */
@Composable
internal fun rememberTrailerOpener(): (MetaTrailer) -> Boolean {
    val context = LocalContext.current
    return remember(context) {
        { trailer: MetaTrailer ->
            fun open(uri: String): Boolean = try {
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(uri)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                true
            } catch (e: ActivityNotFoundException) {
                false
            } catch (e: SecurityException) {
                false
            }
            open(trailer.appUri) || open(trailer.watchUrl)
        }
    }
}
