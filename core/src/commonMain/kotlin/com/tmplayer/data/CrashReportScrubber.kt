package com.tmplayer.data

import com.tmplayer.online.SubMakerSecret
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
 * model, or on a computer the operating system and its version with no device at all. The SDK
 * fills in a great deal besides: timezone, locale, battery, memory, storage, screen, connection
 * type, boot time, permissions, an installation identifier, and on a computer the host name and
 * the Java runtime. Rather than chase each of those by name, and miss whatever the next SDK version
 * adds, the device, OS and app contexts are rebuilt from scratch with only the allowed fields, and
 * every other context, tag and extra goes.
 *
 * Text that survives (exception messages, stack frame variables) has file paths and anything that
 * looks like a video file name replaced, since that is where the name of what somebody was
 * watching would otherwise turn up. A Windows path goes too: the user's folder name is in it.
 *
 * Shared by the Android app and the desktop, which pass [keepDevice] false.
 */
object CrashReportScrubber {

    /**
     * [keepDevice] keeps the device's maker and model, which is what tells one phone or television
     * from another and what the privacy page lists for them. A computer's report carries none.
     */
    fun scrub(event: SentryEvent, keepDevice: Boolean = true): SentryEvent {
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

        scrubContexts(event.contexts, keepDevice)

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

    private fun scrubContexts(contexts: Contexts, keepDevice: Boolean) {
        val old = Triple(contexts.device, contexts.operatingSystem, contexts.app)
        contexts.keys().toList().forEach { contexts.remove(it) }

        old.first?.takeIf { keepDevice }?.let { device ->
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
            // Sentry 8.61 types a frame's locals as objects rather than strings; each is read as
            // its text, since that text is what would be sent.
            frame.vars = frame.vars?.mapValues { (_, value) -> value?.let { redact(it.toString()) } }
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

    /**
     * An absolute path of two or more segments. Class names (`Lcom/x/Y`) start with a letter. A
     * folder's name may have spaces in it (`/Users/Jane Doe/Movies/`, as a Mac's home folder
     * often does), since it ends at a slash; the last segment ends at the first space.
     */
    private val PATH = Regex("""(?<![\w.])/(?:[^/"'\n:;()\[\]]+/)+[^\s"':,;()\[\]]*""")

    /**
     * A Windows path, from a drive (`C:\Users\...`, `D:/Videos/...`) or a share (`\\server\...`),
     * with spaces allowed in its folders as above. The user's folder name is in most of them.
     */
    private val WINDOWS_PATH = Regex(
        """(?<![\w.])(?:[A-Za-z]:[\\/]|\\\\[^\s\\"']+\\)(?:[^\\/"'\n:;()\[\]]+[\\/])*[^\s"':,;()\[\]]*""",
    )

    fun redact(text: String): String = SubMakerSecret.scrub(text)
        .replace(QUOTED_VIDEO, "[file]")
        .replace(WINDOWS_PATH, "[path]")
        .replace(PATH, "[path]")
        .replace(VIDEO_TOKEN, "[file]")
}
