package com.tmplayer.desktop.os

import com.sun.jna.platform.win32.Advapi32Util
import com.sun.jna.platform.win32.WinReg
import com.tmplayer.i18n.L
import com.tmplayer.platform.Logger
import com.tmplayer.platform.TransferNotifier
import com.tmplayer.platform.TransferNotifier.Capability
import com.tmplayer.platform.TransferNotifier.Kind
import com.tmplayer.platform.TransferNotifier.OpenTarget
import java.awt.EventQueue
import java.awt.Taskbar
import java.awt.Window
import java.io.File
import java.io.Writer
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Windows: progress on the taskbar button, and toasts in the Action Center.
 *
 * The taskbar bar is `java.awt.Taskbar`, which is in the JDK, needs no registration and works from
 * the portable zip as well as the MSI. It is the floor, and always on where the JDK supports it.
 *
 * Toasts are WinRT's `ToastNotificationManager`, driven from one hidden `powershell.exe` kept for
 * the life of the app and fed one line of script per call over its standard input: starting a
 * PowerShell per update would cost a second each. A transfer's progress is one toast with a
 * `<progress>` element bound to `NotificationData`, updated in place through `ToastNotifier.Update`
 * with a rising sequence number; completion and failure are new toasts, and the progress one is
 * taken out of the Action Center as they appear.
 *
 * A toast from an app that is not packaged needs an AppUserModelID that Windows knows. jpackage's
 * MSI shortcut does not carry one and the portable zip has no shortcut at all, so the id is
 * registered under `HKCU\Software\Classes\AppUserModelId`, the way the Windows Community Toolkit
 * does it. Should any of this fail (no PowerShell, a policy blocking it, an old Windows), toasts
 * are off for the session and the taskbar keeps going. Nothing here ever throws.
 */
