package com.tmplayer.desktop.os

import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.io.PrintStream
import java.time.LocalDateTime

/**
 * Keeps standard error in a file as well, `<data>/logs/tmplayer.log`, with the run before it as
 * `tmplayer.1.log`.
 *
 * The Windows launcher is a GUI program with no console, so whatever the app printed (every
 * [com.tmplayer.platform.Logger] line, and the stack trace of a crash) used to go nowhere: a launch
 * that died before its window showed left no trace at all. Uncaught exceptions on any thread are
 * written here too, with their thread's name.
 */
object DesktopLog {

    private const val NAME = "tmplayer.log"
    private const val PREVIOUS = "tmplayer.1.log"

    /** Bigger than this and the file starts again, so a long session cannot fill the disk. */
    private const val MAX_BYTES = 8L * 1024 * 1024

    @Volatile
    var file: File? = null
        private set

    fun install(dir: File) {
        val log = runCatching {
            dir.mkdirs()
            val current = File(dir, NAME)
            if (current.exists()) {
                val previous = File(dir, PREVIOUS)
                previous.delete()
                current.renameTo(previous)
            }
            current
        }.getOrNull() ?: return
        val out = runCatching { CappedStream(log, MAX_BYTES) }.getOrNull() ?: return
        file = log
        val console = System.err
        System.setErr(PrintStream(Tee(console, out), true, Charsets.UTF_8))
        System.err.println("TMPlayer ${System.getProperty("jpackage.app-version") ?: "dev"} started ${LocalDateTime.now()}")
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            System.err.println("E/Crash: uncaught on ${thread.name}")
            error.printStackTrace()
            System.err.flush()
            previous?.uncaughtException(thread, error)
        }
    }

    private class Tee(private val a: OutputStream, private val b: OutputStream) : OutputStream() {
        override fun write(b0: Int) {
            runCatching { a.write(b0) }
            runCatching { b.write(b0) }
        }

        override fun write(bytes: ByteArray, off: Int, len: Int) {
            runCatching { a.write(bytes, off, len) }
            runCatching { b.write(bytes, off, len) }
        }

        override fun flush() {
            runCatching { a.flush() }
            runCatching { b.flush() }
        }
    }

    /** Appends until [max] bytes, then empties the file and carries on. */
    private class CappedStream(private val target: File, private val max: Long) : OutputStream() {
        private var stream = FileOutputStream(target, true)
        private var written = target.length()

        @Synchronized
        override fun write(b: Int) {
            roll(1)
            stream.write(b)
            written++
        }

        @Synchronized
        override fun write(bytes: ByteArray, off: Int, len: Int) {
            roll(len)
            stream.write(bytes, off, len)
            written += len
        }

        @Synchronized
        override fun flush() = stream.flush()

        private fun roll(next: Int) {
            if (written + next <= max) return
            stream.close()
            stream = FileOutputStream(target, false)
            written = 0
        }
    }
}
