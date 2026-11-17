// Generated from core/src/iosStandIns/JvmDefaults.kt.template, the same for each of core's packages.
// Do not edit here: edit the template and run core/src/iosStandIns/generate-defaults.sh.

package com.tmplayer.ui.icons

// What the JVM imports by default and core's JVM half uses without an import, on iOS
// (docs/IOS.md, "Chosen instead, for now").

import java.nio.charset.Charset

public typealias Volatile = kotlin.concurrent.Volatile
public typealias Synchronized = java.lang.JvmOnlySynchronized
public typealias System = java.lang.System
public typealias Thread = java.lang.Thread
public typealias Runnable = java.lang.Runnable
public typealias Integer = java.lang.Integer
public typealias Character = java.lang.Character
public typealias Math = java.lang.Math
public typealias Charsets = java.nio.charset.Charsets

public inline fun <R> synchronized(lock: Any, block: () -> R): R = java.lang.Monitors.synchronized(lock, block)

public fun String.lowercase(locale: java.util.Locale): String = java.lang.JvmText.lowercase(this, locale)

public fun String.uppercase(locale: java.util.Locale): String = java.lang.JvmText.uppercase(this, locale)

public fun String.toByteArray(charset: Charset = java.nio.charset.Charsets.UTF_8): ByteArray = charset.encode(this)

public fun ByteArray.toString(charset: Charset): String = charset.decode(this)

public fun String(bytes: ByteArray, charset: Charset): String = charset.decode(bytes)

public fun String(bytes: ByteArray, offset: Int, length: Int, charset: Charset): String =
    charset.decode(bytes.copyOfRange(offset, offset + length))

public fun String.format(vararg args: Any?): String = java.lang.JvmText.format(null, this, args)

public fun String.format(locale: java.util.Locale?, vararg args: Any?): String = java.lang.JvmText.format(locale, this, args)

public fun String.Companion.format(format: String, vararg args: Any?): String = java.lang.JvmText.format(null, format, args)

public fun String.Companion.format(locale: java.util.Locale?, format: String, vararg args: Any?): String =
    java.lang.JvmText.format(locale, format, args)

public fun thread(
    start: Boolean = true,
    isDaemon: Boolean = false,
    name: String? = null,
    block: () -> Unit,
): java.lang.Thread = java.lang.Thread(java.lang.Runnable(block), name ?: "Thread").also {
    it.isDaemon = isDaemon
    if (start) it.start()
}

public typealias NoClassDefFoundError = java.lang.NoClassDefFoundError
public typealias StackOverflowError = java.lang.StackOverflowError
public typealias InterruptedException = java.lang.InterruptedException
public typealias SecurityException = java.lang.SecurityException
public typealias ProcessBuilder = java.lang.ProcessBuilder
public typealias Runtime = java.lang.Runtime

/** `X::class.java` on the JVM; on iOS the KClass itself serves where core and :app pass a class. */
public val <T : Any> kotlin.reflect.KClass<T>.java: kotlin.reflect.KClass<T> get() = this

/**
 * On Kotlin/Native Dispatchers.IO is an extension that needs an import the JVM does not. This one
 * makes threads as the JVM's IO does and lets idle ones go
 * (com.tmplayer.platform.IosIo).
 */
public val kotlinx.coroutines.Dispatchers.IO: kotlinx.coroutines.CoroutineDispatcher
    get() = com.tmplayer.platform.IosIo

public fun <K, V> Map<out K, V>.getOrDefault(key: K, defaultValue: V): V = this[key] ?: defaultValue

public fun String.codePointAt(index: Int): Int {
    val high = this[index]
    if (high.isHighSurrogate() && index + 1 < length && this[index + 1].isLowSurrogate()) {
        return java.lang.Character.toCodePoint(high, this[index + 1])
    }
    return high.code
}

/** The JVM's `javaClass`, for the class names core writes in logs and reports. */
public val <T : Any> T.javaClass: kotlin.reflect.KClass<out T> get() = this::class

public fun <K : Comparable<K>, V> sortedMapOf(vararg pairs: Pair<K, V>): java.util.TreeMap<K, V> =
    java.util.TreeMap<K, V>().apply { pairs.forEach { (k, v) -> put(k, v) } }

public fun <K : Comparable<K>, V> Map<out K, V>.toSortedMap(): java.util.TreeMap<K, V> =
    java.util.TreeMap<K, V>().also { sorted -> forEach { (k, v) -> sorted[k] = v } }

/** java.util.Map.forEach(BiConsumer), which Kotlin on the JVM reaches with a two parameter lambda. */
public inline fun <K, V> Map<out K, V>.forEach(action: (K, V) -> Unit) {
    for ((key, value) in this) action(key, value)
}

public fun <K, V> MutableMap<K, V>.putIfAbsent(key: K, value: V): V? = this[key] ?: run {
    this[key] = value
    null
}

public fun Char.titlecase(locale: java.util.Locale): String = java.lang.JvmText.uppercase(toString(), locale)

public fun String(chars: CharArray): String = chars.concatToString()
