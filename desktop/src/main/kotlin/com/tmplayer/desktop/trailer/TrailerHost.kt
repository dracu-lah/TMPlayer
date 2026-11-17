package com.tmplayer.desktop.trailer

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.tmplayer.data.StorageRelocationPlan
import com.tmplayer.online.TrailerRules
import com.tmplayer.ui.components.TmAlertDialog
import com.tmplayer.ui.i18n.LocalStrings
import com.tmplayer.ui.icons.TmIcons
import com.tmplayer.ui.theme.Floating
import com.tmplayer.ui.theme.FloatingTone
import com.tmplayer.ui.theme.Tone
import com.tmplayer.ui.theme.floatingBorder
import kotlinx.coroutines.delay

/**
 * What [trailers] has on screen, over the page: the consent dialog, the download's progress, or
 * the trailer's sheet. Place it once at the window's root, inside the theme.
 *
 * [window] is TMPlayer's own: when it is minimised the trailer pauses, so nothing plays unseen.
 * [video] draws the browser; the default puts its AWT view in a [androidx.compose.ui.awt.SwingPanel].
 */
@Composable
fun DesktopTrailerHost(
    trailers: DesktopTrailers,
    window: java.awt.Window? = null,
    video: @Composable (TrailerBrowser?, Modifier) -> Unit = { browser, modifier -> BrowserView(browser, modifier) },
) {
    when (val screen = trailers.screen) {
        TrailerScreen.None -> Unit
        is TrailerScreen.Asking -> EngineConsentDialog(screen.offer, onDownload = trailers::accept, onYouTube = trailers::decline, onDismiss = trailers::dismiss)
        is TrailerScreen.Installing -> EngineProgressDialog(screen.progress, onCancel = trailers::cancel)
        is TrailerScreen.Playing -> {
            TrailerSheet(trailers, video)
            DisposableEffect(window) {
                val listener = object : java.awt.event.WindowAdapter() {
                    override fun windowIconified(e: java.awt.event.WindowEvent?) = trailers.pause()
                }
                window?.addWindowListener(listener)
                onDispose { window?.removeWindowListener(listener) }
            }
        }
    }
}

/**
 * The question before anything is downloaded: what the player is, how much it downloads and keeps,
 * where, and how much is free there. Download only where it fits; Watch on YouTube always.
 */
@Composable
internal fun EngineConsentDialog(offer: EngineOffer, onDownload: () -> Unit, onYouTube: () -> Unit, onDismiss: () -> Unit) {
    val s = LocalStrings.current
    val youTubeFocus = remember { FocusRequester() }
    val folder = offer.dir.path
    TmAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(s.trailerEngineTitle) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(s.trailerEngineIntro)
                val size = when (offer) {
                    is EngineOffer.Fits -> s.trailerEngineSize(
                        StorageRelocationPlan.size(offer.native.bytes),
                        StorageRelocationPlan.size(offer.native.unpackedBytes),
                        folder,
                        StorageRelocationPlan.size(offer.freeBytes),
                    )
                    is EngineOffer.NoRoom -> s.trailerEngineNoRoom(folder, StorageRelocationPlan.size(offer.neededBytes), StorageRelocationPlan.size(offer.freeBytes))
                    is EngineOffer.SpaceUnknown -> s.trailerEngineSpaceUnknown(folder)
                }
                Text(size, color = if (offer is EngineOffer.Fits) Tone.muted else Tone.danger, style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            if (offer is EngineOffer.Fits) {
                TextButton(onClick = onDownload) { Text(s.trailerEngineDownload, color = Tone.accent) }
            }
        },
        dismissButton = {
            TextButton(onClick = onYouTube, modifier = Modifier.focusRequester(youTubeFocus)) { Text(s.metadataTrailerOnYoutube) }
        },
    )
    // Focus starts on the choice that downloads nothing, so an Enter out of habit costs nothing.
    LaunchedEffect(Unit) { runCatching { youTubeFocus.requestFocus() } }
}

