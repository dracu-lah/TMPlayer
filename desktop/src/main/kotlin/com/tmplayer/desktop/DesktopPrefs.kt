package com.tmplayer.desktop

import com.tmplayer.desktop.os.OsInfo
import com.tmplayer.platform.Logger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Properties

/** The desktop only settings, as one immutable value so a screen can collect them in one go. */
data class DesktopSettings(
    val volume: Int = 100,
    val muted: Boolean = false,
    val downmix: Boolean = false,
    /** The wheel over the picture seeks instead of changing the volume. */
    val wheelSeeks: Boolean = false,
    /** Decode in software even where the OS has a hardware decoder (mpv `hwdec=no`). */
    val softwareDecoding: Boolean = defaultSoftwareDecoding(),
    /** Ask GitHub, at most once a day, whether a newer TMPlayer is out. */
    val checkForUpdates: Boolean = true,
    /** When that was last asked, epoch milliseconds; 0 for never. */
    val lastUpdateCheck: Long = 0,
    /** A release the viewer closed the notice for, so the same one is not offered every launch. */
    val dismissedRelease: String = "",
) {
    companion object {
        /**
         * Software on Linux, hardware (mpv's `auto-safe`) on Windows and macOS.
         *
         * The libmpv that mediamp bundles carries its own libva, which looks for VA drivers in
         * Debian's directory: on Fedora, Arch and openSUSE it finds none, and on a distribution
         * where it does find one the driver was built against another libva. mpv falls back to
         * software when initialisation fails, but a driver that initialises and then hands back
         * green or torn frames is a failure nobody is told about. A modern CPU decodes 1080p H.264
         * and HEVC without effort, so Linux starts in software and the setting turns hardware on.
         * Windows (D3D11VA) and macOS (VideoToolbox) decoders are part of the OS, not a bundled
         * guess, and `auto-safe` only picks those whitelisted as reliable.
         */
        fun defaultSoftwareDecoding(): Boolean = OsInfo.isLinux
    }
}

/**
 * The settings only the desktop has, in `desktop.properties` beside the shared settings store.
 *
 * Kept out of [com.tmplayer.data.SettingsStore] on purpose: that store is the phone's too, and
 * volume, wheel and decoder choices mean nothing there. A properties file is plain enough to read
 * by hand when a tester reports something odd, and every write replaces it in one rename.
 */
class DesktopPrefs(private val file: File) {

    private val lock = Any()
    private val _state = MutableStateFlow(read())
    val state: StateFlow<DesktopSettings> = _state.asStateFlow()

    val now: DesktopSettings get() = _state.value

    /** Applies [change] and writes the file if anything changed. */
    fun update(change: (DesktopSettings) -> DesktopSettings) {
        synchronized(lock) {
            val before = _state.value
            val after = change(before).let { it.copy(volume = it.volume.coerceIn(0, 100)) }
            if (after == before) return
            _state.value = after
            write(after)
        }
    }

    private fun read(): DesktopSettings {
        val p = Properties()
        runCatching { if (file.isFile) file.inputStream().use { p.load(it) } }
            .onFailure { Logger.w(TAG, "could not read ${file.name}: ${it.message}") }
        val d = DesktopSettings()
        fun bool(key: String, default: Boolean) = p.getProperty(key)?.toBooleanStrictOrNull() ?: default
        return DesktopSettings(
            volume = p.getProperty("volume")?.toIntOrNull()?.coerceIn(0, 100) ?: d.volume,
            muted = bool("muted", d.muted),
            downmix = bool("downmix", d.downmix),
            wheelSeeks = bool("wheel_seeks", d.wheelSeeks),
            softwareDecoding = bool("software_decoding", d.softwareDecoding),
            checkForUpdates = bool("check_for_updates", d.checkForUpdates),
            lastUpdateCheck = p.getProperty("last_update_check")?.toLongOrNull() ?: d.lastUpdateCheck,
            dismissedRelease = p.getProperty("dismissed_release") ?: d.dismissedRelease,
        )
    }

    private fun write(s: DesktopSettings) {
        val p = Properties().apply {
            setProperty("volume", s.volume.toString())
            setProperty("muted", s.muted.toString())
            setProperty("downmix", s.downmix.toString())
            setProperty("wheel_seeks", s.wheelSeeks.toString())
            setProperty("software_decoding", s.softwareDecoding.toString())
            setProperty("check_for_updates", s.checkForUpdates.toString())
            setProperty("last_update_check", s.lastUpdateCheck.toString())
            setProperty("dismissed_release", s.dismissedRelease)
        }
        runCatching {
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.outputStream().use { p.store(it, "TMPlayer desktop settings") }
            // REPLACE_EXISTING, because a plain rename onto an existing file fails on Windows.
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        }.recoverCatching {
            val tmp = File(file.parentFile, file.name + ".tmp")
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }.onFailure { Logger.w(TAG, "could not save ${file.name}: ${it.message}") }
    }

    private companion object {
        const val TAG = "DesktopPrefs"
    }
}
