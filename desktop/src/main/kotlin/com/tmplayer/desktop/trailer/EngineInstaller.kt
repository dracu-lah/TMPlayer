package com.tmplayer.desktop.trailer

import com.tmplayer.platform.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.DigestOutputStream
import java.security.MessageDigest
import java.util.zip.GZIPInputStream
import java.util.zip.ZipInputStream
import kotlin.coroutines.coroutineContext

/** Free space on the drive holding a folder. Null when it cannot be read. */
fun interface FreeSpace {
    fun usableBytes(dir: File): Long?

    companion object {
        /** The drive of [dir], or of its nearest folder that exists (the install folder is made later). */
        val System = FreeSpace { dir ->
            var probe: File? = dir.absoluteFile
            while (probe != null && !probe.exists()) probe = probe.parentFile
            probe?.let { runCatching { Files.getFileStore(it.toPath()).usableSpace }.getOrNull() }?.takeIf { it >= 0 }
        }
    }
}

/** Fetches [url] into [sink], saying how many bytes have arrived so far. Throws on any failure; honours cancellation. */
fun interface EngineDownloader {
    suspend fun download(url: String, sink: OutputStream, onBytes: (Long) -> Unit)

    companion object {
        /** Plain HTTPS, following redirects (GitHub's release links redirect to its file host). */
        val Http = EngineDownloader { url, sink, onBytes ->
            withContext(Dispatchers.IO) {
                var address = URI(url)
                var connection: HttpURLConnection? = null
                // Only https, at most a few hops, and never down to http.
                for (hop in 0..MAX_REDIRECTS) {
                    if (address.scheme != "https") throw IOException("not https: ${address.host}")
                    val c = address.toURL().openConnection() as HttpURLConnection
                    c.instanceFollowRedirects = false
                    c.connectTimeout = TIMEOUT_MS
                    c.readTimeout = TIMEOUT_MS
                    c.setRequestProperty("User-Agent", "TMPlayer")
                    val code = c.responseCode
                    if (code in 300..399) {
                        val next = c.getHeaderField("Location")?.let { runCatching { address.resolve(it) }.getOrNull() }
                        c.disconnect()
                        address = next ?: throw IOException("bad redirect from ${address.host}")
                        continue
                    }
                    if (code != HttpURLConnection.HTTP_OK) {
                        c.disconnect()
                        throw IOException("HTTP $code from ${address.host}")
                    }
                    connection = c
                    break
                }
                val open = connection ?: throw IOException("too many redirects for $url")
                // A cancel breaks a read that is waiting on the network, not only the next loop.
                val job = coroutineContext[kotlinx.coroutines.Job]
                val unhook = job?.invokeOnCompletion { runCatching { open.disconnect() } }
                try {
                    open.inputStream.use { input ->
                        val buffer = ByteArray(BUFFER)
                        var total = 0L
                        while (true) {
                            coroutineContext.ensureActive()
                            val n = input.read(buffer)
                            if (n < 0) break
                            sink.write(buffer, 0, n)
                            total += n
                            onBytes(total)
                        }
                    }
                } finally {
                    unhook?.dispose()
                    open.disconnect()
                }
            }
        }

        private const val MAX_REDIRECTS = 5
        private const val TIMEOUT_MS = 30_000
        private const val BUFFER = 64 * 1024
    }
}

/** Where an install stands, for the progress dialog. */
sealed interface EngineProgress {
    data class Downloading(val done: Long, val total: Long) : EngineProgress
    data class Unpacking(val done: Long, val total: Long) : EngineProgress
}

/** What the consent dialog offers: the folder it would go in, and whether it fits there. */
sealed interface EngineOffer {
    val native: CefNative
    val dir: File

    /** What it takes at the peak: the download and the unpacked folder side by side, and some room to spare. */
    val neededBytes: Long get() = native.bytes + native.unpackedBytes + EngineInstaller.HEADROOM_BYTES

    data class Fits(override val native: CefNative, override val dir: File, val freeBytes: Long) : EngineOffer
    data class NoRoom(override val native: CefNative, override val dir: File, val freeBytes: Long) : EngineOffer
    data class SpaceUnknown(override val native: CefNative, override val dir: File) : EngineOffer
}

/** The natives that arrived were not the pinned file. */
class EngineChecksumMismatch(message: String) : IOException(message)

/** The drive filled up (or was found too full) on the way. */
class EngineNoRoom(message: String) : IOException(message)

/**
 * Downloads, checks and unpacks Chromium for in-app trailers (private/TRAILERS-PLAN.md, T4), and
 * finds an earlier install.
 *
 * Each place in [places] is a `browser` folder; an install is `<place>/<FOLDER>`, holding JCEF's
 * natives, its `build_meta.json` for [CefNatives.RELEASE_TAG] and `install.lock`, which is what
 * jcefmaven checks before it would fetch anything itself. Nothing is written until [install] is
 * called, which the consent dialog's Download button does.
 *
 * No partial files: the download goes to `<place>/.download.part`, is checked against the pinned
 * size and SHA-256, unpacks into `<place>/.unpack`, and only a whole, checked folder is renamed to
 * its real name. A cancel, a failure or a full drive removes both (also the leftovers of a run that
 * was killed, on the next look).
 */
