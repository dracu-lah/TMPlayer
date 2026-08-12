package com.tmplayer.data

import android.content.Context
import android.util.Log
import com.tmplayer.BuildConfig
import io.sentry.Sentry
import io.sentry.SentryLevel
import io.sentry.android.core.SentryAndroid

/**
 * Crash reporting, off unless the viewer asks for it.
 *
 * The whole pitch of this app is that nothing passes through anything of mine: no server, no bot,
 * no analytics. That claim is worth more than a usage graph, so this is the one exception and it
 * is a narrow one. Nothing here runs until [start] is called, [start] does nothing unless the
 * switch in Settings is on, and the switch is off on a fresh install. Sentry's own auto-start
 * ContentProvider is disabled in the manifest, so there is no other way in.
 *
 * What a report contains: the stack trace, the app version, the Android version and the device
 * model. What it does not: breadcrumbs of any kind, sessions, screen names, filenames, chat names,
 * or anything Sentry calls personally identifiable. [maxBreadcrumbs] is zero and every automatic
 * breadcrumb source is off, which is the part that matters here, because a breadcrumb is exactly
 * where the name of the film somebody was watching would otherwise turn up.
 *
 * With no [BuildConfig.SENTRY_DSN] compiled in, which is what a fork and every CI build get, this
 * is inert and Settings does not offer the switch at all.
 */
object CrashReports {

    /** Whether this build has anywhere to send a report. Nothing is offered to the viewer if not. */
    val available: Boolean = BuildConfig.SENTRY_DSN.isNotBlank()

    private var started = false

    /**
     * Brings the SDK up, once, if this build has a DSN and [enabled] is true.
     *
     * Called from [com.tmplayer.App] after reading the setting, and again when the viewer turns
     * the switch on. Turning it off calls [stop].
     */
    @Synchronized
    fun start(context: Context, enabled: Boolean) {
        if (!available || !enabled || started) return

        SentryAndroid.init(context.applicationContext) { options ->
            options.dsn = BuildConfig.SENTRY_DSN
            options.release = "tmplayer@${BuildConfig.VERSION_NAME}"
            options.environment = if (BuildConfig.DEBUG) "debug" else "release"

            // Crashes and application-not-responding, and nothing else. Performance tracing sends
            // a sample of ordinary, healthy operations, which is usage data by another name.
            options.tracesSampleRate = 0.0
            options.isEnableAutoSessionTracking = false

            // No trail of what the viewer was doing before the crash. A breadcrumb is written by
            // every activity change, system event and network call, and any of them can carry the
            // name of a chat or a file. The stack trace says where the bug is; the rest is theirs.
            options.maxBreadcrumbs = 0
            options.isEnableActivityLifecycleBreadcrumbs = false
            options.isEnableAppComponentBreadcrumbs = false
            options.isEnableSystemEventBreadcrumbs = false
            options.isEnableAppLifecycleBreadcrumbs = false
            options.isEnableUserInteractionBreadcrumbs = false
            options.isEnableNetworkEventBreadcrumbs = false

            // No IP address and no device name.
            options.isSendDefaultPii = false
            options.isAttachThreads = true
            options.isAttachStacktrace = true

            // Two gates, in code rather than in configuration. If the switch went off between the
            // crash and the send, the report is dropped on the floor. And the user object goes,
            // which is where Sentry otherwise puts the installation identifier that would
            // otherwise tie one person's reports together across weeks.
            options.setBeforeSend { event, _ ->
                if (!enabledNow) {
                    null
                } else {
                    event.user = null
                    event
                }
            }
            options.setDiagnosticLevel(SentryLevel.ERROR)
        }

        started = true
        enabledNow = true
        Log.i(TAG, "Crash reporting is on, at the viewer's request.")
    }

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

    @Volatile
    private var enabledNow = false

    private const val TAG = "CrashReports"
}
