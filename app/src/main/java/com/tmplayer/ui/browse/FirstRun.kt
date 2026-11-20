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
import androidx.compose.material3.IconButton
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.tmplayer.data.SettingsStore
import com.tmplayer.online.MetaNotice
import com.tmplayer.online.OnlineMetadata
import com.tmplayer.ui.components.LocalBottomChrome
import com.tmplayer.ui.components.PhonePad
import com.tmplayer.ui.components.TmSecondaryButton
import com.tmplayer.ui.components.TvConfirm
import com.tmplayer.ui.components.isTouch
import com.tmplayer.ui.i18n.LocalStrings
import com.tmplayer.ui.icons.TmIcons
import com.tmplayer.ui.theme.Corner
import com.tmplayer.ui.theme.Tone
import com.tmplayer.ui.theme.floatingSurface
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Home on a first run, before anything has been played or starred: how to get a first video in
 * (forward it to Saved Messages), with the button that opens it, and the line on starring chats
 * under it, all on one [FirstVideoCard]. It used to be one muted sentence; the forwarding advice lived only
 * in Saved Messages' own empty state, where nobody new would look.
 */
@Composable
internal fun FirstVideoEmpty(onOpen: (BrowseSection) -> Unit) {
    val s = LocalStrings.current
    val touch = isTouch()
    val focus = remember { FocusRequester() }
    // Scrolls, because a phone on its side has less height than the words and the button need,
    // and ends above the floating bar: large text had put the card's last line under the bar,
    // where a page short enough not to scroll could never bring it out (iPhone 14, 2026-11-15).
    val side = if (touch) PhonePad.Side else 72.dp
    Box(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(start = side, end = side, top = 24.dp, bottom = 24.dp + LocalBottomChrome.current),
        contentAlignment = Alignment.Center,
    ) {
        com.tmplayer.ui.browse.HomeEmptyCard(onOpenSaved = { onOpen(BrowseSection.of(BrowseTab.Saved)) }, focus = focus)
    }
    if (!touch) LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
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
internal fun FirstVideoTip(nothingPlayed: Boolean, start: Dp, end: Dp) {
    if (!nothingPlayed) return
    // Chats already chosen: forwarding a video to Saved Messages is not the advice (ON-15).
    val picked by com.tmplayer.data.AddedChats.choice.collectAsState()
    if (picked.mode == com.tmplayer.data.AddedMode.Picked) return
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
            TmIcons.Send,
            contentDescription = null,
            tint = Tone.accent,
            modifier = Modifier.size(if (touch) 20.dp else 24.dp),
        )
        Spacer(Modifier.size(12.dp))
        Text(
            s.browseFirstVideoTip,
            style = if (touch) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.bodyLarge,
            color = Tone.text,
            modifier = Modifier.weight(1f),
        )
        if (touch) {
            com.tmplayer.ui.components.IconTip(s.commonDismiss) {
                IconButton(onClick = { scope.launch { runCatching { settings.setFirstVideoTipDismissed() } } }) {
                    Icon(TmIcons.Close, contentDescription = s.commonDismiss, tint = Tone.muted)
                }
            }
        }
    }
}

/** The promo build's say over the online metadata note: null follows the stored state. */
object MetaNoticeOverride {
    @Volatile
    var show: Boolean? = null

    /** The same for the invite to turn lookups on ([OnlineMetaInvite]). */
    @Volatile
    var invite: Boolean? = null
}

/**
 * The one-time invite for a viewer whose online metadata is off from an older version, when off
 * was the default (see [MetaNotice.shouldInvite]). The app's own prompt ([TvConfirm]), with Turn
 * on focused first on a television, since it is the press that adds rather than removes. Not now,
 * Back or a tap outside all answer it, and it is never asked again. [onAnswered] runs after, for
 * the grid to hand a remote back to a video.
 */
@Composable
internal fun OnlineMetaInvite(onAnswered: () -> Unit = {}) {
    val online = OnlineMetadata.current ?: return
    val context = LocalContext.current
    val settings = remember { SettingsStore(context.applicationContext) }
    val meta by online.settings.collectAsState()
    // Seen until the store answers, so it never flashes up for somebody who already answered.
    val seen by settings.onlineMetaInviteSeen.collectAsState(initial = true)
    var answered by remember { mutableStateOf(false) }
    val due = MetaNoticeOverride.invite ?: MetaNotice.shouldInvite(meta, seen)
    if (!due || answered) return
    val s = LocalStrings.current
    val scope = rememberCoroutineScope()
    fun answer(turnOn: Boolean) {
        answered = true
        MetaNoticeOverride.invite = null
        if (turnOn) online.setEnabled(true)
        scope.launch {
            runCatching { settings.markOnlineMetaInviteSeen(turnedOn = turnOn) }
            onAnswered()
        }
    }
    TvConfirm(
        title = s.metadataInviteTitle,
        message = s.metadataInviteBody,
        confirmLabel = s.metadataInviteTurnOn,
        cancelLabel = s.metadataInviteNotNow,
        onConfirm = { answer(turnOn = true) },
        onDismiss = { answer(turnOn = false) },
        destructive = false,
        icon = TmIcons.Image,
        focusConfirm = true,
        // The three services that answer, as their tiles (HX93); drawn only, never a stop of their own.
        extra = {
            com.tmplayer.ui.components.ServiceBadgeRow(
                listOf(
                    com.tmplayer.ui.components.Service.Tmdb,
                    com.tmplayer.ui.components.Service.TvMaze,
                    com.tmplayer.ui.components.Service.AniList,
                ),
            )
        },
    )
}

