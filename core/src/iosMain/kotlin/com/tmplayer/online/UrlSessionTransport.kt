@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package com.tmplayer.online

import com.tmplayer.platform.IOException
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.suspendCancellableCoroutine
import platform.Foundation.HTTPBody
import platform.Foundation.HTTPMethod
import platform.Foundation.NSData
import platform.Foundation.NSHTTPURLResponse
import platform.Foundation.NSMutableURLRequest
import platform.Foundation.NSURL
import platform.Foundation.NSURLSession
import platform.Foundation.NSURLSessionConfiguration
import platform.Foundation.create
import platform.Foundation.dataTaskWithRequest
import platform.Foundation.setValue
import platform.posix.memcpy
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * [HttpTransport] over Foundation's URLSession, the iOS twin of the JVM's UrlConnectionTransport:
 * the same timeouts, the same size cap, the same rule that only a failure to get any answer is an
 * [IOException].
 */
object UrlSessionTransport : HttpTransport {
    /** Larger than any subtitle file or search page; past it the answer is refused. */
    private const val MAX_BYTES = 8 * 1024 * 1024

    private val session: NSURLSession by lazy {
        val configuration = NSURLSessionConfiguration.ephemeralSessionConfiguration
        configuration.timeoutIntervalForRequest = 20.0
        // Room for the posters fetched side by side to keep their connections, as on the JVM.
        configuration.HTTPMaximumConnectionsPerHost = 8
        // No cookies and no response cache: the JDK's connection keeps neither.
        configuration.HTTPShouldSetCookies = false
        configuration.HTTPCookieStorage = null
        configuration.URLCache = null
        NSURLSession.sessionWithConfiguration(configuration)
    }

    override suspend fun send(request: HttpRequest): HttpResponse {
        // An address that does not parse, or is not http, is a failed request like any other: the
        // providers catch IOException only.
        val url = NSURL.URLWithString(request.url)
        val scheme = url?.scheme?.lowercase()
        if (url == null || (scheme != "http" && scheme != "https")) throw IOException("Not a web address: ${request.url}")
        val urlRequest = NSMutableURLRequest(uRL = url)
        urlRequest.HTTPMethod = request.method
        request.headers.forEach { (name, value) -> urlRequest.setValue(value, forHTTPHeaderField = name) }
        request.body?.let { urlRequest.HTTPBody = it.encodeToByteArray().toNSData() }
        return suspendCancellableCoroutine { continuation ->
            val task = session.dataTaskWithRequest(urlRequest) { data, response, error ->
                val http = response as? NSHTTPURLResponse
                when {
                    error != null || http == null ->
                        continuation.resumeWithException(IOException(error?.localizedDescription ?: "No answer"))
                    (data?.length ?: 0u) > MAX_BYTES.toULong() ->
                        continuation.resumeWithException(IOException("Answer larger than $MAX_BYTES bytes"))
                    else -> {
                        val headers = http.allHeaderFields.entries
                            .mapNotNull { (name, value) -> (name as? String)?.let { it to value.toString() } }
                            .toMap()
                        continuation.resume(HttpResponse(http.statusCode.toInt(), headers, data?.toByteArray() ?: ByteArray(0)))
                    }
                }
            }
            continuation.invokeOnCancellation { task.cancel() }
            task.resume()
        }
    }
}

internal fun ByteArray.toNSData(): NSData =
    if (isEmpty()) NSData() else usePinned { NSData.create(bytes = it.addressOf(0), length = size.convert()) }

internal fun NSData.toByteArray(): ByteArray {
    val count = length.toInt()
    if (count == 0) return ByteArray(0)
    return ByteArray(count).also { out -> out.usePinned { memcpy(it.addressOf(0), bytes, length) } }
}
