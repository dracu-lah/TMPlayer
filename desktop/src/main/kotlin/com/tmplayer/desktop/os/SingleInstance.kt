package com.tmplayer.desktop.os

import com.tmplayer.desktop.DesktopPaths
import com.tmplayer.platform.Logger
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.channels.Channels
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import java.nio.file.Files

/**
 * One TMPlayer per user. Call first thing in `main`:
 *
 * ```
 * if (!SingleInstance.acquire(args.toList()) { forwarded -> bringToFront(); open(forwarded) }) return
 * ```
 *
 * The first launch takes the lock and keeps it for the life of the process; [acquire] returns true
 * and `onMessage` is called (on a background thread) each time a later launch forwards its
 * arguments, which today means "show the window" and later carries `tg:` links. A later launch
 * hands its arguments over, waits for the first to confirm, and gets false: it should exit.
 *
 * Call [markReady] once the window is up. Until then a later launch is told "starting", and if the
 * first has been starting for too long it is taken to be stuck (an error before its window, with
 * TDLib's threads keeping the process alive) and is ended, so the later launch can open instead of
 * handing over to a process that will never show anything.
 */
object SingleInstance {

    @Volatile
    private var held: InstanceLock? = null

    fun acquire(args: List<String>, onMessage: (List<String>) -> Unit): Boolean {
        val lock = InstanceLock(DesktopPaths.dataDir)
        val first = lock.acquire(args, onMessage)
        if (first) held = lock
        return first
    }

    /** The window is up: later launches may hand over to this one from now on. */
    fun markReady() {
        held?.ready = true
    }

    /** Lets go of the lock and removes the socket, on the way out. */
    fun close() {
        held?.close()
        held = null
    }
}

/**
 * The mechanism behind [SingleInstance], for any directory: a lock file (`<name>.lock`, held with
 * an OS file lock, so a crash never leaves it stale) decides who is first, and a Unix domain socket
 * (`<name>.sock`; JDK 16+, which Windows 10 1803 and later support too) carries the arguments.
 * The holder's process id goes in `<name>.pid`, apart from the lock file, which Windows locks
 * against reading too.
 */
