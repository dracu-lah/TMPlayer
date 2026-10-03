package com.tmplayer.desktop.os

import java.io.File
import java.util.Locale

/** Which of the three desktops this JVM is running on, read once from `os.name` and `os.arch`. */
object OsInfo {
    private val name: String = System.getProperty("os.name").orEmpty().lowercase(Locale.ROOT)
    private val arch: String = System.getProperty("os.arch").orEmpty().lowercase(Locale.ROOT)

    val isLinux: Boolean = name.startsWith("linux")
    val isWindows: Boolean = name.startsWith("windows")
    val isMac: Boolean = name.startsWith("mac") || name.startsWith("darwin")

    /** `x64` or `arm64`, the spelling TDLib's and mediamp's native jars use for their folders. */
    val archTag: String = when (arch) {
        "amd64", "x86_64", "x64" -> "x64"
        "aarch64", "arm64" -> "arm64"
        else -> arch
    }

    /** `linux`, `windows` or `macos`, the same folder spelling. */
    val osTag: String = when {
        isLinux -> "linux"
        isWindows -> "windows"
        isMac -> "macos"
        else -> name
    }

    /** This process's id, which `caffeinate -w` and `tail --pid` use to end with the app. */
    val pid: Long get() = ProcessHandle.current().pid()

    /** True when [command] is an executable somewhere on `PATH`. */
    fun onPath(command: String): Boolean {
        val path = System.getenv("PATH") ?: return false
        return path.split(File.pathSeparatorChar).any { dir ->
            val f = File(dir, command)
            f.isFile && f.canExecute()
        }
    }
}
