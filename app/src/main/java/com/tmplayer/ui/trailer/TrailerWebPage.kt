package com.tmplayer.ui.trailer

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color as AndroidColor
import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import com.tmplayer.online.TrailerEmbed

/**
 * What the trailer dialog needs of the page playing the trailer, so the dialog, its keys and its
 * state can be tested without a real WebView ([TrailerWebPage] is the real one).
 */
interface TrailerPage {
    /** The view to show: the WebView, and YouTube's full screen view over it while that is up. */
    val view: View

    /** Runs one of [TrailerEmbed]'s scripts ([TrailerEmbed.TOGGLE], [TrailerEmbed.seekBy]) in the page. */
    fun run(script: String)

    /** The app went to the background: the trailer pauses and the page stops working. */
    fun pause()

    /** Back in the foreground. The trailer stays paused until the viewer plays it. */
    fun resume()

    /** Leaves YouTube's full screen view; false when it was not up. */
    fun exitFullscreen(): Boolean

    /** Stops everything and frees the page. Called once, when the dialog closes. */
    fun destroy()
}

/** What the page tells the dialog. Called on the main thread. */
interface TrailerPageEvents {
    /** One message from the page ([TrailerEmbed.parseMessage] reads it). */
    fun onMessage(raw: String)

    /** YouTube's own full screen view came up ([shown]) or went. */
    fun onFullscreen(shown: Boolean) {}

    /** A link in YouTube's player the viewer followed (the title, "Watch on YouTube"): it opens outside. */
    fun onLeave(url: String)
}

/** Makes the page for a trailer; throws when the system's WebView cannot be created. */
fun interface TrailerPageFactory {
    fun create(context: Context, html: String, remote: Boolean, events: TrailerPageEvents): TrailerPage
}

/**
 * The trailer in a WebView: [TrailerEmbed.pageHtml] loaded with [TrailerEmbed.BASE_ORIGIN] as its
 * base URL, so YouTube gets a referrer and an origin (a bare data page gets its error 153), and
 * the page's messages arriving through [TrailerEmbed.BRIDGE].
 *
 * Locked down to what the embed needs: no file or content access, no mixed content, no other
 * windows, and no navigation of the page itself; a link YouTube's player opens goes outside
 * ([TrailerNavigation]). The bridge only takes the page's short messages, which are parsed as
 * outside data, so YouTube's frame reaching it can do nothing but report a state.
 *
 * Under a remote ([remote]) neither the WebView nor YouTube's full screen view takes focus: the
 * dialog keeps every key and drives the player through [run].
 */
