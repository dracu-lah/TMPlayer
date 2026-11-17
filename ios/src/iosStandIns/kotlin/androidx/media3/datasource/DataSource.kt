package androidx.media3.datasource

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.DataReader

// Media3's data sources on iOS. Android's player reads TDLib's files through its own (TdDataSource,
// JoinedDataSource); on iOS the engine reads them through its stream (TdStreamProtocol), so these
// compile upstream's sources as written and nothing calls them to play.

/** Media3's source of bytes. */
interface DataSource : DataReader {
    fun addTransferListener(transferListener: TransferListener)

    fun open(dataSpec: DataSpec): Long

    fun getUri(): Uri?

    fun getResponseHeaders(): Map<String, List<String>> = emptyMap()

    fun close()

    /** Media3's factory of data sources. */
    fun interface Factory {
        fun createDataSource(): DataSource
    }
}

/** Media3's base for a data source, which tells its transfer listeners as it reads. */
abstract class BaseDataSource(private val isNetwork: Boolean) : DataSource {
    private val listeners = ArrayList<TransferListener>()
    private var dataSpec: DataSpec? = null

    final override fun addTransferListener(transferListener: TransferListener) {
        if (transferListener !in listeners) listeners.add(transferListener)
    }

    protected fun transferInitializing(dataSpec: DataSpec) {
        listeners.forEach { it.onTransferInitializing(this, dataSpec, isNetwork) }
    }

    protected fun transferStarted(dataSpec: DataSpec) {
        this.dataSpec = dataSpec
        listeners.forEach { it.onTransferStart(this, dataSpec, isNetwork) }
    }

    protected fun bytesTransferred(bytesTransferred: Int) {
        val spec = dataSpec ?: return
        listeners.forEach { it.onBytesTransferred(this, spec, isNetwork, bytesTransferred) }
    }

    protected fun transferEnded() {
        val spec = dataSpec ?: return
        listeners.forEach { it.onTransferEnd(this, spec, isNetwork) }
        dataSpec = null
    }
}

/** What a data source is asked to open, Media3's. */
class DataSpec private constructor(
    val uri: Uri,
    val uriPositionOffset: Long,
    val httpMethod: Int,
    val httpBody: ByteArray?,
    val httpRequestHeaders: Map<String, String>,
    val position: Long,
    val length: Long,
    val key: String?,
    val flags: Int,
    val customData: Any?,
) {
    constructor(uri: Uri) : this(uri, 0, HTTP_METHOD_GET, null, emptyMap(), 0, C.LENGTH_UNSET.toLong(), null, 0, null)

    constructor(uri: Uri, position: Long, length: Long) :
        this(uri, 0, HTTP_METHOD_GET, null, emptyMap(), position, length, null, 0, null)

    val absoluteStreamPosition: Long get() = uriPositionOffset + position

    fun isFlagSet(flag: Int): Boolean = flags and flag == flag

    fun buildUpon(): Builder = Builder().also {
        it.uri = uri
        it.uriPositionOffset = uriPositionOffset
        it.httpMethod = httpMethod
        it.httpBody = httpBody
        it.httpRequestHeaders = httpRequestHeaders
        it.position = position
        it.length = length
        it.key = key
        it.flags = flags
        it.customData = customData
    }

    fun withUri(uri: Uri): DataSpec = buildUpon().setUri(uri).build()

    fun subrange(offset: Long): DataSpec = subrange(offset, if (length == C.LENGTH_UNSET.toLong()) length else length - offset)

    fun subrange(offset: Long, length: Long): DataSpec = buildUpon().setPosition(position + offset).setLength(length).build()

    override fun toString(): String = "DataSpec[$uri, $position, $length, $key, $flags]"

    class Builder {
        internal var uri: Uri? = null
        internal var uriPositionOffset = 0L
        internal var httpMethod = HTTP_METHOD_GET
        internal var httpBody: ByteArray? = null
        internal var httpRequestHeaders: Map<String, String> = emptyMap()
        internal var position = 0L
        internal var length = C.LENGTH_UNSET.toLong()
        internal var key: String? = null
        internal var flags = 0
        internal var customData: Any? = null

        fun setUri(uri: Uri): Builder = apply { this.uri = uri }

        fun setUri(uri: String): Builder = apply { this.uri = Uri.parse(uri) }

        fun setUriPositionOffset(offset: Long): Builder = apply { uriPositionOffset = offset }

        fun setHttpMethod(method: Int): Builder = apply { httpMethod = method }

        fun setHttpBody(body: ByteArray?): Builder = apply { httpBody = body }

        fun setHttpRequestHeaders(headers: Map<String, String>): Builder = apply { httpRequestHeaders = headers }

        fun setPosition(position: Long): Builder = apply { this.position = position }

        fun setLength(length: Long): Builder = apply { this.length = length }

        fun setKey(key: String?): Builder = apply { this.key = key }

        fun setFlags(flags: Int): Builder = apply { this.flags = flags }

        fun setCustomData(customData: Any?): Builder = apply { this.customData = customData }

        fun build(): DataSpec = DataSpec(
            checkNotNull(uri) { "The uri must be set." },
            uriPositionOffset, httpMethod, httpBody, httpRequestHeaders, position, length, key, flags, customData,
        )
    }

    companion object {
        const val HTTP_METHOD_GET = 1
        const val HTTP_METHOD_POST = 2
        const val HTTP_METHOD_HEAD = 3
        const val FLAG_ALLOW_GZIP = 1
        const val FLAG_DONT_CACHE_IF_LENGTH_UNKNOWN = 1 shl 1
        const val FLAG_ALLOW_CACHE_FRAGMENTATION = 1 shl 2
        const val FLAG_MIGHT_NOT_USE_FULL_NETWORK_SPEED = 1 shl 3
    }
}

/** A data source's failure, Media3's, with the error code it maps to. */
open class DataSourceException(message: String?, cause: Throwable?, val reason: Int) : java.io.IOException(message, cause) {
    constructor(reason: Int) : this(null, null, reason)

    constructor(cause: Throwable?, reason: Int) : this(null, cause, reason)

    constructor(message: String?, reason: Int) : this(message, null, reason)

    companion object {
        @Deprecated("Media3's", ReplaceWith("PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE"))
        const val POSITION_OUT_OF_RANGE = 2008

        fun isCausedByPositionOutOfRange(e: java.io.IOException): Boolean {
            var cause: Throwable? = e
            while (cause != null) {
                if (cause is DataSourceException && cause.reason == 2008) return true
                cause = cause.cause
            }
            return false
        }
    }
}

/** Media3's listener of transfers. */
interface TransferListener {
    fun onTransferInitializing(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean)

    fun onTransferStart(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean)

    fun onBytesTransferred(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean, bytesTransferred: Int)

    fun onTransferEnd(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean)
}

/**
 * Media3's data source for every scheme: the upstream factory's source for what it handles
 * (tdfile://), files and the rest otherwise. On iOS the engine reads the video, so it hands out the
 * upstream factory's sources.
 */
class DefaultDataSource private constructor() {
    class Factory(
        @Suppress("UNUSED_PARAMETER") context: android.content.Context,
        private val upstream: DataSource.Factory,
    ) : DataSource.Factory {
        private var listener: TransferListener? = null

        fun setTransferListener(transferListener: TransferListener?): Factory = apply { listener = transferListener }

        override fun createDataSource(): DataSource = upstream.createDataSource().also { source ->
            listener?.let { source.addTransferListener(it) }
        }
    }
}
