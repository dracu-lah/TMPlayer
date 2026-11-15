package com.tmplayer.desktop.cast

import com.tmplayer.cast.Ffmpeg
import com.tmplayer.cast.FfmpegProgress
import com.tmplayer.cast.LoopbackStream
import com.tmplayer.cast.TranscodeAbility
import com.tmplayer.cast.TranscodeJob
import com.tmplayer.cast.Transcoder
import com.tmplayer.desktop.DesktopPaths
import com.tmplayer.platform.Logger
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * Where the user's `ffmpeg` is. TMPlayer ships none: an ffmpeg with libx264 is GPL, and run as its
 * own process it stays the user's program, so the app's licence does not change. Looked for in
 * order: `TMPLAYER_FFMPEG`, `<app data>/ffmpeg/` (and its `bin/`), the folder the app runs from,
 * then every folder on `PATH`.
 */
object FfmpegLocator {

    fun candidates(env: Map<String, String>, dataDir: File, appDir: File?, windows: Boolean): List<File> {
        val name = if (windows) "ffmpeg.exe" else "ffmpeg"
        val out = ArrayList<File>()
        env["TMPLAYER_FFMPEG"]?.takeIf { it.isNotBlank() }?.let { out += File(it) }
        out += File(dataDir, "ffmpeg/$name")
        out += File(dataDir, "ffmpeg/bin/$name")
        appDir?.let {
            out += File(it, name)
            out += File(it, "ffmpeg/$name")
        }
        val path = env["PATH"] ?: env["Path"] ?: ""
        path.split(File.pathSeparatorChar).filter { it.isNotBlank() }.take(MAX_PATH_DIRS).forEach { out += File(it.trim('"'), name) }
        return out
    }

    /** The first candidate that is a file this user may run, or null. */
    fun find(
        env: Map<String, String> = System.getenv(),
        dataDir: File = DesktopPaths.dataDir,
        appDir: File? = runCatching { File(System.getProperty("compose.application.resources.dir") ?: "").parentFile }.getOrNull(),
        windows: Boolean = System.getProperty("os.name").orEmpty().lowercase(Locale.ROOT).contains("win"),
    ): File? = candidates(env, dataDir, appDir, windows).firstOrNull { runCatching { it.isFile && it.canExecute() }.getOrDefault(false) }

    private const val MAX_PATH_DIRS = 200
}

/** Starts a process; the tests give a fake one. */
fun interface ProcessLauncher {
    fun start(args: List<String>): Process

    companion object {
        val Default = ProcessLauncher { args -> ProcessBuilder(args).redirectInput(ProcessBuilder.Redirect.PIPE).start() }
    }
}

/** An ffmpeg found on this computer and the encoders it has. */
class DesktopFfmpeg(val executable: File, val encoders: Set<String>) {

    /** What it can make for a cast, or null without an H.264 or AAC encoder. */
    val ability: TranscodeAbility? = Ffmpeg.ability(encoders)

    /** The H.264 encoder that worked last, tried first next time. */
    @Volatile
    var working: String? = null

    companion object {
        @Volatile
        private var cached: Pair<Long, DesktopFfmpeg?>? = null

        /**
         * The ffmpeg here, read once a minute at most (the user may drop one in while the app runs).
         * Runs `ffmpeg -hide_banner -encoders`, given 10 seconds; anything wrong means none.
         */
        fun detect(launcher: ProcessLauncher = ProcessLauncher.Default, find: () -> File? = { FfmpegLocator.find() }): DesktopFfmpeg? {
            val now = System.nanoTime() / 1_000_000
            cached?.takeIf { now - it.first < RECHECK_MS }?.let { return it.second }
            val found = runCatching { find()?.let { exe -> read(exe, launcher) } }
                .onFailure { Logger.w(TAG, "ffmpeg could not be read: ${it.message}") }
                .getOrNull()
            Logger.i(TAG, if (found != null) "ffmpeg at ${found.executable}, H.264 by ${Ffmpeg.h264Order(found.encoders)}" else "No ffmpeg: no transcoding for casts")
            cached = now to found
            return found
        }

        internal fun read(exe: File, launcher: ProcessLauncher): DesktopFfmpeg? {
            val p = launcher.start(listOf(exe.path, "-hide_banner", "-encoders"))
            runCatching { p.outputStream.close() }
            val err = thread(name = "ffmpeg-encoders-err", isDaemon = true) { runCatching { p.errorStream.readBytes() } }
            val text = p.inputStream.bufferedReader().use { r ->
                val sb = StringBuilder()
                val buf = CharArray(8192)
                while (sb.length < MAX_LISTING) {
                    val n = r.read(buf)
                    if (n < 0) break
                    sb.append(buf, 0, n)
                }
                sb.toString()
            }
            if (!p.waitFor(10, TimeUnit.SECONDS)) {
                p.destroyForcibly()
                return null
            }
            err.join(1_000)
            val e = Ffmpeg.encoders(text)
            return if (e.isEmpty()) null else DesktopFfmpeg(exe, e)
        }

        private const val TAG = "DesktopFfmpeg"
        private const val RECHECK_MS = 60_000L
        private const val MAX_LISTING = 1_000_000
    }
}

