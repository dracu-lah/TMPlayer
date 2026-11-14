package com.tmplayer.player

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.media3.common.util.UnstableApi
import com.tmplayer.data.AndroidPaths
import com.tmplayer.data.DiagnosticFiles
import com.tmplayer.data.LocalDownloads
import com.tmplayer.data.LocalVideo
import com.tmplayer.data.MediaItem
import com.tmplayer.data.OfflineDownloads
import com.tmplayer.data.SettingsStore
import com.tmplayer.data.Td
import com.tmplayer.data.valueOrNull
import com.tmplayer.platform.Logger
import dev.g000sha256.tdl.TdlClient
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * What "Playback test" asks of the device ([PlaybackTestSession.Env]), on Android.
 *
 * The video: a download first (whole on the disk, played as it is), else the one watched last, whose
 * first 30 MB are fetched with a ranged TDLib download. The fetch is the test's own: after the run, a
 * cancel or a failure the file is deleted again unless something was already on the disk before it
 * (the watch cache, a download in progress), which is left as it was.
 *
 * Nothing is written to the playback trace, the watch history or the resume points.
 */
@UnstableApi
class AndroidPlaybackTestEnv(
    context: Context,
    private val settings: SettingsStore = SettingsStore(context),
    private val runner: PlaybackTestRunner,
) : PlaybackTestSession.Env {

    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())

    /** The candidate's own pieces: where a download is, or the TDLib file to fetch. */
    private class Source(val local: LocalVideo?, val media: MediaItem?)

    private val sources = HashMap<String, Source>()

    /** How much of the file was on the disk before the fetch, per candidate id: nothing, so the fetch is ours to delete. */
    private val priorBytes = HashMap<String, Long>()

    override fun anotherVideoPlaying(): Boolean = PlaybackRecorder.playing.get() > 0 || PlaybackService.running

    override suspend fun pick(): PlaybackTestSession.Candidate? = withContext(Dispatchers.IO) {
        sources.clear()
        val downloads = runCatching { settings.downloadsNow() }.getOrDefault(emptyList())
        for (record in downloads.sortedByDescending { it.updatedAt }) {
            val video = LocalDownloads.presentVideo(record) ?: continue
            val id = "${record.chatId}:${record.messageId}"
            sources[id] = Source(video, null)
            return@withContext PlaybackTestSession.Candidate(id, record.title, record.sizeBytes, downloaded = true)
        }
        val watched = runCatching { settings.continueWatching.first() }.getOrDefault(emptyList())
        for (record in watched.take(MAX_TRIES)) {
            // The stored file id is only good for the run that issued it: ask Telegram for the message again.
            val media = runCatching { Td.refreshMedia(record.chatId, record.messageId) }.getOrNull() ?: continue
            if (media.sizeBytes <= 0L || OfflineDownloads.isDownloading(media.fileId)) continue
            val id = "${media.chatId}:${media.messageId}"
            sources[id] = Source(null, media)
            return@withContext PlaybackTestSession.Candidate(id, media.title.ifBlank { record.title }, media.sizeBytes, downloaded = false)
        }
        null
    }

    override suspend fun freeBytes(): Long = withContext(Dispatchers.IO) { runCatching { AndroidPaths(app).disk().freeBytes }.getOrDefault(0L) }

    override suspend fun onDiskPrefix(c: PlaybackTestSession.Candidate): Long {
        val media = sources[c.id]?.media ?: return 0L
        val client = client() ?: return 0L
        return prefix(client, media.fileId)
    }

    override suspend fun fetch(c: PlaybackTestSession.Candidate, progress: (Long) -> Unit): Boolean {
        val media = sources[c.id]?.media ?: return false
        val client = client() ?: return false
        val wanted = PlaybackTest.windowBytes(media.sizeBytes)
        priorBytes[c.id] = client.getFile(media.fileId).valueOrNull?.local?.downloadedSize ?: 0L
        try {
            client.downloadFile(media.fileId, PRIORITY, 0, wanted, false)
            val finished = withTimeoutOrNull(FETCH_TIMEOUT_MS) { waitForWindow(client, media.fileId, wanted, progress) }
            return finished == true
        } finally {
            // Whatever ended it, the download itself stops: the window is on the disk or it is not coming.
            withContext(NonCancellable) { runCatching { client.cancelDownloadFile(media.fileId, onlyIfPending = false) } }
        }
    }

    /** Polls until [wanted] bytes from the start are on the disk (true), or nothing new came for [NO_PROGRESS_MS] (false). */
    private suspend fun waitForWindow(client: TdlClient, fileId: Int, wanted: Long, progress: (Long) -> Unit): Boolean {
        var best = 0L
        var bestAt = System.currentTimeMillis()
        while (true) {
            val have = prefix(client, fileId)
            progress(have)
            if (have >= wanted) return true
            if (have > best) {
                best = have
                bestAt = System.currentTimeMillis()
            } else if (System.currentTimeMillis() - bestAt > NO_PROGRESS_MS) {
                return false
            }
            delay(POLL_MS)
        }
    }

    override suspend fun discardFetched(c: PlaybackTestSession.Candidate) {
        val media = sources[c.id]?.media ?: return
        val client = client() ?: return
        runCatching { client.cancelDownloadFile(media.fileId, onlyIfPending = false) }
        // Only what the test brought: a file that had bytes already (the watch cache) is not ours to delete.
        if ((priorBytes.remove(c.id) ?: 0L) == 0L && !OfflineDownloads.isDownloading(media.fileId)) {
            runCatching { client.deleteFile(media.fileId) }
        }
    }

    override suspend fun play(c: PlaybackTestSession.Candidate, progress: (Long) -> Unit): PlaybackTest.Result {
        val source = sources[c.id]
        val uri = uriFor(source) ?: return PlaybackTest.Result(PlaybackTest.Verdict.COULD_NOT_PLAY, 0, 0, 0L, "", true, "")
        return withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { cont ->
                runner.start(uri, progress) { result -> if (cont.isActive) cont.resume(result) }
                cont.invokeOnCancellation { main.post { runner.stop() } }
            }
        }
    }

    override suspend fun keepForReport(lines: List<String>): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            File(app.filesDir, DiagnosticFiles.PLAYBACK_TEST).writeText(lines.joinToString("\n") + "\n")
        }.onFailure { Logger.w(TAG, "Playback test result not kept", it) }.isSuccess
    }

    private suspend fun uriFor(source: Source?): Uri? {
        source ?: return null
        source.local?.let { return it.uri() }
        val media = source.media ?: return null
        val client = client() ?: return null
        val path = client.getFile(media.fileId).valueOrNull?.local?.path?.takeIf { it.isNotBlank() } ?: return null
        return Uri.fromFile(File(path))
    }

    private suspend fun client(): TdlClient? = Td.awaitConnectedSessionOrNull(CONNECT_TIMEOUT_MS)?.client

    private suspend fun prefix(client: TdlClient, fileId: Int): Long = client.getFileDownloadedPrefixSize(fileId, 0).valueOrNull?.size ?: 0L

    private companion object {
        const val TAG = "PlaybackTest"
        const val PRIORITY = 24
        const val MAX_TRIES = 5
        const val POLL_MS = 500L
        const val CONNECT_TIMEOUT_MS = 8_000L
        const val FETCH_TIMEOUT_MS = 5 * 60_000L
        const val NO_PROGRESS_MS = 30_000L
    }
}
