package com.tmplayer.ui.browse

import com.tmplayer.ui.theme.focusRingOnTv
import com.tmplayer.ui.theme.focusScale
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import com.tmplayer.ui.components.tvReturnPoint
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.tmplayer.ui.components.MenuAction
import com.tmplayer.ui.components.TmButton
import com.tmplayer.ui.components.TvMenu
import com.tmplayer.ui.i18n.LocalStrings
import com.tmplayer.ui.icons.TmIcons
import com.tmplayer.ui.theme.Avatar
import com.tmplayer.ui.theme.Motion
import com.tmplayer.ui.theme.Tone
import com.tmplayer.ui.theme.Tv
import com.tmplayer.ui.theme.rememberReducedMotion
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow

/** The bar's row: tall enough for a pill grown under the remote. */
private val BAR_ROW = 48.dp

/** Between the bar's row and the page under it. */
private val TV_BAR_GAP = 12.dp

/** What a page under the bar leaves clear at its top: the safe area, the row and a gap. */
internal val TV_BAR_HEIGHT: Dp = Tv.SafeV + BAR_ROW + TV_BAR_GAP

/**
 * The one start inset every page under the bar lines up with: the start edge of the bar's first
 * item (the account's picture), which is the TV safe area. The end of the bar's last item (the
 * app's mark and name) stands at [TV_BAR_END], where the rows' own end inset is.
 */
internal val TV_START: Dp = Tv.SafeH
internal val TV_BAR_END: Dp = Tv.SafeH + Tv.FocusClearance


/** The phone's two tabs, which the television's bar carries in the same order. */
internal enum class TvBarTab { Home, Chats }

/** Asks from the bar that a page outside the browser (Downloads) cannot answer itself. */
internal object TvBarSignals {
    /** Search was picked on a page with no search of its own: the browser opens its search. */
    val searchAsked = MutableStateFlow(false)

    /** Home's rows have scrolled up under the bar, which then takes the page's ground behind it. */
    val homeScrolled = MutableStateFlow(false)

    /** Back moved the remote from Home's rows to the bar: Home goes back to its top, the carousel whole. */
    val homeToTop = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /**
     * Menu, when no screen took it (WP-02): the activity asks the bar for the account's menu, so a
     * place with no item action (an empty Home, the carousel's background) still reaches it.
     */
    val accountMenuAsked = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /**
     * Asks the bar for the account's menu. False when no bar is on screen (Settings, a phone), where
     * the caller does what Menu did before.
     */
    fun askAccountMenu(): Boolean = accountMenuAsked.subscriptionCount.value > 0 && accountMenuAsked.tryEmit(Unit)
}

/**
 * The bar: the account's picture at the start, which opens the account's menu (Favourites, History,
 * Watch later, Settings, Refresh where the page has something to fetch again ([onRefresh]), and
 * Update when there is one), with the account's first name beside it at rest (WP-01); then Search,
 * and the tabs Home, Chats and Downloads (R2: a place of its own, with the number running); and at
 * the end the app's mark, which takes no focus (its name is left out to pay for the first name).
 *
 * Nothing is behind it: every page but Home starts below it and is clipped at its foot, and Home's
 * carousel picture melts into the page at its top; once Home's rows scroll up under the bar, the
 * page's ground fills in behind it ([solid]).
 *
 * The page being shown is marked by its filled icon (the account's picture by a short accent line
 * under it, the tabs' selected indicator, never a second ring) whether or not the remote is on the bar; the item the remote is on fills and grows. [overArt] says Home runs up under the bar.
 */
