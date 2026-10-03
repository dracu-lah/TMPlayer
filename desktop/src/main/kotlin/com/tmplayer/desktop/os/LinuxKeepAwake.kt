package com.tmplayer.desktop.os

import com.tmplayer.platform.Logger
import org.freedesktop.dbus.annotations.DBusInterfaceName
import org.freedesktop.dbus.connections.impl.DBusConnection
import org.freedesktop.dbus.connections.impl.DBusConnectionBuilder
import org.freedesktop.dbus.interfaces.DBus
import org.freedesktop.dbus.interfaces.DBusInterface
import org.freedesktop.dbus.types.UInt32
import java.util.concurrent.TimeUnit

/**
 * Linux: asks the session bus, in VLC's order, `org.freedesktop.ScreenSaver`, then
 * `org.freedesktop.PowerManagement`, then `org.gnome.SessionManager`, and stops at the first that
 * answers. Each of those ties the inhibition to this D-Bus connection, so if the app dies the
 * desktop drops it on its own.
 *
 * When none of the three is on the bus (sway, Hyprland and other bare compositors), it falls back
 * to two child processes, each taken only if the tool exists: `systemd-inhibit` blocks idle and
 * sleep through logind for as long as it runs (its child is `tail --pid` on this JVM, so it ends
 * with the app), and `xdg-screensaver suspend` keeps an X11 screensaver off when a window id is
 * known.
 */
