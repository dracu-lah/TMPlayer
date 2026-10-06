package com.tmplayer.online

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI

/** One request to an online provider. [body] is sent as JSON when present. */
data class HttpRequest(
    val method: String,
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    val body: String? = null,
)

/** The status, the headers (names in lower case) and the body, read whole. */
class HttpResponse(
    val code: Int,
    headers: Map<String, String> = emptyMap(),
    val body: ByteArray = ByteArray(0),
) {
    val headers: Map<String, String> = headers.mapKeys { it.key.lowercase() }

    val text: String get() = body.decodeToString()

    fun header(name: String): String? = headers[name.lowercase()]
}

/**
 * The one way the online providers reach the network, so the tests can stand a fake in its place.
 * A failure to connect at all is an [IOException]; any answer, an error status included, is a
 * [HttpResponse].
 */
fun interface HttpTransport {
    suspend fun send(request: HttpRequest): HttpResponse
}

/** [HttpTransport] over the JDK's own connection, which both Android and the desktop have. */
object UrlConnectionTransport : HttpTransport {

    /** Larger than any subtitle file or search page; past it the answer is cut off and refused. */
    private const val MAX_BYTES = 8 * 1024 * 1024

    override suspend fun send(request: HttpRequest): HttpResponse = withContext(Dispatchers.IO) {
        val connection = URI(request.url).toURL().openConnection() as HttpURLConnection
        try {
            connection.requestMethod = request.method
            connection.connectTimeout = 15_000
            connection.readTimeout = 20_000
            connection.instanceFollowRedirects = true
            request.headers.forEach { (name, value) -> connection.setRequestProperty(name, value) }
            if (request.body != null) {
                connection.doOutput = true
                connection.outputStream.use { it.write(request.body.toByteArray()) }
            }
            val code = connection.responseCode
            val stream = if (code >= 400) connection.errorStream else connection.inputStream
            val body = stream?.use { read(it) } ?: ByteArray(0)
            val headers = connection.headerFields
                .filterKeys { it != null }
                .mapValues { it.value.firstOrNull().orEmpty() }
            HttpResponse(code, headers, body)
        } finally {
            connection.disconnect()
        }
    }

    private fun read(input: InputStream): ByteArray {
        val out = ByteArrayOutputStream()
        val chunk = ByteArray(16 * 1024)
        while (true) {
            val n = input.read(chunk)
            if (n < 0) break
            out.write(chunk, 0, n)
            if (out.size() > MAX_BYTES) throw IOException("answer larger than $MAX_BYTES bytes")
        }
        return out.toByteArray()
    }
}