@Composable
internal fun TvTopBar(
    nav: TouchNav,
    current: TvBarTab?,
    /** A page behind the picture is shown: History, Favourites or Downloads. */
    inProfile: Boolean,
    searchOpen: Boolean,
    onHome: () -> Unit,
    onChats: () -> Unit,
    onSearch: () -> Unit,
    /** Called before any item of the account's menu goes anywhere (closes the search, say). */
    beforeProfile: () -> Unit = {},
    /** Null on every page but Home (HX44): the chat list keeps itself live on its own now. */
    onRefresh: (() -> Unit)?,
    /** On the item for the page shown: Up from the page, and Back, land there. */
    currentFocus: FocusRequester,
    /** The page under the bar: Down from the bar goes into it, where the remote last was. */
    contentFocus: FocusRequester,
    overArt: Boolean,
    modifier: Modifier = Modifier,
    /** How much of the page's ground stands behind the bar: 1 once Home's rows are under it. */
    solid: () -> Float = { 0f },
    /** Down from the bar, for a page with a place of its own to land (Home's Play): true if it took the remote. */
    onDown: (() -> Boolean)? = null,
    /** Also on the account's picture, for Back from Settings to put the remote back where it opened. */
    profileFocus: FocusRequester? = null,
    /**
     * Opens the Choose chats screen; null leaves the button off the bar (a page outside the
     * browser, the setup before any chat is chosen).
     */
    onChooseChats: (() -> Unit)? = null,
    /** On the Choose chats button, for Back from that screen to put the remote back on it. */
    chooseChatsFocus: FocusRequester? = null,
    /** The Downloads page is the one shown, for the quick entry's mark. */
    downloadsShown: Boolean = false,
) {
    val s = LocalStrings.current
    var profileOpen by remember { mutableStateOf(false) }
    // Menu from a place with no item action: the activity asks, the bar opens the menu.
    LaunchedEffect(Unit) { TvBarSignals.accountMenuAsked.collect { profileOpen = true } }
    // The shell's sign out (WP-02): under its first question the menu stays, and Cancel puts the remote
    // back on Sign out; once it has started the menu closes, since nothing of it belongs to the menu.
    val signOutFocus = remember { FocusRequester() }
    val signOutPhase = nav.signOutPhase
    var wasAsking by remember { mutableStateOf(false) }
    LaunchedEffect(signOutPhase, profileOpen) {
        if (signOutPhase == com.tmplayer.ui.settings.SignOutPhase.Running && profileOpen) profileOpen = false
        if (signOutPhase == com.tmplayer.ui.settings.SignOutPhase.Idle && wasAsking && profileOpen) {
            withFrameNanos { }
            runCatching { signOutFocus.requestFocus() }
        }
        wasAsking = signOutPhase == com.tmplayer.ui.settings.SignOutPhase.Asking
    }
    // Who is signed in: Telegram's name once it is known, else the profile's own (saved on this
    // device, so it is there offline and while Telegram connects).
    val shownName = nav.account?.name?.takeIf { it.isNotBlank() } ?: nav.profile?.shownName?.takeIf { it.isNotBlank() }
    val description = profileDescription(
        name = shownName,
        username = nav.account?.username,
        fallback = s.navAccount,
        updateWaiting = if (nav.updateVersion != null) s.navUpdateWaiting else null,
    )
    val ground = Tone.background
    val homeFocus = remember { FocusRequester() }
    val chatsFocus = remember { FocusRequester() }
    val searchFocus = remember { FocusRequester() }
    val ownProfileFocus = remember { FocusRequester() }
    val downloadsFocus = remember { FocusRequester() }
    // Which item the shared requester sits on; every other one keeps its own.
    val target = if (searchOpen) null else current
    fun focusOf(tab: TvBarTab, own: FocusRequester) = if (target == tab) currentFocus else own
    val searchRequester = if (searchOpen) currentFocus else searchFocus
    val profileRequester = if (!searchOpen && current == null) currentFocus else ownProfileFocus

    Box(
        modifier
            .fillMaxWidth()
            .height(TV_BAR_HEIGHT)
            // Every other page starts below the bar and is clipped there; only Home runs up under
            // it, and once its rows have scrolled up there the page's ground fills in behind.
            .drawBehind { if (solid() > 0f) drawRect(ground.copy(alpha = solid())) },
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = TV_START, end = TV_BAR_END, top = Tv.SafeV)
                .height(BAR_ROW)
                // Entering the bar from the page lands on the page's own item, never on whichever
                // item happens to sit above the tile the remote was on.
                .focusProperties { onEnter = { runCatching { currentFocus.requestFocus() } } }
                // Down goes into the page, whatever stands below this item: the page's own place.
                .onPreviewKeyEvent { event ->
                    // Menu, with the remote in the bar, opens the account's menu on its release (WP-02);
                    // a tile's Menu is its options and is never seen here ([holdable]).
                    if (event.key == Key.Menu) {
                        if (event.type == KeyEventType.KeyUp) profileOpen = true
                        return@onPreviewKeyEvent true
                    }
                    if (event.type == KeyEventType.KeyDown && event.key == Key.DirectionDown) {
                        if (onDown?.invoke() != true) runCatching { contentFocus.requestFocus() }
                        true
                    } else {
                        false
                    }
                }
                .focusGroup(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ProfileButton(
                account = nav.account,
                profile = nav.profile,
                firstName = FirstName.of(shownName),
                chosen = inProfile && !searchOpen && !downloadsShown,
                // An update waiting; the running downloads count on the Downloads tab now.
                badge = if (nav.updateVersion != null) Tone.caution else null,
                description = description,
                onClickLabel = s.navAccountMenu,
                onClick = { profileOpen = true },
                open = profileOpen,
                modifier = Modifier
                    .focusRequester(profileRequester)
                    .tvReturnPoint(profileRequester)
                    .then(if (profileFocus != null) Modifier.focusRequester(profileFocus) else Modifier),
            )
            // The row's own FocusClearance of padding keeps a tab's focus scale and ring from being
            // cut at the row's ends; the spacer gives it back, so the tabs stand where they did.
            Spacer(Modifier.width(18.dp - Tv.FocusClearance))
            Row(
                Modifier.weight(1f).horizontalScroll(rememberScrollState()).padding(horizontal = Tv.FocusClearance),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BarTab(s.commonSearch, TmIcons.Search, searchOpen, onSearch, Modifier.focusRequester(searchRequester).tvReturnPoint(searchRequester))
                BarTab(s.browseTabHome, TmIcons.Home, current == TvBarTab.Home && !searchOpen, onHome, Modifier.focusRequester(focusOf(TvBarTab.Home, homeFocus)).tvReturnPoint(focusOf(TvBarTab.Home, homeFocus)))
                BarTab(s.browseTabChats, TmIcons.Chats, current == TvBarTab.Chats && !searchOpen, onChats, Modifier.focusRequester(focusOf(TvBarTab.Chats, chatsFocus)).tvReturnPoint(focusOf(TvBarTab.Chats, chatsFocus)))
                // Downloads is a place of its own (R2, PL-16): its name written, after Chats, so Right
                // from Chats reaches it and it shows how many are running.
                BarTab(
                    s.navDownloads, TmIcons.Download, selected = downloadsShown && !searchOpen, onClick = { beforeProfile(); nav.onDownloads() },
                    badge = nav.downloadCount,
                    modifier = Modifier.focusRequester(downloadsFocus).tvReturnPoint(downloadsFocus),
                )
                // Watch later is no tab here (WP-14): it stays in the account menu and in History.
                // Add or remove chats, after the quick entries: an action, not a page, so it is
                // only an icon (named under the remote's focus) after the places.
                if (onChooseChats != null) {
                    com.tmplayer.ui.components.ChooseChatsButton(
                        focus = chooseChatsFocus,
                        size = CLUSTER_ITEM,
                        onClick = onChooseChats,
                    )
                }
            }
            Spacer(Modifier.width(18.dp))
            // The app's own mark at the picture's size, the way a television's home screen signs
            // itself. The name is left out: its width pays for the account's first name (WP-01).
            Image(
                com.tmplayer.ui.components.AppLogo.Mark,
                contentDescription = null,
                modifier = Modifier
                    .size(CLUSTER_ITEM)
                    .clip(RoundedCornerShape(CLUSTER_ITEM * com.tmplayer.ui.components.TILE_CORNER))
                    .clearAndSetSemantics { contentDescription = com.tmplayer.ui.components.BRAND_NAME },
            )
        }
    }

    if (profileOpen) {
        val go: (() -> Unit) -> () -> Unit = { action ->
            {
                profileOpen = false
                beforeProfile()
                action()
            }
        }
        val menuName = shownName ?: s.navAccount
        // "Your viewing" under the name, as the phone's and the desktop's menus head theirs;
        // the rows below go to each list.
        val menuSummary = listOfNotNull(
            nav.account?.username?.takeIf { it.isNotBlank() }?.let { "@$it" },
            s.profileSummary(
                s.formatter.number(nav.watchingCount),
                s.formatter.number(nav.watchedCount),
                s.formatter.number(nav.favoriteCount),
            ),
        ).joinToString("  ·  ")
        val several = nav.onSwitchProfile != null
        val switchOrAdd = nav.onSwitchProfile ?: nav.onSwitchOrAdd
        val signOut = nav.onSignOut
        TvMenu(
            title = menuName,
            subtitle = menuSummary,
            header = if (switchOrAdd != null || signOut != null) {
                { first ->
                    AccountMenuHeader(
                        account = nav.account,
                        profile = nav.profile,
                        name = menuName,
                        summary = menuSummary,
                        switchLabel = if (several) s.profilesSwitchProfile else s.profilesAdd,
                        onSwitchOrAdd = switchOrAdd?.let(go),
                        onSignOut = signOut,
                        first = first,
                        signOutFocus = signOutFocus,
                    )
                }
            } else {
                null
            },
            closeOnMenu = true,
            onDismiss = { profileOpen = false },
            actions = buildList {
                add(MenuAction(s.browseTabFavourites, TmIcons.StarOutline, detail = nav.favoriteCount.takeIf { it > 0 }?.toString(), onSelect = go(nav.onFavourites)))
                add(MenuAction(s.browseTabHistory, TmIcons.History, detail = nav.watchingCount.takeIf { it > 0 }?.toString(), onSelect = go { nav.onHistory(HistoryTab.Continue) }))
                add(MenuAction(s.profileWatched, TmIcons.Check, detail = nav.watchedCount.takeIf { it > 0 }?.toString(), onSelect = go { nav.onHistory(HistoryTab.Watched) }))
                add(MenuAction(s.browseTabWatchLater, TmIcons.Clock, onSelect = go { nav.onHistory(HistoryTab.WatchLater) }))
                // Settings, with the account at its top and Sign out at its foot, as on the phone.
                add(MenuAction(s.navSettings, TmIcons.Settings, onSelect = go(nav.onSettings)))
                // Switch profile (HX88) and Add account are the header's two buttons (WP-02).
                // Home and the chat list fetch again from here: Home also refreshes itself when
                // the app comes back after a while (MainActivity).
                if (onRefresh != null) add(MenuAction(s.commonRefresh, TmIcons.Refresh, onSelect = { profileOpen = false; onRefresh() }))
                nav.updateVersion?.let { version -> add(MenuAction(s.navUpdate, TmIcons.Refresh, detail = version, onSelect = go(nav.onUpdate))) }
            },
        )
    }
}

