package com.tmplayer.desktop.os

import java.io.File

/**
 * Windows long paths (WP-52). `java.io.File` stops at 260 characters on Windows (NIO does not), and
 * a folder or file the viewer picked can be deep: a downloads root on a network share, a backup in
 * a synced folder, a folder name of 100 characters inside another of 100. The two ends:
 *
 * - [clean] takes the `\\?\` prefix off whatever a picker or a saved setting hands back, so the
 *   rest of the app, and the viewer, only ever see ordinary paths.
 * - [io] puts the prefix back, for a path long enough to need it, on the way into `java.io.File`
 *   calls. Short paths and every other OS are returned as they are, so nothing changes for the
 *   people who never hit the limit.
 *
 * The functions are plain text work, tested on any OS; [windows] says which rules apply.
 */
object LongPaths {

    /** The length from which a path is prefixed. Windows' limit is 260 with the terminator; folders ask for less (248). */
    const val LIMIT = 240

    /** Room left for a file name when a folder is asked about: a folder of 140 characters or more is prefixed. */
    const val NAME_RESERVE = 100

    private const val DEVICE = "\\\\?\\"
    private const val DEVICE_UNC = "\\\\?\\UNC\\"

    /** [path] as an ordinary path: `\\?\C:\a` is `C:\a` and `\\?\UNC\host\share` is `\\host\share`. */
    fun clean(path: String): String = when {
        path.startsWith(DEVICE_UNC, ignoreCase = true) -> "\\\\" + path.substring(DEVICE_UNC.length)
        path.startsWith(DEVICE) -> path.substring(DEVICE.length)
        else -> path
    }

    /**
     * [path] ready for `java.io.File`: on Windows, an absolute path of [LIMIT] characters or more
     * gets `\\?\` (or `\\?\UNC\`), with `/` turned into `\` and `.` and `..` folded away, which
     * the prefix would otherwise take literally. Anything else is returned as it was.
     */
    fun extend(path: String, windows: Boolean = OsInfo.isWindows, reserve: Int = 0): String {
        if (!windows || path.length + reserve < LIMIT) return path
        if (path.startsWith(DEVICE)) return path
        val slashed = path.replace('/', '\\')
        val prefixed = when {
            slashed.startsWith("\\\\") -> DEVICE_UNC + fold(slashed.substring(2))
            slashed.length > 2 && slashed[1] == ':' && slashed[0].isLetter() && slashed[2] == '\\' -> DEVICE + fold(slashed)
            // A relative path, or one on the current drive: the prefix needs a full path, so leave it.
            else -> return path
        }
        return prefixed
    }

    /** [file] for `java.io.File` calls; the same instance when nothing needs adding. */
    fun io(file: File, windows: Boolean = OsInfo.isWindows, reserve: Int = 0): File {
        val extended = extend(file.path, windows, reserve)
        return if (extended == file.path) file else File(extended)
    }

    /** [file] as the viewer sees it: no prefix. */
    fun shown(file: File): String = clean(file.path)

    /** [file] from a picker or a saved setting: no prefix, so it compares and prints like any other path. */
    fun fromPicker(file: File?): File? = file?.let { if (clean(it.path) == it.path) it else File(clean(it.path)) }

    /** A picked file, cleaned and then ready for `java.io.File` calls; null stays null. */
    fun forIo(file: File?, windows: Boolean = OsInfo.isWindows): File? = fromPicker(file)?.let { io(it, windows) }

    /** True when a file named [name] in [dir] would pass the ordinary limit, so it needs [io] on Windows. */
    fun needsPrefix(dir: File, name: String): Boolean = dir.path.length + 1 + name.length >= LIMIT

    /** Folds `.` and `..` segments of a backslash path, which a `\\?\` path does not do for itself. */
    private fun fold(path: String): String {
        val out = ArrayList<String>()
        for (part in path.split('\\')) {
            when (part) {
                "." -> Unit
                ".." -> if (out.size > 1) out.removeAt(out.lastIndex)
                else -> out += part
            }
        }
        return out.joinToString("\\")
    }
}
