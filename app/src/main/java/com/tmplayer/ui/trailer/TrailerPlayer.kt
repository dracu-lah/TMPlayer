package com.tmplayer.ui.trailer

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tmplayer.data.MobileData
import com.tmplayer.data.NetworkMonitor
import com.tmplayer.data.NetworkStatus
import com.tmplayer.data.SettingsStore
import com.tmplayer.data.webViewInstalled
import com.tmplayer.online.MetaTrailer
import com.tmplayer.online.TrailerEmbed
import com.tmplayer.online.TrailerPageLabels
import com.tmplayer.online.TrailerPhase
import com.tmplayer.online.TrailerRules
import com.tmplayer.ui.components.isTouch
import com.tmplayer.ui.i18n.LocalStrings
import com.tmplayer.ui.icons.TmIcons
import com.tmplayer.ui.online.LocalTrailerOpener
import com.tmplayer.ui.online.TrailerOpener
import com.tmplayer.ui.online.openTrailerOutside
import com.tmplayer.ui.theme.Tone
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.catch

/** One trailer the viewer chose, and where it goes when it cannot play here. */
class TrailerRequest internal constructor(val trailer: MetaTrailer, internal val onFallback: () -> Unit)

/**
 * Android's [TrailerOpener] (private/TRAILERS-PLAN.md, T3): a trailer chosen on any detail page
 * plays in [TrailerPlayerHost]'s full screen dialog, YouTube's embedded player in a WebView. The
 * launcher only calls [open] with the viewer's `trailers_in_app` setting on, a valid key and
 * [online]; a device without a WebView goes straight to [TrailerRequest.onFallback], the YouTube
 * app or the browser as before. Nothing from Google loads until [open], which only the trailer
 * button's press calls.
 */
@Stable
class AndroidTrailerOpener(
    private val network: () -> Boolean,
    private val hasWebView: () -> Boolean,
) : TrailerOpener {

    /** The trailer showing, if any. */
    var request by mutableStateOf<TrailerRequest?>(null)
        private set

    override val online: Boolean get() = network()

    override fun open(trailer: MetaTrailer, onFallback: () -> Unit) {
        if (!hasWebView()) {
            onFallback()
            return
        }
        request = TrailerRequest(trailer, onFallback)
    }

    /** Closes [which], if it is still the one showing (a second press of Close does nothing more). */
    fun close(which: TrailerRequest) {
        if (request === which) request = null
    }

    /** A trailer that was playing before the activity was recreated (a rotation): it starts again. */
    internal fun restore(trailer: MetaTrailer, onFallback: () -> Unit) {
        if (request == null && hasWebView()) request = TrailerRequest(trailer, onFallback)
    }
}

/**
 * Provides Android's trailer player to everything in [content] and draws it over [content]. Put
 * once at the activity's root. The "On mobile data" answer is read as it changes, so the check at
 * the tap is current.
 */
@Composable
fun TrailerPlayerRoot(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val television = !isTouch()
    val answers = remember(context) { SettingsStore(context).mobileData.catch { emit(MobileData.Ask) } }
    val mobileData by answers.collectAsStateWithLifecycle(initialValue = MobileData.Ask)
    val answer by rememberUpdatedState(mobileData)
    val tv by rememberUpdatedState(television)
    // The trailer showing, kept across a rotation (the activity is recreated) but not across a
    // restart of the process: a trailer never starts by itself in a new run of the app.
    var saved by rememberSaveable { mutableStateOf<String?>(null) }
    val process = android.os.Process.myPid().toString()
    val opener = remember(context) {
        AndroidTrailerOpener(
            network = {
                trailerMayStream(
                    offline = NetworkMonitor.status.value == NetworkStatus.Offline,
                    metered = NetworkMonitor.metered.value,
                    mobileData = answer,
                    television = tv,
                )
            },
            hasWebView = { webViewInstalled(context) },
        ).apply {
            restoredKey(saved, process)?.let { key ->
                val trailer = MetaTrailer(key)
                restore(trailer) { openTrailerOutside(context, trailer) }
            }
        }
    }
    CompositionLocalProvider(LocalTrailerOpener provides opener) { content() }
    TrailerPlayerHost(opener, onShowing = { showing -> saved = showing?.let { "$process:${it.trailer.key}" } })
}

/** The key [saved] holds for this run of the app ([process]), or null. */
internal fun restoredKey(saved: String?, process: String): String? {
    if (saved == null || saved.length > 64) return null
    val key = saved.substringAfter(':', "")
    return key.takeIf { saved.substringBefore(':') == process && TrailerEmbed.isValidKey(it) }
}

/**
 * The dialog for [opener]'s trailer while one is chosen, told to [onShowing] as it changes.
 * [pages] and [readyTimeoutMs] are for tests.
 */
