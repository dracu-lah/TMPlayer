@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package java.nio.charset

import java.nio.ByteBuffer
import java.nio.CharBuffer
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
import platform.CoreFoundation.CFStringConvertEncodingToNSStringEncoding
import platform.CoreFoundation.CFStringConvertIANACharSetNameToEncoding
import platform.CoreFoundation.kCFStringEncodingInvalidId
import platform.Foundation.NSData
import platform.Foundation.NSString
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.create
import platform.Foundation.dataUsingEncoding
import platform.posix.memcpy

/**
 * A charset by its IANA name. UTF-8, ISO-8859-1 and US-ASCII are decoded here; any other name
 * Foundation knows (windows-1252, windows-1251, Shift_JIS, ...) goes through NSString, as subtitle
 * files in those encodings need.
 */
open class Charset internal constructor(private val canonical: String, private val nsEncoding: ULong?) {
    fun name(): String = canonical
    fun displayName(): String = canonical
    override fun toString(): String = canonical
    override fun equals(other: Any?): Boolean = other is Charset && other.canonical.equals(canonical, ignoreCase = true)
    override fun hashCode(): Int = canonical.lowercase().hashCode()

    /** Lenient, as `String(bytes, charset)`: what cannot be decoded becomes U+FFFD. */
    fun decode(bytes: ByteArray): String = decodeOrNull(bytes, strict = false) ?: bytes.decodeToString()

    /** Lenient, as `String.toByteArray(charset)`: what cannot be encoded becomes '?'. */
    fun encode(text: String): ByteArray = when (canonical) {
        "UTF-8" -> text.encodeToByteArray()
        "ISO-8859-1" -> ByteArray(text.length) { i -> text[i].code.let { if (it < 256) it.toByte() else '?'.code.toByte() } }
        "US-ASCII" -> ByteArray(text.length) { i -> text[i].code.let { if (it < 128) it.toByte() else '?'.code.toByte() } }
        else -> NSString.create(string = text).dataUsingEncoding(nsEncoding ?: NSUTF8StringEncoding, allowLossyConversion = true)
            ?.toByteArray() ?: text.encodeToByteArray()
    }

    fun encode(text: CharSequence): ByteBuffer = ByteBuffer.wrap(encode(text.toString()))

    fun decode(buffer: ByteBuffer): CharBuffer {
        val bytes = ByteArray(buffer.remaining())
        buffer.get(bytes)
        return CharBuffer.wrap(decode(bytes))
    }

    fun newDecoder(): CharsetDecoder = CharsetDecoder(this)

    internal fun decodeOrNull(bytes: ByteArray, strict: Boolean): String? = when (canonical) {
        "UTF-8" -> if (strict) runCatching { bytes.decodeToString(throwOnInvalidSequence = true) }.getOrNull() else bytes.decodeToString()
        "ISO-8859-1" -> CharArray(bytes.size) { (bytes[it].toInt() and 0xFF).toChar() }.concatToString()
        "US-ASCII" -> if (strict && bytes.any { it < 0 }) null else CharArray(bytes.size) { (bytes[it].toInt() and 0x7F).toChar() }.concatToString()
        else -> bytes.toNSData().let { data -> NSString.create(data = data, encoding = nsEncoding ?: NSUTF8StringEncoding)?.toString() }
    }

    companion object {
        private val aliases = mapOf(
            "UTF8" to "UTF-8", "UTF-8" to "UTF-8",
            "ISO-8859-1" to "ISO-8859-1", "ISO8859-1" to "ISO-8859-1", "ISO_8859_1" to "ISO-8859-1", "LATIN1" to "ISO-8859-1",
            "US-ASCII" to "US-ASCII", "ASCII" to "US-ASCII",
        )

        fun forName(name: String): Charset {
            aliases[name.uppercase()]?.let { canonical -> return Charset(canonical, null) }
            val cf = CFStringConvertIANACharSetNameToEncoding(name.toCFString())
            if (cf == kCFStringEncodingInvalidId) throw UnsupportedCharsetException(name)
            return Charset(name, CFStringConvertEncodingToNSStringEncoding(cf).convert())
        }

        fun isSupported(name: String): Boolean = runCatching { forName(name) }.isSuccess

        fun defaultCharset(): Charset = Charsets.UTF_8
    }
}

private fun String.toCFString(): platform.CoreFoundation.CFStringRef? =
    platform.CoreFoundation.CFStringCreateWithCString(null, this, platform.CoreFoundation.kCFStringEncodingUTF8)

/** The JVM's `Charsets` (kotlin.text) and `StandardCharsets` (java.nio.charset) at once. */
object Charsets {
    val UTF_8: Charset = Charset.forName("UTF-8")
    val ISO_8859_1: Charset = Charset.forName("ISO-8859-1")
    val US_ASCII: Charset = Charset.forName("US-ASCII")
    val UTF_16: Charset by lazy { Charset.forName("UTF-16") }
    val UTF_16LE: Charset by lazy { Charset.forName("UTF-16LE") }
    val UTF_16BE: Charset by lazy { Charset.forName("UTF-16BE") }
}

typealias StandardCharsets = Charsets

class CodingErrorAction private constructor(private val label: String) {
    override fun toString(): String = label

    companion object {
        val IGNORE = CodingErrorAction("IGNORE")
        val REPLACE = CodingErrorAction("REPLACE")
        val REPORT = CodingErrorAction("REPORT")
    }
}

/** Strict when either action is REPORT, which is how core asks "is this really that charset". */
class CharsetDecoder internal constructor(private val charset: Charset) {
    private var malformed = CodingErrorAction.REPORT
    private var unmappable = CodingErrorAction.REPORT

    fun charset(): Charset = charset
    fun onMalformedInput(action: CodingErrorAction): CharsetDecoder = apply { malformed = action }
    fun onUnmappableCharacter(action: CodingErrorAction): CharsetDecoder = apply { unmappable = action }

    fun decode(buffer: ByteBuffer): CharBuffer {
        val bytes = ByteArray(buffer.remaining())
        buffer.get(bytes)
        val strict = malformed == CodingErrorAction.REPORT || unmappable == CodingErrorAction.REPORT
        val text = charset.decodeOrNull(bytes, strict) ?: if (strict) throw MalformedInputException(0) else charset.decode(bytes)
        return CharBuffer.wrap(text)
    }
}

open class CharacterCodingException : java.io.IOException(null as String?)

class MalformedInputException(private val inputLength: Int) : CharacterCodingException() {
    fun getInputLength(): Int = inputLength
}

class UnsupportedCharsetException(val charsetName: String) : IllegalArgumentException(charsetName)

internal fun ByteArray.toNSData(): NSData =
    if (isEmpty()) NSData() else usePinned { NSData.create(bytes = it.addressOf(0), length = size.convert()) }

internal fun NSData.toByteArray(): ByteArray {
    val count = length.toInt()
    if (count == 0) return ByteArray(0)
    return ByteArray(count).also { out -> out.usePinned { memcpy(it.addressOf(0), bytes, length) } }
}
