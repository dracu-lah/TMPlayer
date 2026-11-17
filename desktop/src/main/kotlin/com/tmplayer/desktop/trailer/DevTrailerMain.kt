package com.tmplayer.desktop.trailer

import androidx.compose.foundation.layout.fillMaxSize
import com.tmplayer.online.TrailerEmbed
import com.tmplayer.online.TrailerPageLabels
import kotlinx.coroutines.runBlocking
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.swing.JFrame
import javax.swing.SwingUtilities
import kotlin.system.exitProcess

/**
 * The trailer player on its own, for checking it on a real Windows, Linux or Mac without signing in:
 * `./gradlew :desktop:runTrailerSmoke --args="--dir <empty folder> [--natives <jcef-natives jar>] [--key <id>]"`.
 *
 * It installs Chromium into `--dir` the way the app does (the pinned SHA-256 included; `--natives`
 * reads an already downloaded jar instead of the network), starts it, plays the trailer in a window
 * and prints each message from the page. It ends 0 once the page says the trailer plays
 * (`state:1`), and 1 on an error message, a failed install or start, or nothing within 40 s.
 */
fun main(args: Array<String>) {
    fun arg(name: String) = args.indexOf(name).takeIf { it >= 0 }?.let { args.getOrNull(it + 1) }
    val dir = File(arg("--dir") ?: return usage())
    val key = arg("--key") ?: "aqz-KE-bpKQ"
    val jar = arg("--natives")?.let(::File)
    val native = CefNatives.forSystem() ?: run {
        println("no Chromium for ${System.getProperty("os.name")} ${System.getProperty("os.arch")}")
        exitProcess(1)
    }
    val downloader = if (jar == null) EngineDownloader.Http else EngineDownloader { _, sink, onBytes ->
        jar.inputStream().use { it.copyTo(sink) }
        onBytes(jar.length())
    }
    val installer = EngineInstaller(native, { listOf(dir) }, downloader = downloader)
    val installed = installer.installed() ?: runBlocking {
        val offer = installer.offer()
        println("offer: $offer")
        if (offer !is EngineOffer.Fits) exitProcess(1)
        val started = System.nanoTime()
        installer.install(offer) { }.also { println("installed in ${(System.nanoTime() - started) / 1_000_000} ms: $it") }
    }
    val checked = System.nanoTime()
    if (!CefProbe.startsHere(installed)) {
        println("Chromium does not start here (the child check), see ${File(installed, CefProbe.FAILED)}")
        exitProcess(1)
    }
    println("child check passed in ${(System.nanoTime() - checked) / 1_000_000} ms")
    val app = CefRuntime.start(installed) ?: run {
        println("Chromium did not start")
        exitProcess(1)
    }
    println("Chromium ${app.version}")
    if ("--compose" in args) composeSmoke(installed, key)
    val html = TrailerEmbed.pageHtml(key, TrailerPageLabels("Trailer", "Loading")) ?: return usage()
    val done = CountDownLatch(1)
    var code = 1
    val listener = object : TrailerBrowser.Listener {
        override fun onMessage(raw: String?) {
            println("page: $raw")
            when {
                raw == "state:1" -> { code = 0; done.countDown() }
                raw?.startsWith("error:") == true -> done.countDown()
            }
        }
        override fun onKey(key: TrailerKey) = println("key: $key")
        override fun onLoadFailed() {
            println("page did not load")
            done.countDown()
        }
    }
    lateinit var browser: CefTrailerBrowser
    lateinit var frame: JFrame
    SwingUtilities.invokeAndWait {
        browser = CefTrailerBrowser(app, html, listener)
        frame = JFrame("TMPlayer trailer smoke").apply {
            defaultCloseOperation = JFrame.DISPOSE_ON_CLOSE
            browser.component?.let { contentPane.add(it) }
            setSize(960, 600)
            isVisible = true
        }
    }
    if (!done.await(40, TimeUnit.SECONDS)) println("no state:1 within 40 s")
    SwingUtilities.invokeAndWait {
        browser.dispose()
        frame.dispose()
    }
    println(if (code == 0) "OK" else "FAILED")
    exitProcess(code)
}

/**
 * `--compose`: the app's own path, in a Compose window: the trailer button's [DesktopTrailers], the
 * sheet with the browser in a SwingPanel inside the dialog, and the bridge's scripts. It presses
 * the trailer, waits for it to play, sends Space (pause) and Right through [DesktopTrailers.key],
 * waits for the page to say paused, closes the sheet, and ends 0.
 */
private fun composeSmoke(installed: File, key: String) {
    val engine = object : TrailerEngine {
        override fun status(): EngineStatus = EngineStatus.Installed(installed)
        override suspend fun install(offer: EngineOffer.Fits, onProgress: (EngineProgress) -> Unit) = installed
        override suspend fun start(dir: File) = CefRuntime.start(dir) != null
        override fun browser(html: String, listener: TrailerBrowser.Listener): TrailerBrowser =
            CefTrailerBrowser(CefRuntime.app ?: error("not started"), html, object : TrailerBrowser.Listener by listener {
                override fun onMessage(raw: String?) {
                    println("page: $raw")
                    listener.onMessage(raw)
                }
            })
    }
    androidx.compose.ui.window.application(exitProcessOnExit = false) {
        androidx.compose.ui.window.Window(onCloseRequest = ::exitApplication, title = "TMPlayer trailer smoke") {
            val scope = androidx.compose.runtime.rememberCoroutineScope()
            val trailers = androidx.compose.runtime.remember { DesktopTrailers(engine, scope, notify = { println("notice: $it") }) }
            com.tmplayer.ui.theme.TmMaterialTheme(dark = true) {
                androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.fillMaxSize()) {
                    androidx.compose.material3.Text("Detail page")
                    DesktopTrailerHost(trailers, window)
                }
            }
            androidx.compose.runtime.LaunchedEffect(Unit) {
                trailers.open(com.tmplayer.online.MetaTrailer(key)) { println("FALLBACK"); exitProcess(1) }
                val deadline = System.currentTimeMillis() + 40_000
                suspend fun until(what: String, check: () -> Boolean) {
                    while (!check()) {
                        if (System.currentTimeMillis() > deadline) {
                            println("timed out waiting for $what: ${trailers.screen}")
                            exitProcess(1)
                        }
                        kotlinx.coroutines.delay(100)
                    }
                }
                fun phase() = (trailers.screen as? TrailerScreen.Playing)?.state?.phase
                until("playing") { phase() == com.tmplayer.online.TrailerPhase.Playing }
                println("sheet playing; component ${trailers.browser?.component?.let { "${it.width}x${it.height} shown=${it.isShowing}" }}")
                kotlinx.coroutines.delay(2_000)
                trailers.key(TrailerKey.Forward)
                trailers.key(TrailerKey.Toggle)
                until("paused") { phase() == com.tmplayer.online.TrailerPhase.Paused }
                println("paused through the bridge")
                trailers.key(TrailerKey.Close)
                until("closed") { trailers.screen == TrailerScreen.None }
                println("OK compose")
                exitProcess(0)
            }
        }
    }
    exitProcess(1)
}

private fun usage() {
    println("--dir <folder> [--natives <jar>] [--key <youtube id>]")
    exitProcess(2)
}
