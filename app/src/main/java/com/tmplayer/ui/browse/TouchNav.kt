package com.tmplayer.ui.browse

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
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
import com.tmplayer.ui.theme.Accent
import com.tmplayer.ui.theme.Background
import com.tmplayer.ui.theme.Caution
import com.tmplayer.ui.theme.SurfaceDark
import com.tmplayer.ui.theme.SurfaceRaised
import com.tmplayer.ui.theme.TextMuted
import com.tmplayer.ui.theme.TextPrimary
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
    selected: BrowseTab,
    favoriteCount: Int,
    onSelect: (BrowseTab) -> Unit,
    onOpenSettings: () -> Unit,
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
            ModalDrawerSheet(
                drawerState = drawerState,
                drawerContainerColor = SurfaceDark,
                // Material's own default is 360dp, which is most of a phone's width given over
                // to a handful of one-word destinations and a name. The sheet is sized to what
                // it holds instead, and still yields to the screen on the narrowest devices so
                // that it can never cover the page it is a way back to.
                modifier = Modifier.width(min(DRAWER_WIDTH, screenWidth * DRAWER_MAX_FRACTION)),
            ) {
                DrawerHeader(account)
                HorizontalDivider(color = TextMuted.copy(alpha = 0.18f))
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                    BrowseTab.entries.forEach { entry ->
                        DrawerDestination(
                            label = entry.label,
                            selected = entry == selected,
                            badge = if (entry == BrowseTab.Favorites && favoriteCount > 0) {
                                favoriteCount.toString()
                            } else {
                                null
                            },
                            icon = {
                                Icon(entry.icon, contentDescription = null)
                            },
                            onClick = { close(); onSelect(entry) },
                        )
                    }
                }
                HorizontalDivider(color = TextMuted.copy(alpha = 0.18f))
                if (updateVersion != null) {
                    DrawerDestination(
                        label = "Update",
                        selected = false,
                        badge = updateVersion,
                        icon = {
                            Icon(
                                Icons.Filled.Refresh,
                                contentDescription = null,
                                tint = Caution,
                            )
                        },
                        onClick = { close(); onUpdate() },
                    )
                }
                DrawerDestination(
                    label = "Settings",
                    selected = false,
                    // Which build this is rides along with Settings here for the same reason it
                    // does on the television: it is information, not a place to go to.
                    badge = "v${Updates.installedVersion}",
                    icon = { Icon(Icons.Filled.Settings, contentDescription = null) },
                    onClick = { close(); onOpenSettings() },
                )
                Spacer(Modifier.height(12.dp))
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

        Scaffold(
            containerColor = Background,
            topBar = {
                TopAppBar(
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
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = SurfaceDark,
                        titleContentColor = TextPrimary,
                        navigationIconContentColor = TextPrimary,
                        actionIconContentColor = TextPrimary,
                    ),
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
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = TextPrimary),
            cursorBrush = SolidColor(Accent),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            modifier = modifier.weight(1f),
            decorationBox = { inner ->
                if (query.isEmpty()) {
                    Text(
                        "Search",
                        style = MaterialTheme.typography.bodyLarge,
                        color = TextMuted,
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
        colors = NavigationDrawerItemDefaults.colors(
            selectedContainerColor = Accent.copy(alpha = 0.16f),
            unselectedContainerColor = Color.Transparent,
            selectedIconColor = Accent,
            unselectedIconColor = TextMuted,
            selectedTextColor = Accent,
            unselectedTextColor = TextPrimary,
            selectedBadgeColor = Accent,
            unselectedBadgeColor = TextMuted,
        ),
        modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding),
    )
}

/**
 * Which app this is and whose account it holds, at the top of the drawer.
 *
 * The rail puts the account in the same place on the television, but no mark: a television shows
 * one app at a time and the launcher just handed it over. A drawer is pulled out of a phone that is
 * running a dozen other things, and its header is the conventional and the only place in the touch
 * shell where the app names itself.
 */
@Composable
private fun DrawerHeader(account: Account?) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 20.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AppMark(MarkSize.Inline)
            Spacer(Modifier.width(12.dp))
            Text(
                "TMPlayer",
                style = MaterialTheme.typography.titleLarge,
                color = TextPrimary,
            )
        }
        Spacer(Modifier.height(16.dp))
        AccountRow(account)
    }
}

@Composable
private fun AccountRow(account: Account?) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(44.dp).clip(CircleShape).background(SurfaceRaised)) {
            if (account != null) {
                MediaPreview(
                    miniThumbnail = account.miniThumbnail,
                    thumbnailFileId = account.photoFileId,
                    fallbackLabel = account.name,
                    modifier = Modifier.fillMaxSize().clip(CircleShape),
                )
            }
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                account?.name ?: "Your account",
                style = MaterialTheme.typography.titleMedium,
                color = TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (!account?.username.isNullOrBlank()) {
                Text(
                    "@${account.username}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * How wide the drawer is on a phone that has room for it.
 *
 * Sized to the longest destination name plus its badge rather than to the screen, because that is
 * all the sheet ever holds.
 */
private val DRAWER_WIDTH = 260.dp

/** On a small screen the drawer gives way, so the listing behind it stays visible and tappable. */
private const val DRAWER_MAX_FRACTION = 0.68f