/**
 * The account menu's head (WP-02): whose account it is, as the picture at [Avatar.Card] with the name
 * and the summary beside it, then the two buttons that act on the account: Add account (one
 * profile) or Switch profile (two or more), and Sign out, quiet at rest and red only under the
 * remote. Sign out is one Right from the first button; its question names the account and has Cancel
 * focused. The buttons wrap under a large font.
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun AccountMenuHeader(
    account: com.tmplayer.data.Account?,
    profile: com.tmplayer.data.Profile?,
    name: String,
    summary: String,
    switchLabel: String,
    onSwitchOrAdd: (() -> Unit)?,
    onSignOut: (() -> Unit)?,
    first: FocusRequester,
    signOutFocus: FocusRequester,
) {
    val s = LocalStrings.current
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(
            Modifier.semantics(mergeDescendants = true) {},
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AccountPicture(account, profile, size = Avatar.Card)
            Column(Modifier.weight(1f)) {
                Text(name, style = MaterialTheme.typography.titleLarge, color = Tone.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(summary, style = MaterialTheme.typography.bodyMedium, color = Tone.muted, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        androidx.compose.foundation.layout.FlowRow(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (onSwitchOrAdd != null) {
                // 48 dp targets: the buttons alone are 40 dp.
                TmButton(onClick = onSwitchOrAdd, modifier = Modifier.heightIn(min = 48.dp).focusRequester(first)) { Text(switchLabel) }
            }
            if (onSignOut != null) {
                TmButton(
                    onClick = onSignOut,
                    destructive = true,
                    quietAtRest = true,
                    modifier = Modifier.heightIn(min = 48.dp).focusRequester(signOutFocus).then(if (onSwitchOrAdd == null) Modifier.focusRequester(first) else Modifier),
                ) { Text(s.settingsSignOutConfirm) }
            }
        }
    }
}

/** How many frames, about a second and a half at 60 Hz, a page opened from the bar leaves the remote on it. */
private const val BAR_HOLD_FRAMES = 90

