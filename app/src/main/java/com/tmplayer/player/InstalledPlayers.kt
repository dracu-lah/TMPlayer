package com.tmplayer.player

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The video players installed on this device (PR4): every app that takes `ACTION_VIEW` for a
 * video, found with `queryIntentActivities`. The manifest's `<queries>` entry names the same two
 * intents, so Android 11 and later answer without `QUERY_ALL_PACKAGES`.
 *
 * Asked twice, because what an app takes decides how it is fed: a `content://` address is a
 * finished file handed over through the file provider, an `http://` address is a stream served
 * from [ExternalStreams] while the video is still coming down.
 */
object InstalledPlayers {

    private const val TAG = "InstalledPlayers"

    /** What the two probes ask about: a finished file as a content address, a stream as a loopback address. */
    internal const val FILE_ADDRESS = "content://media/external/video/media/1"
    internal const val STREAM_ADDRESS = "http://127.0.0.1/video.mp4"

    /** An app with the icon it shows in the chooser. [icon] is null when the system gave none. */
    class Found(val app: ExternalApp, val icon: Drawable?)

    /** The probes: any address of the kind, and a video type. They match the manifest's `<queries>`. */
    internal fun probe(address: String): Intent =
        Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(address), "video/*")

    /** The installed players, [ExternalPlay.order]ed, off the main thread. Never throws: no list is an empty one. */
    suspend fun find(context: Context): List<Found> = withContext(Dispatchers.Default) {
        runCatching { findNow(context) }.onFailure { Log.w(TAG, "Could not list the players", it) }.getOrDefault(emptyList())
    }

    private fun findNow(context: Context): List<Found> {
        val pm = context.packageManager
        val files = pm.resolve(probe(FILE_ADDRESS))
        val streams = pm.resolve(probe(STREAM_ADDRESS))
        val icons = HashMap<String, Drawable?>()
        val raw = ArrayList<ExternalApp>()
        fun add(info: ResolveInfo, file: Boolean, stream: Boolean) {
            val activity = info.activityInfo ?: return
            if (!activity.exported || !activity.enabled) return
            val label = runCatching { info.loadLabel(pm).toString() }.getOrDefault("")
            raw += ExternalApp(activity.packageName, activity.name, label, acceptsFile = file, acceptsStream = stream)
            if (activity.packageName !in icons) icons[activity.packageName] = runCatching { info.loadIcon(pm) }.getOrNull()
        }
        // An activity that takes both is merged by [ExternalPlay.order] into one line; the two
        // answers are marked here so that a file-only app is not offered a stream.
        val streamKeys = streams.mapNotNull { it.activityInfo?.let { a -> a.packageName to a.name } }.toSet()
        val fileKeys = files.mapNotNull { it.activityInfo?.let { a -> a.packageName to a.name } }.toSet()
        for (info in files) {
            val a = info.activityInfo ?: continue
            add(info, file = true, stream = (a.packageName to a.name) in streamKeys)
        }
        for (info in streams) {
            val a = info.activityInfo ?: continue
            if ((a.packageName to a.name) !in fileKeys) add(info, file = false, stream = true)
        }
        return ExternalPlay.order(raw, context.packageName).map { Found(it, icons[it.packageName]) }
    }

    private fun PackageManager.resolve(intent: Intent): List<ResolveInfo> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_ALL.toLong()))
        } else {
            @Suppress("DEPRECATION")
            queryIntentActivities(intent, PackageManager.MATCH_ALL)
        }
}
