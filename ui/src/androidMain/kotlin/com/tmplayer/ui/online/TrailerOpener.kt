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
    return remember(context) { { trailer: MetaTrailer -> openTrailerOutside(context, trailer) } }
}

/**
 * The external open behind [rememberTrailerOpener], for code outside composition: the app's own
 * trailer player falls back to it when a trailer it restored after a rotation cannot play there.
 * False when nothing on the device opens YouTube links.
 */
fun openTrailerOutside(context: android.content.Context, trailer: MetaTrailer): Boolean {
    fun open(uri: String): Boolean = try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(uri)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (e: ActivityNotFoundException) {
        false
    } catch (e: SecurityException) {
        false
    }
    return openInYouTubeApp(context, trailer) || open(trailer.watchUrl)
}

/**
 * The YouTube app before anything else, on a television and on a phone: its own client plays
 * every trailer, which YouTube's embedded player in a WebView does not on many TVs (a press that
 * seemed to do nothing) nor on some phones (sound over a black screen on a POCO F5 with WebView
 * 157, 2026-10-22). Only the `vnd.youtube:` link, which only a YouTube app answers. Used only
 * where no platform player is provided through [LocalTrailerOpener]; the Android app provides
 * one (app/.../ui/trailer), which comes first with the setting on.
 */
@Composable
internal fun rememberTrailerAppFirst(): ((MetaTrailer) -> Boolean)? {
    val context = LocalContext.current
    return remember(context) {
        { trailer: MetaTrailer -> openInYouTubeApp(context, trailer) }
    }
}

/**
 * The trailer in whichever YouTube app answers `vnd.youtube:` links. Google's own apps take the
 * video id in that link; a third party client set as the default for them does not always:
 * SmartTube on a Mi TV opened its home screen and left the trailer unplayed, which looked like a
 * crash (user, 2026-10-23), while it plays the same video from its `https` watch link. So any app
 * but Google's is handed the watch link, sent to that app alone, so it never falls to a browser.
 */
private fun openInYouTubeApp(context: android.content.Context, trailer: MetaTrailer): Boolean {
    val app = Intent(Intent.ACTION_VIEW, Uri.parse(trailer.appUri))
    val handler = runCatching {
        context.packageManager.resolveActivity(app, android.content.pm.PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName
    }.getOrNull() ?: return false
    // The chooser itself answers when several apps can and none is the default: let it ask.
    val intent = when {
        handler == "android" || handler in GOOGLE_YOUTUBE -> app
        else -> Intent(Intent.ACTION_VIEW, Uri.parse(trailer.watchUrl)).setPackage(handler)
    }
    return try {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (e: ActivityNotFoundException) {
        // The client does not take watch links after all: its own link, as before.
        runCatching { context.startActivity(app.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess
    } catch (e: SecurityException) {
        false
    }
}

/** Google's YouTube apps, which open the video named in a `vnd.youtube:` link. */
private val GOOGLE_YOUTUBE = setOf(
    "com.google.android.youtube",
    "com.google.android.youtube.tv",
    "com.google.android.youtube.googletv",
    "com.amazon.firetv.youtube",
)