@SuppressLint("ViewConstructor")
internal class TrailerWebPage(
    context: Context,
    html: String,
    private val remote: Boolean,
    private val events: TrailerPageEvents,
) : TrailerPage {

    private val frame = FrameLayout(context).apply {
        setBackgroundColor(AndroidColor.BLACK)
        if (remote) {
            descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
            isFocusable = false
        }
    }

    private var fullscreen: View? = null
    private var fullscreenCallback: WebChromeClient.CustomViewCallback? = null
    private var destroyed = false

    // Throws (MissingWebViewPackageException, AndroidRuntimeException) when the WebView package is
    // missing, disabled or being updated: the caller falls back to the external open.
    private val web: WebView = WebView(context)

    override val view: View get() = frame

    init {
        configure(web, remote)
        web.webViewClient = TrailerClient(web.settings.userAgentString, events)
        web.webChromeClient = FullscreenClient()
        web.addJavascriptInterface(Bridge(web, events), TrailerEmbed.BRIDGE)
        frame.addView(web, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        // Our own page, with the embed's origin as its base: the only page the bridge is given to.
        web.loadDataWithBaseURL(TrailerEmbed.BASE_ORIGIN + "/", html, "text/html", "utf-8", null)
    }

    override fun run(script: String) {
        if (!destroyed) runCatching { web.evaluateJavascript(script, null) }
    }

    override fun pause() {
        if (destroyed) return
        run(TrailerEmbed.PAUSE)
        runCatching { web.onPause() }
    }

    override fun resume() {
        if (!destroyed) runCatching { web.onResume() }
    }

    override fun exitFullscreen(): Boolean {
        val callback = fullscreenCallback ?: return false
        hideFullscreen()
        runCatching { callback.onCustomViewHidden() }
        return true
    }

    override fun destroy() {
        if (destroyed) return
        destroyed = true
        hideFullscreen()
        runCatching {
            web.stopLoading()
            web.removeJavascriptInterface(TrailerEmbed.BRIDGE)
            web.loadUrl("about:blank")
            web.onPause()
            frame.removeAllViews()
            web.destroy()
        }
    }

    private fun hideFullscreen() {
        val shown = fullscreen ?: return
        frame.removeView(shown)
        fullscreen = null
        fullscreenCallback = null
        web.visibility = View.VISIBLE
        if (!destroyed) events.onFullscreen(false)
    }

    /** YouTube's full screen button: its view over the page, inside the dialog, which already fills the screen. */
    private inner class FullscreenClient : WebChromeClient() {
        override fun onShowCustomView(view: View, callback: CustomViewCallback) {
            if (destroyed) {
                callback.onCustomViewHidden()
                return
            }
            hideFullscreen()
            if (remote && view is ViewGroup) view.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
            if (remote) view.isFocusable = false
            view.setBackgroundColor(AndroidColor.BLACK)
            frame.addView(view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            web.visibility = View.INVISIBLE
            fullscreen = view
            fullscreenCallback = callback
            events.onFullscreen(true)
        }

        override fun onHideCustomView() = hideFullscreen()
    }

    private class Bridge(private val web: WebView, private val events: TrailerPageEvents) {
        /** Called on WebView's own thread: handed to the main one. */
        @JavascriptInterface
        fun post(message: String?) {
            val text = message?.take(MAX_MESSAGE) ?: return
            web.post { events.onMessage(text) }
        }
    }

    companion object {
        /** Longer than any message the page sends; the rest is never read. */
        private const val MAX_MESSAGE = 64

        /** The settings the trailer needs, and none of what a page loaded from the web should have. */
        @SuppressLint("SetJavaScriptEnabled")
        internal fun configure(web: WebView, remote: Boolean) {
            web.setBackgroundColor(AndroidColor.BLACK)
            if (remote) {
                web.isFocusable = false
                web.isFocusableInTouchMode = false
            }
            web.settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                // The tap on the trailer button was the gesture; the page starts the player once it is ready.
                mediaPlaybackRequiresUserGesture = false
                allowFileAccess = false
                allowContentAccess = false
                @Suppress("DEPRECATION")
                allowFileAccessFromFileURLs = false
                @Suppress("DEPRECATION")
                allowUniversalAccessFromFileURLs = false
                mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                setSupportMultipleWindows(false)
                javaScriptCanOpenWindowsAutomatically = false
                setGeolocationEnabled(false)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) safeBrowsingEnabled = true
            }
        }
    }
}

/** The real page, for [TrailerPlayerHost]. */
val WebTrailerPages = TrailerPageFactory { context, html, remote, events -> TrailerWebPage(context, html, remote, events) }

/**
 * Where the trailer's WebView may go. The page itself is loaded by us and never navigates: a link
 * YouTube's player follows in the top frame (its title, "Watch on YouTube") is handed to the
 * external open when it is YouTube's, and dropped otherwise. YouTube's own frame may move between
 * YouTube's hosts and nowhere else.
 */
object TrailerNavigation {

    private val HOSTS = setOf("youtube-nocookie.com", "youtube.com", "youtu.be")
    private const val MAX_URL = 4096

    /** True when [url] is https on one of YouTube's hosts or a subdomain of one. */
    fun isYouTube(url: String?): Boolean {
        if (url == null || url.length > MAX_URL) return false
        val uri = runCatching { java.net.URI(url) }.getOrNull() ?: return false
        if (uri.scheme?.lowercase() != "https") return false
        val host = uri.host?.lowercase() ?: return false
        return HOSTS.any { host == it || host.endsWith(".$it") }
    }

