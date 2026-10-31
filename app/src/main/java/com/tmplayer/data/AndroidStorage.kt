package com.tmplayer.data

import android.content.Context
import android.os.Build
import android.os.storage.StorageManager
import androidx.annotation.ChecksSdkIntAtLeast
import com.tmplayer.i18n.L
import java.io.File

/**
 * Where an Android download can live besides the app's own internal storage (HX26): any other
 * volume the device exposes an app-specific folder on, found with no permission and no system
 * picker. That is also exactly the fallback a television's remote needs, since many TV boxes and
 * launchers have no folder picker at all, so the phone and the television share this one list
 * rather than the phone getting a document picker the television cannot reach.
 *
 * [Context.getExternalFilesDirs] answers one folder per mounted volume without asking for
 * anything, at every level back to the API 23 floor: index 0 is the primary external storage,
 * which is close enough to internal storage on every device this app runs on to leave out, and
 * anything after it is an SD card or a USB-OTG drive actually worth offering. A download's folder
 * inside whichever volume is chosen is shaped exactly as the desktop's is
 * ([StorageRelocationPlan.appFolder]), so that plan's decisions (writable, not nested, room
 * enough, adopted from an earlier choice) are exactly the ones asked here too.
 *
 * TDLib's cache and its database are never moved by any of this: only the Downloads folder is, so
 * the watch cache (recently played videos, streamed and trimmed by [WatchCache]) always stays on
 * internal storage, where [AndroidPaths.filesDir] has always put it.
 */
object AndroidStorage {

    /** A place downloads could move to: the internal default, or one more volume Android exposes. */
    data class Volume(
        /** Null for the internal default; the volume's own app-specific folder otherwise. */
        val root: File?,
        val label: String,
        val removable: Boolean,
    )

    /**
     * The chosen downloads folder, mirrored here from [SettingsStore.downloadRoot] so that
     * resolving a download's folder never waits on a DataStore read: blank, the folder it always
     * was. Kept in step by a collector started once at launch ([com.tmplayer.App]); changed again
     * the moment [SettingsStore.setDownloadRoot] is called, from the same place.
     */
    @Volatile var currentRoot: String = ""

    /**
     * The internal default, first, then every other volume this device has mounted right now.
     * Nothing past the first entry on a device with no card or drive at all, which is when
     * Settings leaves the whole row out: there is nothing to choose between.
     */
    fun volumes(context: Context): List<Volume> {
        val app = context.applicationContext
        val extra = runCatching { app.getExternalFilesDirs(null) }.getOrDefault(emptyArray())
        val manager = storageManager(app)
        val cards = extra.drop(1).filterNotNull().mapIndexed { index, dir ->
            Volume(root = dir, label = labelFor(app, manager, dir, index), removable = true)
        }
        return listOf(Volume(root = null, label = L.storageAndroidInternal, removable = false)) + cards
    }

    @ChecksSdkIntAtLeast(api = Build.VERSION_CODES.N)
    private fun storageManager(context: Context): StorageManager? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            context.getSystemService(Context.STORAGE_SERVICE) as? StorageManager
        } else {
            null
        }

    /**
     * The volume's own name ("SD card", a manufacturer's own words for a USB drive), from
     * [StorageManager] where Android answers it (API 24 and later); a plain "SD card" or "USB
     * drive" numbered by [index] otherwise, which is every television stick down to the API 23
     * floor (a Fire TV Stick 4K on Fire OS 6 among them).
     */
    private fun labelFor(context: Context, manager: StorageManager?, dir: File, index: Int): String {
        val described = manager?.let { sm -> runCatching { describe(context, sm, dir) }.getOrNull() }
        if (!described.isNullOrBlank()) return described
        return L.storageAndroidRemovable(index + 1)
    }

    @ChecksSdkIntAtLeast(api = Build.VERSION_CODES.N)
    private fun describe(context: Context, manager: StorageManager, dir: File): String? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            manager.getStorageVolume(dir)?.getDescription(context)
        } else {
            null
        }

    /** What the disk says about [root] as a candidate downloads folder, for [StorageRelocationPlan.validate]. */
    fun inspect(context: Context, root: File): StorageRelocationPlan.Candidate {
        val base = StorageRelocationPlan.appFolder(root)
        val existed = base.exists()
        val writable = runCatching {
            if (!base.isDirectory && !base.mkdirs()) return@runCatching false
            val probe = File.createTempFile(".tmplayer-", ".probe", base)
            probe.delete()
            true
        }.getOrDefault(false)
        if (!existed) runCatching { base.delete() }
        return StorageRelocationPlan.Candidate(
            root = root,
            writable = writable,
            // No network store ever reaches here (every volume is local), so the type is left
            // blank; StorageRelocationPlan only reads it to warn about a network drive.
            usableBytes = DiskSpace.read(context, root).freeBytes,
            markerExists = File(base, StorageRelocationPlan.MARKER).exists(),
        )
    }

    /** `<root>/TMPlayer/downloads`, the folder a chosen volume's downloads move into. */
    fun downloadsDirOf(root: File): File = File(StorageRelocationPlan.appFolder(root), AndroidPaths.DOWNLOADS)

    /**
     * Whether [root] can still be written to right now: still mounted (an SD card is not simply
     * unplugged), still writable. Asked before every use of a chosen volume, since Android gives
     * no warning when one goes.
     */
    fun isUsable(root: File): Boolean = runCatching {
        val base = StorageRelocationPlan.appFolder(root)
        (base.isDirectory || base.mkdirs()) && base.canWrite()
    }.getOrDefault(false)

    /**
     * Where downloads are kept right now: [currentRoot]'s folder when it is set and the volume is
     * still there to write to, the app's own internal storage otherwise (which is also where a
     * card or drive that went missing falls back to, rather than failing every download until the
     * viewer notices).
     */
    fun downloadsDir(context: Context): File {
        val chosen = currentRoot.takeIf { it.isNotBlank() }?.let(::File)
        if (chosen != null && isUsable(chosen)) return downloadsDirOf(chosen)
        return File(context.applicationContext.filesDir, AndroidPaths.DOWNLOADS)
    }

    /** Whether the chosen volume (if any) is reachable right now, for Settings' own warning line. */
    fun chosenIsReachable(): Boolean {
        val chosen = currentRoot.takeIf { it.isNotBlank() }?.let(::File) ?: return true
        return isUsable(chosen)
    }
}
