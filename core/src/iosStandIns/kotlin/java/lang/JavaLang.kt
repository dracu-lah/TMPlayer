@file:OptIn(kotlin.experimental.ExperimentalNativeApi::class, kotlinx.cinterop.ExperimentalForeignApi::class)

package java.lang

// What core's JVM half reaches in java.lang, on iOS (docs/IOS.md, "Chosen instead, for
// now"). Inside this package `Long` is java.lang.Long, so Kotlin's own is always written kotlin.Long.

import kotlin.native.identityHashCode
import kotlin.native.ref.WeakReference
import kotlin.time.TimeSource
import platform.Foundation.NSDate
import platform.Foundation.NSLock
import platform.Foundation.NSRecursiveLock
import platform.Foundation.NSThread
import platform.Foundation.timeIntervalSince1970
import platform.darwin.DISPATCH_TIME_FOREVER
import platform.darwin.dispatch_semaphore_create
import platform.darwin.dispatch_semaphore_signal
import platform.darwin.dispatch_semaphore_wait
import platform.posix.usleep

object System {
    private val start = TimeSource.Monotonic.markNow()

    fun currentTimeMillis(): kotlin.Long = (NSDate().timeIntervalSince1970 * 1000.0).toLong()

    fun nanoTime(): kotlin.Long = start.elapsedNow().inWholeNanoseconds

    fun arraycopy(src: Any, srcPos: Int, dest: Any, destPos: Int, length: Int) {
        when (src) {
            is ByteArray -> src.copyInto(dest as ByteArray, destPos, srcPos, srcPos + length)
            is CharArray -> src.copyInto(dest as CharArray, destPos, srcPos, srcPos + length)
            is IntArray -> src.copyInto(dest as IntArray, destPos, srcPos, srcPos + length)
            is LongArray -> src.copyInto(dest as LongArray, destPos, srcPos, srcPos + length)
            is ShortArray -> src.copyInto(dest as ShortArray, destPos, srcPos, srcPos + length)
            is Array<*> -> {
                @Suppress("UNCHECKED_CAST")
                (src as Array<Any?>).copyInto(dest as Array<Any?>, destPos, srcPos, srcPos + length)
            }
            else -> throw IllegalArgumentException("arraycopy: not an array")
        }
    }

    private val properties = HashMap<String, String>()

    fun getProperty(key: String): String? = Monitors.synchronized(properties) { properties[key] } ?: defaultProperty(key)

    fun getProperty(key: String, fallback: String): String = getProperty(key) ?: fallback

    fun setProperty(key: String, value: String): String? = Monitors.synchronized(properties) { properties.put(key, value) }

    private fun defaultProperty(key: String): String? = when (key) {
        "user.home" -> platform.Foundation.NSHomeDirectory()
        "java.io.tmpdir" -> platform.Foundation.NSTemporaryDirectory()
        "line.separator" -> "\n"
        "file.separator" -> "/"
        "os.name" -> "iOS"
        else -> null
    }

    fun lineSeparator(): String = "\n"

    fun identityHashCode(x: Any?): Int = x?.identityHashCode() ?: 0
}

open class LinkageError(message: String? = null) : Error(message)

open class VirtualMachineError(message: String? = null) : Error(message)

/**
 * Named so code written for the JVM compiles; Kotlin/Native never throws it (a stack that runs
 * out ends the process), so a reader of outside data limits its own depth instead (org.json's
 * JSONTokener here).
 */
open class StackOverflowError(message: String? = null) : VirtualMachineError(message)

open class NoClassDefFoundError(message: String? = null) : LinkageError(message)

open class InterruptedException(message: String? = null) : Exception(message)

open class SecurityException(message: String? = null) : RuntimeException(message)

open class UnsupportedOperationException(message: String? = null) : RuntimeException(message)

/** No app starts another process on iOS; start() fails as a missing program would. */
class ProcessBuilder(vararg command: String) {
    private val words = command.toList()
    constructor(command: List<String>) : this(*command.toTypedArray())
    fun command(vararg command: String): ProcessBuilder = ProcessBuilder(*command)
    fun redirectErrorStream(redirect: Boolean): ProcessBuilder = this
    fun start(): Process = throw java.io.IOException("Cannot run program \"${words.firstOrNull().orEmpty()}\" on iOS")
}

abstract class Process {
    abstract val inputStream: java.io.InputStream
    abstract fun waitFor(): Int
    abstract fun waitFor(timeout: kotlin.Long, unit: java.util.concurrent.TimeUnit): Boolean
    abstract fun exitValue(): Int
    abstract fun destroyForcibly(): Process
    abstract fun destroy()
}

