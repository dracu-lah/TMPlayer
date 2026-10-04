package com.tmplayer.platform

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * The one time rename of TDLib's files directory from `tdlib-files`, its name in every release up
 * to 1.19, to `cache`, which is what it is now that downloads live elsewhere.
 *
 * Run before TDLib starts, on both platforms. The old name is left behind as a symbolic link to
 * the new one, and that link is the point of doing it this way rather than with a bare rename.
 * TDLib's file database records where each file is by full path, so after a bare rename every
 * file it knows would point into a directory that is no longer there: TDLib only notices a
 * missing file lazily, and until it does it hands out the old path, which TMPlayer's stray sweep
 * would then fail to match against the files it finds under the new name and delete them, legacy
 * downloads included. Through the link the old paths still lead to the same bytes, and every path
 * TMPlayer compares is resolved first, so both spellings come out the same.
 *
 * Where a link cannot be made (Windows without the right to create one, or a file system without
 * them), the rename is undone and the old name kept, which is exactly what the install had before.
 */
object CacheDirRename {

    /** The name TDLib's files directory had before it was called the cache. */
    const val LEGACY_NAME = "tdlib-files"

    /** What it is called now. */
    const val NAME = "cache"

    /**
     * Moves [legacy] to [current] once, leaving a link at [legacy], and returns the directory to
     * give TDLib: [current] when the move happened or was not needed, [legacy] when it could not
     * be done safely and the install carries on under the old name.
     */
    fun adopt(legacy: File, current: File): File {
        val legacyPath = legacy.toPath()
        // Already done on an earlier launch: the old name is the link this left.
        if (Files.isSymbolicLink(legacyPath)) return current
        if (!legacy.exists()) return current
        if (current.exists()) {
            val empty = current.isDirectory && current.list()?.isEmpty() == true
            if (!empty || !current.delete()) {
                // Both names hold something: a downgrade and upgrade in between, perhaps. The old
                // one is what TDLib's database knows, so it stays in use and nothing is merged.
                Logger.w(TAG, "Both ${legacy.name} and ${current.name} exist; keeping ${legacy.name}")
                return legacy
            }
        }
        try {
            Files.move(legacyPath, current.toPath(), StandardCopyOption.ATOMIC_MOVE)
        } catch (failure: Exception) {
            Logger.w(TAG, "Could not rename ${legacy.name} to ${current.name}; keeping the old name", failure)
            return legacy
        }
        try {
            // Relative, so the pair can be moved together and the link still holds.
            Files.createSymbolicLink(legacyPath, current.toPath().fileName)
            Logger.i(TAG, "Renamed ${legacy.name} to ${current.name}")
            return current
        } catch (failure: Exception) {
            Logger.w(TAG, "Could not link ${legacy.name} to ${current.name}; putting the old name back", failure)
        }
        return try {
            Files.move(current.toPath(), legacyPath, StandardCopyOption.ATOMIC_MOVE)
            legacy
        } catch (failure: Exception) {
            // Neither the link nor the way back: the bytes are under the new name, and TDLib will
            // notice its old paths are gone and fetch again. Rare enough not to be worth more.
            Logger.w(TAG, "Could not put ${legacy.name} back either; carrying on under ${current.name}", failure)
            current
        }
    }

    private const val TAG = "CacheDirRename"
}
