package com.tmplayer.cast

import com.tmplayer.platform.Logger
import java.io.Closeable
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.io.RandomAccessFile
import java.net.BindException
import java.net.InetAddress
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * A video served to a player on this device over HTTP (PR3): libVLC in the app, or VLC and MX
 * Player as the default player, which read a URL rather than TMPlayer's data source.
 *
 * It is a [CastServer] of its own, separate from the Cast one, bound to 127.0.0.1 alone and
 * answering loopback clients alone ([isLoopbackClient]): nothing on the network can reach it,
 * and the Cast server's LAN filter is left as it is. Every session has its own random token in
 * the path ([CastServer.token]), so a URL from an earlier session, or a guessed one, gets a 404.
 * [close] ends the session when playback ends; a session nobody asks anything of for
 * [Config.sessionIdleMs] closes itself, so a forgotten one cannot hold the port.
 *
 * The bytes:
 *  - A file that is fully downloaded ([downloaded]) is read from the disk directly, with no TDLib
 *    window. If it disappears (cleared from the cache, deleted) the reads go on from [streaming]
 *    when there is one; without one the video is taken down, so a player's retries get a 404
 *    rather than looping, and [Config.onFailure] hears of it.
 *  - Anything else streams from [streaming] through a [ReadArbiter], so the player's several
 *    connections (the index at the end, then the body) share one TDLib download window, and
 *    through a [RecoveringCastSource] (unless given one, or [Config.recovery] is null), so a
 *    network cut or a FLOOD_WAIT is waited out by the read that met it.
 *  - Ranges are [CastServer]'s: open ended, suffix, past the end (416) and HEAD, in Long, so a
 *    file over 4 GB seeks to its end.
 *
 * During a long pause the arbiter lets go of TDLib after [Config.readerIdleMs]; the player's next
 * request (or the same connection's next read) opens it again at its own place.
 */
class LoopbackStream private constructor(
    private val server: CastServer,
    private val arbiter: ReadArbiter?,
    private val config: Config,
    private val name: String,
) : Closeable {

    /** How a session behaves; the defaults suit playback, the tests shorten them. */
    class Config(
        /** The port to try first; 0, or a port in use, means any free one. */
        val port: Int = 0,
        /** No read for this long and the TDLib window is let go ([ReadArbiter.sweep]). */
        val readerIdleMs: Long = ReadArbiter.IDLE_MS,
        /** No request for this long and the whole session closes. */
        val sessionIdleMs: Long = SESSION_IDLE_MS,
        val handoverMs: Long = ReadArbiter.HANDOVER_MS,
        val maxWaitMs: Long = ReadArbiter.MAX_WAIT_MS,
        /** How a streamed read rides out a stall; null for none (the source does it). */
        val recovery: CastRecovery? = CastRecovery(),
        /** How often the idle rules are checked; 0 to leave it to [sweep] calls alone. */
        val sweepEveryMs: Long = SWEEP_MS,
        val clock: () -> Long = { System.nanoTime() / 1_000_000 },
        /** A read that failed for good (after its recovery): for the player's status sheet. */
        val onFailure: (IOException) -> Unit = {},
    )

    private val closed = AtomicBoolean(false)
    private var timer: ScheduledExecutorService? = null

    /** The URL a player opens: `http://127.0.0.1:<port>/<token>/<name>`. */
    val url: String = server.urlFor(name)

    val token: String get() = server.token
    val port: Int get() = server.boundPort
    val running: Boolean get() = server.running && !closed.get()

    /** Whether the TDLib window is held now (false for a file on the disk, or after an idle sweep). */
    val holdsWindow: Boolean get() = arbiter?.holdsInner == true

    /** Times the streamed source was opened (the window moved or came back), for the log and the tests. */
    val windowOpens: Int get() = arbiter?.innerOpens?.get() ?: 0

    private fun startTimer() {
        if (config.sweepEveryMs <= 0) return
        timer = Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "loopback-sweep").apply { isDaemon = true }
        }.also {
            it.scheduleWithFixedDelay({ runCatching { sweep() } }, config.sweepEveryMs, config.sweepEveryMs, TimeUnit.MILLISECONDS)
        }
    }

    /**
     * The idle rules, run by the timer: the TDLib window goes after [Config.readerIdleMs] with no
     * read, the session after [Config.sessionIdleMs] with no request. True when the session closed.
     */
    fun sweep(): Boolean {
        if (closed.get()) return true
        arbiter?.sweep()
        if (server.idleMs() >= config.sessionIdleMs) {
            Logger.i(TAG, "Nothing asked for ${config.sessionIdleMs}ms; closing the session")
            close()
            return true
        }
        return false
    }

    /** Takes the video down: requests after this get a 404, the session stays open. */
    internal fun takeDown() = server.unpublish(name)

    /** Ends the session: the port closes, open connections drop, the TDLib window is let go. */
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        timer?.shutdownNow()
        timer = null
        server.stop()
        arbiter?.close()
    }

    companion object {
        private const val TAG = "LoopbackStream"
        private val UNSAFE_NAME_CHARS = Regex("[^A-Za-z0-9._-]")
        const val SESSION_IDLE_MS = 2L * 60 * 60 * 1000
        const val SWEEP_MS = 5_000L

        /** 127.0.0.1, named so: never a lookup, never another interface. */
        val loopback: InetAddress = InetAddress.getByAddress("localhost", byteArrayOf(127, 0, 0, 1))

        /** Who may ask: the device itself, nothing else. */
        fun isLoopbackClient(address: InetAddress): Boolean = address.isLoopbackAddress

        /**
         * Starts a session serving [downloaded] (a finished file, read directly) or [streaming]
         * (TDLib's window), at least one of the two, as [name] of [mime]. Throws [IOException]
         * when there is nothing to serve or no port to listen on.
         */
        fun start(
            streaming: CastSource?,
            downloaded: File?,
            mime: String,
            name: String = "video",
            config: Config = Config(),
        ): LoopbackStream {
            val complete = downloaded?.takeIf { it.isFile && it.length() > 0 }
            if (streaming == null && complete == null) throw IOException("Nothing to serve")
            val arbiter = streaming?.let { source ->
                val riding = if (config.recovery == null || source is RecoveringCastSource) source
                else RecoveringCastSource(source, config.recovery)
                ReadArbiter(riding, config.handoverMs, config.maxWaitMs, config.readerIdleMs, clock = config.clock)
            }
            val server = bind(config)
            val holder = arrayOfNulls<LoopbackStream>(1)
            val source = LoopbackSource(
                file = complete,
                length = streaming?.length ?: complete?.length() ?: 0L,
                stream = arbiter,
                onGone = { e ->
                    Logger.w(TAG, "The video is gone: ${e.message}")
                    holder[0]?.takeDown()
                    config.onFailure(e)
                },
                onFailure = config.onFailure,
            )
            val clean = name.replace(UNSAFE_NAME_CHARS, "_").ifBlank { "video" }
            server.publish(clean, CastResource.Ranged(source, mime))
            val stream = LoopbackStream(server, arbiter, config, clean)
            holder[0] = stream
            stream.startTimer()
            Logger.i(TAG, "Serving ${if (complete != null) "a downloaded file" else "a stream"} on port ${stream.port}")
            return stream
        }

        /** The server on [Config.port], or on any free port when that one is taken. */
        private fun bind(config: Config): CastServer {
            if (config.port != 0) {
                val wanted = CastServer(loopback, config.port, ::isLoopbackClient, config.clock)
                try {
                    wanted.start()
                    return wanted
                } catch (e: BindException) {
                    Logger.i(TAG, "Port ${config.port} is taken (${e.message}); using another")
                } catch (e: IOException) {
                    Logger.i(TAG, "Port ${config.port} failed (${e.message}); using another")
                }
            }
            return CastServer(loopback, 0, ::isLoopbackClient, config.clock).apply { start() }
        }
    }
}

