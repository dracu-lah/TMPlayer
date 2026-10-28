package com.tmplayer.desktop.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.tmplayer.data.MediaItem
import com.tmplayer.data.OpeningStall
import com.tmplayer.data.Td
import com.tmplayer.data.Thumbnails
import com.tmplayer.player.OpeningProgress
import com.tmplayer.player.SpeedMeter
import com.tmplayer.player.StreamStats
import com.tmplayer.ui.i18n.LocalStrings
import com.tmplayer.ui.icons.TmIcons
import com.tmplayer.ui.player.PlayerLoader
import com.tmplayer.ui.player.rememberLoaderContent
import kotlinx.coroutines.delay

/**
 * The desktop's pre-roll: the shared [PlayerLoader] over the whole window while [phase] is
 * [Phase.Loading], faded off when playback begins. A back arrow sits in the corner for the mouse;
 * Escape and the mouse's back button keep working as before.
 *
 * The line is [OpeningProgress]'s, filled in stages from [signals] (bytes handed to the player,
 * the engine opened, the buffer ahead) and reaching the end as the sheet goes, on the first frame.
 * During a whole-file download before playing it is the file's share instead. [downloaded] is how
 * much of the file is on the disk; it and the bytes read feed the wait's speed note, shown after
 * [OpeningProgress.SPEED_NOTE_AFTER_MS] once it means something.
 *
 * Both also tell it whether anything is arriving. After [OpeningStall.STALL_MS] with neither
 * moving, the sheet says so over the loader, in the words the line to Telegram earns, with Try
 * again ([onRetry], focused), Download first ([onDownloadFirst], null where the chat does not allow
 * keeping a copy) and Back. TDLib does not fail a download it cannot begin, so without this a
 * blocked network left the loader on "Starting" for good.
 */
