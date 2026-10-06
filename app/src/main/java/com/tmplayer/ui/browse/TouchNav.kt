package com.tmplayer.ui.browse

import com.tmplayer.ui.i18n.LocalStrings
import com.tmplayer.ui.nav.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemColors
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.foundation.layout.height
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import com.tmplayer.data.Account
import com.tmplayer.data.Updates
import com.tmplayer.ui.components.AppMark
import com.tmplayer.ui.components.MediaPreview
import com.tmplayer.ui.components.TmIcons
import com.tmplayer.ui.theme.Avatar
import com.tmplayer.ui.theme.Tone
import kotlinx.coroutines.launch

/**
 * The touch shell: a drawer of destinations behind a hamburger, with [content] filling the rest.
 *
 * The drawer carries exactly what the television's permanent rail carries, in the same order, so
 * neither device has a way in that the other lacks. It starts closed, because on a phone the
 * listing is the reason the app was opened and a rail taking a third of a 411 dp screen is not
 * navigation, it is an obstruction.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TouchBrowseShell(
    account: Account?,
    selected: BrowseSection,
    /** Every destination the rail offers, with this account's folders already slotted in. */
    sections: List<BrowseSection>,
    favoriteCount: Int,
    unreadCount: Int,
    onSelect: (BrowseSection) -> Unit,
    onOpenSettings: () -> Unit,
    /**
     * The Downloads screen. The one-video rule is the watch cache's, not this list's: downloads
     * kept on purpose pile up on a television exactly as they do here, so its rail carries the
     * same row this drawer does.
     */
    onOpenDownloads: () -> Unit,
    updateVersion: String?,
    onUpdate: () -> Unit,
    /** Downloads in flight, badged on the Downloads row so a fetch is never invisible. */
    downloadCount: Int = 0,
    /** What the bar says when it is not being searched in. */
    title: String = selected.heading,
    /**
     * The live search text, or null on a tab that cannot be searched.
     *
     * Passing null is what removes the magnifier altogether, rather than leaving a control that
     * opens a field nothing is listening to.
     */
    searchQuery: String? = null,
    onSearchQueryChange: (String) -> Unit = {},
    /** Offered inside the field when the device can hear; absent when it cannot. */
    onVoiceSearch: (() -> Unit)? = null,
    /** The bar's own buttons, to the right of the magnifier. */
    actions: @Composable RowScope.() -> Unit = {},
    content: @Composable () -> Unit,
) {
    val s = LocalStrings.current
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val groups = rememberNavGroups(current = navGroupOf(selected))
    val scope = rememberCoroutineScope()
    val screenWidth = LocalConfiguration.current.screenWidthDp.dp
    fun close() = scope.launch { drawerState.close() }

    // Back closes the drawer before it leaves the screen, which is what every phone user expects
    // of an open drawer and what the hardware key would otherwise skip straight past.
    BackHandler(enabled = drawerState.isOpen) { close() }

    ModalNavigationDrawer(
        drawerState = drawerState,
        // Left as it comes, so a drag from the left edge opens the drawer without the hamburger.
        gesturesEnabled = true,
        drawerContent = {
            // No colours of its own: the sheet, its rows, its rules and its headings all come
            // from the scheme, which on this device may have been taken from the wallpaper.
            ModalDrawerSheet(
                drawerState = drawerState,
                // Sized to what it holds rather than Material's 360dp default, and still yielding
                // to the screen on the narrowest devices so it can never cover the page behind it.
                modifier = Modifier.width(min(DRAWER_WIDTH, screenWidth * DRAWER_MAX_FRACTION)),
            ) {
                // Measured rather than read from the configuration, which on some phones reports a
                // portrait window while the screen is drawn sideways.
                BoxWithConstraints {
                    // A phone held sideways is too short to pin the brand and the bottom rows and
                    // still scroll a useful middle between them: the middle shrank to a single row
                    // and the chats could not be reached at all. Short sheets scroll as one list
                    // instead.
                    val short = maxHeight < SHORT_DRAWER
                    val sheetScroll = rememberScrollState()
                    val sheet = if (short) {
                        Modifier.verticalScroll(sheetScroll)
                    } else {
                        Modifier.fillMaxHeight()
                    }
                    Column(sheet) {
                        DrawerBrand()

                        val middleScroll = rememberScrollState()
                        val middle = if (short) {
                            Modifier
                        } else {
                            Modifier.weight(1f).verticalScroll(middleScroll)
                        }
                        Column(middle) {
                            // Watch with no heading, then Chats and the account's folders, each
                            // folding under its heading. The grouping is the shared one in :ui, so
                            // the drawer, the television's rail and the desktop's side bar fold the
                            // same way.
                            // Downloads is pinned at the foot beside Settings, not folded into Watch.
                            navGroups(sections, withDownloads = false).forEach { (group, entries) ->
                                val open = groups.isOpen(group)
                                if (group.headed) NavGroupHeading(
                                    group = group,
                                    open = open,
                                    toggleable = groups.canToggle(group),
                                    onToggle = { groups.toggle(group) },
                                    height = DRAWER_ROW,
                                    start = 16.dp,
                                    modifier = Modifier.padding(horizontal = 12.dp),
                                )
                                NavGroupBody(open) {
                                    entries.forEach { entry ->
                                        when (entry) {
                                            NavEntry.Downloads -> DrawerDestination(
                                                label = s.navDownloads,
                                                selected = false,
                                                // How many videos are coming down right now. A
                                                // download outlives the screen it was started
                                                // from, so without a mark here the only evidence it
                                                // is running is a notification the viewer may well
                                                // have swiped away.
                                                badge = downloadCount.takeIf { it > 0 }?.toString(),
                                                icon = { Icon(TmIcons.Download, contentDescription = null) },
                                                onClick = { close(); onOpenDownloads() },
                                            )
                                            is NavEntry.Section -> DrawerSection(
                                                entry.section,
                                                selected,
                                                favoriteCount,
                                                unreadCount,
                                            ) {
                                                close(); onSelect(it)
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        DrawerSeparator()
                        DrawerDestination(
                            label = s.navDownloads,
                            selected = false,
                            // How many videos are coming down right now. A download outlives the
                            // screen it was started from, so without a mark here the only evidence
                            // it is running is a notification the viewer may well have swiped away.
                            badge = downloadCount.takeIf { it > 0 }?.toString(),
                            icon = { Icon(TmIcons.Download, contentDescription = null) },
                            onClick = { close(); onOpenDownloads() },
                        )
                        if (updateVersion != null) {
                            // Amber on the icon, the label and the version alike, as on the TV
                            // rail and the desktop side bar: the one item that is news.
                            DrawerDestination(
                                label = s.navUpdate,
                                selected = false,
                                badge = updateVersion,
                                icon = { Icon(Icons.Filled.Refresh, contentDescription = null) },
                                onClick = { close(); onUpdate() },
                                colors = NavigationDrawerItemDefaults.colors(
                                    unselectedIconColor = Tone.caution,
                                    unselectedTextColor = Tone.caution,
                                    unselectedBadgeColor = Tone.caution,
                                ),
                            )
                        }
                        DrawerDestination(
                            label = s.navSettings,
                            selected = false,
                            badge = null,
                            icon = { Icon(Icons.Filled.Settings, contentDescription = null) },
                            onClick = { close(); onOpenSettings() },
                        )
                        DrawerFooter(account)
                    }
                }
            }
        },
    ) {
        // Whether the bar is currently a search field rather than a title. Saveable, because a
        // rotation in the middle of typing a chat name should not throw the query away.
        var searching by rememberSaveable { mutableStateOf(false) }
        val searchFocus = remember { FocusRequester() }

        // Back leaves the search before it leaves the screen: the field is a mode, and the hardware
        // key is how a phone user closes a mode.
        BackHandler(enabled = searching) {
            searching = false
            onSearchQueryChange("")
        }

        // The bar slides away as the listing is pushed up and comes back on the first pull down,
        // which is what gives a 411 dp screen its content back without asking anybody to reach for
        // anything. It is parked while searching: a field the keyboard is open on must not be able
        // to scroll off the top of its own results.
        val scrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior()
        LaunchedEffect(searching) {
            if (searching) scrollBehavior.state.heightOffset = 0f
        }
        // The bar comes back whenever the screen underneath it changes. A bar left hidden across a
        // change of tab has no pull-down to restore it on a tab too short to scroll, which takes
        // the title, the hamburger and every way out of the screen with it.
        LaunchedEffect(selected, title) { scrollBehavior.state.heightOffset = 0f }
        // And again on the way back from the player, which is an activity coming forward rather
        // than a recomposition, so nothing above would have noticed it.
        val lifecycleOwner = LocalLifecycleOwner.current
        DisposableEffect(lifecycleOwner) {
            val observer = LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME) scrollBehavior.state.heightOffset = 0f
            }
            lifecycleOwner.lifecycle.addObserver(observer)
            onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
        }

        Scaffold(
            modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
            topBar = {
                TopAppBar(
                    scrollBehavior = if (searching) null else scrollBehavior,
                    title = {
                        if (searching && searchQuery != null) {
                            AppBarSearchField(
                                query = searchQuery,
                                onQueryChange = onSearchQueryChange,
                                onVoiceSearch = onVoiceSearch,
                                modifier = Modifier.focusRequester(searchFocus),
                            )
                        } else {
                            Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    },
                    navigationIcon = {
                        // An IconButton, not a bare clickable icon: it carries Material's own
                        // 48 dp touch target around a 24 dp glyph, which is the whole reason a
                        // hamburger drawn at icon size is still hittable with a thumb.
                        if (searching) {
                            IconButton(
                                onClick = { searching = false; onSearchQueryChange("") },
                            ) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = s.browseCloseSearch)
                            }
                        } else {
                            IconButton(onClick = { scope.launch { drawerState.open() } }) {
                                Icon(Icons.Filled.Menu, contentDescription = s.browseOpenNavigation)
                            }
                        }
                    },
                    actions = {
                        // Searching takes the whole bar. Half a bar of buttons beside a field the
                        // user is typing into is where a phone's app bar stops being readable.
                        if (searching) return@TopAppBar
                        if (searchQuery != null) {
                            IconButton(onClick = { searching = true }) {
                                Icon(Icons.Filled.Search, contentDescription = s.commonSearch)
                            }
                        }
                        actions()
                    },
                )
            },
        ) { padding ->
            Box(Modifier.fillMaxSize().padding(padding)) { content() }
        }

        // The magnifier was pressed to type, so the keyboard should already be up by the time the
        // field finishes drawing.
        LaunchedEffect(searching) {
            if (searching) searchFocus.requestFocus()
        }
    }
}

/**
 * The search box that lives inside the app bar, in place of the title.
 *
 * A magnifier that becomes the bar is both the Telegram idiom and Material's, and it spends no
 * permanent screen space on controls a phone would rather give to content.
 *
 * A [BasicTextField] rather than a `TextField`, because Material's own field brings a container,
 * a label slot and a good deal of vertical padding that a 64 dp app bar has no room for.
 */
@Composable
private fun AppBarSearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    onVoiceSearch: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val s = LocalStrings.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        BasicTextField(
            value = query,
            onValueChange = onQueryChange,
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyLarge
                .copy(color = MaterialTheme.colorScheme.onSurface),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            modifier = modifier.weight(1f),
            decorationBox = { inner ->
                if (query.isEmpty()) {
                    Text(
                        s.commonSearch,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
                inner()
            },
        )
        // Clear lives inside the field, where a phone user reaches for it, rather than as a
        // separate labelled button in the row below.
        if (query.isNotEmpty()) {
            IconButton(onClick = { onQueryChange("") }) {
                Icon(Icons.Filled.Close, contentDescription = s.browseClearSearch)
            }
        } else if (onVoiceSearch != null) {
            IconButton(onClick = onVoiceSearch) {
                Icon(TmIcons.Mic, contentDescription = s.browseSearchByVoice)
            }
        }
    }
}

@Composable
private fun DrawerDestination(
    label: String,
    selected: Boolean,
    badge: String?,
    icon: @Composable () -> Unit,
    onClick: () -> Unit,
    colors: NavigationDrawerItemColors = NavigationDrawerItemDefaults.colors(),
) {
    NavigationDrawerItem(
        label = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        icon = icon,
        badge = badge?.let { { Text(it) } },
        selected = selected,
        onClick = onClick,
        // 48 dp rather than Material's 56: still a full thumb target, and the drawer holds a dozen
        // of them.
        modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding).height(DRAWER_ROW),
        colors = colors,
    )
}

/** One browse section as a drawer row. */
@Composable
private fun DrawerSection(
    entry: BrowseSection,
    selected: BrowseSection,
    favoriteCount: Int,
    unreadCount: Int,
    onPick: (BrowseSection) -> Unit,
) {
    DrawerDestination(
        label = entry.label,
        selected = entry == selected,
        // Only counts get a badge: a badge promises something that changes and is worth
        // noticing, which static text is not.
        badge = when {
            entry == BrowseSection.of(BrowseTab.Favorites) && favoriteCount > 0 ->
                favoriteCount.toString()
            entry == BrowseSection.of(BrowseTab.Unread) && unreadCount > 0 ->
                unreadCount.toString()
            else -> null
        },
        icon = { Icon(entry.icon, contentDescription = null) },
        onClick = { onPick(entry) },
    )
}

/**
 * Which app this is, at the top of the drawer and nothing else.
 *
 * A drawer is pulled out of a phone that is running a dozen other things, so it is the conventional
 * place for the app to name itself once, quietly, the way YouTube and NewPipe both do. The account
 * belongs in the footer, not here: nobody opens a drawer to reach it. The version sits beside the
 * name, small and muted, as something to read off when reporting a problem.
 */
@Composable
private fun DrawerBrand() {
    NavBrand(
        version = Updates.installedVersion,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 14.dp),
        logo = { AppMark(BRAND_MARK) },
    )
}

/** The rule above the rows pinned to the bottom of the drawer. */
@Composable
private fun DrawerSeparator() {
    HorizontalDivider(modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp))
}

