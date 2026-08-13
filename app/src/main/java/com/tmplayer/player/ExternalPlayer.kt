package com.tmplayer.player

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.FileProvider
import com.tmplayer.data.LocalFileAvailability
import com.tmplayer.data.Td
import com.tmplayer.data.valueOrNull
import java.io.File

/**
 * Hands one Telegram video to whatever else on the device can play it.
 *
 * There are formats this app will never play, and the viewer may know another player that opens
 * them. Two things make the handover awkward, and both are handled here rather than at each call
 * site. A stream lives in TDLib's own directory inside this app's private storage, so a `file://`
 * path across a process boundary is a `FileUriExposedException`: the handover goes through the file
 * provider the manifest already declares, scoped to that directory, with a read grant that lasts as
 * long as the other app's task. And a video that is still downloading is a video that stops in the
 * middle, with nothing in the intent able to say so, so the readiness is worked out first and the
 * caller is given the sentence to put in front of the viewer.
 */
object ExternalPlayer {

    private const val TAG = "ExternalPlayer"

    /** The authority the manifest declares, scoped to TDLib's own media directory. */
    private const val AUTHORITY_SUFFIX = ".updates"

    /** What another app would actually get if it were handed this file right now. */
    enum class Readiness {
        /** Every byte is on disk. The handover is clean. */
        Complete,

        /** Something is on disk, but not all of it: playable, up to a point. */
        Partial,

        /** Nothing worth handing over. */
        Nothing,
    }

    /** How the handover ended, in the terms the calling screen has to say out loud. */
    sealed interface Handoff {
        /**
         * The chooser is up.
         *
         * [caution] is null for a complete file and a sentence for a partial one, so a caller that
         * asked without checking first still has something honest to show.
         */
        data class Started(val caution: String?) : Handoff

        /** Nothing on disk yet, so there is nothing another app could open. */
        data object NothingOnDisk : Handoff

        /** A file that exists but could not be handed out, or that nothing here would take. */
        data class Refused(val reason: String) : Handoff
    }

    /**
     * Readiness from the two facts TDLib has, kept separate from TDLib so its edges stay testable.
     *
     * [LocalFileAvailability.Partial] already means "a real file, but short", yet a download that
     * has only just been asked for reports the same thing with nothing in it: a chooser over zero
     * bytes is a crash in whatever app wins it, so that case is [Readiness.Nothing].
     */
    fun readiness(availability: LocalFileAvailability, downloadedBytes: Long): Readiness = when {
        availability == LocalFileAvailability.Complete -> Readiness.Complete
        availability == LocalFileAvailability.Missing -> Readiness.Nothing
        downloadedBytes <= 0 -> Readiness.Nothing
        else -> Readiness.Partial
    }

    /**
     * How much of the video is on disk, as a whole percent, or null when the size is not known.
     *
     * Clamped at 99 while bytes are still outstanding, so a nearly complete file is never reported
     * as fully downloaded a moment before it stops.
     */
    fun percentDownloaded(downloadedBytes: Long, totalBytes: Long): Int? {
        if (totalBytes <= 0 || downloadedBytes < 0) return null
        if (downloadedBytes >= totalBytes) return 100
        val percent = (downloadedBytes * 100 / totalBytes).toInt()
        return percent.coerceIn(0, 99)
    }

    /**
     * What to tell the viewer before the chooser opens, or null when there is nothing to warn about.
     *
     * A complete file says nothing, because there is nothing to say and a dialog over a working
     * handover is just a press in the way.
     */
    fun caution(
        readiness: Readiness,
        downloadedBytes: Long,
        totalBytes: Long,
    ): String? = when (readiness) {
        Readiness.Complete -> null
        Readiness.Nothing -> "This video isn't on the device yet, so another app has nothing to open."
        Readiness.Partial -> {
            val percent = percentDownloaded(downloadedBytes, totalBytes)
            if (percent != null) {
                "Only $percent% of this video has downloaded. Another player will stop there."
            } else {
                "This video is still downloading. Another player will stop where it runs out."
            }
        }
    }

    /**
     * The chooser for one file already on disk, or null when it cannot be shared.
     *
     * A chooser rather than a bare `ACTION_VIEW`, because `ACTION_VIEW` on a video lands in
     * whichever app once won the default, and on a device with TMPlayer installed that is very
     * often TMPlayer: the viewer asks to leave and arrives back where they started.
     */
    fun chooserFor(context: Context, path: String, title: String, mimeType: String): Intent? {
        val file = File(path)
        if (!file.isFile || file.length() <= 0) return null
        val uri = runCatching {
            FileProvider.getUriForFile(context, context.packageName + AUTHORITY_SUFFIX, file)
        }.onFailure { Log.w(TAG, "Cannot hand out $path", it) }.getOrNull() ?: return null

        val view = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, mimeType.ifBlank { "video/*" })
            .putExtra(Intent.EXTRA_TITLE, title)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        return Intent.createChooser(view, "Open with").apply {
            // The chooser may be started from a context that is not an activity, and the read
            // grant has to survive being handed on to whichever app is picked.
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    /**
     * The whole handover, for any screen that has a file id: ask TDLib where the file is, work out
     * how much of it there is, and put the chooser up.
     *
     * The single entry point, so no two screens drift apart on the authority, the flags or the
     * wording. It never cancels or deletes anything: a background download carries on while the
     * other app reads what has landed so far, and the file stays exactly where TDLib put it.
     */
    suspend fun handOver(
        context: Context,
        fileId: Int,
        title: String,
        mimeType: String = "video/*",
    ): Handoff {
        if (fileId <= 0) return Handoff.NothingOnDisk
        val session = Td.awaitAuthorizedSession()
        val file = session.client.getFile(fileId).valueOrNull ?: return Handoff.NothingOnDisk
        val availability = Td.localFileAvailability(fileId)
        val downloaded = file.local.downloadedSize
        val state = readiness(availability, downloaded)
        if (state == Readiness.Nothing) return Handoff.NothingOnDisk

        val path = file.local.path.takeIf { it.isNotBlank() } ?: return Handoff.NothingOnDisk
        val chooser = chooserFor(context, path, title, mimeType)
            ?: return Handoff.Refused("That video can't be handed to another app.")
        return runCatching {
            context.startActivity(chooser)
            Handoff.Started(caution(state, downloaded, maxOf(file.size, file.expectedSize)))
        }.getOrElse {
            Log.w(TAG, "Nothing took the video", it)
            Handoff.Refused("Nothing on this device opens that.")
        }
    }
}
