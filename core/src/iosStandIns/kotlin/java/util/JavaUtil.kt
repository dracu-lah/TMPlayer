@file:OptIn(kotlin.io.encoding.ExperimentalEncodingApi::class)

package java.util

import java.io.InputStream
import java.io.OutputStream
import java.io.Reader
import java.io.Writer
import java.lang.Monitors

/** On iOS a locale is the pure half's TmLocale, as on the JVM TmLocale is java.util.Locale. */
typealias Locale = com.tmplayer.i18n.TmLocale

/** java.util.Date: an instant in epoch milliseconds. */
class Date(val time: Long = java.lang.System.currentTimeMillis()) : Comparable<Date> {
    fun getTime(): Long = time
    fun before(other: Date): Boolean = time < other.time
    fun after(other: Date): Boolean = time > other.time
    override fun compareTo(other: Date): Int = time.compareTo(other.time)
    override fun equals(other: Any?): Boolean = other is Date && other.time == time
    override fun hashCode(): Int = time.hashCode()
    override fun toString(): String = java.text.SimpleDateFormat("EEE MMM dd HH:mm:ss zzz yyyy", Locale.US).format(this)
}

/**
 * java.util.Properties: string keys and values, read and written in the .properties format
 * (comments, `=` `:` or space separators, backslash escapes and line continuations).
 */
class Properties private constructor(private val map: LinkedHashMap<Any, Any>) : MutableMap<Any, Any> by map {
    constructor() : this(LinkedHashMap())

    fun getProperty(key: String): String? = map[key] as? String

    fun getProperty(key: String, fallback: String): String = getProperty(key) ?: fallback

    fun setProperty(key: String, value: String): Any? = map.put(key, value)

    fun stringPropertyNames(): Set<String> = map.keys.filterIsInstance<String>().toSet()

    fun load(input: InputStream) = load(input.reader(java.nio.charset.Charsets.ISO_8859_1))

    fun load(reader: Reader) {
        val lines = reader.readText().lines()
        var i = 0
        while (i < lines.size) {
            var line = lines[i++].trimStart()
            if (line.isEmpty() || line[0] == '#' || line[0] == '!') continue
            while (endsWithContinuation(line) && i < lines.size) line = line.dropLast(1) + lines[i++].trimStart()
            var cut = 0
            while (cut < line.length) {
                val c = line[cut]
                if (c == '\\') {
                    cut += 2
                    continue
                }
                if (c == '=' || c == ':' || c.isWhitespace()) break
                cut++
            }
            val key = unescape(line.substring(0, cut.coerceAtMost(line.length)))
            var rest = line.substring(cut.coerceAtMost(line.length)).trimStart()
            if (rest.startsWith("=") || rest.startsWith(":")) rest = rest.drop(1).trimStart()
            map[key] = unescape(rest)
        }
    }

    fun store(out: OutputStream, comments: String?) {
        val writer = out.writer(java.nio.charset.Charsets.ISO_8859_1)
        store(writer, comments)
        writer.flush()
    }

    fun store(writer: Writer, comments: String?) {
        if (comments != null) writer.write("#" + comments.replace("\n", "\n#") + "\n")
        writer.write("#" + Date().toString() + "\n")
        for ((key, value) in map) writer.write(escape(key.toString(), key = true) + "=" + escape(value.toString(), key = false) + "\n")
        writer.flush()
    }

    private fun endsWithContinuation(line: String): Boolean = line.takeLastWhile { it == '\\' }.length % 2 == 1

    private fun unescape(text: String): String {
        val out = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            val c = text[i++]
            if (c != '\\' || i >= text.length) {
                out.append(c)
                continue
            }
            when (val e = text[i++]) {
                't' -> out.append('\t')
                'n' -> out.append('\n')
                'r' -> out.append('\r')
                'f' -> out.append('\u000C')
                'u' -> {
                    val hex = text.substring(i, minOf(i + 4, text.length))
                    out.append(hex.toIntOrNull(16)?.toChar() ?: '?')
                    i += hex.length
                }
                else -> out.append(e)
            }
        }
        return out.toString()
    }

    private fun escape(text: String, key: Boolean): String = buildString {
        text.forEachIndexed { index, c ->
            when {
                c == '\\' -> append("\\\\")
                c == '\t' -> append("\\t")
                c == '\n' -> append("\\n")
                c == '\r' -> append("\\r")
                c == '=' || c == ':' || c == '#' || c == '!' -> append('\\').append(c)
                c == ' ' && (key || index == 0) -> append("\\ ")
                c.code < 0x20 || c.code > 0x7E -> append("\\u").append(c.code.toString(16).padStart(4, '0'))
                else -> append(c)
            }
        }
    }
}

