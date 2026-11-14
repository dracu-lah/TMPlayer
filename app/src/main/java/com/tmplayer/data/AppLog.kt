package com.tmplayer.data

import android.content.Context
import com.tmplayer.platform.FileLogSink
import com.tmplayer.platform.LogSink
import com.tmplayer.platform.Logger
import com.tmplayer.platform.PlainLogFiles
import com.tmplayer.platform.RotatingLog
import com.tmplayer.player.TraceCadence
import java.io.File
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Executor
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlin.concurrent.Volatile

/**
 * The app's persistent log on Android (reliability plan L4): the lines the shared code logs through
 * [Logger], kept in two small files ([RotatingLog], about 2 MB each) that outlive logcat's few
 * minutes and are cleared with sign-out ([forget]).
 *
 * The file holds names, so it only ever leaves the device through the export that scrubs it
 * (`LogExport`, `LogBundle`), never on its own.
 */
object AppLog {

    private const val DIR = "logs"

    @Volatile
    private var log: RotatingLog? = null

    @Volatile
    private var detailed = false

    /** The log's two files in [context]'s own storage; made on first use, which costs no disk work. */
    fun get(context: Context): RotatingLog = log ?: synchronized(this) {
        log ?: RotatingLog(PlainLogFiles(folder(context))).also { log = it }
    }

    fun folder(context: Context): File = File(context.applicationContext.filesDir, DIR)

    /** Sign-out and "Delete your data": the files go, and a line written after starts a fresh one. */
    fun forget(context: Context) {
        runCatching { get(context).clear() }
    }

    /**
     * Puts the file sink in front of [next] (logcat). [choice] is the viewer's own setting (null
     * when they made none), read in the background; until it arrives, and while it is null, the
     * build's default holds ([AppLogPolicy.defaultDetailed]).
     */
    fun install(context: Context, next: LogSink, buildType: String, versionName: String, choice: Flow<Boolean?>, scope: CoroutineScope) {
        val app = context.applicationContext
        val default = AppLogPolicy.defaultDetailed(buildType, versionName)
        detailed = default
        Logger.sink = AppLogSink(next, AsyncLineWriter.threaded(), { detailed }) { get(app) }
        scope.launch { choice.collect { detailed = it ?: default } }
    }
}

/** What the app log keeps, decided apart from the sink so a test can pin it. */
object AppLogPolicy {

    /** Info lines the stable summary keeps: where the layout came from and the safe-mode notice. */
    val SUMMARY_TAGS = setOf("tm-form", "tm-safe")

    /** On in a beta, preview or debug build, off in a stable one. */
    fun defaultDetailed(buildType: String, versionName: String): Boolean = TraceCadence.isTesterBuild(buildType, versionName)

    /** Warnings are always kept; info lines when [detailed], or the few the summary names. */
    fun keepsInfo(tag: String, detailed: Boolean): Boolean = detailed || tag in SUMMARY_TAGS
}

/**
 * Runs the writes of [AppLogSink] on one low priority thread, started on the first line. A queue
 * that is full (a disk that is slow or full) drops lines rather than make the caller wait.
 */
class AsyncLineWriter(private val executor: Executor) {

    fun submit(job: () -> Unit) {
        try {
            executor.execute { runCatching(job) }
        } catch (e: Throwable) {
            // Rejected: the queue is full or the pool is gone. The line is lost, the caller is not held.
        }
    }

    companion object {
        const val QUEUE = 2_000

        /** One daemon thread, made when the first line arrives, and a bounded queue that discards what does not fit. */
        fun threaded(): AsyncLineWriter {
            val pool = ThreadPoolExecutor(
                1, 1, 30, TimeUnit.SECONDS, ArrayBlockingQueue(QUEUE),
                ThreadFactory { r -> Thread(r, "tm-app-log").apply { isDaemon = true; priority = Thread.MIN_PRIORITY } },
                ThreadPoolExecutor.DiscardPolicy(),
            )
            pool.allowCoreThreadTimeOut(true)
            return AsyncLineWriter(pool)
        }
    }
}

/**
 * A [LogSink] that hands every call on to [next] at once and writes the kept ones to the app log
 * on [writer]'s thread, so the caller (often the main thread) never waits for the disk.
 */
class AppLogSink(
    private val next: LogSink,
    private val writer: AsyncLineWriter,
    private val detailed: () -> Boolean,
    private val clock: () -> Long = System::currentTimeMillis,
    private val log: () -> RotatingLog,
) : LogSink {

    override fun i(tag: String, message: String) {
        runCatching { next.i(tag, message) }
        if (!AppLogPolicy.keepsInfo(tag, detailed())) return
        val at = clock()
        writer.submit { FileLogSink(log(), null) { at }.i(tag, message) }
    }

    override fun w(tag: String, message: String, error: Throwable?) {
        runCatching { next.w(tag, message, error) }
        val at = clock()
        writer.submit { FileLogSink(log(), null) { at }.w(tag, message, error) }
    }
}
