package com.tmplayer.ui.settings

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ExitToApp
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Refresh
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
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.layout.BoxWithConstraintsScope
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.tmplayer.data.ChatSummary
import com.tmplayer.data.DiskSpace
import com.tmplayer.data.SettingsStore
import com.tmplayer.data.SizeFilter
import com.tmplayer.data.Td
import com.tmplayer.player.StreamStats
import com.tmplayer.ui.components.TmIcons
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
    onLoggedOut: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings = remember { SettingsStore(context) }

    val openLastChat by settings.openLastChat.collectAsStateWithLifecycle(initialValue = true)
    val askBeforeClearing by settings.askBeforeClearing.collectAsStateWithLifecycle(initialValue = true)
    val lastChatId by settings.lastChatId.collectAsStateWithLifecycle(initialValue = 0L)
    val minSize by settings.minSizeBytes.collectAsStateWithLifecycle(
        initialValue = SizeFilter.DEFAULT_MIN,
    )
    val maxSize by settings.maxSizeBytes.collectAsStateWithLifecycle(
        initialValue = SizeFilter.DEFAULT_MAX,
    )

    var cacheBytes by remember { mutableStateOf(0L) }
    var disk by remember { mutableStateOf(DiskSpace.read(context)) }
    var busy by remember { mutableStateOf<String?>(null) }
    var prompt by remember { mutableStateOf<Prompt?>(null) }
    // Which end of the range the D-pad is currently moving.
    var editingUpper by remember { mutableStateOf(false) }
    // The range row is where focus lands, and where it is sent back to after a reset. It is the
    // first control on the screen and it changes nothing on its own, unlike the delete row further
    // down, which is both destructive and too far down the list to be on screen at all.
    val rangeRow = remember { FocusRequester() }

    suspend fun refresh() {
        cacheBytes = runCatching { Td.storageUsedBytes() }.getOrDefault(0L)
        disk = DiskSpace.read(context)
    }

    LaunchedEffect(Unit) {
        refresh()
        runCatching { rangeRow.requestFocus() }
    }

    // Named, when the chat list has loaded. On a cold start into Settings it may not have, and
    // the setting still has to describe itself, so the wording below covers both.
    val lastChatTitle = remember(chats, lastChatId) {
        chats.firstOrNull { it.id == lastChatId }?.title
    }

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
                    "Changes save as you make them.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextMuted,
                )
            }
        }

        // ---- what shows up --------------------------------------------------------------

        item {
            Row(
                Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Video size limits",
                    style = MaterialTheme.typography.titleLarge,
                    color = TextPrimary,
                    modifier = Modifier.weight(1f),
                )
                ResetChip(
                    enabled = minSize != SizeFilter.DEFAULT_MIN || maxSize != SizeFilter.DEFAULT_MAX,
                ) {
                    // Hand focus to the range row before resetting. The chip greys itself out the
                    // moment the defaults are back, and focus left sitting on a spent control has
                    // nowhere obvious to go; the row below is what the press just changed anyway.
                    runCatching { rangeRow.requestFocus() }
                    scope.launch {
                        // Widen first, so the clamp that stops the two ends crossing cannot
                        // block the new floor on its way past the old ceiling.
                        settings.setMaxSizeBytes(SizeFilter.DEFAULT_MAX)
                        settings.setMinSizeBytes(SizeFilter.DEFAULT_MIN)
                    }
                }
            }
        }
        item {
            Text(
                // Phrased around whichever ends are actually set.
                SizeFilter.describe(minSize, maxSize) +
                    " This keeps short clips and trailers out of the list.",
                style = MaterialTheme.typography.bodyMedium,
                color = TextMuted,
                modifier = Modifier.padding(bottom = 4.dp, start = 4.dp),
            )
        }
        item {
            RangeRow(
                minValue = minSize,
                maxValue = maxSize,
                modifier = Modifier.focusRequester(rangeRow),
                editingUpper = editingUpper,
                onSwitchEnd = { editingUpper = !editingUpper },
                onStep = { direction ->
                    scope.launch {
                        if (editingUpper) {
                            settings.setMaxSizeBytes(SizeFilter.step(maxSize, direction))
                        } else {
                            settings.setMinSizeBytes(SizeFilter.step(minSize, direction))
                        }
                    }
                },
            )
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
                title = "Delete the downloaded film",
                subtitle = "Frees up ${StreamStats.formatBytes(cacheBytes)}",
                icon = Icons.Filled.Delete,
                onClick = { prompt = Prompt.ClearCache },
            )
        }
        item {
            ToggleRow(
                title = "Ask before deleting a film",
                subtitle = "Check with you before a new film replaces the last one",
                icon = Icons.Filled.Notifications,
                checked = askBeforeClearing,
                onToggle = { scope.launch { settings.setAskBeforeClearing(!askBeforeClearing) } },
            )
        }

        // ---- startup ------------------------------------------------------------------------

        item { SectionTitle("On launch") }
        item {
            ToggleRow(
                title = "Carry on from the last chat",
                subtitle = when {
                    lastChatTitle != null -> "Opens $lastChatTitle"
                    lastChatId != 0L -> "Opens the chat you watched last"
                    else -> "Skips the chat list once you have watched something"
                },
                icon = TmIcons.Clock,
                checked = openLastChat,
                onToggle = { scope.launch { settings.setOpenLastChat(!openLastChat) } },
            )
        }
        if (openLastChat && lastChatId != 0L) {
            item {
                ActionRow(
                    title = "Forget it",
                    subtitle = "Start at the chat list again until you open another chat",
                    icon = Icons.Filled.Close,
                    onClick = { scope.launch { settings.forgetLastChat() } },
                )
            }
        }

        // ---- account ------------------------------------------------------------------------

        item { SectionTitle("Account") }
        item {
            ActionRow(
                title = "Sign out of Telegram",
                subtitle = "This TV will stop appearing in your Telegram devices",
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
                    "TMPlayer only ever connects to Telegram. Nothing you watch is sent " +
                        "anywhere else.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextMuted,
                )
            }
        }
    }

    when (prompt) {
        Prompt.ClearCache -> TvConfirm(
            title = "Delete the downloaded film?",
            message = "This frees up ${StreamStats.formatBytes(cacheBytes)}. Any film you open " +
                "again will download again.",
            detail = "Your Telegram account, chats and favourites are untouched.",
            confirmLabel = "Delete",
            onConfirm = {
                prompt = null
                scope.launch {
                    busy = "Deleting…"
                    runCatching { Td.clearMediaCache() }
                    refresh()
                    busy = null
                }
            },
            onDismiss = { prompt = null },
        )

        Prompt.SignOut -> TvConfirm(
            title = "Sign out of Telegram?",
            message = "You'll be signed out and taken back to the sign-in screen. " +
                "The downloaded film is deleted too.",
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

/**
 * A slider a remote can actually drive: left and right nudge it, and the value is spelled out
 * rather than left to be guessed from the thumb position.
 *
 * Left/right are consumed here, so focus cannot escape sideways mid-adjustment. Settings is a
 * single vertical column, so nothing is lost by that.
 */
@Composable
private fun RangeRow(
    minValue: Long,
    maxValue: Long,
    editingUpper: Boolean,
    onSwitchEnd: () -> Unit,
    onStep: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val interactions = remember { MutableInteractionSource() }
    val focused by interactions.collectIsFocusedAsState()
    val background by animateColorAsState(
        targetValue = if (focused) Accent else SurfaceDark,
        animationSpec = tween(140),
        label = "rangeBackground",
    )
    val onSurface = if (focused) Color.White else TextPrimary
    val dim = if (focused) Color.White.copy(alpha = 0.6f) else TextMuted

    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(background)
            .onKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                when (event.key) {
                    Key.DirectionLeft -> { onStep(-1); true }
                    Key.DirectionRight -> { onStep(1); true }
                    // OK swaps which end moves, so one control covers both without the viewer
                    // having to work out which of two identical-looking rows they are on.
                    Key.DirectionCenter, Key.Enter -> { onSwitchEnd(); true }
                    else -> false
                }
            }
            .focusable(interactionSource = interactions)
            .padding(horizontal = 20.dp, vertical = 16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            End("From", SizeFilter.label(minValue), active = !editingUpper, onSurface, dim, focused)
            Spacer(Modifier.weight(1f))
            End("Up to", SizeFilter.label(maxValue), active = editingUpper, onSurface, dim, focused)
        }
        Spacer(Modifier.height(12.dp))
        RangeTrack(minValue, maxValue, editingUpper, focused)
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            // The ends of the track say what the ends of the range would read as, so the track
            // and the "From" / "Up to" captions above it cannot disagree with each other.
            Text(
                SizeFilter.label(SizeFilter.FLOOR),
                style = MaterialTheme.typography.bodyMedium,
                color = dim,
            )
            Row(
                Modifier.weight(1f),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (focused) {
                    // Drawn icons rather than the \u25C0 \u25B6 characters: TV firmware fonts often have no
                    // glyph for those, and a row of tofu boxes would be the only explanation the
                    // viewer gets for a control nothing else on screen describes.
                    Icon(
                        Icons.Filled.KeyboardArrowLeft,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(18.dp),
                    )
                    Icon(
                        Icons.Filled.KeyboardArrowRight,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    // Named after the captions overhead, because "upper" and "lower end" is how
                    // the widget was built, not how it reads from the sofa.
                    Text(
                        "change ${if (editingUpper) "Up to" else "From"}   \u00B7   " +
                            "OK switches to ${if (editingUpper) "From" else "Up to"}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White,
                        textAlign = TextAlign.Center,
                    )
                }
            }
            Text(
                SizeFilter.label(SizeFilter.CEILING),
                style = MaterialTheme.typography.bodyMedium,
                color = dim,
            )
        }
    }
}

