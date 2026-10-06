package com.tmplayer.ui.browse

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.tmplayer.data.CacheShelf
import com.tmplayer.data.DiskSpace
import com.tmplayer.data.LocalDownloads
import com.tmplayer.data.MediaItem
import com.tmplayer.data.OfflineDownloads
import com.tmplayer.data.SettingsStore
import com.tmplayer.data.Td
import com.tmplayer.data.start
import com.tmplayer.i18n.L
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/*
 * Putting videos on the download queue, shared by every place that offers it: a chat's grid and its
 * multi-select, and the detail panel wherever it opens (a chat, Home, the all-chats search).
 */

/**
 * Queues [chosen], having first asked the disk whether they fit, and returns what to tell the
 * viewer, or null when there was nothing to queue.
 *
 * Every route in must come through this plan: calling [OfflineDownloads.start] directly skips the
 * watch cache eviction in [planDownloads], and the service's late check then refuses the video on
 * raw free space alone. A video whose chat restricts saving content is dropped here too.
 */
internal suspend fun queueDownloads(
    context: Context,
    settings: SettingsStore,
    ticked: List<MediaItem>,
    chatTitle: (MediaItem) -> String,
): String? {
    val chosen = ticked.filter { it.canBeSaved }
    if (chosen.isEmpty()) return null
    // Android 13 counts a download's progress notification as one the viewer has to have agreed
    // to. Asked here rather than at first launch, because here is the one moment the request
    // explains itself.
    askForNotifications(context)
    // Off the main thread for the whole of the decision: it walks every download record with a
    // TDLib round trip each, a statvfs() for the free space and a stat() per ticked video, which on
    // the drawing thread would freeze the listing for its whole length.
    val (taken, message) = withContext(Dispatchers.Default) {
        // Already in Downloads: nothing to do. Whole in the cache: straight to the queue, which
        // moves it into Downloads without fetching a byte, so the disk has no say.
        val inDownloads = LocalDownloads.presentIds(settings)
        val coming = OfflineDownloads.active.value
        val (wholeHere, toPlan) = chosen
            .filter { it.id !in inDownloads && !coming.containsKey(it.fileId) }
            .partition { runCatching { Td.isFileCached(it.fileId) }.getOrDefault(false) }
        val (planTaken, planMessage) = planDownloads(context, settings, toPlan)
        val all = wholeHere + planTaken
        all to when {
            all.isEmpty() && toPlan.isEmpty() && chosen.size == 1 -> L.gridAlreadyDownloadedOne
            all.isEmpty() && toPlan.isEmpty() -> L.gridAlreadyDownloadedMany
            toPlan.isEmpty() && all.size == 1 -> L.gridSavingOne
            toPlan.isEmpty() -> L.gridSavingMany(all.size)
            else -> planMessage
        }
    }
    taken.forEach { OfflineDownloads.start(context, it, chatTitle(it)) }
    return message
}

/**
 * The videos that need fetching, planned against the disk: which fit, and what the watch cache
 * gives up to make room. Returns the ones to queue and what to tell the viewer.
 */
private suspend fun planDownloads(
    context: Context,
    settings: SettingsStore,
    chosen: List<MediaItem>,
): Pair<List<MediaItem>, String> {
    if (chosen.isEmpty()) return emptyList<MediaItem>() to ""
    return run {
        // Only the watch cache is on offer here. A video left behind by a press of Play
        // should not be the reason a video somebody ticked is refused; the videos they
        // downloaded on purpose are not touched either way.
        val cachedRecords = runCatching { settings.cachedVideosNow() }
            .getOrDefault(emptyList())
        // Measured and later deleted through the id the message answers with now, not the
        // one saved with the record: a saved id from an earlier session measures as zero
        // and deletes nothing. See [WatchCache.evictAllBut].
        val owned = cachedRecords.mapNotNull { record ->
            val fileId = runCatching {
                Td.currentFileId(record.chatId, record.messageId, record.fileId)
            }.getOrDefault(record.fileId)
            val bytes = runCatching { Td.localDownloadedBytes(fileId) }
                .getOrDefault(0L)
            if (bytes <= 0) null else CacheShelf.Held(fileId, bytes, record.updatedAt) to record
        }
        val cached = owned.map { it.first }
        val owners = owned.associate { it.first.fileId to it.second }
        val coming = OfflineDownloads.active.value
        val candidates = chosen.map { item ->
            CacheShelf.Candidate(
                fileId = item.fileId,
                sizeBytes = item.sizeBytes,
                partialBytes = runCatching { Td.localDownloadedBytes(item.fileId) }
                    .getOrDefault(0L),
                alreadyHere = coming.containsKey(item.fileId) ||
                    runCatching { Td.isFileCached(item.fileId) }.getOrDefault(false),
            )
        }
        // The disk decides here, and nothing else. Ticking three videos is a viewer asking
        // for three videos, so all three go on the queue and the only thing that can turn
        // one away is there being no room for it.
        val batch = CacheShelf.planBatch(
            candidates = candidates,
            cached = cached,
            freeBytes = DiskSpace.read(context).freeBytes,
        )
        // Spent before the first byte is fetched, so the room the plan counted on is
        // actually there by the time the queue starts.
        if (batch.reclaimFileIds.isNotEmpty()) {
            for (fileId in batch.reclaimFileIds) {
                runCatching { Td.deleteFile(fileId) }
                val record = owners[fileId] ?: continue
                val left = runCatching { Td.localDownloadedBytes(fileId) }.getOrDefault(0L)
                if (left <= 0) {
                    runCatching { settings.forgetCachedVideo(record.chatId, record.messageId) }
                }
            }
        }
        batch.fits.map { chosen[it] } to batchMessage(batch, chosen.size)
    }
}

