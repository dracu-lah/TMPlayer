package com.tmplayer.desktop.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.tmplayer.data.MediaItem
import com.tmplayer.data.Thumbnails
import com.tmplayer.player.SpeedMeter
import com.tmplayer.player.StreamStats
import com.tmplayer.ui.i18n.LocalStrings
import com.tmplayer.ui.player.PlayerLoader
import com.tmplayer.ui.player.rememberLoaderContent
import kotlinx.coroutines.delay

/**
 * The desktop's pre-roll: the shared [PlayerLoader] over the whole window while [phase] is
 * [Phase.Loading], faded off when playback begins. A back arrow sits in the corner for the mouse;
 * Escape and the mouse's back button keep working as before.
 *
 * [downloaded] is how much of the file is on the disk, which feeds both the wait's slow-speed note
 * (after three seconds, and only once it means something) and, during a whole-file download
 * before playing, the progress line. Otherwise the line follows the player's buffer.
 */
@Composable
internal fun BoxScope.LoaderSheet(
    phase: Phase,
    item: MediaItem,
    downloaded: Float?,
    bufferedMs: Long,
    isDownload: Boolean,
    onBack: () -> Unit,
    /** The picture to use without metadata; the fixture passes one, the app reads the thumbnail. */
    thumbnailOverride: ImageBitmap? = null,
) {
    val s = LocalStrings.current
    val loading = phase as? Phase.Loading
    // The words stay as they were while the sheet fades out, so the fade is not of a blank sheet.
    val last = remember { arrayOfNulls<Phase.Loading>(1) }
    if (loading != null) last[0] = loading
    val shown = last[0] ?: return

    val thumbnail by produceState(thumbnailOverride, item.thumbnailFileId) {
        if (value != null || item.thumbnailFileId <= 0) return@produceState
        val frame = runCatching { Thumbnails.full(item.thumbnailFileId) }.getOrNull() ?: return@produceState
        // A square or upright thumbnail is, as a rule, the channel's logo: never shown big.
        if (frame.height > 0 && frame.width.toFloat() / frame.height >= 1.3f) value = frame
    }

    // The speed, from the bytes on the disk, for the slow wait's note.
    val meter = remember(item.fileId) { SpeedMeter() }
    var note by remember(item.fileId) { mutableStateOf<String?>(null) }
    val fraction by rememberUpdatedState(downloaded)
    val real by rememberUpdatedState(isDownload)
    LaunchedEffect(item.fileId, loading != null) {
        if (loading == null) return@LaunchedEffect
        val started = System.currentTimeMillis()
        while (true) {
            val now = System.currentTimeMillis()
            fraction?.let { meter.sample((it * item.sizeBytes).toLong(), now) }
            val rate = meter.bytesPerSec
            note = if (now - started > SLOW_MS && rate >= StreamStats.MIN_MEANINGFUL_SPEED && (fraction ?: 1f) < 1f) {
                val figure = s.formatter.speed(rate)
                if (real) s.playerDownloadingRate(figure) else s.playerCachingRate(figure)
            } else {
                null
            }
            delay(TICK_MS)
        }
    }

    val routine = shown.message == s.playerOpeningVideo || shown.message == s.playerConnectingTelegram
    val status = if (routine) s.playerLoaderStarting else shown.message
    // A whole-file download before playing counts the file; anything else counts the buffer.
    val wholeFile = shown.message.startsWith(s.playerDownloadingWholeVideo)
    val progress = if (wholeFile) downloaded ?: 0f else (bufferedMs / BUFFER_TARGET_MS.toFloat()).coerceIn(0f, 1f)

    AnimatedVisibility(loading != null, Modifier.matchParentSize(), enter = fadeIn(tween(0)), exit = fadeOut(tween(250))) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val widthPx = with(LocalDensity.current) { maxWidth.roundToPx() }
            val content = rememberLoaderContent(
                fileName = item.fileName.ifBlank { item.title },
                caption = item.caption,
                fallbackTitle = item.title,
                durationSec = item.durationSec,
                thumbnail = thumbnail,
                screenWidthPx = widthPx,
            )
            PlayerLoader(content = content, progress = progress, status = status, note = note)
            Box(Modifier.align(Alignment.TopStart).padding(12.dp)) {
                OverlayButton(PlayerIcons.ArrowBack, s.playerBackHint, onBack)
            }
        }
    }
}

private const val SLOW_MS = 3_000L
private const val TICK_MS = 500L

/** What mpv likes in hand before it starts, as the line's whole length. */
private const val BUFFER_TARGET_MS = 5_000L