@Composable
fun TrailerPlayerHost(
    opener: AndroidTrailerOpener,
    pages: TrailerPageFactory = WebTrailerPages,
    readyTimeoutMs: Long = TrailerRules.READY_TIMEOUT_MS,
    onShowing: (TrailerRequest?) -> Unit = {},
) {
    val showing = opener.request
    SideEffect { onShowing(showing) }
    val request = showing ?: return
    key(request) {
        TrailerDialog(
            trailer = request.trailer,
            pages = pages,
            readyTimeoutMs = readyTimeoutMs,
            onClose = { opener.close(request) },
            onOutside = {
                opener.close(request)
                request.onFallback()
            },
        )
    }
}

/**
 * The trailer over everything, full screen, on the phone, the tablet and the television.
 *
 * Touch: YouTube's own controls work on the picture; Close and "Watch on YouTube" stay at the top,
 * Back leaves YouTube's full screen view first and then closes. Remote: the WebView takes no focus
 * and the dialog holds every key ([TrailerKeys]). Close has the focus first, while the trailer
 * loads; once it plays the button goes and OK plays or pauses, Left and Right move ten seconds, Up
 * or Down bring Close back for a few seconds, and Back closes.
 *
 * The page is destroyed when the dialog goes, and paused when the app goes to the background
 * (nothing plays out of sight). A YouTube error, or no `ready` within [readyTimeoutMs], says so
 * for a moment and goes to [onOutside], the external open.
 *
 * No background of its own under the page: WebView plays the video on a surface behind the window
 * and leaves a hole in its own drawing for it, and a black box painted under it covered that hole
 * (sound over black on a POCO F5, WebView 157, 2026-10-22). The page and the WebView are black.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun TrailerDialog(
    trailer: MetaTrailer,
    pages: TrailerPageFactory,
    readyTimeoutMs: Long,
    onClose: () -> Unit,
    onOutside: () -> Unit,
) {
    val s = LocalStrings.current
    val remote = !isTouch()
    val context = LocalContext.current
    val session = remember { TrailerSession() }
    var fullscreen by remember { mutableStateOf(false) }
    val leave by rememberUpdatedState(onOutside)
    val events = remember {
        object : TrailerPageEvents {
            override fun onMessage(raw: String) = session.onMessage(raw)
            override fun onFullscreen(shown: Boolean) {
                fullscreen = shown
            }
            override fun onLeave(url: String) = leave()
        }
    }
    val page: TrailerPage? = remember {
        val html = TrailerEmbed.pageHtml(trailer.key, TrailerPageLabels(s.trailerTitle, s.trailerLoading))
        if (html == null) null else runCatching { pages.create(context, html, remote, events) }.getOrNull()
    }
    DisposableEffect(page) { onDispose { page?.destroy() } }
    LaunchedEffect(page) {
        if (page == null) {
            session.onBroken()
            return@LaunchedEffect
        }
        delay(readyTimeoutMs)
        session.onTimeout()
    }
    val state = session.state
    LaunchedEffect(state.fallsBack) {
        if (!state.fallsBack) return@LaunchedEffect
        delay(FALLBACK_NOTICE_MS)
        leave()
    }
    // Out of sight, nothing plays: paused as the app leaves the screen, left paused on its return.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, page) {
        // A new observer is told ON_START at once; only a return from the background resumes.
        var away = false
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> {
                    away = true
                    page?.pause()
                }
                Lifecycle.Event.ON_START -> if (away) {
                    away = false
                    page?.resume()
                }
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }

    val dismiss = { if (page?.exitFullscreen() != true) onClose() }
    Dialog(
        onDismissRequest = dismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        FullscreenBars(fullscreen)
        val closeFocus = remember { FocusRequester() }
        var closeFocused by remember { mutableStateOf(false) }
        val pictureFocus = remember { FocusRequester() }
        var pictureFocused by remember { mutableStateOf(false) }
        var chromeShown by remember { mutableStateOf(true) }
        var pokes by remember { mutableIntStateOf(0) }
        // Remote: Close goes once the trailer plays, and again a while after Up or Down brought it back.
        LaunchedEffect(remote, chromeShown, state.controllable, pokes) {
            if (!remote || !chromeShown || !state.controllable) return@LaunchedEffect
            delay(if (pokes == 0) CHROME_FIRST_MS else CHROME_MS)
            chromeShown = false
        }
        // The focus is Close's while it shows, else the picture's, so the keys always reach the
        // dialog and never the WebView. Only one of the two can take it at a time, so the window's
        // own first focus lands there too. The dialog's window can get the system's focus a few
        // frames after its first one, and a request before that is lost, so it is asked again.
        LaunchedEffect(remote, chromeShown) {
            if (!remote) return@LaunchedEffect
            repeat(FOCUS_TRIES) {
                if (if (chromeShown) closeFocused else pictureFocused) return@LaunchedEffect
                runCatching { (if (chromeShown) closeFocus else pictureFocus).requestFocus() }
                delay(FOCUS_RETRY_MS)
            }
        }
        Box(
            Modifier
                .fillMaxSize()
                .then(
                    if (!remote) {
                        Modifier
                    } else {
                        Modifier.onPreviewKeyEvent { event ->
                            val down = event.type == KeyEventType.KeyDown
                            if (!down && event.type != KeyEventType.KeyUp) return@onPreviewKeyEvent false
                            val action = TrailerKeys.action(event.key, down, event.nativeKeyEvent.repeatCount, chromeShown)
                                ?: return@onPreviewKeyEvent false
                            when (action) {
                                TrailerKeys.Action.Close -> dismiss()
                                TrailerKeys.Action.ShowChrome -> {
                                    chromeShown = true
                                    pokes++
                                }
                                else -> TrailerKeys.script(action)?.let { page?.run(it) }
                            }
                            true
                        }
                    },
                )
                .focusRequester(pictureFocus)
                .onFocusChanged { pictureFocused = it.isFocused }
                .focusable(enabled = remote && !chromeShown),
        ) {
            if (page != null) AndroidView(factory = { page.view }, modifier = Modifier.fillMaxSize())
            when {
                state.fallsBack -> Notice(s.trailerUnavailableInApp, Modifier.align(Alignment.Center))
                state.phase == TrailerPhase.Loading -> CircularProgressIndicator(
                    color = Color.White,
                    strokeWidth = 3.dp,
                    modifier = Modifier.align(Alignment.Center).size(if (remote) 56.dp else 44.dp),
                )
                else -> Unit
            }
            if (chromeShown && !(fullscreen && !remote)) {
                // Wraps onto a second line where both do not fit (a narrow phone at twice the font).
                FlowRow(
                    Modifier
                        .align(Alignment.TopEnd)
                        .windowInsetsPadding(WindowInsets.safeDrawing)
                        .padding(if (remote) 24.dp else 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    if (!remote) OverlayButton(TmIcons.OpenExternal, s.metadataTrailerOnYoutube, onOutside)
                    OverlayButton(
                        TmIcons.Close,
                        s.trailerClose,
                        onClose,
                        Modifier.focusRequester(closeFocus).onFocusChanged { closeFocused = it.isFocused },
                    )
                }
                if (remote) {
                    Text(
                        s.trailerRemoteHint,
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(32.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color.Black.copy(alpha = 0.6f))
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                    )
                }
            }
        }
    }
}

/** YouTube's full screen view hides the status and navigation bars; a swipe brings them back for a moment. */
@Composable
private fun FullscreenBars(fullscreen: Boolean) {
    val view = LocalView.current
    val window = (view.parent as? DialogWindowProvider)?.window ?: return
    DisposableEffect(window, fullscreen) {
        val controller = WindowCompat.getInsetsController(window, view)
        if (fullscreen) {
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
        onDispose { }
    }
}

@Composable
private fun Notice(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        color = Tone.text,
        textAlign = TextAlign.Center,
        modifier = modifier
            .padding(24.dp)
            .widthIn(max = 560.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(Tone.surfaceHigh)
            .padding(20.dp),
    )
}

@Composable
private fun OverlayButton(icon: ImageVector, label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val tv = !isTouch()
    val interactions = remember { MutableInteractionSource() }
    val focused by interactions.collectIsFocusedAsState()
    Row(
        modifier
            .heightIn(min = 48.dp)
            .clip(CircleShape)
            .background(if (focused) Tone.focusFill else Color.Black.copy(alpha = 0.6f))
            .clickable(
                interactionSource = interactions,
                indication = if (tv) null else androidx.compose.foundation.LocalIndication.current,
                role = Role.Button,
                onClick = onClick,
            )
            .padding(horizontal = 14.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        val ink = if (focused) Tone.onFocusFill else Color.White
        Icon(icon, contentDescription = null, tint = ink, modifier = Modifier.size(22.dp))
        Text(label, style = MaterialTheme.typography.labelLarge, color = ink, maxLines = 1)
    }
}

/** How long "cannot play here" shows before the trailer opens outside. */
private const val FALLBACK_NOTICE_MS = 1_500L

/** Close stays this long once the trailer can be played and paused, so it is seen going. */
private const val CHROME_FIRST_MS = 800L

/** How often, and how far apart, Close asks for the focus as it appears. */
private const val FOCUS_TRIES = 20
private const val FOCUS_RETRY_MS = 50L

/** Close stays this long after Up or Down brought it back. */
private const val CHROME_MS = 4_000L
