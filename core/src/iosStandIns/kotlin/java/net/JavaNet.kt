@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.UnsafeNumber::class)

package java.net

import java.io.Closeable
import java.io.IOException
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocPointerTo
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.pointed
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import platform.Foundation.NSURLComponents
import platform.posix.AF_UNSPEC
import platform.posix.EINPROGRESS
import platform.posix.F_GETFL
import platform.posix.F_SETFL
import platform.posix.O_NONBLOCK
import platform.posix.POLLOUT
import platform.posix.SOCK_STREAM
import platform.posix.SOL_SOCKET
import platform.posix.SO_ERROR
import platform.posix.addrinfo
import platform.posix.close
import platform.posix.connect
import platform.posix.errno
import platform.posix.fcntl
import platform.posix.freeaddrinfo
import platform.posix.getaddrinfo
import platform.posix.getsockopt
import platform.posix.poll
import platform.posix.pollfd
import platform.posix.socket
import platform.posix.socklen_tVar

open class URISyntaxException(input: String, reason: String) : Exception("$reason: $input")

open class MalformedURLException(message: String?) : IOException(message)

open class SocketException(message: String?) : IOException(message)

open class SocketTimeoutException(message: String?) : java.io.InterruptedIOException(message)

open class UnknownHostException(message: String?) : IOException(message)

/** java.net.URI's parts, through Foundation's RFC 3986 parser. */
class URI(private val text: String) {
    private val parts: NSURLComponents = NSURLComponents.componentsWithString(text) ?: throw URISyntaxException(text, "Malformed URI")

    val scheme: String? get() = parts.scheme
    /** Empty, not null, when there is none: callers written for the JVM's platform type lowercase it. */
    val host: String get() = parts.host.orEmpty()
    val port: Int get() = parts.port?.intValue ?: -1
    val path: String? get() = parts.path
    val rawPath: String? get() = parts.percentEncodedPath
    val query: String? get() = parts.query
    val rawQuery: String? get() = parts.percentEncodedQuery
    val fragment: String? get() = parts.fragment
    val userInfo: String? get() = parts.user
    /** The user and password as written (percent encoded), "user:password" or "user"; null for none. */
    val rawUserInfo: String? get() = parts.percentEncodedUser?.let { user -> parts.percentEncodedPassword?.let { "$user:$it" } ?: user }

    val authority: String? get() = host.takeIf { it.isNotEmpty() }?.let { h -> if (port >= 0) "$h:$port" else h }
    fun getScheme(): String? = scheme
    fun getHost(): String = host
    fun getPath(): String? = path
    fun isAbsolute(): Boolean = scheme != null
    fun resolve(other: String): URI = URI(platform.Foundation.NSURL.URLWithString(other, relativeToURL = platform.Foundation.NSURL.URLWithString(text))?.absoluteString ?: other)
    fun toURL(): URL = URL(text)
    override fun toString(): String = text
    override fun equals(other: Any?): Boolean = other is URI && other.text == text
    override fun hashCode(): Int = text.hashCode()

    companion object {
        fun create(text: String): URI = try {
            URI(text)
        } catch (e: URISyntaxException) {
            throw IllegalArgumentException(e.message, e)
        }
    }
}

class URL(private val text: String) {
    private val uri = try {
        URI(text)
    } catch (e: URISyntaxException) {
        throw MalformedURLException(e.message)
    }
    val host: String get() = uri.host
    val protocol: String? get() = uri.scheme
    val path: String? get() = uri.path
    val query: String? get() = uri.query
    val port: Int get() = uri.port
    fun toURI(): URI = uri
    fun openConnection(): URLConnection = HttpURLConnection(this)
    override fun toString(): String = text
}

/** application/x-www-form-urlencoded, as java.net.URLEncoder writes it: spaces become '+'. */
object URLEncoder {
    private const val SAFE = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789.-*_"

