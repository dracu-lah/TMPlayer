package com.tmplayer.cast

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.random.Random

class LoopbackStreamTest {

    private val loopback = LoopbackStream.loopback
    private val started = ArrayList<LoopbackStream>()

    @After
    fun closeAll() = started.forEach { it.close() }

    private val quick = LoopbackStream.Config(recovery = null, sweepEveryMs = 0)

    private fun start(
        streaming: CastSource?,
        downloaded: File? = null,
        config: LoopbackStream.Config = quick,
    ): LoopbackStream = LoopbackStream.start(streaming, downloaded, "video/x-matroska", "video.mkv", config).also { started += it }

    private class Reply(val code: Int, val headers: Map<String, String>, val body: ByteArray)

    private fun path(stream: LoopbackStream): String = stream.url.substringAfter(":${stream.port}")

    /** One request on its own connection; at most [limit] body bytes are read before hanging up. */
    private fun request(
        port: Int,
        path: String,
        vararg headers: String,
        method: String = "GET",
        limit: Int = Int.MAX_VALUE,
    ): Reply = Socket(loopback, port).use { s ->
        s.soTimeout = 20_000
        val head = buildString {
            append("$method $path HTTP/1.1\r\nHost: 127.0.0.1\r\nConnection: close\r\n")
            headers.forEach { append(it).append("\r\n") }
            append("\r\n")
        }
        s.getOutputStream().write(head.toByteArray())
        s.getOutputStream().flush()
        readReply(s.getInputStream(), method == "HEAD", limit)
    }

    private fun readReply(input: InputStream, head: Boolean, limit: Int): Reply {
        fun line(): String {
            val sb = StringBuilder()
            while (true) {
                val b = input.read()
                if (b < 0 || b == '\n'.code) break
                if (b != '\r'.code) sb.append(b.toChar())
            }
            return sb.toString()
        }
        val status = line().split(' ').getOrNull(1)?.toIntOrNull() ?: -1
        val headers = HashMap<String, String>()
        while (true) {
            val h = line()
            if (h.isEmpty()) break
            headers[h.substringBefore(':').trim().lowercase()] = h.substringAfter(':').trim()
        }
        val length = headers["content-length"]?.toLongOrNull() ?: 0L
        if (head) return Reply(status, headers, ByteArray(0))
        val want = minOf(length, limit.toLong()).toInt()
        val out = ByteArray(want)
        var at = 0
        while (at < want) {
            val n = input.read(out, at, want - at)
            if (n < 0) break
            at += n
        }
        return Reply(status, headers, out.copyOf(at))
    }

    @Test(timeout = 30_000)
    fun everyRangeForm() {
        val total = 1_000_000L
        val stream = start(PatternSource(total))
        val p = path(stream)
        val whole = request(stream.port, p)
        assertEquals(200, whole.code)
        assertArrayEquals(PatternSource.expected(0, total.toInt()), whole.body)
        assertEquals("bytes", whole.headers["accept-ranges"])

        val open = request(stream.port, p, "Range: bytes=999000-")
        assertEquals(206, open.code)
        assertEquals("bytes 999000-999999/1000000", open.headers["content-range"])
        assertArrayEquals(PatternSource.expected(999_000, 1000), open.body)

        val closed = request(stream.port, p, "Range: bytes=10-19")
        assertEquals(206, closed.code)
        assertArrayEquals(PatternSource.expected(10, 10), closed.body)

        val suffix = request(stream.port, p, "Range: bytes=-500")
        assertEquals(206, suffix.code)
        assertEquals("bytes 999500-999999/1000000", suffix.headers["content-range"])
        assertArrayEquals(PatternSource.expected(999_500, 500), suffix.body)

        val past = request(stream.port, p, "Range: bytes=1000000-")
        assertEquals(416, past.code)
        assertEquals("bytes */1000000", past.headers["content-range"])

        val head = request(stream.port, p, method = "HEAD")
        assertEquals(200, head.code)
        assertEquals("1000000", head.headers["content-length"])
        val headRange = request(stream.port, p, "Range: bytes=100-", method = "HEAD")
        assertEquals(206, headRange.code)
        assertEquals("999900", headRange.headers["content-length"])
    }