@Composable
internal fun BoxScope.LoaderSheet(
    phase: Phase,
    item: MediaItem,
    downloaded: Float?,
    /** Read live, by the wait's loop and the bar while the sheet is up, not by the caller. */
    bufferedMs: () -> Long,
    isDownload: Boolean,
    onBack: () -> Unit,
    /** The picture to use without metadata; the fixture passes one, the app reads the thumbnail. */
    thumbnailOverride: ImageBitmap? = null,
    onRetry: (() -> Unit)? = null,
    onDownloadFirst: (() -> Unit)? = null,
    /** Holds the clock for a test: the sheet as it is once the wait has stalled. */
    stalledOverride: Boolean? = null,
    /** "Play Telegram's 480p" (CP80): the next lighter copy's label and its press; null for none. */
    lighter: Pair<String, () -> Unit>? = null,
    /**
     * False where nothing counts the bytes (the browser, whose page fetches them): the slow
     * wait's note could only ever say "slow" there, so it is left off.
     */
    speedNotes: Boolean = true,
    /** What the opening can measure now, read a few times a second; null for nothing (the line eases). */
    signals: (() -> OpeningProgress.Signals)? = null,
    /** Where the video starts, for "Finding 13:54"; 0 for the start. */
    startMs: Long = 0,
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

    // The speed, from the bytes on the disk and the bytes handed to the player, for the slow wait's note.
    val meter = remember(item.fileId) { SpeedMeter() }
    val readMeter = remember(item.fileId) { SpeedMeter() }
    var note by remember(item.fileId) { mutableStateOf<String?>(null) }
    val fraction by rememberUpdatedState(downloaded)
    val real by rememberUpdatedState(isDownload)
    val buffered by rememberUpdatedState(bufferedMs)
    val measure by rememberUpdatedState(signals)
    val seeks by rememberUpdatedState(startMs > 0)
    // The line, in stages ([OpeningProgress]); a new one for each wait.
    var opening by remember(item.fileId) { mutableStateOf(OpeningProgress()) }
    var line by remember(item.fileId) { mutableStateOf(0f) }
    var stage by remember(item.fileId) { mutableStateOf(OpeningProgress.Stage.Connecting) }
    var stalled by remember(item.fileId) { mutableStateOf(false) }
    // Bumped by Try again, so the count starts over though the sheet never left Loading.
    var tries by remember(item.fileId) { mutableStateOf(0) }
    LaunchedEffect(item.fileId, loading != null, tries) {
        stalled = false
        if (loading == null) {
            line = opening.finish()
            return@LaunchedEffect
        }
        if (opening.value >= 1f) {
            opening = OpeningProgress()
            line = 0f
            stage = OpeningProgress.Stage.Connecting
        }
        val started = System.currentTimeMillis()
        var lastAt = started
        var lastFraction = fraction
        var lastBuffered = buffered()
        var lastRead = 0L
        var nudged = false
        readMeter.reset()
        while (true) {
            val now = System.currentTimeMillis()
            val sig = measure?.invoke() ?: OpeningProgress.Signals()
            val bufferedNow = buffered()
            if (fraction != lastFraction || bufferedNow > lastBuffered || sig.readBytes > lastRead) {
                lastAt = now
                lastFraction = fraction
                lastBuffered = bufferedNow
                lastRead = sig.readBytes
            }
            opening.seeks = seeks
            line = opening.update(sig, now)
            stage = opening.stage
            if (!nudged && OpeningStall.nudgeDue(local = (fraction ?: 0f) >= 1f, lastProgressAtMs = lastAt, nowMs = now)) {
                nudged = true
                com.tmplayer.data.Td.nudgeForStream()
            }
            stalled = OpeningStall.stalled(opening = true, local = (fraction ?: 0f) >= 1f, lastProgressAtMs = lastAt, nowMs = now)
            fraction?.let { meter.sample((it * item.sizeBytes).toLong(), now) }
            readMeter.sample(sig.readBytes, now)
            val rate = maxOf(meter.bytesPerSec, readMeter.bytesPerSec)
            // The line as measured while bytes move, for choosing a lighter copy next time (CP80).
            // TDLib's bytes only: the player's reads include the start already on the disk.
            if (fraction != null && fraction != 1f && now == lastAt) com.tmplayer.player.LineSpeed.note(meter.bytesPerSec)
            note = if (!speedNotes) {
                null
            } else if (now - started > SLOW_MS && rate >= StreamStats.MIN_MEANINGFUL_SPEED && (fraction ?: 0f) < 1f) {
                val figure = s.formatter.speed(rate)
                val said = if (real) s.playerDownloadingRate(figure) else s.playerCachingRate(figure)
                // Telegram's own cap on an account without Premium (CP83): said, nothing offered.
                if (com.tmplayer.data.Td.speedLimitedNow()) said + "\n" + s.playerSpeedLimited else said
            } else if (now - started > SLOW_MS && (fraction ?: 0f) < 1f && rate < StreamStats.MIN_MEANINGFUL_SPEED) {
                // Nothing measurable arriving yet: said quietly, long before the stall sheet.
                s.connectSlowTitle
            } else {
                null
            }
            delay(TICK_MS)
        }
    }

    val startClock = startMs.takeIf { it > 0 }?.let { SeekMath.clock(it) }
    val routine = shown.message == s.playerOpeningVideo || shown.message == s.playerConnectingTelegram ||
        (startClock != null && shown.message == s.playerResumingFrom(startClock))
    // The stage's own words: "Starting", "Reading the video", "Finding 13:54", "Buffering".
    val status = if (routine) OpeningProgress.wordsFor(stage, s, startClock) else shown.message
    // A whole-file download before playing counts the file; anything else is the staged line.
    val wholeFile = shown.message.startsWith(s.playerDownloadingWholeVideo)
    val progress = if (wholeFile) downloaded ?: 0f else line

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
            if (stalledOverride ?: stalled) {
                StalledPanel(
                    onRetry = {
                        tries++
                        onRetry?.invoke()
                    },
                    onDownloadFirst = onDownloadFirst,
                    onBack = onBack,
                    lighter = lighter,
                )
            }
            Box(Modifier.align(Alignment.TopStart).padding(12.dp)) {
                OverlayButton(TmIcons.ArrowBack, s.playerBackHint, onBack)
            }
        }
    }
}

/**
 * A stall once the video is playing: the spinner over the picture, and, once nothing has arrived
 * for [OpeningStall.STALL_MS], the same panel as the opening's, worded for a video that stopped
 * arriving. Bytes again (the buffer or the file growing) take it away.
 */
