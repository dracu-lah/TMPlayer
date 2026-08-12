package com.tmplayer.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File

/**
 * Hands a downloaded video to whatever else is on the phone.
 *
 * The file provider and its scoped path have been in the manifest since downloads existed, with a
 * comment about the Share sheet and nothing anywhere that opened one: a viewer could fill a phone
 * with films for a journey and had no way to put one on somebody else's device, or to open one in
 * a player that reads a format this app cannot.
 *
 * Only complete files. A partial video is a video that stops in the middle, and neither the Share
 * sheet nor the app receiving it has any way to say so, which turns "here is the film" into a
 * complaint two hours away from here.
 */
object ShareMedia {

    /** The authority the manifest declares, scoped to TDLib's own media directory. */
    private const val AUTHORITY_SUFFIX = ".updates"

    /**
     * A share sheet for one or several videos, or null when none of them can be shared.
     *
     * Returns the intent rather than starting it, so the caller decides what to say when there is
     * nothing to send: a screen that knows which videos it asked about can name them.
     */
    fun intentFor(context: Context, files: List<Pair<String, String>>): Intent? {
        val uris = files.mapNotNull { (path, _) -> uriFor(context, path) }
        if (uris.isEmpty()) return null

        val share = if (uris.size == 1) {
            Intent(Intent.ACTION_SEND).apply {
                putExtra(Intent.EXTRA_STREAM, uris.first())
                putExtra(Intent.EXTRA_TITLE, files.first().second)
            }
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
            }
        }
        return Intent.createChooser(
            share.apply {
                // The generic video type rather than each file's own: a selection of an mkv and an
                // mp4 has no single accurate type, and naming one of them hides the apps that
                // handle the other.
                type = "video/*"
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            },
            if (uris.size == 1) "Share this video" else "Share ${uris.size} videos",
        ).apply {
            // The chooser itself is started from a context that may not be an activity, and the
            // read permission has to survive being handed to whichever app is picked.
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    /**
     * A content URI for one file, or null when it is not somewhere this app may share from.
     *
     * The provider only maps TDLib's media directory, so anything outside it throws rather than
     * returning null. That is the right answer and the wrong shape: a video that has been moved or
     * deleted between the list being drawn and the button being pressed should drop out of the
     * share, not take the app down with it.
     */
    private fun uriFor(context: Context, path: String): Uri? {
        val file = File(path)
        if (!file.isFile || file.length() <= 0) return null
        return runCatching {
            FileProvider.getUriForFile(context, context.packageName + AUTHORITY_SUFFIX, file)
        }.getOrNull()
    }
}
