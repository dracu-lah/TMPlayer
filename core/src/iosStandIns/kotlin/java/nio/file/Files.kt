@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package java.nio.file

import java.io.File
import java.io.IOException
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import platform.Foundation.NSFileManager
import platform.posix.EXDEV
import platform.posix.S_IFLNK
import platform.posix.S_IFMT
import platform.posix.errno
import platform.posix.lstat
import platform.posix.rename
import platform.posix.stat
import platform.posix.symlink

/** java.nio.file.Path as core uses it: a path to turn back into a File or to resolve against. */
class Path private constructor(private val file: File) : Comparable<Path> {
    fun toFile(): File = file
    fun resolve(other: String): Path = Path(file.resolve(other))
    fun resolve(other: Path): Path = Path(file.resolve(other.file))
    fun getFileName(): Path = fileName
    /** Not null, unlike the JDK's for a root, since callers rely on the JVM's platform type. */
    val fileName: Path get() = Path(File(file.name.ifEmpty { file.path }))
    fun getParent(): Path? = file.parentFile?.let(::Path)
    val parent: Path? get() = getParent()
    /** The path with its links followed, as [File.canonicalFile] gives it. */
    fun toRealPath(): Path = Path(file.canonicalFile)
    /** [other] from here: what is left of its path once this path is taken off its front. */
    fun relativize(other: Path): Path {
        val base = file.path.trimEnd('/')
        val rest = other.file.path.let { if (it == base) "" else it.removePrefix("$base/") }
        return Path(File(rest))
    }

    fun toAbsolutePath(): Path = Path(file.absoluteFile)
    fun startsWith(other: Path): Boolean = file.startsWith(other.file)
    fun startsWith(other: String): Boolean = file.startsWith(File(other))
    fun normalize(): Path = this
    override fun compareTo(other: Path): Int = file.compareTo(other.file)
    override fun equals(other: Any?): Boolean = other is Path && other.file == file
    override fun hashCode(): Int = file.hashCode()
    override fun toString(): String = file.path

    companion object {
        fun of(first: String, vararg more: String): Path = Path(more.fold(File(first)) { acc, part -> File(acc, part) })
    }
}

object Paths {
    fun get(first: String, vararg more: String): Path = Path.of(first, *more)
}

interface OpenOption

interface CopyOption

enum class StandardOpenOption : OpenOption { READ, WRITE, APPEND, TRUNCATE_EXISTING, CREATE, CREATE_NEW, DELETE_ON_CLOSE, SPARSE, SYNC, DSYNC }

enum class StandardCopyOption : CopyOption { REPLACE_EXISTING, COPY_ATTRIBUTES, ATOMIC_MOVE }

enum class LinkOption : OpenOption, CopyOption { NOFOLLOW_LINKS }

open class FileSystemException(file: String?, other: String? = null, reason: String? = null) :
    IOException(listOfNotNull(file, other?.let { "-> $it" }, reason?.let { ": $it" }).joinToString(" "))

class AtomicMoveNotSupportedException(source: String?, target: String?, reason: String?) : FileSystemException(source, target, reason)

class FileAlreadyExistsException(file: String?) : FileSystemException(file)

class NoSuchFileException(file: String?) : FileSystemException(file)

class DirectoryNotEmptyException(file: String?) : FileSystemException(file)

/** The volume a path is on; core asks it for space only. */
class FileStore internal constructor(private val file: File) {
    fun getTotalSpace(): Long = file.totalSpace
    fun getUsableSpace(): Long = file.usableSpace
    fun getUnallocatedSpace(): Long = file.freeSpace
    val totalSpace: Long get() = file.totalSpace
    val usableSpace: Long get() = file.usableSpace
    val unallocatedSpace: Long get() = file.freeSpace
}

object Files {
    fun exists(path: Path, vararg options: LinkOption): Boolean = path.toFile().exists()

    fun isDirectory(path: Path, vararg options: LinkOption): Boolean = path.toFile().isDirectory

    fun isRegularFile(path: Path, vararg options: LinkOption): Boolean = path.toFile().isFile

    fun isSymbolicLink(path: Path): Boolean = memScoped {
        val info = alloc<stat>()
        lstat(path.toString(), info.ptr) == 0 && (info.st_mode.toUInt() and S_IFMT.toUInt()) == S_IFLNK.toUInt()
    }

    fun size(path: Path): Long = path.toFile().also { if (!it.exists()) throw NoSuchFileException(it.path) }.length()

    fun deleteIfExists(path: Path): Boolean {
        val file = path.toFile()
        if (!file.exists() && !isSymbolicLink(path)) return false
        if (file.isDirectory && !file.list().isNullOrEmpty()) throw DirectoryNotEmptyException(file.path)
        if (!file.delete()) throw IOException("Cannot delete ${file.path}")
        return true
    }

    fun delete(path: Path) {
        if (!deleteIfExists(path)) throw NoSuchFileException(path.toString())
    }

    fun createDirectories(path: Path): Path = path.also { it.toFile().mkdirs() }

    /**
     * A rename, which is atomic within one volume. Across volumes ATOMIC_MOVE fails as on the JVM;
     * without it the file is copied and the source deleted.
     */
    fun move(source: Path, target: Path, vararg options: CopyOption): Path {
        val atomic = StandardCopyOption.ATOMIC_MOVE in options
        val replace = atomic || StandardCopyOption.REPLACE_EXISTING in options
        val from = source.toFile()
        val to = target.toFile()
        if (!from.exists() && !isSymbolicLink(source)) throw NoSuchFileException(from.path)
        if (!replace && to.exists()) throw FileAlreadyExistsException(to.path)
        if (rename(from.path, to.path) == 0) return target
        if (errno == EXDEV) {
            if (atomic) throw AtomicMoveNotSupportedException(from.path, to.path, "Different volumes")
            if (to.exists()) to.deleteRecursively()
            from.copyTo(to, overwrite = true)
            from.deleteRecursively()
            return target
        }
        throw IOException("Cannot move ${from.path} to ${to.path}")
    }

    fun copy(source: Path, target: Path, vararg options: CopyOption): Path {
        source.toFile().copyTo(target.toFile(), overwrite = StandardCopyOption.REPLACE_EXISTING in options)
        return target
    }

    fun createSymbolicLink(link: Path, target: Path): Path {
        if (symlink(target.toString(), link.toString()) != 0) throw IOException("Cannot link $link to $target")
        return link
    }

    fun getFileStore(path: Path): FileStore = FileStore(path.toFile())

    fun readAllBytes(path: Path): ByteArray = path.toFile().readBytes()

    fun write(path: Path, bytes: ByteArray, vararg options: OpenOption): Path = path.also { it.toFile().writeBytes(bytes) }

    fun newInputStream(path: Path, vararg options: OpenOption): java.io.InputStream = path.toFile().inputStream()

    fun newOutputStream(path: Path, vararg options: OpenOption): java.io.OutputStream =
        java.io.FileOutputStream(path.toFile(), append = StandardOpenOption.APPEND in options)

    fun list(path: Path): Sequence<Path> = (path.toFile().listFiles() ?: emptyArray()).asSequence().map { it.toPath() }

    fun getLastModifiedTime(path: Path): Long = path.toFile().lastModified()

    internal fun manager(): NSFileManager = NSFileManager.defaultManager
}