    fun encode(text: String, charsetName: String): String = encode(text, java.nio.charset.Charset.forName(charsetName))

    fun encode(text: String, charset: java.nio.charset.Charset): String = buildString {
        for (b in charset.encode(text)) {
            val c = (b.toInt() and 0xFF).toChar()
            when {
                c == ' ' -> append('+')
                c in SAFE -> append(c)
                else -> append('%').append((b.toInt() and 0xFF).toString(16).uppercase().padStart(2, '0'))
            }
        }
    }
}

object URLDecoder {
    fun decode(text: String, charsetName: String): String = decode(text, java.nio.charset.Charset.forName(charsetName))

    fun decode(text: String, charset: java.nio.charset.Charset): String {
        val bytes = ArrayList<Byte>(text.length)
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                c == '+' -> {
                    bytes += ' '.code.toByte()
                    i++
                }
                c == '%' -> {
                    val hex = text.substring(i + 1, minOf(i + 3, text.length))
                    val value = hex.takeIf { it.length == 2 }?.toIntOrNull(16)
                        ?: throw IllegalArgumentException("URLDecoder: Illegal hex characters in escape (%) pattern")
                    bytes += value.toByte()
                    i += 3
                }
                else -> {
                    charset.encode(c.toString()).forEach { bytes += it }
                    i++
                }
            }
        }
        return charset.decode(bytes.toByteArray())
    }
}

open class SocketAddress

class InetSocketAddress(val hostString: String, val port: Int) : SocketAddress() {
    val hostName: String get() = hostString
    override fun toString(): String = "$hostString:$port"
}

/** A TCP socket for one thing core does with it on iOS: see whether a host answers on a port. */
class Socket : Closeable {
    private var fd = -1

    fun connect(address: SocketAddress, timeoutMs: Int = 0) {
        val target = address as? InetSocketAddress ?: throw IllegalArgumentException("Unsupported address")
        memScoped {
            val hints = alloc<addrinfo>().apply {
                ai_family = AF_UNSPEC
                ai_socktype = SOCK_STREAM
            }
            val found = allocPointerTo<addrinfo>()
            if (getaddrinfo(target.hostString, target.port.toString(), hints.ptr, found.ptr) != 0) {
                throw UnknownHostException(target.hostString)
            }
            try {
                var info = found.value
                var failure: IOException = SocketException("No address for ${target.hostString}")
                while (info != null) {
                    val entry = info.pointed
                    val s = socket(entry.ai_family, entry.ai_socktype, entry.ai_protocol)
                    if (s >= 0) {
                        fcntl(s, F_SETFL, fcntl(s, F_GETFL, 0) or O_NONBLOCK)
                        val started = connect(s, entry.ai_addr, entry.ai_addrlen)
                        if (started == 0 || (started < 0 && errno == EINPROGRESS && waitWritable(s, timeoutMs))) {
                            fd = s
                            return
                        }
                        failure = if (started < 0 && errno == EINPROGRESS) SocketTimeoutException("connect timed out") else SocketException("Connection refused")
                        close(s)
                    }
                    info = entry.ai_next
                }
                throw failure
            } finally {
                freeaddrinfo(found.value)
            }
        }
    }

    private fun waitWritable(s: Int, timeoutMs: Int): Boolean = memScoped {
        val target = alloc<pollfd>().apply {
            fd = s
            events = POLLOUT.toShort()
        }
        if (poll(target.ptr, 1u, if (timeoutMs <= 0) -1 else timeoutMs) <= 0) return false
        val error = alloc<kotlinx.cinterop.IntVar>()
        val length = alloc<socklen_tVar>().apply { value = 4u }
        getsockopt(s, SOL_SOCKET, SO_ERROR, error.ptr, length.ptr) == 0 && error.value == 0
    }

    val isConnected: Boolean get() = fd >= 0

    var soTimeout: Int = 0

    override fun close() {
        if (fd >= 0) close(fd)
        fd = -1
    }
}
