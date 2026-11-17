package com.tmplayer.desktop.os

import com.tmplayer.desktop.BuildInfo
import com.tmplayer.desktop.DesktopPaths
import com.tmplayer.platform.Logger
import java.io.File
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.channels.OverlappingFileLockException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes

/**
 * Replaces the libmpv runtime mediamp unpacked into [DesktopPaths.nativeDir] when this build ships a
 * different one (issue #30 follow-up).
 *
 * mediamp never overwrites a file that is already there, so a viewer who ran an older TMPlayer would
 * keep its glibc 2.38 libraries after an update to a build that ships the glibc 2.35 ones, and
 * playback would stay broken. [prepare] runs before mediamp loads anything: it compares the marker
 * file [MARKER] with [BuildInfo.NATIVES_ID] (a hash of `natives/linux-2-35/PINS.txt`, so it changes
 * exactly when the shipped libraries do, not with the app version) and, when they differ, removes
 * the files the runtime's own manifest lists, so mediamp unpacks the fresh copy, then writes the marker.
 *
 * Only Linux x64, and only a build that swapped the pinned natives in (a blank id): Windows, macOS and
 * a plain build are left alone. Nothing here may take the app down, so every failure is a logged
 * [Outcome] and mediamp goes on with whatever is there.
 *
 *  - Only names from the manifest are touched, each a plain file name inside the folder. A symlink
 *    is unlinked, never followed, and nothing outside the folder is read or deleted.
 *  - A temporary marker is written first. If it cannot be (read only folder, full disk) nothing is
 *    removed, so a start that cannot remember the refresh does not redo it on every launch.
 *  - A lock file serialises two instances starting at once; the one that cannot get it in
 *    [LOCK_WAIT_MS] skips, and mediamp carries on.
 */
object NativeRefresh {

    private const val TAG = "NativeRefresh"

    /** The file in the runtime folder that says which runtime it holds. */
    const val MARKER = "tmplayer-natives.id"
    const val LOCK = "tmplayer-natives.lock"
    private const val MARKER_TMP = "tmplayer-natives.id.tmp"

    /** The marker is a hash; anything longer is not ours and counts as different. */
    private const val MARKER_MAX_BYTES = 256

    private const val MANIFEST_MAX_BYTES = 64 * 1024
    private const val MANIFEST_MAX_NAMES = 1000
    private const val LOCK_WAIT_MS = 10_000L

    private val SAFE_NAME = Regex("[A-Za-z0-9._+~-]{1,128}")

    enum class Outcome {
        /** Not Linux x64, or the build carries no pinned natives: nothing to compare. */
        NotApplicable,

        /** The marker matched: the folder was left alone. */
        Current,

        /** Nothing of the runtime was there (a fresh install): the marker was written. */
        Fresh,

        /** An older or foreign runtime was removed and the marker written. */
        Refreshed,

        /** Another instance held the lock for too long, so this run left the folder to it. */
        Busy,

        /** Something failed (see the log line); the folder was left as far as it got, mediamp goes on. */
        Failed,
    }

    /** Called before mediamp's `prepareLibraries`. */
    fun prepare(dir: File = DesktopPaths.nativeDir) {
        if (!OsInfo.isLinux || OsInfo.archTag != "x64") return
        runCatching { refresh(dir, BuildInfo.NATIVES_ID, manifest()) }
            .onFailure { Logger.w(TAG, "Could not check the mpv runtime folder, carrying on", it) }
    }

    /**
     * The whole step, with everything it depends on given, so a test can run it over a temp folder.
     * [id] blank means no check; [names] is the runtime's manifest (untrusted: filtered here).
     */
    fun refresh(dir: File, id: String, names: List<String>?, lockWaitMs: Long = LOCK_WAIT_MS): Outcome {
        val cleanId = id.trim()
        if (cleanId.isEmpty()) return Outcome.NotApplicable
        if (names == null) {
            Logger.w(TAG, "The mpv runtime's file list is not on the classpath, leaving ${dir.path} as it is")
            return Outcome.Failed
        }
        // In process callers go one at a time; the lock file does the same across processes.
        synchronized(this) {
            return try {
                if (!dir.isDirectory) {
                    Logger.w(TAG, "${dir.path} is not a folder, leaving the mpv runtime to mediamp")
                    return Outcome.Failed
                }
                val safe = safeNames(names)
                withLock(dir, lockWaitMs) { refreshLocked(dir, cleanId, safe) } ?: Outcome.Busy.also {
                    Logger.w(TAG, "Another TMPlayer is checking ${dir.path}, skipping this time")
                }
            } catch (e: Exception) {
                Logger.w(TAG, "Could not check the mpv runtime in ${dir.path} (${e.javaClass.simpleName}: ${e.message}), carrying on", e)
                Outcome.Failed
            }
        }
    }

