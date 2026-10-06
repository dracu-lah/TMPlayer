package com.tmplayer.desktop.os

import com.tmplayer.platform.Logger
import java.awt.Desktop
import java.io.File
import java.net.URI

/**
 * Opens a web link in the browser, or a file or folder in the OS's own file manager.
 *
 * `java.awt.Desktop` first, which is the native call on Windows and macOS and goes through GNOME's
 * or KDE's libraries on Linux. It is missing or refuses on plenty of Linux desktops (sway, i3, a
 * JVM without GTK), so each OS then has the command its shell would use. Everything runs off the
 * caller's thread: `Desktop.browse` can block for seconds on Linux while it starts a browser.
 */
object OpenExternal {

    fun browse(url: String) = launch("browse $url") {
        val uri = URI(url)
        val desktop = desktopFor(Desktop.Action.BROWSE)
        if (desktop != null && runCatching { desktop.browse(uri) }.isSuccess) return@launch true
        run(command(OsInfo.osTag, url))
    }

    /** A `mailto:` link in the mail program, with the shell's opener when AWT has no mail action. */
    fun mail(mailto: String) = launch("mail") {
        val desktop = desktopFor(Desktop.Action.MAIL)
        if (desktop != null && runCatching { desktop.mail(URI(mailto)) }.isSuccess) return@launch true
        run(command(OsInfo.osTag, mailto))
    }

    /** Opens [file] with its default app, or a folder in the file manager. */
    fun open(file: File) = launch("open $file") {
        val desktop = desktopFor(Desktop.Action.OPEN)
        if (desktop != null && runCatching { desktop.open(file) }.isSuccess) return@launch true
        run(command(OsInfo.osTag, file.absolutePath))
    }

    /** The folder holding [file], with the file selected where the OS can do that (Windows, macOS). */
    fun reveal(file: File) = launch("reveal $file") {
        val select = revealCommand(OsInfo.osTag, file)
        if (select != null && run(select)) return@launch true
        val folder = file.parentFile ?: return@launch false
        val desktop = desktopFor(Desktop.Action.OPEN)
        if (desktop != null && runCatching { desktop.open(folder) }.isSuccess) return@launch true
        run(command(OsInfo.osTag, folder.absolutePath))
    }

    /** The shell's own opener, for when `java.awt.Desktop` is not there. */
    internal fun command(os: String, target: String): List<String> = when (os) {
        // Not `cmd /c start`: cmd reads a quoted first argument as a window title and splits a
        // URL at every `&`. The URL handler takes the target as one argument.
        "windows" -> listOf("rundll32", "url.dll,FileProtocolHandler", target)
        "macos" -> listOf("open", target)
        else -> listOf("xdg-open", target)
    }

    internal fun revealCommand(os: String, file: File): List<String>? = when (os) {
        "windows" -> listOf("explorer.exe", "/select,", file.absolutePath)
        "macos" -> listOf("open", "-R", file.absolutePath)
        else -> null
    }

    private fun desktopFor(action: Desktop.Action): Desktop? = runCatching {
        if (!Desktop.isDesktopSupported()) return null
        Desktop.getDesktop().takeIf { it.isSupported(action) }
    }.getOrNull()

    private fun run(command: List<String>): Boolean = runCatching {
        ProcessBuilder(command).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start()
        true
    }.getOrElse {
        Logger.w(TAG, "could not run ${command.first()}: ${it.message}")
        false
    }

    private fun launch(what: String, block: () -> Boolean) {
        Thread({
            if (!runCatching(block).getOrDefault(false)) Logger.w(TAG, "could not $what")
        }, "tmplayer-open").apply { isDaemon = true }.start()
    }

    private const val TAG = "OpenExternal"
}
