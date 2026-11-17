@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package java.util.zip

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CArrayPointer
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.convert
import kotlinx.cinterop.free
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.nativeHeap
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.usePinned
import platform.posix.memcpy
import platform.zlib.Z_BUF_ERROR
import platform.zlib.Z_DEFAULT_COMPRESSION
import platform.zlib.Z_DEFAULT_STRATEGY
import platform.zlib.Z_DEFLATED
import platform.zlib.Z_FINISH
import platform.zlib.Z_NO_FLUSH
import platform.zlib.Z_OK
import platform.zlib.Z_STREAM_END
import platform.zlib.deflate
import platform.zlib.deflateEnd
import platform.zlib.deflateInit2_
import platform.zlib.inflate
import platform.zlib.inflateEnd
import platform.zlib.inflateInit2_
import platform.zlib.z_stream
import platform.zlib.zlibVersion

// java.util.zip's gzip streams over the system zlib, for the sync file (SyncDocument). Added when
// the iOS port was brought into TMPlayer (docs/IOS.md): the sync code came after the port's base.

/** zlib's window bits for a gzip header and trailer around raw deflate. */
private const val GZIP_WINDOW = 15 + 16

/**
 * java.util.zip.GZIPInputStream. The compressed bytes are read from [source] at once (a caller
 * bounds what it hands over); the text comes out a buffer at a time as it is read, so a small file
 * that unpacks to a huge one is never unpacked whole. Cut short or broken data throws [ZipException],
 * an IOException, as on the JDK.
 */
class GZIPInputStream(source: InputStream, @Suppress("UNUSED_PARAMETER") size: Int = 512) : InputStream() {
    private val length: Int
    private val compressed: CArrayPointer<ByteVar>

    init {
        val bytes = source.readBytes()
        length = bytes.size
        compressed = nativeHeap.allocArray(maxOf(1, bytes.size))
        if (bytes.isNotEmpty()) bytes.usePinned { memcpy(compressed, it.addressOf(0), bytes.size.convert()) }
    }

    private val stream = nativeHeap.alloc<z_stream>()
    private var ended = false
    private var closed = false

    init {
        stream.zalloc = null
        stream.zfree = null
        stream.opaque = null
        stream.next_in = compressed.reinterpret()
        stream.avail_in = length.convert()
        if (inflateInit2_(stream.ptr, GZIP_WINDOW, zlibVersion(), sizeOf<z_stream>().toInt()) != Z_OK) {
            release(started = false)
            throw ZipException("Cannot start inflating")
        }
    }

    override fun read(): Int {
        val one = ByteArray(1)
        val n = read(one, 0, 1)
        return if (n <= 0) -1 else one[0].toInt() and 0xFF
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (closed) throw IOException("Stream closed")
        if (offset < 0 || length < 0 || offset + length > buffer.size) throw IndexOutOfBoundsException()
        if (length == 0) return 0
        if (ended) return -1
        while (true) {
            val result = buffer.usePinned { pin ->
                stream.next_out = pin.addressOf(offset).reinterpret()
                stream.avail_out = length.convert()
                inflate(stream.ptr, Z_NO_FLUSH)
            }
            val produced = length - stream.avail_out.toInt()
            if (result == Z_STREAM_END) {
                ended = true
                return if (produced > 0) produced else -1
            }
            if (result != Z_OK && result != Z_BUF_ERROR) throw ZipException("Corrupt GZIP data ($result)")
            if (produced > 0) return produced
            if (stream.avail_in == 0u) throw ZipException("Unexpected end of ZLIB input stream")
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        release(started = true)
    }

    private fun release(started: Boolean) {
        if (started) inflateEnd(stream.ptr)
        nativeHeap.free(stream)
        nativeHeap.free(compressed)
    }
}

/**
 * java.util.zip.GZIPOutputStream. What is written is kept and deflated whole by [finish] or
 * [close], which then writes the gzip data to [sink]; [close] closes [sink] too, as on the JDK.
 */
class GZIPOutputStream(private val sink: OutputStream, @Suppress("UNUSED_PARAMETER") size: Int = 512) : OutputStream() {
    private val pending = ByteArrayOutputStream()
    private var finished = false
    private var closed = false

    override fun write(b: Int) {
        if (finished) throw IOException("write beyond end of stream")
        pending.write(b)
    }

    override fun write(buffer: ByteArray, offset: Int, length: Int) {
        if (finished) throw IOException("write beyond end of stream")
        pending.write(buffer, offset, length)
    }

    fun finish() {
        if (finished) return
        finished = true
        sink.write(gzip(pending.toByteArray()))
    }

    override fun flush() = sink.flush()

    override fun close() {
        if (closed) return
        closed = true
        try {
            finish()
        } finally {
            sink.close()
        }
    }
}

/** [input] deflated with a gzip header and trailer. */
internal fun gzip(input: ByteArray): ByteArray = memScoped {
    val stream = alloc<z_stream>()
    stream.zalloc = null
    stream.zfree = null
    stream.opaque = null
    stream.avail_in = 0u
    stream.next_in = null
    val started = deflateInit2_(
        stream.ptr, Z_DEFAULT_COMPRESSION, Z_DEFLATED, GZIP_WINDOW, 8, Z_DEFAULT_STRATEGY,
        zlibVersion(), sizeOf<z_stream>().toInt(),
    )
    if (started != Z_OK) throw ZipException("Cannot start deflating")
    val out = ByteArrayOutputStream(input.size / 4 + 64)
    val chunk = ByteArray(64 * 1024)
    try {
        // A pinned empty array has no first element: deflate a one-byte stand-in with no input.
        val source = if (input.isEmpty()) ByteArray(1) else input
        source.usePinned { inPin ->
            chunk.usePinned { outPin ->
                stream.next_in = inPin.addressOf(0).reinterpret()
                stream.avail_in = input.size.convert()
                while (true) {
                    stream.next_out = outPin.addressOf(0).reinterpret()
                    stream.avail_out = chunk.size.convert()
                    val result = deflate(stream.ptr, Z_FINISH)
                    out.write(chunk, 0, chunk.size - stream.avail_out.toInt())
                    if (result == Z_STREAM_END) break
                    if (result != Z_OK && result != Z_BUF_ERROR) throw ZipException("Cannot deflate ($result)")
                }
            }
        }
        out.toByteArray()
    } finally {
        deflateEnd(stream.ptr)
    }
}
