package com.tmplayer.data

import io.sentry.SentryEvent
import io.sentry.protocol.App
import io.sentry.protocol.Contexts
import io.sentry.protocol.Device
import io.sentry.protocol.OperatingSystem
import io.sentry.protocol.SentryStackTrace

/**
 * Cuts a crash report down to what the privacy page promises, and nothing more.
 *
 * The page says a report is the stack trace, the app version, the Android version and the device
 * model. The SDK fills in a great deal besides: timezone, locale, battery, memory, storage, screen,
 * connection type, boot time, permissions, an installation identifier. Rather than chase each of
 * those by name, and miss whatever the next SDK version adds, the device, OS and app contexts are
 * rebuilt from scratch with only the allowed fields, and every other context, tag and extra goes.
 *
 * Text that survives (exception messages, stack frame variables) has file paths and anything that
 * looks like a video file name replaced, since that is where the name of what somebody was
 * watching would otherwise turn up.
 */
internal object CrashReportScrubber {

    fun scrub(event: SentryEvent): SentryEvent {
        event.user = null
        event.serverName = null
        event.request = null
        event.transaction = null
        event.breadcrumbs = null
        event.tags = null
        event.extras = null
        event.setModules(null)
        event.unknown = null
        event.message?.let { message ->
            message.formatted = message.formatted?.let(::redact)
            message.message = message.message?.let(::redact)
            message.params = null
        }

        scrubContexts(event.contexts)

        event.exceptions?.forEach { exception ->
            exception.value = exception.value?.let(::redact)
            exception.unknown = null
            exception.mechanism?.data = null
            scrubStackTrace(exception.stacktrace)
        }
        event.threads?.forEach { thread ->
            thread.unknown = null
            scrubStackTrace(thread.stacktrace)
        }
        return event
    }

    /** The only contexts a report may carry, by their Sentry keys. */
    val ALLOWED_CONTEXTS = setOf(Device.TYPE, OperatingSystem.TYPE, App.TYPE)

    private fun scrubContexts(contexts: Contexts) {
        val old = Triple(contexts.device, contexts.operatingSystem, contexts.app)
        contexts.keys().toList().forEach { contexts.remove(it) }

        old.first?.let { device ->
            contexts.setDevice(Device().apply {
                manufacturer = device.manufacturer
                model = device.model
            })
        }
        old.second?.let { os ->
            contexts.setOperatingSystem(OperatingSystem().apply {
                name = os.name
                version = os.version
            })
        }
        old.third?.let { app ->
            contexts.setApp(App().apply {
                appVersion = app.appVersion
                appBuild = app.appBuild
            })
        }
    }

    private fun scrubStackTrace(trace: SentryStackTrace?) {
        trace ?: return
        trace.registers = null
        trace.unknown = null
        trace.frames?.forEach { frame ->
            frame.vars = frame.vars?.mapValues { (_, value) -> redact(value) }
            frame.absPath = null
            frame.preContext = null
            frame.postContext = null
            frame.contextLine = null
            frame.unknown = null
        }
    }

    private const val VIDEO = "mkv|mp4|m4v|avi|mov|webm|ts|m2ts|mts|flv|wmv|mpg|mpeg|3gp|ogv|vob|rmvb"

    /** A quoted string with a video file name in it: the whole quote goes, spaces and all. */
    private val QUOTED_VIDEO = Regex("""(["'])[^"'\n]*\.(?:$VIDEO)\1""", RegexOption.IGNORE_CASE)

    /** An unquoted token ending in a video extension. */
    private val VIDEO_TOKEN = Regex("""[^\s"'/\\:]+\.(?:$VIDEO)\b""", RegexOption.IGNORE_CASE)

    /** An absolute path of two or more segments. Class names (`Lcom/x/Y`) start with a letter. */
    private val PATH = Regex("""(?<![\w.])/[^\s"':,;()\[\]]+/[^\s"':,;()\[\]]*""")

    fun redact(text: String): String = text
        .replace(QUOTED_VIDEO, "[file]")
        .replace(PATH, "[path]")
        .replace(VIDEO_TOKEN, "[file]")
}
