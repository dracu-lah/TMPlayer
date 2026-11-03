package com.tmplayer.cast

import com.tmplayer.platform.Logger
import java.io.BufferedInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.URLDecoder
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * The small web server a Chromecast or Google TV fetches the video from while it is cast.
 *
 * A receiver plays a URL, so TMPlayer has to be one: this listens on the device's own address on
 * the local network (never every interface), under a path that starts with a random token made
 * for this cast session alone, and only while something is cast; [stop] closes it. A request
 * without the token, or from an address outside the private ranges, gets a 404 or is dropped.
 *
 * What it serves ([CastResource]):
 *  - [CastResource.Ranged]: the video's own bytes, with `Range` requests answered by a 206, so the
 *    receiver can read the index at the end of an MP4 and seek wherever it likes. Every read goes
 *    through the [CastSource], which is the platform's ordinary streaming path.
 *  - [CastResource.Text]: a WebVTT subtitle track, small and in memory.
 *  - [CastResource.Stream]: something made as it is sent (the remuxed MP4), with no length and no
 *    ranges; a seek is a new request with the start in its query.
 *
 * Every answer carries the CORS headers a receiver needs for subtitle tracks and media fetched by
 * script.
 */
class CastServer(
    private val bind: InetAddress,
    private val port: Int = 0,
    /** Who may ask; the private and link local ranges by default. Tests on loopback widen it. */
    private val allowed: (InetAddress) -> Boolean = ::isLocalNetwork,
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
) {

    /** This session's secret: the first segment of every path. */
    val token: String = newToken()

    private val resources = ConcurrentHashMap<String, CastResource>()
    private val dlnaServing = ConcurrentHashMap<String, DlnaServing>()
    private val connections = CopyOnWriteArrayList<Socket>()
    private val streamsPerResource = ConcurrentHashMap<String, CopyOnWriteArrayList<Socket>>()

    @Volatile
    private var server: ServerSocket? = null

    @Volatile
    private var acceptor: Thread? = null

    private val threadCount = AtomicInteger()

    /** Requests answered so far, for the tests and the log. */
    val served = AtomicInteger()

    val running: Boolean get() = server?.isClosed == false

    @Volatile
    private var lastActivityMs = clock()

    /**
     * Milliseconds since a request began or a byte of a body was sent. A renderer paused for a
     * long time holds no request open, so a DLNA session that sees this pass 30 minutes while
     * paused closes the server (and its port) instead of keeping it open for nothing.
     */
    fun idleMs(): Long = clock() - lastActivityMs

    /** The port it ended up on, once [start]ed. */
    val boundPort: Int get() = server?.localPort ?: -1

    @Synchronized
    fun start() {
        if (running) return
        val socket = ServerSocket()
        socket.reuseAddress = true
        socket.bind(InetSocketAddress(bind, port), BACKLOG)
        server = socket
        lastActivityMs = clock()
        acceptor = Thread({ acceptLoop(socket) }, "cast-server").apply {
            isDaemon = true
            start()
        }
        Logger.i(TAG, "Serving on ${bind.hostAddress}:${socket.localPort}")
    }

    /** Closes the listener and every open connection; the resources are forgotten. */
    @Synchronized
    fun stop() {
        runCatching { server?.close() }
        server = null
        connections.forEach { runCatching { it.close() } }
        connections.clear()
        streamsPerResource.clear()
        resources.clear()
        dlnaServing.clear()
    }

    /**
     * Makes [resource] available as [name] (a file name, so the receiver sees an extension) and
     * returns the URL a receiver opens it at. The same name again replaces it.
     */
    fun publish(name: String, resource: CastResource, dlna: DlnaServing? = null): String {
        val clean = name.replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { "media" }
        resources[clean] = resource
        if (dlna != null) dlnaServing[clean] = dlna else dlnaServing.remove(clean)
        // A replaced video's open streams belong to the old one.
        streamsPerResource.remove(clean)?.forEach { runCatching { it.close() } }
        return urlFor(clean)
    }

    fun unpublish(name: String) {
        resources.remove(name)
        dlnaServing.remove(name)
        streamsPerResource.remove(name)?.forEach { runCatching { it.close() } }
    }

    fun urlFor(name: String): String {
        val host = bind.hostAddress.let { if (':' in it) "[$it]" else it }
        return "http://$host:$boundPort/$token/$name"
    }

    private fun acceptLoop(socket: ServerSocket) {
        while (!socket.isClosed) {
            val client = try {
                socket.accept()
            } catch (e: IOException) {
                if (!socket.isClosed) Logger.w(TAG, "Accept failed", e)
                break
            }
            if (!allowed(client.inetAddress)) {
                Logger.w(TAG, "Refused ${client.inetAddress.hostAddress}")
                refuse(client)
                continue
            }
            connections += client
            Thread({ handle(client) }, "cast-request-${threadCount.incrementAndGet()}").apply {
                isDaemon = true
                start()
            }
        }
    }

    /**
     * Answers a stranger with a 403 and closes, on a thread of its own so a slow peer cannot hold
     * up the accept loop. The request is read (briefly) before the close: closing on unread data
     * resets the connection, and the 403 would never arrive.
     */
    private fun refuse(client: Socket) {
        Thread({
            try {
                client.soTimeout = REFUSE_READ_MS
                client.getOutputStream().apply {
                    write("HTTP/1.1 403 Forbidden\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray(Charsets.ISO_8859_1))
                    flush()
                }
                client.shutdownOutput()
                val sink = ByteArray(2048)
                runCatching { client.getInputStream().read(sink) }
            } catch (_: Exception) {
            } finally {
                runCatching { client.close() }
            }
        }, "cast-refuse").apply {
            isDaemon = true
            start()
        }
    }

    private fun handle(socket: Socket) {
        try {
            socket.soTimeout = READ_TIMEOUT_MS
            socket.tcpNoDelay = true
            val input = BufferedInputStream(socket.getInputStream())
            val output = socket.getOutputStream().buffered(SEND_BUFFER)
            // Keep-alive, so a receiver's run of small range reads does not pay a connect each.
            while (!socket.isClosed) {
                val request = HttpRequest.read(input) ?: break
                served.incrementAndGet()
                lastActivityMs = clock()
                val keepOpen = respond(socket, request, output)
                output.flush()
                if (!keepOpen || request.wantsClose) break
            }
        } catch (e: SocketException) {
            // The receiver hung up, or stop() closed it: both ordinary.
        } catch (e: IOException) {
            Logger.i(TAG, "Request ended: ${e.message}")
        } catch (e: Exception) {
            Logger.w(TAG, "Request failed", e)
        } finally {
            connections -= socket
            streamsPerResource.values.forEach { it.remove(socket) }
            runCatching { socket.close() }
        }
    }

    /** Answers one request; false when the connection has to close after it. */
    private fun respond(socket: Socket, request: HttpRequest, out: OutputStream): Boolean {
        if (request.method == "OPTIONS") {
            writeHead(out, 204, "No Content", emptyMap(), 0)
            return true
        }
        if (request.method != "GET" && request.method != "HEAD") {
            writeHead(out, 405, "Method Not Allowed", mapOf("Allow" to "GET, HEAD, OPTIONS"), 0)
            return false
        }
        val segments = request.path.trimStart('/').split('/')
        if (segments.size < 2 || segments[0] != token) return notFound(out)
        val name = segments[1]
        val published = resources[name] ?: return notFound(out)
        // A folder (an HLS cast) answers for the paths under it; anything else has none.
        val resource = when {
            published is CastResource.Tree -> if (segments.size > 2) published.resolve(segments.drop(2).joinToString("/")) else null
            segments.size == 2 -> published
            else -> null
        } ?: return notFound(out)
        val head = request.method == "HEAD"
        val extra = dlnaHeaders(dlnaServing[name], request, resource)
        return when (resource) {
            is CastResource.Tree -> notFound(out)
            is CastResource.Chunked -> {
                writeHead(out, 200, "OK", mapOf("Content-Type" to resource.mime, "Transfer-Encoding" to "chunked"), null)
                if (head) return true
                val body = ChunkedOutput(out)
                try {
                    resource.write(body)
                } catch (e: IOException) {
                    // A broken read of the source: the body cannot be finished, so neither can the connection.
                    Logger.i(TAG, "Segment ${request.path.substringAfterLast('/')} failed: ${e.message}")
                    return false
                }
                body.finish()
                true
            }
            is CastResource.Text -> {
                writeHead(out, 200, "OK", mapOf("Content-Type" to resource.mime) + extra, resource.bytes.size.toLong())
                if (!head) out.write(resource.bytes)
                true
            }
            is CastResource.Ranged -> serveRanged(socket, name, resource, request, out, head, extra)
            is CastResource.Stream -> {
                // Made as it goes: no length, so the connection's end is the body's end.
                writeHead(out, 200, "OK", mapOf("Content-Type" to resource.mime, "Connection" to "close") + extra, null)
                if (!head) {
                    track(name, socket)
                    resource.write(request.query, out)
                    out.flush()
                }
                false
            }
        }
    }

    private fun serveRanged(
        socket: Socket,
        name: String,
        resource: CastResource.Ranged,
        request: HttpRequest,
        out: OutputStream,
        head: Boolean,
        extra: Map<String, String> = emptyMap(),
    ): Boolean {
        val total = resource.source.length
        val range = request.header("range")?.let { ByteRange.parse(it, total) }
        if (range == ByteRange.Unsatisfiable) {
            writeHead(out, 416, "Range Not Satisfiable", mapOf("Content-Range" to "bytes */$total"), 0)
            return true
        }
        val span = range as? ByteRange.Span
        val start = span?.start ?: 0L
        val end = span?.endInclusive ?: (total - 1)
        val length = (end - start + 1).coerceAtLeast(0)
        val headers = LinkedHashMap<String, String>()
        headers["Content-Type"] = resource.mime
        headers["Accept-Ranges"] = "bytes"
        headers.putAll(extra)
        if (span != null) headers["Content-Range"] = "bytes $start-$end/$total"
        writeHead(out, if (span != null) 206 else 200, if (span != null) "Partial Content" else "OK", headers, length)
        if (head || length == 0L) return true
        track(name, socket)
        resource.source.open(start).use { reader ->
            val buffer = ByteArray(COPY_BUFFER)
            var left = length
            while (left > 0) {
                val n = reader.read(buffer, 0, minOf(buffer.size.toLong(), left).toInt())
                if (n < 0) throw IOException("Source ended $left bytes early")
                out.write(buffer, 0, n)
                lastActivityMs = clock()
                left -= n
            }
        }
        untrack(name, socket)
        return true
    }

    /**
     * Notes [socket] as one reading [name]. A receiver that seeks opens a new request and leaves
     * the old one hanging; past [MAX_STREAMS] the oldest is closed, so two reads of one Telegram
     * file do not pull its download in three directions.
     */
    private fun track(name: String, socket: Socket) {
        val list = streamsPerResource.getOrPut(name) { CopyOnWriteArrayList() }
        list.remove(socket)
        list += socket
        while (list.size > MAX_STREAMS) {
            val oldest = list.removeAt(0)
            runCatching { oldest.close() }
        }
    }

    private fun untrack(name: String, socket: Socket) {
        streamsPerResource[name]?.remove(socket)
    }

    /**
     * The DLNA headers of an answer for a resource published with [DlnaServing]: the transfer mode
     * (echoing what the renderer asked for), the content features when it asks for them, and the
     * subtitle's address for Samsung's `getCaptionInfo.sec`.
     */
    private fun dlnaHeaders(serving: DlnaServing?, request: HttpRequest, resource: CastResource): Map<String, String> {
        if (serving == null || resource is CastResource.Chunked || resource is CastResource.Tree) return emptyMap()
        val out = LinkedHashMap<String, String>()
        val asked = request.header("transferMode.dlna.org")?.trim()
        out["transferMode.dlna.org"] = TRANSFER_MODES.firstOrNull { it.equals(asked, ignoreCase = true) }
            ?: if (serving.interactive) "Interactive" else "Streaming"
        if (request.header("getcontentFeatures.dlna.org")?.trim() == "1") out["contentFeatures.dlna.org"] = serving.features
        if (request.header("getCaptionInfo.sec")?.trim() == "1" && serving.captionName != null) {
            out["CaptionInfo.sec"] = urlFor(serving.captionName)
        }
        return out
    }

    private fun notFound(out: OutputStream): Boolean {
        writeHead(out, 404, "Not Found", emptyMap(), 0)
        return true
    }

    private fun writeHead(out: OutputStream, code: Int, reason: String, headers: Map<String, String>, length: Long?) {
        val sb = StringBuilder()
        sb.append("HTTP/1.1 ").append(code).append(' ').append(reason).append("\r\n")
        for ((k, v) in headers) sb.append(k).append(": ").append(v).append("\r\n")
        if (length != null) sb.append("Content-Length: ").append(length).append("\r\n")
        sb.append("Access-Control-Allow-Origin: *\r\n")
        sb.append("Access-Control-Allow-Methods: GET, HEAD, OPTIONS\r\n")
        sb.append("Access-Control-Allow-Headers: Range, Content-Type, Accept-Encoding\r\n")
        sb.append("Access-Control-Expose-Headers: Content-Range, Content-Length, Accept-Ranges\r\n")
        sb.append("Cache-Control: no-store\r\n")
        sb.append("\r\n")
        out.write(sb.toString().toByteArray(Charsets.ISO_8859_1))
    }

    companion object {
        private const val TAG = "CastServer"
        private const val BACKLOG = 16
        private const val READ_TIMEOUT_MS = 120_000
        private const val COPY_BUFFER = 64 * 1024
        private const val SEND_BUFFER = 64 * 1024
        private const val REFUSE_READ_MS = 300
        private val TRANSFER_MODES = listOf("Streaming", "Interactive", "Background")

        /** Reads of one resource at once, beyond which the oldest is closed. */
        const val MAX_STREAMS = 3

        private val random = SecureRandom()

        fun newToken(): String {
            val bytes = ByteArray(18)
            random.nextBytes(bytes)
            return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        }

        /** Private, link local, unique local or loopback: anything a receiver on the Wi-Fi can be. */
        fun isLocalNetwork(address: InetAddress): Boolean =
            address.isSiteLocalAddress || address.isLinkLocalAddress || address.isLoopbackAddress ||
                (address.address.size == 16 && (address.address[0].toInt() and 0xFE) == 0xFC) ||
                // 100.64.0.0/10, the carrier grade range some mesh and travel routers hand out.
                (address.address.size == 4 && (address.address[0].toInt() and 0xFF) == 100 &&
                    (address.address[1].toInt() and 0xC0) == 64)
    }
}

/**
 * How a resource is described to a DLNA renderer: the `contentFeatures.dlna.org` value, whether
 * it is fetched as a whole file ([interactive], a subtitle) rather than played, and the name of
 * the subtitle to point `CaptionInfo.sec` at.
 */
class DlnaServing(val features: String, val captionName: String? = null, val interactive: Boolean = false)

/** What [CastServer] can serve. */
sealed interface CastResource {

    /** A file's bytes, read through [source], with ranges. */
    class Ranged(val source: CastSource, val mime: String) : CastResource

    /** A small text in memory, a subtitle track. */
    class Text(val bytes: ByteArray, val mime: String) : CastResource {
        constructor(text: String, mime: String) : this(text.toByteArray(Charsets.UTF_8), mime)
    }

    /**
     * Bytes made while they are sent, with no length: [write] gets the request's query (the start
     * of a seek, for the remuxer) and writes the body until it is done or the receiver hangs up.
     */
    class Stream(val mime: String, val write: (query: Map<String, String>, out: OutputStream) -> Unit) : CastResource

    /**
     * Bytes made when they are asked for, whose length is not known before (an HLS segment): sent
     * with chunked transfer encoding, so the connection stays open for the next request.
     */
    class Chunked(val mime: String, val write: (out: OutputStream) -> Unit) : CastResource

    /** A folder of resources made on request, by the path under its name (an HLS cast's playlists and segments). */
    class Tree(val resolve: (path: String) -> CastResource?) : CastResource
}

/** HTTP/1.1 chunked transfer encoding over [out]: each full buffer a chunk, [finish] the last one. */
internal class ChunkedOutput(private val out: OutputStream) : OutputStream() {
    private val buffer = ByteArray(CHUNK)
    private var used = 0

    override fun write(b: Int) {
        if (used == buffer.size) drain()
        buffer[used++] = b.toByte()
    }

    override fun write(b: ByteArray, off: Int, len: Int) {
        var at = off
        var left = len
        while (left > 0) {
            if (used == buffer.size) drain()
            val n = minOf(left, buffer.size - used)
            System.arraycopy(b, at, buffer, used, n)
            used += n
            at += n
            left -= n
        }
    }

    override fun flush() {
        drain()
        out.flush()
    }

    private fun drain() {
        if (used == 0) return
        out.write((Integer.toHexString(used) + "\r\n").toByteArray(Charsets.ISO_8859_1))
        out.write(buffer, 0, used)
        out.write(CRLF)
        used = 0
    }

    /** The last chunk: what is buffered, then the empty one that ends the body. */
    fun finish() {
        drain()
        out.write("0\r\n\r\n".toByteArray(Charsets.ISO_8859_1))
        out.flush()
    }

    private companion object {
        const val CHUNK = 64 * 1024
        val CRLF = byteArrayOf('\r'.code.toByte(), '\n'.code.toByte())
    }
}

/** A `Range: bytes=` header against a file of a known length. */
sealed interface ByteRange {
    data class Span(val start: Long, val endInclusive: Long) : ByteRange
    data object Unsatisfiable : ByteRange

    companion object {
        /**
         * The one range asked for, or null to send the whole file (no header, a header this does
         * not understand, or several ranges, which no receiver asks for). A start past the end is
         * [Unsatisfiable].
         */
        fun parse(header: String, total: Long): ByteRange? {
            val value = header.trim()
            if (!value.startsWith("bytes=", ignoreCase = true)) return null
            val spec = value.substring(6).trim()
            if (',' in spec) return null
            val dash = spec.indexOf('-')
            if (dash < 0) return null
            val first = spec.substring(0, dash).trim()
            val last = spec.substring(dash + 1).trim()
            if (total <= 0) return Unsatisfiable
            if (first.isEmpty()) {
                // The last N bytes.
                val suffix = last.toLongOrNull() ?: return null
                if (suffix <= 0) return Unsatisfiable
                return Span((total - suffix).coerceAtLeast(0), total - 1)
            }
            val start = first.toLongOrNull() ?: return null
            if (start >= total) return Unsatisfiable
            val end = if (last.isEmpty()) total - 1 else (last.toLongOrNull() ?: return null).coerceAtMost(total - 1)
            if (end < start) return Unsatisfiable
            return Span(start, end)
        }
    }
}

/** The parts of an HTTP/1.1 request the server reads. */
internal class HttpRequest(
    val method: String,
    val path: String,
    val query: Map<String, String>,
    private val headers: Map<String, String>,
) {
    fun header(name: String): String? = headers[name.lowercase()]

    val wantsClose: Boolean get() = header("connection")?.equals("close", ignoreCase = true) == true

    companion object {
        private const val MAX_LINE = 8 * 1024
        private const val MAX_HEADERS = 100

        /** The next request on [input], or null once the other side has closed the connection. */
        fun read(input: InputStream): HttpRequest? {
            var line = readLine(input) ?: return null
            // Tolerate a stray blank line between keep-alive requests.
            if (line.isEmpty()) line = readLine(input) ?: return null
            val parts = line.split(' ')
            if (parts.size < 2) throw IOException("Bad request line")
            val method = parts[0].uppercase()
            val target = parts[1]
            val headers = HashMap<String, String>()
            var count = 0
            while (true) {
                val h = readLine(input) ?: throw IOException("Headers cut off")
                if (h.isEmpty()) break
                if (++count > MAX_HEADERS) throw IOException("Too many headers")
                val colon = h.indexOf(':')
                if (colon > 0) headers[h.substring(0, colon).trim().lowercase()] = h.substring(colon + 1).trim()
            }
            val q = target.indexOf('?')
            val path = if (q >= 0) target.substring(0, q) else target
            val query = if (q >= 0) parseQuery(target.substring(q + 1)) else emptyMap()
            return HttpRequest(method, path, query, headers)
        }

        private fun parseQuery(text: String): Map<String, String> =
            text.split('&').filter { it.isNotEmpty() }.associate { pair ->
                val eq = pair.indexOf('=')
                val k = if (eq >= 0) pair.substring(0, eq) else pair
                val v = if (eq >= 0) pair.substring(eq + 1) else ""
                URLDecoder.decode(k, "UTF-8") to URLDecoder.decode(v, "UTF-8")
            }

        private fun readLine(input: InputStream): String? {
            val sb = StringBuilder()
            while (true) {
                val b = input.read()
                if (b < 0) return if (sb.isEmpty()) null else sb.toString()
                if (b == '\n'.code) break
                if (b != '\r'.code) sb.append(b.toChar())
                if (sb.length > MAX_LINE) throw IOException("Line too long")
            }
            return sb.toString()
        }
    }
}
