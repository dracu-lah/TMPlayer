package android.os

import java.io.File
import platform.Foundation.preferredLanguages
import platform.Foundation.NSProcessInfo
import platform.UIKit.UIDevice

/**
 * android.os.Build as an iPhone or iPad answers it. SDK_INT is the newest Android level :app knows,
 * so its version checks take the modern path, which is the one closest to what iOS offers; no
 * Android-only feature is reached through it, since those classes are left out on iOS.
 */
object Build {
    val MANUFACTURER: String = "Apple"
    val BRAND: String = "Apple"
    val MODEL: String get() = UIDevice.currentDevice.model
    val DEVICE: String get() = UIDevice.currentDevice.model
    val PRODUCT: String get() = UIDevice.currentDevice.model
    val HARDWARE: String = "apple"
    val FINGERPRINT: String = "apple/ios"
    val DISPLAY: String get() = UIDevice.currentDevice.systemVersion
    val SUPPORTED_ABIS: Array<String> = arrayOf("arm64-v8a")

    object VERSION {
        const val SDK_INT: Int = 36
        val RELEASE: String get() = UIDevice.currentDevice.systemVersion
        val INCREMENTAL: String get() = UIDevice.currentDevice.systemVersion
    }

    object VERSION_CODES {
        const val LOLLIPOP = 21
        const val M = 23
        const val N = 24
        const val N_MR1 = 25
        const val O = 26
        const val O_MR1 = 27
        const val P = 28
        const val Q = 29
        const val R = 30
        const val S = 31
        const val S_V2 = 32
        const val TIRAMISU = 33
        const val UPSIDE_DOWN_CAKE = 34
        const val VANILLA_ICE_CREAM = 35
        const val BAKLAVA = 36
    }
}

object SystemClock {
    private val start = kotlin.time.TimeSource.Monotonic.markNow()
    fun uptimeMillis(): Long = start.elapsedNow().inWholeMilliseconds
    fun elapsedRealtime(): Long = (NSProcessInfo.processInfo.systemUptime * 1000).toLong()
    fun elapsedRealtimeNanos(): Long = (NSProcessInfo.processInfo.systemUptime * 1_000_000_000).toLong()
    fun sleep(ms: Long) = java.lang.Thread.sleep(ms)
}

class StatFs(private val path: String) {
    private val file = File(path)
    val availableBytes: Long get() = file.usableSpace
    val freeBytes: Long get() = file.freeSpace
    val totalBytes: Long get() = file.totalSpace
    val blockSizeLong: Long get() = 4096
    val availableBlocksLong: Long get() = availableBytes / 4096
    val blockCountLong: Long get() = totalBytes / 4096
}

object Environment {
    const val DIRECTORY_DOWNLOADS = "Download"
    const val DIRECTORY_MOVIES = "Movies"
    const val DIRECTORY_DCIM = "DCIM"
    const val DIRECTORY_PICTURES = "Pictures"
    const val MEDIA_MOUNTED = "mounted"
    fun getExternalStorageDirectory(): File = android.content.ContextOnIos.documents()
    fun getExternalStoragePublicDirectory(type: String): File = File(getExternalStorageDirectory(), type)
    fun getExternalStorageState(): String = MEDIA_MOUNTED
    fun getExternalStorageState(path: File): String = MEDIA_MOUNTED
    fun isExternalStorageRemovable(path: File): Boolean = false
    fun isExternalStorageEmulated(path: File): Boolean = true
    fun getDataDirectory(): File = File(platform.Foundation.NSHomeDirectory())
}

object Process {
    fun myPid(): Int = NSProcessInfo.processInfo.processIdentifier

    /** Every iPhone and iPad app is 64-bit. */
    fun is64Bit(): Boolean = true
    fun myUid(): Int = 0
    fun killProcess(pid: Int) = Unit
    fun sendSignal(pid: Int, signal: Int) = Unit
}

class Bundle {
    private val values = LinkedHashMap<String, Any?>()
    fun putString(key: String, value: String?) { values[key] = value }
    fun putInt(key: String, value: Int) { values[key] = value }
    fun putLong(key: String, value: Long) { values[key] = value }
    fun putBoolean(key: String, value: Boolean) { values[key] = value }
    fun putStringArray(key: String, value: Array<String>?) { values[key] = value }
    fun getString(key: String): String? = values[key] as? String
    fun getString(key: String, fallback: String): String = values[key] as? String ?: fallback
    fun getInt(key: String, fallback: Int = 0): Int = values[key] as? Int ?: fallback
    fun getLong(key: String, fallback: Long = 0): Long = values[key] as? Long ?: fallback
    fun getBoolean(key: String, fallback: Boolean = false): Boolean = values[key] as? Boolean ?: fallback
    fun containsKey(key: String): Boolean = key in values
    fun keySet(): Set<String> = values.keys
}

