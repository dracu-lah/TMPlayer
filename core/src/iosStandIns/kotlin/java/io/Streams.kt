@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package java.io

import java.nio.charset.Charset
import java.nio.charset.Charsets
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
import platform.posix.EINTR
import platform.posix.O_APPEND
import platform.posix.O_CREAT
import platform.posix.O_RDONLY
import platform.posix.O_RDWR
import platform.posix.O_TRUNC
import platform.posix.O_WRONLY
import platform.posix.SEEK_SET
import platform.posix.errno
import platform.posix.fstat
import platform.posix.fsync
import platform.posix.ftruncate
import platform.posix.lseek
import platform.posix.open
import platform.posix.pread
import platform.posix.read
import platform.posix.stat
import platform.posix.strerror
import platform.posix.write
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.toKString

typealias IOException = com.tmplayer.platform.IOException

open class EOFException(message: String? = null) : IOException(message)

open class FileNotFoundException(message: String? = null) : IOException(message)

open class InterruptedIOException(message: String? = null) : IOException(message)

open class UncheckedIOException(message: String?, cause: IOException) : RuntimeException(message, cause)

/** java.io.Closeable; an AutoCloseable, so Kotlin's `use` works on every stream here. */
interface Closeable : AutoCloseable {
    override fun close()
}

interface Flushable {
    fun flush()
}

abstract class InputStream : Closeable {
    abstract fun read(): Int

    open fun read(buffer: ByteArray): Int = read(buffer, 0, buffer.size)

    open fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        var count = 0
        while (count < length) {
            val b = read()
            if (b < 0) return if (count == 0) -1 else count
            buffer[offset + count++] = b.toByte()
        }
        return count
    }

    open fun skip(n: Long): Long {
        var left = n
        val scratch = ByteArray(8192)
        while (left > 0) {
            val got = read(scratch, 0, minOf(left, scratch.size.toLong()).toInt())
            if (got < 0) break
            left -= got
        }
        return n - left
    }

    open fun available(): Int = 0

    override fun close() = Unit

    /** kotlin.io's InputStream.readBytes(). */
    open fun readBytes(): ByteArray {
        val out = ByteArrayOutputStream()
        copyTo(out)
        return out.toByteArray()
    }

    fun readAllBytes(): ByteArray = readBytes()

    fun readNBytes(length: Int): ByteArray {
        val out = ByteArray(length)
        var done = 0
        while (done < length) {
            val got = read(out, done, length - done)
            if (got < 0) break
            done += got
        }
        return if (done == length) out else out.copyOf(done)
    }

    /** kotlin.io's InputStream.copyTo(). */
    fun copyTo(out: OutputStream, bufferSize: Int = 8 * 1024): Long {
        val buffer = ByteArray(bufferSize)
        var total = 0L
        while (true) {
            val got = read(buffer)
            if (got < 0) return total
            out.write(buffer, 0, got)
            total += got
        }
    }

    fun reader(charset: Charset = Charsets.UTF_8): InputStreamReader = InputStreamReader(this, charset)

    fun bufferedReader(charset: Charset = Charsets.UTF_8): BufferedReader = BufferedReader(reader(charset))

    fun buffered(bufferSize: Int = 8 * 1024): BufferedInputStream = BufferedInputStream(this, bufferSize)
}

abstract class OutputStream : Closeable, Flushable {
    abstract fun write(b: Int)

    open fun write(buffer: ByteArray) = write(buffer, 0, buffer.size)

    open fun write(buffer: ByteArray, offset: Int, length: Int) {
        for (i in offset until offset + length) write(buffer[i].toInt())
    }

    override fun flush() = Unit

    override fun close() = Unit

    fun writer(charset: Charset = Charsets.UTF_8): OutputStreamWriter = OutputStreamWriter(this, charset)

    fun bufferedWriter(charset: Charset = Charsets.UTF_8): BufferedWriter = BufferedWriter(writer(charset))

    fun buffered(bufferSize: Int = 8 * 1024): BufferedOutputStream = BufferedOutputStream(this, bufferSize)
}

class ByteArrayInputStream(private val bytes: ByteArray, offset: Int = 0, length: Int = bytes.size) : InputStream() {
    private var position = offset
    private val end = minOf(offset + length, bytes.size)

    override fun read(): Int = if (position < end) bytes[position++].toInt() and 0xFF else -1

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (position >= end) return -1
        val count = minOf(length, end - position)
        bytes.copyInto(buffer, offset, position, position + count)
        position += count
        return count
    }

    override fun available(): Int = end - position

    override fun skip(n: Long): Long = minOf(n, (end - position).toLong()).coerceAtLeast(0).also { position += it.toInt() }
}

