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
import androidx.compose.foundation.interaction.collectIsHoveredAsState
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
import androidx.compose.ui.platform.testTag
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.paneTitle
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
import com.tmplayer.ui.components.scrimDismiss
import com.tmplayer.ui.browse.BrowseSection
import com.tmplayer.ui.browse.BrowseTab
import com.tmplayer.ui.browse.HistoryTab
import com.tmplayer.ui.browse.onChatsPage
import com.tmplayer.ui.components.LocalBottomChrome
import com.tmplayer.ui.components.toastFloor
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
    // How tall the bar stands as drawn (its margin included): a larger font or a longer word makes its cells grow, and
    // the page keeps that much room at its foot, never less than the bar at its usual size.
    var barPx by remember { mutableIntStateOf(0) }
    val clearance = maxOf(NavBarClearance, with(LocalDensity.current) { barPx.toDp() } + NAV_CLEARANCE_GAP)
    // The profile open now (WP-103): its saved photo or letter stands in for Telegram's picture, and
    // its name for Telegram's, until Telegram has answered (or offline). Null before the profiles open.
    val profileFlow = remember { com.tmplayer.desktop.profiles.DesktopProfiles.flow() }
    val profileList by remember(profileFlow) {
        profileFlow?.switcher?.profiles ?: kotlinx.coroutines.flow.MutableStateFlow(null)
    }.collectAsState()
    val accountFocus = remember { FocusRequester() }
    Box(modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            // The TV's bar is drawn over the page (in the overlay below), as the Android TV app
            // does: this only keeps the page clear of it, except where Home's carousel runs up
            // under it.
            if (tv && barShown && !state.tvBar.overArt) Spacer(Modifier.fillMaxWidth().height(TV_BAR_HEIGHT))
            // On a television, one focus group that remembers where the remote was: Down from the
            // bar and the end of a video put it back on that item (ShellState.focusPage).
            Box(Modifier.weight(1f).fillMaxWidth().focusRequester(state.pageFocus).then(if (tv || state.destination.isModal) Modifier.focusRestorer().focusGroup() else Modifier)) {
                CompositionLocalProvider(LocalBottomChrome provides if (barShown && !tv) clearance else 0.dp) {
                    content()
                }
            }
        }
        if (barShown) {
            var frameX by remember { mutableFloatStateOf(0f) }
            androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize().onGloballyPositioned { frameX = it.boundsInRoot().left }) {
                val room = maxWidth
                if (tv) {
                    TvTopBar(state, update, account, onPicturePlaced = { pictureX = it }, modifier = Modifier.align(Alignment.TopStart))
                }
                if (!tv) {
                    NavBar(
                        state,
                        update,
                        account,
                        // The bar tells toasts where its top edge is, so they stand above it.
                        Modifier.align(Alignment.BottomCenter).toastFloor().onSizeChanged { barPx = it.height },
                        onPicturePlaced = { pictureX = it },
                        profile = profileList?.active,
                        accountFocus = accountFocus,
                        room = room,
                    )
                }
                ProfilePopover(
                    state,
                    update,
                    anchorX = with(LocalDensity.current) { (pictureX - frameX).toDp() },
                    below = if (tv) TV_BAR_HEIGHT else null,
                    name = account?.name,
                    account = account,
                    profileFlow = profileFlow,
                    profileList = profileList,
                    focusBack = accountFocus,
                )
            }
        } else {
            LaunchedEffect(Unit) { state.profileOpen = false }
        }
    }
}

/**
 * Which of the bar's buttons the page on screen lights, or null for none (a page behind the
 * picture). The order is the bar's: the highlight slides to the place's ordinal.
 */
internal enum class NavPlace { Home, Chats, Search, Downloads }

/** The bar's lit button for where the window is. */
internal fun navPlaceOf(state: ShellState): NavPlace? = when {
    state.paletteOpen -> NavPlace.Search
    state.openChat != null -> NavPlace.Chats
    else -> when (state.destination) {
        Destination.Home -> NavPlace.Home
        Destination.Chats -> NavPlace.Chats
        Destination.Downloads -> NavPlace.Downloads
        Destination.Settings, Destination.Favourites, Destination.History -> null
    }
}

