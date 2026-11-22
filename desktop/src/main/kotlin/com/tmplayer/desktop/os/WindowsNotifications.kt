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
    private val onOpenDownloads: () -> Unit = {},
    /** Whether a result's press must ask its profile first ([PressGate]): 2+ profiles or a PIN. */
    private val gatePresses: () -> Boolean = PressGate::neededNow,
    /** Starts the PowerShell host; a test hands a fake one, so no real PowerShell ever starts there. */
    private val launcher: ToastHostLauncher = PowerShellHostLauncher(),
) : TransferNotifier, DockProgress, DesktopNotices, KeyedResults {

    private val worker: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "tmplayer-toast").apply { isDaemon = true }
    }

    // Touched only on the worker thread.
    private var shell: ToastHost? = null
    private var input: Writer? = null
    private var toastsOff = false
    private val sequence = mutableMapOf<Long, Long>()

    // Whether `tmplayer:` links reach this app, so a toast can carry a press (see [ToastPresses]).
    @Volatile
    private var pressable = false

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
        val launch = WindowsToast.launchUri(open)
            ?: if (open == OpenTarget.DownloadsScreen && canPress()) ToastPresses.register(onOpenDownloads) else null
        run(WindowsToast.showDone(WindowsToast.doneXml(title, body, launch)))
    }

    override fun fail(id: Long, title: String, reason: String, retryable: Boolean) = post {
        forget(id)
        run(WindowsToast.showDone(WindowsToast.doneXml(title, reason, null)))
    }

    override fun cancel(id: Long) = post { forget(id) }

    /**
     * A result kept under [key] (HX88, review P2): tagged, so [dismiss] takes it out of the Action
     * Center. Where `tmplayer:` links reach the app, the press goes through such a link and
     * [mayOpen] is asked at the press, before a file or folder opens (the profile count or a PIN
     * can change after the toast is posted). Only where no link can be registered (a development
     * run) and no gate is needed ([gatePresses]) is the file's own URI the press; where a gate is
     * needed and no link exists, the toast has no press.
     */
    override fun result(key: Long, id: Long, title: String, body: String, open: OpenTarget?, failed: Boolean, mayOpen: () -> Boolean) = post {
        forget(id)
        val launch = WindowsToast.resultLaunch(
            open = open,
            failed = failed,
            pressable = canPress(),
            gated = runCatching(gatePresses).getOrDefault(true),
            mayOpen = mayOpen,
            openTarget = ::openTarget,
            onOpenDownloads = onOpenDownloads,
            register = ToastPresses::register,
        )
        run(WindowsToast.showDone(WindowsToast.doneXml(title, body, launch), tag = keyTag(key)))
    }

    override fun keyedNotice(key: Long, title: String, body: String?, onPress: (() -> Unit)?, buttons: List<NoticeButton>) = post {
        if (toastsOff) return@post
        if (input == null) start()
        val canPress = canPress()
        val launch = if (onPress != null && canPress) ToastPresses.register(onPress) else null
        val actions = if (canPress) buttons.map { it.label to ToastPresses.register(it.onPress) } else emptyList()
        run(WindowsToast.showDone(WindowsToast.doneXml(title, body.orEmpty(), launch, actions), tag = keyTag(key)))
    }

    override fun dismiss(key: Long) = post { run(WindowsToast.remove(keyTag(key))) }

    /**
     * Whether a toast can carry a press. [pressable] is only known once the host has started
     * ([start] registers the scheme), so it is started here first: read before, it would say no
     * for the first toast of a run. False while toasts are off.
     */
    private fun canPress(): Boolean {
        if (toastsOff) return false
        if (input == null) start()
        return !toastsOff && pressable
    }

    /** Waits until every call made so far has run, for tests. */
    internal fun flush() {
        runCatching { worker.submit {}.get(FLUSH_WAIT_MS, java.util.concurrent.TimeUnit.MILLISECONDS) }
    }

    private fun openTarget(open: OpenTarget) {
        when (open) {
            is OpenTarget.File -> OpenExternal.reveal(java.io.File(open.path))
            is OpenTarget.Folder -> OpenExternal.open(java.io.File(open.path))
            OpenTarget.DownloadsScreen -> runCatching(onOpenDownloads)
        }
    }

    /**
     * A one-off notice. Its press reaches the app through a `tmplayer:` link ([ToastPresses]); where
     * the link could not be registered the toast only informs.
     */
    override fun notice(title: String, body: String?, onPress: (() -> Unit)?, buttons: List<NoticeButton>) = post {
        if (toastsOff) return@post
        if (input == null) start()
        val canPress = canPress()
        val launch = if (onPress != null && canPress) ToastPresses.register(onPress) else null
        val actions = if (canPress) buttons.map { it.label to ToastPresses.register(it.onPress) } else emptyList()
        run(WindowsToast.showDone(WindowsToast.doneXml(title, body.orEmpty(), launch, actions)))
    }

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

    private fun keyTag(key: Long) = "r$key"

    private fun post(block: () -> Unit) {
        runCatching {
            worker.execute { runCatching(block).onFailure { Logger.w(TAG, "toast failed: ${it.message}") } }
        }
    }

    /** Sends one line of script to the PowerShell host, starting it on first use. */
    private fun run(line: String) {
        if (toastsOff) return
        val writer = input ?: start() ?: return
        val alive = shell?.isAlive() == true
        if (!alive || runCatching {
                writer.write(line)
                writer.write("\r\n")
                writer.flush()
            }.isFailure
        ) {
            Logger.w(TAG, "the toast host stopped; toasts are off for this session")
            toastsOff = true
            runCatching { shell?.stop() }
        }
    }

    private fun start(): Writer? {
        val host = runCatching { launcher.launch() }
            .onFailure { Logger.w(TAG, "no PowerShell for toasts: ${it.message}") }
            .getOrNull()
        if (host == null) {
            toastsOff = true
            return null
        }
        pressable = host.pressable
        return runCatching {
            shell = host
            val w = host.input
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
            runCatching { host.stop() }
        }.getOrNull()
    }

    private companion object {
        const val TAG = "WindowsNotifications"
        const val FLUSH_WAIT_MS = 5_000L
    }
}

