package com.tmplayer.desktop

import com.tmplayer.data.CrashLimits
import com.tmplayer.data.CrashRecord
import com.tmplayer.data.CrashReportScrubber
import com.tmplayer.data.SettingsStore
import com.tmplayer.i18n.L
import com.tmplayer.i18n.Translator
import com.tmplayer.platform.Logger
import io.sentry.Sentry
import io.sentry.SentryEvent
import io.sentry.SentryLevel
import io.sentry.exception.ExceptionMechanismException
import io.sentry.protocol.App
import io.sentry.protocol.Mechanism
import io.sentry.protocol.OperatingSystem
import io.sentry.protocol.SentryId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import java.util.Locale

/**
 * Crash reporting on Windows, Linux and macOS (CP102g), off unless the viewer asks for it: the
 * phone's CrashReports, with the plain JVM SDK.
 *
 * Nothing here sends anything until [start] is called with the switch on, and the switch is off
 * until the viewer turns it on in the setup after sign in, in Settings, or with the crash
 * question's "Always send". With it off, [watch] writes an uncaught exception down in the data
 * folder and nothing more; the next start offers it ([pending]), and it is sent only if the viewer
 * then says so. A crash from an older desktop build is offered too, as that build: the desktop is
 * shrunk but never renamed, so its frames read as the source does without a mapping.
 *
 * A report is the stack trace, the TMPlayer version and the operating system with its version,
 * and nothing else: no host name, no device, no Java runtime, no breadcrumbs, sessions or tracing,
 * every message scrubbed of paths and file names (CrashReportScrubber, shared with the phone), and
 * at most once a day for the same crash and ten a day in all (CrashLimits).
 *
 * A crash on the way up, before the window (StartupFailure), is sent and flushed before the error
 * is shown when the switch is on. With it off, a run of them is asked about before anything else
 * starts on the next launch, in a plain dialog, since the window would never get as far as asking.
 *
 * With no DSN built in ([BuildInfo.SENTRY_DSN]), which is what a fork and every CI build get, this
 * is inert and the switch is never offered.
 */
object DesktopCrashReports {

    /** Whether this build has anywhere to send a report. Nothing is offered to the viewer if not. */
    val available: Boolean = BuildInfo.SENTRY_DSN.isNotBlank()

    private var started = false

    @Volatile
    private var enabledNow = false

    @Volatile
    private var file: File? = null

    @Volatile
    private var limits: CrashLimits? = null

    /** When [watch] ran, for how far into the run a crash came. */
    private var startedAtNanos = System.nanoTime()

    /** The last error the handler saw, so StartupFailure's report of the same one is not a second. */
    @Volatile
    private var lastUncaught: Throwable? = null

    private val _pending = MutableStateFlow(false)

    /** Whether a crash from an earlier run is waiting for the viewer's answer in the window. */
    val pending: StateFlow<Boolean> = _pending.asStateFlow()

    /** The crash the viewer agreed to send and its event id, while it goes out. */
    @Volatile
    private var sendingRecord: Pair<CrashRecord, SentryId?>? = null