fun interface Runnable {
    fun run()
}

/** A thread over NSThread, for the few places core's JVM half starts or sleeps one itself. */
open class Thread(private val target: Runnable? = null, var name: String = "Thread") {
    constructor(name: String) : this(null, name)
    constructor(target: Runnable) : this(target as Runnable?, "Thread")
    constructor(target: Runnable, name: String) : this(target as Runnable?, name)

    var isDaemon: Boolean = false

    /** Kept for the callers that set it; NSThread's own priority is left to the system. */
    var priority: Int = NORM_PRIORITY
    private val done = dispatch_semaphore_create(0)

    @kotlin.concurrent.Volatile
    var isAlive: Boolean = false
        private set

    open fun run() {
        target?.run()
    }

    fun start() {
        isAlive = true
        val thread = NSThread {
            try {
                run()
            } finally {
                isAlive = false
                dispatch_semaphore_signal(done)
            }
        }
        thread.name = name
        thread.start()
    }

    fun join() {
        if (!isAlive) return
        dispatch_semaphore_wait(done, DISPATCH_TIME_FOREVER)
        dispatch_semaphore_signal(done)
    }

    /** Kotlin/Native has no interruption; a waiting thread is woken by its own condition instead. */
    fun interrupt() = Unit

    val isInterrupted: Boolean get() = false

    /** What happens to an exception nothing caught, Java's. */
    fun interface UncaughtExceptionHandler {
        fun uncaughtException(thread: Thread, error: Throwable)
    }

    companion object {
        const val MIN_PRIORITY: Int = 1
        const val NORM_PRIORITY: Int = 5
        const val MAX_PRIORITY: Int = 10

        private var defaultHandler: UncaughtExceptionHandler? = null

        fun getDefaultUncaughtExceptionHandler(): UncaughtExceptionHandler? = defaultHandler

        /**
         * Kotlin/Native runs one hook just before an exception nothing caught ends the process,
         * which is what Java's handler is: told, and the process ends after it, as it does on the JVM.
         */
        fun setDefaultUncaughtExceptionHandler(handler: UncaughtExceptionHandler?) {
            defaultHandler = handler
            if (handler == null) return
            kotlin.native.setUnhandledExceptionHook { error -> runCatching { handler.uncaughtException(currentThread(), error) } }
        }

        fun sleep(millis: kotlin.Long) {
            var left = millis.coerceAtLeast(0)
            while (left > 0) {
                val step = minOf(left, 1_000L)
                usleep((step * 1_000).toUInt())
                left -= step
            }
        }

        fun currentThread(): Thread = Thread(null, NSThread.currentThread.name ?: "Thread")

        fun interrupted(): Boolean = false
    }
}

object Integer {
    const val MAX_VALUE: Int = Int.MAX_VALUE
    const val MIN_VALUE: Int = Int.MIN_VALUE

    fun numberOfLeadingZeros(value: Int): Int = value.countLeadingZeroBits()
    fun numberOfTrailingZeros(value: Int): Int = value.countTrailingZeroBits()
    fun bitCount(value: Int): Int = value.countOneBits()
    fun toHexString(value: Int): String = value.toUInt().toString(16)
    fun toBinaryString(value: Int): String = value.toUInt().toString(2)
    fun parseInt(text: String): Int = text.toInt()
    fun parseInt(text: String, radix: Int): Int = text.toInt(radix)
    fun valueOf(value: Int): Int = value
    fun compare(a: Int, b: Int): Int = a.compareTo(b)

    fun reverse(value: Int): Int {
        var source = value
        var result = 0
        repeat(32) {
            result = (result shl 1) or (source and 1)
            source = source ushr 1
        }
        return result
    }
}

object Long {
    const val MAX_VALUE: kotlin.Long = kotlin.Long.MAX_VALUE
    const val MIN_VALUE: kotlin.Long = kotlin.Long.MIN_VALUE

    fun toHexString(value: kotlin.Long): String = value.toULong().toString(16)
    fun numberOfLeadingZeros(value: kotlin.Long): Int = value.countLeadingZeroBits()
    fun parseLong(text: String): kotlin.Long = text.toLong()
    fun parseLong(text: String, radix: Int): kotlin.Long = text.toLong(radix)
    fun compare(a: kotlin.Long, b: kotlin.Long): Int = a.compareTo(b)
}

object Character {
    const val MIN_SUPPLEMENTARY_CODE_POINT: Int = 0x10000

