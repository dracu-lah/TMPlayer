package com.tmplayer.desktop.ui

import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.SideEffect
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tmplayer.data.Account
import com.tmplayer.desktop.os.OsInfo
import com.tmplayer.ui.browse.BrowseSection
import com.tmplayer.ui.browse.BrowseTab
import com.tmplayer.ui.browse.HistoryTab
import com.tmplayer.ui.browse.onChatsPage
import com.tmplayer.ui.components.LocalBottomChrome
import com.tmplayer.ui.components.captionStyle
import com.tmplayer.ui.components.pressScale
import com.tmplayer.ui.i18n.LocalStrings
import com.tmplayer.ui.icons.TmIcons
import com.tmplayer.ui.nav.BackHandler
import com.tmplayer.ui.theme.FloatingTone
import com.tmplayer.ui.theme.Motion
import com.tmplayer.ui.theme.Tone
import com.tmplayer.ui.theme.collectIsFocusVisibleAsState
import com.tmplayer.ui.theme.floatingBorder
import com.tmplayer.ui.theme.rememberReducedMotion

/**
 * The window's navigation, the phone's floating bar brought to the desktop: one rounded pill
 * centred at the foot of the page, floating over it with the floating hairline and a soft shadow,
 * holding Home, Chats, Search (the Ctrl+K palette) and the viewer's picture, which opens their own
 * lists (Favourites, Downloads, History, Watch later), then Settings, and Update when there is one:
 * the phone's bar and popover as they are now (Settings left the bar for the picture, 2026-10-21).
 *
 * It replaces the side bar of earlier versions, so the desktop and the phone are one app: the
 * pages get the whole width of the window, and the same places sit in the same order. A pointer
 * gets what a thumb does not need: a short name under every icon and a tooltip naming its key.
 * Every button is reached with Tab and pressed with Enter or Space.
 *
 * [content] is the page. Lists on it pad their ends by [LocalBottomChrome], which this provides
 * while the bar is shown, so their last row can be scrolled clear of it. [barShown] is false where
 * the page brings its own bar to the foot (a chat's), which takes this one's place.
 */
@Composable
fun NavFrame(
    state: ShellState,
    update: NavUpdate?,
    account: Account?,
    modifier: Modifier = Modifier,
    barShown: Boolean = true,
    content: @Composable () -> Unit,
) {
    // The big-screen mode (plan 8.7) puts the TV's bar across the top and the page under it. The
    // page stays at the same place in the tree either way, so a change of layout keeps its state.
    val tv = BigScreen.on
    var pictureX by remember { mutableFloatStateOf(Float.NaN) }
    Box(modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            if (tv && barShown) TvTopBar(state, update, account, onPicturePlaced = { pictureX = it })
            // On a television, one focus group that remembers where the remote was: Down from the
            // bar and the end of a video put it back on that item (ShellState.focusPage).
            Box(Modifier.weight(1f).fillMaxWidth().focusRequester(state.pageFocus).then(if (tv || state.destination.isModal) Modifier.focusRestorer().focusGroup() else Modifier)) {
                CompositionLocalProvider(LocalBottomChrome provides if (barShown && !tv) NavBarClearance else 0.dp) {
                    content()
                }
            }
        }
        if (barShown) {
            var frameX by remember { mutableFloatStateOf(0f) }
            Box(Modifier.fillMaxSize().onGloballyPositioned { frameX = it.boundsInRoot().left }) {
                if (!tv) {
                    NavBar(
                        state,
                        update,
                        account,
                        Modifier.align(Alignment.BottomCenter),
                        onPicturePlaced = { pictureX = it },
                    )
                }
                ProfilePopover(
                    state,
                    update,
                    anchorX = with(LocalDensity.current) { (pictureX - frameX).toDp() },
                    below = if (tv) TV_BAR_HEIGHT else null,
                )
            }
        } else {
            LaunchedEffect(Unit) { state.profileOpen = false }
        }
    }
}

