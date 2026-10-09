package com.tmplayer.ui.browse

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.tmplayer.data.SettingsStore
import com.tmplayer.ui.components.BottomCardInset
import com.tmplayer.ui.components.PhonePad
import com.tmplayer.ui.components.TmButton
import com.tmplayer.ui.components.TmIcons
import com.tmplayer.ui.components.isTouch
import com.tmplayer.ui.i18n.LocalStrings
import com.tmplayer.ui.theme.Corner
import com.tmplayer.ui.theme.Tone
import com.tmplayer.ui.theme.floatingSurface
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Home on a first run, before anything has been played or starred: how to get a first video in
 * (forward it to Saved Messages, or with the default groups hidden to a chat in a folder), with
 * the button that opens it ([DefaultGroups.firstStep]), and the line on starring chats under it. It used to be one muted sentence; the forwarding advice lived only in
 * Saved Messages' own empty state, where nobody new would look.
 */
@Composable
internal fun FirstVideoEmpty(step: DefaultGroups.FirstStep, onOpen: (BrowseSection) -> Unit) {
    val s = LocalStrings.current
    val touch = isTouch()
    val focus = remember { FocusRequester() }
    // Scrolls, because a phone on its side has less height than the words and the button need.
    Box(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = if (touch) PhonePad.Side else 72.dp, vertical = 24.dp)
            .padding(bottom = BottomCardInset.height),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(if (touch) 12.dp else 16.dp),
        ) {
            Icon(
                TmIcons.Bookmark,
                contentDescription = null,
                tint = Tone.accent,
                modifier = Modifier.size(if (touch) 44.dp else 56.dp),
            )
            val measure = Modifier.widthIn(max = if (touch) 420.dp else 600.dp)
            Text(
                s.homeFirstTitle,
                style = if (touch) MaterialTheme.typography.titleLarge else MaterialTheme.typography.headlineSmall,
                color = Tone.text,
                textAlign = TextAlign.Center,
                modifier = measure,
            )
            Text(
                step.body,
                style = if (touch) MaterialTheme.typography.bodyLarge else MaterialTheme.typography.titleMedium,
                color = Tone.muted,
                textAlign = TextAlign.Center,
                modifier = measure,
            )
            // With Telegram's default groups hidden and no folders there is nowhere to send the
            // viewer, so the words say what to do and there is no button.
            val target = step.target
            val button = step.button
            if (button != null && target != null) {
                Spacer(Modifier.size(4.dp))
                TmButton(onClick = { onOpen(target) }, modifier = Modifier.focusRequester(focus)) { Text(button) }
                Spacer(Modifier.size(4.dp))
            } else {
                Spacer(Modifier.size(4.dp))
            }
            Text(
                s.homeFirstMore,
                style = if (touch) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.bodyLarge,
                color = Tone.muted,
                textAlign = TextAlign.Center,
                modifier = measure,
            )
        }
    }
    if (!touch && step.target != null) LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
}

/** The promo build's say over the tip: null follows the stored state, true or false forces it. */
object FirstVideoTipOverride {
    @Volatile
    var show: Boolean? = null
}

/**
 * The chat list's first-run tip: forward a video to Saved Messages. Shown above the chats until
 * anything has been played ([nothingPlayed] false) or, on a phone, until it is closed. On a
 * television it is a line of text the D-pad passes over, and playing a first video retires it.
 */
@Composable
internal fun FirstVideoTip(nothingPlayed: Boolean, start: Dp, end: Dp, defaultsHidden: Boolean = false) {
    if (!nothingPlayed) return
    val context = LocalContext.current
    val settings = remember { SettingsStore(context.applicationContext) }
    val stored by settings.firstVideoTipDismissed.collectAsState(initial = true)
    val dismissed = FirstVideoTipOverride.show?.not() ?: stored
    if (dismissed) return
    val s = LocalStrings.current
    val touch = isTouch()
    val scope = rememberCoroutineScope()
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = start, end = end, top = if (touch) 4.dp else 0.dp, bottom = if (touch) 8.dp else 12.dp)
            .clip(RoundedCornerShape(Corner.Medium))
            .background(Tone.surfaceHigh)
            .padding(start = 16.dp, end = if (touch) 4.dp else 16.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.AutoMirrored.Filled.Send,
            contentDescription = null,
            tint = Tone.accent,
            modifier = Modifier.size(if (touch) 20.dp else 24.dp),
        )
        Spacer(Modifier.size(12.dp))
        Text(
            DefaultGroups.firstVideoTip(defaultsHidden),
            style = if (touch) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.bodyLarge,
            color = Tone.text,
            modifier = Modifier.weight(1f),
        )
        if (touch) {
            IconButton(onClick = { scope.launch { runCatching { settings.setFirstVideoTipDismissed() } } }) {
                Icon(Icons.Filled.Close, contentDescription = s.commonDismiss, tint = Tone.muted)
            }
        }
    }
}

/**
 * Key presses anywhere in the shell, for a hint that goes at the next one. Plain listeners rather
 * than state, so a press does not recompose the screen that hears it.
 */
class KeyPresses {
    private val listeners = mutableListOf<() -> Unit>()

    fun fire() {
        if (listeners.isNotEmpty()) listeners.toList().forEach { it() }
    }

    internal fun add(listener: () -> Unit) {
        listeners += listener
    }

    internal fun remove(listener: () -> Unit) {
        listeners -= listener
    }
}

/**
 * The television's one-time browse hint: OK opens, hold OK for favourites, Back goes up a level.
 * The tour no longer teaches the remote; this says it once, over the first chat list, and goes
 * after a while, or at a key pressed once it has been up long enough to read the start of it.
 * It never takes focus.
 */
@Composable
internal fun TvBrowseHint(keys: KeyPresses, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val settings = remember { SettingsStore(context.applicationContext) }
    var showing by remember { mutableStateOf(false) }
    var shownAt by remember { mutableStateOf(0L) }
    LaunchedEffect(Unit) {
        if (runCatching { settings.tvBrowseHintSeenNow() }.getOrDefault(true)) return@LaunchedEffect
        // After the chat list has drawn, so it does not land on a loading screen.
        delay(TV_HINT_DELAY_MS)
        runCatching { settings.markTvBrowseHintSeen() }
        shownAt = System.currentTimeMillis()
        showing = true
        delay(TV_HINT_MS)
        showing = false
    }
    DisposableEffect(keys) {
        val hide = { if (showing && System.currentTimeMillis() - shownAt > TV_HINT_MIN_MS) showing = false }
        keys.add(hide)
        onDispose { keys.remove(hide) }
    }
    AnimatedVisibility(showing, modifier = modifier, enter = fadeIn(), exit = fadeOut()) { TvBrowseHintCard() }
}

/** What [TvBrowseHint] shows; the promo build draws it on its own for screenshots. */
@Composable
fun TvBrowseHintCard(modifier: Modifier = Modifier) {
    val s = LocalStrings.current
    Row(
        modifier
            .widthIn(max = 640.dp)
            .floatingSurface()
            .padding(horizontal = 24.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(TmIcons.Remote, contentDescription = null, tint = Tone.accent, modifier = Modifier.size(28.dp))
        Spacer(Modifier.size(16.dp))
        Text(s.tvBrowseHint, style = MaterialTheme.typography.bodyLarge, color = Tone.text)
    }
}

private const val TV_HINT_DELAY_MS = 1_500L
private const val TV_HINT_MS = 12_000L

/** A key pressed sooner than this after the hint came up is the viewer already moving, not dismissing. */
private const val TV_HINT_MIN_MS = 3_000L

