@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package java.security

import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
import platform.CoreCrypto.CC_MD5
import platform.CoreCrypto.CC_MD5_DIGEST_LENGTH
import platform.CoreCrypto.CC_SHA1
import platform.CoreCrypto.CC_SHA1_DIGEST_LENGTH
import platform.CoreCrypto.CC_SHA256
import platform.CoreCrypto.CC_SHA256_DIGEST_LENGTH
import platform.posix.arc4random_buf

open class GeneralSecurityException(message: String?) : Exception(message)

class NoSuchAlgorithmException(message: String?) : GeneralSecurityException(message)

/**
 * java.security.MessageDigest for SHA-256, SHA-1 and MD5 through CommonCrypto. The input is kept
 * until [digest], which hashes it in one call: core hashes keys and short files.
 */
class MessageDigest private constructor(val algorithm: String) {
    private val input = java.io.ByteArrayOutputStream()

    fun update(bytes: ByteArray) = input.write(bytes, 0, bytes.size)
    fun update(bytes: ByteArray, offset: Int, length: Int) = input.write(bytes, offset, length)
    fun update(b: Byte) = input.write(b.toInt())
    fun reset() = input.reset()

    fun digest(bytes: ByteArray): ByteArray {
        update(bytes)
        return digest()
    }

    fun digest(): ByteArray {
        val data = input.toByteArray()
        input.reset()
        val out = UByteArray(length())
        out.usePinned { o ->
            data.usePinned { d ->
                val source = if (data.isEmpty()) null else d.addressOf(0)
                when (algorithm) {
                    "SHA-256" -> CC_SHA256(source, data.size.convert(), o.addressOf(0))
                    "SHA-1" -> CC_SHA1(source, data.size.convert(), o.addressOf(0))
                    else -> @Suppress("DEPRECATION") CC_MD5(source, data.size.convert(), o.addressOf(0))
                }
            }
        }
        return out.asByteArray()
    }

    private fun length(): Int = when (algorithm) {
        "SHA-256" -> CC_SHA256_DIGEST_LENGTH
        "SHA-1" -> CC_SHA1_DIGEST_LENGTH
        else -> CC_MD5_DIGEST_LENGTH
    }

    fun getDigestLength(): Int = length()

    companion object {
        fun getInstance(algorithm: String): MessageDigest = when (algorithm.uppercase()) {
            "SHA-256", "SHA256" -> MessageDigest("SHA-256")
            "SHA-1", "SHA1", "SHA" -> MessageDigest("SHA-1")
            "MD5" -> MessageDigest("MD5")
            else -> throw NoSuchAlgorithmException("$algorithm MessageDigest not available")
        }
    }
}

/** java.security.SecureRandom over arc4random, the system's CSPRNG. */
class SecureRandom {
    fun nextBytes(bytes: ByteArray) {
        if (bytes.isNotEmpty()) bytes.usePinned { arc4random_buf(it.addressOf(0), bytes.size.convert()) }
    }

    fun nextInt(): Int = ByteArray(4).also(::nextBytes).fold(0) { acc, b -> (acc shl 8) or (b.toInt() and 0xFF) }

    fun nextInt(bound: Int): Int = kotlin.random.Random(nextLong()).nextInt(bound)

    fun nextLong(): Long = ByteArray(8).also(::nextBytes).fold(0L) { acc, b -> (acc shl 8) or (b.toLong() and 0xFF) }
}
