package com.tmplayer.data

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.Process
import com.tmplayer.platform.FileLogSink
import com.tmplayer.platform.KeptLog
import com.tmplayer.platform.Logger
import com.tmplayer.player.DecoderLister
import com.tmplayer.player.LastVideoCodec
import com.tmplayer.player.PlaybackMarks
import com.tmplayer.player.StutterVerdict
import com.tmplayer.player.TraceFacts
import com.tmplayer.player.TraceFile
import com.tmplayer.player.TraceText
import com.tmplayer.ui.settings.feedbackFacts
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Gathers what "Send logs" holds on Android (L5) and hands it to [LogBundle]: the earlier run's
 * crash, the device's facts, the decoders it lists for the last video's codec, the last playback
 * sessions and the marked moments, the last Playback test, the app's own log and this process's
 * logcat. Every part reads local files or the system's own lists; nothing leaves the device here.
 *
 * Blocking: call it off the main thread.
 */
object LogReport {

    private const val TAG = "LogReport"

    fun build(context: Context, known: Collection<String>): LogBundle.Result {
        val app = context.applicationContext
        val codec = LastVideoCodec.read(DiagnosticFiles.codecFile(app))
        val decoders = DecoderReport.lines(DecoderLister.read(listOfNotNull(codec) + DecoderLister.COMMON), codec)
        val parts = LogBundle.Parts(
            crash = guarded("crash") { CrashReports.logLines() },
            device = guarded("device") { deviceLines(app) },
            decoders = decoders,
            sessions = guarded("sessions") { sessionBlocks(File(app.filesDir, TraceFile.NAME)) },
            marks = guarded("marks") { PlaybackMarks(PlaybackMarks.fileIn(app)).reportLines() },
            playbackTest = guarded("playback test") { readLines(File(app.filesDir, DiagnosticFiles.PLAYBACK_TEST)) },
            appLog = guarded("app log") { AppLog.get(app).readLines() },
            // iOS keeps its own lines and TDLib's ([KeptLog]): it has no logcat, and an export that
            // asked for one held nothing but its header (an iPad, 2026-11-15). Android reads its logcat.
            logcat = guarded("logcat") { KeptLog.lines(LogExport.MAX_LINES) ?: ownLogcat() },
        )
        return LogBundle.build(feedbackFacts(app), parts, known)
    }

    /** The playback trace's sessions as blocks of lines, oldest first. */
    fun sessionBlocks(file: File): List<List<String>> = TraceFile(file).read().map { session ->
        val verdict = StutterVerdict.classify(session.samples).name.lowercase()
        listOf("Session started ${FileLogSink.stamp(session.startedAtMs)}, ${session.samples.size} reading(s), verdict $verdict") +
            TraceFacts.lines(session.facts, session.hops) +
            TraceText.lines(session.samples)
    }

    private fun readLines(file: File): List<String> = if (file.isFile) file.readText().lines().filter { it.isNotBlank() } else emptyList()

    private fun deviceLines(context: Context): List<String> = buildList {
        add("Model: ${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE}), hardware ${Build.HARDWARE}")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) add("Chip: ${Build.SOC_MANUFACTURER} ${Build.SOC_MODEL}")
        add("Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), security patch ${Build.VERSION.SECURITY_PATCH}")
        add("ABIs: ${Build.SUPPORTED_ABIS.joinToString()}")
        add("CPU cores: ${Runtime.getRuntime().availableProcessors()}")
        runCatching {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val info = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
            add(
                "Memory: ${info.totalMem / MB} MB in all, app limit ${am.memoryClass} MB (${am.largeMemoryClass} MB large)" +
                    if (am.isLowRamDevice) ", a low memory device" else "",
            )
        }
        add("Build: ${com.tmplayer.BuildConfig.BUILD_TYPE}, version code ${com.tmplayer.BuildConfig.VERSION_CODE}")
    }

    private inline fun <T> guarded(what: String, block: () -> List<T>): List<T> = try {
        block()
    } catch (e: Exception) {
        Logger.w(TAG, "$what could not be read for the report", e)
        emptyList()
    }

    /** This process's last [LogExport.MAX_LINES] logcat lines, which an app may read without a permission. */
    private fun ownLogcat(): List<String> {
        val process = ProcessBuilder(
            "logcat", "-d", "-v", "threadtime", "-t", LogExport.MAX_LINES.toString(), "--pid=${Process.myPid()}",
        ).redirectErrorStream(true).start()
        return try {
            process.inputStream.bufferedReader().useLines { it.toList() }.takeLast(LogExport.MAX_LINES)
        } finally {
            // The timed wait is Android 8's. Earlier the output has already been read to its end,
            // which logcat -d reaches only as it exits, so destroying it straight away loses nothing.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) process.waitFor(2, TimeUnit.SECONDS)
            process.destroy()
        }
    }

    private const val MB = 1024L * 1024L
}
