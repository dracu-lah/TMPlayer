package android.net

import java.io.File
import platform.Foundation.NSURLComponents

/** android.net.Uri: an address taken apart as RFC 3986 does, file:// addresses included. */
open class Uri private constructor(private val text: String) : Comparable<Uri> {
    private val parts: NSURLComponents? by lazy { NSURLComponents.componentsWithString(text) }

    open val scheme: String? get() = parts?.scheme ?: text.substringBefore(':', "").ifEmpty { null }
    open val host: String? get() = parts?.host
    open val port: Int get() = parts?.port?.intValue ?: -1
    open val path: String? get() = parts?.path ?: if (scheme == null) text else null
    open val encodedPath: String? get() = parts?.percentEncodedPath
    open val query: String? get() = parts?.query
    open val encodedQuery: String? get() = parts?.percentEncodedQuery
    open val fragment: String? get() = parts?.fragment
    open val authority: String? get() = host?.let { if (port >= 0) "$it:$port" else it }
    open val pathSegments: List<String> get() = path.orEmpty().split('/').filter { it.isNotEmpty() }
    open val lastPathSegment: String? get() = pathSegments.lastOrNull()
    open val schemeSpecificPart: String get() = text.substringAfter(':')
    open val isAbsolute: Boolean get() = scheme != null
    open val isRelative: Boolean get() = scheme == null
    open val isHierarchical: Boolean get() = parts != null

    fun getQueryParameter(key: String): String? =
        parts?.queryItems?.firstOrNull { (it as? platform.Foundation.NSURLQueryItem)?.name == key }
            ?.let { (it as platform.Foundation.NSURLQueryItem).value }

    /** Every value of [key] in the query, in order (a split video's pieces, JoinedDataSource). */
    fun getQueryParameters(key: String): List<String> =
        parts?.queryItems?.mapNotNull { item ->
            (item as? platform.Foundation.NSURLQueryItem)?.takeIf { it.name == key }?.value
        }.orEmpty()

    fun getQueryParameterNames(): Set<String> =
        parts?.queryItems?.mapNotNull { (it as? platform.Foundation.NSURLQueryItem)?.name }?.toSet().orEmpty()

    fun buildUpon(): Builder = Builder(text)

    override fun toString(): String = text
    override fun equals(other: Any?): Boolean = other is Uri && other.text == text
    override fun hashCode(): Int = text.hashCode()
    override fun compareTo(other: Uri): Int = text.compareTo(other.text)

    class Builder(private var current: String = "") {
        private val query = ArrayList<Pair<String, String>>()
        fun scheme(value: String): Builder = apply { current = "$value://" + current.substringAfter("://", current) }
        fun authority(value: String): Builder = apply { current = current.substringBefore("://", "") + "://" + value }
        fun path(value: String): Builder = apply { current += if (value.startsWith("/")) value else "/$value" }
        fun appendPath(segment: String): Builder = apply { current = current.trimEnd('/') + "/" + java.net.URLEncoder.encode(segment, "UTF-8").replace("+", "%20") }
        fun encodedPath(value: String): Builder = path(value)
        fun appendQueryParameter(key: String, value: String): Builder = apply { query += key to value }
        fun build(): Uri {
            val tail = query.joinToString("&") { (k, v) -> java.net.URLEncoder.encode(k, "UTF-8") + "=" + java.net.URLEncoder.encode(v, "UTF-8") }
            return Uri(if (tail.isEmpty()) current else "$current${if ('?' in current) "&" else "?"}$tail")
        }
    }

    companion object {
        val EMPTY: Uri = Uri("")
        fun parse(text: String?): Uri = Uri(text.orEmpty())
        fun fromFile(file: File): Uri = Uri("file://" + file.absolutePath)
        fun fromParts(scheme: String, ssp: String, fragment: String?): Uri = Uri("$scheme:$ssp" + (fragment?.let { "#$it" } ?: ""))
        fun encode(text: String): String = java.net.URLEncoder.encode(text, "UTF-8").replace("+", "%20")
        fun decode(text: String): String = java.net.URLDecoder.decode(text.replace("+", "%2B"), "UTF-8")
        fun withAppendedPath(base: Uri, segment: String): Uri = base.buildUpon().appendPath(segment).build()
    }
}
