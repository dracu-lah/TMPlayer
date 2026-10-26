package com.tmplayer.data

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.tmplayer.BuildConfig
import io.sentry.Hint
import io.sentry.Sentry
import io.sentry.SentryEvent
import io.sentry.SentryLevel
import io.sentry.android.core.SentryAndroid
import io.sentry.protocol.SentryId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Crash reporting, off unless the viewer asks for it.
 *
 * The app sends nothing to a server of mine, so this is the one narrow exception. Nothing here
 * runs until [start] is called, [start] does nothing unless the switch in Settings is on, and the
 * switch is off on a fresh install. Sentry's own auto-start ContentProvider is disabled in the
 * manifest, so there is no other way in.
 *
 * With the switch off, [watch] writes a crash down on the device instead, and nothing more. The
 * next start offers it in [pending], and it is sent only if the viewer then says so: once with
 * [sendPending], or from then on by turning the switch on. The app not responding (an ANR) and a
 * crash in native code are offered the same way, from Android's own list of how the app's
 * processes ended (see [CrashExits]), since no code of the app's runs as they happen.
 *
 * A report contains the stack trace, the app version, the Android version and the device model,
 * and nothing else: no breadcrumbs, sessions, screen names, filenames or chat names. Every
 * automatic breadcrumb source is off, because a breadcrumb is exactly where the name of the video
 * somebody was watching would otherwise turn up.
 *
 * With no [BuildConfig.SENTRY_DSN] compiled in, which is what a fork and every CI build get, this
 * is inert and Settings does not offer the switch at all.
 */
object CrashReports {

    /** Whether this build has anywhere to send a report. Nothing is offered to the viewer if not. */
    val available: Boolean = BuildConfig.SENTRY_DSN.isNotBlank()

    private var started = false

    /** What the question at start is about, and so what it says. */
    enum class Question {
        /** A Java or Kotlin crash, written down by [watch]. */
        Crash,

        /** Android closed the app for not responding. */
        NotResponding,

        /** The app closed with a crash in native code. */
        NativeCrash,
    }

    /**
     * Brings the SDK up, once, if this build has a DSN and [enabled] is true.
     *
     * Called from [com.tmplayer.App] after reading the setting, and again when the viewer turns
     * the switch on. Turning it off calls [stop].
     */
    @Synchronized
    fun start(context: Context, enabled: Boolean) {
        if (!available || !enabled) return
        enabledNow = true
        if (started) return
        init(context, full = true, exit = null)
        started = true
        Log.i(TAG, "Crash reporting is on, at the viewer's request.")
    }

    /**
     * Initialises the SDK. [full] is the switch on: every kind of report. Otherwise it is up for
     * one report the viewer agreed to, a crash record ([exit] null) or the exit of [exit]'s kind,
     * and only the integration that report needs reads Android's list of exits. Any other
     * integration reading it would find the latest exit, see it dropped by the gate below, and mark
     * it reported in its own cache, so a later Send for it would find nothing to send.
     */
    private fun init(context: Context, full: Boolean, exit: ExitKind?) {
        SentryAndroid.init(context.applicationContext) { options ->
            options.dsn = BuildConfig.SENTRY_DSN
            options.release = "tmplayer@${BuildConfig.VERSION_NAME}"
            options.environment = if (BuildConfig.DEBUG) "debug" else "release"

            // Crashes and application-not-responding, and nothing else. Performance tracing sends
            // a sample of ordinary, healthy operations, which is usage data by another name.
            options.tracesSampleRate = 0.0
            options.isEnableAutoSessionTracking = false

            // App not responding (ANR v2, read from Android's exit list at the next start) and
            // native crashes (the tombstone Android keeps, Android 12 and newer, no NDK module).
            // Only the latest of each, and without the raw thread dump or tombstone as an
            // attachment: attachments count against the plan, and a raw dump holds more than the
            // privacy page lists. TDLib's and FFmpeg's libraries carry no symbols, so a native
            // frame reads as the library and an offset, which is enough to tell which one.
            options.isAnrEnabled = full || exit == ExitKind.NotResponding
            options.isTombstoneEnabled = full || exit == ExitKind.NativeCrash
            options.isReportHistoricalAnrs = false
            options.isReportHistoricalTombstones = false
            options.isAttachAnrThreadDump = false
            options.isAttachRawTombstone = false

            // No trail of what the viewer was doing before the crash: a breadcrumb is written by
            // every activity change, system event and network call, and any of them can carry the
            // name of a chat or a file.
            options.maxBreadcrumbs = 0
            options.isEnableActivityLifecycleBreadcrumbs = false
            options.isEnableAppComponentBreadcrumbs = false
            options.isEnableSystemEventBreadcrumbs = false
            options.isEnableAppLifecycleBreadcrumbs = false
            options.isEnableUserInteractionBreadcrumbs = false
            options.isEnableNetworkEventBreadcrumbs = false

            // No IP address and no device name. Sentry 8.61 deprecates this for its Data Collection
            // options, but setting any one of those switches every option left unset to collect,
            // file paths and request bodies included, so the old off switch stays until 9.0
            // removes it and the replacement is spelt out field by field.
            @Suppress("DEPRECATION")
            options.isSendDefaultPii = false
            options.isAttachThreads = true
            options.isAttachStacktrace = true

            options.setBeforeSend { event, hint -> gate(event, hint) }
            options.setDiagnosticLevel(SentryLevel.ERROR)
        }
    }