/** Which of the bar's buttons the page on screen lights, or null for none (a page behind the picture). */
internal enum class NavPlace { Home, Chats, Search }

/** The bar's lit button for where the window is. */
internal fun navPlaceOf(state: ShellState): NavPlace? = when {
    state.paletteOpen -> NavPlace.Search
    state.openChat != null -> NavPlace.Chats
    else -> when (state.destination) {
        Destination.Home -> NavPlace.Home
        Destination.Chats -> NavPlace.Chats
        Destination.Settings, Destination.Favourites, Destination.History, Destination.Downloads -> null
    }
}

/** True on the pages behind the picture, which then wears a ring. */
private fun inProfile(state: ShellState): Boolean =
    !state.paletteOpen && state.openChat == null &&
        state.destination in setOf(Destination.Favourites, Destination.History, Destination.Downloads, Destination.Settings)

/** The command key as the OS names it, for the tooltips. */
private val COMMAND = if (OsInfo.isMac) "⌘" else "Ctrl+"

@Composable
private fun NavBar(
    state: ShellState,
    update: NavUpdate?,
    account: Account?,
    modifier: Modifier = Modifier,
    onPicturePlaced: (Float) -> Unit,
) {
    val s = LocalStrings.current
    val current = navPlaceOf(state)
    val reduced = rememberReducedMotion() || Motion.reduced
    val inFlight = rememberDownloadsInFlight()
    // Settings' "Icons only in the menu": the names go, and each place is a round icon button,
    // its name in the tooltip.
    // Drawn once the choice is read, so a compact bar never opens wide and shrinks.
    val compact = state.settings.compactNav.collectAsState(initial = null).value ?: return
    val cell = if (compact) NavCellSize(COMPACT_ITEM, COMPACT_ITEM) else NavCellSize(NAV_ITEM, NAV_ITEM_HEIGHT)
    // The highlight slides between places, but jumps with the cells when the setting changes them.
    var lastCell by remember { mutableStateOf(cell) }
    val resized = lastCell != cell
    SideEffect { lastCell = cell }
    CompositionLocalProvider(LocalNavCell provides cell) {
    Box(modifier.padding(bottom = NAV_MARGIN)) {
        Row(
            Modifier
                .height(cell.height + NAV_PADDING * 2)
                .shadow(NAV_SHADOW, CircleShape)
                .clip(CircleShape)
                .background(FloatingTone.menu)
                .border(floatingBorder(), CircleShape)
                .padding(horizontal = NAV_PADDING),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // A preview build's word ("Beta" in the browser), at the start of the bar; none in the apps.
            com.tmplayer.ui.components.BrandBadge(Modifier.padding(start = 8.dp, end = 6.dp))
            Box(contentAlignment = Alignment.CenterStart) {
                // One highlight that slides to the chosen place rather than blinking from one
                // button to the next, so the bar shows where the page went.
                val index = current?.ordinal
                val x by animateDpAsState(
                    targetValue = (cell.width + NAV_GAP) * (index ?: 0),
                    animationSpec = if (reduced || resized) snap() else spring(dampingRatio = 0.8f, stiffness = 500f),
                    label = "navHighlight",
                )
                val shown by animateFloatAsState(
                    targetValue = if (index != null) 1f else 0f,
                    animationSpec = Motion.standard(if (reduced) 0 else Motion.Fast),
                    label = "navHighlightShown",
                )
                Box(
                    Modifier
                        .offset(x = x)
                        .size(cell.width, cell.height)
                        .graphicsLayer { alpha = shown }
                        .clip(RoundedCornerShape(cell.height / 2))
                        .background(Tone.accent.copy(alpha = 0.16f)),
                )
                // A gap between the places, so the chosen one's highlight and the hover on the one
                // beside it stay two shapes rather than running into one block.
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(NAV_GAP)) {
                    NavButton(TmIcons.Home, s.browseTabHome, "${COMMAND}1", current == NavPlace.Home) { state.go(Destination.Home) }
                    NavButton(TmIcons.Chats, s.navChats, "${COMMAND}2", current == NavPlace.Chats) {
                        val open = state.chatSection
                        state.showChats(if (open.onChatsPage) open else BrowseSection.of(BrowseTab.Chats))
                    }
                    NavButton(TmIcons.Search, s.commonSearch, "${COMMAND}K", current == NavPlace.Search) { state.paletteOpen = !state.paletteOpen }
                    ProfileButton(
                        account = account,
                        ringed = inProfile(state),
                        badged = inFlight > 0 || update != null,
                        open = state.profileOpen,
                        onPlaced = onPicturePlaced,
                        onClick = { state.profileOpen = !state.profileOpen },
                    )
                }
            }
        }
    }
    }
}