/** True on the pages behind the picture, which then wears a ring. */
private fun inProfile(state: ShellState): Boolean =
    !state.paletteOpen && state.openChat == null &&
        state.destination in setOf(Destination.Favourites, Destination.History, Destination.Settings)

/** The command key as the OS names it, for the tooltips. */
private val COMMAND = if (OsInfo.isMac) "⌘" else "Ctrl+"

/** The Choose chats button's size on the desktop's headers and bar, a header icon button's. */
internal val CHOOSE_CHATS_SIZE = 40.dp

/** The Downloads button's key as the tooltips name it (ShellState.onPreviewKey). */
internal val DOWNLOADS_SHORTCUT = "${COMMAND}J"

/** The Choose chats button's key as the tooltips name it (ShellState.onPreviewKey). */
internal val CHOOSE_CHATS_SHORTCUT = "${COMMAND}Shift+A"

@Composable
private fun NavBar(
    state: ShellState,
    update: NavUpdate?,
    account: Account?,
    modifier: Modifier = Modifier,
    onPicturePlaced: (Float) -> Unit,
    profile: com.tmplayer.data.Profile? = null,
    accountFocus: FocusRequester? = null,
    /** How wide the window is: the bar never stands wider than that, less its margins. */
    room: Dp = Dp.Infinity,
) {
    val s = LocalStrings.current
    val current = navPlaceOf(state)
    val reduced = rememberReducedMotion() || Motion.reduced
    val inFlight = rememberDownloadsInFlight()
    // Settings' "Icons only in the menu": the names go, and each place is a round icon button,
    // its name in the tooltip.
    // Drawn once the choice is read, so a compact bar never opens wide and shrinks.
    val compact = state.settings.compactNav.collectAsState(initial = null).value ?: return
    val fitted = rememberFittedCell(listOf(s.browseTabHome, s.navChats, s.commonSearch, s.navDownloads), room)
    val cell = if (compact) NavCellSize(COMPACT_ITEM, COMPACT_ITEM) else fitted
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
                    // Downloads is a place of its own (R2), with the number running; the key opens it from anywhere.
                    NavButton(TmIcons.Download, s.navDownloads, DOWNLOADS_SHORTCUT, current == NavPlace.Downloads, badge = inFlight) { state.toggleDownloads() }
                    ProfileButton(
                        account = account,
                        profile = profile,
                        focus = accountFocus,
                        ringed = inProfile(state),
                        badged = update != null,
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

/**
 * The cell every place gets: [NAV_ITEM] by [NAV_ITEM_HEIGHT] at the usual font, wider for the widest of [labels] at
 * the font in use (a larger system font, or a language with a longer word for Downloads, cut it to "Downlo..") and
 * taller for the caption's line, so a name is always whole and the five cells (the picture's too) stay equal.
 *
 * The bar never runs wider than [room] (the window) less its margins: where five cells that wide do not
 * fit (twice the font in a small window, with a wide system font: Téléchargements on Ubuntu's DejaVu),
 * the cells take the room there is and the caption stops growing at the size that fits, rather than
 * the last cells being squeezed and their names cut.
 */
@Composable
private fun rememberFittedCell(labels: List<String>, room: Dp): NavCellSize {
    val measurer = androidx.compose.ui.text.rememberTextMeasurer()
    val density = LocalDensity.current
    val caption = captionStyle().copy(fontWeight = FontWeight.SemiBold)
    val brand = com.tmplayer.ui.components.LocalBrandBadge.current
    val badgeStyle = MaterialTheme.typography.labelSmall
    return remember(labels, caption, density, room, brand, badgeStyle) {
        fun widest(style: androidx.compose.ui.text.TextStyle): Dp = with(density) {
            (labels.maxOfOrNull { measurer.measure(it, style, maxLines = 1, softWrap = false).size.width } ?: 0).toDp()
        }
        // What stands on the bar beside the five cells (four places and the picture, as wide as each other): its
        // padding, the gaps and a preview's word.
        val badge = brand?.let { with(density) { measurer.measure(it, badgeStyle, maxLines = 1).size.width.toDp() } + BRAND_SIDES } ?: 0.dp
        val besides = NAV_PADDING * 2 + NAV_GAP * 4 + badge + NAV_MARGIN * 2
        val budget = if (room == Dp.Infinity) Dp.Infinity else maxOf(NAV_ITEM, (room - besides) / 5)
        var style = caption
        var scale = 1f
        var words = widest(style)
        // Smaller in steps until the widest name fits the cell the window leaves, measured each time (glyphs do not scale exactly).
        while (words + NAV_LABEL_SIDES * 2 > budget && scale > MIN_LABEL_SCALE) {
            scale = maxOf(MIN_LABEL_SCALE, scale * 0.94f)
            style = caption.copy(fontSize = caption.fontSize * scale, lineHeight = caption.lineHeight * scale)
            words = widest(style)
        }
        val line = with(density) { (style.lineHeight.takeIf { it.isSp } ?: 12.sp).toDp() }
        NavCellSize(
            width = maxOf(NAV_ITEM, words + NAV_LABEL_SIDES * 2),
            height = maxOf(NAV_ITEM_HEIGHT, GLYPH_SLOT + 2.dp + line + 10.dp),
            labelScale = scale,
        )
    }
}

/**
 * A place's cell on the bar, which the compact bar makes a round icon button. [labelScale] is below 1 where the
 * window is too narrow for the caption at the font in use.
 */
private data class NavCellSize(val width: Dp, val height: Dp, val labelScale: Float = 1f) {
    val compact: Boolean get() = width == height
}

/** The smallest the caption is drawn to fit a narrow window: 0.6 of the font in use. */
private const val MIN_LABEL_SCALE = 0.6f

/** A preview's word on the bar: its own 6 dp each side and its 8 dp and 6 dp of margin. */
private val BRAND_SIDES = 26.dp

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
    /** A count over the icon's top end (Downloads running); 0 draws none. */
    badge: Int = 0,
    onClick: () -> Unit,
) {
    BarTooltip(label, shortcut) {
        NavCell(label, chosen, onClick, role = Role.Tab, description = if (badge > 0) "$label, $badge" else label) { tint ->
            Box(contentAlignment = Alignment.Center) {
                // Fills in with a drop as the place is chosen, as the phone's bar does.
                com.tmplayer.ui.components.SelectableIcon(icon, selected = chosen, tint = tint, size = 22.dp)
                if (badge > 0) {
                    Text(
                        if (badge > 99) "99+" else badge.toString(),
                        style = MaterialTheme.typography.labelSmall,
                        color = Tone.onAccent,
                        maxLines = 1,
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .offset(x = 10.dp, y = (-6).dp)
                            .clip(CircleShape)
                            .background(Tone.accent)
                            .border(1.5.dp, FloatingTone.menu, CircleShape)
                            .padding(horizontal = 4.dp)
                            .testTag(NAV_BADGE_TAG),
                    )
                }
            }
        }
    }
}

/** The test tag of the count over the Downloads button. */
internal const val NAV_BADGE_TAG = "nav-badge"

@Composable
private fun NavCell(
    /** The caption under the glyph; null draws none (the account: a long name has no place on the bar). */
    label: String?,
    chosen: Boolean,
    onClick: () -> Unit,
    role: Role,
    modifier: Modifier = Modifier,
    description: String = label.orEmpty(),
    glyph: @Composable (Color) -> Unit,
) {
    val interactions = remember { MutableInteractionSource() }
    val focused by interactions.collectIsFocusVisibleAsState()
    val cell = LocalNavCell.current
    // The icon and its name take the accent as the highlight lands under them; the cell sinks a
    // little under the press, as the phone's buttons do.
    val tint by animateColorAsState(if (chosen) Tone.accent else Tone.text, Motion.standard(Motion.Fast), label = "navIcon")
    val ink by animateColorAsState(if (chosen) Tone.accent else Tone.muted, Motion.standard(Motion.Fast), label = "navLabel")
    // The picture's cell, with no caption, is as wide as the places' all the same: the bar's slots stay equal.
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
        if (cell.compact || label == null) return@Column
        Spacer(Modifier.height(2.dp))
        val caption = captionStyle()
        Text(
            label,
            style = if (cell.labelScale < 1f) caption.copy(fontSize = caption.fontSize * cell.labelScale, lineHeight = caption.lineHeight * cell.labelScale) else caption,
            fontWeight = if (chosen) FontWeight.SemiBold else FontWeight.Medium,
            color = ink,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            // Across the whole cell, centred, rather than at the name's own measured width: a paragraph laid out at
            // exactly that (rounded) width counts a Chinese, Japanese or Korean name as not fitting on macOS's fonts.
            modifier = Modifier.padding(horizontal = 4.dp).fillMaxWidth(),
        )
    }
}

