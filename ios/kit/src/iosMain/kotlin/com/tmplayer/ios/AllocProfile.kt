@file:OptIn(
    kotlinx.cinterop.ExperimentalForeignApi::class,
    kotlin.native.runtime.NativeRuntimeApi::class,
    kotlin.native.concurrent.ObsoleteWorkersApi::class,
    ExperimentalStdlibApi::class,
)

package com.tmplayer.ios

import com.tmplayer.ios.allocs.tm_allocs_allocated
import com.tmplayer.ios.allocs.tm_allocs_dropped
import com.tmplayer.ios.allocs.tm_allocs_history_read
import com.tmplayer.ios.allocs.tm_allocs_history_size
import com.tmplayer.ios.allocs.tm_allocs_read
import com.tmplayer.ios.allocs.tm_allocs_slots
import com.tmplayer.ios.allocs.tm_allocs_start
import com.tmplayer.platform.Logger
import kotlinx.cinterop.ULongVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.toCPointer
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import platform.Foundation.NSUserDefaults

/**
 * A heap profile taken on the device itself, launched with `-perfAllocs <seconds>`: what holds the
 * live heap, by the code that made it. From the start (before `App` is made) libmalloc's logger
 * notes one allocation every [INTERVAL] bytes allocated, with its stack, and forgets it once it is
 * freed (`allocs.def`). At that many seconds, and again at twice that, after a full collection,
 * the notes still there are logged: grouped by the first frame of code that is not Kotlin's
 * library, and by the few frames above it. The same for every note taken, freed or not, from the
 * start: what allocates the most, the churn that keeps the collector busy.
 *
 * Kotlin's own objects come from malloc, and so are in the profile, only in a build made with
 * Kotlin/Native's system allocator: `ORG_GRADLE_PROJECT_tmSystemAllocator=true` as an xcodebuild
 * setting. Without it the profile shows the C libraries' blocks (TDLib, Skia, mpv) and the
 * runtime's pages. A developer's build only: every allocation pays for the logger.
 */
internal object AllocProfile {
    private const val TAG = "AllocProfile"
    private const val INTERVAL = 128L * 1024
    private const val FRAMES = 40

    private val names = HashMap<Long, String>()

    fun startIfAsked() {
        val seconds = NSUserDefaults.standardUserDefaults.integerForKey("perfAllocs").toInt()
        if (seconds <= 0) return
        if (tm_allocs_start(INTERVAL.toULong()) == 0) {
            Logger.w(TAG, "the heap profile could not start")
            return
        }
        Logger.i(TAG, "following the heap from now, a sample every ${INTERVAL / 1024} KB allocated")
        for (at in listOf(seconds, seconds * 2)) {
            platform.darwin.dispatch_after(
                platform.darwin.dispatch_time(platform.darwin.DISPATCH_TIME_NOW, at * 1_000_000_000L),
                platform.darwin.dispatch_get_main_queue(),
            ) {
                kotlin.native.concurrent.Worker.start(name = "heap profile")
                    .execute(kotlin.native.concurrent.TransferMode.SAFE, { at }) { report(it) }
            }
        }
    }

    private fun report(at: Int) {
        kotlin.native.runtime.GC.collect()
        val heap = kotlin.native.runtime.GC.lastGCInfo?.memoryUsageAfter?.get("heap")?.totalObjectsSizeBytes ?: -1L
        val samples = ArrayList<Pair<Long, List<String>>>()
        val frames = LongArray(FRAMES)
        memScoped {
            val weight = alloc<ULongVar>()
            for (slot in 0 until tm_allocs_slots()) {
                val depth = frames.usePinned { tm_allocs_read(slot, weight.ptr, it.addressOf(0).reinterpret(), FRAMES) }
                if (depth > 0) samples += weight.value.toLong() to List(depth) { name(frames[it]) }
            }
        }
        val live = samples.sumOf { it.first }
        Logger.i(
            TAG,
            "at $at s: ${mb(live)} MB live in ${samples.size} samples, Kotlin's heap ${mb(heap)} MB, " +
                "${mb(tm_allocs_allocated().toLong())} MB allocated since the start, ${mb(tm_allocs_dropped().toLong())} MB not sampled",
        )
        log("live, by owner", samples.groupingBy { owner(it.second) })
        log("live, by chain", samples.groupingBy { chain(it.second) })
        // What allocates the most, freed or not: the churn that makes the collector work.
        val made = ArrayList<Pair<Long, List<String>>>()
        memScoped {
            val weight = alloc<ULongVar>()
            for (i in 0 until tm_allocs_history_size()) {
                val depth = frames.usePinned { tm_allocs_history_read(i, weight.ptr, it.addressOf(0).reinterpret(), FRAMES) }
                if (depth > 0) made += weight.value.toLong() to List(depth) { name(frames[it]) }
            }
        }
        Logger.i(TAG, "allocated: the first ${mb(made.sumOf { it.first })} MB since the start, in ${made.size} samples")
        log("allocated, by owner", made.groupingBy { owner(it.second) })
        log("allocated, by chain", made.groupingBy { chain(it.second) })
    }

    private fun log(title: String, groups: Grouping<Pair<Long, List<String>>, String>) {
        val bytes = groups.fold(0L) { sum, sample -> sum + sample.first }
        Logger.i(TAG, title)
        bytes.entries.sortedByDescending { it.value }.take(25).forEach { (what, size) ->
            Logger.i(TAG, "${mb(size)} MB  $what")
        }
    }

    /** The first frame of code that is neither the allocator, the runtime nor Kotlin's library. */
    private fun owner(symbols: List<String>): String =
        symbols.firstOrNull { it.startsWith("kfun:") && !library(it) }?.removePrefix("kfun:")
            ?: symbols.firstOrNull { it.startsWith("kfun:") }?.removePrefix("kfun:")
            ?: symbols.firstOrNull { !runtime(it) }
            ?: "?"

    private fun chain(symbols: List<String>): String {
        val own = symbols.filter { it.startsWith("kfun:") && !library(it) }.take(5).map { it.removePrefix("kfun:").take(90) }
        return if (own.isEmpty()) owner(symbols) else own.joinToString(" < ")
    }

    private fun library(symbol: String) = symbol.startsWith("kfun:kotlin.") || symbol.startsWith("kfun:kotlinx.")

    private fun runtime(symbol: String) =
        symbol == "?" || symbol.startsWith("libsystem") || symbol.startsWith("libdyld") || symbol.startsWith("libc++") ||
            "tm_allocs" in symbol ||
            "_ZN6kotlin" in symbol || "Kotlin_" in symbol || "Alloc" in symbol

    private fun name(address: Long): String = names.getOrPut(address) {
        memScoped {
            val info = alloc<platform.posix.Dl_info>()
            if (platform.posix.dladdr(address.toCPointer<kotlinx.cinterop.ByteVar>(), info.ptr) == 0) {
                "?"
            } else {
                val image = info.dli_fname?.toKString()?.substringAfterLast('/') ?: "?"
                val symbol = info.dli_sname?.toKString().orEmpty()
                if (symbol.startsWith("kfun:")) symbol.take(120) else "$image:${symbol.take(70)}"
            }
        }
    }

    private fun mb(bytes: Long): String = "${bytes / 100_000 / 10.0}"
}
