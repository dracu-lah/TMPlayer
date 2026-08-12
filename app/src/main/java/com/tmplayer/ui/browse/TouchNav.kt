package com.tmplayer.ui.browse

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import com.tmplayer.ui.components.MarkSize
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
    /** The phone's Downloads screen. A television keeps one video and has nothing to list. */
    onOpenDownloads: () -> Unit,
    updateVersion: String?,
    onUpdate: () -> Unit,
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
    val drawerState = rememberDrawerState(DrawerValue.Closed)
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
                // Material's own default is 360dp, which is most of a phone's width given over
                // to a handful of one-word destinations and a name. The sheet is sized to what
                // it holds instead, and still yields to the screen on the narrowest devices so
                // that it can never cover the page it is a way back to.
                modifier = Modifier.width(min(DRAWER_WIDTH, screenWidth * DRAWER_MAX_FRACTION)),
            ) {
                DrawerBrand()

                Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                    // The three tabs about this viewer's own watching, first because they are
                    // what somebody opening the app in the evening is reaching for.
                    DrawerDestinations(
                        sections.filter { it in LIBRARY_TABS },
                        selected,
                        favoriteCount,
                        unreadCount,
                    ) {
                        close(); onSelect(it)
                    }

                    // Folders only earn a heading of their own when there are any. An account
                    // with none would otherwise get a rule and the word "Folders" over nothing.
                    val folders = sections.filterIsInstance<BrowseSection.Folder>()
                    if (folders.isNotEmpty()) {
                        DrawerSeparator("Folders")
                        DrawerDestinations(folders, selected, favoriteCount, unreadCount) {
                            close(); onSelect(it)
                        }
                    }

                    DrawerSeparator("Chats")

                    // The four ways of slicing the chat list. Grouping them under a heading is
                    // what turns seven flat destinations into two short lists: the seven read as
                    // one undifferentiated pile, and the eye had to check every one of them to
                    // find out that four of them were the same kind of thing.
                    DrawerDestinations(
                        sections.filter { it !in LIBRARY_TABS && it !is BrowseSection.Folder },
                        selected,
                        favoriteCount,
                        unreadCount,
                    ) {
                        close(); onSelect(it)
                    }
                }

                DrawerSeparator()
                if (updateVersion != null) {
                    DrawerDestination(
                        label = "Update",
                        selected = false,
                        badge = updateVersion,
                        icon = {
                            Icon(
                                Icons.Filled.Refresh,
                                contentDescription = null,
                                tint = Tone.caution,
                            )
                        },
                        onClick = { close(); onUpdate() },
                    )
                }
                DrawerDestination(
                    label = "Downloads",
                    selected = false,
                    badge = null,
                    icon = { Icon(TmIcons.Download, contentDescription = null) },
                    onClick = { close(); onOpenDownloads() },
                )
                DrawerDestination(
                    label = "Settings",
                    selected = false,
                    badge = null,
                    icon = { Icon(Icons.Filled.Settings, contentDescription = null) },
                    onClick = { close(); onOpenSettings() },
                )
                DrawerFooter(account)
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
        // The bar comes back whenever the screen underneath it changes. Without this it stayed
        // hidden across a change of tab and across coming back from the player, and on a tab too
        // short to scroll there was then no pull-down that could bring it back: the title, the
        // hamburger and every way out of the screen were simply gone.
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
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Close search")
                            }
                        } else {
                            IconButton(onClick = { scope.launch { drawerState.open() } }) {
                                Icon(Icons.Filled.Menu, contentDescription = "Open navigation")
                            }
                        }
                    },
                    actions = {
                        // Searching takes the whole bar. Half a bar of buttons beside a field the
                        // user is typing into is where a phone's app bar stops being readable.
                        if (searching) return@TopAppBar
                        if (searchQuery != null) {
                            IconButton(onClick = { searching = true }) {
                                Icon(Icons.Filled.Search, contentDescription = "Search")
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
 * This replaces a pill that sat permanently above the listing with a mic beside it and a Clear
 * button beside that: three controls, always on screen, in the space a phone wants to spend on
 * content. The Telegram idiom, and Material's, is a magnifier that becomes the bar.
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
                        "Search",
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
                Icon(Icons.Filled.Close, contentDescription = "Clear search")
            }
        } else if (onVoiceSearch != null) {
            IconButton(onClick = onVoiceSearch) {
                Icon(TmIcons.Mic, contentDescription = "Search by voice")
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
) {
    NavigationDrawerItem(
        label = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        icon = icon,
        badge = badge?.let { { Text(it) } },
        selected = selected,
        onClick = onClick,
        modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding),
    )
}

/**
 * One group of destinations.
 *
 * Split out so the drawer can be read as the two lists it actually is rather than as one column
 * of seven, which is what it was: a mark, an app name, an avatar, a name, a handle and then seven
 * peers, all before the eye reached anything it could act on.
 */
@Composable
private fun DrawerDestinations(
    tabs: List<BrowseSection>,
    selected: BrowseSection,
    favoriteCount: Int,
    unreadCount: Int,
    onPick: (BrowseSection) -> Unit,
) {
    tabs.forEach { entry ->
        DrawerDestination(
            label = entry.label,
            selected = entry == selected,
            // The only badges left in the list. A count is a thing that changes and is worth
            // noticing; everything else that used to wear one was static text in a shape that
            // promised otherwise.
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
}

/**
 * Which app this is, at the top of the drawer and nothing else.
 *
 * A drawer is pulled out of a phone that is running a dozen other things, so it is the
 * conventional place for the app to name itself once, quietly, the way YouTube and NewPipe both
 * do. What used to be here as well was the whole account block, which made the first thing in the
 * drawer the one thing in it nobody opened the drawer to reach.
 */
@Composable
private fun DrawerBrand() {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AppMark(MarkSize.Inline)
        Spacer(Modifier.width(12.dp))
        Text("TMPlayer", style = MaterialTheme.typography.titleMedium)
    }
}

/**
 * A rule between groups, with an optional heading for the group below it.
 *
 * The heading is deliberately quiet: it is a label on a boundary, not a row, and anything with
 * enough weight to read as a row is one more thing in a drawer that had too many already.
 */
@Composable
private fun DrawerSeparator(heading: String? = null) {
    HorizontalDivider(modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
    if (heading != null) {
        Text(
            heading,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 28.dp, top = 4.dp, bottom = 4.dp),
        )
    }
}

/**
 * Whose library this is, along the bottom edge.
 *
 * Moved out of the header and shrunk to one line, because it answers a question that gets asked
 * once ever ("am I signed in as the right account?") and it was taking the most valuable space in
 * the sheet to do it. The build number rides here for the same reason: it is a thing to read off
 * when reporting a problem, not a destination, and as a badge on Settings it looked like one.
 */
@Composable
private fun DrawerFooter(account: Account?) {
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
                account?.name ?: "Signed in",
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        supportingContent = {
            Text(
                listOfNotNull(
                    account?.username?.takeIf { it.isNotBlank() }?.let { "@$it" },
                    "v${Updates.installedVersion}",
                ).joinToString("  ·  "),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        // The sheet has already painted its own background, and a row that paints a second one
        // over it draws a panel around the account for no reason anybody looking at it could name.
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
    )
}

/**
 * What this viewer has been watching. First, because it is what the app is opened for.
 *
 * Everything else the rail offers falls into the group below the rule without being named here: a
 * destination added later belongs in the drawer whether or not anybody remembers to list it, and a
 * second hand-written list is how one quietly stops appearing at all.
 */
private val LIBRARY_TABS = listOf(BrowseTab.Continue, BrowseTab.Favorites, BrowseTab.Recent)
    .map(BrowseSection::of)

/**
 * How wide the drawer is on a phone that has room for it.
 *
 * Sized to the longest destination name plus its badge rather than to the screen, because that is
 * all the sheet ever holds.
 */
private val DRAWER_WIDTH = 260.dp

/** On a small screen the drawer gives way, so the listing behind it stays visible and tappable. */
private const val DRAWER_MAX_FRACTION = 0.68f