/** A place's cell on the bar, which the compact bar makes a round icon button. */
private data class NavCellSize(val width: Dp, val height: Dp) {
    val compact: Boolean get() = width == height
}

private val LocalNavCell = androidx.compose.runtime.staticCompositionLocalOf { NavCellSize(NAV_ITEM, NAV_ITEM_HEIGHT) }

/**
 * One place on the bar: its icon over a short name in 10 sp, a tooltip naming its key, and the
 * keyboard's focus drawn as an accent ring round the cell (never after a click, see
 * [com.tmplayer.ui.theme.FocusVisibility]).
 */
@Composable
private fun NavButton(
    icon: ImageVector,
    label: String,
    shortcut: String?,
    chosen: Boolean,
    onClick: () -> Unit,
) {
    BarTooltip(label, shortcut) {
        NavCell(label, chosen, onClick, role = Role.Tab) { tint ->
            // Fills in with a drop as the place is chosen, as the phone's bar does.
            com.tmplayer.ui.components.SelectableIcon(icon, selected = chosen, tint = tint, size = 22.dp)
        }
    }
}

@Composable
private fun NavCell(
    label: String,
    chosen: Boolean,
    onClick: () -> Unit,
    role: Role,
    modifier: Modifier = Modifier,
    description: String = label,
    glyph: @Composable (Color) -> Unit,
) {
    val interactions = remember { MutableInteractionSource() }
    val focused by interactions.collectIsFocusVisibleAsState()
    val cell = LocalNavCell.current
    // The icon and its name take the accent as the highlight lands under them; the cell sinks a
    // little under the press, as the phone's buttons do.
    val tint by animateColorAsState(if (chosen) Tone.accent else Tone.text, Motion.standard(Motion.Fast), label = "navIcon")
    val ink by animateColorAsState(if (chosen) Tone.accent else Tone.muted, Motion.standard(Motion.Fast), label = "navLabel")
    Column(
        modifier
            .size(cell.width, cell.height)
            .pressScale(interactions)
            .clip(RoundedCornerShape(cell.height / 2))
            .then(if (focused) Modifier.border(2.dp, Tone.accent, RoundedCornerShape(cell.height / 2)) else Modifier)
            .clickable(interactionSource = interactions, indication = androidx.compose.foundation.LocalIndication.current, role = role, onClick = onClick)
            .semantics {
                contentDescription = description
                selected = chosen
                this.role = role
            },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        // One height for every glyph, the ringed picture included, so the names sit on one line.
        Box(Modifier.size(GLYPH_SLOT), contentAlignment = Alignment.Center) { glyph(tint) }
        if (cell.compact) return@Column
        Spacer(Modifier.height(2.dp))
        Text(
            label,
            style = captionStyle(),
            fontWeight = if (chosen) FontWeight.SemiBold else FontWeight.Medium,
            color = ink,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
    }
}

/** The viewer's picture, ringed while one of its lists is open, with a dot for news behind it. */
@Composable
private fun ProfileButton(
    account: Account?,
    ringed: Boolean,
    badged: Boolean,
    open: Boolean,
    onPlaced: (Float) -> Unit,
    onClick: () -> Unit,
) {
    val s = LocalStrings.current
    // "You", as the phone's bar says: short like the other names, and never blank.
    val name = s.navYou
    BarTooltip(s.navProfile, null) {
        NavCell(
            label = name,
            chosen = ringed || open,
            onClick = onClick,
            role = Role.Button,
            description = s.navProfile,
            modifier = Modifier.onGloballyPositioned { onPlaced(it.boundsInRoot().center.x) },
        ) { _ ->
            val ring by animateColorAsState(if (ringed || open) Tone.accent else Color.Transparent, Motion.standard(Motion.Fast), label = "profileRing")
            Box(Modifier.size(PICTURE + 6.dp).border(2.dp, ring, CircleShape), contentAlignment = Alignment.Center) {
                if (account != null) {
                    ChatAvatar(account.miniThumbnail, account.photoFileId, account.name, PICTURE)
                } else {
                    Box(Modifier.size(PICTURE).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainerHighest), contentAlignment = Alignment.Center) {
                        Icon(TmIcons.Account, contentDescription = null, tint = Tone.muted, modifier = Modifier.size(PICTURE * 0.7f))
                    }
                }
                if (badged) {
                    Box(
                        Modifier
                            .align(Alignment.TopEnd)
                            .size(9.dp)
                            .clip(CircleShape)
                            .background(Tone.caution)
                            .border(1.5.dp, FloatingTone.menu, CircleShape),
                    )
                }
            }
        }
    }
}