    fun isSurrogate(c: Char): Boolean = c.isSurrogate()
    fun isHighSurrogate(c: Char): Boolean = c.isHighSurrogate()
    fun isLowSurrogate(c: Char): Boolean = c.isLowSurrogate()
    fun charCount(codePoint: Int): Int = if (codePoint >= MIN_SUPPLEMENTARY_CODE_POINT) 2 else 1
    fun isLetter(c: Char): Boolean = c.isLetter()
    fun isDigit(c: Char): Boolean = c.isDigit()
    fun isLetterOrDigit(c: Char): Boolean = c.isLetterOrDigit()
    fun isWhitespace(c: Char): Boolean = c.isWhitespace()
    fun toCodePoint(high: Char, low: Char): Int = ((high.code - 0xD800) shl 10) + (low.code - 0xDC00) + 0x10000

    fun toChars(codePoint: Int): CharArray =
        if (codePoint < MIN_SUPPLEMENTARY_CODE_POINT) charArrayOf(codePoint.toChar())
        else (codePoint - MIN_SUPPLEMENTARY_CODE_POINT).let { charArrayOf(((it shr 10) + 0xD800).toChar(), ((it and 0x3FF) + 0xDC00).toChar()) }
}

object Math {
    fun floorDiv(a: kotlin.Long, b: kotlin.Long): kotlin.Long = a.floorDiv(b)
    fun floorDiv(a: Int, b: Int): Int = a.floorDiv(b)
    fun floorMod(a: kotlin.Long, b: kotlin.Long): kotlin.Long = a.mod(b)
    fun floorMod(a: Int, b: Int): Int = a.mod(b)
    fun abs(a: Int): Int = kotlin.math.abs(a)
    fun abs(a: kotlin.Long): kotlin.Long = kotlin.math.abs(a)
    fun abs(a: Double): Double = kotlin.math.abs(a)
    fun max(a: Int, b: Int): Int = maxOf(a, b)
    fun max(a: kotlin.Long, b: kotlin.Long): kotlin.Long = maxOf(a, b)
    fun min(a: Int, b: Int): Int = minOf(a, b)
    fun min(a: kotlin.Long, b: kotlin.Long): kotlin.Long = minOf(a, b)
}

/**
 * The JVM's monitors, for `synchronized(lock) { }`: one reentrant lock per object, held weakly so
 * a lock does not keep its object alive. Per object, not one for all, so two unrelated locks never
 * wait on each other as they would not on the JVM.
 */
object Monitors {
    private val guard = NSLock()
    private val table = HashMap<Int, MutableList<Pair<WeakReference<Any>, NSRecursiveLock>>>()
    private var made = 0

    fun lockFor(target: Any): NSRecursiveLock {
        guard.lock()
        try {
            val bucket = table.getOrPut(target.identityHashCode()) { ArrayList(1) }
            bucket.firstOrNull { it.first.value === target }?.let { return it.second }
            val lock = NSRecursiveLock()
            bucket += WeakReference(target) to lock
            if (++made % 1024 == 0) sweep()
            return lock
        } finally {
            guard.unlock()
        }
    }

    private fun sweep() {
        val emptied = ArrayList<Int>()
        for ((hash, bucket) in table) {
            bucket.removeAll { it.first.value == null }
            if (bucket.isEmpty()) emptied += hash
        }
        emptied.forEach(table::remove)
    }

    inline fun <R> synchronized(target: Any, block: () -> R): R {
        val lock = lockFor(target)
        lock.lock()
        try {
            return block()
        } finally {
            lock.unlock()
        }
    }
}

/**
 * Stands in for the JVM's `@Synchronized`, which takes the object's monitor on the JVM and would do
 * nothing on Kotlin/Native. iOS never compiles a file that uses it as it is: :core and :ui build
 * copies where each such function holds `synchronized(this)` (gradle/ios-real-locks.gradle.kts).
 */
@Target(AnnotationTarget.FUNCTION, AnnotationTarget.PROPERTY_GETTER, AnnotationTarget.PROPERTY_SETTER)
@Retention(AnnotationRetention.SOURCE)
annotation class JvmOnlySynchronized

/** The app's runtime, Java's: the processors and memory it has, from iOS. */
class Runtime private constructor() {
    fun availableProcessors(): kotlin.Int = platform.Foundation.NSProcessInfo.processInfo.activeProcessorCount.toInt()

    /** The most the app may use: iOS sets no heap limit of its own, so the device's memory. */
    fun maxMemory(): kotlin.Long = platform.Foundation.NSProcessInfo.processInfo.physicalMemory.toLong()

    fun totalMemory(): kotlin.Long = maxMemory()

    fun freeMemory(): kotlin.Long = 0L

    fun gc() = Unit

    companion object {
        private val current = Runtime()

        fun getRuntime(): Runtime = current
    }
}