/** The main thread's looper: work posted to a Handler on it runs on the main queue. */
class Looper private constructor() {
    companion object {
        private val main = Looper()
        fun getMainLooper(): Looper = main
        fun myLooper(): Looper? = if (platform.Foundation.NSThread.isMainThread) main else null
    }
}

/**
 * Work for the main thread, now or later, Android's: removeCallbacks takes back what this Handler
 * posted and has not run yet, as Android's removes this Handler's messages from the main queue.
 */
class Handler(private val looper: Looper = Looper.getMainLooper()) {
    private class Posted(val action: Runnable, val owner: Handler, val token: Any?) {
        var cancelled = false
    }

    fun post(action: Runnable): Boolean = postDelayed(action, 0)

    fun postDelayed(action: Runnable, delayMillis: Long): Boolean = enqueue(action, null, delayMillis)

    fun postDelayed(action: Runnable, token: Any?, delayMillis: Long): Boolean = enqueue(action, token, delayMillis)

    fun postAtFrontOfQueue(action: Runnable): Boolean = post(action)

    private fun enqueue(action: Runnable, token: Any?, delayMillis: Long): Boolean {
        val posted = Posted(action, this, token)
        locked { pending += posted }
        platform.darwin.dispatch_after(
            platform.darwin.dispatch_time(platform.darwin.DISPATCH_TIME_NOW, delayMillis.coerceAtLeast(0) * 1_000_000),
            platform.darwin.dispatch_get_main_queue(),
        ) {
            locked { pending.remove(posted) }
            if (!posted.cancelled) action.run()
        }
        return true
    }

    fun removeCallbacks(action: Runnable) = takeBack { it.action === action }

    fun removeCallbacks(action: Runnable, token: Any?) = takeBack { it.action === action && (token == null || it.token === token) }

    /** Everything this Handler posted with [token], or everything it posted for null. */
    fun removeCallbacksAndMessages(token: Any?) = takeBack { token == null || it.token === token }

    fun hasCallbacks(action: Runnable): Boolean = locked { pending.any { it.owner === this && it.action === action && !it.cancelled } }

    private fun takeBack(which: (Posted) -> Boolean) {
        locked {
            val gone = pending.filter { it.owner === this && which(it) }
            gone.forEach { it.cancelled = true }
            pending.removeAll(gone.toSet())
        }
    }

    private companion object {
        val pending = ArrayList<Posted>()
        val lock = platform.Foundation.NSRecursiveLock()

        inline fun <R> locked(block: () -> R): R {
            lock.lock()
            try {
                return block()
            } finally {
                lock.unlock()
            }
        }
    }
}

typealias Runnable = java.lang.Runnable

interface IBinder

interface Parcelable

class CancellationSignal {
    var isCanceled: Boolean = false
        private set
    fun cancel() {
        isCanceled = true
    }
}

class LocaleList(private val locales: List<java.util.Locale>) {
    fun get(index: Int): java.util.Locale? = locales.getOrNull(index)
    fun size(): Int = locales.size
    val isEmpty: Boolean get() = locales.isEmpty()
    fun toLanguageTags(): String = locales.joinToString(",") { it.toLanguageTag() }

    companion object {
        fun getDefault(): LocaleList = LocaleList(
            platform.Foundation.NSLocale.preferredLanguages.filterIsInstance<String>().map { java.util.Locale.forLanguageTag(it) },
        )
        fun getEmptyLocaleList(): LocaleList = LocaleList(emptyList())
        fun forLanguageTags(tags: String?): LocaleList =
            LocaleList(tags.orEmpty().split(',').filter { it.isNotBlank() }.map { java.util.Locale.forLanguageTag(it.trim()) })
    }
}

/** A file opened through ContentResolver: on iOS only file:// addresses open. */
class ParcelFileDescriptor internal constructor(private val file: File, mode: String) : java.io.Closeable {
    private val stream = if (mode.contains('w')) java.io.FileOutputStream(file, append = mode.contains('a')) else null
    val fileDescriptor: java.io.FileDescriptor get() = stream?.fd ?: java.io.FileDescriptor()
    val fd: Int get() = -1
    val statSize: Long get() = file.length()
    override fun close() {
        stream?.close()
    }
}
