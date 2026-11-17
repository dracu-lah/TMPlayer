@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.tmplayer.platform

import kotlinx.cinterop.alloc
import kotlinx.cinterop.autoreleasepool
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.nativeHeap
import kotlinx.cinterop.ptr
import kotlinx.cinterop.staticCFunction
import kotlinx.cinterop.value
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Runnable
import platform.posix.ETIMEDOUT
import platform.posix.pthread_cond_init
import platform.posix.pthread_cond_signal
import platform.posix.pthread_cond_t
import platform.posix.pthread_cond_timedwait_relative_np
import platform.posix.pthread_create
import platform.posix.pthread_detach
import platform.posix.pthread_mutex_init
import platform.posix.pthread_mutex_lock
import platform.posix.pthread_mutex_t
import platform.posix.pthread_mutex_unlock
import platform.posix.pthread_tVar
import platform.posix.timespec
import kotlin.concurrent.Volatile

/**
 * `Dispatchers.IO` for the iOS build, as every package's JvmDefaults hands it to upstream's code,
 * made to behave as the JVM's: a thread more whenever every thread is busy, up to 64, and a thread
 * left idle for [IDLE_SECONDS] ends. kotlinx.coroutines' IO on Kotlin/Native never ends one: the
 * bursts as the app starts left 40, each woken a dozen times a second while the app rested, 490
 * wakeups a second on Home (iPhone 13, 2026-11-13). Grand Central Dispatch lets idle threads go
 * too, but it is made for work that does not block, and much of this work does (TDLib's wait for
 * answers, runBlocking): a pool with the JVM's own rules keeps the work as Android runs it.
 *
 * Each task runs in an autorelease pool of its own, which Kotlin's threads do not drain by
 * themselves; a task that throws is logged and the thread goes on.
 */
object IosIo : CoroutineDispatcher() {
    private const val MAX_THREADS = 64

    /** How long a thread waits for work before it ends; the JVM's IO waits a minute. */
    private const val IDLE_SECONDS = 10L

    private val lock = nativeHeap.alloc<pthread_mutex_t>().also { pthread_mutex_init(it.ptr, null) }
    private val wake = nativeHeap.alloc<pthread_cond_t>().also { pthread_cond_init(it.ptr, null) }
    private val idleFor = nativeHeap.alloc<timespec>().also {
        it.tv_sec = IDLE_SECONDS
        it.tv_nsec = 0
    }

    // The pointers, made once: Kotlin/Native makes an object for a pointer at every `.ptr`, and the
    // pool reached for these at every task and every wait, about 18 MB in Home's first 20 s
    // (iPhone 13, 2026-11-13, `-perfAllocs`).
    private val lockAt = lock.ptr
    private val wakeAt = wake.ptr
    private val idleForAt = idleFor.ptr

    // Guarded by [lock].
    private val tasks = ArrayDeque<Runnable>()
    private var threads = 0
    private var idle = 0

    /** Threads running now, for a developer's check. */
    @Volatile
    var running: Int = 0
        private set

    override fun dispatch(context: kotlin.coroutines.CoroutineContext, block: Runnable) {
        pthread_mutex_lock(lockAt)
        tasks.addLast(block)
        // A waiting thread that was woken counts as idle until it has the lock again, so the tasks
        // waiting are set against the threads waiting: "start one when none is idle" would hand a
        // burst of tasks to one idle thread, and were its first task to block (TDLib's wait for
        // answers) the rest would wait for good.
        val start = tasks.size > idle && threads < MAX_THREADS
        if (start) {
            threads++
            running = threads
        } else {
            pthread_cond_signal(wakeAt)
        }
        pthread_mutex_unlock(lockAt)
        if (start) startThread()
    }

    private fun startThread() = memScoped {
        val thread = alloc<pthread_tVar>()
        if (pthread_create(thread.ptr, null, staticCFunction { _ -> IosIo.work(); null }, null) == 0) {
            pthread_detach(thread.value)
        } else {
            // No thread to be had: the task waits for one that is.
            pthread_mutex_lock(lockAt)
            threads--
            running = threads
            pthread_mutex_unlock(lockAt)
        }
    }

    /**
     * A thread's life: the tasks there are, then a wait, and an end after one with nothing. An
     * autorelease pool stands under the tasks' own for as long as the thread lives, so each task's
     * pool takes the thread's page instead of making one and freeing it.
     */
    private fun work() {
        autoreleasepool {
            while (true) {
                pthread_mutex_lock(lockAt)
                var task = tasks.removeFirstOrNull()
                while (task == null) {
                    idle++
                    val timedOut = waitIdle()
                    idle--
                    task = tasks.removeFirstOrNull()
                    if (task == null && timedOut) {
                        threads--
                        running = threads
                        pthread_mutex_unlock(lockAt)
                        return@autoreleasepool
                    }
                }
                pthread_mutex_unlock(lockAt)
                // Each task's autoreleased objects go when it ends.
                autoreleasepool {
                    try {
                        task.run()
                    } catch (e: Throwable) {
                        Logger.w("IosIo", "a task failed", e)
                    }
                }
            }
        }
    }

    /** Waits on [wake] for [IDLE_SECONDS] at most, [lock] held; true when it timed out. */
    private fun waitIdle(): Boolean = pthread_cond_timedwait_relative_np(wakeAt, lockAt, idleForAt) == ETIMEDOUT

    override fun toString(): String = "Dispatchers.IO (iOS)"
}