/**
 * What to say about a selection the device could not take whole.
 *
 * The count is quoted rather than hidden behind "some could not be downloaded", because a viewer
 * who ticked ten and got seven needs to know which number to act on. [wanted] is everything ticked,
 * including whatever was on the device already: those are subtracted here, so the sentence counts
 * the videos that actually needed fetching.
 */
private fun batchMessage(batch: CacheShelf.Batch, wanted: Int): String {
    val taken = batch.fits.size
    val needed = wanted - batch.alreadyHere.size
    // Room is the only thing that can refuse a tick, and by this point the watch cache has already
    // been handed over, so there is genuinely nothing left to give.
    val noRoom = L.gridNoRoom
    return when {
        needed == 0 && wanted == 1 -> L.gridAlreadyDownloadedOne
        needed == 0 -> L.gridAlreadyDownloadedMany
        taken == 0 -> L.gridNoneFit(room = noRoom)
        taken < needed -> L.gridSomeFit(taken = taken, needed = needed, room = noRoom)
        taken == 1 -> L.gridDownloadingOne
        // They are fetched one at a time, so what the viewer is told is what they will see on the
        // Downloads screen: one coming down and the rest waiting their turn.
        else -> L.gridQueuedMany(taken)
    }
}

/**
 * Asks for the notification permission, once, on the versions that have one.
 *
 * A refusal is not treated as a failure: the download still runs, it simply runs without a bar to
 * watch. Nothing here waits on the answer, because the fetch is the thing the viewer pressed.
 */
internal fun askForNotifications(context: Context) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
    if (
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
        PackageManager.PERMISSION_GRANTED
    ) {
        return
    }
    val activity = context.findActivity() ?: return
    ActivityCompat.requestPermissions(
        activity,
        arrayOf(Manifest.permission.POST_NOTIFICATIONS),
        NOTIFICATION_REQUEST,
    )
}

private fun Context.findActivity(): Activity? {
    var current: Context? = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return null
}

private const val NOTIFICATION_REQUEST = 7301

/**
 * Hands the downloaded file to another app, either to send or to play.
 *
 * Through a `FileProvider`, because TDLib keeps its files inside this app's own storage and a
 * `file://` path handed across a process boundary is a `FileUriExposedException` on every Android
 * this app runs on. The grant is read-only and lasts as long as the other app's task.
 */
internal suspend fun shareVideo(context: Context, item: MediaItem, send: Boolean) {
    val path = LocalDownloads.shareablePath(SettingsStore(context), item.chatId, item.messageId, item.fileId)
    if (path.isNullOrBlank()) {
        // Not a state the menu should be able to reach, since these two entries only appear for a
        // file already on the phone. Said out loud anyway: silence here is indistinguishable from
        // a press that missed.
        Log.w(SHARE_TAG, "No local file for ${item.fileId}")
        Toast.makeText(context, L.gridShareNotHere, Toast.LENGTH_SHORT).show()
        return
    }
    val uri = runCatching {
        FileProvider.getUriForFile(context, "${context.packageName}.updates", File(path))
    }.onFailure { Log.w(SHARE_TAG, "Cannot share $path", it) }.getOrNull() ?: run {
        Toast.makeText(context, L.gridShareFailed, Toast.LENGTH_SHORT)
            .show()
        return
    }

    val mime = item.mimeType.ifBlank { "video/*" }
    val intent = if (send) {
        Intent(Intent.ACTION_SEND)
            .setType(mime)
            .putExtra(Intent.EXTRA_STREAM, uri)
            .putExtra(Intent.EXTRA_TITLE, item.title)
    } else {
        Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime)
    }
    intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)

    // The chooser is deliberate on both: ACTION_VIEW without one lands in whatever app once won
    // the default, which for a video on most phones is this one, and that is a loop.
    val chooser = Intent.createChooser(intent, if (send) L.commonShare else L.gridOpenWith)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(chooser) }.onFailure {
        Log.w(SHARE_TAG, "No app took the video", it)
        Toast.makeText(context, L.gridNoAppOpens, Toast.LENGTH_SHORT).show()
    }
}

private const val SHARE_TAG = "ShareVideo"
