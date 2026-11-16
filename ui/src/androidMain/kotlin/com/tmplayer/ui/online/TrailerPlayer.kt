package com.tmplayer.ui.online

import android.annotation.SuppressLint
import android.graphics.Color as AndroidColor
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.tmplayer.online.MetaTrailer
import com.tmplayer.ui.components.isTouch
import com.tmplayer.ui.i18n.LocalStrings
import com.tmplayer.ui.icons.TmIcons
import com.tmplayer.ui.theme.Tone
import kotlinx.coroutines.delay

/**
 * True where the system has a WebView to play YouTube's embedded player in. Some televisions ship
 * without one (or with it disabled); those keep the YouTube app and the QR code.
 */
@Composable
internal fun rememberInAppTrailers(): Boolean {
    val context = androidx.compose.ui.platform.LocalContext.current
    return remember {
        runCatching {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                WebView.getCurrentWebViewPackage() != null
            } else {
                // Android 6 and 7 cannot name the WebView package; the system feature is the
                // nearest answer they give.
                context.packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_WEBVIEW)
            }
        }.getOrDefault(false)
    }
}

/**
 * A trailer over everything, in YouTube's embedded player, so it plays without leaving the app.
 *
 * The player is given a page of its own with tmplayer.org as its origin: YouTube refuses an embed
 * that arrives with no referrer, which is what a bare embed URL in a WebView sends. The page runs
 * YouTube's IFrame player API and reports back through [Bridge]: a spinner stays up until the
 * trailer really plays, and a trailer that is not allowed to play embedded (YouTube's errors 101,
 * 150, 152 and 153), fails, or has not started after [START_TIMEOUT_MS] is said so, with its QR
 * code ([fallback]) and "Watch on YouTube" beside Close, instead of a black screen.
 *
 * On a television the WebView takes no focus, so the remote's keys stay with the two buttons:
 * Close has the focus at once, and Back closes too.
 *
 * The embed is steered to H.264 (see [H264Only]): older televisions decode VP9 in hardware badly
 * enough under WebView that the trailer played as sound over a black screen.
 */
@SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
@Composable
internal fun TrailerPlayer(
    trailer: MetaTrailer,
    onClose: () -> Unit,
    onOpenElsewhere: () -> Unit,
    fallback: @Composable () -> Unit,
) {
    val s = LocalStrings.current
    val tv = !isTouch()
    var phase by remember(trailer.key) { mutableStateOf(TrailerPhase.Loading) }
    LaunchedEffect(trailer.key) {
        delay(START_TIMEOUT_MS)
        if (phase == TrailerPhase.Loading) phase = TrailerPhase.Failed
    }
    val closeFocus = remember { FocusRequester() }
    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        // No background of its own: WebView plays the video on a surface behind the window and
        // leaves a hole in its own drawing for it, and a black box painted under the WebView in
        // the same window covered that hole, so a trailer played as sound over black (POCO F5,
        // WebView 157, 2026-10-22). The page and the WebView are black around the picture.
        Box(Modifier.fillMaxSize()) {
            var view: WebView? = null
            AndroidView(
                factory = { context ->
                    WebView(context).apply {
                        view = this
                        setBackgroundColor(AndroidColor.BLACK)
                        // The remote's keys belong to the buttons over the player (see above).
                        if (tv) {
                            isFocusable = false
                            isFocusableInTouchMode = false
                        }
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.mediaPlaybackRequiresUserGesture = false
                        webChromeClient = WebChromeClient()
                        webViewClient = H264Only(settings.userAgentString)
                        val web = this
                        addJavascriptInterface(
                            Bridge { event ->
                                // Called on WebView's own thread: the answer is handed to the main one.
                                web.post {
                                    when (event) {
                                        "playing" -> phase = TrailerPhase.Playing
                                        "failed" -> if (phase != TrailerPhase.Playing) phase = TrailerPhase.Failed
                                    }
                                }
                            },
                            BRIDGE,
                        )
                        loadDataWithBaseURL(ORIGIN, page(trailer.key), "text/html", "utf-8", null)
                    }
                },
                modifier = Modifier.fillMaxSize(),
            )
            DisposableEffect(Unit) {
                onDispose {
                    view?.apply {
                        removeJavascriptInterface(BRIDGE)
                        loadUrl("about:blank")
                        destroy()
                    }
                }
            }
            when (phase) {
                TrailerPhase.Loading -> CircularProgressIndicator(
                    color = Color.White,
                    strokeWidth = 3.dp,
                    modifier = Modifier.align(Alignment.Center).size(if (tv) 56.dp else 44.dp),
                )
                TrailerPhase.Failed -> Column(
                    Modifier
                        .align(Alignment.Center)
                        .padding(24.dp)
                        .widthIn(max = 560.dp)
                        .clip(RoundedCornerShape(20.dp))
                        .background(Tone.surfaceHigh)
                        .padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Text(s.metadataTrailerUnavailable, style = MaterialTheme.typography.titleMedium, color = Tone.text)
                    fallback()
                }
                TrailerPhase.Playing -> Unit
            }
            Row(
                Modifier.align(Alignment.TopEnd).padding(if (tv) 24.dp else 12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OverlayButton(TmIcons.OpenExternal, s.metadataTrailerOnYoutube, onOpenElsewhere)
                OverlayButton(TmIcons.Close, s.commonClose, onClose, Modifier.focusRequester(closeFocus))
            }
            if (tv) LaunchedEffect(Unit) { runCatching { closeFocus.requestFocus() } }
        }
    }
}

