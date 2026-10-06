package com.tmplayer.ui.online

import android.annotation.SuppressLint
import android.graphics.Color as AndroidColor
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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.tmplayer.online.MetaTrailer
import com.tmplayer.ui.components.isTouch
import com.tmplayer.ui.i18n.LocalStrings
import com.tmplayer.ui.theme.Tone

/**
 * True where the system has a WebView to play YouTube's embedded player in. Some televisions ship
 * without one (or with it disabled); those keep the YouTube app and the QR code.
 */
@Composable
internal fun rememberInAppTrailers(): Boolean = remember {
    runCatching { WebView.getCurrentWebViewPackage() != null }.getOrDefault(false)
}

/**
 * A trailer over everything, in YouTube's embedded player, so it plays without leaving the app.
 *
 * The player is given a page of its own with tmplayer.org as its origin: YouTube refuses an embed
 * that arrives with no referrer, which is what a bare embed URL in a WebView sends. Some trailers
 * are not allowed to play embedded at all, so "Watch on YouTube" is always there beside Close.
 *
 * The embed is steered to H.264 (see [H264Only]): older televisions decode VP9 in hardware badly
 * enough under WebView that the trailer played as sound over a black screen.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
internal fun TrailerPlayer(trailer: MetaTrailer, onClose: () -> Unit, onOpenElsewhere: () -> Unit) {
    val s = LocalStrings.current
    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            var view: WebView? = null
            AndroidView(
                factory = { context ->
                    WebView(context).apply {
                        view = this
                        setBackgroundColor(AndroidColor.BLACK)
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.mediaPlaybackRequiresUserGesture = false
                        webChromeClient = WebChromeClient()
                        webViewClient = H264Only(settings.userAgentString)
                        loadDataWithBaseURL(ORIGIN, page(trailer.key), "text/html", "utf-8", null)
                    }
                },
                modifier = Modifier.fillMaxSize(),
            )
            DisposableEffect(Unit) {
                onDispose {
                    view?.apply {
                        loadUrl("about:blank")
                        destroy()
                    }
                }
            }
            Row(
                Modifier.align(Alignment.TopEnd).padding(if (isTouch()) 12.dp else 24.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OverlayButton(Icons.AutoMirrored.Filled.ExitToApp, s.metadataTrailerOnYoutube, onOpenElsewhere)
                OverlayButton(Icons.Filled.Close, s.commonClose, onClose)
            }
        }
    }
}

@Composable
private fun OverlayButton(icon: ImageVector, label: String, onClick: () -> Unit) {
    val tv = !isTouch()
    val interactions = remember { MutableInteractionSource() }
    val focused by interactions.collectIsFocusedAsState()
    Row(
        Modifier
            .height(if (tv) 48.dp else 40.dp)
            .clip(CircleShape)
            .background(if (focused) Tone.focusFill else Color.Black.copy(alpha = 0.6f))
            .clickable(interactionSource = interactions, indication = if (tv) null else androidx.compose.foundation.LocalIndication.current, onClick = onClick)
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

/** A black page holding only the player, filling the window, starting at once. */
private fun page(key: String): String {
    val safe = key.filter { it.isLetterOrDigit() || it == '-' || it == '_' }
    return """
        <!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1">
        <style>html,body{margin:0;height:100%;background:#000;overflow:hidden}iframe{border:0;width:100%;height:100%}</style>
        </head><body>
        <iframe src="$EMBED$safe?autoplay=1&playsinline=1&rel=0&modestbranding=1&origin=$ORIGIN"
          allow="autoplay; encrypted-media; picture-in-picture; fullscreen" allowfullscreen></iframe>
        </body></html>
    """.trimIndent()
}

private const val ORIGIN = "https://tmplayer.org"
