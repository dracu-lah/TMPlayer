@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package java.io

import java.nio.charset.Charset
import java.nio.charset.Charsets
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.toKString
import platform.Foundation.NSDate
import platform.Foundation.NSFileManager
import platform.Foundation.NSFileModificationDate
import platform.Foundation.dateWithTimeIntervalSince1970
import platform.posix.F_OK
import platform.posix.R_OK
import platform.posix.W_OK
import platform.posix.access
import platform.posix.getcwd
import platform.posix.mkdir
import platform.posix.realpath
import platform.posix.remove
import platform.posix.rename
import platform.posix.stat
import platform.posix.S_IFDIR
import platform.posix.S_IFMT
import platform.posix.S_IFREG
import platform.posix.PATH_MAX
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.ByteVar

fun interface FileFilter {
    fun accept(file: File): Boolean
}

/**
 * java.io.File over POSIX, with kotlin.io's File extensions (readText, resolve, walkTopDown, ...)
 * as members, since kotlin.io has no File on Kotlin/Native. Paths are normalised as the JDK does:
 * no doubled or trailing separators.
 */
open class File(pathname: String) : Comparable<File> {
    constructor(parent: File?, child: String) : this(join(parent?.path, child))
    constructor(parent: String?, child: String) : this(join(parent, child))

    val path: String = normalize(pathname)

    val name: String get() = path.substringAfterLast('/')
    val parent: String? get() {
        val cut = path.lastIndexOf('/')
        return when {
            cut < 0 -> null
            cut == 0 -> if (path.length > 1) "/" else null
            else -> path.substring(0, cut)
        }
    }
    val parentFile: File? get() = parent?.let(::File)
    val isAbsolute: Boolean get() = path.startsWith("/")
    val absolutePath: String get() = if (isAbsolute) path else join(currentDirectory(), path)
    val absoluteFile: File get() = File(absolutePath)
    val canonicalPath: String get() = memScoped {
        val out = allocArray<ByteVar>(PATH_MAX)
        realpath(absolutePath, out)?.toKString() ?: absolutePath
    }
    val canonicalFile: File get() = File(canonicalPath)

    /** kotlin.io's File.extension and nameWithoutExtension. */
    val extension: String get() = name.substringAfterLast('.', "")
    val nameWithoutExtension: String get() = name.substringBeforeLast('.')

    private fun mode(): UInt? = memScoped {
        val info = alloc<stat>()
        if (stat(path, info.ptr) != 0) null else info.st_mode.toUInt()
    }

    fun exists(): Boolean = access(path, F_OK) == 0
    val isFile: Boolean get() = mode()?.let { (it and S_IFMT.toUInt()) == S_IFREG.toUInt() } ?: false
    val isDirectory: Boolean get() = mode()?.let { (it and S_IFMT.toUInt()) == S_IFDIR.toUInt() } ?: false
    val isHidden: Boolean get() = name.startsWith(".")
    fun canRead(): Boolean = access(path, R_OK) == 0
    fun canWrite(): Boolean = access(path, W_OK) == 0

    fun length(): Long = memScoped {
        val info = alloc<stat>()
        if (stat(path, info.ptr) != 0) 0L else info.st_size
    }

    fun lastModified(): Long = memScoped {
        val info = alloc<stat>()
        if (stat(path, info.ptr) != 0) 0L
        else info.st_mtimespec.tv_sec * 1000L + info.st_mtimespec.tv_nsec / 1_000_000L
    }

    fun setLastModified(time: Long): Boolean = NSFileManager.defaultManager.setAttributes(
        mapOf(NSFileModificationDate to NSDate.dateWithTimeIntervalSince1970(time / 1000.0)),
        ofItemAtPath = path,
        error = null,
    )

    fun delete(): Boolean = remove(path) == 0

    fun mkdir(): Boolean = mkdir(path, 0x1FFu) == 0 // 0777, narrowed by the umask

    fun mkdirs(): Boolean {
        if (exists()) return false
        parentFile?.let { if (!it.exists()) it.mkdirs() }
        return mkdir() || isDirectory
    }