    @Test(timeout = 30_000)
    fun aFileOverFourGigabytes() {
        val total = 5L * 1024 * 1024 * 1024 + 123
        val stream = start(PatternSource(total))
        val p = path(stream)
        val head = request(stream.port, p, method = "HEAD")
        assertEquals(total.toString(), head.headers["content-length"])
        val end = request(stream.port, p, "Range: bytes=${total - 100}-")
        assertEquals("bytes ${total - 100}-${total - 1}/$total", end.headers["content-range"])
        assertArrayEquals(PatternSource.expected(total - 100, 100), end.body)
        val past4g = 4L * 1024 * 1024 * 1024 + 5
        val mid = request(stream.port, p, "Range: bytes=$past4g-${past4g + 99}")
        assertArrayEquals(PatternSource.expected(past4g, 100), mid.body)
        val suffix = request(stream.port, p, "Range: bytes=-16")
        assertArrayEquals(PatternSource.expected(total - 16, 16), suffix.body)
        val open = request(stream.port, p, "Range: bytes=1-", limit = 1000)
        assertEquals((total - 1).toString(), open.headers["content-length"])
        assertArrayEquals(PatternSource.expected(1, 1000), open.body)
    }

    /** A player reading the body on one connection and the index at the end on another. */
    @Test(timeout = 60_000)
    fun concurrentConnectionsShareOneWindow() {
        val total = 8L * 1024 * 1024
        val source = PatternSource(total, readDelayMs = 1)
        val stream = start(source)
        val p = path(stream)
        val errors = CopyOnWriteArrayList<Throwable>()
        val body = Thread {
            runCatching {
                val r = request(stream.port, p)
                assertArrayEquals(PatternSource.expected(0, total.toInt()), r.body)
            }.onFailure { errors += it }
        }
        body.start()
        Thread.sleep(50)
        // Two probes beside the body: the server's own cap (CastServer.MAX_STREAMS, 3) drops the oldest past that.
        val probes = List(CastServer.MAX_STREAMS - 1) { i ->
            Thread {
                runCatching {
                    val at = total - 256 * 1024 * (i + 1)
                    val r = request(stream.port, p, "Range: bytes=$at-${at + 99_999}")
                    assertArrayEquals(PatternSource.expected(at, 100_000), r.body)
                }.onFailure { errors += it }
            }
        }
        probes.forEach { it.start() }
        probes.forEach { it.join() }
        body.join()
        assertTrue(errors.toString(), errors.isEmpty())
        assertEquals("one TDLib window at a time", 1, source.maxOpen.get())
    }

    @Test(timeout = 60_000)
    fun rapidSeeks() {
        val total = 200L * 1024 * 1024
        val source = PatternSource(total)
        val stream = start(source)
        val p = path(stream)
        val random = Random(3)
        val hanging = ArrayList<Socket>()
        repeat(30) { i ->
            val at = random.nextLong(0, total - 10_000)
            if (i % 3 == 0) {
                // A request the player leaves hanging when it seeks again.
                val s = Socket(loopback, stream.port)
                s.getOutputStream().write("GET $p HTTP/1.1\r\nRange: bytes=$at-\r\n\r\n".toByteArray())
                hanging += s
            } else {
                assertArrayEquals(PatternSource.expected(at, 4096), request(stream.port, p, "Range: bytes=$at-", limit = 4096).body)
            }
        }
        hanging.forEach { runCatching { it.close() } }
        assertEquals(1, source.maxOpen.get())
        assertTrue(stream.running)
    }