/**
 * The account's picture (WP-103), with no caption (a long name has no place on the bar: the name is its
 * screen reader label and its hover tip), ringed while one of its lists is open, with a dot
 * for news behind it. The phone's chain: Telegram's picture once it is there, else the profile's
 * saved photo, else the letter on the profile's colour, and the person glyph only when no name is
 * known. A screen reader hears "Account, Asha" (with ", an update is ready" while one waits).
 */
@Composable
private fun ProfileButton(
    account: Account?,
    profile: com.tmplayer.data.Profile?,
    focus: FocusRequester?,
    ringed: Boolean,
    badged: Boolean,
    open: Boolean,
    onPlaced: (Float) -> Unit,
    onClick: () -> Unit,
) {
    val s = LocalStrings.current
    val name = desktopAccountName(account, profile)
    val named = name?.let { s.navAccountNamed(it) } ?: s.navAccount
    BarTooltip(named, null) {
        NavCell(
            label = null,
            chosen = ringed || open,
            onClick = onClick,
            role = Role.Button,
            description = if (badged) "$named, ${s.navUpdateWaiting}" else named,
            modifier = Modifier.onGloballyPositioned { onPlaced(it.boundsInRoot().center.x) }.then(if (focus != null) Modifier.focusRequester(focus) else Modifier).testTag(NAV_ACCOUNT_TAG),
        ) { _ ->
            val ring by animateColorAsState(if (ringed || open) Tone.accent else Color.Transparent, Motion.standard(Motion.Fast), label = "profileRing")
            Box(Modifier.size(PICTURE + 6.dp).border(2.dp, ring, CircleShape), contentAlignment = Alignment.Center) {
                androidx.compose.runtime.key(profile?.id) { AccountPictureDesktop(account, profile, name, PICTURE) }
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

/** Who the picture is: Telegram's name once it is known, else the profile's own, else nobody yet. */
internal fun desktopAccountName(account: Account?, profile: com.tmplayer.data.Profile?): String? =
    account?.name?.trim()?.takeIf { it.isNotEmpty() } ?: profile?.shownName?.trim()?.takeIf { it.isNotEmpty() }

/** The first word of [name], for the bar's short caption; the whole name when it has no space. */
internal fun firstName(name: String): String =
    name.trim().split(' ', '\t', '\n').firstOrNull { it.isNotEmpty() } ?: name

/** The tag of the account's cell on the bar. */
internal const val NAV_ACCOUNT_TAG = "nav-account"

/** The tag of the account's round picture, on the bar and in the menu's head. */
internal const val NAV_PICTURE_TAG = "nav-account-picture"

/** The tag of the account menu's head (picture, name, @username). */
internal const val NAV_MENU_HEAD_TAG = "nav-account-head"

/** The tag of the account menu's card. */
internal const val NAV_MENU_TAG = "nav-account-menu"

/**
 * The account's round picture at [size]: Telegram's (its letter while it loads), else the profile's
 * saved photo or letter, else the person glyph, never an empty disc.
 */
@Composable
private fun AccountPictureDesktop(account: Account?, profile: com.tmplayer.data.Profile?, name: String?, size: Dp) {
    Box(Modifier.size(size).clip(CircleShape).testTag(NAV_PICTURE_TAG), contentAlignment = Alignment.Center) {
        val telegram = account != null && (account.photoFileId > 0 || account.miniThumbnail?.isNotEmpty() == true)
        when {
            telegram && account != null -> ChatAvatar(account.miniThumbnail, account.photoFileId, name ?: account.name, size)
            name != null -> com.tmplayer.ui.lock.ProfileAvatar(name, profile?.photo, size)
            else -> Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainerHighest), contentAlignment = Alignment.Center) {
                Icon(TmIcons.Account, contentDescription = null, tint = Tone.muted, modifier = Modifier.size(size * 0.7f))
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
 * The account menu behind the picture: a small card that grows out of the bar over the picture, as
 * on the phone. In the window (WP-103) it is the phone's account menu: whose account it is (the
 * picture, the name, the @username), Switch profile (two or more) or Add account (one), Settings
 * with its key, Refresh where the page has one, the viewing summary and the lists, Update, and Sign
 * out last. A click outside, Esc or picking a row closes it, and Esc puts the keyboard back on the
 * picture; Up and Down walk the rows, and focus starts on the first. The big screen's keeps its
 * own order (WP-02).
 */
@Composable
private fun BoxScope.ProfilePopover(
    state: ShellState,
    update: NavUpdate?,
    anchorX: Dp,
    below: Dp? = null,
    name: String? = null,
    account: Account? = null,
    profileFlow: com.tmplayer.ui.lock.ProfileFlow? = null,
    profileList: com.tmplayer.data.AccountList? = null,
    /** The picture on the bar, which has the keyboard again when Esc closes the menu. */
    focusBack: FocusRequester? = null,
) {
    val s = LocalStrings.current
    val open = state.profileOpen
    val reduced = rememberReducedMotion() || Motion.reduced
    BackHandler(enabled = open) { state.profileOpen = false }
    if (open) {
        // Catches the click outside; no dim, the card is small and the page stays readable.
        Box(
            Modifier
                .matchParentSize()
                .scrimDismiss { state.profileOpen = false },
        )
    }
    val favourites by state.settings.favorites.collectAsState(initial = emptySet())
    val watching by state.settings.continueWatching.collectAsState(initial = emptyList())
    val watchedVideos by state.watched.watched.collectAsState(initial = emptyMap())
    val inFlight = rememberDownloadsInFlight()
    // The menu also acts on the account (WP-02 on the big screen, WP-103 in the window): Switch
    // profile with two profiles or more and Add account with one, then Sign out. The questions are
    // composed here, outside the popover's own content, so closing the popover never cuts a backup
    // started from it. Sign out asks the app lock's PIN first when Settings is shut; Add account's PIN
    // is the profile flow's own (ProfileFlow.add), asked once.
    val severalProfiles = profileList?.let { com.tmplayer.data.ProfilePicker.atLaunch(it) } == true
    var signOutAsked by remember { mutableStateOf(false) }
    if (signOutAsked) SignOutDialog(state, onDismiss = { signOutAsked = false }, name = name)
    val window = below == null
    val shownName = desktopAccountName(account, profileList?.active)
    // Over the floating bar at the foot, or hanging under the TV's bar at the top (plan 8.7).
    val origin = TransformOrigin(0.5f, if (below != null) 0f else 1f)
    Box(
        Modifier
            .align(if (below != null) Alignment.TopStart else Alignment.BottomStart)
            .padding(top = below?.plus(4.dp) ?: 0.dp, bottom = if (below != null) 0.dp else NAV_MARGIN + NAV_HEIGHT + 8.dp)
            // Centred on the picture it opened from, clamped inside the window so neither edge cuts it off.
            .layout { measurable, constraints ->
                val placeable = measurable.measure(constraints.copy(minWidth = 0, minHeight = 0))
                val edge = 8.dp.roundToPx()
                val want = if (anchorX.value.isNaN()) edge else anchorX.roundToPx() - placeable.width / 2
                val x = want.coerceIn(edge, (constraints.maxWidth - placeable.width - edge).coerceAtLeast(edge))
                layout(x + placeable.width, placeable.height) { placeable.place(x, 0) }
            },
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
                                // The keyboard goes back to the picture that opened the menu.
                                if (window) runCatching { focusBack?.requestFocus() }
                                true
                            }
                            else -> false
                        }
                    }
                    .semantics { paneTitle = shownName?.let { s.navAccountNamed(it) } ?: s.navAccount }
                    .padding(vertical = 8.dp)
                    .testTag(NAV_MENU_TAG),
            ) {
                // One grouping for the window and the big screen alike (user, 2026-11-25), as the
                // phone's sheet has it, left to right on one 16 dp edge and divided only between
                // groups: header | library tiles | rows | version. Refresh and the switcher are the
                // header's two small buttons; the old "Your viewing" block is gone, its numbers on the
                // tiles they belong to. Sign out is in Settings, Account; the big screen, which the
                // remote reaches Settings on slowly, keeps it as the last row (WP-02).
                AccountMenuHead(
                    account,
                    profileList?.active,
                    shownName ?: s.navAccount,
                    onRefresh = state.refreshAction?.let { refresh -> close(refresh) },
                    onSwitch = if (profileFlow != null && severalProfiles) close { profileFlow.ask() } else null,
                )
                MenuDivider()
                com.tmplayer.ui.components.LibraryTiles(
                    starred = favourites.size,
                    watching = watching.size,
                    watched = watchedVideos.size,
                    onFavourites = close { state.go(Destination.Favourites) },
                    onHistory = close { state.showHistory(HistoryTab.Continue) },
                    onWatchLater = close { state.showHistory(HistoryTab.WatchLater) },
                    modifier = Modifier.padding(horizontal = 4.dp),
                    firstFocus = first,
                )
                MenuDivider()
                PopoverRow(TmIcons.Settings, s.navSettings, null, shortcut = "$COMMAND,", onClick = close { state.go(Destination.Settings) })
                if (profileFlow != null) {
                    PopoverRow(
                        TmIcons.Person,
                        if (severalProfiles) s.profilesSwitchOrAdd else s.profilesAdd,
                        null,
                        // The picker for both (it has the Add tile); with one profile in the window, Add account itself.
                        onClick = close { if (severalProfiles || below != null) profileFlow.ask() else profileFlow.add() },
                    )
                }
                if (below != null && profileFlow != null) {
                    PopoverRow(TmIcons.SignOut, s.settingsSignOutConfirm, null, ink = Tone.danger, onClick = close { signOutAsked = true })
                }
                MenuDivider()
                VersionFooterRow(close { state.updatePopup = true })
            }
            LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
        }
    }
}

