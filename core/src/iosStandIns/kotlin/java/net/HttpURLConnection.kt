@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package java.net

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
import platform.Foundation.HTTPBody
import platform.Foundation.HTTPMethod
import platform.Foundation.NSData
import platform.Foundation.NSHTTPURLResponse
import platform.Foundation.NSMutableURLRequest
import platform.Foundation.NSURL
import platform.Foundation.NSURLRequestReloadIgnoringLocalCacheData
import platform.Foundation.NSURLSession
import platform.Foundation.NSURLSessionConfiguration
import platform.Foundation.NSURLSessionTaskDelegateProtocol
import platform.Foundation.NSURLSessionTask
import platform.Foundation.create
import platform.Foundation.dataTaskWithRequest
import platform.Foundation.setValue
import platform.darwin.DISPATCH_TIME_FOREVER
import platform.darwin.NSObject
import platform.darwin.dispatch_semaphore_create
import platform.darwin.dispatch_semaphore_signal
import platform.darwin.dispatch_semaphore_wait
import platform.posix.memcpy

abstract class URLConnection(val url: URL) {
    var connectTimeout: Int = 0
    var readTimeout: Int = 0
    var doOutput: Boolean = false
    var doInput: Boolean = true
    var useCaches: Boolean = true
    protected val requestHeaders = LinkedHashMap<String, MutableList<String>>()

    fun setRequestProperty(name: String, value: String) {
        requestHeaders[name] = mutableListOf(value)
    }

    fun addRequestProperty(name: String, value: String) {
        requestHeaders.getOrPut(name) { ArrayList() } += value
    }

    fun getRequestProperty(name: String): String? = requestHeaders[name]?.firstOrNull()

    /** The JDK streams a body of this length; URLSession sends the body it is given whole, so a hint only. */
    open fun setFixedLengthStreamingMode(contentLength: Int) = Unit

    open fun setFixedLengthStreamingMode(contentLength: Long) = Unit

    abstract fun connect()
    abstract val inputStream: InputStream
    abstract val outputStream: OutputStream
    abstract val headerFields: Map<String?, List<String>>

    fun getHeaderField(name: String): String? = headerFields.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value?.lastOrNull()
    val contentType: String? get() = getHeaderField("Content-Type")
    val contentLengthLong: Long get() = getHeaderField("Content-Length")?.toLongOrNull() ?: -1L
    val contentLength: Int get() = contentLengthLong.let { if (it > Int.MAX_VALUE) -1 else it.toInt() }
}

/**
 * java.net.HttpURLConnection over URLSession, run synchronously as the JDK's is: the request goes
 * out at the first look at the answer, and a status of 400 or more makes inputStream throw while
 * errorStream holds the body.
 */
class HttpURLConnection internal constructor(url: URL) : URLConnection(url) {
    var requestMethod: String = "GET"
    var instanceFollowRedirects: Boolean = true
    private var body: ByteArrayOutputStream? = null
    private var answer: Answer? = null

    private class Answer(val code: Int, val message: String, val headers: Map<String?, List<String>>, val body: ByteArray)

    override val outputStream: OutputStream
        get() {
            if (!doOutput) throw IOException("doOutput is off")
            if (requestMethod == "GET") requestMethod = "POST"
            return body ?: ByteArrayOutputStream().also { body = it }
        }

    override fun connect() {
        answer()
    }

    private fun answer(): Answer = answer ?: send().also { answer = it }