/**
 * Whose library this is, along the bottom edge.
 *
 * One line along the bottom, because it answers a question asked once ever ("am I signed in as the
 * right account?") and should not take the sheet's most valuable space to do it. The version used
 * to ride here too; it now sits beside the name at the top.
 */
@Composable
private fun DrawerFooter(account: Account?) {
    val s = LocalStrings.current
    HorizontalDivider(modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
    ListItem(
        leadingContent = {
            Box(
                Modifier
                    .size(Avatar.Compact)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest),
            ) {
                if (account != null) {
                    MediaPreview(
                        miniThumbnail = account.miniThumbnail,
                        thumbnailFileId = account.photoFileId,
                        fallbackLabel = account.name,
                        modifier = Modifier.fillMaxSize().clip(CircleShape),
                    )
                }
            }
        },
        headlineContent = {
            Text(
                account?.name ?: s.browseSignedIn,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        supportingContent = account?.username?.takeIf { it.isNotBlank() }?.let { username ->
            { Text("@$username", maxLines = 1, overflow = TextOverflow.Ellipsis) }
        },
        // The sheet has already painted its own background, and a second one over it draws a
        // panel around the account for no reason.
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
    )
}

/** A drawer row and a group heading: a full thumb target, and no taller. */
private val DRAWER_ROW = 48.dp

/** The logo beside the name and version, smaller than the 40 dp inline mark so the row stays short. */
private val BRAND_MARK = 32.dp

/**
 * How wide the drawer is on a phone that has room for it.
 *
 * Sized to the longest destination name plus its badge rather than to the screen, because that is
 * all the sheet ever holds.
 */
private val DRAWER_WIDTH = 260.dp

/** Below this the drawer scrolls as one list rather than pinning its top and bottom. */
private val SHORT_DRAWER = 560.dp

/** On a small screen the drawer gives way, so the listing behind it stays visible and tappable. */
private const val DRAWER_MAX_FRACTION = 0.68f