/**
 * The popover's last line: the app's tile and "v1.28.7" with the update state beside it (user,
 * 2026-11-25); a click copies the full line for support (WP-104) and says so. On the menu's one left
 * edge, 16 dp in.
 */
@Composable
private fun VersionFooterRow(onUpdate: () -> Unit) {
    val s = LocalStrings.current
    val info = rememberDesktopVersion()
    val copier = com.tmplayer.ui.components.rememberVersionCopier(info)
    com.tmplayer.ui.components.VersionMark(
        info = info,
        modifier = Modifier.padding(horizontal = 4.dp),
        onClick = copier::copy,
        onClickLabel = s.aboutVersionCopy,
        onUpdate = onUpdate,
        message = if (copier.copied) s.aboutVersionCopied else null,
    )
}

/** A thin line between two groups of the menu, on the same 16 dp edge as its content; never between rows of one group. */
@Composable
private fun MenuDivider() {
    HorizontalDivider(Modifier.padding(horizontal = POPOVER_EDGE, vertical = 8.dp), color = Tone.outline)
}

/**
 * The window's account menu header (WP-103): the picture at 48 dp, the name in two lines at most and
 * the @username under it when Telegram has one, so two profiles of the same name read apart (one node
 * for a screen reader), and at the end two small icon buttons, each with a tip: Refresh (F5) where
 * the page has one and the switcher with two profiles or more.
 */
