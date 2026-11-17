package com.tmplayer.online

/**
 * YouTube's own embedded player for a trailer, the only way a trailer plays inside TMPlayer
 * (private/TRAILERS-PLAN.md): youtube-nocookie.com's embed, driven by YouTube's IFrame Player
 * API. Nothing here extracts a stream, and nothing from Google loads until the viewer has chosen
 * the trailer. Shared by every platform: the web app puts [embedUrl] in an iframe, Android,
 * the desktop and iOS load [pageHtml] into their web view.
 *
 * The key is TMDB's (outside data), so it must pass [isValidKey] before it is put in a URL or a
 * page: both answer null for anything else.
 *
 * ## The page's contract with its host
 *
 * Load [pageHtml] with [BASE_ORIGIN] as its base URL (Android `loadDataWithBaseURL`, CEF
 * `loadURL` of a data page served for that origin, iOS `loadHTMLString(_:baseURL:)`): a bare
 * `data:` or `about:blank` page sends no referrer, and YouTube answers that with error 153.
 *
 * The page speaks to the host through one name, [BRIDGE], in whichever form the host binds:
 * - Android: `addJavascriptInterface(object { @JavascriptInterface fun post(message: String) }, BRIDGE)`,
 *   called as `window.TMPlayerTrailer.post(message)`.
 * - iOS: `userContentController.add(handler, name: BRIDGE)`, called as
 *   `window.webkit.messageHandlers.TMPlayerTrailer.postMessage(message)`.
 * - Desktop (CEF): a `CefMessageRouter` with its default `cefQuery` function; the request is the message.
 *
 * Each message is one short string, read with [parseMessage]: `ready`, `state:<n>` (YouTube's
 * player states: -1 unstarted, 0 ended, 1 playing, 2 paused, 3 buffering, 5 cued) and
 * `error:<n>` (YouTube's error codes, and -1 when its script could not be loaded). Hand them to
 * [TrailerRules.next]; start [TrailerRules.READY_TIMEOUT_MS] when the page is loaded.
 *
 * The host drives the page by running [PLAY], [PAUSE], [TOGGLE] or [seekBy] as script
 * (Android `evaluateJavascript`, CEF `executeJavaScript`, iOS `evaluateJavaScript`). They do
 * nothing until the player is ready. The host lets media play without a further gesture
 * (Android `mediaPlaybackRequiresUserGesture = false`, iOS `mediaTypesRequiringUserActionForPlayback = []`):
 * the tap on the trailer button was the gesture.
 */
object TrailerEmbed {

    /** Where the embed comes from: YouTube's privacy enhanced host. */
    const val HOST = "https://www.youtube-nocookie.com"

    /** The base URL [pageHtml] is loaded with, and the `origin` the embed is told about. */
    const val BASE_ORIGIN = HOST

    /** YouTube's IFrame Player API, the one script the page loads. */
    const val API = "https://www.youtube.com/iframe_api"

    /** The name the page calls the host by; see the contract above. */
    const val BRIDGE = "TMPlayerTrailer"

    /** Scripts the host runs in the page. */
    const val PLAY = "play()"
    const val PAUSE = "pause()"
    const val TOGGLE = "toggle()"

    /** The script that moves the trailer [seconds] on (back when negative), kept within a minute a press. */
    fun seekBy(seconds: Int): String = "seekBy(${seconds.coerceIn(-60, 60)})"

    /** YouTube's video ids are exactly eleven of `A-Z a-z 0-9 _ -`. */
    const val KEY_LENGTH = 11

