package com.tmplayer.desktop.trailer

import com.tmplayer.desktop.os.OsInfo
import com.tmplayer.i18n.L
import com.tmplayer.online.TrailerEmbed
import com.tmplayer.platform.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.friwi.jcefmaven.CefAppBuilder
import me.friwi.jcefmaven.impl.step.check.CefInstallationChecker
import org.cef.CefApp
import org.cef.CefSettings
import org.cef.browser.CefBrowser
import org.cef.browser.CefFrame
import org.cef.browser.CefMessageRouter
import org.cef.callback.CefCallback
import org.cef.callback.CefContextMenuParams
import org.cef.callback.CefMenuModel
import org.cef.callback.CefQueryCallback
import org.cef.handler.CefContextMenuHandlerAdapter
import org.cef.handler.CefKeyboardHandler
import org.cef.handler.CefKeyboardHandlerAdapter
import org.cef.handler.CefLifeSpanHandlerAdapter
import org.cef.handler.CefLoadHandler
import org.cef.handler.CefLoadHandlerAdapter
import org.cef.handler.CefMessageRouterHandlerAdapter
import org.cef.handler.CefRequestHandlerAdapter
import org.cef.handler.CefResourceHandler
import org.cef.handler.CefResourceHandlerAdapter
import org.cef.handler.CefResourceRequestHandler
import org.cef.handler.CefResourceRequestHandlerAdapter
import org.cef.misc.BoolRef
import org.cef.misc.IntRef
import org.cef.misc.StringRef
import org.cef.network.CefRequest
import org.cef.network.CefResponse
import java.io.File

/**
 * The real [TrailerEngine]: Chromium through JCEF (jcefmaven's loader), downloaded by
 * [EngineInstaller] on the first trailer. [native] is null on a computer it has no build for.
 */
class CefTrailerEngine(
    private val native: CefNative? = CefNatives.forSystem(),
    places: () -> List<File>,
) : TrailerEngine {

    private val installer: EngineInstaller? = native?.let { EngineInstaller(it, places) }

    override fun status(): EngineStatus {
        val installer = installer ?: return EngineStatus.Unsupported
        if (CefRuntime.failed) return EngineStatus.StartFailed
        val dir = installer.installed()
        return if (dir != null) EngineStatus.Installed(dir) else EngineStatus.Missing(installer.offer())
    }

    override suspend fun install(offer: EngineOffer.Fits, onProgress: (EngineProgress) -> Unit): File =
        (installer ?: throw java.io.IOException("no trailer player for this computer")).install(offer, onProgress)

    override suspend fun start(dir: File): Boolean = withContext(Dispatchers.IO) {
        if (CefRuntime.app == null && !CefProbe.startsHere(dir)) {
            CefRuntime.markFailed()
            return@withContext false
        }
        CefRuntime.start(dir) != null
    }

    override fun browser(html: String, listener: TrailerBrowser.Listener): TrailerBrowser {
        val app = CefRuntime.app ?: throw IllegalStateException("Chromium has not started")
        return CefTrailerBrowser(app, html, listener)
    }
}

/**
 * Chromium for this process: started once, from the first trailer, and kept until TMPlayer quits
 * (CEF cannot start again in a process once it has stopped). A start that fails is not tried again
 * in this run.
 */
internal object CefRuntime {
    @Volatile
    var app: CefApp? = null
        private set

    @Volatile
    var failed = false
        private set

    /** The child check ([CefProbe]) said Chromium does not start here: not tried again in this run. */
    fun markFailed() {
        failed = true
    }