open class ByteArrayOutputStream(initialSize: Int = 32) : OutputStream() {
    private var buffer = ByteArray(initialSize.coerceAtLeast(1))
    private var count = 0

    private fun ensure(extra: Int) {
        if (count + extra <= buffer.size) return
        buffer = buffer.copyOf(maxOf(buffer.size * 2, count + extra))
    }

    override fun write(b: Int) {
        ensure(1)
        buffer[count++] = b.toByte()
    }

    override fun write(buffer: ByteArray, offset: Int, length: Int) {
        ensure(length)
        buffer.copyInto(this.buffer, count, offset, offset + length)
        count += length
    }

    fun size(): Int = count
    fun reset() {
        count = 0
    }
    fun toByteArray(): ByteArray = buffer.copyOf(count)
    fun writeTo(out: OutputStream) = out.write(buffer, 0, count)
    override fun toString(): String = buffer.decodeToString(0, count)
    fun toString(charset: Charset): String = charset.decode(toByteArray())
    fun toString(charsetName: String): String = toString(Charset.forName(charsetName))
}

open class FilterInputStream(protected val source: InputStream) : InputStream() {
    override fun read(): Int = source.read()
    override fun read(buffer: ByteArray, offset: Int, length: Int): Int = source.read(buffer, offset, length)
    override fun available(): Int = source.available()
    override fun close() = source.close()
}

class BufferedInputStream(source: InputStream, size: Int = 8 * 1024) : FilterInputStream(source) {
    private val buffer = ByteArray(size)
    private var count = 0
    private var position = 0

    private fun fill(): Boolean {
        count = source.read(buffer, 0, buffer.size).coerceAtLeast(0)
        position = 0
        return count > 0
    }

    override fun read(): Int {
        if (position >= count && !fill()) return -1
        return buffer[position++].toInt() and 0xFF
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (position >= count) {
            if (length >= this.buffer.size) return source.read(buffer, offset, length)
            if (!fill()) return -1
        }
        val n = minOf(length, count - position)
        this.buffer.copyInto(buffer, offset, position, position + n)
        position += n
        return n
    }

    override fun available(): Int = (count - position) + source.available()
}

open class FilterOutputStream(protected val sink: OutputStream) : OutputStream() {
    override fun write(b: Int) = sink.write(b)
    override fun write(buffer: ByteArray, offset: Int, length: Int) = sink.write(buffer, offset, length)
    override fun flush() = sink.flush()
    override fun close() {
        flush()
        sink.close()
    }
}

class BufferedOutputStream(sink: OutputStream, size: Int = 8 * 1024) : FilterOutputStream(sink) {
    private val buffer = ByteArray(size)
    private var count = 0

    private fun drain() {
        if (count > 0) sink.write(buffer, 0, count)
        count = 0
    }

    override fun write(b: Int) {
        if (count == buffer.size) drain()
        buffer[count++] = b.toByte()
    }

    override fun write(buffer: ByteArray, offset: Int, length: Int) {
        if (length >= this.buffer.size) {
            drain()
            sink.write(buffer, offset, length)
            return
        }
        if (count + length > this.buffer.size) drain()
        buffer.copyInto(this.buffer, count, offset, offset + length)
        count += length
    }

    override fun flush() {
        drain()
        sink.flush()
    }
}

internal fun posixFailure(what: String, path: String): IOException {
    val code = errno
    val reason = strerror(code)?.toKString() ?: "errno $code"
    return if (code == platform.posix.ENOENT) FileNotFoundException("$path ($reason)") else IOException("$what $path: $reason")
}

/** An open file descriptor, closed once. */
internal class Descriptor(val fd: Int, val path: String) {
    private var open = true

    fun close() {
        if (open) {
            open = false
            platform.posix.close(fd)
        }
    }

    fun readAt(buffer: ByteArray, offset: Int, length: Int, position: Long): Int {
        if (length == 0) return 0
        while (true) {
            val got = buffer.usePinned { pread(fd, it.addressOf(offset), length.convert(), position) }
            if (got < 0 && errno == EINTR) continue
            if (got < 0) throw posixFailure("Cannot read", path)
            return if (got == 0L) -1 else got.toInt()
        }
    }

    fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        while (true) {
            val got = buffer.usePinned { read(fd, it.addressOf(offset), length.convert()) }
            if (got < 0 && errno == EINTR) continue
            if (got < 0) throw posixFailure("Cannot read", path)
            return if (got == 0L) -1 else got.toInt()
        }
    }

    fun write(buffer: ByteArray, offset: Int, length: Int) {
        var done = 0
        while (done < length) {
            val wrote = buffer.usePinned { write(fd, it.addressOf(offset + done), (length - done).convert()) }
            if (wrote < 0 && errno == EINTR) continue
            if (wrote < 0) throw posixFailure("Cannot write", path)
            done += wrote.toInt()
        }
    }

    fun size(): Long = memScoped {
        val info = alloc<stat>()
        if (fstat(fd, info.ptr) != 0) throw posixFailure("Cannot stat", path)
        info.st_size
    }

    fun seek(position: Long) {
        if (lseek(fd, position, SEEK_SET) < 0) throw posixFailure("Cannot seek", path)
    }

    fun truncate(length: Long) {
        if (ftruncate(fd, length) != 0) throw posixFailure("Cannot truncate", path)
    }

    fun sync() {
        fsync(fd)
    }

    companion object {
        fun open(path: String, flags: Int): Descriptor {
            val fd = open(path, flags, 0x1B6) // 0666, narrowed by the umask as on the JVM
            if (fd < 0) throw posixFailure("Cannot open", path)
            return Descriptor(fd, path)
        }

        const val READ = O_RDONLY
        const val WRITE = O_WRONLY or O_CREAT or O_TRUNC
        const val APPEND = O_WRONLY or O_CREAT or O_APPEND
        const val READ_WRITE = O_RDWR or O_CREAT
    }
}

class FileInputStream private constructor(private val descriptor: Descriptor, private val file: File?) : InputStream() {
    constructor(file: File) : this(Descriptor.open(file.path, Descriptor.READ), file)
    constructor(path: String) : this(File(path))

    /** A stream over a descriptor ContentResolver handed out; never on iOS, where none is (see ParcelFileDescriptor). */
    constructor(fd: FileDescriptor) : this(fd.descriptorOrThrow(), null)

    /** FileInputStream.getChannel(), as Kotlin reads it. */
    val channel: java.nio.channels.FileChannel
        get() = java.nio.channels.FileChannel.open(java.nio.file.Path.of(file?.path ?: throw IOException("No file behind this stream")))

    override fun read(): Int {
        val one = ByteArray(1)
        return if (descriptor.read(one, 0, 1) < 0) -1 else one[0].toInt() and 0xFF
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int = descriptor.read(buffer, offset, length)

    override fun close() = descriptor.close()
}

/** A descriptor that can be told to reach the disk, as FileOutputStream.getFD().sync() does. */
class FileDescriptor internal constructor(private val descriptor: Descriptor?) {
    constructor() : this(null)
    internal fun descriptorOrThrow(): Descriptor = descriptor ?: throw IOException("Invalid file descriptor")
    fun valid(): Boolean = descriptor != null
    fun sync() {
        descriptor?.sync()
    }
}

class FileOutputStream(file: File, append: Boolean = false) : OutputStream() {
    constructor(path: String, append: Boolean = false) : this(File(path), append)

    private val descriptor = Descriptor.open(file.path, if (append) Descriptor.APPEND else Descriptor.WRITE)

    override fun write(b: Int) = descriptor.write(byteArrayOf(b.toByte()), 0, 1)

    override fun write(buffer: ByteArray, offset: Int, length: Int) = descriptor.write(buffer, offset, length)

    /** FileOutputStream.getFD(), as Kotlin reads it. */
    val fd: FileDescriptor get() = FileDescriptor(descriptor)

    override fun close() = descriptor.close()
}

/** java.io.RandomAccessFile over one descriptor, modes "r" and "rw". */
class RandomAccessFile(file: File, mode: String) : Closeable {
    constructor(path: String, mode: String) : this(File(path), mode)

    private val descriptor = Descriptor.open(file.path, if (mode.startsWith("rw")) Descriptor.READ_WRITE else Descriptor.READ)
    private var position = 0L

    fun length(): Long = descriptor.size()
    fun getFilePointer(): Long = position
    fun seek(position: Long) {
        if (position < 0) throw IOException("Negative seek offset")
        this.position = position
    }
    fun setLength(length: Long) = descriptor.truncate(length)

    fun read(): Int {
        val one = ByteArray(1)
        return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 0xFF
    }

    fun read(buffer: ByteArray): Int = read(buffer, 0, buffer.size)

    fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        val got = descriptor.readAt(buffer, offset, length, position)
        if (got > 0) position += got
        return got
    }

    /** A read at [at] that leaves the file pointer alone: pread, safe from several threads at once. */
    fun readAt(buffer: ByteArray, offset: Int, length: Int, at: Long): Int = descriptor.readAt(buffer, offset, length, at)

    fun readFully(buffer: ByteArray) = readFully(buffer, 0, buffer.size)