internal class WindowsNotifications(
    private val window: () -> Window?,
) : TransferNotifier, DockProgress {

    private val worker: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "tmplayer-toast").apply { isDaemon = true }
    }

    // Touched only on the worker thread.
    private var shell: Process? = null
    private var input: Writer? = null
    private var toastsOff = false
    private val sequence = mutableMapOf<Long, Long>()

    @Volatile
    private var lastTaskbar: Int? = null

    private val taskbar: Taskbar? by lazy {
        runCatching {
            if (!Taskbar.isTaskbarSupported()) return@runCatching null
            Taskbar.getTaskbar().takeIf {
                it.isSupported(Taskbar.Feature.PROGRESS_VALUE_WINDOW) && it.isSupported(Taskbar.Feature.PROGRESS_STATE_WINDOW)
            }
        }.getOrNull()
    }

    override val capabilities: Set<Capability>
        get() = buildSet {
            if (taskbar != null) add(Capability.DockProgress)
            if (!toastsOff) {
                add(Capability.ProgressBar)
                add(Capability.InPlaceUpdate)
            }
        }

    override fun begin(id: Long, kind: Kind, title: String) = post {
        sequence[id] = 1
        run(
            WindowsToast.showProgress(
                tag = tag(id),
                xml = WindowsToast.progressXml(LinuxNotificationText.summary(kind, title)),
                status = WindowsToast.statusOf(kind),
                value = 0.0,
                valueText = "",
            ),
        )
    }

    override fun progress(id: Long, done: Long, total: Long?, bytesPerSecond: Long?) = post {
        val next = (sequence[id] ?: return@post) + 1
        sequence[id] = next
        val fraction = if (total != null && total > 0) done.coerceIn(0, total).toDouble() / total else null
        run(
            WindowsToast.updateProgress(
                tag = tag(id),
                sequence = next,
                // An indeterminate bar for an unknown size: WinRT's own word for it.
                value = fraction,
                valueText = LinuxNotificationText.progressBody(done, total, bytesPerSecond),
            ),
        )
    }

    override fun complete(id: Long, title: String, body: String, open: OpenTarget?) = post {
        forget(id)
        run(WindowsToast.showDone(WindowsToast.doneXml(title, body, WindowsToast.launchUri(open))))
    }

    override fun fail(id: Long, title: String, reason: String, retryable: Boolean) = post {
        forget(id)
        run(WindowsToast.showDone(WindowsToast.doneXml(title, reason, null)))
    }

    override fun cancel(id: Long) = post { forget(id) }

    private fun forget(id: Long) {
        if (sequence.remove(id) != null) run(WindowsToast.remove(tag(id)))
    }

    override fun dockProgress(fraction: Float?) {
        val bar = taskbar ?: return
        val percent = fraction?.let { (it.coerceIn(0f, 1f) * 100).toInt() }
        if (percent == lastTaskbar) return
        lastTaskbar = percent
        EventQueue.invokeLater {
            val w = window() ?: return@invokeLater
            runCatching {
                if (percent == null) {
                    bar.setWindowProgressState(w, Taskbar.State.OFF)
                } else {
                    bar.setWindowProgressState(w, Taskbar.State.NORMAL)
                    bar.setWindowProgressValue(w, percent)
                }
            }.onFailure { Logger.w(TAG, "taskbar progress failed: ${it.message}") }
        }
    }

    private fun tag(id: Long) = "t$id"

    private fun post(block: () -> Unit) {
        runCatching {
            worker.execute { runCatching(block).onFailure { Logger.w(TAG, "toast failed: ${it.message}") } }
        }
    }

    /** Sends one line of script to the PowerShell host, starting it on first use. */
    private fun run(line: String) {
        if (toastsOff) return
        val writer = input ?: start() ?: return
        val alive = shell?.isAlive == true
        if (!alive || runCatching {
                writer.write(line)
                writer.write("\r\n")
                writer.flush()
            }.isFailure
        ) {
            Logger.w(TAG, "the toast host stopped; toasts are off for this session")
            toastsOff = true
            runCatching { shell?.destroy() }
        }
    }

    private fun start(): Writer? {
        registerAppId()
        return runCatching {
            val p = ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-NoLogo", "-Command", "-")
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .start()
            shell = p
            val w = p.outputStream.bufferedWriter(Charsets.UTF_8)
            input = w
            WindowsToast.preamble().forEach { line ->
                w.write(line)
                w.write("\r\n")
            }
            w.flush()
            w
        }.onFailure {
            Logger.w(TAG, "no PowerShell for toasts: ${it.message}")
            toastsOff = true
        }.getOrNull()
    }

    /** `HKCU\Software\Classes\AppUserModelId\TMPlayer.Desktop`, so Windows shows this app's toasts. */
    private fun registerAppId() {
        runCatching {
            val key = "Software\\Classes\\AppUserModelId\\${WindowsToast.APP_ID}"
            Advapi32Util.registryCreateKey(WinReg.HKEY_CURRENT_USER, key)
            Advapi32Util.registrySetStringValue(WinReg.HKEY_CURRENT_USER, key, "DisplayName", "TMPlayer")
            iconPath()?.let { Advapi32Util.registrySetStringValue(WinReg.HKEY_CURRENT_USER, key, "IconUri", it) }
        }.onFailure { Logger.w(TAG, "could not register the app id: ${it.message}") }
    }

    /** The icon jpackage puts beside `TMPlayer.exe`, where this is an installed copy. */
    private fun iconPath(): String? {
        val exe = ProcessHandle.current().info().command().orElse(null)?.let(::File) ?: return null
        val dir = exe.parentFile ?: return null
        return listOf("TMPlayer.ico", "tmplayer.ico", "TMPlayer.png").map { File(dir, it) }.firstOrNull { it.isFile }?.absolutePath
    }

    private companion object {
        const val TAG = "WindowsNotifications"
    }
}

/**
 * The toast XML and the PowerShell lines that show and update it, apart from the process so they
 * can be tested on any OS.
 */
internal object WindowsToast {
    const val APP_ID = "TMPlayer.Desktop"
    const val GROUP = "transfers"

    /** Loads the WinRT types once and makes `$n`, the notifier every later line uses. */
    fun preamble(): List<String> = listOf(
        "\$ErrorActionPreference = 'SilentlyContinue'",
        "[Windows.UI.Notifications.ToastNotificationManager, Windows.UI.Notifications, ContentType = WindowsRuntime] | Out-Null",
        "[Windows.UI.Notifications.NotificationData, Windows.UI.Notifications, ContentType = WindowsRuntime] | Out-Null",
        "[Windows.Data.Xml.Dom.XmlDocument, Windows.Data.Xml.Dom.XmlDocument, ContentType = WindowsRuntime] | Out-Null",
        "\$n = [Windows.UI.Notifications.ToastNotificationManager]::CreateToastNotifier(${ps(APP_ID)})",
    )