    /** Starts Chromium from [dir] with its profile in [profile]. Call only once [CefProbe.startsHere] said yes. */
    @Synchronized
    fun start(dir: File, profile: File = File(dir.parentFile ?: dir, "profile")): CefApp? {
        app?.let { return it }
        if (failed) return null
        return try {
            // Ours checked the files; jcefmaven would otherwise fetch its own, unchecked.
            if (!CefInstallationChecker.checkInstallation(dir)) throw IllegalStateException("not a complete install: ${dir.path}")
            val builder = CefAppBuilder()
            builder.setInstallDir(dir)
            builder.setSkipInstallation(true)
            builder.setProgressHandler { _, _ -> }
            builder.cefSettings.apply {
                windowless_rendering_enabled = false
                // The profile beside the install, nothing in the OS's shared CEF folder, and no
                // cache_path: cookies and storage stay in memory and go when TMPlayer quits.
                root_cache_path = profile.absolutePath
                persist_session_cookies = false
                // No log by default (CEF would write debug.log into the working folder);
                // -Dtmplayer.cefLog=<file> for one while looking into a problem.
                val logFile = System.getProperty("tmplayer.cefLog")?.takeIf { it.isNotBlank() }
                log_severity = if (logFile != null) CefSettings.LogSeverity.LOGSEVERITY_INFO else CefSettings.LogSeverity.LOGSEVERITY_DISABLE
                if (logFile != null) log_file = logFile
                locale = L.messages.tag
                background_color = this.ColorType(255, 0, 0, 0)
            }
            // The press on the trailer button was the gesture; the page starts the trailer itself.
            builder.addJcefArgs("--autoplay-policy=no-user-gesture-required")
            // The browser is a child of AWT's X11 window, also under Wayland (XWayland).
            if (OsInfo.isLinux) builder.addJcefArgs("--ozone-platform=x11")
            builder.build().also { app = it }
        } catch (e: Throwable) {
            failed = true
            Logger.w("CefRuntime", "Chromium did not start: ${e::class.simpleName}: ${e.message}")
            null
        }
    }
}

/**
 * One trailer page in a windowed Chromium browser.
 *
 * The page is [TrailerEmbed.pageHtml], served for [PAGE_URL] by this browser itself, so it loads
 * with YouTube's privacy enhanced host as its origin (a bare data page gets error 153). The page
 * talks through `cefQuery` ([CefMessageRouter]), from its own frame only; the keys the sheet answers
 * are taken before the page sees them, so Space never reaches YouTube's own shortcut as well.
 * Nothing else may load in the main frame, popups are refused, and the right click menu is empty.
 */
internal class CefTrailerBrowser(app: CefApp, html: String, private val listener: TrailerBrowser.Listener) : TrailerBrowser {

    private val page = html.toByteArray(Charsets.UTF_8)
    private val client = app.createClient()
    private val router = CefMessageRouter.create()
    private val browser: CefBrowser

    override val component: java.awt.Component? get() = browser.uiComponent

    init {
        router.addHandler(
            object : CefMessageRouterHandlerAdapter() {
                override fun onQuery(
                    browser: CefBrowser?,
                    frame: CefFrame?,
                    queryId: Long,
                    request: String?,
                    persistent: Boolean,
                    callback: CefQueryCallback?,
                ): Boolean {
                    if (frame?.isMain != true) return false
                    runCatching { callback?.success("") }
                    listener.onMessage(request)
                    return true
                }
            },
            true,
        )
        client.addMessageRouter(router)
        client.addKeyboardHandler(
            object : CefKeyboardHandlerAdapter() {
                override fun onPreKeyEvent(browser: CefBrowser?, event: CefKeyboardHandler.CefKeyEvent?, isKeyboardShortcut: BoolRef?): Boolean {
                    event ?: return false
                    if (event.modifiers and MODIFIERS != 0) return false
                    val key = when (event.windows_key_code) {
                        VK_SPACE -> TrailerKey.Toggle
                        VK_ESCAPE -> TrailerKey.Close
                        VK_LEFT -> TrailerKey.Back
                        VK_RIGHT -> TrailerKey.Forward
                        else -> return false
                    }
                    if (event.type == CefKeyboardHandler.CefKeyEvent.EventType.KEYEVENT_RAWKEYDOWN) listener.onKey(key)
                    // The press, its character and its release: none reach the page.
                    return true
                }
            },
        )
        client.addRequestHandler(
            object : CefRequestHandlerAdapter() {
                override fun onBeforeBrowse(browser: CefBrowser?, frame: CefFrame?, request: CefRequest?, userGesture: Boolean, isRedirect: Boolean): Boolean =
                    frame?.isMain == true && request?.url != PAGE_URL

                override fun getResourceRequestHandler(
                    browser: CefBrowser?,
                    frame: CefFrame?,
                    request: CefRequest?,
                    isNavigation: Boolean,
                    isDownload: Boolean,
                    requestInitiator: String?,
                    disableDefaultHandling: BoolRef?,
                ): CefResourceRequestHandler? = if (frame?.isMain == true && request?.url == PAGE_URL) PageRequest(page) else null
            },
        )
        client.addLifeSpanHandler(
            object : CefLifeSpanHandlerAdapter() {
                override fun onBeforePopup(browser: CefBrowser?, frame: CefFrame?, targetUrl: String?, targetFrameName: String?): Boolean = true
            },
        )
        client.addLoadHandler(
            object : CefLoadHandlerAdapter() {
                override fun onLoadError(browser: CefBrowser?, frame: CefFrame?, errorCode: CefLoadHandler.ErrorCode?, errorText: String?, failedUrl: String?) {
                    // A refused navigation away is ERR_ABORTED; that is not the page failing.
                    if (frame?.isMain == true && errorCode != CefLoadHandler.ErrorCode.ERR_ABORTED) listener.onLoadFailed()
                }
            },
        )
        client.addContextMenuHandler(
            object : CefContextMenuHandlerAdapter() {
                override fun onBeforeContextMenu(browser: CefBrowser?, frame: CefFrame?, params: CefContextMenuParams?, model: CefMenuModel?) {
                    model?.clear()
                }
            },
        )
        // Windowed: the native browser is made when its component joins the window (SwingPanel).
        browser = client.createBrowser(PAGE_URL, false, false)
    }