/**
 * The account's picture's focus ring and its halo, both inside the 48 dp rect: the ring starts at
 * the picture's own edge (20 dp, or 21 dp once it has grown 5 percent under focus) and runs 2.5 dp
 * out, then the page's own ground as a 0.5 dp halo, ending at 23.5 dp at most.
 */
private val RING_WIDTH = 2.5.dp
private val HALO_WIDTH = 0.5.dp

/** The picture's growth under focus: from a 20 dp to a 21 dp radius, to meet the ring. Nothing outgrows the 48 dp rect. */
private const val PROFILE_FOCUS_SCALE = 1.05f

/** The height of a tab's pill and the size of the account's picture, so the bar reads as one line. */
private val CLUSTER_ITEM = 40.dp

/** The focus rect of the account's picture (the avatar role's 48 dp): the 40 dp picture stands centred in it. */
private val PROFILE_RECT = CLUSTER_ITEM + 8.dp

/** The widest the first name at rest is drawn: longer ends in an ellipsis. */
private val FIRST_NAME_MAX = 120.dp

/** One of the tabs: its icon and name in a pill, the icon filled while its page is shown. */
@Composable
private fun BarTab(
    label: String,
    icon: ImageVector,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    /** Only the icon until the remote is on it (or it is the page shown), which then names it. */
    compact: Boolean = false,
    /** The number running, after the name; 0 says nothing (Downloads). */
    badge: Int = 0,
) {
    val interactions = remember { MutableInteractionSource() }
    val focused by interactions.collectIsFocusedAsState()
    val pressed by interactions.collectIsPressedAsState()
    val still = rememberReducedMotion() || Motion.reduced
    val scale by animateFloatAsState(
        when {
            still -> 1f
            pressed -> Motion.PressScale
            focused -> focusScale
            else -> 1f
        },
        Motion.standard(Motion.Fast),
        label = "barTabScale",
    )
    val fill by animateColorAsState(if (focused) Tone.focusFill else Color.Transparent, Motion.standard(Motion.Fast), label = "barTabFill")
    val ink by animateColorAsState(
        when {
            focused -> Tone.onFocusFill
            selected -> Tone.text
            else -> Tone.muted
        },
        Motion.standard(Motion.Fast),
        label = "barTabInk",
    )
    val pill = CircleShape
    Box(modifier, contentAlignment = Alignment.Center) {
        Row(
            Modifier
                .height(CLUSTER_ITEM)
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                }
                .clip(pill)
                .background(fill)
                .focusRingOnTv(focused, pill)
                .clickable(interactionSource = interactions, indication = null, role = Role.Tab, onClick = onClick)
                .padding(horizontal = if (compact && !focused && !selected) 10.dp else 14.dp)
                .semantics {
                    // The page shown, said to a screen reader as it is drawn (the filled icon).
                    this.selected = selected
                    if (compact) contentDescription = label
                    // "Downloads, 2": the count is read with the name.
                    if (badge > 0) contentDescription = "$label, $badge"
                },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            com.tmplayer.ui.components.SelectableIcon(icon, selected = selected, tint = ink, size = 20.dp)
            if (!compact || focused || selected) Text(
                label,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = if (selected || focused) FontWeight.SemiBold else FontWeight.Medium,
                color = ink,
                maxLines = 1,
            )
            if (badge > 0) {
                Text(
                    if (badge > 99) "99+" else badge.toString(),
                    style = MaterialTheme.typography.labelMedium,
                    color = Tone.onAccent,
                    maxLines = 1,
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(Tone.accent)
                        .padding(horizontal = 7.dp, vertical = 1.dp)
                        .testTag("tv-bar-badge"),
                )
            }
        }
    }
}

