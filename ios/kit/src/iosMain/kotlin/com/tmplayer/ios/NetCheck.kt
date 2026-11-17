package com.tmplayer.ios

import com.tmplayer.data.ChatRepository
import com.tmplayer.data.Td
import com.tmplayer.data.valueOrNull
import com.tmplayer.platform.Logger
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.TimeSource

/**
 * A developer's measurement on a real device, launched with `-netCheck YES` and read from the
 * app's console (`xcrun devicectl device process launch --console`): how TDLib's downloads behave
 * on iOS, for a card's picture and for a video. A picture's download is timed twice, by TDLib's
 * file updates (what the app waits on) and by asking for the file's state each half second, so a
 * picture that arrives without its update shows. A video is downloaded for 15 s and its speed
 * logged, then its partial copy is cancelled and deleted from the device's cache (Telegram keeps
 * the video). Nothing here runs without the launch argument.
 */
internal object NetCheck {
    private const val TAG = "NetCheck"

    fun startIfAsked() {
        if (!platform.Foundation.NSUserDefaults.standardUserDefaults.boolForKey("netCheck")) return
        com.tmplayer.App.backgroundScope.launch {
            runCatching { run() }.onFailure { Logger.w(TAG, "check failed", it) }
        }
    }

    private suspend fun run() {
        val session = Td.awaitAuthorizedSession()
        val td = session.client
        delay(3_000)
        val items = ChatRepository(td).recentMedia(limit = 30)
        Logger.i(TAG, "recent videos: ${items.size}")
        items.take(10).forEachIndexed { i, item ->
            Logger.i(TAG, "  #$i size=${item.sizeBytes / 1_000_000} MB thumb=${item.thumbnailFileId} file=${item.fileId}")
        }
        items.firstOrNull { it.thumbnailFileId > 0 }?.let { picture(td, it.thumbnailFileId) }
            ?: Logger.i(TAG, "no recent video has a Telegram picture")
        items.filter { it.sizeBytes > 50_000_000 }.maxByOrNull { it.sizeBytes }?.let { video(td, it.fileId) }
            ?: Logger.i(TAG, "no recent video over 50 MB to time")
    }

    private suspend fun picture(td: dev.g000sha256.tdl.TdlClient, fileId: Int) {
        val before = td.getFile(fileId).valueOrNull
        Logger.i(TAG, "picture $fileId before: done=${before?.local?.isDownloadingCompleted} size=${before?.size} path=${before?.local?.path?.isNotEmpty()}")
        if (before?.local?.isDownloadingCompleted == true) return
        val start = TimeSource.Monotonic.markNow()
        coroutineScope {
            val byUpdate = async(start = CoroutineStart.UNDISPATCHED) {
                withTimeoutOrNull(30_000) {
                    td.fileUpdates.filter { it.file.id == fileId && it.file.local.isDownloadingCompleted }.collect {
                        Logger.i(TAG, "picture done by update after ${start.elapsedNow().inWholeMilliseconds} ms")
                        throw kotlinx.coroutines.CancellationException("seen")
                    }
                }
            }
            val asked = td.downloadFile(fileId = fileId, priority = 1, offset = 0, limit = 0, synchronous = false)
            Logger.i(TAG, "picture download asked: ok=${asked.valueOrNull != null} done=${asked.valueOrNull?.local?.isDownloadingCompleted}")
            var polled = false
            while (start.elapsedNow().inWholeMilliseconds < 30_000 && !polled) {
                delay(500)
                val now = td.getFile(fileId).valueOrNull
                if (now?.local?.isDownloadingCompleted == true) {
                    polled = true
                    Logger.i(TAG, "picture done by polling after ${start.elapsedNow().inWholeMilliseconds} ms")
                }
            }
            if (!polled) Logger.i(TAG, "picture not done after 30 s by polling")
            runCatching { byUpdate.await() }
            Logger.i(TAG, "picture update listener finished after ${start.elapsedNow().inWholeMilliseconds} ms")
        }
    }

    private suspend fun video(td: dev.g000sha256.tdl.TdlClient, fileId: Int) {
        val before = td.getFile(fileId).valueOrNull
        val had = before?.local?.downloadedSize ?: 0L
        Logger.i(TAG, "video $fileId size=${before?.size} already=${had / 1_000_000} MB")
        val start = TimeSource.Monotonic.markNow()
        var updates = 0
        coroutineScope {
            val counter = launch(start = CoroutineStart.UNDISPATCHED) {
                td.fileUpdates.filter { it.file.id == fileId }.collect { updates++ }
            }
            td.downloadFile(fileId = fileId, priority = 32, offset = 0, limit = 0, synchronous = false)
            var last = had
            for (second in 1..15) {
                delay(1_000)
                val now = td.getFile(fileId).valueOrNull?.local?.downloadedSize ?: last
                Logger.i(TAG, "video t=${second}s got=${(now - had) / 1_000_000} MB rate=${(now - last) / 1_000} kB/s updates=$updates")
                last = now
            }
            counter.cancel()
            val total = last - had
            Logger.i(TAG, "video average ${total / 15_000} kB/s over 15 s (${total / 1_000_000} MB)")
        }
        runCatching { td.cancelDownloadFile(fileId, false) }
        if (had == 0L) runCatching { td.deleteFile(fileId) }
        Logger.i(TAG, "video check done, partial copy ${if (had == 0L) "deleted" else "kept (it was there before)"}")
    }
}