    fun createNewFile(): Boolean {
        if (exists()) return false
        return try {
            FileOutputStream(this).close()
            true
        } catch (e: IOException) {
            throw e
        }
    }

    fun renameTo(destination: File): Boolean = rename(path, destination.path) == 0

    fun list(): Array<String>? {
        if (!isDirectory) return null
        return NSFileManager.defaultManager.contentsOfDirectoryAtPath(path, null)
            ?.filterIsInstance<String>()?.toTypedArray()
    }

    fun listFiles(): Array<File>? = list()?.map { File(this, it) }?.toTypedArray()

    fun listFiles(filter: FileFilter): Array<File>? = listFiles()?.filter(filter::accept)?.toTypedArray()

    private fun fileSystem(key: String?): Long =
        (NSFileManager.defaultManager.attributesOfFileSystemForPath(path, null)?.get(key) as? platform.Foundation.NSNumber)?.longLongValue ?: 0L

    val totalSpace: Long get() = fileSystem(platform.Foundation.NSFileSystemSize)
    val freeSpace: Long get() = fileSystem(platform.Foundation.NSFileSystemFreeSize)

    /** What iOS would make room for, purgeable caches included, as the space a download can count on. */
    val usableSpace: Long get() {
        spaceAsked?.invoke()
        val important = platform.Foundation.NSURL.fileURLWithPath(path)
            .resourceValuesForKeys(listOf(platform.Foundation.NSURLVolumeAvailableCapacityForImportantUsageKey), null)
            ?.get(platform.Foundation.NSURLVolumeAvailableCapacityForImportantUsageKey) as? platform.Foundation.NSNumber
        return important?.longLongValue?.takeIf { it > 0 } ?: freeSpace
    }

    fun toPath(): java.nio.file.Path = java.nio.file.Path.of(path)

    fun resolve(relative: String): File = if (relative.startsWith("/")) File(relative) else File(this, relative)
    fun resolve(relative: File): File = resolve(relative.path)
    fun resolveSibling(relative: String): File = parentFile?.resolve(relative) ?: File(relative)
    fun startsWith(other: File): Boolean = path == other.path || path.startsWith(other.path.trimEnd('/') + "/")
    /** kotlin.io's File.normalize(): "." and ".." segments resolved without touching the disk. */
    fun normalize(): File {
        val parts = ArrayList<String>()
        for (part in path.split('/')) when (part) {
            "", "." -> Unit
            ".." -> if (parts.isNotEmpty() && parts.last() != "..") parts.removeAt(parts.lastIndex) else if (!isAbsolute) parts += part
            else -> parts += part
        }
        val joined = parts.joinToString("/")
        return File(if (isAbsolute) "/$joined" else joined)
    }

    fun relativeTo(base: File): File = File(path.removePrefix(base.path.trimEnd('/') + "/"))

    fun inputStream(): FileInputStream = FileInputStream(this)
    fun outputStream(): FileOutputStream = FileOutputStream(this)
    fun reader(charset: Charset = Charsets.UTF_8): InputStreamReader = inputStream().reader(charset)
    fun bufferedReader(charset: Charset = Charsets.UTF_8): BufferedReader = inputStream().bufferedReader(charset)
    fun writer(charset: Charset = Charsets.UTF_8): OutputStreamWriter = outputStream().writer(charset)
    fun bufferedWriter(charset: Charset = Charsets.UTF_8): BufferedWriter = outputStream().bufferedWriter(charset)