/**
 * The bar's tooltip: the button's name and, beside it, the key that does the same. Above the
 * button, or with [below] under it, for a button near the top of the window. It is the shared
 * [com.tmplayer.ui.components.IconTip], which fades in (at once under reduced motion).
 */
@Composable
internal fun BarTooltip(label: String, shortcut: String?, below: Boolean = false, content: @Composable () -> Unit) {
    com.tmplayer.ui.components.IconTip(
        label,
        shortcut = shortcut,
        side = if (below) com.tmplayer.ui.components.TipSide.Below else com.tmplayer.ui.components.TipSide.Above,
        content = content,
    )
}

/**
 * An icon button on a page, with the bar's tooltip under it naming what it does (and its key,
 * where it has one): an icon alone says little to a pointer that has not used it before.
 */
@Composable
internal fun TipIconButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    shortcut: String? = null,
    enabled: Boolean = true,
    tint: Color = Color.Unspecified,
) {
    BarTooltip(label, shortcut, below = true) {
        androidx.compose.material3.IconButton(onClick = onClick, enabled = enabled) {
            Icon(icon, contentDescription = label, tint = if (tint == Color.Unspecified) androidx.compose.material3.LocalContentColor.current else tint)
        }
    }
}

/**
 * The viewer's own lists, behind their picture: a small card that grows out of the bar over the
 * picture, as on the phone. A click outside, Esc or picking a row closes it; Up and Down walk the
 * rows, and focus starts on the first.
 */
