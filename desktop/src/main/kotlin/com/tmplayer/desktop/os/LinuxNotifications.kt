package com.tmplayer.desktop.os

import com.tmplayer.platform.Logger
import com.tmplayer.platform.TransferNotifier
import com.tmplayer.platform.TransferNotifier.Capability
import com.tmplayer.platform.TransferNotifier.Kind
import com.tmplayer.platform.TransferNotifier.OpenTarget
import com.tmplayer.player.StreamStats
import org.freedesktop.dbus.annotations.DBusInterfaceName
import org.freedesktop.dbus.connections.impl.DBusConnection
import org.freedesktop.dbus.connections.impl.DBusConnectionBuilder
import org.freedesktop.dbus.interfaces.DBusInterface
import org.freedesktop.dbus.messages.DBusSignal
import org.freedesktop.dbus.types.UInt32
import org.freedesktop.dbus.types.Variant
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Something that can draw one progress figure on the app's own icon: the dock on Linux, the
 * taskbar button on Windows. Fed the byte weighted progress of every transfer in flight, or null
 * when nothing is moving and the bar should go.
 */
internal interface DockProgress {
    fun dockProgress(fraction: Float?)
}

/**
 * Linux: transfer progress and completion as desktop notifications, through the session bus's
 * `org.freedesktop.Notifications`, and a bar on the dock icon through Unity's LauncherEntry signal.
 *
 * Progress is one notification per transfer, updated in place with `replaces_id`, with the
 * percentage in the `value` hint (dunst and mako draw it as a bar; GNOME and KDE ignore it, so the
 * body says it in words too). Completion and failure are new notifications, so they pop even when
 * the viewer dismissed the progress one, which is closed at the same moment. Whether the server
 * shows buttons is asked once with `GetCapabilities`; when it does, a finished download carries an
 * Open action, answered through the `ActionInvoked` signal.
 *
 * Without a session bus (a bare X session, some containers) it falls back to `notify-send` when
 * that is on `PATH`, and otherwise to nothing at all: the app's own toast and the Downloads count
 * on the side bar still say what happened.
 *
 * Every call is handed to one worker thread, so D-Bus round trips never block the caller and the
 * notifications arrive in the order they were asked for. Nothing here ever throws.
 */
