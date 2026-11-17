@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package java.util.zip

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.usePinned
import platform.zlib.Z_BUF_ERROR
import platform.zlib.Z_NO_FLUSH
import platform.zlib.Z_OK
import platform.zlib.Z_STREAM_END
import platform.zlib.inflate
import platform.zlib.inflateEnd
import platform.zlib.inflateInit2_
import platform.zlib.z_stream
import platform.zlib.zlibVersion

open class ZipException(message: String?) : IOException(message)

class ZipEntry(val name: String) {
    var size: Long = -1
    var compressedSize: Long = -1
    var method: Int = -1
    val isDirectory: Boolean get() = name.endsWith("/")
    override fun toString(): String = name

    companion object {
        const val STORED = 0
        const val DEFLATED = 8
    }
}

/**
 * java.util.zip.ZipInputStream for the archives core opens (subtitle downloads, small): the whole
 * archive is read first, then entries are walked through their local headers, stored or deflated.
 */
class ZipInputStream(source: InputStream) : InputStream() {
    private val bytes: ByteArray = source.readBytes()
    private var position = 0
    private var current: ByteArrayInputStream? = null

    val nextEntry: ZipEntry? get() = getNextEntry()

    fun getNextEntry(): ZipEntry? {
        current = null
        if (position + 30 > bytes.size || int32(position) != 0x04034b50) return null
        val flags = int16(position + 6)
        val method = int16(position + 8)
        var compressed = int32(position + 18).toLong() and 0xFFFFFFFFL
        val size = int32(position + 22).toLong() and 0xFFFFFFFFL
        val nameLength = int16(position + 26)
        val extraLength = int16(position + 28)
        val nameBytes = bytes.copyOfRange(position + 30, position + 30 + nameLength)
        val name = if (flags and 0x800 != 0) nameBytes.decodeToString()
        else CharArray(nameBytes.size) { (nameBytes[it].toInt() and 0xFF).toChar() }.concatToString()
        val dataStart = position + 30 + nameLength + extraLength
        val hasDescriptor = flags and 0x08 != 0
        val data: ByteArray
        when (method) {
            ZipEntry.STORED -> {
                if (hasDescriptor && compressed == 0L) throw ZipException("Stored entry without sizes: $name")
                data = bytes.copyOfRange(dataStart, (dataStart + compressed).toInt())
            }
            ZipEntry.DEFLATED -> {
                val (inflated, consumed) = inflateRaw(bytes, dataStart, bytes.size - dataStart)
                data = inflated
                compressed = consumed.toLong()
            }
            else -> throw ZipException("Unsupported compression method $method in $name")
        }
        position = (dataStart + compressed).toInt()
        if (hasDescriptor) position += if (position + 4 <= bytes.size && int32(position) == 0x08074b50) 16 else 12
        current = ByteArrayInputStream(data)
        return ZipEntry(name).also {
            it.method = method
            it.size = if (hasDescriptor) data.size.toLong() else size
            it.compressedSize = compressed
        }
    }

    fun closeEntry() {
        current = null
    }

    override fun read(): Int = current?.read() ?: -1

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int = current?.read(buffer, offset, length) ?: -1

    private fun int16(at: Int): Int = (bytes[at].toInt() and 0xFF) or ((bytes[at + 1].toInt() and 0xFF) shl 8)

    private fun int32(at: Int): Int = int16(at) or (int16(at + 2) shl 16)
}

/** Raw deflate (no zlib header), as zip entries hold it; returns the bytes and how much input they took. */
internal fun inflateRaw(input: ByteArray, offset: Int, length: Int): Pair<ByteArray, Int> = memScoped {
    val stream = alloc<z_stream>()
    stream.zalloc = null
    stream.zfree = null
    stream.opaque = null
    stream.avail_in = 0u
    stream.next_in = null
    if (inflateInit2_(stream.ptr, -15, zlibVersion(), sizeOf<z_stream>().toInt()) != Z_OK) throw ZipException("Cannot start inflating")
    val out = ByteArrayOutputStream()
    val chunk = ByteArray(64 * 1024)
    try {
        input.usePinned { inPin ->
            chunk.usePinned { outPin ->
                stream.next_in = if (length > 0) inPin.addressOf(offset).reinterpret() else null
                stream.avail_in = length.convert()
                while (true) {
                    stream.next_out = outPin.addressOf(0).reinterpret()
                    stream.avail_out = chunk.size.convert()
                    val result = inflate(stream.ptr, Z_NO_FLUSH)
                    out.write(chunk, 0, chunk.size - stream.avail_out.toInt())
                    if (result == Z_STREAM_END) break
                    if (result == Z_BUF_ERROR && stream.avail_in == 0u) throw ZipException("Truncated deflate data")
                    if (result != Z_OK && result != Z_BUF_ERROR) throw ZipException("Corrupt deflate data ($result)")
                }
            }
        }
        out.toByteArray() to stream.total_in.toInt()
    } finally {
        inflateEnd(stream.ptr)
    }
}

/** java.util.zip.Inflater for raw or zlib wrapped data handed over whole. */
class Inflater(private val nowrap: Boolean = false) {
    private var input = ByteArray(0)
    private var finished = false
    private var produced: ByteArray? = null
    private var read = 0

    fun setInput(bytes: ByteArray) = setInput(bytes, 0, bytes.size)
    fun setInput(bytes: ByteArray, offset: Int, length: Int) {
        input = bytes.copyOfRange(offset, offset + length)
        produced = null
        read = 0
        finished = false
    }

    fun inflate(buffer: ByteArray): Int = inflate(buffer, 0, buffer.size)

    fun inflate(buffer: ByteArray, offset: Int, length: Int): Int {
        val all = produced ?: run {
            val raw = if (nowrap) input else input.copyOfRange(2, input.size) // skip the zlib header
            inflateRaw(raw, 0, raw.size).first.also { produced = it }
        }
        val n = minOf(length, all.size - read)
        all.copyInto(buffer, offset, read, read + n)
        read += n
        if (read >= all.size) finished = true
        return n
    }

    fun finished(): Boolean = finished
    fun needsInput(): Boolean = produced == null && input.isEmpty()
    fun end() = Unit
    fun reset() = setInput(ByteArray(0))
}
