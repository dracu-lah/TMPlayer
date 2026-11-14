package com.tmplayer.player

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * The stream handed to another app (PR4): one session on 127.0.0.1, a new video replacing the old,
 * the address dead once it is closed, and nothing to serve being an error the caller turns into
 * "play in TMPlayer".
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ExternalStreamsTest {

    private val context: Application = ApplicationProvider.getApplicationContext()

    private fun file(bytes: ByteArray): File = File.createTempFile("video", ".mp4").apply { writeBytes(bytes); deleteOnExit() }

    private fun get(address: String, range: String? = null): Pair<Int, ByteArray> {
        val connection = URL(address).openConnection() as HttpURLConnection
        if (range != null) connection.setRequestProperty("Range", range)
        return try {
            val code = connection.responseCode
            code to (if (code < 400) connection.inputStream.readBytes() else ByteArray(0))
        } finally {
            connection.disconnect()
        }
    }

    @Test
    fun `a finished file is served on loopback with ranges and the address dies on close`() = runBlocking {
        val bytes = ByteArray(10_000) { (it % 251).toByte() }
        val address = ExternalStreams.open(context, 0, 0, "video/mp4", "a b.mp4", "A video", file(bytes))
        try {
            assertTrue(address, address.startsWith("http://127.0.0.1:"))
            assertNotNull(ExternalStreams.current())
            val whole = get(address)
            assertEquals(200, whole.first)
            assertArrayEquals(bytes, whole.second)
            val tail = get(address, "bytes=9000-")
            assertEquals(206, tail.first)
            assertArrayEquals(bytes.copyOfRange(9_000, 10_000), tail.second)
            assertEquals(416, get(address, "bytes=20000-").first)
            // A guessed token reads nothing.
            assertEquals(404, get(address.replace(ExternalStreams.current()!!.token, "guess")).first)
        } finally {
            ExternalStreams.close(context)
        }
        assertNull(ExternalStreams.current())
        assertFalse(runCatching { get(address) }.map { it.first == 200 }.getOrDefault(false))
    }

    @Test
    fun `a new video replaces the old session`() = runBlocking {
        val first = ExternalStreams.open(context, 0, 0, "video/mp4", "one", "One", file(ByteArray(10) { 1 }))
        val second = ExternalStreams.open(context, 0, 0, "video/mp4", "two", "Two", file(ByteArray(10) { 2 }))
        try {
            assertFalse(first == second)
            assertFalse(runCatching { get(first) }.map { it.first == 200 }.getOrDefault(false))
            assertEquals(2.toByte(), get(second).second.first())
        } finally {
            ExternalStreams.close(context)
        }
    }

    @Test
    fun `nothing to serve is an error for the caller to turn into the built in player`() {
        val failure = runCatching { runBlocking { ExternalStreams.open(context, 0, 0, "video/mp4", "x", "X", null) } }.exceptionOrNull()
        assertTrue(failure is IOException)
        assertNull(ExternalStreams.current())
    }

    @Test
    fun `the stream is let go only after the app returned and nothing reads it`() = runBlocking {
        ExternalStreams.open(context, 0, 0, "video/mp4", "x", "X", file(ByteArray(10)))
        try {
            // Not yet back: kept, even though nothing is reading (a file holds no window).
            assertFalse(ExternalStreams.closeIfDone(context))
            ExternalStreams.markReturned()
            assertTrue(ExternalStreams.closeIfDone(context))
            assertNull(ExternalStreams.current())
        } finally {
            ExternalStreams.close(context)
        }
    }
}