@Composable
private fun BoxScope.ProfilePopover(state: ShellState, update: NavUpdate?, anchorX: Dp, below: Dp? = null) {
    val s = LocalStrings.current
    val open = state.profileOpen
    val reduced = rememberReducedMotion() || Motion.reduced
    BackHandler(enabled = open) { state.profileOpen = false }
    if (open) {
        // Catches the click outside; no dim, the card is small and the page stays readable.
        Box(
            Modifier
                .matchParentSize()
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { state.profileOpen = false },
        )
    }
    val favourites by state.settings.favorites.collectAsState(initial = emptySet())
    val watching by state.settings.continueWatching.collectAsState(initial = emptyList())
    val watchedVideos by state.watched.watched.collectAsState(initial = emptyMap())
    val inFlight = rememberDownloadsInFlight()
    val half = POPOVER_WIDTH / 2
    // Over the floating bar at the foot, or hanging under the TV's bar at the top (plan 8.7).
    val origin = TransformOrigin(0.5f, if (below != null) 0f else 1f)
    Box(
        Modifier
            .align(if (below != null) Alignment.TopStart else Alignment.BottomStart)
            .padding(top = below?.plus(4.dp) ?: 0.dp, bottom = if (below != null) 0.dp else NAV_MARGIN + NAV_HEIGHT + 8.dp)
            .offset(x = if (anchorX.value.isNaN()) 0.dp else (anchorX - half).coerceAtLeast(8.dp)),
    ) {
        AnimatedVisibility(
            visible = open,
            enter = if (reduced) fadeIn(Motion.standard(0)) else fadeIn(Motion.standard(Motion.Fast)) +
                scaleIn(Motion.standard(Motion.Normal), initialScale = 0.85f, transformOrigin = origin),
            exit = if (reduced) fadeOut(Motion.standard(0)) else fadeOut(Motion.standard(Motion.Fast)) +
                scaleOut(Motion.standard(Motion.Fast), targetScale = 0.9f, transformOrigin = origin),
        ) {
            val first = remember { FocusRequester() }
            val focus = LocalFocusManager.current
            val close: (() -> Unit) -> () -> Unit = { action -> { state.profileOpen = false; action() } }
            Column(
                Modifier
                    .width(POPOVER_WIDTH)
                    .shadow(NAV_SHADOW, POPOVER_SHAPE)
                    .clip(POPOVER_SHAPE)
                    .background(FloatingTone.dialog)
                    .border(floatingBorder(), POPOVER_SHAPE)
                    .onPreviewKeyEvent { event ->
                        if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                        when (event.key) {
                            Key.DirectionDown -> focus.moveFocus(FocusDirection.Next)
                            Key.DirectionUp -> focus.moveFocus(FocusDirection.Previous)
                            Key.Escape -> {
                                state.profileOpen = false
                                true
                            }
                            else -> false
                        }
                    }
                    .padding(vertical = 6.dp),
            ) {
                com.tmplayer.ui.components.ViewingSummary(
                    watching = watching.size,
                    watched = watchedVideos.size,
                    starred = favourites.size,
                    onWatching = close { state.showHistory(HistoryTab.Continue) },
                    onWatched = close { state.showHistory(HistoryTab.Watched) },
                    onStarred = close { state.go(Destination.Favourites) },
                )
                HorizontalDivider(Modifier.padding(horizontal = 14.dp, vertical = 4.dp), color = Tone.outline)
                PopoverRow(TmIcons.StarOutline, s.browseTabFavourites, favourites.size.takeIf { it > 0 }?.toString(), Modifier.focusRequester(first), onClick = close { state.go(Destination.Favourites) })
                PopoverRow(TmIcons.Download, s.navDownloads, inFlight.takeIf { it > 0 }?.toString(), onClick = close { state.go(Destination.Downloads) })
                PopoverRow(TmIcons.History, s.browseTabHistory, null, onClick = close { state.showHistory(HistoryTab.Continue) })
                PopoverRow(TmIcons.Clock, s.browseTabWatchLater, null, onClick = close { state.showHistory(HistoryTab.WatchLater) })
                HorizontalDivider(Modifier.padding(horizontal = 14.dp, vertical = 4.dp), color = Tone.outline)
                PopoverRow(TmIcons.Settings, s.navSettings, null, shortcut = "$COMMAND,", onClick = close { state.go(Destination.Settings) })
                if (update != null) {
                    // Amber, as on the television's rail and the phone's popover: the one row that is news.
                    HorizontalDivider(Modifier.padding(horizontal = 14.dp, vertical = 4.dp), color = Tone.outline)
                    PopoverRow(TmIcons.Refresh, update.label, update.version, ink = Tone.caution, onClick = close { state.updatePopup = true })
                }
            }
            LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
        }
    }
}