    /**
     * The gates every report passes, in code rather than in configuration.
     *
     * If the switch went off between the crash and the send, the report is dropped. An exit the
     * viewer said Don't send to is dropped for good, switch or no switch. Otherwise the report is
     * cut down to the stack trace, the versions and the device model; see CrashReportScrubber.
     * The exceptions to the switch are the crash record and the exit the viewer has just agreed
     * to send. Last of all, after the scrubber, the day's limits (see [CrashLimits]).
     */
    private fun gate(event: SentryEvent, hint: Hint?): SentryEvent? {
        // The "could not keep up" report (HX2b): only with the switch on, scrubbed the same way,
        // and under its own daily limit rather than the crash budget.
        if (StutterReport.isStutter(event)) return if (enabledNow) StutterReport.scrub(event) else null
        return gateCrash(event, hint)
    }

    private fun gateCrash(event: SentryEvent, hint: Hint?): SentryEvent? =
        pass(event, hint)?.takeIf { kept -> limits?.admit(kept) != false }

    private fun pass(event: SentryEvent, hint: Hint?): SentryEvent? {
        val record = sendingRecord
        // Matched by its id, not by the moment, so nothing else the SDK sends while this one goes
        // out passes as the viewer's answer.
        if (record != null && event.eventId == record.second) {
            return CrashReportScrubber.scrub(event.apply { threads = null }).also { record.first.stampBuild(it) }
        }
        val exitTime = CrashExits.exitTime(hint)
        if (exitTime != null) {
            val accepted = acceptedExit
            val declined = answers?.declinedUpTo ?: Long.MIN_VALUE
            if (!CrashExits.allows(exitTime, declined, accepted, enabledNow)) return null
            // The threads stay: an ANR's report is the main thread's stack among the others, and
            // a tombstone's is every thread's, with names and frames only (see the scrubber).
            return CrashReportScrubber.scrub(event).also { if (exitTime == accepted) exitSent?.countDown() }
        }
        if (!enabledNow) return null
        return CrashReportScrubber.scrub(event)
    }