internal class LinuxNotifications(
    private val onOpenDownloads: () -> Unit,
    private val connect: () -> DBusConnection = {
        DBusConnectionBuilder.forSessionBus().withShared(false).build()
    },
) : TransferNotifier, DockProgress {

    private val worker: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "tmplayer-notify").apply { isDaemon = true }
    }

    // Everything below is touched only on the worker thread.
    private var bus: DBusConnection? = null
    private var server: FdoNotifications? = null
    private var busTried = false
    private var serverActions = false
    private var notifySend: NotifySend? = null
    private var notifySendTried = false

    /** The transfer each progress notification belongs to, by the server's id for it. */
    private val progressIds = mutableMapOf<Long, UInt32>()

    /**
     * What pressing a completion notification opens, by the server's id; bounded, oldest out.
     * Synchronised, since the signal handler reading it runs on one of dbus-java's threads.
     */
    private val targets: MutableMap<Long, OpenTarget> = java.util.Collections.synchronizedMap(
        object : LinkedHashMap<Long, OpenTarget>() {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, OpenTarget>?) = size > MAX_TARGETS
        },
    )

    private val kinds = mutableMapOf<Long, Kind>()
    private var lastDock: Int? = null

    override val capabilities: Set<Capability>
        get() = runCatching {
            worker.submit<Set<Capability>> { capabilitiesNow() }.get(CAPABILITY_WAIT_MS, TimeUnit.MILLISECONDS)
        }.getOrDefault(emptySet())

    private fun capabilitiesNow(): Set<Capability> = buildSet {
        if (server() != null) {
            add(Capability.ProgressBar)
            add(Capability.InPlaceUpdate)
            add(Capability.DockProgress)
            if (serverActions) add(Capability.Actions)
        } else if (notifySend()?.canReplace == true) {
            add(Capability.ProgressBar)
            add(Capability.InPlaceUpdate)
        }
    }

    override fun begin(id: Long, kind: Kind, title: String) = post {
        kinds[id] = kind
        titles[id] = title
        show(id, LinuxNotificationText.summary(kind, title), LinuxNotificationText.progressBody(0, null, null), 0)
    }

    override fun progress(id: Long, done: Long, total: Long?, bytesPerSecond: Long?) = post {
        val kind = kinds[id] ?: Kind.Download
        val title = titles[id].orEmpty()
        show(
            id,
            LinuxNotificationText.summary(kind, title),
            LinuxNotificationText.progressBody(done, total, bytesPerSecond),
            LinuxNotificationText.percent(done, total),
        )
    }

    /** The title [begin] was given, for the summary of each later update. */
    private val titles = mutableMapOf<Long, String>()

    private fun show(id: Long, summary: String, body: String, percent: Int?) {
        val notifications = server()
        if (notifications != null) {
            val replaces = progressIds[id] ?: UInt32(0)
            runCatching {
                notifications.Notify(
                    APP_NAME, replaces, ICON, summary, body, emptyList(),
                    LinuxNotificationText.hints(LinuxNotificationText.CATEGORY_PROGRESS, urgency = URGENCY_LOW, percent = percent),
                    NEVER_EXPIRE,
                )
            }.onSuccess { progressIds[id] = it }
                .onFailure { Logger.w(TAG, "progress notification refused: ${it.message}") }
            return
        }
        val tool = notifySend() ?: return
        if (!tool.canReplace) return
        tool.send(
            summary, body, LinuxNotificationText.CATEGORY_PROGRESS, "low", percent,
            replaces = progressIds[id]?.toLong(),
        )?.let { progressIds[id] = UInt32(it) }
    }

    override fun complete(id: Long, title: String, body: String, open: OpenTarget?) = post {
        finish(id, title, body, LinuxNotificationText.CATEGORY_COMPLETE, URGENCY_NORMAL, open)
    }

    override fun fail(id: Long, title: String, reason: String, retryable: Boolean) = post {
        finish(id, title, reason, LinuxNotificationText.CATEGORY_ERROR, URGENCY_NORMAL, null)
    }

    override fun cancel(id: Long) = post { closeProgress(id) }

    private fun finish(id: Long, title: String, body: String, category: String, urgency: Byte, open: OpenTarget?) {
        closeProgress(id)
        val notifications = server()
        if (notifications != null) {
            val actions = if (open != null && serverActions) LinuxNotificationText.actions(open) else emptyList()
            runCatching {
                // replaces_id 0: a new notification, never the progress one updated.
                notifications.Notify(
                    APP_NAME, UInt32(0), ICON, title, body, actions,
                    LinuxNotificationText.hints(category, urgency = urgency, percent = null),
                    DEFAULT_EXPIRY,
                )
            }.onSuccess { serverId -> if (open != null) targets[serverId.toLong()] = open }
                .onFailure { Logger.w(TAG, "completion notification refused: ${it.message}") }
            return
        }
        notifySend()?.send(title, body, category, "normal", null, replaces = null)
    }

    private fun closeProgress(id: Long) {
        kinds.remove(id)
        titles.remove(id)
        val serverId = progressIds.remove(id) ?: return
        val notifications = server()
        if (notifications != null) {
            runCatching { notifications.CloseNotification(serverId) }
        } else {
            // notify-send cannot close one; replacing it with a short lived empty one is the nearest.
            notifySend()?.takeIf { it.canReplace }?.expire(serverId.toLong())
        }
    }

    override fun dockProgress(fraction: Float?) = post {
        val percent = fraction?.let { (it.coerceIn(0f, 1f) * 100).toInt() }
        if (percent == lastDock) return@post
        lastDock = percent
        val conn = bus() ?: return@post
        runCatching {
            conn.sendMessage(
                UnityLauncherEntry.Update(
                    LAUNCHER_PATH,
                    LinuxNotificationText.LAUNCHER_APP_URI,
                    LinuxNotificationText.launcherProperties(fraction),
                ),
            )
        }.onFailure { Logger.w(TAG, "dock progress signal failed: ${it.message}") }
    }

    private fun post(block: () -> Unit) {
        runCatching {
            worker.execute { runCatching(block).onFailure { Logger.w(TAG, "notification failed: ${it.message}") } }
        }
    }

    private fun bus(): DBusConnection? {
        if (!busTried) {
            busTried = true
            bus = runCatching { connect() }.onFailure { Logger.w(TAG, "no session bus: ${it.message}") }.getOrNull()
        }
        return bus
    }

    private fun server(): FdoNotifications? {
        if (server != null) return server
        val conn = bus() ?: return null
        if (serverTried) return null
        serverTried = true
        server = runCatching {
            conn.getRemoteObject(SERVICE, OBJECT_PATH, FdoNotifications::class.java).also { remote ->
                // Asked first, and a failure means no server: a proxy is made whether or not
                // anything owns the name, and a bare compositor may have no notification daemon.
                // Asking also starts one that is only D-Bus activatable.
                val caps = remote.GetCapabilities()
                serverActions = "actions" in caps
                if (serverActions) {
                    conn.addSigHandler(FdoNotifications.ActionInvoked::class.java) { signal ->
                        val target = targets[signal.id.toLong()] ?: return@addSigHandler
                        open(target)
                    }
                }
                Logger.i(TAG, "notifications through D-Bus, capabilities $caps")
            }
        }.onFailure { Logger.w(TAG, "no notification server: ${it.message}") }.getOrNull()
        return server
    }

    private var serverTried = false

    private fun open(target: OpenTarget) {
        when (target) {
            OpenTarget.DownloadsScreen -> runCatching(onOpenDownloads)
            is OpenTarget.File -> OpenExternal.reveal(File(target.path))
            is OpenTarget.Folder -> OpenExternal.open(File(target.path))
        }
    }

    private fun notifySend(): NotifySend? {
        if (!notifySendTried) {
            notifySendTried = true
            notifySend = NotifySend.find()
        }
        return notifySend
    }

    companion object {
        private const val TAG = "LinuxNotifications"
        const val APP_NAME = "TMPlayer"
        const val SERVICE = "org.freedesktop.Notifications"
        const val OBJECT_PATH = "/org/freedesktop/Notifications"
        const val LAUNCHER_PATH = "/com/canonical/unity/launcherentry/1"

        /** The icon name the packages install into hicolor. */
        const val ICON = "io.github.dracu_lah.TMPlayer"
        const val URGENCY_LOW: Byte = 0
        const val URGENCY_NORMAL: Byte = 1
        const val NEVER_EXPIRE = 0
        const val DEFAULT_EXPIRY = -1
        private const val MAX_TARGETS = 32
        private const val CAPABILITY_WAIT_MS = 2_000L
    }
}