    /** Whether the WebView itself loads [url]: only a YouTube frame inside our page, never the page. */
    fun allows(url: String?, mainFrame: Boolean): Boolean = !mainFrame && isYouTube(url)
}

/**
 * Keeps the page where [TrailerNavigation] says, and steers the embed to H.264.
 *
 * The embed's page is fetched here with a script at the top of its head that reports VP9 and AV1
 * as unsupported, so the player picks H.264, which every device decodes. Seen on a 2020 Mi TV
 * (Android 9, WebView 81): the Amlogic VP9 decoder failed with "video decode error!" and the player
 * went on with the audio alone. Trailers are short and at most 1080p, which H.264 covers, so the
 * choice is made everywhere rather than guessed per device. Anything that goes wrong fetching the
 * page falls back to WebView's own request.
 */
private class TrailerClient(private val userAgent: String, private val events: TrailerPageEvents) : WebViewClient() {

    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
        decide(request.url?.toString(), request.isForMainFrame)

    @Deprecated("Android 6 calls this one")
    override fun shouldOverrideUrlLoading(view: WebView, url: String?): Boolean =
        // Android 6 does not say which frame: anything but YouTube is refused, and YouTube's links go outside.
        decide(url, mainFrame = true)

    private fun decide(url: String?, mainFrame: Boolean): Boolean {
        if (TrailerNavigation.allows(url, mainFrame)) return false
        if (url != null && TrailerNavigation.isYouTube(url)) events.onLeave(url)
        return true
    }

    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
        val url = request.url?.toString() ?: return null
        if (request.method != "GET" || !url.startsWith(EMBED)) return null
        return runCatching {
            val connection = (java.net.URL(url).openConnection() as java.net.HttpURLConnection).apply {
                connectTimeout = 10_000
                readTimeout = 10_000
                instanceFollowRedirects = false
                // Read here rather than from the view, which may not be touched off the main thread.
                setRequestProperty("User-Agent", userAgent)
                request.requestHeaders.forEach { (name, value) -> setRequestProperty(name, value) }
                // WebView leaves the referrer out of the headers it hands over here, and YouTube
                // answers an embed asked for without one with its configuration error (153).
                if (request.requestHeaders.keys.none { it.equals("Referer", ignoreCase = true) }) {
                    setRequestProperty("Referer", TrailerEmbed.BASE_ORIGIN + "/")
                }
                setRequestProperty("Accept-Encoding", "identity")
            }
            try {
                if (connection.responseCode != 200) return@runCatching null
                // A trailer's embed page is a few hundred kilobytes; anything far larger is not it.
                if (connection.contentLength > MAX_EMBED_BYTES) return@runCatching null
                val html = connection.inputStream.bufferedReader().use { reader ->
                    val text = CharArray(MAX_EMBED_BYTES)
                    var read = 0
                    while (read < text.size) {
                        val n = reader.read(text, read, text.size - read)
                        if (n < 0) break
                        read += n
                    }
                    if (read >= text.size) return@runCatching null
                    String(text, 0, read)
                }
                val head = html.indexOf("<head>")
                if (head < 0) return@runCatching null
                val patched = html.substring(0, head + 6) + "<script>$NO_VP9</script>" + html.substring(head + 6)
                WebResourceResponse("text/html", "utf-8", patched.byteInputStream())
            } finally {
                connection.disconnect()
            }
        }.getOrNull()
    }

    private companion object {
        const val EMBED = TrailerEmbed.HOST + "/embed/"
        const val MAX_EMBED_BYTES = 4 * 1024 * 1024

        const val NO_VP9 = """(function(){var no=/vp0?9|av01/i;var M=window.MediaSource;
if(M&&M.isTypeSupported){var t=M.isTypeSupported.bind(M);M.isTypeSupported=function(x){return no.test(x)?false:t(x);};}
var P=HTMLMediaElement.prototype,c=P.canPlayType;P.canPlayType=function(x){return no.test(x)?'':c.call(this,x);};})();"""
    }
}