@Composable
private fun AccountMenuHead(
    account: Account?,
    profile: com.tmplayer.data.Profile?,
    name: String,
    onRefresh: (() -> Unit)? = null,
    onSwitch: (() -> Unit)? = null,
) {
    val s = LocalStrings.current
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = POPOVER_EDGE, end = 8.dp, top = 4.dp, bottom = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            Modifier
                .weight(1f)
                .semantics(mergeDescendants = true) {}
                .testTag(NAV_MENU_HEAD_TAG),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            androidx.compose.runtime.key(profile?.id) { AccountPictureDesktop(account, profile, desktopAccountName(account, profile), 48.dp) }
            Column(Modifier.weight(1f)) {
                Text(name, style = MaterialTheme.typography.titleMedium, color = Tone.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
                val username = account?.username?.trim()?.trimStart('@')?.takeIf { it.isNotEmpty() }
                if (username != null) {
                    Text("@$username", style = MaterialTheme.typography.bodyMedium, color = Tone.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        if (onRefresh != null) HeadIconButton(TmIcons.Refresh, s.commonRefresh, "F5", NAV_MENU_REFRESH_TAG, onRefresh)
        if (onSwitch != null) HeadIconButton(TmIcons.ChevronDown, s.profilesSwitchProfile, null, NAV_MENU_SWITCH_TAG, onSwitch)
    }
}

/** A 40 dp icon button of the menu's header: a hover fill, the keyboard's ring, a tip and a name for a screen reader. */
@Composable
private fun HeadIconButton(icon: ImageVector, label: String, shortcut: String?, tag: String, onClick: () -> Unit) {
    val interactions = remember { MutableInteractionSource() }
    val focused by interactions.collectIsFocusVisibleAsState()
    val hovered by interactions.collectIsHoveredAsState()
    BarTooltip(label, shortcut) {
        Box(
            Modifier
                .size(POPOVER_ROW)
                .clip(CircleShape)
                .background(if (hovered || focused) Tone.surfaceHigh else Color.Transparent)
                .then(if (focused) Modifier.border(2.dp, Tone.accent, CircleShape) else Modifier)
                .clickable(interactionSource = interactions, indication = androidx.compose.foundation.LocalIndication.current, role = Role.Button, onClick = onClick)
                .semantics { contentDescription = label }
                .testTag(tag),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = Tone.text, modifier = Modifier.size(20.dp))
        }
    }
}

/** The tags of the header's Refresh and switcher buttons. */
internal const val NAV_MENU_REFRESH_TAG = "nav-account-refresh"
internal const val NAV_MENU_SWITCH_TAG = "nav-account-switch"

/**
 * One row of the menu: a fixed 20 dp icon column, then the label on the same left edge in every row
 * (16 dp in), the key that does the same said quietly at the end, 40 dp high, filled under the
 * pointer and under the keyboard's focus.
 */
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
    val hovered by interactions.collectIsHoveredAsState()
    Row(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp)
            .height(POPOVER_ROW)
            // The card's 20 dp corner less the 8 dp inset, so a lit row runs parallel to it.
            .clip(RoundedCornerShape(12.dp))
            .background(if (focused || hovered) Tone.surfaceHigh else Color.Transparent)
            .clickable(interactionSource = interactions, indication = androidx.compose.foundation.LocalIndication.current, role = Role.Button, onClick = onClick)
            .padding(horizontal = 8.dp),
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

/** The menu's one left edge: header, tiles, rows and version all start this far in. */
private val POPOVER_EDGE = 16.dp

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

/** The room a cell keeps each side of its name, and the gap the page keeps above the bar. */
private val NAV_LABEL_SIDES = 6.dp
private val NAV_CLEARANCE_GAP = 8.dp

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
private val POPOVER_WIDTH = 320.dp
private val POPOVER_ROW = 40.dp
private val POPOVER_SHAPE = RoundedCornerShape(20.dp)