    override fun run(script: String) {
        browser.executeJavaScript(script, PAGE_URL, 0)
    }

    override fun dispose() {
        // Stop the sound first: closing a windowed browser can take a moment on some systems.
        runCatching { browser.executeJavaScript(TrailerEmbed.PAUSE, PAGE_URL, 0) }
        runCatching { client.removeMessageRouter(router) }
        runCatching { router.dispose() }
        runCatching { browser.close(true) }
        runCatching { client.dispose() }
    }

    /** Serves the page for [PAGE_URL] from memory. */
    private class PageRequest(private val page: ByteArray) : CefResourceRequestHandlerAdapter() {
        override fun getResourceHandler(browser: CefBrowser?, frame: CefFrame?, request: CefRequest?): CefResourceHandler = Page(page)
    }

    private class Page(private val page: ByteArray) : CefResourceHandlerAdapter() {
        private var offset = 0

        override fun processRequest(request: CefRequest?, callback: CefCallback?): Boolean {
            callback?.Continue()
            return true
        }

        override fun getResponseHeaders(response: CefResponse?, responseLength: IntRef?, redirectUrl: StringRef?) {
            response?.mimeType = "text/html"
            response?.status = 200
            response?.setHeaderByName("Cache-Control", "no-store", true)
            responseLength?.set(page.size)
        }

        override fun readResponse(dataOut: ByteArray?, bytesToRead: Int, bytesRead: IntRef?, callback: CefCallback?): Boolean {
            val out = dataOut ?: return false
            val left = page.size - offset
            if (left <= 0) {
                bytesRead?.set(0)
                return false
            }
            val n = minOf(left, bytesToRead, out.size)
            System.arraycopy(page, offset, out, 0, n)
            offset += n
            bytesRead?.set(n)
            return true
        }
    }

    companion object {
        /** A path YouTube's host does not serve; this browser answers it with the page. */
        val PAGE_URL = "${TrailerEmbed.BASE_ORIGIN}/tmplayer-trailer.html"

        private const val VK_SPACE = 0x20
        private const val VK_ESCAPE = 0x1B
        private const val VK_LEFT = 0x25
        private const val VK_RIGHT = 0x27

        /** Shift, Ctrl, Alt and Cmd (CEF's EVENTFLAG_*): a chord is the page's or the system's. */
        private const val MODIFIERS = (1 shl 1) or (1 shl 2) or (1 shl 3) or (1 shl 7)
    }
}