private enum class TrailerPhase { Loading, Playing, Failed }

/** What the page tells the app: "playing" once the trailer runs, "failed" when it cannot. */
private class Bridge(private val onEvent: (String) -> Unit) {
    @JavascriptInterface
    fun post(event: String) = onEvent(event)
}

@Composable
private fun OverlayButton(icon: ImageVector, label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val tv = !isTouch()
    val interactions = remember { MutableInteractionSource() }
    val focused by interactions.collectIsFocusedAsState()
    Row(
        modifier
            .height(48.dp)
            .clip(CircleShape)
            .background(if (focused) Tone.focusFill else Color.Black.copy(alpha = 0.6f))
            .clickable(interactionSource = interactions, indication = if (tv) null else androidx.compose.foundation.LocalIndication.current, role = androidx.compose.ui.semantics.Role.Button, onClick = onClick)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        val ink = if (focused) Tone.onFocusFill else Color.White
        Icon(icon, contentDescription = null, tint = ink, modifier = Modifier.size(22.dp))
        Text(label, style = MaterialTheme.typography.labelLarge, color = ink, maxLines = 1)
    }
}

/**
 * Serves YouTube's embed page with a script at the top of its head that reports VP9 and AV1 as
 * unsupported, so the player picks H.264, which every device decodes.
 *
 * Seen on a 2020 Mi TV (Android 9, WebView 81): the Amlogic VP9 decoder failed with "video decode
 * error!" and the player went on with the audio alone. Trailers are short and at most 1080p, which
 * H.264 covers, so the choice is made everywhere rather than guessed per device. Anything that goes
 * wrong fetching the page falls back to WebView's own request.
 */
private class H264Only(private val userAgent: String) : WebViewClient() {
    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
        val url = request.url.toString()
        if (request.method != "GET" || !url.startsWith(EMBED)) return null
        return runCatching {
            val connection = (java.net.URL(url).openConnection() as java.net.HttpURLConnection).apply {
                connectTimeout = 10_000
                readTimeout = 10_000
                // Read here rather than from the view, which may not be touched off the main thread.
                setRequestProperty("User-Agent", userAgent)
                request.requestHeaders.forEach { (name, value) -> setRequestProperty(name, value) }
                // WebView leaves the referrer out of the headers it hands over here, and YouTube
                // answers an embed asked for without one with its configuration error (153).
                if (request.requestHeaders.keys.none { it.equals("Referer", ignoreCase = true) }) {
                    setRequestProperty("Referer", "$ORIGIN/")
                }
                setRequestProperty("Accept-Encoding", "identity")
            }
            try {
                if (connection.responseCode != 200) return@runCatching null
                val html = connection.inputStream.bufferedReader().use { it.readText() }
                val head = html.indexOf("<head>")
                if (head < 0) return@runCatching null
                val patched = html.substring(0, head + 6) + "<script>$NO_VP9</script>" + html.substring(head + 6)
                WebResourceResponse("text/html", "utf-8", patched.byteInputStream())
            } finally {
                connection.disconnect()
            }
        }.getOrNull()
    }
}

private const val EMBED = "https://www.youtube-nocookie.com/embed/"

private const val NO_VP9 = """(function(){var no=/vp0?9|av01/i;var M=window.MediaSource;
if(M&&M.isTypeSupported){var t=M.isTypeSupported.bind(M);M.isTypeSupported=function(x){return no.test(x)?false:t(x);};}
var P=HTMLMediaElement.prototype,c=P.canPlayType;P.canPlayType=function(x){return no.test(x)?'':c.call(this,x);};})();"""

/**
 * A black page holding only the player, filling the window, starting at once, through YouTube's
 * IFrame API so the page can say when the trailer plays or why it cannot ([Bridge]).
 */
private fun page(key: String): String {
    val safe = key.filter { it.isLetterOrDigit() || it == '-' || it == '_' }
    return """
        <!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1">
        <style>html,body{margin:0;height:100%;background:#000;overflow:hidden}#p,iframe{border:0;width:100%;height:100%}</style>
        </head><body><div id="p"></div>
        <script>
        function say(e){try{$BRIDGE.post(e)}catch(x){}}
        var tag=document.createElement('script');tag.src='https://www.youtube.com/iframe_api';
        tag.onerror=function(){say('failed')};document.head.appendChild(tag);
        function onYouTubeIframeAPIReady(){
          new YT.Player('p',{host:'https://www.youtube-nocookie.com',videoId:'$safe',
            playerVars:{autoplay:1,playsinline:1,rel:0,modestbranding:1,origin:'$ORIGIN'},
            events:{
              onReady:function(e){e.target.playVideo()},
              onStateChange:function(e){if(e.data===1)say('playing')},
              onError:function(){say('failed')}
            }});
        }
        </script>
        </body></html>
    """.trimIndent()
}

private const val ORIGIN = "https://tmplayer.org"

/** The page's name for [Bridge]. */
private const val BRIDGE = "TMPlayerTrailer"

/** Long enough for a slow line to start a trailer; past it the screen says it is not coming. */
private const val START_TIMEOUT_MS = 15_000L
