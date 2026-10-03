package com.tmplayer.desktop.os

import com.tmplayer.platform.Logger
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Keeps the screen on and the machine awake while a video plays.
 *
 * The player calls [acquire] when playback starts or resumes and [release] on pause, end or close;
 * both are idempotent and return at once, because the work behind them (a D-Bus round trip, a
 * child process, a Win32 call) runs on one long lived thread that each implementation owns. That
 * thread matters on Windows, where `SetThreadExecutionState` belongs to the thread that called it.
 *
 * [close] releases and stops that thread, for the app's exit.
 */
interface KeepAwake : AutoCloseable {

    /** What was last asked for: true between [acquire] and [release]. */
    val isHeld: Boolean

    fun acquire(reason: String = "Playing a video")

    fun release()

    override fun close()

    companion object {
        /**
         * The implementation for this OS. [x11WindowId] is only used on Linux, for the last resort
         * `xdg-screensaver suspend`, which needs an X11 window id to attach to; without one that
         * step is skipped.
         */
        fun create(x11WindowId: () -> Long? = { null }): KeepAwake = when {
            OsInfo.isLinux -> LinuxKeepAwake(x11WindowId)
            OsInfo.isWindows -> WindowsKeepAwake()
            OsInfo.isMac -> MacKeepAwake()
            else -> NoKeepAwake
        }
    }
}

/** For an OS none of the three implementations cover. */
object NoKeepAwake : KeepAwake {
    override val isHeld: Boolean get() = false
    override fun acquire(reason: String) = Unit
    override fun release() = Unit
    override fun close() = Unit
}

/**
 * Runs every acquire and release in order on one thread named for the OS, and skips the ones that
 * would not change anything, so a player that reports "playing" every second costs nothing.
 */
internal abstract class SerialKeepAwake(osName: String) : KeepAwake {

    private val worker: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "tmplayer-keep-awake-$osName").apply { isDaemon = true }
    }

    @Volatile
    private var wanted = false

    /** What the worker thread has actually done; written on that thread only. */
    @Volatile
    private var applied = false

    @Volatile
    private var closed = false

    override val isHeld: Boolean get() = wanted

    final override fun acquire(reason: String) {
        if (closed) return
        wanted = true
        submit { if (!applied) applied = runCatching { hold(reason) }.onFailure { warn("acquire", it) }.getOrDefault(false) }
    }

    final override fun release() {
        if (closed) return
        wanted = false
        submit {
            if (applied) {
                runCatching { letGo() }.onFailure { warn("release", it) }
                applied = false
            }
        }
    }

    final override fun close() {
        if (closed) return
        wanted = false
        submit {
            if (applied) runCatching { letGo() }.onFailure { warn("release", it) }
            applied = false
            runCatching { dispose() }
        }
        closed = true
        worker.shutdown()
        worker.awaitTermination(2, TimeUnit.SECONDS)
    }

    /** Waits for everything submitted so far; for tests and the live check. */
    internal fun flush() {
        if (!closed) worker.submit { }.get(10, TimeUnit.SECONDS)
    }

    /** Whether the worker thread holds an inhibition right now; for tests and the live check. */
    internal val isApplied: Boolean get() = applied

    private fun submit(task: () -> Unit) {
        runCatching { worker.execute(task) }
    }

    private fun warn(what: String, error: Throwable) = Logger.w(TAG, "$what failed", error)

    /** Takes the inhibition on the worker thread. False when nothing on this machine took it. */
    protected abstract fun hold(reason: String): Boolean

    /** Gives back what [hold] took, on the worker thread. */
    protected abstract fun letGo()

    /** Frees connections at close, on the worker thread. */
    protected open fun dispose() = Unit

    companion object {
        const val TAG = "KeepAwake"
    }
}
