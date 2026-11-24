package com.tmplayer.desktop.os

import com.sun.jna.platform.win32.Advapi32Util
import com.sun.jna.platform.win32.WinReg
import com.tmplayer.platform.Logger
import java.io.File

/**
 * Open TMPlayer when the viewer signs in (WP-52), off by default, one place per OS:
 * the current user's `Run` key on Windows, a launch agent on macOS (no permission prompt, and
 * System Settings lists it under Login Items), an XDG autostart entry on Linux.
 *
 * The state is never stored by TMPlayer: it is read from the place the OS reads it from
 * ([state]), so a viewer who removes the entry in Task Manager, Login Items or a desktop's
 * Startup Applications sees the switch off, and one who moved the app sees it point at another
 * copy ([State.OtherCopy]). [set] reads back after it writes, and says what it found.
 */
class LaunchAtLogin(
    private val backend: LoginBackend,
    /** What this running copy is started by, or null when it cannot be (a development run, a sandbox). */
    private val selfCommand: () -> String?,
) {
    enum class State {
        /** Nothing starts at sign in. */
        Off,

        /** This copy of TMPlayer starts at sign in. */
        On,

        /** A copy of TMPlayer starts, but another one than this: the app was moved or reinstalled. */
        OtherCopy,

        /** This run cannot be put in the list (development run, Flatpak or Snap sandbox): hide the switch. */
        Unavailable,
    }

    /** What the OS has now. Never throws: a place that cannot be read is [State.Off]. */
    fun state(): State {
        val self = selfCommand() ?: return State.Unavailable
        val entry = runCatching { backend.read() }.getOrNull() ?: return State.Off
        return if (sameCommand(entry, self)) State.On else State.OtherCopy
    }

    /**
     * Turns it on for this copy or off, and returns what the OS holds afterwards, so the caller
     * shows the truth even when the write failed ([ok] false). Off removes only an entry that is
     * TMPlayer's own.
     */
    fun set(on: Boolean): Result {
        val self = selfCommand() ?: return Result(State.Unavailable, ok = false)
        val wrote = runCatching { if (on) backend.write(self) else backend.remove() }
            .onFailure { Logger.w(TAG, "launch at login: ${it.message}") }
            .isSuccess
        val after = state()
        val wanted = if (on) State.On else State.Off
        return Result(after, ok = wrote && after == wanted)
    }

    data class Result(val state: State, val ok: Boolean)

    /** The one place an OS keeps its list: read what TMPlayer's entry starts, write it, remove it. */
    interface LoginBackend {
        /** The program the entry starts, or null when there is no entry. */
        fun read(): String?

        fun write(command: String)

        fun remove()
    }

    companion object {
        private const val TAG = "LaunchAtLogin"

        /** The name every backend files its entry under. */
        const val NAME = "TMPlayer"

        /** Paths compare as the OS does: Windows ignores case. */
        internal fun sameCommand(a: String, b: String): Boolean =
            if (OsInfo.isWindows) a.equals(b, ignoreCase = true) else a == b

        /**
         * The program a login should start, or null for a run that must not register itself: a
         * development run (no launcher), a Flatpak or Snap (their own sandboxes keep their own
         * autostart), or an AppImage, which is started by its file, not the unpacked app.
         */
        fun selfCommand(
            env: Map<String, String> = System.getenv(),
            property: (String) -> String? = System::getProperty,
        ): String? {
            if (env["FLATPAK_ID"] != null || env["SNAP"] != null) return null
            env["APPIMAGE"]?.takeIf { it.isNotBlank() && File(it).isFile }?.let { return it }
            return property("jpackage.app-path")?.takeIf { it.isNotBlank() && File(it).isFile }
        }

        /** For this OS, with the real places. */
        fun system(): LaunchAtLogin = LaunchAtLogin(
            backend = when {
                OsInfo.isWindows -> WindowsRunKey(JnaRunKey)
                OsInfo.isMac -> MacLaunchAgent(File(System.getProperty("user.home").orEmpty(), "Library/LaunchAgents"))
                else -> LinuxAutostart(
                    File(System.getenv("XDG_CONFIG_HOME")?.takeIf { it.isNotBlank() } ?: File(System.getProperty("user.home").orEmpty(), ".config").path, "autostart"),
                )
            },
            selfCommand = { selfCommand() },
        )
    }
}

/** The registry, as far as the Run key needs it; the test stands in a map. */
interface RunKeyApi {
    fun get(name: String): String?
    fun set(name: String, value: String)
    fun delete(name: String)
}

/** `HKCU\Software\Microsoft\Windows\CurrentVersion\Run`: the command is quoted, so a path with spaces starts. */
class WindowsRunKey(private val key: RunKeyApi) : LaunchAtLogin.LoginBackend {
    override fun read(): String? = key.get(LaunchAtLogin.NAME)?.let(::unquote)

    override fun write(command: String) = key.set(LaunchAtLogin.NAME, "\"$command\"")

    override fun remove() = key.delete(LaunchAtLogin.NAME)