    private fun send(): Answer {
        val nsUrl = NSURL.URLWithString(url.toString()) ?: throw MalformedURLException(url.toString())
        val request = NSMutableURLRequest(uRL = nsUrl)
        request.HTTPMethod = requestMethod
        val timeout = maxOf(connectTimeout, readTimeout)
        if (timeout > 0) request.setTimeoutInterval(timeout / 1000.0)
        if (!useCaches) request.setCachePolicy(NSURLRequestReloadIgnoringLocalCacheData)
        for ((name, values) in requestHeaders) request.setValue(values.joinToString(","), forHTTPHeaderField = name)
        body?.toByteArray()?.let { request.HTTPBody = it.toNSData() }

        val done = dispatch_semaphore_create(0)
        var result: Answer? = null
        var failure: String? = null
        val session = if (instanceFollowRedirects) shared else noRedirects
        val task = session.dataTaskWithRequest(request) { data, response, error ->
            val http = response as? NSHTTPURLResponse
            if (error != null || http == null) {
                failure = error?.localizedDescription ?: "No answer"
            } else {
                val headers = LinkedHashMap<String?, List<String>>()
                headers[null] = listOf("HTTP/1.1 ${http.statusCode}")
                http.allHeaderFields.forEach { (name, value) -> (name as? String)?.let { headers[it] = listOf(value.toString()) } }
                result = Answer(http.statusCode.toInt(), NSHTTPURLResponse.localizedStringForStatusCode(http.statusCode), headers, data?.toByteArray() ?: ByteArray(0))
            }
            dispatch_semaphore_signal(done)
        }
        task.resume()
        dispatch_semaphore_wait(done, DISPATCH_TIME_FOREVER)
        return result ?: throw (if (failure?.contains("timed out", ignoreCase = true) == true) SocketTimeoutException(failure) else IOException(failure))
    }

    val responseCode: Int get() = answer().code
    val responseMessage: String get() = answer().message

    override val headerFields: Map<String?, List<String>> get() = answer().headers

    override val inputStream: InputStream
        get() {
            val a = answer()
            if (a.code >= 400) {
                if (a.code == HTTP_NOT_FOUND || a.code == HTTP_GONE) throw FileNotFoundException(url.toString())
                throw IOException("Server returned HTTP response code: ${a.code} for URL: $url")
            }
            return ByteArrayInputStream(a.body)
        }

    val errorStream: InputStream? get() = answer?.takeIf { it.code >= 400 }?.let { ByteArrayInputStream(it.body) }

    fun disconnect() = Unit

    companion object {
        const val HTTP_OK = 200
        const val HTTP_CREATED = 201
        const val HTTP_NO_CONTENT = 204
        const val HTTP_PARTIAL = 206
        const val HTTP_MOVED_PERM = 301
        const val HTTP_MOVED_TEMP = 302
        const val HTTP_NOT_MODIFIED = 304
        const val HTTP_BAD_REQUEST = 400
        const val HTTP_UNAUTHORIZED = 401
        const val HTTP_FORBIDDEN = 403
        const val HTTP_NOT_FOUND = 404
        const val HTTP_GONE = 410
        const val HTTP_INTERNAL_ERROR = 500
        const val HTTP_UNAVAILABLE = 503

        private val shared: NSURLSession by lazy {
            NSURLSession.sessionWithConfiguration(NSURLSessionConfiguration.ephemeralSessionConfiguration)
        }

        private val noRedirects: NSURLSession by lazy {
            NSURLSession.sessionWithConfiguration(NSURLSessionConfiguration.ephemeralSessionConfiguration, RefuseRedirects(), null)
        }
    }

    /**
     * Hands a redirect back as the answer, as instanceFollowRedirects = false does. A class, not an
     * object: Kotlin/Native cannot make an object of an Objective-C subclass. The session keeps it.
     */
    private class RefuseRedirects : NSObject(), NSURLSessionTaskDelegateProtocol {
        override fun URLSession(
            session: NSURLSession,
            task: NSURLSessionTask,
            willPerformHTTPRedirection: NSHTTPURLResponse,
            newRequest: platform.Foundation.NSURLRequest,
            completionHandler: (platform.Foundation.NSURLRequest?) -> Unit,
        ) = completionHandler(null)
    }
}

private fun ByteArray.toNSData(): NSData =
    if (isEmpty()) NSData() else usePinned { NSData.create(bytes = it.addressOf(0), length = size.convert()) }

private fun NSData.toByteArray(): ByteArray {
    val count = length.toInt()
    if (count == 0) return ByteArray(0)
    return ByteArray(count).also { out -> out.usePinned { memcpy(it.addressOf(0), bytes, length) } }
}