    /**
     * Writes a crash down on the device while the switch is off, before the process dies.
     *
     * Called first thing in [com.tmplayer.App.onCreate]. When the switch is on, Sentry puts its own
     * handler in front of this one and reports the crash itself; this one then sees it is on and
     * writes nothing. Only the newest crash is kept.
     *
     * Also says, before any activity is built, whether the last runs were a start-up loop
     * ([holdShell]). That costs a file check on every start and a read of a few kilobytes only
     * after a crash, which is the one start where it matters.
     */
    fun watch(context: Context) {
        if (!available) return
        val startedAt = SystemClock.elapsedRealtime()
        val file = File(context.filesDir, "crash/pending.json")
        this.file = file
        answers = ExitAnswers(File(context.filesDir, "crash/exits.properties"))
        limits = CrashLimits(File(context.filesDir, "crash/limits.json"))
        stutterReporter = StutterReporter({ enabledNow && started }, StutterLimits(File(context.filesDir, "crash/stutter.json")))
        if (file.isFile) {
            val waiting = CrashRecord.read(file)
            _holdShell.value = waiting?.startupLoop == true &&
                CrashRecord.worthSending(waiting, BuildConfig.VERSION_CODE.toLong(), System.currentTimeMillis())
        }
        // A few dozen bytes from the APK, read now rather than in the dying process, where an
        // out of memory error could stop the read. Absent from a build the Sentry plugin skips.
        val uuids = runCatching {
            context.assets.open(DEBUG_META).use { CrashRecord.proguardUuidsIn(it.reader().readText()) }
        }.getOrDefault(emptyList())
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            if (!enabledNow) {
                runCatching {
                    // The record still waiting, if nobody answered it, carries the count of
                    // start-up crashes on.
                    CrashRecord.of(
                        error, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE.toLong(), uuids,
                        sinceStartMillis = SystemClock.elapsedRealtime() - startedAt,
                        previous = CrashRecord.read(file),
                    ).write(file)
                }
            }
            previous?.uncaughtException(thread, error)
        }
    }

    private var file: File? = null

    /** The daily limit of "could not keep up" reports. Set by [watch]. */
    @Volatile
    private var stutterReporter: StutterReporter? = null

    /**
     * Reports that a video could not keep up on this device (HX2b), only while the switch is on:
     * with it off nothing is built, counted, written or queued. At most one a day for each kind
     * of video. Cheap, but it counts a file, so call it off the main thread.
     */
    fun reportStutter(facts: StutterReport.Facts): Boolean {
        if (!available) return false
        return stutterReporter?.report(facts) == true
    }

    /** What the viewer has said about ANRs and native crashes. Set by [watch]. */
    @Volatile
    private var answers: ExitAnswers? = null

    /** How many reports this device has sent today. Set by [watch]. */
    @Volatile
    private var limits: CrashLimits? = null

    /** The ANR or native crash [pending] is asking about, when it is one. */
    @Volatile
    private var pendingExit: AppExit? = null

    private val _pending = MutableStateFlow<Question?>(null)

    /** What from an earlier run is waiting for the viewer's answer, if anything. */
    val pending: StateFlow<Question?> = _pending.asStateFlow()

    private val _holdShell = MutableStateFlow(false)

    /**
     * Whether MainActivity holds back its shell: the last runs crashed one after another as they
     * started ([CrashRecord.startupLoop]). A crash in the shell itself, as 1.27.0's in Home was,
     * would otherwise come round again before the question in the shell was ever drawn, write
     * over the record and never be asked about. Held, the activity shows the question alone, on
     * a phone or a television, and builds the shell once it is answered. With the switch on there
     * is no question: the hold lasts while [review] sends the crash, so the next start's crash
     * cannot cut the send off.
     */
    val holdShell: StateFlow<Boolean> = _holdShell.asStateFlow()

    /**
     * What to do with a crash left from the last run, once the switch has been read at startup.
     *
     * A crash from an older build is asked about too: it carries that build's version and mapping
     * UUID, so it is listed and decoded as the build it happened in. It is thrown away only when
     * it is older than Sentry keeps anything, or comes from another build without a UUID (see
     * [CrashRecord.worthSending]). With the switch on (turned on after the crash, or before Sentry
     * had finished starting) it is sent without asking; otherwise [pending] asks.
     *
     * Then, with no crash to ask about and the switch off, the newest ANR or native crash nobody
     * has been asked about yet is offered the same way. One question per start: an exit left over
     * is offered on the next. With the switch on the SDK sends those itself, and everything up to
     * the newest is marked as dealt with, so turning the switch off later does not ask about them.
     */
    fun review(context: Context, enabled: Boolean) {
        // Whatever happens below, a held shell is let go unless there is now a question for it.
        try {
            if (!reviewCrash(context, enabled)) reviewExits(context, enabled)
        } finally {
            if (_pending.value == null) _holdShell.value = false
        }
    }

    /** True when there was a crash to deal with: sent, or now asked about. */
    private fun reviewCrash(context: Context, enabled: Boolean): Boolean {
        val file = file ?: return false
        val record = CrashRecord.read(file)
        if (record == null ||
            !CrashRecord.worthSending(record, BuildConfig.VERSION_CODE.toLong(), System.currentTimeMillis())
        ) {
            file.delete()
            return false
        }
        if (enabled) sendPendingCrash(context) else _pending.value = Question.Crash
        return !enabled
    }

    private fun reviewExits(context: Context, enabled: Boolean) {
        val answers = answers ?: return
        if (enabled) {
            CrashExits.latest(context)?.let(answers::markAsked)
            return
        }
        val exit = CrashExits.latestUnasked(context, answers.askedUpTo, System.currentTimeMillis()) ?: return
        pendingExit = exit
        _pending.value = when (exit.kind) {
            ExitKind.NotResponding -> Question.NotResponding
            ExitKind.NativeCrash -> Question.NativeCrash
        }
    }

    /**
     * Sends what [pending] asked about, once, then forgets it. Blocks while the report goes out,
     * so it belongs on a background thread.
     *
     * With the switch off the SDK is brought up for this one report and closed again after it.
     * A held shell ([holdShell]) is let go only after the flush, so a crash in the shell as it is
     * built cannot cut this send off.
     */
    fun sendPending(context: Context) {
        try {
            val exit = pendingExit
            pendingExit = null
            if (exit != null) sendExit(context, exit) else sendPendingCrash(context)
        } finally {
            _holdShell.value = false
        }
    }

    @Synchronized
    private fun sendPendingCrash(context: Context) {
        _pending.value = null
        val file = file ?: return
        val record = CrashRecord.read(file)
        file.delete()
        if (record == null || !available) return
        val wasStarted = started
        if (!wasStarted) init(context, full = false, exit = null)
        val event = record.toEvent()
        try {
            sendingRecord = record to event.eventId
            Sentry.captureEvent(event)
        } finally {
            sendingRecord = null
        }
        Sentry.flush(FLUSH_MILLIS)
        if (!wasStarted) runCatching { Sentry.close() }
        Log.i(TAG, "The last crash was sent, at the viewer's request.")
    }

    /**
     * Sends the ANR or native crash the viewer agreed to. The SDK builds that report itself, from
     * Android's list, as its integration starts; this brings the SDK up with that integration
     * alone, lets the one exit through the gate, and waits for it before closing the SDK again.
     *
     * With the switch already on (the question's "Always send" tick turns it on first) the SDK
     * sent the exit as it started, and there is nothing left to do but remember it was asked.
     */
    @Synchronized
    private fun sendExit(context: Context, exit: AppExit) {
        _pending.value = null
        answers?.markAsked(exit.timestamp)
        if (!available || started) return
        val sent = CountDownLatch(1)
        acceptedExit = exit.timestamp
        exitSent = sent
        try {
            init(context, full = false, exit = exit.kind)
            if (!sent.await(EXIT_WAIT_MILLIS, TimeUnit.MILLISECONDS)) {
                Log.w(TAG, "The SDK did not send the exit it was asked to.")
            }
            Sentry.flush(FLUSH_MILLIS)
        } finally {
            acceptedExit = null
            exitSent = null
            runCatching { Sentry.close() }
        }
        Log.i(TAG, "The last ${exit.kind} was sent, at the viewer's request.")
    }

    /**
     * Forgets what [pending] asked about without sending it. An ANR or native crash is remembered
     * as declined, so it is never sent, not even by turning the switch on later.
     */
    fun discardPending() {
        _pending.value = null
        pendingExit?.let { answers?.markDeclined(it.timestamp) }
        pendingExit = null
        file?.delete()
        _holdShell.value = false
    }

    /** The crash the viewer agreed to send and its event id, while it goes out. */
    @Volatile
    private var sendingRecord: Pair<CrashRecord, SentryId?>? = null

    /** The exit the viewer agreed to send, by its timestamp, while the SDK is up for it. */
    @Volatile
    private var acceptedExit: Long? = null

    /** Counted down when [acceptedExit]'s report passes the gate. */
    @Volatile
    private var exitSent: CountDownLatch? = null

    /** Where the Sentry Gradle plugin puts the build's mapping UUID in the APK. */
    private const val DEBUG_META = "sentry-debug-meta.properties"

    /**
     * Turns reporting off and closes the SDK.
     *
     * The flag is cleared first, so a crash occurring during the close is dropped by the
     * beforeSend gate above rather than sent by a half-shut client.
     */
    @Synchronized
    fun stop() {
        enabledNow = false
        if (!started) return
        runCatching { Sentry.close() }
        started = false
        Log.i(TAG, "Crash reporting is off.")
    }

    /**
     * A failure the app caught and carried on from (HX10): a background job that threw
     * ([com.tmplayer.platform.JobFailures]) or a player error that ended in the status sheet.
     * Sent as an error, not a crash, and only with the switch on; with it off nothing is written,
     * since the app did not stop and there is nothing to ask about at the next start. The same
     * scrubber and daily limits as a crash apply ([gate]).
     */
    fun handled(error: Throwable) {
        if (!available || !enabledNow || !started) return
        runCatching { Sentry.captureException(error) { scope -> scope.level = SentryLevel.ERROR } }
    }

    @Volatile
    private var enabledNow = false

    private const val FLUSH_MILLIS = 5_000L

    /** Long enough for the integration to read the list, parse a tombstone and hand the report on. */
    private const val EXIT_WAIT_MILLIS = 15_000L

    private const val TAG = "CrashReports"
}
