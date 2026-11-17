package com.tmplayer.desktop.trailer

import com.tmplayer.platform.Logger
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Whether Chromium starts on this computer, tried in a separate TMPlayer process before the first
 * in-app trailer.
 *
 * JCEF starts Chromium on AWT's event thread, and on some systems (seen on Fedora 43 under sway,
 * Wayland with XWayland, 2026-11-17, and reported for other JCEF apps on Fedora Wayland) that call
 * never returns: in TMPlayer's own process it would freeze the window for good. So the first start
 * is tried in a child process with a time limit; only a child that started Chromium and ended lets
 * the app start it in its own process. The answer is kept beside the install ([OK] for good, [FAILED]
 * for [FAILED_FOR_MS]), so the check costs a few seconds once, not on every trailer.
 */
object CefProbe {

    /** The argument that makes TMPlayer's `main` run [child] instead of the app. */
    const val ARG = "--trailer-probe"

    const val OK = "probe.ok"
    const val FAILED = "probe.failed"

    /** How long the child may take to start Chromium. A healthy start is one to three seconds. */
    const val TIMEOUT_MS = 20_000L

    /** A failed check is believed for a week, then tried again (a different session, an update). */
    const val FAILED_FOR_MS = 7L * 24 * 60 * 60 * 1000

    private const val SAID_OK = "TMPLAYER_CEF_STARTED"

    /** True when Chromium from [dir] is known to start here, checking in a child process the first time. */
    fun startsHere(dir: File, now: Long = System.currentTimeMillis(), run: (List<String>) -> Boolean = ::runChild): Boolean {
        val ok = File(dir, OK)
        val failed = File(dir, FAILED)
        if (ok.isFile) return true
        if (failed.isFile && now - failed.lastModified() in 0 until FAILED_FOR_MS) return false
        val command = command(dir) ?: return false.also { Logger.w(TAG, "no way to start a child process") }
        val started = run(command)
        // The child is ended as soon as it answers, before it can tidy its own profile.
        runCatching { profileOf(dir).deleteRecursively() }
        runCatching {
            if (started) {
                failed.delete()
                ok.writeText("${System.getProperty("os.name")} ${System.getProperty("os.version")}\n")
            } else {
                failed.writeText("${System.getProperty("os.name")} ${System.getProperty("os.version")}\n")
            }
        }
        return started
    }

    /**
     * How to start this same TMPlayer as the child: the packaged app's own launcher with [ARG], or,
     * for a JVM started by hand or by Gradle, that `java` with this class path and the main class.
     */
    internal fun command(
        dir: File,
        launcher: String? = ProcessHandle.current().info().command().orElse(null),
        classPath: String? = System.getProperty("java.class.path"),
        jvmOptions: List<String> = java.lang.management.ManagementFactory.getRuntimeMXBean().inputArguments,
    ): List<String>? {
        launcher ?: return null
        val name = File(launcher).name.lowercase()
        val isJava = name == "java" || name == "java.exe" || name == "javaw.exe"
        return if (isJava) {
            if (classPath.isNullOrBlank()) return null
            listOf(launcher) + jvmOptions.filter { it.startsWith("--add-opens") || it.startsWith("--add-exports") } +
                listOf("-cp", classPath, "com.tmplayer.desktop.MainKt", ARG, dir.absolutePath)
        } else {
            listOf(launcher, ARG, dir.absolutePath)
        }
    }

    private fun runChild(command: List<String>): Boolean {
        val process = runCatching {
            ProcessBuilder(command).redirectErrorStream(true).redirectInput(ProcessBuilder.Redirect.PIPE).start()
        }.getOrElse {
            Logger.w(TAG, "could not start the check: ${it.message}")
            return false
        }
        // Done at the child's word or its end, whichever comes first; its output is otherwise dropped.
        val answer = java.util.concurrent.CompletableFuture<Boolean>()
        Thread {
            runCatching {
                process.inputStream.bufferedReader().useLines { lines -> lines.forEach { if (it.contains(SAID_OK)) answer.complete(true) } }
            }
            answer.complete(false)
        }.apply { isDaemon = true; start() }
        val started = runCatching { answer.get(TIMEOUT_MS, TimeUnit.MILLISECONDS) }.getOrDefault(false)
        if (!started) Logger.w(TAG, "Chromium did not start within ${TIMEOUT_MS / 1000} s here; trailers open in YouTube")
        // Chromium's helpers first, then the child: nothing of the check stays running.
        runCatching { process.descendants().forEach { it.destroyForcibly() } }
        process.destroyForcibly()
        return started
    }

    /** The child: start Chromium from [args]' folder, say so, and end. Never shows a window. */
    fun child(args: Array<String>): Int {
        val dir = args.getOrNull(args.indexOf(ARG) + 1)?.let(::File) ?: return 2
        val profile = profileOf(dir)
        // The parent holds this process's input open; when TMPlayer ends, so does the check, hung or not.
        Thread {
            runCatching { while (System.`in`.read() >= 0) Unit }
            Runtime.getRuntime().halt(3)
        }.apply { isDaemon = true; start() }
        // And a check that hangs past the parent's patience ends itself too.
        Thread {
            Thread.sleep(TIMEOUT_MS + 5_000)
            Runtime.getRuntime().halt(4)
        }.apply { isDaemon = true; start() }
        val code = try {
            val app = CefRuntime.start(dir, profile)
            if (app == null) {
                1
            } else {
                // The first client is what runs Chromium's start (CefApp.initialize, on AWT's thread).
                app.createClient()
                println(SAID_OK)
                System.out.flush()
                0
            }
        } catch (e: Throwable) {
            println("check failed: ${e::class.simpleName}: ${e.message}")
            1
        }
        runCatching { profile.deleteRecursively() }
        // No shutdown: the parent ends this process and Chromium's helpers as soon as it has the answer.
        Runtime.getRuntime().halt(code)
        return code
    }

    /** The child's own Chromium profile, apart from the app's, and removed after the check. */
    private fun profileOf(dir: File) = File(dir.parentFile ?: dir, "probe-profile")

    private const val TAG = "CefProbe"
}