    @Test
    fun idleCloseAndReopenAtTheSamePlace() {
        var now = 0L
        val source = PatternSource(10L * 1024 * 1024)
        val stream = start(source, config = LoopbackStream.Config(recovery = null, sweepEveryMs = 0, readerIdleMs = 30_000, sessionIdleMs = 3_600_000, clock = { now }))
        val p = path(stream)
        assertArrayEquals(PatternSource.expected(5000, 1000), request(stream.port, p, "Range: bytes=5000-5999").body)
        now += 30_000
        assertFalse(stream.sweep())
        assertFalse("the window goes during a long pause", stream.holdsWindow)
        now += 1_000_000
        assertFalse(stream.sweep())
        // The player comes back where it paused: the same URL, the same place.
        assertArrayEquals(PatternSource.expected(6000, 1000), request(stream.port, p, "Range: bytes=6000-6999").body)
        assertEquals(listOf(5000L, 6000L), source.opens.toList())
        // Nobody asking for the session's whole idle time closes it, port and all.
        val port = stream.port
        now += 3_600_000
        assertTrue(stream.sweep())
        assertFalse(stream.running)
        // Another process (a test run in a second checkout, say) may take the freed port at once, so ask
        // for the session's own URL: a closed port refuses, and a stranger on it does not know the token.
        val after = runCatching { request(port, p).code }.getOrNull()
        assertTrue("the session no longer serves", after == null || (after != 200 && after != 206))
    }

    @Test
    fun aPortInUseFallsBackToAnother() {
        ServerSocket(0, 1, loopback).use { taken ->
            val stream = start(PatternSource(1000), config = LoopbackStream.Config(port = taken.localPort, recovery = null, sweepEveryMs = 0))
            assertNotEquals(taken.localPort, stream.port)
            assertEquals(200, request(stream.port, path(stream)).code)
        }
    }

    @Test
    fun aWrongOrReusedTokenIsRefused() {
        val first = start(PatternSource(1000))
        val firstPath = path(first)
        assertEquals(200, request(first.port, firstPath).code)
        assertEquals(404, request(first.port, "/not-the-token/video.mkv").code)
        assertEquals(404, request(first.port, "/${first.token}/other.mkv").code)
        assertEquals(404, request(first.port, "/video.mkv").code)
        val port = first.port
        first.close()
        // A new session on the same port: the old URL is dead.
        val second = start(PatternSource(1000), config = LoopbackStream.Config(port = port, recovery = null, sweepEveryMs = 0))
        assertNotEquals(first.token, second.token)
        assertEquals(404, request(second.port, firstPath).code)
        assertEquals(200, request(second.port, path(second)).code)
    }

    @Test
    fun boundToLoopbackOnly() {
        val stream = start(PatternSource(1000))
        assertTrue(stream.url, stream.url.startsWith("http://127.0.0.1:${stream.port}/${stream.token}/"))
        assertTrue(LoopbackStream.isLoopbackClient(InetAddress.getByName("127.0.0.1")))
        for (stranger in listOf("192.168.1.20", "10.0.0.2", "172.16.0.9", "100.64.0.1", "169.254.1.1", "8.8.8.8", "fd00::1")) {
            assertFalse(stranger, LoopbackStream.isLoopbackClient(InetAddress.getByName(stranger)))
        }
        // Nothing on this machine's other addresses reaches it.
        val others = NetworkInterface.getNetworkInterfaces().toList()
            .flatMap { it.inetAddresses.toList() }
            .filter { it is Inet4Address && !it.isLoopbackAddress }
        for (address in others) {
            val outcome = runCatching { Socket().use { it.connect(InetSocketAddress(address, stream.port), 500) } }.exceptionOrNull()
            assertTrue("reached on ${address.hostAddress}", outcome != null)
        }
    }

