package com.tmplayer.ui.online

import androidx.compose.runtime.Composable
import com.tmplayer.online.MetaTrailer
import java.awt.Desktop
import java.net.URI

/**
 * Opens a trailer's YouTube page in the browser: `java.awt.Desktop` first, then the shell's own
 * opener for the Linux desktops where AWT has none (sway, i3). Off the caller's thread, since
 * starting a browser can block for seconds. Always true: a desktop has a browser.
 */
@Composable
internal fun rememberTrailerOpener(): (MetaTrailer) -> Boolean = ::browse

private fun browse(trailer: MetaTrailer): Boolean {
    val url = trailer.watchUrl
    Thread({
        val desktop = runCatching { Desktop.getDesktop().takeIf { Desktop.isDesktopSupported() && it.isSupported(Desktop.Action.BROWSE) } }.getOrNull()
        if (desktop != null && runCatching { desktop.browse(URI(url)) }.isSuccess) return@Thread
        val os = System.getProperty("os.name").orEmpty().lowercase()
        val command = when {
            "win" in os -> listOf("rundll32", "url.dll,FileProtocolHandler", url)
            "mac" in os -> listOf("open", url)
            else -> listOf("xdg-open", url)
        }
        runCatching { ProcessBuilder(command).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start() }
    }, "tmplayer-trailer").apply { isDaemon = true }.start()
    return true
}