/**
 * The account's picture and first name, first in the bar: opens the account's menu. The focus rect
 * is 48 dp ([PROFILE_RECT]) with the 40 dp picture centred, and the ring and halo are drawn inside
 * it (the avatar role of [com.tmplayer.ui.theme.FocusRole]); the page shown from here is marked by
 * a short accent line under the picture, the selected indicator a chosen tab carries, never a
 * second ring. The first name is there at rest and its width never changes with focus, so the tabs
 * beside it never move.
 */
@Composable
private fun ProfileButton(
    account: com.tmplayer.data.Account?,
    profile: com.tmplayer.data.Profile?,
    /** The first word of the name at rest, or null when no name is known at all. */
    firstName: String?,
    chosen: Boolean,
    /** A dot on the picture: an update waiting (amber). */
    badge: Color?,
    /** What a screen reader says: "<name>, @<username>", with the update waiting after it. */
    description: String,
    /** What OK does, said after "Double tap to": "open the account menu". */
    onClickLabel: String,
    onClick: () -> Unit,
    /** The account's menu is open over the bar: the picture stays marked as the item in use. */
    open: Boolean,
    modifier: Modifier = Modifier,
) {
    val interactions = remember { MutableInteractionSource() }
    val focused by interactions.collectIsFocusedAsState()
    val pressed by interactions.collectIsPressedAsState()
    val still = rememberReducedMotion() || Motion.reduced
    val lit = focused || open
    val scale by animateFloatAsState(
        when {
            still -> 1f
            pressed -> Motion.PressScale
            lit -> PROFILE_FOCUS_SCALE
            else -> 1f
        },
        Motion.standard(Motion.Fast),
        label = "barProfileScale",
    )
    // A ring in the accent round the picture, with a thin halo of the page's own ground outside
    // it, so it reads over a pale page, a dark one and the carousel's picture. Both are drawn
    // inside the 48 dp rect, which itself never scales, so no frame leaves the bar's safe area.
    val ring by animateFloatAsState(if (lit) 1f else 0f, Motion.standard(Motion.Fast), label = "barProfileRing")
    val accent = Tone.accent
    val ground = Tone.background
    Row(
        modifier
            .height(PROFILE_RECT)
            .clickable(interactionSource = interactions, indication = null, role = Role.Button, onClickLabel = onClickLabel, onClick = onClick)
            .semantics { contentDescription = description },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(PROFILE_RECT)
                .drawBehind {
                    val center = Offset(size.width / 2f, size.height / 2f)
                    if (ring > 0f) {
                        val width = RING_WIDTH.toPx()
                        // The picture's edge as it is now, so no gap shows, with reduced motion either.
                        val inner = CLUSTER_ITEM.toPx() / 2f * scale
                        val halo = HALO_WIDTH.toPx()
                        drawCircle(ground.copy(alpha = 0.7f * ring), radius = inner + width + halo / 2f, center = center, style = Stroke(halo))
                        drawCircle(accent.copy(alpha = ring), radius = inner + width / 2f, center = center, style = Stroke(width))
                    }
                    if (chosen) {
                        // The selected indicator: a short line in the accent under the picture.
                        val line = 3.dp.toPx()
                        val length = 24.dp.toPx()
                        drawRoundRect(
                            accent,
                            topLeft = Offset(center.x - length / 2f, size.height - line),
                            size = androidx.compose.ui.geometry.Size(length, line),
                            cornerRadius = androidx.compose.ui.geometry.CornerRadius(line / 2f, line / 2f),
                        )
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            Box(
                Modifier
                    .size(CLUSTER_ITEM)
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                    }
                    .clip(CircleShape),
            ) { AccountPicture(account, profile) }
            if (badge != null) {
                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .padding(top = 1.dp, end = 1.dp)
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(badge)
                        .testTag("tv-bar-update-dot"),
                )
            }
        }
        if (firstName != null) {
            // Read once, in the button's description: the chip is for the eye.
            Box(Modifier.clearAndSetSemantics { }) {
                Text(
                    firstName,
                    style = MaterialTheme.typography.labelLarge,
                    color = Tone.text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = FIRST_NAME_MAX).padding(start = 8.dp, end = 4.dp).testTag("tv-bar-first-name"),
                )
            }
        }
    }
}

