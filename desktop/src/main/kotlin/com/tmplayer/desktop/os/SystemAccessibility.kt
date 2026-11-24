package com.tmplayer.desktop.os

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.platform.win32.Advapi32Util
import com.sun.jna.platform.win32.WinReg
import com.sun.jna.ptr.IntByReference
import com.tmplayer.platform.Logger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.TimeUnit

/** What the OS says about contrast, motion and text size, any of which may be unknown (null). */
data class AccessibilityReading(
    val highContrast: Boolean? = null,
    val reduceMotion: Boolean? = null,
    /** The OS text size as a factor of normal (1.0), kept in [SystemAccessibility.MIN_TEXT_SCALE] to [SystemAccessibility.MAX_TEXT_SCALE]. */
    val textScale: Float = 1f,
)

/**
 * The OS accessibility settings the app takes as its defaults while the viewer has not chosen
 * (WP-50): high contrast, reduce motion and text size, kept current while the app runs like
 * [SystemAppearance] does for dark and light, from a daemon thread that looks every
 * [POLL_MS]. A reading that fails is "unknown" and changes nothing.
 *
 * Windows: the registry's `HighContrast\Flags`, `SystemParametersInfo(SPI_GETCLIENTAREAANIMATION)`
 * (the Animation effects switch) and `Accessibility\TextScaleFactor` (the Text size slider, in
 * percent). Linux (GNOME and what follows its settings): `gsettings` `a11y.interface high-contrast`,
 * `interface enable-animations` and `text-scaling-factor`, a process each, looked at every
 * [PROCESS_POLL_MS]. macOS: `defaults read com.apple.universalaccess` `increaseContrast` and
 * `reduceMotion`; macOS has no text size setting that applications follow.
 */
object SystemAccessibility {

    const val MIN_TEXT_SCALE = 0.75f
    const val MAX_TEXT_SCALE = 2.25f

    private val state = MutableStateFlow(AccessibilityReading())
    private var started = false

    /** The latest reading; starts the poll on first use. */
    val reading: StateFlow<AccessibilityReading>
        get() {
            start()
            return state.asStateFlow()
        }

    @Synchronized
    private fun start() {
        if (started) return
        started = true
        state.value = runCatching { read() }.getOrDefault(AccessibilityReading())
        Thread({
            while (true) {
                runCatching { TimeUnit.MILLISECONDS.sleep(POLL_MS) }.onFailure { return@Thread }
                val now = runCatching { read() }.getOrNull() ?: continue
                if (now != state.value) {
                    Logger.i(TAG, "system accessibility is now $now")
                    state.value = now
                }
            }
        }, "system-accessibility").apply { isDaemon = true }.start()
    }

    private fun read(): AccessibilityReading = when {
        OsInfo.isWindows -> windows()
        OsInfo.isMac -> mac()
        else -> linux()
    }

    // ---- Windows -----------------------------------------------------------------------------

    private interface User32Spi : Library {
        fun SystemParametersInfoW(action: Int, param: Int, value: IntByReference, winIni: Int): Boolean
    }

    private val user32: User32Spi? by lazy { runCatching { Native.load("user32", User32Spi::class.java) }.getOrNull() }

    private fun windows(): AccessibilityReading {
        val hc = runCatching {
            Advapi32Util.registryGetStringValue(WinReg.HKEY_CURRENT_USER, "Control Panel\\Accessibility\\HighContrast", "Flags")
        }.getOrNull()?.let(::windowsHighContrast)
        val animations = runCatching {
            val out = IntByReference()
            if (user32?.SystemParametersInfoW(SPI_GETCLIENTAREAANIMATION, 0, out, 0) == true) out.value != 0 else null
        }.getOrNull()
        val text = runCatching {
            Advapi32Util.registryGetIntValue(WinReg.HKEY_CURRENT_USER, "Software\\Microsoft\\Accessibility", "TextScaleFactor")
        }.getOrNull()?.let(::windowsTextScale) ?: 1f
        return AccessibilityReading(highContrast = hc, reduceMotion = animations?.not(), textScale = text)
    }

    // ---- Linux and macOS: a process per key, so looked at less often ---------------------------

    private var lastProcessNanos = 0L
    private var lastProcessReading = AccessibilityReading()

    private fun linux(): AccessibilityReading = throttled {
        fun gs(schema: String, key: String) = run("gsettings", "get", schema, key)
        AccessibilityReading(
            highContrast = gsettingsBool(gs("org.gnome.desktop.a11y.interface", "high-contrast")),
            reduceMotion = gsettingsBool(gs("org.gnome.desktop.interface", "enable-animations"))?.not(),
            textScale = gsettingsTextScale(gs("org.gnome.desktop.interface", "text-scaling-factor")),
        )
    }

    private fun mac(): AccessibilityReading = throttled {
        fun defaults(key: String) = run("defaults", "read", "com.apple.universalaccess", key)
        AccessibilityReading(
            highContrast = defaultsBool(defaults("increaseContrast")),
            reduceMotion = defaultsBool(defaults("reduceMotion")),
        )
    }

    private inline fun throttled(look: () -> AccessibilityReading): AccessibilityReading {
        val now = System.nanoTime()
        if (lastProcessNanos == 0L || now - lastProcessNanos > PROCESS_POLL_MS * 1_000_000L) {
            lastProcessNanos = now
            lastProcessReading = look()
        }
        return lastProcessReading
    }

    private fun run(vararg command: String): String? = runCatching {
        val process = ProcessBuilder(*command).redirectErrorStream(true).start()
        if (!process.waitFor(2, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            return@runCatching null
        }
        if (process.exitValue() != 0) null else process.inputStream.bufferedReader().readText()
    }.getOrNull()

    // ---- parsers, pure, for the tests ----------------------------------------------------------

    /** The registry's HighContrast Flags, a string of a number: bit 1 (HCF_HIGHCONTRASTON) is on. */
    internal fun windowsHighContrast(flags: String?): Boolean? = flags?.trim()?.toIntOrNull()?.let { it and 1 != 0 }

    /** The Text size slider's percent (100 to 225) as a factor, kept in range; anything else is normal. */
    internal fun windowsTextScale(percent: Int?): Float =
        if (percent == null || percent <= 0) 1f else (percent / 100f).coerceIn(MIN_TEXT_SCALE, MAX_TEXT_SCALE)

    /** `true` or `false`, which is what gsettings prints for a boolean; else unknown. */
    internal fun gsettingsBool(output: String?): Boolean? = when (output?.trim()) {
        "true" -> true
        "false" -> false
        else -> null
    }

    /** `1.25`, or `uint32 1`-like prefixes: the last word as a number, kept in range; else normal. */
    internal fun gsettingsTextScale(output: String?): Float =
        output?.trim()?.substringAfterLast(' ')?.toFloatOrNull()?.takeIf { it.isFinite() && it > 0f }
            ?.coerceIn(MIN_TEXT_SCALE, MAX_TEXT_SCALE) ?: 1f

    /** `defaults read` prints 1 or 0 (or true, false); an unset key fails and is unknown. */
    internal fun defaultsBool(output: String?): Boolean? = when (output?.trim()?.lowercase()) {
        "1", "true" -> true
        "0", "false" -> false
        else -> null
    }

    private const val SPI_GETCLIENTAREAANIMATION = 0x1042
    private const val POLL_MS = 2_000L
    private const val PROCESS_POLL_MS = 15_000L
    private const val TAG = "SystemAccessibility"
}