    @Test
    fun aDownloadedFileIsServedDirectlyThenStreamsWhenItGoes() {
        val total = 300_000
        val file = File.createTempFile("loopback", ".mkv").apply { deleteOnExit() }
        file.writeBytes(PatternSource.expected(0, total))
        val source = PatternSource(total.toLong())
        val stream = start(source, file)
        val p = path(stream)
        assertArrayEquals(PatternSource.expected(1000, 2000), request(stream.port, p, "Range: bytes=1000-2999").body)
        assertTrue("no TDLib window for a file on the disk", source.opens.isEmpty())
        file.delete()
        assertArrayEquals(PatternSource.expected(5000, 2000), request(stream.port, p, "Range: bytes=5000-6999").body)
        assertEquals(listOf(5000L), source.opens.toList())
    }

    @Test
    fun aDownloadedFileDeletedWithNothingToFallBackOnIsTakenDown() {
        val file = File.createTempFile("loopback", ".mp4").apply { deleteOnExit() }
        file.writeBytes(PatternSource.expected(0, 10_000))
        val failures = CopyOnWriteArrayList<IOException>()
        val stream = start(null, file, LoopbackStream.Config(recovery = null, sweepEveryMs = 0, onFailure = { failures += it }))
        val p = path(stream)
        assertEquals(206, request(stream.port, p, "Range: bytes=0-99").code)
        file.delete()
        // The request in flight fails; the ones after it get a 404, not a loop of retries.
        runCatching { request(stream.port, p, "Range: bytes=100-199") }
        assertEquals(404, request(stream.port, p, "Range: bytes=100-199").code)
        assertTrue(failures.isNotEmpty())
        assertTrue(stream.running)
    }

    @Test
    fun nothingToServeIsAnError() {
        assertTrue(runCatching { LoopbackStream.start(null, null, "video/mp4") }.exceptionOrNull() is IOException)
        assertTrue(runCatching { LoopbackStream.start(null, File("/nonexistent/x.mkv"), "video/mp4") }.exceptionOrNull() is IOException)
    }

    @Test(timeout = 30_000)
    fun aNetworkCutIsRiddenOutByTheReadThatMetIt() {
        var cuts = 3
        val source = PatternSource(500_000, beforeRead = { at -> if (at >= 200_000 && cuts > 0) { cuts--; throw IOException("Telegram stopped sending") } })
        val recovery = CastRecovery(budgetMs = 5_000, firstBackoffMs = 5, maxBackoffMs = 20, reconnect = {})
        val stream = start(source, config = LoopbackStream.Config(recovery = recovery, sweepEveryMs = 0))
        val whole = request(stream.port, path(stream))
        assertArrayEquals(PatternSource.expected(0, 500_000), whole.body)
        assertEquals(1, source.maxOpen.get())
    }

    /** A cut that outlasts the recovery (or a full disk under TDLib) fails that request, is reported, and the session goes on. */
    @Test(timeout = 30_000)
    fun aLastingFailureIsReportedAndTheSessionGoesOn() {
        var broken = true
        val source = PatternSource(500_000, beforeRead = { at -> if (broken && at >= 100_000) throw IOException("No space left on device") })
        val failures = CopyOnWriteArrayList<IOException>()
        val recovery = CastRecovery(budgetMs = 200, firstBackoffMs = 5, maxBackoffMs = 20, reconnect = {})
        val stream = start(source, config = LoopbackStream.Config(recovery = recovery, sweepEveryMs = 0, onFailure = { failures += it }))
        val p = path(stream)
        val cut = request(stream.port, p)
        assertTrue(cut.body.size < 500_000)
        assertTrue(failures.any { it.message?.contains("No space") == true })
        broken = false
        assertArrayEquals(PatternSource.expected(100_000, 1000), request(stream.port, p, "Range: bytes=100000-100999").body)
        assertTrue(stream.running)
    }

    @Test
    fun closeEndsTheSession() {
        val stream = start(PatternSource(1000))
        val port = stream.port
        val p = path(stream)
        stream.close()
        assertFalse(stream.running)
        // A stranger may take the freed port at once, and it does not know the token.
        val after = runCatching { request(port, p).code }.getOrNull()
        assertTrue("the session no longer serves", after == null || (after != 200 && after != 206))
        stream.close()
    }
}