    fun readFully(buffer: ByteArray, offset: Int, length: Int) {
        var done = 0
        while (done < length) {
            val got = read(buffer, offset + done, length - done)
            if (got < 0) throw EOFException()
            done += got
        }
    }

    fun write(buffer: ByteArray) = write(buffer, 0, buffer.size)

    fun write(buffer: ByteArray, offset: Int, length: Int) {
        descriptor.seek(position)
        descriptor.write(buffer, offset, length)
        position += length
    }

    override fun close() = descriptor.close()
}

abstract class Reader : Closeable {
    abstract fun read(buffer: CharArray, offset: Int, length: Int): Int

    open fun read(): Int {
        val one = CharArray(1)
        return if (read(one, 0, 1) < 0) -1 else one[0].code
    }

    /** kotlin.io's Reader.readText(). */
    open fun readText(): String {
        val out = StringBuilder()
        val buffer = CharArray(8192)
        while (true) {
            val got = read(buffer, 0, buffer.size)
            if (got < 0) return out.toString()
            out.appendRange(buffer, 0, got)
        }
    }

    fun readLines(): List<String> = readText().lines().let { if (it.lastOrNull() == "") it.dropLast(1) else it }

    fun buffered(): BufferedReader = BufferedReader(this)

    fun <T> useLines(block: (Sequence<String>) -> T): T = use { block(readLines().asSequence()) }
}

/** Decodes the whole stream at its first read: the readers core opens are small files. */
open class InputStreamReader(private val source: InputStream, private val charset: Charset = Charsets.UTF_8) : Reader() {
    constructor(source: InputStream, charsetName: String) : this(source, Charset.forName(charsetName))

    private var text: String? = null
    private var position = 0

    private fun text(): String = text ?: charset.decode(source.readBytes()).also { text = it }

    override fun read(buffer: CharArray, offset: Int, length: Int): Int {
        val all = text()
        if (position >= all.length) return -1
        val n = minOf(length, all.length - position)
        all.toCharArray(buffer, offset, position, position + n)
        position += n
        return n
    }

    override fun close() = source.close()
}

class StringReader(private val text: String) : Reader() {
    private var position = 0

    override fun read(buffer: CharArray, offset: Int, length: Int): Int {
        if (position >= text.length) return -1
        val n = minOf(length, text.length - position)
        text.toCharArray(buffer, offset, position, position + n)
        position += n
        return n
    }

    override fun close() = Unit
}

class BufferedReader(private val source: Reader) : Reader() {
    private var pending: String? = null
    private var position = 0

    private fun all(): String = pending ?: source.readText().also { pending = it }

    override fun read(buffer: CharArray, offset: Int, length: Int): Int {
        val text = all()
        if (position >= text.length) return -1
        val n = minOf(length, text.length - position)
        text.toCharArray(buffer, offset, position, position + n)
        position += n
        return n
    }

    fun readLine(): String? {
        val text = all()
        if (position >= text.length) return null
        var end = position
        while (end < text.length && text[end] != '\n' && text[end] != '\r') end++
        val line = text.substring(position, end)
        position = when {
            end < text.length && text[end] == '\r' && end + 1 < text.length && text[end + 1] == '\n' -> end + 2
            end < text.length -> end + 1
            else -> end
        }
        return line
    }

    fun lineSequence(): Sequence<String> = generateSequence { readLine() }

    override fun close() = source.close()
}

abstract class Writer : Closeable, Flushable {
    abstract fun write(text: String)
    open fun write(chars: CharArray) = write(chars.concatToString())
    open fun append(text: CharSequence?): Writer = apply { write(text?.toString() ?: "null") }
    open fun append(c: Char): Writer = apply { write(c.toString()) }
    override fun flush() = Unit
}

open class OutputStreamWriter(private val sink: OutputStream, private val charset: Charset = Charsets.UTF_8) : Writer() {
    override fun write(text: String) = sink.write(charset.encode(text))
    override fun flush() = sink.flush()
    override fun close() {
        flush()
        sink.close()
    }
}

class BufferedWriter(private val sink: Writer) : Writer() {
    private val pending = StringBuilder()
    override fun write(text: String) {
        pending.append(text)
        if (pending.length >= 8192) flush()
    }
    fun newLine() = write("\n")
    override fun flush() {
        if (pending.isNotEmpty()) sink.write(pending.toString())
        pending.clear()
        sink.flush()
    }
    override fun close() {
        flush()
        sink.close()
    }
}

class StringWriter : Writer() {
    private val buffer = StringBuilder()
    override fun write(text: String) {
        buffer.append(text)
    }
    override fun toString(): String = buffer.toString()
    override fun close() = Unit
}
