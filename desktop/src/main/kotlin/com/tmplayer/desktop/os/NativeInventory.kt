package com.tmplayer.desktop.os

import com.tmplayer.desktop.DesktopPaths
import com.tmplayer.platform.Logger
import java.io.File

/**
 * Logs which native pieces this run uses and where each came from (plan B3.4 item 5), so a report
 * from a tester on an OS nobody here runs says exactly what is missing.
 *
 * Call [log] once at start, after TDLib and the player have loaded, and again from a "Playback
 * details" panel if useful. Nothing here loads a library: it reports what the classpath offers,
 * what the runtime folder holds, and (Linux only, from `/proc/self/maps`) what is actually mapped.
 */
object NativeInventory {

    private const val TAG = "NativeInventory"

    /** Libraries worth naming when found mapped into the process. */
    private val INTERESTING = Regex("tdjson|mpv|jnidispatch|avcodec|avformat|libass|skiko|dbus", RegexOption.IGNORE_CASE)

    fun log(mpvRuntimeDir: File = DesktopPaths.nativeDir) {
        report(mpvRuntimeDir).forEach { Logger.i(TAG, it) }
    }

    fun report(mpvRuntimeDir: File = DesktopPaths.nativeDir): List<String> = buildList {
        add("os: ${System.getProperty("os.name")} ${System.getProperty("os.version")} ${System.getProperty("os.arch")} (${OsInfo.osTag}/${OsInfo.archTag})")
        add("jvm: ${System.getProperty("java.vm.name")} ${System.getProperty("java.runtime.version")} at ${System.getProperty("java.home")}")
        add("java.library.path: ${System.getProperty("java.library.path").orEmpty()}")

        add("jna: ${jnaVersion() ?: "absent"} from ${classOrigin("com.sun.jna.Native") ?: "nowhere"}")
        add("jna dispatch: ${System.getProperty("jnidispatch.path") ?: "not loaded yet"}" +
            (System.getProperty("jna.boot.library.path")?.let { ", jna.boot.library.path=$it" } ?: ""))

        val tdName = when {
            OsInfo.isWindows -> "tdjsonjava.dll"
            OsInfo.isMac -> "libtdjsonjava.dylib"
            else -> "libtdjsonjava.so"
        }
        val tdResource = "${OsInfo.osTag}/${OsInfo.archTag}/$tdName"
        add("tdlib: $tdResource in ${resourceOrigin(tdResource) ?: "MISSING from the classpath"}")

        val mpvManifest = "mpv-natives-${OsInfo.osTag}-${OsInfo.archTag}.txt"
        add("mpv runtime: $mpvManifest in ${resourceOrigin(mpvManifest) ?: "MISSING from the classpath"}")
        add("mpv runtime dir: ${describeDir(mpvRuntimeDir)}")

        val mapped = mappedLibraries()
        if (mapped == null) {
            add("mapped libraries: not visible on this OS")
        } else if (mapped.isEmpty()) {
            add("mapped libraries: none of TDLib, mpv or JNA loaded yet")
        } else {
            mapped.forEach { add("mapped: $it") }
        }
    }

    private fun jnaVersion(): String? = runCatching {
        // Read reflectively so that asking never initialises Native (which loads jnidispatch).
        // Version is a package private interface, hence the setAccessible.
        Class.forName("com.sun.jna.Version", false, javaClass.classLoader).getField("VERSION")
            .apply { isAccessible = true }.get(null) as String
    }.getOrNull()

    private fun classOrigin(name: String): String? = runCatching {
        Class.forName(name, false, javaClass.classLoader).protectionDomain?.codeSource?.location?.toString()
    }.getOrNull()

    private fun resourceOrigin(path: String): String? = runCatching {
        val url = javaClass.classLoader.getResource(path) ?: return null
        url.toString().substringBefore("!/")
    }.getOrNull()

    private fun describeDir(dir: File): String {
        if (!dir.isDirectory) return "${dir.absolutePath} (absent)"
        val files = dir.walkTopDown().filter { it.isFile }.toList()
        val mpv = files.firstOrNull { it.name.contains("mpv") }
        return "${dir.absolutePath} (${files.size} files" + (mpv?.let { ", ${it.name}" } ?: ", no libmpv") + ")"
    }

    /** Distinct paths of interesting shared objects mapped into this process; null off Linux. */
    private fun mappedLibraries(): List<String>? {
        val maps = File("/proc/self/maps")
        if (!OsInfo.isLinux || !maps.canRead()) return null
        return runCatching {
            maps.readLines()
                .mapNotNull { line -> line.indexOf('/').takeIf { it >= 0 }?.let { line.substring(it).trim() } }
                .filter { path -> INTERESTING.containsMatchIn(path.substringAfterLast('/')) }
                .distinct()
        }.getOrNull()
    }
}