/** A running toast host: the stream script lines go to, and whether `tmplayer:` links reach the app. */
internal class ToastHost(
    val input: Writer,
    val isAlive: () -> Boolean,
    val stop: () -> Unit,
    val pressable: Boolean,
)

/** Starts the toast host; null (or a throw) means no toasts for the session. */
internal fun interface ToastHostLauncher {
    fun launch(): ToastHost?
}

/** The real host: one hidden `powershell.exe`, after the app id and the link scheme are registered. */
internal class PowerShellHostLauncher : ToastHostLauncher {
    override fun launch(): ToastHost? {
        registerAppId()
        val pressable = registerScheme()
        val p = ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-NoLogo", "-Command", "-")
            .redirectErrorStream(true)
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .start()
        return ToastHost(p.outputStream.bufferedWriter(Charsets.UTF_8), { p.isAlive }, { p.destroy() }, pressable)
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

    /**
     * `HKCU\Software\Classes\tmplayer`, a link scheme that starts this copy of TMPlayer.exe with
     * the link, so a toast's press reaches the running app ([ToastPresses]). Only for the app's own
     * launcher: a development run under java.exe registers nothing and its toasts only inform.
     */
    private fun registerScheme(): Boolean = runCatching {
        val exe = ProcessHandle.current().info().command().orElse(null)?.let(::File)
            ?.takeIf { it.name.equals("TMPlayer.exe", ignoreCase = true) && it.isFile } ?: return false
        val key = "Software\\Classes\\${ToastPresses.SCHEME}"
        val command = "$key\\shell\\open\\command"
        Advapi32Util.registryCreateKey(WinReg.HKEY_CURRENT_USER, command)
        Advapi32Util.registrySetStringValue(WinReg.HKEY_CURRENT_USER, key, "", "URL:TMPlayer")
        Advapi32Util.registrySetStringValue(WinReg.HKEY_CURRENT_USER, key, "URL Protocol", "")
        Advapi32Util.registrySetStringValue(WinReg.HKEY_CURRENT_USER, command, "", "\"${exe.absolutePath}\" \"%1\"")
        true
    }.onFailure { Logger.w(TAG, "could not register the tmplayer: links: ${it.message}") }.getOrDefault(false)

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
    fun doneXml(title: String, body: String, launch: String?, buttons: List<Pair<String, String>> = emptyList()): String {
        val activation = launch?.let { " activationType=\"protocol\" launch=\"${xml(it)}\"" }.orEmpty() // i18n-ok: toast XML
        // Each button opens its own tmplayer: link, as a press on the toast does.
        val actions = if (buttons.isEmpty()) {
            ""
        } else {
            "<actions>" + buttons.joinToString("") { (label, link) ->
                "<action content=\"${xml(label)}\" activationType=\"protocol\" arguments=\"${xml(link)}\"/>" // i18n-ok: toast XML
            } + "</actions>"
        }
        return "<toast$activation><visual><binding template=\"ToastGeneric\">" +
            "<text>${xml(title)}</text>" +
            (if (body.isNotEmpty()) "<text>${xml(body)}</text>" else "") +
            "</binding></visual>$actions</toast>"
    }

    /**
     * Where pressing a completion goes. A file or folder opens through its `file:` URI, which
     * Explorer handles with no activator of this app's own. The Downloads page is reached through
     * a `tmplayer:` link instead ([ToastPresses]), added by the notifier where it is registered.
     */
    fun launchUri(open: OpenTarget?): String? = when (open) {
        is OpenTarget.File -> File(open.path).parentFile?.toURI()?.toString()
        is OpenTarget.Folder -> File(open.path).toURI().toString()
        OpenTarget.DownloadsScreen, null -> null
    }

    /**
     * The launch link of a result's toast. A file or folder goes through an in-memory `tmplayer:`
     * link whenever [pressable]: it asks [mayOpen] first, so a toast posted while there was one
     * profile and no PIN cannot open under another profile or while locked after it is added or
     * set (the gate is read at the press, not at the post). Without that scheme the plain `file:`
     * URI is used only where no gate is [gated] (2+ profiles or a PIN); a gated result then has no
     * link, so the toast is not pressable. A failure has no press. [pressable] must be read after
     * the host started.
     */
    fun resultLaunch(
        open: OpenTarget?,
        failed: Boolean,
        pressable: Boolean,
        gated: Boolean,
        mayOpen: () -> Boolean,
        openTarget: (OpenTarget) -> Unit,
        onOpenDownloads: () -> Unit,
        register: (() -> Unit) -> String,
    ): String? = when {
        failed -> null
        open is OpenTarget.File || open is OpenTarget.Folder -> when {
            pressable -> register {
                if (runCatching(mayOpen).getOrDefault(false)) openTarget(open)
            }
            gated -> null
            else -> launchUri(open)
        }
        open == OpenTarget.DownloadsScreen && pressable -> register(onOpenDownloads)
        else -> null
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

    /** A finished toast; with a [tag] it can be taken out of the Action Center later ([remove]). */
    fun showDone(xml: String, tag: String? = null): String = if (tag == null) {
        "\$x = New-Object Windows.Data.Xml.Dom.XmlDocument; \$x.LoadXml(${ps(xml)}); " +
            "\$n.Show([Windows.UI.Notifications.ToastNotification]::new(\$x))"
    } else {
        "\$x = New-Object Windows.Data.Xml.Dom.XmlDocument; \$x.LoadXml(${ps(xml)}); " +
            "\$t = [Windows.UI.Notifications.ToastNotification]::new(\$x); " +
            "\$t.Tag = ${ps(tag)}; \$t.Group = ${ps(GROUP)}; \$n.Show(\$t)"
    }

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
