package com.tmplayer.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.tmplayer.data.DiskInfo
import com.tmplayer.data.SettingsStore
import com.tmplayer.data.StorageWarning
import com.tmplayer.player.StreamStats
import com.tmplayer.ui.theme.SurfaceRaised
import com.tmplayer.ui.theme.TextMuted
import com.tmplayer.ui.theme.TextPrimary
import com.tmplayer.ui.theme.tmButtonColors
import kotlinx.coroutines.launch

/** Amber rather than red: something needs attention, but nothing has actually broken. */
private val Caution = Color(0xFFF5A524)

/**
 * An inline banner for something the viewer ought to know but does not have to act on now.
 *
 * Nothing here requests focus: stealing it would drag the remote off whatever row the viewer was
 * on. The Dismiss button is reachable by pressing up.
 *
 * Place it inside the overscan-safe area, e.g. `Modifier.padding(horizontal = Tv.SafeH)`, unless
 * the screen already insets its content.
 */
@Composable
fun WarningCard(
    title: String,
    message: String,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector = Icons.Filled.Warning,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    dismissLabel: String = "Dismiss",
) {
    val shape = RoundedCornerShape(14.dp)
    Row(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(SurfaceRaised)
            .border(1.dp, Caution.copy(alpha = 0.4f), shape)
            .padding(horizontal = 20.dp, vertical = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = Caution,
            modifier = Modifier.size(28.dp),
        )
        Column(
            // Takes the leftover width so a long message wraps instead of squeezing the buttons.
            Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = TextPrimary)
            // bodyLarge, not bodyMedium: 13sp is legible at a desk and not from a sofa.
            Text(message, style = MaterialTheme.typography.bodyLarge, color = TextMuted)
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (actionLabel != null && onAction != null) {
                Button(onClick = onAction, colors = tmButtonColors()) { Text(actionLabel) }
            }
            Button(onClick = onDismiss, colors = tmButtonColors()) { Text(dismissLabel) }
        }
    }
}

/**
 * The low-space warning, wired to its own persistence so a screen only has to place it.
 *
 * Renders nothing at all when there is room, when the disk could not be read, or when the viewer
 * has already dismissed this much free space. See [StorageWarning] for the rules.
 */
@Composable
fun LowSpaceWarning(
    disk: DiskInfo,
    settings: SettingsStore,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    // Null until DataStore has answered. Starting from "never dismissed" instead would flash the
    // card on screen for a frame or two before the stored dismissal arrived and took it away.
    val dismissedAt: Long? by produceState<Long?>(null, settings) {
        settings.lowSpaceDismissedAtFreeBytes.collect { value = it }
    }
    val scope = rememberCoroutineScope()
    val dismissed = dismissedAt ?: return

    LaunchedEffect(disk.freeBytes, disk.totalBytes, dismissed) {
        if (dismissed != StorageWarning.NEVER_DISMISSED &&
            StorageWarning.hasRecovered(disk.freeBytes, disk.totalBytes)
        ) {
            settings.clearLowSpaceDismissal()
        }
    }

    if (!StorageWarning.shouldShow(disk.freeBytes, disk.totalBytes, dismissed)) return

    WarningCard(
        title = "Running low on space",
        message = "Only ${StreamStats.formatBytes(disk.freeBytes)} is free on this device. " +
            "A film downloads in full before it plays, so there may not be room for the next one. " +
            "Removing apps or downloads you no longer use, in the TV's own settings, will free " +
            "some up.",
        onDismiss = { scope.launch { settings.dismissLowSpaceWarning(disk.freeBytes) } },
        modifier = modifier,
        actionLabel = actionLabel,
        onAction = onAction,
    )
}