    /**
     * Writes an uncaught exception down while the switch is off. Called first thing in main, before
     * StartupFailure's handler, so a launch that fails is written down before its error is shown.
     * With the switch on Sentry puts its own handler in front of this one and reports the crash
     * itself; this one then sees it is on and writes nothing. Only the newest crash is kept.
     */
    fun watch(dir: File) {
        if (!available) return
        startedAtNanos = System.nanoTime()
        val file = File(dir, "pending.json")
        this.file = file
        limits = CrashLimits(File(dir, "limits.json"))
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            lastUncaught = error
            if (!enabledNow) runCatching { record(error) }
            previous?.uncaughtException(thread, error)
        }
    }

    private fun record(error: Throwable) {
        val file = file ?: return
        // The record still waiting, if nobody answered it, carries the count of start-up crashes on.
        CrashRecord.of(
            error, BuildInfo.VERSION, VERSION_CODE,
            sinceStartMillis = (System.nanoTime() - startedAtNanos) / 1_000_000,
            previous = CrashRecord.read(file),
        ).write(file)
    }

    /**
     * Reads the switch, brings the SDK up if it is on and deals with a crash left from the last
     * run. Called in main right after [watch], before anything that could fail on the way up. A
     * settings store that cannot be read counts as the switch off, so the crash is written down.
     */
    fun launch(settings: SettingsStore) {
        if (!available) return
        val on = runCatching { runBlocking { withTimeout(READ_MILLIS) { settings.crashReportsNow() } } }.getOrDefault(false)
        start(on)
        review(on, settings)
    }

    /** Brings the SDK up, once, if this build has a DSN and [enabled] is true. Off is [stop]. */
    @Synchronized
    fun start(enabled: Boolean) {
        if (!available || !enabled) return
        enabledNow = true
        if (started) return
        init()
        started = true
        Logger.i(TAG, "Crash reporting is on, at the viewer's request.")
    }

    private fun init() {
        Sentry.init { options ->
            options.dsn = BuildInfo.SENTRY_DSN
            options.release = "tmplayer@${BuildInfo.VERSION}"
            options.dist = VERSION_CODE.toString()
            options.environment = if (BuildInfo.VERSION.contains("-dev")) "debug" else "release"
            // Crashes and nothing else: no tracing, no sessions, no trail of what came before.
            options.tracesSampleRate = 0.0
            options.isEnableAutoSessionTracking = false
            options.maxBreadcrumbs = 0
            // No IP address and no host name. Sentry 8.61 deprecates the first for its Data
            // Collection options; see the phone's CrashReports for why the old switch stays.
            @Suppress("DEPRECATION")
            options.isSendDefaultPii = false
            options.isAttachServerName = false
            // The crashed thread's stack is in the exception; the others are not asked for.
            options.isAttachThreads = false
            options.isAttachStacktrace = false
            options.addInAppInclude("com.tmplayer")
            options.setBeforeSend { event, _ -> gate(event) }
            options.setDiagnosticLevel(SentryLevel.ERROR)
        }
    }

    /**
     * The gates every report passes, in code rather than in configuration: dropped if the switch
     * went off between the crash and the send (unless it is the crash the viewer has just agreed to
     * send), then cut down to the stack trace, the versions and the operating system, then counted
     * against the day's limits.
     */
    private fun gate(event: SentryEvent): SentryEvent? {
        val record = sendingRecord
        val kept = when {
            record != null && event.eventId == record.second -> {
                event.threads = null
                stamp(event)
                CrashReportScrubber.scrub(event, keepDevice = false).also { record.first.stampBuild(it) }
            }
            !enabledNow -> null
            else -> CrashReportScrubber.scrub(stamp(event), keepDevice = false)
        }
        return kept?.takeIf { limits?.admit(it) != false }
    }

    /**
     * The operating system and the app, which the JVM SDK leaves out: the system's name as Java
     * reports it ("Windows 11", "Linux", "Mac OS X") and its version, and the running version.
     */
    private fun stamp(event: SentryEvent): SentryEvent {
        event.contexts.setOperatingSystem(OperatingSystem().apply {
            name = System.getProperty("os.name")
            version = System.getProperty("os.version")
        })
        event.contexts.setApp(App().apply {
            appVersion = BuildInfo.VERSION
            appBuild = VERSION_CODE.toString()
        })
        return event
    }

    /**
     * What to do with a crash left from the last run, once the switch is read. One older than
     * Sentry keeps anything is thrown away. With the switch on it is sent; with it off it is asked
     * about in the window, or, for a run of crashes as the app starts, here and now in a plain
     * dialog, since a launch that keeps failing never gets as far as the window.
     */
    private fun review(enabled: Boolean, settings: SettingsStore) {
        val file = file ?: return
        val record = CrashRecord.read(file)
        if (record == null ||
            !CrashRecord.worthSending(record, VERSION_CODE, System.currentTimeMillis(), needsMapping = false)
        ) {
            file.delete()
            return
        }
        when {
            // Before anything else starts, so the next crash cannot cut the send off.
            enabled && record.startupLoop -> sendPending()
            enabled -> Thread({ sendPending() }, "crash-report").apply { isDaemon = true }.start()
            record.startupLoop && !java.awt.GraphicsEnvironment.isHeadless() -> askBeforeStart(settings)
            else -> _pending.value = true
        }
    }

    /**
     * The crash question on its own, before the app builds anything: Send, Don't send, and the
     * Always send box, in the viewer's language where the settings can still be read.
     */
    private fun askBeforeStart(settings: SettingsStore) {
        runCatching {
            val saved = runBlocking { withTimeout(READ_MILLIS) { settings.language.first() } }
            Translator.select(saved, listOf(Locale.getDefault().toLanguageTag()))
        }
        val answer = runCatching {
            val always = javax.swing.JCheckBox(L.mainCrashAlways)
            val message = arrayOf<Any>(
                javax.swing.JLabel(html(L.mainCrashMessage)),
                javax.swing.JLabel(html(L.mainCrashDetailDesktop)),
                always,
            )
            val send = L.mainCrashSend
            val choice = javax.swing.JOptionPane.showOptionDialog(
                null, message, L.mainCrashTitle,
                javax.swing.JOptionPane.YES_NO_OPTION, javax.swing.JOptionPane.QUESTION_MESSAGE,
                null, arrayOf(send, L.mainCrashDontSend), send,
            )
            (choice == 0) to always.isSelected
        }.getOrNull()
        when {
            // The dialog could not be shown: the window asks instead, should it get that far.
            answer == null -> _pending.value = true
            answer.first -> {
                if (answer.second) {
                    runCatching { runBlocking { settings.setCrashReports(true) } }
                    start(true)
                }
                sendPending()
            }
            else -> discardPending()
        }
    }

    private fun html(text: String): String {
        val escaped = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        return "<html><body style='width: 380px'>$escaped</body></html>"
    }

    /**
     * Sends the crash [pending] asked about, once, then forgets it. Blocks while it goes out, so it
     * belongs on a background thread, or on the main thread before the app is built. With the
     * switch off the SDK is brought up for this one report and closed again after it.
     */
    @Synchronized
    fun sendPending() {
        _pending.value = false
        val file = file ?: return
        val record = CrashRecord.read(file)
        file.delete()
        if (record == null || !available) return
        val wasStarted = started
        runCatching {
            if (!wasStarted) init()
            val event = record.toEvent()
            try {
                sendingRecord = record to event.eventId
                Sentry.captureEvent(event)
            } finally {
                sendingRecord = null
            }
            Sentry.flush(FLUSH_MILLIS)
            if (!wasStarted) Sentry.close()
            Logger.i(TAG, "The last crash was sent, at the viewer's request.")
        }.onFailure { Logger.w(TAG, "could not send the last crash: ${it.message}") }
    }

    /** Forgets the crash [pending] asked about without sending it. */
    fun discardPending() {
        _pending.value = false
        file?.delete()
    }

    /**
     * A launch is failing (StartupFailure), and its error is about to be shown before the process
     * ends. With the switch on the error is sent, unless the SDK's own handler already has it, and
     * the send is flushed first; with it off it is written down for the next start to ask about.
     */
    fun beforeStartupFailure(error: Throwable) {
        if (!available) return
        runCatching {
            if (error !== lastUncaught) {
                if (enabledNow && started) {
                    val mechanism = Mechanism().apply {
                        type = "StartupFailure"
                        isHandled = false
                    }
                    Sentry.captureException(ExceptionMechanismException(mechanism, error, Thread.currentThread())) { scope ->
                        scope.level = SentryLevel.FATAL
                    }
                } else {
                    record(error)
                }
            }
            if (started) Sentry.flush(FLUSH_MILLIS)
        }
    }

    /**
     * A failure the app caught and carried on from (HX10): a background job that threw
     * ([com.tmplayer.platform.JobFailures]). Sent as an error only with the switch on; with it
     * off nothing is written, since nothing stopped. The same gate and limits as a crash.
     */
    fun handled(error: Throwable) {
        if (!available || !enabledNow || !started) return
        runCatching { Sentry.captureException(error) { scope -> scope.level = SentryLevel.ERROR } }
    }

    /**
     * A screen that failed while it was drawn or used, which the window put away behind its error
     * card instead of closing (HX10, [com.tmplayer.desktop.ui.WindowRecovery]). It is a crash in
     * all but name, so it goes where an uncaught one would: sent with the switch on, and with it
     * off written down and asked about at once, since the window is still there to ask.
     */
    fun contained(error: Throwable) {
        if (!available) return
        runCatching {
            if (enabledNow && started) {
                val mechanism = Mechanism().apply {
                    type = "WindowException"
                    isHandled = false
                }
                Sentry.captureException(ExceptionMechanismException(mechanism, error, Thread.currentThread())) { scope ->
                    scope.level = SentryLevel.FATAL
                }
            } else {
                record(error)
                _pending.value = true
            }
        }
    }

    /**
     * Turns reporting off and closes the SDK. The flag is cleared first, so a crash during the
     * close is dropped by the gate rather than sent by a half shut client.
     */
    @Synchronized
    fun stop() {
        enabledNow = false
        if (!started) return
        runCatching { Sentry.close() }
        started = false
        Logger.i(TAG, "Crash reporting is off.")
    }

    /**
     * The development builds' crash triggers, never set by a packaged release: with
     * `-Dtmplayer.crashAtStart=true` the launch fails before the window, and with
     * `-Dtmplayer.crashAfterWindow=true` the window's thread throws five seconds after the first
     * frame. Each is an ordinary uncaught exception to everything above.
     */
    fun crashAtStartIfAsked() {
        if (System.getProperty("tmplayer.crashAtStart") == "true") {
            throw IllegalStateException("Test crash at start (-Dtmplayer.crashAtStart)")
        }
    }

    fun crashAfterWindowIfAsked() {
        if (System.getProperty("tmplayer.crashAfterWindow") != "true") return
        Thread {
            Thread.sleep(5_000)
            java.awt.EventQueue.invokeLater {
                throw IllegalStateException("Test crash after the window (-Dtmplayer.crashAfterWindow)")
            }
        }.apply { isDaemon = true }.start()
    }

    /**
     * The phone's version code for the same version, `x.y.z` as x * 10000 + y * 100 + z, so the
     * two read alike in Sentry. A development build (`1.0.0-dev`) gets its x.y.z's.
     */
    val VERSION_CODE: Long = versionCode(BuildInfo.VERSION)

    internal fun versionCode(version: String): Long {
        val parts = version.substringBefore('-').split('.').map { it.toLongOrNull() ?: 0L }
        return parts.getOrElse(0) { 0L } * 10_000 + parts.getOrElse(1) { 0L } * 100 + parts.getOrElse(2) { 0L }
    }

    private const val FLUSH_MILLIS = 5_000L

    /** How long the settings store is given at start before the switch counts as off. */
    private const val READ_MILLIS = 3_000L

    private const val TAG = "CrashReports"
}