@Composable
internal fun BoxScope.PlaybackStall(
    buffering: Boolean,
    /** Read live by the wait's loop, so the caller does not recompose on every tick. */
    bufferedMs: () -> Long,
    downloaded: Float?,
    onRetry: () -> Unit,
    onDownloadFirst: (() -> Unit)?,
    onBack: () -> Unit,
    lighter: Pair<String, () -> Unit>? = null,
) {
    val fraction by rememberUpdatedState(downloaded)
    val buffered by rememberUpdatedState(bufferedMs)
    var stalled by remember { mutableStateOf(false) }
    var tries by remember { mutableStateOf(0) }
    LaunchedEffect(buffering, tries) {
        stalled = false
        if (!buffering) return@LaunchedEffect
        var lastAt = System.currentTimeMillis()
        var lastFraction = fraction
        var lastBuffered = buffered()
        var nudged = false
        while (true) {
            val now = System.currentTimeMillis()
            val bufferedNow = buffered()
            if (fraction != lastFraction || bufferedNow > lastBuffered) {
                lastAt = now
                lastFraction = fraction
                lastBuffered = bufferedNow
            }
            if (!nudged && OpeningStall.nudgeDue(local = (fraction ?: 0f) >= 1f, lastProgressAtMs = lastAt, nowMs = now)) {
                nudged = true
                com.tmplayer.data.Td.nudgeForStream()
            }
            stalled = OpeningStall.stalled(opening = true, local = (fraction ?: 0f) >= 1f, lastProgressAtMs = lastAt, nowMs = now)
            delay(TICK_MS)
        }
    }
    if (buffering && stalled) {
        StalledPanel(onRetry = { tries++; onRetry() }, onDownloadFirst = onDownloadFirst, onBack = onBack, midPlay = true, lighter = lighter)
    }
}

/** Over the loader once nothing has arrived for a while: why, and the three ways on. */
@Composable
private fun BoxScope.StalledPanel(
    onRetry: () -> Unit,
    onDownloadFirst: (() -> Unit)?,
    onBack: () -> Unit,
    midPlay: Boolean = false,
    lighter: Pair<String, () -> Unit>? = null,
) {
    val s = LocalStrings.current
    val link by Td.link.collectAsState()
    val focus = remember { FocusRequester() }
    Box(Modifier.matchParentSize().background(Color.Black.copy(alpha = 0.72f)), contentAlignment = Alignment.Center) {
        Column(
            Modifier.widthIn(max = 520.dp).padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(if (midPlay) s.playerStalledMidTitle else s.playerStalledTitle, style = MaterialTheme.typography.headlineSmall, color = Color.White, textAlign = TextAlign.Center)
            Text(
                when (OpeningStall.reason(link)) {
                    OpeningStall.Reason.NoLine -> s.playerStalledNoLine
                    OpeningStall.Reason.Slow -> if (midPlay) s.playerStalledMidSlow else s.playerStalledSlow
                },
                color = Color.White.copy(alpha = 0.8f),
                textAlign = TextAlign.Center,
            )
            // Not Telegram's cap on an account without Premium (CP83): it slows a download and
            // never stops it, and this panel is for nothing arriving, so the slow line says it.
            val slow = OpeningStall.reason(link) == OpeningStall.Reason.Slow
            if (slow && lighter != null) {
                Text(s.playerLighterHint, color = Color.White.copy(alpha = 0.65f), textAlign = TextAlign.Center)
            }
            if (onDownloadFirst != null) {
                Text(s.playerStalledDownloadHint, color = Color.White.copy(alpha = 0.65f), textAlign = TextAlign.Center)
            }
            Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = onRetry, modifier = Modifier.focusRequester(focus)) { Text(s.commonTryAgain) }
                if (slow && lighter != null) {
                    OutlinedButton(onClick = lighter.second) { Text(lighter.first, color = Color.White) }
                }
                if (onDownloadFirst != null) {
                    OutlinedButton(onClick = onDownloadFirst) { Text(s.playerDownloadFirst, color = Color.White) }
                }
                TextButton(onClick = onBack) { Text(s.playerGoBack, color = Color.White) }
            }
        }
    }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
}

private const val SLOW_MS = OpeningProgress.SPEED_NOTE_AFTER_MS
private const val TICK_MS = 250L
