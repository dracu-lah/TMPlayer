package java.nio.channels

import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.file.OpenOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption

/** java.nio.channels.FileChannel for positional reads and writes, as TdByteWindow and the downloads use. */
class FileChannel private constructor(private val file: RandomAccessFile) : Closeable {
    private var open = true

    fun isOpen(): Boolean = open

    fun size(): Long = file.length()

    fun position(): Long = file.getFilePointer()

    fun position(newPosition: Long): FileChannel = apply { file.seek(newPosition) }

    /** Reads into [destination] at [position], not moving the channel's own position, as the JDK does. */
    fun read(destination: ByteBuffer, position: Long): Int {
        if (!destination.hasRemaining()) return 0
        val chunk = ByteArray(destination.remaining())
        val got = file.readAt(chunk, 0, chunk.size, position)
        if (got > 0) destination.put(chunk, 0, got)
        return got
    }

    fun read(destination: ByteBuffer): Int {
        if (!destination.hasRemaining()) return 0
        val chunk = ByteArray(destination.remaining())
        val got = file.read(chunk, 0, chunk.size)
        if (got > 0) destination.put(chunk, 0, got)
        return got
    }

    fun write(source: ByteBuffer, position: Long): Int {
        val keep = file.getFilePointer()
        file.seek(position)
        val count = write(source)
        file.seek(keep)
        return count
    }

    fun write(source: ByteBuffer): Int {
        val chunk = ByteArray(source.remaining())
        source.get(chunk)
        file.write(chunk)
        return chunk.size
    }

    /** Copies up to [count] bytes from [position] into [target], in chunks; returns how many it copied. */
    fun transferTo(position: Long, count: Long, target: FileChannel): Long {
        val chunk = ByteArray(minOf(count, 1L shl 20).toInt().coerceAtLeast(1))
        var done = 0L
        while (done < count) {
            val got = file.readAt(chunk, 0, minOf(chunk.size.toLong(), count - done).toInt(), position + done)
            if (got <= 0) break
            target.write(ByteBuffer.wrap(chunk, 0, got))
            done += got
        }
        return done
    }

    fun truncate(size: Long): FileChannel = apply { if (size < file.length()) file.setLength(size) }

    fun force(metaData: Boolean) = Unit

    override fun close() {
        if (open) {
            open = false
            file.close()
        }
    }

    companion object {
        fun open(path: Path, vararg options: OpenOption): FileChannel {
            val writes = options.any { it == StandardOpenOption.WRITE || it == StandardOpenOption.APPEND || it == StandardOpenOption.CREATE }
            val target: File = path.toFile()
            if (!writes && !target.exists()) throw java.nio.file.NoSuchFileException(target.path)
            if (StandardOpenOption.CREATE_NEW in options && target.exists()) throw java.nio.file.FileAlreadyExistsException(target.path)
            val channel = try {
                FileChannel(RandomAccessFile(target, if (writes) "rw" else "r"))
            } catch (e: IOException) {
                throw e
            }
            if (StandardOpenOption.TRUNCATE_EXISTING in options && writes) channel.truncate(0)
            return channel
        }
    }
}