/** java.util.Base64 over Kotlin's own codec. */
object Base64 {
    class Encoder internal constructor(private val codec: kotlin.io.encoding.Base64) {
        fun encodeToString(bytes: ByteArray): String = codec.encode(bytes)
        fun encode(bytes: ByteArray): ByteArray = codec.encodeToByteArray(bytes)
        fun withoutPadding(): Encoder = Encoder(codec.withPadding(kotlin.io.encoding.Base64.PaddingOption.ABSENT))
    }

    class Decoder internal constructor(private val codec: kotlin.io.encoding.Base64) {
        fun decode(text: String): ByteArray = codec.decode(text)
        fun decode(bytes: ByteArray): ByteArray = codec.decode(bytes)
    }

    private val lenient = kotlin.io.encoding.Base64.Default.withPadding(kotlin.io.encoding.Base64.PaddingOption.PRESENT_OPTIONAL)
    private val lenientUrl = kotlin.io.encoding.Base64.UrlSafe.withPadding(kotlin.io.encoding.Base64.PaddingOption.PRESENT_OPTIONAL)

    fun getEncoder(): Encoder = Encoder(kotlin.io.encoding.Base64.Default)
    fun getDecoder(): Decoder = Decoder(lenient)
    fun getUrlEncoder(): Encoder = Encoder(kotlin.io.encoding.Base64.UrlSafe)
    fun getUrlDecoder(): Decoder = Decoder(lenientUrl)
    fun getMimeDecoder(): Decoder = Decoder(kotlin.io.encoding.Base64.Mime)
}

object Collections {
    fun <T> synchronizedSet(set: MutableSet<T>): MutableSet<T> = LockedSet(set)
    fun <T> synchronizedList(list: MutableList<T>): MutableList<T> = LockedList(list)
    fun <T> emptyList(): List<T> = kotlin.collections.emptyList()
    fun <T> unmodifiableList(list: List<T>): List<T> = list.toList()
    fun <T> unmodifiableSet(set: Set<T>): Set<T> = set.toSet()
}

/** A set behind its own lock; iteration walks a copy taken under the lock. */
private class LockedSet<T>(private val set: MutableSet<T>) : MutableSet<T> {
    private inline fun <R> locked(block: () -> R): R = Monitors.synchronized(this, block)
    override val size: Int get() = locked { set.size }
    override fun contains(element: T): Boolean = locked { set.contains(element) }
    override fun containsAll(elements: Collection<T>): Boolean = locked { set.containsAll(elements) }
    override fun isEmpty(): Boolean = locked { set.isEmpty() }
    override fun add(element: T): Boolean = locked { set.add(element) }
    override fun addAll(elements: Collection<T>): Boolean = locked { set.addAll(elements) }
    override fun clear() = locked { set.clear() }
    override fun remove(element: T): Boolean = locked { set.remove(element) }
    override fun removeAll(elements: Collection<T>): Boolean = locked { set.removeAll(elements.toSet()) }
    override fun retainAll(elements: Collection<T>): Boolean = locked { set.retainAll(elements.toSet()) }
    override fun iterator(): MutableIterator<T> {
        val copy = locked { set.toList() }.iterator()
        return object : MutableIterator<T> {
            private var last: T? = null
            override fun hasNext(): Boolean = copy.hasNext()
            override fun next(): T = copy.next().also { last = it }
            @Suppress("UNCHECKED_CAST")
            override fun remove() {
                locked { set.remove(last as T) }
            }
        }
    }
    override fun equals(other: Any?): Boolean = locked { set == other }
    override fun hashCode(): Int = locked { set.hashCode() }
    override fun toString(): String = locked { set.toString() }
}

private class LockedList<T>(private val list: MutableList<T>) : MutableList<T> by list {
    override fun add(element: T): Boolean = Monitors.synchronized(this) { list.add(element) }
    override fun remove(element: T): Boolean = Monitors.synchronized(this) { list.remove(element) }
    override fun iterator(): MutableIterator<T> = Monitors.synchronized(this) { list.toMutableList() }.iterator()
}
