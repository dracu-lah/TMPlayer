@file:OptIn(kotlinx.cinterop.BetaInteropApi::class, kotlin.native.runtime.NativeRuntimeApi::class, ExperimentalStdlibApi::class)

package com.tmplayer.platform

import platform.Foundation.NSLog
import platform.Foundation.NSString
import platform.Foundation.NSUserDefaults
import platform.Foundation.create

/**
 * To the unified log, where Xcode's console and the Console app read it, and to the app's own
 * kept log ([KeptLog]), which "Save logs" reads: the unified log keeps a release build's lines as
 * "<private>" and gives an app none of them back.
 */
internal actual fun printError(line: String, error: Throwable?) {
    val text = if (error == null) line else "$line\n${error.stackTraceToString()}"
    // An NSString, not the Kotlin string: Kotlin/Native hands a String in a C variadic call over as
    // a C string, and %@ would read that as an object and crash. The text is never the format.
    NSLog("%@", NSString.create(string = text))
    KeptLog.keep(text)
    if (HeapMarks.on && marksHeap(line)) HeapMarks.mark(null)
}

/**
 * A developer's check, launched with `-perfHeapMarks YES`: after each step of the start the log
 * names (TDLib's states, Home's rows), a full collection and Kotlin's live heap, to see which step
 * fills it. Off otherwise, and never in a release build's use.
 */
object HeapMarks {
    val on: Boolean by lazy { NSUserDefaults.standardUserDefaults.boolForKey("perfHeapMarks") }

    /** After a full collection, Kotlin's live heap, with [label] (a step of the start) when given. */
    fun mark(label: String?) {
        if (!on) return
        kotlin.native.runtime.GC.collect()
        val heap = kotlin.native.runtime.GC.lastGCInfo?.memoryUsageAfter?.get("heap")?.totalObjectsSizeBytes ?: -1L
        NSLog("%@", NSString.create(string = "I/heap: live ${heap / 100_000 / 10.0} MB${label?.let { " $it" }.orEmpty()}"))
    }

    /** Marks on the main thread [delaysMs] after now: the first frames' compositions. */
    fun markLater(delaysMs: List<Long>) {
        if (!on) return
        for (delay in delaysMs) {
            platform.darwin.dispatch_after(
                platform.darwin.dispatch_time(platform.darwin.DISPATCH_TIME_NOW, delay * 1_000_000L),
                platform.darwin.dispatch_get_main_queue(),
            ) { mark("$delay ms after the activity started") }
        }
    }
}

private fun marksHeap(line: String): Boolean =
    (line.startsWith("I/tm-perf:") && "sharp in" !in line) || line.startsWith("I/Td:")