/** The first word of a name, for the chip at rest. */
internal object FirstName {
    fun of(name: String?): String? = name?.trim()?.split(WHITESPACE)?.firstOrNull()?.takeIf { it.isNotEmpty() }

    private val WHITESPACE = Regex("\\s+")
}

/**
 * What a screen reader says for the account's button: "<name>, @<username>" (the username only
 * when there is one), the [fallback] ("Account") while no name is known, and the [updateWaiting]
 * words after it while one is given. The amber dot itself stays a picture.
 */
internal fun profileDescription(name: String?, username: String?, fallback: String, updateWaiting: String?): String {
    val known = name?.takeIf { it.isNotBlank() }
    return listOfNotNull(
        known ?: fallback,
        username?.takeIf { known != null && it.isNotBlank() }?.let { "@$it" },
        updateWaiting,
    ).joinToString(", ")
}

/**
 * The bar over a page outside the browser (Downloads): the same items, and the page under it.
 * Kept in one shape whether or not [shown], so the page under it keeps its state when the bar
 * comes and goes (Settings, About and the PIN pad have none).
 */
@Composable
internal fun TvBarFrame(
    shown: Boolean,
    barFocus: FocusRequester,
    contentFocus: FocusRequester,
    /**
     * Bumped when the remote belongs on the bar as the page comes up (back from Settings, which
     * the picture's menu opened): it is held there against the page taking focus, until a key is
     * pressed.
     */
    holdBar: Int = 0,
    bar: @Composable () -> Unit,
    content: @Composable () -> Unit,
) {
    val pressed = remember { booleanArrayOf(false) }
    androidx.compose.runtime.LaunchedEffect(holdBar) {
        if (holdBar == 0) return@LaunchedEffect
        pressed[0] = false
        repeat(BAR_HOLD_FRAMES) {
            if (pressed[0]) return@LaunchedEffect
            androidx.compose.runtime.withFrameNanos { }
            if (shown && !pressed[0]) runCatching { barFocus.requestFocus() }
        }
    }
    Box(
        Modifier.fillMaxSize().onPreviewKeyEvent { event ->
            if (event.type == KeyEventType.KeyDown) pressed[0] = true
            false
        },
    ) {
        // The page starts below the bar, as the browser's pages do, and is clipped there.
        Box(
            Modifier
                .fillMaxSize()
                .padding(top = if (shown) TV_BAR_HEIGHT else 0.dp)
                .clipToBounds()
                .then(if (shown) Modifier.underTvBar(contentFocus, barFocus) else Modifier),
        ) { content() }
        if (shown) bar()
    }
}

/**
 * A page under the TV top bar: Up from its top-most item, where the page has nowhere further up
 * to go, goes to the bar's item for the page ([bar]); Down never leaves it; and [content] (Down
 * from the bar) lands on the item the remote was last on here, else the first.
 */
@Composable
internal fun Modifier.underTvBar(content: FocusRequester, bar: FocusRequester, onUpToBar: () -> Unit = {}): Modifier {
    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
    return this
        .focusRequester(content)
        // After the page's own handling (a heading's See all armed by Up, say): only an Up the
        // page left alone moves here, and where it finds nothing above it goes to the bar.
        .onKeyEvent { event ->
            if (event.type != KeyEventType.KeyDown || event.key != Key.DirectionUp) return@onKeyEvent false
            if (!focusManager.moveFocus(FocusDirection.Up)) {
                onUpToBar()
                runCatching { bar.requestFocus() }
            }
            true
        }
        .focusProperties {
            onExit = {
                if (requestedFocusDirection == FocusDirection.Down) cancelFocusChange()
            }
        }
        // Where the remote goes back to from a banner it reached from here (HX22).
        .tvReturnPoint(content)
        .focusRestorer()
        .focusGroup()
}
