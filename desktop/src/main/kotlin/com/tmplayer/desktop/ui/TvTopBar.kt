package com.tmplayer.desktop.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.foundation.focusGroup
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tmplayer.data.Account
import com.tmplayer.ui.browse.BrowseSection
import com.tmplayer.ui.browse.BrowseTab
import com.tmplayer.ui.browse.onChatsPage
import com.tmplayer.ui.i18n.LocalStrings
import com.tmplayer.ui.icons.TmIcons
import com.tmplayer.ui.theme.Motion
import com.tmplayer.ui.theme.Tone
import com.tmplayer.ui.theme.Tv
import com.tmplayer.ui.theme.collectIsFocusVisibleAsState
import com.tmplayer.ui.theme.focusRing
import com.tmplayer.ui.theme.rememberReducedMotion

/**
 * The big-screen mode's bar (plan 8.7), the Android TV app's TvTopBar on a computer: the viewer's
 * picture (their lists and Settings, as the floating bar's picture opens them), Search, Home and
 * Chats, and the app's mark at the end, across the top of the window inside the television's safe
 * area. It takes the floating bar's place; the page sits under it.
 *
 * The remote: Left and Right walk the bar, Down goes into the page onto the item the remote was
 * last on there ([ShellState.focusPage]), and Back from a page comes up to the page's own item
 * ([ShellState.focusBar]). The focused item takes the accent's fill and grows a little, drawn only
 * while focus is shown ([com.tmplayer.ui.theme.FocusVisibility]), so a click never leaves one lit.
 */
@Composable
internal fun TvTopBar(
    state: ShellState,
    update: NavUpdate?,
    account: Account?,
    onPicturePlaced: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val s = LocalStrings.current
    val bar = state.tvBar
    val current = navPlaceOf(state)
    val inFlight = rememberDownloadsInFlight()
    DisposableEffect(bar) {
        bar.shown = true
        onDispose {
            bar.shown = false
            bar.focused = false
        }
    }
    // Leaving the picture's menu puts the remote back on the picture, as a menu closing hands
    // focus back to its button; a place picked from it does the same, its page being one of the
    // picture's.
    var wasOpen by remember { mutableStateOf(false) }
    LaunchedEffect(state.profileOpen) {
        if (wasOpen && !state.profileOpen) runCatching { bar.picture.requestFocus() }
        wasOpen = state.profileOpen
    }
    // The search across chats is the bar's Search: closing it lands back there.
    var paletteWasOpen by remember { mutableStateOf(false) }
    LaunchedEffect(state.paletteOpen) {
        if (paletteWasOpen && !state.paletteOpen && state.nowPlaying == null) runCatching { bar.search.requestFocus() }
        paletteWasOpen = state.paletteOpen
    }
    Box(
        modifier
            .fillMaxWidth()
            .height(TV_BAR_HEIGHT)
            .background(Tone.background),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = Tv.SafeH, end = TV_BAR_END, top = Tv.SafeV)
                .height(TV_BAR_ROW)
                .onFocusChanged { bar.focused = it.hasFocus }
                // Entering the bar from the page with an arrow lands on the page's own item, never
                // on whichever item happens to sit above the one the remote was on. A request for
                // one item by name (the picture, after its menu) is left alone.
                .focusProperties {
                    onEnter = {
                        if (requestedFocusDirection != FocusDirection.Enter) {
                            runCatching { bar.requesterFor(navPlaceOf(state)).requestFocus() }
                        }
                    }
                }
                // Down goes into the page, onto the item the remote was last on there.
                .onPreviewKeyEvent { event ->
                    if (event.type == KeyEventType.KeyDown && event.key == Key.DirectionDown && !state.profileOpen) {
                        state.focusPage()
                    } else {
                        false
                    }
                }
                .focusGroup(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TvPicture(
                account = account,
                chosen = current == null && !state.paletteOpen,
                open = state.profileOpen,
                badge = when {
                    update != null -> Tone.caution
                    inFlight > 0 -> Tone.accent
                    else -> null
                },
                label = s.navProfile,
                onClick = { state.profileOpen = !state.profileOpen },
                modifier = Modifier
                    .focusRequester(bar.picture)
                    .onGloballyPositioned { onPicturePlaced(it.boundsInRoot().center.x) },
            )
            Spacer(Modifier.width(18.dp))
            Row(
                Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TvTab(s.commonSearch, TmIcons.Search, current == NavPlace.Search, Modifier.focusRequester(bar.search)) {
                    state.paletteOpen = !state.paletteOpen
                }
                TvTab(s.browseTabHome, TmIcons.Home, current == NavPlace.Home, Modifier.focusRequester(bar.home)) {
                    state.go(Destination.Home)
                }
                TvTab(s.navChats, TmIcons.Chats, current == NavPlace.Chats, Modifier.focusRequester(bar.chats)) {
                    val open = state.chatSection
                    state.showChats(if (open.onChatsPage) open else BrowseSection.of(BrowseTab.Chats))
                }
            }
            Spacer(Modifier.width(18.dp))
            // The app's mark and its name, as a television's home screen signs itself.
            com.tmplayer.ui.components.BrandLockup(
                size = com.tmplayer.ui.components.BrandSize(TV_CLUSTER_ITEM, TV_BRAND_NAME, 12.dp),
                layout = com.tmplayer.ui.components.BrandLayout.Row,
            )
            // A window the app draws the buttons of (Windows, a tiling window manager), when it is
            // not full screen: minimise, maximise and close at the bar's end, for the mouse. Full
            // screen has none (Main.kt), the remote's way out being Back.
            if (LocalWindowChrome.current != null) {
                RegisterWindowControls()
                Spacer(Modifier.width(12.dp))
                WindowControls()
            }
        }
    }
}