    fun statusOf(kind: Kind): String = when (kind) {
        Kind.Download -> L.downloadsDownloading
        Kind.MoveToDownloads -> L.downloadsMovingIn
        Kind.Relocate -> L.storageMovingDownloads
        Kind.Migrate -> L.storageMovingDownloads
    }

    /** A progress toast whose bar, figures and status are bound to the toast's data. */
    fun progressXml(title: String): String =
        "<toast><visual><binding template=\"ToastGeneric\">" +
            "<text>${xml(title)}</text>" +
            "<progress value=\"{value}\" valueStringOverride=\"{valueString}\" status=\"{status}\"/>" +
            "</binding></visual></toast>"

    /** A finished or failed transfer; [launch] is a file URI opened when it is pressed. */
    fun doneXml(title: String, body: String, launch: String?): String {
        val activation = launch?.let { " activationType=\"protocol\" launch=\"${xml(it)}\"" }.orEmpty() // i18n-ok: toast XML
        return "<toast$activation><visual><binding template=\"ToastGeneric\">" +
            "<text>${xml(title)}</text>" +
            (if (body.isNotEmpty()) "<text>${xml(body)}</text>" else "") +
            "</binding></visual></toast>"
    }

    /**
     * Where pressing a completion goes. A file or folder opens through its `file:` URI, which
     * Explorer handles with no activator of this app's own; the Downloads page cannot be reached
     * that way, so that toast only informs.
     */
    fun launchUri(open: OpenTarget?): String? = when (open) {
        is OpenTarget.File -> File(open.path).parentFile?.toURI()?.toString()
        is OpenTarget.Folder -> File(open.path).toURI().toString()
        OpenTarget.DownloadsScreen, null -> null
    }

    fun showProgress(tag: String, xml: String, status: String, value: Double, valueText: String): String =
        "\$x = New-Object Windows.Data.Xml.Dom.XmlDocument; \$x.LoadXml(${ps(xml)}); " +
            "\$t = [Windows.UI.Notifications.ToastNotification]::new(\$x); " +
            "\$t.Tag = ${ps(tag)}; \$t.Group = ${ps(GROUP)}; " +
            "\$d = [Windows.UI.Notifications.NotificationData]::new(); \$d.SequenceNumber = 1; " +
            "\$d.Values['value'] = ${ps(number(value))}; \$d.Values['valueString'] = ${ps(valueText)}; " +
            "\$d.Values['status'] = ${ps(status)}; \$t.Data = \$d; \$n.Show(\$t)"

    /** [value] null draws the bar as indeterminate. */
    fun updateProgress(tag: String, sequence: Long, value: Double?, valueText: String): String =
        "\$d = [Windows.UI.Notifications.NotificationData]::new(); \$d.SequenceNumber = $sequence; " +
            "\$d.Values['value'] = ${ps(value?.let(::number) ?: "indeterminate")}; " +
            "\$d.Values['valueString'] = ${ps(valueText)}; " +
            "[void]\$n.Update(\$d, ${ps(tag)}, ${ps(GROUP)})"

    fun showDone(xml: String): String =
        "\$x = New-Object Windows.Data.Xml.Dom.XmlDocument; \$x.LoadXml(${ps(xml)}); " +
            "\$n.Show([Windows.UI.Notifications.ToastNotification]::new(\$x))"

    fun remove(tag: String): String =
        "[Windows.UI.Notifications.ToastNotificationManager]::History.Remove(${ps(tag)}, ${ps(GROUP)}, ${ps(APP_ID)})"

    /** Invariant, so a German Windows does not get "0,42". */
    private fun number(value: Double): String = String.format(java.util.Locale.ROOT, "%.3f", value.coerceIn(0.0, 1.0))

    /** XML text or attribute content. */
    fun xml(text: String): String = buildString {
        for (c in text) {
            when (c) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '"' -> append("&quot;")
                '\'' -> append("&apos;")
                else -> if (c.code < 0x20 && c != '\t') append(' ') else append(c)
            }
        }
    }

    /**
     * A single quoted PowerShell string. A quote inside is doubled, and that includes the curly
     * ones, which PowerShell also reads as quotes; a line break would end the command early, since
     * the host is fed one line at a time, so it becomes a space.
     */
    fun ps(text: String): String = buildString {
        append('\'')
        for (c in text) {
            when (c) {
                '\'', '‘', '’', '‚', '‛' -> append(c).append(c)
                '\r', '\n' -> append(' ')
                else -> append(c)
            }
        }
        append('\'')
    }
}