    /** True when [key] is a YouTube video id: exactly [KEY_LENGTH] ASCII letters, digits, `_` or `-`. */
    fun isValidKey(key: String?): Boolean =
        key != null && key.length == KEY_LENGTH && key.all { it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' || it == '_' || it == '-' }

    /** The embed's address for [key], or null when [key] is not a video id or [origin] not a web origin. */
    fun embedUrl(key: String?, origin: String = BASE_ORIGIN): String? {
        if (!isValidKey(key) || !isOrigin(origin)) return null
        return "$HOST/embed/$key?autoplay=1&playsinline=1&rel=0&enablejsapi=1&origin=${encode(origin)}"
    }

    /**
     * A black page holding only the player, filling the window, with the IFrame API wired to
     * [BRIDGE] and `play()`, `pause()`, `toggle()` and `seekBy(seconds)` for the host. [labels]
     * are HTML escaped; the key is the only other text in it. Null when [key] is not a video id.
     */
    fun pageHtml(key: String?, labels: TrailerPageLabels, origin: String = BASE_ORIGIN): String? {
        val src = embedUrl(key, origin) ?: return null
        val title = escapeHtml(labels.title)
        val loading = escapeHtml(labels.loading)
        return """<!doctype html><html><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<meta name="referrer" content="strict-origin-when-cross-origin">
<title>$title</title>
<style>html,body{margin:0;height:100%;background:#000;overflow:hidden}
iframe{border:0;width:100%;height:100%;display:block}
#l{position:absolute;inset:0;display:flex;align-items:center;justify-content:center;color:#fff;font:16px sans-serif;pointer-events:none}</style>
</head><body>
<div id="l" role="status">$loading</div>
<iframe id="p" title="$title" src="${escapeHtml(src)}" allow="autoplay; encrypted-media; picture-in-picture; fullscreen" allowfullscreen referrerpolicy="strict-origin-when-cross-origin"></iframe>
<script>
(function(){
var B='$BRIDGE',player=null,ready=false;
function say(m){
try{if(window[B]&&window[B].post){window[B].post(m);return}}catch(e){}
try{var h=window.webkit&&window.webkit.messageHandlers&&window.webkit.messageHandlers[B];if(h){h.postMessage(m);return}}catch(e){}
try{if(window.cefQuery){window.cefQuery({request:m,persistent:false,onSuccess:function(){},onFailure:function(){}})}}catch(e){}
}
window.play=function(){if(ready)player.playVideo()};
window.pause=function(){if(ready)player.pauseVideo()};
window.toggle=function(){if(!ready)return;var s=player.getPlayerState();if(s===1||s===3)player.pauseVideo();else player.playVideo()};
window.seekBy=function(d){if(!ready)return;var t=player.getCurrentTime()+d;player.seekTo(t<0?0:t,true)};
window.onYouTubeIframeAPIReady=function(){
player=new YT.Player('p',{events:{
onReady:function(){ready=true;var l=document.getElementById('l');if(l)l.parentNode.removeChild(l);player.playVideo();say('ready')},
onStateChange:function(e){say('state:'+e.data)},
onError:function(e){say('error:'+e.data)}}})};
var t=document.createElement('script');t.src='$API';t.onerror=function(){say('error:-1')};document.head.appendChild(t);
})();
</script>
</body></html>"""
    }

    /**
     * What the page said, or null for anything that is not one of its messages. The message
     * comes through a web view from a page that ran YouTube's script, so it is read as outside
     * data: short, one of three shapes, numbers in YouTube's range.
     */
    fun parseMessage(raw: String?): TrailerMessage? {
        if (raw == null || raw.length > MAX_MESSAGE) return null
        val text = raw.trim()
        if (text == "ready") return TrailerMessage.Ready
        val name = text.substringBefore(':', "")
        val code = asciiInt(text.substringAfter(':', "")) ?: return null
        return when (name) {
            "state" -> code.takeIf { it in -1..5 }?.let { TrailerMessage.State(it) }
            "error" -> code.takeIf { it in -1..999 }?.let { TrailerMessage.Error(it) }
            else -> null
        }
    }

    private const val MAX_MESSAGE = 32

    /** `-1` to `9999` in ASCII digits only: `toIntOrNull` would read other scripts' digits too. */
    private fun asciiInt(text: String): Int? {
        val digits = text.removePrefix("-")
        if (digits.isEmpty() || digits.length > 4 || !digits.all { it in '0'..'9' }) return null
        val value = digits.fold(0) { n, c -> n * 10 + (c - '0') }
        return if (text.startsWith("-")) -value else value
    }

    /** `https://host` or `http://host:port`, nothing after it: what YouTube's `origin` takes. */
    private fun isOrigin(origin: String): Boolean {
        if (origin.length > 200) return false
        val rest = when {
            origin.startsWith("https://") -> origin.removePrefix("https://")
            origin.startsWith("http://") -> origin.removePrefix("http://")
            else -> return false
        }
        val host = rest.substringBefore(':')
        val port = rest.substringAfter(':', "")
        if (host.isEmpty() || !host.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '.' || it == '-' }) return false
        return ':' !in rest || (port.length in 1..5 && port.all { it in '0'..'9' })
    }

    /** Percent encoding for a query value: everything but `A-Z a-z 0-9 - _ . ~`. */
    private fun encode(value: String): String = buildString {
        for (byte in value.encodeToByteArray()) {
            val c = byte.toInt() and 0xFF
            val ch = c.toChar()
            if (ch in 'A'..'Z' || ch in 'a'..'z' || ch in '0'..'9' || ch == '-' || ch == '_' || ch == '.' || ch == '~') {
                append(ch)
            } else {
                append('%').append(HEX[c shr 4]).append(HEX[c and 0xF])
            }
        }
    }

    private const val HEX = "0123456789ABCDEF"

    /** Text made safe for an HTML element or a quoted attribute. */
    internal fun escapeHtml(text: String): String = buildString(text.length) {
        for (ch in text) {
            when (ch) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '"' -> append("&quot;")
                '\'' -> append("&#39;")
                else -> append(ch)
            }
        }
    }
}

/** The page's own words, in the viewer's language: its title (read aloud for the player) and the line shown while it loads. */
data class TrailerPageLabels(val title: String, val loading: String)

/** One message from the trailer page ([TrailerEmbed.parseMessage]). */
sealed interface TrailerMessage {
    /** The player is ready: the host's play and pause work from here on. */
    data object Ready : TrailerMessage

    /** YouTube's player state: -1 unstarted, 0 ended, 1 playing, 2 paused, 3 buffering, 5 cued. */
    data class State(val code: Int) : TrailerMessage

    /** YouTube's error code, or -1 when its script could not be loaded. */
    data class Error(val code: Int) : TrailerMessage
}
