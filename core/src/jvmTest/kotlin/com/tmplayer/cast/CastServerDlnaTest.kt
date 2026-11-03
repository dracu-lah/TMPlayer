package com.tmplayer.cast

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.Socket

/** The DLNA part of [CastServer]: HEAD, the headers a renderer asks for, SRT beside the video, and idle time. */
class CastServerDlnaTest {

    private val loopback = InetAddress.getLoopbackAddress()
    private var now = 0L
    private val server = CastServer(loopback, clock = { now }).apply { start() }
    private val features = DlnaServing("DLNA.ORG_OP=01;DLNA.ORG_CI=0;DLNA.ORG_FLAGS=01700000000000000000000000000000", captionName = "subtitle.srt")

    @After
    fun stop() = server.stop()

    private class Reply(val code: Int, val headers: Map<String, String>, val body: String)

    private fun request(path: String, method: String = "GET", vararg headers: String): Reply =
        Socket(loopback, server.boundPort).use { s ->
            val head = buildString {
                append("$method $path HTTP/1.1\r\nHost: t\r\nConnection: close\r\n")
                headers.forEach { append(it).append("\r\n") }
                append("\r\n")
            }
            s.getOutputStream().write(head.toByteArray())
            val input = s.getInputStream().buffered()
            fun line(): String {
                val sb = StringBuilder()
                while (true) {
                    val b = input.read()
                    if (b < 0 || b == '\n'.code) break
                    if (b != '\r'.code) sb.append(b.toChar())
                }
                return sb.toString()
            }
            val code = line().split(' ')[1].toInt()
            val map = HashMap<String, String>()
            while (true) {
                val h = line()
                if (h.isEmpty()) break
                map[h.substringBefore(':').trim().lowercase()] = h.substringAfter(':').trim()
            }
            val length = map["content-length"]?.toIntOrNull() ?: 0
            val body = if (method == "HEAD") "" else String(input.readNBytes(length))
            Reply(code, map, body)
        }

    private fun publish() {
        server.publish("video.mp4", CastResource.Ranged(BytesCastSource(ByteArray(1000)), "video/mp4"), features)
        server.publish("subtitle.srt", CastResource.Text("1\n00:00:01,000 --> 00:00:02,000\nhi\n", WebVtt.SRT_MIME), DlnaServing("DLNA.ORG_OP=00", interactive = true))
    }

    @Test
    fun headAnswersTheLengthAndTheTypeWithoutABody() {
        publish()
        val r = request("/${server.token}/video.mp4", "HEAD")
        assertEquals(200, r.code)
        assertEquals("1000", r.headers["content-length"])
        assertEquals("video/mp4", r.headers["content-type"])
        assertEquals("bytes", r.headers["accept-ranges"])
        assertEquals(404, request("/wrong/video.mp4", "HEAD").code)
    }

    @Test
    fun dlnaHeadersAreSentWhenAskedFor() {
        publish()
        val plain = request("/${server.token}/video.mp4", "GET", "Range: bytes=0-9")
        assertEquals("Streaming", plain.headers["transfermode.dlna.org"])
        assertNull("not asked, not sent", plain.headers["contentfeatures.dlna.org"])
        assertNull(plain.headers["captioninfo.sec"])

        val asked = request("/${server.token}/video.mp4", "HEAD", "getcontentFeatures.dlna.org: 1", "getCaptionInfo.sec: 1", "transferMode.dlna.org: Background")
        assertEquals(features.features, asked.headers["contentfeatures.dlna.org"])
        assertEquals("Background", asked.headers["transfermode.dlna.org"])
        assertEquals(server.urlFor("subtitle.srt"), asked.headers["captioninfo.sec"])
    }

    @Test
    fun theSubtitleIsServedAsSrtInteractively() {
        publish()
        val r = request("/${server.token}/subtitle.srt")
        assertEquals(200, r.code)
        assertEquals(WebVtt.SRT_MIME, r.headers["content-type"])
        assertEquals("Interactive", r.headers["transfermode.dlna.org"])
        assertTrue("hi" in r.body)
    }

    @Test
    fun aResourceNotPublishedForDlnaGetsNoDlnaHeaders() {
        server.publish("plain.mp4", CastResource.Ranged(BytesCastSource(ByteArray(10)), "video/mp4"))
        val r = request("/${server.token}/plain.mp4", "HEAD", "getcontentFeatures.dlna.org: 1")
        assertNull(r.headers["contentfeatures.dlna.org"])
        assertNull(r.headers["transfermode.dlna.org"])
    }

    @Test
    fun idleTimeCountsFromTheLastRequest() {
        publish()
        assertEquals(0L, server.idleMs())
        now += 10_000
        assertEquals(10_000L, server.idleMs())
        request("/${server.token}/video.mp4", "HEAD")
        assertEquals(0L, server.idleMs())
    }

    @Test
    fun aStrangerGets403() {
        val closed = CastServer(loopback, allowed = { false }).apply { start() }
        try {
            Socket(loopback, closed.boundPort).use { s ->
                s.soTimeout = 3000
                s.getOutputStream().write("GET /x/y HTTP/1.1\r\nHost: t\r\n\r\n".toByteArray())
                assertEquals("HTTP/1.1 403 Forbidden", s.getInputStream().bufferedReader().readLine())
            }
        } finally {
            closed.stop()
        }
    }
}