/** Whether the grid's note on online metadata is due: lookups on and the note not put away. */
@Composable
internal fun metaNoticeShown(): Boolean {
    val online = OnlineMetadata.current ?: return false
    val context = LocalContext.current
    val settings = remember { SettingsStore(context.applicationContext) }
    val meta by online.settings.collectAsState()
    // True until the store answers, so the note never flashes up for somebody who put it away.
    val dismissed by settings.onlineMetaNoticeDismissed.collectAsState(initial = true)
    return MetaNoticeOverride.show ?: MetaNotice.shouldShow(meta.enabled, dismissed)
}

/**
 * The chat grid's one-time note on online metadata: posters and overviews are looked up by each
 * video's cleaned title, and nothing else is sent. Lookups are on by default, so this says so the
 * first time a grid shows, styled like [FirstVideoTip]. Keep puts it away; Turn off switches the
 * lookups off as Settings, Online metadata does, puts it away and hands [onTurnedOff] the line
 * saying where to turn them back on. On a television both are buttons the D-pad reaches by going
 * up from the first row; nothing here asks for focus, so the first video keeps it. [onDismissed]
 * runs after either, for the grid to put focus back on a video. Drawn only while
 * [metaNoticeShown] says so, which the grid asks first so a hidden note leaves no empty row.
 */
@Composable
internal fun OnlineMetaNotice(
    start: Dp,
    end: Dp,
    onTurnedOff: (String) -> Unit,
    onDismissed: () -> Unit = {},
) {
    val online = OnlineMetadata.current ?: return
    val context = LocalContext.current
    val settings = remember { SettingsStore(context.applicationContext) }
    val s = LocalStrings.current
    val touch = isTouch()
    val scope = rememberCoroutineScope()
    fun dismiss() {
        MetaNoticeOverride.show = null
        scope.launch {
            runCatching { settings.setOnlineMetaNoticeDismissed() }
            onDismissed()
        }
    }
    val keep = { dismiss() }
    val turnOff = {
        online.setEnabled(false)
        dismiss()
        onTurnedOff(s.gridMetaNoticeTurnedOff)
    }
    val text: @Composable (Modifier) -> Unit = { modifier ->
        Text(
            s.gridMetaNotice,
            style = if (touch) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.bodyLarge,
            color = Tone.text,
            modifier = modifier,
        )
    }
    val icon: @Composable () -> Unit = {
        Icon(
            TmIcons.Image,
            contentDescription = null,
            tint = Tone.accent,
            modifier = Modifier.size(if (touch) 20.dp else 24.dp),
        )
    }
    val card = Modifier
        .fillMaxWidth()
        .padding(start = start, end = end, top = if (touch) 4.dp else 0.dp, bottom = if (touch) 8.dp else 12.dp)
        .clip(RoundedCornerShape(Corner.Medium))
        .background(Tone.surfaceHigh)
    if (touch) {
        // A phone's line wraps, so the two actions sit under it at the end, as a dialog's do.
        Column(card.padding(start = 16.dp, end = 4.dp, top = 12.dp)) {
            Row(Modifier.padding(end = 12.dp), verticalAlignment = Alignment.Top) {
                icon()
                Spacer(Modifier.size(12.dp))
                text(Modifier.weight(1f))
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = turnOff) { Text(s.gridMetaNoticeTurnOff) }
                TextButton(onClick = keep) { Text(s.gridMetaNoticeKeep) }
            }
        }
    } else {
        Row(
            card.padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            icon()
            text(Modifier.weight(1f))
            TmSecondaryButton(onClick = turnOff) { Text(s.gridMetaNoticeTurnOff) }
            TmSecondaryButton(onClick = keep) { Text(s.gridMetaNoticeKeep) }
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
 * It never takes focus. [onBusy] is true from the start until the hint is over (or was seen on an
 * earlier run, or is left), so a card that would stack with it (the tour offer, D-1) waits its turn.
 */
@Composable
internal fun TvBrowseHint(keys: KeyPresses, modifier: Modifier = Modifier, onBusy: (Boolean) -> Unit = {}) {
    val context = LocalContext.current
    val settings = remember { SettingsStore(context.applicationContext) }
    var showing by remember { mutableStateOf(false) }
    var shownAt by remember { mutableStateOf(0L) }
    val busy by rememberUpdatedState(onBusy)
    LaunchedEffect(Unit) {
        onBusy(true)
        try {
            if (runCatching { settings.tvBrowseHintSeenNow() }.getOrDefault(true)) return@LaunchedEffect
            // After the chat list has drawn, so it does not land on a loading screen.
            delay(TV_HINT_DELAY_MS)
            runCatching { settings.markTvBrowseHintSeen() }
            shownAt = System.currentTimeMillis()
            showing = true
            // Up for TV_HINT_MS, or until a key put it away.
            withTimeoutOrNull(TV_HINT_MS) { snapshotFlow { showing }.first { !it } }
            showing = false
        } finally {
            // Done, or left before it finished: either way nothing is waiting on it any more.
            busy(false)
        }
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

