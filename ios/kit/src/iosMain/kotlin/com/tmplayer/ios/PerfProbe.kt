package com.tmplayer.ios

import com.tmplayer.platform.Logger
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.get
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.toCPointer
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import platform.Foundation.NSProcessInfo
import platform.Foundation.NSTimer
import platform.Foundation.NSUserDefaults
import platform.darwin.KERN_SUCCESS
import platform.darwin.TASK_POWER_INFO_V2
import platform.darwin.TASK_VM_INFO
import platform.darwin.mach_msg_type_number_tVar
import platform.darwin.mach_task_self_
import platform.darwin.natural_tVar
import platform.darwin.task_info
import platform.darwin.task_power_info_v2_data_t
import platform.darwin.task_vm_info_data_t
import platform.posix.RUSAGE_SELF
import platform.posix.getrusage
import platform.posix.rusage

/**
 * A developer's measure of what the app costs on a device, read from its console
 * (`xcrun devicectl device process launch --console`), for a device Instruments cannot follow
 * the app on (an iPhone 13 on iOS 27.2.1 with Xcode 27.2 ends every recording a second after the
 * app starts). Launched with `-perfLog <seconds>`, every that many seconds it logs the app's CPU,
 * its memory footprint (the figure iOS judges an app by), its wakeups, the GPU time and energy the
 * system charges it, and how many times the Android View host drew its tree, which is none while
 * nothing on screen changes. Nothing here runs without the launch argument.
 */
@OptIn(ExperimentalForeignApi::class)
internal object PerfProbe {
    private const val TAG = "PerfProbe"

    /** The Android View host's drawings of its tree since the last sample (AndroidViewHost). */
    var drawn = 0L

    /** The views that asked the window to draw since the last sample, by class, on the main thread. */
    private val invalidators = HashMap<String, Int>()

    /** File.usableSpace asked since the last sample, and how many of its callers were logged. */
    private var spaceAsks = 0L
    private var spaceStacks = 0

    private var last: Reading? = null
    private var timer: NSTimer? = null

    @OptIn(kotlin.native.runtime.NativeRuntimeApi::class, kotlin.experimental.ExperimentalNativeApi::class)
    fun startIfAsked() {
        // `-gcInterval <seconds>`: Kotlin/Native's regular collection, every 10 s by default, for
        // measuring what it costs at rest.
        val gc = NSUserDefaults.standardUserDefaults.integerForKey("gcInterval")
        if (gc > 0) {
            kotlin.native.runtime.GC.regularGCInterval = kotlin.time.Duration.parse("${gc}s")
            Logger.i(TAG, "regular GC every $gc s")
        }
        val every = NSUserDefaults.standardUserDefaults.integerForKey("perfLog").toDouble()
        if (every <= 0.0 || timer != null) return
        // The screen kept on while measuring, as the player keeps it while it plays: with it off
        // iOS suspends the app and the measure stops.
        platform.UIKit.UIApplication.sharedApplication.idleTimerDisabled = true
        last = read()
        lastThreads = threadTimes()
        android.view.View.invalidationTrace = { view ->
            val name = view::class.simpleName ?: "View"
            invalidators[name] = (invalidators[name] ?: 0) + 1
        }
        drawn = 0
        java.io.File.spaceAsked = {
            spaceAsks++
            if (spaceStacks < 3) {
                spaceStacks++
                Logger.i(TAG, "free space asked from: " + Throwable().getStackTrace().drop(1).take(12).joinToString(" < ") { it.substringAfter("kfun:").substringBefore("(").take(90) })
            }
        }
        timer = NSTimer.scheduledTimerWithTimeInterval(every, repeats = true) { _ -> sample() }
    }

    private class Reading(
        val seconds: Double,
        val cpuSeconds: Double,
        val footprint: Long,
        val interruptWakeups: Long,
        val idleWakeups: Long,
        val gpuNs: Long,
        val energyNj: Long,
        val graphics: Long,
        val media: Long,
        val compressed: Long,
    )