@Composable
private fun PopoverRow(
    icon: ImageVector,
    label: String,
    badge: String?,
    modifier: Modifier = Modifier,
    ink: Color = Tone.text,
    /** The key that does the same, said quietly at the row's end. */
    shortcut: String? = null,
    onClick: () -> Unit,
) {
    val interactions = remember { MutableInteractionSource() }
    val focused by interactions.collectIsFocusVisibleAsState()
    Row(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 6.dp)
            .height(POPOVER_ROW)
            // The card's 20 dp corner less the 6 dp inset, so a lit row runs parallel to it.
            .clip(RoundedCornerShape(14.dp))
            .background(if (focused) Tone.surfaceHigh else Color.Transparent)
            .clickable(interactionSource = interactions, indication = androidx.compose.foundation.LocalIndication.current, role = Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(icon, contentDescription = null, tint = ink, modifier = Modifier.size(20.dp))
        Text(label, style = MaterialTheme.typography.bodyMedium, color = ink, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        if (shortcut != null) Text(shortcut, style = MaterialTheme.typography.labelSmall, color = Tone.muted)
        if (badge != null) {
            Text(
                badge,
                style = MaterialTheme.typography.labelMedium,
                color = if (ink == Tone.text) Tone.onAccent else ink,
                modifier = Modifier
                    .clip(CircleShape)
                    .background(if (ink == Tone.text) Tone.accent else Color.Transparent)
                    .padding(horizontal = 8.dp, vertical = 2.dp),
            )
        }
    }
}

/**
 * Makes [action] the window's refresh (F5, Ctrl+R) while the calling page is on screen, the
 * desktop's word for the phone's pull to refresh.
 */
@Composable
fun RegisterRefresh(state: ShellState, action: () -> Unit) {
    val latest by rememberUpdatedState(action)
    val stable = remember { { latest() } }
    DisposableEffect(state, stable) {
        state.refreshAction = stable
        onDispose { if (state.refreshAction === stable) state.refreshAction = null }
    }
}

/**
 * Where the window's short messages show ([com.tmplayer.ui.components.rememberToast]): a small
 * pill at the top centre, the phone's TopMessageHost on the desktop, so a message never covers the
 * floating bar or a page's buttons at the foot. A newer message replaces the one showing.
 */
@Composable
fun TopMessageHost(host: androidx.compose.material3.SnackbarHostState, modifier: Modifier = Modifier) {
    androidx.compose.material3.SnackbarHost(host, modifier) { data ->
        Box(
            Modifier
                .widthIn(max = 520.dp)
                .shadow(6.dp, CircleShape)
                .clip(CircleShape)
                .background(FloatingTone.menu)
                .border(floatingBorder(), CircleShape)
                .padding(horizontal = 18.dp, vertical = 10.dp)
                .semantics { liveRegion = androidx.compose.ui.semantics.LiveRegionMode.Polite },
        ) {
            Text(data.visuals.message, style = MaterialTheme.typography.bodyMedium, color = Tone.text, textAlign = TextAlign.Center)
        }
    }
}

/** How much of the foot of the page the bar keeps: the bar, its margin and a gap above it. */
val NavBarClearance: Dp get() = NAV_MARGIN + NAV_HEIGHT + 8.dp

private val NAV_ITEM = 68.dp

/** Between two places, as much as the bar keeps round them. */
private val NAV_GAP = 6.dp

/** The same gap between the buttons of a chat's bar, which stands in this bar's place. */
internal val BarGap: Dp get() = NAV_GAP
private val NAV_ITEM_HEIGHT = 50.dp

/** A place's cell on the compact bar: round, the glyph alone. */
private val COMPACT_ITEM = 44.dp
private val NAV_PADDING = 6.dp
private val NAV_HEIGHT = NAV_ITEM_HEIGHT + NAV_PADDING * 2
private val NAV_MARGIN = 16.dp
private val NAV_SHADOW = 10.dp
private val PICTURE = 22.dp
private val GLYPH_SLOT = 28.dp
private val POPOVER_WIDTH = 232.dp
private val POPOVER_ROW = 40.dp
private val POPOVER_SHAPE = RoundedCornerShape(20.dp)
