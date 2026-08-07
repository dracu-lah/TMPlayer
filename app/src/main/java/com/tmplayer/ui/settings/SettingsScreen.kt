package com.tmplayer.ui.settings

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ExitToApp
import androidx.compose.material.icons.filled.Star
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.tmplayer.data.ChatSummary
import com.tmplayer.data.DiskSpace
import com.tmplayer.data.SettingsStore
import com.tmplayer.data.Td
import com.tmplayer.player.StreamStats
import com.tmplayer.ui.components.TvConfirm
import com.tmplayer.ui.theme.Accent
import com.tmplayer.ui.theme.SurfaceDark
import com.tmplayer.ui.theme.SurfaceRaised
import com.tmplayer.ui.theme.TextMuted
import com.tmplayer.ui.theme.TextPrimary
import com.tmplayer.ui.theme.Tv
import kotlinx.coroutines.launch

private sealed interface Prompt {
    data object ClearCache : Prompt
    data object SignOut : Prompt
}

@Composable
fun SettingsScreen(
    chats: List<ChatSummary>,
    favorites: Set<Long>,
    onLoggedOut: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings = remember { SettingsStore(context) }

    val jumpToFavorite by settings.jumpToFavorite.collectAsStateWithLifecycle(initialValue = true)
    val askBeforeClearing by settings.askBeforeClearing.collectAsStateWithLifecycle(initialValue = true)
    val defaultChatId by settings.defaultChatId.collectAsStateWithLifecycle(initialValue = 0L)

    var cacheBytes by remember { mutableStateOf(0L) }
    var disk by remember { mutableStateOf(DiskSpace.read(context)) }
    var busy by remember { mutableStateOf<String?>(null) }
    var prompt by remember { mutableStateOf<Prompt?>(null) }
    val firstRow = remember { FocusRequester() }

    suspend fun refresh() {
        cacheBytes = runCatching { Td.storageUsedBytes() }.getOrDefault(0L)
        disk = DiskSpace.read(context)
    }

    LaunchedEffect(Unit) {
        refresh()
        runCatching { firstRow.requestFocus() }
    }

    val favoriteChats = remember(chats, favorites) { chats.filter { it.id in favorites } }

    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = Tv.SafeH),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            top = Tv.SafeV,
            bottom = 40.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Column(Modifier.padding(bottom = 12.dp)) {
                Text("Settings", style = MaterialTheme.typography.headlineLarge, color = TextPrimary)
                Text(
                    "Everything here applies immediately.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextMuted,
                )
            }
        }

        // ---- storage ------------------------------------------------------------------------

        item { SectionTitle("Storage") }
        item {
            StorageCard(
                cacheBytes = cacheBytes,
                freeBytes = disk.freeBytes,
                totalBytes = disk.totalBytes,
            )
        }
        item {
            ActionRow(
                title = "Clear cached video now",
                subtitle = "Frees ${StreamStats.formatBytes(cacheBytes)}",
                icon = Icons.Filled.Delete,
                modifier = Modifier.focusRequester(firstRow),
                onClick = { prompt = Prompt.ClearCache },
            )
        }
        item {
            ToggleRow(
                title = "Ask before making room",
                subtitle = "Confirm before the previous film is deleted for a new one",
                checked = askBeforeClearing,
                onToggle = { scope.launch { settings.setAskBeforeClearing(!askBeforeClearing) } },
            )
        }

        // ---- startup ------------------------------------------------------------------------

        item { SectionTitle("On launch") }
        item {
            ToggleRow(
                title = "Open my favourite straight away",
                subtitle = if (favoriteChats.size > 1) {
                    "Goes to the chat marked default below"
                } else {
                    "Skips the chat list when there is one favourite"
                },
                checked = jumpToFavorite,
                onToggle = { scope.launch { settings.setJumpToFavorite(!jumpToFavorite) } },
            )
        }
        if (jumpToFavorite && favoriteChats.size > 1) {
            item {
                Text(
                    "Which favourite opens",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextMuted,
                    modifier = Modifier.padding(top = 8.dp, start = 4.dp),
                )
            }
            items(favoriteChats, key = { "fav-${it.id}" }) { chat ->
                ActionRow(
                    title = chat.title,
                    subtitle = if (chat.id == defaultChatId) "Opens on launch" else "Make default",
                    icon = if (chat.id == defaultChatId) Icons.Filled.Check else Icons.Filled.Star,
                    tint = if (chat.id == defaultChatId) Accent else TextPrimary,
                    onClick = {
                        scope.launch {
                            // Tapping the current default clears it rather than doing nothing.
                            val next = if (chat.id == defaultChatId) 0L else chat.id
                            settings.setDefaultChatId(next)
                        }
                    },
                )
            }
        }

        // ---- account ------------------------------------------------------------------------

        item { SectionTitle("Account") }
        item {
            ActionRow(
                title = "Sign out of Telegram",
                subtitle = "Removes this device from your Telegram sessions",
                icon = Icons.Filled.ExitToApp,
                onClick = { prompt = Prompt.SignOut },
            )
        }

        item {
            Column(Modifier.padding(top = 20.dp)) {
                busy?.let {
                    Text(it, style = MaterialTheme.typography.bodyLarge, color = Accent)
                    Spacer(Modifier.height(12.dp))
                }
                Text(
                    "TMPlayer is open source and talks only to Telegram's own servers.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextMuted,
                )
            }
        }
    }

    when (prompt) {
        Prompt.ClearCache -> TvConfirm(
            title = "Clear cached video?",
            message = "Everything downloaded so far is removed, freeing " +
                "${StreamStats.formatBytes(cacheBytes)}. Films you re-open will download again.",
            detail = "Your Telegram account, chats and favourites are untouched.",
            confirmLabel = "Clear now",
            onConfirm = {
                prompt = null
                scope.launch {
                    busy = "Clearing…"
                    runCatching { Td.clearMediaCache() }
                    refresh()
                    busy = null
                }
            },
            onDismiss = { prompt = null },
        )

        Prompt.SignOut -> TvConfirm(
            title = "Sign out of Telegram?",
            message = "TMPlayer will forget this account and return to the QR code. " +
                "Cached video is deleted too.",
            confirmLabel = "Sign out",
            onConfirm = {
                prompt = null
                scope.launch {
                    busy = "Signing out…"
                    runCatching { Td.clearMediaCache() }
                    Td.logOut()
                    onLoggedOut()
                }
            },
            onDismiss = { prompt = null },
        )

        null -> Unit
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleLarge,
        color = TextPrimary,
        modifier = Modifier.padding(top = 16.dp, bottom = 2.dp),
    )
}

