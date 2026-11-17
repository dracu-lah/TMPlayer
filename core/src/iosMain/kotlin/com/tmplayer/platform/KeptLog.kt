@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.tmplayer.platform

import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.toKString
import platform.Foundation.NSBundle
import platform.Foundation.NSCachesDirectory
import platform.Foundation.NSData
import platform.Foundation.NSDate
import platform.Foundation.NSDateFormatter
import platform.Foundation.NSFileManager
import platform.Foundation.NSLocale
import platform.Foundation.NSProcessInfo
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSUserDomainMask
import platform.Foundation.dataWithContentsOfFile
import platform.Foundation.dateWithTimeIntervalSince1970
import platform.darwin.dispatch_async
import platform.darwin.dispatch_queue_create
import platform.darwin.dispatch_sync
import platform.posix.FILE
import platform.posix.SEEK_END
import platform.posix.fclose
import platform.posix.fflush
import platform.posix.fopen
import platform.posix.fputs
import platform.posix.fseek
import platform.posix.ftell
import platform.posix.getpid
import platform.posix.pthread_mach_thread_np
import platform.posix.pthread_self
import platform.posix.rename
import platform.posix.uname
import platform.posix.utsname

/**
 * The app's own log on iOS, for "Save logs" and "Send logs". Android's export reads the app's
 * logcat, where TDLib writes as well; iOS has nothing an app may read back, and its system log
 * keeps a release build's lines as "<private>". So the export found nothing but its header, and a
 * sign in that went wrong on an iPad left no trace (2026-11-15).
 *
 * Every line the app logs ([Logger], android.util.Log's stand in, the Swift side through it) is
 * written here too, with its moment, process and thread, on one serial queue so no caller waits on
 * the disk. The files live under Caches/applog and survive a restart and an update, so a log saved
 * after one still holds what came before it: two of [MAX_BYTES], the older one replaced as the
 * newer one fills. TDLib writes its own file beside them at [tdlibVerbosity], warnings included
 * (a lost authorization is one), and [lines] merges the two by time ([KeptLogLines]).
 */
object KeptLog {
    /** TDLib's level for its file: 2 adds warnings to its errors. */
    val tdlibVerbosity: Int get() = 2

    /** The size TDLib's file grows to before TDLib moves it to `.old` and starts another. */
    val tdlibMaxBytes: Long get() = 1L shl 20

    /** Where TDLib writes its log, or null if the folder could not be made. */
    val tdlibLogPath: String? get() = folder?.let { "$it/tdlib.log" }

    private const val MAX_BYTES = 512L * 1024
    private const val TAG = "KeptLog"

    private val queue = dispatch_queue_create("org.tmplayer.keptlog", null)
    private val folder: String? by lazy { makeFolder() }
    private val stamp: NSDateFormatter by lazy {
        NSDateFormatter().apply {
            dateFormat = "yyyy-MM-dd HH:mm:ss.SSS"
            locale = NSLocale(localeIdentifier = "en_US_POSIX")
        }
    }
    private val process = getpid()

    // Only ever touched on [queue].
    private var out: kotlinx.cinterop.CPointer<FILE>? = null
    private var size = 0L
    private var opened = false

    /** Keeps [text] (one line, or a line and a stack trace) as logged now on this thread. */
    fun keep(text: String) {
        val at = NSDate()
        val thread = pthread_mach_thread_np(pthread_self())
        dispatch_async(queue) { write(at, thread, text) }
    }

    /**
     * The last [max] lines kept, the app's and TDLib's merged by time, the oldest first. Waits for
     * what was logged before the call to be written.
     */
    fun lines(max: Int): List<String> {
        var kept = emptyList<String>()
        dispatch_sync(queue) {
            out?.let { fflush(it) }
            val dir = folder ?: return@dispatch_sync
            val app = read("$dir/app.1.log") + read("$dir/app.log")
            val tdlib = KeptLogLines.fromTdlib(read("$dir/tdlib.log.old") + read("$dir/tdlib.log")) { millis ->
                stamp.stringFromDate(NSDate.dateWithTimeIntervalSince1970(millis / 1000.0))
            }
            kept = KeptLogLines.merge(app, tdlib, max)
        }
        return kept
    }

    private fun write(at: NSDate, thread: UInt, text: String) {
        val file = open(at) ?: return
        val head = "${stamp.stringFromDate(at)} $process t$thread"
        // Every line of a stack trace carries the moment too, so the merge keeps them together.
        val block = text.lineSequence().joinToString(separator = "\n", postfix = "\n") { "$head $it" }
        fputs(block, file)
        fflush(file)
        size += block.encodeToByteArray().size
        if (size >= MAX_BYTES) {
            fclose(file)
            out = null
            folder?.let { rename("$it/app.log", "$it/app.1.log") }
            size = 0
        }
    }

    // [at] is the first line's moment, which the run's first line takes too, so it heads the run.
    private fun open(at: NSDate): kotlinx.cinterop.CPointer<FILE>? {
        out?.let { return it }
        val dir = folder ?: return null
        val file = fopen("$dir/app.log", "a") ?: return null
        fseek(file, 0, SEEK_END)
        size = ftell(file)
        out = file
        if (!opened) {
            // Where one run of the app begins, with what it is and where it runs.
            opened = true
            val start = "${stamp.stringFromDate(at)} $process t0 I/$TAG: ${started()}\n"
            fputs(start, file)
            size += start.encodeToByteArray().size
        }
        return file
    }

    private fun started(): String {
        val info = NSBundle.mainBundle.infoDictionary
        val version = info?.get("CFBundleShortVersionString") as? String ?: "?"
        val build = info?.get("CFBundleVersion") as? String ?: "?"
        val machine = memScoped {
            val name = alloc<utsname>()
            if (uname(name.ptr) == 0) name.machine.toKString() else "?"
        }
        return "TMPlayer $version (build $build) started on $machine, ${NSProcessInfo.processInfo.operatingSystemVersionString}"
    }

    private fun makeFolder(): String? {
        val caches = NSSearchPathForDirectoriesInDomains(NSCachesDirectory, NSUserDomainMask, true).firstOrNull() as? String
            ?: return null
        val dir = "$caches/applog"
        val made = NSFileManager.defaultManager.createDirectoryAtPath(dir, withIntermediateDirectories = true, attributes = null, error = null)
        return dir.takeIf { made || NSFileManager.defaultManager.fileExistsAtPath(it) }
    }

    // The file's lines, or none when it is not there. Bytes that are not UTF-8 are replaced
    // rather than losing the file.
    private fun read(path: String): List<String> {
        val data = NSData.dataWithContentsOfFile(path) ?: return emptyList()
        val length = data.length.toInt()
        if (length == 0) return emptyList()
        val bytes = data.bytes?.reinterpret<kotlinx.cinterop.ByteVar>()?.readBytes(length) ?: return emptyList()
        return bytes.decodeToString().lines().filter { it.isNotEmpty() }
    }
}
