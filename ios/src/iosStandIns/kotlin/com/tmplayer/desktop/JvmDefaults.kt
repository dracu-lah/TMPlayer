// Generated from core/src/iosStandIns/JvmDefaults.kt.template, the same for each of core's packages.
// Do not edit here: edit the template and run core/src/iosStandIns/generate-defaults.sh.

package com.tmplayer.desktop

// What the JVM imports by default and core's JVM half uses without an import, on iOS
// (docs/IOS.md, "Chosen instead, for now").

import java.nio.charset.Charset

internal typealias Volatile = kotlin.concurrent.Volatile
internal typealias Synchronized = java.lang.JvmOnlySynchronized
internal typealias System = java.lang.System
internal typealias Thread = java.lang.Thread
internal typealias Runnable = java.lang.Runnable
internal typealias Integer = java.lang.Integer
internal typealias Character = java.lang.Character
internal typealias Math = java.lang.Math
internal typealias Charsets = java.nio.charset.Charsets

internal inline fun <R> synchronized(lock: Any, block: () -> R): R = java.lang.Monitors.synchronized(lock, block)

internal fun String.lowercase(locale: java.util.Locale): String = java.lang.JvmText.lowercase(this, locale)

internal fun String.uppercase(locale: java.util.Locale): String = java.lang.JvmText.uppercase(this, locale)

internal fun String.toByteArray(charset: Charset = java.nio.charset.Charsets.UTF_8): ByteArray = charset.encode(this)

internal fun ByteArray.toString(charset: Charset): String = charset.decode(this)

internal fun String(bytes: ByteArray, charset: Charset): String = charset.decode(bytes)

internal fun String(bytes: ByteArray, offset: Int, length: Int, charset: Charset): String =
    charset.decode(bytes.copyOfRange(offset, offset + length))

internal fun String.format(vararg args: Any?): String = java.lang.JvmText.format(null, this, args)

internal fun String.format(locale: java.util.Locale?, vararg args: Any?): String = java.lang.JvmText.format(locale, this, args)

internal fun String.Companion.format(format: String, vararg args: Any?): String = java.lang.JvmText.format(null, format, args)

internal fun String.Companion.format(locale: java.util.Locale?, format: String, vararg args: Any?): String =
    java.lang.JvmText.format(locale, format, args)

internal fun thread(
    start: Boolean = true,
    isDaemon: Boolean = false,
    name: String? = null,
    block: () -> Unit,
): java.lang.Thread = java.lang.Thread(java.lang.Runnable(block), name ?: "Thread").also {
    it.isDaemon = isDaemon
    if (start) it.start()
}

internal typealias NoClassDefFoundError = java.lang.NoClassDefFoundError
internal typealias StackOverflowError = java.lang.StackOverflowError
internal typealias InterruptedException = java.lang.InterruptedException
internal typealias SecurityException = java.lang.SecurityException
internal typealias ProcessBuilder = java.lang.ProcessBuilder
internal typealias Runtime = java.lang.Runtime

/** `X::class.java` on the JVM; on iOS the KClass itself serves where core and :app pass a class. */
internal val <T : Any> kotlin.reflect.KClass<T>.java: kotlin.reflect.KClass<T> get() = this

/**
 * On Kotlin/Native Dispatchers.IO is an extension that needs an import the JVM does not. This one
 * makes threads as the JVM's IO does and lets idle ones go
 * (com.tmplayer.platform.IosIo).
 */
internal val kotlinx.coroutines.Dispatchers.IO: kotlinx.coroutines.CoroutineDispatcher
    get() = com.tmplayer.platform.IosIo

internal fun <K, V> Map<out K, V>.getOrDefault(key: K, defaultValue: V): V = this[key] ?: defaultValue

internal fun String.codePointAt(index: Int): Int {
    val high = this[index]
    if (high.isHighSurrogate() && index + 1 < length && this[index + 1].isLowSurrogate()) {
        return java.lang.Character.toCodePoint(high, this[index + 1])
    }
    return high.code
}

/** The JVM's `javaClass`, for the class names core writes in logs and reports. */
internal val <T : Any> T.javaClass: kotlin.reflect.KClass<out T> get() = this::class

internal fun <K : Comparable<K>, V> sortedMapOf(vararg pairs: Pair<K, V>): java.util.TreeMap<K, V> =
    java.util.TreeMap<K, V>().apply { pairs.forEach { (k, v) -> put(k, v) } }

internal fun <K : Comparable<K>, V> Map<out K, V>.toSortedMap(): java.util.TreeMap<K, V> =
    java.util.TreeMap<K, V>().also { sorted -> forEach { (k, v) -> sorted[k] = v } }

/** java.util.Map.forEach(BiConsumer), which Kotlin on the JVM reaches with a two parameter lambda. */
internal inline fun <K, V> Map<out K, V>.forEach(action: (K, V) -> Unit) {
    for ((key, value) in this) action(key, value)
}

internal fun <K, V> MutableMap<K, V>.putIfAbsent(key: K, value: V): V? = this[key] ?: run {
    this[key] = value
    null
}

internal fun Char.titlecase(locale: java.util.Locale): String = java.lang.JvmText.uppercase(toString(), locale)

internal fun String(chars: CharArray): String = chars.concatToString()