internal class LinuxKeepAwake(
    private val x11WindowId: () -> Long?,
    private val connect: () -> DBusConnection = {
        DBusConnectionBuilder.forSessionBus().withShared(false).build()
    },
) : SerialKeepAwake("linux") {

    private var bus: DBusConnection? = null
    private var busFailed = false
    private var held: Held? = null

    /** What [hold] took, so [letGo] gives back the same thing. */
    private sealed interface Held {
        val via: String
        fun release()
    }

    override fun hold(reason: String): Boolean {
        val taken = viaDBus(reason) ?: viaProcesses(reason)
        held = taken
        if (taken != null) Logger.i(TAG, "inhibited via ${taken.via}") else Logger.w(TAG, "nothing on this desktop accepted an inhibit")
        return taken != null
    }

    override fun letGo() {
        held?.let {
            it.release()
            Logger.i(TAG, "released ${it.via}")
        }
        held = null
    }

    override fun dispose() {
        runCatching { bus?.close() }
        bus = null
    }

    /** The name of what holds the inhibition now, or null; for the live check. */
    internal val heldVia: String? get() = held?.via

    private fun bus(): DBusConnection? {
        if (bus == null && !busFailed) {
            bus = runCatching { connect() }.onFailure {
                busFailed = true
                Logger.w(TAG, "no session bus: ${it.message}")
            }.getOrNull()
        }
        return bus
    }

    private fun viaDBus(reason: String): Held? {
        val conn = bus() ?: return null
        val names = runCatching {
            conn.getRemoteObject("org.freedesktop.DBus", "/org/freedesktop/DBus", DBus::class.java)
        }.getOrNull() ?: return null
        fun has(name: String) = runCatching { names.NameHasOwner(name) }.getOrDefault(false)

        if (has(SCREENSAVER)) {
            runCatching {
                val ss = conn.getRemoteObject(SCREENSAVER, "/org/freedesktop/ScreenSaver", FdoScreenSaver::class.java)
                val cookie = ss.Inhibit(APP, reason)
                return DBusHeld(SCREENSAVER) { ss.UnInhibit(cookie) }
            }.onFailure { Logger.w(TAG, "$SCREENSAVER refused: ${it.message}") }
        }
        if (has(POWER)) {
            runCatching {
                val pm = conn.getRemoteObject(POWER, "/org/freedesktop/PowerManagement/Inhibit", FdoPowerInhibit::class.java)
                val cookie = pm.Inhibit(APP, reason)
                return DBusHeld(POWER) { pm.UnInhibit(cookie) }
            }.onFailure { Logger.w(TAG, "$POWER refused: ${it.message}") }
        }
        if (has(GNOME)) {
            runCatching {
                val sm = conn.getRemoteObject(GNOME, "/org/gnome/SessionManager", GnomeSessionManager::class.java)
                val xid = x11WindowId()?.let { UInt32(it) } ?: UInt32(0)
                val cookie = sm.Inhibit(APP, xid, reason, UInt32(GNOME_INHIBIT_SUSPEND or GNOME_INHIBIT_IDLE))
                return DBusHeld(GNOME) { sm.Uninhibit(cookie) }
            }.onFailure { Logger.w(TAG, "$GNOME refused: ${it.message}") }
        }
        return null
    }

    private fun viaProcesses(reason: String): Held? {
        val started = mutableListOf<Pair<String, Process>>()
        var xdgId: String? = null
        if (OsInfo.onPath("systemd-inhibit") && OsInfo.onPath("tail")) {
            runCatching {
                ProcessBuilder(
                    "systemd-inhibit", "--what=idle:sleep", "--who=$APP", "--why=$reason", "--mode=block",
                    "tail", "--pid=${OsInfo.pid}", "-f", "/dev/null",
                ).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start()
            }.onSuccess { p ->
                // A refusal (no logind, no permission) exits at once; a hold keeps running.
                if (!p.waitFor(300, TimeUnit.MILLISECONDS)) started += "systemd-inhibit" to p
            }
        }
        val window = x11WindowId()
        if (window != null && OsInfo.onPath("xdg-screensaver")) {
            val id = "0x" + window.toString(16)
            val ok = runCatching {
                ProcessBuilder("xdg-screensaver", "suspend", id)
                    .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .start().waitFor(5, TimeUnit.SECONDS)
            }.getOrDefault(false)
            if (ok) xdgId = id
        }
        if (started.isEmpty() && xdgId == null) return null
        val via = (started.map { it.first } + listOfNotNull(xdgId?.let { "xdg-screensaver" })).joinToString(" + ")
        return ProcessHeld(via, started.map { it.second }, xdgId)
    }

    private class DBusHeld(override val via: String, private val undo: () -> Unit) : Held {
        override fun release() {
            runCatching(undo).onFailure { Logger.w(TAG, "$via release failed: ${it.message}") }
        }
    }

    private class ProcessHeld(
        override val via: String,
        private val processes: List<Process>,
        private val xdgWindow: String?,
    ) : Held {
        override fun release() {
            processes.forEach { p ->
                p.descendants().forEach { it.destroy() }
                p.destroy()
            }
            if (xdgWindow != null) {
                runCatching {
                    ProcessBuilder("xdg-screensaver", "resume", xdgWindow)
                        .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD)
                        .start().waitFor(5, TimeUnit.SECONDS)
                }
            }
        }
    }

    companion object {
        private const val TAG = SerialKeepAwake.TAG
        private const val APP = "TMPlayer"
        const val SCREENSAVER = "org.freedesktop.ScreenSaver"
        const val POWER = "org.freedesktop.PowerManagement"
        const val GNOME = "org.gnome.SessionManager"

        /** `GsmInhibitorFlag`: 4 inhibits suspend, 8 marks the session as not idle. */
        private const val GNOME_INHIBIT_SUSPEND = 4L
        private const val GNOME_INHIBIT_IDLE = 8L
    }
}

@Suppress("FunctionName")
@DBusInterfaceName("org.freedesktop.ScreenSaver")
internal interface FdoScreenSaver : DBusInterface {
    fun Inhibit(applicationName: String, reasonForInhibit: String): UInt32
    fun UnInhibit(cookie: UInt32)
}

@Suppress("FunctionName")
@DBusInterfaceName("org.freedesktop.PowerManagement.Inhibit")
internal interface FdoPowerInhibit : DBusInterface {
    fun Inhibit(application: String, reason: String): UInt32
    fun UnInhibit(cookie: UInt32)
}

@Suppress("FunctionName")
@DBusInterfaceName("org.gnome.SessionManager")
internal interface GnomeSessionManager : DBusInterface {
    fun Inhibit(appId: String, toplevelXid: UInt32, reason: String, flags: UInt32): UInt32
    fun Uninhibit(inhibitCookie: UInt32)
}
