package com.tmplayer.data

import io.sentry.SentryEvent
import io.sentry.SentryLevel
import io.sentry.protocol.App
import io.sentry.protocol.DebugImage
import io.sentry.protocol.DebugMeta
import io.sentry.protocol.Mechanism
import io.sentry.protocol.SentryException
import io.sentry.protocol.SentryStackFrame
import io.sentry.protocol.SentryStackTrace
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Date

/**
 * A crash written down on the device while crash reporting is off, so the next start can ask
 * whether to send it.
 *
 * It holds what a report would: the exception chain with its stack frames, the moment, and the
 * build it happened in. It is never sent on its own; [CrashReports] turns it into a report only
 * after the viewer says yes, and the report then goes through [CrashReportScrubber] like any other.
 *
 * The build is named in full, down to the UUID its R8 mapping was uploaded under, so a crash can
 * still be read after the viewer has updated: people who crash tend to update next, and the
 * update is often the fix for that very crash. The report then says it came from the old build,
 * and Sentry decodes its frames with the old build's mapping rather than the running one's.
 */
class CrashRecord(
    val time: Long,
    val versionName: String,
    val versionCode: Long,
    /** Outermost first, each followed by its cause. */
    val chain: List<Link>,
    /**
     * The UUIDs of the build's R8 mappings, from `assets/sentry-debug-meta.properties`, which the
     * Sentry Gradle plugin writes into a release APK. Empty for a record written by 1.27.1 or
     * older, and for a debug build, which is not minified and has nothing to decode.
     */
    val proguardUuids: List<String> = emptyList(),
    /** How long the process had been running at the crash, or -1 where that is not known. */
    val sinceStartMillis: Long = -1,
    /**
     * How many runs in a row, this one included, crashed within [STARTUP_MILLIS] of starting: 0
     * for a crash later in a run. Two or more is a start-up loop ([startupLoop]).
     */
    val startupRun: Int = 0,
) {

    /** The app keeps closing as it opens, so the question is asked before anything else is built. */
    val startupLoop: Boolean get() = startupRun >= LOOP_RUNS

    class Link(
        val type: String,
        val message: String?,
        val frames: List<StackTraceElement>,
    )

    /**
     * The report as Sentry would have built it at the crash: causes before the exception that
     * caused nothing, oldest frame first, marked as unhandled.
     */
    fun toEvent(): SentryEvent = SentryEvent(Date(time)).apply {
        level = SentryLevel.FATAL
        // The build that crashed, not the one sending: the SDK fills these in only where they are
        // still empty. The app context it fills regardless, so [stampBuild] puts that back after.
        release = "tmplayer@$versionName"
        dist = versionCode.toString()
        stampBuild(this)
        // The crashed build's mapping. Sentry keeps a ProGuard image already on an event rather
        // than adding the running build's beside it (DebugMeta.buildDebugMeta in 8.61), so the
        // frames are decoded with this one alone.
        if (proguardUuids.isNotEmpty()) {
            debugMeta = DebugMeta().apply {
                images = proguardUuids.map { uuid ->
                    DebugImage().apply {
                        type = DebugImage.PROGUARD
                        this.uuid = uuid
                    }
                }
            }
        }
        setExceptions(chain.reversed().map { link ->
            SentryException().apply {
                type = link.type.substringAfterLast('.')
                module = link.type.substringBeforeLast('.', "")
                value = link.message
                mechanism = Mechanism().apply {
                    type = "UncaughtExceptionHandler"
                    isHandled = false
                }
                stacktrace = SentryStackTrace(link.frames.reversed().map { element ->
                    SentryStackFrame().apply {
                        module = element.className
                        function = element.methodName
                        filename = element.fileName
                        lineno = element.lineNumber.takeIf { it >= 0 }
                        isNative = element.isNativeMethod
                        isInApp = element.className.startsWith("com.tmplayer")
                    }
                })
            }
        })
    }

    /**
     * Writes the crashed build's version into the event's app context. The SDK's own processor
     * fills that context from the running package, after [toEvent] and before beforeSend, so a
     * crash from an older build would otherwise be listed under the newer one.
     */
    fun stampBuild(event: SentryEvent) {
        val app = event.contexts.app ?: App()
        app.appVersion = versionName
        app.appBuild = versionCode.toString()
        event.contexts.setApp(app)
    }

    /**
     * The crash as lines for the log a viewer sends ([LogExport.report]): the logcat of the run
     * that is open cannot hold the run that crashed, and the crash is what the maintainer needs.
     * Types, messages and frames only, [MAX_LINES] of them at most; the log's scrubber takes names
     * out of each line after. The mapping ids let an obfuscated frame be read with the mapping of
     * the build that crashed, which is not always the one running now.
     */
    fun describe(): List<String> = buildList {
        add("Last crash, from an earlier run (not in the lines below)")
        val moment = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss 'UTC'", java.util.Locale.ROOT)
            .apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
            .format(Date(time))
        val after = if (sinceStartMillis >= 0) ", ${sinceStartMillis / 100 / 10.0} s into the run" else ""
        val loop = if (startupRun > 0) ", start-up crash number $startupRun in a row" else ""
        add("  Build $versionName ($versionCode), $moment$after$loop")
        if (proguardUuids.isNotEmpty()) add("  Mapping ids: ${proguardUuids.joinToString(" ")}")
        for (link in chain) {
            if (size >= MAX_LINES) break
            add("  ${link.type}: ${link.message.orEmpty().take(MAX_MESSAGE)}")
            for (frame in link.frames.take(MAX_FRAMES)) {
                if (size >= MAX_LINES) break
                add("    at $frame")
            }
        }
        add("")
    }

    fun write(file: File) {
        val json = JSONObject()
            .put("time", time)
            .put("versionName", versionName)
            .put("versionCode", versionCode)
            .put("proguardUuids", JSONArray(proguardUuids))
            .put("sinceStart", sinceStartMillis)
            .put("startupRun", startupRun)
            .put("chain", JSONArray(chain.map { link ->
                JSONObject()
                    .put("type", link.type)
                    .put("message", link.message ?: JSONObject.NULL)
                    .put("frames", JSONArray(link.frames.map { element ->
                        JSONObject()
                            .put("class", element.className)
                            .put("method", element.methodName)
                            .put("file", element.fileName ?: JSONObject.NULL)
                            .put("line", element.lineNumber)
                    }))
            }))
        file.parentFile?.mkdirs()
        // Through a temporary file, so a process killed halfway leaves the old record or none,
        // never half a one.
        val partial = File(file.path + ".part")
        partial.writeText(json.toString())
        partial.renameTo(file)
    }

    companion object {

        /** Deep enough for any real chain, and a stop for one that loops back on itself. */
        private const val MAX_CAUSES = 8

        /**
         * How long a crash is kept for the viewer to be asked about: the free plan's retention.
         * A report older than that would be thrown away by Sentry the day it arrived.
         */
        const val KEEP_MILLIS = 30L * 24 * 60 * 60 * 1000

        /**
         * A crash this soon after the process started is a start-up crash: the shell and Home have
         * been built by then on the slowest television the app runs on, and nobody has had the
         * time to do anything of their own yet.
         */
        const val STARTUP_MILLIS = 15_000L

        /** Start-up crashes in a row that make a loop. One could be anything; two is a pattern. */
        const val LOOP_RUNS = 2

        /** The most lines [describe] gives the log, and the most of each cause's stack and message in them. */
        private const val MAX_LINES = 60
        private const val MAX_FRAMES = 25
        private const val MAX_MESSAGE = 300

        /**
         * The record of [error], [sinceStartMillis] into the run. [previous] is the record still
         * waiting from an earlier run, if any, which is how a run of start-up crashes is counted:
         * the count carries on only while each crash in turn came early and nobody answered.
         */
        fun of(
            error: Throwable,
            versionName: String,
            versionCode: Long,
            proguardUuids: List<String> = emptyList(),
            sinceStartMillis: Long = -1,
            previous: CrashRecord? = null,
        ): CrashRecord {
            val chain = generateSequence(error) { it.cause.takeIf { cause -> cause !== it } }
                .take(MAX_CAUSES)
                .map { Link(it.javaClass.name, it.message, it.stackTrace.toList()) }
                .toList()
            val early = sinceStartMillis in 0..STARTUP_MILLIS
            val run = if (early) (previous?.startupRun ?: 0) + 1 else 0
            return CrashRecord(
                System.currentTimeMillis(), versionName, versionCode, chain, proguardUuids, sinceStartMillis, run,
            )
        }

        /**
         * Whether a crash left on the device is still worth asking about from the build
         * [runningVersionCode], at [now].
         *
         * Not once it is older than [KEEP_MILLIS]. A crash from another build is kept only when it
         * names that build's mapping: without one its frames could only be decoded with the
         * running build's mapping, which is the wrong one, and that is every record written by
         * 1.27.1 or older. A crash from the running build needs no UUID, since the SDK adds its own.
         *
         * [needsMapping] is false for the desktop, whose code is shrunk but never renamed: its
         * frames read as the source does whichever build they came from.
         */
        fun worthSending(
            record: CrashRecord,
            runningVersionCode: Long,
            now: Long,
            needsMapping: Boolean = true,
        ): Boolean = when {
            now - record.time > KEEP_MILLIS -> false
            record.versionCode == runningVersionCode || !needsMapping -> true
            else -> record.proguardUuids.isNotEmpty()
        }

        /**
         * The mapping UUIDs in the text of `sentry-debug-meta.properties`: the value of
         * `io.sentry.ProguardUuids`, which the plugin writes as one UUID, or several joined by `|`.
         */
        fun proguardUuidsIn(properties: String): List<String> {
            val props = java.util.Properties().apply { load(properties.reader()) }
            return props.getProperty("io.sentry.ProguardUuids").orEmpty()
                .split('|', ',')
                .map { it.trim() }
                .filter { it.isNotEmpty() }
        }

        /** The record in [file], or null when there is none or it cannot be read. */
        fun read(file: File): CrashRecord? = runCatching {
            if (!file.isFile) return null
            val json = JSONObject(file.readText())
            val chain = json.getJSONArray("chain").let { links ->
                (0 until links.length()).map { i ->
                    val link = links.getJSONObject(i)
                    val frames = link.getJSONArray("frames")
                    Link(
                        type = link.getString("type"),
                        message = link.optString("message").takeUnless { link.isNull("message") },
                        frames = (0 until frames.length()).map { f ->
                            val frame = frames.getJSONObject(f)
                            StackTraceElement(
                                frame.getString("class"),
                                frame.getString("method"),
                                frame.optString("file").takeUnless { frame.isNull("file") },
                                frame.getInt("line"),
                            )
                        },
                    )
                }
            }
            // Absent from a record written by 1.27.1 or older.
            val uuids = json.optJSONArray("proguardUuids")
                ?.let { array -> (0 until array.length()).map { array.getString(it) } }
                .orEmpty()
            CrashRecord(
                json.getLong("time"), json.getString("versionName"), json.getLong("versionCode"), chain, uuids,
                sinceStartMillis = json.optLong("sinceStart", -1),
                startupRun = json.optInt("startupRun", 0),
            )
        }.getOrNull()
    }
}