    internal companion object {
        /** The program of a Run value: the quoted part, or the text before the first space of a bare one. */
        fun unquote(value: String): String {
            val v = value.trim()
            if (v.startsWith('"')) return v.drop(1).substringBefore('"')
            return v.substringBefore(' ')
        }
    }
}

internal object JnaRunKey : RunKeyApi {
    private const val PATH = "Software\\Microsoft\\Windows\\CurrentVersion\\Run"

    override fun get(name: String): String? =
        if (Advapi32Util.registryValueExists(WinReg.HKEY_CURRENT_USER, PATH, name)) {
            Advapi32Util.registryGetStringValue(WinReg.HKEY_CURRENT_USER, PATH, name)
        } else {
            null
        }

    override fun set(name: String, value: String) = Advapi32Util.registrySetStringValue(WinReg.HKEY_CURRENT_USER, PATH, name, value)

    override fun delete(name: String) {
        if (Advapi32Util.registryValueExists(WinReg.HKEY_CURRENT_USER, PATH, name)) {
            Advapi32Util.registryDeleteValue(WinReg.HKEY_CURRENT_USER, PATH, name)
        }
    }
}

/** macOS: `~/Library/LaunchAgents/<id>.plist` with `RunAtLoad`. */
class MacLaunchAgent(private val dir: File) : LaunchAtLogin.LoginBackend {
    private val file get() = File(dir, "$LABEL.plist")

    override fun read(): String? {
        val text = runCatching { file.readText() }.getOrNull() ?: return null
        val args = text.substringAfter("<key>ProgramArguments</key>", "")
        val first = Regex("<string>(.*?)</string>", RegexOption.DOT_MATCHES_ALL).find(args)?.groupValues?.getOrNull(1) ?: return null
        return unescape(first)
    }

    override fun write(command: String) {
        dir.mkdirs()
        val plist = """<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
    <key>Label</key>
    <string>$LABEL</string>
    <key>ProgramArguments</key>
    <array>
        <string>${escape(command)}</string>
    </array>
    <key>RunAtLoad</key>
    <true/>
</dict>
</plist>
"""
        val part = File(dir, "$LABEL.plist.part")
        part.writeText(plist)
        if (!part.renameTo(file)) {
            part.delete()
            error("could not write ${file.name}")
        }
    }

    override fun remove() {
        if (file.exists() && !file.delete()) error("could not remove ${file.name}")
    }

    internal companion object {
        const val LABEL = "io.github.dracu-lah.tmplayer.login"
        fun escape(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        fun unescape(s: String) = s.replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&")
    }
}

/** Linux: `$XDG_CONFIG_HOME/autostart/<id>.desktop`, which GNOME, KDE, Xfce and the others read. */
class LinuxAutostart(private val dir: File) : LaunchAtLogin.LoginBackend {
    private val file get() = File(dir, ENTRY)

    override fun read(): String? {
        val text = runCatching { file.readText() }.getOrNull() ?: return null
        // A desktop's Startup Applications switch writes this line instead of deleting the file.
        if (text.lines().any { it.trim().equals("Hidden=true", ignoreCase = true) }) return null
        if (text.lines().any { it.trim().equals("X-GNOME-Autostart-enabled=false", ignoreCase = true) }) return null
        val exec = text.lines().firstOrNull { it.startsWith("Exec=") }?.removePrefix("Exec=")?.trim() ?: return null
        return firstToken(exec)
    }

    override fun write(command: String) {
        dir.mkdirs()
        val entry = """[Desktop Entry]
Type=Application
Name=TMPlayer
Exec=${quote(command)}
Terminal=false
X-GNOME-Autostart-enabled=true
"""
        val part = File(dir, "$ENTRY.part")
        part.writeText(entry)
        if (!part.renameTo(file)) {
            part.delete()
            error("could not write $ENTRY")
        }
    }

    override fun remove() {
        if (file.exists() && !file.delete()) error("could not remove $ENTRY")
    }

    internal companion object {
        const val ENTRY = "io.github.dracu_lah.TMPlayer.desktop"

        /** The Desktop Entry spec's quoting: double quotes, with `"`, `` ` ``, `$` and `\` escaped by a backslash. */
        fun quote(path: String): String =
            "\"" + path.replace("\\", "\\\\").replace("\"", "\\\"").replace("`", "\\`").replace("$", "\\$").replace("%", "%%") + "\""

        /** The program of an `Exec` line, undoing [quote]; a bare word ends at the first space. */
        fun firstToken(exec: String): String {
            if (!exec.startsWith('"')) return exec.substringBefore(' ')
            val out = StringBuilder()
            var i = 1
            while (i < exec.length) {
                val c = exec[i]
                when {
                    c == '\\' && i + 1 < exec.length -> { out.append(exec[i + 1]); i += 2 }
                    c == '"' -> return out.toString()
                    c == '%' && exec.getOrNull(i + 1) == '%' -> { out.append('%'); i += 2 }
                    else -> { out.append(c); i++ }
                }
            }
            return out.toString()
        }
    }
}