/** One of the bar's places: its icon and name in a pill, the icon filled while its page is shown. */
@Composable
private fun TvTab(label: String, icon: ImageVector, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val interactions = remember { MutableInteractionSource() }
    val focused by interactions.collectIsFocusVisibleAsState()
    val pressed by interactions.collectIsPressedAsState()
    val still = rememberReducedMotion() || Motion.reduced
    val scale by animateFloatAsState(
        when {
            still -> 1f
            pressed -> Motion.PressScale
            focused -> Motion.FocusScale
            else -> 1f
        },
        Motion.standard(Motion.Fast),
        label = "tvTabScale",
    )
    val fill by animateColorAsState(if (focused) Tone.focusFill else Color.Transparent, Motion.standard(Motion.Fast), label = "tvTabFill")
    val ink by animateColorAsState(
        when {
            focused -> Tone.onFocusFill
            selected -> Tone.text
            else -> Tone.muted
        },
        Motion.standard(Motion.Fast),
        label = "tvTabInk",
    )
    Box(modifier, contentAlignment = Alignment.Center) {
        Row(
            Modifier
                .height(TV_CLUSTER_ITEM)
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                }
                .clip(CircleShape)
                .background(fill)
                .focusRing(focused, CircleShape)
                .clickable(interactionSource = interactions, indication = null, role = Role.Tab, onClick = onClick)
                .semantics {
                    contentDescription = label
                    this.selected = selected
                }
                .padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            com.tmplayer.ui.components.SelectableIcon(icon, selected = selected, tint = ink, size = 20.dp)
            Text(
                label,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = if (selected || focused) FontWeight.SemiBold else FontWeight.Medium,
                color = ink,
                maxLines = 1,
            )
        }
    }
}

/** The viewer's picture, first in the bar: opens their lists. Ringed while one of its pages is shown. */
@Composable
private fun TvPicture(
    account: Account?,
    chosen: Boolean,
    open: Boolean,
    badge: Color?,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interactions = remember { MutableInteractionSource() }
    val focused by interactions.collectIsFocusVisibleAsState()
    val pressed by interactions.collectIsPressedAsState()
    val still = rememberReducedMotion() || Motion.reduced
    val lit = focused || open
    val scale by animateFloatAsState(
        when {
            still -> 1f
            pressed -> Motion.PressScale
            lit -> Motion.FocusScale
            else -> 1f
        },
        Motion.standard(Motion.Fast),
        label = "tvPictureScale",
    )
    // A ring in the accent a little way out from the picture, with a thin halo of the page's own
    // ground outside it, so it reads over a pale page and a dark one alike.
    val ring by animateFloatAsState(if (lit) 1f else 0f, Motion.standard(Motion.Fast), label = "tvPictureRing")
    val accent = Tone.accent
    val ground = Tone.background
    Box(modifier, contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .size(TV_CLUSTER_ITEM)
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                }
                .drawBehind {
                    val picture = size.minDimension / 2f
                    if (ring > 0f) {
                        val width = 3.dp.toPx()
                        val gap = 3.dp.toPx()
                        drawCircle(ground.copy(alpha = 0.7f * ring), radius = picture + gap + width + 1.dp.toPx(), style = Stroke(2.dp.toPx()))
                        drawCircle(accent.copy(alpha = ring), radius = picture + gap + width / 2f, style = Stroke(width))
                    } else if (chosen) {
                        drawCircle(accent, radius = picture + 1.dp.toPx(), style = Stroke(2.dp.toPx()))
                    }
                }
                .clip(CircleShape)
                .clickable(interactionSource = interactions, indication = null, role = Role.Button, onClick = onClick)
                .semantics { contentDescription = label },
            contentAlignment = Alignment.Center,
        ) {
            if (account != null) {
                ChatAvatar(account.miniThumbnail, account.photoFileId, account.name, TV_CLUSTER_ITEM)
            } else {
                Box(Modifier.size(TV_CLUSTER_ITEM).background(MaterialTheme.colorScheme.surfaceContainerHighest), contentAlignment = Alignment.Center) {
                    Icon(TmIcons.Account, contentDescription = null, tint = Tone.muted, modifier = Modifier.size(TV_CLUSTER_ITEM * 0.6f))
                }
            }
        }
        if (badge != null) {
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(badge),
            )
        }
    }
}

/** The bar's row: a tab's pill and the picture are [TV_CLUSTER_ITEM] in it. */
private val TV_BAR_ROW = 48.dp

/** The bar from the window's top to the page: the safe area, the row and a gap, as tv.css has it (87 dp). */
internal val TV_BAR_HEIGHT: Dp = Tv.SafeV + TV_BAR_ROW + 12.dp

/** The bar's end inset: the safe area and the room a row keeps for a focused card's ring. */
private val TV_BAR_END: Dp = Tv.SafeH + Tv.FocusClearance

private val TV_CLUSTER_ITEM = 40.dp
private val TV_BRAND_NAME = 22.sp
