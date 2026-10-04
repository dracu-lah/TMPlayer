package com.tmplayer.desktop.os

import com.sun.jna.platform.win32.KnownFolders
import com.sun.jna.platform.win32.Shell32Util
import com.tmplayer.platform.Logger
import java.io.File

/**
 * The viewer's own Downloads folder, where the OS keeps it rather than where it usually is.
 *
 * On Windows the folder can be redirected (to another drive, or to OneDrive), so it is asked of
 * the shell as a Known Folder rather than assumed to be `%USERPROFILE%\Downloads`. On Linux it is
 * whatever `xdg-user-dirs` says, which a desktop in another language names in that language
 * ("Téléchargements"), with `~/Downloads` when nothing says otherwise. On macOS it is always
 * `~/Downloads`.
 */
object UserDirs {

    /** The Downloads folder, resolved for the OS this runs on. Never null; may not exist yet. */
    fun downloads(): File = runCatching {
        when {
            OsInfo.isWindows -> windowsDownloads()
            OsInfo.isMac -> null
            else -> xdgDownloads(System.getenv(), home())
        }
    }.onFailure { Logger.w(TAG, "could not resolve the Downloads folder: ${it.message}") }
        .getOrNull() ?: File(home(), "Downloads")

    private fun home(): File = File(System.getProperty("user.home").orEmpty())

    private fun windowsDownloads(): File? =
        Shell32Util.getKnownFolderPath(KnownFolders.FOLDERID_Downloads)?.takeIf { it.isNotBlank() }?.let(::File)

    /**
     * The XDG Downloads folder, from the environment and `user-dirs.dirs`.
     *
     * `$XDG_CONFIG_HOME/user-dirs.dirs` is read first and `~/.config/user-dirs.dirs` second: inside
     * a Flatpak the first is the sandbox's own config directory, which holds the host's file only
     * when Flatpak has put it there.
     */
    internal fun xdgDownloads(env: Map<String, String>, home: File): File? {
        env["XDG_DOWNLOAD_DIR"]?.let { parseValue(it, home) }?.let { return it }
        val configHome = env["XDG_CONFIG_HOME"]?.let(::File)?.takeIf { it.isAbsolute }
        val candidates = listOfNotNull(configHome, File(home, ".config")).distinct()
        for (dir in candidates) {
            val file = File(dir, "user-dirs.dirs")
            if (!file.isFile) continue
            val found = runCatching { parseUserDirs(file.readText(), home) }.getOrNull()
            if (found != null) return found
        }
        return null
    }

    /**
     * `XDG_DOWNLOAD_DIR` out of a `user-dirs.dirs` file, or null when it is absent or unusable.
     *
     * The format is shell assignments, one per line, each value in double quotes and either
     * absolute or starting with `$HOME`. A value that is `$HOME` itself means the folder is turned
     * off, which reads here as absent.
     */
    fun parseUserDirs(text: String, home: File, key: String = "XDG_DOWNLOAD_DIR"): File? {
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            val eq = line.indexOf('=')
            if (eq <= 0 || line.substring(0, eq).trim() != key) continue
            return parseValue(line.substring(eq + 1), home)
        }
        return null
    }

    private fun parseValue(raw: String, home: File): File? {
        var value = raw.trim()
        if (value.length >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
            value = value.substring(1, value.length - 1)
        }
        value = value.replace("\\\"", "\"").replace("\\$", "$").replace("\\\\", "\\")
        val path = when {
            value == "\$HOME" || value == "\$HOME/" || value == "\${HOME}" || value == "\${HOME}/" -> return null
            value.startsWith("\$HOME/") -> File(home, value.removePrefix("\$HOME/"))
            value.startsWith("\${HOME}/") -> File(home, value.removePrefix("\${HOME}/"))
            value.startsWith("/") -> File(value)
            // Relative paths are not allowed by the spec, and guessing at one is worse than the fallback.
            else -> return null
        }
        return path.takeIf { it.path.isNotBlank() && it.absoluteFile != home.absoluteFile }
    }

    private const val TAG = "UserDirs"
}