/**
 * What a [LoopbackStream] reads: [file] while it is there (a finished download), else [stream].
 * A file gone with no stream to fall back on is [onGone]; any other failure is [onFailure] and
 * fails that request alone.
 */
internal class LoopbackSource(
    private val file: File?,
    override val length: Long,
    private val stream: CastSource?,
    private val onGone: (IOException) -> Unit,
    private val onFailure: (IOException) -> Unit,
) : CastSource {

    @Volatile
    private var fileGone = file == null

    override fun askAgain(position: Long) {
        stream?.askAgain(position)
    }

    override fun open(position: Long): CastReader {
        if (!fileGone && file != null) {
            val direct = openFile(file, position)
            if (direct != null) return direct
            fileGone = true
            Logger.i(TAG, "The downloaded file is gone; ${if (stream != null) "streaming instead" else "nothing to fall back on"}")
        }
        val s = stream ?: FileNotFoundException("The video's file is gone").let { onGone(it); throw it }
        return guarded(s.open(position))
    }

    /** The file opened at [position], or null when it is gone or shorter than it was. */
    private fun openFile(file: File, position: Long): CastReader? {
        val raf = try {
            RandomAccessFile(file, "r")
        } catch (_: IOException) {
            return null
        } catch (_: SecurityException) {
            return null
        }
        try {
            if (raf.length() < length) {
                raf.close()
                return null
            }
            raf.seek(position)
        } catch (e: IOException) {
            runCatching { raf.close() }
            return null
        }
        return guarded(object : CastReader {
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int = raf.read(buffer, offset, length)
            override fun close() = raf.close()
        })
    }

    private fun guarded(reader: CastReader): CastReader = object : CastReader {
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int = try {
            reader.read(buffer, offset, length)
        } catch (e: IOException) {
            // A reader closed under it is the request ending (a hang up, a seek), not a failure.
            if (e !is ReadArbiter.ReaderClosedException) runCatching { onFailure(e) }
            throw e
        }

        override fun close() = reader.close()
    }

    private companion object {
        const val TAG = "LoopbackStream"
    }
}