    private fun refreshLocked(dir: File, id: String, names: List<String>): Outcome {
        val marker = File(dir, MARKER).toPath()
        if (readMarker(marker) == id) return Outcome.Current

        val present = names.filter { Files.exists(File(dir, it).toPath(), LinkOption.NOFOLLOW_LINKS) }
        val tmp = File(dir, MARKER_TMP).toPath()
        // Prove the folder can be written (and has room) before removing anything.
        try {
            Files.write(tmp, id.toByteArray(Charsets.UTF_8))
        } catch (e: IOException) {
            runCatching { Files.deleteIfExists(tmp) }
            Logger.w(TAG, "Cannot write in ${dir.path} (${e.javaClass.simpleName}: ${e.message}), keeping the mpv runtime there as it is", e)
            return Outcome.Failed
        }

        var failed = 0
        for (name in present) if (!remove(File(dir, name).toPath())) failed++
        if (failed > 0) {
            runCatching { Files.deleteIfExists(tmp) }
            Logger.w(TAG, "Could not remove $failed of ${present.size} old mpv files from ${dir.path}, the marker is not written")
            return Outcome.Failed
        }
        try {
            try {
                Files.move(tmp, marker, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(tmp, marker, StandardCopyOption.REPLACE_EXISTING)
            }
        } catch (e: IOException) {
            runCatching { Files.deleteIfExists(tmp) }
            Logger.w(TAG, "Removed ${present.size} old mpv files but could not write $MARKER (${e.message})", e)
            return Outcome.Failed
        }
        return if (present.isEmpty()) {
            Logger.i(TAG, "Fresh mpv runtime folder ${dir.path}, marked ${id.take(12)}")
            Outcome.Fresh
        } else {
            Logger.i(TAG, "Removed ${present.size} files of an older mpv runtime from ${dir.path}, mediamp unpacks the current one")
            Outcome.Refreshed
        }
    }

    /** At most [MARKER_MAX_BYTES] of the file, as text; null when absent, not a regular file, too long or unreadable. */
    private fun readMarker(marker: Path): String? = try {
        if (!Files.isRegularFile(marker, LinkOption.NOFOLLOW_LINKS)) {
            null
        } else {
            String(Files.newInputStream(marker).use { it.readNBytes(MARKER_MAX_BYTES + 1) }, Charsets.UTF_8)
                .takeIf { it.length <= MARKER_MAX_BYTES }?.trim()
        }
    } catch (_: Exception) {
        null
    }

    /** Only plain names: no path parts, no `.` or `..`, not our own files, no duplicates. */
    internal fun safeNames(names: List<String>): List<String> =
        names.asSequence().map { it.trim() }
            .filter { SAFE_NAME.matches(it) && it != "." && it != ".." && it != MARKER && it != LOCK && it != MARKER_TMP }
            .distinct().take(MANIFEST_MAX_NAMES).toList()

    /** Removes a file or link (the link itself, never its target) or a folder tree, not following links. */
    private fun remove(path: Path): Boolean = try {
        if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
            Files.walkFileTree(path, object : SimpleFileVisitor<Path>() {
                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    Files.deleteIfExists(file)
                    return FileVisitResult.CONTINUE
                }

                override fun postVisitDirectory(dir: Path, exc: IOException?): FileVisitResult {
                    Files.deleteIfExists(dir)
                    return FileVisitResult.CONTINUE
                }
            })
        } else {
            Files.deleteIfExists(path)
        }
        true
    } catch (_: Exception) {
        false
    }

    /** Runs [block] holding the folder's lock file; null when it could not be had within [waitMs]. */
    private fun <T> withLock(dir: File, waitMs: Long, block: () -> T): T? {
        // No lock file means the folder cannot be written, so the step cannot run safely: the caller logs it.
        val channel = FileChannel.open(File(dir, LOCK).toPath(), StandardOpenOption.CREATE, StandardOpenOption.WRITE)
        channel.use { ch ->
            val deadline = System.nanoTime() + waitMs * 1_000_000
            while (true) {
                val lock = try {
                    ch.tryLock()
                } catch (_: OverlappingFileLockException) {
                    null
                }
                if (lock != null) return lock.use { block() }
                if (System.nanoTime() >= deadline) return null
                Thread.sleep(50)
            }
        }
    }

    /** The runtime's own list of what it unpacks, from its jar; null when the jar lacks it. */
    private fun manifest(): List<String>? = runCatching {
        val name = "mpv-natives-${OsInfo.osTag}-${OsInfo.archTag}.txt"
        NativeRefresh::class.java.classLoader.getResourceAsStream(name)?.use { input ->
            String(input.readNBytes(MANIFEST_MAX_BYTES), Charsets.UTF_8).lines()
        }
    }.getOrNull()
}