/**
 * The words, hints and actions of a transfer notification, apart from D-Bus so they can be tested
 * without a bus.
 */
internal object LinuxNotificationText {
    const val DESKTOP_ENTRY = "io.github.dracu_lah.TMPlayer"
    const val LAUNCHER_APP_URI = "application://$DESKTOP_ENTRY.desktop"
    const val CATEGORY_PROGRESS = "transfer"
    const val CATEGORY_COMPLETE = "transfer.complete"
    const val CATEGORY_ERROR = "transfer.error"

    /** The key of the action that opens what finished; "default" is a press on the body. */
    const val ACTION_OPEN = "open"
    const val ACTION_DEFAULT = "default"

    fun summary(kind: Kind, title: String): String = when (kind) {
        Kind.Download -> if (title.isBlank()) "Downloading" else "Downloading $title"
        Kind.MoveToDownloads -> if (title.isBlank()) "Moving into Downloads" else "Moving $title into Downloads"
        Kind.Relocate, Kind.Migrate -> title
    }

    /** Whole percent, or null with no size to measure against. */
    fun percent(done: Long, total: Long?): Int? {
        if (total == null || total <= 0) return null
        return ((done.coerceIn(0, total) * 100) / total).toInt()
    }

    /** "42 %, 1.2 GB of 2.9 GB, 3.1 MB/s", or as much of it as is known. */
    fun progressBody(done: Long, total: Long?, bytesPerSecond: Long?): String = buildString {
        val pct = percent(done, total)
        if (pct != null) {
            append(pct).append(" %, ")
            append(StreamStats.formatBytes(done)).append(" of ").append(StreamStats.formatBytes(total!!))
        } else {
            append(StreamStats.formatBytes(done))
        }
        if (bytesPerSecond != null && bytesPerSecond > 0) append(", ").append(StreamStats.formatSpeed(bytesPerSecond))
    }

    fun hints(category: String, urgency: Byte, percent: Int?): Map<String, Variant<*>> = buildMap {
        put("desktop-entry", Variant(DESKTOP_ENTRY))
        put("category", Variant(category))
        put("urgency", Variant(urgency))
        if (percent != null) put("value", Variant(percent.coerceIn(0, 100)))
        // A progress notification is not worth keeping in a history once it is gone.
        if (category == CATEGORY_PROGRESS) put("transient", Variant(true))
    }

