package com.tmplayer.data

import android.content.Context
import com.tmplayer.platform.Logger
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlin.time.TimeSource

/**
 * iOS gives the app a new folder when it is updated or installed again: the name of its data
 * container, the UUID in `.../Containers/Data/Application/<UUID>/`, changes in every path, while
 * the files inside it stay where they were. Android's records keep a finished download's whole
 * path ([ResumeRecord.localPath]), as they should on Android, where the app's folder never moves.
 * On iOS that path names the old folder after an update, and the download reads as missing: the
 * Downloads page shows it as gone, it does not play, and deleting it (by hand, "Remove after
 * watching", or with the downloads when signing out) forgets the record while the video stays on
 * the disk with nothing left that can name it, play it or delete it.
 *
 * So at launch, before any screen or job reads them, each record whose file was in an earlier
 * container and is in this one now is pointed at it ([repointDownloads]). Nothing else in a
 * record changes, and a record whose file is gone from both stays as it is, missing, as on Android.
 */
object MovedContainer {
    private const val TAG = "MovedContainer"
    private const val CONTAINERS = "/Containers/Data/Application/"

    /**
     * Home's and the chat listings' disk caches keep TDLib's file ids from the run that wrote them,
     * and TDLib's ids hold for one run only: TDLib numbers its files again at each start, as its
     * documentation says, and an id can then name another file. A thumbnail's old id named whole
     * videos, which TDLib then fetched for a card's picture: 258 MB and 1.1 GB on the iPhone 13 after
     * a reinstall (2026-11-12), and a 448 MB video for a card after a plain restart (2026-11-14),
     * the card black meanwhile. So the two are forgotten at every launch, before anything reads them,
     * and Home and the listings come from Telegram, whose own database on the device answers in
     * moments with the ids of this run.
     */
    fun forgetSavedFileIds(filesDir: File) {
        val forgotten = SAVED_FILE_IDS.count { File(filesDir, it).delete() }
        if (forgotten > 0) Logger.i(TAG, "$forgotten caches with an earlier run's TDLib file ids forgotten")
    }

    /** The files written by HomeCache and ChatGridDisk (App.kt), under the app's files folder. */
    private val SAVED_FILE_IDS = listOf("home/home-cache.json", "home/chat-grids.json")

    /** The app's data container now. */
    fun home(): String = platform.Foundation.NSHomeDirectory()

    /**
     * The same place as [path] inside [home], when [path] is inside another of the app's data
     * containers; null when it needs no move: it is in [home] already, or it is not inside a data
     * container at all (a picked folder's document, a path anywhere else, a [home] that is not a
     * container, as in a test).
     */
    fun moved(path: String, home: String = home()): String? {
        val (homePrefix, homeId) = container(home) ?: return null
        val at = path.indexOf(CONTAINERS).takeIf { it >= 0 } ?: return null
        val idStart = at + CONTAINERS.length
        val idEnd = path.indexOf('/', idStart).takeIf { it > idStart } ?: return null
        if (path.substring(idStart, idEnd) == homeId) return null
        return homePrefix + path.substring(idEnd)
    }

    // [home] up to the end of its container's name, and the name.
    private fun container(home: String): Pair<String, String>? {
        val at = home.indexOf(CONTAINERS).takeIf { it >= 0 } ?: return null
        val idStart = at + CONTAINERS.length
        val idEnd = home.indexOf('/', idStart).let { if (it < 0) home.length else it }
        if (idEnd <= idStart) return null
        return home.substring(0, idEnd) to home.substring(idStart, idEnd)
    }

    /**
     * Points every download record whose file was in an earlier container at the same file in
     * [home]: only when the old path is not a file any more and the new one is, so no record is
     * ever pointed at nothing or away from a file that is there.
     *
     * @return how many records were pointed at their file.
     */
    suspend fun repointDownloads(
        settings: SettingsStore,
        home: String = home(),
        isFile: (String) -> Boolean = { File(it).isFile },
    ): Int {
        var repointed = 0
        for (record in settings.downloadsNow()) {
            val path = record.localPath ?: continue
            if (StoredLocation.isDocument(path)) continue
            val here = moved(path, home) ?: continue
            if (isFile(path) || !isFile(here)) continue
            if (settings.setDownloadPath(record.chatId, record.messageId, here)) repointed++
        }
        return repointed
    }

    /**
     * [repointDownloads] at launch, before the app's jobs and screens start. Blocks: a record read
     * before it is right is a download shown as missing, or deleted as one. Records are read once,
     * and only those that moved are written, so a launch with nothing to move costs one read.
     */
    fun atLaunch(context: Context) {
        forgetSavedFileIds(context.filesDir)
        val started = TimeSource.Monotonic.markNow()
        try {
            val repointed = runBlocking { repointDownloads(SettingsStore(context)) }
            if (repointed > 0) {
                Logger.i(TAG, "$repointed downloads pointed at their files in the app's folder now, in ${started.elapsedNow().inWholeMilliseconds} ms")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Logger.w(TAG, "could not check where the downloads are", e)
        }
    }
}
