package com.tmplayer.ui.theme

import androidx.compose.runtime.Composable
import java.util.concurrent.TimeUnit

/**
 * True when the desktop has its animations turned off: GNOME's `enable-animations`, macOS's
 * Reduce motion, Windows' "Animate controls and elements" (the MinAnimate value it writes). Asked
 * once, on a thread of its own, the first time anything wants to know; a change waits for
 * the next start, which is enough for a setting people turn once. See [Motion].
 */
@Composable
fun rememberReducedMotion(): Boolean = SystemMotion.reduced.value

private object SystemMotion {
    /**
     * The JVM property ("true" or "false") that answers in place of the desktop. The tests set it
     * to false, so a check that a knob slides gives one answer on every machine: the macOS and
     * Windows CI runners have their animations off, and the knob snapped there (2026-11-17).
     */
    private const val OVERRIDE = "tmplayer.systemReducedMotion"

    /**
     * Read at once rather than on the thread: the first read is in a composition, and an answer
     * that lands before that composition's snapshot is applied is lost (a thread that answered in
     * no time left this false).
     */
    private val forced: Boolean? = System.getProperty(OVERRIDE)?.toBooleanStrictOrNull()

    /** False (moving) until the answer is in, then whatever the desktop said. */
    val reduced = androidx.compose.runtime.mutableStateOf(forced ?: false)

    init {
        if (forced == null) {
            Thread({ reduced.value = runCatching { ask() }.getOrDefault(false) }, "system-motion")
                .apply { isDaemon = true }
                .start()
        }
    }

    private fun ask(): Boolean {
        val os = System.getProperty("os.name").orEmpty().lowercase()
        return when {
            os.startsWith("linux") ->
                run("gsettings", "get", "org.gnome.desktop.interface", "enable-animations")?.trim() == "false"
            os.startsWith("mac") || os.startsWith("darwin") ->
                run("defaults", "read", "com.apple.universalaccess", "reduceMotion")?.trim() == "1"
            os.startsWith("windows") ->
                run("reg", "query", "HKCU\\Control Panel\\Desktop\\WindowMetrics", "/v", "MinAnimate")
                    ?.lineSequence()?.firstOrNull { "MinAnimate" in it }?.trim()?.endsWith(" 0") == true
            else -> false
        }
    }

    private fun run(vararg command: String): String? {
        val process = ProcessBuilder(*command).redirectErrorStream(true).start()
        if (!process.waitFor(2, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            return null
        }
        return if (process.exitValue() == 0) process.inputStream.bufferedReader().readText() else null
    }
}