    private fun sample() {
        val now = read() ?: return
        val before = last ?: return
        last = now
        val span = now.seconds - before.seconds
        if (span <= 0) return
        val draws = drawn
        drawn = 0
        fun perSecond(a: Long, b: Long) = (a - b) / span
        // One decimal, as Kotlin/Native has no String.format.
        fun Double.d() = (kotlin.math.round(this * 10) / 10).toString()
        val cpu = (now.cpuSeconds - before.cpuSeconds) / span * 100
        val gpu = (now.gpuNs - before.gpuNs) / 1e9 / span * 100
        val milliwatts = (now.energyNj - before.energyNj) / 1e6 / span
        Logger.i(
            TAG,
            "perf over ${span.d()} s: cpu ${cpu.d()}%, footprint ${(now.footprint / 1048576.0).d()} MB, " +
                "wakeups ${perSecond(now.interruptWakeups, before.interruptWakeups).d()}/s " +
                "(idle ${perSecond(now.idleWakeups, before.idleWakeups).d()}/s), gpu ${gpu.d()}%, " +
                "energy ${milliwatts.d()} mW, host draws ${(draws / span).d()}/s, free space asked ${(spaceAsks / span).d()}/s",
        )
        spaceAsks = 0
        // Which views had the window drawn, when it was drawn often: what keeps the host busy.
        if (draws / span > 5 && invalidators.isNotEmpty()) {
            Logger.i(TAG, "drawn for: " + invalidators.entries.sortedByDescending { it.value }.take(6).joinToString(", ") { "${it.key} ${it.value}" })
        }
        invalidators.clear()
        // Where the footprint is: the graphics iOS counts for the app (Metal textures and surfaces:
        // Skia's, mpv's), media, what is compressed, and Kotlin's heap after its last collection.
        @OptIn(kotlin.native.runtime.NativeRuntimeApi::class, kotlin.ExperimentalStdlibApi::class)
        val heap = kotlin.native.runtime.GC.lastGCInfo?.memoryUsageAfter?.get("heap")?.totalObjectsSizeBytes ?: -1L
        fun Long.mb() = (this / 1048576.0).d()
        Logger.i(TAG, "memory: footprint ${now.footprint.mb()} MB, graphics ${now.graphics.mb()}, media ${now.media.mb()}, compressed ${now.compressed.mb()}, kotlin heap ${heap.mb()}")
        // Which threads the CPU went to: each thread's time since the last sample, by name.
        val threads = threadTimes()
        val busiest = threads.mapNotNull { (id, entry) ->
            val (name, seconds) = entry
            val was = lastThreads[id]?.second ?: return@mapNotNull null
            Triple(name, (seconds - was) / span * 1e6, id)
        }.sortedByDescending { it.second }.take(12)
        lastThreads = threads
        // In microseconds of CPU a second, so a thread waking often for a moment shows too.
        Logger.i(TAG, "threads: " + busiest.joinToString(", ") { "${it.first.ifBlank { "#${it.third}" }} ${it.second.toLong()}us/s" } + " (${threads.size} threads)")
        // Where the busiest unnamed threads wait: the libraries and functions on their stacks.
        if (NSUserDefaults.standardUserDefaults.boolForKey("perfStacks")) {
            for ((name, micros, id) in busiest.filter { it.first.startsWith("[") }.take(6)) {
                val addresses = threadPorts[id]?.let(::returnAddresses).orEmpty()
                Logger.i(TAG, "stack of $name ${micros.toLong()}us/s: " + addresses.map(::where).distinct().joinToString(" < "))
            }
        }
        threadPorts.values.forEach { platform.darwin.mach_port_deallocate(mach_task_self_, it) }
        threadPorts = emptyMap()
    }

    private var lastThreads: Map<Long, Pair<String, Double>> = emptyMap()

    /** The ports of the threads read at the last sample, by id, kept for reading their stacks. */
    private var threadPorts: Map<Long, platform.darwin.thread_act_t> = emptyMap()

    /** Where a suspended thread is: its pc, its lr and up to ten return addresses along its frames. */
    private fun returnAddresses(thread: platform.darwin.thread_act_t): List<Long> = memScoped {
        val self = platform.posix.pthread_mach_thread_np(platform.posix.pthread_self())
        if (thread == self || platform.darwin.thread_suspend(thread) != KERN_SUCCESS) return emptyList()
        val out = ArrayList<Long>(12)
        try {
            val state = alloc<platform.darwin.arm_thread_state64_t>()
            val count = alloc<mach_msg_type_number_tVar>()
            count.value = (sizeOf<platform.darwin.arm_thread_state64_t>() / 4).toUInt()
            if (platform.darwin.thread_get_state(thread, platform.darwin.ARM_THREAD_STATE64, state.ptr.reinterpret(), count.ptr) == KERN_SUCCESS) {
                out += state.__pc.toLong()
                out += state.__lr.toLong()
                var fp = state.__fp.toLong()
                var frames = 0
                while (fp != 0L && fp % 8 == 0L && frames < 10) {
                    val frame = fp.toCPointer<kotlinx.cinterop.LongVar>() ?: break
                    val next = frame[0]
                    val ret = frame[1]
                    if (ret == 0L) break
                    out += ret
                    // Callers' frames sit higher on a stack that grows down, and not far away.
                    if (next <= fp || next - fp > 1_048_576) break
                    fp = next
                    frames++
                }
            }
        } finally {
            platform.darwin.thread_resume(thread)
        }
        out
    }

    /** The library and function an address is in, as `library:function`. */
    private fun where(address: Long): String = memScoped {
        val info = alloc<platform.posix.Dl_info>()
        if (platform.posix.dladdr(address.toCPointer<kotlinx.cinterop.ByteVar>(), info.ptr) == 0) return "?"
        val image = info.dli_fname?.toKString()?.substringAfterLast('/') ?: "?"
        val symbol = info.dli_sname?.toKString().orEmpty()
        "$image:${symbol.take(70)}"
    }

    /** Every thread of the app now: its id, its name and the CPU seconds it has used. */
    private fun threadTimes(): Map<Long, Pair<String, Double>> = memScoped {
        val list = alloc<kotlinx.cinterop.CPointerVar<platform.darwin.thread_act_tVar>>()
        val count = alloc<mach_msg_type_number_tVar>()
        if (platform.darwin.task_threads(mach_task_self_, list.ptr, count.ptr) != KERN_SUCCESS) return emptyMap()
        val threads = list.value ?: return emptyMap()
        val out = HashMap<Long, Pair<String, Double>>()
        val ports = HashMap<Long, platform.darwin.thread_act_t>()
        val n = count.value.toInt()
        for (i in 0 until n) {
            val thread = threads[i]
            val basic = alloc<platform.darwin.thread_basic_info_data_t>()
            val basicCount = alloc<mach_msg_type_number_tVar>()
            basicCount.value = (sizeOf<platform.darwin.thread_basic_info_data_t>() / sizeOf<natural_tVar>()).toUInt()
            val ident = alloc<platform.darwin.thread_identifier_info_data_t>()
            val identCount = alloc<mach_msg_type_number_tVar>()
            identCount.value = (sizeOf<platform.darwin.thread_identifier_info_data_t>() / sizeOf<natural_tVar>()).toUInt()
            if (platform.darwin.thread_info(thread, platform.darwin.THREAD_BASIC_INFO.toUInt(), basic.ptr.reinterpret(), basicCount.ptr) == KERN_SUCCESS &&
                platform.darwin.thread_info(thread, platform.darwin.THREAD_IDENTIFIER_INFO.toUInt(), ident.ptr.reinterpret(), identCount.ptr) == KERN_SUCCESS
            ) {
                val seconds = basic.user_time.seconds + basic.system_time.seconds +
                    (basic.user_time.microseconds + basic.system_time.microseconds) / 1e6
                val name = allocArray<kotlinx.cinterop.ByteVar>(64)
                val pthread = platform.posix.pthread_from_mach_thread_np(thread)
                val label = if (pthread != null && platform.posix.pthread_getname_np(pthread, name, 64u) == 0) {
                    name.toKString()
                } else {
                    ""
                }
                // A thread serving a dispatch queue, and its scheduling priority, for the unnamed ones.
                val kind = (if (ident.dispatch_qaddr != 0uL) "gcd" else "") + "p" + basic.policy + "/" +
                    (basic.run_state)
                out[ident.thread_id.toLong()] = (label.ifBlank { "[$kind]#${ident.thread_id}" }) to seconds
                ports[ident.thread_id.toLong()] = thread
            } else {
                platform.darwin.mach_port_deallocate(mach_task_self_, thread)
            }
        }
        platform.darwin.vm_deallocate(
            mach_task_self_,
            threads.rawValue.toLong().toULong(),
            (n.toLong() * sizeOf<platform.darwin.thread_act_tVar>()).toULong(),
        )
        threadPorts.values.forEach { platform.darwin.mach_port_deallocate(mach_task_self_, it) }
        threadPorts = ports
        out
    }

    /** The app's memory footprint and what iOS still allows it, in MB, for the memory warning's log line. */
    /** A thread's name (or "unnamed") and the CPU it has used so far, for the sampler's census. */
    fun threadName(thread: platform.darwin.thread_act_t): String = memScoped {
        val name = allocArray<kotlinx.cinterop.ByteVar>(64)
        val pthread = platform.posix.pthread_from_mach_thread_np(thread)
        val label = if (pthread != null && platform.posix.pthread_getname_np(pthread, name, 64u) == 0) name.toKString() else ""
        val basic = alloc<platform.darwin.thread_basic_info_data_t>()
        val basicCount = alloc<mach_msg_type_number_tVar>()
        basicCount.value = (sizeOf<platform.darwin.thread_basic_info_data_t>() / sizeOf<natural_tVar>()).toUInt()
        val cpu = if (platform.darwin.thread_info(thread, platform.darwin.THREAD_BASIC_INFO.toUInt(), basic.ptr.reinterpret(), basicCount.ptr) == KERN_SUCCESS) {
            basic.user_time.seconds + basic.system_time.seconds + (basic.user_time.microseconds + basic.system_time.microseconds) / 1e6
        } else {
            -1.0
        }
        "${label.ifBlank { "unnamed" }} (cpu ${(kotlin.math.round(cpu * 100) / 100)} s)"
    }

    fun memoryNow(): String {
        val (footprint, left) = memory() ?: return "footprint unknown"
        val mb = 1024L * 1024
        return "footprint ${footprint / mb} MB, ${left / mb} MB left before iOS ends the app"
    }

    /** The app's footprint and what iOS still allows it, in bytes, or null when iOS does not say. */
    fun memory(): Pair<Long, Long>? = memScoped {
        val vm = alloc<task_vm_info_data_t>()
        val count = alloc<mach_msg_type_number_tVar>()
        count.value = (sizeOf<task_vm_info_data_t>() / sizeOf<natural_tVar>()).toUInt()
        if (task_info(mach_task_self_, TASK_VM_INFO.toUInt(), vm.ptr.reinterpret(), count.ptr) != KERN_SUCCESS) return null
        vm.phys_footprint.toLong() to vm.limit_bytes_remaining.toLong()
    }

    private fun read(): Reading? = memScoped {
        val vm = alloc<task_vm_info_data_t>()
        val vmCount = alloc<mach_msg_type_number_tVar>()
        vmCount.value = (sizeOf<task_vm_info_data_t>() / sizeOf<natural_tVar>()).toUInt()
        if (task_info(mach_task_self_, TASK_VM_INFO.toUInt(), vm.ptr.reinterpret(), vmCount.ptr) != KERN_SUCCESS) return null
        val power = alloc<task_power_info_v2_data_t>()
        val powerCount = alloc<mach_msg_type_number_tVar>()
        powerCount.value = (sizeOf<task_power_info_v2_data_t>() / sizeOf<natural_tVar>()).toUInt()
        val hasPower = task_info(mach_task_self_, TASK_POWER_INFO_V2.toUInt(), power.ptr.reinterpret(), powerCount.ptr) == KERN_SUCCESS
        val usage = alloc<rusage>()
        getrusage(RUSAGE_SELF, usage.ptr)
        val cpu = usage.ru_utime.tv_sec + usage.ru_stime.tv_sec + (usage.ru_utime.tv_usec + usage.ru_stime.tv_usec) / 1e6
        Reading(
            seconds = NSProcessInfo.processInfo.systemUptime,
            cpuSeconds = cpu,
            footprint = vm.phys_footprint.toLong(),
            interruptWakeups = if (hasPower) power.cpu_energy.task_interrupt_wakeups.toLong() else 0,
            idleWakeups = if (hasPower) power.cpu_energy.task_platform_idle_wakeups.toLong() else 0,
            gpuNs = if (hasPower) power.gpu_energy.task_gpu_utilisation.toLong() else 0,
            energyNj = if (hasPower) power.task_energy.toLong() else 0,
            graphics = vm.ledger_tag_graphics_footprint,
            media = vm.ledger_tag_media_footprint,
            compressed = vm.compressed.toLong(),
        )
    }
}

/**
 * A sampling profiler for [PerfProbe], launched with `-perfSample <seconds>` (a few seconds after
 * the app starts): on a thread of its own, every 2 ms it stops each other thread that is running
 * at that moment, copies its frames' return addresses into memory set aside beforehand (nothing is
 * allocated while a thread is stopped, which could be holding the allocator's lock), lets it go,
 * and at the end logs the functions found most often, the Kotlin ones by name: what keeps the app
 * busy while it should rest.
 */
@OptIn(ExperimentalForeignApi::class, kotlin.experimental.ExperimentalNativeApi::class, kotlin.native.concurrent.ObsoleteWorkersApi::class)
internal object PerfSampler {
    fun startIfAsked() {
        val seconds = NSUserDefaults.standardUserDefaults.integerForKey("perfSample").toInt()
        val atBackground = NSUserDefaults.standardUserDefaults.integerForKey("perfSampleAtBackground").toInt()
        // Called on the main thread, which is left alone: it is the probe's own.
        val main = platform.posix.pthread_mach_thread_np(platform.posix.pthread_self())
        // `-perfSampleWait <s>` starts the sampling that many seconds in (8 when unset), and
        // `-perfSampleMain YES` samples the main thread too, with each stack's three innermost
        // functions: with `-playFileAfter`, what opening a video costs in an app already running.
        val waitFor = NSUserDefaults.standardUserDefaults.integerForKey("perfSampleWait").toInt().takeIf { it > 0 } ?: 8
        val withMain = NSUserDefaults.standardUserDefaults.boolForKey("perfSampleMain")
        if (seconds > 0) {
            kotlin.native.concurrent.Worker.start(name = "perf sampler").execute(kotlin.native.concurrent.TransferMode.SAFE, { Triple(seconds, main, waitFor to withMain) }) {
                run(it.first, it.second, wait = true, withMain = it.third.second, waitSeconds = it.third.first)
            }
        }
        // `-perfSampleEverySecond <s>`: from the start, what ran in each second, the main thread
        // included, with the footprint beside it, for a fault that comes and goes.
        val everySecond = NSUserDefaults.standardUserDefaults.integerForKey("perfSampleEverySecond").toInt()
        if (everySecond > 0) {
            kotlin.native.concurrent.Worker.start(name = "perf sampler").execute(kotlin.native.concurrent.TransferMode.SAFE, { everySecond to main }) {
                run(it.first, it.second, wait = false, withMain = true, window = true)
            }
        }
        // `-perfThreads <s>`: once, after that many seconds, where every thread is, running or
        // waiting, with its name: what each of the app's threads is for.
        val census = NSUserDefaults.standardUserDefaults.integerForKey("perfThreads").toInt()
        if (census > 0) {
            // Started when due, from the main queue: no thread of its own waits until then.
            platform.darwin.dispatch_after(
                platform.darwin.dispatch_time(platform.darwin.DISPATCH_TIME_NOW, census * 1_000_000_000L),
                platform.darwin.dispatch_get_main_queue(),
            ) {
                kotlin.native.concurrent.Worker.start(name = "perf census").execute(kotlin.native.concurrent.TransferMode.SAFE, { Unit }) { census() }
            }
        }
        // `-perfSampleAtBackground <s>`: from the moment the app stops being in front, the main
        // thread included, a report every half second, for what runs as the app goes away (a
        // process iOS may end within seconds).
        if (atBackground > 0) {
            var started = false
            platform.Foundation.NSNotificationCenter.defaultCenter.addObserverForName(
                platform.UIKit.UIApplicationWillResignActiveNotification, `object` = null, queue = platform.Foundation.NSOperationQueue.mainQueue,
            ) { _ ->
                if (!started) {
                    started = true
                    kotlin.native.concurrent.Worker.start(name = "perf sampler").execute(kotlin.native.concurrent.TransferMode.SAFE, { atBackground to main }) {
                        run(it.first, it.second, wait = false, withMain = true)
                    }
                }
            }
        }
    }

    private fun run(seconds: Int, main: UInt, wait: Boolean, withMain: Boolean, window: Boolean = false, waitSeconds: Int = 8) {
        Logger.i("PerfSampler", "the main thread is #$main")
        if (wait) platform.posix.sleep(waitSeconds.toUInt())
        val self = platform.posix.pthread_mach_thread_np(platform.posix.pthread_self())
        val list = kotlinx.cinterop.nativeHeap.alloc<kotlinx.cinterop.CPointerVar<platform.darwin.thread_act_tVar>>()
        val count = kotlinx.cinterop.nativeHeap.alloc<mach_msg_type_number_tVar>()
        val basic = kotlinx.cinterop.nativeHeap.alloc<platform.darwin.thread_basic_info_data_t>()
        val basicCount = kotlinx.cinterop.nativeHeap.alloc<mach_msg_type_number_tVar>()
        val frames = LongArray(48)
        val stacks = ArrayList<LongArray>()
        var samples = 0
        var running = 0
        val rounds = seconds * 500
        repeat(rounds) {
            if (platform.darwin.task_threads(mach_task_self_, list.ptr, count.ptr) == KERN_SUCCESS) {
                val threads = list.value
                val n = count.value.toInt()
                if (threads != null) {
                    for (i in 0 until n) {
                        val thread = threads[i]
                        basicCount.value = (sizeOf<platform.darwin.thread_basic_info_data_t>() / sizeOf<natural_tVar>()).toUInt()
                        val isRunning = thread != self && (withMain || thread != main) &&
                            platform.darwin.thread_info(thread, platform.darwin.THREAD_BASIC_INFO.toUInt(), basic.ptr.reinterpret(), basicCount.ptr) == KERN_SUCCESS &&
                            basic.run_state == platform.darwin.TH_STATE_RUNNING
                        if (isRunning) {
                            // Held, read and let go in one C call (sampler.def): safe with the collector.
                            val depth = frames.usePinned { com.tmplayer.ios.sampler.tm_sample_thread(thread, it.addressOf(0).reinterpret(), frames.size) }
                            if (depth > 0) {
                                // The thread's port first, so a report can say whose work it was.
                                stacks += LongArray(depth + 1) { if (it == 0) thread.toLong() else frames[it - 1] }
                                running++
                            }
                        }
                        platform.darwin.mach_port_deallocate(mach_task_self_, thread)
                    }
                    platform.darwin.vm_deallocate(mach_task_self_, threads.rawValue.toLong().toULong(), (n.toLong() * sizeOf<platform.darwin.thread_act_tVar>()).toULong())
                }
            }
            samples++
            if (window && samples % 125 == 0) {
                Logger.i("PerfSampler", "at ${samples / 500.0} s: ${PerfProbe.memoryNow()}")
                report(stacks, samples, running, chain = true)
                stacks.clear()
            } else if (withMain && !window && samples % 250 == 0) {
                report(stacks, samples, running, chain = true)
            }
            platform.posix.usleep(2_000u)
        }
        report(stacks, samples, running, chain = withMain)
    }

    /** Each thread's CPU so far, in microseconds, by its port. */
    private fun cpuByThread(): Map<UInt, Long> = memScoped {
        val out = HashMap<UInt, Long>()
        val list = alloc<kotlinx.cinterop.CPointerVar<platform.darwin.thread_act_tVar>>()
        val count = alloc<mach_msg_type_number_tVar>()
        if (platform.darwin.task_threads(mach_task_self_, list.ptr, count.ptr) != KERN_SUCCESS) return@memScoped out
        val threads = list.value ?: return@memScoped out
        val n = count.value.toInt()
        for (i in 0 until n) {
            val thread = threads[i]
            val basic = alloc<platform.darwin.thread_basic_info_data_t>()
            val basicCount = alloc<mach_msg_type_number_tVar>()
            basicCount.value = (sizeOf<platform.darwin.thread_basic_info_data_t>() / sizeOf<natural_tVar>()).toUInt()
            if (platform.darwin.thread_info(thread, platform.darwin.THREAD_BASIC_INFO.toUInt(), basic.ptr.reinterpret(), basicCount.ptr) == KERN_SUCCESS) {
                out[thread] = (basic.user_time.seconds + basic.system_time.seconds).toLong() * 1_000_000 +
                    basic.user_time.microseconds + basic.system_time.microseconds
            }
            platform.darwin.mach_port_deallocate(mach_task_self_, thread)
        }
        platform.darwin.vm_deallocate(mach_task_self_, threads.rawValue.toLong().toULong(), (n.toLong() * sizeOf<platform.darwin.thread_act_tVar>()).toULong())
        out
    }

    /**
     * Every thread's name, what it used over ten seconds (µs a second) and where it is now, held or
     * not: the busiest first, the libraries and functions nearest its top.
     */
    private fun census() {
        val self = platform.posix.pthread_mach_thread_np(platform.posix.pthread_self())
        val before = cpuByThread()
        platform.posix.sleep(10u)
        val after = cpuByThread()
        val rates = after.mapValues { (port, now) -> (now - (before[port] ?: now)) / 10 }
        val frames = LongArray(48)
        memScoped {
            val list = alloc<kotlinx.cinterop.CPointerVar<platform.darwin.thread_act_tVar>>()
            val count = alloc<mach_msg_type_number_tVar>()
            if (platform.darwin.task_threads(mach_task_self_, list.ptr, count.ptr) != KERN_SUCCESS) return
            val threads = list.value ?: return
            val n = count.value.toInt()
            Logger.i("PerfSampler", "census of $n threads, CPU over 10 s")
            val lines = ArrayList<Pair<Long, String>>()
            for (i in 0 until n) {
                val thread = threads[i]
                if (thread != self) {
                    val depth = frames.usePinned { com.tmplayer.ios.sampler.tm_sample_thread(thread, it.addressOf(0).reinterpret(), frames.size) }
                    val symbols = (0 until depth).map { nameOf(frames[it]) }.distinct()
                    val rate = rates[thread] ?: -1
                    lines += rate to "#$thread ${rate}us/s ${PerfProbe.threadName(thread)}: " +
                        symbols.take(7).joinToString(" < ") { it.removePrefix("kfun:").take(70) }
                }
                platform.darwin.mach_port_deallocate(mach_task_self_, thread)
            }
            lines.sortedByDescending { it.first }.forEach { Logger.i("PerfSampler", it.second) }
            platform.darwin.vm_deallocate(mach_task_self_, threads.rawValue.toLong().toULong(), (n.toLong() * sizeOf<platform.darwin.thread_act_tVar>()).toULong())
        }
    }

    private fun nameOf(address: Long): String = names.getOrPut(address) {
        memScoped {
            val info = alloc<platform.posix.Dl_info>()
            if (platform.posix.dladdr(address.toCPointer<kotlinx.cinterop.ByteVar>(), info.ptr) == 0) {
                "?"
            } else {
                val image = info.dli_fname?.toKString()?.substringAfterLast('/') ?: "?"
                val symbol = info.dli_sname?.toKString().orEmpty()
                if (symbol.startsWith("kfun:")) symbol.take(110) else "$image:${symbol.take(60)}"
            }
        }
    }

    private val names = HashMap<Long, String>()

    /**
     * Each sample counted once for the first Kotlin function on its stack (the work itself), or its
     * first library otherwise; with [chain], the first three such functions, innermost first, so the
     * caller that keeps the work going shows too.
     */
    private fun report(stacks: List<LongArray>, samples: Int, running: Int, chain: Boolean) {
        fun name(address: Long): String = names.getOrPut(address) {
            memScoped {
                val info = alloc<platform.posix.Dl_info>()
                if (platform.posix.dladdr(address.toCPointer<kotlinx.cinterop.ByteVar>(), info.ptr) == 0) {
                    "?"
                } else {
                    val image = info.dli_fname?.toKString()?.substringAfterLast('/') ?: "?"
                    val symbol = info.dli_sname?.toKString().orEmpty()
                    if (symbol.startsWith("kfun:")) symbol.take(110) else "$image:${symbol.take(60)}"
                }
            }
        }
        val counts = HashMap<String, Int>()
        for (stack in stacks) {
            val thread = stack[0]
            val symbols = stack.drop(1).map(::name)
            val own = symbols.filter {
                it.startsWith("kfun:") && !it.startsWith("kfun:kotlinx.") && !it.startsWith("kfun:kotlin.") && !it.startsWith("kfun:java.")
            }
            val work = if (chain && own.isNotEmpty()) {
                // A copy or a fill under the Kotlin code says a large block is being made.
                val leaf = symbols.first().takeIf { "memset" in it || "memmove" in it || "memcpy" in it || "bzero" in it }
                val library = symbols.firstOrNull { it.startsWith("kfun:kotlinx.") || it.startsWith("kfun:java.") }?.removePrefix("kfun:")?.take(60)
                "#$thread " + (listOfNotNull(leaf, library) + own.take(6).map { it.removePrefix("kfun:").take(80) }).joinToString(" < ")
            } else {
                own.firstOrNull()
                    ?: symbols.firstOrNull { it.startsWith("kfun:") }
                    ?: symbols.firstOrNull { !it.startsWith("libsystem") } ?: symbols.first()
            }
            counts[work] = (counts[work] ?: 0) + 1
        }
        Logger.i("PerfSampler", "$samples samples, ${samples / 500.0} s, $running running threads caught")
        counts.entries.sortedByDescending { it.value }.take(if (chain) 10 else 25).forEach { (work, n) -> Logger.i("PerfSampler", "$n  $work") }
    }
}