class InstanceLock(
    private val dir: File,
    private val name: String = "instance",
    private val stuckAfterMs: Long = STUCK_AFTER_MS,
) : AutoCloseable {

    private var channel: FileChannel? = null
    private var fileLock: FileLock? = null
    private var server: ServerSocketChannel? = null

    @Volatile
    private var closed = false

    /** Set by the owner once it can show itself; before that, later launches hear "starting". */
    @Volatile
    var ready = false

    private val pidFile = File(dir, "$name.pid")

    internal val socketFile: File = socketPath(dir, name)

    /** True if this is the first instance; false after the arguments reached the first one. */
    fun acquire(args: List<String>, onMessage: (List<String>) -> Unit): Boolean {
        dir.mkdirs()
        if (tryLock() || forward(args) != Answer.Shown && takeOver()) {
            runCatching { pidFile.writeText(ProcessHandle.current().pid().toString()) }
            listen(onMessage)
            return true
        }
        return false
    }

    /**
     * Ends the holder if it never got its window up in [stuckAfterMs], then takes the lock. Only a
     * process running the same program as this one, and never this process itself.
     */
    private fun takeOver(): Boolean {
        val pid = runCatching { pidFile.readText().trim().toLong() }.getOrNull() ?: return false
        val self = ProcessHandle.current()
        if (pid == self.pid()) return false
        val holder = ProcessHandle.of(pid).orElse(null) ?: return false
        val info = holder.info()
        if (info.command().orElse(null) != self.info().command().orElse(null)) return false
        val started = info.startInstant().orElse(null) ?: return false
        val age = java.time.Duration.between(started, java.time.Instant.now()).toMillis()
        if (age < stuckAfterMs) return false
        Logger.w(TAG, "TMPlayer $pid has held the lock for ${age / 1000} s without a window, ending it")
        holder.destroyForcibly()
        runCatching { holder.onExit().get(KILL_WAIT_MS, java.util.concurrent.TimeUnit.MILLISECONDS) }
        // Windows lets go of a dead process's file lock a moment after it is gone.
        repeat(CONNECT_ATTEMPTS) {
            if (tryLock()) return true
            Thread.sleep(CONNECT_RETRY_MS)
        }
        return false
    }

    override fun close() {
        closed = true
        runCatching { server?.close() }
        runCatching { if (fileLock != null) Files.deleteIfExists(socketFile.toPath()) }
        runCatching { if (fileLock != null) Files.deleteIfExists(pidFile.toPath()) }
        runCatching { fileLock?.release() }
        runCatching { channel?.close() }
        server = null
        fileLock = null
        channel = null
    }

    private fun tryLock(): Boolean {
        val ch = RandomAccessFile(File(dir, "$name.lock"), "rw").channel
        val lock = try {
            ch.tryLock()
        } catch (_: OverlappingFileLockException) {
            // This JVM already holds it (two locks in one process, as in tests), which counts as
            // taken. The channel stays open until close(): with POSIX locks, closing any handle
            // on the file would drop the lock the other one holds.
            channel = ch
            return false
        } catch (e: IOException) {
            Logger.w(TAG, "lock file unusable, running anyway: ${e.message}")
            channel = ch
            return true
        }
        if (lock == null) {
            ch.close()
            return false
        }
        channel = ch
        fileLock = lock
        return true
    }

    private fun listen(onMessage: (List<String>) -> Unit) {
        val path = socketFile.toPath()
        val srv = runCatching {
            // Left behind by a crash: the lock says nobody is listening on it.
            Files.deleteIfExists(path)
            ServerSocketChannel.open(StandardProtocolFamily.UNIX).bind(UnixDomainSocketAddress.of(path))
        }.onFailure { Logger.w(TAG, "no instance socket, later launches will not reach this one: ${it.message}") }
            .getOrNull() ?: return
        server = srv
        Thread({
            while (!closed) {
                val client = try {
                    srv.accept()
                } catch (_: IOException) {
                    break
                }
                runCatching { client.use { serve(it, onMessage) } }
                    .onFailure { Logger.w(TAG, "bad message from a later launch: ${it.message}") }
            }
        }, "tmplayer-single-instance").apply { isDaemon = true }.start()
    }

    private fun serve(client: SocketChannel, onMessage: (List<String>) -> Unit) {
        val input = DataInputStream(Channels.newInputStream(client))
        val output = DataOutputStream(Channels.newOutputStream(client))
        val args = readMessage(input) ?: return
        val shown = ready
        output.writeByte(if (shown) ACK else STARTING)
        output.flush()
        if (shown) onMessage(args)
    }

    private enum class Answer { Shown, Starting, Silent }

    private fun forward(args: List<String>): Answer {
        val address = UnixDomainSocketAddress.of(socketFile.toPath())
        // The first instance may hold the lock but not be listening yet, if both started together.
        repeat(CONNECT_ATTEMPTS) { attempt ->
            val reply = runCatching {
                SocketChannel.open(address).use { ch ->
                    val output = DataOutputStream(Channels.newOutputStream(ch))
                    writeMessage(output, args)
                    output.flush()
                    DataInputStream(Channels.newInputStream(ch)).readByte().toInt()
                }
            }.getOrNull()
            if (reply == ACK) return Answer.Shown
            if (reply == STARTING) return Answer.Starting
            if (attempt < CONNECT_ATTEMPTS - 1) Thread.sleep(CONNECT_RETRY_MS)
        }
        Logger.w(TAG, "another TMPlayer holds the lock but did not answer")
        return Answer.Silent
    }

    internal companion object {
        private const val TAG = "SingleInstance"
        private const val MAGIC = 0x544D5031 // "TMP1"
        private const val ACK = 0x06
        private const val STARTING = 0x15
        private const val MAX_ARGS = 256
        private const val CONNECT_ATTEMPTS = 20
        private const val CONNECT_RETRY_MS = 150L

        /** A first launch with no window after this long is stuck, not slow. */
        const val STUCK_AFTER_MS = 60_000L
        private const val KILL_WAIT_MS = 5_000L

        /** Unix socket paths stop at about 104 bytes on macOS and 108 on Linux. */
        private const val MAX_SOCKET_PATH = 100

        fun socketPath(dir: File, name: String): File {
            val inDir = File(dir, "$name.sock")
            if (inDir.absolutePath.toByteArray().size <= MAX_SOCKET_PATH) return inDir
            val tag = Integer.toHexString(dir.absolutePath.hashCode())
            return File(System.getProperty("java.io.tmpdir"), "tmplayer-$tag-$name.sock")
        }

        fun writeMessage(out: DataOutputStream, args: List<String>) {
            out.writeInt(MAGIC)
            out.writeInt(args.size)
            args.forEach { out.writeUTF(it) }
        }

        fun readMessage(input: DataInputStream): List<String>? {
            if (input.readInt() != MAGIC) return null
            val count = input.readInt()
            if (count !in 0..MAX_ARGS) return null
            return List(count) { input.readUTF() }
        }
    }
}