/** The download and the unpacking, with Cancel, which leaves nothing on disk. */
@Composable
internal fun EngineProgressDialog(progress: EngineProgress?, onCancel: () -> Unit) {
    val s = LocalStrings.current
    val cancelFocus = remember { FocusRequester() }
    val (title, done, total) = when (progress) {
        null -> Triple(s.trailerEngineDownloading, 0L, 0L)
        is EngineProgress.Downloading -> Triple(s.trailerEngineDownloading, progress.done, progress.total)
        is EngineProgress.Unpacking -> Triple(s.trailerEngineUnpacking, progress.done, progress.total)
    }
    TmAlertDialog(
        // Only the Cancel button stops it: a stray click beside the dialog must not throw away a download.
        onDismissRequest = {},
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (total > 0) {
                    LinearProgressIndicator(progress = { (done.toFloat() / total).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                    Text(
                        s.trailerEngineProgress(StorageRelocationPlan.size(done), StorageRelocationPlan.size(total)),
                        color = Tone.muted,
                        style = MaterialTheme.typography.bodySmall,
                    )
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onCancel, modifier = Modifier.focusRequester(cancelFocus)) { Text(s.commonCancel) }
        },
    )
    LaunchedEffect(Unit) { runCatching { cancelFocus.requestFocus() } }
}

/**
 * The trailer over the detail page, at 16:9 as large as the window allows: its title, Close, the
 * player, and a line on the keys. Space plays or pauses, Left and Right skip 10 s, Esc closes;
 * inside the browser the same keys are taken before the page sees them ([CefTrailerBrowser]).
 * Nothing answers from YouTube within [TrailerRules.READY_TIMEOUT_MS]: it opens in YouTube.
 */
@Composable
internal fun TrailerSheet(trailers: DesktopTrailers, video: @Composable (TrailerBrowser?, Modifier) -> Unit) {
    val s = LocalStrings.current
    val focus = remember { FocusRequester() }
    var holdsFocus by remember { mutableStateOf(false) }
    val browser = trailers.browser
    // From the moment the page is in a browser: Chromium's own start is not YouTube's fault.
    LaunchedEffect(browser) {
        if (browser == null) return@LaunchedEffect
        delay(TrailerRules.READY_TIMEOUT_MS)
        trailers.timedOut()
    }
    Dialog(onDismissRequest = trailers::close, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            val byHeight = (maxHeight * 0.85f - CHROME_HEIGHT) * (16f / 9f)
            val width = minOf(maxWidth - 48.dp, MAX_WIDTH, byHeight).coerceAtLeast(240.dp)
            Column(
                Modifier
                    .width(width)
                    .clip(Floating.DialogShape)
                    .background(FloatingTone.sheet)
                    .border(floatingBorder(), Floating.DialogShape)
                    // Before the focus target, so it hears keys whether the sheet or its Close button has the focus.
                    .onPreviewKeyEvent { event ->
                        val key = trailerKey(event) ?: return@onPreviewKeyEvent false
                        // The release is spent too, so a focused button never takes a Space as its click.
                        if (event.type == KeyEventType.KeyDown) trailers.key(key)
                        true
                    }
                    .onFocusChanged { holdsFocus = it.hasFocus }
                    .focusRequester(focus)
                    .focusable()
                    .semantics { contentDescription = s.trailerTitle },
            ) {
                Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(s.trailerTitle, style = MaterialTheme.typography.titleMedium, color = Tone.text, modifier = Modifier.weight(1f))
                    IconButton(onClick = trailers::close) { Icon(TmIcons.Close, contentDescription = s.trailerClose, tint = Tone.text) }
                }
                Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).background(Color.Black), contentAlignment = Alignment.Center) {
                    if (browser == null) Text(s.trailerLoading, color = Color.White, style = MaterialTheme.typography.bodyMedium)
                    video(browser, Modifier.fillMaxSize())
                }
                Text(
                    s.trailerKeysHint,
                    style = MaterialTheme.typography.bodySmall,
                    color = Tone.muted,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
                )
            }
        }
        // Inside the dialog's own layer, where the sheet is.
        // The dialog's layer joins the window a frame or two after it is composed, so ask until it holds.
        LaunchedEffect(Unit) {
            repeat(FOCUS_TRIES) {
                if (holdsFocus) return@LaunchedEffect
                runCatching { focus.requestFocus() }
                androidx.compose.runtime.withFrameNanos { }
            }
        }
    }
}

/** The browser's own view, once there is one. */
@Composable
private fun BrowserView(browser: TrailerBrowser?, modifier: Modifier) {
    val component = browser?.component ?: return
    androidx.compose.ui.awt.SwingPanel(background = Color.Black, factory = { component }, modifier = modifier)
}

/** The sheet's keys, bare ones only: a chord belongs to the window (Ctrl+Q still quits). */
internal fun trailerKey(event: KeyEvent): TrailerKey? {
    if (event.type != KeyEventType.KeyDown && event.type != KeyEventType.KeyUp) return null
    if (event.isCtrlPressed || event.isAltPressed || event.isMetaPressed || event.isShiftPressed) return null
    return when (event.key) {
        // The media key of a keyboard or a PC remote too; Enter stays the focused button's (Close).
        Key.Spacebar, Key.MediaPlayPause -> TrailerKey.Toggle
        Key.Escape, Key.Back -> TrailerKey.Close
        Key.DirectionLeft -> TrailerKey.Back
        Key.DirectionRight -> TrailerKey.Forward
        else -> null
    }
}

private val MAX_WIDTH = 1280.dp

private const val FOCUS_TRIES = 20

/** The title row and the line under the player, which the 16:9 picture shares the height with. */
private val CHROME_HEIGHT = 110.dp
