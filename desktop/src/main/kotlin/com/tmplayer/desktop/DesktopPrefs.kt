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
    /**
     * The folder the viewer chose to hold TMPlayer's files, or blank for the default folders.
     *
     * When set, the cache, downloads and update staging all live under `<root>/TMPlayer/`; see
     * [DesktopPaths.layout]. Read once at launch, before TDLib starts.
     */
    val storageRoot: String = "",
    /** How much the cache may hold before the least recently played video goes. */
    val cacheLimitBytes: Long = DEFAULT_CACHE_LIMIT_BYTES,
    /**
     * A move to a new storage location that began and has not finished, encoded by
     * [com.tmplayer.data.StorageRelocationPlan.Pending], or blank. Written before the first
     * download moves and cleared after the last, so the next launch can finish what a crash left.
     */
    val storageRootPending: String = "",
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

        /** A computer has disk to spare, and ten gigabytes is a handful of films played again. */
        const val DEFAULT_CACHE_LIMIT_BYTES = 10L * 1024 * 1024 * 1024

        /** The Cache limit stepper's range and step, in gigabytes. */
        const val MIN_CACHE_LIMIT_GB = 2
        const val MAX_CACHE_LIMIT_GB = 100

        /**
         * The next Cache limit up or down from [bytes]: one gigabyte at a time up to ten, then five,
         * then ten past fifty, so the far end of the range is a few presses away rather than ninety.
         */
        fun stepCacheLimit(bytes: Long, direction: Int): Long {
            val gb = (bytes / GB).toInt().coerceIn(MIN_CACHE_LIMIT_GB, MAX_CACHE_LIMIT_GB)
            val stops = (MIN_CACHE_LIMIT_GB..10) + (15..50 step 5) + (60..MAX_CACHE_LIMIT_GB step 10)
            val next = if (direction > 0) stops.firstOrNull { it > gb } ?: MAX_CACHE_LIMIT_GB
            else stops.lastOrNull { it < gb } ?: MIN_CACHE_LIMIT_GB
            return next * GB
        }

        private const val GB = 1024L * 1024 * 1024
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

    /**
     * The update check's settings as 1.19 and older kept them here, before they moved to the shared
     * store beside the phone's. Hands them to [into] once and then rewrites the file without them;
     * does nothing when there is nothing left to move.
     */
    suspend fun migrateUpdatePrefs(into: suspend (LegacyUpdatePrefs) -> Unit) {
        val p = Properties()
        runCatching { if (file.isFile) file.inputStream().use { p.load(it) } }
        if (LEGACY_UPDATE_KEYS.none(p::containsKey)) return
        into(
            LegacyUpdatePrefs(
                notify = p.getProperty("check_for_updates")?.toBooleanStrictOrNull(),
                lastCheck = p.getProperty("last_update_check")?.toLongOrNull() ?: 0L,
                dismissed = p.getProperty("dismissed_release").orEmpty(),
            ),
        )
        synchronized(lock) { write(_state.value) }
    }

    private fun read(): DesktopSettings = read(file)

    private fun write(s: DesktopSettings) {
        val p = Properties().apply {
            setProperty("volume", s.volume.toString())
            setProperty("muted", s.muted.toString())
            setProperty("downmix", s.downmix.toString())
            setProperty("wheel_seeks", s.wheelSeeks.toString())
            setProperty("software_decoding", s.softwareDecoding.toString())
            setProperty("storage_root", s.storageRoot)
            setProperty("cache_limit_bytes", s.cacheLimitBytes.toString())
            setProperty("storage_root_pending", s.storageRootPending)
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

    companion object {
        private const val TAG = "DesktopPrefs"
        private val LEGACY_UPDATE_KEYS = listOf("check_for_updates", "last_update_check", "dismissed_release")

        /**
         * Reads [file] without holding it, for code that needs a setting before the app's one
         * [DesktopPrefs] exists: [DesktopPaths] wants the storage root before TDLib starts.
         */
        fun read(file: File): DesktopSettings {
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
                storageRoot = p.getProperty("storage_root")?.trim() ?: d.storageRoot,
                cacheLimitBytes = p.getProperty("cache_limit_bytes")?.toLongOrNull()?.takeIf { it > 0 }
                    ?: d.cacheLimitBytes,
                storageRootPending = p.getProperty("storage_root_pending")?.trim() ?: d.storageRootPending,
            )
        }
    }
}

/** See [DesktopPrefs.migrateUpdatePrefs]. A dismissed release is what is now a skipped one. */
data class LegacyUpdatePrefs(val notify: Boolean?, val lastCheck: Long, val dismissed: String)
