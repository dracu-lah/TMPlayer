package com.tmplayer.data

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The upload against a local server: a code, a limit, an error, no answer, a connection cut off, and a cancel. */
class LogUploaderTest {

    private var server: HttpServer? = null
    private var raw: ServerSocket? = null

    @After
    fun stop() {
        server?.stop(0)
        runCatching { raw?.close() }
    }

    private fun serve(status: Int, body: String, headers: Map<String, String> = emptyMap(), path: String = "/v1/report", seen: (String, String?) -> Unit = { _, _ -> }): String {
        val s = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        s.createContext(path) { ex ->
            val received = ex.requestBody.readBytes().toString(Charsets.UTF_8)
            seen(received, ex.requestHeaders.getFirst("Content-Type"))
            val out = body.toByteArray()
            headers.forEach { (k, v) -> ex.responseHeaders.add(k, v) }
            ex.sendResponseHeaders(status, out.size.toLong())
            ex.responseBody.use { it.write(out) }
        }
        s.start()
        server = s
        return "http://127.0.0.1:${s.address.port}$path"
    }

    @Test
    fun `a stored report gives its code and sends the text as plain text`() = runBlocking {
        var got = ""
        var type: String? = null
        val url = serve(201, """{"code":"TMP-7K3Q","keepDays":30}""", seen = { b, t -> got = b; type = t })
        val outcome = LogUploader.send(url, "TMPlayer logs\nhello é")
        assertEquals(LogUpload.Outcome.Sent("TMP-7K3Q"), outcome)
        assertEquals("TMPlayer logs\nhello é", got)
        assertEquals("text/plain; charset=utf-8", type)
    }

    @Test
    fun `a limit and an error are told apart`() = runBlocking {
        assertEquals(LogUpload.Outcome.SlowDown(45), LogUploader.send(serve(429, """{"error":"slow_down"}""", mapOf("Retry-After" to "45")), "TMPlayer logs"))
        stop()
        assertEquals(LogUpload.Outcome.Failed(503), LogUploader.send(serve(503, """{"error":"unavailable"}"""), "TMPlayer logs"))
    }

    @Test
    fun `a redirect is a failure, not a code`() = runBlocking {
        val url = serve(302, "", mapOf("Location" to "http://127.0.0.1:1/"))
        assertEquals(LogUpload.Outcome.Failed(302), LogUploader.send(url, "TMPlayer logs"))
    }

    @Test
    fun `nobody listening is no connection`() = runBlocking {
        val free = ServerSocket(0).use { it.localPort }
        assertEquals(LogUpload.Outcome.NoConnection, LogUploader.send("http://127.0.0.1:$free/v1/report", "TMPlayer logs"))
    }

    @Test
    fun `a connection cut off before the answer is no connection`() = runBlocking {
        val socket = ServerSocket(0)
        raw = socket
        Thread {
            runCatching {
                socket.accept().use { it.getInputStream().read(ByteArray(64)) }
            }
        }.start()
        assertEquals(LogUpload.Outcome.NoConnection, LogUploader.send("http://127.0.0.1:${socket.localPort}/v1/report", "TMPlayer logs"))
    }

    @Test
    fun `an address that is not one is a failure and not a crash`() = runBlocking {
        assertEquals(LogUpload.Outcome.Failed(0), LogUploader.send("not a url", "TMPlayer logs"))
    }

    @Test
    fun `text over the cap never leaves`() = runBlocking {
        val url = serve(201, """{"code":"TMP-7K3Q"}""")
        assertEquals(LogUpload.Outcome.Failed(413), LogUploader.send(url, "a".repeat(LogUpload.MAX_BYTES + 1)))
    }

    @Test
    fun `cancelling while the server is silent returns promptly`() {
        val socket = ServerSocket(0)
        raw = socket
        val accepted = CountDownLatch(1)
        Thread {
            runCatching {
                socket.accept().use {
                    accepted.countDown()
                    Thread.sleep(10_000)
                }
            }
        }.start()
        val began = System.nanoTime()
        runBlocking {
            val job = async(kotlinx.coroutines.Dispatchers.Default) { LogUploader.send("http://127.0.0.1:${socket.localPort}/v1/report", "TMPlayer logs") }
            assertTrue(accepted.await(5, TimeUnit.SECONDS))
            delay(200)
            job.cancel()
            try {
                job.await()
            } catch (e: CancellationException) {
                // expected
            }
        }
        assertTrue("took ${(System.nanoTime() - began) / 1_000_000} ms", (System.nanoTime() - began) / 1_000_000 < 5_000)
    }

    @Test
    fun `a title report is posted as plain text and its code comes back`() = runBlocking {
        var got = ""
        var type: String? = null
        val url = serve(201, """{"code":"NAME-2PGE","duplicate":false,"keepDays":30}""", path = "/v1/titles", seen = { b, t -> got = b; type = t })
        val text = TitleReport.of("Night.Train.2024.mkv", "Night Train")!!.toJson()
        assertEquals(TitleUpload.Outcome.Sent("NAME-2PGE"), LogUploader.sendTitle(url, text))
        assertEquals(text, got)
        assertEquals("text/plain; charset=utf-8", type)
    }

    @Test
    fun `a title report meets the limit, an error, a redirect and nobody listening`() = runBlocking {
        assertEquals(TitleUpload.Outcome.SlowDown(7), LogUploader.sendTitle(serve(429, "{}", mapOf("Retry-After" to "7"), "/v1/titles"), "{}"))
        stop()
        assertEquals(TitleUpload.Outcome.Failed(503), LogUploader.sendTitle(serve(503, "{}", path = "/v1/titles"), "{}"))
        stop()
        assertEquals(TitleUpload.Outcome.Failed(302), LogUploader.sendTitle(serve(302, "", mapOf("Location" to "http://127.0.0.1:1/"), "/v1/titles"), "{}"))
        val free = ServerSocket(0).use { it.localPort }
        assertEquals(TitleUpload.Outcome.NoConnection, LogUploader.sendTitle("http://127.0.0.1:$free/v1/titles", "{}"))
    }

    @Test
    fun `a title text over the cap never leaves`() = runBlocking {
        val url = serve(201, """{"code":"NAME-2PGE"}""", path = "/v1/titles")
        assertEquals(TitleUpload.Outcome.Failed(413), LogUploader.sendTitle(url, "x".repeat(TitleUpload.MAX_BYTES + 1)))
    }
}