class EngineInstaller(
    private val native: CefNative,
    /** Candidate `browser` folders, preferred first: the storage location's, then the default one. */
    private val places: () -> List<File>,
    private val freeSpace: FreeSpace = FreeSpace.System,
    private val downloader: EngineDownloader = EngineDownloader.Http,
) {

    /** A complete install of this build in any of the places, or null. Reads the disk; call off the main thread. */
    fun installed(): File? = places().map { File(it, FOLDER) }.firstOrNull { complete(it) }

    /** The first place it fits, else what the preferred place lacks. Cleans what an interrupted run left behind. */
    fun offer(): EngineOffer {
        val candidates = places().distinctBy { it.absoluteFile }
        candidates.forEach { sweep(it) }
        val needed = native.bytes + native.unpackedBytes + HEADROOM_BYTES
        var first: EngineOffer? = null
        for (place in candidates) {
            val free = freeSpace.usableBytes(place)
            val offer = when {
                free == null -> EngineOffer.SpaceUnknown(native, place)
                free >= needed -> return EngineOffer.Fits(native, place, free)
                else -> EngineOffer.NoRoom(native, place, free)
            }
            if (first == null) first = offer
        }
        return first ?: EngineOffer.SpaceUnknown(native, File(FOLDER))
    }

    /**
     * Downloads, checks and unpacks into [offer]'s folder and answers the install. Throws
     * [CancellationException] on cancel, [EngineChecksumMismatch], [EngineNoRoom] or another
     * [IOException] on failure; in every case nothing of it is left on disk.
     */
    suspend fun install(offer: EngineOffer.Fits, onProgress: (EngineProgress) -> Unit): File = withContext(Dispatchers.IO) {
        val place = offer.dir
        val part = File(place, PART)
        val staging = File(place, STAGING)
        val target = File(place, FOLDER)
        val cleanUp = Thread { part.delete(); staging.deleteRecursively() }
        // Quitting TMPlayer half way leaves nothing either.
        val hooked = runCatching { Runtime.getRuntime().addShutdownHook(cleanUp) }.isSuccess
        try {
            sweep(place)
            if (!place.isDirectory && !place.mkdirs()) throw IOException("cannot make ${place.path}")
            ensureRoom(place, native.bytes + native.unpackedBytes)
            download(part, onProgress)
            ensureRoom(place, native.unpackedBytes)
            unpack(part, staging, onProgress)
            part.delete()
            check(staging)
            if (!File(staging, LOCK).createNewFile()) throw IOException("cannot write the install lock")
            if (target.exists()) target.deleteRecursively()
            Files.move(staging.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
            removeOthers(keep = target)
            Logger.i(TAG, "installed ${native.platform} in ${target.path}")
            target
        } catch (e: Throwable) {
            part.delete()
            staging.deleteRecursively()
            if (e is CancellationException) Logger.i(TAG, "download cancelled") else Logger.w(TAG, "install failed: ${e.message}")
            throw if (e is IOException && isDiskFull(e)) EngineNoRoom(e.message ?: "disk full") else e
        } finally {
            if (hooked) runCatching { Runtime.getRuntime().removeShutdownHook(cleanUp) }
        }
    }

    private suspend fun download(part: File, onProgress: (EngineProgress) -> Unit) {
        var last: Throwable? = null
        for (url in native.urls) {
            coroutineContext.ensureActive()
            try {
                part.delete()
                val digest = MessageDigest.getInstance("SHA-256")
                var got = 0L
                DigestOutputStream(part.outputStream().buffered(), digest).use { out ->
                    downloader.download(url, out) { done ->
                        got = done
                        // A server sending more than the pinned file is not sending that file.
                        if (done > native.bytes) throw EngineChecksumMismatch("more than ${native.bytes} bytes from $url")
                        onProgress(EngineProgress.Downloading(done, native.bytes))
                    }
                }
                if (got != native.bytes || part.length() != native.bytes) {
                    throw EngineChecksumMismatch("${part.length()} bytes from $url, expected ${native.bytes}")
                }
                val sha = digest.digest().joinToString("") { "%02x".format(it) }
                if (sha != native.sha256) throw EngineChecksumMismatch("SHA-256 $sha from $url, expected ${native.sha256}")
                return
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                if (isDiskFull(e)) throw EngineNoRoom(e.message ?: "disk full")
                Logger.w(TAG, "download from ${URI(url).host} failed: ${e.message}")
                last = e
                part.delete()
            }
        }
        throw last ?: IOException("nowhere to download from")
    }

    /**
     * The jar's one `.tar.gz` into [staging]. Entries that would land outside it (an absolute path,
     * `..`) are refused, links are made only on macOS and Linux and only to a place inside it.
     */
    private suspend fun unpack(jar: File, staging: File, onProgress: (EngineProgress) -> Unit) {
        staging.deleteRecursively()
        if (!staging.mkdirs()) throw IOException("cannot make ${staging.path}")
        val root = staging.canonicalFile.toPath()
        ZipInputStream(jar.inputStream().buffered()).use { zip ->
            var entry = zip.nextEntry
            while (entry != null && !(entry.name.endsWith(".tar.gz") && !entry.isDirectory)) entry = zip.nextEntry
            if (entry == null) throw IOException("no .tar.gz in the download")
            TarArchiveInputStream(GZIPInputStream(zip, BUFFER)).use { tar ->
                var item: TarArchiveEntry? = tar.nextEntry
                var written = 0L
                val buffer = ByteArray(BUFFER)
                while (item != null) {
                    coroutineContext.ensureActive()
                    val path = root.resolve(item.name).normalize()
                    if (!path.startsWith(root)) throw IOException("archive entry outside the folder: ${item.name}")
                    val file = path.toFile()
                    when {
                        item.isDirectory -> if (!file.isDirectory && !file.mkdirs()) throw IOException("cannot make ${file.path}")
                        item.isSymbolicLink || item.isLink -> link(root, path, item)
                        item.isFile -> {
                            file.parentFile?.mkdirs()
                            file.outputStream().use { out ->
                                while (true) {
                                    val n = tar.read(buffer)
                                    if (n < 0) break
                                    out.write(buffer, 0, n)
                                    written += n
                                    if (written > native.unpackedBytes + UNPACK_SLACK) throw IOException("archive larger than expected")
                                }
                            }
                            if (item.mode and 0b001_001_001 != 0) file.setExecutable(true, false)
                            onProgress(EngineProgress.Unpacking(written, native.unpackedBytes))
                        }
                    }
                    item = tar.nextEntry
                }
            }
        }
    }

    private fun link(root: java.nio.file.Path, path: java.nio.file.Path, item: TarArchiveEntry) {
        if (com.tmplayer.desktop.os.OsInfo.isWindows) return
        val target = if (item.isLink) root.resolve(item.linkName).normalize() else path.parent.resolve(item.linkName).normalize()
        if (!target.startsWith(root)) throw IOException("archive link outside the folder: ${item.name}")
        path.parent?.toFile()?.mkdirs()
        Files.deleteIfExists(path)
        if (item.isLink) Files.createLink(path, target) else Files.createSymbolicLink(path, path.parent.relativize(target))
    }

    /** The unpacked folder is this build for this computer, or jcefmaven would fetch its own over it. */
    private fun check(staging: File) {
        val meta = File(staging, META)
        val text = runCatching { meta.readText() }.getOrNull() ?: throw IOException("no build_meta.json in the download")
        if (!text.contains("\"${CefNatives.RELEASE_TAG}\"")) throw IOException("build_meta.json is for another build")
        if (!text.contains("\"${native.platform}\"")) throw IOException("build_meta.json is for another platform")
        if (com.tmplayer.desktop.os.OsInfo.isMac) {
            // What jcefmaven does after its own unpack; a file Java wrote carries no quarantine, so this is a courtesy.
            runCatching { me.friwi.jcefmaven.impl.util.macos.UnquarantineUtil.unquarantine(staging) }
        }
    }

    private fun complete(dir: File): Boolean = runCatching {
        File(dir, LOCK).isFile && File(dir, META).readText().contains("\"${CefNatives.RELEASE_TAG}\"")
    }.getOrDefault(false)

    private fun ensureRoom(place: File, bytes: Long) {
        val free = freeSpace.usableBytes(place) ?: throw EngineNoRoom("free space in ${place.path} cannot be read")
        if (free < bytes + HEADROOM_BYTES / 2) throw EngineNoRoom("${place.path} has $free bytes free, $bytes needed")
    }

    /** What an interrupted run left: the partial download and a half unpacked folder. */
    private fun sweep(place: File) {
        runCatching { File(place, PART).delete() }
        runCatching { File(place, STAGING).takeIf { it.exists() }?.deleteRecursively() }
    }

    /** Older builds in every place, and this build in a place no longer used: one copy on disk. */
    private fun removeOthers(keep: File) {
        for (place in places().distinctBy { it.absoluteFile }) {
            val children = place.listFiles() ?: continue
            for (child in children) {
                if (child.absoluteFile == keep.absoluteFile || !child.isDirectory) continue
                // Only what TMPlayer put there: a folder with its own build_meta.json.
                if (File(child, META).isFile) runCatching { child.deleteRecursively() }
            }
        }
    }

    private fun isDiskFull(e: IOException): Boolean {
        val text = (e.message ?: "").lowercase()
        return "no space" in text || "disk full" in text || "not enough space" in text || e is EngineNoRoom
    }

    companion object {
        private const val TAG = "TrailerEngine"

        /** The installed folder's name inside a place. One per JCEF build. */
        val FOLDER = "jcef-${CefNatives.MAVEN_VERSION}"
        const val PART = ".download.part"
        const val STAGING = ".unpack"
        const val LOCK = "install.lock"
        const val META = "build_meta.json"

        /** Room kept free beyond the peak, so the drive is never filled to the last byte. */
        const val HEADROOM_BYTES = 100L * 1024 * 1024

        private const val UNPACK_SLACK = 16L * 1024 * 1024
        private const val BUFFER = 64 * 1024
    }
}