    /** The action list for a completion, as the spec pairs them: key, label, key, label. */
    fun actions(target: OpenTarget): List<String> {
        val label = when (target) {
            OpenTarget.DownloadsScreen -> "Show Downloads"
            is OpenTarget.File -> "Show in folder"
            is OpenTarget.Folder -> "Open folder"
        }
        return listOf(ACTION_DEFAULT, label, ACTION_OPEN, label)
    }

    /** LauncherEntry's properties: a bar while something moves, none when nothing does. */
    fun launcherProperties(fraction: Float?): Map<String, Variant<*>> = if (fraction == null) {
        mapOf("progress-visible" to Variant(false))
    } else {
        mapOf(
            "progress" to Variant(fraction.coerceIn(0f, 1f).toDouble()),
            "progress-visible" to Variant(true),
        )
    }

    /** `notify-send` arguments for one notification, without the program name. */
    fun notifySendArgs(
        summary: String,
        body: String,
        category: String,
        urgency: String,
        percent: Int?,
        replaces: Long?,
        printId: Boolean,
    ): List<String> = buildList {
        add("-a"); add(LinuxNotifications.APP_NAME)
        add("-u"); add(urgency)
        add("-c"); add(category)
        add("-h"); add("string:desktop-entry:$DESKTOP_ENTRY")
        if (percent != null) { add("-h"); add("int:value:${percent.coerceIn(0, 100)}") }
        if (printId) add("-p")
        if (replaces != null && replaces > 0) { add("-r"); add(replaces.toString()) }
        // "--" so a title that starts with a dash is not read as an option.
        add("--")
        add(summary)
        if (body.isNotEmpty()) add(body)
    }
}

/**
 * `notify-send`, for a session with no bus this process can reach. Versions before 0.7.9 cannot
 * print or replace an id, and then only completions are sent: a fresh notification a second for
 * progress would bury the screen.
 */
internal class NotifySend private constructor(val canReplace: Boolean) {

    /** Sends one and returns its id where this version prints one. */
    fun send(summary: String, body: String, category: String, urgency: String, percent: Int?, replaces: Long?): Long? {
        val args = LinuxNotificationText.notifySendArgs(summary, body, category, urgency, percent, replaces, printId = canReplace)
        return runCatching {
            val p = ProcessBuilder(listOf(COMMAND) + args).redirectErrorStream(true).start()
            val out = p.inputStream.bufferedReader().readText()
            p.waitFor(WAIT_S, TimeUnit.SECONDS)
            out.trim().lineSequence().lastOrNull()?.trim()?.toLongOrNull()
        }.getOrNull()
    }

    /** Replaces a progress notification with one that goes at once. */
    fun expire(id: Long) {
        runCatching {
            ProcessBuilder(COMMAND, "-a", LinuxNotifications.APP_NAME, "-r", id.toString(), "-t", "1", "--", " ")
                .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .start().waitFor(WAIT_S, TimeUnit.SECONDS)
        }
    }

    companion object {
        private const val COMMAND = "notify-send"
        private const val WAIT_S = 3L

        fun find(): NotifySend? {
            if (!OsInfo.onPath(COMMAND)) return null
            val help = runCatching {
                val p = ProcessBuilder(COMMAND, "--help").redirectErrorStream(true).start()
                val text = p.inputStream.bufferedReader().readText()
                p.waitFor(WAIT_S, TimeUnit.SECONDS)
                text
            }.getOrDefault("")
            return NotifySend(canReplace = "--print-id" in help && "--replace-id" in help)
        }
    }
}

@Suppress("FunctionName")
@DBusInterfaceName(LinuxNotifications.SERVICE)
internal interface FdoNotifications : DBusInterface {
    fun Notify(
        appName: String,
        replacesId: UInt32,
        appIcon: String,
        summary: String,
        body: String,
        actions: List<String>,
        hints: Map<String, @JvmSuppressWildcards Variant<*>>,
        expireTimeout: Int,
    ): UInt32

    fun CloseNotification(id: UInt32)

    fun GetCapabilities(): List<String>

    /** A button, or the body, of one of this app's notifications was pressed. */
    class ActionInvoked(path: String, val id: UInt32, val actionKey: String) : DBusSignal(path, id, actionKey)
}

/**
 * Unity's launcher API, which KDE's task manager and Dash to Dock also read: one signal, sent from
 * any path, naming the app by its desktop file.
 */
@DBusInterfaceName("com.canonical.Unity.LauncherEntry")
internal interface UnityLauncherEntry : DBusInterface {
    class Update(
        path: String,
        val appUri: String,
        val properties: Map<String, @JvmSuppressWildcards Variant<*>>,
    ) : DBusSignal(path, appUri, properties)
}