@Composable
private fun StorageCard(cacheBytes: Long, freeBytes: Long, totalBytes: Long) {
    val used = (totalBytes - freeBytes).coerceAtLeast(0)
    val usedFraction = if (totalBytes > 0) used.toFloat() / totalBytes else 0f
    val cacheFraction = if (totalBytes > 0) cacheBytes.toFloat() / totalBytes else 0f

    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(SurfaceDark)
            .padding(20.dp),
    ) {
        Text(
            "${StreamStats.formatBytes(freeBytes)} free of ${StreamStats.formatBytes(totalBytes)}",
            style = MaterialTheme.typography.titleLarge,
            color = TextPrimary,
        )
        Spacer(Modifier.height(16.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .height(14.dp)
                .clip(RoundedCornerShape(7.dp))
                .background(SurfaceRaised),
        ) {
            // Everything on the device, then TMPlayer's own slice highlighted inside it.
            Box(
                Modifier
                    .fillMaxWidth(usedFraction)
                    .fillMaxHeight()
                    .background(TextMuted.copy(alpha = 0.6f)),
            )
            Box(
                Modifier
                    .fillMaxWidth(cacheFraction)
                    .fillMaxHeight()
                    .background(Accent),
            )
        }
        Spacer(Modifier.height(16.dp))
        Text(
            "TMPlayer is holding ${StreamStats.formatBytes(cacheBytes)} of video.",
            style = MaterialTheme.typography.bodyLarge,
            color = TextPrimary,
        )
        Text(
            "One film is kept at a time — starting another replaces it.",
            style = MaterialTheme.typography.bodyMedium,
            color = TextMuted,
        )
    }
}

@Composable
private fun ActionRow(
    title: String,
    subtitle: String,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    tint: Color = TextPrimary,
    onClick: () -> Unit,
) {
    FocusRow(modifier, onClick) { focused ->
        Icon(
            icon,
            contentDescription = null,
            tint = if (focused) Color.White else tint,
            modifier = Modifier.size(26.dp),
        )
        Spacer(Modifier.size(20.dp))
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                color = if (focused) Color.White else TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = if (focused) Color.White.copy(alpha = 0.85f) else TextMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun ToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onToggle: () -> Unit,
) {
    FocusRow(Modifier, onToggle) { focused ->
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                color = if (focused) Color.White else TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = if (focused) Color.White.copy(alpha = 0.85f) else TextMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Switch(checked = checked, focused = focused)
    }
}

/** A plain pill switch — big enough to read across a room, with no touch affordances implied. */
@Composable
private fun Switch(checked: Boolean, focused: Boolean) {
    val track by animateColorAsState(
        targetValue = when {
            checked && focused -> Color.White
            checked -> Accent
            focused -> Color.White.copy(alpha = 0.4f)
            else -> SurfaceRaised
        },
        animationSpec = tween(140),
        label = "switchTrack",
    )
    Box(
        Modifier
            .size(width = 64.dp, height = 34.dp)
            .clip(RoundedCornerShape(17.dp))
            .background(track),
        contentAlignment = if (checked) Alignment.CenterEnd else Alignment.CenterStart,
    ) {
        Box(
            Modifier
                .padding(horizontal = 4.dp)
                .size(26.dp)
                .clip(RoundedCornerShape(13.dp))
                .background(if (checked && !focused) Color.White else SurfaceDark),
        )
    }
}

@Composable
private fun FocusRow(
    modifier: Modifier,
    onClick: () -> Unit,
    content: @Composable androidx.compose.foundation.layout.RowScope.(focused: Boolean) -> Unit,
) {
    val interactions = remember { MutableInteractionSource() }
    val focused by interactions.collectIsFocusedAsState()
    val background by animateColorAsState(
        targetValue = if (focused) Accent else SurfaceDark,
        animationSpec = tween(140),
        label = "rowBackground",
    )

    Row(
        modifier
            .fillMaxWidth()
            .height(66.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(background)
            .clickable(interactionSource = interactions, indication = null, onClick = onClick)
            .padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        content(focused)
    }
}