/** One end of the range. The active one is the only thing the D-pad will move. */
@Composable
private fun End(
    caption: String,
    value: String,
    active: Boolean,
    onSurface: Color,
    dim: Color,
    focused: Boolean,
) {
    Column {
        Text(caption, style = MaterialTheme.typography.bodyMedium, color = dim)
        Text(
            value,
            style = MaterialTheme.typography.titleLarge,
            color = if (active) onSurface else dim,
        )
        // A rule under the live end, because on a TV a colour difference alone is easy to miss.
        Box(
            Modifier
                .padding(top = 3.dp)
                .height(3.dp)
                .width(if (active) 56.dp else 0.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(if (focused) Color.White else Accent),
        )
    }
}

@Composable
private fun RangeTrack(minValue: Long, maxValue: Long, editingUpper: Boolean, focused: Boolean) {
    BoxWithConstraints(
        Modifier
            .fillMaxWidth()
            .height(18.dp)
            .clip(RoundedCornerShape(9.dp))
            .background(if (focused) Color.White.copy(alpha = 0.22f) else SurfaceRaised),
    ) {
        val width = maxWidth
        val start = width * SizeFilter.fraction(minValue)
        val span = (width * (SizeFilter.fraction(maxValue) - SizeFilter.fraction(minValue)))
            .coerceAtLeast(3.dp)

        Box(
            Modifier
                .padding(start = start)
                .width(span)
                .fillMaxHeight()
                .background(if (focused) Color.White.copy(alpha = 0.55f) else Accent.copy(alpha = 0.5f)),
        )

        Thumb(width, minValue, live = !editingUpper, focused = focused)
        Thumb(width, maxValue, live = editingUpper, focused = focused)
    }
}

@Composable
private fun BoxWithConstraintsScope.Thumb(width: Dp, value: Long, live: Boolean, focused: Boolean) {
    val w = if (live) 10.dp else 6.dp
    Box(
        Modifier
            .padding(start = (width * SizeFilter.fraction(value) - w / 2).coerceIn(0.dp, width - w))
            .width(w)
            .fillMaxHeight()
            .clip(RoundedCornerShape(5.dp))
            .background(
                when {
                    focused && live -> Color.White
                    focused -> Color.White.copy(alpha = 0.7f)
                    live -> Accent
                    else -> Accent.copy(alpha = 0.6f)
                },
            ),
    )
}

/** Right-aligned "Reset", greyed out when the range is already the default. */
@Composable
private fun ResetChip(enabled: Boolean, onClick: () -> Unit) {
    val interactions = remember { MutableInteractionSource() }
    val focused by interactions.collectIsFocusedAsState()
    val background by animateColorAsState(
        targetValue = if (focused) Accent else SurfaceRaised,
        animationSpec = tween(140),
        label = "resetChip",
    )
    Row(
        Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(if (enabled) background else SurfaceDark)
            // Always clickable, never `enabled = false`: pressing this chip is what greys it out,
            // and a disabled clickable installs no focus target, so the node the viewer was
            // standing on would vanish underneath them and the next press would go nowhere.
            // Greyed out it simply does nothing, and the caller moves focus off it.
            .clickable(
                interactionSource = interactions,
                indication = null,
                onClick = { if (enabled) onClick() },
            )
            .padding(horizontal = 18.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Filled.Refresh,
            contentDescription = null,
            tint = when {
                !enabled -> TextMuted
                focused -> Color.White
                else -> TextPrimary
            },
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            "Reset",
            style = MaterialTheme.typography.bodyLarge,
            color = when {
                !enabled -> TextMuted
                focused -> Color.White
                else -> TextPrimary
            },
            maxLines = 1,
        )
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
            "TMPlayer has ${StreamStats.formatBytes(cacheBytes)} of video saved.",
            style = MaterialTheme.typography.bodyLarge,
            color = TextPrimary,
        )
        Text(
            "One film is kept at a time; starting another replaces it.",
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
    icon: ImageVector,
    checked: Boolean,
    onToggle: () -> Unit,
) {
    FocusRow(Modifier, onToggle) { focused ->
        // Carries an icon for the same reason ActionRow does: the two kinds of row are stacked in
        // one column, and without it their titles would start at two different x positions.
        Icon(
            icon,
            contentDescription = null,
            tint = if (focused) Color.White else TextPrimary,
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
        Switch(checked = checked, focused = focused)
    }
}

/** A plain pill switch, big enough to read across a room, with no touch affordances implied. */
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
