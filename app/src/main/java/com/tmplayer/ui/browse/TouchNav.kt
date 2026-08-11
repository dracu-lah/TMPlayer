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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Refresh
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import com.tmplayer.data.Account
import com.tmplayer.data.Updates
import com.tmplayer.ui.components.AppMark
import com.tmplayer.ui.components.MarkSize
import com.tmplayer.ui.components.MediaPreview
import com.tmplayer.ui.theme.Accent
import com.tmplayer.ui.theme.Background
import com.tmplayer.ui.theme.Caution
import com.tmplayer.ui.theme.SurfaceDark
import com.tmplayer.ui.theme.SurfaceRaised
import com.tmplayer.ui.theme.TextMuted
import com.tmplayer.ui.theme.TextPrimary
import kotlinx.coroutines.launch

/**
 * The phone half of the app's chrome, in ordinary Material 3.
 *
 * TV Material's controls are built around D-pad focus and never dispatch a tap, which was
 * confirmed on a touch-only phone: a control that looked pressable simply was not. So every
 * interactive part of the touch layout comes from `androidx.compose.material3` and the TV branch
 * keeps `androidx.tv.material3` to itself. The two are never mixed inside one control.
 *
 * The palette is the app's own, so a phone and a television are recognisably the same product; it
 * is only the type scale that is left at the Material default, because the TV scale is sized for a
 * sofa and is far too large in the hand.
 */
@Composable
private fun TouchMaterialTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Accent,
            onPrimary = Color.White,
            background = Background,
            onBackground = TextPrimary,
            surface = SurfaceDark,
            onSurface = TextPrimary,
            surfaceVariant = SurfaceRaised,
            onSurfaceVariant = TextPrimary,
            surfaceContainerLow = SurfaceDark,
            surfaceContainerHigh = SurfaceRaised,
            error = Caution,
        ),
        content = content,
    )
}

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
    content: @Composable () -> Unit,
) = TouchMaterialTheme {
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
        Scaffold(
            containerColor = Background,
            topBar = {
                TopAppBar(
                    title = { Text(selected.heading, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    navigationIcon = {
                        // An IconButton, not a bare clickable icon: it carries Material's own
                        // 48 dp touch target around a 24 dp glyph, which is the whole reason a
                        // hamburger drawn at icon size is still hittable with a thumb.
                        IconButton(onClick = { scope.launch { drawerState.open() } }) {
                            Icon(Icons.Filled.Menu, contentDescription = "Open navigation")
                        }
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
