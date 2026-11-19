package com.tmplayer.data

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

/**
 * Posts one text to the log inbox (`workers/log-inbox`) after the viewer said yes: a report
 * ([send], [LogUpload] says how the answer reads), a title report ([sendTitle], [TitleUpload]) or
 * feedback ([sendFeedback], [FeedbackUpload]).
 * One request, nothing kept: no cookies, no redirects followed (a captive portal's answer is a
 * failure, not a code), no retry on its own, and the connection is closed the moment the viewer
 * cancels. The phone and the desktop share it.
 */
object LogUploader {

    private const val CONNECT_MS = 15_000
    private const val READ_MS = 30_000
    private const val MAX_REPLY_BYTES = 4_096

    /** What came back from one request, before it is read as a report's or a title's answer. */
    private sealed interface Exchange {
        data class Answer(val status: Int, val body: String?, val retryAfter: String?) : Exchange
        data object NoConnection : Exchange
        data class Failed(val status: Int) : Exchange
    }

    /**
     * Never throws except for cancellation. Any trouble reaching the inbox, including a connection
     * that dies part way through the body, is [LogUpload.Outcome.NoConnection]; the caller keeps the
     * file instead.
     */
    suspend fun send(url: String, text: String): LogUpload.Outcome {
        if (!LogUpload.fits(text)) return LogUpload.Outcome.Failed(413)
        return when (val got = exchange(url, text)) {
            is Exchange.Answer -> LogUpload.outcome(got.status, got.body, got.retryAfter)
            Exchange.NoConnection -> LogUpload.Outcome.NoConnection
            is Exchange.Failed -> LogUpload.Outcome.Failed(got.status)
        }
    }

    /** One title report (the JSON of a [TitleReport]) to [url] (see [LogUpload.titlesUrl]). Never throws except for cancellation. */
    suspend fun sendTitle(url: String, text: String): TitleUpload.Outcome {
        if (text.encodeToByteArray().size > TitleUpload.MAX_BYTES) return TitleUpload.Outcome.Failed(413)
        return when (val got = exchange(url, text)) {
            is Exchange.Answer -> TitleUpload.outcome(got.status, got.body, got.retryAfter)
            Exchange.NoConnection -> TitleUpload.Outcome.NoConnection
            is Exchange.Failed -> TitleUpload.Outcome.Failed(got.status)
        }
    }

    /** One feedback report (the JSON of a [FeedbackReport]) to [url] (see [LogUpload.feedbackUrl]). Never throws except for cancellation. */
    suspend fun sendFeedback(url: String, text: String): TitleUpload.Outcome {
        if (text.encodeToByteArray().size > FeedbackUpload.MAX_BYTES) return TitleUpload.Outcome.Failed(413)
        return when (val got = exchange(url, text)) {
            is Exchange.Answer -> FeedbackUpload.outcome(got.status, got.body, got.retryAfter)
            Exchange.NoConnection -> TitleUpload.Outcome.NoConnection
            is Exchange.Failed -> TitleUpload.Outcome.Failed(got.status)
        }
    }

    private suspend fun exchange(url: String, text: String): Exchange {
        val bytes = text.toByteArray(Charsets.UTF_8)
        val connection = try {
            URL(url).openConnection() as HttpURLConnection
        } catch (e: Exception) {
            return Exchange.Failed(0)
        }
        return coroutineScope {
            val job = async(Dispatchers.IO) { post(connection, bytes) }
            try {
                job.await()
            } catch (e: CancellationException) {
                // Closing the socket is what makes the blocked write or read return.
                runCatching { connection.disconnect() }
                throw e
            }
        }
    }

    private fun post(connection: HttpURLConnection, bytes: ByteArray): Exchange = try {
        connection.requestMethod = "POST"
        connection.connectTimeout = CONNECT_MS
        connection.readTimeout = READ_MS
        connection.instanceFollowRedirects = false
        connection.useCaches = false
        connection.doOutput = true
        connection.setFixedLengthStreamingMode(bytes.size)
        connection.setRequestProperty("Content-Type", "text/plain; charset=utf-8")
        connection.setRequestProperty("User-Agent", "TMPlayer")
        connection.outputStream.use { it.write(bytes) }
        val status = connection.responseCode
        val stream = if (status >= 400) connection.errorStream else connection.inputStream
        val reply = stream?.use { readSmall(it) }
        Exchange.Answer(status, reply, connection.getHeaderField("Retry-After"))
    } catch (e: IOException) {
        Exchange.NoConnection
    } catch (e: Exception) {
        Exchange.Failed(0)
    } finally {
        runCatching { connection.disconnect() }
    }

    private fun readSmall(input: java.io.InputStream): String {
        val out = java.io.ByteArrayOutputStream()
        val chunk = ByteArray(1024)
        while (out.size() < MAX_REPLY_BYTES) {
            val n = input.read(chunk, 0, minOf(chunk.size, MAX_REPLY_BYTES - out.size()))
            if (n < 0) break
            out.write(chunk, 0, n)
        }
        return out.toString(Charsets.UTF_8.name())
    }
}