/**
 * The desktop's [Transcoder]: the user's ffmpeg as its own process, reading the video from a
 * [LoopbackStream] (127.0.0.1 alone, ranges, so it seeks in an MP4 with its index at the end) and
 * writing the cast stream on its standard output, copied to the receiver's request. Progress comes
 * from `-progress pipe:2`. A hardware encoder that fails before its first byte (an NVENC build on a
 * computer without the card) falls to the next one; HDR tone mapping that fails falls to none.
 * [cancel], the receiver hanging up, or an error kills the process and closes its loopback.
 */
class FfmpegTranscoder(
    private val ffmpeg: DesktopFfmpeg,
    private val launcher: ProcessLauncher = ProcessLauncher.Default,
    /** Serves the video to ffmpeg; the tests give a fixed URL. */
    private val serve: (TranscodeJob) -> Pair<String, AutoCloseable> = { job ->
        val loop = LoopbackStream.start(job.source, null, "application/octet-stream", "video")
        loop.url to loop
    },
) : Transcoder {

    private val cancelled = AtomicBoolean(false)

    @Volatile
    private var process: Process? = null

    override fun write(job: TranscodeJob, out: OutputStream, made: (Long) -> Unit) {
        if (cancelled.get()) throw IOException("Cancelled")
        val (url, input) = serve(job)
        try {
            val target = job.route.target
            val order = Ffmpeg.h264Order(ffmpeg.encoders).let { all -> ffmpeg.working?.let { w -> listOf(w) + (all - w) } ?: all }
            if (order.isEmpty()) throw IOException("No H.264 encoder in this ffmpeg")
            // The picture passed through needs no encoder: one try.
            val encoders = if (target.copyVideo) order.take(1) else order
            var toneMap = target.toneMap
            var i = 0
            while (i < encoders.size) {
                val encoder = encoders[i]
                val attempt = run(Ffmpeg.args(ffmpeg.executable.path, url, job, encoder, toneMap), out, made)
                if (cancelled.get()) throw IOException("Cancelled")
                if (attempt.exit == 0) {
                    if (!target.copyVideo) ffmpeg.working = encoder
                    return
                }
                val why = attempt.error ?: "exit ${attempt.exit}"
                if (attempt.bytes > 0) throw IOException("ffmpeg stopped: $why")
                if (toneMap && (why.contains("zscale", true) || why.contains("tonemap", true) || why.contains("No such filter", true))) {
                    Logger.i(TAG, "No tone mapping in this ffmpeg ($why); converting without it")
                    toneMap = false
                    continue
                }
                Logger.i(TAG, "$encoder failed before its first byte ($why)")
                i++
                if (i == encoders.size) throw IOException("ffmpeg could not convert: $why")
            }
        } finally {
            process?.let { kill(it) }
            process = null
            runCatching { input.close() }
        }
    }

    private class Attempt(val exit: Int, val bytes: Long, val error: String?)

    private fun run(args: List<String>, out: OutputStream, made: (Long) -> Unit): Attempt {
        Logger.i(TAG, "Running ${args.drop(1).joinToString(" ")}")
        val p = launcher.start(args)
        process = p
        if (cancelled.get()) kill(p)
        runCatching { p.outputStream.close() }
        val progress = FfmpegProgress()
        val reader = thread(name = "ffmpeg-progress", isDaemon = true) {
            runCatching {
                p.errorStream.bufferedReader().useLines { lines ->
                    lines.forEach { line -> progress.feed(line)?.let { u -> made(u.outMs) } }
                }
            }
        }
        var bytes = 0L
        try {
            p.inputStream.use { input ->
                val buf = ByteArray(COPY_BUFFER)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    bytes += n
                }
            }
            out.flush()
        } catch (e: IOException) {
            kill(p)
            reader.join(1_000)
            // The process's own pipe closed by a cancel is a cancel; the receiver's is its hang up.
            if (cancelled.get()) throw IOException("Cancelled", e)
            throw e
        }
        if (!p.waitFor(EXIT_WAIT_S, TimeUnit.SECONDS)) kill(p)
        reader.join(1_000)
        val exit = runCatching { p.exitValue() }.getOrDefault(-1)
        return Attempt(exit, bytes, progress.lastError)
    }

    override fun cancel() {
        cancelled.set(true)
        process?.let { kill(it) }
    }

    private fun kill(p: Process) {
        runCatching { p.destroy() }
        runCatching { if (!p.waitFor(1, TimeUnit.SECONDS)) p.destroyForcibly() }
    }

    private companion object {
        const val TAG = "FfmpegTranscoder"
        const val COPY_BUFFER = 64 * 1024
        const val EXIT_WAIT_S = 10L
    }
}
