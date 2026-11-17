package com.tmplayer.data

import android.content.Context
import android.content.ServiceHost
import com.tmplayer.desktop.DesktopDownloadRunner
import com.tmplayer.platform.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import platform.UIKit.UIApplication
import platform.UIKit.UIBackgroundTaskIdentifier
import platform.UIKit.UIBackgroundTaskInvalid

/**
 * Downloads on iOS. :app's screens ask for them as on Android, with DownloadService intents
 * (AndroidDownloadRunner); here ServiceHost hands those to the desktop's in-process queue
 * (DesktopDownloadRunner, compiled for iOS as upstream wrote it), which fetches one video at a time
 * through TDLib and moves each finished file into the downloads folder.
 *
 * iOS gives an app put away about half a minute before it suspends it; [keepGoingBriefly] asks for
 * that time while something is fetching. What is unfinished then carries on when the app returns,
 * as TDLib picks its downloads up again.
 */
object IosDownloads {
    private const val TAG = "IosDownloads"
    private var runner: DesktopDownloadRunner? = null
    private var backgroundTask: UIBackgroundTaskIdentifier = UIBackgroundTaskInvalid

    // The service's intents, in the order they came: a download is checked against its message
    // first ([currentFileOf]), and a Cancel pressed meanwhile must not overtake it.
    private val actions = Channel<suspend () -> Unit>(Channel.UNLIMITED)

    fun install(context: Context) {
        if (runner != null) return
        val settings = SettingsStore(context)
        val made = DesktopDownloadRunner(
            settings = settings,
            downloadsDir = { AndroidPaths(context).downloadsDir },
            cacheDisk = { AndroidPaths(context).disk() },
            connectivity = NetworkMonitor,
        )
        made.notifier = AndroidTransferNotifier(context)
        runner = made
        com.tmplayer.App.backgroundScope.launch {
            for (action in actions) {
                try {
                    action()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    Logger.w(TAG, "a download action failed", e)
                }
            }
        }
        ServiceHost.register(DownloadService::class.simpleName.orEmpty()) { intent ->
            val fileId = intent.getIntExtra(DownloadService.EXTRA_FILE_ID, 0)
            val action: suspend () -> Unit = when (intent.action) {
                DownloadService.ACTION_DOWNLOAD -> {
                    val request = DownloadRequest.from(intent)
                    suspend { request?.let { made.download(currentFileOf(it)) } }
                }
                DownloadService.ACTION_RESUME -> suspend { OfflineDownloads.resume(made, fileId) }
                DownloadService.ACTION_CANCEL -> suspend { made.cancel(fileId) }
                DownloadService.ACTION_PAUSE -> suspend { made.pause(fileId) }
                else -> suspend { Logger.w(TAG, "unknown download action ${intent.action}") }
            }
            actions.trySend(action)
        }
        // What an earlier run left, once signed in: the unfinished part paused, finished files moved.
        com.tmplayer.App.backgroundScope.launch {
            Td.awaitAuthorizedSession()
            runCatching { currentQueue(settings) }.onFailure { Logger.w(TAG, "could not check the queue's files", it) }
            runCatching { made.restore() }.onFailure { Logger.w(TAG, "restore failed", it) }
        }
    }

    /**
     * [request] with the file its message has in this run of TDLib. A request can carry an id from
     * an earlier run: Home's rows are read from the disk at launch ([HomeCache]) and the queue is
     * restored from it, and after a restart the same number can name another file, a thumbnail or
     * another video. Upstream's queue asks Telegram again only when TDLib answers with an error, and
     * a stale id that names a whole file gives none: the file it names, a 13 KB picture on the
     * simulator, was moved into Downloads under the video's name. So the id is checked first, as
     * Android's player checks it before playing ([Td.fileIdFits]), and when it does not fit, the
     * message is asked for again and its copy of the requested size taken, or its video. When the
     * message cannot be reached the request goes on unchanged, as upstream sends it.
     */
    internal suspend fun currentFileOf(request: DownloadRequest): DownloadRequest {
        if (request.chatId == 0L || request.messageId == 0L) return request
        if (runCatching { Td.fileIdFits(request.fileId, request.sizeBytes) }.getOrDefault(true)) return request
        val fresh = (runCatching { Td.lookupMedia(request.chatId, request.messageId) }.getOrNull() as? MessageLookup.Found)?.item
        if (fresh == null) {
            Logger.w(TAG, "file ${request.fileId} does not fit its video and the message could not be asked for; queued as it came")
            return request
        }
        val copy = fresh.qualities.firstOrNull { it.sizeBytes == request.sizeBytes }
        val id = copy?.fileId ?: fresh.fileId
        val size = copy?.sizeBytes ?: fresh.sizeBytes
        if (id <= 0 || id == request.fileId) return request
        Logger.i(TAG, "file ${request.fileId} names another file in this run; the message's is $id")
        return request.copy(fileId = id, sizeBytes = size.takeIf { it > 0 } ?: request.sizeBytes)
    }

    /** The queue an earlier run left, its files checked by [currentFileOf] before it is restored. */
    private suspend fun currentQueue(settings: SettingsStore) {
        val queue = settings.downloadQueueNow()
        if (queue.isEmpty()) return
        val current = queue.map { currentFileOf(it) }
        if (current != queue) settings.saveDownloadQueue(current)
    }

    /** The app is being put away: keep fetching for the time iOS allows, if anything is fetching. */
    fun keepGoingBriefly() {
        if (backgroundTask != UIBackgroundTaskInvalid) return
        val fetching = OfflineDownloads.ordered.any { it.stage == OfflineDownloads.Stage.Running || it.stage == OfflineDownloads.Stage.Moving }
        if (!fetching) return
        backgroundTask = UIApplication.sharedApplication.beginBackgroundTaskWithName("downloads") { stopKeepingGoing() }
    }

    /** Back in front, or out of time: the background allowance is handed back. */
    fun stopKeepingGoing() {
        val task = backgroundTask
        if (task == UIBackgroundTaskInvalid) return
        backgroundTask = UIBackgroundTaskInvalid
        UIApplication.sharedApplication.endBackgroundTask(task)
    }
}
