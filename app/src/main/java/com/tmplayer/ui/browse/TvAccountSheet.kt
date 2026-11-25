package com.tmplayer.ui.browse

import android.view.WindowManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.tmplayer.data.Account
import com.tmplayer.data.Profile
import com.tmplayer.ui.components.FocusOnOpen
import com.tmplayer.ui.i18n.LocalStrings
import com.tmplayer.ui.icons.TmIcons
import com.tmplayer.ui.theme.Corner
import com.tmplayer.ui.theme.FloatingTone
import com.tmplayer.ui.theme.LocalOnFloating
import com.tmplayer.ui.theme.Motion
import com.tmplayer.ui.theme.Tone
import com.tmplayer.ui.theme.Tv
import com.tmplayer.ui.theme.focusRingOnTv
import com.tmplayer.ui.theme.rememberReducedMotion
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Test tags of the television's account sheet, its rows (`tv-account-row-<id>`) and its version. */
internal const val TV_ACCOUNT_SHEET_TAG = "tv-account-sheet"
internal const val TV_ACCOUNT_ROW_TAG = "tv-account-row"
internal const val TV_ACCOUNT_VERSION_TAG = "tv-account-version"

/** Every size of the sheet, on the 8 dp grid. */
internal val TV_SHEET_ROW = 56.dp
internal val TV_SHEET_ROW_GAP = 4.dp
internal val TV_SHEET_PAD_START = 24.dp
private val ROW_PAD = 20.dp
private val ICON_COLUMN = 28.dp
private val ICON_GAP = 16.dp
private val HEADER_PICTURE = 56.dp
private const val SHEET_IN_MS = 200
private const val SHEET_OUT_MS = 150
private const val SCRIM_ALPHA = 0.6f

/** The sheet's width: 440 dp on a 960 dp screen, growing with it, never under 400 nor over 520. */
internal fun tvSheetWidth(screen: Dp): Dp = (screen * 0.4583f).coerceIn(400.dp, 520.dp)

/**
 * The account menu on a television (user, 2026-11-25): a sheet down the right edge, in place of the
 * centred modal. A dim over the rest of the screen, the sheet sliding in from the right in 200 ms and
 * out in 150 ms (at once with reduced motion), inside the safe area on top, bottom and right. It is a
 * window of its own, so nothing behind it takes the remote.
 *
 * One column the remote walks with Up and Down, stopping at its two ends (no wrap): the header (the
 * picture, the name, the @username; not a stop), then Favourites, History and Watch later, then
 * Settings, Switch or add account (Add account with one profile), Refresh where the page has one and
 * the update when there is one, then the version, the app's tile and "v1.28.7", which opens About.
 * The viewing counts ride on the rows they belong to. Sign out is in Settings, Account, behind the
 * Settings PIN. The first row has the focus on open; Back or Menu close it and the caller puts the
 * remote back on the picture. The focus look is the shared one ([focusRingOnTv] and the focus fill);
 * a long column (a large display size) scrolls under the focus with a fade at the cut edge.
 */
@Composable
internal fun TvAccountSheet(
    account: Account?,
    profile: Profile?,
    name: String,
    username: String?,
    favouriteCount: Int,
    watchingCount: Int,
    watchedCount: Int,
    several: Boolean,
    updateVersion: String?,
    onFavourites: () -> Unit,
    onHistory: () -> Unit,
    onWatchLater: () -> Unit,
    onSettings: () -> Unit,
    onSwitchOrAdd: (() -> Unit)?,
    onRefresh: (() -> Unit)?,
    onUpdate: () -> Unit,
    onAbout: () -> Unit,
    onDismiss: () -> Unit,
    /** The standing sync status as a sentence, a row after Settings that opens the sync switch; null for none. */
    syncNote: String? = null,
    onSyncNote: (() -> Unit)? = null,
) {
    val s = LocalStrings.current
    val reduced = rememberReducedMotion() || Motion.reduced
    val scope = rememberCoroutineScope()
    var shown by remember { mutableStateOf(false) }
    var closing by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { shown = true }
    // Out first, then gone: the sheet leaves in 150 ms and the caller's state follows.
    val close: () -> Unit = {
        if (!closing) {
            closing = true
            shown = false
            scope.launch {
                if (!reduced) delay(SHEET_OUT_MS.toLong())
                onDismiss()
            }
        }
    }
    // A row ends the sheet at once: what it opens is the caller's, and the sheet need not play out.
    val go: (() -> Unit) -> () -> Unit = { action -> { if (!closing) { closing = true; onDismiss(); action() } } }

    Dialog(
        onDismissRequest = close,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        val view = LocalView.current
        val window = (view.parent as? DialogWindowProvider)?.window
        // The scrim is the sheet's own, so it fades with it; the window's dim is off.
        SideEffect { window?.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND) }
        CompositionLocalProvider(LocalOnFloating provides true) {
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val width = tvSheetWidth(maxWidth)
                val first = remember { FocusRequester() }
                AnimatedVisibility(
                    visible = shown,
                    enter = if (reduced) fadeIn(tween(0)) else fadeIn(tween(SHEET_IN_MS)),
                    exit = if (reduced) fadeOut(tween(0)) else fadeOut(tween(SHEET_OUT_MS)),
                ) {
                    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = SCRIM_ALPHA)))
                }
                AnimatedVisibility(
                    visible = shown,
                    modifier = Modifier.align(Alignment.CenterEnd),
                    enter = if (reduced) fadeIn(tween(0)) else slideInHorizontally(tween(SHEET_IN_MS)) { it },
                    exit = if (reduced) fadeOut(tween(0)) else slideOutHorizontally(tween(SHEET_OUT_MS)) { it },
                ) {
                    SheetPanel(
                        width = width,
                        first = first,
                        account = account,
                        profile = profile,
                        name = name,
                        username = username,
                        favouriteCount = favouriteCount,
                        watchingCount = watchingCount,
                        watchedCount = watchedCount,
                        several = several,
                        updateVersion = updateVersion,
                        onFavourites = go(onFavourites),
                        onHistory = go(onHistory),
                        onWatchLater = go(onWatchLater),
                        onSettings = go(onSettings),
                        onSwitchOrAdd = onSwitchOrAdd?.let(go),
                        onRefresh = onRefresh?.let(go),
                        onUpdate = go(onUpdate),
                        onAbout = go(onAbout),
                        onClose = close,
                        title = s.navAccountNamed(name),
                        syncNote = syncNote,
                        onSyncNote = onSyncNote?.let(go),
                    )
                }
                FocusOnOpen(first, key = shown)
            }
        }
    }
}