    fun readBytes(): ByteArray = inputStream().use { it.readBytes() }
    fun writeBytes(bytes: ByteArray) = outputStream().use { it.write(bytes) }
    fun appendBytes(bytes: ByteArray) = FileOutputStream(this, append = true).use { it.write(bytes) }
    fun readText(charset: Charset = Charsets.UTF_8): String = charset.decode(readBytes())
    fun writeText(text: String, charset: Charset = Charsets.UTF_8) = writeBytes(charset.encode(text))
    fun appendText(text: String, charset: Charset = Charsets.UTF_8) = appendBytes(charset.encode(text))
    fun readLines(charset: Charset = Charsets.UTF_8): List<String> = bufferedReader(charset).use { it.lineSequence().toList() }
    fun forEachLine(charset: Charset = Charsets.UTF_8, action: (String) -> Unit) = readLines(charset).forEach(action)
    fun <T> useLines(charset: Charset = Charsets.UTF_8, block: (Sequence<String>) -> T): T = block(readLines(charset).asSequence())

    fun copyTo(target: File, overwrite: Boolean = false, bufferSize: Int = 8 * 1024): File {
        if (!exists()) throw FileNotFoundException("$path: the source file doesn't exist.")
        if (target.exists()) {
            if (!overwrite) throw IOException("${target.path}: the target file already exists.")
            if (!target.delete()) throw IOException("${target.path}: cannot overwrite the target file.")
        }
        if (isDirectory) {
            if (!target.mkdirs()) throw IOException("${target.path}: cannot create the target directory.")
        } else {
            target.parentFile?.mkdirs()
            inputStream().use { input -> target.outputStream().use { output -> input.copyTo(output, bufferSize) } }
        }
        return target
    }

    fun deleteRecursively(): Boolean = walkBottomUp().fold(true) { all, file -> (file.delete() || !file.exists()) && all }

    fun walk(): FileTreeWalk = FileTreeWalk(this, topDown = true)
    fun walkTopDown(): FileTreeWalk = FileTreeWalk(this, topDown = true)
    fun walkBottomUp(): FileTreeWalk = FileTreeWalk(this, topDown = false)

    override fun compareTo(other: File): Int = path.compareTo(other.path)
    override fun equals(other: Any?): Boolean = other is File && other.path == path
    override fun hashCode(): Int = path.hashCode() xor 1234321
    override fun toString(): String = path

    companion object {
        /** Told of each [usableSpace] asked, for a developer's measure of how often (ios/kit's PerfProbe). */
        var spaceAsked: (() -> Unit)? = null
        const val separator: String = "/"
        const val separatorChar: Char = '/'
        const val pathSeparator: String = ":"

        private fun normalize(path: String): String {
            if (path.isEmpty()) return path
            val collapsed = path.replace(Regex("/{2,}"), "/")
            return if (collapsed.length > 1) collapsed.trimEnd('/') else collapsed
        }

        private fun join(parent: String?, child: String): String = when {
            parent == null -> child
            parent.isEmpty() -> "/$child"
            else -> parent.trimEnd('/') + "/" + child.trimStart('/')
        }

        private fun currentDirectory(): String = memScoped {
            val out = allocArray<ByteVar>(PATH_MAX)
            getcwd(out, PATH_MAX.toULong())?.toKString() ?: "/"
        }

        fun createTempFile(prefix: String, suffix: String?, directory: File? = null): File {
            val dir = directory ?: File(platform.Foundation.NSTemporaryDirectory())
            while (true) {
                val candidate = File(dir, prefix + kotlin.random.Random.nextLong().toULong().toString(36) + (suffix ?: ".tmp"))
                if (candidate.createNewFile()) return candidate
            }
        }
    }
}

/** kotlin.io's FileTreeWalk: a lazy walk with a depth limit, top down or bottom up. */
class FileTreeWalk internal constructor(
    private val start: File,
    private val topDown: Boolean,
    private val depthLimit: Int = Int.MAX_VALUE,
) : Sequence<File> {
    fun maxDepth(depth: Int): FileTreeWalk = FileTreeWalk(start, topDown, depth)

    override fun iterator(): Iterator<File> = sequence {
        suspend fun SequenceScope<File>.visit(file: File, depth: Int) {
            if (topDown) yield(file)
            if (depth < depthLimit && file.isDirectory) file.listFiles()?.sortedBy { it.name }?.forEach { visit(it, depth + 1) }
            if (!topDown) yield(file)
        }
        if (start.exists()) visit(start, 0)
    }.iterator()
}