@Composable
internal fun SheetPanel(
    width: Dp,
    first: FocusRequester,
    account: Account?,
    profile: Profile?,
    name: String,
    username: String?,
    favouriteCount: Int,
    watchingCount: Int,
    watchedCount: Int,
    several: Boolean,
    updateVersion: String?,
    onFavourites: () -> Unit,
    onHistory: () -> Unit,
    onWatchLater: () -> Unit,
    onSettings: () -> Unit,
    onSwitchOrAdd: (() -> Unit)?,
    onRefresh: (() -> Unit)?,
    onUpdate: () -> Unit,
    onAbout: () -> Unit,
    onClose: () -> Unit,
    title: String,
    syncNote: String? = null,
    onSyncNote: (() -> Unit)? = null,
) {
    val s = LocalStrings.current
    val scroll = rememberScrollState()
    val fill = FloatingTone.sheet
    val formatter = s.formatter
    val historyNotes = listOfNotNull(
        watchingCount.takeIf { it > 0 }?.let { s.navTileWatching(formatter.number(it)) },
        watchedCount.takeIf { it > 0 }?.let { s.navTileWatched(formatter.number(it)) },
    ).joinToString("  ·  ").takeIf { it.isNotEmpty() }
    Box(
        Modifier
            .width(width)
            .fillMaxHeight()
            .background(fill)
            .semantics { paneTitle = title }
            .onPreviewKeyEvent { event ->
                // Menu closes it on its release, as it opened it; Back is the dialog's own.
                if (event.key == Key.Menu) {
                    if (event.type == KeyEventType.KeyUp) onClose()
                    true
                } else {
                    false
                }
            }
            .testTag(TV_ACCOUNT_SHEET_TAG),
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .padding(start = TV_SHEET_PAD_START, end = Tv.SafeH, top = Tv.SafeV, bottom = Tv.SafeV),
        ) {
            // The header: who is signed in. Not a stop of the remote.
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = ROW_PAD, vertical = 8.dp)
                    .semantics(mergeDescendants = true) {}
                    .testTag("tv-account-header"),
                horizontalArrangement = Arrangement.spacedBy(ICON_GAP),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AccountPicture(account, profile, size = HEADER_PICTURE)
                Column(Modifier.weight(1f)) {
                    Text(name, style = MaterialTheme.typography.titleLarge, color = Tone.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    if (username != null) {
                        Text("@$username", style = MaterialTheme.typography.bodyMedium, color = Tone.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Box(Modifier.weight(1f, fill = false)) {
                Column(
                    Modifier
                        .verticalScroll(scroll)
                        .padding(vertical = Tv.FocusClearance),
                    verticalArrangement = Arrangement.spacedBy(TV_SHEET_ROW_GAP),
                ) {
                    SheetRow("favourites", TmIcons.StarOutline, s.browseTabFavourites, favouriteCount.takeIf { it > 0 }?.let { s.navTileStarred(formatter.number(it)) }, onFavourites, Modifier.focusRequester(first), isFirst = true)
                    SheetRow("history", TmIcons.History, s.browseTabHistory, historyNotes, onHistory)
                    SheetRow("watch-later", TmIcons.Clock, s.browseTabWatchLater, null, onWatchLater)
                    Divider()
                    SheetRow("settings", TmIcons.Settings, s.navSettings, null, onSettings)
                    // The sync that stands (stopped, needs you): once its pill has left the bar, this row is where it lives.
                    if (syncNote != null && onSyncNote != null) {
                        SheetRow("sync", TmIcons.Warning, syncNote, null, onSyncNote, ink = Tone.caution, labelLines = 2)
                    }
                    if (onSwitchOrAdd != null) {
                        SheetRow("switch", TmIcons.Person, if (several) s.profilesSwitchOrAdd else s.profilesAdd, null, onSwitchOrAdd)
                    }
                    if (onRefresh != null) SheetRow("refresh", TmIcons.Refresh, s.commonRefresh, null, onRefresh)
                    if (updateVersion != null) {
                        // The one row that is news, in amber: the update window is a press away, as on the phone's version row.
                        SheetRow("update", TmIcons.Refresh, s.navUpdate, updateVersion, onUpdate, ink = Tone.caution)
                    }
                    Divider()
                    // The version: the app's tile and "v1.28.7"; OK opens About. The last stop, which Down stops at.
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .onPreviewKeyEvent { event ->
                                if (event.type == KeyEventType.KeyDown && event.key == Key.DirectionDown) true else false
                            }
                            .testTag(TV_ACCOUNT_VERSION_TAG),
                    ) {
                        com.tmplayer.ui.components.VersionMark(
                            info = com.tmplayer.ui.components.rememberVersionInfo(),
                            tv = true,
                            onClick = onAbout,
                            onClickLabel = s.settingsAbout,
                        )
                    }
                }
                // A fade where a long column is cut, top and bottom, only while there is more that way.
                if (scroll.canScrollBackward) Fade(Modifier.align(Alignment.TopCenter), fill, down = true)
                if (scroll.canScrollForward) Fade(Modifier.align(Alignment.BottomCenter), fill, down = false)
            }
        }
    }
}

@Composable
private fun Fade(modifier: Modifier, ground: Color, down: Boolean) {
    Box(
        modifier
            .fillMaxWidth()
            .height(24.dp)
            .background(
                Brush.verticalGradient(
                    if (down) listOf(ground, ground.copy(alpha = 0f)) else listOf(ground.copy(alpha = 0f), ground),
                ),
            ),
    )
}

/** A thin line between two groups: 1 dp, low alpha, 12 dp from the rows either side. */
@Composable
private fun Divider() {
    HorizontalDivider(Modifier.padding(vertical = 12.dp - TV_SHEET_ROW_GAP, horizontal = ROW_PAD), thickness = 1.dp, color = Tone.outline.copy(alpha = 0.5f))
}

/**
 * One row: 56 dp high, 20 dp in on both sides, the icon in a 28 dp column, the label at 18 sp and the
 * sub-label at 14 sp under it. Its fill and text colour under focus are the television's shared ones.
 * The first row stops Up and the version stops Down: the remote never wraps.
 */
@Composable
private fun SheetRow(
    id: String,
    icon: ImageVector,
    label: String,
    sub: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    isFirst: Boolean = false,
    ink: Color = Tone.text,
    /** A long sentence of a row (the sync note) may take two lines of its 56 dp. */
    labelLines: Int = 1,
) {
    val interactions = remember { MutableInteractionSource() }
    // The first row takes the remote as it is laid out: asked from the row itself, so it is there to be asked.
    val own = remember { FocusRequester() }
    if (isFirst) {
        LaunchedEffect(Unit) {
            androidx.compose.runtime.withFrameNanos { }
            runCatching { own.requestFocus() }
        }
    }
    val focused by interactions.collectIsFocusedAsState()
    val shape = RoundedCornerShape(Corner.Medium)
    val background by animateColorAsState(if (focused) Tone.focusFill else Color.Transparent, Motion.standard(Motion.Fast), label = "tvAccountRow")
    val foreground = if (focused) Tone.onFocusFill else ink
    val muted = if (focused) Tone.onFocusFill else Tone.muted
    Row(
        modifier
            .fillMaxWidth()
            .height(TV_SHEET_ROW)
            .focusRequester(own)
            .clip(shape)
            .background(background)
            .focusRingOnTv(focused, shape)
            .onPreviewKeyEvent { event ->
                if (isFirst && event.type == KeyEventType.KeyDown && event.key == Key.DirectionUp) true else false
            }
            .clickable(interactionSource = interactions, indication = null, role = Role.Button, onClick = onClick)
            .semantics { if (sub != null) contentDescription = "$label, $sub" }
            .padding(horizontal = ROW_PAD)
            .testTag("$TV_ACCOUNT_ROW_TAG-$id"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ICON_GAP),
    ) {
        Box(Modifier.size(ICON_COLUMN), contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, tint = foreground, modifier = Modifier.size(ICON_COLUMN))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
            Text(label, style = MaterialTheme.typography.bodyLarge.copy(fontSize = 18.sp, lineHeight = if (labelLines > 1) 22.sp else 24.sp), color = foreground, maxLines = labelLines, overflow = TextOverflow.Ellipsis)
            if (sub != null) {
                Text(sub, style = MaterialTheme.typography.bodySmall.copy(fontSize = 14.sp, lineHeight = 18.sp), color = muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}
